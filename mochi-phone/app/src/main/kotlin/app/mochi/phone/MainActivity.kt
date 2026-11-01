package app.mochi.phone

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Setup (server address + Mochi's token), "Call Mochi", and the permissions a ringing phone needs.
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
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        RingService.start(this)
        refresh()
        if (Mochi.configured(this)) ramWatch.first()
    }

    override fun onPause() {
        ramWatch.second()
        super.onPause()
    }

    private fun refresh() {
        setup.visibility = if (Mochi.configured(this)) View.GONE else View.VISIBLE
        state.text = when {
            !Mochi.configured(this) -> "Enter your server and Mochi's token to start."
            RingService.connected -> "Connected. Mochi can call you."
            else -> "Not connected to Mochi yet. Is Tailscale on?"
        }
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
        state.postDelayed({ if (!isFinishing) refreshState() }, 2_000)
    }

    private fun refreshState() {
        if (Mochi.configured(this)) {
            state.text = if (RingService.connected) "Connected. Mochi can call you."
                         else "Not connected to Mochi yet. Is Tailscale on?"
        }
    }

    private fun fix(label: String, intent: Intent) {
        fixes.addView(button(label, "#55555C") { startActivity(intent) }, wide())
    }

    private fun wide() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 }

    private fun button(label: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        setPadding(24, 32, 24, 32)
        background = GradientDrawable().apply { cornerRadius = 48f; setColor(Color.parseColor(color)) }
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.parseColor("#F6F5F2"))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "Mochi"; textSize = 30f; setTextColor(Color.parseColor("#1D1D1F")) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ram = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#6B6B70")) }
        header.addView(ram)
        root.addView(header)
        ramWatch = Mochi.ramWatcher(this, ram)
        state = TextView(this).apply { textSize = 15f; setPadding(0, 8, 0, 8); setTextColor(Color.parseColor("#55555C")) }
        root.addView(state)

        val host = EditText(this).apply {
            hint = "Server, e.g. xarvis-core.tailnet.ts.net"
            setText(Mochi.host(this@MainActivity))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val token = EditText(this).apply {
            hint = "Mochi's token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(host)
            addView(token)
            addView(button("Save", "#3B6FD8") {
                val h = host.text.toString().trim().removePrefix("https://").substringBefore('/').substringBefore(':')
                val t = token.text.toString().trim()
                if (h.isNotEmpty() && t.isNotEmpty()) {
                    Mochi.save(this@MainActivity, h, t)
                    token.setText("")
                    RingService.start(this@MainActivity)
                    refresh()
                }
            }, wide())
        }
        root.addView(setup)

        root.addView(button("Call Mochi", "#2E9D5B") {
            if (Mochi.configured(this)) {
                startActivity(Intent(this, CallActivity::class.java).putExtra(CallActivity.EXTRA_ANSWER, true))
            }
        }, wide())
        root.addView(button("Chat with Mochi", "#3B6FD8") {
            if (Mochi.configured(this)) startActivity(Intent(this, ChatActivity::class.java))
        }, wide())
        root.addView(button("Conversations", "#55555C") {
            if (Mochi.configured(this)) startActivity(Intent(this, ConversationsActivity::class.java))
        }, wide())
        root.addView(button("New conversation", "#55555C") {
            if (Mochi.configured(this)) {
                startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_NEW, true))
            }
        }, wide())
        fixes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(fixes)
        root.addView(button("Change server or token", "#55555C") {
            setup.visibility = View.VISIBLE
        }, wide())
        root.addView(TextView(this).apply {
            text = "Calls go over Tailscale only: keep the Tailscale app connected."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F6F5F2")); addView(root) })
    }
}
