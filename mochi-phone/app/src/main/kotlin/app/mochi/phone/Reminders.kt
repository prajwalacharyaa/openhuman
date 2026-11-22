package app.mochi.phone

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.Field
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.Label
import app.mochi.phone.ui.LineIcon
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Mascot
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.Refreshable
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.WhileVisible
import app.mochi.phone.ui.io
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Mochi's reminders (made on a call, in a chat with mochi-remind, or here): the scheduler's "reminder:" jobs.
 * The text rides in the job's name ("reminder:<id> <text>"), so this list needs nothing but the engine.
 * One made here carries its text in the job's command, base64url (mochi-notify --reminder <id> <text> [call]):
 * no quote in it can reach the shell. No AI runs when it fires.
 */
object Reminders {
    private const val PREFIX = "reminder:"

    data class Item(val jobId: String, val text: String, val next: Instant?, val repeat: String, val call: Boolean)

    fun list(c: Context): List<Item> {
        val r = Mochi.rpc(c, "openhuman.cron_list", JSONObject())
        val jobs = r.optJSONArray("result") ?: (r.opt("result") as? JSONObject)?.optJSONArray("result") ?: JSONArray()
        return (0 until jobs.length()).mapNotNull { jobs.optJSONObject(it) }
            .filter { it.optString("name").startsWith(PREFIX) && it.optBoolean("enabled", true) }
            .map { j ->
                val name = j.optString("name")
                Item(j.optString("id"), name.substringAfter(" ", "").ifBlank { "Reminder" },
                    j.optString("next_run").takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    j.optString("expression").takeIf { it.isNotBlank() && it != "null" } ?: "",
                    j.optString("command").endsWith(" call"))
            }
            .sortedBy { it.next ?: Instant.MAX }
    }

    fun cancel(c: Context, jobId: String) {
        Mochi.rpc(c, "openhuman.cron_remove", JSONObject().put("job_id", jobId))
    }

    /** One at [at], or every day at that time when [daily]; [call] rings the phone instead of a notification. */
    fun add(c: Context, text: String, at: ZonedDateTime, daily: Boolean, call: Boolean) {
        val clean = text.trim().replace(Regex("\\s+"), " ").take(300)
        require(clean.isNotEmpty()) { "Write what to remind you of" }
        require(daily || at.isAfter(ZonedDateTime.now())) { "That time has passed" }
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val b64 = Base64.encodeToString(clean.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val schedule = if (daily) JSONObject().put("kind", "cron").put("expr", "${at.minute} ${at.hour} * * *")
            .put("tz", ZoneId.systemDefault().id)
        else JSONObject().put("kind", "at").put("at", at.toInstant().truncatedTo(ChronoUnit.SECONDS).toString())
        Mochi.rpc(c, "openhuman.cron_add", JSONObject().put("name", "$PREFIX$id $clean").put("schedule", schedule)
            .put("job_type", "shell").put("command", "mochi-notify --reminder $id $b64" + if (call) " call" else "")
            .put("delete_after_run", !daily))
    }

    fun whenText(i: Item): String {
        val next = i.next?.atZone(ZoneId.systemDefault()) ?: return if (i.repeat.isNotEmpty()) "repeating" else "soon"
        val day = when (next.toLocalDate()) {
            LocalDate.now() -> "Today"
            LocalDate.now().plusDays(1) -> "Tomorrow"
            else -> next.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        }
        val repeat = when {
            i.repeat.isEmpty() -> ""
            Regex("""\d+ \d+ \* \* \*""").matches(i.repeat) -> " · every day"
            else -> " · repeats"
        }
        return "$day ${next.format(DateTimeFormatter.ofPattern("h:mm a"))}$repeat"
    }
}

@Composable
internal fun RemindersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<Reminders.Item>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    fun load() = io(scope, { Reminders.list(ctx) }, { loading = false; error = Room.friendly(it) }) {
        loading = false; error = ""; items.clear(); items.addAll(it)
    }
    // fired one-offs drop off by themselves: refresh every minute while the screen is on view
    WhileVisible { while (true) { load(); delay(60_000) } }

    Screen("Reminders", onBack, action = {
        RoundButton(if (adding) Icons2.Close else Icons2.Plus, if (adding) "Close" else "New reminder", c.ink, c.bg, 44.dp) {
            adding = !adding
        }
    }) {
        Refreshable({ load() }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (adding) item { NewReminder { text, at, daily, call ->
                io(scope, { Reminders.add(ctx, text, at, daily, call) }, { error = Room.friendly(it) }) { adding = false; load() }
            } }
            if (error.isNotEmpty()) item {
                Text(error, style = Type.small, color = c.danger, modifier = Modifier.padding(vertical = Space.s))
            }
            if (!loading && items.isEmpty() && !adding) item {
                Column(Modifier.fillMaxWidth().padding(top = Space.xxl), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Mascot(Modifier.size(80.dp))
                    Text("No reminders", style = Type.title, color = c.ink)
                    Text("Tap + or tell Mochi \"remind me…\" on a call.", style = Type.small, color = c.muted)
                }
            }
            items(items, key = { it.jobId }) { r ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(Space.l),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    LineIcon(if (r.call) Icons2.Phone else Icons2.Bell, c.ink, 22.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(r.text, style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                        Text(Reminders.whenText(r) + if (r.call) " · rings you" else "", style = Type.small, color = c.muted)
                    }
                    RoundButton(Icons2.Trash, "Cancel reminder: ${r.text}", c.field, c.ink, 44.dp) {
                        items.remove(r)
                        io(scope, { Reminders.cancel(ctx, r.jobId) }, { error = "Couldn't cancel: ${Room.friendly(it)}"; load() }) {}
                    }
                }
            }
            item { Spacer(Modifier.height(Space.xl)) }
        }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NewReminder(save: (String, ZonedDateTime, Boolean, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    var text by remember { mutableStateOf("") }
    var at by remember { mutableStateOf(ZonedDateTime.now().plusHours(1).truncatedTo(ChronoUnit.MINUTES)) }
    var daily by remember { mutableStateOf(false) }
    var call by remember { mutableStateOf(false) }
    val now = ZonedDateTime.now().truncatedTo(ChronoUnit.MINUTES)
    fun today(h: Int) = now.with(LocalTime.of(h, 0)).let { if (it.isAfter(now)) it else it.plusDays(1) }
    val quick = listOf("In 10 min" to now.plusMinutes(10), "In 1 hour" to now.plusHours(1),
        "8 PM" to today(20), "Tomorrow 9 AM" to now.plusDays(1).with(LocalTime.of(9, 0)))

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Field(text, { text = it }, "Remind me to…", Modifier.fillMaxWidth())
        Label("When")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            for ((label, t) in quick) Pill(label, at == t, height = 40.dp, tab = true) { at = t }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Pill(at.format(DateTimeFormatter.ofPattern("EEE d MMM")), false, height = 40.dp) {
                DatePickerDialog(ctx, { _, y, m, d -> at = at.withYear(y).withMonth(m + 1).withDayOfMonth(d) },
                    at.year, at.monthValue - 1, at.dayOfMonth).show()
            }
            Pill(at.format(DateTimeFormatter.ofPattern("h:mm a")), false, height = 40.dp) {
                TimePickerDialog(ctx, { _, h, m -> at = at.withHour(h).withMinute(m) }, at.hour, at.minute, false).show()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Pill(if (daily) "✓ Every day" else "Every day", daily, height = 40.dp) { daily = !daily }
            Pill(if (call) "✓ Ring me" else "Ring me", call, height = 40.dp) { call = !call }
        }
        Pill("Save reminder", true, Modifier.fillMaxWidth().padding(top = Space.s)) { save(text, at, daily, call) }
        Text(if (call) "Mochi calls you and says it." else "A notification on this phone.", style = Type.small, color = c.muted,
            modifier = Modifier.clickable(role = Role.Button) { call = !call })
    }
}
