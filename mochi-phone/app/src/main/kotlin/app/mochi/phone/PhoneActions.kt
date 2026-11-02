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
            "call.place" -> confirmAndDo(c, "call", args.optString("to"), "")
            "sms.send" -> confirmAndDo(c, "sms", args.optString("to"), args.optString("text"))
            "open" -> open(c, args.optString("url"))
            else -> fail("unknown action $action")
        }
    } catch (e: SecurityException) {
        fail("permission is off on the phone (turn it on under Phone abilities in the Mochi app)")
    } catch (e: Exception) {
        fail(e.message ?: e.javaClass.simpleName)
    }

    private fun ok(result: Any) = JSONObject().put("ok", true).put("result", result)

    /** Phone numbers never leave the phone (December's privacy rule): numbers are only used here, on the phone. */
    private val NUMBER = Regex("""\+?\d[\d\s().-]{5,}\d""")
    fun hideNumbers(text: String) = NUMBER.replace(text) { if (it.value.count(Char::isDigit) >= 7) "[number hidden]" else it.value }

    /** Contacts as Mochi sees them: names, and how many numbers each has (never the numbers). */
    private fun namesOnly(found: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until found.length()) {
            val contact = found.getJSONObject(i)
            out.put(JSONObject().put("name", contact.getString("name"))
                .put("phone_numbers_saved", contact.getJSONArray("numbers").length()))
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
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { cur ->
            val byName = LinkedHashMap<String, MutableSet<String>>()
            while (cur.moveToNext() && byName.size <= limit) {
                val name = cur.getString(0) ?: continue
                val number = cur.getString(1)?.filter { it.isDigit() || it == '+' } ?: continue
                byName.getOrPut(name) { linkedSetOf() }.add(number)
            }
            byName.entries.take(limit).forEach { (name, numbers) ->
                out.put(JSONObject().put("name", name).put("numbers", JSONArray(numbers.toList())))
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
                val name = cur.getString(0)?.takeIf { it.isNotBlank() }
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
                    val n = list.getJSONObject(i).getJSONArray("numbers"); (0 until n.length()).map { n.getString(it) } }
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
            if (it.moveToFirst()) it.getString(0) else null
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

    // ---- outward actions: only after December taps Confirm on the phone ----

    private class Pending(val latch: CountDownLatch = CountDownLatch(1)) {
        @Volatile var answer: JSONObject? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Resolve "Mom" or a number, show the confirm screen, wait for December's tap. */
    private fun confirmAndDo(c: Context, kind: String, to: String, text: String): JSONObject {
        if (to.isBlank()) return fail("who to ${if (kind == "call") "call" else "text"}?")
        if (kind == "sms" && text.isBlank()) return fail("the message is empty")
        val (name, number) = resolve(c, to) ?: return fail("no contact or number matches \"$to\"")
        val id = java.util.UUID.randomUUID().toString()
        val p = Pending()
        pending[id] = p
        try {
            val intent = Intent(c, ConfirmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ConfirmActivity.EXTRA_ID, id).putExtra(ConfirmActivity.EXTRA_KIND, kind)
                .putExtra(ConfirmActivity.EXTRA_NAME, name).putExtra(ConfirmActivity.EXTRA_NUMBER, number)
                .putExtra(ConfirmActivity.EXTRA_TEXT, text)
                .putExtra(ConfirmActivity.EXTRA_NOTIFICATION, id.hashCode())
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
        return first.getString("name") to first.getJSONArray("numbers").getString(0)
    }
}
