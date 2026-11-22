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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import app.mochi.phone.ui.Pill

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
        Text(title, style = Type.small.copy(fontWeight = Type.strong), color = c.muted,
            modifier = Modifier.padding(start = Space.s).semantics { heading() })
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(Space.m), content = content)
    }
}

/** A row with a label, a line of explanation and a switch. */
@Composable
private fun SwitchRow(title: String, detail: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = LocalMochi.current
    // the whole row is the switch: a tap on the words works, and TalkBack reads it as one "switch, on/off"
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(title, style = Type.body, color = c.ink)
            Text(detail, style = Type.small, color = c.muted)
        }
        Switch(on, null, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
    }
}

/** A Settings row: what it is, its state now in one line, and a chevron; a tap opens its page. */
@Composable
private fun NavRow(title: String, state: String, onClick: () -> Unit) {
    val c = LocalMochi.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
        .clickable(role = Role.Button, onClickLabel = "Open $title", onClick = onClick).padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
            if (state.isNotEmpty()) Text(state, style = Type.small, color = c.muted)
        }
        Text("›", style = Type.title, color = c.muted)
    }
}

/**
 * Settings, as rows that open their own page (2026-11-22: one long page had grown hard to use). The things used
 * most come first: calls and voice, chat, the phone's abilities; then the look; then accounts and security; About.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }
    val back = { page = null }
    when (page) {
        "calls" -> SettingsPage("Calls & voice", back) { CallsPage() }
        "chat" -> SettingsPage("Chat", back) { ChatPage() }
        "appearance" -> SettingsPage("Appearance", back) { AppearancePage() }
        "security" -> SettingsPage("Fingerprint & vault", back) { SecurityPage() }
        "claude" -> SettingsPage("Claude accounts", back) { Section("Accounts") { ClaudeAccounts() } }
        "trading" -> SettingsPage("Trading (Samir)", back) { Section("Samir") { TradingSettings() } }
        "server" -> SettingsPage("Server connection", back) { Section("Server") { SetupCard(framed = false) {} } }
        "about" -> SettingsPage("About", back) { AboutPage() }
        else -> Screen("Settings", onBack) {
            val configured = Mochi.configured(ctx)
            val voice = when {
                VoiceId.enabled(ctx) -> "Voice recognition on"
                VoiceId.downloaded(ctx) -> "Voice recognition off"
                else -> "Voice recognition not set up"
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl)
                .padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
                Section("Everyday") {
                    NavRow("Calls & voice", (if (Mochi.longAnswers(ctx)) "Long answers in full" else "Short answers first") +
                        " · $voice") { page = "calls" }
                    NavRow("Chat", "Voice typing: " + if (Mochi.dictationLanguage(ctx).startsWith("ne")) "नेपाली" else "English") { page = "chat" }
                    NavRow("Phone abilities", "Texts, notifications, calls, calendar: what Mochi may use") {
                        ctx.startActivity(Intent(ctx, AbilitiesActivity::class.java))
                    }
                }
                Section("Look") {
                    NavRow("Appearance", when (Appearance.get(ctx)) { Appearance.LIGHT -> "Light"; Appearance.DARK -> "Dark"; else -> "Follows the phone" }) {
                        page = "appearance"
                    }
                }
                Section("Accounts & security") {
                    NavRow("Fingerprint & vault", if (AppLock.enabled(ctx)) "Fingerprint lock on" else "Fingerprint lock off") { page = "security" }
                    if (configured) NavRow("Claude accounts", "Which Claude account Mochi uses") { page = "claude" }
                    if (configured) NavRow("Trading (Samir)", "Paper or live, auto-buy, MeroShare") { page = "trading" }
                    NavRow("Server connection", if (configured) "Connected to ${Mochi.host(ctx)}" else "Not set up") { page = "server" }
                }
                Section("About") { NavRow("About Mochi", "Version and how it connects") { page = "about" } }
            }
        }
    }
}

@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Screen(title, onBack) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl)
            .padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.xl), content = content)
    }
}

@Composable
private fun CallsPage() {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    Section("Answers") {
        var full by remember { mutableStateOf(Mochi.longAnswers(ctx)) }
        SwitchRow("Read long answers in full",
            if (full) "Mochi reads the whole answer, a part at a time; say \"stop\" to cut it short."
            else "Mochi gives the short version first and offers the rest. Saying \"the full thing\" or " +
                "\"everything\" still gets you all of it.", full) { want ->
            Mochi.setLongAnswers(ctx, want); full = want
        }
        Text("Takes effect on the next call.", style = Type.small, color = c.muted)
    }
    Section("Voice recognition") { VoiceRecognition() }
}

/** Download the voice model, turn the check on, teach your voice, remove it all. */
@Composable
private fun VoiceRecognition() {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var downloaded by remember { mutableStateOf(VoiceId.downloaded(ctx)) }
    var on by remember { mutableStateOf(VoiceId.enabled(ctx)) }
    var progress by remember { mutableStateOf(-1f) }
    var note by remember { mutableStateOf("") }
    var teaching by remember { mutableStateOf(false) }
    var fresh by remember { mutableStateOf(false) }
    var own by remember { mutableStateOf(VoiceId.hasOwnPrint(ctx)) }
    Text("Mochi learns to tell your voice from other people's. Your voice and voiceprint stay on this phone; " +
        "only a yes or no goes to the server.", style = Type.small, color = c.muted)
    if (!downloaded) {
        if (progress >= 0f) {
            Text("Downloading the voice model… ${(progress * 100).toInt()}%", style = Type.body, color = c.ink)
            androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else Pill("Download the voice model (40 MB)", true, Modifier.fillMaxWidth()) {
            progress = 0f; note = ""
            io(scope, { VoiceId.download(ctx) { p -> progress = p } }, { progress = -1f; note = Room.friendly(it) }) {
                progress = -1f; downloaded = true; VoiceId.setEnabled(ctx, true); on = true
            }
        }
        Text("From your server over Tailscale, once. On the server it's kept in the encrypted vault.", style = Type.small, color = c.muted)
    } else {
        SwitchRow("Voice recognition", if (on) "On: Mochi knows your voice. Say \"stranger mode\" on a call and only your voice reaches it." else "Off: Mochi doesn't check who's talking.", on) { want ->
            VoiceId.setEnabled(ctx, want); on = want
        }
        NavRow(if (own) "Teach Mochi more" else "Teach Mochi your voice", if (own)
            "${VoiceId.ownClips(ctx)} sentences so far. Add 5 more somewhere else (on speaker, across the room, a noisy " +
                "place): your voice sounds different there, and Mochi needs all of them" else
            "Read 5 short sentences (about 30 seconds) for the best match") { teaching = true }
        if (own) Text("Start the voiceprint over", style = Type.small, color = c.muted,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { fresh = true; teaching = true }
                .heightIn(min = 44.dp).padding(vertical = Space.s))
        Text("Remove the voice model from this phone", style = Type.small, color = c.danger,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                VoiceId.remove(ctx); downloaded = false; on = false; own = false
            }.heightIn(min = 44.dp).padding(vertical = Space.s))
    }
    if (note.isNotEmpty()) Text(note, style = Type.small, color = c.danger)
    if (teaching) TeachVoice(fresh, { teaching = false; fresh = false }) { own = true; note = it }
}

/** Five sentences read aloud, each recorded on the phone; their mean is the "own" voiceprint. */
@Composable
private fun TeachVoice(fresh: Boolean, onClose: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val lines = remember { listOf(
        "Mochi, what's on my calendar today?",
        "Please remind me to call the bank tomorrow morning.",
        "मोची, आज के छ नयाँ समाचार?",
        "Open the room and read me the verdict.",
        "The quick brown fox jumps over the lazy dog near the river.") }
    val clips = remember { androidx.compose.runtime.mutableStateListOf<ShortArray>() }
    var recording by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val rec = remember { VoiceRecorder() }
    val askMic = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> if (!ok) error = "Mochi needs the microphone for this." }
    androidx.compose.material3.AlertDialog(onDismissRequest = { rec.stop(); onClose() },
        title = { Text("Teach Mochi your voice", style = Type.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                val n = clips.size
                if (n < lines.size) {
                    Text("Sentence ${n + 1} of ${lines.size}. Tap Record, read it in your normal voice, tap Stop.",
                        style = Type.small, color = c.muted)
                    Text(lines[n], style = Type.title, color = c.ink)
                } else Text(if (saving) "Making your voiceprint…" else "All 5 recorded.", style = Type.body, color = c.ink)
                if (error.isNotEmpty()) Text(error, style = Type.small, color = c.danger)
            }
        },
        confirmButton = {
            val n = clips.size
            when {
                n < lines.size -> androidx.compose.material3.TextButton({
                    if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        askMic.launch(android.Manifest.permission.RECORD_AUDIO); return@TextButton
                    }
                    if (!recording) { error = ""; rec.start(); recording = true }
                    else {
                        recording = false
                        val pcm = rec.stop()
                        if (pcm.size < 16_000 * 1.2) error = "That was too short: read the whole sentence." else clips.add(pcm)
                    }
                }) { Text(if (recording) "Stop" else "Record") }
                !saving -> androidx.compose.material3.TextButton({
                    saving = true
                    io(scope, { VoiceId.teach(ctx, clips.toList(), fresh) }, { saving = false; error = Room.friendly(it); clips.clear() }) { alike ->
                        saving = false
                        onDone(if (alike < 0.6f) "Saved, but the recordings didn't sound much alike: a quieter room helps. You can teach it again." else "")
                        onClose()
                    }
                }) { Text("Save") }
            }
        },
        dismissButton = { androidx.compose.material3.TextButton({ rec.stop(); onClose() }) { Text("Cancel") } })
}

/** Records 16 kHz mono PCM until stopped (at most 10 s). */
private class VoiceRecorder {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val out = java.io.ByteArrayOutputStream()

    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        out.reset(); running = true
        thread = Thread {
            val min = android.media.AudioRecord.getMinBufferSize(16_000, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT)
            val r = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000,
                android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
            val buf = ByteArray(3200)
            runCatching {
                r.startRecording()
                while (running && out.size() < 16_000 * 2 * 10) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) synchronized(out) { out.write(buf, 0, n) }
                }
            }
            runCatching { r.stop() }
            r.release()
        }.apply { start() }
    }

    fun stop(): ShortArray {
        running = false
        thread?.join(1500)
        thread = null
        val b = synchronized(out) { out.toByteArray() }
        return ShortArray(b.size / 2) { ((b[2 * it + 1].toInt() shl 8) or (b[2 * it].toInt() and 0xff)).toShort() }
    }
}

@Composable
private fun ChatPage() {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    Section("Voice typing") {
        var lang by remember { mutableStateOf(Mochi.dictationLanguage(ctx)) }
        Text("Language", style = Type.body, color = c.ink)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.field).padding(4.dp)) {
            listOf("en-US" to "English", "ne-NP" to "नेपाली (Nepali)").forEach { (key, label) ->
                val on = lang == key
                Text(label, style = Type.body.copy(fontWeight = if (on) Type.strong else null), color = if (on) c.bg else c.ink,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (on) c.ink else c.field)
                        .selectable(selected = on, role = Role.RadioButton) { lang = key; Mochi.setDictationLanguage(ctx, key) }
                        .padding(vertical = Space.m), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Text("The mic in a chat's message box types what you say. Long-press a message to copy, share or select it.",
            style = Type.small, color = c.muted)
    }
}

@Composable
private fun AppearancePage() {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    Section("Theme") {
        var mode by remember { mutableStateOf(Appearance.get(ctx)) }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.field).padding(4.dp)) {
            listOf(Appearance.SYSTEM to "System", Appearance.LIGHT to "Light", Appearance.DARK to "Dark").forEach { (key, label) ->
                val on = mode == key
                Text(label, style = Type.body.copy(fontWeight = if (on) Type.strong else null), color = if (on) c.bg else c.ink,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (on) c.ink else c.field)
                        .selectable(selected = on, role = Role.RadioButton) { mode = key; Appearance.set(ctx, key) }
                        .padding(vertical = Space.m), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Text("System follows the phone's dark mode.", style = Type.small, color = c.muted)
    }
}

@Composable
private fun SecurityPage() {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    Section("Fingerprint") {
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
    }
    if (Mochi.configured(ctx)) Section("Server vault") { UnlockCard() }
}

@Composable
private fun AboutPage() {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    Section("Mochi") {
        val version = remember {
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }
                .getOrDefault("?")
        }
        Text("Mochi $version", style = Type.body, color = c.ink)
        Text("Talks to your server over Tailscale only.", style = Type.small, color = c.muted)
    }
}
