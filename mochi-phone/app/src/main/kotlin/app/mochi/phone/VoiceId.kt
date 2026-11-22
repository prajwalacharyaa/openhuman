package app.mochi.phone

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Is it December talking? A speaker check on the phone (2026-11-22): the TitaNet-small model December's voiceprints
 * were made with, downloaded once from the server (Settings > Calls & voice > Voice recognition), runs here, so
 * the voice and the voiceprint never leave the phone; only a score goes to the server.
 *
 * Listen-only for now: during a call every stretch of speech (1 to 6 s) is scored against each voiceprint and the
 * numbers go to mochi-ring (/voice/scores) so the threshold is set from real calls. Nothing is blocked yet.
 *
 * Audio features as NeMo makes them for this model: 16 kHz, pre-emphasis 0.97, 25 ms Hann window in a 512-point
 * FFT, 10 ms hop, power spectrum, 80 Slaney mel bands to 8 kHz, log(x + 2^-24), normalised per band.
 */
object VoiceId {
    private const val RATE = 16_000
    private const val N_FFT = 512
    private const val WIN = 400
    private const val HOP = 160
    private const val MELS = 80
    const val MIN_SECS = 1.0
    private const val MAX_SECS = 6.0

    private fun prefs(c: Context) = c.getSharedPreferences("voice_id", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "voice").apply { mkdirs() }
    private fun modelFile(c: Context) = File(dir(c), "titanet_small.onnx")
    private fun serverPrintFile(c: Context) = File(dir(c), "voiceprint.json")
    private fun ownPrintFile(c: Context) = File(dir(c), "own.json")

    fun downloaded(c: Context) = modelFile(c).length() > 30_000_000 && serverPrintFile(c).exists()
    fun enabled(c: Context) = prefs(c).getBoolean("on", false) && downloaded(c)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("on", on).apply()
    fun hasOwnPrint(c: Context) = ownPrintFile(c).exists()
    fun ownPrintMade(c: Context): Long = ownPrintFile(c).lastModified()

    /** Downloads the model (40 MB) and the voiceprints from mochi-ring over Tailscale; [progress] 0..1. */
    fun download(c: Context, progress: (Float) -> Unit) {
        fun get(path: String, to: File, report: Boolean) {
            val req = Request.Builder().url("${Mochi.ringUrl(c)}$path").header("Authorization", "Bearer ${Mochi.token(c)}").build()
            Mochi.http.newCall(req).execute().use { r ->
                if (r.code == 404) throw IllegalStateException("The server has no voice model yet (or the vault is locked).")
                if (!r.isSuccessful) throw IllegalStateException("The server said ${r.code}")
                val body = r.body ?: throw IllegalStateException("empty reply")
                val total = body.contentLength().takeIf { it > 0 } ?: 40_000_000L
                val tmp = File(to.path + ".part")
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (report) progress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (!tmp.renameTo(to)) throw IllegalStateException("couldn't save the file")
            }
        }
        get("/voice/print", serverPrintFile(c), false)
        get("/voice/model", modelFile(c), true)
        close()
    }

    /** Removes the model and every voiceprint from the phone. */
    fun remove(c: Context) {
        close()
        dir(c).listFiles()?.forEach { it.delete() }
        setEnabled(c, false)
    }

    // ---- the model ----

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Synchronized
    private fun session(c: Context): OrtSession {
        session?.let { return it }
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        return e.createSession(modelFile(c).absolutePath, opts).also { session = it }
    }

    /** Frees the model's memory (~100 MB); it loads again on the next use. */
    @Synchronized
    fun close() {
        runCatching { session?.close() }
        session = null
    }

    /** The voice embedding of 16 kHz PCM, length 1 (L2-normalised), or null when too short. */
    fun embed(c: Context, pcm: ShortArray): FloatArray? {
        if (pcm.size < RATE * MIN_SECS) return null
        val feats = features(pcm)
        val frames = feats.size / MELS
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val s = session(c)
        OnnxTensor.createTensor(e, FloatBuffer.wrap(feats), longArrayOf(1, MELS.toLong(), frames.toLong())).use { audio ->
            OnnxTensor.createTensor(e, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1)).use { len ->
                s.run(mapOf("audio_signal" to audio, "length" to len), setOf("embs")).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val v = (out[0].value as Array<FloatArray>)[0]
                    return normalise(v)
                }
            }
        }
    }

    private fun normalise(v: FloatArray): FloatArray {
        val n = sqrt(v.fold(0.0) { a, x -> a + x * x }).toFloat().coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / n }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return (dot / (sqrt(na) * sqrt(nb)).coerceAtLeast(1e-9)).toFloat()
    }

    // ---- voiceprints ----

    private fun floats(arr: JSONArray?): FloatArray? = arr?.let { a -> FloatArray(a.length()) { a.optDouble(it).toFloat() } }

    /** Every voiceprint on the phone by name: "own" (taught here), "phone" and "laptop" (from the old app). */
    fun prints(c: Context): Map<String, FloatArray> {
        val out = linkedMapOf<String, FloatArray>()
        runCatching { floats(JSONObject(ownPrintFile(c).readText()).optJSONArray("mean"))?.let { out["own"] = normalise(it) } }
        runCatching {
            val j = JSONObject(serverPrintFile(c).readText())
            floats(j.optJSONObject("phone")?.optJSONArray("mean"))?.let { out["phone"] = normalise(it) }
            floats(j.optJSONObject("laptop")?.optJSONArray("ref"))?.let { out["laptop"] = normalise(it) }
        }
        return out
    }

    /** The scores of [emb] against each voiceprint (cosine, -1..1). */
    fun scores(c: Context, emb: FloatArray): Map<String, Float> = prints(c).mapValues { cosine(emb, it.value) }

    /** "Teach Mochi your voice": the mean of the clips' embeddings becomes the "own" voiceprint. Returns how alike
     *  the clips were (the lowest score of a clip against the mean: low means a noisy or mixed recording). */
    fun teach(c: Context, clips: List<ShortArray>): Float {
        val embs = clips.mapNotNull { embed(c, it) }
        require(embs.size >= 3) { "Each sentence needs at least a second of speech: try again a bit slower." }
        val mean = normalise(FloatArray(embs[0].size) { i -> embs.sumOf { it[i].toDouble() }.toFloat() / embs.size })
        ownPrintFile(c).writeText(JSONObject().put("mean", JSONArray(mean.map { it.toDouble() })).put("clips", embs.size)
            .put("made", System.currentTimeMillis()).toString())
        return embs.minOf { cosine(it, mean) }
    }

    // ---- listen-only scoring during a call ----

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mochi-voice-id") }
    private val pending = JSONArray()
    @Volatile var lastLabel = ""        // shown on the call screen: "you", "someone else" or "" (not sure / off)
    @Volatile var lastScore = 0f
    /** Until the threshold is set from real calls: the old laptop voiceprint's own threshold. */
    private const val TRIAL_THRESHOLD = 0.52f

    private val segment = java.io.ByteArrayOutputStream()
    private var silentFrames = 0
    private var noise = 300.0          // the room's noise level (RMS), learned as the call goes

    /** One 100 ms frame of the mic (16 kHz PCM16, little-endian), only while December isn't muted or frozen. */
    fun onFrame(c: Context, frame: ByteArray, mochiTalking: Boolean) {
        // in stranger mode its own checks are the scores (one model run per stretch, not two)
        if (!enabled(c) || Stranger.on) return
        val rms = rms(frame)
        val speech = rms > max(noise * 2.5, 250.0)
        if (!speech) noise = noise * 0.95 + rms * 0.05
        if (speech) { segment.write(frame); silentFrames = 0 }
        else if (segment.size() > 0) { silentFrames++; if (silentFrames <= 2) segment.write(frame) }
        val secs = segment.size() / (RATE * 2.0)
        if ((silentFrames >= 4 && segment.size() > 0) || secs >= MAX_SECS) {
            val bytes = segment.toByteArray()
            segment.reset(); silentFrames = 0
            if (bytes.size >= RATE * 2 * MIN_SECS) {
                val talking = mochiTalking
                worker.execute { score(c, bytes, talking) }
            }
        }
    }

    private fun rms(frame: ByteArray): Double {
        var sum = 0.0
        for (i in 0 until frame.size / 2) {
            val s = ((frame[2 * i + 1].toInt() shl 8) or (frame[2 * i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
        }
        return sqrt(sum / (frame.size / 2))
    }

    private fun score(c: Context, bytes: ByteArray, mochiTalking: Boolean) {
        val pcm = ShortArray(bytes.size / 2) { ((bytes[2 * it + 1].toInt() shl 8) or (bytes[2 * it].toInt() and 0xff)).toShort() }
        val emb = runCatching { embed(c, pcm) }.getOrNull() ?: return
        val all = scores(c, emb)
        val best = all["own"] ?: all["phone"] ?: all["laptop"] ?: return
        lastScore = best
        lastLabel = when {
            best >= TRIAL_THRESHOLD -> "you"
            best < TRIAL_THRESHOLD - 0.15f -> "someone else"
            else -> ""
        }
        val now = System.currentTimeMillis() / 1000
        synchronized(pending) {
            for ((name, v) in all) pending.put(JSONObject().put("at", now).put("score", v.toDouble()).put("print", name)
                .put("secs", pcm.size / RATE.toDouble()).put("mochi_talking", mochiTalking))
        }
        if (pending.length() >= 30) flush(c)
    }

    /** Stranger mode: [frames] (16 kHz PCM16) against December's own voiceprint, off the audio thread; [done] gets
     *  the score, or null when it can't tell (too short, no voiceprint, the model failed). */
    fun check(c: Context, frames: List<ByteArray>, done: (Float?) -> Unit) = worker.execute {
        val bytes = java.io.ByteArrayOutputStream().apply { frames.forEach { write(it) } }.toByteArray()
        val pcm = ShortArray(bytes.size / 2) { ((bytes[2 * it + 1].toInt() shl 8) or (bytes[2 * it].toInt() and 0xff)).toShort() }
        val own = prints(c)["own"]
        val score = if (own == null) null else runCatching { embed(c, pcm)?.let { cosine(it, own) } }.getOrNull()
        done(score)
        if (score != null) synchronized(pending) {
            pending.put(JSONObject().put("at", System.currentTimeMillis() / 1000).put("score", score.toDouble())
                .put("print", "own-stranger").put("secs", pcm.size / RATE.toDouble()).put("mochi_talking", Call.talking()))
        }
    }

    /** Loads the model ahead of the first check (stranger mode turned on). */
    fun preload(c: Context) = worker.execute { runCatching { session(c) } }

    /** Sends the scores so far to the server (at the end of a call, and every few dozen). */
    fun flush(c: Context) = worker.execute {
        val rows = synchronized(pending) {
            if (pending.length() == 0) return@execute
            val copy = JSONArray(pending.toString())
            while (pending.length() > 0) pending.remove(0)
            copy
        }
        runCatching {
            val body = JSONObject().put("scores", rows).toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("${Mochi.ringUrl(c)}/voice/scores").header("Authorization", "Bearer ${Mochi.token(c)}")
                .post(body).build()
            Mochi.http.newCall(req).execute().close()
        }
    }

    /** The call ended: send what's left, drop the half-heard stretch, free the model. */
    fun callEnded(c: Context) {
        segment.reset(); silentFrames = 0; lastLabel = ""
        flush(c)
        worker.execute { close() }
    }

    // ---- features ----

    private val window = FloatArray(WIN) { (0.5 - 0.5 * cos(2 * PI * it / (WIN - 1))).toFloat() }   // periodic=False
    private val melBank: Array<FloatArray> by lazy { slaneyMel() }

    private fun hzToMel(f: Double): Double = if (f < 1000.0) 3.0 * f / 200.0 else 15.0 + ln(f / 1000.0) / (ln(6.4) / 27.0)
    private fun melToHz(m: Double): Double = if (m < 15.0) 200.0 * m / 3.0 else 1000.0 * kotlin.math.exp((ln(6.4) / 27.0) * (m - 15.0))

    private fun slaneyMel(): Array<FloatArray> {
        val bins = N_FFT / 2 + 1
        val fft = DoubleArray(bins) { it * RATE.toDouble() / N_FFT }
        val lo = hzToMel(0.0); val hi = hzToMel(RATE / 2.0)
        val pts = DoubleArray(MELS + 2) { melToHz(lo + (hi - lo) * it / (MELS + 1)) }
        return Array(MELS) { m ->
            val enorm = 2.0 / (pts[m + 2] - pts[m])
            FloatArray(bins) { k ->
                val up = (fft[k] - pts[m]) / (pts[m + 1] - pts[m])
                val down = (pts[m + 2] - fft[k]) / (pts[m + 2] - pts[m + 1])
                (max(0.0, minOf(up, down)) * enorm).toFloat()
            }
        }
    }

    /** Log-mel features, band-major (80 x frames), normalised per band. */
    fun features(pcm: ShortArray): FloatArray {
        val x = FloatArray(pcm.size) { pcm[it] / 32768f }
        for (i in x.size - 1 downTo 1) x[i] -= 0.97f * x[i - 1]
        val pad = N_FFT / 2
        val padded = FloatArray(x.size + 2 * pad).also { System.arraycopy(x, 0, it, pad, x.size) }
        val frames = 1 + (padded.size - N_FFT) / HOP
        val out = FloatArray(MELS * frames)
        val re = DoubleArray(N_FFT); val im = DoubleArray(N_FFT)
        val off = (N_FFT - WIN) / 2
        val power = DoubleArray(N_FFT / 2 + 1)
        for (t in 0 until frames) {
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            val start = t * HOP
            for (i in 0 until WIN) re[off + i] = (padded[start + off + i] * window[i]).toDouble()
            fft(re, im)
            for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until MELS) {
                val f = melBank[m]
                var s = 0.0
                for (k in power.indices) s += f[k] * power[k]
                out[m * frames + t] = ln(s + 5.960464477539063e-8).toFloat()   // 2^-24
            }
        }
        for (m in 0 until MELS) {
            var mean = 0.0
            for (t in 0 until frames) mean += out[m * frames + t]
            mean /= frames
            var v = 0.0
            for (t in 0 until frames) { val d = out[m * frames + t] - mean; v += d * d }
            val std = sqrt(v / max(1, frames - 1)) + 1e-5
            for (t in 0 until frames) out[m * frames + t] = ((out[m * frames + t] - mean) / std).toFloat()
        }
        return out
    }

    /** In-place radix-2 FFT (N_FFT = 512). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            for (i in 0 until n step len) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
            }
            len = len shl 1
        }
    }
}
