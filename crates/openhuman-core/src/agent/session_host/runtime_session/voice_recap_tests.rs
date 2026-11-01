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
