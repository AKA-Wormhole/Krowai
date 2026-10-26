package com.krow

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Everything the image compressor / editor can do. Defaults = "no change except re-encode". */
data class ImgOpts(
    val format: String = "jpeg",      // jpeg | webp | png
    val quality: Int = 80,            // 1..100 (ignored for png)
    val maxSide: Int = 0,             // 0 = keep size, otherwise longest side in px
    val targetKb: Int = 0,            // 0 = off; otherwise find the best quality that fits (shrinks pixels if needed)
    val rotate: Int = 0,              // 0 / 90 / 180 / 270 clockwise
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val crop: String = "original",    // original | 1:1 | 4:3 | 3:4 | 16:9 | 9:16 (centered)
    val grayscale: Boolean = false,
    val brightness: Float = 0f,       // -1..1
    val contrast: Float = 1f,         // 0..2
    val saturation: Float = 1f,       // 0..2
)

class ImgResult(val bytes: ByteArray, val width: Int, val height: Int, val format: String, val quality: Int)

object ImageTools {
    /** Longest side we ever decode. Bigger photos are scaled down to this first (keeps memory safe on phones). */
    const val MAX_DECODE = 4096

    fun ext(format: String) = when (format) { "png" -> "png"; "webp" -> "webp"; else -> "jpg" }
    fun mime(format: String) = when (format) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }

    @Suppress("DEPRECATION")
    private fun cf(format: String): Bitmap.CompressFormat = when (format) {
        "png" -> Bitmap.CompressFormat.PNG
        "webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }

    // ---------------------------------------------------------------- decoding
    private fun sampleFor(w: Int, h: Int, limit: Int): Int { var s = 1; while (max(w, h) / (s * 2) >= limit) s *= 2; return s }

    private fun fixRotation(bmp: Bitmap, orient: Int): Bitmap {
        val m = Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun finish(bmp: Bitmap, orient: Int, limit: Int): Bitmap {
        var b = fixRotation(bmp, orient)
        val side = max(b.width, b.height)
        if (side > limit) {
            val sc = limit.toFloat() / side
            b = Bitmap.createScaledBitmap(b, (b.width * sc).roundToInt().coerceAtLeast(1), (b.height * sc).roundToInt().coerceAtLeast(1), true)
        }
        return b
    }

    /** Decodes a picked image (EXIF orientation applied, longest side <= limit). Returns null if it isn't an image. */
    fun decode(ctx: Context, uri: Uri, limit: Int = MAX_DECODE): Bitmap? = runCatching<Bitmap?> {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return@runCatching null
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, limit) }) } ?: return@runCatching null
        val orient = cr.openInputStream(uri)?.use { runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1) } ?: 1
        finish(bmp, orient, limit)
    }.getOrNull()

    fun decodeFile(f: File, limit: Int = MAX_DECODE): Bitmap? = runCatching<Bitmap?> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return@runCatching null
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, limit) }) ?: return@runCatching null
        val orient = runCatching { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        finish(bmp, orient, limit)
    }.getOrNull()

    fun size(f: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return if (o.outWidth > 0) o.outWidth to o.outHeight else null
    }

    // ---------------------------------------------------------------- processing
    private fun cropRect(w: Int, h: Int, mode: String): android.graphics.Rect? {
        val (a, b) = when (mode) { "1:1" -> 1 to 1; "4:3" -> 4 to 3; "3:4" -> 3 to 4; "16:9" -> 16 to 9; "9:16" -> 9 to 16; else -> return null }
        val target = a.toFloat() / b
        val cur = w.toFloat() / h
        return if (cur > target) { val nw = (h * target).roundToInt().coerceAtLeast(1); val x = (w - nw) / 2; android.graphics.Rect(x, 0, x + nw, h) }
        else { val nh = (w / target).roundToInt().coerceAtLeast(1); val y = (h - nh) / 2; android.graphics.Rect(0, y, w, y + nh) }
    }

    /** Geometry + color. Returns a NEW bitmap (never mutates src). Alpha is flattened on white for JPEG. */
    fun process(src: Bitmap, o: ImgOpts): Bitmap {
        var b = src
        cropRect(b.width, b.height, o.crop)?.let { r -> b = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height()) }
        if (o.rotate % 360 != 0 || o.flipH || o.flipV) {
            val m = Matrix()
            if (o.rotate % 360 != 0) m.postRotate((o.rotate % 360).toFloat())
            m.postScale(if (o.flipH) -1f else 1f, if (o.flipV) -1f else 1f)
            b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
        if (o.maxSide > 0 && max(b.width, b.height) > o.maxSide) b = scaleTo(b, o.maxSide)
        val needColor = o.grayscale || o.brightness != 0f || o.contrast != 1f || o.saturation != 1f
        val flatten = o.format == "jpeg" && b.hasAlpha()
        if (needColor || flatten) {
            val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            if (flatten) c.drawColor(Color.WHITE)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            if (needColor) {
                val cm = ColorMatrix().apply { setSaturation(if (o.grayscale) 0f else o.saturation) }
                val k = o.contrast; val t = o.brightness * 255f + 128f * (1f - k)
                cm.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
                paint.colorFilter = ColorMatrixColorFilter(cm)
            }
            c.drawBitmap(b, 0f, 0f, paint)
            b = out
        }
        return b
    }

    private fun scaleTo(b: Bitmap, side: Int): Bitmap {
        val sc = side.toFloat() / max(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * sc).roundToInt().coerceAtLeast(1), (b.height * sc).roundToInt().coerceAtLeast(1), true)
    }

    private fun enc(b: Bitmap, format: String, q: Int): ByteArray {
        val out = ByteArrayOutputStream(max(16_384, b.byteCount / 8))
        b.compress(cf(format), q.coerceIn(1, 100), out)
        return out.toByteArray()
    }

    /**
     * Encodes with the options. With targetKb > 0 it binary-searches the highest quality that fits; if even the
     * lowest quality is too big (or the format is PNG) it shrinks the pixels step by step until it fits.
     */
    fun encode(processed: Bitmap, o: ImgOpts): ImgResult {
        if (o.targetKb <= 0) return ImgResult(enc(processed, o.format, o.quality), processed.width, processed.height, o.format, o.quality)
        val limit = o.targetKb * 1024
        var b = processed
        repeat(10) {
            if (o.format != "png") {
                var lo = 5; var hi = o.quality.coerceIn(5, 100)
                var best: ByteArray? = null; var bestQ = lo
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    val d = enc(b, o.format, mid)
                    if (d.size <= limit) { best = d; bestQ = mid; lo = mid + 1 } else hi = mid - 1
                }
                if (best != null) return ImgResult(best, b.width, b.height, o.format, bestQ)
            } else {
                val d = enc(b, "png", 100)
                if (d.size <= limit) return ImgResult(d, b.width, b.height, "png", 100)
            }
            b = scaleTo(b, (max(b.width, b.height) * 0.85f).toInt().coerceAtLeast(16))
        }
        return ImgResult(enc(b, o.format, 5), b.width, b.height, o.format, 5)
    }

    fun run(src: Bitmap, o: ImgOpts): ImgResult = encode(process(src, o), o)

    // ---------------------------------------------------------------- saving / sharing
    /** Saves to Pictures/Krow via MediaStore (Android 10+, no permission needed). Returns the content Uri or null. */
    fun saveToGallery(ctx: Context, bytes: ByteArray, name: String, format: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime(format))
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Krow")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, cv, null, null)
            uri
        }.getOrNull()
    }

    fun humanSize(n: Long): String = when {
        n >= 1_048_576 -> String.format("%.2f MB", n / 1_048_576.0)
        n >= 1024 -> String.format("%.1f KB", n / 1024.0)
        else -> "$n B"
    }

    fun nameWithExt(base: String, format: String): String = base.substringBeforeLast('.').ifBlank { "image" } + "_krow." + ext(format)
}
