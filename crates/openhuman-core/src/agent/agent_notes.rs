//! Mochi's agents' own notes (`~/OpenHuman/projects/agent-notes/<agent>/`): what one agent remembers of December.
//!
//! December's choice (2026-11-14): what they tell an agent in a personal talk is remembered by that agent only, in
//! its notes, never in Mochi's shared memory. So a call with an agent hears its notes at the start, its words go to
//! the agent's `calls/` folder (one file a day; Claude, working as that agent, folds what matters into `MEMORY.md`),
//! and neither a call nor a typed chat with an agent is logged into the shared memory. Plain files, no AI.

use std::io::Write;
use std::path::{Path, PathBuf};

/// Most of the notes a call hears at its start.
const NOTES_BYTES: usize = 8_000;
/// Most of the latest call days a call hears (their ends: the newest words).
const CALLS_BYTES: usize = 3_000;
/// Call days kept in `calls/`.
const KEEP_CALL_DAYS: usize = 14;

/// The agent's notes folder. `workspace_dir` is `~/OpenHuman/workspace`.
pub(crate) fn notes_dir(workspace_dir: &Path, agent: &str) -> PathBuf {
    workspace_dir.parent().unwrap_or(workspace_dir).join("projects/agent-notes").join(agent)
}

fn valid(agent: &str) -> bool {
    !agent.is_empty()
        && agent.len() <= 40
        && agent.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-' || c == '_')
}

/// The agent a chat belongs to (its `agent:<name>` label in the thread index), if it is one of the agents' chats.
pub(crate) fn thread_agent(workspace_dir: &Path, thread: &str) -> Option<String> {
    let text = std::fs::read_to_string(workspace_dir.join("memory/conversations/threads.jsonl")).ok()?;
    let mut labels = None;
    for line in text.lines().filter(|line| line.contains(thread)) {
        let Ok(entry) = serde_json::from_str::<serde_json::Value>(line) else { continue };
        if entry.get("thread_id").and_then(serde_json::Value::as_str) != Some(thread) {
            continue;
        }
        if let Some(list) = entry.get("labels").and_then(serde_json::Value::as_array) {
            labels = Some(list.iter().filter_map(serde_json::Value::as_str).map(str::to_string).collect::<Vec<_>>());
        }
    }
    labels?
        .iter()
        .find_map(|label| label.strip_prefix("agent:").map(str::to_string))
        .filter(|name| valid(name))
}

fn tail(text: &str, max: usize) -> &str {
    if text.len() <= max {
        return text;
    }
    let mut start = text.len() - max;
    while !text.is_char_boundary(start) {
        start += 1;
    }
    &text[start..]
}

fn head(text: &str, max: usize) -> &str {
    if text.len() <= max {
        return text;
    }
    let mut end = max;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

/// What a call with `agent` starts with: its notes and the end of its latest call days.
pub(crate) fn call_block(workspace_dir: &Path, agent: &str) -> Option<String> {
    if !valid(agent) {
        return None;
    }
    let dir = notes_dir(workspace_dir, agent);
    let notes = std::fs::read_to_string(dir.join("MEMORY.md")).unwrap_or_default();
    let mut days: Vec<PathBuf> = std::fs::read_dir(dir.join("calls"))
        .map(|entries| entries.filter_map(|e| e.ok().map(|e| e.path())).filter(|p| p.extension().is_some_and(|x| x == "md")).collect())
        .unwrap_or_default();
    days.sort();
    let mut calls = String::new();
    for day in days.iter().rev().take(3).rev() {
        if let Ok(text) = std::fs::read_to_string(day) {
            calls.push_str(&format!("{}:\n{}\n", day.file_stem().and_then(|s| s.to_str()).unwrap_or(""), text.trim()));
        }
    }
    let (notes, calls) = (head(notes.trim(), NOTES_BYTES), tail(calls.trim(), CALLS_BYTES));
    if notes.is_empty() && calls.is_empty() {
        return None;
    }
    let mut out = String::from("What you remember of December (your own notes, only yours):\n");
    out.push_str(if notes.is_empty() { "(no notes yet)" } else { notes });
    if !calls.is_empty() {
        out.push_str("\n\nThe end of your latest calls with December:\n");
        out.push_str(calls);
    }
    Some(out)
}

/// Appends one spoken line of a call with `agent` to today's file in its `calls/` folder (and keeps the newest
/// [`KEEP_CALL_DAYS`] days). Failures are logged, never fatal to the call.
pub(crate) fn record_call_line(workspace_dir: &Path, agent: &str, speaker: &str, text: &str) {
    if !valid(agent) || text.trim().is_empty() {
        return;
    }
    let dir = notes_dir(workspace_dir, agent).join("calls");
    let now = chrono::Local::now();
    let line = format!("{} {speaker}: {}\n", now.format("%H:%M"), text.split_whitespace().collect::<Vec<_>>().join(" "));
    let written = std::fs::create_dir_all(&dir).and_then(|_| {
        std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(dir.join(format!("{}.md", now.format("%Y-%m-%d"))))
            .and_then(|mut file| file.write_all(line.as_bytes()))
    });
    if let Err(error) = written {
        tracing::warn!(agent, "[agent-notes] couldn't save a call line: {error}");
        return;
    }
    if let Ok(entries) = std::fs::read_dir(&dir) {
        let mut days: Vec<PathBuf> = entries.filter_map(|e| e.ok().map(|e| e.path())).collect();
        days.sort();
        for old in days.iter().take(days.len().saturating_sub(KEEP_CALL_DAYS)) {
            let _ = std::fs::remove_file(old);
        }
    }
}

#[cfg(test)]
#[path = "agent_notes_tests.rs"]
mod tests;
