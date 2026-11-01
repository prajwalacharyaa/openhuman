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
    const val NOTIFY_LISTENING = 1
    const val NOTIFY_RING = 2
    const val NOTIFY_ONGOING = 3
    const val NOTIFY_MISSED = 4
    private const val CLIENT_ID = "mochi-phone"
    private val JSON = "application/json".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // mochi-ring pings every 25 s
        .pingInterval(20, TimeUnit.SECONDS) // keeps the voice WebSocket alive through Tailscale
        .build()

    private fun prefs(c: Context) = c.getSharedPreferences("mochi", Context.MODE_PRIVATE)
    fun host(c: Context): String = prefs(c).getString("host", "") ?: ""
    fun token(c: Context): String = prefs(c).getString("token", "") ?: ""
    fun configured(c: Context) = host(c).isNotEmpty() && token(c).isNotEmpty()
    fun save(c: Context, host: String, token: String) =
        prefs(c).edit().putString("host", host).putString("token", token).apply()

    /** The conversation "Call Mochi" continues; null starts a new one. */
    fun thread(c: Context): String? = prefs(c).getString("thread", null)
    fun setThread(c: Context, id: String?) = prefs(c).edit().putString("thread", id).apply()

    fun voiceUrl(c: Context) = "wss://${host(c)}:8446/ws/live-voice"
    fun ringUrl(c: Context) = "https://${host(c)}:8448"

    /** One JSON-RPC call to the core; returns `result` or throws. Blocking: call off the main thread. */
    fun rpc(c: Context, method: String, params: JSONObject): JSONObject {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
        val req = Request.Builder().url("https://${host(c)}:8446/rpc")
            .header("Authorization", "Bearer ${token(c)}")
            .post(body.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            val reply = JSONObject(r.body?.string() ?: "{}")
            reply.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message")) }
            return reply.optJSONObject("result") ?: JSONObject()
        }
    }

    /** Tell mochi-ring how a call ended (answered / declined / missed) so it stops ringing. */
    fun callDone(c: Context, callId: String, result: String) {
        val req = Request.Builder().url("${ringUrl(c)}/calls/$callId/done")
            .header("Authorization", "Bearer ${token(c)}")
            .post(JSONObject().put("result", result).toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().close()
    }

    /**
     * After a call Mochi started: post a typed message into the call's thread so the text brain picks
     * the task back up. The core adds what was said on the call to that turn (voice recap).
     */
    fun continueAfterCall(c: Context, threadId: String) {
        val text = "We just talked about this on the phone. Continue with what I decided on the call."
        val message = JSONObject().put("id", "phone-" + UUID.randomUUID()).put("content", text)
            .put("type", "text").put("extraMetadata", JSONObject()).put("sender", "user")
            .put("createdAt", Instant.now().toString())
        rpc(c, "openhuman.threads_message_append", JSONObject().put("thread_id", threadId).put("message", message))
        rpc(c, "openhuman.channel_web_chat",
            JSONObject().put("client_id", CLIENT_ID).put("thread_id", threadId).put("message", text))
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
        ))
    }
}
