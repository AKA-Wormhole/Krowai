package com.krow.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.krow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun mimeOf(name: String): String =
    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: when (name.substringAfterLast('.', "").lowercase()) {
            "kt", "kts", "java", "py", "js", "ts", "md", "gradle", "yml", "yaml", "sh", "properties", "toml" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }

fun shareFile(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val mime = mimeOf(f.name)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, f.name)
        clipData = android.content.ClipData.newRawUri(f.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Share " + f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

class SavedDownload(val name: String, val uri: Uri?)

fun saveToDownloads(ctx: Context, src: File, displayName: String = src.name): String? = saveToDownloadsEx(ctx, src, displayName)?.name

fun shareUri(ctx: Context, uri: Uri, name: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = mimeOf(name)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, name)
        clipData = android.content.ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Share $name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Copies a file into the phone's public Downloads folder (shows up in the system Downloads app). Returns the final name and uri, or null on failure. */
fun saveToDownloadsEx(ctx: Context, src: File, displayName: String = src.name): SavedDownload? {
    val name = displayName.replace(Regex("[\\/:*?\"<>|]"), "_").ifBlank { src.name }
    val mime = mimeOf(name)
    return try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
            cr.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            cv.clear(); cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            cr.update(uri, cv, null, null)
            SavedDownload(name, uri)
        } else {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).also { it.mkdirs() }
            var t = File(dir, name)
            var k = 1
            while (t.exists()) { t = File(dir, name.substringBeforeLast('.', name) + " ($k)" + (if (name.contains('.')) "." + name.substringAfterLast('.') else "")); k++ }
            src.copyTo(t)
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).addCompletedDownload(t.name, t.name, true, mime, t.absolutePath, t.length(), true)
            SavedDownload(t.name, null)
        }
    } catch (e: Throwable) { null }
}

fun openDownloads(ctx: Context) {
    runCatching { ctx.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Zips a folder (without Krow's metadata folder) into out. Blocking; call off the main thread. */
fun zipFolder(src: File, out: File) {
    out.parentFile?.mkdirs()
    val base = src.canonicalFile
    ZipOutputStream(FileOutputStream(out).buffered()).use { z ->
        base.walkTopDown().onEnter { !(it.parentFile == base && it.name == META_DIR) }.forEach { f ->
            if (f == base) return@forEach
            val name = f.relativeTo(base).path.replace(File.separatorChar, '/')
            if (f.isDirectory) { z.putNextEntry(ZipEntry("$name/")); z.closeEntry() }
            else { z.putNextEntry(ZipEntry(name).also { it.time = f.lastModified() }); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
    }
}

private fun fmtDate(t: Long) = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(t))

@Composable
fun FilesScreen(core: Core, project: Project, onBack: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember(project.id) { core.projectDir(project).canonicalFile }
    var cwd by rememberSaveable(project.id) { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    val dir = remember(cwd, tick) { runCatching { resolveIn(root, cwd.ifEmpty { "." }) }.getOrNull()?.takeIf { it.isDirectory } ?: root }
    val entries = remember(dir, tick) {
        (dir.listFiles()?.toList() ?: emptyList()).filter { !(dir == root && it.name == META_DIR) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }
    fun here() = dir.relativeTo(root).path.replace(File.separatorChar, '/')
    BackHandler(enabled = dir != root) { cwd = dir.parentFile?.relativeTo(root)?.path?.replace(File.separatorChar, '/') ?: "" }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) scope.launch {
            val n = withContext(Dispatchers.IO) {
                var count = 0
                for (u in uris) runCatching {
                    val name = ctx.contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null } ?: "file"
                    var t = File(dir, name.replace('/', '_'))
                    var k = 1
                    while (t.exists()) { t = File(dir, name.substringBeforeLast('.', name) + " ($k)" + (if (name.contains('.')) "." + name.substringAfterLast('.') else "")); k++ }
                    ctx.contentResolver.openInputStream(u)!!.use { i -> t.outputStream().use { o -> i.copyTo(o) } }
                    count++
                }
                count
            }
            core.say("Imported $n file${if (n == 1) "" else "s"}")
            tick++
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconBtn(Icons.Outlined.ArrowBack, "Back") { if (dir != root) cwd = dir.parentFile?.relativeTo(root)?.path?.replace(File.separatorChar, '/') ?: "" else onBack() }
            Column(Modifier.weight(1f)) {
                T("Artifacts", 16, weight = FontWeight.Medium)
                T(project.name + "/" + here(), 11, p.muted, family = Mono, maxLines = 1)
            }
            Box {
                IconBtn(Icons.Outlined.MoreVert, "More") { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = p.raised, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, p.line2)) {
                    DropdownMenuItem({ T("New file", 14) }, { menu = false; newFile = true })
                    DropdownMenuItem({ T("New folder", 14) }, { menu = false; newFolder = true })
                    DropdownMenuItem({ T("Import from device", 14) }, { menu = false; importer.launch(arrayOf("*/*")) })
                    DropdownMenuItem({ T("Share folder as ZIP", 14) }, {
                        menu = false
                        scope.launch {
                            runCatching {
                                val out = File(ctx.cacheDir, "share/" + (if (dir == root) project.name else dir.name).replace(Regex("[^A-Za-z0-9._-]"), "_") + ".zip")
                                withContext(Dispatchers.IO) { zipFolder(dir, out) }
                                shareFile(ctx, out)
                            }.onFailure { core.say("Could not create ZIP: ${it.message}") }
                        }
                    })
                }
            }
        }
        HLine()
        if (project.kind == "repo") { GitBar(core, project, tick) { tick++ }; HLine() }
        if (entries.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), Alignment.Center) {
            T("This folder is empty. Ask Krow to create files, or import some from your device.", 14, p.muted)
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            items(entries, key = { it.path }) { f ->
                var m by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { if (f.isDirectory) cwd = f.relativeTo(root).path.replace(File.separatorChar, '/') else viewing = f }
                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(p.sunk).border(1.dp, p.line, RoundedCornerShape(8.dp)), Alignment.Center) {
                        Ic(if (f.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description, 16, if (f.isDirectory) p.accent else p.muted)
                    }
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        T(f.name, 14, weight = FontWeight.Medium, maxLines = 1)
                        T(if (f.isDirectory) "${f.list()?.size ?: 0} items" else humanSize(f.length()) + "  ·  " + fmtDate(f.lastModified()), 11, p.muted, family = Mono, maxLines = 1)
                    }
                    Box {
                        IconBtn(Icons.Outlined.MoreVert, "More", 36) { m = true }
                        DropdownMenu(m, { m = false }, containerColor = p.raised, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, p.line2)) {
                            DropdownMenuItem({ T("Rename", 14) }, { m = false; renaming = f })
                            if (!f.isDirectory) DropdownMenuItem({ T("Download", 14) }, {
                                m = false
                                core.say(saveToDownloads(ctx, f)?.let { "Saved to Downloads: $it" } ?: "Could not save to Downloads")
                            })
                            DropdownMenuItem({ T("Share", 14) }, {
                                m = false
                                scope.launch {
                                    runCatching {
                                        if (f.isDirectory) {
                                            val out = File(ctx.cacheDir, "share/" + f.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".zip")
                                            withContext(Dispatchers.IO) { zipFolder(f, out) }
                                            shareFile(ctx, out)
                                        } else shareFile(ctx, f)
                                    }.onFailure { core.say("Could not share: ${it.message}") }
                                }
                            })
                            DropdownMenuItem({ T("Delete", 14, p.bad) }, { m = false; deleting = f })
                        }
                    }
                }
            }
        }
    }

    if (newFolder) TextDialog("New folder", "", "Folder name", "Create", { newFolder = false }) { n ->
        newFolder = false
        runCatching { val t = resolveIn(root, (here() + "/" + n).trim('/')); if (t.exists()) error("Already exists"); if (!t.mkdirs()) error("Could not create folder"); Unit }
            .onFailure { core.say(it.message ?: "Failed") }
        tick++
    }
    if (newFile) TextDialog("New file", "", "File name, e.g. notes.md", "Create", { newFile = false }) { n ->
        newFile = false
        runCatching { val t = resolveIn(root, (here() + "/" + n).trim('/')); if (t.exists()) error("Already exists"); t.parentFile?.mkdirs(); t.createNewFile(); Unit }
            .onFailure { core.say(it.message ?: "Failed") }
        tick++
    }
    renaming?.let { f -> TextDialog("Rename", f.name, "New name", "Rename", { renaming = null }) { n ->
        renaming = null
        runCatching {
            val t = resolveIn(root, (here() + "/" + n).trim('/'))
            if (t.exists()) error("“$n” already exists"); if (!f.renameTo(t)) error("Rename failed"); Unit
        }.onFailure { core.say(it.message ?: "Failed") }
        tick++
    } }
    deleting?.let { f -> ConfirmDialog("Delete “${f.name}”?", if (f.isDirectory) "This deletes the folder and everything inside it." else "This deletes the file from the project.", "Delete", { deleting = null }) {
        deleting = null
        scope.launch { withContext(Dispatchers.IO) { f.deleteRecursively() }; tick++ }
    } }
    viewing?.let { f -> FileViewer(core, f, { viewing = null }) { tick++ } }
}

@Composable
private fun FileViewer(core: Core, f: File, onClose: () -> Unit, onSaved: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val big = f.length() > 400_000
    val binary = remember(f) { !big && runCatching { isBinaryFile(f) }.getOrDefault(true) }
    val original = remember(f) { if (big || binary) "" else runCatching { f.readText() }.getOrDefault("") }
    var text by remember(f) { mutableStateOf(original) }
    var editing by remember(f) { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconBtn(Icons.Outlined.Close, "Close", onClick = onClose)
                Column(Modifier.weight(1f)) { T(f.name, 15, weight = FontWeight.Medium, maxLines = 1); T(humanSize(f.length()), 11, p.muted, family = Mono) }
                if (!big && !binary) {
                    IconBtn(Icons.Outlined.ContentCopy, "Copy") { clip.setText(AnnotatedString(text)); core.say("Copied") }
                    if (editing) Pill("Save", filled = true) {
                        runCatching { f.writeText(text) }.onSuccess { editing = false; core.say("Saved"); onSaved() }.onFailure { core.say("Could not save: ${it.message}") }
                    } else Pill("Edit", icon = Icons.Outlined.Edit) { editing = true }
                }
                IconBtn(Icons.Outlined.Download, "Download") { core.say(saveToDownloads(ctx, f)?.let { "Saved to Downloads: $it" } ?: "Could not save to Downloads") }
                IconBtn(Icons.Outlined.Share, "Share") { runCatching { shareFile(ctx, f) } }
            }
            HLine()
            if (big || binary) Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                T(if (binary) "This is a binary file, so it can't be shown as text. You can still share it." else "This file is larger than 400 KB. Share it to open it elsewhere, or ask Krow to read parts of it.", 14, p.muted)
            } else BasicTextField(
                text, { text = it }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), readOnly = !editing,
                textStyle = TextStyle(color = p.ink, fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 19.sp), cursorBrush = SolidColor(p.accent),
            )
        }
    }
}
