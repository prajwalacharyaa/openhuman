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

#[test]
fn a_fresh_session_opens_with_recent_messages_only() {
    use tinyinference_llm::Message;
    let mut messages = vec![Message::system("rules")];
    for i in 0..30 {
        messages.push(Message::user(format!("question {i}")));
        messages.push(Message::assistant(format!("answer {i}")));
    }
    keep_recent(&mut messages, FRESH_HISTORY);
    assert_eq!(messages.len(), 1 + FRESH_HISTORY);
    assert!(matches!(messages[0], Message::System(_)));
    assert_eq!(messages.last().unwrap().text(), "answer 29");

    let (_dir, cap) = setup(&[call(260_000)]);
    let mut big = request(Some("thread-1"));
    big.messages = (0..50).map(|i| Message::user(format!("m{i}"))).collect();
    assert_eq!(cap.rekey(big).messages.len(), FRESH_HISTORY);
    // a chat with no session yet opens one the same way
    let mut new_chat = request(Some("thread-new"));
    new_chat.messages = (0..50).map(|i| Message::user(format!("m{i}"))).collect();
    assert_eq!(cap.rekey(new_chat).messages.len(), FRESH_HISTORY);
    // a chat whose session is resumed keeps what it was sent (the provider sends only the new turn)
    let (_dir, cap) = setup(&[call(40_000)]);
    let mut resumed = request(Some("thread-1"));
    resumed.messages = (0..50).map(|i| Message::user(format!("m{i}"))).collect();
    assert_eq!(cap.rekey(resumed).messages.len(), 50);
}

fn said(thread: &str, words: &str) -> ModelRequest {
    let mut request = request(Some(thread));
    request.messages = vec![tinyinference_llm::Message::user(format!(
        "{words}\n\n{}\nDecember asked to take your time last week{}",
        crate::memory::lifecycle::hooks::OPEN_TAG,
        crate::memory::lifecycle::hooks::CLOSE_TAG
    ))];
    request
}

#[test]
fn december_picks_the_model_in_words() {
    assert_eq!(asked_model("quick mode: what's the time in tokyo"), Some(QUICK));
    assert_eq!(asked_model("can you do it fast"), Some(QUICK));
    assert_eq!(asked_model("take your time and plan the redesign"), Some(DEEP));
    assert_eq!(asked_model("back to normal mode"), Some(NORMAL));
    assert_eq!(asked_model("what's on my calendar"), None);
    let request = said("t", "hello mochi");
    assert_eq!(latest_user_words(&request), "hello mochi", "the memory pack is not December's words");
}

#[test]
fn small_talk_is_light_and_work_is_not() {
    assert!(is_light("good morning mochi"));
    assert!(is_light("remind me at 5 to call mum"));
    assert!(!is_light("fix the bug in the portfolio repo"));
    assert!(!is_light("latest news from nepal"));
    assert!(!is_light(&"a".repeat(LIGHT_CHARS + 1)));
}

#[test]
fn a_chat_is_routed_and_switches_only_with_a_fresh_session() {
    let (dir, cap) = setup(&[call(40_000)]);
    let cap = cap.routed(true);
    // a new chat opening with small talk runs on Haiku
    let first = cap.rekey(said("thread-new", "hi mochi"));
    assert_eq!(first.model.as_deref(), Some(QUICK));
    // the existing small session keeps its model while nothing asks for another one
    let _ = std::fs::write(
        dir.path().join("ws/claude-session-model.json"),
        r#"{"thread-1": {"model": "sonnet", "chosen": false}}"#,
    );
    let same = cap.rekey(said("thread-1", "and what about tomorrow"));
    assert_eq!(same.model.as_deref(), Some(NORMAL));
    assert_eq!(key(&same), Some("thread-1"));
    // December asks for depth: Opus, on a fresh session
    let deep = cap.rekey(said("thread-1", "take your time with this one"));
    assert_eq!(deep.model.as_deref(), Some(DEEP));
    assert_eq!(key(&deep), Some("thread-1~1"));
}

#[test]
fn routing_is_off_unless_turned_on() {
    let (_dir, cap) = setup(&[call(40_000)]);
    assert_eq!(cap.rekey(said("thread-1", "quick mode")).model, None);
}

#[test]
fn an_agent_chat_runs_as_its_agent() {
    let (dir, mut cap) = setup(&[call(40_000)]);
    let agents = dir.path().join("agents");
    std::fs::create_dir_all(&agents).unwrap();
    std::fs::write(agents.join("mark.md"), "---\nname: mark\nmodel: opus\n---\nYou are Mark.\n").unwrap();
    cap.agent_files = agents;
    std::fs::create_dir_all(dir.path().join("workspace/memory/conversations")).unwrap();
    std::fs::write(
        dir.path().join("workspace/memory/conversations/threads.jsonl"),
        concat!(
            r#"{"op":"upsert","thread_id":"thread-1","labels":["agent:mark"]}"#, "\n",
            r#"{"op":"upsert","thread_id":"thread-2","labels":["general"]}"#, "\n",
            r#"{"op":"upsert","thread_id":"thread-3","labels":["agent:nobody"]}"#, "\n",
        ),
    )
    .unwrap();
    std::fs::create_dir_all(dir.path().join("projects/.claude/agent-memory/mark")).unwrap();
    std::fs::write(dir.path().join("projects/.claude/agent-memory/mark/MEMORY.md"), "- likes short sentences").unwrap();
    let mut asked = said("thread-1", "is this sentence right?");
    asked.messages.insert(0, tinyinference_llm::Message::system("You are Mochi."));
    let out = cap.rekey(asked);
    assert_eq!(out.metadata.get("claude_agent").and_then(|a| a.as_str()), Some("mark"));
    // Mochi's persona is gone; the agent's note with its notes is there
    let system: Vec<String> = out.messages.iter()
        .filter(|m| matches!(m, tinyinference_llm::Message::System(_)))
        .map(tinyinference_llm::Message::text)
        .collect();
    assert_eq!(system.len(), 1);
    assert!(system[0].contains("You are Mark") && system[0].contains("likes short sentences"));
    assert!(!system[0].contains("You are Mochi"));
    // the agent's own model, even with routing off; quick words still switch
    assert_eq!(out.model.as_deref(), Some(DEEP));
    assert_eq!(cap.rekey(said("thread-1", "quick mode please")).model.as_deref(), Some(QUICK));
    // an ordinary chat, and a label naming no agent file, stay Mochi's
    assert!(cap.rekey(said("thread-2", "hello")).metadata.get("claude_agent").is_none());
    assert!(cap.rekey(said("thread-3", "hello")).metadata.get("claude_agent").is_none());
}

#[test]
fn agent_names_and_displays() {
    assert!(valid_agent_name("mark") && valid_agent_name("seo-checker"));
    assert!(!valid_agent_name("../etc") && !valid_agent_name("Mark") && !valid_agent_name(""));
    assert_eq!(agent_display("mark"), "Mark");
    assert_eq!(agent_display("sy"), "SY");
}
