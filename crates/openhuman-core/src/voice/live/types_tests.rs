use super::*;
use serde_json::json;

#[test]
fn client_frames_use_snake_case_tags_and_defaults() {
    let start: ClientFrame = serde_json::from_value(json!({"type": "start"})).unwrap();
    assert_eq!(
        start,
        ClientFrame::Start {
            provider: None,
            thread_id: None,
            client_id: None,
            input_sample_rate: 16_000,
            agent: None,
            dry_run: false,
            reconnect: false,
            long_answers: false,
        }
    );
    let text: ClientFrame = serde_json::from_value(json!({"type": "text", "text": "hi"})).unwrap();
    assert_eq!(text, ClientFrame::Text { text: "hi".into() });
    assert_eq!(
        serde_json::from_value::<ClientFrame>(json!({"type": "interrupt"})).unwrap(),
        ClientFrame::Interrupt
    );
    assert!(serde_json::from_value::<ClientFrame>(json!({"type": "nope"})).is_err());
}

#[test]
fn server_frames_serialize_the_documented_shape() {
    let frame = ServerFrame::Transcript {
        role: TranscriptRole::Agent,
        text: "hi".into(),
        is_final: true,
    };
    assert_eq!(
        serde_json::to_value(frame).unwrap(),
        json!({"type": "transcript", "role": "agent", "text": "hi", "final": true})
    );
    assert_eq!(
        serde_json::to_value(ServerFrame::TurnComplete).unwrap(),
        json!({"type": "turn_complete"})
    );
    assert_eq!(
        serde_json::to_value(ServerFrame::ToolFinished {
            call_id: "c".into(),
            name: "n".into(),
            ok: false,
            cancelled: true
        })
        .unwrap(),
        json!({"type": "tool_finished", "call_id": "c", "name": "n", "ok": false, "cancelled": true})
    );
}

/// Stranger mode's table frame from the phone (an older engine ignores it as an unknown frame).
#[test]
fn the_phone_says_when_other_voices_are_near() {
    let on: ClientFrame = serde_json::from_str(r#"{"type":"table","on":true}"#).unwrap();
    assert_eq!(on, ClientFrame::Table { on: true });
}
