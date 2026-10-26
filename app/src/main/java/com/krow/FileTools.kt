package com.krow

import kotlinx.serialization.json.*
import java.io.File

/*
 * Precise, forgiving file editing for the AI.
 *  - edit_file / multi_edit: exact match first, then tolerant of trailing whitespace and indentation, CRLF-safe,
 *    all-or-nothing, and failures say where the text really is.
 *  - insert_lines / replace_lines / append_file: edit by line number or anchor text without resending the file.
 *  - copy_path.
 */

private const val MAX_EDIT_BYTES = 2_000_000L

private fun nl(s: String) = s.replace("\r\n", "\n")

private class Found(val start: Int, val end: Int)

private fun norm(mode: Int, s: String) = if (mode == 1) s.trimEnd() else s.trim()

private fun lineOf(s: String, index: Int) = s.substring(0, index).count { it == '\n' } + 1

/** All places where [old] occurs in [src]: exact, else ignoring trailing spaces per line, else ignoring indentation too. */
private fun findAll(src: String, old: String): List<Found> {
    val out = ArrayList<Found>()
    var i = src.indexOf(old)
    while (i >= 0) { out += Found(i, i + old.length); i = src.indexOf(old, i + old.length) }
    if (out.isNotEmpty()) return out
    val ol = old.trim('\n').split("\n")
    if (ol.all { it.isBlank() }) return out
    val sl = src.split("\n")
    val offs = IntArray(sl.size)
    var p = 0
    for (k in sl.indices) { offs[k] = p; p += sl[k].length + 1 }
    for (mode in 1..2) {
        val on = ol.map { norm(mode, it) }
        var k = 0
        while (k + ol.size <= sl.size) {
            var ok = true
            for (j in ol.indices) if (norm(mode, sl[k + j]) != on[j]) { ok = false; break }
            if (ok) { out += Found(offs[k], offs[k + ol.size - 1] + sl[k + ol.size - 1].length); k += ol.size } else k++
        }
        if (out.isNotEmpty()) return out
    }
    return out
}

private fun hint(s: String, old: String): String {
    val first = old.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
    val lines = s.split("\n")
    var idx = lines.indexOfFirst { it.trim() == first }
    if (idx < 0) idx = lines.indexOfFirst { it.contains(first.take(40)) }
    if (idx < 0) return " Nothing similar was found; read_file the file again."
    val from = maxOf(0, idx - 1)
    val to = minOf(lines.size, idx + 4)
    return " The first line of old_text looks like line ${idx + 1}. Actual text there:\n" + (from until to).joinToString("\n") { "${it + 1}\t${lines[it]}" }
}

private class Edit(val old: String, val new: String, val all: Boolean)

/** Applies every edit in order to [raw]; throws IllegalArgumentException (nothing written) if any fails. Returns new text and replacement count. */
private fun applyEdits(raw: String, edits: List<Edit>): Pair<String, Int> {
    val crlf = raw.contains("\r\n")
    var s = nl(raw)
    var total = 0
    edits.forEachIndexed { n, e ->
        val tag = if (edits.size > 1) "Edit ${n + 1}: " else ""
        val old = nl(e.old)
        val new = nl(e.new)
        if (old.isEmpty()) throw IllegalArgumentException("${tag}old_text must not be empty. Use write_file, insert_lines or append_file instead.")
        val hits = findAll(s, old)
        if (hits.isEmpty()) throw IllegalArgumentException("${tag}old_text was not found.${hint(s, old)}")
        if (hits.size > 1 && !e.all) {
            val lines = hits.take(8).joinToString(", ") { lineOf(s, it.start).toString() }
            throw IllegalArgumentException("${tag}old_text occurs ${hits.size} times (lines $lines). Add surrounding context to make it unique, or set replace_all=true.")
        }
        val use = if (e.all) hits else hits.take(1)
        val sb = StringBuilder()
        var last = 0
        for (h in use) { sb.append(s, last, h.start); sb.append(new); last = h.end }
        sb.append(s, last, s.length)
        s = sb.toString()
        total += use.size
    }
    return (if (crlf) s.replace("\n", "\r\n") else s) to total
}

private fun readText(f: File, shown: String?): String {
    if (!f.isFile) throw IllegalArgumentException("Not a file: $shown")
    if (f.length() > MAX_EDIT_BYTES || isBinaryFile(f)) throw IllegalArgumentException("Cannot edit a large or binary file as text.")
    return f.readText()
}

private fun report(c: ToolCtx, f: File, verb: String, before: String, after: String, extra: String = ""): ToolOut {
    f.writeText(after)
    val rel = relTo(c.dir, f)
    val d = Diff.unified(rel, before, after)
    if (d.isEmpty()) return ToolOut("$verb $rel — content unchanged.")
    val (ad, de) = Diff.counts(d)
    return ToolOut("$verb $rel  +$ad −$de$extra\n\n$d")
}

private fun fileName(a: JsonObject) = a.str("path")?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { null } ?: "file"

// ---------------------------------------------------------------------------------------------------- edit_file
class EditFile : Tool {
    override val spec = ToolSpec("edit_file",
        "Replace text in an existing file. old_text should match the file (small whitespace/indentation differences are tolerated) and be unique, unless replace_all is true. " +
            "For several changes in one file use multi_edit. To add new lines use insert_lines.",
        schema(listOf("path", "old_text", "new_text"), "path" to prop("string", "File path relative to the project root"),
            "old_text" to prop("string", "Text to find (include a few surrounding lines so it is unique)"), "new_text" to prop("string", "Replacement text (may be empty to delete)"),
            "replace_all" to prop("boolean", "Replace every occurrence. Default false.")))
    override val kind = "edit"
    override val mutating = true
    override fun title(a: JsonObject) = "Edit " + fileName(a)
    override fun cmd(a: JsonObject) = a.str("path") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val f = c.res(a.req("path"))
        val oldT = a.str("old_text") ?: throw IllegalArgumentException("Missing required argument 'old_text'")
        val newT = a.str("new_text") ?: throw IllegalArgumentException("Missing required argument 'new_text'")
        return try {
            val src = readText(f, a.str("path"))
            val (out, n) = applyEdits(src, listOf(Edit(oldT, newT, a.bool("replace_all") == true)))
            report(c, f, "Edited", src, out, " ($n replacement${if (n == 1) "" else "s"})")
        } catch (e: IllegalArgumentException) { ToolOut(e.message ?: "Edit failed", false) }
    }
}

// ---------------------------------------------------------------------------------------------------- multi_edit
class MultiEdit : Tool {
    override val spec = ToolSpec("multi_edit",
        "Apply several text replacements to ONE file in a single step, in order. All-or-nothing: if any edit fails, nothing is written and the error says which one.",
        schema(listOf("path", "edits"), "path" to prop("string", "File path relative to the project root"),
            "edits" to buildJsonObject {
                put("type", "array"); put("description", "Edits applied in order; later edits see the result of earlier ones.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("old_text") { put("type", "string"); put("description", "Text to find") }
                        putJsonObject("new_text") { put("type", "string"); put("description", "Replacement") }
                        putJsonObject("replace_all") { put("type", "boolean"); put("description", "Replace every occurrence") }
                    }
                    putJsonArray("required") { add("old_text"); add("new_text") }
                }
            }))
    override val kind = "edit"
    override val mutating = true
    override fun title(a: JsonObject) = "Edit " + fileName(a)
    override fun cmd(a: JsonObject) = a.str("path") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val f = c.res(a.req("path"))
        val arr = a["edits"] as? JsonArray ?: throw IllegalArgumentException("Missing required argument 'edits'")
        val edits = arr.mapIndexed { i, e ->
            val o = e as? JsonObject ?: throw IllegalArgumentException("Edit ${i + 1} is not an object")
            Edit(o.str("old_text") ?: throw IllegalArgumentException("Edit ${i + 1}: missing old_text"), o.str("new_text") ?: throw IllegalArgumentException("Edit ${i + 1}: missing new_text"), o.bool("replace_all") == true)
        }
        if (edits.isEmpty()) return ToolOut("No edits given.", false)
        return try {
            val src = readText(f, a.str("path"))
            val (out, n) = applyEdits(src, edits)
            report(c, f, "Edited", src, out, " (${edits.size} edits, $n replacements)")
        } catch (e: IllegalArgumentException) { ToolOut((e.message ?: "Edit failed") + "\nNothing was changed.", false) }
    }
}

// ---------------------------------------------------------------------------------------------------- insert_lines
class InsertLines : Tool {
    override val spec = ToolSpec("insert_lines",
        "Insert text into a file without resending it. Choose ONE position: line (insert before this 1-based line number; omit to append at the end), after_text (insert after the line containing this unique anchor), or before_text. Creates the file if it does not exist.",
        schema(listOf("path", "text"), "path" to prop("string", "File path relative to the project root"),
            "text" to prop("string", "Text to insert (one or more lines; no need for a trailing newline)"),
            "line" to prop("integer", "Insert before this line number (1-based). Use the total line count + 1 or omit to append."),
            "after_text" to prop("string", "Insert after the line where this unique text appears"),
            "before_text" to prop("string", "Insert before the line where this unique text appears")))
    override val kind = "edit"
    override val mutating = true
    override fun title(a: JsonObject) = "Insert into " + fileName(a)
    override fun cmd(a: JsonObject) = a.str("path") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val path = a.req("path")
        val text = a.str("text") ?: throw IllegalArgumentException("Missing required argument 'text'")
        val f = c.res(path)
        if (f.isDirectory) return ToolOut("That path is a folder.", false)
        return try {
            val raw = if (f.exists()) readText(f, path) else ""
            val crlf = raw.contains("\r\n")
            val s = nl(raw)
            val lines = if (s.isEmpty()) ArrayList<String>() else ArrayList(s.removeSuffix("\n").split("\n"))
            val endsNl = s.isEmpty() || s.endsWith("\n")
            val after = a.str("after_text")?.let { nl(it) }
            val before = a.str("before_text")?.let { nl(it) }
            val at: Int
            if (after != null || before != null) {
                val key = (after ?: before)!!
                val hits = findAll(s, key)
                if (hits.isEmpty()) return ToolOut("Anchor text was not found.${hint(s, key)}", false)
                if (hits.size > 1) return ToolOut("Anchor text occurs ${hits.size} times (lines ${hits.take(8).joinToString(", ") { lineOf(s, it.start).toString() }}). Make it more specific.", false)
                val h = hits[0]
                at = if (after != null) s.substring(0, h.end).count { it == '\n' } + 1 else s.substring(0, h.start).count { it == '\n' }
            } else {
                at = (a.int("line")?.minus(1) ?: lines.size).coerceIn(0, lines.size)
            }
            lines.addAll(at.coerceAtMost(lines.size), nl(text).trimEnd('\n').split("\n"))
            var out = lines.joinToString("\n") + (if (endsNl) "\n" else "")
            if (crlf) out = out.replace("\n", "\r\n")
            f.parentFile?.mkdirs()
            report(c, f, if (raw.isEmpty() && !f.exists()) "Created" else "Edited", raw, out, " (inserted at line ${at + 1})")
        } catch (e: IllegalArgumentException) { ToolOut(e.message ?: "Insert failed", false) }
    }
}

// ---------------------------------------------------------------------------------------------------- replace_lines
class ReplaceLines : Tool {
    override val spec = ToolSpec("replace_lines",
        "Replace (or delete) an inclusive range of lines by number. Read the file first so the numbers are current. Pass empty new_text to delete the lines.",
        schema(listOf("path", "start_line", "end_line", "new_text"), "path" to prop("string", "File path relative to the project root"),
            "start_line" to prop("integer", "First line to replace (1-based)"), "end_line" to prop("integer", "Last line to replace (inclusive)"),
            "new_text" to prop("string", "Replacement lines (empty = delete)")))
    override val kind = "edit"
    override val mutating = true
    override fun title(a: JsonObject) = "Edit " + fileName(a)
    override fun cmd(a: JsonObject) = (a.str("path") ?: "") + ":" + (a.int("start_line") ?: "?") + "-" + (a.int("end_line") ?: "?")
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val path = a.req("path")
        val f = c.res(path)
        val st = a.int("start_line") ?: throw IllegalArgumentException("Missing required argument 'start_line'")
        val en = a.int("end_line") ?: throw IllegalArgumentException("Missing required argument 'end_line'")
        val newT = a.str("new_text") ?: ""
        return try {
            val raw = readText(f, path)
            val crlf = raw.contains("\r\n")
            val s = nl(raw)
            val lines = if (s.isEmpty()) ArrayList<String>() else ArrayList(s.removeSuffix("\n").split("\n"))
            if (st < 1 || en < st || en > lines.size) return ToolOut("Invalid range $st-$en: the file has ${lines.size} lines.", false)
            val repl: List<String> = if (newT.isEmpty()) emptyList() else nl(newT).trimEnd('\n').split("\n")
            val head = lines.subList(0, st - 1).toList()
            val tail = lines.subList(en, lines.size).toList()
            var out = (head + repl + tail).joinToString("\n") + (if (s.endsWith("\n") || s.isEmpty()) "\n" else "")
            if (crlf) out = out.replace("\n", "\r\n")
            report(c, f, "Edited", raw, out, " (lines $st-$en)")
        } catch (e: IllegalArgumentException) { ToolOut(e.message ?: "Edit failed", false) }
    }
}

// ---------------------------------------------------------------------------------------------------- append_file
class AppendFile : Tool {
    override val spec = ToolSpec("append_file",
        "Add text to the end of a file (created if missing). Good for logs, long files written in chunks, or adding entries to a list.",
        schema(listOf("path", "content"), "path" to prop("string", "File path relative to the project root"), "content" to prop("string", "Text to append")))
    override val kind = "edit"
    override val mutating = true
    override fun title(a: JsonObject) = "Append to " + fileName(a)
    override fun cmd(a: JsonObject) = a.str("path") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val path = a.req("path")
        val add = a.str("content") ?: throw IllegalArgumentException("Missing required argument 'content'")
        val f = c.res(path)
        if (f.isDirectory) return ToolOut("That path is a folder.", false)
        return try {
            val existed = f.exists()
            val raw = if (existed) readText(f, path) else ""
            val sep = if (raw.isNotEmpty() && !raw.endsWith("\n")) "\n" else ""
            f.parentFile?.mkdirs()
            report(c, f, if (existed) "Appended to" else "Created", raw, raw + sep + add)
        } catch (e: IllegalArgumentException) { ToolOut(e.message ?: "Append failed", false) }
    }
}

// ---------------------------------------------------------------------------------------------------- copy_path
class CopyPath : Tool {
    override val spec = ToolSpec("copy_path", "Copy a file or folder to a new path inside the project. Refuses to overwrite unless overwrite is true.",
        schema(listOf("from", "to"), "from" to prop("string", "Existing file or folder"), "to" to prop("string", "New path"), "overwrite" to prop("boolean", "Replace an existing destination. Default false.")))
    override val kind = "file"
    override val mutating = true
    override fun title(a: JsonObject) = "Copy " + (a.str("from")?.trimEnd('/')?.substringAfterLast('/') ?: "file")
    override fun cmd(a: JsonObject) = (a.str("from") ?: "") + " → " + (a.str("to") ?: "")
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val src = c.res(a.req("from")); val dst = c.res(a.req("to"))
        if (!src.exists()) return ToolOut("Not found: ${a.str("from")}", false)
        if (dst.canonicalPath == src.canonicalPath || dst.canonicalPath.startsWith(src.canonicalPath + File.separator)) return ToolOut("Cannot copy a path into itself.", false)
        if (dst.exists()) {
            if (a.bool("overwrite") != true) return ToolOut("${relTo(c.dir, dst)} already exists. Pass overwrite=true to replace it.", false)
            if (dst.isDirectory) dst.deleteRecursively() else dst.delete()
        }
        dst.parentFile?.mkdirs()
        src.copyRecursively(dst, overwrite = true)
        return ToolOut("Copied ${relTo(c.dir, src)} → ${relTo(c.dir, dst)}")
    }
}
