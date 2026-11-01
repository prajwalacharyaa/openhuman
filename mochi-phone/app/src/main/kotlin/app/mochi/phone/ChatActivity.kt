package app.mochi.phone

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Typed chat with Mochi, the same conversation the calls use (and the desktop app shows). A message goes
 * to Claude like a desktop chat; the screen re-reads the conversation every few seconds, so replies and
 * anything said on a call show up on their own.
 */
class ChatActivity : Activity() {
    private lateinit var lines: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var ram: TextView
    private lateinit var ramWatch: Pair<() -> Unit, () -> Unit>
    private val main = Handler(Looper.getMainLooper())
    private var thread: String? = null
    private var shown = ""          // id of the last message drawn, to redraw only on change
    private var waitingSince = 0L   // when we sent a message and haven't seen a reply yet
    private val refresh = object : Runnable {
        override fun run() {
            load()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
        thread = Mochi.thread(this)
        if (intent.getBooleanExtra(EXTRA_NEW, false) || thread == null) startNew()
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
        ramWatch.first()
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        ramWatch.second()
        super.onPause()
    }

    private fun startNew() {
        status.text = "Starting a new conversation…"
        background({ Mochi.newThread(this) }) { id ->
            thread = id
            Mochi.setThread(this, id)
            lines.removeAllViews()
            shown = ""
            status.text = "New conversation. Calls continue this one too."
        }
    }

    private fun load() {
        val id = thread ?: return
        background({ Mochi.messages(this, id) }) { all ->
            val messages = (0 until all.length()).map { all.getJSONObject(it) }.filter(::visible)
            val last = messages.lastOrNull()
            if (waitingSince > 0 && last != null && last.optString("sender") != "user") waitingSince = 0
            status.text = if (waitingSince > 0) "Mochi is thinking…" else ""
            val key = last?.optString("id") + messages.size
            if (key == shown) return@background
            shown = key
            lines.removeAllViews()
            messages.forEach(::bubble)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Hides the server's notes to the voice model; everything December and Mochi said is shown. */
    private fun visible(m: JSONObject): Boolean {
        val text = m.optString("content").trim()
        return text.isNotEmpty() && !text.startsWith("(December picked up the phone call")
    }

    private fun bubble(m: JSONObject) {
        val user = m.optString("sender") == "user"
        val voice = m.optJSONObject("extraMetadata")?.optString("source") == "voice"
        val text = m.optString("content").trim().replace(VOICE_ASK_NOTE, "")
        val view = TextView(this).apply {
            this.text = if (voice) "🎙 $text" else text
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(28, 18, 28, 18)
            setTextColor(if (user) Color.WHITE else Color.parseColor("#1D1D1F"))
            background = GradientDrawable().apply {
                cornerRadius = 36f
                setColor(Color.parseColor(if (user) "#3B6FD8" else "#ECEAE4"))
            }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.topMargin = 14
        if (user) lp.marginStart = 80 else lp.marginEnd = 80
        lines.addView(view, lp)
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        val id = thread ?: return
        if (text.isEmpty()) return
        input.setText("")
        send.isEnabled = false
        waitingSince = System.currentTimeMillis()
        status.text = "Mochi is thinking…"
        background({ Mochi.send(this, id, text) }) {
            send.isEnabled = true
            load()
        }
    }

    /** Run a server call off the main thread, then `done` on it; failures show in the status line. */
    private fun <T> background(work: () -> T, done: (T) -> Unit) {
        Thread {
            val result = runCatching(work)
            main.post {
                if (isDestroyed) return@post
                result.onSuccess(done).onFailure {
                    send.isEnabled = true
                    waitingSince = 0
                    status.text = "Can't reach Mochi. Is Tailscale on? (${it.message})"
                }
            }
        }.start()
    }

    private fun buildUi() {
        val pad = (resources.displayMetrics.density * 12).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#F6F5F2"))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Mochi"; textSize = 24f; setTextColor(Color.parseColor("#1D1D1F"))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ram = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#6B6B70")); setPadding(0, 0, 16, 0) }
        header.addView(ram)
        ramWatch = Mochi.ramWatcher(this, ram)
        header.addView(Button(this).apply {
            text = "New"; isAllCaps = false
            setOnClickListener { startNew() }
        })
        root.addView(header)
        status = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#6B6B70")) }
        root.addView(status)
        lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(lines) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Message Mochi"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
            setTextColor(Color.parseColor("#1D1D1F"))
        }
        bar.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        send = Button(this).apply {
            text = "Send"; isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = 48f; setColor(Color.parseColor("#3B6FD8")) }
            setOnClickListener { sendTyped() }
        }
        bar.addView(send)
        root.addView(bar)
        setContentView(root)
    }

    companion object {
        const val EXTRA_NEW = "new"
        private const val REFRESH_MS = 3_000L
        /** The prefix mochi-ask adds for Claude; not something December said. */
        private val VOICE_ASK_NOTE = Regex("""^\(Asked on a voice call[^)]*\)\s*""")
    }
}
