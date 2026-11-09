package app.mochi.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Settings and server calls shared by the app.
 *
 * Mochi's server is reached over Tailscale only:
 *  - `https://<host>:8446`: OpenHuman core (live voice WebSocket + JSON-RPC)
 *  - `https://<host>:8448`: mochi-ring (incoming-call events)
 * Both take the same OpenHuman core token.
 */
object Mochi {
    const val CHANNEL_CALLS = "calls"
    const val CHANNEL_ONGOING = "ongoing"
    const val CHANNEL_LISTENING = "listening"
    const val CHANNEL_NOTICES = "notices"
    const val NOTIFY_LISTENING = 1
    const val NOTIFY_RING = 2
    const val NOTIFY_ONGOING = 3
    const val NOTIFY_APPROVALS = 5
    private const val FRESH_AFTER_MS = 30 * 60_000L
    const val NOTIFY_NOTICE_BASE = 100
    private const val CLIENT_ID = "mochi-phone"
    private val JSON = "application/json".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // mochi-ring pings every 50 s; silence past this = dropped connection
        .pingInterval(20, TimeUnit.SECONDS) // keeps the voice WebSocket alive through Tailscale
        .build()

    /** For quick requests (chat, approvals, RAM): give up after 20 s instead of piling up while offline. */
    private val quick: OkHttpClient = http.newBuilder().readTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).build()

    private fun prefs(c: Context) = c.getSharedPreferences("mochi", Context.MODE_PRIVATE)
    fun host(c: Context): String = prefs(c).getString("host", "") ?: ""
    fun token(c: Context): String = prefs(c).getString("token", "") ?: ""
    fun configured(c: Context) = host(c).isNotEmpty() && token(c).isNotEmpty()
    fun save(c: Context, host: String, token: String) =
        prefs(c).edit().putString("host", host).putString("token", token).apply()

    /** The conversation the chat screen shows; null starts a new one. */
    fun thread(c: Context): String? = prefs(c).getString("thread", null)

    /**
     * The conversation "Call Mochi" continues: the current one while it's in use, a new one after a
     * [FRESH_AFTER_MS] break. One all-day conversation makes every Claude answer re-read the whole day.
     */
    fun callThread(c: Context): String? =
        thread(c)?.takeIf { System.currentTimeMillis() - prefs(c).getLong("thread_at", 0) < FRESH_AFTER_MS }

    /** The current conversation was just used (a call or a typed message). */
    fun touchThread(c: Context) = prefs(c).edit().putLong("thread_at", System.currentTimeMillis()).apply()

    /** The Picovoice wake word was removed: drop its saved access key, switch and keyword file (once, harmless after). */
    fun forgetWakeWord(c: Context) {
        if (prefs(c).contains("wake_key") || prefs(c).contains("wake")) prefs(c).edit().remove("wake_key").remove("wake").apply()
        java.io.File(c.filesDir, "mochi.ppn").delete()
    }
    fun setThread(c: Context, id: String?) =
        prefs(c).edit().putString("thread", id).putLong("thread_at", System.currentTimeMillis()).apply()

    fun voiceUrl(c: Context) = "wss://${host(c)}:8446/ws/live-voice"
    fun ringUrl(c: Context) = "https://${host(c)}:8448"

    /** One JSON-RPC call to the core; returns `result` or throws. Blocking: call off the main thread. */
    fun rpc(c: Context, method: String, params: JSONObject, progress: ((Float) -> Unit)? = null): JSONObject {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
        val payload = body.toString().toRequestBody(JSON)
        val req = Request.Builder().url("https://${host(c)}:8446/rpc")
            .header("Authorization", "Bearer ${token(c)}")
            .post(if (progress != null) Counted(payload, progress) else payload).build()
        // an upload can take longer than a quick request's 20 s
        (if (progress != null) http else quick).newCall(req).execute().use { r ->
            val reply = JSONObject(r.body?.string() ?: "{}")
            reply.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message")) }
            return reply.optJSONObject("result") ?: JSONObject()
        }
    }

    /** A request body that reports how much of it has been sent (0..1), for upload progress. */
    private class Counted(private val inner: okhttp3.RequestBody, private val onProgress: (Float) -> Unit) :
        okhttp3.RequestBody() {
        override fun contentType() = inner.contentType()
        override fun contentLength() = inner.contentLength()
        override fun writeTo(sink: okio.BufferedSink) {
            val total = contentLength().coerceAtLeast(1)
            var sent = 0L
            var shown = -1
            val counting = object : okio.ForwardingSink(sink) {
                override fun write(source: okio.Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    sent += byteCount
                    val pct = (sent * 100 / total).toInt()
                    if (pct != shown) { shown = pct; onProgress(sent.toFloat() / total) }
                }
            }
            val buffered = counting.buffer()
            inner.writeTo(buffered)
            buffered.flush()
        }
    }

    /** Files December attached in chats, newest first: {id, chat, title, name, size, at}. Blocking. */
    fun uploads(c: Context): List<JSONObject> {
        val req = Request.Builder().url("${ringUrl(c)}/uploads").header("Authorization", "Bearer ${token(c)}").build()
        quick.newCall(req).execute().use { r ->
            val arr = JSONObject(r.body?.string() ?: "{}").optJSONArray("uploads") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
    }

    /** Delete uploads (by id "<chat>/<id>"): the server removes the files within seconds. Blocking. */
    fun deleteUploads(c: Context, ids: List<String>) {
        val body = JSONObject().put("ids", JSONArray(ids)).toString().toRequestBody(JSON)
        val req = Request.Builder().url("${ringUrl(c)}/uploads/delete").header("Authorization", "Bearer ${token(c)}")
            .post(body).build()
        quick.newCall(req).execute().use { r -> if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}") }
    }

    /** The app has this chat open: a decision asked in it keeps waiting for December (mochi-ring). Blocking. */
    fun presence(c: Context, threadId: String) {
        val req = Request.Builder().url("${ringUrl(c)}/presence/$threadId").header("Authorization", "Bearer ${token(c)}").build()
        runCatching { quick.newCall(req).execute().close() }
    }

    /** "RAM 68% · Storage 47% (26 GB free)" for the screen headers; asked only while a screen is open. Blocking. */
    fun ramLabel(c: Context): String {
        val req = Request.Builder().url("${ringUrl(c)}/stats").header("Authorization", "Bearer ${token(c)}").build()
        quick.newCall(req).execute().use { r ->
            val o = JSONObject(r.body?.string() ?: "{}")
            // storage too (an older server answers RAM only)
            val disk = if (o.has("disk")) " · Storage ${o.getInt("disk")}% (${o.optDouble("disk_free_gb").let {
                if (it >= 10) it.toInt().toString() else "%.1f".format(it) }} GB free)" else ""
            return "RAM ${o.getInt("ram")}%$disk"
        }
    }

    /** What waits for December (mochi-ring GET /approvals), newest first. Blocking. */
    fun approvals(c: Context): List<JSONObject> {
        val req = Request.Builder().url("${ringUrl(c)}/approvals").header("Authorization", "Bearer ${token(c)}").build()
        quick.newCall(req).execute().use { r ->
            val items = JSONObject(r.body?.string() ?: "{}").optJSONArray("items") ?: JSONArray()
            return (0 until items.length()).map { items.getJSONObject(it) }
        }
    }

    /**
     * Allow or deny an approval (a browser task, an email to someone else) on its approval page. That page trusts only requests that come through
     * Tailscale, so no token is sent (or needed): the tap is proven to come from December's tailnet.
     */
    fun decideApproval(c: Context, approveUrl: String, allow: Boolean) {
        val req = Request.Builder().url("$approveUrl/${if (allow) "allow" else "deny"}")
            .post(ByteArray(0).toRequestBody(null)).build()
        quick.newCall(req).execute().use { r -> if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}") }
    }

    /** A missed or declined call was dealt with some other way: drop it from Approvals. */
    fun dismissCall(c: Context, callId: String) {
        val req = Request.Builder().url("${ringUrl(c)}/calls/$callId/dismiss")
            .header("Authorization", "Bearer ${token(c)}")
            .post(JSONObject().toString().toRequestBody(JSON)).build()
        quick.newCall(req).execute().close()
    }

    /** The phone's answer to a mochi-ring "phone" request. */
    fun phoneResult(c: Context, jobId: String, result: JSONObject) {
        val req = Request.Builder().url("${ringUrl(c)}/phone/$jobId/result")
            .header("Authorization", "Bearer ${token(c)}")
            .post(result.toString().toRequestBody(JSON)).build()
        quick.newCall(req).execute().close()
    }

    /** Tell mochi-ring how a call ended (answered / declined / missed) so it stops ringing. */
    fun callDone(c: Context, callId: String, result: String) {
        val req = Request.Builder().url("${ringUrl(c)}/calls/$callId/done")
            .header("Authorization", "Bearer ${token(c)}")
            .post(JSONObject().put("result", result).toString().toRequestBody(JSON)).build()
        quick.newCall(req).execute().close()
    }

    /** The `data` of a core RPC envelope (or the result itself). */
    private fun data(result: JSONObject): JSONObject = result.optJSONObject("data") ?: result

    /** Start a new conversation (labelled `agent:<name>` for a chat with one of Mochi's agents); returns its id. */
    fun newThread(c: Context, agent: String? = null): String =
        data(rpc(c, "openhuman.threads_create_new", JSONObject().apply {
            if (agent != null) put("labels", JSONArray().put("agent:$agent"))
        })).getString("id")

    /** The agent a conversation belongs to ("mark" for a chat with Mark), or null for Mochi's own. */
    fun agentOf(thread: JSONObject?): String? {
        val labels = thread?.optJSONArray("labels") ?: return null
        return (0 until labels.length()).map { labels.optString(it) }.firstOrNull { it.startsWith("agent:") }
            ?.removePrefix("agent:")?.takeIf { it.isNotBlank() }
    }

    /** The conversation's messages, oldest first. */
    fun messages(c: Context, threadId: String): JSONArray =
        data(rpc(c, "openhuman.threads_messages_list", JSONObject().put("thread_id", threadId)))
            .optJSONArray("messages") ?: JSONArray()

    /**
     * Send a typed message, exactly like the desktop chat: Claude answers it in the thread. `followup`: wait for
     * a turn Claude is still working on instead of cancelling it.
     */
    fun send(c: Context, threadId: String, text: String, followup: Boolean = false, model: String? = null,
             progress: ((Float) -> Unit)? = null) {
        val message = JSONObject().put("id", "phone-" + UUID.randomUUID()).put("content", text)
            .put("type", "text").put("extraMetadata", JSONObject()).put("sender", "user")
            .put("createdAt", Instant.now().toString())
        // the append saves any attachment originals and hands back the compact text the turn should use
        val saved = data(rpc(c, "openhuman.threads_message_append",
            JSONObject().put("thread_id", threadId).put("message", message), progress))
        val compact = saved.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
            ?: saved.optString("content").takeIf { it.isNotBlank() } ?: text
        if (threadId == thread(c)) touchThread(c)
        val chat = JSONObject().put("client_id", CLIENT_ID).put("thread_id", threadId).put("message", compact)
        if (followup) chat.put("queue_mode", "followup")
        if (model != null) chat.put("model_override", model)
        rpc(c, "openhuman.channel_web_chat", chat)
    }

    /** A note in a conversation that no one answers (a call's recap for the next speaker). Blocking. */
    fun appendNote(c: Context, threadId: String, text: String) {
        val message = JSONObject().put("id", "note-" + UUID.randomUUID()).put("content", text)
            .put("type", "text").put("extraMetadata", JSONObject().put("source", "voice")).put("sender", "user")
            .put("createdAt", Instant.now().toString())
        rpc(c, "openhuman.threads_message_append", JSONObject().put("thread_id", threadId).put("message", message))
    }

    /** Stop the reply Mochi is writing in this conversation. */
    fun cancel(c: Context, threadId: String) {
        rpc(c, "openhuman.channel_web_cancel", JSONObject().put("client_id", CLIENT_ID).put("thread_id", threadId))
    }

    /** December's conversations (not worker threads), newest first. */
    fun threads(c: Context): List<JSONObject> {
        val reply = data(rpc(c, "openhuman.threads_list", JSONObject()))
        val arr = reply.optJSONArray("threads") ?: reply.optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.isNull("parentThreadId") || it.optString("parentThreadId").isEmpty() }
            .sortedByDescending { it.optString("lastMessageAt") }
    }

    fun deleteThread(c: Context, threadId: String) {
        rpc(c, "openhuman.threads_delete",
            JSONObject().put("thread_id", threadId).put("deleted_at", Instant.now().toString()))
        if (thread(c) == threadId) prefs(c).edit().remove("thread").apply()
    }

    /** One page of memory: its items and the cursor for the next page (null at the end). */
    class MemoryPage(val items: List<JSONObject>, val next: String?)

    /**
     * What Mochi remembers, a page at a time: [kinds] "learning" (what it was taught or learned) or "conversation"
     * (the logged chat turns); matches for [query], or newest first when it's blank. Blocking.
     */
    fun memories(c: Context, query: String, kinds: List<String>, cursor: String? = null): MemoryPage {
        val filter = JSONObject().put("kinds", JSONArray(kinds))
        val params = JSONObject().put("limit", 40).put("filter", filter)
        if (cursor != null) params.put("cursor", cursor)
        val result = if (query.isBlank()) {
            data(rpc(c, "openhuman.memory_items_list", params.put("preview", true)))
        } else {
            data(rpc(c, "openhuman.memory_fetch", params.put("query", query)))
        }
        val arr = result.optJSONArray("items") ?: result.optJSONArray("hits") ?: JSONArray()
        val next = result.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
        return MemoryPage((0 until arr.length()).mapNotNull { arr.optJSONObject(it) }, next)
    }

    /** Forget these memory items for good. */
    fun forget(c: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        rpc(c, "openhuman.memory_forget", JSONObject().put("ids", JSONArray(ids)))
    }

    /** The projects with a Graphify map (mochi-publish copies them for mochi-ring). Blocking. */
    fun graphs(c: Context): List<JSONObject> {
        val req = Request.Builder().url("${ringUrl(c)}/graphs").header("Authorization", "Bearer ${token(c)}").build()
        quick.newCall(req).execute().use { r ->
            val arr = JSONObject(r.body?.string() ?: "{}").optJSONArray("projects") ?: JSONArray()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }
    }

    /** One project's map (graph.json) as text. Blocking. */
    fun graph(c: Context, repo: String): String {
        val req = Request.Builder().url("${ringUrl(c)}/graphs/$repo").header("Authorization", "Bearer ${token(c)}").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return r.body?.string() ?: "{}"
        }
    }

    /** Teach Mochi something directly ("My sister's birthday is 3 March"). */
    fun learn(c: Context, text: String) {
        rpc(c, "openhuman.memory_learn", JSONObject().put("text", text).put("kind", "fact"))
    }

    /** Notices and reminders that reached the phone, newest first (kept on the phone, last 100). */
    fun inbox(c: Context): List<JSONObject> {
        val arr = runCatching { JSONArray(prefs(c).getString("inbox", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.reversed()
    }

    fun addToInbox(c: Context, notice: JSONObject) {
        val arr = runCatching { JSONArray(prefs(c).getString("inbox", "[]")) }.getOrDefault(JSONArray())
        arr.put(JSONObject().put("title", notice.optString("title")).put("body", notice.optString("body"))
            .put("at", System.currentTimeMillis()))
        val kept = JSONArray()
        for (i in maxOf(0, arr.length() - 100) until arr.length()) kept.put(arr.get(i))
        prefs(c).edit().putString("inbox", kept.toString()).apply()
    }

    /** "What Mochi is doing" shows each step's detail line (double-tap Mochi there to switch). The widget follows. */
    fun detailed(c: Context) = prefs(c).getBoolean("detailed", true)
    fun setDetailed(c: Context, on: Boolean) = prefs(c).edit().putBoolean("detailed", on).apply()

    fun clearInbox(c: Context) = prefs(c).edit().putString("inbox", "[]").apply()

    /** Notices that came in since December last opened the Inbox (the red count). */
    fun unreadInbox(c: Context): Int {
        val seen = prefs(c).getLong("inbox_seen", 0)
        return inbox(c).count { it.optLong("at") > seen }
    }

    fun markInboxSeen(c: Context) = prefs(c).edit().putLong("inbox_seen", System.currentTimeMillis()).apply()

    /** The chat mode chosen on the phone: null = automatic, else a Claude Code model for model_override. */
    fun mode(c: Context): String = prefs(c).getString("mode", "normal") ?: "normal"
    fun setMode(c: Context, mode: String) = prefs(c).edit().putString("mode", mode).apply()
    fun modelFor(mode: String): String? = when (mode) {
        "quick" -> "claude-code:haiku"
        "deep" -> "claude-code:opus"
        else -> null
    }

    /**
     * After a call Mochi started: post a typed message into the call's thread so the text brain picks
     * the task back up (the core adds what was said on the call). Skipped when Mochi already handed the
     * decisions to Claude during the call (`mochi-ask`), so Claude doesn't run twice.
     */
    fun continueAfterCall(c: Context, threadId: String, callStartedAt: Instant) {
        val all = messages(c, threadId)
        val handedOff = (0 until all.length()).map { all.getJSONObject(it) }.any {
            it.optString("id").startsWith("voice-ask-") &&
                runCatching { Instant.parse(it.optString("createdAt")) >= callStartedAt }.getOrDefault(false)
        }
        // Claude may still be busy with something from during the call (a pasted CV): queue behind it
        if (!handedOff) {
            send(c, threadId, "We just talked about this on the phone. Continue with what I decided on the call.", followup = true)
        }
    }

    /** Whether this notice id was shown before (remembers the last 100). */
    fun firstSeen(c: Context, id: String): Boolean {
        val seen = (prefs(c).getString("notices", "") ?: "").split(',').filter { it.isNotEmpty() }
        if (id in seen) return false
        prefs(c).edit().putString("notices", (seen + id).takeLast(100).joinToString(",")).apply()
        return true
    }

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val ring = NotificationChannel(CHANNEL_CALLS, "Calls from Mochi", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            enableVibration(true)
        }
        nm.createNotificationChannels(listOf(
            ring,
            NotificationChannel(CHANNEL_ONGOING, "Call in progress", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_LISTENING, "Waiting for Mochi's calls", NotificationManager.IMPORTANCE_MIN),
            NotificationChannel(CHANNEL_NOTICES, "Updates and reminders", NotificationManager.IMPORTANCE_HIGH),
        ))
    }
}
