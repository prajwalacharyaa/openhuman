//! The tool harness behind a live (realtime voice) agent session.
//!
//! A live session has no model loop of its own here — the live provider holds
//! the conversation — but every tool call it makes must pass the same gates a
//! typed chat turn's calls do. This module assembles an `AgentHarness` with
//! the session's exact tool surface and the **tool-boundary** part of the turn
//! harness's middleware stack, in the same order as
//! [`assemble_turn_harness`](super::harness_assembly):
//!
//! 1. the SDK tool-policy projection (`require_sandbox`),
//! 2. human-in-the-loop approval (`ApprovalSecurityMiddleware` through the
//!    global `ApprovalGate`),
//! 3. the CLI/RPC-only scope gate, then the voice shell gate (only Mochi's
//!    helper commands: project and install work goes to Claude),
//! 4. the session's `ToolPolicyMiddleware` (deny / require-approval rules),
//! 5. credential scrubbing of every tool result, then the per-result byte cap,
//! 6. malformed-argument recovery,
//! 7. bare packed-tool routing and embedder tool hooks.
//!
//! Model-loop middleware (context ladder, compaction, prompt-cache guard,
//! budgets, repeat breakers, memory packs) is deliberately absent: nothing in
//! a live session calls a model through this harness.

use std::collections::HashSet;
use std::sync::Arc;

use tinyagents_harness::middleware::{
    ApprovalGateMiddleware, ArgRecoveryMiddleware, ToolPolicyMiddleware as TaToolPolicyMiddleware,
};
use tinyagents_harness::runtime::AgentHarness;
use tinyagents_registry::CapabilityRegistry;

use super::harness_tool_registration::register_turn_tools_and_agents;
use super::host::OpenHumanRunContext;
use super::middleware;
use super::turn_policy::run_policy_for;
use super::ToolPolicyEnforcement;

/// Generous tool-call budget for one live conversation; the harness's
/// per-call admission still counts against it.
pub(crate) const LIVE_MAX_TOOL_CALLS: usize = 400;

/// The tools and policy a live session runs with.
pub(crate) struct LiveToolSurface {
    /// The durable tool registry, then any synthesised tools — the order turn
    /// dispatch resolves names in.
    pub(crate) tool_sets: Vec<Arc<Vec<Box<dyn tinytools::Tool>>>>,
    /// Names the model may call (the session's visible, policy-allowed set).
    pub(crate) allowed: HashSet<String>,
    /// The session's fail-closed tool policy.
    pub(crate) tool_policy: Option<ToolPolicyEnforcement>,
    /// Whether the session is bound to a chat thread (thread-scoped tools are
    /// only offered then).
    pub(crate) has_thread: bool,
}

/// Builds the live session's tool harness.
pub(crate) fn assemble_live_tool_harness(
    surface: LiveToolSurface,
) -> AgentHarness<(), OpenHumanRunContext> {
    let LiveToolSurface {
        tool_sets,
        allowed,
        tool_policy,
        has_thread,
    } = surface;
    let mut harness: AgentHarness<(), OpenHumanRunContext> = AgentHarness::new();
    harness.with_policy(run_policy_for(LIVE_MAX_TOOL_CALLS, false));

    let mut capability_registry: CapabilityRegistry<()> = CapabilityRegistry::new();
    let (tool_count, names, diagnostics, _snapshot) = register_turn_tools_and_agents(
        &mut harness,
        &mut capability_registry,
        &tool_sets,
        &Some(allowed),
        &HashSet::new(),
        None,
        false,
        has_thread,
        &HashSet::new(),
    );
    for diagnostic in &diagnostics {
        tracing::warn!(
            kind = diagnostic.kind.as_str(),
            name = %diagnostic.name,
            "[voice-live] tool registry diagnostic: {}",
            diagnostic.message
        );
    }
    tracing::debug!(tool_count, tools = ?names, "[voice-live] assembled live tool harness");

    harness.push_middleware(Arc::new(
        TaToolPolicyMiddleware::new(harness.tools().policies()).require_sandbox(true),
    ));
    harness.push_tool_middleware(Arc::new(ApprovalGateMiddleware::new(
        "approval_security",
        Arc::new(middleware::ApprovalSecurityMiddleware::new(
            tool_sets.clone(),
        )),
    )));
    harness.push_tool_middleware(Arc::new(middleware::CliRpcOnlyMiddleware::new(
        tool_sets.clone(),
    )));
    harness.push_tool_middleware(Arc::new(middleware::VoiceShellMiddleware));
    let route_session = tool_policy
        .as_ref()
        .map(|enforcement| enforcement.session.clone());
    if let Some(enforcement) = tool_policy {
        harness.push_tool_middleware(Arc::new(middleware::ToolPolicyMiddleware::new(
            enforcement.policy,
            enforcement.session,
            tool_sets.clone(),
            enforcement.session_id,
            enforcement.channel,
            enforcement.agent_definition_id,
        )));
    }
    harness.push_tool_middleware(Arc::new(middleware::credential_scrub_middleware()));
    // The typed turn's per-result byte cap (without its summary model or
    // artifact store): a live provider takes each result whole, and one big
    // result (a recursive `ls` of a project) ends its session.
    harness.push_middleware(Arc::new(middleware::ToolOutputMiddleware {
        budget_bytes: crate::agent::context::DEFAULT_TOOL_RESULT_BUDGET_BYTES,
        payload_summarizer: None,
        artifact_store: None,
        tokenjuice_compaction_enabled: false,
        tokenjuice_compression: crate::inference::tokenjuice::AgentTokenjuiceCompression::Off,
        runtime_config: None,
        tool_policies: harness.tools().policies(),
        artifact_reads: Default::default(),
        focus_by_call: Default::default(),
        summary_focus_tools: Default::default(),
        raw_fetches: Default::default(),
        file_reads: Default::default(),
    }));
    harness.push_middleware(Arc::new(ArgRecoveryMiddleware::new(tool_sets.clone())));
    let registered_tools = harness.tools().names();
    harness.push_middleware(Arc::new(middleware::PackedToolRouteMiddleware::new(
        registered_tools,
        route_session,
    )));
    let embedder_tool_hooks = crate::agent::hooks::embedder_tool_hooks();
    if !embedder_tool_hooks.is_empty() {
        harness.push_middleware(Arc::new(middleware::EmbedderToolHooksMiddleware::new(
            embedder_tool_hooks,
        )));
    }
    harness
}

#[cfg(test)]
#[path = "live_harness_tests.rs"]
mod tests;
