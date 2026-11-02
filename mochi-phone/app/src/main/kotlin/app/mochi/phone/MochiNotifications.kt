package app.mochi.phone

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * "Check the message that just arrived": keeps the last messages' notifications (WhatsApp, Messenger, SMS…)
 * in memory only, so Mochi can read them when asked. Works only after December turns on notification access
 * for Mochi; nothing is stored on disk or sent anywhere unless Mochi asks for it.
 */
class MochiNotifications : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        synchronized(recent) {
            recent.addFirst(Item(app, title.take(100), text.take(500), sbn.postTime))
            while (recent.size > KEPT) recent.removeLast()
        }
    }

    private data class Item(val app: String, val title: String, val text: String, val time: Long)

    companion object {
        private const val KEPT = 30
        private val recent = ArrayDeque<Item>()

        /** The newest `limit` notifications, optionally from one app ("WhatsApp"). */
        fun recent(limit: Int, app: String): JSONArray {
            val out = JSONArray()
            synchronized(recent) {
                recent.filter { app.isBlank() || it.app.contains(app, ignoreCase = true) }
                    .take(limit.coerceIn(1, 20))
                    .forEach {
                        out.put(JSONObject().put("app", it.app).put("from", PhoneActions.hideNumbers(it.title))
                            .put("text", PhoneActions.hideNumbers(it.text))
                            .put("time", Instant.ofEpochMilli(it.time).toString()))
                    }
            }
            if (out.length() == 0 && recent.isEmpty()) {
                throw IllegalStateException("no notifications seen yet (is notification access on for Mochi?)")
            }
            return out
        }
    }
}
