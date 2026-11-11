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

    private fun post(c: Context, path: String, body: JSONObject): JSONObject =
        Mochi.http.newCall(Request.Builder().url(url(c) + path).post(body.toString().toRequestBody(JSON)).build())
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

    /** Unlock the server with this phone's key. [done] gets null on success or the reason (main thread). */
    fun unlock(a: Activity, done: (String?) -> Unit) {
        val p = prefs(a)
        val sealed = p.getString("key", null) ?: return done("Set this phone up first.")
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, Base64.decode(p.getString("iv", ""), Base64.NO_WRAP)))
            }
        } catch (e: Exception) { return done("The key on this phone can't be used any more (${e.message}): set it up again.") }
        authenticate(a, cipher, "Unlock the server", { unlocked ->
            val raw = try { unlocked.doFinal(Base64.decode(sealed, Base64.NO_WRAP)) } catch (e: Exception) { return@authenticate done(e.message) }
            val body = JSONObject().put("key", Base64.encodeToString(raw, Base64.NO_WRAP))
            raw.fill(0)
            Thread {
                val error = try { post(a, "/unlock", body); null } catch (e: Exception) { e.message ?: "failed" }
                a.runOnUiThread { done(error) }
            }.start()
        }, done)
    }

    /** Forget the key on this phone (the server keeps its slot until `mochi-vault remove-key` over SSH). */
    fun forget(c: Context) {
        prefs(c).edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }
}

/** Settings: the vault's state, setting this phone up, and the Unlock button. */
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
    fun refresh() = scope.launch {
        locked = withContext(Dispatchers.IO) { runCatching { VaultUnlock.status(ctx).optBoolean("locked") }.getOrNull() }
    }
    LaunchedEffect(Unit) { refresh() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("Server vault", style = Type.title, color = c.ink)
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
            Pill(if (busy) "Unlocking…" else "Unlock server", locked != false, Modifier.fillMaxWidth()) {
                if (busy) return@Pill
                busy = true; note = ""
                VaultUnlock.unlock(a) { error ->
                    busy = false
                    note = error ?: "Unlocked. Tiny starts within a minute."
                    refresh()
                }
            }
            Text("Remove from this phone", style = Type.small, color = c.muted,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { VaultUnlock.forget(ctx); enrolled = false; note = "Removed from this phone. On the server: sudo mochi-vault remove-key" }
                    .padding(vertical = Space.s))
        }
        if (note.isNotEmpty()) Text(note, style = Type.small, color = c.muted)
    }
}
