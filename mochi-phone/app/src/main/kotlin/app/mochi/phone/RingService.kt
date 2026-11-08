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
    @Volatile private var current: okhttp3.Call? = null // the open /events request, to cancel it
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Mochi.createChannels(this)
        val n = Notification.Builder(this, Mochi.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_mochi)
            .setContentTitle("Mochi can call you")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Mochi.NOTIFY_LISTENING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(Mochi.NOTIFY_LISTENING, n)
            }
        } catch (e: Exception) {
            // Android refused a background start (e.g. a restart while battery-restricted): try again when the
            // app is next opened
            Log.i("mochi-ring", "couldn't start in the background: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECONNECT) current?.cancel() // new server or token: reconnect with them
        if (!running) {
            running = true
            worker = Thread(::listen, "mochi-ring").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        current?.cancel()
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
                val call = Mochi.http.newCall(req).also { current = it }
                call.execute().use { r ->
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
                                val call = runCatching { JSONObject(line.removePrefix("data: ")) }.getOrNull()
                                if (call != null && call.optString("id").isNotEmpty()) main.post { ring(this, call) }
                            }
                            line.startsWith("data: ") && event == "phone" -> {
                                val job = runCatching { JSONObject(line.removePrefix("data: ")) }.getOrNull()
                                val id = job?.optString("id").orEmpty()
                                // once per request, even if a reconnect replays it
                                if (job != null && id.isNotEmpty() && Mochi.firstSeen(this, "job:$id")) Thread {
                                    val result = PhoneActions.handle(this, job.optString("action"),
                                        job.optJSONObject("args") ?: JSONObject())
                                    runCatching { Mochi.phoneResult(this, id, result) }
                                }.start()
                            }
                            line.startsWith("data: ") && event == "notice" -> {
                                val notice = runCatching { JSONObject(line.removePrefix("data: ")) }.getOrNull()
                                val id = notice?.optString("id").orEmpty()
                                if (notice != null && id.isNotEmpty() && Mochi.firstSeen(this, id)) {
                                    main.post { showNotice(this, notice) }
                                }
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
        private const val ACTION_RECONNECT = "app.mochi.phone.RECONNECT"
        private var ringing: String? = null
        /** The incoming-call screen, to close when the ring stops (missed, declined from the notification). */
        var onRingStopped: (() -> Unit)? = null

        fun start(c: Context) {
            if (!Mochi.configured(c)) return
            runCatching { c.startForegroundService(Intent(c, RingService::class.java)) }
                .onFailure { Log.i("mochi-ring", "couldn't start: ${it.message}") }
        }

        /** After the server or token changed: drop the connection and reconnect with the new ones. */
        fun reconnect(c: Context) {
            if (!Mochi.configured(c)) return
            runCatching { c.startForegroundService(Intent(c, RingService::class.java).setAction(ACTION_RECONNECT)) }
        }

        /** Show the incoming call: full screen on a locked phone, a heads-up call card otherwise. */
        fun ring(c: Context, call: JSONObject) {
            if (Call.active) {
                // already talking to Mochi: ask in this call; otherwise it stays in Approvals if it's missed
                if (Call.relay(call)) Thread { runCatching { Mochi.callDone(c, call.getString("id"), "answered") } }.start()
                return
            }
            val id = call.optString("id")
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
                .setSmallIcon(R.drawable.ic_stat_mochi)
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

        private var notices = 0

        /**
         * A notification from Mochi: a reminder, what happened with a task, or a request to use the browser.
         * Tapping opens the Approvals screen for a request, else the chat.
         */
        fun showNotice(c: Context, notice: JSONObject) {
            if (notice.has("url")) return approvalNotice(c, notice.optString("body"))
            Mochi.addToInbox(c, notice)
            val open = withHome(c, 7 + notices % 50, Intent(c, ChatActivity::class.java))
            val body = notice.optString("body")
            val n = Notification.Builder(c, Mochi.CHANNEL_NOTICES)
                .setSmallIcon(R.drawable.ic_stat_mochi)
                .setContentTitle(notice.optString("title"))
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            c.getSystemService(NotificationManager::class.java).notify(Mochi.NOTIFY_NOTICE_BASE + notices++ % 50, n)
        }

        private var waiting = 0

        /** Opens `target` with the home screen under it, so Back goes home instead of leaving the app. */
        private fun withHome(c: Context, code: Int, target: Intent) = PendingIntent.getActivities(c, code,
            arrayOf(Intent(c, MainActivity::class.java), target),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /**
         * One notification for everything waiting on December (browser requests, missed calls): it counts them
         * and opens the Approvals screen, which clears it.
         */
        fun approvalNotice(c: Context, latest: String) {
            waiting++
            val open = PendingIntent.getActivity(c, 6, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, Mochi.CHANNEL_NOTICES)
                .setSmallIcon(R.drawable.ic_stat_mochi)
                .setContentTitle(if (waiting == 1) "Mochi needs you" else "Mochi needs you: $waiting things waiting")
                .setContentText(latest)
                .setStyle(Notification.BigTextStyle().bigText(latest))
                .setNumber(waiting)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            c.getSystemService(NotificationManager::class.java).notify(Mochi.NOTIFY_APPROVALS, n)
        }

        fun clearApprovalNotice(c: Context) {
            waiting = 0
            c.getSystemService(NotificationManager::class.java).cancel(Mochi.NOTIFY_APPROVALS)
        }

        /** Stop ringing (answered or declined). */
        fun stopRinging(c: Context) {
            ringing = null
            onRingStopped?.invoke()
            c.getSystemService(NotificationManager::class.java).cancel(Mochi.NOTIFY_RING)
        }

        private fun missed(c: Context, call: JSONObject) {
            stopRinging(c)
            approvalNotice(c, "Missed call: " + call.optString("question"))
            Thread { runCatching { Mochi.callDone(c, call.getString("id"), "missed") } }.start()
        }
    }
}
