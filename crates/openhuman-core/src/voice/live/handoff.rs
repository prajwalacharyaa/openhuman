//! Catches the live model saying it handed work to Claude without doing it.
//!
//! Voice hands work it can't do (GitHub projects, code, server files) to
//! Claude by running `mochi-ask` with its shell tool (the server's command
//! that posts the request into the typed chat). A realtime model sometimes only *says*
//! "Claude is on it" and never makes the call, so the user waits for an answer
//! that will never come. This tracks each turn: when the model's words claim a
//! handoff and the turn completes with no handoff call, it returns a short
//! correction for the caller to send to the model, once per turn.
//!
//! It also brings Claude's answer back: when the typed-chat turn `mochi-ask`
//! started for this call's thread finishes, [`claude_reply_note`] is the note
//! that makes the live model say the answer without being asked.

use crate::web_chat::WebChannelEvent;

/// Tools a handoff runs through. The event carries no arguments, so any shell
/// call in a turn that claims a handoff counts as the `mochi-ask` call.
const HANDOFF_TOOLS: &[&str] = &["shell", "spawn_async_subagent"];

/// Sent to the live model when it claimed a handoff it didn't make.
pub(crate) const NUDGE: &str = "(Server check, not December speaking: you said Claude is handling this, \
but you did not run mochi-ask, so nothing was sent to Claude. Run mochi-ask now with your shell tool, \
with December's full request and the context it needs, then confirm in one short sentence. If you already \
handed this same request to Claude earlier in this call, don't call it again; just say Claude is still working.)";

/// The web-chat `client_id` `mochi-ask` (tiny-setup) sends its requests as.
const ASK_CLIENT: &str = "mochi-ask";

/// Most of Claude's answer passed into the call; the rest stays in the chat.
const MAX_ANSWER_CHARS: usize = 3000;

/// The note that makes the live model tell December how a `mochi-ask`
/// request for this thread ended; `None` for every other chat event.
pub(crate) fn claude_reply_note(event: &WebChannelEvent, thread_id: &str) -> Option<String> {
    if event.client_id != ASK_CLIENT || event.thread_id != thread_id {
        return None;
    }
    match event.event.as_str() {
        "chat_done" => {
            let answer = event.full_response.as_deref().unwrap_or_default().trim();
            if answer.is_empty() {
                return None;
            }
            let answer: String = answer.chars().take(MAX_ANSWER_CHARS).collect();
            Some(format!(
                "(Server note, not December speaking: Claude finished the request you handed it. \
                 Tell December the answer now, briefly and in spoken style, without waiting to be asked. \
                 The full answer is in the chat.)\n\n{answer}"
            ))
        }
        "chat_error" => Some(format!(
            "(Server note, not December speaking: Claude couldn't finish the request you handed it{}. \
             Tell December briefly.)",
            event.message.as_deref().map(|m| format!(": {m}")).unwrap_or_default()
        )),
        _ => None,
    }
}

/// What the handoff check needs to know about a session event.
pub(crate) enum HandoffEvent<'a> {
    /// A final transcript of the user's speech: a new turn starts.
    UserSaid,
    /// A final transcript of the model's speech.
    AgentSaid(&'a str),
    ToolStarted(&'a str),
    TurnComplete,
}

/// Whether the model's words claim Claude is (or will be) doing the work.
/// A question ("Shall I ask Claude?") is not a claim.
pub(crate) fn claims_handoff(text: &str) -> bool {
    let text = text.trim().to_lowercase();
    if text.ends_with('?') || !(text.contains("claude") || text.contains("क्लाउड")) {
        return false;
    }
    const MARKERS: &[&str] = &[
        "on it", "will ", "'ll ", "is checking", "is looking", "is handling", "is working",
        "asked", "handed", "passed", "sent", "get back", "let me ask",
    ];
    MARKERS.iter().any(|marker| text.contains(marker))
}

/// Per-session handoff state.
#[derive(Debug, Default)]
pub(crate) struct HandoffCheck {
    claimed: bool,
    handed_off: bool,
}

impl HandoffCheck {
    /// Returns [`NUDGE`] when a turn ends with a claimed but missing handoff.
    pub(crate) fn on_event(&mut self, event: HandoffEvent<'_>) -> Option<&'static str> {
        match event {
            HandoffEvent::UserSaid => *self = Self::default(),
            HandoffEvent::AgentSaid(text) => self.claimed |= claims_handoff(text),
            HandoffEvent::ToolStarted(name) => self.handed_off |= HANDOFF_TOOLS.contains(&name),
            HandoffEvent::TurnComplete if self.claimed && !self.handed_off => {
                self.claimed = false;
                return Some(NUDGE);
            }
            HandoffEvent::TurnComplete => {}
        }
        None
    }
}

#[cfg(test)]
#[path = "handoff_tests.rs"]
mod tests;
