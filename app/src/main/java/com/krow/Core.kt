package com.krow

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** App-wide state holder + agent loop. One instance, created in KrowApp. */
class Core(val app: Application) {
    init { Attachments.init(app) }
    init { Attachments.profile = Store(app).load().settings.imgQuality }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    val secrets = Secrets(app)
    val memory by lazy { MemoryStore(app) }
    private val store = Store(app)
    private val _s = MutableStateFlow(normalize(store.load()))
    val state: StateFlow<AppState> = _s.asStateFlow()
    val busy = MutableStateFlow<String?>(null)      // chat id currently running
    val live = MutableStateFlow<Live?>(null)        // streaming text (not persisted)
    val notice = MutableStateFlow<String?>(null)    // transient message at the bottom of the screen
    val incomingText = MutableStateFlow<String?>(null)                 // text / link shared into Krow from another app
    val search by lazy { WebSearch(this) }
    val projectsRoot = File(app.filesDir, "projects").also { it.mkdirs() }
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var job: Job? = null
    private var saveJob: Job? = null
    private val jsonLenient = Json { ignoreUnknownKeys = true }

    // ---------- state helpers ----------
    private fun normalize(s: AppState) = s.copy(chats = s.chats.map { c ->
        c.copy(msgs = c.msgs.map { m -> if (m.role == "tool" && (m.status == "pending" || m.status == "running")) m.copy(status = "stopped") else m })
    })

    private fun mutate(f: (AppState) -> AppState) { _s.update(f); saveSoon() }
    private fun saveSoon() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(300); val s = _s.value; withContext(Dispatchers.IO) { store.save(s) } }
    }

    val settings get() = _s.value.settings
    fun chat(id: String?): Chat? = _s.value.chats.firstOrNull { it.id == id }
    fun project(id: String): Project = _s.value.projects.firstOrNull { it.id == id } ?: _s.value.projects.first()
    fun projectDir(p: Project): File = File(projectsRoot, p.id).also { it.mkdirs() }
    fun tokensToday(s: AppState = _s.value) = if (s.tokDay == LocalDate.now().toString()) s.tokens else 0
    private fun addTokens(n: Int) {
        if (n <= 0) return
        val d = LocalDate.now().toString()
        mutate { s -> if (s.tokDay == d) s.copy(tokens = s.tokens + n) else s.copy(tokDay = d, tokens = n) }
    }

    // ---------- chats ----------
    fun newChat(): String {
        val s = _s.value
        val linked = linkedRepo()
        s.activeChat?.let { id -> chat(id)?.let { if (it.msgs.isEmpty() && (linked != null || s.activeProject.isEmpty() || it.projectId == s.activeProject)) return id } }
        val c = Chat(uid(), projectId = linked?.id ?: s.activeProject.ifEmpty { s.projects.first().id })
        mutate { it.copy(chats = listOf(c) + it.chats, activeChat = c.id) }
        return c.id
    }
    fun openChat(id: String) = mutate { it.copy(activeChat = id) }
    fun deleteChat(id: String) {
        if (busy.value == id) stop()
        chat(id)?.msgs?.forEach { m -> m.images.forEach { Attachments.delete(it) } }
        mutate { s -> s.copy(chats = s.chats.filterNot { it.id == id }, activeChat = if (s.activeChat == id) null else s.activeChat) }
    }
    fun renameChat(id: String, t: String) = mutate { s -> s.copy(chats = s.chats.map { if (it.id == id) it.copy(title = t.trim().ifBlank { "New chat" }) else it }) }
    fun pinChat(id: String) = mutate { s -> s.copy(chats = s.chats.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }) }
    fun clearAllChats() { stop(); mutate { it.copy(chats = emptyList(), activeChat = null) } }
    private fun ensureChat(): String = _s.value.activeChat?.takeIf { chat(it) != null } ?: newChat()

    private fun append(chatId: String, m: Msg) =
        mutate { s -> s.copy(chats = s.chats.map { if (it.id == chatId) it.copy(msgs = it.msgs + m, updated = now()) else it }) }
    private fun updateTool(chatId: String, callId: String, f: (Msg) -> Msg) =
        mutate { s -> s.copy(chats = s.chats.map { c -> if (c.id != chatId) c else c.copy(msgs = c.msgs.map { m -> if (m.role == "tool" && m.callId == callId) f(m) else m }) }) }

    // ---------- projects ----------
    fun setActiveProject(id: String) = mutate { it.copy(activeProject = id) }
    fun addProject(name: String): String {
        val id = uid()
        mutate { s -> s.copy(projects = s.projects + Project(id, name.trim().ifBlank { "Project" }, color = PROJECT_COLORS[s.projects.size % PROJECT_COLORS.size]), activeProject = id) }
        File(projectsRoot, id).mkdirs()
        return id
    }
    /** The repository project Krow is locked to (set in Settings → GitHub → Repository), or null. */
    fun linkedRepo(): Project? = _s.value.projects.firstOrNull { it.id == _s.value.settings.repoProject && it.kind == "repo" }

    /** The project the AI and the Files screen really work in: the linked repository when there is one, otherwise the chat's own project. */
    fun workProject(chatProjectId: String): Project = linkedRepo() ?: project(chatProjectId)

    /**
     * Locks Krow to this repository project. Files that were created in the plain Workspace (and do not exist in the repo yet)
     * are copied in as ordinary uncommitted changes, so e.g. a main.py written before linking can be pushed right away.
     * Returns how many files were copied.
     */
    suspend fun linkRepo(id: String): Int {
        val p = _s.value.projects.firstOrNull { it.id == id && it.kind == "repo" } ?: throw IllegalStateException("That repository is not available.")
        val already = _s.value.settings.repoProject == id
        mutate { s -> s.copy(settings = s.settings.copy(repoProject = id), activeProject = "") }
        if (already) return 0
        return withContext(Dispatchers.IO) { importWorkspaceFiles(p) }
    }

    fun unlinkRepo() = mutate { s -> s.copy(settings = s.settings.copy(repoProject = "")) }

    private fun importWorkspaceFiles(p: Project): Int {
        val src = File(projectsRoot, "scratch").canonicalFile
        if (!src.isDirectory) return 0
        val dst = projectDir(p).canonicalFile
        val skipTop = setOf(META_DIR, "artifacts", "images", ".git")
        var n = 0
        src.walkTopDown().onEnter { !(it.parentFile == src && it.name in skipTop) }.forEach { f ->
            if (!f.isFile || f.length() > 10L * 1024 * 1024) return@forEach
            val rel = f.relativeTo(src).path.replace(File.separatorChar, '/')
            val target = try { resolveIn(dst, rel) } catch (e: IllegalArgumentException) { return@forEach }
            if (target.exists()) return@forEach
            target.parentFile?.mkdirs()
            f.copyTo(target)
            n++
        }
        return n
    }

    fun deleteProject(id: String) {
        if (id == "scratch") return
        if (busy.value != null && chat(busy.value)?.projectId == id) stop()
        mutate { s -> s.copy(settings = if (s.settings.repoProject == id) s.settings.copy(repoProject = "") else s.settings,
            projects = s.projects.filterNot { it.id == id }, chats = s.chats.filterNot { it.projectId == id },
            activeProject = if (s.activeProject == id) "scratch" else s.activeProject,
            activeChat = if (chat(s.activeChat)?.projectId == id) null else s.activeChat) }
        scope.launch(Dispatchers.IO) { File(projectsRoot, id).deleteRecursively() }
    }

    // ---------- GitHub ----------
    fun githubToken(): String? = secrets.get(Secrets.GITHUB)
    fun gh(): Gh = Gh(http, githubToken() ?: throw IllegalStateException("No GitHub token saved. Open Settings → GitHub and add one."))

    /** Clones owner/repo@branch into a new repo project (or selects the existing one). Throws on failure, leaving nothing behind. */
    suspend fun addRepoProject(owner: String, repo: String, branch: String, progress: (String) -> Unit): String {
        _s.value.projects.firstOrNull { it.kind == "repo" && it.owner.equals(owner, true) && it.repo.equals(repo, true) && (branch.isBlank() || it.branch == branch) }?.let { setActiveProject(it.id); return it.id }
        val gh = gh()
        val id = uid()
        val dir = File(projectsRoot, id).also { it.mkdirs() }
        try {
            val br = withContext(Dispatchers.IO) { GitOps.clone(gh, owner, repo, branch, dir, progress) }
            mutate { s -> s.copy(projects = s.projects + Project(id, repo, "repo", owner, repo, br, PROJECT_COLORS[s.projects.size % PROJECT_COLORS.size]), activeProject = id) }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
            throw e
        }
        return id
    }

    // ---------- settings ----------
    fun setSettings(f: (Settings) -> Settings) { mutate { it.copy(settings = f(it.settings)) }; Attachments.profile = _s.value.settings.imgQuality }
    fun setMode(m: String) = setSettings { it.copy(mode = m) }
    fun apiKey(): String? = secrets.get(Secrets.apiKeyName(settings.baseUrl))
    fun say(msg: String) { notice.value = msg }

    suspend fun fetchModels(): List<String> {
        val s = settings
        val key = apiKey() ?: throw IllegalStateException("Save an API key for this provider first.")
        return fetchModelIds(http, s.provider, s.baseUrl, key)
    }

    private fun llm(): Llm {
        val s = settings
        val key = apiKey() ?: throw IllegalStateException("No API key saved for ${s.baseUrl}. Open Settings → AI provider and save one.")
        if (s.model.isBlank()) throw IllegalStateException("No model selected. Add one in Settings → AI provider.")
        return when {
            s.provider == "anthropic" -> AnthropicLlm(http, s.baseUrl, key, s.model)
            s.provider == "gemini" || s.baseUrl.contains("generativelanguage.googleapis.com", true) -> GeminiLlm(http, s.baseUrl, key, s.model)
            else -> OpenAiLlm(http, s.baseUrl, key, s.model)
        }
    }

    // ---------- agent ----------
    fun send(text: String, files: List<Attach> = emptyList()) {
        if (busy.value != null) return
        val body = text.trim()
        if (body.isBlank() && files.isEmpty()) return
        val id = ensureChat()
        if (chat(id)?.title == "New chat") {
            val t = body.lineSequence().firstOrNull().orEmpty().take(48)
                .ifBlank { files.firstOrNull()?.let { if (it.image != null) "Image" else it.name } ?: "New chat" }
            renameChat(id, t)
            needTitle[id] = t.trim()
        }
        append(id, Msg("user", body, images = files.mapNotNull { it.image }, files = files.filter { it.image == null }.map { FileRef(it.name, it.text) }))
        start(id)
    }

    fun retry() {
        val id = _s.value.activeChat ?: return
        if (busy.value != null) return
        val c = chat(id) ?: return
        val lastUser = c.msgs.indexOfLast { it.role == "user" }
        if (lastUser < 0) return
        mutate { s -> s.copy(chats = s.chats.map { if (it.id == id) it.copy(msgs = it.msgs.take(lastUser + 1)) else it }) }
        start(id)
    }

    /** Replace one of the user's messages (matched by timestamp), drop everything after it and ask again. */
    fun editAndResend(msgAt: Long, newText: String) {
        val id = _s.value.activeChat ?: return
        if (busy.value != null) return
        val c = chat(id) ?: return
        val idx = c.msgs.indexOfFirst { it.role == "user" && it.at == msgAt }
        if (idx < 0) return
        val body = newText.trim()
        val old = c.msgs[idx]
        if (body.isBlank() && old.images.isEmpty() && old.files.isEmpty()) return
        c.msgs.drop(idx + 1).forEach { m -> m.images.forEach { Attachments.delete(it) } }
        mutate { s -> s.copy(chats = s.chats.map { if (it.id == id) it.copy(msgs = it.msgs.take(idx) + old.copy(text = body, at = now()), updated = now()) else it }) }
        start(id)
    }

    private val needTitle = HashMap<String, String>()

    /** Adds the optional step_name argument to every tool so the model can give each action a good name. */
    private fun named(s: ToolSpec): ToolSpec {
        val props = s.schema["properties"] as? JsonObject ?: return s
        val withName = JsonObject(props + ("step_name" to prop("string", "Short, specific, human-friendly name for this action (2-5 words), e.g. 'Build debug APK'. Shown to the user in the chat.")))
        return ToolSpec(s.name, s.description, JsonObject(s.schema + ("properties" to withName)))
    }

    /** After the first answer in a new chat, ask the model for a short, specific title. Best effort. */
    private fun maybeTitle(id: String) {
        val fallback = needTitle.remove(id) ?: return
        scope.launch {
            runCatching {
                val c = chat(id) ?: return@launch
                val u = c.msgs.firstOrNull { it.role == "user" }?.text.orEmpty().take(600)
                val a = c.msgs.lastOrNull { it.role == "assistant" && it.calls.isEmpty() }?.text.orEmpty().take(600)
                if (u.isBlank() && a.isBlank()) return@launch
                val r = llm().stream(
                    "You name chats. Reply with ONLY a short, specific title of 2 to 5 words, in the language the user wrote in. No quotes, no trailing punctuation, no emoji, no prefix like 'Chat about'.",
                    listOf(Msg("user", "User message:\n$u\n\nAssistant reply:\n$a\n\nTitle:")), emptyList(),
                ) { }
                val t = r.text.trim().lineSequence().firstOrNull().orEmpty().trim().trim('"', '\'', '*', '#', '.', ' ').take(48)
                if (t.isNotBlank() && chat(id)?.title == fallback) renameChat(id, t)
            }
        }
    }

    fun stop() { job?.cancel() }
    fun approve(callId: String, ok: Boolean) { approvals[callId]?.complete(ok) }

    private fun start(id: String) { busy.value = id; job = scope.launch { runAgent(id) } }

    private fun system(p: Project, userText: String = ""): String = buildString {
        append("You are Krow, an AI assistant running inside a native Android app. Today is ${LocalDate.now()}. ")
        append("Be accurate and concise. Write the way a thoughtful person talks: plain, natural paragraphs by default. ")
        append("Use a bullet list only when the content really is a list of parallel items, keep each item to a sentence or two, never nest bullets, never bold every item, and skip headings in short answers. Use Markdown only where it helps (code blocks, links, the occasional list). No emojis unless the user uses them. ")
        append(Tools.systemNotes(this@Core, p))
        if (settings.memory != "off") append(memory.prompt(userText))
        val ins = settings.instructions
        if (ins.isNotBlank()) append("\n\nUser instructions:\n").append(ins)
    }

    /** Messages as sent to the API: failed-turn notes removed, every tool call guaranteed to have a result. */
    private fun history(c: Chat): List<Msg> {
        val out = ArrayList<Msg>()
        val ms = c.msgs
        var i = 0
        while (i < ms.size) {
            val m = ms[i]
            if (m.role == "assistant" && m.calls.isNotEmpty()) {
                out += m
                val have = HashSet<String>()
                var j = i + 1
                while (j < ms.size && ms[j].role == "tool") { out += ms[j]; ms[j].callId?.let { have += it }; j++ }
                m.calls.filter { it.id !in have }.forEach { out += Msg("tool", "Cancelled before it ran.", callId = it.id, tool = it.name, status = "stopped") }
                i = j
                continue
            }
            if (m.role == "assistant" && (m.text.isBlank() || m.status == "fail")) { i++; continue }
            if (m.role != "tool") out += m
            i++
        }
        return out
    }

    private suspend fun runAgent(chatId: String) {
        try {
            val chat0 = chat(chatId) ?: return
            val llm = llm()
            val model = settings.model
            for (step in 0 until MAX_STEPS) {
                val mode = settings.mode
                // Resolved every step: if a repository gets linked mid-turn (e.g. the AI just created one), git tools appear on the next step.
                val project = workProject(chat(chatId)?.projectId ?: chat0.projectId)
                val tools = Tools.available(this, project, mode)
                live.value = Live(chatId, "")
                val t0 = now()
                val res = llm.stream(system(project, chat(chatId)?.msgs?.lastOrNull { it.role == "user" }?.text.orEmpty()), history(chat(chatId) ?: return), tools.map { named(it.spec) }, { d -> live.update { l -> l?.copy(thought = l.thought + d) } }) { d ->
                    live.update { l -> l?.copy(text = l.text + d) }
                }
                val genMs = now() - t0
                live.value = null
                append(chatId, Msg("assistant", res.text, res.calls, model = model, inTok = res.inTok, outTok = res.outTok, genMs = genMs, thought = res.thought.trim()))
                addTokens(res.inTok + res.outTok)
                if (res.calls.isEmpty()) { maybeTitle(chatId); return }
                for (c in res.calls) executeTool(chatId, project, c, tools, mode)
                if (step == MAX_STEPS - 1) append(chatId, Msg("assistant", "Stopped after $MAX_STEPS steps. Say “continue” to keep going."))
            }
        } catch (e: CancellationException) {
            live.value?.takeIf { it.text.isNotBlank() }?.let { append(chatId, Msg("assistant", it.text + "\n\n*(stopped)*")) }
            throw e
        } catch (e: Throwable) {
            live.value?.takeIf { it.text.isNotBlank() }?.let { append(chatId, Msg("assistant", it.text)) }
            append(chatId, Msg("assistant", "⚠ " + friendly(e), status = "fail"))
        } finally {
            live.value = null
            busy.value = null
        }
    }

    private suspend fun executeTool(chatId: String, project: Project, call: ToolCall, tools: List<Tool>, mode: String) {
        val tool = tools.firstOrNull { it.spec.name == call.name }
        if (tool == null) {
            append(chatId, Msg("tool", "Unknown or unavailable tool '${call.name}' in the current mode.", callId = call.id, tool = call.name, title = call.name, status = "fail"))
            return
        }
        val args: JsonObject = runCatching { jsonLenient.parseToJsonElement(call.args.ifBlank { "{}" }) as JsonObject }.getOrElse { JsonObject(emptyMap()) }
        val needs = tool.mutating && (tool.alwaysAsk || (!tool.trusted && mode == "ask"))
        append(chatId, Msg("tool", callId = call.id, tool = call.name, kind = tool.kind, title = (if (tool.kind == "artifact") null else args.str("step_name")?.trim()?.take(60)?.ifBlank { null }) ?: tool.title(args), cmd = tool.cmd(args), status = if (needs) "pending" else "running"))
        try {
            if (needs) {
                val d = CompletableDeferred<Boolean>()
                approvals[call.id] = d
                val ok = try { d.await() } finally { approvals.remove(call.id) }
                if (!ok) { updateTool(chatId, call.id) { it.copy(status = "denied", text = "The user denied this action.") }; return }
                updateTool(chatId, call.id) { it.copy(status = "running") }
            }
            val t0 = now()
            val out = try {
                withContext(Dispatchers.IO) { tool.run(ToolCtx(this@Core, project, projectDir(project)), args) }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { ToolOut("Error: ${e.message ?: e}", false) }
            updateTool(chatId, call.id) { it.copy(text = clip(out.text), status = if (out.ok) "done" else "fail", exit = out.exit, ms = now() - t0) }
        } catch (e: CancellationException) {
            updateTool(chatId, call.id) { it.copy(status = "stopped", text = "Stopped by user.") }
            throw e
        }
    }

    private fun clip(s: String, max: Int = 24_000) =
        if (s.length <= max) s else s.take(max * 2 / 3) + "\n…[${s.length - max} characters omitted]…\n" + s.takeLast(max / 3)

    private fun friendly(e: Throwable): String = when {
        e is ApiError && (e.code == 401 || e.code == 403) -> "The API rejected the key (HTTP ${e.code}). Check Settings → AI provider.\n\n${e.body.take(300)}"
        e is ApiError -> "API error ${e.code}: ${e.body.take(500)}"
        e is java.net.UnknownHostException -> "No connection (could not resolve ${e.message})."
        e is java.io.IOException -> "Network error: ${e.message}"
        else -> e.message ?: e.toString()
    }

    companion object { const val MAX_STEPS = 30 }
}
