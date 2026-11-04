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
    const val NOTIFY_WAKE = 7
    private const val FRESH_AFTER_MS = 30 * 60_000L
    const val NOTIFY_WAKE_PROMPT = 8
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

    /** "Mochi" wake word: on/off, and the Picovoice access key it needs (the keyword file is in filesDir). */
    fun wakeEnabled(c: Context) = prefs(c).getBoolean("wake", false)
    fun setWakeEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("wake", on).apply()
    fun wakeKey(c: Context): String = prefs(c).getString("wake_key", "") ?: ""
    fun setWakeKey(c: Context, key: String) = prefs(c).edit().putString("wake_key", key).apply()
    fun setThread(c: Context, id: String?) =
        prefs(c).edit().putString("thread", id).putLong("thread_at", System.currentTimeMillis()).apply()

    fun voiceUrl(c: Context) = "wss://${host(c)}:8446/ws/live-voice"
    fun ringUrl(c: Context) = "https://${host(c)}:8448"

    /** One JSON-RPC call to the core; returns `result` or throws. Blocking: call off the main thread. */
    fun rpc(c: Context, method: String, params: JSONObject): JSONObject {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
        val req = Request.Builder().url("https://${host(c)}:8446/rpc")
            .header("Authorization", "Bearer ${token(c)}")
            .post(body.toString().toRequestBody(JSON)).build()
        quick.newCall(req).execute().use { r ->
            val reply = JSONObject(r.body?.string() ?: "{}")
            reply.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message")) }
            return reply.optJSONObject("result") ?: JSONObject()
        }
    }

    /** "RAM 68%" for the screen headers; asked only while a screen is open. Blocking. */
    fun ramLabel(c: Context): String {
        val req = Request.Builder().url("${ringUrl(c)}/stats").header("Authorization", "Bearer ${token(c)}").build()
        quick.newCall(req).execute().use { r -> return "RAM ${JSONObject(r.body?.string() ?: "{}").getInt("ram")}%" }
    }

    /**
     * Keeps [label] showing the server's RAM, every 10 s, while the screen is open: call the returned
     * function's start in onResume and stop in onPause.
     */
    fun ramWatcher(c: Context, label: android.widget.TextView): Pair<() -> Unit, () -> Unit> {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val busy = java.util.concurrent.atomic.AtomicBoolean(false)
        val tick = object : Runnable {
            override fun run() {
                if (busy.compareAndSet(false, true)) {
                    Thread {
                        val text = runCatching { ramLabel(c) }.getOrDefault("")
                        busy.set(false)
                        main.post { label.text = text }
                    }.start()
                }
                main.postDelayed(this, 10_000)
            }
        }
        val start: () -> Unit = { main.removeCallbacks(tick); main.post(tick) }
        val stop: () -> Unit = { main.removeCallbacks(tick) }
        return Pair(start, stop)
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

    /** Start a new conversation; returns its thread id. */
    fun newThread(c: Context): String = data(rpc(c, "openhuman.threads_create_new", JSONObject())).getString("id")

    /** The conversation's messages, oldest first. */
    fun messages(c: Context, threadId: String): JSONArray =
        data(rpc(c, "openhuman.threads_messages_list", JSONObject().put("thread_id", threadId)))
            .optJSONArray("messages") ?: JSONArray()

    /**
     * Send a typed message, exactly like the desktop chat: Claude answers it in the thread. `followup`: wait for
     * a turn Claude is still working on instead of cancelling it.
     */
    fun send(c: Context, threadId: String, text: String, followup: Boolean = false) {
        val message = JSONObject().put("id", "phone-" + UUID.randomUUID()).put("content", text)
            .put("type", "text").put("extraMetadata", JSONObject()).put("sender", "user")
            .put("createdAt", Instant.now().toString())
        rpc(c, "openhuman.threads_message_append", JSONObject().put("thread_id", threadId).put("message", message))
        if (threadId == thread(c)) touchThread(c)
        val chat = JSONObject().put("client_id", CLIENT_ID).put("thread_id", threadId).put("message", text)
        if (followup) chat.put("queue_mode", "followup")
        rpc(c, "openhuman.channel_web_chat", chat)
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
