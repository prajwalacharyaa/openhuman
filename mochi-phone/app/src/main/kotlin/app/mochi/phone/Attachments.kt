package app.mochi.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * A photo or file December attached, ready to send: the same `[IMAGE:<data-uri>]` / `[FILE:<data-uri>]` markers
 * the desktop app uses (the core saves the original and gives Claude the file). Photos are shrunk to 1600 px
 * JPEG first: phone photos are far bigger than a model needs, and the server takes 8 MB per image.
 */
data class Attachment(val name: String, val image: Boolean, val marker: String, val thumb: Bitmap?)

object Attachments {
    private const val MAX_SIDE = 1600
    private const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private val FILE_TYPES = setOf(
        "application/pdf", "text/plain", "text/csv", "text/markdown", "application/zip",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )

    /** Reads [uri] into an attachment; null when the type isn't supported or it's too big. Blocking. */
    fun load(c: Context, uri: Uri): Attachment? {
        val type = c.contentResolver.getType(uri) ?: return null
        val name = c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "file"
        return if (type.startsWith("image/")) {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val data = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            val thumb = Bitmap.createScaledBitmap(bmp, 160, (160f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
            Attachment(name, true, "[IMAGE:data:image/jpeg;base64,$data]", thumb)
        } else {
            if (type !in FILE_TYPES) return null
            val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.size > MAX_FILE_BYTES) return null
            Attachment(name, false, "[FILE:data:$type;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}]", null)
        }
    }

    /** The text to send: December's words, then the markers. */
    fun message(text: String, files: List<Attachment>): String {
        if (files.isEmpty()) return text
        val markers = files.joinToString(" ") { it.marker }
        return if (text.isBlank()) markers else "${text.trim()} $markers"
    }
}
