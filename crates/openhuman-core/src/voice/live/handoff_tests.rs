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
    check.on_event(HandoffEvent::UserSaid(""), t0);
    check.on_event(HandoffEvent::AgentSaid("Sure, Claude is on it."), t0);
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, t0), Some(NUDGE));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, t0), None, "once per claim");

    // the model answers the nudge with another claim and no call: no second nudge (that looped before)
    check.on_event(HandoffEvent::AgentSaid("I've sent that to Claude, it will handle it."), t0);
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, t0), None, "once until December speaks again");

    // December speaks again: the check is armed again
    check.on_event(HandoffEvent::UserSaid(""), t0);
    check.on_event(HandoffEvent::AgentSaid("Claude will do it."), t0);
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, t0), Some(NUDGE));
}

#[test]
fn stays_quiet_when_the_handoff_happened() {
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut check = HandoffCheck::default();
    check.on_event(HandoffEvent::UserSaid(""), t0);
    check.on_event(HandoffEvent::AgentSaid("I'll ask Claude to check that."), t0);
    check.on_event(HandoffEvent::ToolStarted("shell"), t0);
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, t0), None);

    // Gemini finalised December's words only after it started the tool: still covered (this nudged before)
    check.on_event(HandoffEvent::ToolStarted("shell"), later(10));
    check.on_event(HandoffEvent::UserSaid(""), later(11));
    check.on_event(HandoffEvent::AgentSaid("I've asked Claude to check the traffic."), later(12));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(13)), None);

    // long after any handoff, a bare claim is caught again
    check.on_event(HandoffEvent::UserSaid(""), later(300));
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

#[test]
fn notes_wait_until_the_model_stops_speaking() {
    let mut gate = NoteGate::default();
    assert_eq!(gate.offer("first".into()), Some("first".into()), "quiet: straight through");
    gate.agent_audio();
    assert_eq!(gate.offer("second".into()), None, "mid-sentence: held");
    assert_eq!(gate.offer("third".into()), None);
    assert_eq!(gate.turn_over(), Some("second\n\nthird".into()));
    assert_eq!(gate.turn_over(), None);
}

#[test]
fn knows_fresh_questions() {
    assert!(asks_fresh("Can you tell me the news?"));
    assert!(asks_fresh("Tell me highlights of Nepali news."));
    assert!(asks_fresh("पनि नेपालीमा न्युज"));
    assert!(asks_fresh("What's the NEPSE index today?"));
    assert!(!asks_fresh("Can you look into previous conversation for yesterday's news?"));
    assert!(!asks_fresh("Can you tell me today's date in Nepali?"));
    assert!(announces_lookup("Checking…"));
    assert!(!announces_lookup("Today is Kartik 17, 2083."));
}

#[test]
fn nudges_a_fresh_answer_given_without_a_lookup() {
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut check = LookupCheck::default();
    check.on_event(HandoffEvent::UserSaid("Tell me highlights of Nepali news."), t0);
    check.on_event(HandoffEvent::AgentSaid("The mayor of Kathmandu warned…"), later(1));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(2)), Some(LOOKUP_NUDGE));

    // its answer to the nudge doesn't draw another one, even without a search
    check.on_event(HandoffEvent::AgentSaid("Checking the latest…"), later(3));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(4)), None);

    // "checking…" with no tool call is caught too
    check.on_event(HandoffEvent::UserSaid("What about Kathmandu?"), later(60));
    check.on_event(HandoffEvent::AgentSaid("Checking…"), later(61));
    check.on_event(HandoffEvent::AgentSaid("Air quality is moderate today."), later(62));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(63)), Some(LOOKUP_NUDGE));
}

#[test]
fn stays_quiet_when_it_looked_or_nothing_current_was_asked() {
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut check = LookupCheck::default();
    check.on_event(HandoffEvent::UserSaid("Can you tell me the news?"), t0);
    check.on_event(HandoffEvent::ToolStarted("web_search_tool"), later(1));
    check.on_event(HandoffEvent::AgentSaid("Americans are heading to the polls…"), later(5));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(6)), None);

    // the search started before December's words were finalised: still counts
    check.on_event(HandoffEvent::ToolStarted("web_search_tool"), later(100));
    check.on_event(HandoffEvent::UserSaid("Latest share market news?"), later(102));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(108)), None);

    // the request's words arrive in two parts: still one request, still needs a lookup
    check.on_event(HandoffEvent::UserSaid("Tell me the"), later(200));
    check.on_event(HandoffEvent::UserSaid("weather"), later(201));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(203)), Some(LOOKUP_NUDGE));

    // nothing current asked
    check.on_event(HandoffEvent::UserSaid("How are you?"), later(300));
    check.on_event(HandoffEvent::AgentSaid("I'm well, thanks!"), later(301));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(302)), None);
}

#[test]
fn knows_made_up_actions_and_giving_in() {
    assert!(claims_done("I have successfully installed Graphify on your server."));
    assert!(claims_done("Understood, I’ve activated Ponytail mode."));
    assert!(!claims_done("Claude has installed Graphify and updated the rules."));
    assert!(!claims_done("Shall I have it installed?"));
    assert!(!claims_done("The plugin installs globally."));
    assert!(concedes("You're absolutely right, and I apologize. I stand corrected."));
    assert!(!concedes("Right now it is sunny."));
    assert!(asks_fresh("Also check if this project was actually awarded number one."));
}

#[test]
fn nudges_a_made_up_action_first() {
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut check = LookupCheck::default();
    check.on_event(HandoffEvent::UserSaid("Okay, let's add it to our portfolio site then."), t0);
    check.on_event(HandoffEvent::AgentSaid("Checking… I have successfully installed Graphify."), later(1));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(2)), Some(DONE_NUDGE));

    check.on_event(HandoffEvent::UserSaid("No, I mean an Anthropic hackathon."), later(60));
    check.on_event(HandoffEvent::AgentSaid("You're absolutely right, it won. I stand corrected."), later(61));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(62)), Some(AGREE_NUDGE));

    // it ran a command: the claim may be true
    check.on_event(HandoffEvent::UserSaid("Open it in my map."), later(120));
    check.on_event(HandoffEvent::ToolStarted("shell"), later(121));
    check.on_event(HandoffEvent::AgentSaid("I've opened the location in your maps app."), later(123));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(124)), None);
}
