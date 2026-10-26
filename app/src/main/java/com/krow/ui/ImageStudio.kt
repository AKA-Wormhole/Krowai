package com.krow.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.krow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private class Pic(val uri: Uri, val name: String, val bytes: Long)

private fun describe(ctx: Context, uri: Uri): Pic {
    var name = "image"; var size = 0L
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
    }
    return Pic(uri, name, size)
}

private fun downscale(b: Bitmap, side: Int): Bitmap {
    val m = max(b.width, b.height)
    if (m <= side) return b
    val sc = side.toFloat() / m
    return Bitmap.createScaledBitmap(b, (b.width * sc).roundToInt().coerceAtLeast(1), (b.height * sc).roundToInt().coerceAtLeast(1), true)
}

private fun previewOf(bytes: ByteArray, side: Int = 1600): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1
    while (max(o.outWidth, o.outHeight) / (s * 2) >= side) s *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()

private fun shareFiles(ctx: Context, files: List<File>, format: String) {
    val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
    else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
    i.type = ImageTools.mime(format); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageStudioScreen(core: Core, project: Project, initial: List<Uri>, onBack: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pics = remember { mutableStateListOf<Pic>().apply { addAll(initial.map { describe(ctx, it) }) } }
    var sel by remember { mutableIntStateOf(0) }
    var o by remember { mutableStateOf(ImgOpts()) }
    var showOriginal by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { l ->
        if (l.isNotEmpty()) { pics.addAll(l.map { describe(ctx, it) }); if (pics.size == l.size) sel = 0 }
    }
    val cur = pics.getOrNull(sel)

    val base by produceState<Bitmap?>(null, cur?.uri) { value = null; value = cur?.let { c -> withContext(Dispatchers.IO) { ImageTools.decode(ctx, c.uri) } } }
    val shown = remember(base) { base?.let { downscale(it, 1600).asImageBitmap() } }
    var result by remember { mutableStateOf<ImgResult?>(null) }
    var resultPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(base, o) {
        val b = base ?: run { result = null; resultPreview = null; return@LaunchedEffect }
        delay(160)
        val r = withContext(Dispatchers.Default) { ImageTools.run(b, o) }
        result = r
        resultPreview = withContext(Dispatchers.Default) { previewOf(r.bytes) }
    }

    /** Runs the current settings on `targets` at full working resolution. */
    fun export(targets: List<Pic>, dest: String) {
        if (working) return
        working = true
        scope.launch {
            var ok = 0; var inB = 0L; var outB = 0L; val made = ArrayList<File>()
            for (t in targets) {
                val bmp = withContext(Dispatchers.IO) { ImageTools.decode(ctx, t.uri) } ?: continue
                val r = withContext(Dispatchers.Default) { ImageTools.run(bmp, o) }
                val name = ImageTools.nameWithExt(t.name, o.format)
                withContext(Dispatchers.IO) {
                    when (dest) {
                        "gallery" -> if (ImageTools.saveToGallery(ctx, r.bytes, name, o.format) != null) ok++
                        "project" -> {
                            val dir = File(core.projectDir(project), "images").also { it.mkdirs() }
                            var f = File(dir, name); var n = 2
                            while (f.exists()) { f = File(dir, name.substringBeforeLast('.') + "_$n." + name.substringAfterLast('.')); n++ }
                            f.writeBytes(r.bytes); ok++
                        }
                        else -> { val dir = File(ctx.cacheDir, "share").also { it.mkdirs() }; val f = File(dir, name); f.writeBytes(r.bytes); made += f; ok++ }
                    }
                }
                inB += t.bytes; outB += r.bytes.size
            }
            working = false
            if (dest == "share" && made.isNotEmpty()) shareFiles(ctx, made, o.format)
            val where = when (dest) { "gallery" -> "Pictures/Krow"; "project" -> "${project.name}/images"; else -> "" }
            core.say(
                if (ok == 0) (if (dest == "gallery" && android.os.Build.VERSION.SDK_INT < 29) "Saving to the gallery needs Android 10+. Use Share or Save to project." else "Nothing was saved")
                else "$ok image${if (ok > 1) "s" else ""} ${if (where.isNotEmpty()) "saved to $where" else "ready"} · ${ImageTools.humanSize(inB)} → ${ImageTools.humanSize(outB)}",
            )
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconBtn(Icons.Outlined.ArrowBack, "Back", onClick = onBack)
            T("Image Studio", 16, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconBtn(Icons.Outlined.AddPhotoAlternate, "Add images", tint = p.accent) { picker.launch("image/*") }
        }
        HLine()
        if (pics.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(32.dp)) {
                    T("Compress, resize, convert and edit images", 18, family = Read)
                    T("Pick one or many photos. Everything runs on this device. Location and camera metadata are removed.", 13, p.muted)
                    Pill("Choose images", filled = true) { picker.launch("image/*") }
                }
            }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // ---- preview
            Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(14.dp)).background(p.sunk).border(1.dp, p.line, RoundedCornerShape(14.dp)), Alignment.Center) {
                val img = if (showOriginal) shown else resultPreview ?: shown
                if (img != null) Image(img, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                else androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = p.accent, strokeWidth = 2.dp)
                if (working) Box(Modifier.fillMaxSize().background(p.bg.copy(alpha = .6f)), Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(26.dp), color = p.accent, strokeWidth = 2.dp) }
            }
            val r = result
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    if (r != null && cur != null) {
                        val before = if (cur.bytes > 0) cur.bytes else 0L
                        val pct = if (before > 0) 100 - (r.bytes.size * 100L / before) else 0
                        T(if (before > 0) "${ImageTools.humanSize(before)} → ${ImageTools.humanSize(r.bytes.size.toLong())}" else ImageTools.humanSize(r.bytes.size.toLong()), 15, weight = FontWeight.SemiBold)
                        T("${r.width}×${r.height} · ${o.format.uppercase()}${if (o.format != "png") " q${r.quality}" else ""}" + (if (before > 0) (if (pct >= 0) " · $pct% smaller" else " · ${-pct}% larger") else ""), 12, p.muted, family = Mono)
                    } else T("Working…", 13, p.muted)
                }
                Pill(if (showOriginal) "Original" else "Result", selected = showOriginal) { showOriginal = !showOriginal }
            }
            if (base != null && cur != null && max(base!!.width, base!!.height) >= ImageTools.MAX_DECODE)
                T("Photos larger than ${ImageTools.MAX_DECODE}px are scaled down to ${ImageTools.MAX_DECODE}px first.", 11, p.faint)

            // ---- batch strip
            if (pics.size > 1) Section("Batch · ${pics.size} images") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pics.forEachIndexed { i, pc ->
                        Column(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, if (i == sel) p.accent else p.line, RoundedCornerShape(10.dp)).clickable { sel = i }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            T(pc.name.take(18), 12, if (i == sel) p.accent else p.ink, FontWeight.Medium, maxLines = 1)
                            if (pc.bytes > 0) T(ImageTools.humanSize(pc.bytes), 10, p.faint, family = Mono)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Remove this") { pics.removeAt(sel); sel = sel.coerceAtMost((pics.size - 1).coerceAtLeast(0)) }
                    Pill("Clear all") { pics.clear(); sel = 0 }
                }
                T("The settings below apply to every image when you use “Save all” / “Share all”.", 12, p.muted)
            }

            // ---- output
            Section("Output") {
                Label("Format")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("jpeg" to "JPEG", "webp" to "WebP", "png" to "PNG (lossless)").forEach { (k, l) -> Pill(l, selected = o.format == k) { o = o.copy(format = k) } }
                }
                if (o.format != "png") {
                    Label("Quality · ${o.quality}")
                    Slider(o.quality.toFloat(), { o = o.copy(quality = it.roundToInt()) }, valueRange = 1f..100f, colors = sliderColors())
                } else T("PNG is lossless: use a smaller size or a target size to shrink it.", 12, p.muted)
                Label("Longest side")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Original", 3840 to "3840", 2560 to "2560", 1920 to "1920", 1280 to "1280", 1024 to "1024", 640 to "640").forEach { (v, l) -> Pill(l, selected = o.maxSide == v) { o = o.copy(maxSide = v) } }
                }
                Label("Target file size")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Off", 50 to "50 KB", 100 to "100 KB", 250 to "250 KB", 500 to "500 KB", 1000 to "1 MB", 2000 to "2 MB").forEach { (v, l) -> Pill(l, selected = o.targetKb == v) { o = o.copy(targetKb = v) } }
                }
                if (o.targetKb > 0) T("Finds the highest quality that fits; shrinks pixels only if it has to.", 12, p.muted)
            }

            // ---- edit
            Section("Edit") {
                Label("Rotate & flip")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("⟲ 90°") { o = o.copy(rotate = (o.rotate + 270) % 360) }
                    Pill("⟳ 90°") { o = o.copy(rotate = (o.rotate + 90) % 360) }
                    Pill("Flip ↔", selected = o.flipH) { o = o.copy(flipH = !o.flipH) }
                    Pill("Flip ↕", selected = o.flipV) { o = o.copy(flipV = !o.flipV) }
                }
                Label("Crop (centered)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("original" to "None", "1:1" to "1:1", "4:3" to "4:3", "3:4" to "3:4", "16:9" to "16:9", "9:16" to "9:16").forEach { (v, l) -> Pill(l, selected = o.crop == v) { o = o.copy(crop = v) } }
                }
                Label("Color")
                Pill("Black & white", selected = o.grayscale) { o = o.copy(grayscale = !o.grayscale) }
                Label("Brightness · ${(o.brightness * 100).roundToInt()}")
                Slider(o.brightness, { o = o.copy(brightness = it) }, valueRange = -0.5f..0.5f, colors = sliderColors())
                Label("Contrast · ${(o.contrast * 100).roundToInt()}%")
                Slider(o.contrast, { o = o.copy(contrast = it) }, valueRange = 0.5f..1.5f, colors = sliderColors())
                Label("Saturation · ${(o.saturation * 100).roundToInt()}%")
                Slider(o.saturation, { o = o.copy(saturation = it) }, valueRange = 0f..2f, colors = sliderColors(), enabled = !o.grayscale)
                Pill("Reset edits") { o = o.copy(rotate = 0, flipH = false, flipV = false, crop = "original", grayscale = false, brightness = 0f, contrast = 1f, saturation = 1f) }
            }

            // ---- export
            Section("Save") {
                val one = listOfNotNull(cur)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (pics.size > 1) "Save all to gallery" else "Save to gallery", filled = true) { export(pics.toList(), "gallery") }
                    Pill(if (pics.size > 1) "Share all" else "Share") { export(pics.toList(), "share") }
                    Pill("Save to ${project.name}/images") { export(pics.toList(), "project") }
                    if (pics.size > 1) Pill("Only this one → gallery") { export(one, "gallery") }
                }
                T("Originals are never changed. The output is a new file named …_krow.${ImageTools.ext(o.format)}.", 12, p.muted)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun sliderColors(): SliderColors {
    val p = LocalPal.current
    return SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.line2)
}
private typealias SliderColors = androidx.compose.material3.SliderColors
