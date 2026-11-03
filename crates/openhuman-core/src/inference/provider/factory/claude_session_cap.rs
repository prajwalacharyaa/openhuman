//! Keeps each chat's Claude Code session small.
//!
//! The Claude Code provider resumes one CLI session per chat thread, and every model call in it re-reads the
//! whole session: on one long day a single chat grew to ~485k tokens per call and re-read ~143M tokens in total.
//! Claude Code compacts on its own only near its (very large) context window, so that never happens in practice.
//!
//! [`SessionCap`] wraps the provider and moves a chat to a fresh session once its last call read more than
//! [`MAX_CONTEXT_TOKENS`], or after [`IDLE_FRESH`] without use. It only changes the thread key the provider
//! sees (`<thread>~<n>`), also when the session's transcript was cleaned up: a new key is a new CLI session, and the provider seeds it with the chat's recent
//! messages ([`FRESH_HISTORY`] of them), so recent context carries over; old tool output and file reads don't.
//! Memory recall covers older parts of the chat.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, SystemTime};

use tinyinference_llm::{ChatModel, InputModality, InputSource, ModelRequest, ModelResponse, ModelStream};

/// A session whose last call read more than this many tokens starts fresh on the next turn.
pub(crate) const MAX_CONTEXT_TOKENS: u64 = 150_000;
/// A session unused this long starts fresh (the old one's details are stale anyway).
pub(crate) const IDLE_FRESH: Duration = Duration::from_secs(6 * 3600);
/// Chat messages a fresh session is opened with (the provider folds them into its first message). OpenHuman
/// sends the whole chat, which on a long day is itself tens of thousands of tokens; memory recall covers older.
pub(crate) const FRESH_HISTORY: usize = 20;
/// How much of a session transcript's end is read to find its last call's size.
const TAIL_BYTES: u64 = 1 << 20;

/// The Claude Code provider, with each chat's session kept small.
pub(crate) struct SessionCap {
    inner: Arc<dyn ChatModel<()>>,
    /// `claude-code-sessions.json` (thread key → CLI session id), written by the provider.
    sessions_file: PathBuf,
    /// thread → how many times it moved to a fresh session.
    fresh_file: PathBuf,
    /// Where the CLI keeps session transcripts (`~/.claude/projects`).
    transcripts: PathBuf,
    lock: Mutex<()>,
}

impl SessionCap {
    pub(crate) fn new(inner: Arc<dyn ChatModel<()>>, workspace: &Path) -> Self {
        let home = std::env::var_os("HOME").map(PathBuf::from).unwrap_or_default();
        Self::with_paths(inner, workspace, home.join(".claude").join("projects"))
    }

    pub(crate) fn with_paths(inner: Arc<dyn ChatModel<()>>, workspace: &Path, transcripts: PathBuf) -> Self {
        Self {
            inner,
            sessions_file: workspace.join("claude-code-sessions.json"),
            fresh_file: workspace.join("claude-session-fresh.json"),
            transcripts,
            lock: Mutex::new(()),
        }
    }

    /// The request with its thread key moved to a fresh session when the current one is too big or idle.
    pub(crate) fn rekey(&self, mut request: ModelRequest) -> ModelRequest {
        let Some(thread) = request.metadata.get("thread_id").and_then(|t| t.as_str()).map(str::to_string) else {
            return request;
        };
        let _guard = self.lock.lock().unwrap_or_else(PoisonError::into_inner);
        let mut fresh = read_json(&self.fresh_file);
        let generation = fresh.get(&thread).and_then(serde_json::Value::as_u64).unwrap_or(0);
        let key = session_key(&thread, generation);
        let session = read_json(&self.sessions_file)
            .get("sessions")
            .and_then(|s| s.get(&key))
            .and_then(serde_json::Value::as_str)
            .map(str::to_string);
        // no transcripts folder at all (another HOME): leave sessions as they are
        let stale = self.transcripts.is_dir()
            && session.as_deref().is_some_and(|id| self.transcript(id).is_none_or(|transcript| needs_fresh(&transcript)));
        // too big, idle, or its transcript was cleaned up (resuming it would fail)
        if session.is_none() || stale {
            keep_recent(&mut request.messages, FRESH_HISTORY);
        }
        let key = if stale {
            fresh[thread.as_str()] = serde_json::json!(generation + 1);
            if let Err(error) = write_json(&self.fresh_file, &fresh) {
                tracing::warn!(%error, "[claude-code] couldn't save the fresh-session count; keeping the session");
                key
            } else {
                tracing::info!(thread = %thread, "[claude-code] session too big, idle or cleaned up: starting a fresh one");
                session_key(&thread, generation + 1)
            }
        } else {
            key
        };
        if let Some(metadata) = request.metadata.as_object_mut() {
            metadata.insert("thread_id".into(), key.into());
        }
        request
    }

    fn transcript(&self, session: &str) -> Option<PathBuf> {
        let name = format!("{session}.jsonl");
        std::fs::read_dir(&self.transcripts)
            .ok()?
            .flatten()
            .map(|dir| dir.path().join(&name))
            .find(|path| path.is_file())
    }
}

/// Keeps the system messages and the last `keep` others: what a new session is opened with.
pub(crate) fn keep_recent(messages: &mut Vec<tinyinference_llm::Message>, keep: usize) {
    let others = messages.iter().filter(|m| !matches!(m, tinyinference_llm::Message::System(_))).count();
    let mut drop = others.saturating_sub(keep);
    messages.retain(|m| {
        if drop == 0 || matches!(m, tinyinference_llm::Message::System(_)) {
            return true;
        }
        drop -= 1;
        false
    });
}

/// The provider's thread key for a chat's `generation`-th session.
pub(crate) fn session_key(thread: &str, generation: u64) -> String {
    if generation == 0 { thread.to_string() } else { format!("{thread}~{generation}") }
}

/// Whether the session in `transcript` is idle too long or its last call read too much.
fn needs_fresh(transcript: &Path) -> bool {
    let idle = std::fs::metadata(transcript)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|at| SystemTime::now().duration_since(at).ok())
        .is_some_and(|idle| idle >= IDLE_FRESH);
    idle || last_context_tokens(transcript).is_some_and(|tokens| tokens > MAX_CONTEXT_TOKENS)
}

/// How many tokens the transcript's last model call read (input + cache reads + cache writes).
pub(crate) fn last_context_tokens(transcript: &Path) -> Option<u64> {
    use std::io::{Read, Seek, SeekFrom};
    let mut file = std::fs::File::open(transcript).ok()?;
    let len = file.metadata().ok()?.len();
    file.seek(SeekFrom::Start(len.saturating_sub(TAIL_BYTES))).ok()?;
    let mut tail = Vec::new();
    file.read_to_end(&mut tail).ok()?;
    String::from_utf8_lossy(&tail).lines().rev().find_map(|line| {
        let entry: serde_json::Value = serde_json::from_str(line).ok()?;
        let usage = entry.get("message")?.get("usage")?;
        let n = |k: &str| usage.get(k).and_then(serde_json::Value::as_u64).unwrap_or(0);
        Some(n("input_tokens") + n("cache_read_input_tokens") + n("cache_creation_input_tokens"))
    })
}

fn read_json(path: &Path) -> serde_json::Value {
    std::fs::read_to_string(path)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .filter(serde_json::Value::is_object)
        .unwrap_or_else(|| serde_json::json!({}))
}

fn write_json(path: &Path, value: &serde_json::Value) -> std::io::Result<()> {
    std::fs::write(path, serde_json::to_vec_pretty(value).map_err(std::io::Error::other)?)
}

#[async_trait::async_trait]
impl ChatModel<()> for SessionCap {
    fn profile(&self) -> Option<&tinyinference_llm::model::ModelProfile> {
        self.inner.profile()
    }

    fn supports_input(&self, modality: InputModality, mime_type: &str, source: InputSource) -> bool {
        self.inner.supports_input(modality, mime_type, source)
    }

    fn cache_identity(&self) -> Option<String> {
        self.inner.cache_identity()
    }

    async fn invoke(&self, state: &(), request: ModelRequest) -> tinyinference_llm::Result<ModelResponse> {
        self.inner.invoke(state, self.rekey(request)).await
    }

    async fn stream(&self, state: &(), request: ModelRequest) -> tinyinference_llm::Result<ModelStream> {
        self.inner.stream(state, self.rekey(request)).await
    }
}

#[cfg(test)]
#[path = "claude_session_cap_tests.rs"]
mod tests;
