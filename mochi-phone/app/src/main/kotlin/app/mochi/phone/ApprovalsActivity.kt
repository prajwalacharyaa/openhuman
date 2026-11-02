package app.mochi.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What waits for December: Mochi's browser requests (Allow / Deny / answer with a message) and calls that were
 * missed or declined with a decision still in them (Call back / reply / Dismiss). Items stay until dealt with,
 * so a missed ring is never lost. Re-read every 10 s while open.
 */
class ApprovalsActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private var shown = ""                // the items drawn, to redraw only when they change
    @Volatile private var loading = false
    private val replies = mutableListOf<EditText>()
    private val refresh = object : Runnable {
        override fun run() {
            load()
            main.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = Ui.screen(this, "Approvals", back = true)
        status = Ui.text(this, "Loading…", 13f, Ui.MUTED)
        screen.root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.root.addView(ScrollView(this).apply { addView(list) }, Ui.fill())
    }

    override fun onResume() {
        super.onResume()
        RingService.clearApprovalNotice(this) // looking at them now
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    private fun load(force: Boolean = false) {
        // don't wipe a reply December is typing; and don't stack requests while offline
        if (loading || (!force && replies.any { it.hasFocus() || it.text.isNotEmpty() })) return
        loading = true
        background({ try { Mochi.approvals(this) } finally { loading = false } }) { items ->
            if (status.text.startsWith("Can't reach")) status.text = ""
            if (items.isEmpty()) status.text = "Nothing waiting for you."
            else if (status.text == "Nothing waiting for you.") status.text = ""
            val key = items.joinToString { it.optString("id") + it.optString("result") }
            if (key == shown && !force) return@background
            shown = key
            replies.clear()
            list.removeAllViews()
            items.forEach { list.addView(if (it.optString("kind") == "browse") browseCard(it) else callCard(it)) }
        }
    }

    private fun browseCard(item: JSONObject): LinearLayout {
        val card = card("Browser request", item.optString("purpose") +
            item.optString("url").let { if (it.isEmpty()) "" else "\n$it" }, item)
        val approve = item.optString("approve_url")
        card.addView(row(
            button("Allow", "#2E9D5B") { decide(approve, true) },
            button("Deny", "#B4472C") { decide(approve, false) }))
        card.addView(reply("Or tell Mochi something else…") { text ->
            // answering with a message means "not this": deny, then say what to do instead
            Mochi.decideBrowse(this, approve, false)
            Mochi.send(this, item.optString("thread_id"), text)
        })
        return card
    }

    private fun callCard(item: JSONObject): LinearLayout {
        val what = if (item.optString("result") == "declined") "Declined call" else "Missed call"
        val card = card("$what from Mochi", item.optString("question"), item)
        card.addView(row(
            button("Call back", "#2E9D5B") {
                startActivity(Intent(this, CallActivity::class.java)
                    .putExtra(CallActivity.EXTRA_CALL, item.toString()).putExtra(CallActivity.EXTRA_ANSWER, true))
            },
            button("Dismiss", "#55555C") { background({ Mochi.dismissCall(this, item.optString("id")) }) { load(force = true) } }))
        card.addView(reply("Or answer by typing…") { text ->
            Mochi.send(this, item.optString("thread_id"), text)
            Mochi.dismissCall(this, item.optString("id"))
        })
        return card
    }

    private fun decide(approveUrl: String, allow: Boolean) {
        status.text = if (allow) "Allowing…" else "Denying…"
        background({ Mochi.decideBrowse(this, approveUrl, allow) }) {
            status.text = if (allow) "Allowed: Mochi carries on in the chat." else "Denied."
            load(force = true)
        }
    }

    private fun card(title: String, text: String, item: JSONObject): LinearLayout {
        val at = runCatching {
            DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(item.optLong("at")))
        }.getOrDefault("")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@ApprovalsActivity, 16), Ui.dp(this@ApprovalsActivity, 12), Ui.dp(this@ApprovalsActivity, 16), Ui.dp(this@ApprovalsActivity, 12))
            background = GradientDrawable().apply { cornerRadius = 28f; setColor(Color.WHITE) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@ApprovalsActivity, 12) }
            addView(TextView(this@ApprovalsActivity).apply {
                this.text = "$title · $at"; textSize = 13f; setTextColor(Color.parseColor("#6B6B70"))
            })
            addView(TextView(this@ApprovalsActivity).apply {
                this.text = text; textSize = 16f; setTextColor(Color.parseColor("#1D1D1F")); setPadding(0, 8, 0, 8)
            })
        }
    }

    private fun row(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        buttons.forEach { addView(it) }
    }

    private fun reply(hint: String, send: (String) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val input = EditText(this@ApprovalsActivity).apply {
            replies.add(this)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(Color.parseColor("#1D1D1F"))
        }
        addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(button("Send", "#3B6FD8") {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                status.text = "Sending…"
                background({ send(text) }) {
                    status.text = "Sent to Mochi: it answers in the chat."
                    input.setText("")
                    input.clearFocus()
                    load(force = true)
                }
            }
        })
    }

    private fun button(label: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = 40f; setColor(Color.parseColor(color)) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 16; topMargin = 8 }
    }

    /** Run a server call off the main thread, then `done` on it; failures show in the status line. */
    private fun <T> background(work: () -> T, done: (T) -> Unit) {
        Thread {
            val result = runCatching(work)
            main.post {
                if (isDestroyed) return@post
                result.onSuccess(done).onFailure { status.text = "Can't reach Mochi. Is Tailscale on? (${it.message})" }
            }
        }.start()
    }
}
