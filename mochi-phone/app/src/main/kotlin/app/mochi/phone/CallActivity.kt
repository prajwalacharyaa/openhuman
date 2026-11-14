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
        Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().padding(Space.l),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // top: back to Home (the call goes on; Home shows "Back to call"), with "Mochi", what it's doing and
            // this call's tokens beside it (like the "What Mochi is doing" header)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                RoundButton(Icons2.Back, "Back to Home", c.field, c.ink, 44.dp) { finish() }
                Column(Modifier.weight(1f)) {
                    Text(speakerName, style = Type.title, color = c.ink)
                    Text(when {
                            Call.silent && !incoming -> "$status · silent: Mochi answers in text"
                            mood == Mood.Sleepy && !Call.frozen && !incoming -> "Listening (quiet for a while)"
                            else -> status
                        }, style = Type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    // this call's tokens: Claude's exact (from the server's feed), Gemini's estimated from the audio
                    if (Call.active && !incoming) Text("Claude ${Doing.fmt(Doing.claudeSince(Call.startedAtMs).total)}" +
                        " · Gemini ≈${Doing.fmt(Call.geminiTokens)} tokens", style = Type.small, color = c.muted, maxLines = 1)
                }
                // top right: on a call with an agent, Mochi; on Mochi's call, the agent working for it right now.
                // A tap passes the phone to them; holding opens what they're doing (the call goes on)
                val other: Pair<String?, Agents.Agent?>? = when {
                    incoming || !Call.active -> null
                    Call.agent != null -> null to null
                    else -> team.firstOrNull { Agents.working(it.name, now) }?.let { it.name to it }
                }
                if (other != null) {
                    val (otherName, otherAgent) = other
                    val label = otherAgent?.display ?: "Mochi"
                    Box(Modifier.size(52.dp).clip(CircleShape).background(c.card)
                        .combinedClickable(role = Role.Button, enabled = !Call.switching,
                            onClickLabel = "Pass the phone to $label", onLongClickLabel = "See what $label is doing",
                            onClick = { Call.switchTo(otherName) },
                            onLongClick = {
                                if (otherName == null) DoingActivity.open(this@CallActivity)
                                else AgentsActivity.open(this@CallActivity, otherName)
                            }), contentAlignment = Alignment.Center) {
                        Mascot(Modifier.size(42.dp), mood = if (otherAgent != null) Mood.Thinking else Mood.Idle,
                            look = otherAgent?.look, label = label)
                    }
                }
            }
            // who you're talking with (a tap opens what they're doing), then the conversation
            Mascot(Modifier.padding(top = Space.s).size(96.dp), mood = if (Call.switching) Mood.Surprised else mood,
                look = speaker?.look, label = speakerName, onTap = {
                    val a = Call.agent
                    if (a == null) DoingActivity.open(this@CallActivity) else AgentsActivity.open(this@CallActivity, a)
                })
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                if (incoming && question.isNotBlank()) item { Bubble(false, question, true) }
                items(lines) { l ->
                    if (l.role == "system") Text(l.text, style = Type.small, color = c.muted, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs))
                    else Bubble(l.role == "user", l.text, l.final)
                }
            }
            key(tick) {
                // while Mochi talks: one big tap stops the rest of that reply (no speech detection needed). Always laid
                // out and only faded in and out: adding and removing it each time Mochi started or stopped talking
                // crashed the call screen (phone-v67, NullPointerException in a layout pass, 4 times on 2026-11-14)
                if (!incoming) {
                    val shown = talking
                    Box(Modifier.padding(bottom = Space.m).fillMaxWidth().height(56.dp).alpha(if (shown) 1f else 0f)
                        .clip(RoundedCornerShape(28.dp)).background(c.ink)
                        .clickable(enabled = shown, role = Role.Button) { Call.holdOn() }
                        .semantics { contentDescription = "Hold on: stop Mochi's answer" }, contentAlignment = Alignment.Center) {
                        Text("Hold on", style = Type.body.copy(fontWeight = Type.strong), color = c.bg)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    if (incoming) {
                        CallButton(Icons2.HangUp, "Decline", c.danger, Color.White) { decline() }
                        CallButton(Icons2.Phone, "Answer", Color(0xFF2E9D5B), Color.White) { answer() }
                    } else {
                        CallButton(if (Call.muted) Icons2.MicOff else Icons2.Mic, if (Call.muted) "Unmute" else "Mute",
                            if (Call.muted) c.ink else c.field, if (Call.muted) c.bg else c.ink) {
                            Call.muted = !Call.muted
                            showControls()
                        }
                        // one tap, no speech recognition needed: Mochi hears nothing until unfrozen
                        CallButton(Icons2.Snow, if (Call.frozen) "Unfreeze" else "Freeze",
                            if (Call.frozen) c.blueInk else c.field, if (Call.frozen) c.bg else c.ink) {
                            if (Call.frozen) Call.unfreeze() else Call.freezeNow()
                            window.decorView.postDelayed({ showControls() }, 150)
                        }
                        // Speaker → Earpiece → Silent (Mochi's words only as text) → Speaker
                        val sound = when { Call.silent -> "silent"; Call.speaker -> "speaker"; else -> "earpiece" }
                        CallButton(when (sound) { "silent" -> Icons2.Muted; "speaker" -> Icons2.Speaker; else -> Icons2.Earpiece },
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
                        CallButton(Icons2.HangUp, "Hang up", c.danger, Color.White) { if (Call.active) Call.end() else finish() }
                    }
                }
            }
        }
    }

    @Composable
    private fun Bubble(user: Boolean, text: String, final: Boolean) {
        val c = LocalMochi.current
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
            Text(text, style = Type.body, color = (if (user) c.onUserBubble else c.ink).copy(alpha = if (final) 1f else 0.65f),
                modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(22.dp))
                    .background(if (user) c.userBubble else c.card).padding(horizontal = Space.l, vertical = Space.m))
        }
    }

    /** A round icon call button, 72 dp (named for TalkBack). */
    @Composable
    private fun CallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Color, fg: Color,
                           onClick: () -> Unit) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(bg).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            LineIcon(icon, fg, 30.dp)
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
