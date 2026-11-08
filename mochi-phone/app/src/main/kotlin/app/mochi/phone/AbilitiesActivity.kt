package app.mochi.phone

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * What Mochi may do on this phone, each switched on separately: read contacts / calls / texts, place calls and
 * send texts (always after a Confirm tap), read message notifications, and being the
 * phone's assistant button. Nothing here is on until December turns it on.
 */
class AbilitiesActivity : Activity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = Ui.screen(this, "Phone abilities", back = true)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.root.addView(ScrollView(this).apply { addView(body) }, Ui.fill())
    }

    override fun onResume() {
        super.onResume()
        draw()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun draw() {
        body.removeAllViews()
        section("Contacts, calls and texts",
            "Mochi can find a contact, say who called, read your latest texts, and call or text someone. On a voice " +
                "call with Mochi, it asks which contact, number and SIM (and remembers), then calls when you say yes; " +
                "otherwise, and for every text, you tap Confirm. Numbers never leave the phone.",
            listOf(
                Triple("Contacts", granted(Manifest.permission.READ_CONTACTS)) { ask(Manifest.permission.READ_CONTACTS) },
                Triple("Call history", granted(Manifest.permission.READ_CALL_LOG)) { ask(Manifest.permission.READ_CALL_LOG) },
                Triple("Read texts", granted(Manifest.permission.READ_SMS)) { ask(Manifest.permission.READ_SMS) },
                Triple("Place calls", granted(Manifest.permission.CALL_PHONE)) { ask(Manifest.permission.CALL_PHONE) },
                Triple("Choose the SIM", granted(Manifest.permission.READ_PHONE_STATE)) { ask(Manifest.permission.READ_PHONE_STATE) },
                Triple("Send texts", granted(Manifest.permission.SEND_SMS)) { ask(Manifest.permission.SEND_SMS) },
            ))
        section("Message notifications",
            "\"Check the message that just arrived\" (WhatsApp, Messenger, texts…): Mochi reads the newest message " +
                "notifications, kept in the phone's memory only.",
            listOf(Triple("Notification access", notificationAccess()) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            ComponentName(this, MochiNotifications::class.java).flattenToString()))
                }.onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }))
        body.addView(Ui.text(this, "If Android says this setting is restricted: open Settings → Apps → Mochi → ⋮ → " +
            "Allow restricted settings, then try again (Android asks this of apps installed from a file).", 12f, Ui.MUTED)
            .apply { setPadding(0, Ui.dp(this@AbilitiesActivity, 6), 0, 0) })
        section("Assistant button",
            "Make Mochi the phone's digital assistant: the assistant gesture (long-press power, or a corner swipe) " +
                "then starts a Mochi call. Nothing listens in the background.",
            listOf(Triple("Choose Mochi as assistant", false) {
                runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                    .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }))
        body.addView(Ui.text(this, "What Mochi reads goes only to Mochi's answer (Gemini or Claude); it isn't kept unless " +
            "you ask Mochi to remember it.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(this@AbilitiesActivity, 16), 0, 0) })
    }

    private fun section(title: String, about: String, items: List<Triple<String, Boolean, () -> Unit>>) {
        val card = Ui.card(this)
        card.addView(Ui.text(this, title, 17f))
        card.addView(Ui.text(this, about, 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(this@AbilitiesActivity, 4), 0, 0) })
        items.forEach { (label, on, action) ->
            card.addView(Ui.button(this, if (on) "✓ $label" else "Allow: $label", if (on) Ui.GREEN else Ui.BLUE) {
                if (!on) action()
            }, Ui.matchWidth(this, 8))
        }
        body.addView(card)
    }

    private fun ask(permission: String) = requestPermissions(arrayOf(permission), 1)

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) = draw()

    private fun notificationAccess() = getSystemService(android.app.NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(this, MochiNotifications::class.java))

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
    }
}
