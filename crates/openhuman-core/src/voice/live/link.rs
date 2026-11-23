//! How good the phone's line to the server is during a call (2026-11-23). Calls felt slow and answers didn't come
//! on 2026-11-22, and only a ping by hand showed the Tailscale path losing 40% of its packets. The server now pings
//! the phone over the call's own connection every [EVERY] and times the answer, so the log shows it on its own.

use std::time::{Duration, Instant};

/// How often the phone is pinged.
pub(crate) const EVERY: Duration = Duration::from_secs(15);
/// An answer slower than this, or none by the next ping, is a bad stretch worth a line in the log.
const SLOW: Duration = Duration::from_millis(1500);

#[derive(Default)]
pub(crate) struct Line {
    sent: Option<Instant>,
    times_ms: Vec<u64>,
    unanswered: u32,
}

impl Line {
    /// Time to ping: `Some(warning)` if the last ping got no answer. Call [`Line::sent`] after sending.
    pub(crate) fn ping(&mut self, now: Instant) -> Option<String> {
        let missed = self.sent.take().map(|_| {
            self.unanswered += 1;
            format!("the phone didn't answer a ping within {} s: its line is dropping packets", EVERY.as_secs())
        });
        self.sent = Some(now);
        missed
    }

    /// The phone answered: `Some(warning)` if it was slow.
    pub(crate) fn pong(&mut self, now: Instant) -> Option<String> {
        let took = now.duration_since(self.sent.take()?);
        self.times_ms.push(took.as_millis() as u64);
        (took > SLOW).then(|| format!("the phone took {} ms to answer a ping: its line is slow", took.as_millis()))
    }

    /// One line for the end of the call, or `None` if no ping went out.
    pub(crate) fn summary(&self) -> Option<String> {
        if self.times_ms.is_empty() && self.unanswered == 0 {
            return None;
        }
        let mut times = self.times_ms.clone();
        times.sort_unstable();
        let pick = |share: usize| times.get((times.len().saturating_sub(1)) * share / 100).copied().unwrap_or(0);
        Some(format!(
            "the phone's line: {} pings answered (middle {} ms, worst {} ms), {} unanswered",
            times.len(),
            pick(50),
            times.last().copied().unwrap_or(0),
            self.unanswered
        ))
    }
}

#[cfg(test)]
#[path = "link_tests.rs"]
mod tests;
