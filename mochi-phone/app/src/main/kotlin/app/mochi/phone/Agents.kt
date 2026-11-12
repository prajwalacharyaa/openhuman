package app.mochi.phone

import android.content.Context
import androidx.compose.ui.graphics.Color
import app.mochi.phone.ui.AgentLook
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Mochi's specialist agents (Mark the writer, Melissa the security checker…), from mochi-ring's /agents: who
 * they are, their look and their notes (read-only). What they're doing comes from [Doing]'s feed, where their
 * steps carry who = "Helper: <name>". The last list is kept on the phone so the screen opens instantly.
 */
object Agents {
    data class Agent(
        val name: String, val display: String, val role: String, val color: Color, val accessory: String,
        val description: String, val model: String, val skills: List<String>, val notes: String,
        val isHr: Boolean = false,
    ) {
        val look get() = AgentLook(color, accessory)
        val hr get() = isHr
        val who get() = "Helper: $name"
    }

    private const val CACHE = "agents_cache"

    private fun parse(o: JSONObject): List<Agent> {
        val arr = o.optJSONArray("agents") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = a.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val skills = a.optJSONArray("skills")
            Agent(name, a.optString("display").ifBlank { name.replaceFirstChar { it.uppercase() } }, a.optString("role"),
                color(a.optString("color")), a.optString("accessory"), a.optString("description"), a.optString("model"),
                (0 until (skills?.length() ?: 0)).map { skills!!.optString(it) }, a.optString("notes"), a.optBoolean("hr"))
        }
    }

    private fun color(hex: String): Color = runCatching {
        Color(android.graphics.Color.parseColor(hex.ifBlank { "#7A8CA5" }))
    }.getOrDefault(Color(0xFF7A8CA5))

    /** The list as last seen (instant, no network). */
    fun cached(c: Context): List<Agent> = runCatching {
        parse(JSONObject(c.getSharedPreferences("mochi", Context.MODE_PRIVATE).getString(CACHE, "{}") ?: "{}"))
    }.getOrDefault(emptyList())

    /** The list from the server, kept for next time. Blocking. */
    fun load(c: Context): List<Agent> {
        val o = Doing.get(c, "/agents")
        c.getSharedPreferences("mochi", Context.MODE_PRIVATE).edit().putString(CACHE, o.toString()).apply()
        return parse(o)
    }

    /** Today's tokens per agent (all helpers, by name). Blocking. */
    fun tokensToday(c: Context): Map<String, Doing.Tokens> {
        val agents = Doing.get(c, "/tokens").optJSONObject("agents") ?: return emptyMap()
        return agents.keys().asSequence().associateWith { n ->
            val t = agents.getJSONObject(n)
            Doing.Tokens(t.optLong("t_in"), t.optLong("t_cache_read"), t.optLong("t_cache_write"), t.optLong("t_out"))
        }
    }

    fun who(name: String) = "Helper: $name"

    /** SY's hiring board: proposals waiting for December, and recent decisions. Blocking. */
    fun board(c: Context): Pair<List<JSONObject>, List<JSONObject>> {
        val o = Doing.get(c, "/hiring")
        fun list(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        return list("waiting") to list("history")
    }

    /** December's decision on a proposal: "approve", "reject" or "remarks" (with [text]). Blocking. */
    fun decide(c: Context, id: String, action: String, text: String = "") {
        val body = JSONObject().put("action", action).put("text", text).toString()
            .toRequestBody("application/json".toMediaType())
        val req = okhttp3.Request.Builder().url("${Mochi.ringUrl(c)}/hiring/$id")
            .header("Authorization", "Bearer ${Mochi.token(c)}").post(body).build()
        Mochi.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException(
                runCatching { JSONObject(r.body?.string() ?: "").optString("error") }.getOrNull()?.ifBlank { null } ?: "HTTP ${r.code}")
        }
    }

    /** The newest chat with [name], if December has one. Blocking. */
    fun latestChat(c: Context, name: String): String? =
        Mochi.threads(c).firstOrNull { Mochi.agentOf(it) == name }?.optString("id")?.takeIf { it.isNotBlank() }

    /**
     * The chat a call with [name] continues: its newest chat if it was used in the last 30 minutes or the agent is
     * working now (SY's Hiring chat while she works), else a new one. Blocking.
     */
    fun callChat(c: Context, name: String): String {
        val latest = Mochi.threads(c).firstOrNull { Mochi.agentOf(it) == name }
        val recent = latest?.optString("lastMessageAt")?.let {
            runCatching { System.currentTimeMillis() - java.time.Instant.parse(it).toEpochMilli() < 30 * 60_000 }.getOrNull()
        } == true
        return latest?.optString("id")?.takeIf { it.isNotBlank() && (recent || working(name)) } ?: Mochi.newThread(c, name)
    }

    fun find(c: Context, name: String?): Agent? = name?.let { n -> cached(c).firstOrNull { it.name == n } }

    /** The agent's latest step or line, if it has one in the feed. */
    fun latest(name: String): Doing.Event? =
        Doing.events.lastOrNull { it.who == who(name) && (it.kind == "step" || it.kind == "line") }

    /** Working from a job's start until its "Finished replying" (2026-11-12: Iris writing a long file and Nadia waiting
     *  on Iris showed as resting for minutes); a job that never finished counts for [OPEN_MS] at most. */
    fun working(name: String, now: Long = System.currentTimeMillis()) =
        Doing.events.lastOrNull { it.who == who(name) && it.kind in setOf("task", "step", "line") }
            ?.let { now - it.at < OPEN_MS && it.text != "Finished replying" } == true

    private const val OPEN_MS = 20 * 60_000L

    /** The agent's jobs, newest first (each job is its own task in the feed). */
    fun tasks(name: String): List<Doing.TaskView> =
        Doing.tasks().filter { t -> t.task?.who == who(name) || t.steps.any { it.step.who == who(name) } }

    /** "Mark" for "mark", from the list if it's there (a helper like builder just gets a capital). */
    fun display(name: String, list: List<Agent>) =
        list.firstOrNull { it.name == name }?.display ?: name.replaceFirstChar { it.uppercase() }
}
