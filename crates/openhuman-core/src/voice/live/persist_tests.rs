use super::*;

fn persister(dir: &std::path::Path) -> TranscriptPersister {
    TranscriptPersister::new(dir.to_path_buf(), "t1".into(), "s1".into(), "sarvam".into())
}

#[test]
fn builds_deterministic_messages_and_skips_blank_text() {
    let dir = tempfile::tempdir().unwrap();
    let mut p = persister(dir.path());
    assert!(p.message(TranscriptRole::User, "   ").is_none());
    let user = p
        .message(TranscriptRole::User, " what time is it ")
        .unwrap();
    assert_eq!(user.id, "voice-s1-1-user");
    assert_eq!(user.sender, "user");
    assert_eq!(user.content, "what time is it");
    assert_eq!(user.extra_metadata["source"], "voice");
    assert_eq!(user.extra_metadata["provider"], "sarvam");
    let agent = p.message(TranscriptRole::Agent, "noon").unwrap();
    assert_eq!(agent.id, "voice-s1-2-agent");
    assert_eq!(agent.sender, "agent");
}

#[tokio::test]
async fn saves_into_the_thread_store() {
    let dir = tempfile::tempdir().unwrap();
    crate::threads::store::blocking::ensure_thread(
        dir.path().to_path_buf(),
        crate::threads::store::CreateConversationThread {
            id: "t1".into(),
            title: "Voice".into(),
            created_at: chrono::Utc::now().to_rfc3339(),
            parent_thread_id: None,
            labels: None,
            personality_id: None,
            working_dir: None,
        },
    )
    .await
    .unwrap();
    let mut p = persister(dir.path());
    p.save(TranscriptRole::User, "hello").await;
    p.save(TranscriptRole::Agent, "hi there").await;
    p.save(TranscriptRole::Agent, "").await;
    let messages =
        crate::threads::store::blocking::get_messages(dir.path().to_path_buf(), "t1".into())
            .await
            .unwrap();
    let texts: Vec<_> = messages
        .iter()
        .map(|m| (m.sender.as_str(), m.content.as_str()))
        .collect();
    assert_eq!(texts, vec![("user", "hello"), ("agent", "hi there")]);
}

#[tokio::test]
async fn a_missing_thread_is_logged_not_fatal() {
    let dir = tempfile::tempdir().unwrap();
    let mut p = persister(dir.path());
    // No thread created: the store refuses, the call still returns.
    p.save(TranscriptRole::User, "hello").await;
}

#[test]
fn voice_turns_pair_each_user_utterance_with_the_speech_after_it() {
    use TranscriptRole::{Agent, User};
    let mut turns = VoiceTurns::default();
    assert!(turns.push(Agent, "hi, I'm Tiny").is_empty(), "a greeting before the user is not logged");
    assert!(turns.push(User, " remember I have EOD 5 to 8 ").is_empty(), "released when the exchange ends");
    assert!(turns.push(Agent, "Got it.").is_empty());
    assert!(turns.push(Agent, "Saved.").is_empty());
    assert!(turns.push(Agent, "  ").is_empty());
    assert_eq!(
        turns.push(User, "what about Monday"),
        vec![
            MemoryEntry::User { index: 0, text: "remember I have EOD 5 to 8".into() },
            MemoryEntry::Reply { index: 1, text: "Got it. Saved.".into() },
        ]
    );
    assert_eq!(
        turns.flush(),
        vec![MemoryEntry::User { index: 2, text: "what about Monday".into() }],
        "a turn the agent never answered logs no reply"
    );
    assert!(turns.flush().is_empty(), "flushing twice is a no-op");
    turns.push(User, "and Tuesday");
    turns.push(Agent, "EOD again.");
    assert_eq!(
        turns.flush(),
        vec![
            MemoryEntry::User { index: 4, text: "and Tuesday".into() },
            MemoryEntry::Reply { index: 5, text: "EOD again.".into() },
        ]
    );
}

#[test]
fn an_exchange_that_read_the_phone_stays_out_of_memory() {
    use TranscriptRole::{Agent, User};
    let mut turns = VoiceTurns::default();
    // usual order: the words, then the phone is read, then the reply
    turns.push(User, "who called me");
    turns.mark_private();
    turns.push(Agent, "Mom called at nine.");
    assert!(turns.push(User, "thanks").is_empty(), "the phone exchange is dropped");
    turns.push(Agent, "Anytime.");
    assert_eq!(turns.flush().len(), 2, "the next exchange is logged again");

    // Gemini finalised the words after starting the tool: the mark carries to that exchange
    turns.mark_private();
    turns.push(User, "read my last message");
    turns.push(Agent, "Sita says she's late.");
    assert!(turns.flush().is_empty());
}

#[tokio::test]
async fn a_persister_without_memory_log_finishes_quietly() {
    let dir = tempfile::tempdir().unwrap();
    let mut p = persister(dir.path());
    p.save(TranscriptRole::User, "hello").await;
    p.finish().await;
}

#[test]
fn spots_phone_reads() {
    assert!(reads_phone("shell", &serde_json::json!({"command": "mochi-phone calls 3"})));
    assert!(!reads_phone("shell", &serde_json::json!({"command": "cat server-status.md"})));
    assert!(!reads_phone("web_search_tool", &serde_json::json!({"query": "mochi-phone"})));
}

#[test]
fn an_agent_calls_words_go_to_its_notes_not_the_shared_memory() {
    let root = tempfile::tempdir().unwrap();
    let workspace = root.path().join("workspace");
    std::fs::create_dir_all(&workspace).unwrap();
    let persister = TranscriptPersister::new(workspace.clone(), "t".into(), "s".into(), "gemini".into())
        .for_agent("mark".into(), "Mark".into());
    assert!(persister.memory.is_none());
    let mut persister = persister;
    tokio::runtime::Runtime::new().unwrap().block_on(persister.save(TranscriptRole::User, "I miss her."));
    let block = crate::agent::agent_notes::call_block(&workspace, "mark").unwrap();
    assert!(block.contains("December: I miss her."), "{block}");
}
