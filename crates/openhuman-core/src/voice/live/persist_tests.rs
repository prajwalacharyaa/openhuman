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
    assert_eq!(
        turns.push(User, " remember I have EOD 5 to 8 "),
        vec![MemoryEntry::User { index: 0, text: "remember I have EOD 5 to 8".into() }]
    );
    assert!(turns.push(Agent, "Got it.").is_empty());
    assert!(turns.push(Agent, "Saved.").is_empty());
    assert!(turns.push(Agent, "  ").is_empty());
    assert_eq!(
        turns.push(User, "what about Monday"),
        vec![
            MemoryEntry::Reply { index: 1, text: "Got it. Saved.".into() },
            MemoryEntry::User { index: 2, text: "what about Monday".into() },
        ]
    );
    assert_eq!(
        turns.flush(),
        vec![],
        "a turn the agent never answered logs no reply"
    );
    assert!(turns.flush().is_empty(), "flushing twice is a no-op");
    assert_eq!(
        turns.push(User, "and Tuesday"),
        vec![MemoryEntry::User { index: 4, text: "and Tuesday".into() }]
    );
    turns.push(Agent, "EOD again.");
    assert_eq!(
        turns.flush(),
        vec![MemoryEntry::Reply { index: 5, text: "EOD again.".into() }]
    );
}

#[tokio::test]
async fn a_persister_without_memory_log_finishes_quietly() {
    let dir = tempfile::tempdir().unwrap();
    let mut p = persister(dir.path());
    p.save(TranscriptRole::User, "hello").await;
    p.finish().await;
}
