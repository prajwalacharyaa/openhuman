package app.mochi.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/** "Decline" on the incoming-call card: stop ringing and tell mochi-ring. Mochi's question stays in the chat. */
class DeclineReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        RingService.stopRinging(c)
        val call = JSONObject(intent.getStringExtra(CallActivity.EXTRA_CALL) ?: return)
        val pending = goAsync()
        Thread {
            runCatching { Mochi.callDone(c, call.getString("id"), "declined") }
            pending.finish()
        }.start()
    }
}

/** Start listening for calls again after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        RingService.start(c)
    }
}
