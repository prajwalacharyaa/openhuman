package app.mochi.phone

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Mochi (or any agent) opening a screen of this app: "open settings", "show my holdings", "open NABIL".
 * A fixed list of screens, never taps on December's behalf; locking, unlocking and the wipe stay touch-only.
 * Android lets the app open a screen itself only while one of its screens is on view; otherwise December gets a
 * notification to tap (as for links).
 */
object AppNav {
    /** Screen names the server may send, with what each is (mochi-phone screen lists these). */
    val SCREENS = linkedMapOf(
        "home" to "Home", "chats" to "all chats", "chat" to "the latest chat (arg new: a new chat)",
        "memory" to "Memory", "inbox" to "Inbox", "maps" to "project maps", "settings" to "Settings",
        "doing" to "what Mochi is doing", "agents" to "the agents (arg: an agent's name)",
        "trading" to "Portfolio & market (arg: a tab, or a stock symbol)", "abilities" to "Phone abilities",
        "call" to "the call screen")
    private val TABS = listOf("trades", "holdings", "ipo", "watchlist", "market", "journal", "calendar")

    fun open(c: Context, screen: String, arg: String): JSONObject {
        val s = screen.trim().lowercase().let { if (it == "research" || it == "portfolio") "trading" else it }
        val a = arg.trim()
        val intent = when (s) {
            "home", "chats", "memory", "inbox", "maps", "settings" ->
                Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, s)
            "chat" -> Intent(c, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_NEW, a.lowercase() == "new")
            "doing" -> Intent(c, DoingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            "agents" -> Intent(c, AgentsActivity::class.java).apply {
                if (a.isNotEmpty()) putExtra(AgentsActivity.EXTRA_AGENT, a.lowercase())
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            "trading" -> Intent(c, ResearchActivity::class.java).apply {
                if (a.lowercase() in TABS) putExtra(ResearchActivity.EXTRA_TAB, a.lowercase())
                else if (a.isNotEmpty()) putExtra(ResearchActivity.EXTRA_STOCK, a.uppercase())
            }
            "abilities" -> Intent(c, AbilitiesActivity::class.java)
            "call" -> if (Call.active) Intent(c, CallActivity::class.java) else return fail("no call is on")
            else -> return fail("no screen called $screen; screens: ${SCREENS.keys.joinToString()}")
        }
        val what = SCREENS[s]!!.substringBefore(" (") + if (a.isNotEmpty()) " ($a)" else ""
        return show(c, intent, s.hashCode() xor a.hashCode(), "Mochi opened $what: tap to see it", what)
    }

    /** Opens [intent] now if the app is on view, else posts a notification December taps. */
    fun show(c: Context, intent: Intent, id: Int, title: String, what: String): JSONObject {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (AppLock.visible && runCatching { c.startActivity(intent) }.isSuccess) return ok("opened $what on the phone")
        val tap = PendingIntent.getActivity(c, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, Mochi.CHANNEL_NOTICES)
            .setSmallIcon(R.drawable.ic_stat_mochi)
            .setContentTitle(title)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
        return ok("the app isn't on screen: sent as a notification, December taps it to open $what")
    }

    private fun ok(result: String) = JSONObject().put("ok", true).put("result", result)
    private fun fail(error: String) = JSONObject().put("ok", false).put("error", error)
}
