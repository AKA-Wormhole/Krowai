package com.krow

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/*
 * Stage 3 — GitHub, scoped to ONE repository per project.
 *
 * Design notes
 *  - Everything goes through GitHub's REST API (there is no git binary on Android). A "commit" is therefore created
 *    directly on the remote branch (blobs -> tree -> commit -> ref update): commit and push are one step.
 *  - Each repo project stores its last known remote state in <project>/.krow/git.json (branch, commit sha, root tree
 *    sha, path -> blob sha). Status = hash the local files like git does and compare with that record.
 *  - owner/repo/branch ALWAYS come from the selected Project, never from model arguments.
 *  - The token is only ever sent to https://api.github.com (the zipball redirect is followed by hand WITHOUT it).
 */

class GhError(val code: Int, message: String) : Exception(message)

internal fun need(s: String?): String = s ?: throw GhError(0, "Unexpected response from GitHub")

class RepoInfo(val owner: String, val name: String, val priv: Boolean, val branch: String, val desc: String, val canPush: Boolean)

@Serializable
class GitEntry(val sha: String, val mode: String = "100644")

@Serializable
class GitBase(
    val branch: String,
    val commit: String,
    val tree: String,
    val files: Map<String, GitEntry>,
    val skip: List<String> = emptyList(), // symlinks: not tracked, never touched
)

class Change(val path: String, val kind: Char) // A added | M modified | D deleted

fun encRef(b: String): String = b.split('/').joinToString("/") { Uri.encode(it) }
private val NAME_RE = Regex("^[A-Za-z0-9_.-]+$")
fun validRepoPart(s: String) = NAME_RE.matches(s) && s != "." && s != ".."

class Gh(val http: OkHttpClient, private val token: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val api = "https://api.github.com"
    private val jsonAccept = "application/vnd.github+json"

    internal fun builder(path: String, accept: String): Request.Builder {
        require(path.startsWith("/")) { "bad path" }
        return Request.Builder().url(api + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Krow-Android")
    }

    /** Request builder for an absolute GitHub URL (e.g. uploads.github.com) with the token attached. */
    internal fun rawBuilder(url: String): Request.Builder = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", jsonAccept)
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "Krow-Android")

    internal fun fail(r: Response): Nothing {
        val body = r.body?.string().orEmpty()
        val msg = runCatching { (json.parseToJsonElement(body) as JsonObject).str("message") }.getOrNull() ?: body.take(200)
        val hint = when (r.code) {
            401 -> " — the GitHub token was rejected (expired or revoked). Update it in Settings → GitHub."
            403 -> if (r.header("X-RateLimit-Remaining") == "0") " — GitHub rate limit reached, try again later." else " — the token lacks permission for this."
            404 -> " — not found, or the token has no access to it (it needs Contents: read & write on this repository)."
            else -> ""
        }
        throw GhError(r.code, "GitHub ${r.code}: $msg$hint")
    }

    private fun exec(req: Request): JsonElement = http.newCall(req).execute().use { r ->
        if (!r.isSuccessful) fail(r)
        val t = r.body?.string().orEmpty()
        if (t.isBlank()) JsonNull else json.parseToJsonElement(t)
    }

    fun get(path: String): JsonElement = exec(builder(path, jsonAccept).get().build())

    fun send(method: String, path: String, body: JsonElement): JsonElement {
        val rb = json.encodeToString(JsonElement.serializer(), body).toRequestBody("application/json".toMediaType())
        return exec(builder(path, jsonAccept).method(method, rb).build())
    }

    /** Raw bytes of a blob. */
    fun rawBlob(owner: String, repo: String, sha: String): ByteArray {
        val req = builder("/repos/$owner/$repo/git/blobs/$sha", "application/vnd.github.raw+json").get().build()
        return http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) fail(r)
            r.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Downloads the zipball of one commit. The redirect to codeload is followed manually so the token is never sent there. */
    fun downloadZip(owner: String, repo: String, commit: String, out: File) {
        val noRedirect = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        noRedirect.newCall(builder("/repos/$owner/$repo/zipball/$commit", jsonAccept).get().build()).execute().use { r ->
            when {
                r.code in 301..308 -> {
                    val loc = r.header("Location") ?: throw GhError(r.code, "GitHub redirect without a location")
                    val u = loc.toHttpUrl()
                    if (u.scheme != "https") throw GhError(0, "Refusing a non-HTTPS download link")
                    http.newCall(Request.Builder().url(u).header("User-Agent", "Krow-Android").build()).execute().use { r2 ->
                        if (!r2.isSuccessful) throw GhError(r2.code, "Download failed (HTTP ${r2.code})")
                        out.outputStream().use { o -> r2.body?.byteStream()?.copyTo(o) }
                    }
                }
                r.isSuccessful -> out.outputStream().use { o -> r.body?.byteStream()?.copyTo(o) }
                else -> fail(r)
            }
        }
    }

    private fun info(o: JsonObject) = RepoInfo(
        owner = need(o["owner"]?.jsonObject?.str("login")),
        name = need(o.str("name")),
        priv = o.bool("private") == true,
        branch = o.str("default_branch") ?: "main",
        desc = o.str("description") ?: "",
        canPush = o["permissions"]?.jsonObject?.bool("push") ?: true,
    )

    fun me(): String = need(get("/user").jsonObject.str("login"))

    fun repos(page: Int): List<RepoInfo> =
        get("/user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member&page=$page").jsonArray.map { info(it.jsonObject) }

    fun repo(owner: String, name: String): RepoInfo {
        if (!validRepoPart(owner) || !validRepoPart(name)) throw IllegalArgumentException("Invalid repository name")
        return info(get("/repos/$owner/$name").jsonObject)
    }

    fun branches(owner: String, name: String): List<String> =
        get("/repos/$owner/$name/branches?per_page=100").jsonArray.mapNotNull { it.jsonObject.str("name") }

    fun createRepo(name: String, desc: String, priv: Boolean): RepoInfo {
        if (!validRepoPart(name)) throw IllegalArgumentException("Repository names may only contain letters, digits, '-', '_' and '.'")
        val body = buildJsonObject { put("name", name); put("description", desc); put("private", priv); put("auto_init", true) }
        return info(send("POST", "/user/repos", body).jsonObject)
    }
}

object GitOps {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val hashCache = ConcurrentHashMap<String, Triple<Long, Long, String>>()
    private const val MAX_FILE = 25L * 1024 * 1024
    private const val MAX_TEXT = 2_000_000
    private const val MAX_COMMIT_FILES = 300
    private const val MAX_PULL_FILES = 500

    private fun baseFile(dir: File) = File(dir, "$META_DIR/git.json")
    fun loadBase(dir: File): GitBase? = runCatching { json.decodeFromString(GitBase.serializer(), baseFile(dir).readText()) }.getOrNull()
    private fun needBase(dir: File): GitBase = loadBase(dir) ?: throw IllegalStateException("This project has no git state. Delete it and add the repository again.")
    private fun saveBase(dir: File, b: GitBase) {
        val f = baseFile(dir)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.encodeToString(GitBase.serializer(), b))
        tmp.renameTo(f)
    }

    fun ignored(path: String) = path == ".git" || path.startsWith(".git/") || path.contains("/.git/")

    /** Git blob id of a file: sha1("blob <size>\0" + bytes). */
    fun blobSha(f: File): String {
        val len = f.length(); val mod = f.lastModified()
        hashCache[f.path]?.let { if (it.first == len && it.second == mod) return it.third }
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob $len\u0000".toByteArray(Charsets.UTF_8))
        f.inputStream().use { s ->
            val buf = ByteArray(65536)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val h = md.digest().joinToString("") { "%02x".format(it) }
        hashCache[f.path] = Triple(len, mod, h)
        return h
    }

    private fun localFiles(dir: File): Map<String, File> {
        val base = dir.canonicalFile
        val m = sortedMapOf<String, File>()
        base.walkTopDown().onEnter { !(it.parentFile == base && it.name == META_DIR) }.forEach { f ->
            if (f.isFile) {
                val rel = f.relativeTo(base).path.replace(File.separatorChar, '/')
                if (!ignored(rel)) m[rel] = f
            }
        }
        return m
    }

    fun status(dir: File, b: GitBase): List<Change> {
        val local = localFiles(dir)
        val skip = b.skip.toHashSet()
        val out = ArrayList<Change>()
        for ((path, f) in local) {
            if (path in skip) continue
            val e = b.files[path]
            if (e == null) out += Change(path, 'A') else if (blobSha(f) != e.sha) out += Change(path, 'M')
        }
        for (path in b.files.keys) if (path !in local) out += Change(path, 'D')
        return out.sortedBy { it.path }
    }

    private fun matches(path: String, filter: String): Boolean {
        val x = filter.trim().trim('/')
        return x.isEmpty() || x == "." || path == x || path.startsWith("$x/")
    }

    private fun remoteHead(gh: Gh, p: Project): String {
        try {
            return need(gh.get("/repos/${p.owner}/${p.repo}/git/ref/heads/${encRef(p.branch)}").jsonObject["object"]?.jsonObject?.str("sha"))
        } catch (e: GhError) {
            if (e.code == 409) throw GhError(409, "This repository has no commits yet.")
            if (e.code == 404) throw GhError(404, "Branch '${p.branch}' was not found on ${p.owner}/${p.repo} (deleted or renamed?).")
            throw e
        }
    }

    private class Snap(val commit: String, val tree: String, val files: Map<String, GitEntry>, val skip: List<String>)

    private fun snapshot(gh: Gh, p: Project, commit: String): Snap {
        val c = gh.get("/repos/${p.owner}/${p.repo}/git/commits/$commit").jsonObject
        val tree = need(c["tree"]?.jsonObject?.str("sha"))
        val t = gh.get("/repos/${p.owner}/${p.repo}/git/trees/$tree?recursive=1").jsonObject
        if (t["truncated"]?.jsonPrimitive?.booleanOrNull == true)
            throw GhError(0, "This repository is too large for GitHub's tree listing (it was truncated), so Krow cannot track it safely.")
        val arr = t["tree"] as? JsonArray ?: throw GhError(0, "Unexpected response from GitHub")
        val files = LinkedHashMap<String, GitEntry>()
        val skip = ArrayList<String>()
        for (e in arr) {
            val o = e.jsonObject
            if (o.str("type") != "blob") continue
            val path = need(o.str("path"))
            if (path.substringBefore('/') == META_DIR || ignored(path)) continue
            val mode = o.str("mode") ?: "100644"
            if (mode == "120000") skip += path else files[path] = GitEntry(need(o.str("sha")), mode)
        }
        return Snap(commit, tree, files, skip)
    }

    private fun extractStripped(zip: File, dir: File) {
        val base = dir.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val rel = e.name.replace('\\', '/').substringAfter('/', "")
                if (rel.isEmpty() || rel.substringBefore('/') == META_DIR) continue
                val t = File(base, rel).canonicalFile
                if (!t.path.startsWith(base.path + File.separator)) throw IllegalStateException("Unsafe path in archive: $rel")
                if (e.isDirectory) t.mkdirs() else { t.parentFile?.mkdirs(); t.outputStream().use { o -> z.copyTo(o) } }
            }
        }
    }

    /** Downloads the branch head as a zipball into [dir] and records the remote state. Returns the branch used. */
    fun clone(gh: Gh, owner: String, repo: String, branch0: String, dir: File, progress: (String) -> Unit): String {
        if (!validRepoPart(owner) || !validRepoPart(repo)) throw IllegalArgumentException("Invalid repository name")
        progress("Reading repository…")
        var branch = branch0.ifBlank { need(gh.get("/repos/$owner/$repo").jsonObject.str("default_branch")) }
        var p = Project("tmp", repo, "repo", owner, repo, branch)
        val head = try {
            remoteHead(gh, p)
        } catch (e: GhError) {
            if (e.code != 409) throw e
            // Brand-new repository without any commit: create the first commit (README) so there is a branch to work on.
            progress("Setting up the empty repository…")
            val body = buildJsonObject { put("message", "Initial commit"); put("content", Base64.encodeToString("# $repo\n".toByteArray(), Base64.NO_WRAP)) }
            gh.send("PUT", "/repos/$owner/$repo/contents/README.md", body)
            val def = need(gh.get("/repos/$owner/$repo").jsonObject.str("default_branch"))
            p = Project("tmp", repo, "repo", owner, repo, def)
            remoteHead(gh, p)
        }
        branch = p.branch
        progress("Listing files…")
        val snap = snapshot(gh, p, head)
        progress("Downloading ${snap.files.size} files…")
        val zip = File.createTempFile("krow-clone", ".zip")
        try {
            gh.downloadZip(owner, repo, head, zip)
            progress("Extracting…")
            extractStripped(zip, dir)
        } finally { zip.delete() }
        saveBase(dir, GitBase(branch, head, snap.tree, snap.files, snap.skip))
        return branch
    }

    private fun isBin(b: ByteArray): Boolean { for (i in 0 until minOf(b.size, 8000)) if (b[i].toInt() == 0) return true; return false }

    // ------------------------------------------------------------------------------------------ tools' backends
    fun statusText(gh: Gh?, p: Project, dir: File): String {
        val b = needBase(dir)
        val ch = status(dir, b)
        val sb = StringBuilder("On branch ${b.branch} of ${p.owner}/${p.repo} (last synced commit ${b.commit.take(7)})\n")
        if (gh != null) runCatching {
            val head = remoteHead(gh, p)
            if (head != b.commit) sb.append("Remote has newer commits (${head.take(7)}). Run git_pull before committing.\n")
        }
        if (ch.isEmpty()) sb.append("Working tree clean — nothing to commit.")
        else {
            sb.append("${ch.size} changed file${if (ch.size == 1) "" else "s"}:\n")
            for (c in ch) sb.append(c.kind).append("  ").append(c.path).append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun diff(gh: Gh, p: Project, dir: File, filter: String?): String {
        val b = needBase(dir)
        val all = status(dir, b).filter { filter.isNullOrBlank() || matches(it.path, filter) }
        if (all.isEmpty()) return "No changes."
        val root = dir.canonicalFile
        val sb = StringBuilder()
        var shown = 0
        for (c in all) {
            if (shown >= 30) { sb.append("… ${all.size - shown} more changed files not shown. Pass a path to narrow the diff.\n"); break }
            shown++
            val f = File(root, c.path)
            val old: ByteArray? = if (c.kind == 'A') null else gh.rawBlob(p.owner, p.repo, b.files.getValue(c.path).sha)
            val new: ByteArray? = if (c.kind == 'D') null else f.takeIf { it.length() <= MAX_FILE }?.readBytes()
            val label = when (c.kind) { 'A' -> "new file"; 'D' -> "deleted"; else -> "modified" }
            sb.append("# ").append(label).append(": ").append(c.path).append('\n')
            if ((old != null && (isBin(old) || old.size > MAX_TEXT)) || (new != null && (isBin(new) || new.size > MAX_TEXT)) || (c.kind != 'D' && new == null)) {
                sb.append("(binary or large file — content not shown)\n\n"); continue
            }
            val d = Diff.unified(c.path, old?.toString(Charsets.UTF_8) ?: "", new?.toString(Charsets.UTF_8) ?: "")
            sb.append(if (d.isEmpty()) "(empty file)" else d).append("\n\n")
        }
        return sb.toString().trimEnd()
    }

    fun log(gh: Gh, p: Project, n: Int): String {
        val arr = gh.get("/repos/${p.owner}/${p.repo}/commits?sha=${Uri.encode(p.branch)}&per_page=${n.coerceIn(1, 50)}").jsonArray
        if (arr.isEmpty()) return "No commits."
        return arr.joinToString("\n") { e ->
            val o = e.jsonObject
            val c = o["commit"]?.jsonObject
            val msg = c?.str("message").orEmpty().lineSequence().firstOrNull().orEmpty()
            val who = c?.get("author")?.jsonObject?.str("name").orEmpty()
            val date = c?.get("author")?.jsonObject?.str("date").orEmpty().take(10)
            "${o.str("sha").orEmpty().take(7)}  $date  $who  $msg"
        }
    }

    /** Commit + push in one step (REST API). */
    fun commit(gh: Gh, p: Project, dir: File, message: String, paths: List<String>?, tries: Int = 0): String {
        if (message.isBlank()) throw IllegalArgumentException("A commit message is required.")
        val b = needBase(dir)
        val head = remoteHead(gh, p)
        if (head != b.commit) {
            // Remote moved on: fast-forward first (never overwrites local edits), then commit on top of it.
            if (tries >= 2) throw IllegalStateException("The remote branch '${p.branch}' keeps changing. Run git_pull and try again.")
            val r = pull(gh, p, dir)
            if (r.contains("changed both here and upstream")) throw IllegalStateException("The remote branch had new commits that touch files you also changed, so nothing was pushed.\n\n$r")
            return commit(gh, p, dir, message, paths, tries + 1)
        }
        var ch = status(dir, b)
        if (!paths.isNullOrEmpty()) ch = ch.filter { c -> paths.any { matches(c.path, it) } }
        if (ch.isEmpty()) return "Nothing to commit."
        if (ch.size > MAX_COMMIT_FILES) throw IllegalArgumentException("${ch.size} changed files is more than one commit can carry here (limit $MAX_COMMIT_FILES). Commit a subset with the paths argument.")
        val root = dir.canonicalFile
        val repoPath = "/repos/${p.owner}/${p.repo}"
        val newEntries = HashMap<String, GitEntry>()
        val tree = ArrayList<JsonObject>()
        for (c in ch) {
            val mode = b.files[c.path]?.mode ?: "100644"
            if (c.kind == 'D') {
                tree += buildJsonObject { put("path", c.path); put("mode", mode); put("type", "blob"); put("sha", JsonNull) }
            } else {
                val f = File(root, c.path)
                if (f.length() > MAX_FILE) throw IllegalArgumentException("${c.path} is larger than ${humanSize(MAX_FILE)}; too big to commit from the phone.")
                val body = buildJsonObject { put("content", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)); put("encoding", "base64") }
                val sha = need(gh.send("POST", "$repoPath/git/blobs", body).jsonObject.str("sha"))
                newEntries[c.path] = GitEntry(sha, mode)
                tree += buildJsonObject { put("path", c.path); put("mode", mode); put("type", "blob"); put("sha", sha) }
            }
        }
        val newTree = need(gh.send("POST", "$repoPath/git/trees", buildJsonObject { put("base_tree", b.tree); put("tree", JsonArray(tree)) }).jsonObject.str("sha"))
        val newCommit = need(gh.send("POST", "$repoPath/git/commits", buildJsonObject {
            put("message", message.trim()); put("tree", newTree); put("parents", buildJsonArray { add(b.commit) })
        }).jsonObject.str("sha"))
        gh.send("PATCH", "$repoPath/git/refs/heads/${encRef(p.branch)}", buildJsonObject { put("sha", newCommit); put("force", false) })
        val files = b.files.toMutableMap()
        for (c in ch) if (c.kind == 'D') files.remove(c.path) else newEntries[c.path]?.let { files[c.path] = it }
        saveBase(dir, GitBase(b.branch, newCommit, newTree, files, b.skip))
        val a = ch.count { it.kind == 'A' }; val m = ch.count { it.kind == 'M' }; val d = ch.count { it.kind == 'D' }
        return "Committed ${newCommit.take(7)} and pushed to ${p.owner}/${p.repo}@${p.branch}: ${ch.size} file${if (ch.size == 1) "" else "s"} (added $a, modified $m, deleted $d)\n${message.trim().lineSequence().first()}"
    }

    private fun pruneEmpty(d: File?, root: File) {
        var x = d
        while (x != null && x != root && x.isDirectory && x.list().isNullOrEmpty()) { x.delete(); x = x.parentFile }
    }

    /** Fast-forwards the working folder to the remote head. Locally changed files are never overwritten (reported as conflicts). */
    fun pull(gh: Gh, p: Project, dir: File): String {
        val b = needBase(dir)
        val head = remoteHead(gh, p)
        if (head == b.commit) return "Already up to date (${head.take(7)})."
        val snap = snapshot(gh, p, head)
        val paths = (b.files.keys + snap.files.keys).filter { b.files[it]?.sha != snap.files[it]?.sha }.sorted()
        if (paths.size > MAX_PULL_FILES) throw IllegalStateException("${paths.size} files changed upstream — too many to fetch one by one. Delete this project and add the repository again to re-clone it.")
        val root = dir.canonicalFile
        var upd = 0; var add = 0; var del = 0
        val conflicts = ArrayList<String>()
        for (path in paths) {
            val f = try { resolveIn(root, path) } catch (e: IllegalArgumentException) { continue }
            val localSha = if (f.isFile) blobSha(f) else null
            val baseSha = b.files[path]?.sha
            val remote = snap.files[path]
            if (localSha == remote?.sha) continue
            if (localSha != baseSha) { conflicts += path; continue }
            if (remote == null) { f.delete(); pruneEmpty(f.parentFile, root); del++ }
            else {
                val bytes = gh.rawBlob(p.owner, p.repo, remote.sha)
                f.parentFile?.mkdirs(); f.writeBytes(bytes)
                if (baseSha == null) add++ else upd++
            }
        }
        saveBase(dir, GitBase(b.branch, head, snap.tree, snap.files, snap.skip))
        val sb = StringBuilder("Pulled ${p.owner}/${p.repo}@${p.branch} → ${head.take(7)}: updated $upd, added $add, removed $del.")
        if (conflicts.isNotEmpty()) {
            sb.append("\n\n${conflicts.size} file${if (conflicts.size == 1) "" else "s"} changed both here and upstream; your local version was kept:\n")
            conflicts.take(40).forEach { sb.append("  ").append(it).append('\n') }
            sb.append("They now show as changes against the new upstream. Commit to overwrite upstream with your version, or git_restore a path to take the upstream version.")
        }
        return sb.toString().trimEnd()
    }

    /** Discards local changes (back to the last synced state) for a path, a folder, or "." for everything. */
    fun restore(gh: Gh, p: Project, dir: File, path: String): String {
        val b = needBase(dir)
        val root = dir.canonicalFile
        val ch = status(dir, b).filter { matches(it.path, path) }
        if (ch.isEmpty()) return "Nothing to restore — no local changes there."
        var n = 0
        for (c in ch) {
            val f = resolveIn(root, c.path)
            if (c.kind == 'A') { f.delete(); pruneEmpty(f.parentFile, root) }
            else {
                val bytes = gh.rawBlob(p.owner, p.repo, b.files.getValue(c.path).sha)
                f.parentFile?.mkdirs(); f.writeBytes(bytes)
            }
            n++
        }
        return "Restored $n file${if (n == 1) "" else "s"} to the last synced version (${b.commit.take(7)})."
    }
}

// ---------------------------------------------------------------------------------------------------- tools
private fun JsonObject.strList(k: String): List<String>? = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

class GitStatus : Tool {
    override val spec = ToolSpec("git_status", "Show the branch and the files added (A), modified (M) or deleted (D) compared with the last synced state of the selected GitHub repository. Also says if the remote has newer commits.", schema(emptyList()))
    override val kind = "git"
    override fun title(a: JsonObject) = "Git status"
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val gh = runCatching { c.core.gh() }.getOrNull()
        return ToolOut(GitOps.statusText(gh, c.project, c.dir))
    }
}

class GitDiff : Tool {
    override val spec = ToolSpec("git_diff", "Show a unified diff of local changes against the last synced state. Optionally limit to a file or folder.",
        schema(emptyList(), "path" to prop("string", "File or folder relative to the project root. Default: all changes.")))
    override val kind = "git"
    override fun title(a: JsonObject) = "Git diff"
    override fun cmd(a: JsonObject) = a.str("path") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject) = ToolOut(GitOps.diff(c.core.gh(), c.project, c.dir, a.str("path")))
}

class GitLog : Tool {
    override val spec = ToolSpec("git_log", "List recent commits on the selected branch of the GitHub repository (newest first).",
        schema(emptyList(), "limit" to prop("integer", "How many commits (1-50). Default 15.")))
    override val kind = "git"
    override fun title(a: JsonObject) = "Git log"
    override suspend fun run(c: ToolCtx, a: JsonObject) = ToolOut(GitOps.log(c.core.gh(), c.project, a.int("limit") ?: 15))
}

class GitCommit : Tool {
    override val spec = ToolSpec("git_commit", "Commit local changes and push them to the selected branch of the GitHub repository in one step (the REST API has no separate local commit). If the remote has newer commits it pulls them first automatically (and stops only on conflicts).",
        schema(listOf("message"), "message" to prop("string", "Commit message"),
            "paths" to buildJsonObject { put("type", "array"); put("description", "Only commit these files/folders. Default: every change."); putJsonObject("items") { put("type", "string") } }))
    override val kind = "git"
    override val mutating = true
    override val trusted = true // the AI pushes by itself, never asks (scope is locked to the selected repository)
    override fun title(a: JsonObject) = "Commit & push"
    override fun cmd(a: JsonObject) = (a.str("message") ?: "").lineSequence().firstOrNull().orEmpty()
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val msg = a.str("message") ?: throw IllegalArgumentException("Missing required argument 'message'")
        return ToolOut(GitOps.commit(c.core.gh(), c.project, c.dir, msg, a.strList("paths")))
    }
}

class GitPull : Tool {
    override val spec = ToolSpec("git_pull", "Fetch new commits from the remote branch into the project folder. Files you changed locally are never overwritten; conflicts are reported.", schema(emptyList()))
    override val kind = "git"
    override val mutating = true
    override val trusted = true
    override fun title(a: JsonObject) = "Git pull"
    override suspend fun run(c: ToolCtx, a: JsonObject) = ToolOut(GitOps.pull(c.core.gh(), c.project, c.dir))
}

class GitRestore : Tool {
    override val spec = ToolSpec("git_restore", "Discard local changes and return a file or folder to the last synced version ('.' = everything). Added files are deleted; this cannot be undone.",
        schema(listOf("path"), "path" to prop("string", "File or folder relative to the project root, or '.' for all changes")))
    override val kind = "git"
    override val mutating = true
    override val alwaysAsk = true // destroys work
    override fun title(a: JsonObject) = "Git restore"
    override fun cmd(a: JsonObject) = a.str("path") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val path = a.str("path") ?: throw IllegalArgumentException("Missing required argument 'path'")
        return ToolOut(GitOps.restore(c.core.gh(), c.project, c.dir, path))
    }
}

/** Creates a NEW repository for the user. It does not touch any other repository; the user then selects it in the repo picker. */
class GitHubCreateRepo : Tool {
    override val spec = ToolSpec("github_create_repo", "Create a new GitHub repository (with a README) on the user's account. Only when the user asks for it. It is cloned and linked automatically as the working repository, so you can commit and push to it right afterwards.",
        schema(listOf("name"), "name" to prop("string", "Repository name (letters, digits, - _ .)"),
            "description" to prop("string", "Short description"), "private" to prop("boolean", "Private repository. Default true.")))
    override val kind = "git"
    override val mutating = true
    override val alwaysAsk = true
    override fun title(a: JsonObject) = "Create GitHub repository"
    override fun cmd(a: JsonObject) = a.str("name") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val name = a.str("name") ?: throw IllegalArgumentException("Missing required argument 'name'")
        val r = c.core.gh().createRepo(name, a.str("description") ?: "", a.bool("private") ?: true)
        // Clone it and lock Krow to it, so git_commit / push work from the very next step (no manual selection needed).
        return try {
            val id = c.core.addRepoProject(r.owner, r.name, r.branch) { }
            val n = c.core.linkRepo(id)
            ToolOut("Created ${if (r.priv) "private" else "public"} repository ${r.owner}/${r.name} (branch ${r.branch}) and linked it as the working repository" +
                (if (n > 0) "; $n file${if (n == 1) "" else "s"} from the Workspace were copied in as uncommitted changes" else "") + ". Git tools are available from your next step: commit and push whenever the user asks.")
        } catch (e: Exception) {
            ToolOut("Created ${r.owner}/${r.name}, but linking it failed: ${e.message}. The user can pick it in Settings → GitHub → Repository.", false)
        }
    }
}
