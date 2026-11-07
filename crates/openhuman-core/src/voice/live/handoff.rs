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
//! [`LookupCheck`] does the same for turns that needed a tool and ran none: a
//! question about something current (news, prices, weather, "the latest")
//! answered from the model's own knowledge, which is about a year old; "I've
//! installed it" when nothing was run; and giving in to a pushback ("you're
//! right, I stand corrected") without checking.
//!
//! It also brings Claude's answer back: when the typed-chat turn `mochi-ask`
//! started for this call's thread finishes, [`claude_reply_note`] is the note
//! that makes the live model say the answer without being asked. The same goes
//! for what December types into this chat during the call (a pasted CV, say):
//! the live model never sees typed messages, so it's told one arrived (not its
//! text, which may hold personal details) and then hears Claude's answer.

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

/// Sent when December starts a typed turn in this call's chat.
pub(crate) const TYPED_NOTE: &str = "(Server note, not December speaking: December just typed or pasted a \
message into this chat, and Claude is working on it. You can't see its text and don't need to. Say in a few \
words that you got it and Claude is on it; never ask December to say or paste it again. Claude's answer will \
come to you.)";

/// The note that makes the live model tell December how a `mochi-ask`
/// request for this thread ended, or that December typed into this chat (and
/// what Claude answered); `None` for every other chat event.
pub(crate) fn claude_reply_note(event: &WebChannelEvent, thread_id: &str) -> Option<String> {
    if event.thread_id != thread_id {
        return None;
    }
    if event.client_id != ASK_CLIENT {
        return typed_turn_note(event);
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
                 Tell December now, without waiting to be asked, in one short spoken sentence (two at most): \
                 the result only. If Claude asks December to decide something, name each choice in a few \
                 words and ask; never drop a choice or a warning. Don't read out details: the full answer is \
                 in the chat.)\n\n{answer}"
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

/// A turn December typed into this call's chat (any client but `mochi-ask`).
fn typed_turn_note(event: &WebChannelEvent) -> Option<String> {
    match event.event.as_str() {
        "inference_start" => Some(TYPED_NOTE.to_string()),
        "chat_done" => {
            let answer = event.full_response.as_deref().unwrap_or_default().trim();
            if answer.is_empty() {
                return None;
            }
            let answer: String = answer.chars().take(MAX_ANSWER_CHARS).collect();
            Some(format!(
                "(Server note, not December speaking: Claude answered what December typed into the chat. \
                 Tell December the gist now, briefly and in spoken style. The full answer is in the chat.)\n\n{answer}"
            ))
        }
        "chat_error" => Some(
            "(Server note, not December speaking: Claude couldn't finish what December typed into the chat. \
             Tell December briefly.)"
                .to_string(),
        ),
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

/// Words of a status update about work handed off earlier ("Claude is still working on it", "I'll update you
/// the moment Claude finishes"): not a new handoff.
const STATUS_UPDATE: &[&str] = &["still", "keeping an eye", "update you", "let you know", "finish", "is done", "ready"];

/// Whether the model's words claim Claude is (or will be) doing the work.
/// A question ("Shall I ask Claude?") is not a claim, and neither is a status update.
pub(crate) fn claims_handoff(text: &str) -> bool {
    let text = text.trim().to_lowercase();
    if text.ends_with('?')
        || STATUS_UPDATE.iter().any(|w| text.contains(w))
        || !(text.contains("claude") || text.contains("क्लाउड"))
    {
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

/// Sent to the live model when December asked how Claude's work is going and it answered without checking.
pub(crate) const STATUS_NUDGE: &str = "(Server check, not December speaking: December asked how Claude's work \
is going and you answered without checking. Run mochi-ask --result now and say what it shows in one short \
sentence, like \"Claude's still on it\" or \"Claude's done, pull request 13 is up\". Send nothing new to Claude.)";

/// Sent to the live model when it said it did something (installed, added, pushed…) with no tool call.
pub(crate) const DONE_NUDGE: &str = "(Server check, not December speaking: you said you did something, but you \
ran no command in this turn, so nothing was done. If Claude did it earlier, say so (\"Claude did that earlier\"). \
Otherwise tell December plainly it isn't done yet and hand it to Claude with mochi-ask; you can't install, \
change or switch on anything yourself.)";

/// Sent to the live model when it gave in to December's pushback without checking.
pub(crate) const AGREE_NUDGE: &str = "(Server check, not December speaking: you changed your answer because \
December pushed back, without checking anything. Check now: search with your web search tool, or hand it to \
Claude with mochi-ask if it's about December's own server, projects or code. If your first answer stands, say \
so politely; agree only with what the check shows.)";

/// Words in December's request that only a fresh lookup can answer.
const FRESH_WORDS: &[&str] = &[
    "news", "headline", "latest", "price", "score", "weather", "nepse", "share market", "stock market",
    "check if", "verify", "is it true", "is that true", "are you sure", "genuine",
    "समाचार", "न्युज", "खबर", "ताजा", "मौसम", "सेयर",
];
/// How December asks whether Claude is still working or done.
const STATUS_ASKED: &[&str] = &[
    "is claude", "claude is", "has claude", "if claude", "claude still", "claude done", "claude finished",
    "claude doing", "claude's doing", "क्लाउडले",
];
/// ...unless December asks about something already said ("yesterday's news from our last chat").
const PAST_WORDS: &[&str] = &["yesterday", "previous", "earlier", "last time", "हिजो", "अघिल्लो", "पहिले"];
/// How the model announces a lookup it then has to make.
const LOOKUP_SAID: &[&str] = &["checking", "let me check", "searching", "looking it up", "let me look", "हेर्दै", "खोज्दै"];
/// Things only a command can do; "I've installed…" with no tool call is made up.
const DONE_VERBS: &[&str] = &[
    "installed", "added", "cloned", "activated", "enabled", "pushed", "set up", "created", "updated", "saved",
    "deleted", "removed", "opened a pull request", "opened a pr", "merged", "configured",
];
/// How the model gives in to a pushback.
const AGREE_SAID: &[&str] =
    &["you're absolutely right", "you are absolutely right", "i stand corrected", "my mistake", "i was wrong"];

/// Whether December's words ask for something current.
pub(crate) fn asks_fresh(text: &str) -> bool {
    let text = text.to_lowercase();
    FRESH_WORDS.iter().any(|w| text.contains(w)) && !PAST_WORDS.iter().any(|w| text.contains(w))
}

/// Whether December asks how Claude's work is going ("Is Claude still editing?").
pub(crate) fn asks_status(text: &str) -> bool {
    let text = text.to_lowercase().replace('\u{2019}', "'");
    STATUS_ASKED.iter().any(|w| text.contains(w))
}

/// Whether the model's words say it is looking something up.
pub(crate) fn announces_lookup(text: &str) -> bool {
    let text = text.to_lowercase();
    LOOKUP_SAID.iter().any(|w| text.contains(w))
}

/// Whether the model's words say it did something itself ("I've installed it"). Reporting Claude's work
/// ("Claude has installed…") and questions are not claims.
pub(crate) fn claims_done(text: &str) -> bool {
    let text = text.trim().to_lowercase().replace('\u{2019}', "'");
    if text.ends_with('?') || text.contains("claude") {
        return false;
    }
    DONE_VERBS.iter().any(|verb| {
        ["i've ", "i have ", "i've successfully ", "i have successfully ", "i've now ", "i have now "]
            .iter()
            .any(|me| text.contains(&format!("{me}{verb}")))
    })
}

/// Whether the model's words give in to a pushback.
pub(crate) fn concedes(text: &str) -> bool {
    let text = text.to_lowercase().replace('\u{2019}', "'");
    AGREE_SAID.iter().any(|w| text.contains(w))
}

/// A tool started this soon before December's words were finalised was for them (Gemini often finalises the
/// transcript only after it has started the tool).
const LOOKUP_GRACE: Duration = Duration::from_secs(15);

/// Per-session state: catches turns that needed a tool call and made none.
#[derive(Debug, Default)]
pub(crate) struct LookupCheck {
    /// The nudge this turn needs if it ends without a tool call (the most serious reason wins).
    needed: Option<&'static str>,
    /// December is speaking (the next final transcripts belong to the same request).
    user_turn: bool,
    asked_at: Option<Instant>,
    last_tool: Option<Instant>,
    /// Already nudged since December last spoke.
    nudged: bool,
}

impl LookupCheck {
    fn need(&mut self, nudge: &'static str) {
        let rank = |n: &str| [DONE_NUDGE, AGREE_NUDGE, STATUS_NUDGE, LOOKUP_NUDGE].iter().position(|x| *x == n);
        if self.needed.is_none_or(|current| rank(nudge) < rank(current)) {
            self.needed = Some(nudge);
        }
    }

    /// Returns the nudge when a turn that needed a tool call ends without any.
    pub(crate) fn on_event(&mut self, event: HandoffEvent<'_>, now: Instant) -> Option<&'static str> {
        match event {
            HandoffEvent::UserSaid(text) => {
                if !self.user_turn {
                    self.user_turn = true;
                    self.needed = None;
                    self.nudged = false;
                    self.asked_at = Some(now);
                }
                if asks_status(text) {
                    // checked with mochi-ask --result, not a web search
                    self.need(STATUS_NUDGE);
                } else if asks_fresh(text) {
                    self.need(LOOKUP_NUDGE);
                }
            }
            HandoffEvent::AgentSaid(text) => {
                self.user_turn = false;
                if claims_done(text) {
                    self.need(DONE_NUDGE);
                }
                if concedes(text) {
                    self.need(AGREE_NUDGE);
                }
                if announces_lookup(text) {
                    self.need(LOOKUP_NUDGE);
                }
            }
            HandoffEvent::ToolStarted(_) => self.last_tool = Some(now),
            HandoffEvent::TurnComplete => {
                self.user_turn = false;
                let looked = match (self.last_tool, self.asked_at) {
                    (Some(tool), Some(asked)) => tool + LOOKUP_GRACE >= asked,
                    (tool, None) => tool.is_some(),
                    (None, _) => false,
                };
                let needed = self.needed.take();
                if !looked && !self.nudged {
                    if let Some(nudge) = needed {
                        self.nudged = true;
                        return Some(nudge);
                    }
                }
            }
        }
        None
    }
}

/// Both checks for one call, with at most one nudge between them until December speaks again: each nudge
/// draws an answer, so a second one (from the other check) made the model answer the same question three times.
#[derive(Debug, Default)]
pub(crate) struct CallChecks {
    handoff: HandoffCheck,
    lookup: LookupCheck,
    nudged: bool,
}

impl CallChecks {
    /// The one nudge this event calls for, if any.
    pub(crate) fn on_event(&mut self, event: HandoffEvent<'_>, now: Instant) -> Option<&'static str> {
        if matches!(event, HandoffEvent::UserSaid(_)) {
            self.nudged = false;
        }
        // both always see the event, so their state stays right even when only one may speak
        let claimed = self.handoff.on_event(event, now);
        let unchecked = self.lookup.on_event(event, now);
        let nudge = claimed.or(unchecked)?;
        if self.nudged {
            return None;
        }
        self.nudged = true;
        Some(nudge)
    }
}

#[cfg(test)]
#[path = "handoff_tests.rs"]
mod tests;
