package app.mochi.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Past conversations (the same list the desktop app has), newest first. Pick one to chat in it or call
 * Mochi in it; that conversation then stays the current one for "Call Mochi" and "Chat with Mochi".
 */
class ConversationsActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = Ui.screen(this, "Conversations", back = true)
        status = Ui.text(this, "Loading…", 13f, Ui.MUTED)
        screen.root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.root.addView(ScrollView(this).apply { addView(list) }, Ui.fill())
    }

    override fun onResume() {
        super.onResume()
        Thread {
            val result = runCatching {
                val reply = Mochi.rpc(this, "openhuman.threads_list", JSONObject())
                val threads = (reply.optJSONObject("data") ?: reply).getJSONArray("threads")
                (0 until threads.length()).map { threads.getJSONObject(it) }
                    .filter { it.isNull("parentThreadId") || it.optString("parentThreadId").isEmpty() } // not worker threads
                    .sortedByDescending { it.optString("lastMessageAt") }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess(::show).onFailure { status.text = "Can't reach Mochi. Is Tailscale on? (${it.message})" }
            }
        }.start()
    }

    private fun show(threads: List<JSONObject>) {
        val current = Mochi.thread(this)
        status.text = if (threads.isEmpty()) "No conversations yet." else ""
        list.removeAllViews()
        threads.forEach { list.addView(row(it, it.optString("id") == current)) }
    }

    private fun row(thread: JSONObject, current: Boolean): LinearLayout {
        val id = thread.optString("id")
        val when_ = runCatching {
            DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(ZoneId.systemDefault())
                .format(Instant.parse(thread.optString("lastMessageAt")))
        }.getOrDefault("")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@ConversationsActivity, 16), Ui.dp(this@ConversationsActivity, 12), Ui.dp(this@ConversationsActivity, 16), Ui.dp(this@ConversationsActivity, 12))
            background = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(Color.WHITE)
                if (current) setStroke(4, Color.parseColor("#3B6FD8"))
            }
        }
        card.addView(TextView(this).apply {
            text = thread.optString("title").ifEmpty { "Conversation" }
            textSize = 17f; setTextColor(Color.parseColor("#1D1D1F"))
        })
        card.addView(TextView(this).apply {
            text = "$when_ · ${thread.optInt("messageCount")} messages" + if (current) " · current" else ""
            textSize = 13f; setTextColor(Color.parseColor("#6B6B70"))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        actions.addView(action("Chat", "#3B6FD8") {
            Mochi.setThread(this, id)
            startActivity(Intent(this, ChatActivity::class.java))
        })
        actions.addView(action("Call", "#2E9D5B") {
            Mochi.setThread(this, id)
            startActivity(Intent(this, CallActivity::class.java).putExtra(CallActivity.EXTRA_ANSWER, true))
        })
        card.addView(actions)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 12)
        card.layoutParams = lp
        return card
    }

    private fun action(label: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = 40f; setColor(Color.parseColor(color)) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 16; topMargin = 12 }
    }
}
