package app.mochi.phone

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import org.json.JSONObject

/** Holds the microphone while a call runs, so the call keeps going with the screen off. */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANG_UP -> {
                Call.end("Hung up from the notification")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MUTE -> { // the notification follows by itself (Call.muted / frozen refresh it)
                if (Call.active) { if (Call.frozen) Call.unfreeze() else Call.muted = !Call.muted }
                MochiWidget.update(this, force = true)
                return START_NOT_STICKY
            }
        }
        Mochi.createChannels(this)
        startForeground(Mochi.NOTIFY_ONGOING, notice(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        Call.start(this, intent?.getStringExtra(CallActivity.EXTRA_CALL)?.let(::JSONObject),
            intent?.getStringExtra(CallActivity.EXTRA_AGENT), intent?.getStringExtra(CallActivity.EXTRA_THREAD))
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_HANG_UP = "app.mochi.phone.HANG_UP"
        const val ACTION_MUTE = "app.mochi.phone.MUTE"

        /** The ongoing-call notification: red Hang up, and Mute / Unmute on its right. */
        fun notice(c: Context): Notification {
            val hangUp = PendingIntent.getService(c, 5,
                Intent(c, CallService::class.java).setAction(ACTION_HANG_UP), PendingIntent.FLAG_IMMUTABLE)
            val mute = PendingIntent.getService(c, 7,
                Intent(c, CallService::class.java).setAction(ACTION_MUTE), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(c, 6,
                Intent(c, CallActivity::class.java).putExtra(CallActivity.EXTRA_ANSWER, true), PendingIntent.FLAG_IMMUTABLE)
            val muted = Call.muted || Call.frozen
            val muteAction = Notification.Action.Builder(
                Icon.createWithResource(c, if (muted) R.drawable.ic_w_micoff else R.drawable.ic_w_mic),
                if (muted) "Unmute" else "Mute", mute).build()
            return Notification.Builder(c, Mochi.CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_mochi)
                .setStyle(Notification.CallStyle.forOngoingCall(Person.Builder().setName("Mochi").build(), hangUp))
                .addAction(muteAction)
                .setContentText(if (muted) "Muted" else null)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        fun refresh(c: Context) {
            c.getSystemService(NotificationManager::class.java).notify(Mochi.NOTIFY_ONGOING, notice(c))
        }
    }
}
