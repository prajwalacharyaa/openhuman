package app.mochi.phone

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Mascot
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type

/**
 * Settings > "Fingerprint to open Mochi": when on, the app asks for the fingerprint (or the screen lock) when it
 * opens, and once unlocked it stays unlocked until the phone's screen is locked (December, 2026-11-14: switching
 * to another app and back asked again after 30 s). A restarted app asks again. Mochi's incoming call, the call screen, the
 * phone-action confirmations and the assistant overlay are never blocked, so a call can always be answered.
 */
object AppLock {
    private val EXEMPT: Set<Class<*>> = setOf(CallActivity::class.java, ConfirmActivity::class.java, AssistActivity::class.java,
        LockActivity::class.java)
    private var started = 0
    /** A screen of the app is on view: Android then lets it open another one by itself (see [AppNav]). */
    val visible get() = started > 0
    private var unlocked = false

    private fun prefs(c: Context) = c.getSharedPreferences("mochi-lock", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("on", on).apply(); if (on) unlocked = true }

    /** Can this phone ask for a fingerprint or the screen lock at all? */
    fun available(c: Context) = c.getSystemService(BiometricManager::class.java)
        .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    /** Fingerprint / screen lock, no key involved. [done] gets null on success or the reason (main thread). */
    fun ask(a: Activity, title: String, done: (String?) -> Unit) {
        BiometricPrompt.Builder(a).setTitle(title).setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build()
            .authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(null)
                override fun onAuthenticationError(code: Int, msg: CharSequence) = done(msg.toString())
            })
    }

    fun passed() { unlocked = true }

    /** The phone's screen went off (locked): the next time Mochi is opened it asks again. */
    fun relock() { unlocked = false }

    /** Watches every screen of the app (registered once, by [MochiApp]). */
    val watcher = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(a: Activity) { started++ }
        override fun onActivityStopped(a: Activity) { started = (started - 1).coerceAtLeast(0) }
        override fun onActivityResumed(a: Activity) {
            if (unlocked || !enabled(a) || a.javaClass in EXEMPT) return
            a.startActivity(Intent(a, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
}

/** The app's own Application: only to watch its screens for the fingerprint lock. */
class MochiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crashes.install(this)
        registerActivityLifecycleCallbacks(AppLock.watcher)
        // the screen going off is the phone locking: Mochi locks with it (only while the app is running; a
        // restarted app starts locked anyway)
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = AppLock.relock()
        }, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
        Crashes.sendPending(this)
    }
}

/** "Mochi is locked": asks for the fingerprint at once; Back leaves the app. */
class LockActivity : ComponentActivity() {
    private var note by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MochiTheme {
                val c = LocalMochi.current
                Column(Modifier.fillMaxSize().background(c.bg).padding(Space.xl),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l, Alignment.CenterVertically)) {
                    Mascot(Modifier.size(96.dp), onTap = { prompt() })
                    Text("Mochi is locked", style = Type.title, color = c.ink)
                    if (note.isNotEmpty()) Text(note, style = Type.small, color = c.muted)
                    Pill("Unlock", true) { prompt() }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        prompt()
    }

    private fun prompt() = AppLock.ask(this, "Unlock Mochi") { error ->
        if (error == null) { AppLock.passed(); finish() } else note = error
    }
}
