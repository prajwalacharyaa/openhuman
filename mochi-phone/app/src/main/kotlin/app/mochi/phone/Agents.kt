package app.mochi.phone

import android.content.Context
import androidx.compose.ui.graphics.Color
import app.mochi.phone.ui.AgentLook
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
    ) {
        val look get() = AgentLook(color, accessory)
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
                (0 until (skills?.length() ?: 0)).map { skills!!.optString(it) }, a.optString("notes"))
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

    /** The agent's latest step or line, if it has one in the feed. */
    fun latest(name: String): Doing.Event? =
        Doing.events.lastOrNull { it.who == who(name) && (it.kind == "step" || it.kind == "line") }

    fun working(name: String, now: Long = System.currentTimeMillis()) =
        latest(name)?.let { now - it.at < Doing.BUSY_MS && it.text != "Finished replying" } == true

    /** The agent's jobs, newest first (each job is its own task in the feed). */
    fun tasks(name: String): List<Doing.TaskView> =
        Doing.tasks().filter { t -> t.task?.who == who(name) || t.steps.any { it.step.who == who(name) } }

    /** "Mark" for "mark", from the list if it's there (a helper like builder just gets a capital). */
    fun display(name: String, list: List<Agent>) =
        list.firstOrNull { it.name == name }?.display ?: name.replaceFirstChar { it.uppercase() }
}
