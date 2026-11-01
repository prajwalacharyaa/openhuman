//! Starting a live voice session for a chat thread.
//!
//! The orchestrator's session host supplies the tool surface (the same tools,
//! visibility and policy a typed turn on that agent gets); the live tool
//! harness runs every call through approvals and policy; `tinyagents-live`
//! connects the provider and executes the calls. Tool calls run inside a
//! `voice` external-channel origin and an approval chat context for the
//! thread, so approval prompts reach the user's open conversation.
//!
//! The prompt gives the voice what a typed turn gets: the workspace persona
//! and role files, the same date stamp in the user's zone, and a memory pack
//! recalled when the session starts. The speaking
//! guidance comes from the workspace's `VOICE.md` when it has one, else the
//! built-in `prompt.md`.

use std::path::Path;
use std::sync::Arc;

use tinyagents_harness::context::RunConfig;
use tinyagents_live::{LiveAgent, LiveAgentOptions, LiveAgentSession, TaskScope};

use super::error::LiveVoiceError;
use super::providers;
use crate::agent::prompts::{current_datetime_line, inject_workspace_file};
use crate::agent::session_host::OpenHumanSessionHost;
use crate::agent::tinyagents::host::OpenHumanRunContext;
use crate::agent::tinyagents::live_harness::{assemble_live_tool_harness, LIVE_MAX_TOOL_CALLS};
use crate::agent::turn_origin::{with_origin, AgentTurnOrigin};
use crate::config::schema::voice_live::is_live_provider;
use crate::config::Config;
use crate::security::approval::{ApprovalChatContext, APPROVAL_CHAT_CONTEXT};
use crate::threads::store::{ConversationMessage, CreateConversationThread};

/// The agent a live session borrows its tools from.
pub(crate) const LIVE_AGENT_ID: &str = "orchestrator";
/// How many recent thread messages are summarised into the prompt.
pub(crate) const CONTEXT_MESSAGES: usize = 12;
/// Characters kept per message in that summary.
pub(crate) const CONTEXT_CHARS: usize = 600;
/// The latest messages are kept nearly whole, so "summarize that" right after
/// a long typed answer (a news roundup) sees all of it.
pub(crate) const LATEST_MESSAGES: usize = 2;
/// Characters kept per message among the [`LATEST_MESSAGES`].
pub(crate) const LATEST_CONTEXT_CHARS: usize = 4000;

const BASE_PROMPT: &str = include_str!("prompt.md");
/// Workspace file that replaces [`BASE_PROMPT`] when present and not blank.
pub(crate) const VOICE_PROMPT_FILE: &str = "VOICE.md";
/// Workspace files a typed orchestrator turn carries, in the same order.
pub(crate) const PERSONA_FILES: [&str; 3] = ["SOUL.md", "IDENTITY.md", "ROLE.md"];
/// What the opening memory recall is ranked around when the thread has no
/// user message yet.
const DEFAULT_RECALL_FOCUS: &str =
    "who the user is, their preferences, routines, schedule and ongoing plans";

/// What the client asked for.
#[derive(Debug, Clone)]
pub(crate) struct LiveStartRequest {
    pub(crate) provider: Option<String>,
    pub(crate) thread_id: Option<String>,
    pub(crate) client_id: Option<String>,
    pub(crate) input_sample_rate: u32,
}

/// A running session plus what the transport needs to know about it.
pub(crate) struct StartedLiveSession {
    pub(crate) session: LiveAgentSession,
    pub(crate) provider: String,
    pub(crate) thread_id: String,
}

/// The provider a request resolves to.
pub(crate) fn resolve_provider(
    config: &Config,
    requested: Option<&str>,
) -> Result<String, LiveVoiceError> {
    let provider = requested
        .filter(|p| !p.trim().is_empty())
        .unwrap_or(config.voice_live.default_provider.as_str())
        .to_string();
    if is_live_provider(&provider) {
        Ok(provider)
    } else {
        Err(LiveVoiceError::invalid(format!(
            "unknown live provider `{provider}`"
        )))
    }
}

/// The system prompt: the speaking guidance, the persona files, the date
/// stamp, the opening memory pack and the thread's recent messages, so a
/// spoken turn knows what a typed one would and what was just typed.
pub(crate) fn system_prompt(
    workspace_dir: &Path,
    zone: Option<&str>,
    recent: &[ConversationMessage],
    memory: Option<&str>,
) -> String {
    let custom = std::fs::read_to_string(workspace_dir.join(VOICE_PROMPT_FILE)).ok();
    let guidance = custom
        .as_deref()
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .unwrap_or(BASE_PROMPT.trim());
    let mut prompt = guidance.to_string();

    let mut persona = String::new();
    for file in PERSONA_FILES {
        inject_workspace_file(&mut persona, workspace_dir, file);
    }
    if !persona.trim().is_empty() {
        prompt.push_str(
            "\n\nWho you are and how you work (the same as in typed chats; when speaking, \
             the speaking guidance above decides the format):\n\n",
        );
        prompt.push_str(persona.trim_end());
    }

    prompt.push_str("\n\n");
    prompt.push_str(&current_datetime_line(zone));

    if let Some(memory) = memory.map(str::trim).filter(|text| !text.is_empty()) {
        prompt.push_str("\n\n");
        prompt.push_str(memory);
    }

    let shown: Vec<_> = recent.iter().filter(|m| !m.content.trim().is_empty()).collect();
    let start = shown.len().saturating_sub(CONTEXT_MESSAGES);
    let latest_from = shown.len().saturating_sub(LATEST_MESSAGES);
    let lines: Vec<String> = shown[start..]
        .iter()
        .enumerate()
        .map(|(i, m)| {
            let who = if m.sender == "user" { "User" } else { "You" };
            let cap = if start + i >= latest_from { LATEST_CONTEXT_CHARS } else { CONTEXT_CHARS };
            let text: String = m.content.trim().chars().take(cap).collect();
            format!("{who}: {text}")
        })
        .collect();
    if !lines.is_empty() {
        prompt.push_str("\n\nThe conversation so far (most recent last):\n");
        prompt.push_str(&lines.join("\n"));
    }
    prompt
}

/// What the opening recall is ranked around: the thread's last user message,
/// else [`DEFAULT_RECALL_FOCUS`].
pub(crate) fn recall_focus(recent: &[ConversationMessage]) -> String {
    recent
        .iter()
        .rev()
        .find(|m| m.sender == "user" && !m.content.trim().is_empty())
        .map(|m| m.content.trim().chars().take(CONTEXT_CHARS).collect())
        .unwrap_or_else(|| DEFAULT_RECALL_FOCUS.to_string())
}

/// Makes sure the session's thread exists, creating a voice thread when the
/// client is not on one.
async fn ensure_thread(
    config: &Config,
    thread_id: Option<String>,
) -> Result<String, LiveVoiceError> {
    let id = thread_id
        .filter(|t| !t.trim().is_empty())
        .unwrap_or_else(|| format!("voice-{}", uuid::Uuid::new_v4()));
    crate::threads::store::blocking::ensure_thread(
        config.workspace_dir.clone(),
        CreateConversationThread {
            id: id.clone(),
            title: "Voice conversation".to_string(),
            created_at: chrono::Utc::now().to_rfc3339(),
            parent_thread_id: None,
            labels: None,
            personality_id: None,
            working_dir: None,
        },
    )
    .await
    .map_err(LiveVoiceError::internal)?;
    Ok(id)
}

/// Starts a live session.
pub(crate) async fn start(
    config: Arc<Config>,
    request: LiveStartRequest,
    session_id: &str,
) -> Result<StartedLiveSession, LiveVoiceError> {
    let provider = resolve_provider(&config, request.provider.as_deref())?;
    let thread_id = ensure_thread(&config, request.thread_id).await?;
    tracing::debug!(
        session_id,
        provider = %provider,
        thread_id = %thread_id,
        "[voice-live] starting session"
    );

    let mut host = OpenHumanSessionHost::from_config_for_agent(&config, LIVE_AGENT_ID)
        .map_err(|e| LiveVoiceError::internal(format!("agent build failed: {e}")))?;
    host.set_event_context(format!("voice_live_{session_id}"), "voice");
    host.set_thread_id(Some(&thread_id));
    let harness = assemble_live_tool_harness(host.live_tool_surface());

    let recent = crate::threads::store::blocking::get_messages(
        config.workspace_dir.clone(),
        thread_id.clone(),
    )
    .await
    .unwrap_or_else(|error| {
        tracing::warn!(
            session_id,
            "[voice-live] could not load thread context: {error}"
        );
        Vec::new()
    });
    let identity = crate::memory::scope::resolve_current(&config);
    let pack = crate::memory::lifecycle::hooks::session_start(
        &config,
        &identity,
        &thread_id,
        Some(recall_focus(&recent)),
    )
    .await;
    let zone = config.time_zone();
    let prompt = system_prompt(
        &config.workspace_dir,
        Some(zone.as_str()),
        &recent,
        pack.as_ref().map(|pack| pack.injection()).as_deref(),
    );
    let mut live = providers::live_config(&config, &provider, &prompt, request.input_sample_rate);
    // Hosted Gemini fixes its tools when the ticket is minted, so they must be
    // in the configuration before `prepare`.
    live.tools = tinyagents_live::tool_declarations(&harness, false);
    tracing::debug!(
        session_id,
        direct = live.tools.len(),
        deferred = harness.tools().deferred_schemas().len(),
        tools = ?live.tools.iter().map(|t| t.name.as_str()).collect::<Vec<_>>(),
        "[voice-live] tools declared to the live model"
    );
    let prepared = providers::prepare(&config, &provider, live).await?;

    let origin = AgentTurnOrigin::ExternalChannel {
        sender_name: None,
        channel: "voice".to_string(),
        sender: None,
        reply_target: session_id.to_string(),
        message_id: format!("voice-live-{session_id}"),
        history_key: None,
    };
    let mut context = OpenHumanRunContext::new();
    context.origin = Some(origin.clone());
    context.thread_id = Some(thread_id.clone());
    context.workspace = host.live_workspace_descriptor();
    let run_config =
        RunConfig::new(format!("voice-live-{session_id}")).with_max_tool_calls(LIVE_MAX_TOOL_CALLS);
    let ctx = context.into_tinyagents(run_config);

    let approval = ApprovalChatContext {
        thread_id: thread_id.clone(),
        client_id: request
            .client_id
            .unwrap_or_else(|| format!("voice-live-{session_id}")),
        request_id: None,
    };
    // The approval gate still reads the turn origin and chat context from
    // task-locals (an unlabelled call is treated as `Unknown` and denied), so
    // the tool worker — a spawned task — must be scoped explicitly. Baselined
    // in `agent-runtime-boundary-baseline.json` with the other entry points
    // until the gate reads the origin from the run context.
    let scope: TaskScope = Arc::new(move |task| {
        let origin = origin.clone();
        let approval = approval.clone();
        Box::pin(with_origin(
            origin,
            APPROVAL_CHAT_CONTEXT.scope(approval, task),
        ))
    });
    let agent = LiveAgent::new(Arc::new(harness), Arc::new(())).with_task_scope(scope);
    let session = agent
        .start(
            prepared.provider.as_ref(),
            prepared.config,
            ctx,
            LiveAgentOptions::default(),
        )
        .await?;
    Ok(StartedLiveSession {
        session,
        provider,
        thread_id,
    })
}

#[cfg(test)]
#[path = "session_tests.rs"]
mod tests;
