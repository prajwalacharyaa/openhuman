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
            let answer = from_in_short(event.full_response.as_deref().unwrap_or_default().trim());
            if answer.is_empty() {
                return None;
            }
            let answer: String = answer.chars().take(MAX_ANSWER_CHARS).collect();
            Some(format!(
                "(Server note, not December speaking: Claude finished the request you handed it. \
                 Tell December now, without waiting to be asked, as yourself (never name Claude): say only its \
                 \"In short\" part (or, without one, its main point in one or two sentences), then stop. Keep the \
                 rest: when December asks for more (\"more\", \"why\", \"which ones\"), answer from it, without \
                 handing anything on again. Read all of it only if December asked for the full story or details. \
                 If it asks December to decide something, name each choice and ask; never drop a choice or a \
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

/// The answer from its "In short:" line on: Claude's narration before its tool steps ("Graph exists. Let me query
/// it.") isn't part of it, and was read out as if it were (2026-11-14). Without that line, the narration sentences
/// it starts with are left out (2026-11-19: "Let me check the actual site flow in the repo before giving Prabesh
/// exact steps, so I don't invent UI details" was read out to a guest, joined to the answer with no space).
pub(crate) fn from_in_short(answer: &str) -> &str {
    if let Some(at) = answer.find("In short:") {
        return &answer[at..];
    }
    const NARRATION: &[&str] = &[
        "Let me ", "Now let me ", "First, let me ", "First let me ", "I'll check ", "I'll look ", "I'll verify ",
    ];
    let mut rest = answer.trim_start();
    while NARRATION.iter().any(|lead| rest.starts_with(lead)) {
        let Some((end, stop)) = rest.char_indices().find(|(_, c)| matches!(c, '.' | '!' | '\n' | '\u{2026}')) else {
            break;
        };
        rest = rest[end + stop.len_utf8()..].trim_start();
    }
    if rest.is_empty() { answer } else { rest }
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
    // the question ends the answer: a "?" further up is quoted text (2026-11-16: a LinkedIn draft read out
    // ended its own post with a question, and "okay, read that to me" was nudged as an answer not sent on)
    let end: String = tail.chars().rev().take(120).collect();
    end.contains('?') || OFFERS.iter().any(|offer| tail.contains(offer))
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
    // passing it to someone on the team (2026-11-18: "I'm passing your request to Iris", nothing was sent)
    "passing your request", "passing it to", "passing this to", "passing that to", "passing it on",
    "passed your request", "passed it to", "passed it on", "i've asked", "i have asked", "i've sent",
    "i have sent", "i've handed", "handing it to", "handing this to", "handing that to", "sending it to",
    "sent it to", "i've told", "i'll ask",
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
/// Whether December's words ask for something current.
pub(crate) fn asks_fresh(text: &str) -> bool {
    let text = text.to_lowercase();
    FRESH_WORDS.iter().any(|w| text.contains(w)) && !PAST_WORDS.iter().any(|w| text.contains(w)) && is_request(&text)
}

/// How a request starts ("check the weather", "is the market open").
const REQUEST_STARTS: &[&str] = &[
    "what", "what's", "whats", "when", "where", "who", "why", "how", "which", "is", "are", "was", "were", "do",
    "does", "did", "can", "could", "will", "would", "should", "any", "also", "and", "please", "now", "then", "mochi",
    "check", "tell", "find", "search", "look", "show", "give", "get", "verify", "research", "read",
];
const REQUEST_WORDS: &[&str] = &["can you", "could you", "tell me", "check ", "find out", "look up", "look it up", "search for", "research"];

/// Whether December's words (lowercased) ask for something, rather than remark on it: a question, a short
/// phrase ("Nepal news today"), a request, or Nepali (no word list for it). 2026-11-16: "It's not just about the
/// share market or anything, I don't want random guesses" drew a search nudge, and the apology was said twice.
fn is_request(text: &str) -> bool {
    let words: Vec<&str> = text.split_whitespace().collect();
    text.contains('?')
        || !text.is_ascii()
        || words.len() <= 6
        || words.first().is_some_and(|w| REQUEST_STARTS.contains(&w.trim_matches(|c: char| !c.is_alphanumeric() && c != '\'')))
        || REQUEST_WORDS.iter().any(|w| text.contains(w))
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
        let rank = |n: &str| [STATUS_NUDGE, LOOKUP_NUDGE, MEMORY_NUDGE].iter().position(|x| *x == n);
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
    /// When December's words that started this turn were heard.
    asked_at: Option<Instant>,
    last_tool: Option<Instant>,
    last_shell: Option<Instant>,
    /// December named a book in their library this turn (2026-11-15: "the summary of Kafka" was answered from the
    /// voice model's memory in one sentence).
    book_asked: bool,
    /// A call with one of Mochi's agents (the voice is only its mouth: the agent's knowledge, notes and tools are
    /// in its own chat).
    agent_call: bool,
    /// December asked the agent for work this turn.
    work_asked: bool,
    /// A shell command ran in this turn. Kept until the turn ends, not reset when December's words arrive:
    /// Gemini often sends the transcript of what December said after the command it led to (2026-11-18: two
    /// "agent work" nudges right after mochi-ask, and Samir said "I am looking into this" over his own answer).
    turn_shell: bool,
    /// The voice said it can't do something, this turn.
    cant_said: bool,
    /// The turn is the voice passing on Claude's answer (whose "I can't" is Claude's verdict, not a refusal).
    reading_answer: bool,
    /// December named one of their own projects this turn.
    project_asked: bool,
    /// December's project names, as they say them ("nepal hazard explorer", "jigubites").
    projects: Vec<String>,
    /// The voice gave an answer or a verdict while the one it handed on was still being worked on.
    early_verdict: bool,
    /// December said "no, not that" while the request handed on was being worked on.
    correction: bool,
}

/// Sent when the voice answers before the answer it handed on is back (2026-11-19: "my view is to hold" MNBBL, said
/// a minute before Samir's answer came in, and Samir's answer was "sell, not hold").
pub(crate) const VERDICT_NUDGE: &str = "(Server check, not December speaking: you just gave December an answer or \
a verdict, but the request you handed on hasn't come back yet, so you don't know the answer. Tell December in one \
short sentence to ignore what you just said: the real answer is still coming, and you'll say it when it arrives. \
Never guess an answer while one is on its way.)";

/// How a line gives an answer or a verdict (lowercased, straight apostrophes).
const VERDICT_SAID: &[&str] = &[
    "my view is", "my view:", "my advice", "i'd say", "i would say", "i recommend", "i'd recommend", "i suggest",
    "you should", "is to hold", "is to sell", "is to buy", "better to hold", "better to sell", "better to buy",
    "good to sell", "good to buy", "good time to", "bearish", "bullish", "the trend is", "trend is down",
    "trend is up", "the answer is", "it turns out", "looks fine", "everything is fine", "होल्ड गर्नु", "बेच्नु",
    "किन्नु",
];

/// ...unless the line only names what is being checked ("Looking up whether it's good to sell MNBBL").
const VERDICT_NOT: &[&str] = &[
    "checking", "check ", "looking", "asking", "finding out", "whether", "let me", "हेर्दै", "बुझ्दै", "सोध्दै",
];

/// Whether the voice's line (lowercased) gives an answer or a verdict.
pub(crate) fn gives_verdict(text: &str) -> bool {
    VERDICT_SAID.iter().any(|w| text.contains(w)) && !VERDICT_NOT.iter().any(|w| text.contains(w))
}

/// Sent when December says "no, not that" while the request handed on is being worked on (2026-11-19: a misheard
/// "budget" went to Claude; "No, it's not that" stopped nothing, and the answer about map fees came a minute later).
pub(crate) const CORRECTION_NUDGE: &str = "(Server check, not December speaking: December said that's not what they \
meant, and the request you handed on is still being worked on, so it's the wrong request. Run mochi-ask --cancel \
now, then hand on what December does want (ask them in a few words if it isn't clear). If December's words were \
about something else, ignore this note.)";

/// How December says the request was misheard (lowercased).
const CORRECTION_SAID: &[&str] = &[
    "not that", "not this", "that's not what", "not what i", "didn't mean", "did not mean", "don't mean",
    "misheard", "wrong one", "wrong thing", "त्यो होइन", "यो होइन", "होइन होइन", "त्यो हैन", "यो हैन",
];

/// Whether December's words say the request was the wrong one ("No, it's not that.").
pub(crate) fn corrects(text: &str) -> bool {
    let text = text.trim().to_lowercase().replace('\u{2019}', "'");
    text == "no" || text.starts_with("no,") || text.starts_with("no.") || CORRECTION_SAID.iter().any(|w| text.contains(w))
}

/// Whether a session ended because the call was hung up (not dropped by the provider or the network, which
/// reconnect).
pub(crate) fn hung_up(reason: &str) -> bool {
    reason.starts_with("December hung up") || reason.contains("hang up")
}

/// Sent on a call with an agent when December asked for work and the voice did it from its own head (2026-11-17:
/// Nadia's voice made up topics and a "draft" for eight minutes, said she can't post, and handed on nothing).
pub(crate) const WORK_NUDGE: &str = "(Server check, not December speaking: December asked you for work (ideas, a \
list, a draft, a plan, a check, posting) and you answered from your own head. On a call you are only the voice: \
what you know, your notes and your tools are in your own chat. Hand December's request to it now with mochi-ask, \
in their words with what they said they want, and say in one short line that you're on it. When the answer \
comes, say that one; what you said from your own head doesn't count.)";

/// Words of a request for work, in a call with an agent.
const WORK_WORDS: &[&str] = &[
    "draft", "write", "rewrite", "post", "publish", "idea", "topic", "list", "plan", "strategy", "schedule",
    "generate", "create", "make ", "prepare", "suggest", "recommend", "review", "analy", "research", "find ",
    "check ", "give me", "send ", "fix ", "build", "design", "should i", "should we", "can you", "could you",
];

/// Whether December asks an agent for work ("give me some topics", "make one draft", "can you post it").
pub(crate) fn asks_work(text: &str) -> bool {
    let text = text.to_lowercase();
    WORK_WORDS.iter().any(|w| text.contains(w))
}

/// Sent when the voice said it can't do something without trying (2026-11-18: "I can't generate images", then "so
/// I can't ask a visual designer", with Iris on the team; 2026-11-17: Nadia's voice said she can't post; 2026-11-16:
/// "I don't have the ability to clear the inbox", where opening the Inbox screen was the helpful answer).
pub(crate) const CANT_NUDGE: &str = "(Server check, not December speaking: you told December you can't do that, \
without trying. Mochi's team does much more than you can on a call: drawings and designs (Iris), writing (Mark), \
LinkedIn (Nadia), shares (Samir), code, research, the server. Hand the request on with mochi-ask now, in December's \
words, and say in a few words that you're on it. Only the answer that comes back can say it can't be done. If it is \
something December does by touch in the app (deleting a book, clearing the inbox, locking), open that screen for them \
with mochi-phone screen and say what to tap.)";

/// How the voice says it can't (lowercased, straight apostrophes).
const CANT_SAID: &[&str] = &[
    "i can't", "i cannot", "i can not", "i'm not able", "i am not able", "i'm unable", "i am unable",
    "i don't have the ability", "i do not have the ability", "i don't have access", "i have no way",
    "not able to do", "सक्दिन", "सक्दैन",
    // handing the job back to December is a refusal too (2026-11-16/17: "you will need to post it manually")
    "manually", "do it yourself", "you'll have to do it", "you will have to do it",
];

/// ...unless it's about hearing or certainty ("I can't hear you", "I can't say for sure"), not a refusal.
const CANT_NOT_REFUSAL: &[&str] = &[
    "hear", "understand", "catch", "make out", "wait", "guarantee", "promise", "be sure", "for sure", "सुनि",
];

/// Whether the voice's line says it can't do something.
pub(crate) fn says_cant(text: &str) -> bool {
    let text = text.to_lowercase().replace('\u{2019}', "'");
    CANT_SAID.iter().any(|w| text.contains(w)) && !CANT_NOT_REFUSAL.iter().any(|w| text.contains(w))
}

/// Sent when December asked about one of their own projects and the turn handed nothing on (2026-11-18: "any update
/// in Nepal Hazard project?" was web-searched and answered with someone else's project).
pub(crate) const PROJECT_NUDGE: &str = "(Server check, not December speaking: December asked about one of their \
own projects, and you answered without handing it on. A web search can't see their repos or their work, and what \
you remember may be old. Hand it to Claude now with mochi-ask, in December's words, and say in a few words that \
you're on it; what you said before doesn't count.)";

/// Words that are too common to stand for a project on their own ("portfolio" is also the shares portfolio).
const PROJECT_TOO_COMMON: &[&str] = &[
    "portfolio", "agent", "auto", "mochi", "design", "site", "app", "backend", "nepal", "acharya",
];

/// How December says each project folder's name: "nepal-hazard-explorer" -> "nepal hazard explorer" and
/// "nepal hazard"; "jigubites_app" -> "jigubites app" and "jigubites"; "portfolio-site" stays "portfolio site".
pub(crate) fn project_names(folders: &[String]) -> Vec<String> {
    let mut names = Vec::new();
    for folder in folders {
        let spoken = folder.to_lowercase().replace(['-', '_'], " ");
        let words: Vec<&str> = spoken.split_whitespace().collect();
        if words.is_empty() {
            continue;
        }
        let mut add = |name: String| {
            if !PROJECT_TOO_COMMON.contains(&name.as_str()) && !names.contains(&name) {
                names.push(name);
            }
        };
        add(words.join(" "));
        if words.len() >= 2 && ["site", "app", "backend", "api", "web"].contains(&words[words.len() - 1]) {
            add(words[..words.len() - 1].join(" "));
        }
        if words.len() >= 3 {
            add(words[..2].join(" "));
        }
    }
    names
}

/// December's project folders (`projects/github`), as spoken names.
pub(crate) fn own_projects() -> Vec<String> {
    project_names(&crate::agent::tinyagents::middleware::project_folders())
}

/// Whether December's words name one of their projects (whole words).
pub(crate) fn names_project(text: &str, projects: &[String]) -> bool {
    let spoken = format!(" {} ", text.to_lowercase().replace(['-', '_'], " ")
        .chars().map(|c| if c.is_alphanumeric() { c } else { ' ' }).collect::<String>()
        .split_whitespace().collect::<Vec<_>>().join(" "));
    projects.iter().any(|name| spoken.contains(&format!(" {name} ")))
}

/// Sent when December named a book in their library and the turn answered without opening it.
pub(crate) const BOOK_NUDGE: &str = "(Server check, not December speaking: December asked about a book in their own \
library and you answered without opening it. Answer from the library now: the summary with mochi-books read \"<book>\" \
--summary, a review with --report, reading with mochi-books read \"<book>\", a question with mochi-books search. Never \
from your own memory of the book.)";

/// A short name for a nudge, for the log (which check fired is what decides whether it earns its place).
pub(crate) fn nudge_kind(nudge: &str) -> &'static str {
    [
        (NUDGE, "handoff claimed"), (LOOKUP_NUDGE, "lookup"), (STATUS_NUDGE, "status"), (MEMORY_NUDGE, "memory"),
        (ANSWER_NUDGE, "answer not sent on"), (BUSY_NUDGE, "busy"), (CANCEL_NUDGE, "cancel"), (HANGING_NUDGE, "hanging"),
        (BOOK_NUDGE, "library book"), (WORK_NUDGE, "agent work"), (CANT_NUDGE, "said it can't"),
        (PROJECT_NUDGE, "own project"), (VERDICT_NUDGE, "answered before the answer"),
        (CORRECTION_NUDGE, "wrong request"),
    ]
    .iter()
    .find(|(text, _)| *text == nudge)
    .map_or("other", |(_, kind)| *kind)
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

/// Whether the line (lowercased) says it's checking and stops there. "Checking… The share market is open today."
/// went on to answer in the same line (2026-11-16: it was nudged as hanging and the answer came twice).
pub(crate) fn only_announces(text: &str) -> bool {
    let sentences: Vec<&str> = text.split(['.', '!', '?', '\u{2026}', '\u{0964}']).collect();
    let Some(last) = sentences.iter().rposition(|s| CHECKING_NOW.iter().any(|w| s.contains(w))) else {
        return false;
    };
    !sentences[last + 1..].iter().any(|s| s.split_whitespace().count() >= 3)
}

/// How long a nudge waits before it's sent. Gemini often ends the spoken turn and starts the tool a second or
/// two later, so a check made the moment the turn ends sees "no tool" for a turn that is about to run one
/// (2026-11-15/16: three of eight nudges were followed by the tool within 2 s; each drew a repeated sentence).
pub(crate) const NUDGE_WAIT: Duration = Duration::from_millis(2500);

/// A nudge held back for [`NUDGE_WAIT`]: dropped when a tool starts or December speaks meanwhile.
#[derive(Debug, Default)]
pub(crate) struct NudgeDelay {
    waiting: Option<(&'static str, Instant)>,
}

impl NudgeDelay {
    pub(crate) fn hold(&mut self, nudge: &'static str, now: Instant) {
        self.waiting = Some((nudge, now + NUDGE_WAIT));
    }

    /// A tool started or December spoke: the turn wasn't over, or a new one began. Returns what was dropped.
    pub(crate) fn drop_waiting(&mut self) -> Option<&'static str> {
        self.waiting.take().map(|(nudge, _)| nudge)
    }

    /// The nudge to send now, once its wait is over.
    pub(crate) fn due(&mut self, now: Instant) -> Option<&'static str> {
        let (_, at) = self.waiting?;
        (now >= at).then(|| self.waiting.take().map(|(nudge, _)| nudge)).flatten()
    }
}

/// Claude's answers passed into a call that the voice hasn't finished saying, by chat: when the call drops in
/// between, the next session on that chat says it (2026-11-16: Gemini closed the session a second after the
/// answer arrived; the reconnected call never told December).
static UNSPOKEN: std::sync::Mutex<Vec<(String, String, Instant)>> = std::sync::Mutex::new(Vec::new());
/// An answer older than this isn't brought up again by itself (it's in the chat).
const UNSPOKEN_KEEPS: Duration = Duration::from_secs(600);

/// An answer note went (or is waiting to go) to the voice in this chat.
pub(crate) fn unspoken_set(thread_id: &str, note: &str, now: Instant) {
    if !is_answer_note(note) {
        return;
    }
    let mut held = UNSPOKEN.lock().unwrap_or_else(|e| e.into_inner());
    held.retain(|(thread, _, at)| thread != thread_id && now.duration_since(*at) < UNSPOKEN_KEEPS);
    held.push((thread_id.to_string(), note.to_string(), now));
}

/// The voice said it (or December cut it off, having heard enough).
pub(crate) fn unspoken_clear(thread_id: &str) {
    UNSPOKEN.lock().unwrap_or_else(|e| e.into_inner()).retain(|(thread, _, _)| thread != thread_id);
}

/// A new session on this chat: the answer the last one never said, if it's recent.
pub(crate) fn unspoken_take(thread_id: &str, now: Instant) -> Option<String> {
    let mut held = UNSPOKEN.lock().unwrap_or_else(|e| e.into_inner());
    let at = held.iter().position(|(thread, _, _)| thread == thread_id)?;
    let (_, note, since) = held.remove(at);
    (now.duration_since(since) < UNSPOKEN_KEEPS).then_some(note)
}

/// The voice each chat's call started with. The voice setting is read when a session starts, so a call that
/// reconnected after the setting changed (or after Gemini dropped it) came back as someone else (2026-11-16).
static CALL_VOICE: std::sync::Mutex<Vec<(String, Option<String>)>> = std::sync::Mutex::new(Vec::new());
/// Chats whose call voice is remembered (the newest ones).
const CALL_VOICES_KEPT: usize = 16;

/// The voice for a session on this chat: on a reconnect the one the call started with, else `wanted` (which a
/// new call starts with, and its reconnects keep).
pub(crate) fn call_voice(thread_id: &str, reconnect: bool, wanted: Option<String>) -> Option<String> {
    let mut held = CALL_VOICE.lock().unwrap_or_else(|e| e.into_inner());
    if reconnect {
        if let Some((_, voice)) = held.iter().find(|(thread, _)| thread == thread_id) {
            return voice.clone();
        }
    }
    held.retain(|(thread, _)| thread != thread_id);
    held.push((thread_id.to_string(), wanted.clone()));
    let extra = held.len().saturating_sub(CALL_VOICES_KEPT);
    held.drain(..extra);
    wanted
}

/// A tool started this soon before "checking" was said is the check it announced.
const CHECK_GRACE: Duration = Duration::from_secs(3);
/// A shell command this recent can be the cancel the model talks about.
const CANCEL_COVERS: Duration = Duration::from_secs(30);

impl CallChecks {
    /// The checks for a call with one of Mochi's agents: work December asks for has to reach the agent's chat.
    pub(crate) fn for_agent() -> Self {
        Self { agent_call: true, ..Self::default() }
    }

    /// The checks for a call: with an agent or with Mochi, knowing December's own projects.
    pub(crate) fn for_call(agent_call: bool) -> Self {
        Self { agent_call, projects: own_projects(), ..Self::default() }
    }

    /// The same checks, with these project names (tests).
    #[cfg(test)]
    pub(crate) fn with_projects(mut self, projects: Vec<String>) -> Self {
        self.projects = projects;
        self
    }

    /// An answer from another speaker's chat is being passed to the call.
    pub(crate) fn answer_passed(&mut self) {
        self.reading_answer = true;
    }

    /// Claude started a turn in this call's chat.
    pub(crate) fn brain_started(&mut self) {
        self.brain_busy = true;
    }

    /// Claude's turn in this call's chat ended; `asked` when its answer asks December something.
    pub(crate) fn brain_finished(&mut self, asked: bool) {
        self.brain_busy = false;
        self.claude_asked = asked;
        self.reading_answer = true;
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
            HandoffEvent::UserSaid(text) => {
                self.book_asked = crate::agent::tinyagents::middleware::library_book(
                    text, &crate::agent::tinyagents::middleware::library_catalogue()).is_some();
                if self.agent_call && asks_work(text) {
                    self.work_asked = true;
                }
                if names_project(text, &self.projects) {
                    self.project_asked = true;
                }
                if self.claude_asked {
                    self.claude_asked = false;
                    self.answer_due = true;
                }
                if self.brain_busy && corrects(text) {
                    self.correction = true;
                }
                self.said_not_started = false;
                self.said_cancelled = false;
                self.checking_at = None;
                self.asked_at = Some(now);
            }
            HandoffEvent::AgentSaid(text) => {
                let text = text.to_lowercase().replace('\u{2019}', "'");
                if says_cant(&text) {
                    self.cant_said = true;
                }
                if self.brain_busy && NOT_STARTED_SAID.iter().any(|w| text.contains(w)) {
                    self.said_not_started = true;
                }
                if self.brain_busy && !self.reading_answer && gives_verdict(&text) {
                    self.early_verdict = true;
                }
                if CANCEL_SAID.iter().any(|w| text.contains(w)) {
                    self.said_cancelled = true;
                }
                if only_announces(&text) {
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
                    self.book_asked = false;
                    self.work_asked = false;
                    self.turn_shell = true;
                }
                self.checking_at = None;
            }
            HandoffEvent::TurnComplete => {
                let shelled = self.last_shell.is_some_and(|at| now.duration_since(at) < CANCEL_COVERS);
                // searched before saying "checking", nothing after it: the answer never came. Not after a helper
                // (shell): "I'm on it, checking…" is the right line once mochi-ask has the request, and "Checking
                // the status file shows…" is an answer (2026-11-13: both were nudged, one into a failed command).
                // The search has to be this request's: one for the question before isn't "searched, then stopped"
                // (2026-11-16: "Confirm it with Samir" -> "Checking with Samir", said twice).
                let hanging = self.last_shell != self.last_tool
                    && self.checking_at.is_some_and(|said| {
                        self.last_tool.is_some_and(|tool| {
                            tool + CHECK_GRACE < said
                                && said.duration_since(tool) < HANDOFF_COVERS
                                && self.asked_at.is_none_or(|asked| tool + LOOKUP_GRACE >= asked)
                        })
                    });
                let ran = std::mem::take(&mut self.turn_shell);
                let answering = std::mem::take(&mut self.reading_answer);
                let book = std::mem::take(&mut self.book_asked) && !ran;
                let work = std::mem::take(&mut self.work_asked) && !ran;
                let project = std::mem::take(&mut self.project_asked) && !ran && !answering;
                let cant = std::mem::take(&mut self.cant_said) && !ran && !answering;
                let verdict = std::mem::take(&mut self.early_verdict);
                let corrected = std::mem::take(&mut self.correction) && !ran;
                let nudge = if verdict {
                    Some(VERDICT_NUDGE)
                } else if self.said_not_started {
                    Some(BUSY_NUDGE)
                } else if self.said_cancelled && !shelled {
                    Some(CANCEL_NUDGE)
                } else if corrected {
                    Some(CORRECTION_NUDGE)
                } else if self.answer_due && !ran {
                    Some(ANSWER_NUDGE)
                } else if hanging {
                    Some(HANGING_NUDGE)
                } else if book {
                    Some(BOOK_NUDGE)
                } else if project {
                    Some(PROJECT_NUDGE)
                } else if work {
                    Some(WORK_NUDGE)
                } else if cant {
                    Some(CANT_NUDGE)
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
