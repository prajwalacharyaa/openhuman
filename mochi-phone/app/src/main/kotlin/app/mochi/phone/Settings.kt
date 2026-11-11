package app.mochi.phone

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type

/** Light / dark / follow the phone, for this app only (Android 12+ applies it to every screen and remembers it). */
object Appearance {
    const val SYSTEM = "system"; const val LIGHT = "light"; const val DARK = "dark"
    private fun prefs(c: Context) = c.getSharedPreferences("mochi-look", Context.MODE_PRIVATE)
    fun get(c: Context) = prefs(c).getString("theme", SYSTEM) ?: SYSTEM
    fun set(c: Context, mode: String) {
        prefs(c).edit().putString("theme", mode).apply()
        c.getSystemService(UiModeManager::class.java).setApplicationNightMode(when (mode) {
            LIGHT -> UiModeManager.MODE_NIGHT_NO
            DARK -> UiModeManager.MODE_NIGHT_YES
            else -> UiModeManager.MODE_NIGHT_AUTO
        })
    }
}

/** One group on the Settings page: a title over a rounded card. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalMochi.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(title, style = Type.small.copy(fontWeight = Type.strong), color = c.muted, modifier = Modifier.padding(start = Space.s))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(Space.m), content = content)
    }
}

/** A row with a label, a line of explanation and a switch. */
@Composable
private fun SwitchRow(title: String, detail: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = LocalMochi.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(title, style = Type.body, color = c.ink)
            Text(detail, style = Type.small, color = c.muted)
        }
        Switch(on, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    Screen("Settings", onBack) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl)
            .padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
            Section("Server connection") { SetupCard(framed = false) {} }

            Section("Security") {
                var lockOn by remember { mutableStateOf(AppLock.enabled(ctx)) }
                val can = remember { AppLock.available(ctx) }
                var note by remember { mutableStateOf("") }
                SwitchRow("Fingerprint to open Mochi",
                    if (can) "Asks for your fingerprint or screen lock when Mochi opens, and after 30 seconds away. " +
                        "Incoming calls from Mochi can always be answered." else "Set a fingerprint or screen lock on the phone first.",
                    lockOn, enabled = can) { want ->
                    val a = ctx as? Activity ?: return@SwitchRow
                    // confirm it's you both ways: turning the lock off needs the fingerprint too
                    AppLock.ask(a, if (want) "Turn on the fingerprint lock" else "Turn off the fingerprint lock") { error ->
                        if (error == null) { AppLock.setEnabled(ctx, want); lockOn = want; note = "" } else note = error
                    }
                }
                if (note.isNotEmpty()) Text(note, style = Type.small, color = c.muted)
                if (Mochi.configured(ctx)) {
                    Text("Server vault", style = Type.body, color = c.ink, modifier = Modifier.padding(top = Space.s))
                    UnlockCard()
                }
            }

            if (Mochi.configured(ctx)) Section("Trading (Samir)") { TradingSettings() }

            Section("Appearance") {
                var mode by remember { mutableStateOf(Appearance.get(ctx)) }
                Text("Theme", style = Type.body, color = c.ink)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.field).padding(4.dp)) {
                    listOf(Appearance.SYSTEM to "System", Appearance.LIGHT to "Light", Appearance.DARK to "Dark").forEach { (key, label) ->
                        val on = mode == key
                        Text(label, style = Type.body.copy(fontWeight = if (on) Type.strong else null), color = if (on) c.bg else c.ink,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (on) c.ink else c.field)
                                .clickable(role = Role.RadioButton) { mode = key; Appearance.set(ctx, key) }
                                .padding(vertical = Space.m), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                Text("System follows the phone's dark mode.", style = Type.small, color = c.muted)
            }

            Section("Phone") {
                Text("Phone abilities", style = Type.body, color = c.ink,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { ctx.startActivity(Intent(ctx, AbilitiesActivity::class.java)) }
                        .padding(vertical = Space.s))
                Text("Texts, notifications, calls and the assistant: what Mochi may use on this phone.", style = Type.small, color = c.muted)
            }

            Section("About") {
                val version = remember {
                    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }
                        .getOrDefault("?")
                }
                Text("Mochi $version", style = Type.body, color = c.ink)
                Text("Talks to your server over Tailscale only.", style = Type.small, color = c.muted)
            }
        }
    }
}
