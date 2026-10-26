package com.krow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Stores chat images on disk (downscaled JPEG) and reads attachments from the system pickers. */
object Attachments {
    lateinit var dir: File
    const val MAX_TEXT = 400_000
    /** fast | balanced | high — set from Settings. Controls longest side and JPEG quality of images sent to the model. */
    @Volatile var profile: String = "balanced"
    private val maxSide get() = when (profile) { "fast" -> 1024; "high" -> 2048; else -> 1568 }
    private val jpegQ get() = when (profile) { "fast" -> 72; "high" -> 92; else -> 85 }

    fun init(ctx: Context) { dir = File(ctx.filesDir, "attachments").also { it.mkdirs() } }
    fun file(name: String) = File(dir, name)
    fun base64(name: String): String? = runCatching { Base64.encodeToString(file(name).readBytes(), Base64.NO_WRAP) }.getOrNull()
    fun delete(name: String) { runCatching { file(name).delete() } }

    /** Decode, fix rotation, downscale and save as JPEG. Returns the stored file name. */
    fun saveImage(ctx: Context, uri: Uri): String? = runCatching<String?> {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching null
        val orient = cr.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orient) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
        if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
        if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        if (bmp.hasAlpha()) {
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            bmp = flat
        }
        val name = "img_${uid()}.jpg"
        FileOutputStream(file(name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, jpegQ, it) }
        name
    }.getOrNull()

    /** Reads a picked file. Returns the attachment, or an error message to show the user. */
    fun read(ctx: Context, uri: Uri): Pair<Attach?, String?> {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri).orEmpty()
        val name = runCatching {
            cr.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null }
        }.getOrNull() ?: "file"
        if (mime.startsWith("image/")) {
            val saved = saveImage(ctx, uri) ?: return null to "Could not read $name as an image."
            return Attach(name, "", saved) to null
        }
        val out = ByteArrayOutputStream()
        try {
            cr.openInputStream(uri)?.use { ins ->
                val buf = ByteArray(8192); var total = 0
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    total += n
                    if (total > MAX_TEXT) return null to "$name is larger than 400 KB."
                    out.write(buf, 0, n)
                }
            } ?: return null to "Could not open $name."
        } catch (e: Exception) { return null to "Could not read $name: ${e.message}" }
        val bytes = out.toByteArray()
        if (bytes.any { it.toInt() == 0 }) return null to "$name looks like a binary file. Attach images or text / code files."
        return Attach(name, bytes.toString(Charsets.UTF_8)) to null
    }
}

/** The user's text plus any attached files, as sent to the model. */
fun Msg.apiText(): String {
    val b = StringBuilder(text.trim())
    for (f in files) {
        val fence = if (f.text.contains("```")) "~~~~" else "```"
        if (b.isNotEmpty()) b.append("\n\n")
        b.append("[Attached file: ").append(f.name).append("]\n").append(fence).append('\n').append(f.text).append('\n').append(fence)
    }
    return b.toString().ifBlank { if (images.isNotEmpty()) "(image attached)" else "(attachment)" }
}
