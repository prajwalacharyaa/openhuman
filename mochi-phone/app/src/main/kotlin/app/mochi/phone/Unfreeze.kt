package app.mochi.phone

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Waiting quietly for "Mochi", on the phone only, while a call rests (a silence) or is frozen ("Mochi, freeze"):
 * Android's on-device speech recognizer listens, and "Mochi" alone wakes it in both states ("Mochi, what's the
 * time?" wakes it and the words are passed on). "Unfreeze" works too. Nothing leaves the phone; anything else said
 * is ignored.
 *
 * Quiet while waiting (December 2026-11-28: "the voice is going off and on again and again"): the recognizer plays a
 * start/stop sound each round, so the system and notification sounds are muted while it listens (put back after),
 * a round is as long as the recognizer allows (a segmented session where the phone supports one), and the wake
 * itself is one short vibration.
 */
object Unfreeze {
    private const val LISTEN_MS = 30_000L
    // "Mochi" as phones mishear it (Moshi, Moti, Mosi, Muchi), one word of the sentence
    private val NAME = Regex("m[ou]{1,2}(c{1,2}h?|tch|sh|s|t)(i|ii|ee|ie|y)s?")
    // and in Devanagari, searched in the whole sentence: where \W is ASCII-only (plain Java), splitting into words
    // breaks Devanagari apart; this works whichever way the phone's regex treats it
    private val NAME_NE = Regex("मोची|मोचि|मुची|मोशी|मोटी")
    private val UNFREEZE = Regex("\\b(un|on|an|en)\\s?fr(ee|ie|i|e)+[sz]e?\\b|अनफ्रीज")
    // The sentence that asked for silence is often still being said when listening starts ("Mochi, stop there",
    // "Mochi, mute, not here"): for this long after a freeze only a plain "unfreeze" brings Mochi back
    // (2026-11-16: four freezes ended by themselves within 1 to 17 seconds). Resting has no such sentence.
    private const val SETTLE_MS = 6_000L
    // the streams the recognizer's start/stop sounds play on, muted while waiting
    private val QUIET_STREAMS = intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)
    @Volatile private var startedAt = 0L
    private val main = Handler(Looper.getMainLooper())

    private var app: Context? = null
    private var recognizer: SpeechRecognizer? = null
    private var onBack: ((String) -> Unit)? = null
    private var listening = false
    /** Frozen (December asked for silence): the settle time applies. */
    private var afterFreeze = false
    private var heard = ""
    private val muted = mutableListOf<Int>()

    /** The recognizer has the microphone: the call sends silence meanwhile. */
    @Volatile var needsMic = false
        private set

    /** Starts listening. Null when saying "Mochi" works, else why only a tap does. */
    @Synchronized
    fun start(c: Context, afterFreeze: Boolean = false, back: (String) -> Unit): String? {
        stop()
        this.afterFreeze = afterFreeze
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) return "this phone has no on-device speech recognition"
        app = c.applicationContext
        onBack = back
        listening = true
        startedAt = android.os.SystemClock.elapsedRealtime()
        needsMic = true
        quiet(true)
        main.postDelayed({ listen() }, 300)
        return null
    }

    @Synchronized
    fun stop() {
        listening = false
        main.removeCallbacksAndMessages(null)
        recognizer?.let { r -> main.post { r.destroy() } }
        recognizer = null
        needsMic = false
        onBack = null
        // after the recognizer's last sound has played, the phone's sounds come back
        main.postDelayed({ if (!listening) quiet(false) }, 600)
    }

    /** One listening round (restarted until Mochi wakes). */
    private fun listen() {
        val c = app ?: return
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            // the whole sentence ("Mochi, what's the time?"), so it can be passed on
            override fun onResults(results: Bundle) { if (says(results)) back(r) else done(r) }
            override fun onSegmentResults(segmentResults: Bundle) { if (says(segmentResults)) back(r) }
            override fun onEndOfSegmentedSession() = done(r)
            override fun onPartialResults(results: Bundle) {}
            override fun onError(error: Int) = done(r)
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, LISTEN_MS)
            // one long session that hands over each sentence, instead of a round (and its sounds) per sentence;
            // a phone that can't ignores it and ends the round as before
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
        main.postDelayed({ if (recognizer === r) r.startListening(intent) }, 150) // the call's mic lets go first
        main.postDelayed({ done(r) }, LISTEN_MS + 2_000)
    }

    private fun says(results: Bundle): Boolean =
        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().any { said ->
            val text = said.lowercase().replace("'", "")
            val named = text.split(Regex("\\W+")).any { NAME.matches(it) } || NAME_NE.containsMatchIn(text)
            val settled = !afterFreeze || android.os.SystemClock.elapsedRealtime() - startedAt > SETTLE_MS
            (UNFREEZE.containsMatchIn(text) || (named && settled)).also { if (it) heard = said }
        }

    /** Not a wake: listen again while still waiting. */
    private fun done(r: SpeechRecognizer) {
        if (recognizer !== r) return
        recognizer = null
        r.destroy()
        if (listening && onBack != null) {
            main.postDelayed({ if (listening) listen() }, 250) // keep listening while waiting
        } else {
            needsMic = false
        }
    }

    private fun back(r: SpeechRecognizer) {
        if (recognizer !== r) return
        listening = false
        needsMic = false
        val then = onBack
        val words = heard
        done(r)
        buzz()
        main.postDelayed({ quiet(false) }, 600)
        then?.invoke(words)
    }

    /** Mutes (true) or puts back (false) the streams the recognizer's sounds play on; only what this muted. */
    @Synchronized
    private fun quiet(on: Boolean) {
        val am = app?.getSystemService(AudioManager::class.java) ?: return
        if (on) {
            for (s in QUIET_STREAMS) {
                if (s in muted || am.isStreamMute(s)) continue
                // muting the notification stream can need Do Not Disturb access on some phones: then it stays on
                if (runCatching { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0) }.isSuccess) muted.add(s)
            }
        } else {
            for (s in muted) runCatching { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) }
            muted.clear()
        }
    }

    /** One short buzz: Mochi heard its name. */
    private fun buzz() {
        val c = app ?: return
        runCatching {
            c.getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }
}
