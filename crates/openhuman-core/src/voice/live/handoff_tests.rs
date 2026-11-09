use super::*;

#[test]
fn recognises_claims_but_not_questions() {
    assert!(claims_handoff("Sure thing, Claude is on it and will handle checking the branch."));
    assert!(claims_handoff("I'll ask Claude to look into that."));
    assert!(claims_handoff("I've asked Claude, it will get back to you."));
    assert!(!claims_handoff("That needs Claude. Shall I ask Claude?"));
    assert!(!claims_handoff("Claude is still working on opening the website for you."));
    assert!(!claims_handoff("Today is Kartik 16, 2083."));
    // speaking as itself (2026-11-07: December asked it never to name Claude)
    assert!(claims_handoff("Sure, I'm on it."));
    assert!(claims_handoff("Looking into it, I'll get back to you."));
    assert!(!claims_handoff("Shall I look into it?"));
    assert!(!claims_handoff("I'm still on it, I'll let you know when it's ready."));
    // work nobody asked for is a false claim for the meaning check, not a handoff to make
    assert!(!claims_handoff("I'm working on the backend filters and the frontend toggle."));
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
    assert!(claude_reply_note(&done("desktop", "t1"), "t1").unwrap().contains("Claude answered what December typed"));
    assert_eq!(claude_reply_note(&done("desktop", "t2"), "t1"), None, "a typed turn in another chat");

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
    let t0 = std::time::Instant::now();
    let mut gate = NoteGate::default();
    assert_eq!(gate.offer("first".into(), t0), Some("first".into()), "quiet: straight through");
    gate.agent_audio();
    assert_eq!(gate.offer("second".into(), t0), None, "mid-sentence: held");
    assert_eq!(gate.offer("third".into(), t0), None);
    assert_eq!(gate.turn_over(t0), Some("second\n\nthird".into()));
    assert_eq!(gate.turn_over(t0), None);
}

#[test]
fn notes_wait_while_december_is_talking() {
    let t0 = std::time::Instant::now();
    let at = |ms: u64| t0 + std::time::Duration::from_millis(ms);
    let mut gate = NoteGate::default();
    gate.heard(t0);
    assert_eq!(gate.offer("answer".into(), at(200)), None, "December mid-sentence: held");
    gate.heard(at(1000));
    assert_eq!(gate.tick(at(2000)), None, "still within the quiet gap");
    // the model answers December first; its turn ends while December is quiet: the note goes then
    gate.agent_audio();
    assert_eq!(gate.tick(at(3000)), None, "never while the model speaks");
    assert_eq!(gate.turn_over(at(3500)), Some("answer".into()));

    // December spoke but the model stayed silent (noise): the tick lets it out once December is quiet
    gate.heard(at(4000));
    assert_eq!(gate.offer("later".into(), at(4100)), None);
    assert_eq!(gate.tick(at(4000) + QUIET_AFTER_USER), Some("later".into()));
    assert_eq!(gate.tick(at(9000)), None);

    // December cuts the model off: nothing goes out until December has finished
    gate.agent_audio();
    gate.interrupted(at(10_000));
    assert_eq!(gate.offer("after".into(), at(10_100)), None);
    assert_eq!(gate.tick(at(10_000) + QUIET_AFTER_USER), Some("after".into()));
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
fn knows_giving_in() {
    assert!(concedes("You're absolutely right, and I apologize. I stand corrected."));
    assert!(!concedes("Right now it is sunny."));
    assert!(asks_fresh("Also check if this project was actually awarded number one."));
}

#[test]
fn nudges_giving_in_without_a_check() {
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut check = LookupCheck::default();
    check.on_event(HandoffEvent::UserSaid("No, I mean an Anthropic hackathon."), later(60));
    check.on_event(HandoffEvent::AgentSaid("You're absolutely right, it won. I stand corrected."), later(61));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(62)), Some(AGREE_NUDGE));

    // it ran a command: the claim may be true
    check.on_event(HandoffEvent::UserSaid("Open it in my map."), later(120));
    check.on_event(HandoffEvent::ToolStarted("shell"), later(121));
    check.on_event(HandoffEvent::AgentSaid("I've opened the location in your maps app."), later(123));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(124)), None);
}

#[test]
fn tells_the_call_december_typed_without_the_text() {
    let started = |client: &str| WebChannelEvent {
        event: "inference_start".into(),
        client_id: client.into(),
        thread_id: "t1".into(),
        ..Default::default()
    };
    assert_eq!(claude_reply_note(&started("mochi-phone"), "t1").as_deref(), Some(TYPED_NOTE));
    // a mochi-ask turn is the call's own handoff: nothing to announce
    assert_eq!(claude_reply_note(&started("mochi-ask"), "t1"), None);
    let progress = WebChannelEvent { event: "tool_call".into(), ..started("desktop") };
    assert_eq!(claude_reply_note(&progress, "t1"), None);
}

#[test]
fn knows_status_questions_and_updates() {
    assert!(asks_status("Can you check if Claude is making edit in portfolio site?"));
    assert!(asks_status("Is Claude still working?"));
    assert!(!asks_status("Ask Claude to fix the footer."));
    assert!(asks_status("Are you done with the footer?"));
    assert!(asks_status("Any update on the pull request?"));
    assert!(!claims_handoff("I am keeping an eye on the progress and will update you the moment Claude finishes the edits."));
    assert!(!claims_handoff("Claude's answer is ready."));
}

/// 2026-11-07: "Can you check if Claude is making edit?" drew three answers (a guess, a web-search nudge, then a
/// handoff nudge that made the model send an old request to Claude again).
#[test]
fn a_status_question_gets_one_status_nudge_only() {
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut checks = CallChecks::default();
    checks.on_event(HandoffEvent::UserSaid("Can you check if Claude is making edit in portfolio site?"), t0);
    checks.on_event(
        HandoffEvent::AgentSaid("Claude is still working on the edits, and I'll let you know as soon as everything is ready."),
        later(1),
    );
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(2)), Some(STATUS_NUDGE));

    // whatever the answer to that nudge says, no second nudge from either check
    checks.on_event(HandoffEvent::AgentSaid("Sure, Claude is on it."), later(3));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(4)), None);

    // December speaks again: armed again
    checks.on_event(HandoffEvent::UserSaid("Fix the footer too."), later(60));
    checks.on_event(HandoffEvent::AgentSaid("Claude will do it."), later(61));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(62)), Some(NUDGE));
}

#[test]
fn checks_memory_for_questions_about_before() {
    assert!(asks_recall("Do you remember what I said about the portfolio?"));
    assert!(asks_recall("What do I have on Monday?"));
    assert!(!asks_recall("What's the weather?"));
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut check = LookupCheck::default();
    check.on_event(HandoffEvent::UserSaid("What did I say about the meeting?"), t0);
    check.on_event(HandoffEvent::AgentSaid("You said it was on Friday."), later(1));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(2)), Some(MEMORY_NUDGE));
    // a memory search answers it: no nudge
    check.on_event(HandoffEvent::UserSaid("What was my plan for Sunday?"), later(60));
    check.on_event(HandoffEvent::ToolStarted("memory_search"), later(61));
    check.on_event(HandoffEvent::AgentSaid("A hike with friends."), later(62));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(63)), None);
}

#[test]
fn plans_need_a_memory_search_and_claims_need_a_real_check() {
    assert!(asks_recall("Do I have any reminders for tomorrow?"));
    assert!(!asks_recall("Remind me at 5 to call mum"), "setting a reminder isn't asking about plans");
    assert!(asks_fresh("Check the current state, do the proper research"));
    assert!(claims_memory("I checked the memory as well, nothing there"));
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    // reminders were checked (a shell helper), memory wasn't: one memory nudge
    let mut check = LookupCheck::default();
    check.on_event(HandoffEvent::UserSaid("Do I have any reminders for tomorrow?"), t0);
    check.on_event(HandoffEvent::ToolStarted("shell"), later(1));
    check.on_event(HandoffEvent::AgentSaid("No reminders for tomorrow."), later(2));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(3)), Some(MEMORY_NUDGE));
    // "my research shows" with no tool at all: a lookup nudge
    let mut check = LookupCheck::default();
    check.on_event(HandoffEvent::UserSaid("Why is everything falling?"), t0);
    check.on_event(HandoffEvent::AgentSaid("My research shows money is moving to cash."), later(1));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(2)), Some(LOOKUP_NUDGE));
}

#[test]
fn giving_in_without_checking_is_caught_in_more_words() {
    assert!(concedes("You're right to ask; Rajan Bikram Thapa is indeed the newly appointed chairman."));
    assert!(concedes("I made a mistake by relying on an outdated source. I'm sorry for the confusion this caused."));
    assert!(!concedes("Rajan Bikram Thapa is the newly appointed chairman of the Nepal Insurance Authority."));
}
