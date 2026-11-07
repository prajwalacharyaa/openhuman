//! Catches the live model saying something happened when it didn't.
//!
//! The word lists in [`super::handoff`] only catch phrasings someone wrote down ("I've installed…", "Claude is
//! on it"); a realtime model keeps finding new ones ("I'm working on the backend filters", "the new voice is
//! active now"). This check reads the meaning instead: after each model turn, a small, fast text model gets
//! what December said, what the voice model said, which tools ran and what the back office (Claude, through
//! `mochi-ask`) is doing or last answered, and says whether the turn claimed something nothing backs. The
//! caller then has the voice model correct itself at once, and every catch goes to
//! `<workspace>/voice-claims.log` so a repeat is visible.
//!
//! The check runs on Gemini (the same Google key the call already uses, so no new provider sees the call). It
//! fails open: no key, a timeout or an odd reply means no correction, never a stuck call. Turns that read
//! December's phone are never sent ([`TurnFacts::private`]).

use std::path::Path;
use std::time::Duration;

use serde_json::{json, Value};

/// Overrides the checking model (a Gemini text model).
pub(crate) const CLAIM_MODEL_ENV: &str = "OPENHUMAN_VOICE_CLAIM_MODEL";
const DEFAULT_MODEL: &str = "gemini-3.5-flash-lite";
const ENDPOINT: &str = "https://generativelanguage.googleapis.com/v1beta/models";
const TIMEOUT: Duration = Duration::from_secs(6);
/// Below this the check stays quiet: a wrong correction interrupts December for nothing.
const MIN_CONFIDENCE: f64 = 0.8;
const MAX_SAID: usize = 1500;
const MAX_ANSWER: usize = 2000;
/// Log file in the workspace, one line per catch.
pub(crate) const CLAIMS_LOG: &str = "voice-claims.log";

/// What happened in one model turn.
#[derive(Debug, Default, Clone, PartialEq)]
pub(crate) struct TurnFacts {
    pub(crate) user_said: String,
    pub(crate) agent_said: String,
    pub(crate) tools: Vec<String>,
    /// The back office is working on a request from this call right now.
    pub(crate) back_office_working: bool,
    /// The back office's latest answer in this call, if any.
    pub(crate) back_office_answer: Option<String>,
    /// The turn read December's phone: never sent anywhere.
    pub(crate) private: bool,
}

/// Collects [`TurnFacts`] from the call's events.
#[derive(Debug, Default)]
pub(crate) struct TurnRecorder {
    facts: TurnFacts,
    user_turn: bool,
    /// Bumped each time December starts speaking: a verdict for an older turn is dropped, so a correction
    /// never talks over December.
    generation: u64,
}

impl TurnRecorder {
    pub(crate) fn user_said(&mut self, text: &str) {
        if !self.user_turn {
            self.user_turn = true;
            self.generation += 1;
            self.facts.user_said.clear();
            self.facts.agent_said.clear();
            self.facts.tools.clear();
            self.facts.private = false;
        }
        push_line(&mut self.facts.user_said, text);
    }

    pub(crate) fn agent_said(&mut self, text: &str) {
        self.user_turn = false;
        push_line(&mut self.facts.agent_said, text);
    }

    pub(crate) fn tool_started(&mut self, name: &str) {
        self.facts.tools.push(name.to_string());
    }

    pub(crate) fn private(&mut self) {
        self.facts.private = true;
    }

    /// A `mochi-ask` request from this call started (true) or ended (false, with its answer).
    pub(crate) fn back_office(&mut self, working: bool, answer: Option<&str>) {
        self.facts.back_office_working = working;
        if let Some(answer) = answer.map(str::trim).filter(|a| !a.is_empty()) {
            self.facts.back_office_answer = Some(answer.chars().take(MAX_ANSWER).collect());
        }
    }

    pub(crate) fn generation(&self) -> u64 {
        self.generation
    }

    /// The model's turn ended: what to check (nothing for a silent or private turn). The next model turn
    /// starts fresh, but keeps December's words until December speaks again.
    pub(crate) fn turn_over(&mut self) -> Option<(u64, TurnFacts)> {
        self.user_turn = false;
        let facts = self.facts.clone();
        self.facts.agent_said.clear();
        self.facts.tools.clear();
        (!facts.agent_said.trim().is_empty() && !facts.private).then_some((self.generation, facts))
    }
}

fn push_line(into: &mut String, text: &str) {
    let text = text.trim();
    if text.is_empty() {
        return;
    }
    if !into.is_empty() {
        into.push(' ');
    }
    into.push_str(text);
    if into.len() > MAX_SAID * 2 {
        let cut = into.len() - MAX_SAID;
        let cut = (cut..into.len()).find(|i| into.is_char_boundary(*i)).unwrap_or(into.len());
        into.drain(..cut);
    }
}

/// The check's answer.
#[derive(Debug, Clone, PartialEq)]
pub(crate) enum Verdict {
    Ok,
    /// Said it is on something December asked for this turn, but handed nothing on.
    MissingHandoff,
    /// Said something was done, is being done or is true, and nothing backs it.
    FalseClaim { claim: String, truth: String },
}

impl Verdict {
    pub(crate) fn kind(&self) -> &'static str {
        match self {
            Verdict::Ok => "ok",
            Verdict::MissingHandoff => "missing_handoff",
            Verdict::FalseClaim { .. } => "false_claim",
        }
    }
}

/// The note that makes the voice model correct a false claim.
pub(crate) fn correction_note(claim: &str, truth: &str) -> String {
    format!(
        "(Server check, not December speaking: you just said \"{claim}\", but that didn't happen: {truth}. \
         Correct it now in one short sentence that starts with \"Correction:\", and offer the real next step \
         as a question if there is one. Say nothing else, and don't start anything December didn't ask for.)"
    )
}

fn clip(text: &str, max: usize) -> String {
    let text = text.trim();
    if text.chars().count() <= max {
        text.to_string()
    } else {
        format!("{}…", text.chars().take(max).collect::<String>())
    }
}

/// The instructions for the checking model.
pub(crate) fn check_prompt(facts: &TurnFacts) -> String {
    let tools = if facts.tools.is_empty() { "none".to_string() } else { facts.tools.join(", ") };
    let answer = facts.back_office_answer.as_deref().map(|a| clip(a, MAX_ANSWER)).unwrap_or_else(|| "none yet".into());
    format!(
        "You check one spoken turn of Mochi, a voice assistant, for claims that nothing backs.\n\
         Mochi's voice side can itself only search the web, set reminders, use December's phone tools and hand \
         work to its back office (the handoff is a `shell` tool call). It cannot change, install, build, fix, \
         switch or set up anything itself: the back office does that and reports back in its answer.\n\n\
         What happened:\n\
         - December said: \"{user}\"\n\
         - Mochi said: \"{agent}\"\n\
         - Tools Mochi ran in this turn: {tools}\n\
         - The back office is working on a request right now: {working}\n\
         - The back office's latest answer: \"{answer}\"\n\n\
         Verdicts:\n\
         - FALSE_CLAIM: Mochi states as fact that something was done, is being done, has started, has changed or \
         is now true (code, projects, pull requests, settings, its voice, files, the server, December's accounts), \
         and nothing above backs it: no tool in this turn did it and the back office's latest answer doesn't say \
         it. Also FALSE_CLAIM: saying it is working on something December did not ask for in this turn.\n\
         - MISSING_HANDOFF: December asked for work in this turn, Mochi says it is on it, looking into it or will \
         get back, but ran no tool in this turn and the back office isn't working on anything.\n\
         - OK: everything else. Repeating or summarising the back office's answer is OK, even in the first person \
         (\"I've opened pull request 13\" when the answer says so). Questions, offers (\"Want me to…?\"), plans \
         December confirmed, apologies, small talk and answers from a tool run in this turn are OK. When unsure, OK.\n\n\
         Reply with JSON only: {{\"verdict\": \"OK\" | \"FALSE_CLAIM\" | \"MISSING_HANDOFF\", \"claim\": \"Mochi's \
         exact words that are wrong, empty for OK\", \"truth\": \"what is actually true, in a few plain words\", \
         \"confidence\": 0.0 to 1.0}}",
        user = clip(&facts.user_said, MAX_SAID),
        agent = clip(&facts.agent_said, MAX_SAID),
        working = if facts.back_office_working { "yes" } else { "no" },
    )
}

/// Reads the checking model's JSON; anything unexpected or unsure is [`Verdict::Ok`].
pub(crate) fn parse_verdict(raw: &str) -> Verdict {
    let raw = raw.trim().trim_start_matches("```json").trim_start_matches("```").trim_end_matches("```").trim();
    let Ok(value) = serde_json::from_str::<Value>(raw) else {
        return Verdict::Ok;
    };
    let confidence = value.get("confidence").and_then(Value::as_f64).unwrap_or(0.0);
    if confidence < MIN_CONFIDENCE {
        return Verdict::Ok;
    }
    let text = |key: &str| value.get(key).and_then(Value::as_str).unwrap_or_default().trim().to_string();
    match text("verdict").to_uppercase().as_str() {
        "MISSING_HANDOFF" => Verdict::MissingHandoff,
        "FALSE_CLAIM" => {
            let claim = clip(&text("claim"), 200);
            if claim.is_empty() {
                return Verdict::Ok;
            }
            let truth = text("truth");
            let truth = if truth.is_empty() { "nothing was run for it".to_string() } else { clip(&truth, 200) };
            Verdict::FalseClaim { claim, truth }
        }
        _ => Verdict::Ok,
    }
}

fn model() -> String {
    std::env::var(CLAIM_MODEL_ENV).ok().filter(|m| !m.trim().is_empty()).unwrap_or_else(|| DEFAULT_MODEL.into())
}

/// Asks the checking model about one turn. Fails open.
pub(crate) async fn check(client: &reqwest::Client, key: &str, facts: &TurnFacts) -> Verdict {
    if facts.private || facts.agent_said.trim().is_empty() {
        return Verdict::Ok;
    }
    let body = json!({
        "contents": [{"role": "user", "parts": [{"text": check_prompt(facts)}]}],
        "generationConfig": {"temperature": 0, "maxOutputTokens": 300, "responseMimeType": "application/json"}
    });
    let url = format!("{ENDPOINT}/{}:generateContent", model());
    let reply = client.post(url).header("x-goog-api-key", key).timeout(TIMEOUT).json(&body).send().await;
    let reply = match reply {
        Ok(r) if r.status().is_success() => r,
        Ok(r) => {
            tracing::warn!(status = %r.status(), "[voice-live] claim check: the model refused");
            return Verdict::Ok;
        }
        Err(e) => {
            tracing::warn!(error = %e, "[voice-live] claim check: no answer");
            return Verdict::Ok;
        }
    };
    let Ok(value) = reply.json::<Value>().await else {
        return Verdict::Ok;
    };
    let text = value
        .pointer("/candidates/0/content/parts/0/text")
        .and_then(Value::as_str)
        .unwrap_or_default();
    parse_verdict(text)
}

/// Appends a catch to the workspace log (what was said and what was true; never December's words).
pub(crate) async fn log_catch(workspace: &Path, session_id: &str, verdict: &Verdict) {
    let line = match verdict {
        Verdict::Ok => return,
        Verdict::MissingHandoff => format!("{} {session_id} missing_handoff\n", stamp()),
        Verdict::FalseClaim { claim, truth } => {
            format!("{} {session_id} false_claim said={claim:?} truth={truth:?}\n", stamp())
        }
    };
    use tokio::io::AsyncWriteExt;
    let path = workspace.join(CLAIMS_LOG);
    if let Ok(mut file) = tokio::fs::OpenOptions::new().create(true).append(true).open(&path).await {
        let _ = file.write_all(line.as_bytes()).await;
    }
}

fn stamp() -> String {
    chrono::Utc::now().format("%Y-%m-%dT%H:%M:%SZ").to_string()
}

#[cfg(test)]
#[path = "claims_tests.rs"]
mod tests;
