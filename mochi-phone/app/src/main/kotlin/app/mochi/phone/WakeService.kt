package app.mochi.phone

import ai.picovoice.porcupine.PorcupineManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * "Mochi" wake word: listens on the phone only (Picovoice Porcupine, nothing leaves the phone) and starts a
 * call when it hears it. Runs only while December has it switched on, with its own "Listening for 'Mochi'"
 * notification. Pauses during a call (the call needs the microphone) and resumes after.
 */
class WakeService : Service() {
    private var porcupine: PorcupineManager? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Mochi.createChannels(this)
        val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, Mochi.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_mochi)
            .setContentTitle("Listening for \"Mochi\"")
            .setContentText("On the phone only. Turn it off in the Mochi app.")
            .setContentIntent(open)
            .build()
        try {
            // first, always: a service started with startForegroundService must get here, or Android ends the app
            startForeground(Mochi.NOTIFY_WAKE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.i("mochi-wake", "can't listen: ${e.message}")
            lastError = e.message ?: "couldn't start"
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Mochi.wakeEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Call.active) pause() else listen()
        // not sticky: Android may not let a background restart use the microphone; opening the app starts it
        return START_NOT_STICKY
    }

    /** A Mochi call or a phone call is using the microphone. */
    private fun micBusy(): Boolean {
        val mode = getSystemService(AudioManager::class.java).mode
        return Call.active || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun listen() {
        if (porcupine != null || instance !== this || !Mochi.wakeEnabled(this)) return
        if (micBusy()) {
            main.postDelayed({ listen() }, 30_000) // try again after the call
            return
        }
        val keyword = File(filesDir, KEYWORD_FILE)
        try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(Mochi.wakeKey(this))
                .setKeywordPath(keyword.absolutePath)
                .setSensitivity(0.6f)
                .setErrorCallback { e -> Log.i("mochi-wake", "listener error: ${e.message}") }
                .build(applicationContext) { _ -> main.post { heard() } }
                .also { it.start() }
        } catch (e: Exception) {
            Log.i("mochi-wake", "couldn't start listening: ${e.message}")
            lastError = e.message ?: "couldn't start"
            pause()
            stopSelf()
        }
    }

    private fun pause() {
        porcupine?.let { runCatching { it.stop() }; runCatching { it.delete() } }
        porcupine = null
    }

    /** "Mochi" was heard: a chime, then a call. */
    private fun heard() {
        if (micBusy()) return
        pause() // the call needs the microphone; listening resumes when it ends
        // and if no call comes of it (mic denied, screen not opened), listen again anyway
        main.postDelayed({ if (!Call.active) listen() }, 20_000)
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            main.postDelayed({ tone.release() }, 400)
        }
        val call = Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CallActivity.EXTRA_ANSWER, true)
        if (Settings.canDrawOverlays(this)) {
            // "Display over other apps" lets Mochi open the call screen straight away
            runCatching { startActivity(call) }.onFailure { offerCall(call) }
        } else {
            offerCall(call)
        }
    }

    /** Without "display over other apps": a full-screen prompt (straight to the call on a locked phone). */
    private fun offerCall(call: Intent) {
        val open = PendingIntent.getActivity(this, 12, call, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, Mochi.CHANNEL_NOTICES)
            .setSmallIcon(R.drawable.ic_stat_mochi)
            .setContentTitle("Mochi heard you: tap to talk")
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
            .build()
        getSystemService(NotificationManager::class.java).notify(Mochi.NOTIFY_WAKE_PROMPT, n)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        pause()
        super.onDestroy()
    }

    companion object {
        const val KEYWORD_FILE = "mochi.ppn"
        /** Why listening couldn't start, for the main screen. */
        @Volatile var lastError = ""
        @Volatile private var instance: WakeService? = null

        fun start(c: Context) {
            if (!Mochi.wakeEnabled(c)) return
            if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                lastError = "microphone permission is off"
                return
            }
            lastError = ""
            runCatching { c.startForegroundService(Intent(c, WakeService::class.java)) }
                .onFailure { lastError = it.message ?: "couldn't start" }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, WakeService::class.java))
        }

        /**
         * After a call: listen again. The service kept running (only the listener paused), so this needs no new
         * foreground start, which Android may refuse from the background.
         */
        fun resume(c: Context) {
            // only a running service: from the background the microphone can't be started anew (opening the app does)
            Handler(Looper.getMainLooper()).post { instance?.listen() }
        }

        /** A call is starting: give it the microphone now (main thread). */
        fun pauseNow() {
            instance?.pause()
        }
    }
}
