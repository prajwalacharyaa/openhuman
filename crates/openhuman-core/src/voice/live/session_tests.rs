use super::*;
use crate::config::schema::voice_live::{LIVE_PROVIDER_GEMINI_HOSTED, LIVE_PROVIDER_SARVAM};

fn message(sender: &str, content: &str) -> ConversationMessage {
    ConversationMessage {
        id: format!("{sender}-{content}"),
        content: content.into(),
        message_type: "text".into(),
        extra_metadata: serde_json::Value::Null,
        sender: sender.into(),
        created_at: String::new(),
    }
}

#[test]
fn resolves_the_requested_or_default_provider() {
    let mut config = Config::default();
    assert_eq!(
        resolve_provider(&config, None).unwrap(),
        LIVE_PROVIDER_GEMINI_HOSTED
    );
    assert_eq!(
        resolve_provider(&config, Some(" ")).unwrap(),
        LIVE_PROVIDER_GEMINI_HOSTED
    );
    assert_eq!(
        resolve_provider(&config, Some(LIVE_PROVIDER_SARVAM)).unwrap(),
        LIVE_PROVIDER_SARVAM
    );
    assert_eq!(
        resolve_provider(&config, Some("nope")).unwrap_err().code,
        "invalid_request"
    );
    config.voice_live.default_provider = "broken".into();
    assert!(resolve_provider(&config, None).is_err());
}

#[test]
fn the_prompt_carries_voice_guidance_time_and_recent_messages() {
    let ws = tempfile::tempdir().unwrap();
    let bare = system_prompt(ws.path(), None, &[], None, None);
    assert!(bare.contains("You are Tiny"));
    assert!(bare.contains("Current Date & Time: "));
    assert!(!bare.contains("conversation so far"));
    assert!(!bare.contains("Who you are"), "no persona block without persona files");

    let mut recent: Vec<_> = (0..20)
        .map(|i| message(if i % 2 == 0 { "user" } else { "agent" }, &format!("m{i}")))
        .collect();
    recent.push(message("user", "   "));
    recent.push(message("agent", &"o".repeat(CONTEXT_CHARS + 50)));
    recent.push(message("agent", &"x".repeat(LATEST_CONTEXT_CHARS + 50)));
    let prompt = system_prompt(ws.path(), None, &recent, None, None);
    assert!(prompt.contains("The conversation so far"));
    assert!(!prompt.contains("m0\n"), "older messages are dropped");
    assert!(prompt.contains("User: m10"));
    assert!(prompt.contains("You: m19"));
    let line = |c: char| prompt.lines().find(|l| l.contains(c.to_string().repeat(50).as_str())).unwrap().len();
    assert_eq!(line('o'), "You: ".len() + CONTEXT_CHARS + 50, "second-latest kept whole");
    assert_eq!(line('x'), "You: ".len() + LATEST_CONTEXT_CHARS, "latest capped at the larger limit");
}

#[test]
fn the_prompt_carries_persona_files_like_a_typed_turn() {
    let ws = tempfile::tempdir().unwrap();
    std::fs::write(ws.path().join("SOUL.md"), "# Soul\nWarm and direct.").unwrap();
    std::fs::write(ws.path().join("ROLE.md"), "# Role\nRead news as a full bulletin.").unwrap();
    let prompt = system_prompt(ws.path(), None, &[], None, None);
    assert!(prompt.contains("Who you are"));
    assert!(prompt.contains("### SOUL.md"));
    assert!(prompt.contains("Warm and direct."));
    assert!(prompt.contains("Read news as a full bulletin."));
    assert!(
        prompt.find("### SOUL.md").unwrap() < prompt.find("### ROLE.md").unwrap(),
        "same order as a typed turn"
    );
}

#[test]
fn a_workspace_voice_file_replaces_the_built_in_guidance() {
    let ws = tempfile::tempdir().unwrap();
    std::fs::write(ws.path().join(VOICE_PROMPT_FILE), "You are Tiny on a radio show.").unwrap();
    let prompt = system_prompt(ws.path(), None, &[], None, None);
    assert!(prompt.starts_with("You are Tiny on a radio show."));
    assert!(!prompt.contains("one to three sentences"));

    std::fs::write(ws.path().join(VOICE_PROMPT_FILE), "  \n").unwrap();
    let prompt = system_prompt(ws.path(), None, &[], None, None);
    assert!(prompt.contains("one to three sentences"), "a blank file keeps the built-in");
}

#[test]
fn the_prompt_carries_the_memory_pack_and_the_users_zone() {
    let ws = tempfile::tempdir().unwrap();
    let pack = "<memory-context>\nThe user has EOD Monday to Saturday.\n</memory-context>";
    let prompt = system_prompt(ws.path(), Some("Asia/Kathmandu"), &[], Some(pack), None);
    assert!(prompt.contains("The user has EOD Monday to Saturday."));
    assert!(prompt.contains(" Asia/Kathmandu ("), "{prompt}");
    let prompt = system_prompt(ws.path(), None, &[], Some("   "), None);
    assert!(!prompt.contains("memory-context"));
}

#[test]
fn recall_is_focused_on_the_last_user_message() {
    let recent = vec![
        message("user", "what is on Monday"),
        message("agent", "your EOD block"),
        message("user", "  "),
    ];
    assert_eq!(recall_focus(&recent), "what is on Monday");
    assert_eq!(recall_focus(&[]), DEFAULT_RECALL_FOCUS);
}

#[test]
fn other_conversations_show_their_latest_messages() {
    let other = OtherConversation {
        title: "Nepal hazard bot".into(),
        last_message_at: "2026-11-01T16:20:00Z".into(),
        messages: vec![
            message("user", "old question"),
            message("user", "why are the alerts failing?"),
            message("agent", "BIPAD has been down since 22 October; the bot now fails fast."),
            message("user", "ok"),
            message("agent", "Done."),
        ],
    };
    let block = other_conversations_block(&[other]).expect("a block");
    assert!(block.contains("- Nepal hazard bot (last message 2026-11-01T16:20:00Z):"));
    assert!(block.contains("You: BIPAD has been down since 22 October"));
    assert!(!block.contains("old question"), "only the latest messages");
    assert_eq!(other_conversations_block(&[]), None);

    let prompt = system_prompt(Path::new("/nonexistent"), None, &[], None, Some(&block));
    assert!(prompt.contains("Your other recent conversations"));
}
