package app.mochi.phone

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import org.json.JSONObject

/** Holds the microphone while a call runs, so the call keeps going with the screen off. */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) {
            Call.end("Hung up from the notification")
            stopSelf()
            return START_NOT_STICKY
        }
        Mochi.createChannels(this)
        val hangUp = PendingIntent.getService(this, 5,
            Intent(this, CallService::class.java).setAction(ACTION_HANG_UP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 6,
            Intent(this, CallActivity::class.java).putExtra(CallActivity.EXTRA_ANSWER, true), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, Mochi.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_mochi)
            .setStyle(Notification.CallStyle.forOngoingCall(Person.Builder().setName("Mochi").build(), hangUp))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .build()
        startForeground(Mochi.NOTIFY_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        Call.start(this, intent?.getStringExtra(CallActivity.EXTRA_CALL)?.let(::JSONObject),
            intent?.getStringExtra(CallActivity.EXTRA_AGENT), intent?.getStringExtra(CallActivity.EXTRA_THREAD))
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_HANG_UP = "app.mochi.phone.HANG_UP"
    }
}
