//! Live voice agents: realtime, two-way speech with the agent, including tool
//! calls.
//!
//! - [`providers`]: the four live providers (Gemini Live via TinyHumans,
//!   ElevenLabs via TinyHumans, Gemini with a Google key, Sarvam) and the
//!   ticket / signed-URL minting the hosted ones need.
//! - [`session`]: starts a session for a chat thread. Tools come from the
//!   orchestrator's session host and run through approvals and tool policy
//!   (`agent::tinyagents::live_harness`) via `tinyagents-live`.
//! - [`ws`]: the `/ws/live-voice` WebSocket that carries audio and events
//!   (`http-server` builds only).
//! - [`persist`]: final transcripts are saved into the thread.
//! - [`claims`]: a meaning-based check that the model didn't claim work or facts nothing backs.
//! - [`timing`]: optional per-turn timing records (`OPENHUMAN_VOICE_TIMING=1`).
//! - [`ops`] / [`schemas`]: the `voice.live_*` RPCs (catalogue, settings,
//!   provider test).
//!
//! Provider protocols live in `tinyliveagents`; nothing here speaks a
//! provider's wire format.

#[cfg(feature = "http-server")]
mod claims;
mod error;
#[cfg(feature = "http-server")]
mod handoff;
pub mod ops;
mod persist;
mod providers;
pub mod schemas;
mod session;
#[cfg(feature = "http-server")]
mod timing;
pub mod types;
#[cfg(feature = "http-server")]
pub mod ws;

pub use schemas::{live_controller_schemas, live_registered_controllers, live_schemas};
