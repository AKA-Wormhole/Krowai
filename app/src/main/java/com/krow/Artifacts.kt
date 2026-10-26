package com.krow

import kotlinx.serialization.json.JsonObject
import java.io.File

/*
 * Stage 4 — Artifacts: important deliverables the model surfaces as first-class cards in chat
 * (copy / share), optionally written into the project under artifacts/.
 */

private val SAFE_NAME = Regex("[^A-Za-z0-9._+-]+")

fun safeArtifactName(title: String): String {
    val base = title.trim().ifBlank { "artifact" }.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = SAFE_NAME.replace(base, "_").trim('_').take(80).ifBlank { "artifact" }
    return cleaned
}

/** "krow_voice-update.zip" → "Krow voice update"; "MainActivity.kt" → "Main Activity". Used when the AI gave no title. */
fun prettyName(raw: String): String {
    val file = raw.trim().substringAfterLast('/').substringAfterLast('\\')
    val base = if (file.contains('.') && file.substringAfterLast('.').length <= 5) file.substringBeforeLast('.') else file
    val spaced = base.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace(Regex("[_\\-.]+"), " ").replace(Regex("\\s+"), " ").trim()
    return spaced.ifBlank { "File" }.replaceFirstChar { it.uppercase() }
}

private val DEFAULT_EXT = mapOf("kotlin" to "kt", "java" to "java", "python" to "py", "javascript" to "js", "typescript" to "ts", "markdown" to "md", "json" to "json", "yaml" to "yml", "xml" to "xml", "shell" to "sh", "html" to "html", "css" to "css", "gradle" to "gradle")

object ArtifactOps {
    fun send(dir: File, title: String, content: String, path: String?, language: String?): ToolOut {
        if (content.length > 400_000) return ToolOut("Artifact content is larger than 400 KB; split it or write the file with write_file instead.", false)
        val rel = path?.trim()?.trimStart('/')?.takeIf { it.isNotBlank() }
            ?: "artifacts/" + safeArtifactName(title).let { n -> if (n.contains('.')) n else n + "." + (language?.trim()?.lowercase()?.let { DEFAULT_EXT[it] } ?: if (language.isNullOrBlank()) "md" else "txt") }
        val f = resolveIn(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        val lang = language?.trim().orEmpty().ifBlank {
            when (f.extension.lowercase()) {
                "kt", "kts" -> "kotlin"
                "java" -> "java"
                "py" -> "python"
                "js", "mjs", "cjs" -> "javascript"
                "ts", "tsx" -> "typescript"
                "md", "markdown" -> "markdown"
                "json" -> "json"
                "yml", "yaml" -> "yaml"
                "xml" -> "xml"
                "sh" -> "shell"
                "html", "htm" -> "html"
                "css" -> "css"
                "gradle" -> "gradle"
                else -> f.extension.ifBlank { "text" }
            }
        }
        val preview = if (content.length <= 12_000) content
        else content.take(8_000) + "\n…[${content.length - 12_000} characters omitted in preview — full file on disk]…\n" + content.takeLast(4_000)
        val header = "ARTIFACT\t$rel\t$lang\t${title.trim().ifBlank { f.name }}\n"
        return ToolOut(header + preview)
    }
}

class SendArtifact : Tool {
    override val spec = ToolSpec(
        "send_artifact",
        "Present an important deliverable (code, config, document, report, script) as a shareable artifact card in the chat, and save it under the project (default artifacts/<title>). Use for things the user should keep, copy, or share — not for routine one-line edits.",
        schema(
            listOf("title", "content"),
            "title" to prop("string", "A beautiful, human-friendly name for the card, no file extension, e.g. 'Release notes' or 'Login screen'. Always make it nice to read."),
            "content" to prop("string", "Full text of the artifact"),
            "path" to prop("string", "Project-relative path to write, with a proper file name and extension, e.g. artifacts/release-notes.md. Default: artifacts/<title>.<ext>"),
            "language" to prop("string", "Language hint for display (kotlin, markdown, json, …)"),
        ),
    )
    override val kind = "artifact"
    override val mutating = true
    override fun title(a: JsonObject) = a.str("title")?.trim()?.ifBlank { null } ?: "Artifact"
    override fun cmd(a: JsonObject): String {
        val t = a.str("title") ?: "artifact"
        return a.str("path")?.trim()?.trimStart('/')?.ifBlank { null } ?: "artifacts/${safeArtifactName(t)}"
    }
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val title = a.str("title") ?: throw IllegalArgumentException("Missing required argument 'title'")
        val content = a.str("content") ?: throw IllegalArgumentException("Missing required argument 'content'")
        return ArtifactOps.send(c.dir, title, content, a.str("path"), a.str("language"))
    }
}

data class ArtifactPayload(val path: String, val lang: String, val title: String, val body: String)

/** Parses the ARTIFACT header line produced by send_artifact. */
fun parseArtifactPayload(text: String): ArtifactPayload? {
    val first = text.lineSequence().firstOrNull() ?: return null
    if (!first.startsWith("ARTIFACT\t")) return null
    val parts = first.split('\t')
    if (parts.size < 4) return null
    val body = text.substringAfter('\n', missingDelimiterValue = "")
    return ArtifactPayload(parts[1], parts[2], parts[3], body)
}

private val TEXT_EXT = setOf("txt", "md", "markdown", "json", "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "xml", "yml", "yaml", "sh", "html", "htm", "css", "gradle", "csv", "log", "properties", "toml", "ini", "sql", "c", "h", "cpp", "go", "rs", "swift", "rb", "php")

/** Surfaces any existing project file (zip, apk, image, pdf, text…) as a downloadable file card in the chat. */
class ShareFile : Tool {
    override val spec = ToolSpec(
        "share_file",
        "Show an existing file from the project as a file card in the chat, with Download and Share buttons for the user. Works for any file type (ZIP, APK, image, PDF, document, code). Use it whenever you hand a file to the user.",
        schema(
            listOf("path"),
            "path" to prop("string", "Project-relative path of the file"),
            "title" to prop("string", "A beautiful, human-friendly name for the file card, no extension, e.g. 'Krow voice update' or 'Quarterly budget'. Always provide one; never use the raw file name."),
        ),
    )
    override val kind = "artifact"
    override fun title(a: JsonObject) = a.str("title")?.trim()?.ifBlank { null } ?: a.str("path")?.let { prettyName(it) } ?: "File"
    override fun cmd(a: JsonObject) = a.str("path")?.trim()?.trimStart('/') ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val path = a.str("path")?.trim()?.trimStart('/')?.ifBlank { null } ?: throw IllegalArgumentException("Missing required argument 'path'")
        val f = resolveIn(c.dir, path)
        if (!f.isFile) return ToolOut("File not found: $path", false)
        val ext = f.extension.lowercase()
        val preview = if (ext in TEXT_EXT && f.length() <= 400_000) f.readText().let { if (it.length <= 12_000) it else it.take(8_000) + "\n…[truncated in preview]…\n" + it.takeLast(4_000) } else ""
        return ToolOut("ARTIFACT\t$path\t${ext.ifBlank { "file" }}\t${title(a)}\n" + preview)
    }
}
