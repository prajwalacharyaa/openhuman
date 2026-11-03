package app.mochi.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What Mochi can ask of the phone (mochi-ring "phone" events). Reading answers right away with only what was
 * asked; placing a call or sending an SMS waits for December to tap Confirm on the phone ([ConfirmActivity]).
 * Every answer is {"ok": true, "result": …} or {"ok": false, "error": "…"}. Runs on a background thread.
 */
object PhoneActions {
    private const val CONFIRM_WAIT_S = 50L

    fun handle(c: Context, action: String, args: JSONObject): JSONObject = try {
        when (action) {
            "contacts.search" -> ok(namesOnly(contacts(c, args.optString("query"), 5)))
            "calls.recent" -> ok(calls(c, args.optInt("limit", 5)))
            "sms.recent" -> ok(sms(c, args.optInt("limit", 3), args.optString("from")))
            "notifications.recent" -> ok(MochiNotifications.recent(args.optInt("limit", 5), args.optString("app")))
            "call.place" -> placeCall(c, args)
            "sms.send" -> confirmAndDo(c, "sms", args.optString("to"), args.optString("text"))
            "open" -> open(c, args.optString("url"))
            else -> fail("unknown action $action")
        }
    } catch (e: SecurityException) {
        fail("permission is off on the phone (turn it on under Phone abilities in the Mochi app)")
    } catch (e: Exception) {
        fail(hideNumbers(e.message ?: e.javaClass.simpleName))
    }

    private fun ok(result: Any) = JSONObject().put("ok", true).put("result", result)

    /** Phone numbers never leave the phone (December's privacy rule): numbers are only used here, on the phone. */
    private val NUMBER = Regex("""\+?\d[\d\s().-]{5,}\d""")
    fun hideNumbers(text: String) = NUMBER.replace(text) { if (it.value.count(Char::isDigit) >= 7) "[number hidden]" else it.value }

    /** Contacts as Mochi sees them: names, and each number's kind ("mobile", "home"), never the digits. */
    private fun namesOnly(found: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until found.length()) {
            val contact = found.getJSONObject(i)
            val numbers = contact.getJSONArray("numbers")
            out.put(JSONObject().put("name", contact.getString("name"))
                .put("numbers", JSONArray((0 until numbers.length()).map { numbers.getJSONObject(it).getString("label") })))
        }
        return out
    }
    private fun fail(error: String) = JSONObject().put("ok", false).put("error", error)

    private fun need(c: Context, permission: String, what: String) {
        if (c.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("$what permission is off")
        }
    }

    /** Contacts whose name contains `query`, with their numbers (for use on the phone only; see [namesOnly]). */
    fun contacts(c: Context, query: String, limit: Int): JSONArray {
        need(c, Manifest.permission.READ_CONTACTS, "Contacts")
        val out = JSONArray()
        if (query.isBlank()) return out
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
        // stable order, so "mobile 2" means the same number next time
        c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC, ${ContactsContract.CommonDataKinds.Phone._ID} ASC")?.use { cur ->
            // contact id -> (name, number -> label); two people with the same name stay apart
            val byContact = LinkedHashMap<Long, Pair<String, LinkedHashMap<String, String>>>()
            while (cur.moveToNext() && byContact.size <= limit) {
                // a contact saved without a name has its number as its name: never send that
                val name = cur.getString(0)?.let(::hideNumbers)?.takeIf { "[number hidden]" !in it } ?: continue
                val number = cur.getString(1)?.filter { it.isDigit() || it == '+' }?.takeIf { it.isNotEmpty() } ?: continue
                val label = hideNumbers(ContactsContract.CommonDataKinds.Phone.getTypeLabel(c.resources, cur.getInt(2),
                    cur.getString(3)).toString().lowercase())
                byContact.getOrPut(cur.getLong(4)) { name to LinkedHashMap() }.second.putIfAbsent(number, label)
            }
            byContact.values.take(limit).forEach { (name, numbers) ->
                val list = JSONArray()
                val seen = HashMap<String, Int>()
                numbers.forEach { (number, label) ->
                    // two "mobile" numbers become "mobile 1" and "mobile 2", so December can pick by voice
                    val n = (seen[label] ?: 0) + 1
                    seen[label] = n
                    val shown = if (numbers.values.count { it == label } > 1) "$label $n" else label
                    list.put(JSONObject().put("number", number).put("label", shown))
                }
                out.put(JSONObject().put("name", name).put("numbers", list))
            }
        }
        return out
    }

    /** The last calls: who, which way, when, how long. */
    private fun calls(c: Context, limit: Int): JSONArray {
        need(c, Manifest.permission.READ_CALL_LOG, "Call log")
        val out = JSONArray()
        val cols = arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE,
            CallLog.Calls.DURATION)
        c.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, null, null, "${CallLog.Calls.DATE} DESC")?.use { cur ->
            while (cur.moveToNext() && out.length() < limit.coerceIn(1, 20)) {
                val type = when (cur.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "received"
                    CallLog.Calls.OUTGOING_TYPE -> "made"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.REJECTED_TYPE -> "declined"
                    else -> "other"
                }
                val name = cur.getString(0)?.takeIf { it.isNotBlank() }?.let(::hideNumbers)
                    ?: nameFor(c, cur.getString(1) ?: "") ?: "unknown number"
                out.put(JSONObject().put("name", name).put("type", type).put("time", Instant.ofEpochMilli(cur.getLong(3)).toString())
                    .put("seconds", cur.getLong(4)))
            }
        }
        return out
    }

    /** The last text messages received, optionally from one contact (name or number). */
    private fun sms(c: Context, limit: Int, from: String): JSONArray {
        need(c, Manifest.permission.READ_SMS, "SMS")
        val numbers = if (from.isBlank()) emptySet() else
            (runCatching { contacts(c, from, 3) }.getOrNull()?.let { list ->
                (0 until list.length()).flatMap { i ->
                    val n = list.getJSONObject(i).getJSONArray("numbers"); (0 until n.length()).map { n.getJSONObject(it).getString("number") } }
            } ?: emptyList()).map { it.takeLast(9) }.toSet() + from.filter(Char::isDigit).takeLast(9)
        val out = JSONArray()
        val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        c.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, cols, null, null, "${Telephony.Sms.DATE} DESC")?.use { cur ->
            while (cur.moveToNext() && out.length() < limit.coerceIn(1, 10)) {
                val address = cur.getString(0) ?: ""
                if (numbers.isNotEmpty() && numbers.none { it.isNotEmpty() && address.filter(Char::isDigit).endsWith(it) }) continue
                // the sender's name, or what the sender calls itself (a bank, "NTC"), never a phone number
                val sender = nameFor(c, address) ?: if (address.count(Char::isDigit) >= 7) "unknown number" else address
                out.put(JSONObject().put("from", sender)
                    .put("text", hideNumbers((cur.getString(1) ?: "").take(500)))
                    .put("time", Instant.ofEpochMilli(cur.getLong(2)).toString()))
            }
        }
        return out
    }

    private fun nameFor(c: Context, number: String): String? = runCatching {
        val uri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
        c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.let(::hideNumbers)?.takeIf { n -> "[number hidden]" !in n } else null
        }
    }.getOrNull()

    /**
     * Open a link on the phone (directions in Google Maps, a page, a video). During a Mochi call the app is on
     * screen and may open it straight away; otherwise a notification December taps (Android doesn't let a
     * background app open screens by itself).
     */
    private fun open(c: Context, url: String): JSONObject {
        if (!url.startsWith("https://")) return fail("only https links")
        val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Call.active && runCatching { c.startActivity(view) }.isSuccess) return ok("opened on the phone")
        val tap = PendingIntent.getActivity(c, url.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val maps = url.startsWith("https://www.google.com/maps")
        val n = Notification.Builder(c, Mochi.CHANNEL_NOTICES)
            .setSmallIcon(R.drawable.ic_mochi)
            .setContentTitle(if (maps) "Directions from Mochi: tap to open Maps" else "Link from Mochi: tap to open")
            .setContentText(android.net.Uri.parse(url).getQueryParameter("destination") ?: url)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(url.hashCode(), n)
        return ok("sent to the phone as a notification: December taps it to open")
    }

    // ---- calls by voice: pick the contact, number and SIM by asking, remember the choice ----

    private class Sim(val slot: Int, val name: String, val handle: android.telecom.PhoneAccountHandle?)

    /** The phone's SIMs (slot and carrier name, never the number); empty without the phone-state permission. */
    private fun sims(c: Context): List<Sim> {
        if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return runCatching {
            val subs = c.getSystemService(android.telephony.SubscriptionManager::class.java).activeSubscriptionInfoList
                ?: return emptyList()
            val telecom = c.getSystemService(android.telecom.TelecomManager::class.java)
            val tm = c.getSystemService(android.telephony.TelephonyManager::class.java)
            val handles = telecom.callCapablePhoneAccounts
            val sorted = subs.sortedBy { it.simSlotIndex }
            // phone makers map a SIM to its call account differently: Android's own lookup first, then the account id
            // (often the subscription id), then its name (NTC, Ncell), then, when there's one account per SIM, the order
            val simAccounts = handles.filter { h ->
                telecom.getPhoneAccount(h)?.hasCapabilities(android.telecom.PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true
            }
            sorted.mapIndexed { i, sub ->
                val handle = handles.firstOrNull { h -> runCatching { tm.getSubscriptionId(h) == sub.subscriptionId }.getOrDefault(false) }
                    ?: handles.firstOrNull { it.id == sub.subscriptionId.toString() }
                    ?: simAccounts.filter { h ->
                        val label = telecom.getPhoneAccount(h)?.label?.toString().orEmpty()
                        label.isNotEmpty() && (label == sub.displayName?.toString() || label == sub.carrierName?.toString())
                    }.singleOrNull()
                    ?: simAccounts.takeIf { it.size == sorted.size }?.get(i)
                Sim(sub.simSlotIndex + 1, (sub.carrierName ?: sub.displayName ?: "SIM").toString(), handle)
            }
        }.getOrDefault(emptyList())
    }

    /** The call account for SIM `slot` (1-based), for the Confirm screen; null = the phone's default. */
    fun simHandle(c: Context, slot: Int) = if (slot > 0) sims(c).firstOrNull { it.slot == slot }?.handle else null

    private fun prefs(c: Context) = c.getSharedPreferences("calls", Context.MODE_PRIVATE)

    /**
     * "Call Mom": one contact, one number and one SIM, from what December said, what they chose last time for the
     * same words (kept on the phone), or by asking ({"status": "choose", …}). During a Mochi call on this phone it
     * calls straight away (December said it); otherwise the Confirm screen asks for a tap.
     */
    private fun placeCall(c: Context, args: JSONObject): JSONObject {
        val said = args.optString("to").trim()
        if (said.isEmpty()) return fail("who to call?")
        if (said.count(Char::isDigit) >= 6) return confirmAndDo(c, "call", said, "") // a dictated number: tap to confirm
        val key = said.lowercase()
        val last = prefs(c).getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val found = contacts(c, said, 50) // wide: the exact name mustn't be cut off by "Abhiram", "Bikram"…
        if (found.length() == 0) return fail("no contact matches \"$said\"")

        val all = (0 until found.length()).map { found.getJSONObject(it) }
        val exact = all.filter { it.getString("name").equals(said, ignoreCase = true) }
        val people = exact.ifEmpty { all.take(6) }
        val person = exact.singleOrNull() ?: people.singleOrNull()
            ?: people.firstOrNull { it.getString("name") == last?.optString("name") }
            ?: return choose("Found ${people.size} contacts. Ask December which one (spell a name if asked), then call again " +
                "with the exact name.", contacts = people.map { it.getString("name") })
        val name = person.getString("name")

        val numbers = person.getJSONArray("numbers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val wanted = args.optString("number").trim().lowercase()
        val askNumber = { choose("$name has ${numbers.size} numbers. Ask December which, then call again with --number.",
            numbers = numbers.map { it.getString("label") }, name = name) }
        val number = if (wanted.isNotEmpty()) {
            // what December said must pick exactly one ("mobile" when there are "mobile 1" and "mobile 2" doesn't)
            numbers.filter { it.getString("label") == wanted }.singleOrNull()
                ?: numbers.filter { it.getString("label").startsWith(wanted) }.singleOrNull()
                ?: return askNumber()
        } else {
            numbers.singleOrNull()
                ?: numbers.firstOrNull { last != null && last.optString("name") == name && it.getString("label") == last.optString("label") }
                ?: return askNumber()
        }

        val sims = sims(c)
        val askSim = { choose("Ask December which SIM to call $name from, then call again with --sim.",
            sims = sims.map { "SIM ${it.slot} (${it.name})" }, name = name) }
        val sim: Sim? = when {
            sims.size <= 1 -> sims.firstOrNull()
            args.optInt("sim", 0) > 0 -> sims.firstOrNull { it.slot == args.optInt("sim") }
                ?: return fail("there's no SIM ${args.optInt("sim")}")
            // the SIM chosen last time for this person; if it's gone, ask again
            last != null && last.optString("name") == name && last.optInt("sim", 0) > 0 ->
                sims.firstOrNull { it.slot == last.optInt("sim") } ?: return askSim()
            else -> return askSim()
        }

        val remember = { prefs(c).edit().putString(key, JSONObject().put("name", name)
            .put("label", number.getString("label")).put("sim", sim?.slot ?: 0).toString()).apply() }
        val how = "$name (${number.getString("label")})" + (sim?.let { " from SIM ${it.slot}, ${it.name}" } ?: "")
        // December asked on a Mochi call on this phone, or said yes out loud on a voice call (the server checked a
        // voice call is live): no tap. Otherwise the Confirm screen.
        if (!Call.active && !args.optBoolean("voice_ok")) {
            val result = confirmAndDo(c, "call", name, "", number.getString("number"), sim?.slot ?: 0)
            if (result.optBoolean("ok")) remember() // only a call December confirmed is remembered
            return result
        }
        if (c.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return fail("the Place calls permission is off (Phone abilities in the Mochi app)")
        }
        // end a Mochi call here first (it holds the audio), then let Telecom place the call; that isn't an activity
        // launch, so Android can't silently block it from the background
        if (Call.active) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (Call.active) Call.end() }
            Thread.sleep(400)
        }
        val extras = android.os.Bundle()
        sim?.handle?.let { extras.putParcelable(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        c.getSystemService(android.telecom.TelecomManager::class.java)
            .placeCall(android.net.Uri.fromParts("tel", number.getString("number"), null), extras)
        remember()
        // without the SIM's call account Android shows its own SIM picker: say so, so it can be fixed
        return ok("calling $how" + if (sim != null && sim.handle == null) " (the phone couldn't match SIM ${sim.slot}, so Android asks which SIM)" else "")
    }

    private fun choose(ask: String, contacts: List<String> = emptyList(), numbers: List<String> = emptyList(),
                       sims: List<String> = emptyList(), name: String = ""): JSONObject {
        val r = JSONObject().put("status", "choose").put("ask", ask)
        if (name.isNotEmpty()) r.put("name", name)
        if (contacts.isNotEmpty()) r.put("contacts", JSONArray(contacts))
        if (numbers.isNotEmpty()) r.put("numbers", JSONArray(numbers))
        if (sims.isNotEmpty()) r.put("sims", JSONArray(sims))
        return ok(r)
    }

    // ---- outward actions: only after December taps Confirm on the phone ----

    private class Pending(val latch: CountDownLatch = CountDownLatch(1)) {
        @Volatile var answer: JSONObject? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Resolve "Mom" or a number, show the confirm screen, wait for December's tap. */
    private fun confirmAndDo(c: Context, kind: String, to: String, text: String, knownNumber: String = "",
                             sim: Int = 0): JSONObject {
        if (to.isBlank()) return fail("who to ${if (kind == "call") "call" else "text"}?")
        if (kind == "sms" && text.isBlank()) return fail("the message is empty")
        val (name, number) = if (knownNumber.isNotEmpty()) to to knownNumber
            else resolve(c, to) ?: return fail("no contact or number matches \"$to\"")
        val id = java.util.UUID.randomUUID().toString()
        val p = Pending()
        pending[id] = p
        try {
            val intent = Intent(c, ConfirmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ConfirmActivity.EXTRA_ID, id).putExtra(ConfirmActivity.EXTRA_KIND, kind)
                .putExtra(ConfirmActivity.EXTRA_NAME, name).putExtra(ConfirmActivity.EXTRA_NUMBER, number)
                .putExtra(ConfirmActivity.EXTRA_TEXT, text)
                .putExtra(ConfirmActivity.EXTRA_NOTIFICATION, id.hashCode())
                .putExtra(ConfirmActivity.EXTRA_SIM, sim)
            // During a Mochi call the app is on screen and may open it directly; otherwise a full-screen notification
            // is how a background app brings up a screen (like an incoming call)
            if (Call.active && runCatching { c.startActivity(intent) }.isSuccess) {
                return waitFor(p, c, id.hashCode())
            }
            val open = PendingIntent.getActivity(c, id.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, Mochi.CHANNEL_NOTICES)
                .setSmallIcon(R.drawable.ic_mochi)
                .setContentTitle(if (kind == "call") "Call $name?" else "Text $name?")
                .setContentText(if (kind == "call") number else text)
                .setFullScreenIntent(open, true)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_CALL)
                .setAutoCancel(true)
                .build()
            c.getSystemService(NotificationManager::class.java).notify(id.hashCode(), n)
            return waitFor(p, c, id.hashCode())
        } finally {
            pending.remove(id)
        }
    }

    private fun waitFor(p: Pending, c: Context, notificationId: Int): JSONObject {
        if (!p.latch.await(CONFIRM_WAIT_S, TimeUnit.SECONDS)) {
            c.getSystemService(NotificationManager::class.java).cancel(notificationId)
            return fail("December didn't confirm on the phone in time")
        }
        return p.answer ?: fail("not done")
    }

    /** Whether Mochi is still waiting for this confirmation (not timed out, not answered). */
    fun isPending(id: String) = pending[id]?.answer == null && pending.containsKey(id)

    /** ConfirmActivity reports what happened (done, cancelled, failed). */
    fun answer(id: String, result: JSONObject) {
        pending[id]?.let { it.answer = result; it.latch.countDown() }
    }

    fun answered(text: String) = ok(text)
    fun failed(text: String) = fail(text)

    /** A contact name ("Mom") or a number, as (name, number). */
    private fun resolve(c: Context, to: String): Pair<String, String>? {
        val digits = to.filter { it.isDigit() || it == '+' }
        if (digits.count(Char::isDigit) >= 6 && digits.length >= to.trim().length - 3) {
            return (nameFor(c, digits) ?: "that number") to digits // the name shown back to Mochi, never the number
        }
        val found = contacts(c, to.trim(), 3)
        if (found.length() == 0) return null
        val first = found.getJSONObject(0)
        return first.getString("name") to first.getJSONArray("numbers").getJSONObject(0).getString("number")
    }
}
