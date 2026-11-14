package app.mochi.phone

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/**
 * When the app crashes, what crashed is saved on the phone and sent to the server (mochi-ring /app/crash) the next
 * time the app starts, so a crash can be fixed from its real cause (2026-11-14: "the app keeps crashing" during calls,
 * and there was no way to see why). Only the error and where in the app it happened: no messages, contacts or audio.
 */
object Crashes {
    private const val FILE = "last-crash.txt"

    fun install(app: Context) {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                File(app.filesDir, FILE).writeText(
                    "version ${version ?: "?"}, thread ${thread.name}, at ${java.time.Instant.now()}\n" +
                        error.stackTraceToString().take(12_000))
            }
            before?.uncaughtException(thread, error)
        }
    }

    /** Sends a saved crash (off the main thread); kept on the phone until the server has it. */
    fun sendPending(c: Context) {
        val file = File(c.filesDir, FILE)
        if (!file.exists()) return
        Thread {
            runCatching {
                val body = JSONObject().put("report", file.readText())
                val req = Request.Builder().url("${Mochi.ringUrl(c)}/app/crash")
                    .header("Authorization", "Bearer ${Mochi.token(c)}")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                Mochi.http.newCall(req).execute().use { if (it.isSuccessful) file.delete() }
            }
        }.start()
    }
}
