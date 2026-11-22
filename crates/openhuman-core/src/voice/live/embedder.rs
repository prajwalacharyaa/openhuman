//! Keeps the memory embedder loaded while a call is on.
//!
//! Memory recall (before every Claude turn a call hands on, and the voice's own memory tool) embeds the question
//! with the local embedder. It is started on demand and lets go of its ~390 MB after 10 idle minutes, and loading
//! it again takes ~3 s on this server, so the first hand-off of a call used to wait for it (2026-11-22: 7 s of
//! memory before Claude started). A call asks for it at its start and every few minutes while it lasts; after the
//! call it unloads as before. `MOCHI_EMBEDDER_HEALTH` overrides the address; empty turns this off.

use std::time::Duration;

/// How often a call asks again, well inside the embedder's 10-minute idle limit.
pub(crate) const WARM_EVERY: Duration = Duration::from_secs(240);
const DEFAULT_HEALTH: &str = "http://127.0.0.1:3150/health";

/// The address asked, or None when warming is off.
pub(crate) fn health_url() -> Option<String> {
    match std::env::var("MOCHI_EMBEDDER_HEALTH") {
        Ok(url) if url.trim().is_empty() => None,
        Ok(url) => Some(url.trim().to_string()),
        Err(_) => Some(DEFAULT_HEALTH.to_string()),
    }
}

/// Asks the embedder for its health (which loads it); failures only log, a call never waits on this.
pub(crate) async fn warm() {
    let Some(url) = health_url() else { return };
    let started = std::time::Instant::now();
    let reply = reqwest::Client::new().get(&url).timeout(Duration::from_secs(20)).send().await;
    match reply {
        Ok(r) if r.status().is_success() => {
            let ms = started.elapsed().as_millis();
            if ms > 500 {
                tracing::info!(ms, "[voice-live] memory embedder loaded for the call");
            }
        }
        Ok(r) => tracing::debug!(status = %r.status(), "[voice-live] memory embedder warm-up: not ready"),
        Err(error) => tracing::debug!(%error, "[voice-live] memory embedder warm-up failed"),
    }
}

#[cfg(test)]
#[path = "embedder_tests.rs"]
mod tests;
