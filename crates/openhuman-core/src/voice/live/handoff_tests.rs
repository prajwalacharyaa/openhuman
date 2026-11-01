use super::*;

#[test]
fn recognises_claims_but_not_questions() {
    assert!(claims_handoff("Sure thing, Claude is on it and will handle checking the branch."));
    assert!(claims_handoff("I'll ask Claude to look into that."));
    assert!(claims_handoff("I've asked Claude, it will get back to you."));
    assert!(!claims_handoff("That needs Claude. Shall I ask Claude?"));
    assert!(!claims_handoff("Today is Kartik 16, 2083."));
}

#[test]
fn nudges_once_when_the_handoff_call_is_missing() {
    let mut check = HandoffCheck::default();
    check.on_event(HandoffEvent::UserSaid);
    check.on_event(HandoffEvent::AgentSaid("Sure, Claude is on it."));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete), Some(NUDGE));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete), None, "once per claim");
}

#[test]
fn stays_quiet_when_the_handoff_happened() {
    let mut check = HandoffCheck::default();
    check.on_event(HandoffEvent::UserSaid);
    check.on_event(HandoffEvent::AgentSaid("I'll ask Claude to check that."));
    check.on_event(HandoffEvent::ToolStarted("shell"));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete), None);

    // A later turn that only talks about Claude's earlier work starts clean.
    check.on_event(HandoffEvent::UserSaid);
    check.on_event(HandoffEvent::ToolStarted("web_search_tool"));
    check.on_event(HandoffEvent::AgentSaid("Nothing new yet."));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete), None);
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
