package app.mochi.phone

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/**
 * Home-screen widget: what Mochi is doing (or "On a call" / "Resting"), with Call Mochi, or Mute and Hang up while
 * a call is on. Updated when a step arrives or the call changes; a tap opens "What Mochi is doing" or the app.
 */
class MochiWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) = update(c, force = true)

    companion object {
        const val ACTION_HANGUP = "app.mochi.phone.widget.HANGUP"
        const val ACTION_MUTE = "app.mochi.phone.widget.MUTE"
        private var lastAt = 0L
        /** Decisions waiting (from mochi-ring), refreshed in the background at most once a minute. */
        @Volatile private var approvals = 0
        @Volatile private var approvalsAt = 0L

        private fun badge(v: RemoteViews, id: Int, n: Int) {
            v.setViewVisibility(id, if (n > 0) View.VISIBLE else View.GONE)
            v.setTextViewText(id, if (n > 9) "9+" else "$n")
        }

        private fun refreshApprovals(c: Context) {
            val now = SystemClock.elapsedRealtime()
            if (now - approvalsAt < 60_000 || !Mochi.configured(c)) return
            approvalsAt = now
            Thread {
                val n = runCatching { Mochi.approvals(c).size }.getOrNull() ?: return@Thread
                if (n != approvals) { approvals = n; android.os.Handler(android.os.Looper.getMainLooper()).post { update(c, force = true) } }
            }.start()
        }

        /** Redraw every Mochi widget (at most every 2 s unless [force]). */
        fun update(c: Context, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastAt < 2_000) return
            lastAt = now
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, MochiWidget::class.java))
            if (ids.isEmpty()) return
            refreshApprovals(c)
            val step = Doing.latestStep()
            val busy = Doing.busy()
            val status = when {
                Call.active -> "On a call" + (if (busy && step != null) " · ${step.text}" else if (Call.muted) " · muted" else "")
                busy && step != null -> "Working: ${step.text}" +
                    (if (Mochi.detailed(c) && step.detail.isNotBlank() && step.detail != step.text) "\n${step.detail}" else "")
                else -> "Resting · tap to open"
            }
            val v = RemoteViews(c.packageName, R.layout.widget_mochi)
            v.setTextViewText(R.id.widget_status, status)
            val onCall = Call.active
            v.setViewVisibility(R.id.widget_call_box, if (onCall) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.widget_mute_box, if (onCall) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.widget_hangup_box, if (onCall) View.VISIBLE else View.GONE)
            val muted = Call.muted || Call.frozen
            v.setImageViewResource(R.id.widget_mute, if (muted) R.drawable.ic_w_micoff else R.drawable.ic_w_mic)
            v.setInt(R.id.widget_mute, "setBackgroundResource", if (muted) R.drawable.widget_circle_red else R.drawable.widget_circle_grey)
            v.setContentDescription(R.id.widget_mute, if (muted) "Unmute" else "Mute")
            badge(v, R.id.widget_approvals_count, approvals)
            badge(v, R.id.widget_inbox_count, Mochi.unreadInbox(c))
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            // an open screen comes back to the front instead of a second copy stacking up
            val front = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            val open = if (busy || onCall) Intent(c, DoingActivity::class.java).addFlags(front)
                else Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, 30, open, flags))
            v.setOnClickPendingIntent(R.id.widget_call, PendingIntent.getActivity(c, 31,
                Intent(c, MainActivity::class.java).setAction(MainActivity.ACTION_CALL), flags))
            v.setOnClickPendingIntent(R.id.widget_mute, PendingIntent.getBroadcast(c, 32,
                Intent(c, WidgetActions::class.java).setAction(ACTION_MUTE), flags))
            v.setOnClickPendingIntent(R.id.widget_hangup, PendingIntent.getBroadcast(c, 33,
                Intent(c, WidgetActions::class.java).setAction(ACTION_HANGUP), flags))
            v.setOnClickPendingIntent(R.id.widget_new, PendingIntent.getActivity(c, 34,
                Intent(c, ChatActivity::class.java).setAction(ChatActivity.ACTION_NEW_CHAT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
            v.setOnClickPendingIntent(R.id.widget_approvals, PendingIntent.getActivity(c, 35,
                Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "home"), flags))
            v.setOnClickPendingIntent(R.id.widget_inbox, PendingIntent.getActivity(c, 36,
                Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "inbox"), flags))
            manager.updateAppWidget(ids, v)
        }
    }
}

/** The widget's Mute and Hang up taps (not exported: only Mochi's own widget can send these). */
class WidgetActions : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            MochiWidget.ACTION_HANGUP -> if (Call.active) Call.end()
            MochiWidget.ACTION_MUTE -> if (Call.active) { if (Call.frozen) Call.unfreeze() else Call.muted = !Call.muted }
        }
        MochiWidget.update(c, force = true)
    }
}
