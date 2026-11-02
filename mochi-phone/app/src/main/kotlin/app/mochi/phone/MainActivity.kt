package app.mochi.phone

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Home: Call / Chat with Mochi, Approvals, past conversations, setup (server address + Mochi's token) and the
 * permissions a ringing phone needs.
 */
class MainActivity : Activity() {
    private lateinit var state: TextView
    private lateinit var setup: LinearLayout
    private lateinit var fixes: LinearLayout
    private lateinit var ram: TextView
    private lateinit var ramWatch: Pair<() -> Unit, () -> Unit>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        RingService.start(this)
        WakeService.start(this) // listening for "Mochi" again after a reboot, if it's on
        refresh()
        if (Mochi.configured(this)) ramWatch.first()
    }

    override fun onPause() {
        ramWatch.second()
        super.onPause()
    }

    private fun refresh() {
        Ui.gone(setup, Mochi.configured(this))
        showState()
        fixes.removeAllViews()
        val nm = getSystemService(NotificationManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        if (!nm.areNotificationsEnabled()) {
            fix("Allow notifications (needed to ring)", Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            fix("Allow full-screen calls (ring on the lock screen)",
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        }
        if (!power.isIgnoringBatteryOptimizations(packageName)) {
            fix("Let Mochi run in the background (so calls arrive)",
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        state.postDelayed({ if (!isFinishing) showState() }, 2_000) // the ring connection takes a moment
    }

    private fun showState() {
        state.text = when {
            !Mochi.configured(this) -> "Enter your server and Mochi's token to start."
            RingService.connected -> "● Connected. Mochi can call you."
            else -> "○ Not connected to Mochi yet. Is Tailscale on?"
        }
    }

    private fun fix(label: String, intent: Intent) {
        fixes.addView(Ui.button(this, label, Ui.GREY) { startActivity(intent) }, Ui.matchWidth(this, 8))
    }

    private fun open(target: Class<*>, extra: String? = null) {
        if (!Mochi.configured(this)) return
        startActivity(Intent(this, target).apply { if (extra != null) putExtra(extra, true) })
    }

    private fun buildUi() {
        val screen = Ui.screen(this, "Mochi", back = false)
        ram = Ui.text(this, size = 13f, color = Ui.MUTED)
        screen.right.addView(ram)
        ramWatch = Mochi.ramWatcher(this, ram)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        state = Ui.text(this, size = 14f, color = Ui.MUTED)
        body.addView(state)

        val host = EditText(this).apply {
            hint = "Server, e.g. xarvis-core.tailnet.ts.net"
            setText(Mochi.host(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Ui.TEXT)
        }
        val token = EditText(this).apply {
            hint = "Mochi's token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Ui.TEXT)
        }
        setup = Ui.card(this).apply {
            addView(host)
            addView(token)
            addView(Ui.button(this@MainActivity, "Save", Ui.BLUE) {
                val h = host.text.toString().trim().removePrefix("https://").substringBefore('/').substringBefore(':')
                val t = token.text.toString().trim()
                if (h.isNotEmpty() && t.isNotEmpty()) {
                    if (h.contains(' ') || "https://$h/".toHttpUrlOrNull() == null) {
                        state.text = "That server address isn't valid (e.g. xarvis-core.tailnet.ts.net)."
                        return@button
                    }
                    Mochi.save(this@MainActivity, h, t)
                    token.setText("")
                    RingService.reconnect(this@MainActivity)
                    refresh()
                    ramWatch.first()
                }
            }, Ui.matchWidth(this@MainActivity, 8))
        }
        body.addView(setup)

        // the two things done most, side by side
        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        main.addView(Ui.button(this, "Call Mochi", Ui.GREEN) {
            if (Mochi.configured(this)) {
                startActivity(Intent(this, CallActivity::class.java).putExtra(CallActivity.EXTRA_ANSWER, true))
            }
        }, LinearLayout.LayoutParams(0, Ui.dp(this, 64), 1f).apply { marginEnd = Ui.dp(this@MainActivity, 6) })
        main.addView(Ui.button(this, "Chat", Ui.BLUE) { open(ChatActivity::class.java) },
            LinearLayout.LayoutParams(0, Ui.dp(this, 64), 1f).apply { marginStart = Ui.dp(this@MainActivity, 6) })
        body.addView(main, Ui.matchWidth(this, 16))

        body.addView(Ui.button(this, "Approvals", Ui.RED) { open(ApprovalsActivity::class.java) }, Ui.matchWidth(this))
        body.addView(Ui.button(this, "Conversations", Ui.GREY) { open(ConversationsActivity::class.java) }, Ui.matchWidth(this))
        body.addView(Ui.button(this, "New conversation", Ui.GREY) { open(ChatActivity::class.java, ChatActivity.EXTRA_NEW) },
            Ui.matchWidth(this))
        body.addView(Ui.button(this, "Phone abilities", Ui.GREY) { open(AbilitiesActivity::class.java) }, Ui.matchWidth(this))

        fixes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(fixes, Ui.matchWidth(this, 8))
        body.addView(Ui.text(this, "Change server or token", 14f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 20), 0, 0)
            setOnClickListener { Ui.gone(setup, false) }
        })
        body.addView(Ui.text(this, "Calls and chat go over Tailscale only: keep the Tailscale app connected.", 12f, Ui.MUTED)
            .apply { setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0) })
        screen.root.addView(ScrollView(this).apply { addView(body) }, Ui.fill())
    }
}
