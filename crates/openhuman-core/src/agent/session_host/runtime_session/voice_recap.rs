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
const MAX_MESSAGES: usize = 8;
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

/// Most unanswered typed messages carried into one turn.
const MAX_UNANSWERED: usize = 4;
/// Characters kept per unanswered typed message.
const MAX_UNANSWERED_CHARS: usize = 1500;

/// Typed messages December sent since the last reply that no turn answered
/// (the turn was stopped or interrupted, failed, or the engine restarted),
/// oldest first, without the message this turn is for (`current`; the client
/// has usually appended it to the thread already). A stopped turn's message
/// never reaches the session transcript, so the next turn would not know it:
/// on 2026-11-13 "can you check that" arrived without the request it was
/// about, and the reply was "your message cuts off".
pub(super) fn unanswered_typed<'a>(messages: &'a [ConversationMessage], current: &str) -> Vec<&'a ConversationMessage> {
    let mut start = messages.len();
    while start > 0 && !is_voice(&messages[start - 1]) && messages[start - 1].sender == "user" {
        start -= 1;
    }
    let mut run: Vec<&ConversationMessage> =
        messages[start..].iter().filter(|m| !m.content.trim().is_empty()).collect();
    let current = current.trim();
    let is_current = |m: &ConversationMessage| {
        let text = m.content.trim();
        current.is_empty() || current.contains(text) || text.contains(current)
    };
    if run.last().is_some_and(|m| is_current(*m)) {
        run.pop();
    }
    run[run.len().saturating_sub(MAX_UNANSWERED)..].to_vec()
}

/// The context block for [`unanswered_typed`], or `None` when every earlier
/// typed message was answered.
pub(super) fn unanswered_block(messages: &[ConversationMessage], current: &str) -> Option<String> {
    let earlier = unanswered_typed(messages, current);
    if earlier.is_empty() {
        return None;
    }
    let lines: Vec<String> = earlier
        .iter()
        .map(|m| {
            let text: String = m.content.trim().chars().take(MAX_UNANSWERED_CHARS).collect();
            format!("User: {text}")
        })
        .collect();
    Some(format!(
        "[December wrote this just before in this conversation and it got no answer (that turn was stopped \
         or did not finish). It is part of what they are asking now.]\n{}\n\n",
        lines.join("\n")
    ))
}

/// Reads the thread and builds [`recap`] and [`unanswered_block`] (`current`
/// is this turn's own message); `None` without a thread, when the thread
/// can't be read (the turn runs either way), or when there is neither.
pub(super) async fn recap_block(workspace_dir: &Path, thread_id: Option<&str>, current: &str) -> Option<String> {
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
    let block = [recap(&messages), unanswered_block(&messages, current)].into_iter().flatten().collect::<String>();
    (!block.is_empty()).then_some(block)
}

#[cfg(test)]
#[path = "voice_recap_tests.rs"]
mod tests;
