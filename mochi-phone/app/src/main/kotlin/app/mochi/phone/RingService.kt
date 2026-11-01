package app.mochi.phone

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import okhttp3.Request
import org.json.JSONObject

/**
 * Keeps one connection to mochi-ring open (over Tailscale) and rings like an incoming call when Mochi
 * calls. Runs as a low-priority foreground service so Android doesn't kill it; reconnects on its own.
 */
class RingService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Mochi.createChannels(this)
        val n = Notification.Builder(this, Mochi.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_mochi)
            .setContentTitle("Mochi can call you")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Mochi.NOTIFY_LISTENING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(Mochi.NOTIFY_LISTENING, n)
        }
        if (!running) {
            running = true
            worker = Thread(::listen, "mochi-ring").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        connected = false
        super.onDestroy()
    }

    private fun listen() {
        var backoff = 2_000L
        while (running) {
            try {
                val req = Request.Builder().url("${Mochi.ringUrl(this)}/events")
                    .header("Authorization", "Bearer ${Mochi.token(this)}").build()
                Mochi.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    connected = true
                    backoff = 2_000L
                    val source = r.body!!.source()
                    var event = ""
                    while (running) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.startsWith("event: ") -> event = line.removePrefix("event: ")
                            line.startsWith("data: ") && event == "call" -> {
                                val call = JSONObject(line.removePrefix("data: "))
                                main.post { ring(this, call) }
                            }
                            line.isEmpty() -> event = ""
                        }
                    }
                }
            } catch (e: Exception) {
                Log.i("mochi-ring", "connection lost: ${e.message}")
            }
            connected = false
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { }
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    companion object {
        /** Whether the ring connection is up, for the main screen. */
        @Volatile var connected = false
        private const val RING_FOR_MS = 45_000L
        private var ringing: String? = null

        fun start(c: Context) {
            if (Mochi.configured(c)) c.startForegroundService(Intent(c, RingService::class.java))
        }

        /** Show the incoming call: full screen on a locked phone, a heads-up call card otherwise. */
        fun ring(c: Context, call: JSONObject) {
            val id = call.getString("id")
            ringing = id
            val mochi = Person.Builder().setName("Mochi").setImportant(true).build()
            val answer = Intent(c, CallActivity::class.java).putExtra(CallActivity.EXTRA_CALL, call.toString())
            val full = PendingIntent.getActivity(c, 1, answer, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val answerNow = PendingIntent.getActivity(c, 2, Intent(answer).putExtra(CallActivity.EXTRA_ANSWER, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val decline = PendingIntent.getBroadcast(c, 3,
                Intent(c, DeclineReceiver::class.java).putExtra(CallActivity.EXTRA_CALL, call.toString()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, Mochi.CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_mochi)
                .setContentText(call.optString("question"))
                .setStyle(Notification.CallStyle.forIncomingCall(mochi, decline, answerNow))
                .setFullScreenIntent(full, true)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .build()
            n.flags = n.flags or Notification.FLAG_INSISTENT // keep ringing until answered
            c.getSystemService(NotificationManager::class.java).notify(Mochi.NOTIFY_RING, n)
            Handler(Looper.getMainLooper()).postDelayed({ if (ringing == id) missed(c, call) }, RING_FOR_MS)
        }

        /** Stop ringing (answered or declined). */
        fun stopRinging(c: Context) {
            ringing = null
            c.getSystemService(NotificationManager::class.java).cancel(Mochi.NOTIFY_RING)
        }

        private fun missed(c: Context, call: JSONObject) {
            stopRinging(c)
            val open = PendingIntent.getActivity(c, 4,
                Intent(c, CallActivity::class.java).putExtra(CallActivity.EXTRA_CALL, call.toString())
                    .putExtra(CallActivity.EXTRA_ANSWER, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, Mochi.CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_mochi)
                .setContentTitle("Missed call from Mochi: tap to call back")
                .setContentText(call.optString("question"))
                .setStyle(Notification.BigTextStyle().bigText(call.optString("question")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            c.getSystemService(NotificationManager::class.java).notify(Mochi.NOTIFY_MISSED, n)
            Thread { runCatching { Mochi.callDone(c, call.getString("id"), "missed") } }.start()
        }
    }
}
