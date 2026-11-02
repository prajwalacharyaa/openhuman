use super::*;

#[test]
fn recognises_claims_but_not_questions() {
    assert!(claims_handoff("Sure thing, Claude is on it and will handle checking the branch."));
    assert!(claims_handoff("I'll ask Claude to look into that."));
    assert!(claims_handoff("I've asked Claude, it will get back to you."));
    assert!(!claims_handoff("That needs Claude. Shall I ask Claude?"));
    assert!(!claims_handoff("Claude is still working on opening the website for you."));
    assert!(!claims_handoff("Today is Kartik 16, 2083."));
}

#[test]
fn nudges_once_when_the_handoff_call_is_missing() {
    let t0 = std::time::Instant::now();
    let mut check = HandoffCheck::default();
    check.on_event(HandoffEvent::UserSaid, t0);
    check.on_event(HandoffEvent::AgentSaid("Sure, Claude is on it."), t0);
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, t0), Some(NUDGE));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, t0), None, "once per claim");

    // the model answers the nudge with another claim and no call: no second nudge (that looped before)
    check.on_event(HandoffEvent::AgentSaid("I've sent that to Claude, it will handle it."), t0);
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, t0), None, "once until December speaks again");

    // December speaks again: the check is armed again
    check.on_event(HandoffEvent::UserSaid, t0);
    check.on_event(HandoffEvent::AgentSaid("Claude will do it."), t0);
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, t0), Some(NUDGE));
}

#[test]
fn stays_quiet_when_the_handoff_happened() {
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut check = HandoffCheck::default();
    check.on_event(HandoffEvent::UserSaid, t0);
    check.on_event(HandoffEvent::AgentSaid("I'll ask Claude to check that."), t0);
    check.on_event(HandoffEvent::ToolStarted("shell"), t0);
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, t0), None);

    // Gemini finalised December's words only after it started the tool: still covered (this nudged before)
    check.on_event(HandoffEvent::ToolStarted("shell"), later(10));
    check.on_event(HandoffEvent::UserSaid, later(11));
    check.on_event(HandoffEvent::AgentSaid("I've asked Claude to check the traffic."), later(12));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(13)), None);

    // long after any handoff, a bare claim is caught again
    check.on_event(HandoffEvent::UserSaid, later(300));
    check.on_event(HandoffEvent::AgentSaid("Claude will handle it."), later(301));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(302)), Some(NUDGE));
}

#[test]
fn passes_back_only_this_calls_claude_answers() {
    let done = |client: &str, thread: &str| WebChannelEvent {
        event: "chat_done".into(),
        client_id: client.into(),
        thread_id: thread.into(),
        full_response: Some("Yes, it's merged in pull request 28.".into()),
        ..Default::default()
    };
    let note = claude_reply_note(&done("mochi-ask", "t1"), "t1").expect("a note");
    assert!(note.ends_with("Yes, it's merged in pull request 28."));
    assert_eq!(claude_reply_note(&done("mochi-ask", "t2"), "t1"), None, "another chat");
    assert_eq!(claude_reply_note(&done("desktop", "t1"), "t1"), None, "a typed chat");

    let failed = WebChannelEvent {
        event: "chat_error".into(),
        client_id: "mochi-ask".into(),
        thread_id: "t1".into(),
        message: Some("rate limited".into()),
        ..Default::default()
    };
    assert!(claude_reply_note(&failed, "t1").unwrap().contains("couldn't finish the request you handed it: rate limited"));
}
