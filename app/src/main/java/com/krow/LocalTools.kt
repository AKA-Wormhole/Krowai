package com.krow

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Folder inside every project that Krow reserves for its own metadata (git state). Tools never expose it. */
const val META_DIR = ".krow"

/** Resolves a project-relative path. Anything that escapes the project folder (.., absolute paths, symlinks) is refused. */
fun resolveIn(dir: File, rel: String?): File {
    val base = dir.canonicalFile
    val clean = (rel ?: ".").trim().ifEmpty { "." }.replace('\\', '/').trimStart('/')
    val f = File(base, clean).canonicalFile
    if (f != base && !f.path.startsWith(base.path + File.separator)) throw IllegalArgumentException("Path is outside the project folder: ${rel ?: "."}")
    if (f != base && f.relativeTo(base).path.split(File.separatorChar).first() == META_DIR) throw IllegalArgumentException("$META_DIR is reserved by Krow.")
    return f
}

fun relTo(dir: File, f: File): String = f.relativeTo(dir.canonicalFile).path.replace(File.separatorChar, '/').ifEmpty { "." }

fun isBinaryFile(f: File): Boolean {
    f.inputStream().use { s ->
        val b = ByteArray(8000)
        val n = s.read(b)
        for (i in 0 until maxOf(n, 0)) if (b[i].toInt() == 0) return true
    }
    return false
}

fun humanSize(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> String.format("%.1f KB", n / 1024.0)
    n < 1024L * 1024 * 1024 -> String.format("%.1f MB", n / 1048576.0)
    else -> String.format("%.2f GB", n / 1073741824.0)
}

internal fun ToolCtx.res(rel: String?) = resolveIn(dir, rel)
internal fun JsonObject.req(k: String): String = str(k) ?: throw IllegalArgumentException("Missing required argument '$k'")
private fun arrayProp(desc: String) = buildJsonObject { put("type", "array"); put("description", desc); putJsonObject("items") { put("type", "string") } }

private const val MAX_READ_BYTES = 2_000_000L

// ---------------------------------------------------------------------------------------------------- list_files
class ListFiles : Tool {
    override val spec = ToolSpec("list_files", "List files and folders in the project. Folders end with '/'.",
        schema(emptyList(), "path" to prop("string", "Folder relative to the project root. Default '.'"),
            "recursive" to prop("boolean", "List subfolders too (max depth 8, max 500 entries). Default false.")))
    override val kind = "folder"
    override fun title(a: JsonObject) = if (a.bool("recursive") == true) "List files (recursive)" else "List files"
    override fun cmd(a: JsonObject) = a.str("path") ?: "."
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val root = c.res(a.str("path"))
        if (!root.exists()) return ToolOut("Not found: ${a.str("path") ?: "."}", false)
        if (!root.isDirectory) return ToolOut("Not a folder: ${a.str("path")}", false)
        val rec = a.bool("recursive") == true
        val base = c.dir.canonicalFile
        val sb = StringBuilder(); var n = 0; var more = false
        fun walk(d: File, depth: Int) {
            val kids = d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (k in kids) {
                if (d == base && k.name == META_DIR) continue
                if (n >= 500) { more = true; return }
                n++
                val r = relTo(base, k)
                if (k.isDirectory) { sb.append(r).append("/\n"); if (rec && depth < 8) walk(k, depth + 1) }
                else sb.append(r).append("  (").append(humanSize(k.length())).append(")\n")
            }
        }
        walk(root, 0)
        if (n == 0) return ToolOut("(empty folder)")
        if (more) sb.append("… more entries not shown (limit 500). List a subfolder.\n")
        return ToolOut(sb.toString().trimEnd())
    }
}

// ---------------------------------------------------------------------------------------------------- read_file
class ReadFile : Tool {
    override val spec = ToolSpec("read_file", "Read a text file. Output lines are prefixed with their line number and a tab (the prefix is not part of the file).",
        schema(listOf("path"), "path" to prop("string", "File path relative to the project root"),
            "offset" to prop("integer", "First line to read (1-based). Default 1."),
            "limit" to prop("integer", "Max lines to read. Default 2000.")))
    override val kind = "file"
    override fun title(a: JsonObject) = "Read " + (a.str("path")?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { null } ?: "file")
    override fun cmd(a: JsonObject) = a.str("path") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val f = c.res(a.req("path"))
        if (!f.exists()) return ToolOut("Not found: ${a.str("path")}", false)
        if (f.isDirectory) return ToolOut("That is a folder; use list_files.", false)
        if (f.length() > MAX_READ_BYTES) return ToolOut("File is ${humanSize(f.length())}, too large to read whole. Use search_files or run_command (e.g. head/sed) instead.", false)
        if (isBinaryFile(f)) return ToolOut("Binary file (${humanSize(f.length())}); cannot show as text.", false)
        val all = f.readText().split("\n").let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
        val off = maxOf(1, a.int("offset") ?: 1)
        val lim = (a.int("limit") ?: 2000).coerceIn(1, 5000)
        if (off > all.size && all.isNotEmpty()) return ToolOut("File has only ${all.size} lines.", false)
        val sb = StringBuilder()
        var i = off - 1
        while (i < all.size && i < off - 1 + lim) { sb.append(i + 1).append('\t').append(all[i]).append('\n'); i++ }
        if (i < all.size) sb.append("… ${all.size - i} more lines (total ${all.size}). Use offset=${i + 1} to continue.\n")
        if (all.isEmpty()) return ToolOut("(empty file)")
        return ToolOut(sb.toString().trimEnd('\n'))
    }
}

// ---------------------------------------------------------------------------------------------------- write_file
class WriteFile : Tool {
    override val spec = ToolSpec("write_file", "Create a file or completely overwrite an existing one. Parent folders are created automatically.",
        schema(listOf("path", "content"), "path" to prop("string", "File path relative to the project root"), "content" to prop("string", "Full file content")))
    override val kind = "edit"
    override val mutating = true
    override fun title(a: JsonObject) = "Write " + (a.str("path")?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { null } ?: "file")
    override fun cmd(a: JsonObject) = a.str("path") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val path = a.req("path"); val content = a.str("content") ?: throw IllegalArgumentException("Missing required argument 'content'")
        val f = c.res(path)
        if (f.isDirectory) return ToolOut("That path is a folder.", false)
        val existed = f.exists()
        val old = if (existed && f.length() < MAX_READ_BYTES && !isBinaryFile(f)) f.readText() else null
        f.parentFile?.mkdirs()
        f.writeText(content)
        val head = (if (existed) "Overwrote " else "Created ") + relTo(c.dir, f) + " (${humanSize(f.length())})"
        val d = if (old != null) Diff.unified(relTo(c.dir, f), old, content) else ""
        if (d.isEmpty()) return ToolOut(if (existed && old == content) "$head — content unchanged." else head)
        val (ad, de) = Diff.counts(d)
        return ToolOut("$head  +$ad −$de\n\n$d")
    }
}

// ---------------------------------------------------------------------------------------------------- rename_path
class RenamePath : Tool {
    override val spec = ToolSpec("rename_path", "Rename or move a file or folder inside the project. Fails if the destination exists.",
        schema(listOf("from", "to"), "from" to prop("string", "Existing path"), "to" to prop("string", "New path")))
    override val kind = "file"
    override val mutating = true
    override fun title(a: JsonObject) = "Rename"
    override fun cmd(a: JsonObject) = "${a.str("from") ?: ""} → ${a.str("to") ?: ""}"
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val src = c.res(a.req("from")); val dst = c.res(a.req("to"))
        if (src == c.dir.canonicalFile) return ToolOut("Cannot rename the project root.", false)
        if (!src.exists()) return ToolOut("Not found: ${a.str("from")}", false)
        if (dst.exists()) return ToolOut("Destination already exists: ${a.str("to")}", false)
        if (src.isDirectory && dst.path.startsWith(src.path + File.separator)) return ToolOut("Cannot move a folder into itself.", false)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) return ToolOut("Rename failed.", false)
        return ToolOut("Renamed ${relTo(c.dir, src)} → ${relTo(c.dir, dst)}")
    }
}

// ---------------------------------------------------------------------------------------------------- delete_path
class DeletePath : Tool {
    override val spec = ToolSpec("delete_path", "Delete a file, or a folder with recursive=true. Cannot be undone (unless the project is a git repo and the file is committed or restorable).",
        schema(listOf("path"), "path" to prop("string", "Path to delete"), "recursive" to prop("boolean", "Required to delete a non-empty folder")))
    override val kind = "file"
    override val mutating = true
    override fun title(a: JsonObject) = "Delete"
    override fun cmd(a: JsonObject) = a.str("path") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val f = c.res(a.req("path"))
        if (f == c.dir.canonicalFile) return ToolOut("Refusing to delete the project root.", false)
        if (!f.exists()) return ToolOut("Not found: ${a.str("path")}", false)
        if (f.isDirectory) {
            val nonEmpty = (f.list()?.size ?: 0) > 0
            if (nonEmpty && a.bool("recursive") != true) return ToolOut("Folder is not empty. Set recursive=true to delete it and everything inside.", false)
            val n = f.walkTopDown().count { it.isFile }
            if (!f.deleteRecursively()) return ToolOut("Could not delete everything in the folder.", false)
            return ToolOut("Deleted folder ${relTo(c.dir, f)} ($n files)")
        }
        if (!f.delete()) return ToolOut("Could not delete the file.", false)
        return ToolOut("Deleted ${relTo(c.dir, f)}")
    }
}

// ---------------------------------------------------------------------------------------------------- make_dir
class MakeDir : Tool {
    override val spec = ToolSpec("make_dir", "Create a folder (and any missing parents).", schema(listOf("path"), "path" to prop("string", "Folder path relative to the project root")))
    override val kind = "folder"
    override val mutating = true
    override fun title(a: JsonObject) = "Create folder"
    override fun cmd(a: JsonObject) = a.str("path") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val f = c.res(a.req("path"))
        if (f.isDirectory) return ToolOut("Folder already exists: ${relTo(c.dir, f)}")
        if (f.exists()) return ToolOut("A file with that name exists.", false)
        if (!f.mkdirs()) return ToolOut("Could not create the folder.", false)
        return ToolOut("Created folder ${relTo(c.dir, f)}/")
    }
}

// ---------------------------------------------------------------------------------------------------- search_files
class SearchFiles : Tool {
    override val spec = ToolSpec("search_files", "Search file contents with a regular expression (Java regex). Returns path:line:text. Skips binary and very large files.",
        schema(listOf("pattern"), "pattern" to prop("string", "Regular expression"),
            "path" to prop("string", "File or folder to search. Default '.'"),
            "glob" to prop("string", "Only files whose name matches, e.g. *.kt or src/**/*.ts"),
            "ignore_case" to prop("boolean", "Case-insensitive. Default false.")))
    override val kind = "search"
    override fun title(a: JsonObject) = "Search"
    override fun cmd(a: JsonObject) = a.str("pattern") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val rx = try { Regex(a.req("pattern"), if (a.bool("ignore_case") == true) setOf(RegexOption.IGNORE_CASE) else emptySet()) }
        catch (e: Exception) { return ToolOut("Invalid regex: ${e.message?.lineSequence()?.firstOrNull()}", false) }
        val root = c.res(a.str("path"))
        if (!root.exists()) return ToolOut("Not found: ${a.str("path")}", false)
        val base = c.dir.canonicalFile
        val globRx = a.str("glob")?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        val matchPath = a.str("glob")?.contains('/') == true
        val sb = StringBuilder(); var hits = 0; var files = 0
        val seq = if (root.isFile) sequenceOf(root) else root.walkTopDown().onEnter { !(it.parentFile == base && it.name == META_DIR) && it.name != ".git" }.filter { it.isFile }
        outer@ for (f in seq) {
            currentCoroutineContext().ensureActive()
            if (f.length() > 1_000_000) continue
            val r = relTo(base, f)
            if (globRx != null && !globRx.matches(if (matchPath) r else f.name)) continue
            if (isBinaryFile(f)) continue
            var matched = false
            f.bufferedReader().useLines { lines ->
                var ln = 0
                for (line in lines) {
                    ln++
                    if (rx.containsMatchIn(line)) {
                        matched = true; hits++
                        sb.append(r).append(':').append(ln).append(':').append(line.take(300)).append('\n')
                        if (hits >= 200) break
                    }
                }
            }
            if (matched) files++
            if (hits >= 200) { sb.append("… stopped at 200 matches\n"); break@outer }
        }
        if (hits == 0) return ToolOut("No matches.")
        sb.append("\n$hits match${if (hits == 1) "" else "es"} in $files file${if (files == 1) "" else "s"}")
        return ToolOut(sb.toString())
    }

    private fun globToRegex(g: String): Regex {
        val sb = StringBuilder(); var i = 0
        while (i < g.length) {
            val ch = g[i]
            when {
                ch == '*' && i + 1 < g.length && g[i + 1] == '*' -> { sb.append(".*"); i++ }
                ch == '*' -> sb.append("[^/]*")
                ch == '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(ch.toString()))
            }
            i++
        }
        return Regex(sb.toString())
    }
}

// ---------------------------------------------------------------------------------------------------- zip_create
class ZipCreate : Tool {
    override val spec = ToolSpec("zip_create", "Create a ZIP archive from files/folders in the project.",
        schema(listOf("output"), "sources" to arrayProp("Paths to include. Default: the whole project."),
            "output" to prop("string", "Output zip path, e.g. build/app.zip")))
    override val kind = "zip"
    override val mutating = true
    override fun title(a: JsonObject) = "Create ZIP"
    override fun cmd(a: JsonObject) = a.str("output") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        var outPath = a.req("output"); if (!outPath.endsWith(".zip", true)) outPath += ".zip"
        val out = c.res(outPath)
        if (out.isDirectory) return ToolOut("Output path is a folder.", false)
        val base = c.dir.canonicalFile
        val srcs = (a.get("sources") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it.isNotBlank() }?.ifEmpty { null } ?: listOf(".")
        val roots = srcs.map { c.res(it).also { f -> if (!f.exists()) return ToolOut("Not found: $it", false) } }
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".part")
        var files = 0; var dirs = 0
        val seen = HashSet<String>()
        ZipOutputStream(FileOutputStream(tmp).buffered()).use { z ->
            fun add(f: File) {
                if (f == base) return
                val name = relTo(base, f)
                if (f == out || f == tmp || name == META_DIR || name.startsWith("$META_DIR/")) return
                if (!seen.add(name)) return
                if (f.isDirectory) { z.putNextEntry(ZipEntry("$name/")); z.closeEntry(); dirs++ }
                else { z.putNextEntry(ZipEntry(name).also { it.time = f.lastModified() }); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); files++ }
            }
            for (r in roots) {
                if (r.isDirectory) r.walkTopDown().forEach { add(it) } else add(r)
            }
        }
        if (out.exists()) out.delete()
        tmp.renameTo(out)
        return ToolOut("Created ${relTo(base, out)}: $files files, $dirs folders, ${humanSize(out.length())}")
    }
}

// ---------------------------------------------------------------------------------------------------- unzip
class Unzip : Tool {
    override val spec = ToolSpec("unzip", "Extract a ZIP archive that is inside the project into a folder inside the project. Existing files are overwritten.",
        schema(listOf("path"), "path" to prop("string", "Zip file path"), "dest" to prop("string", "Destination folder. Default '.'")))
    override val kind = "zip"
    override val mutating = true
    override fun title(a: JsonObject) = "Extract ZIP"
    override fun cmd(a: JsonObject) = "${a.str("path") ?: ""} → ${a.str("dest") ?: "."}"
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val zip = c.res(a.req("path")); val dest = c.res(a.str("dest"))
        if (!zip.isFile) return ToolOut("Not a file: ${a.str("path")}", false)
        if (dest.exists() && !dest.isDirectory) return ToolOut("Destination is a file.", false)
        dest.mkdirs()
        val d = dest.canonicalFile
        val base = c.dir.canonicalFile
        var files = 0; var bytes = 0L
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                currentCoroutineContext().ensureActive()
                val t = File(d, e.name.replace('\\', '/').trimStart('/')).canonicalFile
                if (t != d && !t.path.startsWith(d.path + File.separator)) return ToolOut("Unsafe path in archive: ${e.name}", false)
                if (t.relativeTo(base).path.split(File.separatorChar).first() == META_DIR) continue
                if (e.isDirectory) { t.mkdirs(); continue }
                if (t.isDirectory) return ToolOut("Archive entry collides with a folder: ${e.name}", false)
                t.parentFile?.mkdirs()
                FileOutputStream(t).use { o ->
                    val buf = ByteArray(65536)
                    while (true) { val n = z.read(buf); if (n < 0) break; o.write(buf, 0, n); bytes += n; if (bytes > 1_500_000_000L) return ToolOut("Archive too large (over 1.5 GB extracted). Stopped.", false) }
                }
                files++
            }
        }
        return ToolOut("Extracted $files files (${humanSize(bytes)}) into ${relTo(base, d)}")
    }
}

// ---------------------------------------------------------------------------------------------------- run_command
class RunCommand : Tool {
    override val spec = ToolSpec("run_command",
        "Run a shell command with /system/bin/sh on the user's Android device. The working directory is the project folder. stdout and stderr are combined. " +
            "Only commands that exist on Android are available (toybox/toolbox such as ls, cat, grep, sed, find, tar, wget/curl if present). No git, python or package managers.",
        schema(listOf("command"), "command" to prop("string", "Shell command"), "timeout_seconds" to prop("integer", "Kill after this many seconds. Default 60, max 600.")))
    override val kind = "terminal"
    override val mutating = true
    override fun title(a: JsonObject) = "Run command"
    override fun cmd(a: JsonObject) = a.str("command") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val command = a.req("command")
        val timeout = (a.int("timeout_seconds") ?: 60).coerceIn(1, 600)
        val pb = ProcessBuilder("/system/bin/sh", "-c", command).directory(c.dir).redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", c.dir.path); put("PWD", c.dir.path); put("TMPDIR", c.core.app.cacheDir.path); put("TERM", "dumb")
        }
        val proc = pb.start()
        runCatching { proc.outputStream.close() }
        val sb = StringBuilder(); var truncated = false
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf); if (n < 0) break
                        synchronized(sb) { if (sb.length < 1_000_000) sb.append(buf, 0, n) else truncated = true }
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
        var timedOut = false
        try {
            val deadline = System.currentTimeMillis() + timeout * 1000L
            while (!proc.waitFor(100, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() > deadline) { timedOut = true; break }
            }
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
        reader.join(1500)
        val text = synchronized(sb) { sb.toString() }.trimEnd()
        if (timedOut) return ToolOut(text + "\n[killed: timed out after ${timeout}s]", false, 124)
        val code = proc.exitValue()
        val body = text + (if (truncated) "\n[output truncated at 1 MB]" else "") + (if (code != 0) "\n[exit code $code]" else "")
        return ToolOut(body.ifBlank { "(no output)" }, code == 0, code)
    }
}
