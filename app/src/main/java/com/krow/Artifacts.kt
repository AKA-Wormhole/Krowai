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

object ArtifactOps {
    fun send(dir: File, title: String, content: String, path: String?, language: String?): ToolOut {
        if (content.length > 400_000) return ToolOut("Artifact content is larger than 400 KB; split it or write the file with write_file instead.", false)
        val rel = path?.trim()?.trimStart('/')?.takeIf { it.isNotBlank() }
            ?: "artifacts/${safeArtifactName(title)}"
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
            "title" to prop("string", "Short title or filename, e.g. MainActivity.kt or release-notes.md"),
            "content" to prop("string", "Full text of the artifact"),
            "path" to prop("string", "Optional project-relative path to write. Default: artifacts/<safe-title>"),
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
