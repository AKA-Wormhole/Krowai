package com.krow

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream

/*
 * Stage 4 — GitHub Actions (scoped to the selected repository) + APK build helpers.
 *
 * Tools never take owner/repo from the model; they always use Project.owner / Project.repo / Project.branch.
 * Triggering workflows and downloading artifacts need a token with Actions: Read and Actions: Read and write
 * (fine-grained) or the classic `repo` + `workflow` scopes.
 */

data class GhWorkflow(val id: Long, val name: String, val path: String, val state: String)
data class GhRun(val id: Long, val name: String, val status: String, val conclusion: String?, val htmlUrl: String, val headSha: String, val createdAt: String)
data class GhArtifact(val id: Long, val name: String, val size: Long, val expired: Boolean)

private val WF_FILE = Regex("""^[A-Za-z0-9_./-]+\.ya?ml$""")

fun Gh.workflows(owner: String, repo: String): List<GhWorkflow> =
    get("/repos/$owner/$repo/actions/workflows?per_page=100").jsonObject["workflows"]!!.jsonArray.map {
        val o = it.jsonObject
        GhWorkflow(o["id"]!!.jsonPrimitive.long, need(o.str("name")), need(o.str("path")), o.str("state") ?: "unknown")
    }

fun Gh.triggerWorkflow(owner: String, repo: String, workflow: String, ref: String, inputs: Map<String, String> = emptyMap()) {
    val idOrFile = workflow.trim()
    if (idOrFile.isEmpty()) throw IllegalArgumentException("workflow is required (filename under .github/workflows/ or numeric id)")
    val body = buildJsonObject {
        put("ref", ref)
        if (inputs.isNotEmpty()) putJsonObject("inputs") { inputs.forEach { (k, v) -> put(k, v) } }
    }
    send("POST", "/repos/$owner/$repo/actions/workflows/${encRef(idOrFile)}/dispatches", body)
}

fun Gh.runs(owner: String, repo: String, workflow: String? = null, limit: Int = 10): List<GhRun> {
    val lim = limit.coerceIn(1, 30)
    val path = if (workflow.isNullOrBlank()) "/repos/$owner/$repo/actions/runs?per_page=$lim"
    else "/repos/$owner/$repo/actions/workflows/${encRef(workflow.trim())}/runs?per_page=$lim"
    return get(path).jsonObject["workflow_runs"]!!.jsonArray.map { runOf(it.jsonObject) }
}

fun Gh.run(owner: String, repo: String, runId: Long): GhRun =
    runOf(get("/repos/$owner/$repo/actions/runs/$runId").jsonObject)

private fun runOf(o: JsonObject) = GhRun(
    id = o["id"]!!.jsonPrimitive.long,
    name = o.str("name") ?: o.str("display_title") ?: "run",
    status = o.str("status") ?: "unknown",
    conclusion = o.str("conclusion"),
    htmlUrl = o.str("html_url") ?: "",
    headSha = (o.str("head_sha") ?: "").take(7),
    createdAt = o.str("created_at") ?: "",
)

fun Gh.artifactsForRun(owner: String, repo: String, runId: Long): List<GhArtifact> =
    get("/repos/$owner/$repo/actions/runs/$runId/artifacts").jsonObject["artifacts"]!!.jsonArray.map {
        val o = it.jsonObject
        GhArtifact(o["id"]!!.jsonPrimitive.long, need(o.str("name")), o["size_in_bytes"]?.jsonPrimitive?.longOrNull ?: 0L, o.bool("expired") == true)
    }

/** Downloads an Actions artifact ZIP. Redirect to Azure/blob is followed without the GitHub token. */
fun Gh.downloadArtifactZip(owner: String, repo: String, artifactId: Long, out: File) {
    val noRedirect = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    val req = builder("/repos/$owner/$repo/actions/artifacts/$artifactId/zip", "application/vnd.github+json").get().build()
    noRedirect.newCall(req).execute().use { r ->
        when {
            r.code in 301..308 -> {
                val loc = r.header("Location") ?: throw GhError(r.code, "Artifact redirect without a location")
                val u = loc.toHttpUrl()
                if (u.scheme != "https") throw GhError(0, "Refusing a non-HTTPS download link")
                http.newCall(Request.Builder().url(u).header("User-Agent", "Krow-Android").build()).execute().use { r2 ->
                    if (!r2.isSuccessful) throw GhError(r2.code, "Artifact download failed (HTTP ${r2.code})")
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> r2.body?.byteStream()?.copyTo(o) }
                }
            }
            r.isSuccessful -> {
                out.parentFile?.mkdirs()
                out.outputStream().use { o -> r.body?.byteStream()?.copyTo(o) }
            }
            else -> fail(r)
        }
    }
}

/** Standard Android debug-APK workflow body (Gradle 8.9, JDK 17). */
fun apkWorkflowYaml(artifactName: String = "app-debug-apk"): String = """
name: Build APK
on:
  workflow_dispatch:
  push:
    branches: [main, master]

jobs:
  apk:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'
      - name: Assemble debug APK
        run: gradle :app:assembleDebug --no-daemon
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: $artifactName
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: error
""".trimStart()

object ActionOps {
    fun listWorkflows(gh: Gh, p: Project): String {
        val list = gh.workflows(p.owner, p.repo)
        if (list.isEmpty()) return "No workflows in ${p.owner}/${p.repo}. Add .github/workflows/*.yml (or use ensure_apk_workflow) then push."
        return buildString {
            append("Workflows in ${p.owner}/${p.repo}:\n")
            for (w in list) append("  ${w.path}  ·  ${w.name}  ·  ${w.state}  ·  id=${w.id}\n")
        }.trimEnd()
    }

    fun trigger(gh: Gh, p: Project, workflow: String, inputs: Map<String, String>): String {
        val ref = p.branch.ifBlank { "main" }
        gh.triggerWorkflow(p.owner, p.repo, workflow, ref, inputs)
        return "Triggered workflow “$workflow” on ${p.owner}/${p.repo}@$ref. Use github_list_runs to watch status, then github_download_artifact when it finishes."
    }

    fun listRuns(gh: Gh, p: Project, workflow: String?, limit: Int): String {
        val runs = gh.runs(p.owner, p.repo, workflow, limit)
        if (runs.isEmpty()) return "No recent workflow runs."
        return buildString {
            append("Recent runs on ${p.owner}/${p.repo}${if (workflow.isNullOrBlank()) "" else " · $workflow"}:\n")
            for (r in runs) {
                val conc = r.conclusion?.let { " → $it" } ?: ""
                append("  #${r.id}  ${r.name}  ·  ${r.status}$conc  ·  ${r.headSha}  ·  ${r.createdAt}\n")
                if (r.htmlUrl.isNotBlank()) append("    ${r.htmlUrl}\n")
            }
        }.trimEnd()
    }

    fun runStatus(gh: Gh, p: Project, runId: Long): String {
        val r = gh.run(p.owner, p.repo, runId)
        val arts = runCatching { gh.artifactsForRun(p.owner, p.repo, runId) }.getOrDefault(emptyList())
        return buildString {
            append("Run #${r.id} “${r.name}”: ${r.status}")
            r.conclusion?.let { append(" → $it") }
            append("\nsha ${r.headSha}  ·  ${r.createdAt}")
            if (r.htmlUrl.isNotBlank()) append("\n${r.htmlUrl}")
            if (arts.isEmpty()) append("\nNo artifacts yet.")
            else {
                append("\nArtifacts:")
                for (a in arts) append("\n  id=${a.id}  ${a.name}  (${humanSize(a.size)})${if (a.expired) " EXPIRED" else ""}")
            }
        }
    }

    fun downloadArtifact(gh: Gh, p: Project, dir: File, runId: Long, artifactId: Long?, nameHint: String?): String {
        val arts = gh.artifactsForRun(p.owner, p.repo, runId).filter { !it.expired }
        if (arts.isEmpty()) return "No non-expired artifacts on run #$runId."
        val art = when {
            artifactId != null -> arts.firstOrNull { it.id == artifactId }
                ?: return "Artifact id $artifactId not on run #$runId. Available: ${arts.joinToString { "${it.id}=${it.name}" }}"
            !nameHint.isNullOrBlank() -> arts.firstOrNull { it.name.contains(nameHint, true) }
                ?: return "No artifact matching “$nameHint”. Available: ${arts.joinToString { it.name }}"
            else -> arts.firstOrNull { it.name.contains("apk", true) } ?: arts.first()
        }
        val outDir = resolveIn(dir, "artifacts/ci")
        outDir.mkdirs()
        val zip = File(outDir, "${art.name}-${art.id}.zip")
        gh.downloadArtifactZip(p.owner, p.repo, art.id, zip)
        // Extract APKs (and other files) next to the zip for convenience
        var extracted = 0
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/').ifBlank { e.name }
                if (name.contains("..")) continue
                val dest = File(outDir, name)
                dest.outputStream().use { z.copyTo(it) }
                extracted++
                z.closeEntry()
            }
        }
        return "Downloaded artifact “${art.name}” (${humanSize(art.size)}) → artifacts/ci/${zip.name}" +
            if (extracted > 0) " and extracted $extracted file(s) into artifacts/ci/." else "."
    }

    fun ensureApkWorkflow(dir: File): String {
        val f = resolveIn(dir, ".github/workflows/build-apk.yml")
        if (f.exists()) return "Already present: .github/workflows/build-apk.yml (${humanSize(f.length())}). Commit & push if the remote does not have it yet, then github_trigger_workflow with workflow=build-apk.yml."
        f.parentFile?.mkdirs()
        f.writeText(apkWorkflowYaml())
        return "Wrote .github/workflows/build-apk.yml. Commit and push it (git_commit), then call github_trigger_workflow with workflow=build-apk.yml. When the run succeeds, github_download_artifact will pull the APK into artifacts/ci/."
    }
}

// ---------------------------------------------------------------------------------------------------- tools
class GhListWorkflows : Tool {
    override val spec = ToolSpec(
        "github_list_workflows",
        "List GitHub Actions workflows in the selected repository.",
        schema(emptyList()),
    )
    override val kind = "actions"
    override fun title(a: JsonObject) = "List workflows"
    override suspend fun run(c: ToolCtx, a: JsonObject) = ToolOut(ActionOps.listWorkflows(c.core.gh(), c.project))
}

class GhTriggerWorkflow : Tool {
    override val spec = ToolSpec(
        "github_trigger_workflow",
        "Trigger a workflow_dispatch on the selected repository (branch = the project's branch). Use the workflow file name (e.g. build-apk.yml) or numeric id from github_list_workflows.",
        schema(
            listOf("workflow"),
            "workflow" to prop("string", "Workflow file name under .github/workflows/ (e.g. build-apk.yml) or numeric workflow id"),
            "inputs" to buildJsonObject {
                put("type", "object")
                put("description", "Optional workflow_dispatch inputs as string key/value pairs")
                putJsonObject("additionalProperties") { put("type", "string") }
            },
        ),
    )
    override val kind = "actions"
    override val mutating = true
    override val alwaysAsk = true
    override fun title(a: JsonObject) = "Trigger workflow"
    override fun cmd(a: JsonObject) = a.str("workflow") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val wf = a.str("workflow")?.trim().orEmpty()
        if (wf.isEmpty()) throw IllegalArgumentException("Missing required argument 'workflow'")
        if (wf.toLongOrNull() == null && !WF_FILE.matches(wf) && !wf.endsWith(".yml") && !wf.endsWith(".yaml"))
            throw IllegalArgumentException("workflow should be a .yml filename or numeric id")
        val inputs = linkedMapOf<String, String>()
        (a["inputs"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { inputs[k] = it } }
        return ToolOut(ActionOps.trigger(c.core.gh(), c.project, wf, inputs))
    }
}

class GhListRuns : Tool {
    override val spec = ToolSpec(
        "github_list_runs",
        "List recent GitHub Actions runs for the selected repository (optionally one workflow).",
        schema(
            emptyList(),
            "workflow" to prop("string", "Optional workflow file name or id to filter"),
            "limit" to prop("integer", "How many runs (1-30). Default 8."),
        ),
    )
    override val kind = "actions"
    override fun title(a: JsonObject) = "List workflow runs"
    override fun cmd(a: JsonObject) = a.str("workflow") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject) =
        ToolOut(ActionOps.listRuns(c.core.gh(), c.project, a.str("workflow"), a.int("limit") ?: 8))
}

class GhRunStatus : Tool {
    override val spec = ToolSpec(
        "github_run_status",
        "Show status and artifacts for one Actions run id.",
        schema(listOf("run_id"), "run_id" to prop("integer", "Workflow run id from github_list_runs")),
    )
    override val kind = "actions"
    override fun title(a: JsonObject) = "Workflow run status"
    override fun cmd(a: JsonObject) = a.int("run_id")?.toString() ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val id = a.int("run_id")?.toLong() ?: throw IllegalArgumentException("Missing required argument 'run_id'")
        return ToolOut(ActionOps.runStatus(c.core.gh(), c.project, id))
    }
}

class GhDownloadArtifact : Tool {
    override val spec = ToolSpec(
        "github_download_artifact",
        "Download a GitHub Actions artifact (e.g. a built APK) into artifacts/ci/ in the project. Prefer the APK-named artifact when several exist.",
        schema(
            listOf("run_id"),
            "run_id" to prop("integer", "Workflow run id that produced the artifact"),
            "artifact_id" to prop("integer", "Optional specific artifact id from github_run_status"),
            "name" to prop("string", "Optional name substring to pick the artifact (e.g. apk)"),
        ),
    )
    override val kind = "actions"
    override val mutating = true
    override fun title(a: JsonObject) = "Download Actions artifact"
    override fun cmd(a: JsonObject) = "run ${a.int("run_id") ?: "?"}"
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val runId = a.int("run_id")?.toLong() ?: throw IllegalArgumentException("Missing required argument 'run_id'")
        return ToolOut(ActionOps.downloadArtifact(c.core.gh(), c.project, c.dir, runId, a.int("artifact_id")?.toLong(), a.str("name")))
    }
}

class EnsureApkWorkflow : Tool {
    override val spec = ToolSpec(
        "ensure_apk_workflow",
        "Create .github/workflows/build-apk.yml in the project if missing (debug APK via GitHub Actions). Does not push — use git_commit afterwards, then github_trigger_workflow.",
        schema(emptyList()),
    )
    override val kind = "actions"
    override val mutating = true
    override fun title(a: JsonObject) = "Ensure APK workflow"
    override fun cmd(a: JsonObject) = ".github/workflows/build-apk.yml"
    override suspend fun run(c: ToolCtx, a: JsonObject) = ToolOut(ActionOps.ensureApkWorkflow(c.dir))
}
