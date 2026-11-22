package app.mochi.phone

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/**
 * "Reply" on a notice from Mochi or an agent: the answer goes into that one's chat without opening the app, the
 * notice quoted so it knows what is being answered. The phone must be unlocked to reply (setAuthenticationRequired).
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY)?.toString()?.trim().orEmpty()
        val id = intent.getIntExtra(EXTRA_ID, 0)
        if (text.isEmpty()) return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val agent = intent.getStringExtra(EXTRA_AGENT)
        val done = goAsync()
        Thread {
            val result = runCatching {
                val thread = if (agent != null)
                    Mochi.threads(c).firstOrNull { Mochi.agentOf(it) == agent }?.optString("id") ?: Mochi.newThread(c, agent)
                else Mochi.thread(c) ?: Mochi.newThread(c).also { Mochi.setThread(c, it) }
                Mochi.send(c, thread, "(Replying to the notice \"$title: ${body.take(400)}\")\n\n$text")
            }
            val n = Notification.Builder(c, Mochi.CHANNEL_NOTICES)
                .setSmallIcon(R.drawable.ic_stat_mochi)
                .setContentTitle(title)
                .setContentText(if (result.isSuccess) "You replied: $text" else "Not sent (${Room.friendly(result.exceptionOrNull()!!)}). Open the app to try again.")
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(if (result.isSuccess) 8_000 else 0)
                .build()
            c.getSystemService(NotificationManager::class.java).notify(id, n)
            done.finish()
        }.start()
    }

    companion object {
        private const val KEY = "reply"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BODY = "body"
        private const val EXTRA_AGENT = "agent"

        /** The Reply action for notice [id]; [agent] is the agent it came from (null: Mochi). */
        fun action(c: Context, id: Int, title: String, body: String, agent: String?): Notification.Action {
            val i = Intent(c, ReplyReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BODY, body).apply { if (agent != null) putExtra(EXTRA_AGENT, agent) }
            // mutable: Android writes the typed reply into it
            val p = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat_mochi), "Reply", p)
                .addRemoteInput(RemoteInput.Builder(KEY).setLabel(if (agent != null) "Reply to ${Agents.display(agent, Agents.cached(c))}" else "Reply to Mochi").build())
                .setAllowGeneratedReplies(false)
                .setAuthenticationRequired(true)
                .build()
        }
    }
}
