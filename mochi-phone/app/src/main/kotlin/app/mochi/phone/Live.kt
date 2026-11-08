package app.mochi.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live replies for one conversation: a minimal Socket.IO (Engine.IO v4) client on the core's `/socket.io/`
 * endpoint, the same feed the desktop app uses. Authenticates with Mochi's core token, joins `thread:<id>`
 * and passes on the web-chat events (`text_delta`, `tool_call`, `tool_result`, `chat_done`, `chat_error`…)
 * on the main thread. Reconnects by itself while open; [close] when the screen goes away.
 */
class Live(private val c: Context, private val threadId: String, private val onEvent: (String, JSONObject) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile var connected = false
        private set

    fun open() {
        if (closed || !Mochi.configured(c)) return
        val req = Request.Builder().url("wss://${Mochi.host(c)}:8446/socket.io/?EIO=4&transport=websocket").build()
        ws = Mochi.http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = retry()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry()
        })
    }

    fun close() {
        closed = true
        connected = false
        ws?.close(1000, null)
        ws = null
        main.removeCallbacksAndMessages(null)
    }

    private fun retry() {
        connected = false
        if (!closed) main.postDelayed({ open() }, 3_000)
    }

    private fun handle(socket: WebSocket, text: String) {
        when {
            text.startsWith("0") -> // Engine.IO open: connect to the default namespace with the token
                socket.send("40" + JSONObject().put("token", Mochi.token(c)))
            text == "2" -> socket.send("3") // ping → pong
            text.startsWith("40") -> { // namespace connected: join this conversation's room
                connected = true
                socket.send("421" + JSONArray().put("thread:subscribe").put(JSONObject().put("thread_id", threadId)))
            }
            text.startsWith("44") -> { connected = false; socket.close(1000, null) } // refused (bad token)
            text.startsWith("42") -> {
                val arr = runCatching { JSONArray(text.substring(2)) }.getOrNull() ?: return
                val name = arr.optString(0)
                val data = arr.optJSONObject(1) ?: JSONObject()
                if (data.optString("thread_id").let { it.isEmpty() || it == threadId }) main.post { onEvent(name, data) }
            }
        }
    }
}
