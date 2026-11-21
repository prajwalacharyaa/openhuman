import { type RefObject, useEffect, useRef, useState } from 'react';
import { LuMic, LuMicOff, LuPhoneOff, LuRotateCw } from 'react-icons/lu';

import Badge from '../../components/ui/Badge';
import Button from '../../components/ui/Button';
import { useT } from '../../lib/i18n/I18nContext';
import type { RealtimeVoiceAudio } from './voice/amplitudeLipsync';
import {
  type LiveVoiceError,
  type LiveVoiceState,
  type LiveVoiceToolCall,
  useLiveVoiceSession,
} from './voice/live/useLiveVoiceSession';

/** Brand names for the provider badge (not translated). */
export const LIVE_VOICE_PROVIDER_LABELS: Record<string, string> = {
  'gemini-hosted': 'Gemini',
  'elevenlabs-hosted': 'ElevenLabs',
  gemini: 'Gemini',
  sarvam: 'Sarvam AI',
};

/** Who December can talk to on a call: Mochi, or one of its agents (the server's workspace/voice-agents). */
export const LIVE_VOICE_AGENTS: { id: string; label: string }[] = [
  { id: '', label: 'Mochi' },
  { id: 'mark', label: 'Mark (writer)' },
  { id: 'melissa', label: 'Melissa (security)' },
  { id: 'sy', label: 'SY (hiring)' },
  { id: 'samir', label: 'Samir (shares)' },
  { id: 'iris', label: 'Iris (designer)' },
  { id: 'linkedin', label: 'Nadia (LinkedIn)' },
  { id: 'researcher', label: 'Priya (research)' },
  { id: 'seo', label: 'Sage (SEO)' },
  { id: 'qa', label: 'Theo (testing)' },
];

const AGENT_KEY = 'mochi.liveVoice.agent';
const LONG_KEY = 'mochi.liveVoice.longAnswers';

function readPref(key: string): string {
  try {
    return window.localStorage.getItem(key) ?? '';
  } catch {
    return '';
  }
}

function writePref(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // private window or blocked storage: the choice lasts for this session only
  }
}

/** What the hosting surface wants to know about the session, coarsely. */
export type LiveVoicePhase = 'off' | 'connecting' | 'listening' | 'speaking';

const phaseOf = (state: LiveVoiceState): LiveVoicePhase =>
  state === 'connecting' || state === 'listening' || state === 'speaking' ? state : 'off';

export interface LiveVoiceControlsProps {
  /** Thread the session talks into (the open conversation), if any. */
  threadId?: string | null;
  /**
   * Called once on mount; returning true starts a session immediately (the
   * mascot dock click). Kept as a function so the request is consumed by the
   * mounted control rather than replayed on every render.
   */
  consumeAutoStart?: () => boolean;
  /** Sink for the mascot's lip-sync signal (see RealtimeVoiceAudio). */
  audioRef?: RefObject<RealtimeVoiceAudio>;
  /** Notified with the agent's speaking edge so the page can gate its loop. */
  onSpeakingChange?: (speaking: boolean) => void;
  /** Notified on every coarse phase change. */
  onPhaseChange?: (phase: LiveVoicePhase) => void;
}

const TOOL_VARIANT: Record<
  LiveVoiceToolCall['status'],
  'primary' | 'success' | 'danger' | 'neutral'
> = { running: 'primary', ok: 'success', failed: 'danger', cancelled: 'neutral' };

const TOOL_LABEL_KEY: Record<LiveVoiceToolCall['status'], string> = {
  running: 'voice.live.toolRunning',
  ok: 'voice.live.toolOk',
  failed: 'voice.live.toolFailed',
  cancelled: 'voice.live.toolCancelled',
};

function errorText(t: (key: string) => string, error: LiveVoiceError): string {
  switch (error.code) {
    case 'mic_denied':
      return t('voice.live.error.micDenied');
    case 'mic_unavailable':
      return t('voice.live.error.micUnavailable');
    case 'connection_failed':
    case 'core_unreachable':
      return t('voice.live.error.connection');
    default:
      return error.message || t('voice.live.error.connection');
  }
}

/**
 * Controls for a live voice-agent session: start, mute, end, live captions,
 * tool-call chips, the provider badge, and an error state with retry. Owns the
 * session (`useLiveVoiceSession`), so unmounting the control ends the call.
 */
export default function LiveVoiceControls({
  threadId = null,
  consumeAutoStart,
  audioRef,
  onSpeakingChange,
  onPhaseChange,
}: LiveVoiceControlsProps) {
  const { t } = useT();
  const session = useLiveVoiceSession();
  const { state, getOutputVolume } = session;
  const live = state === 'listening' || state === 'speaking';
  const speaking = state === 'speaking';

  // Latest start + thread in refs so the mount-only auto-start effect does not
  // re-run (and restart the call) when either changes identity.
  const startRef = useRef(session.start);
  startRef.current = session.start;
  const threadRef = useRef(threadId);
  threadRef.current = threadId;
  const [agent, setAgent] = useState(() => readPref(AGENT_KEY));
  const [longAnswers, setLongAnswers] = useState(() => readPref(LONG_KEY) === '1');
  const choiceRef = useRef({ agent, longAnswers });
  choiceRef.current = { agent, longAnswers };
  // Survives StrictMode's simulated unmount/remount: the request is consumed
  // once, and the remount (whose cleanup tore the first attempt down) restarts.
  const wantAutoStartRef = useRef(false);
  useEffect(() => {
    if (consumeAutoStart?.()) wantAutoStartRef.current = true;
    if (wantAutoStartRef.current)
      void startRef.current({
        threadId: threadRef.current,
        agent: choiceRef.current.agent || null,
        longAnswers: choiceRef.current.longAnswers,
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- mount-only by design
  }, []);

  // Publish the output-loudness accessor for the mascot's lip-sync. A ref, not
  // state: the mascot samples it once per animation frame.
  useEffect(() => {
    if (audioRef?.current) {
      audioRef.current.getOutputVolume = live ? getOutputVolume : null;
      audioRef.current.speaking = speaking;
    }
    onSpeakingChange?.(speaking);
  }, [audioRef, live, speaking, getOutputVolume, onSpeakingChange]);

  const phase = phaseOf(state);
  useEffect(() => {
    onPhaseChange?.(phase);
  }, [phase, onPhaseChange]);

  // A session that ends mid-speech must not leave the mouth frozen open.
  useEffect(
    () => () => {
      if (audioRef?.current) {
        audioRef.current.getOutputVolume = null;
        audioRef.current.speaking = false;
      }
      onSpeakingChange?.(false);
      onPhaseChange?.('off');
    },
    [audioRef, onSpeakingChange, onPhaseChange]
  );

  const startSession = () =>
    void session.start({ threadId, agent: agent || null, longAnswers });

  const status =
    state === 'connecting'
      ? t('voice.mode.connecting')
      : session.muted && live
        ? t('voice.live.muted')
        : speaking
          ? t('voice.mode.speaking')
          : state === 'listening'
            ? t('voice.mode.listening')
            : null;

  const providerLabel = session.provider
    ? (LIVE_VOICE_PROVIDER_LABELS[session.provider] ?? session.provider)
    : null;
  const recent = session.captions.slice(-2);

  return (
    <div
      className="flex w-full max-w-[420px] flex-col items-center gap-3"
      data-testid="live-voice-controls"
      data-state={state}>
      {(providerLabel || status) && (
        <div className="flex items-center gap-2 text-xs text-content-muted">
          {providerLabel && (
            <Badge
              variant="primary"
              data-testid="live-voice-provider"
              aria-label={t('voice.live.providerLabel').replace('{provider}', providerLabel)}>
              {providerLabel}
            </Badge>
          )}
          {status && (
            <span data-testid="live-voice-status" aria-live="polite">
              {status}
            </span>
          )}
        </div>
      )}

      {(recent.length > 0 || session.partial.user || session.partial.agent) && (
        <div
          className="flex max-h-28 w-full flex-col gap-1 overflow-hidden text-center text-sm"
          aria-label={t('voice.live.captionsLabel')}
          data-testid="live-voice-captions">
          {recent.map((line, index) => (
            <p
              key={`${index}-${line.role}`}
              className={line.role === 'user' ? 'text-content-muted' : 'font-medium text-content'}
              data-role={line.role}>
              {line.text}
            </p>
          ))}
          {session.partial.user && (
            <p className="italic text-content-muted" data-testid="live-voice-partial-user">
              {session.partial.user}
            </p>
          )}
          {session.partial.agent && (
            <p className="italic text-content" data-testid="live-voice-partial-agent">
              {session.partial.agent}
            </p>
          )}
        </div>
      )}

      {session.toolCalls.length > 0 && (
        <ul className="flex flex-wrap justify-center gap-1.5" data-testid="live-voice-tools">
          {session.toolCalls.map(call => (
            <li key={call.callId}>
              <Badge
                variant={TOOL_VARIANT[call.status]}
                data-testid="live-voice-tool-chip"
                data-status={call.status}
                aria-label={t(TOOL_LABEL_KEY[call.status]).replace('{name}', call.name)}>
                {call.name}
              </Badge>
            </li>
          ))}
        </ul>
      )}

      {state === 'error' && session.error && (
        <div
          role="alert"
          className="flex flex-col items-center gap-2 text-center text-xs text-coral-600 dark:text-coral-300"
          data-testid="live-voice-error">
          <span>{errorText(t, session.error)}</span>
        </div>
      )}

      {!session.active && (
        <div className="flex flex-wrap items-center justify-center gap-3 text-xs text-content-muted">
          <label className="flex items-center gap-1.5">
            Talk to
            <select
              data-testid="live-voice-agent"
              className="rounded-md border border-line bg-surface px-2 py-1 text-content"
              value={agent}
              onChange={event => {
                setAgent(event.target.value);
                writePref(AGENT_KEY, event.target.value);
              }}>
              {LIVE_VOICE_AGENTS.map(option => (
                <option key={option.id} value={option.id}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex items-center gap-1.5">
            <input
              type="checkbox"
              data-testid="live-voice-long-answers"
              checked={longAnswers}
              onChange={event => {
                setLongAnswers(event.target.checked);
                writePref(LONG_KEY, event.target.checked ? '1' : '');
              }}
            />
            Read long answers in full
          </label>
        </div>
      )}

      <div className="flex items-center gap-3">
        {session.active ? (
          <>
            <Button
              iconOnly
              variant={session.muted ? 'secondary' : 'tertiary'}
              analyticsId="live-voice-mute"
              data-testid="live-voice-mute"
              aria-pressed={session.muted}
              aria-label={session.muted ? t('voice.live.unmute') : t('voice.live.mute')}
              title={session.muted ? t('voice.live.unmute') : t('voice.live.mute')}
              disabled={!live}
              className="size-11 rounded-full"
              onClick={session.toggleMute}>
              {session.muted ? <LuMicOff className="size-5" /> : <LuMic className="size-5" />}
            </Button>
            <Button
              iconOnly
              tone="danger"
              analyticsId="live-voice-end"
              data-testid="live-voice-end"
              aria-label={t('voice.mode.stop')}
              title={t('voice.mode.stop')}
              className="size-14 rounded-full shadow-float"
              onClick={session.stop}>
              <LuPhoneOff className="size-6" />
            </Button>
          </>
        ) : state === 'error' ? (
          <Button
            analyticsId="live-voice-retry"
            data-testid="live-voice-retry"
            leadingIcon={<LuRotateCw className="size-4" />}
            onClick={startSession}>
            {t('voice.live.retry')}
          </Button>
        ) : (
          <Button
            iconOnly
            analyticsId="live-voice-start"
            data-testid="live-voice-start"
            aria-label={t('voice.mode.start')}
            title={t('voice.mode.start')}
            className="size-14 rounded-full shadow-float"
            onClick={startSession}>
            <LuMic className="size-6" />
          </Button>
        )}
      </div>
    </div>
  );
}
