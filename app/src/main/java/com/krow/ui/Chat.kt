package com.krow.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

sealed class Block {
    class User(val m: Msg) : Block()
    class Ai(val ms: List<Msg>) : Block()
}

fun group(ms: List<Msg>): List<Block> {
    val out = ArrayList<Block>()
    var cur: MutableList<Msg>? = null
    for (m in ms) {
        if (m.role == "user") { cur = null; out += Block.User(m) }
        else {
            if (cur == null) { cur = ArrayList(); out += Block.Ai(cur) }
            cur.add(m)
        }
    }
    return out
}

fun secs(ms: Long) = String.format(Locale.US, "%.1fs", ms / 1000.0)
fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

private fun dayLabel(t: Long): String {
    val d = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        else -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(t))
    }
}

private fun blockAt(b: Block): Long = when (b) {
    is Block.User -> b.m.at
    is Block.Ai -> b.ms.firstOrNull()?.at ?: 0L
}

// ------------------------------------------------------------------ screen
@Composable
fun ChatScreen(core: Core, onMenu: () -> Unit, onSettings: () -> Unit, onFiles: () -> Unit) {
    val p = LocalPal.current
    val s by core.state.collectAsState()
    val live by core.live.collectAsState()
    val busy by core.busy.collectAsState()
    val chat = s.chats.firstOrNull { it.id == s.activeChat }
    val project = core.project(chat?.projectId ?: s.activeProject.ifEmpty { s.projects.first().id })
    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        TopBar(core, s, chat, project, onMenu, onFiles, onSettings)
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        val msgs = chat?.msgs ?: emptyList()
        val liveHere = live?.takeIf { it.chatId == chat?.id }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty() && liveHere == null) EmptyState(core, project)
            else Thread(core, project, msgs, liveHere?.text, busy != null)
        }
        Composer(core, busy != null, project)
    }
}

// ------------------------------------------------------------------ top bar — new chat, repo, model, mode
@Composable
private fun TopBar(
    core: Core, s: AppState, chat: Chat?, project: Project,
    onMenu: () -> Unit, onFiles: () -> Unit, onSettings: () -> Unit,
) {
    val p = LocalPal.current
    var modelMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    val last = chat?.msgs?.lastOrNull { it.role == "assistant" && it.outTok > 0 && it.genMs > 0 }
    val repoLabel = if (project.kind == "repo" && project.owner.isNotBlank())
        "${project.owner}/${project.repo}" else project.name
    val branch = project.branch.takeIf { project.kind == "repo" && it.isNotBlank() }

    Column(Modifier.fillMaxWidth().background(p.bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(52.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconBtn(Icons.Outlined.Menu, "Menu", onClick = onMenu)
            IconBtn(Icons.Outlined.EditNote, "New chat", onClick = { core.newChat() })

            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                T(chat?.title ?: "New chat", 15, weight = FontWeight.SemiBold, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (project.kind == "repo") Ic(Icons.Outlined.AccountTree, 12, p.muted)
                    T(repoLabel, 11, p.muted, family = Mono, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (branch != null) {
                        Box(Modifier.size(3.dp).clip(CircleShape).background(p.faint))
                        T(branch, 11, p.faint, family = Mono, maxLines = 1)
                    }
                }
            }

            if (Tools.enabled) IconBtn(Icons.Outlined.Folder, "Files", onClick = onFiles)

            // Mode
            if (Tools.enabled) Box {
                Pill(modeLabel(s.settings.mode), icon = modeIcon(s.settings.mode), small = true, selected = s.settings.mode != "ask") {
                    modeMenu = true
                }
                DropdownMenu(modeMenu, { modeMenu = false }, containerColor = p.raised, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, p.line2)) {
                    listOf("ask", "auto", "read").forEach { k ->
                        DropdownMenuItem(
                            {
                                Column {
                                    T(modeLabel(k) + if (s.settings.mode == k) "  ✓" else "", 14, if (s.settings.mode == k) p.accent else p.ink, FontWeight.Medium)
                                    T(modeHint(k), 12, p.muted)
                                }
                            },
                            { core.setMode(k); modeMenu = false },
                        )
                    }
                }
            }

            // Model
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, p.line, RoundedCornerShape(10.dp))
                        .background(p.surface)
                        .clickable { modelMenu = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    T(s.settings.model.ifBlank { "Model" }, 12, weight = FontWeight.Medium, maxLines = 1, modifier = Modifier.widthIn(max = 100.dp))
                    if (last != null) T("${last.outTok * 1000L / last.genMs}", 11, p.accent, family = Mono)
                    Ic(Icons.Outlined.ExpandMore, 14)
                }
                DropdownMenu(modelMenu, { modelMenu = false }, containerColor = p.raised, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, p.line2)) {
                    if (s.settings.models.isEmpty()) DropdownMenuItem({ T("No models. Add one in Settings.", 13, p.muted) }, { modelMenu = false; onSettings() })
                    s.settings.models.forEach { m ->
                        DropdownMenuItem(
                            { T(m + if (m == s.settings.model) "  ✓" else "", 14, if (m == s.settings.model) p.accent else p.ink) },
                            { core.setSettings { it.copy(model = m) }; modelMenu = false },
                        )
                    }
                    DropdownMenuItem({ T("Provider settings…", 13, p.muted) }, { modelMenu = false; onSettings() })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ empty
@Composable
private fun EmptyState(core: Core, project: Project) {
    val p = LocalPal.current
    val sugg = when {
        project.kind == "repo" -> listOf(
            Icons.Outlined.PlayArrow to ("Build an APK via Actions" to "workflow → trigger → download"),
            Icons.Outlined.AccountTree to ("Review and commit" to "status, diff, then push"),
            Icons.Outlined.Search to ("Explain this repository" to "map folders and entry points"),
            Icons.Outlined.BugReport to ("Find and fix a bug" to "reproduce, patch, verify"),
        )
        Tools.enabled -> listOf(
            Icons.Outlined.Folder to ("Explain this project" to "map folders and entry points"),
            Icons.Outlined.BugReport to ("Find and fix a bug" to "run it, read the trace, patch"),
            Icons.Outlined.Inventory2 to ("Write a script as an artifact" to "create, save, share"),
            Icons.Outlined.Terminal to ("Run a command" to "inspect output together"),
        )
        else -> listOf(
            Icons.Outlined.Lightbulb to ("Explain a concept" to "clear, step by step"),
            Icons.Outlined.Description to ("Draft a document" to "outline, then write it"),
            Icons.Outlined.BugReport to ("Debug an error" to "paste the trace"),
            Icons.Outlined.Flag to ("Plan a project" to "milestones and risks"),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = p.threadMax)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Mark(52)
            Spacer(Modifier.height(18.dp))
            T("What are we building today?", 32, family = Read, lh = 1.12f, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            T(
                when {
                    project.kind == "repo" -> "Working in ${project.owner}/${project.repo}${if (project.branch.isNotBlank()) " · ${project.branch}" else ""}. Edit, commit, trigger Actions, download APKs."
                    Tools.enabled -> "Ask anything. Krow can edit files, run commands, and send shareable artifacts in “${project.name}”."
                    else -> "Ask a question. Answers use the provider you set in Settings."
                },
                14, p.muted, lh = 1.5f, modifier = Modifier.widthIn(max = 420.dp),
            )
            Spacer(Modifier.height(28.dp))
            for (row in sugg.chunked(2)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((icon, pair) in row) {
                        val (a, b) = pair
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, p.line, RoundedCornerShape(14.dp))
                                .background(p.surface)
                                .clickable { core.send(a) }
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Ic(icon, 16, p.accent)
                            T(a, 14, weight = FontWeight.Medium, lh = 1.3f)
                            T(b, 11, p.muted, family = Mono)
                        }
                    }
                }
            }
            Spacer(Modifier.height(64.dp))
        }
    }
}

// ------------------------------------------------------------------ thread
@Composable
private fun Thread(core: Core, project: Project, msgs: List<Msg>, liveText: String?, busy: Boolean) {
    val p = LocalPal.current
    val blocks = remember(msgs) { group(msgs) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    val extra = if (liveText != null && (blocks.isEmpty() || blocks.last() is Block.User)) 1 else 0
    val total = blocks.size + extra

    suspend fun jumpBottom() {
        if (total <= 0) return
        list.scrollToItem((total - 1).coerceAtLeast(0))
        list.animateScrollBy(80_000f, krowSpring())
    }

    LaunchedEffect(list) { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false } }
    LaunchedEffect(list) { snapshotFlow { list.canScrollForward }.collect { if (!it) follow = true } }
    LaunchedEffect(total, liveText?.length, msgs.size, follow) { if (follow) jumpBottom() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            flingBehavior = ScrollableDefaults.flingBehavior(),
        ) {
            items(total) { i ->
                val b = blocks.getOrNull(i)
                val prev = blocks.getOrNull(i - 1)
                // Day break
                if (b != null) {
                    val day = dayLabel(blockAt(b))
                    val prevDay = prev?.let { dayLabel(blockAt(it)) }
                    if (day != prevDay) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.widthIn(max = p.threadMax).fillMaxWidth().padding(horizontal = 16.dp)) {
                                ThreadBreak(day)
                            }
                        }
                    } else if (prev is Block.Ai && b is Block.User) {
                        Spacer(Modifier.height(10.dp))
                    }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.widthIn(max = p.threadMax).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        if (i >= blocks.size) AiBlock(core, project, emptyList(), liveText ?: "", true, busy)
                        else when (val blk = blocks[i]) {
                            is Block.User -> UserBubble(blk.m)
                            is Block.Ai -> AiBlock(core, project, blk.ms, if (i == blocks.size - 1) liveText else null, i == blocks.size - 1, busy)
                        }
                    }
                }
            }
        }

        // Soft fade under top bar
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(18.dp)
                .background(Brush.verticalGradient(listOf(p.bg, p.bg.copy(alpha = 0f)))),
        )

        AnimatedVisibility(
            visible = !follow && total > 0,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            enter = fadeIn(krowSpring()) + scaleIn(krowSpring(), initialScale = .88f) + slideInVertically(krowSpring()) { it / 3 },
            exit = fadeOut() + scaleOut(targetScale = .88f),
        ) {
            Box(
                Modifier
                    .shadow(10.dp, CircleShape, ambientColor = p.shadow, spotColor = p.shadow)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(p.raised)
                    .border(1.dp, p.line2, CircleShape)
                    .clickable {
                        follow = true
                        scope.launch { jumpBottom() }
                    },
                Alignment.Center,
            ) { Ic(Icons.Outlined.KeyboardArrowDown, 22, p.ink) }
        }
    }
}

@Composable
private fun UserBubble(m: Msg) {
    val p = LocalPal.current
    Box(Modifier.fillMaxWidth(), Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier
                    .widthIn(max = 520.dp)
                    .shadow(2.dp, RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp), ambientColor = p.shadow, spotColor = p.shadow)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                    .background(p.raised)
                    .border(1.dp, p.line, RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                    .padding(horizontal = 15.dp, vertical = 11.dp),
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    T(m.text, 15, lh = 1.48f)
                }
            }
            T(hhmm(m.at), 10, p.faint, family = Mono, modifier = Modifier.padding(end = 4.dp))
        }
    }
}

private sealed class Part {
    class Say(val m: Msg) : Part()
    class Steps(val ms: MutableList<Msg>) : Part()
    class Artifact(val m: Msg) : Part()
}

@Composable
private fun AiBlock(core: Core, project: Project, ms: List<Msg>, live: String?, isLast: Boolean, busy: Boolean) {
    val p = LocalPal.current
    val clip = LocalClipboardManager.current
    val parts = remember(ms) {
        val out = ArrayList<Part>()
        for (m in ms) {
            when {
                m.role == "assistant" -> if (m.text.isNotBlank()) out += Part.Say(m)
                m.kind == "artifact" && m.status == "done" -> out += Part.Artifact(m)
                else -> {
                    val l = out.lastOrNull()
                    if (l is Part.Steps) l.ms.add(m) else out += Part.Steps(mutableListOf(m))
                }
            }
        }
        out
    }
    val lastAi = ms.lastOrNull { it.role == "assistant" }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Mark(22)
            T("Krow", 14, weight = FontWeight.SemiBold)
            (lastAi?.at ?: ms.firstOrNull()?.at)?.let { T(hhmm(it), 11, p.faint, family = Mono) }
        }
        for (part in parts) when (part) {
            is Part.Say -> {
                val final = part.m.calls.isEmpty()
                if (part.m.status == "fail") T(part.m.text, 14, p.bad)
                else if (final) Markdown(part.m.text)
                else Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(p.line2))
                    T(part.m.text, 15, p.muted, family = Read, italic = true, lh = 1.55f, modifier = Modifier.padding(start = 12.dp))
                }
            }
            is Part.Steps -> StepsCard(core, part.ms)
            is Part.Artifact -> ArtifactCard(core, project, part.m)
        }
        if (live != null) {
            if (live.isBlank()) ThinkingRow()
            else Markdown(live + " ▍")
        } else if (isLast && busy && parts.lastOrNull() is Part.Steps) {
            ThinkingRow("Working…")
        }
        if (isLast && !busy && lastAi != null && lastAi.status != "fail") {
            val metrics = buildList {
                lastAi.model?.let { add(it) }
                if (lastAi.inTok + lastAi.outTok > 0) add("${lastAi.inTok}↑ ${lastAi.outTok}↓")
                if (lastAi.outTok > 0 && lastAi.genMs > 0) add("${lastAi.outTok * 1000L / lastAi.genMs} tok/s")
                if (lastAi.genMs > 0) add(secs(lastAi.genMs))
            }.joinToString("  ·  ")
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconBtn(Icons.Outlined.ContentCopy, "Copy", 32, p.faint) {
                        clip.setText(AnnotatedString(ms.filter { it.role == "assistant" && it.calls.isEmpty() }.joinToString("\n\n") { it.text }))
                    }
                    IconBtn(Icons.Outlined.Refresh, "Retry", 32, p.faint) { core.retry() }
                }
                T(metrics, 11, p.muted, family = Mono, modifier = Modifier.weight(1f).padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun ThinkingRow(label: String = "Thinking…") {
    val p = LocalPal.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(12.dp), color = p.accent, strokeWidth = 1.5.dp)
        T(label, 14, p.muted)
    }
}

@Composable
private fun ArtifactCard(core: Core, project: Project, m: Msg) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val parsed = remember(m.text) { parseArtifactPayload(m.text) }
    val path = parsed?.path ?: m.cmd
    val lang = parsed?.lang.orEmpty().ifBlank { "text" }
    val title = parsed?.title ?: m.title
    val body = parsed?.body ?: m.text
    var open by remember { mutableStateOf(true) }
    SoftCard(elevation = 3.dp, radius = 12.dp) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(p.accentSoft), Alignment.Center) {
                Ic(Icons.Outlined.Inventory2, 15, p.accent)
            }
            Column(Modifier.weight(1f)) {
                T(title, 14, weight = FontWeight.SemiBold, maxLines = 1)
                T(path, 11, p.muted, family = Mono, maxLines = 1)
            }
            Pill(lang, small = true, mono = true)
            Ic(Icons.Outlined.ExpandMore, 18, p.muted, Modifier.rotate(if (open) 180f else 0f))
        }
        if (open) {
            HLine()
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    body,
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .background(p.sunk)
                        .padding(12.dp),
                    color = p.ink, fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 20.sp, softWrap = false,
                )
            }
            HLine()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill("Copy", icon = Icons.Outlined.ContentCopy, small = true) { clip.setText(AnnotatedString(body)) }
                Pill("Share", icon = Icons.Outlined.Share, small = true) {
                    val f = File(core.projectDir(project), path)
                    if (f.isFile) shareFile(ctx, f)
                    else {
                        val cache = File(ctx.cacheDir, "share").also { it.mkdirs() }
                        val tmp = File(cache, safeArtifactName(title))
                        tmp.writeText(body)
                        shareFile(ctx, tmp)
                    }
                }
                Spacer(Modifier.weight(1f))
                T("${body.lines().size} lines", 11, p.faint, family = Mono)
            }
        }
    }
}

// ------------------------------------------------------------------ steps / commands
@Composable
private fun StepsCard(core: Core, steps: List<Msg>) {
    val p = LocalPal.current
    val active = steps.any { it.status == "pending" || it.status == "running" }
    var open by remember { mutableStateOf(active || steps.any { it.status == "fail" }) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, p.line, shape)
            .background(p.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Ic(Icons.Outlined.ChevronRight, 16, p.muted, Modifier.rotate(if (open || active) 90f else 0f))
            T(
                if (active) "Running commands…" else "${steps.size} step${if (steps.size == 1) "" else "s"}",
                13, p.muted, FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            T(secs(steps.sumOf { it.ms }), 11, p.faint, family = Mono)
        }
        if (open || active) steps.forEach { HLine(); StepRow(core, it) }
    }
}

@Composable
private fun StepRow(core: Core, m: Msg) {
    val p = LocalPal.current
    var open by remember { mutableStateOf(m.status == "pending" || m.status == "fail") }
    val clip = LocalClipboardManager.current
    val pending = m.status == "pending"
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(p.sunk).border(1.dp, p.line, RoundedCornerShape(7.dp)),
                Alignment.Center,
            ) { Ic(kindIcon(m.kind), 14) }
            Column(Modifier.weight(1f)) {
                T(m.title, 14, weight = FontWeight.Medium, maxLines = 1)
                if (m.cmd.isNotBlank()) T(m.cmd, 12, p.muted, family = Mono, maxLines = 1)
            }
            when (m.status) {
                "running" -> CircularProgressIndicator(Modifier.size(14.dp), color = p.accent, strokeWidth = 1.5.dp)
                "pending" -> Box(Modifier.size(8.dp).clip(CircleShape).background(p.accent))
                "done" -> Ic(Icons.Outlined.Check, 14, p.ok)
                "fail", "denied" -> Ic(Icons.Outlined.Close, 14, p.bad)
                else -> Ic(Icons.Outlined.Close, 14, p.faint)
            }
            if (m.ms > 0) T(secs(m.ms), 11, p.muted, family = Mono)
        }
        if (pending) {
            Row(
                Modifier.fillMaxWidth().background(p.accentSoft).padding(start = 48.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                T("Allow this command?", 13, modifier = Modifier.weight(1f))
                Pill("Deny", small = true) { core.approve(m.callId ?: "", false) }
                Pill("Allow", small = true, filled = true) { core.approve(m.callId ?: "", true) }
                Pill("Auto-run", small = true, icon = Icons.Outlined.Bolt) { core.setMode("auto"); core.approve(m.callId ?: "", true) }
            }
        }
        if (open || pending) {
            Column(
                Modifier.fillMaxWidth().background(p.sunk).padding(start = 48.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (m.cmd.isNotBlank()) {
                    T("COMMAND", 11, p.faint, FontWeight.Medium, Mono)
                    OutBox(AnnotatedString("$ " + m.cmd))
                }
                if (m.text.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        T("OUTPUT", 11, p.faint, FontWeight.Medium, Mono)
                        m.exit?.let { e -> Pill("exit $e", small = true, mono = true, color = if (e == 0) p.ok else p.bad) }
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier.clip(RoundedCornerShape(6.dp)).clickable { clip.setText(AnnotatedString(m.text)) }.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Ic(Icons.Outlined.ContentCopy, 12)
                            T("Copy", 12, p.muted)
                        }
                    }
                    OutBox(colorize(m, p))
                }
            }
        }
    }
}

private fun colorize(m: Msg, p: Pal): AnnotatedString {
    val diff = m.kind == "edit" || m.kind == "git"
    return buildAnnotatedString {
        val lines = m.text.split("\n")
        lines.forEachIndexed { i, l ->
            val c = when {
                diff && l.startsWith("+") && !l.startsWith("+++") -> p.ok
                diff && l.startsWith("-") && !l.startsWith("---") -> p.bad
                diff && l.startsWith("@@") -> p.muted
                else -> p.ink
            }
            withStyle(SpanStyle(color = c)) { append(l) }
            if (i < lines.lastIndex) append("\n")
        }
    }
}

@Composable
private fun OutBox(text: AnnotatedString) {
    val p = LocalPal.current
    androidx.compose.foundation.text.selection.SelectionContainer {
        androidx.compose.material3.Text(
            text,
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, p.line, RoundedCornerShape(8.dp))
                .background(p.surface)
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
            color = p.ink, fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 20.sp, softWrap = false,
        )
    }
}

// ------------------------------------------------------------------ composer
@Composable
private fun Composer(core: Core, busy: Boolean, project: Project) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val s by core.state.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    val files = remember { mutableStateListOf<Attach>() }
    var modeMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val density = LocalDensity.current
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }.coerceIn(1, 12)
    val fieldMax = with(density) { (lineCount * 22).dp.coerceIn(24.dp, 200.dp) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                } ?: "file"
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                when {
                    bytes.size > 400_000 -> core.say("$name is larger than 400 KB.")
                    bytes.any { it.toInt() == 0 } -> core.say("$name looks like a binary file; only text files can be attached.")
                    else -> files.add(Attach(name, bytes.toString(Charsets.UTF_8)))
                }
            }.onFailure { core.say("Could not read that file: ${it.message}") }
        }
    }

    fun doSend() {
        if (busy || (text.isBlank() && files.isEmpty())) return
        core.send(text, files.toList())
        text = ""
        files.clear()
    }

    val can = text.isNotBlank() || files.isNotEmpty()
    val sendSrc = remember { MutableInteractionSource() }
    val sendPressed by sendSrc.collectIsPressedAsState()
    val sendScale by animateFloatAsState(if (sendPressed) .9f else 1f, krowSpring(), label = "send")

    Box(Modifier.fillMaxWidth().background(p.bg)) {
        // Fade into composer
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(20.dp)
                .offset(y = (-20).dp)
                .background(Brush.verticalGradient(listOf(p.bg.copy(alpha = 0f), p.bg))),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SoftCard(
                modifier = Modifier.widthIn(max = p.threadMax).fillMaxWidth(),
                elevation = if (focused) 12.dp else 5.dp,
                radius = 20.dp,
            ) {
                // Force SoftCard border to match focus — SoftCard already has border; nest padding
                Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 8.dp)) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 24.dp, max = fieldMax)
                            .focusRequester(focus),
                        textStyle = TextStyle(color = p.ink, fontSize = 15.sp, lineHeight = 22.sp),
                        cursorBrush = SolidColor(p.accent),
                        interactionSource = interaction,
                        minLines = 1,
                        maxLines = 12,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default,
                        ),
                        keyboardActions = KeyboardActions(onSend = { doSend() }),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth()) {
                                if (text.isEmpty()) T("Message Krow…", 15, p.faint)
                                inner()
                            }
                        },
                    )
                    if (files.isNotEmpty()) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            files.toList().forEach { f ->
                                Row(
                                    Modifier
                                        .clip(CircleShape)
                                        .background(p.sunk)
                                        .border(1.dp, p.line, CircleShape)
                                        .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Ic(Icons.Outlined.Description, 14)
                                    T(f.name, 12, family = Mono, maxLines = 1)
                                    Box(Modifier.size(22.dp).clip(CircleShape).clickable { files.remove(f) }, Alignment.Center) {
                                        Ic(Icons.Outlined.Close, 12)
                                    }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconBtn(Icons.Outlined.AttachFile, "Attach") { picker.launch(arrayOf("*/*")) }
                        if (Tools.enabled) Box {
                            Pill(modeLabel(s.settings.mode), icon = modeIcon(s.settings.mode), small = true) { modeMenu = true }
                            DropdownMenu(modeMenu, { modeMenu = false }, containerColor = p.raised, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, p.line2)) {
                                listOf("ask", "auto", "read").forEach { k ->
                                    DropdownMenuItem(
                                        {
                                            Column {
                                                T(modeLabel(k) + if (s.settings.mode == k) "  ✓" else "", 14)
                                                T(modeHint(k), 12, p.muted)
                                            }
                                        },
                                        { core.setMode(k); modeMenu = false },
                                    )
                                }
                            }
                        }
                        if (project.kind == "repo") {
                            Pill(
                                "${project.repo.ifBlank { project.name }}",
                                icon = Icons.Outlined.AccountTree,
                                small = true,
                                mono = true,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (text.isNotBlank()) T("${text.length}", 11, p.faint, family = Mono)
                        Box(
                            Modifier
                                .scale(sendScale)
                                .size(36.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (busy || can) p.accent else p.raised)
                                .clickable(interactionSource = sendSrc, indication = null, enabled = busy || can) {
                                    if (busy) core.stop() else doSend()
                                },
                            Alignment.Center,
                        ) {
                            Ic(
                                if (busy) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                                18,
                                if (busy || can) p.accentInk else p.faint,
                            )
                        }
                    }
                }
            }
            T(
                when {
                    project.kind == "repo" -> "Repo ${project.owner}/${project.repo} · ${modeLabel(s.settings.mode)}. Review commands before they run."
                    Tools.enabled -> "Project “${project.name}” · ${modeLabel(s.settings.mode)}. Krow can edit files and run commands."
                    else -> "Krow can make mistakes. Check important information."
                },
                11, p.faint,
                modifier = Modifier.widthIn(max = p.threadMax).fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}
