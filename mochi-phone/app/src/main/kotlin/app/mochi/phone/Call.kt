package app.mochi.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    /** Muted or frozen: this much silence (1 s) tells Gemini December stopped talking; after it nothing is sent,
     *  since Gemini bills streamed audio by the second, silence included. */
    private const val QUIET_FRAMES = 10
    /** While muted / frozen, one silent frame this often keeps the call open: Gemini closes a session after ~2 min
     *  with no audio ("client inactivity"). 100 ms per 20 s = 0.5% of the time billed. */
    private const val KEEPALIVE_FRAMES = 200
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
        set(v) {
            if (field != v) {
                field = v
                notice()
                // the call screen follows, also when Mochi muted it by voice (mochi-phone mute)
                if (active && !frozen) status(if (v) "Muted: Mochi hears nothing. Tap Mute to talk again." else "Listening")
            }
        }
    var speaker = true
        private set
    /** Sleep timer: when the call ends by itself (ms; 0 = no timer). Set by "stop after 30 minutes" (mochi-phone sleep). */
    @Volatile var sleepAt = 0L
        private set
    /** How fast Mochi's voice plays (0.75 to 1.25, pitch kept): "read slower / faster" (mochi-phone speed). */
    @Volatile var speed = 1f
        private set
    /** When December last said something (ms): the night check asks after a long silence. */
    @Volatile private var userSpokeAt = 0L
    /** When the night check asked "still listening?" (ms; 0 = not asked since December last spoke). */
    @Volatile private var nightAskedAt = 0L
    /** The goodnight was asked for: the call ends a few seconds later. */
    @Volatile private var closing = false

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
    /** Every chat this call has used (Mochi's, then an agent's after a pass of the phone…): a new session hears the
     *  answers to what was handed on in the earlier ones too, instead of dropping them. */
    private val callThreads = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private var startedAt: java.time.Instant = java.time.Instant.now()
    @Volatile private var answered = false
    @Volatile private var flushPlayback = false
    /** December said "stop" / "be quiet": Mochi's audio is dropped until December speaks again. */
    @Volatile private var quiet = false
    /** Hold on (the button or the notification): the rest of the reply Mochi is giving now is dropped; its next
     *  reply plays. Gemini has no cancel of its own and, at low speech sensitivity, didn't hear "wait wait wait"
     *  over its own voice (2026-11-14: about 40 s), so this is done here, on the phone. */
    @Volatile private var cutTurn = false
    /** When Mochi's voice last arrived (ms): the Hold on button shows while it is talking. */
    @Volatile var talkingAt = 0L
        private set

    /** Mochi is talking right now. */
    fun talking(): Boolean = active && !cutTurn && System.currentTimeMillis() - talkingAt < 1_500

    /** Stop the reply Mochi is giving now, without hearing it out: the call goes on and listens. */
    fun holdOn(): Boolean {
        val socket = ws ?: return false
        if (!active) return false
        cutTurn = true
        socket.send("""{"type":"interrupt"}""")
        playback.clear()
        flushPlayback = true
        status("Stopped. Go ahead.")
        return true
    }
    /** December said "Mochi, freeze": the mic isn't sent and Mochi says nothing until "Mochi, unfreeze" (heard on
     *  the phone, see [Unfreeze]) or the Unfreeze button. */
    @Volatile var frozen = false
        private set(v) { if (field != v) { field = v; notice() } }
    /** Frozen by itself after [REST_MS] of nobody talking (Settings: "Rest when nobody talks"), not by December:
     *  "Mochi" alone wakes it, and so does Mochi starting to speak (an answer it was waiting for). */
    @Volatile var resting = false
        private set
    private var restedAt = 0L

    /** The call notification's Mute button follows the mute wherever it changed (call screen, widget, "freeze"). */
    private fun notice() {
        if (::app.isInitialized) main.post { if (active) CallService.refresh(app) }
    }
    private val playback = LinkedBlockingQueue<ByteArray>()

    /** Who December is talking with: one of Mochi's agents ("sy"), or null for Mochi itself. */
    @Volatile var agent: String? = null
        private set
    /** Why the last call ended, for the call screen ("You hung up", "The connection dropped…"). */
    @Volatile var endReason = ""
        private set
    /** Reconnect tries for the current drop (a call survives a network switch or a brief outage). */
    @Volatile private var reconnects = 0
    @Volatile private var reconnecting = false
    /** The socket a reconnect was already started for. */
    @Volatile private var dropped: WebSocket? = null

    /** Passing the phone to another speaker (a few seconds): the call screen shows it. */
    @Volatile var switching = false
        private set

    /**
     * The call's connection (2026-11-23). OkHttp only notices a dead line when a ping goes unanswered: with the shared
     * client's 20 s pings that was up to 40 s of silence before reconnecting. Pings every 8 s here, and [lineWatch]
     * reconnects as soon as the microphone's sound piles up unsent (it streams 32 KB a second, so a line that
     * stopped moving shows within seconds), sooner still right after the phone changes network.
     */
    private val voiceHttp by lazy { Mochi.http.newBuilder().pingInterval(8, TimeUnit.SECONDS).build() }
    /** Sound waiting to go out past this: the line has stopped moving. */
    private const val STALLED_BYTES = 4 * 32_000L
    /** Right after a network change: a smaller pile is enough. */
    private const val STALLED_AFTER_SWITCH_BYTES = 32_000L
    private const val LINE_WATCH_MS = 1_000L
    @Volatile private var networkChangedAt = 0L

    private val lineWatch = object : Runnable {
        override fun run() {
            if (!active) return
            val socket = ws
            val waiting = socket?.queueSize() ?: 0L
            val justSwitched = System.currentTimeMillis() - networkChangedAt in 2_000L..15_000L
            if (socket != null && answered && !reconnecting && !switching &&
                waiting > (if (justSwitched) STALLED_AFTER_SWITCH_BYTES else STALLED_BYTES)) {
                Crashes.note(app, "call: the line stopped moving (${waiting / 1000} KB unsent" +
                    (if (justSwitched) ", after a network change" else "") + "): reconnecting now")
                reconnect(if (justSwitched) "the network changed" else "the connection stalled", socket)
            }
            main.postDelayed(this, LINE_WATCH_MS)
        }
    }

    /** Wi-Fi or mobile data coming or going under Tailscale: [lineWatch] looks harder for the next seconds. */
    private val networks = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { networkChangedAt = System.currentTimeMillis() }
        override fun onLost(network: Network) { networkChangedAt = System.currentTimeMillis() }
    }

    private fun watchNetworks(on: Boolean) {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            if (on) cm.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networks)
            else cm.unregisterNetworkCallback(networks)
        }
    }

    private fun voiceRequest(): Request =
        Request.Builder().url(Mochi.voiceUrl(app) + "?token=" + URLEncoder.encode(Mochi.token(app), "UTF-8")).build()

    /**
     * Start a call. `mochiCall` is the ring event when answering Mochi; null when December calls. [withAgent]: a
     * call with one of Mochi's agents, in its chat [agentThread] (its voice and persona; Claude works as it there).
     */
    @Synchronized
    fun start(c: Context, mochiCall: JSONObject?, withAgent: String? = null, agentThread: String? = null) {
        if (active) return
        app = c.applicationContext
        val request = runCatching { voiceRequest() }.getOrElse {
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
        resting = false
        call = mochiCall
        startedAt = java.time.Instant.now()
        startedAtMs = System.currentTimeMillis()
        sentBytes = 0
        heardBytes = 0
        lastSpoke = System.currentTimeMillis()
        userSpokeAt = lastSpoke
        nightAskedAt = 0L
        sleepAt = 0L
        closing = false
        speed = app.getSharedPreferences("mochi-call", Context.MODE_PRIVATE).getFloat("speed", 1f)
        main.postDelayed(watch, WATCH_MS)
        main.postDelayed(lineWatch, LINE_WATCH_MS)
        networkChangedAt = 0L
        watchNetworks(true)
        main.post { said.clear() }
        callThreads.clear()
        agent = withAgent?.takeIf { agentThread != null && mochiCall == null }
        switching = false
        reconnects = 0
        reconnecting = false
        endReason = ""
        threadId = if (agent != null) agentThread
            else mochiCall?.optString("thread_id")?.takeIf { it.isNotEmpty() } ?: Mochi.callThread(app)
        playback.clear()
        val am = app.getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(true)
        status("Connecting…")
        ws = voiceHttp.newWebSocket(request, Socket())
        main.post { MochiWidget.update(app, force = true) }
    }

    /**
     * The connection went away without December hanging up: the same call reconnects (same speaker and chat), up
     * to three tries with growing pauses; then it ends with [why] shown. Not before the call was ever answered.
     */
    private fun reconnect(why: String, from: WebSocket? = ws) {
        if (!active) return
        // one socket going away reports it more than once (error frame, closed frame, onClosing, onClosed)
        synchronized(this) { if (from != null && from === dropped) return; dropped = from }
        if (!answered || reconnects >= 3) {
            endReason = why
            main.post { end(why) }
            return
        }
        reconnects++
        reconnecting = true
        status("Reconnecting… ($why)")
        main.postDelayed({
            synchronized(this) {
                if (!active) return@synchronized
                val old = ws
                playback.clear()
                ws = voiceHttp.newWebSocket(voiceRequest(), Socket())
                old?.let { runCatching { it.cancel() } }
            }
        }, if (reconnects == 1) 300L else 1_500L * (reconnects - 1))
    }

    // ---- sleep timer, night check, speed ------------------------------------------------------------------------

    private const val WATCH_MS = 15_000L
    /** Nobody talking this long: the call rests (checked every [WATCH_MS], so 45 to 60 s). */
    private const val REST_MS = 45_000L
    private const val REST_END_MS = 30 * 60_000L
    /** The night check: from this hour to [NIGHT_END], after [NIGHT_QUIET_MS] of December saying nothing. */
    private const val NIGHT_START = 21
    private const val NIGHT_END = 6
    private const val NIGHT_QUIET_MS = 20 * 60_000L
    /** No answer this long after "still listening?": goodnight. */
    private const val NIGHT_ANSWER_MS = 75_000L

    /** Every 15 s while a call is on: the sleep timer and the night check. */
    private val watch = object : Runnable {
        override fun run() {
            if (!active) return
            val now = System.currentTimeMillis()
            val hour = java.time.LocalTime.now().hour
            val night = hour >= NIGHT_START || hour < NIGHT_END
            when {
                closing -> {}
                sleepAt in 1..now -> goodnight("the sleep timer December set is up")
                nightAskedAt > 0 && now - nightAskedAt > NIGHT_ANSWER_MS -> goodnight("December didn't answer, so they're asleep")
                // resting half an hour: December left or fell asleep, so the phone stops listening and hangs up
                resting && now - restedAt > REST_END_MS -> end("Nobody talked for half an hour")
                !frozen && !muted && !Stranger.on && answered && now - maxOf(lastSpoke, talkingAt) > REST_MS &&
                    playback.isEmpty() && Mochi.restWhenQuiet(app) -> rest()
                night && nightAskedAt == 0L && now - userSpokeAt > NIGHT_QUIET_MS && !frozen -> {
                    nightAskedAt = now
                    note("(Server note, not December speaking: it's late and December hasn't said anything for 20 " +
                        "minutes. Stop where you are and ask once, softly and in a few words, whether they're still " +
                        "listening. If you were reading, the place is kept.)")
                }
            }
            main.postDelayed(this, WATCH_MS)
        }
    }

    /** A server note into the call (the model says it; it isn't December speaking). */
    private fun note(text: String) {
        ws?.send(JSONObject().put("type", "text").put("text", text).toString())
    }

    /** Ends the call after a goodnight: a few words from the voice, then hang up and lock the screen when allowed. */
    private fun goodnight(why: String) {
        closing = true
        note("(Server note, not December speaking: $why. Say goodnight in a few words, nothing else. If you were " +
            "reading, the place is saved.)")
        main.postDelayed({
            if (!active) return@postDelayed
            end("Ended by the sleep timer")
            LockAdmin.lockIfAllowed(app)
        }, 9_000L)
    }

    /** "Stop after 30 minutes" (mochi-phone sleep): minutes > 0 sets it, "off" clears it, "status" says what's left. */
    fun sleep(minutes: Int, what: String): String {
        if (!active) throw IllegalStateException("no call is on")
        return when (what) {
            "off" -> { sleepAt = 0L; main.post { notice() }; "the sleep timer is off" }
            "status" -> if (sleepAt == 0L) "no sleep timer is set"
                else "the call ends in ${((sleepAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)} minutes"
            else -> {
                sleepAt = System.currentTimeMillis() + minutes * 60_000L
                main.post { notice() }
                "the call ends in $minutes minutes" + (if (LockAdmin.allowed(app)) ", and the phone locks then" else
                    " (the screen turns off by itself; to have it lock, December turns on Lock the phone under Phone abilities)")
            }
        }
    }

    /** Minutes left on the sleep timer, or null. */
    fun sleepLeft(): Long? = sleepAt.takeIf { it > 0 }?.let { ((it - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0) }

    /** "Read slower / faster" (mochi-phone speed): kept for later calls too. */
    fun setSpeed(v: Float): String {
        speed = v.coerceIn(0.75f, 1.25f)
        app.getSharedPreferences("mochi-call", Context.MODE_PRIVATE).edit().putFloat("speed", speed).apply()
        return if (speed == 1f) "the voice plays at normal speed" else "the voice plays at ${"%.2f".format(speed)}x speed"
    }

    @Synchronized
    fun end(why: String = "You hung up") {
        if (!active) return
        main.removeCallbacks(watch)
        main.removeCallbacks(lineWatch)
        watchNetworks(false)
        sleepAt = 0L
        if (endReason.isEmpty()) endReason = why
        active = false
        Doing.addGeminiToday(app, geminiTokens)
        frozen = false
        resting = false
        Unfreeze.stop()
        VoiceId.callEnded(app)
        Stranger.callEnded()
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

    /**
     * Pass the phone: the same call continues with [target] (an agent's name, or null for Mochi) in a few seconds.
     * The new speaker gets the last few lines of the call as a note in its chat, so "what do you think of that?"
     * works. The microphone and speaker threads belong to the old connection and stop with it. Background thread.
     */
    fun switchTo(target: String?): Boolean {
        if (!active || target == agent || switching) return false
        switching = true
        if (frozen) unfreeze()
        val from = agent
        val name = target?.let { Agents.display(it, Agents.cached(app)) } ?: "Mochi"
        status("Passing you to $name…")
        // the one passing the phone goes quiet at once, and a short handover sound plays instead of words
        ws?.send("""{"type":"interrupt"}""")
        cutTurn = true          // what the old session still sends isn't played
        playback.clear()
        flushPlayback = true
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 50)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 180)
            main.postDelayed({ tone.release() }, 600)
        }
        Thread {
            val thread = runCatching {
                if (target == null) Mochi.callThread(app) ?: Mochi.newThread(app)
                else Agents.callChat(app, target)
            }.getOrNull()
            if (thread == null) {
                switching = false
                status("Couldn't pass the phone: can't reach Mochi.")
                return@Thread
            }
            val recent = said.toList().filter { it.final && it.role != "system" }.takeLast(6)
            if (recent.isNotEmpty()) runCatching {
                val by = from?.let { Agents.display(it, Agents.cached(app)) } ?: "Mochi"
                Mochi.appendNote(app, thread, "(December passed the call over from $by. Just said:\n" +
                    recent.joinToString("\n") { (if (it.role == "user") "December: " else "$by: ") + it.text } + ")")
            }
            synchronized(this) {
                if (!active) { switching = false; return@Thread }
                val old = ws
                agent = target
                threadId = thread
                playback.clear()
                ws = voiceHttp.newWebSocket(voiceRequest(), Socket())
                old?.let { runCatching { it.send("""{"type":"stop"}"""); it.close(1000, "passed the phone") } }
            }
            main.post { said.add(Said("system", "Now talking with $name", true)); MochiWidget.update(app, force = true) }
        }.start()
        return true
    }

    /** Back from "Mochi, freeze": the mic is sent again and Mochi says it's there. */
    @Synchronized
    fun unfreeze(byVoice: Boolean = false) {
        if (!frozen) return
        if (resting) return wake()
        Unfreeze.stop()
        frozen = false
        quiet = false
        status("Listening")
        // how December came back is in the note, so a call review can tell a tap from the phone mishearing
        ws?.send(JSONObject().put("type", "text").put("text",
            "(December is back after asking you to stay silent, ${if (byVoice) "by voice" else "with the button"}. " +
                "Say in a few words that you're here.)").toString())
    }

    /**
     * Mochi rang while December is already on a call with it: the question goes into this call instead
     * (true), so it's answered here rather than missed. Not while frozen: December asked for silence.
     */
    @Synchronized
    fun relay(call: JSONObject): Boolean {
        val socket = ws ?: return false
        if ((frozen && !resting) || !answered) return false
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
        if (!active || ws !== socket) return // the call ended before the freeze ran: no listener left on the mic
        resting = false
        frozen = true
        quiet = true
        socket.send("""{"type":"interrupt"}""")
        playback.clear()
        flushPlayback = true
        val why = Unfreeze.start(app) { unfreeze(byVoice = true) }
        status(if (why == null) "Frozen: Mochi hears nothing. Say \"Mochi, unfreeze\" or tap Unfreeze."
               else "Frozen: Mochi hears nothing. Tap Unfreeze to continue ($why).")
    }

    /** Nobody has talked for a while: stop sending the mic (nothing goes to Gemini) and listen on the phone. */
    private fun rest() {
        if (ws == null || frozen) return
        resting = true
        restedAt = System.currentTimeMillis()
        frozen = true
        val why = Unfreeze.start(app, nameAlone = true) { words -> wake(words) }
        status(if (why == null) "Resting. Say \"Mochi\" when you want me." else "Resting. Tap Unfreeze to continue ($why).")
    }

    /** Back from resting: by "Mochi" ([words] = what December said, passed on so it's answered), a tap, or Mochi
     *  starting to speak. */
    @Synchronized
    fun wake(words: String = "") {
        if (!resting) return
        resting = false
        Unfreeze.stop()
        frozen = false
        lastSpoke = System.currentTimeMillis()
        status("Listening")
        if (words.isNotBlank()) note("(Server note, not December speaking: the call was resting after a silence, and " +
            "December just said: \"$words\". Answer that; if it was only your name, say one word to show you're listening.)")
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
                .put("client_id", "mochi-phone").put("input_sample_rate", RATE).put("agent", agent ?: JSONObject.NULL)
                // the same call coming back: it keeps the voice it started with
                .put("reconnect", reconnecting)
                .put("long_answers", Mochi.longAnswers(app))
            val earlier = synchronized(callThreads) { callThreads.filter { it != threadId }.takeLast(8) }
            if (earlier.isNotEmpty()) start.put("earlier_threads", org.json.JSONArray(earlier))
            webSocket.send(start.toString())
            // a reconnect in stranger mode at a table: the new session must know too
            if (Stranger.on && Stranger.tableOn) webSocket.send("""{"type":"table","on":true}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return // a hung-up call's late message
            val f = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (f.optString("type")) {
                "ready" -> {
                    val first = !answered // a pass of the phone opens a new session: no second hello
                    answered = true
                    f.optString("thread_id").takeIf { it.isNotEmpty() && it != "null" }?.let {
                        threadId = it
                        callThreads.add(it)
                        if (call == null && agent == null) Mochi.setThread(app, it) // an agent's chat stays its own
                    }
                    val passed = switching && !reconnecting
                    switching = false
                    if (passed) {
                        cutTurn = false
                        // 2026-11-22: the one taking the phone answers like a person handed a phone ("Hi, Mark here"),
                        // instead of Mochi saying "Passing you to..." and being cut off mid-word
                        val name = agent?.let { Agents.display(it, Agents.cached(app)) } ?: "Mochi"
                        webSocket.send(JSONObject().put("type", "text").put("text",
                            "(December was just handed over to you on this call. Answer like someone handed the phone: " +
                                "a few words with your name, like \"Hi, $name here\" or \"$name speaking, go ahead\" (vary it), " +
                                "then listen.)").toString())
                    }
                    if (reconnecting) {
                        reconnecting = false
                        reconnects = 0
                        main.post { said.add(Said("system", "Reconnected", true)) }
                    }
                    startAudio(webSocket, f.optInt("output_sample_rate", 24_000).takeIf { it in 8_000..48_000 } ?: 24_000)
                    status("Listening")
                    call?.takeIf { first }?.let {
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
                    if (frozen && !(resting && role != "user")) return // a late line from before the freeze
                    if (role == "user" && final && isFreeze(line)) {
                        freeze(webSocket)
                    } else if (role == "user" && final) {
                        cutTurn = false
                        userSpokeAt = System.currentTimeMillis()
                        nightAskedAt = 0L
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
                "tool_finished" -> if (!frozen) status("Listening")
                "turn_complete" -> { cutTurn = false; if (!frozen) status("Listening") }
                "interrupted" -> { playback.clear(); flushPlayback = true; cutTurn = false }
                // Gemini dropping its session (inactivity, its own time limit, a hiccup) isn't December hanging
                // up: the same call reconnects, like a dropped connection
                "error" -> {
                    status("Error: ${f.optString("message")}")
                    if (f.optBoolean("fatal")) reconnect("the voice service closed: ${f.optString("message")}", webSocket)
                }
                "closed" -> if (f.optString("reason") == "client") end()
                    else reconnect("the voice service closed the call (${f.optString("reason")})", webSocket)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket === ws) heardBytes += bytes.size
            if (webSocket === ws && resting) main.post { wake() } // an answer Mochi was waiting for: listen again
            if (webSocket === ws && !quiet && !cutTurn) {
                talkingAt = System.currentTimeMillis()
                playback.offer(bytes.toByteArray())
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            if (response?.code == 401 || response?.code == 403) {
                status("The server refused the token.")
                main.postDelayed({ if (ws === webSocket) end("The server refused the token") }, 2_000)
                return
            }
            // a network switch, Tailscale reconnecting, a brief outage: the call reconnects instead of ending
            reconnect("the connection dropped" + (t.message?.let { ": $it" } ?: ""))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket === ws) reconnect("the server closed the call ($code${if (reason.isNotBlank()) " $reason" else ""})")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket === ws) reconnect("the server closed the call ($code${if (reason.isNotBlank()) " $reason" else ""})")
        }
    }

    /** "Stop", "be quiet", "silent", "shut up", "चुप"… said on its own (a short phrase, not a sentence). */
    private fun isStop(said: String): Boolean {
        val words = said.lowercase().replace(Regex("[^\\p{L}\\p{M}\\s]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 4) return false
        val phrase = words.distinct().joinToString(" ")
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
        "shut up", "hush", "pause", "enough", "chup", "chup lag", "chup basa", "चुप", "चुप लाग", "चुप बस", "रोक",
        "wait", "hold on", "hang on", "one second", "one sec", "पर्ख", "एकछिन")

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
                val why = "Audio problem: ${e.message ?: "the microphone or speaker is busy"}"
                status(why)
                end(why)
            }
        }
    }

    /** A new recording from the call mic, already started; throws with a plain reason when it can't record. */
    private fun openMic(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("the microphone is busy")
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            throw IllegalStateException("the microphone is busy (another app may be using it)")
        }
        return rec
    }

    private fun record(socket: WebSocket) = audio(socket) {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("microphone permission is off")
        }
        var rec = openMic()
        var echo: AcousticEchoCanceler? = null
        var noise: NoiseSuppressor? = null
        fun effects() {
            echo?.release(); noise?.release()
            echo = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null
            noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) else null
            echo?.enabled = true
            noise?.enabled = true
        }
        effects()
        val buf = ByteArray(FRAME_BYTES)
        val silence = ByteArray(FRAME_BYTES)
        var quietSent = 0 // silent frames sent since the mute / freeze began
        var failed = 0     // mic reads in a row that gave nothing
        var lastError = 0
        var reopened = 0
        fun quiet() {
            quietSent++
            // the first second tells Gemini December stopped; then one frame every 20 s so the session isn't dropped
            if (quietSent > QUIET_FRAMES && (quietSent - QUIET_FRAMES) % KEEPALIVE_FRAMES != 0) return
            socket.send(silence.toByteString())
            sentBytes += silence.size
        }
        try {
            while (active && ws === socket) {
                if (Unfreeze.needsMic) {
                    // 2026-11-22: a whole call went to Mochi as silence. The unfreeze listener only ever runs during
                    // a freeze; outside one it's a leftover holding the mic, so it's stopped here.
                    if (!frozen) {
                        Unfreeze.stop()
                        Crashes.note(app, "call: the unfreeze listener held the mic outside a freeze; let go")
                        continue
                    }
                    // checking what came after "Mochi" on the phone: the recognizer needs the mic; the call stays open
                    rec.stop()
                    while (Unfreeze.needsMic && frozen && active && ws === socket) {
                        quiet()
                        Thread.sleep(100)
                    }
                    rec.startRecording()
                    continue
                }
                var n = 0
                while (n < FRAME_BYTES && active) {
                    val got = rec.read(buf, n, FRAME_BYTES - n)
                    if (got <= 0) { lastError = got; break }
                    n += got
                }
                if (n == FRAME_BYTES) {
                    if (failed >= 10) {
                        if (!muted && !frozen) status("Listening")
                        Crashes.note(app, "call: the microphone works again after ${failed / 10} s")
                    }
                    failed = 0
                    if (muted || frozen) {
                        quiet()
                    } else if (Stranger.on) {
                        // stranger mode: only December's voice goes on (the held second first); others get silence
                        quietSent = 0
                        val out = Stranger.frame(app, buf.copyOf(), talking()) { near ->
                            socket.send("""{"type":"table","on":$near}""")
                        }
                        if (out.isEmpty()) {
                            socket.send(silence.toByteString()); sentBytes += silence.size
                        } else for (f in out) {
                            socket.send(f.toByteString()); sentBytes += f.size
                        }
                    } else {
                        quietSent = 0
                        socket.send(buf.toByteString())
                        sentBytes += buf.size
                        // the voice check (listen-only: scores only), off the audio path: it copies the frame
                        VoiceId.onFrame(app, buf.copyOf(), talking())
                    }
                } else if (active) {
                    // the mic gave nothing (another app holds it, a Bluetooth switch): keep the session alive
                    // with the same silent keepalive as a mute, or Gemini closes it for inactivity after ~2 min
                    quiet()
                    Thread.sleep(100)
                    failed++
                    // 2026-11-22: this used to go on silently for a whole call ("Listening" on screen, Mochi heard
                    // nothing). After 1 s: say so, tell the server why, and open the mic again (3 tries, then hang up).
                    if (failed == 10) {
                        Crashes.note(app, "call: the microphone gave nothing for 1 s (read $lastError, muted=$muted, frozen=$frozen); opening it again")
                        if (!muted && !frozen) status("The microphone gives no sound: trying again…")
                    }
                    if (failed % 10 == 0) {
                        if (reopened >= 3) throw IllegalStateException("the microphone gives no sound (another app may be using it)")
                        reopened++
                        runCatching { rec.stop() }
                        rec.release()
                        rec = openMic()
                        effects()
                    }
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
        var applied = 1f
        try {
            track.play()
            while (active && ws === socket) {
                if (applied != speed) {   // slower or faster, same pitch (Android's time-stretch)
                    applied = speed
                    runCatching { track.playbackParams = track.playbackParams.setSpeed(applied).setPitch(1f) }
                }
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
