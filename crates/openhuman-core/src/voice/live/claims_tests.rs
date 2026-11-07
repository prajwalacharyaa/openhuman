use super::*;

#[test]
fn reads_verdicts_and_stays_quiet_when_unsure() {
    let claim = r#"{"verdict":"FALSE_CLAIM","claim":"I'm working on the backend filters","truth":"December only asked a question; nothing was started","confidence":0.93}"#;
    assert_eq!(
        parse_verdict(claim),
        Verdict::FalseClaim {
            claim: "I'm working on the backend filters".into(),
            truth: "December only asked a question; nothing was started".into(),
        }
    );
    assert_eq!(
        parse_verdict(r#"{"verdict":"MISSING_HANDOFF","claim":"on it","truth":"","confidence":0.9}"#),
        Verdict::MissingHandoff
    );
    // fenced JSON, as some models reply
    assert_eq!(
        parse_verdict("```json\n{\"verdict\":\"missing_handoff\",\"confidence\":0.85}\n```"),
        Verdict::MissingHandoff
    );
    // unsure, empty claim, OK or garbage: no correction
    assert_eq!(parse_verdict(r#"{"verdict":"FALSE_CLAIM","claim":"x","truth":"y","confidence":0.5}"#), Verdict::Ok);
    assert_eq!(parse_verdict(r#"{"verdict":"FALSE_CLAIM","claim":"","confidence":0.99}"#), Verdict::Ok);
    assert_eq!(parse_verdict(r#"{"verdict":"OK","claim":"","truth":"","confidence":1}"#), Verdict::Ok);
    assert_eq!(parse_verdict("not json"), Verdict::Ok);
}

#[test]
fn a_false_claim_gets_a_correction_without_new_work() {
    let note = correction_note("The new voice is active now", "it starts on the next call");
    assert!(note.contains("\"The new voice is active now\""));
    assert!(note.contains("it starts on the next call"));
    assert!(note.contains("Correction:"));
    assert!(note.contains("don't start anything December didn't ask for"));
}

#[test]
fn records_one_turn_and_keeps_decembers_words_for_the_follow_up() {
    let mut turns = TurnRecorder::default();
    turns.user_said("Where should we add the open now toggle?");
    turns.user_said("In the app.");
    let first = turns.generation();
    turns.tool_started("shell");
    turns.agent_said("Looking into it.");
    let (generation, facts) = turns.turn_over().expect("a turn with speech");
    assert_eq!(generation, first);
    assert_eq!(facts.user_said, "Where should we add the open now toggle? In the app.");
    assert_eq!(facts.tools, vec!["shell".to_string()]);

    // the model speaks again without December: same request, fresh tools
    turns.back_office(false, Some("Both backend and app need changes."));
    turns.agent_said("I'm working on the backend filters and the frontend toggle.");
    let (generation, facts) = turns.turn_over().expect("a second turn");
    assert_eq!(generation, first);
    assert!(facts.tools.is_empty());
    assert_eq!(facts.user_said, "Where should we add the open now toggle? In the app.");
    assert_eq!(facts.back_office_answer.as_deref(), Some("Both backend and app need changes."));

    // December speaks: a new generation, so an old verdict is dropped
    turns.user_said("Next time don't name Claude.");
    assert!(turns.generation() > first);
    assert!(turns.turn_over().is_none(), "no model speech yet");
}

#[test]
fn never_checks_a_private_or_silent_turn() {
    let mut turns = TurnRecorder::default();
    turns.user_said("Read my last message.");
    turns.private();
    turns.agent_said("Your last message says hello.");
    assert!(turns.turn_over().is_none());

    let mut turns = TurnRecorder::default();
    turns.user_said("Hello");
    assert!(turns.turn_over().is_none());
}

#[test]
fn the_prompt_carries_what_happened() {
    let facts = TurnFacts {
        user_said: "Can I hear your voice? Is it changed?".into(),
        agent_said: "Yes, the new voice is active now.".into(),
        tools: vec![],
        back_office_working: false,
        back_office_answer: Some("Switched to Sulafat. It'll be live on the next call.".into()),
        private: false,
    };
    let prompt = check_prompt(&facts);
    assert!(prompt.contains("Can I hear your voice? Is it changed?"));
    assert!(prompt.contains("Yes, the new voice is active now."));
    assert!(prompt.contains("Tools Mochi ran in this turn: none"));
    assert!(prompt.contains("working on a request right now: no"));
    assert!(prompt.contains("It'll be live on the next call."));
    assert!(prompt.contains("When unsure, OK."));
}

#[test]
fn long_speech_is_trimmed_on_a_character_boundary() {
    let mut turns = TurnRecorder::default();
    turns.user_said("hi");
    for _ in 0..400 {
        turns.agent_said("नमस्ते, म यहाँ छु।");
    }
    let (_, facts) = turns.turn_over().expect("speech");
    assert!(facts.agent_said.len() <= MAX_SAID * 2 + 64);
    assert!(facts.agent_said.ends_with("म यहाँ छु।"));
}
