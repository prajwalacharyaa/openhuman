//! Optional per-turn timing for live voice sessions (no audio, no words).
//!
//! Turned on with `OPENHUMAN_VOICE_TIMING=1`. For each turn it measures, from
//! the server's point of view:
//! - `heard_ms`: the user's last loud audio frame → the provider's transcript
//!   of it (how long end-of-speech detection + transcription took)
//! - `tool_ms`: each tool call's duration
//! - `reply_ms`: the user's last loud frame → the first reply audio
//! - `barge_in_ms`: the user starting to talk over a reply → the provider
//!   interrupting it
//!
//! Loudness comes from the 16-bit PCM the client already streams. Records are
//! returned to the caller, which logs them, so this stays a pure state machine.

use std::collections::HashMap;
use std::time::Instant;

/// Environment variable that turns timing records on.
pub(crate) const TIMING_ENV: &str = "OPENHUMAN_VOICE_TIMING";

/// RMS above which a PCM16 frame counts as the user speaking.
const SPEECH_RMS: f64 = 600.0;

/// One timing measurement.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum TimingRecord {
    Heard { heard_ms: u128 },
    Tool { name: String, tool_ms: u128 },
    Reply { reply_ms: u128 },
    BargeIn { barge_in_ms: u128 },
}

/// What the timing tracker needs to know about a session event.
pub(crate) enum TimingEvent<'a> {
    UserTranscript,
    ToolStarted { call_id: &'a str },
    ToolFinished { call_id: &'a str, name: &'a str },
    AgentAudio,
    Interrupted,
}

/// Whether `OPENHUMAN_VOICE_TIMING` asks for timing records.
pub(crate) fn enabled_from(raw: Option<&str>) -> bool {
    matches!(raw.map(str::trim), Some("1" | "true" | "yes" | "on"))
}

/// RMS loudness of a little-endian PCM16 buffer.
pub(crate) fn pcm16_rms(pcm: &[u8]) -> f64 {
    let samples = pcm.len() / 2;
    if samples == 0 {
        return 0.0;
    }
    let sum: f64 = pcm
        .chunks_exact(2)
        .map(|b| f64::from(i16::from_le_bytes([b[0], b[1]])).powi(2))
        .sum();
    (sum / samples as f64).sqrt()
}

/// Per-session timing state.
#[derive(Debug, Default)]
pub(crate) struct VoiceTiming {
    last_loud: Option<Instant>,
    heard_logged: bool,
    awaiting_reply: bool,
    agent_speaking: bool,
    talk_over_since: Option<Instant>,
    tools: HashMap<String, Instant>,
}

impl VoiceTiming {
    /// A client audio frame arrived at `now`.
    pub(crate) fn on_client_audio(&mut self, pcm: &[u8], now: Instant) {
        if pcm16_rms(pcm) < SPEECH_RMS {
            return;
        }
        self.last_loud = Some(now);
        self.heard_logged = false;
        self.awaiting_reply = true;
        if self.agent_speaking && self.talk_over_since.is_none() {
            self.talk_over_since = Some(now);
        }
    }

    /// A session event at `now`; returns a record when it completes a measurement.
    pub(crate) fn on_event(&mut self, event: TimingEvent<'_>, now: Instant) -> Option<TimingRecord> {
        let since_loud = |t: Option<Instant>| t.map(|t| now.duration_since(t).as_millis());
        match event {
            TimingEvent::UserTranscript if !self.heard_logged => {
                self.heard_logged = true;
                since_loud(self.last_loud).map(|heard_ms| TimingRecord::Heard { heard_ms })
            }
            TimingEvent::UserTranscript => None,
            TimingEvent::ToolStarted { call_id } => {
                self.tools.insert(call_id.to_string(), now);
                None
            }
            TimingEvent::ToolFinished { call_id, name } => {
                self.tools.remove(call_id).map(|start| TimingRecord::Tool {
                    name: name.to_string(),
                    tool_ms: now.duration_since(start).as_millis(),
                })
            }
            TimingEvent::AgentAudio => {
                self.agent_speaking = true;
                if !self.awaiting_reply {
                    return None;
                }
                self.awaiting_reply = false;
                since_loud(self.last_loud).map(|reply_ms| TimingRecord::Reply { reply_ms })
            }
            TimingEvent::Interrupted => {
                self.agent_speaking = false;
                since_loud(self.talk_over_since.take())
                    .map(|barge_in_ms| TimingRecord::BargeIn { barge_in_ms })
            }
        }
    }
}

#[cfg(test)]
#[path = "timing_tests.rs"]
mod tests;
