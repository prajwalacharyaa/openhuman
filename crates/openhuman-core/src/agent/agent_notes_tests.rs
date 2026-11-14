use super::*;

#[test]
fn a_call_hears_the_agents_notes_and_latest_calls() {
    let root = tempfile::tempdir().unwrap();
    let workspace = root.path().join("workspace");
    std::fs::create_dir_all(workspace.join("memory/conversations")).unwrap();
    assert_eq!(call_block(&workspace, "mark"), None, "nothing yet");
    std::fs::create_dir_all(notes_dir(&workspace, "mark")).unwrap();
    std::fs::write(notes_dir(&workspace, "mark").join("MEMORY.md"), "- likes short paragraphs").unwrap();
    record_call_line(&workspace, "mark", "December", "She stopped   replying after we met twice.");
    record_call_line(&workspace, "mark", "Mark", "What did the last message say?");
    let block = call_block(&workspace, "mark").unwrap();
    assert!(block.contains("likes short paragraphs"));
    assert!(block.contains("December: She stopped replying after we met twice."), "{block}");
    assert!(block.contains("Mark: What did the last message say?"));
    assert_eq!(call_block(&workspace, "../etc"), None);
    record_call_line(&workspace, "../etc", "x", "y");
    assert!(!root.path().join("projects/agent-notes/../etc").join("calls").exists());
}

#[test]
fn knows_an_agents_chat_from_its_label() {
    let root = tempfile::tempdir().unwrap();
    let workspace = root.path().to_path_buf();
    std::fs::create_dir_all(workspace.join("memory/conversations")).unwrap();
    std::fs::write(
        workspace.join("memory/conversations/threads.jsonl"),
        "{\"op\":\"upsert\",\"thread_id\":\"thread-a\",\"labels\":[\"agent:mark\"]}\n\
         {\"op\":\"upsert\",\"thread_id\":\"thread-b\",\"labels\":[]}\n",
    )
    .unwrap();
    assert_eq!(thread_agent(&workspace, "thread-a").as_deref(), Some("mark"));
    assert_eq!(thread_agent(&workspace, "thread-b"), None);
    assert_eq!(thread_agent(&workspace, "thread-c"), None);
}
