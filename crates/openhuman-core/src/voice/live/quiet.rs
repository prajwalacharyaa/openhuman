//! Phone control without talk (2026-11-22, December: "it keeps shouting done done… make it not speak when doing
//! that"). A phone step that worked (go home, swipe, open an app, tap, type…) is its own answer: December sees it
//! on the screen. Gemini always says something after a tool, and "say nothing" came out as "Nothing." and then
//! "Done.", so the reply to a successful step is held back here. A step that failed is spoken (why it couldn't),
//! and anything December says, or another tool, ends the quiet.

use std::collections::HashSet;

/// mochi-phone steps that change only the screen December is looking at.
const SCREEN_STEPS: &[&str] = &[
    "home", "back", "recents", "notifications", "swipe", "scroll", "app", "tap", "type", "enter", "clear", "media",
    "screen", "open", "textonly", "search",
];

/// Whether this tool call is a phone step whose success needs no words.
pub(crate) fn is_screen_step(name: &str, command: &str) -> bool {
    if name != "shell" {
        return false;
    }
    let mut words = command.split_whitespace();
    words.next() == Some("mochi-phone") && words.next().is_some_and(|step| SCREEN_STEPS.contains(&step))
}

/// The call's quiet state: on after a successful screen step, until December speaks, another tool starts or the
/// turn ends.
#[derive(Default)]
pub(crate) struct Quiet {
    steps: HashSet<String>,
    pub(crate) on: bool,
}

impl Quiet {
    pub(crate) fn tool_call(&mut self, call_id: &str, name: &str, command: &str) {
        if is_screen_step(name, command) {
            self.steps.insert(call_id.to_string());
        } else {
            self.on = false;
        }
    }

    /// Returns true when the quiet starts (for the log).
    pub(crate) fn tool_finished(&mut self, call_id: &str, is_error: bool) -> bool {
        let screen = self.steps.remove(call_id);
        let start = screen && !is_error && !self.on;
        if screen {
            self.on = !is_error;
        }
        start
    }

    pub(crate) fn end(&mut self) {
        self.on = false;
    }
}

#[cfg(test)]
#[path = "quiet_tests.rs"]
mod tests;
