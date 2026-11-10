package app.mochi.phone

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * December's LinkedIn (mochi-ring /linkedin): the login, posts waiting for approval, scheduled ones, drafts,
 * failures and recent posts. Shown on the page of the agent that has the `linkedin` skill. Approving goes through
 * the same tailnet approval page as everything else (no token: the tap is proven to come from December's tailnet).
 */
object LinkedIn {
    /** Status, drafts (text cut to 280) and the last posts. Blocking. */
    fun load(c: Context): JSONObject = Doing.get(c, "/linkedin")

    /** One draft or post in full. Blocking. */
    fun full(c: Context, id: String): JSONObject = Doing.get(c, "/linkedin/drafts/$id")

    /** "submit" (ask December to approve) or "delete". Blocking. */
    fun act(c: Context, id: String, action: String) {
        val req = Request.Builder().url("${Mochi.ringUrl(c)}/linkedin/drafts/$id/$action")
            .header("Authorization", "Bearer ${Mochi.token(c)}")
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        Mochi.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException(
                runCatching { JSONObject(r.body?.string() ?: "").optString("error") }.getOrNull()?.ifBlank { null } ?: "HTTP ${r.code}")
        }
    }

    /** The approval page's address for a draft (the login link sits on the same tailnet listener). */
    fun approveUrl(status: JSONObject, id: String) = status.optString("login_url").removeSuffix("/linkedin/login") + "/approve/$id"

    /** A draft's image from its approval page. Blocking. */
    fun image(url: String): ImageBitmap? = runCatching {
        Mochi.http.newCall(Request.Builder().url("$url/image").build()).execute().use { r ->
            val bytes = r.body?.bytes() ?: return null
            if (!r.isSuccessful) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }.getOrNull()
}

private fun JSONObject.list(key: String) = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

private fun firstLine(d: JSONObject) = d.optString("text").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

private fun at(d: JSONObject, key: String = "at"): String =
    d.optLong(key).takeIf { it > 0 }?.let { java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(it * 1000)) }.orEmpty()

@Composable
internal fun LinkedInBoard(status: JSONObject, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf("") }
    val drafts = status.list("drafts")
    val waiting = drafts.filter { it.optString("status") == "pending" }
    val scheduled = drafts.filter { it.optString("status") in setOf("scheduled", "approved", "posting") }
    val failed = drafts.filter { it.optString("status") == "failed" }
    val saved = drafts.filter { it.optString("status") == "draft" }
    val posted = status.list("posted")

    fun run(d: JSONObject, work: () -> Unit) {
        val id = d.optString("id")
        busy = busy + id; error = ""
        io(scope, work, { busy = busy - id; error = it.message ?: "didn't work" }) { busy = busy - id; opened = null; onChanged() }
    }
    fun decide(d: JSONObject, allow: Boolean) = run(d) { Mochi.decideApproval(ctx, LinkedIn.approveUrl(status, d.optString("id")), allow) }
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        // the login
        val connected = status.optBoolean("connected")
        val days = status.optInt("days_left")
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (connected && days >= 7) c.greenBg else c.amberBg)
            .padding(Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(when {
                !status.optBoolean("configured") -> "LinkedIn isn't set up on the server yet"
                !connected -> "LinkedIn isn't connected"
                else -> "Posting as ${status.optString("name")} · login ends in $days days"
            }, style = Type.small, color = if (connected && days >= 7) c.greenInk else c.amberInk, modifier = Modifier.weight(1f))
            if (status.optBoolean("configured") && (!connected || days < 7)) Pill("Log in", true) { open(status.optString("login_url")) }
        }
        if (error.isNotBlank()) Text(error, style = Type.small, color = c.danger)

        Text(if (waiting.isEmpty()) "Nothing waiting for you" else "Waiting for you (${waiting.size})",
            style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.s))
        waiting.forEach { d ->
            PostCard(d, c.amberBg, if (d.optLong("at") > 0) "Goes out ${at(d)} once approved" else "Goes out when you approve",
                d.optString("id") in busy, onOpen = { opened = d }) {
                Pill("Post it", true, Modifier.weight(1f)) { decide(d, true) }
                Pill("Keep as draft", false, Modifier.weight(1f)) { decide(d, false) }
            }
        }
        if (scheduled.isNotEmpty()) {
            Text("Scheduled", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.m))
            scheduled.forEach { d ->
                PostCard(d, c.blueBg, if (d.optString("status") == "scheduled") "Goes out ${at(d)}" else "Posting now…",
                    false, onOpen = { opened = d })
            }
        }
        if (failed.isNotEmpty()) {
            Text("Didn't go out", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.m))
            failed.forEach { d ->
                PostCard(d, c.card, d.optString("error"), d.optString("id") in busy, onOpen = { opened = d }) {
                    Pill("Try again", true, Modifier.weight(1f)) { run(d) { LinkedIn.act(ctx, d.optString("id"), "submit") } }
                    Pill("Delete", false, Modifier.weight(1f)) { deleting = d }
                }
            }
        }
        Text(if (saved.isEmpty()) "No drafts" else "Drafts (${saved.size})", style = Type.title, color = c.ink,
            modifier = Modifier.padding(top = Space.m))
        if (saved.isEmpty()) Text("Ask for a post and it's kept here until you want it out.", style = Type.small, color = c.muted)
        saved.forEach { d ->
            PostCard(d, c.card, listOf(d.optString("note"), at(d).takeIf { it.isNotBlank() }?.let { "for $it" })
                .filter { !it.isNullOrBlank() }.joinToString(" · "), d.optString("id") in busy, onOpen = { opened = d }) {
                Pill("Ask me to approve", true, Modifier.weight(1f)) { run(d) { LinkedIn.act(ctx, d.optString("id"), "submit") } }
                Pill("Delete", false, Modifier.weight(1f)) { deleting = d }
            }
        }
        if (posted.isNotEmpty()) {
            Text("Posted lately", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.m))
            posted.take(10).forEach { d ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) {
                    d.optString("url").takeIf { it.isNotBlank() }?.let(::open) ?: run { opened = d }
                }.padding(vertical = Space.xs)) {
                    Text(firstLine(d), style = Type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(at(d, "posted_at") + if (d.optString("url").isNotBlank()) " · open on LinkedIn" else "",
                        style = Type.small, color = c.muted)
                }
            }
        }
    }
    opened?.let { d ->
        PostSheet(d, status, onClose = { opened = null },
            onApprove = if (d.optString("status") == "pending") ({ decide(d, true) }) else null,
            onKeep = if (d.optString("status") == "pending") ({ decide(d, false) }) else null,
            onSubmit = if (d.optString("status") in setOf("draft", "failed")) ({ run(d) { LinkedIn.act(ctx, d.optString("id"), "submit") } }) else null)
    }
    deleting?.let { d ->
        AlertDialog(onDismissRequest = { deleting = null }, containerColor = c.bg,
            title = { Text("Delete this draft?", style = Type.title, color = c.ink) },
            text = { Text(firstLine(d), style = Type.body, color = c.muted, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { deleting = null; run(d) { LinkedIn.act(ctx, d.optString("id"), "delete") } }) {
                Text("Delete", color = c.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep", color = c.ink) } })
    }
}

@Composable
private fun PostCard(d: JSONObject, bg: androidx.compose.ui.graphics.Color, line: String, busy: Boolean, onOpen: () -> Unit,
                     buttons: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null) {
    val c = LocalMochi.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(bg)
        .clickable(role = Role.Button, onClickLabel = "Read the whole post", onClick = onOpen).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(d.optString("text"), style = Type.body, color = c.ink, maxLines = 5, overflow = TextOverflow.Ellipsis)
        Text(listOf(line, "${d.optInt("chars")} characters", if (d.optBoolean("has_image")) "with image" else "")
            .filter { it.isNotBlank() }.joinToString(" · "), style = Type.small, color = c.muted, maxLines = 3,
            overflow = TextOverflow.Ellipsis)
        if (buttons != null) {
            if (busy) Text("…", style = Type.small, color = c.muted)
            else Row(horizontalArrangement = Arrangement.spacedBy(Space.s), content = buttons)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostSheet(d: JSONObject, status: JSONObject, onClose: () -> Unit, onApprove: (() -> Unit)?, onKeep: (() -> Unit)?,
                      onSubmit: (() -> Unit)?) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var full by remember { mutableStateOf(d) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(d.optString("id")) {
        io(scope, { LinkedIn.full(ctx, d.optString("id")) }) { if (it.has("text")) full = it }
        if (d.optBoolean("has_image")) io(scope, { LinkedIn.image(LinkedIn.approveUrl(status, d.optString("id"))) }) { picture = it }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.bg, dragHandle = null) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LinkedIn post", style = Type.display, color = c.ink, modifier = Modifier.weight(1f))
                RoundButton(Icons2.Close, "Close", c.field, c.ink, 40.dp, onClose)
            }
            Text(full.optString("text"), style = Type.body, color = c.ink)
            picture?.let { Image(it, contentDescription = "The post's image", contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) }
            Text(listOf("${full.optInt("chars")} characters", at(full).takeIf { it.isNotBlank() }?.let { "for $it" },
                full.optString("error").takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "),
                style = Type.small, color = c.muted)
            if (onApprove != null && onKeep != null) Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Pill("Post it", true, Modifier.weight(1f), onClick = onApprove)
                Pill("Keep as draft", false, Modifier.weight(1f), onClick = onKeep)
            }
            if (onSubmit != null) Pill("Ask me to approve", true, Modifier.fillMaxWidth(), onClick = onSubmit)
            full.optString("url").takeIf { it.isNotBlank() }?.let { url ->
                Pill("Open on LinkedIn", false, Modifier.fillMaxWidth()) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        }
    }
}
