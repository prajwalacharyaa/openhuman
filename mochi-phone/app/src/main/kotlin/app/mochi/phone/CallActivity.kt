package app.mochi.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Mascot
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.Mood
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.LineIcon
import app.mochi.phone.ui.WhileVisible
import kotlinx.coroutines.delay
import org.json.JSONObject
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.selection.toggleable

/**
 * The call screen: an incoming call from Mochi (Answer / Decline), then the live call itself, with Mochi listening,
 * thinking and talking, what's being said, and Mute, Speaker, Hang up. Also used when December calls Mochi.
 */
class CallActivity : ComponentActivity(), Call.Listener {
    private var status by mutableStateOf("")
    private var incoming by mutableStateOf(false)
    private var ended by mutableStateOf(false)
    private var question by mutableStateOf("") // what Mochi is calling about, while it rings
    private var tick by mutableIntStateOf(0) // redraws the buttons after mute/speaker changes
    private var call: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MochiTheme { CallScreen() } }
        // after a rotation the intent is the same one: don't answer or start a call again
        handle(intent, fresh = savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent, fresh = true)
    }

    override fun onResume() {
        super.onResume()
        Call.listener = this
        if (Call.active) showControls()
    }

    override fun onDestroy() {
        if (Call.listener === this) Call.listener = null
        RingService.onRingStopped = null
        super.onDestroy()
    }

    private fun handle(intent: Intent, fresh: Boolean) {
        call = intent.getStringExtra(EXTRA_CALL)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: call
        when {
            Call.active -> showControls()
            fresh && intent.getBooleanExtra(EXTRA_ANSWER, false) -> answer()
            call != null -> showIncoming()
            else -> finish()
        }
    }

    private fun showIncoming() {
        // missed (45 s) or declined from the notification: this screen goes too
        RingService.onRingStopped = { if (!Call.active) finish() }
        status = "Mochi is calling"
        question = call?.optString("question").orEmpty()
        incoming = true
    }

    private fun answer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        RingService.onRingStopped = null
        RingService.stopRinging(this)
        question = ""; ended = false
        startForegroundService(Intent(this, CallService::class.java).putExtra(EXTRA_CALL, call?.toString())
            .putExtra(EXTRA_AGENT, intent.getStringExtra(EXTRA_AGENT)).putExtra(EXTRA_THREAD, intent.getStringExtra(EXTRA_THREAD)))
        showControls()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        @Suppress("DEPRECATION") super.onRequestPermissionsResult(code, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            answer()
        } else {
            status = "Mochi needs the microphone for calls"
            if (call != null) decline() // it stays on Home: call back or answer by typing there
        }
    }

    private fun showControls() {
        incoming = false
        status = Call.status
        tick++
    }

    override fun onStatus(text: String) {
        status = text
        tick++
    }

    // the transcript itself is kept by Call (Call.said), for the whole call
    override fun onTranscript(role: String, text: String, final: Boolean) = Unit

    override fun onEnded() {
        // why it ended stays on screen a moment longer when December didn't hang up themselves
        val why = Call.endReason
        val byDecember = why.isBlank() || why == "You hung up" || why.startsWith("Hung up")
        status = if (byDecember) "Call ended" else "Call ended: $why"
        ended = true
        window.decorView.postDelayed({ finish() }, if (byDecember) 1_200 else 5_000)
    }

    private fun decline() {
        sendBroadcast(Intent(this, DeclineReceiver::class.java).putExtra(EXTRA_CALL, call?.toString()))
        finish()
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun CallScreen() {
        val c = LocalMochi.current
        val list = rememberLazyListState()
        val lines = Call.said
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        WhileVisible { while (true) { delay(1_000); now = System.currentTimeMillis() } }
        // whether Mochi is talking (the Hold on button), looked at often so it shows the moment a reply starts
        var talking by remember { mutableStateOf(false) }
        WhileVisible { while (true) { talking = Call.talking(); delay(300) } }
        var strangerTaps by remember { mutableStateOf(0L) }
        // follow the newest words: also while a reply grows inside its line (same count, longer text), to the very
        // end of the list (the question card on top shifts the indexes) and to the end of a long reply, not its top.
        // Not while December has scrolled up to read something earlier.
        val newest = lines.lastOrNull()
        LaunchedEffect(lines.size, newest?.text?.length) {
            val total = list.layoutInfo.totalItemsCount
            if (total == 0 || list.isScrollInProgress) return@LaunchedEffect
            val lastSeen = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastSeen >= total - 2) {
                list.scrollToItem(total - 1)
                list.scrollBy(100_000f)   // clamped to the end: shows the bottom of a long last bubble
            }
        }
        val last = lines.lastOrNull()
        // who December is talking with: Mochi, or one of its agents (the face, name and voice change together)
        val team = remember { Agents.cached(this@CallActivity) }
        val speaker = Call.agent?.let { a -> team.firstOrNull { it.name == a } }
        val speakerName = Call.agent?.let { Agents.display(it, team) } ?: "Mochi"
        val mood = when {
            ended || status.startsWith("Error") -> Mood.Sad
            incoming -> Mood.Surprised
            Call.frozen -> Mood.Sleepy
            last != null && last.role != "user" && !last.final -> Mood.Speaking
            last != null && last.role == "user" && !last.final -> Mood.Listening
            status.contains("connect", ignoreCase = true) || status.startsWith("Checking") -> Mood.Thinking
            now - Call.lastSpoke > SLEEPY_AFTER_MS -> Mood.Sleepy
            else -> Mood.Listening
        }
        val statusLine = when {
            Call.silent && !incoming -> "$status · Mochi answers in text"
            mood == Mood.Sleepy && !Call.frozen && !incoming -> "Listening (quiet for a while)"
            else -> status
        }
        Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
            // top bar: back to Home (the call goes on; Home shows "Back to call"), and on Mochi's call the agent
            // working for it now (tap: pass the phone; hold: see what they're doing)
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons2.Back, "Back to Home", c.field, c.ink, 44.dp) { finish() }
                Box(Modifier.weight(1f))
                val other: Pair<String?, Agents.Agent?>? = when {
                    incoming || !Call.active -> null
                    Call.agent != null -> null to null
                    else -> team.firstOrNull { Agents.working(it.name, now) }?.let { it.name to it }
                }
                if (other != null) {
                    val (otherName, otherAgent) = other
                    val label = otherAgent?.display ?: "Mochi"
                    Box(Modifier.size(44.dp).clip(CircleShape).background(c.card)
                        .combinedClickable(role = Role.Button, enabled = !Call.switching,
                            onClickLabel = "Pass the phone to $label", onLongClickLabel = "See what $label is doing",
                            onClick = { Call.switchTo(otherName) },
                            onLongClick = {
                                if (otherName == null) DoingActivity.open(this@CallActivity)
                                else AgentsActivity.open(this@CallActivity, otherName)
                            }), contentAlignment = Alignment.Center) {
                        Mascot(Modifier.size(36.dp), mood = if (otherAgent != null) Mood.Thinking else Mood.Idle,
                            look = otherAgent?.look, label = label)
                    }
                }
            }
            // who you're talking with: the face, the name, what it's doing (a tap on the face opens what it's doing)
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.l), horizontalAlignment = Alignment.CenterHorizontally) {
                Mascot(Modifier.size(104.dp), mood = if (Call.switching) Mood.Surprised else mood,
                    look = speaker?.look, label = speakerName, onTap = {
                        val a = Call.agent
                        if (a == null) DoingActivity.open(this@CallActivity) else AgentsActivity.open(this@CallActivity, a)
                    })
                Text(speakerName, style = Type.display, color = c.ink, modifier = Modifier.padding(top = Space.s))
                Text(statusLine, style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    // TalkBack says it when it changes: connecting, reconnecting, ended
                    modifier = Modifier.padding(top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite })
                if (Call.active && !incoming) {
                    // read with the 1 s tick (and the tap), so the state stays current
                    @Suppress("UNUSED_VARIABLE") val tick = now + strangerTaps
                    Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s),
                        verticalAlignment = Alignment.CenterVertically) {
                        // stranger mode: a tap turns it on or off (also by voice: "stranger mode" / "turn off stranger mode")
                        if (VoiceId.enabled(this@CallActivity)) {
                            val on = Stranger.on
                            Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) c.blueBg else c.field)
                                .toggleable(value = on, role = Role.Switch) { want ->
                                    val said = Stranger.set(this@CallActivity, want)
                                    strangerTaps++
                                    if (said.startsWith("can't")) android.widget.Toast.makeText(this@CallActivity,
                                        said.removePrefix("can't: ").replaceFirstChar { it.uppercase() }, android.widget.Toast.LENGTH_LONG).show()
                                }
                                .semantics { contentDescription = if (on) "Stranger mode on: only your voice reaches Mochi" else "Stranger mode off" }
                                .heightIn(min = 36.dp).padding(horizontal = Space.m, vertical = Space.xs),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) c.blueInk else c.muted))
                                Text(when {
                                        !on -> "Stranger mode"
                                        Stranger.tableOn -> "Only you · talking with others"
                                        else -> "Only you"
                                    }, style = Type.small.copy(fontWeight = Type.strong), color = if (on) c.blueInk else c.muted)
                            }
                        }
                        // this call's tokens: Claude's exact (from the server's feed), Gemini's estimated from the audio
                        Text("Claude ${Doing.fmt(Doing.claudeSince(Call.startedAtMs).total)} · Gemini ≈${Doing.fmt(Call.geminiTokens)}",
                            style = Type.small, color = c.muted, maxLines = 1)
                    }
                }
            }
            // the conversation
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (incoming && question.isNotBlank()) item { Bubble(false, question, true, speakerName) }
                itemsIndexed(lines) { i, l ->
                    if (l.role == "system") Text(l.text, style = Type.small, color = c.muted, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs))
                    // the name only where the speaker changes, not over every line
                    else Bubble(l.role == "user", l.text, l.final, speakerName.takeIf { i == 0 || lines[i - 1].role != l.role })
                }
            }
            key(tick) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.card)
                    .padding(start = Space.l, end = Space.l, top = Space.m, bottom = Space.l)) {
                    // while Mochi talks: one big tap stops the rest of that reply (no speech detection needed). Always laid
                    // out and only faded in and out: adding and removing it each time Mochi started or stopped talking
                    // crashed the call screen (phone-v67, NullPointerException in a layout pass, 4 times on 2026-11-14)
                    if (!incoming) {
                        val shown = talking
                        Box(Modifier.padding(bottom = Space.m).fillMaxWidth().heightIn(min = 48.dp).alpha(if (shown) 1f else 0f)
                            .clip(RoundedCornerShape(24.dp)).background(c.ink)
                            .clickable(enabled = shown, role = Role.Button) { Call.holdOn() }
                            .semantics { contentDescription = "Hold on: stop Mochi's answer" }, contentAlignment = Alignment.Center) {
                            Text("Hold on", style = Type.body.copy(fontWeight = Type.strong), color = c.bg)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Top) {
                        if (incoming) {
                            CallButton(Icons2.HangUp, "Decline", "Decline", c.danger, Color.White) { decline() }
                            CallButton(Icons2.Phone, "Answer", "Answer", Color(0xFF2E9D5B), Color.White) { answer() }
                        } else {
                            CallButton(if (Call.muted) Icons2.MicOff else Icons2.Mic, if (Call.muted) "Unmute" else "Mute",
                                if (Call.muted) "Unmute" else "Mute",
                                if (Call.muted) c.ink else c.field, if (Call.muted) c.bg else c.ink) {
                                Call.muted = !Call.muted
                                showControls()
                            }
                            // one tap, no speech recognition needed: Mochi hears nothing until unfrozen
                            CallButton(Icons2.Snow, if (Call.frozen) "Unfreeze" else "Freeze", if (Call.frozen) "Unfreeze" else "Freeze",
                                if (Call.frozen) c.blueInk else c.field, if (Call.frozen) c.bg else c.ink) {
                                if (Call.frozen) Call.unfreeze() else Call.freezeNow()
                                window.decorView.postDelayed({ showControls() }, 150)
                            }
                            // Speaker → Earpiece → Silent (Mochi's words only as text) → Speaker
                            val sound = when { Call.silent -> "silent"; Call.speaker -> "speaker"; else -> "earpiece" }
                            CallButton(when (sound) { "silent" -> Icons2.Muted; "speaker" -> Icons2.Speaker; else -> Icons2.Earpiece },
                                when (sound) { "silent" -> "Text only"; "speaker" -> "Speaker"; else -> "Earpiece" },
                                when (sound) {
                                    "silent" -> "Silent: Mochi answers in text. Switch to speaker"
                                    "speaker" -> "Speaker: switch to earpiece"
                                    else -> "Earpiece: switch to silent"
                                }, if (sound == "silent") c.ink else c.field, if (sound == "silent") c.bg else c.ink) {
                                when (sound) {
                                    "speaker" -> Call.setSpeaker(false)
                                    "earpiece" -> Call.silent = true
                                    else -> { Call.silent = false; Call.setSpeaker(true) }
                                }
                                showControls()
                            }
                            CallButton(Icons2.HangUp, "End", "Hang up", c.danger, Color.White) { if (Call.active) Call.end() else finish() }
                        }
                    }
                }
            }
        }
    }

    /** One line of the call: December's on the right, Mochi's on the left with its name above the first line. */
    @Composable
    private fun Bubble(user: Boolean, text: String, final: Boolean, name: String?) {
        val c = LocalMochi.current
        val shape = if (user) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            if (name != null) Text(if (user) "You" else name, style = Type.small, color = c.muted,
                modifier = Modifier.padding(start = Space.s, end = Space.s, top = Space.s, bottom = 2.dp))
            Text(text, style = Type.body, color = (if (user) c.onUserBubble else c.ink).copy(alpha = if (final) 1f else 0.6f),
                modifier = Modifier.widthIn(max = 300.dp).clip(shape)
                    .background(if (user) c.userBubble else c.card).padding(horizontal = Space.l, vertical = Space.m))
        }
    }

    /** A round icon call button with its name under it (64 dp; [label] is what TalkBack says). */
    @Composable
    private fun CallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, label: String, bg: Color,
                           fg: Color, onClick: () -> Unit) {
        val c = LocalMochi.current
        Column(Modifier.widthIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }.padding(vertical = Space.xs),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                LineIcon(icon, fg, 28.dp)
            }
            Text(name, style = Type.small, color = c.muted, modifier = Modifier.padding(top = 6.dp))
        }
    }

    companion object {
        /** Quiet this long on a call and Mochi dozes off (wakes the moment anyone speaks). */
        private const val SLEEPY_AFTER_MS = 25_000L
        const val EXTRA_CALL = "call"
        const val EXTRA_ANSWER = "answer"
        /** A call with one of Mochi's agents, in its chat [EXTRA_THREAD] (see [callAgent]). */
        const val EXTRA_AGENT = "agent"
        const val EXTRA_THREAD = "thread"

        /**
         * Call one of Mochi's agents: on a call already, pass the phone to it; else a new call in its current chat
         * (or a new one), in its own voice.
         */
        fun callAgent(c: android.content.Context, agent: String, done: (String?) -> Unit = {}) {
            if (Call.active) {
                Call.switchTo(agent)
                c.startActivity(Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return done(null)
            }
            Thread {
                val thread = runCatching { Agents.callChat(c, agent) }.getOrNull()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (thread == null) return@post done("Can't reach Mochi. Is Tailscale on?")
                    c.startActivity(Intent(c, CallActivity::class.java).putExtra(EXTRA_ANSWER, true)
                        .putExtra(EXTRA_AGENT, agent).putExtra(EXTRA_THREAD, thread).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    done(null)
                }
            }.start()
        }
    }
}
