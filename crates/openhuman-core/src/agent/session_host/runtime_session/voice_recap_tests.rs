use super::*;

fn msg(sender: &str, content: &str, voice: bool) -> ConversationMessage {
    ConversationMessage {
        id: format!("{sender}-{content}"),
        content: content.into(),
        message_type: "text".into(),
        extra_metadata: if voice {
            serde_json::json!({ "source": "voice" })
        } else {
            serde_json::Value::Null
        },
        sender: sender.into(),
        created_at: String::new(),
    }
}

#[test]
fn takes_the_voice_run_before_the_new_typed_message() {
    let thread = vec![
        msg("user", "typed earlier", false),
        msg("agent", "typed reply", false),
        msg("user", "remember EOD 5 to 8", true),
        msg("agent", "Saved.", true),
        msg("user", "  ", true),
        msg("user", "what did I just say?", false), // this turn, already appended by the client
    ];
    let block = recap(&thread).unwrap();
    assert!(block.contains("User: remember EOD 5 to 8\nYou: Saved."), "{block}");
    assert!(!block.contains("typed earlier"));
    assert!(!block.contains("what did I just say"));
}

#[test]
fn works_before_the_client_appended_the_typed_message() {
    let thread = vec![msg("user", "hi by voice", true), msg("agent", "hello", true)];
    assert_eq!(voice_since_last_typed(&thread).len(), 2);
}

#[test]
fn nothing_once_a_typed_exchange_followed_the_voice() {
    let thread = vec![
        msg("user", "voice", true),
        msg("agent", "voice reply", true),
        msg("user", "typed", false),
        msg("agent", "typed reply", false),
        msg("user", "next typed", false),
    ];
    assert!(recap(&thread).is_none());
    assert!(recap(&[]).is_none());
}

#[test]
fn keeps_only_the_latest_messages() {
    let thread: Vec<_> = (0..40)
        .map(|i| msg(if i % 2 == 0 { "user" } else { "agent" }, &format!("m{i}"), true))
        .collect();
    let voice = voice_since_last_typed(&thread);
    assert_eq!(voice.len(), MAX_MESSAGES);
    assert_eq!(voice.last().unwrap().content, "m39");
}

#[test]
fn a_stopped_turns_message_reaches_the_next_turn() {
    // 2026-11-13: the request, then "can you check that" 24 s later (the first turn was cancelled)
    let thread = vec![
        msg("user", "typed earlier", false),
        msg("agent", "typed reply", false),
        msg("user", "on my portfolio site the word solid is invisible", false),
        msg("user", "can you check that", false), // this turn, already appended by the client
    ];
    let block = unanswered_block(&thread, "can you check that").unwrap();
    assert!(block.contains("User: on my portfolio site the word solid is invisible"), "{block}");
    assert!(!block.contains("can you check that"));
    assert!(!block.contains("typed earlier"));
    // the turn's text may carry more than the thread's copy (a date line, a brief)
    assert_eq!(unanswered_typed(&thread, "Current Date & Time: today\n\ncan you check that").len(), 1);
    // the client hasn't appended this turn's message: every trailing one is earlier
    assert_eq!(unanswered_typed(&thread, "something new").len(), 2);
}

#[test]
fn answered_messages_and_voice_are_not_unanswered() {
    let answered = vec![msg("user", "first", false), msg("agent", "reply", false), msg("user", "second", false)];
    assert!(unanswered_block(&answered, "second").is_none());
    // a follow-up queued behind a running turn sits before that turn's reply in the thread
    let queued = vec![msg("user", "first", false), msg("user", "second", false), msg("agent", "reply to first", false)];
    assert!(unanswered_block(&queued, "second").is_none());
    let after_voice = vec![msg("user", "by voice", true), msg("agent", "spoken", true), msg("user", "typed now", false)];
    assert!(unanswered_block(&after_voice, "typed now").is_none());
    assert!(unanswered_block(&[], "x").is_none());
    let many: Vec<_> = (0..9).map(|i| msg("user", &format!("m{i}"), false)).collect();
    let kept = unanswered_typed(&many, "m8");
    assert_eq!(kept.len(), MAX_UNANSWERED);
    assert_eq!(kept.last().unwrap().content, "m7");
}
