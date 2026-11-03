//! The tool surface a live voice session runs with.
//!
//! A live session borrows its tools from an ordinary session host so a spoken
//! request reaches exactly what a typed one on the same agent would: the same
//! registry, the same visible (policy-allowed) names and the same fail-closed
//! tool policy. See `agent::tinyagents::live_harness`.

use std::sync::Arc;

use crate::agent::session_host::types::OpenHumanSessionHost;
use crate::agent::tinyagents::live_harness::LiveToolSurface;
use crate::agent::tinyagents::ToolPolicyEnforcement;

/// Tools a voice call never gets: editing files and sub-agents (which can't reach Claude anyway).
const NOT_BY_VOICE: &[&str] = &["apply_patch", "spawn_async_subagent", "continue_subagent", "list_subagents"];

impl OpenHumanSessionHost {
    /// The durable tools, the visible names and the tool policy of this
    /// session, for a live voice session. Synthesised delegation tools are
    /// left out: a delegate needs the parent turn's execution context, which a
    /// live session (no model loop on this side) does not have. So are
    /// [`NOT_BY_VOICE`]: a call hands that work to Claude (`mochi-ask`).
    pub(crate) fn live_tool_surface(&self) -> LiveToolSurface {
        let allowed = self
            .visible_tool_names
            .iter()
            .filter(|name| !NOT_BY_VOICE.contains(&name.as_str()))
            .filter(|name| self.tools.iter().any(|tool| tool.name() == name.as_str()))
            .cloned()
            .collect();
        LiveToolSurface {
            tool_sets: vec![Arc::clone(&self.tools)],
            allowed,
            tool_policy: Some(ToolPolicyEnforcement {
                policy: self.tool_policy.clone(),
                session: self.tool_policy_session.clone(),
                session_id: self.event_session_id.clone(),
                channel: self.event_channel.clone(),
                agent_definition_id: self.agent_definition_id.clone(),
            }),
            has_thread: self.thread_id.is_some(),
        }
    }

    /// The workspace descriptor tools run against.
    pub(crate) fn live_workspace_descriptor(&self) -> Option<tinytools::WorkspaceDescriptor> {
        self.workspace_descriptor.clone()
    }
}

#[cfg(test)]
#[path = "live_tools_tests.rs"]
mod tests;
