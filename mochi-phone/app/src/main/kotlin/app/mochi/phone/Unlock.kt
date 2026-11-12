package app.mochi.phone

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The server's encrypted vault, unlocked from this phone (mochi-unlock on the server, tailnet :8452).
 *
 * Setting up: December types the vault phrase once; the phone makes its own 32 random bytes, the server adds
 * them as a separate key of the vault (the phrase authorises that and is not kept here). The 32 bytes are kept
 * encrypted with an Android-keystore key that needs the fingerprint / screen lock for every single use, so the
 * phone's key never sits readable in storage and can't be copied off the phone.
 */
object VaultUnlock {
    private const val ALIAS = "mochi-vault-unlock"
    private val JSON = "application/json".toMediaType()

    private fun prefs(c: Context) = c.getSharedPreferences("mochi-vault", Context.MODE_PRIVATE)
    private fun url(c: Context) = "https://${Mochi.host(c)}:8452"
    fun enrolled(c: Context) = prefs(c).contains("key")

    /** {"locked": bool, "phones": [...]} or throws. Blocking. */
    fun status(c: Context): JSONObject =
        Mochi.http.newCall(Request.Builder().url("${url(c)}/status").build()).execute().use { r ->
            JSONObject(r.body?.string() ?: "{}")
        }

    /** The saved Claude accounts: names and the last 4 characters of each token, never a token. Blocking; throws. */
    fun claudeAccounts(c: Context): JSONObject =
        Mochi.http.newCall(Request.Builder().url("${url(c)}/claude/accounts").build()).execute().use { r ->
            val reply = JSONObject(r.body?.string() ?: "{}")
            if (!r.isSuccessful) throw IllegalStateException(reply.optString("error", "the server said ${r.code}"))
            reply
        }

    private fun post(c: Context, path: String, body: JSONObject): JSONObject =
        // switching a Claude account tests the token and restarts Mochi: that can take a minute
        (if (path.startsWith("/claude")) Mochi.http.newBuilder().readTimeout(150, java.util.concurrent.TimeUnit.SECONDS).build() else Mochi.http)
            .newCall(Request.Builder().url(url(c) + path).post(body.toString().toRequestBody(JSON)).build())
            .execute().use { r ->
                val reply = JSONObject(r.body?.string() ?: "{}")
                if (!r.isSuccessful) throw IllegalStateException(reply.optString("error", "the server said ${r.code}"))
                reply
            }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // every use asks: fingerprint (strong biometric) or the screen lock
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .build())
        return gen.generateKey()
    }

    /** Fingerprint / screen lock, then [onOk] with the unlocked cipher (on the main thread). */
    private fun authenticate(a: Activity, cipher: Cipher, title: String, onOk: (Cipher) -> Unit, onError: (String) -> Unit) {
        BiometricPrompt.Builder(a).setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
            .authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), a.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        result.cryptoObject?.cipher?.let(onOk) ?: onError("no cipher came back")
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) = onError(msg.toString())
                })
    }

    private fun deviceName() = (Build.MODEL ?: "phone").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30)
        .ifEmpty { "phone" }

    /** Set this phone up. [done] gets null on success or the reason it failed (main thread). */
    fun enroll(a: Activity, phrase: String, done: (String?) -> Unit) {
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        } catch (e: Exception) { return done("This phone can't keep the key safely: ${e.message}") }
        authenticate(a, cipher, "Set up Unlock server", { unlocked ->
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val sealed = try { unlocked.doFinal(raw) } catch (e: Exception) { raw.fill(0); return@authenticate done(e.message) }
            val iv = unlocked.iv
            val body = JSONObject().put("phrase", phrase).put("key", Base64.encodeToString(raw, Base64.NO_WRAP))
                .put("name", deviceName())
            raw.fill(0)
            Thread {
                val error = try { post(a, "/enroll", body); null } catch (e: Exception) { e.message ?: "failed" }
                if (error == null) prefs(a).edit()
                    .putString("key", Base64.encodeToString(sealed, Base64.NO_WRAP))
                    .putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP)).apply()
                a.runOnUiThread { done(error) }
            }.start()
        }, done)
    }

    /** Fingerprint, then send this phone's key to [path] (with [extra] fields). [done]: null or the reason. */
    private fun withKey(a: Activity, title: String, path: String, extra: JSONObject, done: (String?) -> Unit) {
        val p = prefs(a)
        val sealed = p.getString("key", null) ?: return done("Set this phone up first.")
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, Base64.decode(p.getString("iv", ""), Base64.NO_WRAP)))
            }
        } catch (e: Exception) { return done("The key on this phone can't be used any more (${e.message}): set it up again.") }
        authenticate(a, cipher, title, { unlocked ->
            val raw = try { unlocked.doFinal(Base64.decode(sealed, Base64.NO_WRAP)) } catch (e: Exception) { return@authenticate done(e.message) }
            val body = JSONObject(extra.toString()).put("key", Base64.encodeToString(raw, Base64.NO_WRAP))
            raw.fill(0)
            Thread {
                val error = try { post(a, path, body); null } catch (e: Exception) { e.message ?: "failed" }
                a.runOnUiThread { done(error) }
            }.start()
        }, done)
    }

    /** Fingerprint, then [path] on the unlock service with this phone's key (e.g. /trading/creds). */
    fun send(a: Activity, title: String, path: String, extra: JSONObject, done: (String?) -> Unit) = withKey(a, title, path, extra, done)

    /** Unlock the server (Tiny starts). */
    fun unlock(a: Activity, done: (String?) -> Unit) = withKey(a, "Unlock the server", "/unlock", JSONObject(), done)

    /** Lock it now: Tiny stops, the data is unreadable until the next unlock. Reversible. */
    fun lock(a: Activity, done: (String?) -> Unit) = withKey(a, "Lock the server now", "/lock", JSONObject(), done)

    /** EMERGENCY WIPE: the vault's keys are destroyed at once, then everything personal is deleted. Irreversible. */
    fun wipe(a: Activity, done: (String?) -> Unit) =
        withKey(a, "EMERGENCY WIPE the server", "/wipe", JSONObject().put("confirm", "DELETE EVERYTHING"), done)

    /** Forget the key on this phone (the server keeps its slot until `mochi-vault remove-key` over SSH). */
    fun forget(c: Context) {
        prefs(c).edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }
}

/** Settings > Security: the vault's state, setting this phone up, one Lock/Unlock button, Emergency wipe. */
@Composable
fun UnlockCard() {
    val ctx = LocalContext.current
    val a = ctx as? Activity ?: return
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var locked by remember { mutableStateOf<Boolean?>(null) }
    var enrolled by remember { mutableStateOf(VaultUnlock.enrolled(ctx)) }
    var phrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var wipeTyping by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf("") }
    var countdown by remember { mutableStateOf(0) }
    fun refresh() = scope.launch {
        locked = withContext(Dispatchers.IO) { runCatching { VaultUnlock.status(ctx).optBoolean("locked") }.getOrNull() }
    }
    LaunchedEffect(Unit) { refresh() }
    // the 5-second Cancel window, then the fingerprint, then the wipe
    LaunchedEffect(countdown) {
        if (countdown <= 0) return@LaunchedEffect
        kotlinx.coroutines.delay(1_000)
        if (countdown == 1) {
            countdown = 0; wipeTyping = false; confirm = ""; busy = true
            VaultUnlock.wipe(a) { error ->
                busy = false
                note = error ?: "Wipe started: the vault's key is destroyed. You'll get the wipe emails."
                refresh()
            }
        } else if (countdown > 1) countdown -= 1
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(when (locked) { null -> "Checking…"; true -> "Locked: Tiny waits until you unlock it."; false -> "Unlocked: Tiny is running." },
            style = Type.small, color = if (locked == true) c.danger else c.muted)
        if (!enrolled) {
            Text("Set up once: type your vault phrase. The phone makes its own key and keeps it behind your fingerprint; " +
                "the phrase isn't kept.", style = Type.small, color = c.muted)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.field).padding(Space.l)) {
                if (phrase.isEmpty()) Text("Vault phrase", style = Type.body, color = c.muted)
                BasicTextField(phrase, { phrase = it }, singleLine = true, textStyle = Type.body.copy(color = c.ink),
                    cursorBrush = SolidColor(c.ink), visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Vault phrase" })
            }
            Pill(if (busy) "Setting up…" else "Set up Unlock server", true, Modifier.fillMaxWidth()) {
                if (busy || phrase.isBlank()) return@Pill
                busy = true; note = ""
                VaultUnlock.enroll(a, phrase) { error ->
                    busy = false; phrase = ""
                    if (error == null) { enrolled = true; note = "This phone can unlock the server now." } else note = error
                }
            }
        } else {
            // one button: Unlock while it's locked, Lock while it's open
            Pill(when {
                busy -> "…"
                locked == null -> "Checking…"
                locked == true -> "Unlock server"
                else -> "Lock server"
            }, locked == true, Modifier.fillMaxWidth()) {
                if (busy || locked == null) return@Pill
                busy = true; note = ""
                val opening = locked == true
                val after: (String?) -> Unit = { error ->
                    busy = false
                    note = error ?: if (opening) "Unlocked. Tiny starts within a minute." else "Locked. Tiny is stopped until you unlock it."
                    refresh()
                }
                if (opening) VaultUnlock.unlock(a, after) else VaultUnlock.lock(a, after)
            }
            if (locked == false) Text("Locking stops Tiny (and a Claude session on the server) until it's unlocked again.",
                style = Type.small, color = c.muted)
            when {
                countdown > 0 -> {
                    Text("Wiping in $countdown…", style = Type.title, color = c.danger)
                    Pill("Cancel", true, Modifier.fillMaxWidth()) { countdown = 0; wipeTyping = false; confirm = "" }
                }
                wipeTyping -> {
                    Text("This destroys the vault's key at once and deletes everything personal on the server. It can't be " +
                        "undone: only your GitHub backup and age key rebuild it. Type DELETE EVERYTHING:", style = Type.small, color = c.danger)
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.field).padding(Space.l)) {
                        BasicTextField(confirm, { confirm = it }, singleLine = true, textStyle = Type.body.copy(color = c.ink),
                            cursorBrush = SolidColor(c.ink), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Type DELETE EVERYTHING" })
                    }
                    Pill("Wipe the server", confirm == "DELETE EVERYTHING", Modifier.fillMaxWidth()) {
                        if (confirm == "DELETE EVERYTHING") countdown = 5
                    }
                    Text("Cancel", style = Type.small, color = c.muted,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { wipeTyping = false; confirm = "" }.padding(vertical = Space.s))
                }
                else -> Text("Emergency wipe…", style = Type.small, color = c.danger,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { wipeTyping = true; note = "" }.padding(vertical = Space.s))
            }
            Text("Remove from this phone", style = Type.small, color = c.muted,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { VaultUnlock.forget(ctx); enrolled = false; note = "Removed from this phone. On the server: sudo mochi-vault remove-key" }
                    .padding(vertical = Space.s))
        }
        if (note.isNotEmpty()) Text(note, style = Type.small, color = c.muted)
    }
}
