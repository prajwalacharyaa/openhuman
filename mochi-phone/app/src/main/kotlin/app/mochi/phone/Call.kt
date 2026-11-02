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
    @Volatile var active = false
        private set
    @Volatile var muted = false
    var speaker = true
        private set
    var status = ""
        private set

    private lateinit var app: Context
    private var ws: WebSocket? = null
    private var call: JSONObject? = null // set when Mochi called December
    private var threadId: String? = null
    private var startedAt: java.time.Instant = java.time.Instant.now()
    @Volatile private var answered = false
    @Volatile private var flushPlayback = false
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
        call = mochiCall
        startedAt = java.time.Instant.now()
        threadId = mochiCall?.optString("thread_id")?.takeIf { it.isNotEmpty() } ?: Mochi.thread(app)
        playback.clear()
        val am = app.getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(true)
        status("Connecting…")
        ws = Mochi.http.newWebSocket(request, Socket())
    }

    @Synchronized
    fun end() {
        if (!active) return
        active = false
        ws?.let { runCatching { it.send("""{"type":"stop"}"""); it.close(1000, "hang up") } }
        ws = null
        val am = app.getSystemService(AudioManager::class.java)
        am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
        val ended = call
        val thread = threadId
        val since = startedAt
        if (ended != null) {
            Thread {
                runCatching { Mochi.callDone(app, ended.getString("id"), if (answered) "answered" else "missed") }
                if (answered && thread != null) runCatching { Mochi.continueAfterCall(app, thread, since) }
            }.start()
        }
        app.stopService(Intent(app, CallService::class.java))
        main.post { listener?.onEnded() }
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
                        val hello = "(December picked up the phone call you made. Greet them in a few words, " +
                            "say what you need decided and why, and ask: ${it.optString("question")})"
                        webSocket.send(JSONObject().put("type", "text").put("text", hello).toString())
                    }
                }
                "transcript" -> {
                    val role = f.optString("role")
                    val line = f.optString("text")
                    val final = f.optBoolean("final")
                    main.post { listener?.onTranscript(role, line, final) }
                }
                "tool_started" -> status("Checking (${f.optString("name")})…")
                "tool_finished", "turn_complete" -> status("Listening")
                "interrupted" -> { playback.clear(); flushPlayback = true }
                "error" -> { status("Error: ${f.optString("message")}"); if (f.optBoolean("fatal")) end() }
                "closed" -> end()
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket === ws) playback.offer(bytes.toByteArray())
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

    private fun startAudio(socket: WebSocket, outRate: Int) {
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
                var n = 0
                while (n < FRAME_BYTES && active) {
                    val got = rec.read(buf, n, FRAME_BYTES - n)
                    if (got <= 0) break
                    n += got
                }
                if (n == FRAME_BYTES) socket.send((if (muted) silence else buf).toByteString())
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
                if (!flushPlayback) track.write(chunk, 0, chunk.size)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
