package app.mochi.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Mochi is doing, as plain steps: from the server's watcher (mochi-activity → mochi-ring, live over the
 * "activity" event) for Claude, its helpers, memory and the server, and from the call itself for the voice side.
 * Kept on the phone (last 120) for the "What Mochi is doing" page and Home's "Mochi is working" strip.
 */
object Doing {
    /** One step. [lane]: voice, brain, helper, memory, web, server, phone, github. */
    data class Step(val id: String, val at: Long, val lane: String, val who: String, val text: String, val repo: String)

    val steps = mutableStateListOf<Step>()
    private val main = Handler(Looper.getMainLooper())
    private const val KEEP = 120
    /** A step this recent means Mochi is still at it. */
    const val BUSY_MS = 45_000L

    fun add(e: JSONObject) {
        val step = Step(e.optString("id").ifEmpty { "${System.nanoTime()}" }, e.optLong("at", System.currentTimeMillis() / 1000) * 1000,
            e.optString("lane", "brain"), e.optString("who", "Mochi"), e.optString("text"), e.optString("repo"))
        if (step.text.isBlank()) return
        main.post {
            if (steps.any { it.id == step.id }) return@post
            steps.add(step)
            steps.sortBy { it.at }
            while (steps.size > KEEP) steps.removeAt(0)
        }
    }

    /** The last steps the server kept (for a page opened after they happened). Blocking. */
    fun backfill(c: Context) {
        val req = Request.Builder().url("${Mochi.ringUrl(c)}/activity").header("Authorization", "Bearer ${Mochi.token(c)}").build()
        Mochi.http.newCall(req).execute().use { r ->
            val arr = JSONObject(r.body?.string() ?: "{}").optJSONArray("events") ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::add)
        }
    }

    fun busy(now: Long = System.currentTimeMillis()) = steps.lastOrNull()?.let { now - it.at < BUSY_MS } == true

    /** The lanes with a step in the last [BUSY_MS] (they light up on the page). */
    fun activeLanes(now: Long = System.currentTimeMillis()): Set<String> =
        steps.filter { now - it.at < BUSY_MS }.map { if (it.lane == "helper") "helper:" + it.who else it.lane }.toSet()

    /** A tool Gemini started on the call, as a step (phone data never: just "Using your phone"). */
    fun voiceTool(name: String, args: JSONObject?) {
        val command = args?.optString("command").orEmpty()
        val (lane, text) = when {
            name.contains("memory") -> "memory" to "Checking memory"
            name.contains("search") || name.contains("answer") -> "web" to
                ("Searching the web" + (args?.optString("query")?.takeIf { it.isNotBlank() }?.let { ": ${it.take(60)}" } ?: ""))
            name == "shell" && command.contains("mochi-ask") -> "brain" to "Handing this to Claude"
            name == "shell" && command.contains("mochi-phone") -> "phone" to "Using your phone"
            name == "shell" && command.contains("mochi-remind") -> "server" to "Setting a reminder"
            name == "shell" && command.contains("mochi-mail") -> "server" to "Writing an email"
            name == "shell" -> "server" to "Running a helper on the server"
            else -> "voice" to name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        add(JSONObject().put("lane", lane).put("who", "Mochi (voice)").put("text", text)
            .put("at", System.currentTimeMillis() / 1000))
    }
}
