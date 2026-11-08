package app.mochi.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * What Mochi is doing, from the server's watcher (mochi-activity → mochi-ring, live over the "activity" event):
 * tasks (each request), steps (each thing done for it), lines (what happened inside a step) and usage (each model
 * call's tokens). Kept on the phone (last 600) for the "What Mochi is doing" page, Home's strip and the widget.
 */
object Doing {
    /** One event. [kind]: task, step, line or usage. Lines point at their step, steps and usage at their task. */
    data class Event(
        val id: String, val at: Long, val kind: String, val lane: String, val who: String, val text: String,
        val repo: String, val detail: String, val step: String, val task: String, val thread: String,
        val model: String, val tokens: Tokens,
    )

    /** Tokens of one or more model calls: new input, cache reads, cache writes, output. */
    data class Tokens(val input: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0, val output: Long = 0) {
        val total get() = input + cacheRead + cacheWrite + output
        operator fun plus(o: Tokens) =
            Tokens(input + o.input, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite, output + o.output)
    }

    /** A step with the lines under it. */
    data class StepView(val step: Event, val lines: List<Event>)

    /** A task (one request) with its steps, the models it used and its tokens. */
    data class TaskView(val task: Event?, val steps: List<StepView>, val models: Set<String>, val tokens: Tokens) {
        val at get() = steps.lastOrNull()?.step?.at ?: task?.at ?: 0L
        val finished get() = steps.any { it.step.text == "Finished replying" || it.step.text == "Call ended" }
    }

    val events = mutableStateListOf<Event>()
    private val main = Handler(Looper.getMainLooper())
    private const val KEEP = 600
    /** A step this recent means Mochi is still at it. */
    const val BUSY_MS = 45_000L

    private fun parse(e: JSONObject) = Event(
        e.optString("id").ifEmpty { "${System.nanoTime()}" }, e.optLong("at", System.currentTimeMillis() / 1000) * 1000,
        e.optString("kind", "step"), e.optString("lane", "brain"), e.optString("who", "Mochi"), e.optString("text"),
        e.optString("repo"), e.optString("detail"), e.optString("step"), e.optString("task"), e.optString("thread"),
        e.optString("model"),
        Tokens(e.optLong("t_in"), e.optLong("t_cache_read"), e.optLong("t_cache_write"), e.optLong("t_out")),
    )

    fun add(e: JSONObject) = addAll(listOf(parse(e)))

    private fun addAll(new: List<Event>) {
        main.post {
            val have = events.map { it.id }.toHashSet()
            val fresh = new.filter { it.id !in have && (it.text.isNotBlank() || it.kind == "usage") }
            if (fresh.isEmpty()) return@post
            events.addAll(fresh)
            events.sortBy { it.at }
            while (events.size > KEEP) events.removeAt(0)
        }
    }

    private fun get(c: Context, path: String): JSONObject {
        val req = Request.Builder().url("${Mochi.ringUrl(c)}$path")
            .header("Authorization", "Bearer ${Mochi.token(c)}").build()
        Mochi.http.newCall(req).execute().use { r -> return JSONObject(r.body?.string() ?: "{}") }
    }

    private fun list(o: JSONObject): List<Event> {
        val arr = o.optJSONArray("events") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parse) }
    }

    /** The recent events the server kept (a page opened after they happened). Blocking. */
    fun backfill(c: Context) = addAll(list(get(c, "/activity")))

    /** One step with all its lines, from the server (it keeps more than the phone). Blocking. */
    fun step(c: Context, id: String): List<Event> = list(get(c, "/activity?step=$id")).also { addAll(it) }

    /** Today's Claude tokens per model, from the server. Blocking. */
    fun today(c: Context): Map<String, Tokens> {
        val models = get(c, "/tokens").optJSONObject("models") ?: return emptyMap()
        return models.keys().asSequence().associateWith { m ->
            val t = models.getJSONObject(m)
            Tokens(t.optLong("t_in"), t.optLong("t_cache_read"), t.optLong("t_cache_write"), t.optLong("t_out"))
        }
    }

    /** Everything grouped as the page shows it: tasks, newest first; steps in order inside each. */
    fun tasks(): List<TaskView> {
        val all = events.toList()
        val lines = all.filter { it.kind == "line" }.groupBy { it.step }
        val steps = all.filter { it.kind == "step" }
        val byTask = all.filter { it.kind == "task" }.associateBy { it.id }
        val usage = all.filter { it.model.isNotEmpty() }.groupBy { it.task }
        val grouped = steps.groupBy { it.task }.map { (taskId, s) ->
            val u = usage[taskId].orEmpty()
            TaskView(byTask[taskId], s.map { StepView(it, lines[it.id].orEmpty()) },
                u.map { it.model }.toSet(), u.fold(Tokens()) { acc, e -> acc + e.tokens })
        }
        // a task that has started but has no step yet still shows (its card says what was asked)
        val empty = byTask.values.filter { t -> steps.none { it.task == t.id } }
            .map { TaskView(it, emptyList(), emptySet(), Tokens()) }
        return (grouped + empty).sortedByDescending { it.at }
    }

    /** Claude tokens since [since] (ms): a call's own count. */
    fun claudeSince(since: Long): Tokens =
        events.filter { it.model.isNotEmpty() && it.at >= since }.fold(Tokens()) { acc, e -> acc + e.tokens }

    fun latestStep(): Event? = events.lastOrNull { it.kind == "step" || it.kind == "line" }

    fun busy(now: Long = System.currentTimeMillis()) = latestStep()?.let { now - it.at < BUSY_MS } == true

    /** The lanes with a step in the last [BUSY_MS] (they light up on the page). */
    fun activeLanes(now: Long = System.currentTimeMillis()): Set<String> =
        events.filter { (it.kind == "step" || it.kind == "line") && now - it.at < BUSY_MS }
            .map { if (it.lane == "helper") "helper:" + it.who else it.lane }.toSet()

    /** The chat of the task Mochi is working on now (for Stop), if any. */
    fun busyThread(now: Long = System.currentTimeMillis()): String? =
        tasks().firstOrNull { !it.finished && now - it.at < BUSY_MS }?.task?.thread?.takeIf { it.isNotBlank() }

    /** Today's Gemini voice tokens on this phone (estimated from the call audio, see [Call.geminiTokens]). */
    fun geminiToday(c: Context): Long =
        c.getSharedPreferences("mochi", Context.MODE_PRIVATE).getLong("gemini_" + LocalDate.now(), 0)

    fun addGeminiToday(c: Context, n: Long) {
        val p = c.getSharedPreferences("mochi", Context.MODE_PRIVATE)
        val key = "gemini_" + LocalDate.now()
        p.edit().putLong(key, p.getLong(key, 0) + n).apply()
    }

    /** 52, 4.1k, 1.2M. */
    fun fmt(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fk".format(n / 1_000.0)
        else -> "$n"
    }

    /** "Sonnet · 41k (39k cached) · 180 out" */
    fun describe(model: String, t: Tokens): String =
        listOfNotNull(model.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() }, fmt(t.total),
            if (t.cacheRead > 0) "(${fmt(t.cacheRead)} cached)" else null, "${fmt(t.output)} out").joinToString(" · ")
}
