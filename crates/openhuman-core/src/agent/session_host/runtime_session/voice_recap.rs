//! What was said by voice since the last typed exchange, for a typed turn.
//!
//! A live voice session saves its final utterances into the chat thread
//! (`voice::live::persist`), but a typed turn's history is the session
//! transcript, which never sees them. So when December switches from voice
//! back to typing in the same window, the typed turn would not know what was
//! just said. This module reads the thread's trailing voice messages and
//! hands them to `enrich_request` as a context block; that block becomes part
//! of the turn's committed input, so later turns keep it too, and it is added
//! after the turn's memory log, so memory doesn't store the voice twice.

use std::path::Path;

use crate::threads::store::ConversationMessage;

/// Most voice messages carried into one typed turn.
const MAX_MESSAGES: usize = 24;
/// Characters kept per voice message.
const MAX_CHARS: usize = 600;

/// Whether a thread message was saved by a live voice session.
fn is_voice(message: &ConversationMessage) -> bool {
    message.extra_metadata.get("source").and_then(|s| s.as_str()) == Some("voice")
}

/// The voice messages since the last typed exchange: skip the typed user
/// message(s) the client already appended for this turn, then take the run
/// of voice messages right before them.
pub(super) fn voice_since_last_typed(messages: &[ConversationMessage]) -> Vec<&ConversationMessage> {
    let mut end = messages.len();
    while end > 0 && !is_voice(&messages[end - 1]) && messages[end - 1].sender == "user" {
        end -= 1;
    }
    let mut start = end;
    while start > 0 && is_voice(&messages[start - 1]) {
        start -= 1;
    }
    let run: Vec<_> = messages[start..end]
        .iter()
        .filter(|m| !m.content.trim().is_empty())
        .collect();
    run[run.len().saturating_sub(MAX_MESSAGES)..].to_vec()
}

/// The context block for `messages`, or `None` when nothing was said by
/// voice since the last typed exchange.
pub(super) fn recap(messages: &[ConversationMessage]) -> Option<String> {
    let voice = voice_since_last_typed(messages);
    if voice.is_empty() {
        return None;
    }
    let lines: Vec<String> = voice
        .iter()
        .map(|m| {
            let who = if m.sender == "user" { "User" } else { "You" };
            let text: String = m.content.trim().chars().take(MAX_CHARS).collect();
            format!("{who}: {text}")
        })
        .collect();
    Some(format!(
        "[Said by voice in this conversation since the last typed message (\"You\" is what you said aloud). \
         Continue from it.]\n{}\n\n",
        lines.join("\n")
    ))
}

/// Reads the thread and builds [`recap`]; `None` without a thread or when
/// the thread can't be read (the turn runs either way).
pub(super) async fn recap_block(workspace_dir: &Path, thread_id: Option<&str>) -> Option<String> {
    let thread_id = thread_id?;
    let messages = crate::threads::store::blocking::get_messages(
        workspace_dir.to_path_buf(),
        thread_id.to_string(),
    )
    .await
    .map_err(|error| {
        tracing::debug!(thread_id, "[session_host:voice_recap] thread unreadable: {error}");
    })
    .ok()?;
    recap(&messages)
}

#[cfg(test)]
#[path = "voice_recap_tests.rs"]
mod tests;
