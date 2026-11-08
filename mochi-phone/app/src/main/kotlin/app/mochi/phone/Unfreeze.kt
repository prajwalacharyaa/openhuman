package app.mochi.phone

import ai.picovoice.porcupine.Porcupine
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File

/**
 * Bringing Mochi back after "Mochi, freeze", on the phone only: the "Mochi" wake word (Porcupine, fed the call's
 * own microphone frames, which aren't sent while frozen), then Android's on-device speech recognizer for a few
 * seconds to hear "unfreeze", "are you there" or "let's do this". Nothing leaves the phone; anything else said
 * after "Mochi" (someone mentioning Mochi) is ignored.
 */
object Unfreeze {
    private const val LISTEN_MS = 4_000L
    // the recognizer starts a moment after "Mochi", so the phrase's first word can be lost: any of these will do
    private val BACK = setOf("unfreeze", "there", "this", "back", "here", "wake", "resume")
    private val main = Handler(Looper.getMainLooper())

    private var app: Context? = null
    private var porcupine: Porcupine? = null
    private var pending = ShortArray(0) // samples short of a whole Porcupine frame
    private var recognizer: SpeechRecognizer? = null
    private var onBack: (() -> Unit)? = null
    /** No wake word set up: the on-device recognizer listens the whole time Mochi is frozen (nothing leaves the
     *  phone), for "unfreeze" or "Mochi" plus a back word. */
    private var phraseMode = false
    private const val PHRASE_LISTEN_MS = 8_000L
    private val NAME = Regex("m[ou]{1,2}c{1,2}h?(i|ii|ee|ie|y|o)s?|मोची|मोचि|मुची")
    private val UNFREEZE = Regex("\\b(un|on|an|en)\\s?fr(ee|ie|i|e)+[sz]e?\\b|अनफ्रीज")

    /** The recognizer has the microphone: the call sends silence meanwhile. */
    @Volatile var needsMic = false
        private set

    /** Starts listening for "Mochi". Null when saying "Mochi, unfreeze" works, else why only a tap does. */
    @Synchronized
    fun start(c: Context, back: () -> Unit): String? {
        stop()
        val keyword = File(c.filesDir, WakeService.KEYWORD_FILE)
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) return "this phone has no on-device speech recognition"
        if (!keyword.exists() || Mochi.wakeKey(c).isBlank()) {
            // no wake word: listen for the phrase itself, on the phone, until unfrozen
            app = c.applicationContext
            onBack = back
            phraseMode = true
            needsMic = true
            main.postDelayed({ listen() }, 300)
            return null
        }
        porcupine = runCatching {
            Porcupine.Builder().setAccessKey(Mochi.wakeKey(c)).setKeywordPath(keyword.absolutePath).setSensitivity(0.6f)
                .build(c.applicationContext)
        }.getOrElse { return "the wake word didn't start: ${it.message}" }
        app = c.applicationContext
        onBack = back
        return null
    }

    @Synchronized
    fun stop() {
        phraseMode = false
        main.removeCallbacksAndMessages(null)
        recognizer?.let { r -> main.post { r.destroy() } }
        recognizer = null
        needsMic = false
        porcupine?.let { runCatching { it.delete() } }
        porcupine = null
        pending = ShortArray(0)
        onBack = null
    }

    /** One frame of the call's microphone (16 kHz PCM16, little-endian) while frozen. */
    @Synchronized
    fun feed(frame: ByteArray) {
        val engine = porcupine ?: return
        if (needsMic) return
        val samples = ShortArray(frame.size / 2) {
            ((frame[2 * it].toInt() and 0xFF) or (frame[2 * it + 1].toInt() shl 8)).toShort()
        }
        val all = pending + samples
        val size = engine.frameLength
        var at = 0
        while (all.size - at >= size) {
            if (engine.process(all.copyOfRange(at, at + size)) >= 0) {
                pending = ShortArray(0)
                needsMic = true
                main.post { listen() }
                return
            }
            at += size
        }
        pending = all.copyOfRange(at, all.size)
    }

    /** "Mochi" was heard: what comes next decides. */
    private fun listen() {
        val c = app ?: return
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(results: Bundle) { if (says(results)) back(r) }
            override fun onResults(results: Bundle) { if (says(results)) back(r) else done(r) }
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
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        main.postDelayed({ if (recognizer === r) r.startListening(intent) }, 150) // the call's mic lets go first
        main.postDelayed({ done(r) }, if (phraseMode) PHRASE_LISTEN_MS else LISTEN_MS)
    }

    private fun says(results: Bundle): Boolean =
        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().any { heard ->
            val text = heard.lowercase().replace("'", "")
            val words = text.split(Regex("\\W+"))
            if (phraseMode) {
                // stricter without the wake word: "unfreeze", or "Mochi" with a back word ("Mochi, I'm back")
                UNFREEZE.containsMatchIn(text) || (words.any { NAME.matches(it) } && words.any { it in BACK })
            } else {
                words.any { it in BACK }
            }
        }

    /** Not an unfreeze: give the microphone back and keep waiting for "Mochi". */
    private fun done(r: SpeechRecognizer) {
        if (recognizer !== r) return
        recognizer = null
        r.destroy()
        if (phraseMode && onBack != null) {
            main.postDelayed({ if (phraseMode) listen() }, 250) // keep listening while frozen
        } else {
            needsMic = false
        }
    }

    private fun back(r: SpeechRecognizer) {
        if (recognizer !== r) return
        phraseMode = false
        needsMic = false
        val then = onBack
        done(r)
        then?.invoke()
    }
}
