//! Stranger mode at a table (2026-11-22): December switched on stranger mode on the phone and the phone hears
//! other voices nearby. It sends Mochi only December's own voice, but December also talks to the people at the
//! table; Mochi should answer only what is for it. The phone says so with a `table` frame; while it's on, a turn
//! of December's counts as for Mochi when it names Mochi near its start, or follows Mochi's last words closely.
//! Otherwise the reply is held back: its audio and words never reach the phone or the chat.

use std::time::{Duration, Instant};

/// December speaking this soon after Mochi stopped is taken as a follow-up.
pub(crate) const FOLLOW_UP: Duration = Duration::from_secs(15);
/// "Mochi" must come within this many words of the start ("okay so Mochi, …").
const NAME_WITHIN: usize = 5;

/// Whether December's words (so far) are for Mochi.
pub(crate) fn addressed(text: &str, mochi_last_spoke: Option<Instant>, now: Instant) -> bool {
    if mochi_last_spoke.is_some_and(|at| now.duration_since(at) <= FOLLOW_UP) {
        return true;
    }
    text.split(|c: char| c.is_whitespace() || matches!(c, ',' | '.' | '!' | '?' | '।'))
        .filter(|w| !w.is_empty())
        .take(NAME_WITHIN)
        .any(|w| {
            let w = w.to_lowercase();
            // how the name comes out of the transcript: Mochi, Mochie, Mochee, Motchi, Moochi, मोची, मोचि, मची
            w.starts_with("moch") || w.starts_with("mooch") || w.starts_with("motch") || w.starts_with("मोच")
                || w.starts_with("मच")
        })
}

/// The call's table state: on/off from the phone, and whether the reply now being made is held back.
#[derive(Default)]
pub(crate) struct Table {
    pub(crate) on: bool,
    /// The current turn isn't for Mochi: its reply is dropped until December speaks again.
    pub(crate) hush: bool,
    heard: String,
    mochi_last_spoke: Option<Instant>,
}

impl Table {
    pub(crate) fn set(&mut self, on: bool) {
        self.on = on;
        if !on {
            self.hush = false;
        }
    }

    /// December's words of this turn (partial or final). Returns Some(hush) when the decision changes.
    pub(crate) fn user_said(&mut self, text: &str, is_final: bool, now: Instant) -> Option<bool> {
        if !self.on {
            return None;
        }
        self.heard.push(' ');
        self.heard.push_str(text);
        let hush = !addressed(&self.heard, self.mochi_last_spoke, now);
        if is_final {
            self.heard.clear();
        }
        (hush != self.hush).then(|| {
            self.hush = hush;
            hush
        })
    }

    /// Mochi's voice reached the phone (not a held-back reply): follow-ups count from here.
    pub(crate) fn mochi_spoke(&mut self, now: Instant) {
        if !self.hush {
            self.mochi_last_spoke = Some(now);
        }
    }
}

#[cfg(test)]
#[path = "table_tests.rs"]
mod tests;
