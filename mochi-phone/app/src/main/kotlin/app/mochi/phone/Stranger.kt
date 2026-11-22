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
 * - A conversation with someone (2026-11-22): December's voice and another one taking turns, each starting within
 *   3 s of the other stopping, twice in the last minute, while the other voice isn't talking most of the time (a TV
 *   or a crowd talks on regardless, a person waits and answers). Then the phone tells the engine ("table"), which
 *   lets Mochi answer only what names Mochi or follows its last words (table.rs). A TV, music or Mochi's own voice
 *   from the speaker never turns it on: their words are silenced and Mochi answers December as usual.
 *
 * Thresholds from December's first calls (2026-11-22, own voiceprint): December 0.40-0.78 listening, others 0.08-0.25;
 * 0.45 blocked December (19:46: seven stretches 0.26 or less on the 1 s check), so 0.35 and a second, whole-stretch check.
 */
object Stranger {
    const val YOU = 0.35f
    private const val RATE = 16_000
    private const val CHECK_FRAMES = 10            // 1 s of speech before the first check
    private const val RECHECK_FRAMES = 25          // "not you" at 1 s: the whole stretch is checked again at 2.5 s
    private const val END_FRAMES = 4               // 400 ms of quiet ends a stretch
    private const val MAX_HOLD_FRAMES = 40         // never hold more than 4 s
    private const val SHORT_GRACE_MS = 8_000L
    private const val TURN_GAP_MS = 3_000L         // the other started within 3 s of this one stopping: a reply
    private const val TURNS = 2                    // replies (either way) in the last minute = a conversation
    private const val TALK_WINDOW_MS = 60_000L
    private const val BUSY_WINDOW_MS = 30_000L
    private const val BUSY_SHARE = 0.6             // the other voice talking more than this: a TV or a crowd
    private const val ECHO_TAIL_MS = 1_500L        // Mochi's last words still coming out of the speaker

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
    private var checks = 0                              // checks run on this stretch (2 = final)
    @Volatile private var lastYouAt = 0L
    @Volatile private var lastMochiAt = 0L
    private var stretchStart = 0L
    private var stretchEnd = 0L
    private var stretchEcho = false
    /** Whose stretches were heard (start, end, December?), the last minute. */
    private class Stretch(val start: Long, val end: Long, val you: Boolean)
    private val stretches = ArrayDeque<Stretch>()
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
        if (!want) { last = ""; tableOn = false; stretches.clear() }
        return if (want) "stranger mode on: Mochi hears only December's voice now (other voices get silence)"
            else "stranger mode off: Mochi hears everyone again"
    }

    /** The call ended. */
    fun callEnded() { on = false; last = ""; tableOn = false; stretches.clear(); reset() }

    private fun reset() {
        synchronized(held) { held.clear() }
        speechFrames = 0; quiet = 0; active = false; decision = null; checking = false; waiting = false; mochiDuring = false; checks = 0; segment++
    }

    /**
     * One 100 ms mic frame (while December isn't muted or frozen). Returns the frames to send now: none means send
     * silence in its place. [table] is called when "other voices nearby" changes.
     */
    fun frame(c: Context, frame: ByteArray, mochiTalking: Boolean, table: (Boolean) -> Unit): List<ByteArray> {
        val now = System.currentTimeMillis()
        if (mochiTalking) lastMochiAt = now
        val talk = conversation(now)
        if (talk != tableOn) { tableOn = talk; table(talk) }
        val rms = rms(frame)
        val speech = rms > maxOf(noise * 2.5, 250.0)
        // a stretch ended while its check was still running: its held audio waits for the answer, with what came since
        if (waiting) {
            synchronized(held) { held.add(frame) }
            // the first check said "not you": the whole stretch gets a second, final look before it's dropped
            if (decision == null && !checking) startCheck(c)
            val d = decision ?: return if (synchronized(held) { held.size } > MAX_HOLD_FRAMES) { giveUp(); emptyList() } else emptyList()
            waiting = false
            decision = null
            heard(d)
            return if (d) flush() else { drop(); emptyList() }
        }
        if (!speech && !active) noise = noise * 0.95 + rms * 0.05
        if (!active) {
            if (!speech) return emptyList()
            active = true; speechFrames = 0; quiet = 0; decision = null; checking = false; mochiDuring = false; checks = 0
            stretchStart = now; stretchEcho = now - lastMochiAt < ECHO_TAIL_MS
        }
        if (mochiTalking) mochiDuring = true
        if (speech) { speechFrames++; quiet = 0 } else quiet++
        val ended = quiet >= END_FRAMES
        if (ended) stretchEnd = now - END_FRAMES * 100L
        return when (decision) {
            true -> {
                val out = flush() + frame
                if (ended) { active = false; heard(true) }
                out
            }
            false -> {
                if (ended) { active = false; heard(false) }
                emptyList()
            }
            null -> {
                synchronized(held) { held.add(frame) }
                if (!checking && ((checks == 0 && speechFrames >= CHECK_FRAMES) || (checks == 1 && speechFrames >= RECHECK_FRAMES))) startCheck(c)
                when {
                    ended && (checking || checks > 0) -> { active = false; waiting = true; emptyList() }
                    ended -> {
                        active = false
                        // too short to check: December's if December just spoke ("yes", "go on")
                        if (now - lastYouAt < SHORT_GRACE_MS) { heard(true); flush() } else { drop(); emptyList() }
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
        checks++
        val final = checks >= 2
        val audio = synchronized(held) { held.toList() }
        val mine = segment
        VoiceId.check(c, audio) { score ->
            if (mine != segment) return@check
            val you = score != null && score >= YOU
            if (you || final) {
                last = if (you) "you" else if (score == null) "" else "someone else"
                if (you) lastYouAt = System.currentTimeMillis()
                // couldn't tell (too short, model failed): not let through, and not counted as a person either
                if (score == null) stretchEcho = true
                decision = you
            }
            checking = false
        }
    }

    /** A stretch ended and whose it was is known. Mochi's own voice from the speaker isn't a person: left out. */
    private fun heard(you: Boolean) {
        if (!you && (mochiDuring || stretchEcho)) return
        val end = if (stretchEnd >= stretchStart) stretchEnd else System.currentTimeMillis()
        stretches.addLast(Stretch(stretchStart, end, you))
    }

    /** December talking with someone: replies both ways in the last minute, the other voice not talking on and on. */
    private fun conversation(now: Long): Boolean {
        while (stretches.isNotEmpty() && now - stretches.first().end > TALK_WINDOW_MS) stretches.removeFirst()
        if (stretches.size < TURNS + 1) return false
        var replies = 0
        for (i in 1 until stretches.size) {
            val a = stretches[i - 1]; val b = stretches[i]
            if (a.you != b.you && b.start - a.end in 0..TURN_GAP_MS) replies++
        }
        val busy = stretches.filter { !it.you && now - it.end < BUSY_WINDOW_MS }
            .sumOf { (minOf(it.end, now) - maxOf(it.start, now - BUSY_WINDOW_MS)).coerceAtLeast(0) }
        return replies >= TURNS && busy < BUSY_WINDOW_MS * BUSY_SHARE
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
