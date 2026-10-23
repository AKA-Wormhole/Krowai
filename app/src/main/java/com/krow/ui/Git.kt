package com.krow.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.krow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------------------------------ Settings → GitHub
@Composable
fun GitHubSection(core: Core) {
    val p = LocalPal.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var tokenIn by remember { mutableStateOf("") } // deliberately not rememberSaveable: never copied into saved-state bundles
    var checking by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<String?>(null) }
    val saved = remember(tick) { core.githubToken() }
    LaunchedEffect(saved) {
        login = null
        if (saved != null) login = withContext(Dispatchers.IO) { runCatching { core.gh().me() }.getOrNull() }
    }
    Section("GitHub") {
        Label(when {
            saved == null -> "Personal access token  ·  not saved"
            login != null -> "Connected as @$login  ·  token …${saved.takeLast(4)}"
            else -> "Token saved (…${saved.takeLast(4)})  ·  could not verify (offline or rejected)"
        })
        Field(tokenIn, { tokenIn = it }, "Paste token (github_pat_… or ghp_…)", mono = true, password = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (checking) "Checking…" else "Verify & save", filled = true) {
                val t = tokenIn.trim()
                if (t.isNotEmpty() && !checking) scope.launch {
                    checking = true
                    val r = withContext(Dispatchers.IO) { runCatching { Gh(core.http, t).me() } }
                    r.onSuccess { who -> core.secrets.put(Secrets.GITHUB, t); tokenIn = ""; tick++; core.say("Connected to GitHub as @$who") }
                        .onFailure { core.say("Token not saved: ${it.message?.take(160)}") }
                    checking = false
                }
            }
            if (saved != null) Pill("Remove token") { core.secrets.remove(Secrets.GITHUB); tick++; core.say("GitHub token removed") }
        }
        T("Use a fine-grained token limited to the repositories you want Krow to touch. Minimum: Contents Read and write (Metadata Read is automatic). " +
            "To trigger Actions and download APKs, also grant Actions: Read and write (and Workflows if offered). " +
            "Creating new repositories needs a classic token with the repo (+ workflow) scope — fine-grained tokens may be refused for that. " +
            "The token is stored encrypted on this device and is only ever sent to api.github.com. Krow only operates on a repository after you pick it with the Repo button in the sidebar.", 12, p.muted)
    }
}

// ------------------------------------------------------------------------------------------ Repo picker
private val DIRECT = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

@Composable
fun RepoPicker(core: Core, onClose: () -> Unit, onDone: () -> Unit) {
    val p = LocalPal.current
    val scope = rememberCoroutineScope()
    val hasToken = remember { core.githubToken() != null }
    var repos by remember { mutableStateOf<List<RepoInfo>>(emptyList()) }
    var page by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<RepoInfo?>(null) }
    var creating by remember { mutableStateOf(false) }

    fun loadMore() {
        if (loading) return
        scope.launch {
            loading = true; error = null
            val r = withContext(Dispatchers.IO) { runCatching { core.gh().repos(page + 1) } }
            r.onSuccess { l -> repos = repos + l; page += 1; more = l.size >= 100 }.onFailure { error = it.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (hasToken) loadMore() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconBtn(if (chosen != null) Icons.Outlined.ArrowBack else Icons.Outlined.Close, "Back") { if (chosen != null) chosen = null else onClose() }
                Column(Modifier.weight(1f)) { T(if (chosen != null) "Open repository" else "GitHub repositories", 16, weight = FontWeight.Medium) }
                if (chosen == null && hasToken) Pill("New", icon = Icons.Outlined.Add, small = true) { creating = true }
            }
            HLine()
            val c = chosen
            when {
                !hasToken -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    T("Add a GitHub personal access token in Settings → GitHub first. Krow only touches the repository you pick here.", 14, p.muted)
                }
                c != null -> RepoDetail(core, c, onDone)
                else -> Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(q, { q = it }, "Filter, or type owner/repo", mono = true)
                    val shown = remember(repos, q) { repos.filter { q.isBlank() || "${it.owner}/${it.name}".contains(q.trim(), true) } }
                    val typed = q.trim()
                    val direct = DIRECT.matches(typed) && repos.none { "${it.owner}/${it.name}".equals(typed, true) }
                    error?.let { T(it, 13, p.bad) }
                    LazyColumn(Modifier.weight(1f)) {
                        if (direct) item {
                            RepoRow("Look up $typed", "Any repository your token can access", Icons.Outlined.Search) {
                                scope.launch {
                                    error = null
                                    val r = withContext(Dispatchers.IO) { runCatching { core.gh().repo(typed.substringBefore('/'), typed.substringAfter('/')) } }
                                    r.onSuccess { chosen = it }.onFailure { error = it.message }
                                }
                            }
                        }
                        items(shown, key = { it.owner + "/" + it.name }) { r ->
                            RepoRow(r.owner + "/" + r.name, (if (r.priv) "private" else "public") + (if (r.desc.isNotBlank()) "  ·  " + r.desc else ""), if (r.priv) Icons.Outlined.Lock else Icons.Outlined.Public) { chosen = r }
                        }
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), Alignment.Center) {
                                when {
                                    loading -> CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
                                    more -> Pill("Load more") { loadMore() }
                                    repos.isEmpty() && error == null -> T("No repositories found for this token.", 13, p.muted)
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating) NewRepoDialog(core, { creating = false }) { chosen = it; creating = false }
}

@Composable
private fun RepoRow(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(p.sunk).border(1.dp, p.line, RoundedCornerShape(8.dp)), Alignment.Center) { Ic(icon, 16, p.accent) }
        Column(Modifier.weight(1f)) {
            T(title, 14, weight = FontWeight.Medium, maxLines = 1)
            T(sub, 11, p.muted, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepoDetail(core: Core, info: RepoInfo, onDone: () -> Unit) {
    val p = LocalPal.current
    val scope = rememberCoroutineScope()
    var branches by remember(info) { mutableStateOf(listOf(info.branch)) }
    var branch by remember(info) { mutableStateOf(info.branch) }
    var busy by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(info) {
        val l = withContext(Dispatchers.IO) { runCatching { core.gh().branches(info.owner, info.name) }.getOrNull() }
        if (!l.isNullOrEmpty()) branches = if (info.branch in l) l else listOf(info.branch) + l
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T(info.owner + "/" + info.name, 18, weight = FontWeight.Medium, family = Mono)
        if (info.desc.isNotBlank()) T(info.desc, 13, p.muted)
        T((if (info.priv) "Private" else "Public") + " repository", 12, p.faint)
        if (!info.canPush) T("Your token has read-only access here, so GitHub will refuse commits and pushes.", 13, p.bad)
        Label("Branch")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            branches.forEach { b -> Pill(b, selected = b == branch, mono = true, small = true) { if (busy == null) branch = b } }
        }
        T("Krow downloads this branch into a new project. From then on, every Git action is limited to this repository and branch.", 12, p.muted)
        err?.let { T(it, 13, p.bad) }
        if (busy != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), color = p.accent, strokeWidth = 2.dp); T(busy ?: "", 13, p.muted)
        } else Pill("Clone and open", filled = true, icon = Icons.Outlined.Download) {
            scope.launch {
                err = null; busy = "Starting…"
                val r = runCatching { core.addRepoProject(info.owner, info.name, branch) { busy = it } }
                busy = null
                r.onSuccess { core.say("Opened ${info.owner}/${info.name}@$branch"); onDone() }.onFailure { err = it.message ?: it.toString() }
            }
        }
    }
}

@Composable
private fun NewRepoDialog(core: Core, onDismiss: () -> Unit, onCreated: (RepoInfo) -> Unit) {
    val p = LocalPal.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var priv by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() }, containerColor = p.raised,
        title = { T("New repository", 17, weight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, { name = it }, "Repository name", mono = true)
                Field(desc, { desc = it }, "Description (optional)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Private", selected = priv) { priv = true }
                    Pill("Public", selected = !priv) { priv = false }
                }
                T("Created on your own account with a README. Needs a token allowed to create repositories.", 12, p.muted)
                err?.let { T(it, 13, p.bad) }
            }
        },
        confirmButton = {
            Pill(if (busy) "Creating…" else "Create", filled = true) {
                if (!busy && name.isNotBlank()) scope.launch {
                    busy = true; err = null
                    val r = withContext(Dispatchers.IO) { runCatching { core.gh().createRepo(name.trim(), desc.trim(), priv) } }
                    busy = false
                    r.onSuccess { onCreated(it) }.onFailure { err = it.message }
                }
            }
        },
        dismissButton = { Pill("Cancel") { if (!busy) onDismiss() } },
    )
}

// ------------------------------------------------------------------------------------------ Files screen: git bar
private fun colorDiff(text: String, p: Pal): AnnotatedString = buildAnnotatedString {
    val lines = text.split("\n")
    lines.forEachIndexed { i, l ->
        val c = when {
            l.startsWith("+") && !l.startsWith("+++") -> p.ok
            l.startsWith("-") && !l.startsWith("---") -> p.bad
            l.startsWith("@@") || l.startsWith("#") -> p.muted
            else -> p.ink
        }
        withStyle(SpanStyle(color = c)) { append(l) }
        if (i < lines.lastIndex) append("\n")
    }
}

@Composable
private fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    val p = LocalPal.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.raised,
        title = { T(title, 17, weight = FontWeight.SemiBold) },
        text = { T(body, 13, p.ink, family = Mono, modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { Pill("OK", filled = true, onClick = onDismiss) },
    )
}

@Composable
fun GitBar(core: Core, project: Project, tick: Int, onChanged: () -> Unit) {
    val p = LocalPal.current
    val scope = rememberCoroutineScope()
    val dir = remember(project.id) { core.projectDir(project) }
    var changes by remember { mutableStateOf<List<Change>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var local by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<String?>(null) }
    var commitDlg by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Change?>(null) }
    var discarding by remember { mutableStateOf<Change?>(null) }
    var report by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(project.id, tick, local) {
        val r = withContext(Dispatchers.IO) {
            runCatching { GitOps.status(dir, GitOps.loadBase(dir) ?: error("No git state for this project. Delete it and add the repository again.")) }
        }
        changes = r.getOrNull(); err = r.exceptionOrNull()?.message
    }

    fun act(label: String, failTitle: String, okTitle: String, block: () -> String) {
        if (working != null) return
        scope.launch {
            working = label
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            working = null
            r.onSuccess { report = okTitle to it }.onFailure { report = failTitle to (it.message ?: it.toString()) }
            local++; onChanged()
        }
    }

    val n = changes?.size ?: 0
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, p.line, RoundedCornerShape(14.dp)).background(p.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ic(Icons.Outlined.AccountTree, 16, p.accent)
            Column(Modifier.weight(1f).clickable { open = !open }) {
                T(project.owner + "/" + project.repo, 13, weight = FontWeight.Medium, family = Mono, maxLines = 1)
                T(project.branch + "  ·  " + (if (err != null) "no git state" else if (changes == null) "checking…" else if (n == 0) "clean" else "$n change${if (n == 1) "" else "s"}"), 11, p.muted, family = Mono, maxLines = 1)
            }
            Pill("Pull", small = true) { act("Pulling…", "Pull failed", "Pull") { GitOps.pull(core.gh(), project, dir) } }
            Pill("Commit", small = true, filled = n > 0) { if (n > 0) commitDlg = true else core.say("Nothing to commit") }
            IconBtn(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "Changes", 32) { open = !open }
        }
        working?.let {
            Row(Modifier.padding(start = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), color = p.accent, strokeWidth = 2.dp); T(it, 12, p.muted)
            }
        }
        err?.let { T(it, 12, p.bad, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) }
        if (open) {
            HLine()
            val list = changes.orEmpty()
            if (list.isEmpty()) T("No local changes.", 12, p.muted, modifier = Modifier.padding(12.dp))
            else Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                list.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { viewing = c }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        T(c.kind.toString(), 12, when (c.kind) { 'A' -> p.ok; 'D' -> p.bad; else -> p.accent }, FontWeight.SemiBold, Mono)
                        T(c.path, 12, family = Mono, maxLines = 1, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                        IconBtn(Icons.Outlined.Undo, "Discard", 32) { discarding = c }
                    }
                }
            }
        }
    }

    if (commitDlg) TextDialog("Commit & push", "", "Commit message", "Commit", { commitDlg = false }) { msg ->
        commitDlg = false
        if (msg.isBlank()) core.say("A commit message is required")
        else act("Committing and pushing…", "Commit failed", "Committed") { GitOps.commit(core.gh(), project, dir, msg, null) }
    }
    discarding?.let { c ->
        ConfirmDialog("Discard changes?", (if (c.kind == 'A') "This deletes the new file " else "This returns the file to the last synced version: ") + c.path + ". It cannot be undone.", "Discard", { discarding = null }) {
            discarding = null
            act("Restoring…", "Restore failed", "Restored") { GitOps.restore(core.gh(), project, dir, c.path) }
        }
    }
    viewing?.let { c ->
        var text by remember(c, tick, local) { mutableStateOf<String?>(null) }
        LaunchedEffect(c, tick, local) {
            text = withContext(Dispatchers.IO) { runCatching { GitOps.diff(core.gh(), project, dir, c.path) }.getOrElse { it.message ?: it.toString() } }
        }
        AlertDialog(
            onDismissRequest = { viewing = null }, containerColor = p.raised,
            title = { T(c.path, 14, weight = FontWeight.SemiBold, family = Mono, maxLines = 2) },
            text = {
                val t = text
                if (t == null) CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
                else androidx.compose.material3.Text(colorDiff(t, p), Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                    fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp, softWrap = false)
            },
            confirmButton = { Pill("Close", filled = true) { viewing = null } },
        )
    }
    report?.let { (t, b) -> InfoDialog(t, b) { report = null } }
}
