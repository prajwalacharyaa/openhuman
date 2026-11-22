package app.mochi.phone

import android.content.Context

/**
 * Stranger mode (2026-11-22): on a call, Mochi hears only December. Every stretch of speech is held for about a
 * second and checked against the voiceprint December taught on this phone; December's goes to Mochi (the held part
 * first, then live), anyone else's is replaced by silence, so a stranger's words never reach Mochi or Google.
 * Voices are told apart only while it's on; off, the call is exactly as before.
 *
 * - "Not sure" counts as not December here (say it again a little longer, or "Mochi, …").
 * - A word too short to check ("yes", "hmm") counts as December's if December spoke within the last 8 seconds.
 * - Other voices heard in the last 2 minutes: the phone tells the engine ("table"), which then lets Mochi answer
 *   only what names Mochi or follows its last words (table.rs on the server).
 *
 * Thresholds from December's first calls (2026-11-22, own voiceprint): December 0.45-0.78, others 0.08-0.25.
 */
object Stranger {
    const val YOU = 0.45f
    private const val RATE = 16_000
    private const val CHECK_FRAMES = 10            // 1 s of speech before the check
    private const val END_FRAMES = 4               // 400 ms of quiet ends a stretch
    private const val MAX_HOLD_FRAMES = 40         // never hold more than 4 s
    private const val SHORT_GRACE_MS = 8_000L
    private const val TABLE_MS = 120_000L

    @Volatile var on = false
        private set
    /** Who the last checked stretch was: "you", "someone else" (shown on the call screen while it's on). */
    @Volatile var last = ""
    @Volatile var tableOn = false
        private set

    private val held = ArrayList<ByteArray>()
    private var speechFrames = 0
    private var quiet = 0
    private var active = false
    private var mochiDuring = false
    @Volatile private var decision: Boolean? = null     // true = December
    @Volatile private var checking = false
    private var waiting = false
    private var segment = 0
    @Volatile private var lastYouAt = 0L
    @Volatile private var lastOtherAt = 0L
    private var noise = 300.0

    /** Why it can't be turned on, or null when it can. */
    fun blocker(c: Context): String? = when {
        !VoiceId.downloaded(c) -> "voice recognition isn't set up: Settings > Calls & voice > Voice recognition"
        !VoiceId.enabled(c) -> "voice recognition is off: turn it on in Settings > Calls & voice"
        !VoiceId.hasOwnPrint(c) -> "Mochi doesn't know December's voice yet: Settings > Calls & voice > Teach Mochi your voice"
        else -> null
    }

    fun set(c: Context, want: Boolean): String {
        if (want) blocker(c)?.let { return "can't: $it" }
        on = want
        reset()
        if (want) VoiceId.preload(c)   // the model loads now, not on the first sentence
        if (!want) { last = ""; tableOn = false }
        return if (want) "stranger mode on: Mochi hears only December's voice now (other voices get silence)"
            else "stranger mode off: Mochi hears everyone again"
    }

    /** The call ended. */
    fun callEnded() { on = false; last = ""; tableOn = false; reset() }

    private fun reset() {
        synchronized(held) { held.clear() }
        speechFrames = 0; quiet = 0; active = false; decision = null; checking = false; waiting = false; mochiDuring = false; segment++
    }

    /**
     * One 100 ms mic frame (while December isn't muted or frozen). Returns the frames to send now: none means send
     * silence in its place. [table] is called when "other voices nearby" changes.
     */
    fun frame(c: Context, frame: ByteArray, mochiTalking: Boolean, table: (Boolean) -> Unit): List<ByteArray> {
        val now = System.currentTimeMillis()
        val nearby = now - lastOtherAt < TABLE_MS
        if (nearby != tableOn) { tableOn = nearby; table(nearby) }
        val rms = rms(frame)
        val speech = rms > maxOf(noise * 2.5, 250.0)
        // a stretch ended while its check was still running: its held audio waits for the answer, with what came since
        if (waiting) {
            synchronized(held) { held.add(frame) }
            val d = decision ?: return if (synchronized(held) { held.size } > MAX_HOLD_FRAMES) { giveUp(); emptyList() } else emptyList()
            waiting = false
            decision = null
            return if (d) flush() else { drop(); emptyList() }
        }
        if (!speech && !active) noise = noise * 0.95 + rms * 0.05
        if (!active) {
            if (!speech) return emptyList()
            active = true; speechFrames = 0; quiet = 0; decision = null; checking = false; mochiDuring = false
        }
        if (mochiTalking) mochiDuring = true
        if (speech) { speechFrames++; quiet = 0 } else quiet++
        val ended = quiet >= END_FRAMES
        return when (decision) {
            true -> {
                val out = flush() + frame
                if (ended) active = false
                out
            }
            false -> {
                if (ended) active = false
                emptyList()
            }
            null -> {
                synchronized(held) { held.add(frame) }
                if (!checking && speechFrames >= CHECK_FRAMES) startCheck(c)
                when {
                    ended && checking -> { active = false; waiting = true; emptyList() }
                    ended -> {
                        active = false
                        // too short to check: December's if December just spoke ("yes", "go on")
                        if (now - lastYouAt < SHORT_GRACE_MS) flush() else { drop(); emptyList() }
                    }
                    synchronized(held) { held.size } > MAX_HOLD_FRAMES -> { giveUp(); emptyList() }
                    else -> emptyList()
                }
            }
        }
    }

    /** The check took too long (over 4 s held): this stretch is dropped, and its late answer ignored. */
    private fun giveUp() {
        drop(); segment++; checking = false; waiting = false; decision = false
    }

    private fun startCheck(c: Context) {
        checking = true
        val audio = synchronized(held) { held.toList() }
        val mine = segment
        val echo = mochiDuring
        VoiceId.check(c, audio) { score ->
            if (mine != segment) return@check
            val you = score != null && score >= YOU
            last = if (you) "you" else if (score == null) "" else "someone else"
            if (you) lastYouAt = System.currentTimeMillis()
            // Mochi's own voice picked up by the mic is "not December" too, but it isn't a person at the table
            else if (score != null && !echo) lastOtherAt = System.currentTimeMillis()
            decision = you
            checking = false
        }
    }

    private fun flush(): List<ByteArray> = synchronized(held) { held.toList().also { held.clear() } }
    private fun drop() = synchronized(held) { held.clear() }

    private fun rms(frame: ByteArray): Double {
        var sum = 0.0
        for (i in 0 until frame.size / 2) {
            val s = ((frame[2 * i + 1].toInt() shl 8) or (frame[2 * i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
        }
        return kotlin.math.sqrt(sum / (frame.size / 2))
    }
}
