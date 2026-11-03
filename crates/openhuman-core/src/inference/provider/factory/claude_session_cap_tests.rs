use super::*;

struct Answers;

#[async_trait::async_trait]
impl ChatModel<()> for Answers {
    async fn invoke(&self, _state: &(), _request: ModelRequest) -> tinyinference_llm::Result<ModelResponse> {
        Ok(ModelResponse::assistant("ok"))
    }
}

fn request(thread: Option<&str>) -> ModelRequest {
    let mut request = ModelRequest::default();
    if let Some(thread) = thread {
        request.metadata = serde_json::json!({ "thread_id": thread });
    }
    request
}

fn key(request: &ModelRequest) -> Option<&str> {
    request.metadata.get("thread_id").and_then(|t| t.as_str())
}

fn call(tokens: u64) -> String {
    serde_json::json!({"type": "assistant", "message": {"usage": {
        "input_tokens": 2, "cache_read_input_tokens": tokens, "cache_creation_input_tokens": 100}}})
    .to_string()
}

fn setup(transcript_lines: &[String]) -> (tempfile::TempDir, SessionCap) {
    let dir = tempfile::tempdir().unwrap();
    let workspace = dir.path().join("ws");
    let projects = dir.path().join("projects");
    std::fs::create_dir_all(&workspace).unwrap();
    std::fs::create_dir_all(projects.join("-home-x-projects")).unwrap();
    std::fs::write(
        workspace.join("claude-code-sessions.json"),
        r#"{"sessions": {"thread-1": "aaaa-session"}}"#,
    )
    .unwrap();
    std::fs::write(projects.join("-home-x-projects/aaaa-session.jsonl"), transcript_lines.join("\n")).unwrap();
    let cap = SessionCap::with_paths(Arc::new(Answers), &workspace, projects);
    (dir, cap)
}

#[test]
fn keeps_a_small_session() {
    let (_dir, cap) = setup(&[call(40_000), call(90_000)]);
    assert_eq!(key(&cap.rekey(request(Some("thread-1")))), Some("thread-1"));
}

#[test]
fn moves_a_big_session_to_a_fresh_one_once() {
    let (_dir, cap) = setup(&[call(40_000), call(260_000), r#"{"type":"last-prompt"}"#.to_string()]);
    assert_eq!(key(&cap.rekey(request(Some("thread-1")))), Some("thread-1~1"));
    // the fresh session has no transcript yet: it stays on it
    assert_eq!(key(&cap.rekey(request(Some("thread-1")))), Some("thread-1~1"));
}

#[test]
fn leaves_requests_without_a_thread_alone() {
    let (_dir, cap) = setup(&[call(400_000)]);
    assert_eq!(key(&cap.rekey(request(None))), None);
    assert_eq!(key(&cap.rekey(request(Some("thread-unknown")))), Some("thread-unknown"));
}

#[test]
fn reads_the_last_calls_size() {
    let (dir, _cap) = setup(&[call(40_000), call(90_000)]);
    let transcript = dir.path().join("projects/-home-x-projects/aaaa-session.jsonl");
    assert_eq!(last_context_tokens(&transcript), Some(90_102));
    assert_eq!(session_key("t", 0), "t");
    assert_eq!(session_key("t", 2), "t~2");
}

#[test]
fn moves_on_when_the_transcript_was_cleaned_up() {
    let (dir, cap) = setup(&[call(40_000)]);
    std::fs::remove_file(dir.path().join("projects/-home-x-projects/aaaa-session.jsonl")).unwrap();
    assert_eq!(key(&cap.rekey(request(Some("thread-1")))), Some("thread-1~1"));
}
