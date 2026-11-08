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
/// `cat` reads only Mochi's workspace (see [`cat_reads_only_workspace`]).
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
        && parts.iter().all(|part| cat_reads_only_workspace(part))
}

/// Mochi's workspace: the only place a call's `cat` may read (status and report files live there).
fn workspace_root() -> std::path::PathBuf {
    std::env::var_os("OPENHUMAN_WORKSPACE")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| std::path::PathBuf::from("/home/openhuman/OpenHuman/workspace"))
}

/// A `cat` reads only files inside [`workspace_root`] (or stdin): never keys, `/proc` or other users' files,
/// which a misheard or injected request could otherwise read out loud. Paths with `..`, `~`, variables or
/// wildcards are refused, and a symlink counts by where it points.
fn cat_reads_only_workspace(part: &str) -> bool {
    let mut words = part.split_whitespace();
    let Some(program) = words.next() else { return true };
    if program.rsplit('/').next() != Some("cat") {
        return true;
    }
    let root = workspace_root();
    let real_root = std::fs::canonicalize(&root).unwrap_or_else(|_| root.clone());
    words.filter(|word| !word.starts_with('-')).all(|word| {
        let path = word.trim_matches(|c| c == '"' || c == '\'');
        if path.contains("..") || path.contains(['~', '$', '*', '?', '[']) {
            return false;
        }
        let path = std::path::Path::new(path);
        if !path.starts_with(&root) {
            return false;
        }
        // a link inside the workspace must not lead out of it
        match std::fs::canonicalize(path) {
            Ok(real) => real.starts_with(&real_root),
            Err(_) => true, // missing: cat fails on its own
        }
    })
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
