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

        /** Redraw every Mochi widget (at most every 2 s unless [force]). */
        fun update(c: Context, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastAt < 2_000) return
            lastAt = now
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, MochiWidget::class.java))
            if (ids.isEmpty()) return
            val step = Doing.steps.lastOrNull()
            val busy = Doing.busy()
            val status = when {
                Call.active -> "On a call" + (if (busy && step != null) " · ${step.text}" else if (Call.muted) " · muted" else "")
                busy && step != null -> "Working: ${step.text}"
                else -> "Resting · tap to open"
            }
            val v = RemoteViews(c.packageName, R.layout.widget_mochi)
            v.setTextViewText(R.id.widget_status, status)
            v.setViewVisibility(R.id.widget_call, if (Call.active) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.widget_mute, if (Call.active) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.widget_hangup, if (Call.active) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.widget_mute, if (Call.muted || Call.frozen) "Unmute" else "Mute")
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = if (busy || Call.active) Intent(c, DoingActivity::class.java) else Intent(c, MainActivity::class.java)
            v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, 30, open, flags))
            v.setOnClickPendingIntent(R.id.widget_call, PendingIntent.getActivity(c, 31,
                Intent(c, MainActivity::class.java).setAction(MainActivity.ACTION_CALL), flags))
            v.setOnClickPendingIntent(R.id.widget_mute, PendingIntent.getBroadcast(c, 32,
                Intent(c, WidgetActions::class.java).setAction(ACTION_MUTE), flags))
            v.setOnClickPendingIntent(R.id.widget_hangup, PendingIntent.getBroadcast(c, 33,
                Intent(c, WidgetActions::class.java).setAction(ACTION_HANGUP), flags))
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
