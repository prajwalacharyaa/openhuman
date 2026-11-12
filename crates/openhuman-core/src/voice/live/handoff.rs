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
//! answered from the model's own knowledge, which is about a year old, and
//! giving in to a pushback ("you're right, I stand corrected") without checking.
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
pub(crate) const NUDGE: &str = "(Server check, not December speaking: you said you're on it, but you did not \
run mochi-ask, so nothing was handed on and nobody is working on it. Run mochi-ask now with your shell tool, \
with December's full request and the context it needs, then confirm in one short sentence. If you already \
handed this same request on earlier in this call, don't call it again; just say you're still on it.)";

/// The web-chat `client_id` `mochi-ask` (tiny-setup) sends its requests as.
pub(crate) const ASK_CLIENT: &str = "mochi-ask";

/// Most of Claude's answer passed into the call; the rest stays in the chat.
const MAX_ANSWER_CHARS: usize = 12000;

/// Sent when December starts a typed turn in this call's chat.
pub(crate) const TYPED_NOTE: &str = "(Server note, not December speaking: December just typed or pasted a \
message into this chat, and it's being worked on. You can't see its text and don't need to. Say in a few \
words that you got it and you're on it; never ask December to say or paste it again. The answer will come to \
you.)";

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
                 Tell December now, without waiting to be asked, as yourself (never name Claude). If it's a status or \
                 the result of a change: one or two sentences. If it's information December asked for (about people, \
                 places, news, research, how or why): read all of it out, completely and in detail, in natural \
                 spoken sentences and the same order; don't summarize or drop anything, and keep going to the end. \
                 If Claude asks December to decide something, name each choice and ask; never drop a choice or a \
                 warning.)\n\n{answer}"
            ))
        }
        // 2026-11-12: an approved task stopped by a newer message came out as "I wasn't able to complete that"
        "chat_error" if event.error_type.as_deref() == Some("cancelled") => Some(
            "(Server note, not December speaking: the request you handed on was stopped before it finished, so \
             it is NOT done. If December asked you to stop it, just confirm it's stopped. Otherwise (a newer \
             message stopped it) tell December that plainly in one sentence and ask whether to run it again.)"
                .to_string(),
        ),
        "chat_error" => Some(format!(
            "(Server note, not December speaking: Claude couldn't finish the request you handed it{}. \
             Tell December briefly, with the reason.)",
            event.message.as_deref().map(|m| format!(": {m}")).unwrap_or_default()
        )),
        _ => None,
    }
}

/// Whether a note carries Claude's finished answer (the model's next words retell it: they aren't saved
/// again, the answer is already in the chat).
pub(crate) fn is_answer_note(note: &str) -> bool {
    [ANSWER_MARK, EARLIER_ANSWER_MARK, TYPED_ANSWER_MARK].iter().any(|mark| note.contains(mark))
}

const ANSWER_MARK: &str = "Claude finished the request you handed it.";
const EARLIER_ANSWER_MARK: &str = "a request handed on earlier in this call, before the phone was passed, is finished";
const TYPED_ANSWER_MARK: &str = "Claude answered what December typed into the chat.";

/// Whether Claude's answer ends by asking December something (a choice, a yes/no): December's next words
/// are the answer, and must go back to Claude.
pub(crate) fn asks_december(answer: &str) -> bool {
    let answer = answer.trim();
    let tail: String = answer.chars().rev().take(300).collect::<Vec<_>>().into_iter().rev().collect::<String>().to_lowercase();
    // 2026-11-12 the four-option answer ended "just say the word if you want me to build and PR it": no "?"
    const OFFERS: &[&str] = &["say the word", "want me to", "should i ", "shall i ", "let me know if", "which one", "do you want"];
    tail.contains('?') || OFFERS.iter().any(|offer| tail.contains(offer))
}

/// The most chats from earlier in the same call (before the phone was passed) a session also listens to.
pub(crate) const MAX_EARLIER_THREADS: usize = 8;

/// Chats used earlier in this call: the `earlier_threads` the phone app sends in the start frame when it
/// passes the phone (2026-11-10: Nadia's answer finished after the phone went back to Mochi, and the new
/// session, on Mochi's chat, dropped it). Read straight from the frame so the start request stays as it was.
pub(crate) fn earlier_threads(start_frame: &str) -> Vec<String> {
    let Ok(value) = serde_json::from_str::<serde_json::Value>(start_frame) else {
        return Vec::new();
    };
    value
        .get("earlier_threads")
        .and_then(|v| v.as_array())
        .map(|ids| {
            ids.iter()
                .filter_map(|id| id.as_str())
                .filter(|id| !id.is_empty() && id.len() <= 120 && id.chars().all(|c| c.is_ascii_alphanumeric() || "-_.~".contains(c)))
                .take(MAX_EARLIER_THREADS)
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default()
}

/// Claude's answer to a `mochi-ask` request made earlier in this call, in another speaker's chat: passed on by
/// whoever has the phone now. Only answers (never another chat's typed turns).
pub(crate) fn earlier_reply_note(event: &WebChannelEvent, earlier: &[String]) -> Option<String> {
    if event.client_id != ASK_CLIENT || !earlier.iter().any(|t| *t == event.thread_id) {
        return None;
    }
    match event.event.as_str() {
        // short, in December's language now: 2026-11-12 Samir's whole analysis was read out in Nepali while
        // December, speaking English, was asking about something else
        "chat_done" => {
            let answer = event.full_response.as_deref().unwrap_or_default().trim();
            if answer.is_empty() {
                return None;
            }
            let answer: String = answer.chars().take(MAX_ANSWER_CHARS).collect();
            Some(format!(
                "(Server note, not December speaking: {EARLIER_ANSWER_MARK} (it was asked in another speaker's \
                 chat). When December isn't in the middle of something with you, tell them in one or two short \
                 sentences, in the language December is speaking to you now: whose it was (e.g. \"Samir's \
                 analysis is ready\") and its main point. The full answer stays in that chat; say more only if \
                 December asks.)\n\n{answer}"
            ))
        }
        "chat_error" => Some(
            "(Server note, not December speaking: a request handed on earlier in this call, before the phone was \
             passed, couldn't be finished. Tell December briefly.)"
                .to_string(),
        ),
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
                 Tell December the gist now, briefly, in spoken style and as yourself (never name Claude). The full \
                 answer is in the chat.)\n\n{answer}"
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

/// How long after December's last heard words a note still waits: a note sent while December is mid-sentence
/// is answered instead of December (2026-11-08: Claude's answer arrived while December was asking the next
/// question, and the reply mixed the two).
pub(crate) const QUIET_AFTER_USER: Duration = Duration::from_millis(1500);

/// Holds server notes (Claude's answers) while the model is speaking or December is talking: a note sent
/// mid-sentence is taken as new input, so the model cuts itself off or talks over December. Notes go out when
/// the model's turn is over and December is quiet ([`Self::turn_over`], else [`Self::tick`]).
#[derive(Debug, Default)]
pub(crate) struct NoteGate {
    speaking: bool,
    heard_at: Option<Instant>,
    waiting: Vec<String>,
}

impl NoteGate {
    /// The model's audio is playing.
    pub(crate) fn agent_audio(&mut self) {
        self.speaking = true;
    }

    /// December's words were heard (any transcript, partial or final).
    pub(crate) fn heard(&mut self, now: Instant) {
        self.heard_at = Some(now);
    }

    fn user_quiet(&self, now: Instant) -> bool {
        self.heard_at.is_none_or(|at| now.duration_since(at) >= QUIET_AFTER_USER)
    }

    fn release(&mut self, now: Instant) -> Option<String> {
        (!self.speaking && self.user_quiet(now) && !self.waiting.is_empty())
            .then(|| std::mem::take(&mut self.waiting).join("\n\n"))
    }

    /// A note to pass to the model: now, or `None` while it waits.
    pub(crate) fn offer(&mut self, note: String, now: Instant) -> Option<String> {
        self.waiting.push(note);
        self.release(now)
    }

    /// The model finished or was interrupted: the notes that waited, as one, unless December is talking.
    pub(crate) fn turn_over(&mut self, now: Instant) -> Option<String> {
        self.speaking = false;
        self.release(now)
    }

    /// December cut the model off: it stopped, and December is talking now.
    pub(crate) fn interrupted(&mut self, now: Instant) {
        self.speaking = false;
        self.heard_at = Some(now);
    }

    /// Called about once a second: notes that waited for December to finish.
    pub(crate) fn tick(&mut self, now: Instant) -> Option<String> {
        self.release(now)
    }
}

/// Keeps the model's spoken retelling of Claude's answer out of the chat: the answer is saved there already, so
/// the chat showed every answer twice (2026-11-12). Late final transcripts of the same turn are skipped too.
#[derive(Debug, Default)]
pub(crate) struct Retelling {
    pending: bool,
    active: bool,
    ended: bool,
}

impl Retelling {
    /// A note went to the model.
    pub(crate) fn note_sent(&mut self, note: &str) {
        if is_answer_note(note) {
            self.pending = true;
        }
    }

    /// The model's audio is playing.
    pub(crate) fn agent_spoke(&mut self) {
        if self.pending {
            self.pending = false;
            self.active = true;
            self.ended = false;
        } else if self.ended {
            self.active = false;
            self.ended = false;
        }
    }

    /// December's words were finalised: what the model says next answers them.
    pub(crate) fn user_spoke(&mut self) {
        *self = Self::default();
    }

    /// The model's turn ended.
    pub(crate) fn turn_over(&mut self) {
        self.ended = true;
    }

    /// Whether the model's final transcript now is a retelling (not saved).
    pub(crate) fn skips_agent_line(&self) -> bool {
        self.active
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
/// "is ready", not "ready": "I'll get that ready for you" is a promise, not a status (2026-11-12: it slipped
/// through, nothing was handed on, and December found out 20 minutes later).
const STATUS_UPDATE: &[&str] = &[
    "still", "keeping an eye", "update you", "let you know", "finish", "is done", "is ready", "'s ready", "are ready",
];

/// How the model, speaking as itself, says it took a request on ("I'm on it", "looking into it", "I'll get
/// that ready").
const SELF_HANDOFF: &[&str] = &[
    "i'm on it", "i am on it", "on it.", "looking into it", "look into it", "get back to you", "i'll find out",
    "i'll get that", "i'll get it", "i'll get started", "i'll go with", "i'll start", "i'll fix", "i'll make that",
    "i'll handle", "i'll take care", "i'll set that up", "i'll set it up", "getting that ready", "i'll kick",
    "हेर्छु", "पत्ता लगाउँछु", "गरिदिन्छु", "मिलाइदिन्छु", "सुरु गर्छु",
];

/// Whether the model's words claim the work was taken on ("Claude is on it", "I'm on it, I'll get back to you").
/// A question ("Shall I look into it?") is not a claim, and neither is a status update.
pub(crate) fn claims_handoff(text: &str) -> bool {
    let text = text.trim().to_lowercase().replace('\u{2019}', "'");
    if text.ends_with('?') || STATUS_UPDATE.iter().any(|w| text.contains(w)) {
        return false;
    }
    if !(text.contains("claude") || text.contains("क्लाउड")) {
        return SELF_HANDOFF.iter().any(|marker| text.contains(marker));
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
sentence, like \"Still on it\" or \"Done, pull request 13 is up\". Send nothing new.)";

/// Sent to the live model when it gave in to December's pushback without checking.
pub(crate) const AGREE_NUDGE: &str = "(Server check, not December speaking: you changed your answer because \
December pushed back, without checking anything. Check now: search with your web search tool, or hand it to \
Claude with mochi-ask if it's about December's own server, projects or code. If your first answer stands, say \
so politely; agree only with what the check shows.)";

/// Sent to the live model when December asked about something from before and it answered without memory.
pub(crate) const MEMORY_NUDGE: &str = "(Server check, not December speaking: December asked about something from \
before (their plans, an earlier talk, something they told you) and you answered without searching memory. If \
your answer came from the memory notes you were given at the start of this call, say nothing. Otherwise search \
memory now with memory_search and answer only from what it shows; if it has nothing, say you don't have that \
saved. Never guess.)";

/// How December asks about something only memory knows (their own plans, earlier talks).
const RECALL_ASKED: &[&str] = &[
    "do you remember", "remember when", "did i tell you", "did i say", "what did i", "what did we",
    "when did i", "where did i", "what was my", "my plan", "my schedule", "what do i have", "when is my",
    "any reminder", "reminders", "my reminder", "any plans", "plans for", "do i have", "anything planned", "my meeting",
    "मैले भनेको", "सम्झ",
];
/// ...unless December is asking to set something, not asking what's there ("remind me at 5").
const SETTING_WORDS: &[&str] = &["remind me", "set a", "add a", "create a", "make a", "schedule a"];

/// Words in December's request that only a fresh lookup can answer.
const FRESH_WORDS: &[&str] = &[
    "news", "headline", "latest", "price", "score", "weather", "nepse", "share market", "stock market",
    "check if", "verify", "is it true", "is that true", "are you sure", "genuine",
    "current", "right now", "research", "look up", "look it up", "search for", "find out",
    "समाचार", "न्युज", "खबर", "ताजा", "मौसम", "सेयर",
    // travel: distances, routes, buses and tickets change and the model's guess sounds sure (2026-11-12)
    // ("bus" alone, or "the bus", would match "the business")
    "how far", "distance", "route", "a bus ", "the bus ", "by bus", "bus to", "buses", "bus park", "ticket",
    "दूरी", "टाढा", "टिकट", "गाडी",
];
/// How December asks whether Claude is still working or done.
const STATUS_ASKED: &[&str] = &[
    "is claude", "claude is", "has claude", "if claude", "claude still", "claude done", "claude finished",
    "claude doing", "claude's doing", "क्लाउडले", "are you done", "is it done", "any update",
    "how's it going", "how is it going", "is it ready", "सकियो",
    // 2026-11-12: "tell me what is happening" and "काम भयो त कि बाँकी छ?" were answered from memory, wrongly
    "what is happening", "what's happening", "what's going on", "what is going on", "what are you doing",
    "was it done", "is that done", "is the edit done", "status", "progress",
    "भयो कि", "भयो त", "बाँकी छ", "के हुँदै", "कहाँ पुग्यो",
];
/// ...unless December asks about something already said ("yesterday's news from our last chat").
const PAST_WORDS: &[&str] = &["yesterday", "previous", "earlier", "last time", "हिजो", "अघिल्लो", "पहिले"];
/// How the model announces a lookup it then has to make.
const LOOKUP_SAID: &[&str] = &[
    "checking", "let me check", "searching", "looking it up", "let me look", "हेर्दै", "खोज्दै",
    // a finished check it may not have made: answered only if a tool really ran this turn
    "my research", "research shows", "i checked", "i've checked", "i have checked", "i looked", "i've looked",
    "i searched", "i've searched", "according to my search",
];
/// How the model says it looked in memory (a memory search must back it, not some other tool).
const MEMORY_SAID: &[&str] = &["checked the memory", "checked your memory", "checked memory", "memory confirms",
    "in your memory", "in my memory", "memory shows"];
/// How the model gives in to a pushback.
/// 2026-11-09: "You're right to ask; Rajan Bikram Thapa is indeed…" and "I made a mistake… sorry for the
/// confusion" flipped a fact without a new search, and none of the first list caught it.
const AGREE_SAID: &[&str] = &[
    "you're absolutely right", "you are absolutely right", "i stand corrected", "my mistake", "i was wrong",
    "you're right", "you are right", "you're correct", "you are correct", "i made a mistake", "sorry for the confusion",
    "good catch", "my apologies, ", "तपाईं सही", "मेरो गल्ती",
];

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

/// Whether December asks about something from before that memory holds.
pub(crate) fn asks_recall(text: &str) -> bool {
    let text = text.to_lowercase().replace('\u{2019}', "'");
    RECALL_ASKED.iter().any(|w| text.contains(w)) && !SETTING_WORDS.iter().any(|w| text.contains(w))
}

/// Whether the model's words say it looked in memory.
pub(crate) fn claims_memory(text: &str) -> bool {
    let text = text.to_lowercase();
    MEMORY_SAID.iter().any(|w| text.contains(w))
}

/// Whether the model's words say it is looking something up.
pub(crate) fn announces_lookup(text: &str) -> bool {
    let text = text.to_lowercase();
    LOOKUP_SAID.iter().any(|w| text.contains(w))
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
    /// When a memory tool last ran: only that answers a question about December's own plans.
    last_memory: Option<Instant>,
    /// Already nudged since December last spoke.
    nudged: bool,
}

impl LookupCheck {
    fn need(&mut self, nudge: &'static str) {
        let rank = |n: &str| [AGREE_NUDGE, STATUS_NUDGE, LOOKUP_NUDGE, MEMORY_NUDGE].iter().position(|x| *x == n);
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
                } else if asks_recall(text) {
                    self.need(MEMORY_NUDGE);
                }
            }
            HandoffEvent::AgentSaid(text) => {
                self.user_turn = false;
                if concedes(text) {
                    self.need(AGREE_NUDGE);
                }
                if claims_memory(text) {
                    self.need(MEMORY_NUDGE);
                } else if announces_lookup(text) {
                    self.need(LOOKUP_NUDGE);
                }
            }
            HandoffEvent::ToolStarted(name) => {
                self.last_tool = Some(now);
                if name.contains("memory") {
                    self.last_memory = Some(now);
                }
            }
            HandoffEvent::TurnComplete => {
                self.user_turn = false;
                let needed = self.needed.take();
                // a question about December's plans needs a memory search (reminders alone don't answer it)
                let tool = if needed == Some(MEMORY_NUDGE) { self.last_memory } else { self.last_tool };
                let looked = match (tool, self.asked_at) {
                    (Some(tool), Some(asked)) => tool + LOOKUP_GRACE >= asked,
                    (tool, None) => tool.is_some(),
                    (None, _) => false,
                };
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
    /// Claude is working on a request in this call's chat right now.
    brain_busy: bool,
    /// Claude's last answer asked December something; December hasn't spoken since.
    claude_asked: bool,
    /// December spoke after Claude asked: this turn must hand the answer on.
    answer_due: bool,
    said_not_started: bool,
    said_cancelled: bool,
    /// When the model said it's checking, in this turn.
    checking_at: Option<Instant>,
    last_tool: Option<Instant>,
    last_shell: Option<Instant>,
}

/// Sent when December answered a question Claude asked and the turn handed nothing on.
pub(crate) const ANSWER_NUDGE: &str = "(Server check, not December speaking: Claude's last answer asked December \
to decide or confirm something, and December has answered, but you didn't send it on, so nobody is acting on it. If \
December's words answer Claude's question, run mochi-ask now with their answer and what it's about (e.g. \"December \
picked option 1 for the portfolio brush fix: do it\"), then confirm in a few words. If December was talking about \
something else, ignore this note.)";

/// Sent when the model says the work hasn't started while Claude is working on it.
pub(crate) const BUSY_NUDGE: &str = "(Server check, not December speaking: you said the work hasn't started or is \
waiting, but Claude IS working on a request in this chat right now. Correct that in one short sentence. If December \
asks how far it is, run mochi-ask --result.)";

/// Sent when the model says it cancelled something without running anything.
pub(crate) const CANCEL_NUDGE: &str = "(Server check, not December speaking: you said you cancelled or stopped \
something, but you ran no command, so nothing was cancelled. If December wants Claude's work stopped, run mochi-ask \
--cancel now and say what it shows. If the work had already finished, tell December that instead.)";

/// Sent when the model searched, then said it's still checking and stopped without an answer.
pub(crate) const HANGING_NUDGE: &str = "(Server check, not December speaking: you searched, then said you're \
checking and stopped without an answer. If what you found answers it, tell December now. If it doesn't, hand it on \
with mochi-ask and say so in a few words.)";

/// How the model says the work hasn't begun (2026-11-12: said twice while Claude was building the fix).
const NOT_STARTED_SAID: &[&str] = &[
    "haven't started", "have not started", "hasn't started", "not started", "nothing has been",
    "waiting for your approval", "सुरु भएको छैन", "सुरु गरेको छैन", "स्वीकृति कुर",
];
/// How the model says it cancelled something itself (2026-11-12: "मैले त्यसलाई क्यान्सिल गरिदिएँ", nothing ran).
const CANCEL_SAID: &[&str] = &[
    "i cancelled", "i've cancelled", "i have cancelled", "i canceled", "i've canceled", "cancelled it",
    "canceled it", "cancelled that", "i stopped it", "i've stopped it", "क्यान्सिल गरिदिएँ", "क्यान्सिल गरें",
    "रद्द गरें", "रद्द गरिदिएँ",
];
/// How the model says it is checking right now (not "I checked").
const CHECKING_NOW: &[&str] =
    &["checking", "let me check", "searching", "looking it up", "let me look", "हेर्दै", "खोज्दै"];

/// A tool started this soon before "checking" was said is the check it announced.
const CHECK_GRACE: Duration = Duration::from_secs(3);
/// A shell command this recent can be the cancel the model talks about.
const CANCEL_COVERS: Duration = Duration::from_secs(30);

impl CallChecks {
    /// Claude started a turn in this call's chat.
    pub(crate) fn brain_started(&mut self) {
        self.brain_busy = true;
    }

    /// Claude's turn in this call's chat ended; `asked` when its answer asks December something.
    pub(crate) fn brain_finished(&mut self, asked: bool) {
        self.brain_busy = false;
        self.claude_asked = asked;
    }

    /// The one nudge this event calls for, if any.
    pub(crate) fn on_event(&mut self, event: HandoffEvent<'_>, now: Instant) -> Option<&'static str> {
        if matches!(event, HandoffEvent::UserSaid(_)) {
            self.nudged = false;
        }
        // all always see the event, so their state stays right even when only one may speak
        let wrong = self.call_facts(event, now);
        let claimed = self.handoff.on_event(event, now);
        let unchecked = self.lookup.on_event(event, now);
        let nudge = wrong.or(claimed).or(unchecked)?;
        if self.nudged {
            return None;
        }
        self.nudged = true;
        Some(nudge)
    }

    /// What the model said against what is really going on in this call.
    fn call_facts(&mut self, event: HandoffEvent<'_>, now: Instant) -> Option<&'static str> {
        match event {
            HandoffEvent::UserSaid(_) => {
                if self.claude_asked {
                    self.claude_asked = false;
                    self.answer_due = true;
                }
                self.said_not_started = false;
                self.said_cancelled = false;
                self.checking_at = None;
            }
            HandoffEvent::AgentSaid(text) => {
                let text = text.to_lowercase().replace('\u{2019}', "'");
                if self.brain_busy && NOT_STARTED_SAID.iter().any(|w| text.contains(w)) {
                    self.said_not_started = true;
                }
                if CANCEL_SAID.iter().any(|w| text.contains(w)) {
                    self.said_cancelled = true;
                }
                if CHECKING_NOW.iter().any(|w| text.contains(w)) {
                    self.checking_at.get_or_insert(now);
                } else if text.chars().count() > 40 {
                    self.checking_at = None; // it went on to answer
                }
            }
            HandoffEvent::ToolStarted(name) => {
                self.last_tool = Some(now);
                if name == "shell" {
                    self.last_shell = Some(now);
                    self.answer_due = false;
                }
                self.checking_at = None;
            }
            HandoffEvent::TurnComplete => {
                let shelled = self.last_shell.is_some_and(|at| now.duration_since(at) < CANCEL_COVERS);
                // searched before saying "checking", nothing after it: the answer never came
                let hanging = self.checking_at.is_some_and(|said| {
                    self.last_tool.is_some_and(|tool| tool + CHECK_GRACE < said && said.duration_since(tool) < HANDOFF_COVERS)
                });
                let nudge = if self.said_not_started {
                    Some(BUSY_NUDGE)
                } else if self.said_cancelled && !shelled {
                    Some(CANCEL_NUDGE)
                } else if self.answer_due {
                    Some(ANSWER_NUDGE)
                } else if hanging {
                    Some(HANGING_NUDGE)
                } else {
                    None
                };
                self.said_not_started = false;
                self.said_cancelled = false;
                self.answer_due = false;
                self.checking_at = None;
                return nudge;
            }
        }
        None
    }
}

#[cfg(test)]
#[path = "handoff_tests.rs"]
mod tests;
