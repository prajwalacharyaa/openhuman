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
//!
//! It also picks each chat's model (routing, on the default Sonnet brain only): December's own words choose
//! one ("quick mode", "do it fast" → Haiku; "take your time", "think hard" → Opus; "normal mode" → Sonnet),
//! and that holds for the chat until changed or the session goes fresh. Otherwise a chat that opens with a
//! short, simple message runs on Haiku and anything else on Sonnet; a Haiku chat that gets real work moves
//! up to Sonnet. The model only ever changes together with a fresh session: switching inside a session would
//! throw away its prompt cache and cost more than it saves.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, SystemTime};

use tinyinference_llm::{ChatModel, InputModality, InputSource, ModelRequest, ModelResponse, ModelStream};

/// A session whose last call read more than this many tokens starts fresh on the next turn.
pub(crate) const MAX_CONTEXT_TOKENS: u64 = 100_000;
/// A session unused this long starts fresh (the old one's details are stale anyway).
pub(crate) const IDLE_FRESH: Duration = Duration::from_secs(6 * 3600);
/// Chat messages a fresh session is opened with (the provider folds them into its first message). OpenHuman
/// sends the whole chat, which on a long day is itself tens of thousands of tokens; memory recall covers older.
pub(crate) const FRESH_HISTORY: usize = 6;
/// Claude Code model aliases a chat is routed between.
pub(crate) const QUICK: &str = "haiku";
pub(crate) const NORMAL: &str = "sonnet";
pub(crate) const DEEP: &str = "opus";
/// December's words that pick a model, checked in this order (most specific first).
const NORMAL_WORDS: &[&str] = &["normal mode", "back to normal", "use sonnet"];
const DEEP_WORDS: &[&str] = &[
    "take your time", "take time", "think hard", "think deeply", "think carefully", "think it through",
    "deep mode", "deep dive", "use opus", "समय लिएर",
];
const QUICK_WORDS: &[&str] = &[
    "quick mode", "fast mode", "be quick", "quickly", "do it fast", "make it fast", "quick question",
    "quick one", "use haiku", "छिटो",
];
/// Words that mark real work (code, projects, research, writing): never a Haiku chat on its own.
const WORK_WORDS: &[&str] = &[
    "code", "fix", "bug", "build", "repo", "github", "pull request", "deploy", "install", "server", "project",
    "research", "analy", "plan", "design", "write", "draft", "compare", "debug", "error", "script", "config",
    "review", "explain", "why ", "how do", "how does", "how to", "report", "browse", "website", "portfolio",
    "redesign", "graphify", "jigubites", "test", "decide", "decision",
];
/// Longest message (characters) that can open a Haiku chat on its own.
const LIGHT_CHARS: usize = 160;
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
    /// thread → the model its current session runs on and whether December chose it.
    routes_file: PathBuf,
    /// Pick a model per chat (only for the default Sonnet brain; coding and memory roles keep theirs).
    route: bool,
    lock: Mutex<()>,
}

impl SessionCap {
    pub(crate) fn new(inner: Arc<dyn ChatModel<()>>, workspace: &Path, route: bool) -> Self {
        let home = std::env::var_os("HOME").map(PathBuf::from).unwrap_or_default();
        Self::with_paths(inner, workspace, home.join(".claude").join("projects")).routed(route)
    }

    /// Turns per-chat model routing on or off.
    pub(crate) fn routed(mut self, route: bool) -> Self {
        self.route = route;
        self
    }

    pub(crate) fn with_paths(inner: Arc<dyn ChatModel<()>>, workspace: &Path, transcripts: PathBuf) -> Self {
        Self {
            inner,
            sessions_file: workspace.join("claude-code-sessions.json"),
            fresh_file: workspace.join("claude-session-fresh.json"),
            transcripts,
            routes_file: workspace.join("claude-session-model.json"),
            route: false,
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
        // the model this chat should run on; a different one than its session's needs a fresh session
        let route = self.route.then(|| self.pick_model(&thread, &request, session.is_none() || stale));
        let switch = route.as_ref().is_some_and(|r| r.switch) && session.is_some();
        let stale = stale || switch;
        if let Some(route) = route {
            tracing::info!(thread = %thread, model = route.model, chosen = route.chosen, "[claude-code] chat model");
            request.model = Some(route.model.to_string());
        }
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

    /// Chooses the chat's model and saves it. `new_session`: the session is starting fresh anyway.
    fn pick_model(&self, thread: &str, request: &ModelRequest, new_session: bool) -> Route {
        let text = latest_user_words(request);
        let mut routes = read_json(&self.routes_file);
        let current = routes.get(thread).and_then(|r| r.get("model")).and_then(serde_json::Value::as_str);
        let current = [QUICK, NORMAL, DEEP].into_iter().find(|m| Some(*m) == current);
        // December's choice holds until the session goes fresh
        let was_chosen = !new_session && routes.get(thread).and_then(|r| r.get("chosen")).and_then(serde_json::Value::as_bool) == Some(true);
        let (model, chosen) = match (asked_model(&text), current) {
            (Some(asked), _) => (asked, true),
            (None, Some(current)) if !new_session && was_chosen => (current, true),
            (None, Some(QUICK)) if !new_session => (if is_light(&text) { QUICK } else { NORMAL }, false),
            (None, Some(current)) if !new_session => (current, false),
            (None, _) => (if is_light(&text) { QUICK } else { NORMAL }, false),
        };
        let switch = !new_session && current.is_some_and(|c| c != model);
        routes[thread] = serde_json::json!({ "model": model, "chosen": chosen });
        if let Err(error) = write_json(&self.routes_file, &routes) {
            tracing::warn!(%error, "[claude-code] couldn't save the chat's model");
        }
        Route { model, chosen, switch }
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

/// A chat's model for this turn.
struct Route {
    model: &'static str,
    /// December asked for it in words.
    chosen: bool,
    /// Differs from the model the chat's session runs on.
    switch: bool,
}

/// December's own words in the latest user message: the memory pack OpenHuman appends is not theirs.
pub(crate) fn latest_user_words(request: &ModelRequest) -> String {
    let text = request
        .messages
        .iter()
        .rev()
        .find(|m| matches!(m, tinyinference_llm::Message::User(_)))
        .map(tinyinference_llm::Message::text)
        .unwrap_or_default();
    let own = text.split(crate::memory::lifecycle::hooks::OPEN_TAG).next().unwrap_or_default();
    own.trim().to_lowercase()
}

/// The model December asked for in so many words, if any.
pub(crate) fn asked_model(words: &str) -> Option<&'static str> {
    let says = |list: &[&str]| list.iter().any(|w| words.contains(w));
    if says(NORMAL_WORDS) {
        Some(NORMAL)
    } else if says(DEEP_WORDS) {
        Some(DEEP)
    } else if says(QUICK_WORDS) {
        Some(QUICK)
    } else {
        None
    }
}

/// A short, simple message (a greeting, a quick fact, a reminder) that Haiku answers as well as Sonnet.
pub(crate) fn is_light(words: &str) -> bool {
    !words.is_empty()
        && words.chars().count() <= LIGHT_CHARS
        && !words.contains("```")
        && !WORK_WORDS.iter().any(|w| words.contains(w))
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
