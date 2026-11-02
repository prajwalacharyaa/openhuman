package app.mochi.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * The call screen: an incoming call from Mochi (Answer / Decline), then the live call itself
 * (what's being said, Mute, Speaker, Hang up). Also used when December calls Mochi from the main screen.
 */
class CallActivity : Activity(), Call.Listener {
    private lateinit var status: TextView
    private lateinit var lines: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var controls: LinearLayout
    private lateinit var incoming: LinearLayout
    private lateinit var mute: Button
    private lateinit var speaker: Button
    private val partial = HashMap<String, TextView>()
    private var call: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
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
        status.text = "Mochi is calling"
        line("agent", call?.optString("question") ?: "", true)
        incoming.visibility = View.VISIBLE
        controls.visibility = View.GONE
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

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            answer()
        } else {
            status.text = "Mochi needs the microphone for calls"
            if (call != null) decline() // it stays in Approvals: call back or answer by typing there
        }
    }

    private fun showControls() {
        incoming.visibility = View.GONE
        controls.visibility = View.VISIBLE
        status.text = Call.status
        mute.text = if (Call.muted) "Unmute" else "Mute"
        speaker.text = if (Call.speaker) "Earpiece" else "Speaker"
    }

    override fun onStatus(text: String) {
        status.text = text
    }

    override fun onTranscript(role: String, text: String, final: Boolean) {
        val view = partial.getOrPut(role) { line(role, "", false) }
        view.text = text
        if (final) {
            view.alpha = 1f
            partial.remove(role)
        }
    }

    override fun onEnded() {
        status.text = "Call ended"
        lines.postDelayed({ finish() }, 1_200)
    }

    private fun line(role: String, text: String, final: Boolean): TextView {
        val user = role == "user"
        val view = TextView(this).apply {
            this.text = text
            textSize = 17f
            setPadding(28, 18, 28, 18)
            alpha = if (final) 1f else 0.65f
            setTextColor(if (user) Color.WHITE else Color.parseColor("#1D1D1F"))
            background = GradientDrawable().apply {
                cornerRadius = 36f
                setColor(Color.parseColor(if (user) "#3B6FD8" else "#ECEAE4"))
            }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.topMargin = 14
        lines.addView(view, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return view
    }

    private fun button(label: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = 80f; setColor(Color.parseColor(color)) }
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad * 2)
            setBackgroundColor(Color.parseColor("#F6F5F2"))
        }
        root.addView(TextView(this).apply {
            text = "Mochi"; textSize = 30f; setTextColor(Color.parseColor("#1D1D1F")); gravity = Gravity.CENTER
        })
        status = TextView(this).apply { textSize = 15f; setTextColor(Color.parseColor("#6B6B70")); gravity = Gravity.CENTER }
        root.addView(status)
        lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(lines) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = { LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER } }
        val cell = LinearLayout.LayoutParams(0, (resources.displayMetrics.density * 64).toInt(), 1f).apply {
            marginStart = pad / 2; marginEnd = pad / 2
        }
        incoming = row().apply {
            addView(button("Decline", "#B4472C") { decline() }, cell)
            addView(button("Answer", "#2E9D5B") { answer() }, cell)
        }
        mute = button("Mute", "#55555C") { Call.muted = !Call.muted; showControls() }
        speaker = button("Earpiece", "#55555C") { Call.setSpeaker(!Call.speaker); showControls() }
        controls = row().apply {
            addView(mute, cell)
            addView(speaker, cell)
            addView(button("Hang up", "#B4472C") { if (Call.active) Call.end() else finish() }, cell)
        }
        root.addView(incoming)
        root.addView(controls)
        Ui.edgeToEdge(this, root, pad)
    }

    private fun decline() {
        sendBroadcast(Intent(this, DeclineReceiver::class.java).putExtra(EXTRA_CALL, call?.toString()))
        finish()
    }

    companion object {
        const val EXTRA_CALL = "call"
        const val EXTRA_ANSWER = "answer"
    }
}
