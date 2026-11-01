//! Saves a live session's final transcripts into the open chat thread.
//!
//! Each final user or agent utterance becomes one thread message with a
//! deterministic id (`voice-<session>-<n>-<role>`), so a retried write is a
//! no-op and the UI can refresh the thread to show it. Partial transcripts and
//! tool traffic are not stored.
//!
//! With [`TranscriptPersister::with_memory_log`] the same utterances are also
//! logged into memory, like a typed turn: each user utterance opens a turn and
//! the agent's speech until the next one is its reply. Logging runs on a
//! background worker in conversation order, so it never delays the live audio.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use serde_json::json;
use tokio::sync::mpsc;

use super::types::TranscriptRole;
use crate::config::Config;
use crate::memory::lifecycle::hooks::{self, PostTurnInput, PreTurnInput};
use crate::threads::store::ConversationMessage;

/// How long the end of a session waits for queued memory logging.
const MEMORY_FLUSH_TIMEOUT: Duration = Duration::from_secs(10);

/// One memory write, in the order the conversation happened.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum MemoryEntry {
    /// A user utterance, logged as a user turn.
    User { index: u32, text: String },
    /// The agent's speech after it, logged as that turn's reply.
    Reply { index: u32, text: String },
}

/// Pairs final utterances into memory turns. A user utterance opens a turn;
/// the agent utterances after it are its reply, joined and released when the
/// next user utterance arrives or the session ends. Agent speech before any
/// user utterance (a greeting) is not logged.
#[derive(Debug, Default)]
pub(crate) struct VoiceTurns {
    exchanges: usize,
    open: bool,
    reply: Vec<String>,
}

impl VoiceTurns {
    /// The memory entries a final utterance releases.
    pub(crate) fn push(&mut self, role: TranscriptRole, text: &str) -> Vec<MemoryEntry> {
        let text = text.trim();
        if text.is_empty() {
            return Vec::new();
        }
        match role {
            TranscriptRole::User => {
                let mut entries = self.flush();
                self.open = true;
                entries.push(MemoryEntry::User {
                    index: hooks::user_turn_index(self.exchanges),
                    text: text.to_string(),
                });
                entries
            }
            TranscriptRole::Agent => {
                if self.open {
                    self.reply.push(text.to_string());
                }
                Vec::new()
            }
        }
    }

    /// Closes the open turn: its reply, if the agent said anything.
    pub(crate) fn flush(&mut self) -> Vec<MemoryEntry> {
        if !self.open {
            return Vec::new();
        }
        let index = hooks::user_turn_index(self.exchanges) + 1;
        self.open = false;
        self.exchanges += 1;
        let reply = std::mem::take(&mut self.reply).join(" ");
        if reply.is_empty() {
            Vec::new()
        } else {
            vec![MemoryEntry::Reply { index, text: reply }]
        }
    }
}

/// Logs a live session's turns into memory on a background worker.
#[derive(Debug)]
pub(crate) struct VoiceMemoryLog {
    turns: VoiceTurns,
    sender: mpsc::UnboundedSender<MemoryEntry>,
    worker: tokio::task::JoinHandle<()>,
}

impl VoiceMemoryLog {
    /// Starts the worker; `None` when conversation logging is off. Turns go
    /// under their own memory thread (`voice-<session>`), so their indices
    /// never collide with typed turns of the chat thread the session shares.
    pub(crate) fn start(config: Arc<Config>, session_id: &str) -> Option<Self> {
        if !config.memory.conversations.enabled {
            return None;
        }
        let mut identity = crate::memory::scope::resolve_current(&config);
        identity.recall = false; // log only: the session got its pack at start
        let thread_id = format!("voice-{session_id}");
        let (sender, mut receiver) = mpsc::unbounded_channel::<MemoryEntry>();
        let worker = tokio::spawn(async move {
            while let Some(entry) = receiver.recv().await {
                match entry {
                    MemoryEntry::User { index, text } => {
                        let input = PreTurnInput {
                            thread_id: thread_id.clone(),
                            turn_index: index,
                            user_text: text,
                            in_prompt_from: index,
                            at: chrono::Utc::now(),
                            resumed_after_compaction: false,
                            observed_actor: None,
                        };
                        let _ = hooks::pre_turn(&config, &identity, input).await;
                    }
                    MemoryEntry::Reply { index, text } => {
                        let input = PostTurnInput {
                            thread_id: thread_id.clone(),
                            turn_index: index,
                            assistant_text: text,
                            tool_calls: Vec::new(),
                            at: chrono::Utc::now(),
                        };
                        hooks::post_turn(&config, &identity, input).await;
                    }
                }
            }
        });
        Some(Self {
            turns: VoiceTurns::default(),
            sender,
            worker,
        })
    }

    fn record(&mut self, role: TranscriptRole, text: &str) {
        for entry in self.turns.push(role, text) {
            let _ = self.sender.send(entry);
        }
    }

    /// Logs the last reply and waits (bounded) for the queue to drain.
    async fn finish(mut self) {
        for entry in self.turns.flush() {
            let _ = self.sender.send(entry);
        }
        drop(self.sender);
        if tokio::time::timeout(MEMORY_FLUSH_TIMEOUT, self.worker).await.is_err() {
            tracing::warn!("[voice-live] memory logging still running at session end; left to finish");
        }
    }
}

/// Writes final transcripts for one session.
#[derive(Debug)]
pub(crate) struct TranscriptPersister {
    workspace_dir: PathBuf,
    thread_id: String,
    session_id: String,
    provider: String,
    sequence: u64,
    memory: Option<VoiceMemoryLog>,
}

impl TranscriptPersister {
    pub(crate) fn new(
        workspace_dir: PathBuf,
        thread_id: String,
        session_id: String,
        provider: String,
    ) -> Self {
        Self {
            workspace_dir,
            thread_id,
            session_id,
            provider,
            sequence: 0,
            memory: None,
        }
    }

    /// Also logs the session's turns into memory (when conversation logging
    /// is on).
    pub(crate) fn with_memory_log(mut self, config: Arc<Config>) -> Self {
        self.memory = VoiceMemoryLog::start(config, &self.session_id);
        self
    }

    /// Ends the session: logs the last reply into memory.
    pub(crate) async fn finish(self) {
        if let Some(memory) = self.memory {
            memory.finish().await;
        }
    }

    /// The message a final utterance becomes, or `None` for blank text.
    pub(crate) fn message(
        &mut self,
        role: TranscriptRole,
        text: &str,
    ) -> Option<ConversationMessage> {
        let content = text.trim();
        if content.is_empty() {
            return None;
        }
        self.sequence += 1;
        let (sender, suffix) = match role {
            TranscriptRole::User => ("user", "user"),
            TranscriptRole::Agent => ("agent", "agent"),
        };
        Some(ConversationMessage {
            id: format!("voice-{}-{}-{suffix}", self.session_id, self.sequence),
            content: content.to_string(),
            message_type: "text".to_string(),
            extra_metadata: json!({
                "source": "voice",
                "provider": self.provider,
                "voiceSessionId": self.session_id,
            }),
            sender: sender.to_string(),
            created_at: chrono::Utc::now().to_rfc3339(),
        })
    }

    /// Appends a final utterance to the thread. Failures are logged, never
    /// fatal: losing a transcript row must not end a live conversation.
    pub(crate) async fn save(&mut self, role: TranscriptRole, text: &str) {
        if let Some(memory) = self.memory.as_mut() {
            memory.record(role, text);
        }
        let Some(message) = self.message(role, text) else {
            return;
        };
        let id = message.id.clone();
        match crate::threads::store::blocking::append_message(
            self.workspace_dir.clone(),
            self.thread_id.clone(),
            message,
        )
        .await
        {
            Ok(_) => tracing::debug!(message_id = %id, "[voice-live] transcript saved to thread"),
            Err(error) => tracing::warn!(
                message_id = %id,
                "[voice-live] could not save transcript to thread: {error}"
            ),
        }
    }
}

#[cfg(test)]
#[path = "persist_tests.rs"]
mod tests;
