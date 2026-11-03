package app.mochi.phone

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Typed chat with Mochi, the same conversation the calls use (and the desktop app shows). A message goes
 * to Claude like a desktop chat; the screen re-reads the conversation every few seconds, so replies and
 * anything said on a call show up on their own. The newest message stays in view, keyboard open or not.
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
    private var shown = ""          // what's drawn (last id + count + waiting), to redraw only on change
    private var waitingSince = 0L   // when we sent a message and haven't seen a reply yet
    @Volatile private var loading = false // a refresh is in flight (offline, they'd pile up)
    private val refresh = object : Runnable {
        override fun run() {
            load()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        thread = Mochi.thread(this)
        // after a rotation the intent is the same one: don't start yet another conversation
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_NEW, false) || thread == null) startNew()
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
            status.text = "New conversation. A call within half an hour continues it."
        }
    }

    private fun load() {
        val id = thread ?: return
        if (loading) return
        loading = true
        background({
            try {
                val all = Mochi.messages(this, id)
                (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter(::visible)
            } finally {
                loading = false
            }
        }) { messages ->
            val last = messages.lastOrNull()
            if (waitingSince > 0 && last != null && last.optString("sender") != "user") waitingSince = 0
            if (status.text.startsWith("Can't reach")) status.text = ""
            val key = "${last?.optString("id")}/${messages.size}/${waitingSince > 0}"
            if (key == shown) return@background
            shown = key
            lines.removeAllViews()
            messages.forEach(::bubble)
            if (waitingSince > 0) thinking()
            toBottom()
        }
    }

    private fun toBottom() = scroll.post { scroll.scrollTo(0, lines.height) }

    /** Hides the server's notes to the voice model; everything December and Mochi said is shown. */
    private fun visible(m: JSONObject): Boolean {
        val text = m.optString("content").trim()
        return text.isNotEmpty() && !text.startsWith("(December picked up the phone call")
    }

    private fun bubble(m: JSONObject) {
        val user = m.optString("sender") == "user"
        val voice = m.optJSONObject("extraMetadata")?.optString("source") == "voice"
        val text = m.optString("content").trim().replace(VOICE_ASK_NOTE, "")
        add(TextView(this).apply {
            this.text = if (voice) "🎙 $text" else text
            textSize = 16f
            setTextIsSelectable(true)
            setTextColor(if (user) Color.WHITE else Ui.TEXT)
            background = GradientDrawable().apply {
                cornerRadius = Ui.dp(this@ChatActivity, 18).toFloat()
                setColor(if (user) Color.parseColor(Ui.BLUE) else Ui.CARD)
            }
        }, user)
    }

    private fun thinking() = add(TextView(this).apply {
        text = "Mochi is thinking…"
        textSize = 15f
        setTypeface(typeface, Typeface.ITALIC)
        setTextColor(Ui.MUTED)
        background = GradientDrawable().apply { cornerRadius = Ui.dp(this@ChatActivity, 18).toFloat(); setColor(Ui.CARD) }
    }, false)

    private fun add(view: TextView, user: Boolean) {
        val d = Ui.dp(this, 1)
        view.setPadding(14 * d, 10 * d, 14 * d, 10 * d)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.topMargin = 8 * d
        if (user) lp.marginStart = 48 * d else lp.marginEnd = 48 * d
        lines.addView(view, lp)
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val id = thread ?: return startNew() // the first try to start one failed (offline): try again
        input.setText("")
        send.isEnabled = false
        waitingSince = System.currentTimeMillis()
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
        val screen = Ui.screen(this, "Chat", back = true)
        ram = Ui.text(this, size = 13f, color = Ui.MUTED).apply { setPadding(0, 0, Ui.dp(this@ChatActivity, 12), 0) }
        screen.right.addView(ram)
        ramWatch = Mochi.ramWatcher(this, ram)
        screen.right.addView(Ui.button(this, "New", Ui.GREY) { startNew() })
        status = Ui.text(this, size = 13f, color = Ui.MUTED)
        screen.root.addView(status)
        lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, Ui.dp(this@ChatActivity, 8)) }
        scroll = ScrollView(this).apply {
            addView(lines)
            isVerticalFadingEdgeEnabled = true
            // the keyboard opening shrinks the list: keep the newest message in view
            addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom -> if (bottom < oldBottom) toBottom() }
        }
        screen.root.addView(scroll, Ui.fill())
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@ChatActivity, 12), Ui.dp(this@ChatActivity, 4), Ui.dp(this@ChatActivity, 6), Ui.dp(this@ChatActivity, 4))
            background = GradientDrawable().apply { cornerRadius = Ui.dp(this@ChatActivity, 24).toFloat(); setColor(Ui.CARD) }
        }
        input = EditText(this).apply {
            hint = "Message Mochi"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
            background = null
            setTextColor(Ui.TEXT)
            setOnFocusChangeListener { _, focused -> if (focused) toBottom() }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        send = Ui.button(this, "Send", Ui.BLUE) { sendTyped() }
        bar.addView(send)
        screen.root.addView(bar, Ui.matchWidth(this, 8))
    }

    companion object {
        const val EXTRA_NEW = "new"
        private const val REFRESH_MS = 3_000L
        /** The prefix mochi-ask adds for Claude; not something December said. */
        private val VOICE_ASK_NOTE = Regex("""^\(Asked on a voice call[^)]*\)\s*""")
    }
}
