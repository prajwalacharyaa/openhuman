package app.mochi.phone

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * "Share with a laptop" (2026-11-25): another laptop on this phone's hotspot opens Mochi's chat page (and, in the
 * mode December picked, one editor) at https://<hotspot address>:8443. The laptop never joins Tailscale: this phone
 * relays only /bridge/… to mochi-ring with December's token and this session's id. Nothing else of the server is
 * reachable. A 6-digit code pairs each laptop; sharing ends after an hour, when the hotspot goes off, or on Stop.
 * HTTPS with a certificate the server made (Chrome lets a page edit a folder on the laptop only on a secure page):
 * the browser warns once, December passes it. Plain Kotlin and OkHttp, no new library.
 */
object Bridge {
    const val PORT = 8443
    private const val FOR_MS = 60 * 60_000L
    private const val TRIES = 5
    val MODES = listOf("chat" to "Chat only", "local" to "A folder on this laptop", "projects" to "Mochi's projects")
    fun modeName(m: String) = MODES.firstOrNull { it.first == m }?.second ?: m

    class State(val running: Boolean = false, val mode: String = "chat", val url: String = "", val code: String = "",
                val until: Long = 0, val note: String = "")
    var state by mutableStateOf(State())
        private set

    @Volatile private var server: ServerSocket? = null
    @Volatile private var session = ""
    private val cookies = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var failed = 0
    private val random = SecureRandom()
    private lateinit var app: Context

    private fun newCode() = "%06d".format(random.nextInt(1_000_000))
    private fun hex(n: Int) = ByteArray(n).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** The phone's own address on its hotspot (not on the Wi-Fi or mobile network it uses itself), or null. */
    fun hotspotAddress(c: Context): InetAddress? {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        // the networks the phone itself gets the internet from (mobile data, Wi-Fi): their addresses aren't the
        // hotspot. Only those with internet: Android lists the hotspot itself as a network too (2026-11-26, phone-v102
        // excluded every network and so found no hotspot at all).
        @Suppress("DEPRECATION")
        val own = cm.allNetworks.filter { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            } ?: false
        }.flatMap { n -> cm.getLinkProperties(n)?.linkAddresses?.map { it.address }.orEmpty() }.toSet()
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().map { i.name to it } }
            .filter { (_, a) -> a.isSiteLocalAddress }
        val hotspotName = Regex("^(ap|swlan|softap|wlan|wigig|rndis)")
        val found = all.filter { (_, a) -> a !in own }
        return (found.firstOrNull { (n, _) -> hotspotName.containsMatchIn(n) } ?: found.firstOrNull()
            // nothing left after that: a hotspot-type interface anyway, rather than refusing to start
            ?: all.firstOrNull { (n, _) -> Regex("^(ap|swlan|softap)").containsMatchIn(n) })?.second
    }

    /** Start sharing in [mode]. Blocking (network): call off the main thread. Null, or why it couldn't start. */
    @Synchronized
    fun start(c: Context, mode: String): String? {
        app = c.applicationContext
        if (state.running) stop(c)
        val ip = hotspotAddress(c) ?: return "Turn on your phone's hotspot first, and connect the laptop to it."
        return try {
            val s = post(c, "/bridge-session", JSONObject().put("mode", mode))
            session = s.getString("session")
            val cert = get(c, "/bridge-cert")
            val ks = KeyStore.getInstance("PKCS12").apply {
                load(Base64.decode(cert.getString("p12"), Base64.DEFAULT).inputStream(), cert.getString("password").toCharArray())
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(ks, cert.getString("password").toCharArray())
            }
            val tls = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            val socket = tls.serverSocketFactory.createServerSocket(PORT, 16, ip)
            server = socket
            cookies.clear(); failed = 0
            state = State(true, mode, "https://${ip.hostAddress}:$PORT", newCode(), System.currentTimeMillis() + FOR_MS)
            Thread({ accept(socket) }, "mochi-bridge").start()
            c.startForegroundService(Intent(c, BridgeService::class.java))
            null
        } catch (e: Exception) {
            Log.i("mochi-bridge", "couldn't start: ${e.message}")
            runCatching { server?.close() }
            "Couldn't start sharing: ${e.message}"
        }
    }

    /** Stop sharing: the server forgets the session, the laptop's page stops working. */
    fun stop(c: Context, why: String = "") {
        val s = server
        server = null
        runCatching { s?.close() }
        cookies.clear()
        val was = session
        session = ""
        state = State(note = why)
        if (was.isNotEmpty()) Thread { runCatching { delete(c, "/bridge-session") } }.start()
        c.stopService(Intent(c, BridgeService::class.java))
    }

    /** Every 15 s while sharing: the hour is up, or the hotspot went off. */
    fun check(c: Context) {
        if (!state.running) return
        when {
            System.currentTimeMillis() > state.until -> stop(c, "Sharing ended after an hour.")
            hotspotAddress(c)?.hostAddress != state.url.removePrefix("https://").substringBefore(":") ->
                stop(c, "Sharing ended: the hotspot went off.")
        }
    }

    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            Thread({ runCatching { serve(client) }; runCatching { client.close() } }, "mochi-bridge-client").start()
        }
    }

    private class Req(val method: String, val path: String, val headers: Map<String, String>, val input: InputStream)

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            out.write(b)
            if (out.size() > 16_384) throw IllegalStateException("line too long")
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 120_000
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()
        while (server != null) {
            val first = readLine(input) ?: return
            val parts = first.split(" ")
            if (parts.size < 3) return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                if (headers.size > 60) return
            }
            if (headers["transfer-encoding"] != null) return respond(output, 411, "text/plain", "Length required".toByteArray(), close = true)
            handle(Req(parts[0], parts[1], headers, input), output)
            if (headers["connection"].equals("close", true)) return
        }
    }

    private fun cookieOk(r: Req) = r.headers["cookie"].orEmpty().split(";").map { it.trim() }
        .any { it.startsWith("mb=") && it.removePrefix("mb=") in cookies }

    private fun handle(r: Req, out: OutputStream) {
        val length = r.headers["content-length"]?.toLongOrNull() ?: 0L
        val path = r.path.substringBefore("#")
        val bare = path.substringBefore("?")
        if (bare == "/pair" && r.method == "POST") {
            val body = readBody(r.input, minOf(length, 1000L)).toString(Charsets.UTF_8)
            val code = Regex("code=(\\d{6})").find(body)?.groupValues?.get(1)
            if (code != null && code == state.code && failed < TRIES) {
                val cookie = hex(24)
                cookies.add(cookie)
                return respond(out, 303, "text/plain", ByteArray(0), mapOf("Location" to "/bridge/",
                    "Set-Cookie" to "mb=$cookie; Path=/; HttpOnly; Secure; SameSite=Strict"))
            }
            failed++
            if (failed >= TRIES) state = State(state.running, state.mode, state.url, newCode(), state.until).also { failed = 0 }
            return respond(out, 200, "text/html; charset=utf-8", pairPage("That code didn't match. " +
                if (failed == 0) "Too many tries: the phone shows a new code." else "Try again.").toByteArray())
        }
        if (!cookieOk(r)) {
            skip(r.input, length)
            return respond(out, if (bare == "/" || bare == "/pair" || bare.startsWith("/bridge")) 200 else 404,
                "text/html; charset=utf-8", pairPage("").toByteArray())
        }
        if (bare == "/" || bare == "/pair") return respond(out, 303, "text/plain", ByteArray(0), mapOf("Location" to "/bridge/"))
        if (bare != "/bridge" && !bare.startsWith("/bridge/")) {
            skip(r.input, length)
            return respond(out, 404, "text/plain", "Not here".toByteArray())
        }
        relay(r, path, length, out)
    }

    /** One request to mochi-ring, its answer back; bodies in pieces (a dropped file can be 100 MB). */
    private fun relay(r: Req, path: String, length: Long, out: OutputStream) {
        val kind = (r.headers["content-type"] ?: "application/octet-stream").toMediaType()
        val body: RequestBody? = if (r.method == "GET" || r.method == "HEAD") null else object : RequestBody() {
            override fun contentType() = kind
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                var left = length
                val buf = ByteArray(65_536)
                while (left > 0) {
                    val n = r.input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    left -= n
                }
            }
        }
        val req = Request.Builder().url(Mochi.ringUrl(app) + path)
            .header("Authorization", "Bearer ${Mochi.token(app)}")
            .header("X-Mochi-Bridge", session)
            .method(r.method, body ?: if (r.method in setOf("POST", "PUT", "DELETE")) ByteArray(0).toRequestBody() else null)
            .build()
        try {
            Mochi.http.newCall(req).execute().use { res ->
                val stream = res.body?.byteStream()
                val n = res.body?.contentLength() ?: 0L
                val keep = listOf("Content-Type", "Content-Disposition", "Content-Security-Policy", "X-Content-Type-Options", "Cache-Control")
                val extra = keep.mapNotNull { h -> res.header(h)?.let { h to it } }.toMap()
                if (n >= 0 && stream != null) {
                    head(out, res.code, n, extra)
                    stream.copyTo(out, 65_536)
                } else {
                    val all = stream?.readBytes() ?: ByteArray(0)
                    head(out, res.code, all.size.toLong(), extra)
                    out.write(all)
                }
                out.flush()
            }
        } catch (e: Exception) {
            respond(out, 502, "text/plain", "Mochi's server isn't reachable from the phone (Tailscale off?)".toByteArray())
        }
    }

    private fun head(out: OutputStream, code: Int, length: Long, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code ${if (code < 400) "OK" else "Error"}\r\n")
        sb.append("Content-Length: $length\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n")
        headers.forEach { (k, v) -> sb.append("$k: ${v.replace("\r", "").replace("\n", "")}\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
    }

    private fun respond(out: OutputStream, code: Int, kind: String, body: ByteArray, headers: Map<String, String> = emptyMap(),
                        close: Boolean = false) {
        head(out, code, body.size.toLong(), headers + ("Content-Type" to kind) + (if (close) mapOf("Connection" to "close") else emptyMap()))
        out.write(body)
        out.flush()
    }

    private fun readBody(input: InputStream, n: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var left = n
        while (left > 0) {
            val got = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (got < 0) break
            out.write(buf, 0, got)
            left -= got
        }
        return out.toByteArray()
    }

    private fun skip(input: InputStream, n: Long) { readBody(input, minOf(n, 1L shl 20)) }

    private fun pairPage(note: String) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Mochi</title>
<style>body{font:16px system-ui;background:#fff8ef;color:#2b2118;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
form{background:#fff;padding:28px;border-radius:14px;border:1px solid #eadfcf;text-align:center}
input{font-size:28px;letter-spacing:6px;width:7em;text-align:center;padding:8px;margin:14px 0;border-radius:8px;border:1px solid #eadfcf}
button{background:#e8873a;color:#fff;border:0;border-radius:8px;padding:10px 22px;font-size:16px}p{color:#b3261e;font-size:14px}</style>
</head><body><form method="post" action="/pair"><b>Mochi</b><br>Type the 6-digit code shown on the phone.<br>
<input name="code" inputmode="numeric" pattern="\d{6}" maxlength="6" autofocus required><br><button>Open</button>
<p>$note</p></form></body></html>"""

    // ---- the ring, with December's token (over Tailscale) ----
    private fun call(c: Context, req: Request.Builder): JSONObject =
        Mochi.http.newCall(req.header("Authorization", "Bearer ${Mochi.token(c)}").build()).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IllegalStateException(
                runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: "HTTP ${r.code}")
            runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        }
    private fun get(c: Context, path: String) = call(c, Request.Builder().url(Mochi.ringUrl(c) + path))
    fun post(c: Context, path: String, body: JSONObject) = call(c, Request.Builder().url(Mochi.ringUrl(c) + path)
        .post(body.toString().toRequestBody("application/json".toMediaType())))
    private fun delete(c: Context, path: String) = call(c, Request.Builder().url(Mochi.ringUrl(c) + path).delete())
    fun browserSites(c: Context) = get(c, "/browse/sites")
}

/** Keeps sharing going with the screen off, shows the code, and checks the hour and the hotspot. */
class BridgeService : Service() {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            Bridge.check(this@BridgeService)
            if (Bridge.state.running) { notice(); main.postDelayed(this, 15_000) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { Bridge.stop(this); return START_NOT_STICKY }
        Mochi.createChannels(this)
        startForeground(Mochi.NOTIFY_BRIDGE, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        main.removeCallbacks(tick)
        main.postDelayed(tick, 15_000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun notice() = getSystemService(android.app.NotificationManager::class.java).notify(Mochi.NOTIFY_BRIDGE, build())

    private fun build(): Notification {
        val s = Bridge.state
        val stop = PendingIntent.getService(this, 21, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val left = ((s.until - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        return Notification.Builder(this, Mochi.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_mochi)
            .setContentTitle("Sharing with a laptop: ${Bridge.modeName(s.mode)}")
            .setContentText("${s.url}  ·  code ${s.code}  ·  $left min left")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 22, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, "Stop sharing", stop).build())
            .build()
    }

    companion object { const val ACTION_STOP = "app.mochi.phone.BRIDGE_STOP" }
}
