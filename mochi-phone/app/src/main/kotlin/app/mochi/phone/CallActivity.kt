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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
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
import app.mochi.phone.ui.LineIcon
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
        startForegroundService(Intent(this, CallService::class.java).putExtra(EXTRA_CALL, call?.toString()))
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
        status = "Call ended"
        ended = true
        window.decorView.postDelayed({ finish() }, 1_200)
    }

    private fun decline() {
        sendBroadcast(Intent(this, DeclineReceiver::class.java).putExtra(EXTRA_CALL, call?.toString()))
        finish()
    }

    @Composable
    private fun CallScreen() {
        val c = LocalMochi.current
        val list = rememberLazyListState()
        val lines = Call.said
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }
        val last = lines.lastOrNull()
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
            Mascot(Modifier.padding(top = Space.xl).size(160.dp), mood = mood, onTap = {})
            Text("Mochi", style = Type.display, color = c.ink, modifier = Modifier.padding(top = Space.m))
            Text(if (mood == Mood.Sleepy && !Call.frozen && !incoming) "Listening (quiet for a while)" else status,
                style = Type.body, color = c.muted, textAlign = TextAlign.Center)
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                if (incoming && question.isNotBlank()) item { Bubble(false, question, true) }
                items(lines) { l -> Bubble(l.role == "user", l.text, l.final) }
            }
            key(tick) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    if (incoming) {
                        CallButton(Icons2.HangUp, "Decline", c.danger, Color.White) { decline() }
                        CallButton(Icons2.Phone, "Answer", Color(0xFF2E9D5B), Color.White) { answer() }
                    } else {
                        val muteLabel = when {
                            Call.frozen -> "Unfreeze"
                            Call.muted -> "Unmute"
                            else -> "Mute"
                        }
                        CallButton(if (Call.muted || Call.frozen) Icons2.MicOff else Icons2.Mic, muteLabel,
                            if (Call.muted || Call.frozen) c.ink else c.field, if (Call.muted || Call.frozen) c.bg else c.ink) {
                            if (Call.frozen) Call.unfreeze() else Call.muted = !Call.muted
                            showControls()
                        }
                        CallButton(if (Call.speaker) Icons2.Speaker else Icons2.Earpiece,
                            if (Call.speaker) "Speaker on: switch to earpiece" else "Earpiece: switch to speaker",
                            c.field, c.ink) { Call.setSpeaker(!Call.speaker); showControls() }
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
    }
}
