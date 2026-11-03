//! [`VoiceShellMiddleware`]: a voice call's shell runs only Mochi's own helpers.
//!
//! A realtime voice model acts on what it heard, and speech is easily misheard. It hands project, code and
//! install work to Claude (`mochi-ask`) and runs only quick helpers itself; this makes that a rule the
//! model can't talk its way past (it once ran `pip install` mid-call to "add" a tool December asked about).

use async_trait::async_trait;

use tinyagents_harness::context::RunContext;
use tinyagents_harness::error::Result as TaResult;
use tinyagents_harness::middleware::{MiddlewareToolOutcome, ToolHandler, ToolMiddleware};
use tinyinference_llm::tool::ToolCall as TaToolCall;
use tinytools::ToolResult as TaToolResult;

/// Commands a call's shell may run: Mochi's helpers and quick reads.
pub(crate) const VOICE_COMMANDS: &[&str] = &[
    "cat", "date", "mochi-ask", "mochi-phone", "mochi-remind", "mochi-watch", "mochi-mail", "mochi-notify",
    "mochi-call",
];

/// Whether every command in `command` (split on pipes and `;` / `&&` / `||` / newlines outside quotes) is a
/// [`VOICE_COMMANDS`] one. Substitutions (`$(…)`, backticks) and redirects to files are refused outright.
pub(crate) fn voice_may_run(command: &str) -> bool {
    if command.contains("$(") || command.contains('`') {
        return false;
    }
    let mut parts = vec![String::new()];
    let mut quote = None;
    for c in command.chars() {
        match (quote, c) {
            (Some(q), _) if c == q => quote = None,
            (None, '>' | '<') => return false,
            (Some(_), _) => {}
            (None, '\'' | '"') => quote = Some(c),
            (None, '|' | ';' | '&' | '\n') => {
                parts.push(String::new());
                continue;
            }
            (None, _) => {}
        }
        parts.last_mut().expect("never empty").push(c);
    }
    let programs: Vec<&str> = parts.iter().filter_map(|part| part.split_whitespace().next()).collect();
    quote.is_none()
        && !programs.is_empty()
        && programs.iter().all(|program| VOICE_COMMANDS.contains(&program.rsplit('/').next().unwrap_or(program)))
}

/// `wrap_tool`: refuse a shell command outside [`VOICE_COMMANDS`]. Installed on live (voice) harnesses only.
pub(crate) struct VoiceShellMiddleware;

#[async_trait]
impl ToolMiddleware<(), crate::agent::tinyagents::host::OpenHumanRunContext> for VoiceShellMiddleware {
    fn name(&self) -> &str {
        "voice_shell"
    }

    async fn wrap_tool(
        &self,
        ctx: &RunContext<crate::agent::tinyagents::host::OpenHumanRunContext>,
        state: &(),
        call: TaToolCall,
        next: ToolHandler<'_, (), crate::agent::tinyagents::host::OpenHumanRunContext>,
    ) -> TaResult<MiddlewareToolOutcome> {
        if call.name == "shell" {
            let command = call.arguments.get("command").and_then(|c| c.as_str()).unwrap_or_default();
            if !voice_may_run(command) {
                tracing::warn!("[voice-live] shell command outside the voice helpers: refused");
                return Ok(MiddlewareToolOutcome::Result(TaToolResult::error(
                    "Not on a call: by voice you run only mochi-ask, mochi-phone, mochi-remind, mochi-watch, \
                     mochi-mail and cat. Hand this to Claude with mochi-ask (December's full request), \
                     then tell December Claude is on it."
                        .to_string(),
                )));
            }
        }
        next.run(ctx, state, call).await
    }
}

#[cfg(test)]
#[path = "voice_shell_tests.rs"]
mod tests;
