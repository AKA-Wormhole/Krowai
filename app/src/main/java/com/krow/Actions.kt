package com.krow

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import android.net.Uri
import com.krow.ui.mimeOf
import com.krow.ui.saveToDownloadsEx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
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

concurrency:
  group: build-apk-${'$'}{{ github.ref }}
  cancel-in-progress: true

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
          retention-days: 3
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
    override val trusted = true
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


// ---------------------------------------------------------------------------------------------------- more Actions API
fun Gh.cancelRun(owner: String, repo: String, runId: Long) { send("POST", "/repos/$owner/$repo/actions/runs/$runId/cancel", buildJsonObject { }) }

fun Gh.rerun(owner: String, repo: String, runId: Long, failedOnly: Boolean) {
    send("POST", "/repos/$owner/$repo/actions/runs/$runId/" + if (failedOnly) "rerun-failed-jobs" else "rerun", buildJsonObject { })
}

fun Gh.deleteArtifact(owner: String, repo: String, artifactId: Long) { send("DELETE", "/repos/$owner/$repo/actions/artifacts/$artifactId", buildJsonObject { }) }

class GhJob(val id: Long, val name: String, val conclusion: String?, val failedStep: String?)

fun Gh.jobs(owner: String, repo: String, runId: Long): List<GhJob> =
    get("/repos/$owner/$repo/actions/runs/$runId/jobs?per_page=50").jsonObject["jobs"]!!.jsonArray.map {
        val o = it.jsonObject
        val failed = (o["steps"] as? JsonArray)?.map { s -> s.jsonObject }?.firstOrNull { s -> s.str("conclusion") == "failure" }?.str("name")
        GhJob(o["id"]!!.jsonPrimitive.long, o.str("name") ?: "job", o.str("conclusion"), failed)
    }

/** Plain-text log of one job (GitHub answers with a redirect to a short-lived download link). */
fun Gh.jobLog(owner: String, repo: String, jobId: Long): String {
    val noRedirect = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    val req = builder("/repos/$owner/$repo/actions/jobs/$jobId/logs", "application/vnd.github+json").get().build()
    return noRedirect.newCall(req).execute().use { r ->
        when {
            r.code in 301..308 -> {
                val u = (r.header("Location") ?: throw GhError(r.code, "Log redirect without a location")).toHttpUrl()
                if (u.scheme != "https") throw GhError(0, "Refusing a non-HTTPS log link")
                http.newCall(Request.Builder().url(u).header("User-Agent", "Krow-Android").build()).execute().use { r2 ->
                    if (!r2.isSuccessful) throw GhError(r2.code, "Log download failed (HTTP ${r2.code})")
                    r2.body?.string().orEmpty()
                }
            }
            r.isSuccessful -> r.body?.string().orEmpty()
            else -> fail(r)
        }
    }
}

fun Gh.createRelease(owner: String, repo: String, tag: String, name: String, notes: String, target: String, prerelease: Boolean): Pair<Long, String> {
    val o = send("POST", "/repos/$owner/$repo/releases", buildJsonObject {
        put("tag_name", tag); put("name", name); put("body", notes); put("target_commitish", target)
        put("prerelease", prerelease); put("draft", false)
    }).jsonObject
    return o["id"]!!.jsonPrimitive.long to (o.str("html_url") ?: "")
}

fun Gh.uploadReleaseAsset(owner: String, repo: String, releaseId: Long, file: File, name: String) {
    val req = rawBuilder("https://uploads.github.com/repos/$owner/$repo/releases/$releaseId/assets?name=${Uri.encode(name)}")
        .post(file.asRequestBody(mimeOf(name).toMediaType())).build()
    http.newCall(req).execute().use { r -> if (!r.isSuccessful) fail(r) }
}

private val LOG_TS = Regex("""^\d{4}-\d\d-\d\dT[\d:.]+Z """)
private val LOG_HIT = Regex("""(?i)(^e: |\berror:|\bFAILED\b|What went wrong|Execution failed|Could not (resolve|find)|Unresolved reference|FAILURE: Build failed|\bexception\b|cannot find symbol|Compilation error)""")

object CiLogs {
    /** The useful lines of a failed run: compiler errors and Gradle's own explanation, not 5000 lines of setup noise. */
    fun digest(gh: Gh, p: Project, runId: Long): String {
        val failed = gh.jobs(p.owner, p.repo, runId).filter { it.conclusion == "failure" }.ifEmpty { gh.jobs(p.owner, p.repo, runId) }
        val sb = StringBuilder()
        for (j in failed.take(2)) {
            sb.append("Job “${j.name}”").append(j.failedStep?.let { " failed at step “$it”" } ?: "").append(":\n")
            val lines = gh.jobLog(p.owner, p.repo, j.id).lineSequence().map { LOG_TS.replaceFirst(it, "").trimEnd() }.filter { it.isNotBlank() }.toList()
            val hits = LinkedHashSet<String>()
            lines.forEachIndexed { i, l ->
                if (LOG_HIT.containsMatchIn(l) && !l.contains("##[group]") && !l.startsWith("at ")) {
                    hits += l.take(300)
                    if (l.contains("What went wrong")) lines.drop(i + 1).take(4).forEach { hits += it.take(300) }
                }
            }
            val use = if (hits.isEmpty()) lines.takeLast(40) else hits.take(60).toList()
            use.forEach { sb.append("  ").append(it).append('\n') }
        }
        return sb.toString().trimEnd().takeLast(6000)
    }
}

// ---------------------------------------------------------------------------------------------------- one-shot APK build
class GhBuildApk : Tool {
    override val spec = ToolSpec(
        "github_build_apk",
        "Build the project's APK with GitHub Actions, end to end, in one call: (optionally) commit & push local changes, trigger the workflow, wait until it finishes, download the APK, " +
            "save it to the phone's Downloads folder, and delete every temporary copy from the app's storage. Returns a file card for the APK. " +
            "If the build fails you get the compiler errors: fix the code, then call this again.",
        schema(
            emptyList(),
            "workflow" to prop("string", "Workflow file name. Default build-apk.yml (created automatically if missing)."),
            "commit_message" to prop("string", "If there are uncommitted local changes, commit and push them with this message before building."),
            "title" to prop("string", "A beautiful name for the APK card, e.g. 'Krow debug build'. Default: <repo> debug APK."),
            "timeout_minutes" to prop("integer", "Longest time to wait for the build. Default 25."),
            "delete_remote_artifact" to prop("boolean", "Also delete the artifact from GitHub afterwards. Default false."),
        ),
    )
    override val kind = "artifact"
    override val mutating = true
    override val trusted = true
    override fun title(a: JsonObject) = "Build APK"
    override fun cmd(a: JsonObject) = a.str("workflow") ?: "build-apk.yml"

    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val p = c.project
        if (p.kind != "repo") return ToolOut("This project is not a GitHub repository, so there is no CI to build with.", false)
        val gh = c.core.gh()
        val wf = a.str("workflow")?.trim()?.ifBlank { null } ?: "build-apk.yml"
        val timeoutMs = (a.int("timeout_minutes") ?: 25).coerceIn(3, 45) * 60_000L

        val wfFile = resolveIn(c.dir, ".github/workflows/$wf")
        if (!wfFile.exists() && wf == "build-apk.yml") { wfFile.parentFile?.mkdirs(); wfFile.writeText(apkWorkflowYaml()) }

        val base = GitOps.loadBase(c.dir)
        val changes = if (base != null) GitOps.status(c.dir, base) else emptyList()
        if (changes.isNotEmpty()) {
            val msg = a.str("commit_message")?.trim().orEmpty()
            if (msg.isBlank()) return ToolOut("There are ${changes.size} uncommitted local change(s), so a build would not include them. Call github_build_apk again with commit_message to commit & push them first.", false)
            withContext(Dispatchers.IO) { GitOps.commit(gh, p, c.dir, msg, null) }
        }

        val known = gh.workflows(p.owner, p.repo).any { it.path == wf || it.path.endsWith("/$wf") }
        if (!known) return ToolOut("GitHub has no workflow named $wf on ${p.owner}/${p.repo} yet. Push .github/workflows/$wf (git_commit) and call this again.", false)

        val before = gh.runs(p.owner, p.repo, wf, 5).maxOfOrNull { it.id } ?: 0L
        var sent = false
        for (i in 0 until 3) {
            if (sent) break
            try { ActionOps.trigger(gh, p, wf, emptyMap()); sent = true } catch (e: GhError) { if (i == 2) throw e; delay(3000) }
        }

        var runId = 0L
        try {
            val t0 = System.currentTimeMillis()
            var cur: GhRun? = null
            while (cur == null && System.currentTimeMillis() - t0 < 90_000) {
                delay(4000)
                cur = runCatching { gh.runs(p.owner, p.repo, wf, 5).filter { it.id > before }.maxByOrNull { it.id } }.getOrNull()
            }
            if (cur == null) return ToolOut("The workflow was triggered but no new run appeared within 90 seconds. Check github_list_runs.", false)
            runId = cur.id
            var errors = 0
            while (cur!!.status != "completed") {
                if (System.currentTimeMillis() - t0 > timeoutMs) {
                    runCatching { gh.cancelRun(p.owner, p.repo, runId) }
                    return ToolOut("The build was still running after ${timeoutMs / 60_000} minutes, so I cancelled run #$runId. ${cur.htmlUrl}", false)
                }
                delay(10_000)
                try { cur = gh.run(p.owner, p.repo, runId); errors = 0 } catch (e: GhError) { if (++errors >= 6) throw e }
            }
            if (cur.conclusion != "success") {
                val digest = runCatching { withContext(Dispatchers.IO) { CiLogs.digest(gh, p, runId) } }.getOrDefault("")
                return ToolOut("Build ${cur.conclusion ?: "failed"} (run #$runId, ${cur.htmlUrl}).\n\n" +
                    (if (digest.isNotBlank()) "Errors from the build log:\n$digest\n\n" else "") +
                    "Fix the problem, then call github_build_apk again with a commit_message.", false)
            }

            val tmp = File(c.core.app.cacheDir, "ci/$runId").also { it.deleteRecursively(); it.mkdirs() }
            try {
                val arts = gh.artifactsForRun(p.owner, p.repo, runId).filter { !it.expired }
                val art = arts.firstOrNull { it.name.contains("apk", true) } ?: arts.firstOrNull()
                    ?: return ToolOut("The build succeeded but uploaded no artifact. Check the workflow's upload step. ${cur.htmlUrl}", false)
                val zip = File(tmp, "artifact.zip")
                withContext(Dispatchers.IO) { gh.downloadArtifactZip(p.owner, p.repo, art.id, zip) }
                val apk = withContext(Dispatchers.IO) {
                    var found: File? = null
                    ZipInputStream(zip.inputStream().buffered()).use { z ->
                        while (true) {
                            val e = z.nextEntry ?: break
                            val n = e.name.substringAfterLast('/')
                            if (e.isDirectory || !n.endsWith(".apk", true) || n.contains("..")) continue
                            val f = File(tmp, n)
                            f.outputStream().use { z.copyTo(it) }
                            if (found == null) found = f
                        }
                    }
                    found
                } ?: return ToolOut("The artifact “${art.name}” contained no .apk file.", false)
                val nice = "${p.repo}-${p.branch.ifBlank { "main" }}-run$runId.apk".replace(Regex("[^A-Za-z0-9._-]"), "_")
                val saved = withContext(Dispatchers.IO) { saveToDownloadsEx(c.core.app, apk, nice) }
                    ?: return ToolOut("The APK was built but I could not save it to the Downloads folder (storage permission?).", false)
                if (a.bool("delete_remote_artifact") == true) runCatching { gh.deleteArtifact(p.owner, p.repo, art.id) }
                val summary = "Built from run #$runId · ${humanSize(apk.length())} · saved to Downloads/${saved.name}. Temporary files were deleted from the app."
                val cardTitle = a.str("title")?.trim()?.ifBlank { null } ?: (prettyName(p.repo) + " debug APK")
                return ToolOut("ARTIFACT\t${saved.uri?.toString() ?: "downloads/${saved.name}"}\tapk\t$cardTitle\n$summary")
            } finally {
                tmp.deleteRecursively()
            }
        } catch (e: CancellationException) {
            if (runId != 0L) withContext(NonCancellable) { runCatching { gh.cancelRun(p.owner, p.repo, runId) } }
            throw e
        }
    }
}

class GhRunLogs : Tool {
    override val spec = ToolSpec(
        "github_run_logs",
        "Show the errors from a failed (or any) GitHub Actions run: the failing job and step plus the compiler/Gradle error lines. Use it to find out why a build failed.",
        schema(listOf("run_id"), "run_id" to prop("integer", "Run id from github_list_runs")),
    )
    override val kind = "actions"
    override fun title(a: JsonObject) = "Read build log"
    override fun cmd(a: JsonObject) = a.int("run_id")?.let { "run $it" } ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val id = a.int("run_id")?.toLong() ?: throw IllegalArgumentException("Missing required argument 'run_id'")
        val text = withContext(Dispatchers.IO) { CiLogs.digest(c.core.gh(), c.project, id) }
        return ToolOut(text.ifBlank { "No log output found for run #$id." })
    }
}

class GhCancelRun : Tool {
    override val spec = ToolSpec("github_cancel_run", "Cancel a running GitHub Actions run.", schema(listOf("run_id"), "run_id" to prop("integer", "Run id")))
    override val kind = "actions"
    override val mutating = true
    override fun title(a: JsonObject) = "Cancel build"
    override fun cmd(a: JsonObject) = a.int("run_id")?.let { "run $it" } ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val id = a.int("run_id")?.toLong() ?: throw IllegalArgumentException("Missing required argument 'run_id'")
        c.core.gh().cancelRun(c.project.owner, c.project.repo, id)
        return ToolOut("Cancel requested for run #$id.")
    }
}

class GhRerun : Tool {
    override val spec = ToolSpec(
        "github_rerun",
        "Re-run a GitHub Actions run (all jobs, or only the failed ones). Handy for flaky network failures.",
        schema(listOf("run_id"), "run_id" to prop("integer", "Run id"), "failed_only" to prop("boolean", "Only re-run failed jobs. Default true.")),
    )
    override val kind = "actions"
    override val mutating = true
    override fun title(a: JsonObject) = "Re-run build"
    override fun cmd(a: JsonObject) = a.int("run_id")?.let { "run $it" } ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val id = a.int("run_id")?.toLong() ?: throw IllegalArgumentException("Missing required argument 'run_id'")
        c.core.gh().rerun(c.project.owner, c.project.repo, id, a.bool("failed_only") != false)
        return ToolOut("Re-run requested for run #$id. Use github_run_status to watch it.")
    }
}

class GhCreateRelease : Tool {
    override val spec = ToolSpec(
        "github_create_release",
        "Create a GitHub Release on the selected repository and optionally attach a file from the project (for example an APK you downloaded). Only when the user asks to publish a release.",
        schema(
            listOf("tag"),
            "tag" to prop("string", "Tag name, e.g. v1.2.0"),
            "name" to prop("string", "Release title. Default: the tag."),
            "notes" to prop("string", "Release notes in Markdown"),
            "file" to prop("string", "Optional project-relative path of a file to attach"),
            "prerelease" to prop("boolean", "Mark as pre-release"),
        ),
    )
    override val kind = "actions"
    override val mutating = true
    override val alwaysAsk = true
    override fun title(a: JsonObject) = "Create release ${a.str("tag") ?: ""}".trim()
    override fun cmd(a: JsonObject) = a.str("file") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val tag = a.str("tag")?.trim()?.ifBlank { null } ?: throw IllegalArgumentException("Missing required argument 'tag'")
        val p = c.project
        val gh = c.core.gh()
        val file = a.str("file")?.trim()?.ifBlank { null }?.let { resolveIn(c.dir, it) }
        if (file != null && !file.isFile) return ToolOut("File not found: ${a.str("file")}", false)
        val (id, url) = withContext(Dispatchers.IO) { gh.createRelease(p.owner, p.repo, tag, a.str("name")?.ifBlank { null } ?: tag, a.str("notes").orEmpty(), p.branch.ifBlank { "main" }, a.bool("prerelease") == true) }
        if (file != null) withContext(Dispatchers.IO) { gh.uploadReleaseAsset(p.owner, p.repo, id, file, file.name) }
        return ToolOut("Release $tag created on ${p.owner}/${p.repo}${if (file != null) " with ${file.name} attached" else ""}.\n$url")
    }
}
