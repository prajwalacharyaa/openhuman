package app.mochi.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One live voice call with Mochi: the same `/ws/live-voice` session the desktop app and the browser page use.
 * Mic → 16 kHz PCM16 frames of 100 ms; Mochi's audio comes back as PCM16 at the ready frame's rate.
 * The phone's voice-call audio path (echo cancellation) lets December talk over Mochi to interrupt.
 */
object Call {
    interface Listener {
        fun onStatus(text: String)
        fun onTranscript(role: String, text: String, final: Boolean)
        fun onEnded()
    }

    private const val RATE = 16_000
    private const val FRAME_BYTES = 3_200 // 100 ms of 16 kHz PCM16
    private val main = Handler(Looper.getMainLooper())

    var listener: Listener? = null

    /** One line said on the call. */
    data class Said(val role: String, val text: String, val final: Boolean)

    /**
     * What's been said on this call, kept for the whole call (not by the screen), so going Home and back to the
     * call shows it all, including what was said meanwhile. Changed on the main thread only; cleared at a new call.
     */
    val said = androidx.compose.runtime.mutableStateListOf<Said>()

    /** When anyone last spoke (ms): Mochi gets sleepy after a long silence. */
    @Volatile var lastSpoke = 0L
        private set
    @Volatile var active = false
        private set
    @Volatile var muted = false
    var speaker = true
        private set
    /** When this call started (ms): its Claude tokens are the ones used since. */
    @Volatile var startedAtMs = 0L
        private set
    /** The chat this call is in (the chat screen shows Silent there instead of Call). */
    val thread: String? get() = threadId
    @Volatile private var sentBytes = 0L
    @Volatile private var heardBytes = 0L
    @Volatile private var outRate = 24_000

    /**
     * This call's Gemini tokens, estimated: Google counts audio at about 32 tokens a second, for what the phone sent
     * (16 kHz PCM16, silence included: Gemini still receives it) and what Mochi said (PCM16 at the output rate).
     */
    val geminiTokens: Long get() = ((sentBytes / 32_000.0 + heardBytes / (outRate * 2.0)) * 32).toLong()

    /** Silent: Mochi's voice isn't played; its words still appear on the call screen (December keeps talking). */
    @Volatile var silent = false
    var status = ""
        private set

    private lateinit var app: Context
    private var ws: WebSocket? = null
    private var call: JSONObject? = null // set when Mochi called December
    private var threadId: String? = null
    private var startedAt: java.time.Instant = java.time.Instant.now()
    @Volatile private var answered = false
    @Volatile private var flushPlayback = false
    /** December said "stop" / "be quiet": Mochi's audio is dropped until December speaks again. */
    @Volatile private var quiet = false
    /** December said "Mochi, freeze": the mic isn't sent and Mochi says nothing until "Mochi, unfreeze" (heard on
     *  the phone, see [Unfreeze]) or the Unfreeze button. */
    @Volatile var frozen = false
        private set
    private val playback = LinkedBlockingQueue<ByteArray>()

    /** Start a call. `mochiCall` is the ring event when answering Mochi; null when December calls. */
    @Synchronized
    fun start(c: Context, mochiCall: JSONObject?) {
        if (active) return
        app = c.applicationContext
        val request = runCatching {
            Request.Builder().url(Mochi.voiceUrl(app) + "?token=" + URLEncoder.encode(Mochi.token(app), "UTF-8")).build()
        }.getOrElse {
            status("The server address isn't valid: change it on the home screen.")
            app.stopService(Intent(app, CallService::class.java))
            main.post { listener?.onEnded() }
            return
        }
        active = true
        answered = false
        muted = false
        silent = false
        quiet = false
        frozen = false
        call = mochiCall
        startedAt = java.time.Instant.now()
        startedAtMs = System.currentTimeMillis()
        sentBytes = 0
        heardBytes = 0
        lastSpoke = System.currentTimeMillis()
        main.post { said.clear() }
        threadId = mochiCall?.optString("thread_id")?.takeIf { it.isNotEmpty() } ?: Mochi.callThread(app)
        playback.clear()
        val am = app.getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(true)
        status("Connecting…")
        ws = Mochi.http.newWebSocket(request, Socket())
        main.post { MochiWidget.update(app, force = true) }
    }

    @Synchronized
    fun end() {
        if (!active) return
        active = false
        Doing.addGeminiToday(app, geminiTokens)
        frozen = false
        Unfreeze.stop()
        ws?.let { runCatching { it.send("""{"type":"stop"}"""); it.close(1000, "hang up") } }
        ws = null
        val am = app.getSystemService(AudioManager::class.java)
        am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
        val ended = call
        val thread = threadId
        val since = startedAt
        if (ended == null && answered && thread == Mochi.thread(app)) Mochi.touchThread(app)
        if (ended != null) {
            Thread {
                runCatching { Mochi.callDone(app, ended.getString("id"), if (answered) "answered" else "missed") }
                // a reminder has nothing for the text brain to carry on with
                if (answered && thread != null && !ended.optBoolean("reminder")) {
                    runCatching { Mochi.continueAfterCall(app, thread, since) }
                }
            }.start()
        }
        app.stopService(Intent(app, CallService::class.java))
        main.post { listener?.onEnded(); MochiWidget.update(app, force = true) }
    }

    /** Back from "Mochi, freeze": the mic is sent again and Mochi says it's there. */
    @Synchronized
    fun unfreeze() {
        if (!frozen) return
        Unfreeze.stop()
        frozen = false
        quiet = false
        status("Listening")
        ws?.send(JSONObject().put("type", "text").put("text",
            "(December is back after asking you to stay silent. Say in a few words that you're here.)").toString())
    }

    /**
     * Mochi rang while December is already on a call with it: the question goes into this call instead
     * (true), so it's answered here rather than missed. Not while frozen: December asked for silence.
     */
    @Synchronized
    fun relay(call: JSONObject): Boolean {
        val socket = ws ?: return false
        if (frozen || !answered) return false
        val question = call.optString("question")
        val text = if (call.optBoolean("reminder")) {
            "(Server note, not December speaking: a reminder is due. At a pause, give it in a few words: $question)"
        } else {
            "(Server note, not December speaking: Mochi's typed side needs December to decide something. At a " +
                "pause, ask them: $question. When they answer, hand their answer to Claude with mochi-ask.)"
        }
        return socket.send(JSONObject().put("type", "text").put("text", text).toString())
    }

    /** Freeze now: the Freeze button, or Mochi itself (`mochi-phone freeze`, when it understood December). */
    fun freezeNow(): Boolean {
        val socket = ws ?: return false
        if (!active) return false
        if (!frozen) main.post { freeze(socket) }
        return true
    }

    private fun freeze(socket: WebSocket) {
        frozen = true
        quiet = true
        socket.send("""{"type":"interrupt"}""")
        playback.clear()
        flushPlayback = true
        val why = Unfreeze.start(app) { unfreeze() }
        status(if (why == null) "Frozen: Mochi hears nothing. Say \"Mochi, unfreeze\" or tap Unfreeze."
               else "Frozen: Mochi hears nothing. Tap Unfreeze to continue ($why).")
    }

    fun setSpeaker(on: Boolean) {
        speaker = on
        if (!::app.isInitialized) return
        val am = app.getSystemService(AudioManager::class.java)
        if (on) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { am.setCommunicationDevice(it) }
        } else {
            am.clearCommunicationDevice() // earpiece, or a headset when one is connected
        }
    }

    private fun status(text: String) {
        status = text
        main.post { listener?.onStatus(text) }
    }

    private class Socket : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val start = JSONObject().put("type", "start").put("thread_id", threadId ?: JSONObject.NULL)
                .put("client_id", "mochi-phone").put("input_sample_rate", RATE)
            webSocket.send(start.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return // a hung-up call's late message
            val f = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (f.optString("type")) {
                "ready" -> {
                    answered = true
                    f.optString("thread_id").takeIf { it.isNotEmpty() && it != "null" }?.let {
                        threadId = it
                        if (call == null) Mochi.setThread(app, it)
                    }
                    startAudio(webSocket, f.optInt("output_sample_rate", 24_000).takeIf { it in 8_000..48_000 } ?: 24_000)
                    status("Listening")
                    call?.let {
                        // picked up: December decides on this call, so the 3-minute fallback mustn't run meanwhile
                        Thread { runCatching { Mochi.callDone(app, it.getString("id"), "answered") } }.start()
                        val hello = if (it.optBoolean("reminder")) {
                            "(December picked up the phone call you made. Greet them in a few words and give them " +
                                "this reminder; there's nothing to decide: ${it.optString("question")})"
                        } else {
                            "(December picked up the phone call you made. Greet them in a few words, " +
                                "say what you need decided and why, and ask: ${it.optString("question")})"
                        }
                        webSocket.send(JSONObject().put("type", "text").put("text", hello).toString())
                    }
                }
                "transcript" -> {
                    val role = f.optString("role")
                    val line = f.optString("text")
                    val final = f.optBoolean("final")
                    if (frozen) return // a late line from before the freeze
                    if (role == "user" && final && isFreeze(line)) {
                        freeze(webSocket)
                    } else if (role == "user" && final) {
                        quiet = isStop(line)
                        if (quiet) {
                            // say nothing at all until December speaks again: cut Mochi off and drop its reply
                            webSocket.send("""{"type":"interrupt"}""")
                            playback.clear()
                            flushPlayback = true
                            status("Quiet. Say something when you want Mochi again.")
                        }
                    }
                    if (role == "user" || !quiet) main.post {
                        lastSpoke = System.currentTimeMillis()
                        val i = said.indexOfLast { it.role == role && !it.final }
                        val entry = Said(role, line, final)
                        if (i >= 0) said[i] = entry else said.add(entry)
                        listener?.onTranscript(role, line, final)
                    }
                }
                "tool_started" -> {
                    // the step itself (with its detail) comes from the server's activity feed
                    main.postDelayed({ MochiWidget.update(app) }, 300)
                    if (!frozen) status("Checking (${f.optString("name")})…")
                }
                "tool_finished", "turn_complete" -> if (!frozen) status("Listening")
                "interrupted" -> { playback.clear(); flushPlayback = true }
                "error" -> { status("Error: ${f.optString("message")}"); if (f.optBoolean("fatal")) end() }
                "closed" -> end()
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket === ws) heardBytes += bytes.size
            if (webSocket === ws && !quiet) playback.offer(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            status(if (response?.code == 401 || response?.code == 403) "The server refused the token."
                   else "Can't reach Mochi. Is Tailscale on?")
            main.postDelayed({ if (ws === webSocket) end() }, 2_000)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket === ws) end()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket === ws) end()
        }
    }

    /** "Stop", "be quiet", "silent", "shut up", "चुप"… said on its own (a short phrase, not a sentence). */
    private fun isStop(said: String): Boolean {
        val words = said.lowercase().replace(Regex("[^\\p{L}\\p{M}\\s]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 4) return false
        val phrase = words.joinToString(" ")
        return STOP.any { phrase == it || phrase == "mochi $it" || phrase == "$it mochi" || phrase == "$it please" }
    }

    /** "Mochi freeze", "Mochi don't talk", "Mochi silent"…: Mochi's name and a freeze phrase, said on their own.
     *  Said as a short command, the live transcription often mishears it ("mucho frío", "Mochi fries", 4 Nov),
     *  so both words go by how they sound; the short phrase with the name keeps sentences from matching. */
    private fun isFreeze(said: String): Boolean {
        val words = said.lowercase().replace("'", "").replace("’", "").replace(Regex("[^\\p{L}\\p{M}\\s]"), " ")
            .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        // "freeze" on its own is a command too (it isn't something people say mid-sentence)
        if (words.size == 1) return FREEZE_WORD.matches(words[0]) && !words[0].startsWith("breeze")
        if (words.size !in 2..5) return false
        val rest = when {
            NAME.matches(words.first()) -> words.drop(1)
            NAME.matches(words.last()) -> words.dropLast(1)
            else -> return false
        }.filter { it != "now" && it != "please" }
        return (rest.size == 1 && FREEZE_WORD.matches(rest[0])) || rest.joinToString(" ") in FREEZE
    }

    // Mochi as transcription spells it: mochi, moochi, mocchi, mochee, mochy, mucho, muchi…, or in Devanagari
    private val NAME = Regex("m[ou]{1,2}c{1,2}h?(i|ii|ee|ie|y|o)s?|मोची|मोचि|मुची")
    // freeze, frees, frieze, fries, frío, breeze…, or फ्रीज / फ्रिज (with or without the nukta)
    private val FREEZE_WORD = Regex("fr(ee|ie|i|e|í)+[sz]{1,2}e?[sd]?|fries|fr[ií]o|breeze|फ्र[ीि](ज\u093C?|\u095B)")
    private val FREEZE = setOf("dont talk", "do not talk", "dont speak", "do not speak", "silent", "be silent",
        "stay silent", "go silent", "silence")

    private val STOP = setOf("stop", "stop it", "stop talking", "silent", "be silent", "silence", "quiet", "be quiet",
        "shut up", "hush", "pause", "enough", "chup", "chup lag", "chup basa", "चुप", "चुप लाग", "चुप बस", "रोक")

    private fun startAudio(socket: WebSocket, outRate: Int) {
        this.outRate = outRate
        Thread({ record(socket) }, "mochi-mic").start()
        Thread({ play(socket, outRate) }, "mochi-speaker").start()
    }

    /** Runs an audio thread's body; an audio failure ends the call instead of crashing the app. */
    private fun audio(socket: WebSocket, body: () -> Unit) {
        try {
            body()
        } catch (e: Exception) {
            if (ws === socket) {
                status("Audio problem: ${e.message ?: "the microphone or speaker is busy"}")
                end()
            }
        }
    }

    private fun record(socket: WebSocket) = audio(socket) {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("microphone permission is off")
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("the microphone is busy")
        }
        val echo = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null
        val noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) else null
        echo?.enabled = true
        noise?.enabled = true
        val buf = ByteArray(FRAME_BYTES)
        val silence = ByteArray(FRAME_BYTES)
        try {
            rec.startRecording()
            while (active && ws === socket) {
                if (Unfreeze.needsMic) {
                    // checking what came after "Mochi" on the phone: the recognizer needs the mic; the call stays open
                    rec.stop()
                    while (Unfreeze.needsMic && active && ws === socket) {
                        socket.send(silence.toByteString())
                        sentBytes += silence.size
                        Thread.sleep(100)
                    }
                    rec.startRecording()
                    continue
                }
                var n = 0
                while (n < FRAME_BYTES && active) {
                    val got = rec.read(buf, n, FRAME_BYTES - n)
                    if (got <= 0) break
                    n += got
                }
                if (n == FRAME_BYTES) {
                    socket.send((if (muted || frozen) silence else buf).toByteString())
                    sentBytes += buf.size
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            echo?.release()
            noise?.release()
        }
    }

    private fun play(socket: WebSocket, rate: Int) = audio(socket) {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 2)) // ~200 ms: small, so interrupting is quick
            .build()
        try {
            track.play()
            while (active && ws === socket) {
                if (flushPlayback) {
                    flushPlayback = false
                    track.pause(); track.flush(); track.play()
                }
                val chunk = playback.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (!flushPlayback && !silent) track.write(chunk, 0, chunk.size)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
