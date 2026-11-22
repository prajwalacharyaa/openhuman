package app.mochi.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Phone control by voice (2026-11-22, December): "go home", "swipe left", "open Instagram", "tap Reels", "type mango".
 * Android's accessibility access, used only when December says so on a call; all of it runs here on the phone. What
 * is on the screen never leaves the phone: Mochi gets back only "done" or why not (December's privacy rule), so a
 * button is found by the name December says, on the phone, not by a model looking at the screen.
 *
 * Two switches: this service turned on in Android's settings (the phone asks December itself), and "Phone control"
 * in Settings > Phone abilities. Taps that send, post, pay or delete need December's own word ("--yes" after they
 * said "send"), like a text waits for Confirm.
 */
class PhoneControl : AccessibilityService() {
    override fun onServiceConnected() { running = this }
    override fun onUnbind(intent: Intent?): Boolean { if (running === this) running = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (running === this) running = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val want = learning ?: return
        if (System.currentTimeMillis() > learningUntil) { learning = null; return }
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // a tap inside Mochi itself isn't the lesson
        val node = event.source ?: return
        val r = Rect().also { node.getBoundsInScreen(it) }
        val m = resources.displayMetrics
        val step = JSONObject().put("name", want).put("app", pkg).put("id", node.viewIdResourceName ?: "")
            .put("label", label(node).take(80)).put("rx", r.exactCenterX() / m.widthPixels).put("ry", r.exactCenterY() / m.heightPixels)
            .put("at", System.currentTimeMillis())
        learning = null
        save(this, step)
        android.os.Handler(mainLooper).post {
            android.widget.Toast.makeText(this, "Mochi learned \"$want\"", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    override fun onInterrupt() = Unit

    companion object {
        @Volatile private var running: PhoneControl? = null
        private const val PREF = "phone_control"
        /** Words on buttons that act for December where others see it, or can't be undone. */
        private val RISKY = Regex("""\b(send|post|share|pay|buy|order|delete|remove|confirm|publish|transfer|submit|call)\b""", RegexOption.IGNORE_CASE)

        // learned taps (2026-11-22, December: "look how I close it, then do it next time"): on the phone only
        private const val LEARNED = "phone_control_learned"
        @Volatile private var learning: String? = null
        @Volatile private var learningUntil = 0L

        private fun norm(name: String) = name.trim().lowercase().replace(Regex("[^a-z0-9\\p{L} ]"), "")
            .removePrefix("the ").replace(Regex("\\s+"), " ")

        fun learned(c: Context): List<JSONObject> = runCatching {
            val a = org.json.JSONArray(Mochi.prefs(c).getString(LEARNED, "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }
        }.getOrDefault(emptyList())

        private fun store(c: Context, list: List<JSONObject>) {
            Mochi.prefs(c).edit().putString(LEARNED, org.json.JSONArray(list.takeLast(200)).toString())
                .putBoolean(LEARNED_DIRTY, true).apply()
            Thread { push(c) }.start()
        }

        // the server keeps a copy of the phone's list (2026-11-22, December: get them back after a reinstall). The
        // phone's list wins: every change sends it whole; one that couldn't go (offline) is sent at the next start.
        private const val LEARNED_DIRTY = "phone_control_learned_dirty"
        private const val LEARNED_RESTORED = "phone_control_learned_restored"
        private const val LESSONS_PATH = "/phone-control/lessons"

        private fun push(c: Context) = runCatching {
            val body = JSONObject().put("lessons", org.json.JSONArray(learned(c)))
            val req = okhttp3.Request.Builder().url(Mochi.ringUrl(c) + LESSONS_PATH)
                .header("Authorization", "Bearer ${Mochi.token(c)}")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            Mochi.http.newCall(req).execute().use { if (it.isSuccessful) Mochi.prefs(c).edit().putBoolean(LEARNED_DIRTY, false).apply() }
        }

        /** At the app's start: send changes that didn't go; on a fresh install (nothing here yet), take the server's copy. */
        fun sync(c: Context) = Thread {
            val prefs = Mochi.prefs(c)
            if (prefs.getBoolean(LEARNED_DIRTY, false)) { push(c); return@Thread }
            if (learned(c).isNotEmpty() || prefs.getBoolean(LEARNED_RESTORED, false)) return@Thread
            runCatching {
                val req = okhttp3.Request.Builder().url(Mochi.ringUrl(c) + LESSONS_PATH)
                    .header("Authorization", "Bearer ${Mochi.token(c)}").build()
                Mochi.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val got = JSONObject(r.body?.string().orEmpty()).optJSONArray("lessons") ?: org.json.JSONArray()
                    prefs.edit().putString(LEARNED, got.toString()).putBoolean(LEARNED_RESTORED, true).apply()
                }
            }
        }.start()

        /** One lesson per name and app: showing it again replaces it. */
        private fun save(c: Context, step: JSONObject) = store(c, learned(c).filterNot {
            norm(it.optString("name")) == norm(step.optString("name")) && it.optString("app") == step.optString("app")
        } + step)

        fun forgetAll(c: Context) = store(c, emptyList())

        private fun learn(c: Context, name: String): JSONObject {
            if (norm(name).isEmpty()) return fail("learn what? say what it does, like \"close the video\"")
            learning = name.trim()
            learningUntil = System.currentTimeMillis() + 30_000
            return ok("watching: December taps it now (within 30 seconds), and next time \"$name\" taps the same thing in this app")
        }

        private fun forget(c: Context, name: String): JSONObject {
            val before = learned(c)
            val left = if (norm(name) in setOf("everything", "all", "")) emptyList() else before.filterNot { norm(it.optString("name")) == norm(name) }
            store(c, left)
            return if (left.size < before.size) ok("forgot it") else fail("nothing learned called \"$name\"")
        }

        /** A lesson for these words in the app on screen: the same button by its id, else its name, else its place. */
        private fun tapLearned(c: Context, s: PhoneControl, wanted: String): JSONObject? {
            val pkg = s.rootInActiveWindow?.packageName?.toString() ?: return null
            val w = norm(wanted)
            val lesson = learned(c).filter { it.optString("app") == pkg }.firstOrNull {
                val n = norm(it.optString("name")); n == w || (n.length > 3 && (w.contains(n) || n.contains(w)))
            } ?: return null
            val name = lesson.optString("name")
            val all = nodes(s).filter { it.isVisibleToUser }
            val byId = lesson.optString("id").takeIf { it.isNotEmpty() }?.let { id -> all.firstOrNull { it.viewIdResourceName == id } }
            val byLabel = lesson.optString("label").takeIf { it.isNotEmpty() }?.let { l -> all.firstOrNull { label(it) == l } }
            var n: AccessibilityNodeInfo? = byId ?: byLabel
            while (n != null && !n.isClickable) n = n.parent
            if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return ok("did \"$name\" (as December showed)")
            val m = s.resources.displayMetrics
            val x = lesson.optDouble("rx").toFloat() * m.widthPixels; val y = lesson.optDouble("ry").toFloat() * m.heightPixels
            return gesture(s, Path().apply { moveTo(x, y) }, 60L, "did \"$name\" (where December tapped)")
        }

        fun allowed(c: Context) = Mochi.prefs(c).getBoolean(PREF, false)
        fun setAllowed(c: Context, on: Boolean) = Mochi.prefs(c).edit().putBoolean(PREF, on).apply()
        /** The service is turned on in Android's accessibility settings (and connected). */
        fun connected() = running != null
        fun openAndroidSettings(c: Context) = runCatching {
            c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        private fun ok(result: String) = JSONObject().put("ok", true).put("result", result)
        private fun fail(error: String) = JSONObject().put("ok", false).put("error", error)

        /** Why it can't act now, or null. */
        private fun blocked(c: Context): String? = when {
            !allowed(c) -> "phone control is off: December turns it on in the Mochi app, Settings > Phone abilities > Phone control"
            running == null -> "phone control isn't allowed in Android yet: December turns on Mochi under Settings > Accessibility (the app's Phone control row opens it)"
            else -> null
        }

        /** One step: {op: home|back|recents|notifications|swipe|scroll|app|tap|type, ...}. */
        fun act(c: Context, args: JSONObject): JSONObject {
            val op = args.optString("op")
            if (op == "app") return openApp(c, args.optString("name"))
            if (op == "search") return search(c, args.optString("app"), args.optString("query"))
            if (op == "media") return media(c, args.optString("key"))
            blocked(c)?.let { return fail(it) }
            val s = running ?: return fail("phone control isn't connected")
            return when (op) {
                "home" -> global(s, GLOBAL_ACTION_HOME, "home screen")
                "back" -> global(s, GLOBAL_ACTION_BACK, "went back")
                "recents" -> global(s, GLOBAL_ACTION_RECENTS, "recent apps shown")
                "notifications" -> global(s, GLOBAL_ACTION_NOTIFICATIONS, "notifications pulled down")
                "swipe", "scroll" -> swipe(s, op, args.optString("dir").lowercase())
                "tap" -> position(args.optString("label"))?.let { tapNth(s, it) }
                    ?: tap(c, s, args.optString("label"), args.optBoolean("yes"))
                // "watch how I close this": the next tap December makes is remembered under that name (on the phone)
                "learn" -> learn(c, args.optString("name"))
                "forget" -> forget(c, args.optString("name"))
                "type" -> type(s, args.optString("text"), args.optBoolean("enter"))
                // the keyboard's Enter / Search key in the box on screen: runs a search in any app
                "enter" -> enter(s)
                // "remove what's typed": empty the text box on screen
                "clear" -> clear(s)
                else -> fail("unknown step: home, back, recents, notifications, swipe, scroll, app, tap, type, enter, search, learn, forget, clear, media")
            }
        }

        private fun global(s: PhoneControl, action: Int, done: String) =
            if (s.performGlobalAction(action)) ok(done) else fail("the phone didn't do it")

        /** Swipe = a page sideways or up/down (home screen pages, Reels); scroll = a shorter move of the content. */
        private fun swipe(s: PhoneControl, op: String, dir: String): JSONObject {
            val m = s.resources.displayMetrics
            val w = m.widthPixels.toFloat(); val h = m.heightPixels.toFloat()
            val long = op == "swipe"
            // the finger's path: "swipe left" moves the finger right to left (the next page comes in from the right)
            val (x1, y1, x2, y2) = when (dir) {
                "left" -> listOf(w * 0.85f, h * 0.5f, w * 0.15f, h * 0.5f)
                "right" -> listOf(w * 0.15f, h * 0.5f, w * 0.85f, h * 0.5f)
                // "scroll down" = see what's further down = the finger moves up
                "down" -> if (long) listOf(w * 0.5f, h * 0.2f, w * 0.5f, h * 0.8f) else listOf(w * 0.5f, h * 0.7f, w * 0.5f, h * 0.35f)
                "up" -> if (long) listOf(w * 0.5f, h * 0.8f, w * 0.5f, h * 0.2f) else listOf(w * 0.5f, h * 0.35f, w * 0.5f, h * 0.7f)
                else -> return fail("which way: left, right, up or down")
            }
            return gesture(s, Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, if (long) 250L else 400L, "$op $dir")
        }

        private fun gesture(s: PhoneControl, path: Path, ms: Long, done: String): JSONObject {
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
            return if (s.dispatchGesture(g, null, null)) ok(done) else fail("the phone didn't take the gesture")
        }

        /** Every node on the screen now (all windows: the app, the keyboard, the system bars). */
        private fun nodes(s: PhoneControl): List<AccessibilityNodeInfo> {
            val out = ArrayList<AccessibilityNodeInfo>()
            val roots = runCatching { s.windows.mapNotNull { it.root } }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(s.rootInActiveWindow) }
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 40 || out.size > 3000) return
                out.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            roots.forEach { walk(it, 0) }
            return out
        }

        private fun label(n: AccessibilityNodeInfo) = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()

        /** Tap the button or text December named: exact name first, then one that starts with it, then contains it. */
        private fun tap(c: Context, s: PhoneControl, wanted: String, yes: Boolean): JSONObject {
            val w = wanted.trim().lowercase()
            if (w.isEmpty()) return fail("tap what? say the button's name")
            if (!RISKY.containsMatchIn(w) || yes) tapLearned(c, s, wanted)?.let { return it }
            if (RISKY.containsMatchIn(w) && !yes)
                return fail("\"$wanted\" acts for December (sends, posts, pays or deletes): only after December says so themselves, then mochi-phone tap \"$wanted\" --yes")
            val all = nodes(s).filter { it.isVisibleToUser && label(it).isNotEmpty() }
            val hit = all.firstOrNull { label(it).lowercase() == w }
                ?: all.firstOrNull { label(it).lowercase().startsWith(w) }
                ?: all.firstOrNull { label(it).lowercase().contains(w) }
                ?: return fail("nothing named \"$wanted\" on the screen now (say it as it's written, or scroll first)")
            // a label is often inside the thing that takes the tap
            var n: AccessibilityNodeInfo? = hit
            while (n != null && !n.isClickable) n = n.parent
            if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return ok("tapped \"${label(hit).take(40)}\"")
            val r = Rect().also { hit.getBoundsInScreen(it) }
            return gesture(s, Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 60L, "tapped \"${label(hit).take(40)}\"")
        }

        /** "first", "second video", "the 3rd one", "last" -> 1, 2, 3, -1; null when it isn't a position. */
        fun position(label: String): Int? {
            val w = label.trim().lowercase().removePrefix("the ").split(" ").firstOrNull() ?: return null
            val rest = label.trim().lowercase().removePrefix("the ").removePrefix(w).trim()
            if (rest.isNotEmpty() && rest !in setOf("one", "video", "reel", "post", "result", "item", "message", "chat", "short"))
                return null
            return when (w) {
                "first", "1st", "top" -> 1; "second", "2nd" -> 2; "third", "3rd" -> 3; "fourth", "4th" -> 4
                "fifth", "5th" -> 5; "sixth", "6th" -> 6; "last" -> -1
                else -> null
            }
        }

        /**
         * "Open the second video": the nth big item in the list on screen (a video, a reel, a post, a chat), found by
         * its size and place, not by reading it: tappable things at least 60% as wide as the screen and 8% as tall,
         * below the app's top bar, top to bottom, one per row (a card's inner parts aren't counted again).
         */
        private fun tapNth(s: PhoneControl, n: Int): JSONObject {
            val m = s.resources.displayMetrics
            val r = Rect()
            val all = nodes(s).filter { it.isClickable && it.isVisibleToUser }.mapNotNull { node ->
                node.getBoundsInScreen(r)
                if (r.top > m.heightPixels * 0.08 && r.width() >= m.widthPixels * 0.6 && r.height() >= m.heightPixels * 0.08)
                    node to Rect(r) else null
            }.sortedWith(compareBy({ it.second.top }, { -it.second.height() }))
            val rows = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
            for (item in all) {
                val last = rows.lastOrNull()
                if (last == null || item.second.top >= last.second.bottom - last.second.height() / 10) rows.add(item)
            }
            if (rows.isEmpty()) return fail("no list of videos or posts on the screen now")
            val hit = (if (n == -1) rows.last() else rows.getOrNull(n - 1))
                ?: return fail("only ${rows.size} on the screen now: scroll down for more")
            val said = if (n == -1) "the last one" else "number $n"
            if (hit.first.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return ok("opened $said")
            return gesture(s, Path().apply { moveTo(hit.second.exactCenterX(), hit.second.exactCenterY()) }, 60L, "opened $said")
        }

        /**
         * "Pause", "play", "next song": the phone's media buttons, as headphones press them, for whatever is playing
         * (YouTube, Spotify, most video players). An app that ignores them (Instagram Reels): December shows it once
         * ("watch how I pause this") and the learned tap does it.
         */
        fun media(c: Context, key: String): JSONObject {
            val code = when (key) {
                "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> return fail("pause, play, next or previous")
            }
            val am = c.getSystemService(android.media.AudioManager::class.java)
            val wasPlaying = am.isMusicActive
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
            // a pause that didn't stop anything: this app ignores media buttons
            if (key == "pause" && wasPlaying) {
                Thread.sleep(400)
                if (am.isMusicActive) return fail("this app ignores the pause button: if December shows it once (\"watch how I pause\"), then tap \"pause\"")
            }
            return ok(key)
        }

        /** "Search YouTube for lofi music": the app's own search results, straight from a link (no typing needed). */
        fun search(c: Context, app: String, query: String): JSONObject {
            if (query.isBlank()) return fail("search for what?")
            val q = Uri.encode(query.trim())
            val intent = when (app.lowercase().trim()) {
                "youtube", "yt" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q"))
                    .setPackage("com.google.android.youtube")
                "google", "web", "" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q"))
                "playstore", "play store", "play" -> Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$q"))
                "maps" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
                "spotify" -> Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$q"))
                else -> return fail("search in: youtube, google, playstore, maps or spotify (in other apps: tap \"Search\", then type)")
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.`package`?.let { p -> if (runCatching { c.packageManager.getPackageInfo(p, 0) }.isFailure) intent.setPackage(null) }
            return if (startOrNotice(c, intent, "Search $app")) ok("searching $app for \"$query\"") else ok("tap the notification to see the results")
        }

        /** Type into the box in focus (or the first one on the screen). Never presses send. */
        private fun type(s: PhoneControl, text: String, enter: Boolean = false): JSONObject {
            if (text.isEmpty()) return fail("type what?")
            val all = nodes(s)
            val box = all.firstOrNull { it.isEditable && it.isFocused } ?: all.firstOrNull { it.isEditable && it.isVisibleToUser }
                ?: return fail("there's no text box on the screen now (tap the message box first)")
            box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val before = box.text?.toString().orEmpty().takeUnless { box.isShowingHintText }.orEmpty()
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, if (before.isBlank()) text else "$before $text")
            }
            if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return fail("the box didn't take the text")
            if (!enter) return ok("typed it (not sent)")
            // a search box: Enter runs the search (in a chat box Enter may send, so it's only asked for searches)
            return if (box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) ok("typed it and pressed Enter")
                else ok("typed it; Enter didn't work here: tap the search button")
        }

        /** Empty the box in focus (or the first one on screen). */
        private fun clear(s: PhoneControl): JSONObject {
            val all = nodes(s)
            val box = all.firstOrNull { it.isEditable && it.isFocused } ?: all.firstOrNull { it.isEditable && it.isVisibleToUser }
                ?: return fail("there's no text box on the screen now")
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
            return if (box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) ok("cleared") else fail("the box didn't clear")
        }

        /** The keyboard's Enter key in the focused box. */
        private fun enter(s: PhoneControl): JSONObject {
            val box = nodes(s).firstOrNull { it.isEditable && it.isFocused } ?: return fail("no text box is selected")
            return if (box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) ok("pressed Enter")
                else fail("Enter didn't work in this box: tap the button instead")
        }

        /** Open an installed app by the name December says ("Instagram", "insta", "YouTube"). No accessibility needed. */
        fun openApp(c: Context, name: String): JSONObject {
            val w = name.trim().lowercase().removeSuffix(" app")
            if (w.isEmpty()) return fail("open which app?")
            val pm = c.packageManager
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
            val alias = mapOf("insta" to "instagram", "fb" to "facebook", "yt" to "youtube", "wa" to "whatsapp",
                "messenger" to "messenger", "gmail" to "gmail", "maps" to "maps", "camera" to "camera", "photos" to "photos")
            val key = alias[w] ?: w
            val hit = apps.firstOrNull { it.second.lowercase() == key }
                ?: apps.firstOrNull { it.second.lowercase().startsWith(key) }
                ?: apps.firstOrNull { it.second.lowercase().contains(key) }
                ?: return fail("no app called \"$name\" on the phone")
            val launch = pm.getLaunchIntentForPackage(hit.first)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return fail("${hit.second} can't be opened from outside")
            return if (startOrNotice(c, launch, "Open ${hit.second}")) ok("${hit.second} is open") else
                ok("${hit.second}: tap the notification to open it (Android allows opening apps only with phone control on or the Mochi app open)")
        }

        /**
         * "Message Samir on Instagram: mango": that person's chat opens, the words are typed (with phone control on),
         * and nothing is sent: December taps Send or says "send". Phone numbers stay on the phone (WhatsApp, SMS).
         */
        fun message(c: Context, args: JSONObject): JSONObject {
            val app = args.optString("app").lowercase().trim()
            val to = args.optString("to").trim().removePrefix("@")
            val text = args.optString("text")
            if (to.isEmpty()) return fail("message whom?")
            val intent = when (app) {
                "instagram", "insta" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://ig.me/m/" + Uri.encode(to)))
                    .setPackage("com.instagram.android")
                "telegram" -> Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=" + Uri.encode(to) +
                    (if (text.isNotEmpty()) "&text=" + Uri.encode(text) else "")))
                "messenger" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://m.me/" + Uri.encode(to)))
                "whatsapp", "sms", "text" -> {
                    // a contact's name -> its number, here on the phone only
                    val number = PhoneActions.numberOf(c, to) ?: return fail("no contact called \"$to\" on the phone")
                    if (app == "whatsapp") Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + number.filter(Char::isDigit) +
                        (if (text.isNotEmpty()) "?text=" + Uri.encode(text) else ""))).setPackage("com.whatsapp")
                    else Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text)
                }
                else -> return fail("which app: instagram, whatsapp, telegram, messenger or sms")
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.`package` != null && runCatching { c.packageManager.getPackageInfo(intent.`package`!!, 0) }.isFailure)
                return fail("$app isn't installed")
            if (!startOrNotice(c, intent, "Open the chat with $to")) return ok("tap the notification to open the chat with $to")
            // Instagram and Messenger links can't carry the words: they're typed once the chat is open
            val prefilled = app in setOf("telegram", "whatsapp", "sms", "text")
            if (text.isNotEmpty() && !prefilled) {
                if (blocked(c) != null) return ok("the chat with $to is open; type \"$text\" yourself (phone control is off, so I can't type)")
                Thread.sleep(2_500) // the chat opening
                val typed = running?.let { type(it, text) }
                if (typed?.optBoolean("ok") != true) return ok("the chat with $to is open, but the words didn't go in: ${typed?.optString("error") ?: "phone control isn't connected"}")
            }
            return ok("the chat with $to is open with \"$text\" typed, not sent: December taps Send or says \"send\"")
        }

        private fun startOrNotice(c: Context, intent: Intent, what: String): Boolean {
            if ((running != null || Call.active || AppLock.visible) && runCatching { c.startActivity(intent) }.isSuccess) return true
            AppNav.show(c, intent, what.hashCode(), "Mochi: $what", what)
            return false
        }
    }
}
