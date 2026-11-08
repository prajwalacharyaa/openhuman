package app.mochi.phone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * App updates over Tailscale: the server (mochi-publish) keeps the newest signed APK; this checks it, downloads it
 * from mochi-ring, checks its SHA-256 and hands it to Android's installer. Android shows its own "Update?" screen
 * and only accepts an APK signed with this app's key.
 */
object Updater {
    /** This build's number, from its version name `phone-vN` (0 for local "dev" builds). */
    fun current(c: Context): Int {
        val name = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
        return Regex("""phone-v(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** The newer version the server has, or null. Blocking. */
    fun available(c: Context): JSONObject? {
        val req = Request.Builder().url("${Mochi.ringUrl(c)}/app/latest")
            .header("Authorization", "Bearer ${Mochi.token(c)}").build()
        Mochi.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val info = JSONObject(r.body?.string() ?: "{}")
            val v = info.optInt("version")
            val mine = current(c)
            return if (v > 0 && mine > 0 && v > mine) info else null
        }
    }

    /** Whether Android lets this app install updates (the one-time "Install unknown apps" permission). */
    fun allowed(c: Context) = c.packageManager.canRequestPackageInstalls()

    fun allowIntent(c: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))

    /** Downloads [info]'s APK (reporting 0..1 progress), checks it and starts the install. Blocking. */
    fun install(c: Context, info: JSONObject, progress: (Float) -> Unit) {
        val file = File(c.cacheDir, "update.apk")
        val req = Request.Builder().url("${Mochi.ringUrl(c)}/app/apk")
            .header("Authorization", "Bearer ${Mochi.token(c)}").build()
        val digest = MessageDigest.getInstance("SHA-256")
        Mochi.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("download failed (HTTP ${r.code})")
            val body = r.body ?: throw IllegalStateException("empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: info.optLong("size", 1)
            var got = 0L
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        got += n
                        progress((got.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != info.optString("sha256")) {
            file.delete()
            throw IllegalStateException("the download didn't match its checksum: not installed")
        }
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(c.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("mochi.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val done = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(done.intentSender)
        }
        file.delete()
    }
}

/** Android's answer to an install: shows its confirmation screen when it asks for one. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
