package app.mochi.phone

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.activity.result.PickVisualMediaRequest
import okio.source
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.Field
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * December's LinkedIn (mochi-ring /linkedin), on the page of the agent with the `linkedin` skill (Nadia): the
 * login, posts waiting for approval, scheduled ones, drafts, failures, recent posts, and an editor for each draft.
 * December's own edits keep a draft where it is (their edit is their approval); Post it / Keep as draft go
 * straight to mochi-ring with the app's token, which only December's app holds. "Suggest changes", "Make an
 * image" and "New post idea" are messages in one Mochi chat labelled `linkedin`: Mochi passes them to Nadia (and
 * Mark for wording), so they cost an agent run; everything else costs no tokens.
 */
object LinkedIn {
    const val MAX_TEXT = 3000
    /** About this many characters show on a phone before LinkedIn's "…see more". */
    const val FOLD = 140

    fun load(c: Context): JSONObject = Doing.get(c, "/linkedin")

    fun full(c: Context, id: String): JSONObject = Doing.get(c, "/linkedin/drafts/$id")

    private fun post(c: Context, path: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url("${Mochi.ringUrl(c)}$path")
            .header("Authorization", "Bearer ${Mochi.token(c)}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        Mochi.http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IllegalStateException(
                runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: "HTTP ${r.code}")
            return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        }
    }

    /** December's edit: any of text, at (0 = no time), image_b64, no_image, alt. Blocking. */
    fun edit(c: Context, id: String, changes: JSONObject) = post(c, "/linkedin/drafts/$id", changes.put("by_december", true))

    /** "submit", "delete", "approve" (Post it) or "decline" (Keep as draft). Blocking. */
    fun act(c: Context, id: String, action: String) = post(c, "/linkedin/drafts/$id/$action", JSONObject())

    /** The approval page's address (the login link sits on the same tailnet listener); its image is under it. */
    fun approveUrl(status: JSONObject, id: String) = status.optString("login_url").removeSuffix("/linkedin/login") + "/approve/$id"

    fun image(url: String): ImageBitmap? = runCatching {
        Mochi.http.newCall(Request.Builder().url("$url/image").build()).execute().use { r ->
            val bytes = r.body?.bytes() ?: return null
            if (!r.isSuccessful) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }.getOrNull()

    /** December asks for a video (noted on the draft; the request itself goes to Nadia through Mochi). Blocking. */
    fun requestVideo(c: Context, id: String, voice: Boolean) {
        post(c, "/linkedin/drafts/$id/request-video", JSONObject())
        ask(c, "December requests a video for LinkedIn draft $id (from Nadia's page in the app), " +
            (if (voice) "WITH a voice-over. " else "without a voice-over (music only if it helps). ") +
            "Make it with mochi-video from the post's own lines and attach it with mochi-linkedin video $id <mp4>; keep the text as it is.")
    }

    /** Plays the draft's video in the phone's own player (streamed from the approval page over Tailscale). */
    fun play(c: Context, status: JSONObject, id: String) = c.startActivity(Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(approveUrl(status, id) + "/video"), "video/mp4").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** December's own video for the draft: streamed (never all in memory) to mochi-ring, which checks it's an MP4.
     *  Blocking; up to 200 MB. */
    fun uploadVideo(c: Context, id: String, uri: Uri) {
        val cr = c.contentResolver
        val type = cr.getType(uri) ?: ""
        if (type != "video/mp4") throw IllegalStateException("LinkedIn takes MP4 videos (this one is ${type.ifBlank { "unknown" }})")
        val size = cr.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 }
            ?: cr.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1L }
            ?: -1L
        if (size <= 0) throw IllegalStateException("Couldn't read that video")
        if (size > 200L * 1024 * 1024) throw IllegalStateException("The video must be up to 200 MB")
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = "video/mp4".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: okio.BufferedSink) {
                cr.openInputStream(uri)?.use { ins -> sink.writeAll(ins.source()) } ?: throw java.io.IOException("can't open the video")
            }
        }
        val req = Request.Builder().url("${Mochi.ringUrl(c)}/linkedin/drafts/$id/video")
            .header("Authorization", "Bearer ${Mochi.token(c)}").post(body).build()
        Mochi.http.newBuilder().writeTimeout(10, java.util.concurrent.TimeUnit.MINUTES).readTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
            .build().newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException(
                    runCatching { JSONObject(r.body?.string() ?: "").optString("error") }.getOrNull()?.ifBlank { null } ?: "HTTP ${r.code}")
            }
    }

    /** A gallery photo as base64 JPEG (shrunk to 1600 px by [Attachments]). Blocking. */
    fun photo(c: Context, uri: Uri): String? =
        Attachments.load(c, uri)?.takeIf { it.image }?.marker?.substringAfter("base64,")?.removeSuffix("]")

    /** The one Mochi chat for LinkedIn requests from this page (made the first time). Blocking. */
    fun chat(c: Context): String {
        Mochi.threads(c).firstOrNull { t ->
            val l = t.optJSONArray("labels")
            l != null && (0 until l.length()).any { l.optString(it) == "linkedin" }
        }?.optString("id")?.takeIf { it.isNotBlank() }?.let { return it }
        return Mochi.rpc(c, "openhuman.threads_create_new", JSONObject().put("labels", JSONArray().put("linkedin")))
            .let { it.optJSONObject("data") ?: it }.getString("id")
    }

    /** A request to Nadia through Mochi, about one draft or a new idea. Blocking. */
    fun ask(c: Context, text: String) = Mochi.send(c, chat(c), text, followup = true)
}

private fun JSONObject.list(key: String) = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

private fun firstLine(d: JSONObject) = d.optString("text").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

private fun fmt(t: Long): String = if (t <= 0) "" else
    java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t * 1000))

@Composable
internal fun LinkedInBoard(status: JSONObject, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf("") }
    var idea by remember { mutableStateOf("") }
    var ideaSent by remember { mutableStateOf(false) }
    val drafts = status.list("drafts")
    val waiting = drafts.filter { it.optString("status") == "pending" }
    val scheduled = drafts.filter { it.optString("status") in setOf("scheduled", "approved", "posting") }
    val failed = drafts.filter { it.optString("status") == "failed" }
    val saved = drafts.filter { it.optString("status") == "draft" }
    val posted = status.list("posted")

    fun run(d: JSONObject, work: () -> Unit) {
        val id = d.optString("id")
        busy = busy + id; error = ""
        io(scope, work, { busy = busy - id; error = it.message ?: "didn't work" }) { busy = busy - id; onChanged() }
    }
    fun act(d: JSONObject, action: String) = run(d) { LinkedIn.act(ctx, d.optString("id"), action) }
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        val connected = status.optBoolean("connected")
        val days = status.optInt("days_left")
        val good = connected && days >= 7
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (good) c.greenBg else c.amberBg)
            .padding(Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(when {
                !status.optBoolean("configured") -> "LinkedIn isn't set up on the server yet"
                !connected -> "LinkedIn isn't connected"
                else -> "Posting as ${status.optString("name")} · login ends in $days days"
            }, style = Type.small, color = if (good) c.greenInk else c.amberInk, modifier = Modifier.weight(1f))
            if (status.optBoolean("configured") && !good) Pill("Log in", true) { open(status.optString("login_url")) }
        }
        if (error.isNotBlank()) Text(error, style = Type.small, color = c.danger)

        Text(if (waiting.isEmpty()) "Nothing waiting for you" else "Waiting for you (${waiting.size})",
            style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.s))
        waiting.forEach { d ->
            PostCard(d, c.amberBg, if (d.optLong("at") > 0) "Goes out ${fmt(d.optLong("at"))} once approved" else "Goes out when you approve",
                d.optString("id") in busy, onOpen = { opened = d }) {
                Pill("Post it", true, Modifier.weight(1f)) { act(d, "approve") }
                Pill("Keep as draft", false, Modifier.weight(1f)) { act(d, "decline") }
            }
        }
        if (scheduled.isNotEmpty()) {
            Text("Scheduled", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.m))
            scheduled.forEach { d ->
                val isScheduled = d.optString("status") == "scheduled"
                PostCard(d, c.blueBg, if (isScheduled) "Goes out ${fmt(d.optLong("at"))}" else "Posting now…",
                    d.optString("id") in busy, onOpen = { if (isScheduled) opened = d },
                    buttons = if (isScheduled) ({ Pill("Don't post it", false, Modifier.weight(1f)) { act(d, "decline") } }) else null)
            }
        }
        if (failed.isNotEmpty()) {
            Text("Didn't go out", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.m))
            failed.forEach { d ->
                PostCard(d, c.card, d.optString("error"), d.optString("id") in busy, onOpen = { opened = d }) {
                    Pill("Try again", true, Modifier.weight(1f)) { act(d, "approve") }
                    Pill("Delete", false, Modifier.weight(1f)) { deleting = d }
                }
            }
        }
        Text(if (saved.isEmpty()) "No drafts" else "Drafts (${saved.size})", style = Type.title, color = c.ink,
            modifier = Modifier.padding(top = Space.m))
        saved.forEach { d ->
            PostCard(d, c.card, listOf(d.optString("note"), if (d.optLong("at") > 0) "for ${fmt(d.optLong("at"))}" else "")
                .filter { it.isNotBlank() }.joinToString(" · "), d.optString("id") in busy, onOpen = { opened = d }) {
                Pill("Post it", true, Modifier.weight(1f)) { act(d, "approve") }
                Pill("Edit", false, Modifier.weight(1f)) { opened = d }
            }
        }
        // a new idea goes to Nadia (through Mochi's LinkedIn chat)
        Text("New post idea", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.m))
        Field(idea, { idea = it; ideaSent = false }, "A topic, a PR, something you learned…", Modifier.fillMaxWidth()) {
            if (idea.isNotBlank()) Pill("Send", true) {
                val text = idea.trim()
                io(scope, { LinkedIn.ask(ctx, "LinkedIn post idea from December (from Nadia's page in the app): $text") },
                    { error = it.message ?: "not sent" }) { idea = ""; ideaSent = true }
            }
        }
        if (ideaSent) Text("Sent to Nadia. The draft shows up here when it's ready.", style = Type.small, color = c.muted)

        if (posted.isNotEmpty()) {
            Text("Posted lately", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.m))
            posted.take(10).forEach { d ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) {
                    d.optString("url").takeIf { it.isNotBlank() }?.let(::open)
                }.padding(vertical = Space.xs)) {
                    Text(firstLine(d), style = Type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmt(d.optLong("posted_at")) + if (d.optString("url").isNotBlank()) " · open on LinkedIn" else "",
                        style = Type.small, color = c.muted)
                }
            }
        }
    }
    opened?.let { d ->
        DraftEditor(d, status, onClose = { opened = null }, onChanged = onChanged, onDelete = { opened = null; deleting = d })
    }
    deleting?.let { d ->
        AlertDialog(onDismissRequest = { deleting = null }, containerColor = c.bg,
            title = { Text("Delete this draft?", style = Type.title, color = c.ink) },
            text = { Text(firstLine(d), style = Type.body, color = c.muted, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { deleting = null; act(d, "delete") }) { Text("Delete", color = c.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep", color = c.ink) } })
    }
}

@Composable
private fun PostCard(d: JSONObject, bg: Color, line: String, busy: Boolean, onOpen: () -> Unit,
                     buttons: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalMochi.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(bg)
        .clickable(role = Role.Button, onClickLabel = "Open the post", onClick = onOpen).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(d.optString("text"), style = Type.body, color = c.ink, maxLines = 5, overflow = TextOverflow.Ellipsis)
        Text(listOf(line, "${d.optInt("chars")} characters", when {
            d.optBoolean("has_video") -> "with video"
            d.optString("video_status") == "requested" -> "video coming"
            d.optBoolean("has_image") -> "with image"
            else -> "" })
            .filter { it.isNotBlank() }.joinToString(" · "), style = Type.small, color = c.muted, maxLines = 3,
            overflow = TextOverflow.Ellipsis)
        if (buttons != null) {
            if (busy) Text("…", style = Type.small, color = c.muted)
            else Row(horizontalArrangement = Arrangement.spacedBy(Space.s), content = buttons)
        }
    }
}

/**
 * One draft, editable: text (with the "see more" fold marked), time, image (replace from the gallery, remove,
 * description), then Save / Post it / Keep as draft / Delete. "Suggest changes" and "Make an image" ask Nadia.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraftEditor(d: JSONObject, status: JSONObject, onClose: () -> Unit, onChanged: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    val id = d.optString("id")
    val state = d.optString("status")
    var loaded by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf(JSONObject()) }
    var text by remember { mutableStateOf(d.optString("text")) }
    var at by remember { mutableLongStateOf(d.optLong("at")) }
    var alt by remember { mutableStateOf("") }
    var hasImage by remember { mutableStateOf(d.optBoolean("has_image")) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    var newImage by remember { mutableStateOf<String?>(null) }   // base64 JPEG picked from the gallery
    var removeImage by remember { mutableStateOf(false) }
    var hasVideo by remember { mutableStateOf(d.optBoolean("has_video")) }
    var removeVideo by remember { mutableStateOf(false) }
    var videoStatus by remember { mutableStateOf(d.optString("video_status")) }
    var withVoice by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(id) {
        io(scope, { LinkedIn.full(ctx, id) }) { f ->
            if (f.has("text")) {
                original = f; text = f.optString("text"); at = f.optLong("at"); alt = f.optString("alt")
                hasVideo = f.optBoolean("has_video"); videoStatus = f.optString("video_status")
            }
            loaded = true
        }
        if (d.optBoolean("has_image")) io(scope, { LinkedIn.image(LinkedIn.approveUrl(status, id)) }) { picture = it }
    }
    // a photo or a video from the phone's picker; a video is sent as it is (LinkedIn takes MP4)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && ctx.contentResolver.getType(uri)?.startsWith("video/") == true) {
            busy = true; message = "Sending the video…"
            io(scope, { LinkedIn.uploadVideo(ctx, id, uri) }, { busy = false; message = it.message ?: "Couldn't send that video" }) {
                busy = false; hasVideo = true; removeVideo = false; videoStatus = "ready"; message = "Video attached."; onChanged()
            }
        } else if (uri != null) io(scope, { LinkedIn.photo(ctx, uri) to runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull() },
            { message = "Couldn't read that photo" }) { (b64, bmp) ->
            if (b64 == null) message = "That isn't a photo the app can use" else {
                newImage = b64; picture = bmp; hasImage = true; removeImage = false
            }
        }
    }
    val changes = JSONObject().apply {
        if (loaded && text != original.optString("text")) put("text", text)
        if (loaded && at != original.optLong("at")) put("at", at)
        if (loaded && alt != original.optString("alt")) put("alt", alt)
        newImage?.let { put("image_b64", it) }
        if (removeImage) put("no_image", true)
        if (removeVideo) put("no_video", true)
    }
    val dirty = changes.length() > 0
    fun work(after: String, block: () -> Unit) {
        busy = true; message = ""
        io(scope, block, { busy = false; message = it.message ?: "didn't work" }) {
            busy = false; onChanged()
            if (after.isNotBlank()) message = after else onClose()
        }
    }
    fun save() = if (dirty) LinkedIn.edit(ctx, id, changes) else JSONObject()
    fun pickTime() {
        val cal = Calendar.getInstance().apply { if (at > 0) timeInMillis = at * 1000 else add(Calendar.HOUR_OF_DAY, 1) }
        DatePickerDialog(ctx, { _, y, m, day ->
            TimePickerDialog(ctx, { _, h, min ->
                val chosen = Calendar.getInstance().apply { set(y, m, day, h, min, 0) }.timeInMillis / 1000
                if (chosen < System.currentTimeMillis() / 1000 + 120) message = "Pick a time later than now" else { at = chosen; message = "" }
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    // the sheet stops below the status bar (time, battery), with a little room
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.statusBarsPadding().padding(top = Space.m), containerColor = c.bg, dragHandle = null) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()
            .padding(horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LinkedIn post", style = Type.display, color = c.ink)
                    Text(when (state) { "pending" -> "Waiting for you"; "scheduled" -> "Scheduled"; "failed" -> "Didn't go out"; else -> "Draft" } +
                        (d.optString("note").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = Type.small, color = c.muted)
                }
                RoundButton(Icons2.Close, "Close", c.field, c.ink, 40.dp, onClose)
            }

            // the text, with what shows before "…see more" in the accent colour
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).clip(RoundedCornerShape(16.dp)).background(c.field).padding(Space.m)) {
                BasicTextField(text, { if (it.length <= LinkedIn.MAX_TEXT + 200) text = it },
                    textStyle = Type.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth())
            }
            val over = text.length > LinkedIn.MAX_TEXT
            Text(buildAnnotatedString {
                append("Before \"…see more\": ")
                withStyle(SpanStyle(color = c.blueInk)) { append(text.take(LinkedIn.FOLD).replace('\n', ' ').trim()) }
                if (text.length > LinkedIn.FOLD) append("…")
            }, style = Type.small, color = c.muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text("${text.length} / ${LinkedIn.MAX_TEXT} characters", style = Type.small, color = if (over) c.danger else c.muted)

            // time
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(if (at > 0) "Goes out ${fmt(at)}" else "Goes out when you tap Post it", style = Type.body, color = c.ink,
                    modifier = Modifier.weight(1f))
                Pill(if (at > 0) "Change" else "Set a time", false) { pickTime() }
                if (at > 0) Pill("Clear", false) { at = 0 }
            }

            // image
            if (hasImage && !removeImage) {
                picture?.let { Image(it, contentDescription = alt.ifBlank { "The post's image" }, contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) }
                    ?: Text("Loading the image…", style = Type.small, color = c.muted)
                Field(alt, { alt = it }, "Describe the image (for people using screen readers)", Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Pill(if (hasImage && !removeImage) "Replace photo / video" else "Add photo / video", false, Modifier.weight(1f)) {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                if (hasImage && !removeImage) Pill("Remove image", false, Modifier.weight(1f)) {
                    removeImage = true; newImage = null; picture = null
                }
            }
            Text("Or ask Nadia for one:", style = Type.small, color = c.muted)
            // two a row, so each label fits its button
            listOf("Screenshot" to "a screenshot of the project it's about", "Quote card" to "a quote card from its strongest line",
                "Number card" to "a number card with its key number", "Chart" to "a simple chart of its numbers").chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    row.forEach { (label, what) ->
                        Pill(label, false, Modifier.weight(1f), height = 44.dp) {
                            work("Asked Nadia for $label. It shows up here when it's attached.") {
                                LinkedIn.ask(ctx, "December asks for an image on LinkedIn draft $id (from Nadia's page in the app): $what. " +
                                    "Attach it with mochi-linkedin edit $id --image <file> --alt \"...\"; keep the text as it is.")
                            }
                        }
                    }
                }
            }

            // video: play / remove, or ask for one (green when Nadia thinks the post suits video, red when not)
            Text("Video", style = Type.title, color = c.ink)
            if (hasVideo && !removeVideo) {
                Text("A video is attached. It's posted instead of the image.", style = Type.small, color = c.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Pill("Play video", true, Modifier.weight(1f)) { LinkedIn.play(ctx, status, id) }
                    Pill("Remove video", false, Modifier.weight(1f)) { removeVideo = true }
                }
            } else {
                val fit = original.optString("video_fit").ifBlank { d.optString("video_fit") }
                val why = original.optString("video_why").ifBlank { d.optString("video_why") }
                when (videoStatus) {
                    "requested" -> Text("Video requested: Nadia is making it. It shows up here when it's attached.",
                        style = Type.small, color = c.muted)
                    "failed" -> Text("The video couldn't be made: ${original.optString("video_error")}", style = Type.small, color = c.danger)
                }
                if (fit.isNotBlank()) Text((if (fit == "good") "Good for a video: " else "Not ideal for a video: ") + why,
                    style = Type.small, color = if (fit == "good") c.greenInk else c.danger)
                if (videoStatus != "requested") {
                    // voice-over is December's choice only; music Nadia adds when it helps
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Pill(if (withVoice) "✓ With voice-over" else "With voice-over", withVoice, height = 36.dp) { withVoice = !withVoice }
                        Text(if (withVoice) "Narrated, music under it" else "Silent or music only", style = Type.small, color = c.muted)
                    }
                    VideoButton(fit) {
                        val voice = withVoice
                        work("Asked Nadia for a video (about a minute to make once she starts).") {
                            save(); LinkedIn.requestVideo(ctx, id, voice)
                        }
                        videoStatus = "requested"
                    }
                }
            }

            // ask for changes
            Field(note, { note = it }, "Suggest changes (Nadia and Mark rework it)", Modifier.fillMaxWidth()) {
                if (note.isNotBlank()) Pill("Send", true) {
                    val n = note.trim()
                    work("Sent. The new version shows up here.") {
                        save()
                        LinkedIn.ask(ctx, "December's changes for LinkedIn draft $id (from Nadia's page in the app): $n")
                    }
                    note = ""
                }
            }

            if (message.isNotBlank()) Text(message, style = Type.small, color = c.ink)
            if (busy) Text("…", style = Type.small, color = c.muted)
            else {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Pill(if (at > 0) "Approve for ${fmt(at)}" else "Post it", true, Modifier.weight(1f)) {
                        if (over) message = "It's over ${LinkedIn.MAX_TEXT} characters" else work("") { save(); LinkedIn.act(ctx, id, "approve") }
                    }
                    if (dirty) Pill("Save", false, Modifier.weight(1f)) { if (over) message = "It's over ${LinkedIn.MAX_TEXT} characters" else work("") { save() } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (state == "pending" || state == "scheduled")
                        Pill("Keep as draft", false, Modifier.weight(1f)) { work("") { save(); LinkedIn.act(ctx, id, "decline") } }
                    Pill("Delete", false, Modifier.weight(1f), onClick = onDelete)
                }
            }
        }
    }
}

/** Request video: green when Nadia rated the post good for video, red when she didn't, neutral when she hasn't said. */
@Composable
private fun VideoButton(fit: String, onClick: () -> Unit) {
    val c = LocalMochi.current
    val (bg, ink) = when (fit) {
        "good" -> c.greenInk to Color.White
        "poor" -> c.danger to Color.White
        else -> c.field to c.ink
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(bg)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.l, vertical = Space.m),
        contentAlignment = Alignment.Center) {
        Text(if (fit == "poor") "Request video anyway" else "Request video", style = Type.body.copy(fontWeight = Type.strong), color = ink)
    }
}
