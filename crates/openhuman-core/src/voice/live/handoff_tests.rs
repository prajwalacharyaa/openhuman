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
fn answers_from_earlier_in_the_call_follow_the_phone() {
    let earlier = earlier_threads(r#"{"type":"start","thread_id":"t2","earlier_threads":["t1","bad id!","t3"]}"#);
    assert_eq!(earlier, vec!["t1".to_string(), "t3".to_string()], "only plausible thread ids");
    assert!(earlier_threads(r#"{"type":"start"}"#).is_empty());
    let done = |client: &str, thread: &str| WebChannelEvent {
        event: "chat_done".into(),
        client_id: client.into(),
        thread_id: thread.into(),
        full_response: Some("Here's the plan.".into()),
        ..Default::default()
    };
    let note = earlier_reply_note(&done("mochi-ask", "t1"), &earlier).expect("a note");
    assert!(note.contains("before the phone was passed") && note.ends_with("Here's the plan."));
    assert_eq!(earlier_reply_note(&done("desktop", "t1"), &earlier), None, "never another chat's typed turns");
    assert_eq!(earlier_reply_note(&done("mochi-ask", "t9"), &earlier), None, "a chat not in this call");
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
fn giving_in_is_not_nudged_any_more() {
    // 2026-11-14: "you're right" / "sorry for the confusion" drew a re-check nudge and December heard the same
    // answer two and three times; a correction is left to December's next words
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut check = LookupCheck::default();
    check.on_event(HandoffEvent::UserSaid("So altogether that's only about 40 percent?"), later(60));
    check.on_event(HandoffEvent::AgentSaid("You're right, the rest is the system and caches."), later(61));
    assert_eq!(check.on_event(HandoffEvent::TurnComplete, later(62)), None);
    assert!(asks_fresh("Also check if this project was actually awarded number one."));
    assert_eq!(nudge_kind(LOOKUP_NUDGE), "lookup");
    assert_eq!(nudge_kind("anything else"), "other");
}

#[test]
fn an_answer_note_asks_for_the_short_part_only() {
    let done = WebChannelEvent {
        event: "chat_done".into(),
        client_id: "mochi-ask".into(),
        thread_id: "t1".into(),
        full_response: Some("In short: hold, wait for 351.\n\nDetail: volume is a fifth of the average.".into()),
        ..Default::default()
    };
    let note = claude_reply_note(&done, "t1").unwrap();
    assert!(note.contains("say only its first part"), "{note}");
    assert!(note.ends_with("hold, wait for 351.\n\n(more, only if asked) volume is a fifth of the average."), "{note}");
    assert!(!note.contains("In short:") && !note.contains("Detail:"), "no labels to read out: {note}");
    assert_eq!(from_in_short("Graph exists. Let me query it.In short: a hazard map."), "In short: a hazard map.");
    assert_eq!(from_in_short("Done, PR 13 is up."), "Done, PR 13 is up.");
    assert!(!note.contains("read all of it out, completely"));
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
fn promises_status_questions_and_travel_are_caught() {
    assert!(claims_handoff("I'll go with the first option, adding a solid base fill. I'll get that ready for you."));
    assert!(!claims_handoff("Claude's answer is ready."));
    assert!(asks_status("Okay, tell me what is happening."));
    assert!(asks_status("काम भयो त कि बाँकी छ?"));
    assert!(asks_fresh("Can you check what distance it is from Thali to Dubachaur?"));
    assert!(asks_fresh("आमालाई दुबाचौर जाने गाडीको टिकट चाहिएको थियो"));
    assert!(!asks_fresh("How is the business going?"), "\"bus\" inside \"business\" is not travel");
}

#[test]
fn says_it_hasnt_started_while_claude_works() {
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut checks = CallChecks::default();
    checks.brain_started();
    checks.on_event(HandoffEvent::UserSaid("Okay, did it start?"), t0);
    checks.on_event(HandoffEvent::AgentSaid("I haven't started the brush fix yet."), later(1));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(2)), Some(BUSY_NUDGE));
    // once Claude is done, the same words are no longer contradicted
    checks.brain_finished(false);
    checks.on_event(HandoffEvent::UserSaid("And the other one?"), later(60));
    checks.on_event(HandoffEvent::AgentSaid("That one hasn't started."), later(61));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(62)), None);
}

#[test]
fn a_cancel_needs_a_command() {
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut checks = CallChecks::default();
    checks.on_event(HandoffEvent::UserSaid("होइन, पर्दैन त्यो।"), t0);
    checks.on_event(HandoffEvent::AgentSaid("हुन्छ, मैले त्यसलाई क्यान्सिल गरिदिएँ।"), later(1));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(2)), Some(CANCEL_NUDGE));
    // with mochi-ask --cancel run, it's true
    checks.on_event(HandoffEvent::UserSaid("Stop it."), later(60));
    checks.on_event(HandoffEvent::ToolStarted("shell"), later(61));
    checks.on_event(HandoffEvent::AgentSaid("I cancelled it."), later(62));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(63)), None);
}

#[test]
fn an_answer_to_claudes_question_goes_back_to_claude() {
    assert!(asks_december("Four options. I haven't changed anything yet, just say the word if you want me to build it."));
    assert!(asks_december("Which one should I use?"));
    assert!(!asks_december("Done. Pull request 18 is up."));
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut checks = CallChecks::default();
    checks.brain_finished(true);
    checks.on_event(HandoffEvent::UserSaid("Choose whichever one is proper."), t0);
    checks.on_event(HandoffEvent::AgentSaid("Okay, the first option then."), later(1));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(2)), Some(ANSWER_NUDGE));
    // handed on: nothing to say
    let mut checks = CallChecks::default();
    checks.brain_finished(true);
    checks.on_event(HandoffEvent::UserSaid("The first one."), t0);
    checks.on_event(HandoffEvent::ToolStarted("shell"), later(1));
    checks.on_event(HandoffEvent::AgentSaid("Sent, on it."), later(2));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(3)), None);
}

#[test]
fn searching_then_saying_checking_without_an_answer_is_caught() {
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut checks = CallChecks::default();
    checks.on_event(HandoffEvent::UserSaid("Where is the place called Dubachaur?"), t0);
    checks.on_event(HandoffEvent::ToolStarted("web_search_tool"), later(1));
    checks.on_event(HandoffEvent::ToolStarted("web_search_tool"), later(4));
    checks.on_event(HandoffEvent::AgentSaid("I'm checking the road distance right now."), later(12));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(13)), Some(HANGING_NUDGE));
    // handed on with the helper, then "I'm on it, checking…": the right line, not a hanging search
    let mut checks = CallChecks::default();
    checks.on_event(HandoffEvent::UserSaid("The word solid is invisible on my portfolio site."), t0);
    checks.on_event(HandoffEvent::ToolStarted("shell"), later(1));
    checks.on_event(HandoffEvent::AgentSaid("I'm on it, checking the site's styling for that invisible text now."), later(7));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(8)), None);
    // "let me check" and then the search: normal
    let mut checks = CallChecks::default();
    checks.on_event(HandoffEvent::UserSaid("What's the weather in Pokhara?"), t0);
    checks.on_event(HandoffEvent::AgentSaid("Let me check."), later(1));
    checks.on_event(HandoffEvent::ToolStarted("web_search_tool"), later(2));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(3)), None);
}

#[test]
fn a_retelling_of_claudes_answer_is_not_saved_again() {
    let mut retell = Retelling::default();
    retell.note_sent("(Server note, not December speaking: Claude finished the request you handed it. Tell…)\n\nDone.");
    assert!(!retell.skips_agent_line());
    retell.agent_spoke();
    assert!(retell.skips_agent_line(), "the model is retelling the answer");
    retell.turn_over();
    assert!(retell.skips_agent_line(), "its late final transcript is skipped too");
    retell.agent_spoke();
    assert!(!retell.skips_agent_line(), "the next turn is saved");
    retell.note_sent("(Server check, not December speaking: you said you're on it…)");
    retell.agent_spoke();
    assert!(!retell.skips_agent_line(), "nudges aren't answers");
}

#[test]
fn a_cancelled_request_is_reported_as_not_done() {
    let cancelled = WebChannelEvent {
        event: "chat_error".into(),
        client_id: "mochi-ask".into(),
        thread_id: "t1".into(),
        message: Some("Cancelled by newer request".into()),
        error_type: Some("cancelled".into()),
        ..Default::default()
    };
    assert!(claude_reply_note(&cancelled, "t1").unwrap().contains("it is NOT done"));
}


#[test]
fn a_remark_with_a_fresh_word_is_not_a_request() {
    // 2026-11-16: the complaint drew a search nudge and the apology was said twice
    assert!(!asks_fresh("It's not just about the share market or anything. I don't want you to just give me the random guesses over anything."));
    assert!(asks_fresh("What share market open today?"));
    assert!(asks_fresh("Nepal news today"));
    assert!(asks_fresh("Mochi, check the weather in Pokhara for me and for the whole week ahead"));
}

#[test]
fn checking_with_the_answer_in_the_same_line_is_not_hanging() {
    assert!(!only_announces("checking\u{2026} the share market is open today."));
    assert!(only_announces("checking\u{2026}"));
    assert!(only_announces("let me check that for you."));
    assert!(!only_announces("the market is open."));

    let t0 = Instant::now();
    let mut checks = CallChecks::default();
    checks.on_event(HandoffEvent::UserSaid("What share market open today?"), t0);
    checks.on_event(HandoffEvent::ToolStarted("web_search_tool"), t0);
    let said = t0 + Duration::from_secs(5);
    checks.on_event(HandoffEvent::AgentSaid("Checking\u{2026} The share market is open today."), said);
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, said), None);
}

#[test]
fn a_search_for_the_question_before_is_not_hanging() {
    // 2026-11-16: searched at :08, "Confirm it with Samir" at :38 -> "Checking with Samir…" was nudged as hanging
    let t0 = Instant::now();
    let mut checks = CallChecks::default();
    checks.on_event(HandoffEvent::UserSaid("I don't think it is."), t0);
    checks.on_event(HandoffEvent::ToolStarted("web_search_tool"), t0);
    let asked = t0 + Duration::from_secs(30);
    checks.on_event(HandoffEvent::UserSaid("Confirm it with Samir."), asked);
    checks.on_event(HandoffEvent::AgentSaid("Checking with Samir about the current market status for you."), asked);
    assert_ne!(checks.on_event(HandoffEvent::TurnComplete, asked), Some(HANGING_NUDGE));
}

#[test]
fn a_nudge_waits_and_is_dropped_when_a_tool_starts() {
    let t0 = Instant::now();
    let mut delay = NudgeDelay::default();
    delay.hold(LOOKUP_NUDGE, t0);
    assert_eq!(delay.due(t0 + Duration::from_secs(1)), None);
    assert_eq!(delay.due(t0 + NUDGE_WAIT), Some(LOOKUP_NUDGE));
    assert_eq!(delay.due(t0 + NUDGE_WAIT * 2), None, "sent once");

    delay.hold(BOOK_NUDGE, t0);
    assert_eq!(delay.drop_waiting(), Some(BOOK_NUDGE));
    assert_eq!(delay.due(t0 + NUDGE_WAIT), None);
}

#[test]
fn an_answer_the_call_never_said_goes_to_the_next_session() {
    let t0 = Instant::now();
    let note = format!("(Server note: {ANSWER_MARK})\n\nIn short: done.");
    unspoken_set("thread-unspoken-a", &note, t0);
    unspoken_set("thread-unspoken-a", "(Server check: not an answer)", t0);
    assert_eq!(unspoken_take("thread-unspoken-a", t0 + Duration::from_secs(5)), Some(note.clone()));
    assert_eq!(unspoken_take("thread-unspoken-a", t0 + Duration::from_secs(6)), None, "once");

    unspoken_set("thread-unspoken-b", &note, t0);
    unspoken_clear("thread-unspoken-b");
    assert_eq!(unspoken_take("thread-unspoken-b", t0), None, "said already");

    unspoken_set("thread-unspoken-c", &note, t0);
    assert_eq!(unspoken_take("thread-unspoken-c", t0 + UNSPOKEN_KEEPS), None, "too old to bring up");
}

#[test]
fn a_quoted_question_further_up_is_not_claude_asking() {
    let post = format!("Detail: It reads: \"what was the first thing that felt off to you?\" {} It hasn't gone out yet.", "It has a carousel image attached and a video ready to go with it, and nothing else is pending on it at all.");
    assert!(!asks_december(&post));
}

#[test]
fn a_reconnected_call_keeps_its_voice() {
    let voice = |name: &str| Some(name.to_string());
    assert_eq!(call_voice("thread-voice-a", false, voice("Sulafat")), voice("Sulafat"));
    // the setting changed mid-call: the reconnect still speaks as the call started
    assert_eq!(call_voice("thread-voice-a", true, voice("Puck")), voice("Sulafat"));
    // a new call takes the new voice, and its reconnects keep that one
    assert_eq!(call_voice("thread-voice-a", false, voice("Puck")), voice("Puck"));
    assert_eq!(call_voice("thread-voice-a", true, voice("Achernar")), voice("Puck"));
    // a reconnect nobody remembers (the engine restarted) uses the setting
    assert_eq!(call_voice("thread-voice-b", true, voice("Achernar")), voice("Achernar"));
}

#[test]
fn an_agent_on_a_call_hands_work_to_its_chat() {
    let t0 = Instant::now();
    let turn = |checks: &mut CallChecks, said: &str, tool: Option<&str>| {
        checks.on_event(HandoffEvent::UserSaid(said), t0);
        if let Some(tool) = tool {
            checks.on_event(HandoffEvent::ToolStarted(tool), t0);
        }
        checks.on_event(HandoffEvent::AgentSaid("You could share how AI helps reclaim time for creative work."), t0);
        checks.on_event(HandoffEvent::TurnComplete, t0)
    };
    let mut nadia = CallChecks::for_agent();
    // the 2026-11-17 call: topics, a list and a draft, all from the voice's own head
    assert_eq!(turn(&mut nadia, "Give me some topics and I will choose from that.", None), Some(WORK_NUDGE));
    assert_eq!(turn(&mut nadia, "Make one proper LinkedIn draft that I can post.", None), Some(WORK_NUDGE));
    // handed on: nothing to say
    assert_eq!(turn(&mut nadia, "Make one proper LinkedIn draft that I can post.", Some("shell")), None);
    // talk is talk
    assert_eq!(turn(&mut nadia, "Hello Nadia, are you there?", None), None);
    assert_eq!(turn(&mut nadia, "Why are some people magnetic online, you think", None), None);
    // Mochi's own call keeps its own checks
    assert_eq!(turn(&mut CallChecks::default(), "Give me some topics and I will choose from that.", None), None);
}

#[test]
fn the_2026_11_18_call_is_caught() {
    let t0 = Instant::now();
    let projects = project_names(&["nepal-hazard-explorer".to_string(), "portfolio-site".to_string(), "jigubites_app".to_string()]);
    let turn = |checks: &mut CallChecks, said: &str, tool: Option<&str>, reply: &str| {
        checks.on_event(HandoffEvent::UserSaid(said), t0);
        if let Some(tool) = tool {
            checks.on_event(HandoffEvent::ToolStarted(tool), t0);
        }
        checks.on_event(HandoffEvent::AgentSaid(reply), t0);
        checks.on_event(HandoffEvent::TurnComplete, t0)
    };
    let mut mochi = CallChecks::default().with_projects(projects.clone());
    // refusing without trying
    assert_eq!(turn(&mut mochi, "Design a guy with fractured leg.", None,
        "I can't generate images or visual designs directly."), Some(CANT_NUDGE));
    assert_eq!(turn(&mut mochi, "Mochi, can you ask our visual designer to generate one image of key?", None,
        "I can't generate images or create visual designs, so I can't ask a visual designer to do that for you."),
        Some(CANT_NUDGE));
    // a can't after trying is the result, not a refusal
    assert_eq!(turn(&mut mochi, "Can you call Iris?", Some("shell"), "I couldn't find Iris; I can't call her."), None);
    // Claude's verdict, passed on, is not the voice refusing
    mochi.brain_finished(false);
    mochi.on_event(HandoffEvent::AgentSaid("I can't send a live browser URL at all."), t0);
    assert_eq!(mochi.on_event(HandoffEvent::TurnComplete, t0), None);
    // their own project, web-searched
    assert_eq!(turn(&mut mochi, "If there is any update in Nepal Hazard project?", Some("web_search_tool"),
        "I found active hazard mapping initiatives in Nepal, including the METEOR Explorer."), Some(PROJECT_NUDGE));
    assert_eq!(turn(&mut mochi, "Any update in Nepal Hazard project?", Some("shell"), "Looking into it."), None);
    // the shares portfolio is not the portfolio site
    assert!(!names_project("What are today's portfolio holdings?", &projects));
    assert!(names_project("check my portfolio site please", &projects));
    assert!(names_project("how is JiguBites doing", &projects));
    assert!(!names_project("my portfolio is up today", &projects));

    // December's words arriving after the command they led to: handed on, so no nudge
    let mut iris = CallChecks::for_agent();
    iris.on_event(HandoffEvent::ToolStarted("shell"), t0);
    iris.on_event(HandoffEvent::UserSaid("Hi Iris, can you design a guy with a fractured leg?"), t0);
    iris.on_event(HandoffEvent::AgentSaid("मैले यो काम अगाडि बढाइसकेको छु।"), t0);
    assert_eq!(iris.on_event(HandoffEvent::TurnComplete, t0), None);
}

#[test]
fn passing_it_to_someone_is_a_handoff_claim() {
    assert!(claims_handoff("I'm passing your request to Iris to proceed with the one-off sketch design."));
    assert!(claims_handoff("I've asked Nadia to write a new LinkedIn post draft for you."));
    assert!(!claims_handoff("Passing you to Mark."));
    assert!(!claims_handoff("I've set a reminder for one minute from now."));
}

#[test]
fn hearing_trouble_is_not_a_refusal() {
    assert!(says_cant("I can't generate images or visual designs directly."));
    assert!(says_cant("I don't have the ability to publish to LinkedIn directly."));
    assert!(!says_cant("Sorry, I can't hear you well, could you say that again?"));
    assert!(!says_cant("You're completely right. I overreached, and I apologize for suggesting something I can't actually do."), "an apology, not a refusal");
    assert!(!says_cant("I can't say for sure without checking."));
    assert!(!says_cant("I couldn't find Iris in your phone contacts."));
}

#[test]
fn project_names_are_said_the_way_december_says_them() {
    let names = project_names(&["nepal-hazard-explorer".to_string(), "portfolio-site".to_string(), "jigubites_app".to_string(),
        "auto_share".to_string(), "juju-bites-site".to_string()]);
    for name in ["nepal hazard explorer", "nepal hazard", "portfolio site", "jigubites", "auto share", "juju bites"] {
        assert!(names.contains(&name.to_string()), "{name} in {names:?}");
    }
    assert!(!names.contains(&"portfolio".to_string()));
}

#[test]
fn the_2026_11_19_calls_are_caught() {
    let t0 = Instant::now();
    let at = |s: u64| t0 + Duration::from_secs(s);
    // Samir's voice said "hold" while Samir's real answer ("sell, not hold") was still being written
    let mut samir = CallChecks::for_agent();
    samir.on_event(HandoffEvent::UserSaid("Open the NMBBL script in my screen and visually show me if it's good to sell now or not."), t0);
    samir.on_event(HandoffEvent::ToolStarted("shell"), at(1));
    samir.brain_started();
    samir.on_event(HandoffEvent::ToolStarted("shell"), at(2));
    samir.on_event(HandoffEvent::AgentSaid("I've opened the MNBBL chart for you. The price is currently near support, but the overall trend is down with several bearish signals, so my view is to hold."), at(13));
    assert_eq!(samir.on_event(HandoffEvent::TurnComplete, at(14)), Some(VERDICT_NUDGE));
    // naming what is being checked is not a verdict; the answer itself, once back, is said freely
    assert!(!gives_verdict("looking up whether it's good to sell mnbbl now."));
    let mut mochi = CallChecks::default();
    mochi.brain_started();
    mochi.on_event(HandoffEvent::UserSaid("Is it good to sell MNBBL?"), t0);
    mochi.on_event(HandoffEvent::ToolStarted("shell"), at(1));
    mochi.on_event(HandoffEvent::AgentSaid("Looking up whether it's good to sell MNBBL now."), at(2));
    assert_eq!(mochi.on_event(HandoffEvent::TurnComplete, at(3)), None);
    mochi.brain_finished(false);
    mochi.on_event(HandoffEvent::AgentSaid("MNBBL's chart says sell: the trend is down."), at(60));
    assert_eq!(mochi.on_event(HandoffEvent::TurnComplete, at(61)), None);

    // a misheard request: "No, it's not that." while it's being worked on, and nothing cancelled
    let mut mochi = CallChecks::default();
    mochi.on_event(HandoffEvent::UserSaid("I want you to not just look at the budget but check it properly."), t0);
    mochi.on_event(HandoffEvent::ToolStarted("shell"), at(1));
    mochi.brain_started();
    mochi.on_event(HandoffEvent::AgentSaid("Looking into your JiguBites budget for saving opportunities."), at(4));
    assert_eq!(mochi.on_event(HandoffEvent::TurnComplete, at(5)), None);
    mochi.on_event(HandoffEvent::UserSaid("No, it's not that."), at(10));
    mochi.on_event(HandoffEvent::AgentSaid("Got it, let's look at it again. What should we check?"), at(11));
    assert_eq!(mochi.on_event(HandoffEvent::TurnComplete, at(12)), Some(CORRECTION_NUDGE));
    // cancelled: nothing to say
    mochi.on_event(HandoffEvent::UserSaid("No, not that one."), at(20));
    mochi.on_event(HandoffEvent::ToolStarted("shell"), at(21));
    mochi.on_event(HandoffEvent::AgentSaid("Stopped it."), at(22));
    assert_eq!(mochi.on_event(HandoffEvent::TurnComplete, at(23)), None);
    assert!(corrects("त्यो होइन, अर्को"));
    assert!(!corrects("Nothing else for now."));

    // December's words arriving after the command they led to: the answer was sent on, no nudge (five repeats)
    let mut mochi = CallChecks::default();
    mochi.brain_finished(true);
    mochi.on_event(HandoffEvent::ToolStarted("shell"), t0);
    mochi.on_event(HandoffEvent::UserSaid("त्यहाँनिर पुल गरेर राखिराख न सर्भरमा।"), at(1));
    mochi.on_event(HandoffEvent::AgentSaid("Okay."), at(3));
    assert_eq!(mochi.on_event(HandoffEvent::TurnComplete, at(4)), None);

    // Claude's own step line isn't read out
    assert_eq!(
        from_in_short("Let me check the actual site flow in the repo before giving Prabesh exact steps, so I don't invent UI details that don't match reality.ठिक छ, Prabesh जी, पहिलो स्टेप।"),
        "ठिक छ, Prabesh जी, पहिलो स्टेप।"
    );
    assert_eq!(from_in_short("Let me check."), "Let me check.");

    // only hanging up ends a guest's turn on the phone
    assert!(hung_up("December hung up (stop frame)"));
    assert!(hung_up("the phone closed it (1000 hang up)"));
    assert!(!hung_up("the voice provider closed it: Error(Provider(\"policy violation\"))"));
    assert!(!hung_up("the phone's connection failed: WebSocket protocol error"));
}

#[test]
fn handing_the_job_back_is_a_refusal_too() {
    // 2026-11-16/17 calls: the voice handed December the job instead of trying or opening the screen
    assert!(says_cant("You will need to post it manually, as I don't have the ability to publish to LinkedIn."));
    assert!(says_cant("This is something you will need to do manually."));
    assert!(says_cant("You'll have to do it yourself in the app."));
    assert!(!says_cant("I can't hear you clearly, say it again?"));
    assert!(!says_cant("Done, the inbox is open."));
}

#[test]
fn a_dropped_call_resumes_once_with_its_newest_handle() {
    let thread = "test-resume-thread";
    resume_handle_set(thread, "h1");
    resume_handle_set(thread, "h2");
    assert_eq!(resume_handle_take(thread, false), None, "a new call never resumes");
    resume_handle_set(thread, "h3");
    assert_eq!(resume_handle_take(thread, true), Some("h3".to_string()), "a reconnect takes the newest");
    assert_eq!(resume_handle_take(thread, true), None, "used once: if resuming fails, the next try starts fresh");
    resume_handle_set(thread, "h4");
    resume_handle_clear(thread);
    assert_eq!(resume_handle_take(thread, true), None, "hung up: nothing to resume");
}

#[test]
fn a_nepali_i_searched_needs_a_search_behind_it() {
    // 2026-11-21 12:41: "I searched the web again" said in Nepali with no search that turn
    let said = "मैले फेरि वेब सर्च गरेर हेरेँ तर मलाई लाइभ स्टेटस जानकारी मिलेन।";
    assert!(LOOKUP_SAID.iter().any(|w| said.contains(w)));
}

#[test]
fn a_nepali_i_couldnt_is_a_refusal() {
    // 2026-11-21 12:41: "I couldn't check the live status, contact Buddha Air" (the flight question)
    assert!(says_cant("मैले आजको बुद्ध एयरको पोखरा जाने फ्लाइटको लाइभ स्टेटस हेर्न सकिनँ।"));
}

#[test]
fn a_status_check_does_not_cover_a_claimed_handoff() {
    // 2026-11-21 21:50: mochi-ask --result ran, then "I've asked Claude to send the room link" with nothing sent
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut checks = CallChecks::default();
    checks.on_event(HandoffEvent::UserSaid("Provide me the link for that room discussion."), t0);
    checks.tool_call("shell", "mochi-ask --result", later(1));
    checks.on_event(HandoffEvent::ToolStarted("shell"), later(1));
    checks.on_event(HandoffEvent::TurnComplete, later(3));
    checks.on_event(HandoffEvent::UserSaid("Tell Claude to provide that link to me in the chat."), later(15));
    checks.on_event(HandoffEvent::AgentSaid("I've asked Claude to send the room discussion link directly to your chat."), later(16));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(17)), Some(NUDGE));

    // a real handoff still covers the claim
    let mut checks = CallChecks::default();
    checks.on_event(HandoffEvent::UserSaid("Ask Claude for the link."), t0);
    checks.tool_call("shell", "mochi-ask \"Send December the room link\"", later(1));
    checks.on_event(HandoffEvent::ToolStarted("shell"), later(1));
    checks.on_event(HandoffEvent::AgentSaid("I've asked Claude to send it."), later(2));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(3)), None);
}

#[test]
fn done_said_while_only_handed_on_is_corrected() {
    // 2026-11-21 21:49 and 22:04: "I've updated the LinkedIn strategy", "I've sent the brief to the room"
    assert!(claims_done("i've updated the linkedin strategy to focus on your technical knowledge."));
    assert!(claims_done("i've sent the linkedin strategy brief to the team in the room for their decision."));
    assert!(!claims_done("i've sent it to claude, it's working on it."));
    assert!(!claims_done("it'll be done in a minute."));
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut checks = CallChecks::default();
    checks.on_event(HandoffEvent::UserSaid("Okay, let's send it to the room."), t0);
    checks.tool_call("shell", "mochi-ask \"Send the LinkedIn brief to the room\"", later(1));
    checks.on_event(HandoffEvent::ToolStarted("shell"), later(1));
    checks.on_event(HandoffEvent::AgentSaid("I've sent the LinkedIn strategy brief to the team in the room for their decision."), later(5));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(6)), Some(DONE_NUDGE));
    // once the answer is back, passing it on is not a false claim
    checks.wants_full_answer(later(30));
    checks.brain_finished(false);
    checks.on_event(HandoffEvent::AgentSaid("It's done: the room is meeting on it now."), later(31));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(32)), None);
}

#[test]
fn all_of_it_means_all_of_it() {
    // 2026-11-21 03:42: "Can you tell the full news?" got two sentences
    assert!(asks_full("About the Russia bombing campaign, can you tell the full news?"));
    assert!(asks_full("Read me the whole thing."));
    assert!(asks_full("पूरा भन त"));
    assert!(!asks_full("What? I don't get it. Explain to me simply."));
    assert!(!asks_full("Is it completed?"));
    let t0 = std::time::Instant::now();
    let later = |s: u64| t0 + std::time::Duration::from_secs(s);
    let mut checks = CallChecks::default();
    checks.on_event(HandoffEvent::UserSaid("Can you tell the full news?"), t0);
    checks.on_event(HandoffEvent::AgentSaid("Russia started bombing Ukraine's energy grid during peace talks."), later(2));
    assert_eq!(checks.on_event(HandoffEvent::TurnComplete, later(3)), Some(FULL_NUDGE));

    // an answer handed on while December asked for all of it is read whole; one asked plainly is not
    let mut checks = CallChecks::default();
    checks.on_event(HandoffEvent::UserSaid("Check the JiguBites repos and tell me everything."), t0);
    checks.tool_call("shell", "mochi-ask \"Check the JiguBites repos\"", later(1));
    checks.on_event(HandoffEvent::UserSaid("And what's the weather?"), later(60));
    checks.tool_call("shell", "mochi-ask \"Weather in Kathmandu\"", later(61));
    assert!(checks.wants_full_answer(later(100)));
    assert!(!checks.wants_full_answer(later(130)));
    let mut checks = CallChecks::default();
    checks.set_long_answers(true);
    assert!(checks.wants_full_answer(later(5)), "December chose long answers in the app");
}

#[test]
fn typed_text_reaches_the_voice_without_phone_numbers() {
    // 2026-11-21 21:45: the pasted LinkedIn link, and "I can't access the chat" three times
    let note = typed_text_note("https://www.linkedin.com/in/acharyaprajwal/");
    assert!(note.contains("https://www.linkedin.com/in/acharyaprajwal/"));
    assert!(!TYPED_NOTE.contains("can't see its text"));
    assert_eq!(strip_phone_numbers("call 9841-234567 or +977 1 4412345 today"), "call [number] or [number] today");
    assert_eq!(strip_phone_numbers("post 2 of 10 on 2026-11-21, 1500000 rupees"), "post 2 of 10 on 2026-11-21, 1500000 rupees");
}

#[test]
fn a_long_answer_offers_the_rest_and_a_full_one_reads_it_all() {
    let long = format!("In short: two repos are behind. Detail: {}", "Nepal Hazard Explorer is two commits behind. ".repeat(20));
    assert!(has_more(&long));
    assert!(!has_more("In short: all up to date. Detail: four repos checked."));
    let note = full_answer_note(&format!("(Server note, not December speaking: x)\n\n{long}"));
    assert!(is_answer_note(&note) && note.contains("mochi-ask --read --start") && note.ends_with(long.as_str()));
}

#[test]
fn the_in_short_and_detail_labels_are_never_read_out() {
    assert_eq!(without_labels("In short: YouTube is open on your phone now."), "YouTube is open on your phone now.");
    assert_eq!(
        without_labels("In short: de7mber-design is synced. Detail: it pulled the newest commits."),
        "de7mber-design is synced.\n\n(more, only if asked) it pulled the newest commits."
    );
    assert_eq!(without_labels("No labels here."), "No labels here.");
}

#[test]
fn saying_it_learned_without_learning_is_caught() {
    assert!(says_learning("understood, thank you for showing me. i am learning."));
    assert!(says_learning("yes, i understand now and have learned the action."));
    assert!(!says_learning("done."));
    assert!(asks_learn("No, it's not done. Learn from this, okay? You have to click here."));
    assert!(asks_learn("Watch how I close this video."));
    assert!(!asks_learn("Close the minimized video."));
}
