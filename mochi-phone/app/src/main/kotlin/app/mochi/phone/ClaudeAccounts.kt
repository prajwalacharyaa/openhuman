package app.mochi.phone

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.Field
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import org.json.JSONArray
import org.json.JSONObject

/** One account in the list: saved ones have an [id] (and show only the last 4 characters); new ones carry the pasted token. */
private class AccountRow(val id: String?, val ref: String, label: String, val tail: String) {
    var label by mutableStateOf(label)
    var token by mutableStateOf("")
}

/**
 * Settings > Claude accounts: as many accounts as wanted (each a token from `claude setup-token`), tick the one Mochi
 * should use and Save (fingerprint). The server tests a new token first, switches only if it works, and undoes the switch
 * if Mochi doesn't come back. Tokens live in the server's vault; this screen never gets one back.
 */
@Composable
internal fun ClaudeAccounts() {
    val ctx = LocalContext.current
    val a = ctx as? Activity ?: return
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<AccountRow>() }
    var inUse by remember { mutableStateOf("") }      // the id Mochi uses right now (from the server)
    var picked by remember { mutableStateOf("") }     // the ref of the ticked row
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var next by remember { mutableIntStateOf(1) }

    fun load() {
        io(scope, { VaultUnlock.claudeAccounts(ctx) }, { note = it.message ?: "Can't reach the server."; loaded = true }) { d ->
            rows.clear()
            val list = d.optJSONArray("accounts") ?: JSONArray()
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { rows.add(AccountRow(it.optString("id"), it.optString("id"), it.optString("label"), it.optString("tail"))) }
            inUse = d.optString("active"); picked = inUse; loaded = true
        }
    }
    LaunchedEffect(Unit) { load() }

    Text("Mochi's brain runs on one Claude account. Add the accounts you have (a token from `claude setup-token`, run on a " +
        "computer signed in to that account), tick the one to use and Save. Switching restarts Mochi for about 10 seconds: " +
        "an answer or call in progress is cut. The new token is tested first; if it doesn't work, nothing changes.",
        style = Type.small, color = c.muted)
    if (!loaded) Text("Loading…", style = Type.small, color = c.muted)
    rows.toList().forEach { r ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.field).padding(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Checkbox(checked = picked == r.ref, onCheckedChange = { picked = r.ref },
                    colors = CheckboxDefaults.colors(checkedColor = c.ink, checkmarkColor = c.bg),
                    modifier = Modifier.semantics { contentDescription = "Use ${r.label.ifBlank { "this account" }}" })
                Box(Modifier.weight(1f)) { Field(r.label, { r.label = it.take(40) }, "Name (e.g. Personal, Work)", singleLine = true) }
                RoundButton(Icons2.Close, "Remove ${r.label.ifBlank { "this account" }}", c.card, c.ink, 36.dp) {
                    rows.remove(r)
                    if (picked == r.ref) picked = rows.firstOrNull()?.ref ?: ""
                }
            }
            if (r.id == null) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(Space.l)) {
                    if (r.token.isEmpty()) Text("Paste the token", style = Type.body, color = c.muted)
                    androidx.compose.foundation.text.BasicTextField(r.token, { r.token = it.trim() }, singleLine = true,
                        textStyle = Type.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Claude token" })
                }
            } else Text((if (r.id == inUse) "In use now · " else "") + "token ••••${r.tail}", style = Type.small,
                color = if (r.id == inUse) c.greenInk else c.muted, modifier = Modifier.padding(start = Space.m))
        }
    }
    Pill("Add an account", false, Modifier.fillMaxWidth()) {
        val ref = "n${next++}"
        rows.add(AccountRow(null, ref, "", ""))
        if (picked.isBlank()) picked = ref
    }
    Pill(if (busy) "Testing and switching… up to a minute" else "Save (fingerprint)", !busy, Modifier.fillMaxWidth()) {
        if (busy) return@Pill
        val problem = when {
            rows.isEmpty() -> "Add at least one account."
            rows.none { it.ref == picked } -> "Tick the account to use."
            rows.any { it.label.isBlank() } -> "Every account needs a name."
            rows.any { it.id == null && it.token.isBlank() } -> "Paste the token of each new account."
            else -> null
        }
        if (problem != null) { note = problem; return@Pill }
        val body = JSONObject().put("active", picked).put("accounts", JSONArray(rows.map { r ->
            JSONObject().put("ref", r.ref).put("label", r.label.trim()).apply {
                r.id?.let { put("id", it) }
                if (r.token.isNotBlank()) put("token", r.token.trim())
            }
        }))
        busy = true; note = ""
        VaultUnlock.send(a, "Save the Claude accounts", "/claude/accounts", body) { err ->
            busy = false
            if (err == null) { note = "Saved."; load() } else note = err   // on a failure the rows stay as typed, to fix and retry
        }
    }
    if (note.isNotEmpty()) Text(note, style = Type.small, color = if (note == "Saved.") c.greenInk else c.ink)
}
