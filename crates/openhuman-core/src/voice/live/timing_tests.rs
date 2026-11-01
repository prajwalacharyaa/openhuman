use std::time::{Duration, Instant};

use super::*;

fn pcm(level: i16, samples: usize) -> Vec<u8> {
    (0..samples).flat_map(|_| level.to_le_bytes()).collect()
}

#[test]
fn rms_and_env_switch() {
    assert_eq!(pcm16_rms(&[]), 0.0);
    assert!((pcm16_rms(&pcm(1000, 160)) - 1000.0).abs() < 0.01);
    assert!(enabled_from(Some("1")) && enabled_from(Some(" true ")));
    assert!(!enabled_from(None) && !enabled_from(Some("0")) && !enabled_from(Some("")));
}

#[test]
fn measures_a_turn_with_a_tool_and_a_barge_in() {
    let t0 = Instant::now();
    let at = |ms: u64| t0 + Duration::from_millis(ms);
    let mut timing = VoiceTiming::default();

    timing.on_client_audio(&pcm(3000, 1600), at(0));
    timing.on_client_audio(&pcm(3000, 1600), at(1000)); // last loud frame
    timing.on_client_audio(&pcm(10, 1600), at(1100)); // silence doesn't move it
    assert_eq!(
        timing.on_event(TimingEvent::UserTranscript, at(1900)),
        Some(TimingRecord::Heard { heard_ms: 900 })
    );
    assert_eq!(timing.on_event(TimingEvent::UserTranscript, at(1950)), None, "once per turn");
    assert_eq!(timing.on_event(TimingEvent::ToolStarted { call_id: "c1" }, at(2000)), None);
    assert_eq!(
        timing.on_event(TimingEvent::ToolFinished { call_id: "c1", name: "web_search_tool" }, at(4000)),
        Some(TimingRecord::Tool { name: "web_search_tool".into(), tool_ms: 2000 })
    );
    assert_eq!(
        timing.on_event(TimingEvent::AgentAudio, at(4500)),
        Some(TimingRecord::Reply { reply_ms: 3500 })
    );
    assert_eq!(timing.on_event(TimingEvent::AgentAudio, at(4600)), None, "first chunk only");

    timing.on_client_audio(&pcm(3000, 1600), at(6000)); // talking over the reply
    assert_eq!(
        timing.on_event(TimingEvent::Interrupted, at(6150)),
        Some(TimingRecord::BargeIn { barge_in_ms: 150 })
    );
}

#[test]
fn unknown_tool_and_silent_session_record_nothing() {
    let now = Instant::now();
    let mut timing = VoiceTiming::default();
    assert_eq!(timing.on_event(TimingEvent::UserTranscript, now), None);
    assert_eq!(timing.on_event(TimingEvent::ToolFinished { call_id: "x", name: "t" }, now), None);
    assert_eq!(timing.on_event(TimingEvent::Interrupted, now), None);
}
