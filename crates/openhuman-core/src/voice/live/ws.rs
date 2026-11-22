//! `GET /ws/live-voice`: one live voice session per WebSocket.
//!
//! The client opens with a `start` frame ([`ClientFrame::Start`]), then streams
//! microphone PCM16 as binary frames and may send `text`, `interrupt` and
//! `stop`. The core answers with [`ServerFrame`] JSON events and the agent's
//! speech as binary PCM16 at the `ready` frame's `output_sample_rate`. Final
//! transcripts are saved to the session's thread as they arrive.
//!
//! Logs carry the session id, provider and event kinds — never audio or
//! transcript text.

use std::sync::Arc;
use std::time::Duration;

use axum::extract::ws::{Message, WebSocket};
use bytes::Bytes;
use futures::{SinkExt, StreamExt};
use tinyagents_live::tinyliveagents::{CloseReason, LiveEvent};
use tinyagents_live::LiveAgentEvent;

use super::error::LiveVoiceError;
use super::handoff::{
    asks_december, claude_reply_note, earlier_reply_note, earlier_threads, full_answer_note, hung_up,
    is_answer_note, resume_handle_clear, typed_text_note, wants_long_answers, ASK_CLIENT, TYPED_NOTE,
    resume_handle_set, CallChecks, HandoffEvent, NoteGate, NudgeDelay, Retelling, NUDGE, nudge_kind, unspoken_clear,
    unspoken_set, unspoken_take,
};
use super::persist::{reads_phone, TranscriptPersister};
use super::timing::{self, TimingEvent, VoiceTiming};
use super::session::{self, LiveStartRequest};
use super::types::{ClientFrame, ServerFrame, TranscriptRole};
use crate::config::Config;

const LOG_PREFIX: &str = "[voice-live]";
/// How long a client may take to send its `start` frame.
pub(crate) const START_TIMEOUT: Duration = Duration::from_secs(15);

/// What one session event turns into on the socket.
#[derive(Debug, Clone, PartialEq)]
pub(crate) enum Outbound {
    Json(ServerFrame),
    Audio(Bytes),
    Persist(TranscriptRole, String),
}

/// Translates one session event. `session_id` and `thread_id` fill the
/// `ready` frame.
pub(crate) fn map_event(
    event: LiveAgentEvent,
    session_id: &str,
    provider: &str,
    thread_id: &str,
) -> Vec<Outbound> {
    match event {
        LiveAgentEvent::ToolStarted { call_id, name } => {
            vec![Outbound::Json(ServerFrame::ToolStarted { call_id, name })]
        }
        LiveAgentEvent::ToolFinished {
            call_id,
            name,
            is_error,
            cancelled,
            ..
        } => vec![Outbound::Json(ServerFrame::ToolFinished {
            call_id,
            name,
            ok: !is_error,
            cancelled,
        })],
        LiveAgentEvent::Live(event) => match event {
            LiveEvent::Ready(info) => vec![Outbound::Json(ServerFrame::Ready {
                session_id: Some(info.session_id.unwrap_or_else(|| session_id.to_string())),
                provider: provider.to_string(),
                output_sample_rate: info.output_format.sample_rate,
                thread_id: Some(thread_id.to_string()),
            })],
            LiveEvent::Audio(pcm) => vec![Outbound::Audio(pcm)],
            LiveEvent::InputTranscript { text, is_final } => {
                transcript(TranscriptRole::User, text, is_final)
            }
            LiveEvent::OutputTranscript { text, is_final } => {
                transcript(TranscriptRole::Agent, text, is_final)
            }
            LiveEvent::Interrupted => vec![Outbound::Json(ServerFrame::Interrupted)],
            LiveEvent::TurnComplete { .. } => vec![Outbound::Json(ServerFrame::TurnComplete)],
            LiveEvent::Error { error, fatal } => {
                let error = LiveVoiceError::from(error);
                vec![Outbound::Json(ServerFrame::Error {
                    code: error.code.to_string(),
                    message: error.message,
                    fatal,
                })]
            }
            LiveEvent::Closed(reason) => {
                let mut out = Vec::new();
                let reason = match reason {
                    CloseReason::Client => "client".to_string(),
                    CloseReason::Remote { code, reason } => match code {
                        Some(code) => format!("remote ({code}) {reason}").trim().to_string(),
                        None => "remote".to_string(),
                    },
                    CloseReason::Error(error) => {
                        let error = LiveVoiceError::from(error);
                        out.push(Outbound::Json(ServerFrame::Error {
                            code: error.code.to_string(),
                            message: error.message,
                            fatal: true,
                        }));
                        "error".to_string()
                    }
                    _ => "closed".to_string(),
                };
                out.push(Outbound::Json(ServerFrame::Closed { reason }));
                out
            }
            // Tool calls are reported through ToolStarted/ToolFinished; the
            // rest has no client-visible meaning.
            _ => Vec::new(),
        },
    }
}

fn transcript(role: TranscriptRole, text: String, is_final: bool) -> Vec<Outbound> {
    let mut out = vec![Outbound::Json(ServerFrame::Transcript {
        role,
        text: text.clone(),
        is_final,
    })];
    if is_final {
        out.push(Outbound::Persist(role, text));
    }
    out
}

fn json_message(frame: &ServerFrame) -> Message {
    Message::Text(serde_json::to_string(frame).unwrap_or_default().into())
}

fn error_frame(error: &LiveVoiceError) -> ServerFrame {
    ServerFrame::Error {
        code: error.code.to_string(),
        message: error.message.clone(),
        fatal: true,
    }
}

/// Waits for and parses the `start` frame.
pub(crate) fn parse_start(message: &Message) -> Result<LiveStartRequest, LiveVoiceError> {
    let Message::Text(text) = message else {
        return Err(LiveVoiceError::invalid(
            "the first frame must be a start frame",
        ));
    };
    match serde_json::from_str::<ClientFrame>(text) {
        Ok(ClientFrame::Start {
            provider,
            thread_id,
            client_id,
            input_sample_rate,
            agent,
            dry_run,
            reconnect,
            long_answers: _,
        }) => Ok(LiveStartRequest {
            provider,
            thread_id,
            client_id,
            input_sample_rate,
            agent,
            dry_run,
            reconnect,
        }),
        Ok(_) => Err(LiveVoiceError::invalid(
            "the first frame must be a start frame",
        )),
        Err(error) => Err(LiveVoiceError::invalid(format!("bad start frame: {error}"))),
    }
}

/// Runs one live voice WebSocket to completion.
pub async fn handle_live_voice_ws(socket: WebSocket, config: Arc<Config>) {
    let session_id = uuid::Uuid::new_v4().to_string();
    let (mut sink, mut stream) = socket.split();

    let first = match tokio::time::timeout(START_TIMEOUT, stream.next()).await {
        Ok(Some(Ok(message))) => message,
        _ => {
            tracing::debug!(session_id, "{LOG_PREFIX} no start frame; closing");
            let _ = sink.close().await;
            return;
        }
    };
    let earlier = match &first {
        Message::Text(text) => earlier_threads(text),
        _ => Vec::new(),
    };
    let long_answers = matches!(&first, Message::Text(text) if wants_long_answers(text));
    let request = match parse_start(&first) {
        Ok(request) => request,
        Err(error) => {
            let _ = sink.send(json_message(&error_frame(&error))).await;
            let _ = sink.close().await;
            return;
        }
    };

    let dry_run = request.dry_run;
    let input_rate = request.input_sample_rate;
    let agent = request.agent.as_deref().and_then(|name| session::agent_voice(&config.workspace_dir, name));
    let agent_call = agent.is_some();
    let started = match session::start(config.clone(), request, &session_id).await {
        Ok(started) => started,
        Err(error) => {
            tracing::warn!(
                session_id,
                code = error.code,
                "{LOG_PREFIX} session failed to start"
            );
            let _ = sink.send(json_message(&error_frame(&error))).await;
            let _ = sink
                .send(json_message(&ServerFrame::Closed {
                    reason: "error".into(),
                }))
                .await;
            let _ = sink.close().await;
            return;
        }
    };
    let session::StartedLiveSession {
        mut session,
        provider,
        thread_id,
    } = started;
    tracing::info!(session_id, provider = %provider, "{LOG_PREFIX} session started");
    let sender = session.sender();
    let mut persister = TranscriptPersister::new(
        config.workspace_dir.clone(),
        thread_id.clone(),
        session_id.clone(),
        provider.clone(),
    );
    // a test call's sentences are not December's: they stay out of memory
    if !dry_run {
        persister = match agent {
            Some(agent) => persister.for_agent(agent.name, agent.display),
            None => persister.with_memory_log(config.clone()),
        };
    }
    let timing_on = timing::enabled_from(std::env::var(timing::TIMING_ENV).ok().as_deref());
    let mut timing = VoiceTiming::default();
    let mut checks = CallChecks::for_call(agent_call);
    checks.set_long_answers(long_answers);
    // the last sound from the phone or laptop: Gemini closes a session that hears nothing ("client inactivity",
    // 2026-11-21 on the desktop app while December waited for an answer), so silence is sent in its place
    let mut last_client_audio = std::time::Instant::now();
    let mut last_keepalive = std::time::Instant::now();
    // the memory embedder unloads after 10 idle minutes (it holds ~390 MB of the 2 GB server) and takes ~3 s to load:
    // asked for at the start of a call and every few minutes during it, so a hand-off's memory recall isn't cold
    // (2026-11-22: the first hand-off of a call waited 7 s for memory, 3.2 s of it loading the embedder)
    let mut embedder_warmed: Option<std::time::Instant> = None;
    // stranger mode at a table: replies to talk that isn't for Mochi are held back (table.rs)
    let mut table = super::table::Table::default();
    // a phone step that worked needs no words (quiet.rs): its reply is held back
    let mut quiet = super::quiet::Quiet::default();
    let mut notes = NoteGate::default();
    let mut retell = Retelling::default();
    let mut nudges = NudgeDelay::default();
    // an answer the last session on this chat never got to say (the call dropped in between)
    if !dry_run {
        if let Some(note) = unspoken_take(&thread_id, std::time::Instant::now()) {
            tracing::info!(session_id, "{LOG_PREFIX} an answer the dropped call never said: passing it to this one");
            unspoken_set(&thread_id, &note, std::time::Instant::now());
            if let Some(now) = notes.offer(note, std::time::Instant::now()) {
                retell.note_sent(&now);
                let _ = sender.send_text(now).await;
            }
        }
    }
    // reading aloud: the next part is asked for once per part, and only after the voice has said the last one
    // (2026-11-15: "keep reading" went out after the tool turn too, the same part was read two and three times over)
    let mut read_spoke = false;
    // Claude may already be working in this chat (the call was redialled or the phone passed back mid-task):
    // 2026-11-12 the new session said "I haven't started" while the fix was being built.
    if crate::web_chat::channel_web_queue_status(&thread_id)
        .await
        .is_ok_and(|status| status.value.get("active").and_then(|v| v.as_bool()) == Some(true))
    {
        checks.brain_started();
    }
    // Lets out notes that waited for December to finish talking when no model turn ends to carry them.
    let mut note_tick = tokio::time::interval(Duration::from_secs(1));
    note_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    // Claude's answers to `mochi-ask` requests arrive as web-chat events.
    let mut chat_events = crate::web_chat::subscribe_web_channel_events();

    // why the call ended, for the log (calls that "just ended" are diagnosed from this)
    let mut ended_because = String::from("unknown");
    loop {
        tokio::select! {
            incoming = stream.next() => {
                let message = match incoming {
                    None | Some(Err(_)) | Some(Ok(Message::Close(_))) => {
                        ended_because = match &incoming {
                            None => "the phone's connection closed".to_string(),
                            Some(Err(error)) => format!("the phone's connection failed: {error}"),
                            Some(Ok(Message::Close(frame))) => format!(
                                "the phone closed it ({})",
                                frame.as_ref().map_or("no reason".to_string(), |f| format!("{} {}", f.code, &*f.reason))
                            ),
                            _ => "the phone".to_string(),
                        };
                        let _ = sender.close().await;
                        break;
                    }
                    Some(Ok(message)) => message,
                };
                let result = match message {
                    Message::Binary(pcm) => {
                        last_client_audio = std::time::Instant::now();
                        if timing_on {
                            timing.on_client_audio(&pcm, std::time::Instant::now());
                        }
                        sender.send_audio(pcm).await
                    }
                    Message::Text(text) => match serde_json::from_str::<ClientFrame>(&text) {
                        Ok(ClientFrame::Text { text }) => {
                            persister.save(TranscriptRole::User, &text).await;
                            sender.send_text(text).await
                        }
                        Ok(ClientFrame::Interrupt) => sender.interrupt().await,
                        Ok(ClientFrame::Table { on }) => {
                            if on != table.on {
                                tracing::info!(session_id, on, "{LOG_PREFIX} stranger mode: other voices nearby {}",
                                    if on { "(Mochi answers only what's for it)" } else { "gone (back to normal)" });
                            }
                            table.set(on);
                            Ok(())
                        }
                        Ok(ClientFrame::Stop) => {
                            ended_because = "December hung up (stop frame)".to_string();
                            let _ = sender.close().await;
                            Ok(())
                        }
                        Ok(ClientFrame::Start { .. }) | Err(_) => {
                            tracing::debug!(session_id, "{LOG_PREFIX} ignoring unexpected client frame");
                            Ok(())
                        }
                    },
                    _ => Ok(()),
                };
                if result.is_err() {
                    tracing::debug!(session_id, "{LOG_PREFIX} provider session already closed");
                }
            }
            chat = chat_events.recv() => {
                match chat {
                    Ok(chat) => {
                        if chat.thread_id == thread_id {
                            match chat.event.as_str() {
                                "inference_start" => checks.brain_started(),
                                "chat_done" => checks.brain_finished(asks_december(
                                    chat.full_response.as_deref().unwrap_or_default(),
                                )),
                                "chat_error" => checks.brain_finished(false),
                                _ => {}
                            }
                        }
                        let mut note = claude_reply_note(&chat, &thread_id);
                        if chat.thread_id == thread_id && chat.client_id == ASK_CLIENT
                            && matches!(chat.event.as_str(), "chat_done" | "chat_error")
                        {
                            let full = checks.wants_full_answer(std::time::Instant::now());
                            if full && chat.event == "chat_done" {
                                note = note.filter(|n| is_answer_note(n)).map(|n| full_answer_note(&n));
                                tracing::info!(session_id, "{LOG_PREFIX} December asked for all of it: the answer is read whole");
                            }
                        }
                        // what December typed goes to the voice too, so it can talk about it
                        if note.as_deref() == Some(TYPED_NOTE) {
                            let typed = crate::threads::store::blocking::get_messages(config.workspace_dir.clone(), thread_id.clone())
                                .await
                                .ok()
                                .and_then(|messages| {
                                    messages.into_iter().rev().find(|m| m.sender == "user" && !m.content.trim().is_empty())
                                })
                                .map(|m| m.content);
                            if let Some(typed) = typed {
                                note = Some(typed_text_note(&typed));
                            }
                        }
                        let note = note.or_else(|| {
                            let note = earlier_reply_note(&chat, &earlier);
                            if note.is_some() {
                                checks.answer_passed();
                                tracing::info!(session_id, "{LOG_PREFIX} an answer from earlier in this call arrived (another speaker's chat)");
                            }
                            note
                        });
                        if let Some(note) = note {
                            tracing::info!(session_id, "{LOG_PREFIX} Claude's answer arrived: passing it to the call");
                            unspoken_set(&thread_id, &note, std::time::Instant::now());
                            if let Some(now) = notes.offer(note, std::time::Instant::now()) {
                                retell.note_sent(&now);
                                let _ = sender.send_text(now).await;
                            }
                        }
                    }
                    // Lagged: a burst of other chats' events went past. The bus is a
                    // process-wide static, so it never closes.
                    Err(_) => {}
                }
            }
            _ = note_tick.tick() => {
                let now = std::time::Instant::now();
                if !dry_run && embedder_warmed.map_or(true, |at| now.duration_since(at) > super::embedder::WARM_EVERY) {
                    embedder_warmed = Some(now);
                    tokio::spawn(super::embedder::warm());
                }
                if now.duration_since(last_client_audio) > Duration::from_secs(15)
                    && now.duration_since(last_keepalive) > Duration::from_secs(10)
                {
                    if last_keepalive <= last_client_audio {
                        tracing::info!(session_id, "{LOG_PREFIX} no sound from the phone or laptop for 15 s: sending silence so the line stays open");
                    }
                    last_keepalive = now;
                    let silence = vec![0u8; (input_rate as usize / 10) * 2]; // 100 ms of PCM16 mono
                    let _ = sender.send_audio(silence).await;
                }
                if let Some(nudge) = nudges.due(std::time::Instant::now()) {
                    if nudge == NUDGE {
                        tracing::info!(session_id, "{LOG_PREFIX} said it's on it without a handoff call: nudging");
                    } else {
                        tracing::info!(session_id, kind = nudge_kind(nudge), "{LOG_PREFIX} a turn that needed a check or command ran none: nudging");
                    }
                    let _ = sender.send_text(nudge.to_string()).await;
                }
                if let Some(waited) = notes.tick(std::time::Instant::now()) {
                    retell.note_sent(&waited);
                    let _ = sender.send_text(waited).await;
                }
            }
            event = session.recv() => {
                let Some(event) = event else {
                    if ended_because == "unknown" {
                        ended_because = "the voice provider's session stream ended".to_string();
                    }
                    break;
                };
                let closing = matches!(event, LiveAgentEvent::Live(LiveEvent::Closed(_)));
                if let LiveAgentEvent::Live(LiveEvent::Closed(why)) = &event {
                    if ended_because == "unknown" {
                        ended_because = format!("the voice provider closed it: {why:?}");
                    }
                }
                if timing_on {
                    let step = match &event {
                        LiveAgentEvent::ToolStarted { call_id, .. } => {
                            Some(TimingEvent::ToolStarted { call_id: call_id.as_str() })
                        }
                        LiveAgentEvent::ToolFinished { call_id, name, .. } => Some(
                            TimingEvent::ToolFinished { call_id: call_id.as_str(), name: name.as_str() },
                        ),
                        LiveAgentEvent::Live(LiveEvent::InputTranscript { .. }) => {
                            Some(TimingEvent::UserTranscript)
                        }
                        LiveAgentEvent::Live(LiveEvent::Audio(_)) => Some(TimingEvent::AgentAudio),
                        LiveAgentEvent::Live(LiveEvent::Interrupted) => Some(TimingEvent::Interrupted),
                        _ => None,
                    };
                    if let Some(record) = step.and_then(|s| timing.on_event(s, std::time::Instant::now())) {
                        tracing::info!(session_id, ?record, "{LOG_PREFIX} timing");
                    }
                }
                match &event {
                    LiveAgentEvent::Live(LiveEvent::Audio(_)) => {
                        notes.agent_audio();
                        retell.agent_spoke();
                    }
                    LiveAgentEvent::Live(LiveEvent::InputTranscript { is_final, .. }) => {
                        notes.heard(std::time::Instant::now());
                        if *is_final {
                            retell.user_spoke();
                            // December said something: reading pauses ("carry on" starts it again)
                            crate::agent::tinyagents::middleware::reading_stop(&thread_id);
                        }
                    }
                    LiveAgentEvent::Live(LiveEvent::Interrupted) => {
                        if retell.skips_agent_line() {
                            unspoken_clear(&thread_id); // December cut the answer off: heard enough
                        }
                        notes.interrupted(std::time::Instant::now())
                    }
                    // after the model's turn; while December is still talking the notes keep waiting
                    LiveAgentEvent::Live(LiveEvent::TurnComplete { .. }) => {
                        if retell.skips_agent_line() {
                            unspoken_clear(&thread_id); // the answer was said
                        }
                        retell.turn_over();
                        if let Some(waited) = notes.turn_over(std::time::Instant::now()) {
                            retell.note_sent(&waited);
                            let _ = sender.send_text(waited).await;
                        } else if let Some(next) = crate::agent::tinyagents::middleware::reading_next(&thread_id)
                            .filter(|_| std::mem::take(&mut read_spoke))
                        {
                            // reading aloud: the next part, by itself, until December speaks or it ends
                            tracing::info!(session_id, "{LOG_PREFIX} reading on: the next part");
                            let _ = sender.send_text(format!(
                                "(Server note, not December speaking: keep reading. Run {next} now and read the next part \
                                 aloud, with no words of your own before it.)")).await;
                        }
                    }
                    _ => {}
                }
                // the newest handle to resume this call with if the connection drops (Gemini's ~10-minute cap)
                if let LiveAgentEvent::Live(LiveEvent::ResumptionHandle { handle, .. }) = &event {
                    if !dry_run {
                        resume_handle_set(&thread_id, handle);
                    }
                }
                if let LiveAgentEvent::Live(LiveEvent::GoAway { time_left_ms, .. }) = &event {
                    tracing::info!(session_id, ?time_left_ms, "{LOG_PREFIX} the provider will close this connection soon");
                }
                if let LiveAgentEvent::Live(LiveEvent::ToolCall(call)) = &event {
                    let command = call.args.get("command").and_then(serde_json::Value::as_str).unwrap_or_default();
                    checks.tool_call(&call.name, command, std::time::Instant::now());
                    quiet.tool_call(&call.call_id, &call.name, command);
                    if reads_phone(&call.name, &call.args) {
                        persister.mark_private(); // December's phone data stays out of memory
                    }
                }
                match &event {
                    // a tool started: what the voice says next is that tool's part, not the last one
                    LiveAgentEvent::ToolStarted { .. } => read_spoke = false,
                    LiveAgentEvent::Live(LiveEvent::OutputTranscript { text, is_final: true }) if !text.trim().is_empty() => {
                        read_spoke = true
                    }
                    _ => {}
                }
                match &event {
                    LiveAgentEvent::ToolFinished { call_id, is_error, .. } => {
                        if quiet.tool_finished(call_id, *is_error) {
                            tracing::info!(session_id, "{LOG_PREFIX} a phone step worked: its spoken reply is held back");
                        }
                    }
                    LiveAgentEvent::Live(LiveEvent::InputTranscript { .. } | LiveEvent::TurnComplete { .. }) => quiet.end(),
                    _ => {}
                }
                if let LiveAgentEvent::Live(LiveEvent::InputTranscript { text, is_final }) = &event {
                    if let Some(hush) = table.user_said(text, *is_final, std::time::Instant::now()) {
                        tracing::info!(session_id, hush, "{LOG_PREFIX} {}", if hush {
                            "table talk, not for Mochi: its reply is held back"
                        } else {
                            "December is talking to Mochi again"
                        });
                    }
                }
                let handoff_step = match &event {
                    LiveAgentEvent::ToolStarted { name, .. } => Some(HandoffEvent::ToolStarted(name.as_str())),
                    LiveAgentEvent::Live(LiveEvent::InputTranscript { text, is_final: true }) => {
                        Some(HandoffEvent::UserSaid(text.as_str()))
                    }
                    LiveAgentEvent::Live(LiveEvent::OutputTranscript { text, is_final: true }) => {
                        Some(HandoffEvent::AgentSaid(text.as_str()))
                    }
                    LiveAgentEvent::Live(LiveEvent::TurnComplete { .. }) => Some(HandoffEvent::TurnComplete),
                    _ => None,
                };
                if let Some(step) = handoff_step {
                    // a tool started or December spoke: the nudge that waited was for a turn that wasn't over
                    if matches!(step, HandoffEvent::ToolStarted(_) | HandoffEvent::UserSaid(_)) {
                        if let Some(dropped) = nudges.drop_waiting() {
                            tracing::info!(session_id, kind = nudge_kind(dropped), "{LOG_PREFIX} a nudge was not needed (a tool started or December spoke): dropped");
                        }
                    }
                    if let Some(nudge) = checks.on_event(step, std::time::Instant::now()) {
                        nudges.hold(nudge, std::time::Instant::now());
                    }
                }
                for outbound in map_event(event, &session_id, &provider, &thread_id) {
                    // a reply to table talk: neither heard on the phone nor kept in the chat
                    if (table.hush || quiet.on) && matches!(&outbound, Outbound::Audio(_)
                        | Outbound::Json(ServerFrame::Transcript { role: TranscriptRole::Agent, .. })
                        | Outbound::Persist(TranscriptRole::Agent, _))
                    {
                        continue;
                    }
                    if matches!(&outbound, Outbound::Audio(_)) {
                        table.mochi_spoke(std::time::Instant::now());
                    }
                    let sent = match outbound {
                        Outbound::Json(frame) => sink.send(json_message(&frame)).await,
                        Outbound::Audio(pcm) => sink.send(Message::Binary(pcm)).await,
                        // a retelling of Claude's answer: the answer itself is in the chat already
                        // logged (the journal stays in memory), so "I got two replies" can be checked later
                        Outbound::Persist(TranscriptRole::Agent, text) if retell.skips_agent_line() => {
                            let said: String = text.chars().take(160).collect();
                            tracing::info!(session_id, chars = text.chars().count(), said = %said, "{LOG_PREFIX} said, retelling an answer");
                            Ok(())
                        }
                        Outbound::Persist(role, text) => {
                            persister.save(role, &text).await;
                            Ok(())
                        }
                    };
                    if sent.is_err() {
                        tracing::debug!(session_id, "{LOG_PREFIX} client went away");
                        if ended_because == "unknown" {
                            ended_because = "sending to the phone failed (it went away)".to_string();
                        }
                        let _ = sender.close().await;
                    }
                }
                if closing {
                    break;
                }
            }
        }
    }
    tracing::info!(session_id, provider = %provider, reason = %ended_because, "{LOG_PREFIX} session ended");
    // a guest had the phone only for this call; a dropped session reconnects, so only hanging up ends it
    if !dry_run && hung_up(&ended_because) {
        crate::agent::tinyagents::middleware::guest_end();
        resume_handle_clear(&thread_id);   // the call is over: the next one starts fresh
    }
    let _ = sink.close().await;
    persister.finish().await;
}

#[cfg(test)]
#[path = "ws_tests.rs"]
mod tests;
