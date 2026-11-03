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
//! [`LookupCheck`] does the same for searches: a question about something
//! current (news, prices, weather, "the latest") answered with no lookup came
//! from the model's own knowledge, which is about a year old, so it is told to
//! search and answer again.
//!
//! It also brings Claude's answer back: when the typed-chat turn `mochi-ask`
//! started for this call's thread finishes, [`claude_reply_note`] is the note
//! that makes the live model say the answer without being asked.

use std::time::{Duration, Instant};

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

/// Holds server notes (Claude's answers) while the model is speaking: a note sent mid-sentence is taken as new
/// input, so the model cuts itself off and starts its answer again. Notes go out when its turn is over.
#[derive(Debug, Default)]
pub(crate) struct NoteGate {
    speaking: bool,
    waiting: Vec<String>,
}

impl NoteGate {
    /// The model's audio is playing.
    pub(crate) fn agent_audio(&mut self) {
        self.speaking = true;
    }

    /// A note to pass to the model: now, or `None` while it's speaking (then on [`Self::turn_over`]).
    pub(crate) fn offer(&mut self, note: String) -> Option<String> {
        if self.speaking {
            self.waiting.push(note);
            None
        } else {
            Some(note)
        }
    }

    /// The model finished (or was interrupted): the notes that waited, as one.
    pub(crate) fn turn_over(&mut self) -> Option<String> {
        self.speaking = false;
        (!self.waiting.is_empty()).then(|| std::mem::take(&mut self.waiting).join("\n\n"))
    }
}

/// What the handoff and lookup checks need to know about a session event.
#[derive(Clone, Copy)]
pub(crate) enum HandoffEvent<'a> {
    /// A final transcript of (part of) the user's speech: a new turn starts.
    UserSaid(&'a str),
    /// A final transcript of the model's speech.
    AgentSaid(&'a str),
    ToolStarted(&'a str),
    TurnComplete,
}

/// Whether the model's words claim Claude is (or will be) doing the work.
/// A question ("Shall I ask Claude?") is not a claim, and neither is a status update about work handed off
/// earlier ("Claude is still working on it").
pub(crate) fn claims_handoff(text: &str) -> bool {
    let text = text.trim().to_lowercase();
    if text.ends_with('?') || text.contains("still") || !(text.contains("claude") || text.contains("क्लाउड")) {
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
    /// When a handoff tool last started. Claims soon after it are about that handoff. Kept across the user's
    /// next words on purpose: Gemini often finalises the transcript of what December said only after it has
    /// already started the tool, so "December spoke" can arrive after the handoff it led to.
    last_handoff: Option<Instant>,
    /// Already nudged since December last spoke: the model's answer to a nudge must not draw another one.
    nudged: bool,
}

/// How long after a handoff the model may talk about it without a new one.
const HANDOFF_COVERS: Duration = Duration::from_secs(90);

impl HandoffCheck {
    /// Returns [`NUDGE`] when a turn ends with a claimed but missing handoff.
    pub(crate) fn on_event(&mut self, event: HandoffEvent<'_>, now: Instant) -> Option<&'static str> {
        match event {
            HandoffEvent::UserSaid(_) => {
                self.claimed = false;
                self.nudged = false;
            }
            HandoffEvent::AgentSaid(text) => self.claimed |= claims_handoff(text),
            HandoffEvent::ToolStarted(name) => {
                if HANDOFF_TOOLS.contains(&name) {
                    self.last_handoff = Some(now);
                }
            }
            HandoffEvent::TurnComplete => {
                let covered = self.last_handoff.is_some_and(|at| now.duration_since(at) < HANDOFF_COVERS);
                let nudge = self.claimed && !covered && !self.nudged;
                self.claimed = false;
                if nudge {
                    self.nudged = true;
                    return Some(NUDGE);
                }
            }
        }
        None
    }
}

/// Sent to the live model when it answered a question about something current without looking it up.
pub(crate) const LOOKUP_NUDGE: &str = "(Server check, not December speaking: December asked about something \
current, or you said you were checking, but you answered without searching. Your own knowledge is about a year \
old, so that answer may be out of date. Search now with your web search tool and give December the up-to-date \
answer briefly. If the search fails or this needs deeper research, hand it to Claude with mochi-ask instead.)";

/// Words in December's request that only a fresh lookup can answer.
const FRESH_WORDS: &[&str] = &[
    "news", "headline", "latest", "price", "score", "weather", "nepse", "share market", "stock market",
    "समाचार", "न्युज", "खबर", "ताजा", "मौसम", "सेयर",
];
/// ...unless December asks about something already said ("yesterday's news from our last chat").
const PAST_WORDS: &[&str] = &["yesterday", "previous", "earlier", "last time", "हिजो", "अघिल्लो", "पहिले"];
/// How the model announces a lookup it then has to make.
const LOOKUP_SAID: &[&str] = &["checking", "let me check", "searching", "looking it up", "let me look", "हेर्दै", "खोज्दै"];

/// Whether December's words ask for something current.
pub(crate) fn asks_fresh(text: &str) -> bool {
    let text = text.to_lowercase();
    FRESH_WORDS.iter().any(|w| text.contains(w)) && !PAST_WORDS.iter().any(|w| text.contains(w))
}

/// Whether the model's words say it is looking something up.
pub(crate) fn announces_lookup(text: &str) -> bool {
    let text = text.to_lowercase();
    LOOKUP_SAID.iter().any(|w| text.contains(w))
}

/// A tool started this soon before December's words were finalised was for them (Gemini often finalises the
/// transcript only after it has started the tool).
const LOOKUP_GRACE: Duration = Duration::from_secs(15);

/// Per-session lookup state: catches answers about current things given without any tool call.
#[derive(Debug, Default)]
pub(crate) struct LookupCheck {
    /// This turn needs a lookup: December asked for something current, or the model said it was checking.
    needed: bool,
    /// December is speaking (the next final transcripts belong to the same request).
    user_turn: bool,
    asked_at: Option<Instant>,
    last_tool: Option<Instant>,
    /// Already nudged since December last spoke.
    nudged: bool,
}

impl LookupCheck {
    /// Returns [`LOOKUP_NUDGE`] when a turn that needed a lookup ends without any tool call.
    pub(crate) fn on_event(&mut self, event: HandoffEvent<'_>, now: Instant) -> Option<&'static str> {
        match event {
            HandoffEvent::UserSaid(text) => {
                if !self.user_turn {
                    self.user_turn = true;
                    self.needed = false;
                    self.nudged = false;
                    self.asked_at = Some(now);
                }
                self.needed |= asks_fresh(text);
            }
            HandoffEvent::AgentSaid(text) => {
                self.user_turn = false;
                self.needed |= announces_lookup(text);
            }
            HandoffEvent::ToolStarted(_) => self.last_tool = Some(now),
            HandoffEvent::TurnComplete => {
                self.user_turn = false;
                let looked = match (self.last_tool, self.asked_at) {
                    (Some(tool), Some(asked)) => tool + LOOKUP_GRACE >= asked,
                    (tool, None) => tool.is_some(),
                    (None, _) => false,
                };
                let nudge = self.needed && !looked && !self.nudged;
                self.needed = false;
                if nudge {
                    self.nudged = true;
                    return Some(LOOKUP_NUDGE);
                }
            }
        }
        None
    }
}

#[cfg(test)]
#[path = "handoff_tests.rs"]
mod tests;
