package com.krow.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.krow.Attach
import com.krow.Attachments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

private fun decodeImage(name: String, px: Int): ImageBitmap? = runCatching {
    val f = Attachments.file(name)
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (max(o.outWidth, o.outHeight) / s > px * 2) s *= 2
    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()

@Composable
fun ImageThumb(name: String, size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val p = LocalPal.current
    val bmp by produceState<ImageBitmap?>(null, name) { value = withContext(Dispatchers.IO) { decodeImage(name, 600) } }
    Box(
        modifier.size(size).clip(RoundedCornerShape(16.dp)).background(p.sunk).border(1.dp, p.line, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) { bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
}

@Composable
fun ImageViewer(name: String, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, name) { value = withContext(Dispatchers.IO) { decodeImage(name, 2400) } }
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .94f)), Alignment.Center) {
            bmp?.let {
                Image(
                    it, null,
                    Modifier.fillMaxSize().padding(8.dp)
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; off = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.5f }) }
                        .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            off = if (scale <= 1f) androidx.compose.ui.geometry.Offset.Zero else off + pan
                        } }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y),
                    contentScale = ContentScale.Fit,
                )
            }
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ViewerBtn("Save") {
                    val f = Attachments.file(name)
                    val u = if (f.exists()) saveJpegToGallery(ctx, f.readBytes(), "krow_${name.substringBeforeLast('.')}.jpg") else null
                    android.widget.Toast.makeText(ctx, if (u != null) "Saved to Pictures/Krow" else "Saving to the gallery needs Android 10+ — use Share", android.widget.Toast.LENGTH_SHORT).show()
                }
                ViewerBtn("Share") {
                    runCatching {
                        val dir = java.io.File(ctx.cacheDir, "share").also { it.mkdirs() }
                        val out = java.io.File(dir, name).also { Attachments.file(name).copyTo(it, overwrite = true) }
                        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
                        val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "image/jpeg"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        ctx.startActivity(android.content.Intent.createChooser(i, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f)).clickable(onClick = onClose), Alignment.Center) { Ic(Icons.Outlined.Close, 18, Color.White) }
            }
        }
    }
}

@Composable
private fun ViewerBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.height(38.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f)).clickable(onClick = onClick).padding(horizontal = 16.dp), Alignment.Center) { T(label, 13, Color.White, FontWeight.Medium) }
}

private fun sizeLabel(chars: Int): String = if (chars >= 1000) String.format("%.1f KB", chars / 1000.0) else "$chars B"

@Composable
fun FileChip(name: String, chars: Int, onRemove: (() -> Unit)? = null) {
    val p = LocalPal.current
    val ext = name.substringAfterLast('.', "txt").take(4).uppercase()
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(p.sunk).border(1.dp, p.line, RoundedCornerShape(16.dp))
            .padding(start = 8.dp, end = if (onRemove != null) 4.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(p.brand), Alignment.Center) { T(ext, 10, p.accentInk, FontWeight.Bold, Mono, lh = 1f) }
        Column(Modifier.widthIn(max = 150.dp)) {
            T(name, 13, weight = FontWeight.Medium, maxLines = 1)
            T(sizeLabel(chars), 11, p.muted, family = Mono)
        }
        if (onRemove != null) Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onRemove), Alignment.Center) { Ic(Icons.Outlined.Close, 14) }
    }
}

/** Pending attachments above the text field: image thumbnails and file chips. */
@Composable
fun AttachPreview(files: List<Attach>, onRemove: (Attach) -> Unit) {
    val p = LocalPal.current
    if (files.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        files.forEach { f ->
            if (f.image != null) Box {
                ImageThumb(f.image, 68.dp)
                Box(
                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = .6f)).clickable { onRemove(f) },
                    Alignment.Center,
                ) { Ic(Icons.Outlined.Close, 12, Color.White) }
            } else FileChip(f.name, f.text.length) { onRemove(f) }
        }
    }
}

/** Saves a JPEG to Pictures/Krow via MediaStore (Android 10+, no permission needed). Returns the content Uri or null. */
private fun saveJpegToGallery(ctx: android.content.Context, bytes: ByteArray, name: String): android.net.Uri? {
    if (android.os.Build.VERSION.SDK_INT < 29) return null
    return runCatching {
        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Krow")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        cv.clear(); cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        ctx.contentResolver.update(uri, cv, null, null)
        uri
    }.getOrNull()
}
