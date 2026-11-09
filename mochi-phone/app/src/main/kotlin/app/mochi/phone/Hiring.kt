package app.mochi.phone

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.AgentLook
import app.mochi.phone.ui.Field
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Mascot
import app.mochi.phone.ui.Mood
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import org.json.JSONObject

/**
 * SY's hiring board (her page on the Agents screen): proposals waiting for December (hire, change, retire) with
 * Approve, Reject and Remarks, each opening in full on a tap; then what was decided lately. December's taps go to
 * mochi-ring, and mochi-hire (no AI) installs, archives or sends the remarks to SY.
 */
@Composable
internal fun HiringBoard(waiting: List<JSONObject>, history: List<JSONObject>, onChanged: () -> Unit) {
    val c = LocalMochi.current
    var opened by remember { mutableStateOf<JSONObject?>(null) }
    var asking by remember { mutableStateOf<Pair<JSONObject, String>?>(null) } // proposal, "approve" | "reject"
    var sent by remember { mutableStateOf(setOf<String>()) }                   // decided here, waiting for the server
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(if (waiting.isEmpty()) "Nothing waiting for you" else "Waiting for you (${waiting.size})",
            style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.s))
        if (waiting.isEmpty()) Text("When SY proposes hiring, changing or retiring an agent, it shows up here and you " +
            "get an email.", style = Type.small, color = c.muted)
        waiting.forEach { p ->
            ProposalCard(p, p.optString("id") in sent, onOpen = { opened = p },
                onApprove = { asking = p to "approve" }, onReject = { asking = p to "reject" }, onRemarks = { opened = p })
        }
        if (history.isNotEmpty()) {
            Text("Decided lately", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.m))
            history.take(12).forEach { h -> HistoryRow(h) }
        }
    }
    opened?.let { p ->
        ProposalSheet(p, p.optString("id") in sent, onClose = { opened = null }, onApprove = { asking = p to "approve" },
            onReject = { asking = p to "reject" }, onRemarksSent = { sent = sent + p.optString("id"); opened = null; onChanged() })
    }
    asking?.let { (p, action) ->
        DecisionDialog(p, action, onDone = { ok ->
            asking = null
            if (ok) { sent = sent + p.optString("id"); opened = null; onChanged() }
        })
    }
}

private fun kindLabel(kind: String) = when (kind) { "appraisal" -> "Change"; "retire" -> "Retire"; else -> "Hire" }

private fun lookOf(p: JSONObject): AgentLook = AgentLook(
    runCatching { Color(android.graphics.Color.parseColor(p.optString("color").ifBlank { "#7A8CA5" })) }
        .getOrDefault(Color(0xFF7A8CA5)), p.optString("accessory"))

private fun nameOf(p: JSONObject) = p.optString("display").ifBlank { p.optString("agent").replaceFirstChar { it.uppercase() } }

@Composable
private fun Chip(text: String, bg: Color, ink: Color) {
    Text(text, style = Type.small, color = ink, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(10.dp))
        .background(bg).padding(horizontal = Space.s, vertical = 2.dp))
}

@Composable
private fun ProposalCard(p: JSONObject, sent: Boolean, onOpen: () -> Unit, onApprove: () -> Unit, onReject: () -> Unit,
                         onRemarks: () -> Unit) {
    val c = LocalMochi.current
    val kind = p.optString("kind", "hire")
    val revising = p.optString("status") == "revising"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card)
        .clickable(role = Role.Button, onClickLabel = "Read the whole proposal", onClick = onOpen).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Mascot(Modifier.size(56.dp), mood = if (kind == "retire") Mood.Sleepy else Mood.Happy, look = lookOf(p),
                label = nameOf(p))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Chip(kindLabel(kind), when (kind) { "retire" -> c.field; "appraisal" -> c.blueBg; else -> c.greenBg },
                        when (kind) { "retire" -> c.muted; "appraisal" -> c.blueInk; else -> c.greenInk })
                    Text(nameOf(p), style = Type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (p.optString("role").isNotBlank()) Text(p.optString("role"), style = Type.small, color = lookOf(p).color)
            }
        }
        Text(p.optString("why"), style = Type.body, color = c.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
        val chips = buildList {
            p.optString("model").takeIf { it.isNotBlank() }?.let { add(it.replaceFirstChar { ch -> ch.uppercase() }) }
            p.optJSONArray("skills")?.let { s -> for (i in 0 until s.length()) s.optJSONObject(i)?.let { k ->
                add(k.optString("name") + if (k.optString("melissa") == "safe") " ✓" else "")
            } }
            if (p.optBoolean("readonly")) add("Read-only")
            p.optLong("est_tokens_per_run").takeIf { it > 0 }?.let { add("~${Doing.fmt(it)} tokens/run") }
        }
        if (chips.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            items(chips) { Chip(it, c.field, c.ink) }
        }
        when {
            sent -> Text("Sent. Updating…", style = Type.small, color = c.muted)
            revising -> Text(p.optString("error").takeIf { it.isNotBlank() }?.let { "SY is fixing it: $it" }
                ?: "SY is revising it with your remarks…", style = Type.small, color = c.amberInk)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Pill("Approve", true, Modifier.weight(1f), height = 40.dp, onClick = onApprove)
                Pill("Remarks", false, Modifier.weight(1f), height = 40.dp, onClick = onRemarks)
                Pill("Reject", false, Modifier.weight(1f), height = 40.dp, onClick = onReject)
            }
        }
    }
}

@Composable
private fun HistoryRow(h: JSONObject) {
    val c = LocalMochi.current
    val status = h.optString("status")
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Chip(status.replaceFirstChar { it.uppercase() }, if (status == "rejected") c.field else c.greenBg,
            if (status == "rejected") c.muted else c.greenInk)
        Text("${kindLabel(h.optString("kind"))} ${h.optString("display")}" +
            (h.optString("note").takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""), style = Type.small, color = c.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(DateUtils.getRelativeTimeSpanString(h.optLong("at") * 1000).toString(), style = Type.small, color = c.muted)
    }
}

/** The whole proposal: who, why, the evidence, its instructions and skills; Remarks to SY; Approve or Reject. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProposalSheet(p: JSONObject, sent: Boolean, onClose: () -> Unit, onApprove: () -> Unit, onReject: () -> Unit,
                          onRemarksSent: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var remarks by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val kind = p.optString("kind", "hire")
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = c.bg, dragHandle = null) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()
            .padding(horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Mascot(Modifier.size(64.dp), mood = Mood.Happy, look = lookOf(p), label = nameOf(p))
                Column(Modifier.weight(1f)) {
                    Text("${kindLabel(kind)}: ${nameOf(p)}", style = Type.display, color = c.ink)
                    if (p.optString("role").isNotBlank()) Text(p.optString("role"), style = Type.body, color = lookOf(p).color)
                    Text("Revision ${p.optInt("revision", 1)}", style = Type.small, color = c.muted)
                }
                RoundButton(Icons2.Close, "Close", c.field, c.ink, 40.dp, onClose)
            }
            Section("Why") { Text(p.optString("why"), style = Type.body, color = c.ink) }
            p.optJSONArray("evidence")?.takeIf { it.length() > 0 }?.let { ev ->
                Section("Evidence") {
                    for (i in 0 until ev.length()) Text("· " + ev.optString(i), style = Type.small, color = c.ink)
                }
            }
            if (p.optString("description").isNotBlank()) Section("What it does") {
                Text(p.optString("description"), style = Type.body, color = c.ink)
            }
            if (kind != "retire") Section("Setup") {
                val tools = p.optJSONArray("tools")?.let { t -> (0 until t.length()).joinToString(", ") { t.optString(it) } }.orEmpty()
                Text(listOfNotNull(p.optString("model").takeIf { it.isNotBlank() }?.let { "Model: $it" },
                    tools.takeIf { it.isNotBlank() }?.let { "Tools: $it" },
                    if (p.optBoolean("readonly")) "Read-only (writes only its notes)" else null,
                    p.optInt("max_turns").takeIf { it > 0 }?.let { "Up to $it steps a job" },
                    p.optLong("est_tokens_per_run").takeIf { it > 0 }?.let { "About ${Doing.fmt(it)} tokens a job" })
                    .joinToString("\n"), style = Type.small, color = c.ink)
            }
            p.optJSONArray("skills")?.takeIf { it.length() > 0 }?.let { sk ->
                Section("Skills") {
                    for (i in 0 until sk.length()) sk.optJSONObject(i)?.let { k ->
                        Text(k.optString("name") + (k.optString("source").takeIf { it.isNotBlank() }?.let { " · $it" } ?: " · installed") +
                            (k.optString("melissa").takeIf { it.isNotBlank() }?.let { " · Melissa: $it" } ?: ""),
                            style = Type.small, color = c.ink)
                    }
                }
            }
            if (p.optString("prompt").isNotBlank()) Section("Its instructions") {
                Text(p.optString("prompt"), style = Type.small.copy(fontFamily = FontFamily.Monospace), color = c.ink,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.codeBg).padding(Space.s))
            }
            p.optJSONArray("remarks")?.takeIf { it.length() > 0 }?.let { rm ->
                Section("Your remarks so far") {
                    for (i in 0 until rm.length()) Text("· " + (rm.optJSONObject(i)?.optString("text") ?: ""), style = Type.small, color = c.ink)
                }
            }
            if (!sent && p.optString("status") == "waiting") {
                Section("Remarks for SY") {
                    Field(remarks, { remarks = it }, "e.g. also make it handle Nepali")
                    if (error.isNotBlank()) Text(error, style = Type.small, color = c.danger)
                    Pill(if (sending) "Sending…" else "Send remarks to SY", false, Modifier.fillMaxWidth(), height = 40.dp) {
                        if (remarks.isBlank() || sending) return@Pill
                        sending = true
                        io(scope, { Agents.decide(ctx, p.optString("id"), "remarks", remarks.trim()) },
                            { sending = false; error = "Not sent: ${it.message}" }) { sending = false; onRemarksSent() }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Pill("Approve", true, Modifier.weight(1f), onClick = onApprove)
                    Pill("Reject", false, Modifier.weight(1f), onClick = onReject)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = LocalMochi.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(title, style = Type.small.copy(fontWeight = Type.strong), color = c.muted)
        content()
    }
}

/** "Approve hiring Nova?" / "Reject…? (optional reason for SY)". */
@Composable
private fun DecisionDialog(p: JSONObject, action: String, onDone: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val verb = when (p.optString("kind")) { "appraisal" -> "the change to"; "retire" -> "retiring"; else -> "hiring" }
    AlertDialog(onDismissRequest = { if (!sending) onDone(false) },
        title = { Text(if (action == "approve") "Approve $verb ${nameOf(p)}?" else "Reject $verb ${nameOf(p)}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(if (action == "approve") when (p.optString("kind")) {
                        "retire" -> "${nameOf(p)} leaves the team; their notes are kept, so they can come back."
                        "appraisal" -> "${nameOf(p)}'s new version goes live at once."
                        else -> "${nameOf(p)} joins Mochi's team at once, with the skills and rules shown."
                    } else "SY notes your reason so she doesn't propose it again.", style = Type.body, color = c.ink)
                if (action == "reject") Field(reason, { reason = it }, "Reason (optional)")
                if (error.isNotBlank()) Text(error, style = Type.small, color = c.danger)
            }
        },
        confirmButton = {
            TextButton(enabled = !sending, onClick = {
                sending = true
                io(scope, { Agents.decide(ctx, p.optString("id"), action, reason.trim()) },
                    { sending = false; error = "Not sent: ${it.message}" }) { sending = false; onDone(true) }
            }) { Text(if (sending) "Sending…" else if (action == "approve") "Approve" else "Reject") }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = { onDone(false) }) { Text("Cancel") } })
}
