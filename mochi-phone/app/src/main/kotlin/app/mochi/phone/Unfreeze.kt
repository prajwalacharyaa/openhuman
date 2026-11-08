package app.mochi.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Bringing Mochi back after "Mochi, freeze", on the phone only: Android's on-device speech recognizer listens the
 * whole time Mochi is frozen for "unfreeze", or "Mochi" with a back word ("Mochi, I'm back", "are you there").
 * Nothing leaves the phone; anything else said is ignored.
 */
object Unfreeze {
    private const val LISTEN_MS = 8_000L
    // the phrase's first word can be lost as the recognizer restarts: any of these with "Mochi" will do
    private val BACK = setOf("unfreeze", "there", "this", "back", "here", "wake", "resume")
    private val main = Handler(Looper.getMainLooper())

    private var app: Context? = null
    private var recognizer: SpeechRecognizer? = null
    private var onBack: (() -> Unit)? = null
    private var listening = false
    private val NAME = Regex("m[ou]{1,2}c{1,2}h?(i|ii|ee|ie|y|o)s?|मोची|मोचि|मुची")
    private val UNFREEZE = Regex("\\b(un|on|an|en)\\s?fr(ee|ie|i|e)+[sz]e?\\b|अनफ्रीज")

    /** The recognizer has the microphone: the call sends silence meanwhile. */
    @Volatile var needsMic = false
        private set

    /** Starts listening. Null when saying "Mochi, unfreeze" works, else why only a tap does. */
    @Synchronized
    fun start(c: Context, back: () -> Unit): String? {
        stop()
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) return "this phone has no on-device speech recognition"
        app = c.applicationContext
        onBack = back
        listening = true
        needsMic = true
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
    }

    /** One listening round (restarted until Mochi is unfrozen). */
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
        main.postDelayed({ done(r) }, LISTEN_MS)
    }

    private fun says(results: Bundle): Boolean =
        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().any { heard ->
            val text = heard.lowercase().replace("'", "")
            val words = text.split(Regex("\\W+"))
            UNFREEZE.containsMatchIn(text) || (words.any { NAME.matches(it) } && words.any { it in BACK })
        }

    /** Not an unfreeze: listen again while still frozen. */
    private fun done(r: SpeechRecognizer) {
        if (recognizer !== r) return
        recognizer = null
        r.destroy()
        if (listening && onBack != null) {
            main.postDelayed({ if (listening) listen() }, 250) // keep listening while frozen
        } else {
            needsMic = false
        }
    }

    private fun back(r: SpeechRecognizer) {
        if (recognizer !== r) return
        listening = false
        needsMic = false
        val then = onBack
        done(r)
        then?.invoke()
    }
}
