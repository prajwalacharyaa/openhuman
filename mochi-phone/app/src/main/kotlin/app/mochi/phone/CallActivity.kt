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
import androidx.compose.runtime.mutableStateListOf
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
import org.json.JSONObject

/**
 * The call screen: an incoming call from Mochi (Answer / Decline), then the live call itself, with Mochi listening,
 * thinking and talking, what's being said, and Mute, Speaker, Hang up. Also used when December calls Mochi.
 */
class CallActivity : ComponentActivity(), Call.Listener {
    /** One line said on the call. */
    data class Line(val role: String, val text: String, val final: Boolean)

    private val lines = mutableStateListOf<Line>()
    private var status by mutableStateOf("")
    private var incoming by mutableStateOf(false)
    private var speaking by mutableStateOf(false)
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
        lines.clear()
        call?.optString("question")?.takeIf { it.isNotBlank() }?.let { lines.add(Line("agent", it, true)) }
        incoming = true
    }

    private fun answer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        RingService.onRingStopped = null
        RingService.stopRinging(this)
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

    override fun onTranscript(role: String, text: String, final: Boolean) {
        val i = lines.indexOfLast { it.role == role && !it.final }
        val line = Line(role, text, final)
        if (i >= 0) lines[i] = line else lines.add(line)
        speaking = role != "user" && !final
    }

    override fun onEnded() {
        status = "Call ended"
        speaking = false
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
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }
        val mood = when {
            speaking -> Mood.Speaking
            incoming -> Mood.Idle
            status.contains("connect", ignoreCase = true) || status.contains("think", ignoreCase = true) -> Mood.Thinking
            else -> Mood.Listening
        }
        Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().padding(Space.l),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Mascot(Modifier.padding(top = Space.xl).size(160.dp), mood = mood, onTap = {})
            Text("Mochi", style = Type.display, color = c.ink, modifier = Modifier.padding(top = Space.m))
            Text(status, style = Type.body, color = c.muted, textAlign = TextAlign.Center)
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                items(lines) { l ->
                    val user = l.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                        Text(l.text, style = Type.body,
                            color = (if (user) c.onUserBubble else c.ink).copy(alpha = if (l.final) 1f else 0.65f),
                            modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(22.dp))
                                .background(if (user) c.userBubble else c.card).padding(horizontal = Space.l, vertical = Space.m))
                    }
                }
            }
            key(tick) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    if (incoming) {
                        CallButton("Decline", c.danger, Color.White, Modifier.weight(1f)) { decline() }
                        CallButton("Answer", Color(0xFF2E9D5B), Color.White, Modifier.weight(1f)) { answer() }
                    } else {
                        val mute = when {
                            Call.frozen -> "Unfreeze"
                            Call.muted -> "Unmute"
                            else -> "Mute"
                        }
                        CallButton(mute, c.field, c.ink, Modifier.weight(1f)) {
                            if (Call.frozen) Call.unfreeze() else Call.muted = !Call.muted
                            showControls()
                        }
                        CallButton(if (Call.speaker) "Earpiece" else "Speaker", c.field, c.ink, Modifier.weight(1f)) {
                            Call.setSpeaker(!Call.speaker); showControls()
                        }
                        CallButton("Hang up", c.danger, Color.White, Modifier.weight(1f)) {
                            if (Call.active) Call.end() else finish()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CallButton(label: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
        Box(modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(bg)
            .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
            Text(label, style = Type.title, color = fg)
        }
    }

    companion object {
        const val EXTRA_CALL = "call"
        const val EXTRA_ANSWER = "answer"
    }
}
