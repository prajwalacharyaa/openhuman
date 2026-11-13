use super::*;
use async_trait::async_trait;
use tinytools::{Tool, ToolResult};

struct NamedTool(&'static str);

#[async_trait]
impl Tool for NamedTool {
    fn name(&self) -> &str {
        self.0
    }

    fn description(&self) -> &str {
        "test tool"
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({"type": "object"})
    }

    async fn execute(&self, _args: serde_json::Value) -> anyhow::Result<ToolResult> {
        Ok(ToolResult::success("ok"))
    }
}

/// Prints far more than a live model can take, like `ls -R` in a project.
struct HugeTool;

#[async_trait]
impl Tool for HugeTool {
    fn name(&self) -> &str {
        "huge"
    }

    fn description(&self) -> &str {
        "test tool"
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({"type": "object"})
    }

    async fn execute(&self, _args: serde_json::Value) -> anyhow::Result<ToolResult> {
        Ok(ToolResult::success("node_modules/x.js\n".repeat(60_000)))
    }
}

fn surface(allowed: &[&str]) -> LiveToolSurface {
    let tools: Vec<Box<dyn tinytools::Tool>> = vec![
        Box::new(NamedTool("alpha")),
        Box::new(NamedTool("beta")),
        Box::new(HugeTool),
    ];
    LiveToolSurface {
        tool_sets: vec![Arc::new(tools)],
        allowed: allowed.iter().map(|s| (*s).to_string()).collect(),
        tool_policy: None,
        has_thread: true,
        dry_run: false,
    }
}

#[test]
fn registers_only_allowed_tools() {
    let harness = assemble_live_tool_harness(surface(&["alpha"]));
    let names: Vec<_> = harness
        .tools()
        .schemas()
        .into_iter()
        .map(|schema| schema.name)
        .collect();
    assert_eq!(names, vec!["alpha"]);
}

#[test]
fn installs_the_tool_boundary_middleware() {
    let harness = assemble_live_tool_harness(surface(&["alpha", "beta"]));
    assert_eq!(harness.tools().schemas().len(), 2);
    // Approval, CLI/RPC-only and credential scrubbing wrap every call.
    assert!(harness.middleware().tool_middleware_len() >= 3);
    assert!(harness.middleware().len() >= 3);
}

async fn run_one(allowed: &str) -> String {
    use tinyagents_harness::agent_loop::phases::execute_tool_batch;
    let harness = assemble_live_tool_harness(surface(&[allowed]));
    let mut ctx = OpenHumanRunContext::new().into_tinyagents(
        tinyagents_harness::context::RunConfig::new("live-harness-test"),
    );
    let mut run = tinyagents_harness::middleware::AgentRun::new();
    let mut status = tinyagents_harness::events::HarnessRunStatus::new(
        ctx.run_id().clone(),
        tinyagents_harness::ComponentId::new("test".to_string()),
    );
    let mut messages = Vec::new();
    let outcome = execute_tool_batch(
        &harness,
        &(),
        &mut ctx,
        &mut run,
        &mut status,
        &mut messages,
        vec![tinyinference_llm::tool::ToolCall::new(
            "c1",
            allowed,
            serde_json::json!({}),
        )],
    )
    .await
    .expect("batch runs");
    assert_eq!(outcome.results.len(), 1);
    outcome.results[0].text().to_string()
}

#[tokio::test]
async fn runs_an_allowed_call_through_the_pipeline() {
    assert!(run_one("alpha").await.contains("ok"));
}

#[tokio::test]
async fn caps_a_huge_result() {
    let text = run_one("huge").await;
    assert!(text.len() < 20 * 1024, "result is {} bytes", text.len());
    assert!(text.starts_with("node_modules/x.js"));
}
