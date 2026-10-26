package com.krow.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import android.speech.RecognizerIntent
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
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
fun ChatScreen(core: Core, onMenu: () -> Unit, onSettings: (String) -> Unit, onFiles: () -> Unit) {
    val p = LocalPal.current
    val s by core.state.collectAsState()
    val live by core.live.collectAsState()
    val busy by core.busy.collectAsState()
    val chat = s.chats.firstOrNull { it.id == s.activeChat }
    val project = core.workProject(chat?.projectId ?: s.activeProject.ifEmpty { s.projects.first().id })
    val draft = rememberSaveable { mutableStateOf("") }
    var attachReq by remember { mutableStateOf("") }
    val shared by core.incomingText.collectAsState()
    LaunchedEffect(shared) { shared?.let { draft.value = (if (draft.value.isBlank()) "" else draft.value + "\n") + it; core.incomingText.value = null } }
    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        TopBar(core, busy != null, onMenu, onSettings)
        val msgs = chat?.msgs ?: emptyList()
        val liveHere = live?.takeIf { it.chatId == chat?.id }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty() && liveHere == null) EmptyState(project, onPrompt = { draft.value = it }, onAttach = { attachReq = it })
            else {
                val versions = remember(msgs) { artifactVersions(msgs) }
                CompositionLocalProvider(LocalArtifactVersions provides versions) { Thread(core, project, msgs, liveHere?.text, busy != null) }
            }
        }
        Composer(core, busy != null, project, draft, attachReq, onSettings) { attachReq = "" }
    }
}

// ------------------------------------------------------------------ top bar — menu, chat title, new chat
@Composable
private fun TopBar(core: Core, running: Boolean, onMenu: () -> Unit, onSettings: (String) -> Unit) {
    val p = LocalPal.current
    val s by core.state.collectAsState()
    val title = s.chats.firstOrNull { it.id == s.activeChat }?.title?.takeIf { it.isNotBlank() && it != "New chat" } ?: "Krow"
    Row(Modifier.fillMaxWidth().background(p.bg).statusBarsPadding().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onMenu), Alignment.Center) {
            Canvas(Modifier.size(22.dp)) {
                val w = 2.dp.toPx(); val c = p.ink
                drawRoundRect(c, Offset(0f, size.height * .22f), Size(size.width, w), CornerRadius(w))
                drawRoundRect(c, Offset(0f, size.height * .5f - w / 2), Size(size.width * .66f, w), CornerRadius(w))
                drawRoundRect(c, Offset(0f, size.height * .78f - w), Size(size.width * .4f, w), CornerRadius(w))
            }
            if (running) Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(9.dp).clip(CircleShape).background(p.accent))
        }
        T(title, 17, p.ink, FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { core.newChat() }, Alignment.Center) { Ic(Icons.Outlined.AddComment, 23, p.ink) }
    }
}

/** Model picker shown inside the composer. */
@Composable
private fun ModelPill(core: Core, onSettings: (String) -> Unit) {
    val p = LocalPal.current
    val s by core.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(40.dp).clip(CircleShape).background(p.bubble).clickable { menu = true }.padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            T(s.settings.model.ifBlank { "Add a model" }.substringAfterLast('/'), 14, p.ink, FontWeight.Medium, maxLines = 1, modifier = Modifier.widthIn(max = 150.dp))
            Ic(Icons.Outlined.ExpandMore, 18, p.muted)
        }
        DropdownMenu(menu, { menu = false }, containerColor = p.raised, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, p.line2)) {
            s.settings.models.forEach { m ->
                DropdownMenuItem(
                    { T(m + if (m == s.settings.model) "  ✓" else "", 14, if (m == s.settings.model) p.accent else p.ink, FontWeight.Medium) },
                    { core.setSettings { it.copy(model = m) }; menu = false },
                )
            }
            DropdownMenuItem({ T(if (s.settings.models.isEmpty()) "Add a model…" else "Manage models…", 14, p.muted) }, { menu = false; onSettings("model") })
        }
    }
}

// ------------------------------------------------------------------ empty / new chat
@Composable
private fun EmptyState(project: Project, onPrompt: (String) -> Unit, onAttach: (String) -> Unit) {
    val p = LocalPal.current
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val greeting = when { hour < 5 -> "Hello"; hour < 12 -> "Good morning"; hour < 18 -> "Good afternoon"; else -> "Good evening" }
    val chips = buildList<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit>> {
        add(Triple(Icons.Outlined.Edit, "Write or edit", { onPrompt("Help me write or edit this:\n\n") }))
        add(Triple(Icons.Outlined.Code, "Review my code", { onPrompt("Review this code and point out bugs and improvements:\n\n") }))
        add(Triple(Icons.Outlined.Description, "Summarize a file", { onAttach("file") }))
        if (project.kind == "repo") add(Triple(Icons.Outlined.AccountTree, "Explain this repo", { onPrompt("Explain this repository: map the folders and entry points.") }))
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            T(greeting, 34, p.ink, FontWeight.Medium, Read, lh = 1.2f)
            T("What are we building today?", 19, p.muted, family = Read)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { (icon, label, act) ->
                Row(
                    Modifier.clip(CircleShape).background(p.field).border(1.dp, p.line2, CircleShape).clickable(onClick = act).padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { Ic(icon, 18, p.accent); T(label, 14, p.ink, FontWeight.Medium, maxLines = 1) }
            }
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
        val last = total - 1
        // Only snap to the last item when it isn't on screen yet; otherwise just nudge down, so there is no visible jump.
        if (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index != last) list.scrollToItem(last)
        if (list.canScrollForward) list.scrollBy(1_000_000f)
    }

    LaunchedEffect(list) { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false } }
    LaunchedEffect(list) { snapshotFlow { list.canScrollForward }.collect { if (!it) follow = true } }
    LaunchedEffect(total, liveText?.length, msgs.size) { if (follow && !list.isScrollInProgress) jumpBottom() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            items(total) { i ->
                val b = blocks.getOrNull(i)
                val prev = blocks.getOrNull(i - 1)
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
                            is Block.User -> UserBubble(core, blk.m, busy)
                            is Block.Ai -> AiBlock(core, project, blk.ms, if (i == blocks.size - 1) liveText else null, i == blocks.size - 1, busy)
                        }
                    }
                }
            }
        }

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
private fun UserBubble(core: Core, m: Msg, busy: Boolean) {
    val p = LocalPal.current
    val clip = LocalClipboardManager.current
    var viewing by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            m.images.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { img -> ImageThumb(img, if (m.images.size == 1) 220.dp else 130.dp) { viewing = img } }
                }
            }
            m.files.forEach { f -> FileChip(f.name, f.text.length) }
            if (m.text.isNotBlank()) Box(
                Modifier
                    .widthIn(max = 520.dp)
                    .clip(RoundedCornerShape(24.dp, 24.dp, 8.dp, 24.dp))
                    .background(p.bubble)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    T(m.text, 16, lh = 1.45f)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(hhmm(m.at), 10, p.faint, family = Mono, modifier = Modifier.padding(end = 6.dp))
                if (m.text.isNotBlank()) {
                    IconBtn(Icons.Outlined.ContentCopy, "Copy", 28, p.muted) { clip.setText(AnnotatedString(m.text)); core.say("Copied") }
                    IconBtn(Icons.Outlined.SelectAll, "Select text", 28, p.muted) { selecting = true }
                    if (core.settings.memory != "off") IconBtn(Icons.Outlined.BookmarkAdd, "Remember this", 28, p.muted) { runCatching { core.memory.add(m.text, "fact") }; core.say("Saved to memory") }
                    if (!busy) IconBtn(Icons.Outlined.Edit, "Edit and resend", 28, p.muted) { editing = true }
                }
            }
        }
    }
    if (editing) EditMessageDialog(m.text, onDismiss = { editing = false }) { core.editAndResend(m.at, it); editing = false }
    if (selecting) SelectTextDialog(m.text) { selecting = false }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

private val CODE_EXT = setOf("kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "json", "xml", "html", "css", "sh", "gradle", "c", "cpp", "h", "rs", "go", "rb", "php", "swift", "yml", "yaml", "sql", "dart")

private val LocalArtifactVersions = staticCompositionLocalOf<Map<String, Int>> { emptyMap() }

private fun artifactKey(m: Msg): String? {
    if (m.kind != "artifact" || m.status != "done") return null
    val pl = parseArtifactPayload(m.text) ?: return null
    return if (pl.path.startsWith("content://") || pl.path.startsWith("downloads/")) "t:" + pl.title.trim().lowercase() else "p:" + pl.path.trim().lowercase()
}

/** For each shared-file message: how many earlier versions of the same file exist in this chat (0 = original). */
private fun artifactVersions(msgs: List<Msg>): Map<String, Int> {
    val seen = HashMap<String, Int>()
    val out = HashMap<String, Int>()
    for (m in msgs) {
        val k = artifactKey(m) ?: continue
        val n = seen[k] ?: 0
        seen[k] = n + 1
        out[m.callId ?: m.at.toString()] = n
    }
    return out
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
    val ctx = LocalContext.current
    var selecting by remember { mutableStateOf(false) }
    val lv by core.live.collectAsState()
    val parts = remember(ms) {
        val out = ArrayList<Part>()
        for (m in ms) {
            when {
                m.role == "assistant" -> if (m.text.isNotBlank() || m.thought.isNotBlank()) out += Part.Say(m)
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (part in parts) when (part) {
            is Part.Say -> {
                val final = part.m.calls.isEmpty()
                if (part.m.thought.isNotBlank()) ThoughtCard(part.m.thought, false)
                if (part.m.text.isBlank()) Unit
                else if (part.m.status == "fail") T(part.m.text, 14, p.bad)
                else Markdown(part.m.text)
            }
            is Part.Steps -> StepsCard(core, part.ms)
            is Part.Artifact -> ArtifactCard(core, project, part.m)
        }
        if (live != null) {
            val liveThought = lv?.thought.orEmpty()
            if (liveThought.isNotBlank()) ThoughtCard(liveThought, live.isBlank())
            if (live.isBlank()) { if (liveThought.isBlank()) ThinkingRow() }
            else Markdown(live + " ▍")
        } else if (isLast && busy && parts.lastOrNull() is Part.Steps) {
            ThinkingRow("Still working on it…", dots = false)
        }
        if (!isLast && lastAi != null && lastAi.status != "fail" && live == null) {
            val body = ms.filter { it.role == "assistant" && it.calls.isEmpty() }.joinToString("\n\n") { it.text }
            if (body.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconBtn(Icons.Outlined.ContentCopy, "Copy", 30, p.muted) { clip.setText(AnnotatedString(body)); core.say("Copied") }
                IconBtn(Icons.Outlined.SelectAll, "Select text", 30, p.muted) { selecting = true }
                IconBtn(Icons.Outlined.Share, "Share", 30, p.muted) { shareText(ctx, body) }
                if (core.settings.memory != "off") IconBtn(Icons.Outlined.BookmarkAdd, "Remember this", 30, p.muted) { runCatching { core.memory.add(body.take(280), "fact") }; core.say("Saved to memory") }
            }
        }
        if (isLast && !busy && lastAi != null && lastAi.status != "fail") {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconBtn(Icons.Outlined.ContentCopy, "Copy", 32, p.muted) {
                        clip.setText(AnnotatedString(ms.filter { it.role == "assistant" && it.calls.isEmpty() }.joinToString("\n\n") { it.text })); core.say("Copied")
                    }
                    IconBtn(Icons.Outlined.SelectAll, "Select text", 32, p.muted) { selecting = true }
                    IconBtn(Icons.Outlined.Share, "Share", 32, p.muted) { shareText(ctx, ms.filter { it.role == "assistant" && it.calls.isEmpty() }.joinToString("\n\n") { it.text }) }
                    IconBtn(Icons.Outlined.Refresh, "Retry", 32, p.muted) { core.retry() }
                }
            }
        }
        if (selecting) SelectTextDialog(ms.filter { it.role == "assistant" && it.calls.isEmpty() }.joinToString("\n\n") { it.text }) { selecting = false }
    }
}

@Composable
private fun EditMessageDialog(initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val p = LocalPal.current
    var v by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.raised,
        title = { T("Edit message", 17, weight = FontWeight.SemiBold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(v, { v = it }, "Message", singleLine = false)
            T("Everything after this message is replaced by a new answer.", 12, p.muted)
        } },
        confirmButton = { Pill("Resend", filled = true) { onOk(v) } },
        dismissButton = { Pill("Cancel", onClick = onDismiss) },
    )
}

private fun thoughtTitle(t: String, running: Boolean): String {
    val bold = Regex("""\*\*(.+?)\*\*""").find(t)?.groupValues?.get(1)?.trim()
    val first = t.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.trim('*', ' ', '#')
    return (bold ?: first ?: "").take(70).ifBlank { if (running) "Thinking…" else "Thought process" }
}

/** Three softly pulsing dots — the "working" indicator. */
@Composable
private fun PulseDots() {
    val p = LocalPal.current
    val tr = rememberInfiniteTransition(label = "dots")
    val t by tr.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "t")
    Row(Modifier.width(22.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val a = (1f - ((t - i + 3f) % 3f) / 3f).coerceIn(.25f, 1f)
            Box(Modifier.size(5.dp).clip(CircleShape).background(p.accent.copy(alpha = a)))
        }
    }
}

/** Collapsed "thinking" of the model (Gemini thought summaries): one muted line, tap to read. */
@Composable
private fun ThoughtCard(text: String, running: Boolean) {
    val p = LocalPal.current
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (running) PulseDots() else Ic(Icons.Outlined.Schedule, 18, p.faint)
            T(thoughtTitle(text, running), 15, p.muted, FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Ic(Icons.Outlined.ChevronRight, 18, p.faint, Modifier.rotate(if (open) 90f else 0f))
        }
        AnimatedVisibility(open) {
            Row(Modifier.padding(top = 4.dp, start = 8.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(p.line2))
                SelectionContainer { Text(inlineMd(text.trim()), Modifier.padding(start = 14.dp), color = p.muted, fontFamily = Sans, fontSize = 13.5.sp, lineHeight = 21.sp) }
            }
        }
    }
}

@Composable
private fun ThinkingRow(label: String = "Thinking…", dots: Boolean = true) {
    val p = LocalPal.current
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (dots) PulseDots()
        T(label, 15, p.muted)
    }
}

@Composable
private fun ArtifactCard(core: Core, project: Project, m: Msg) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val parsed = remember(m.text) { parseArtifactPayload(m.text) }
    val path = parsed?.path ?: m.cmd
    val title = (parsed?.title ?: m.title).ifBlank { prettyName(path) }
    val version = LocalArtifactVersions.current[m.callId ?: m.at.toString()] ?: 0
    val body = parsed?.body ?: m.text
    val ext = path.substringAfterLast('.', "").uppercase().ifBlank { (parsed?.lang ?: "FILE").uppercase() }
    val inDownloads = path.startsWith("content://") || path.startsWith("downloads/")
    var open by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(inDownloads) }
    val shape = RoundedCornerShape(16.dp)

    /** The real file in the project, or a temp copy of the text when it only lives in the chat. */
    suspend fun realFile(): File = withContext(Dispatchers.IO) {
        val f = File(core.projectDir(project), path)
        if (f.isFile) f else {
            val tmp = File(File(ctx.cacheDir, "share").also { it.mkdirs() }, safeArtifactName(title.ifBlank { path }))
            tmp.writeText(body); tmp
        }
    }

    val isCode = path.substringAfterLast('.', "").lowercase() in CODE_EXT
    val kindLabel = when {
        isCode -> "Code · $ext"
        ext in listOf("MD", "TXT", "DOC", "DOCX", "PDF") -> "Document · $ext"
        else -> ext
    }
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, p.line2, shape).background(p.surface)) {
        Row(
            Modifier.fillMaxWidth().then(if (body.isNotBlank() && !inDownloads) Modifier.clickable { open = !open } else Modifier).padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (isCode) p.accentSoft else p.sunk), Alignment.Center) {
                Ic(if (isCode) Icons.Outlined.Code else Icons.Outlined.Description, 21, if (isCode) p.accent else p.muted)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(title, 15, p.ink, FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (version > 0) Pill("Update $version", small = true, filled = true)
                }
                T(if (inDownloads) "$kindLabel · saved" else kindLabel, 12, p.muted, maxLines = 1)
            }
            IconBtn(if (saved) Icons.Outlined.FolderOpen else Icons.Outlined.Download, if (saved) "Open Downloads" else "Download", 38, p.muted) {
                if (saved) openDownloads(ctx)
                else scope.launch {
                    val f = realFile()
                    val name = if (title.contains('.') && title.substringAfterLast('.').length <= 5) title
                        else title + (if (version > 0) " - Update $version" else "") + (f.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: "")
                    val out = withContext(Dispatchers.IO) { saveToDownloads(ctx, f, name) }
                    if (out != null) { saved = true; core.say("Saved to Downloads: $out") } else core.say("Could not save to Downloads")
                }
            }
            if (!inDownloads || path.startsWith("content://")) IconBtn(Icons.Outlined.Share, "Share", 38, p.muted) {
                scope.launch {
                    runCatching { if (path.startsWith("content://")) shareUri(ctx, Uri.parse(path), title.takeIf { it.contains('.') } ?: "app.apk") else shareFile(ctx, realFile()) }
                        .onFailure { core.say("Could not share: ${it.message}") }
                }
            }
        }
        if (inDownloads && body.isNotBlank()) T(body, 13, p.muted, modifier = Modifier.padding(start = 64.dp, end = 12.dp, bottom = 10.dp))
        if (open && body.isNotBlank() && !inDownloads) {
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Copy", icon = Icons.Outlined.ContentCopy, small = true) { clip.setText(AnnotatedString(body)); core.say("Copied") }
                Spacer(Modifier.weight(1f))
                T("${body.lines().size} lines", 12, p.muted, family = Mono)
            }
        }
    }
}

// ------------------------------------------------------------------ steps / commands
private fun stepCat(tool: String): String = when {
    tool == "write_file" -> "create"
    tool == "edit_file" || tool == "rename_path" -> "edit"
    tool == "read_file" || tool == "image_info" -> "read"
    tool == "list_files" || tool == "search_files" -> "explore"
    tool == "run_command" -> "cmd"
    tool == "web_search" || tool == "wikipedia" -> "search"
    tool == "fetch_url" -> "page"
    tool.startsWith("git") || tool.startsWith("github") || tool == "ensure_apk_workflow" -> "git"
    tool.startsWith("memory") -> "memory"
    tool == "process_image" -> "image"
    tool == "zip_create" || tool == "unzip" -> "zip"
    else -> "tool"
}

private fun catPhrase(c: String, n: Int): String = when (c) {
    "create" -> if (n == 1) "created a file" else "created $n files"
    "edit" -> if (n == 1) "edited a file" else "edited $n files"
    "read" -> if (n == 1) "read a file" else "read $n files"
    "explore" -> "explored the project"
    "cmd" -> if (n == 1) "ran a command" else "ran $n commands"
    "search" -> if (n == 1) "searched the web" else "searched the web $n times"
    "page" -> if (n == 1) "read a web page" else "read $n web pages"
    "git" -> if (n == 1) "used git" else "ran $n git actions"
    "memory" -> "updated memory"
    "image" -> if (n == 1) "processed an image" else "processed $n images"
    "zip" -> if (n == 1) "worked with a ZIP" else "worked with $n ZIPs"
    else -> if (n == 1) "used a tool" else "used $n tools"
}

private fun stepSummary(steps: List<Msg>): String {
    val order = LinkedHashMap<String, Int>()
    for (m in steps) order.merge(stepCat(m.tool.orEmpty()), 1) { a, b -> a + b }
    val parts = order.entries.map { (c, n) -> catPhrase(c, n) }
    val shown = if (parts.size > 3) parts.take(2) + "did ${steps.size - order.entries.take(2).sumOf { it.value }} more" else parts
    return shown.joinToString(", ").replaceFirstChar { it.uppercase() }
}

private val PROGRESSIVE = mapOf(
    "write" to "Writing", "edit" to "Editing", "read" to "Reading", "create" to "Creating", "insert" to "Inserting", "append" to "Appending",
    "copy" to "Copying", "list" to "Listing", "delete" to "Deleting", "rename" to "Renaming", "search" to "Searching", "run" to "Running",
    "open" to "Opening", "extract" to "Extracting", "build" to "Building", "fix" to "Fixing", "commit" to "Committing", "trigger" to "Triggering",
    "download" to "Downloading", "check" to "Checking", "update" to "Updating", "add" to "Adding", "remove" to "Removing", "replace" to "Replacing",
    "process" to "Processing", "cancel" to "Cancelling", "re-run" to "Re-running", "improve" to "Improving", "install" to "Installing",
)

private fun progressive(title: String): String {
    val t = title.trim().ifBlank { "Working" }
    val first = t.substringBefore(' ')
    val rest = t.substringAfter(' ', "")
    val v = PROGRESSIVE[first.lowercase()]
    return (if (v != null) (if (rest.isBlank()) v else "$v $rest") else t).trimEnd('.', '…') + "…"
}

@Composable
private fun StepsCard(core: Core, steps: List<Msg>) {
    val p = LocalPal.current
    val running = steps.lastOrNull { it.status == "running" || it.status == "pending" }
    val failed = steps.count { it.status == "fail" }
    var open by remember { mutableStateOf(false) }
    val showing = open || steps.any { it.status == "pending" }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (running != null) PulseDots() else Ic(if (failed > 0) Icons.Outlined.ErrorOutline else Icons.Outlined.Layers, 18, if (failed > 0) p.bad else p.faint)
            T(
                if (running != null) progressive(running.title) else "${steps.size} step${if (steps.size == 1) "" else "s"}" + if (failed > 0) " · $failed failed" else "",
                15, if (failed > 0 && running == null) p.bad else p.muted, FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f, fill = false),
            )
            Ic(Icons.Outlined.ChevronRight, 18, p.faint, Modifier.rotate(if (showing) 90f else 0f))
        }
        AnimatedVisibility(showing) {
            Row(Modifier.padding(top = 4.dp, start = 8.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(p.line2))
                Column(Modifier.padding(start = 6.dp).fillMaxWidth()) { steps.forEach { StepRow(core, it) } }
            }
        }
    }
}

@Composable
private fun StepRow(core: Core, m: Msg) {
    val p = LocalPal.current
    var open by remember { mutableStateOf(m.status == "pending" || m.status == "fail") }
    val clip = LocalClipboardManager.current
    val pending = m.status == "pending"
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Ic(kindIcon(m.kind), 18, if (m.status == "fail") p.bad else p.faint)
            Column(Modifier.weight(1f)) {
                T(m.title, 14, p.ink, FontWeight.Medium, maxLines = 1)
                if (m.cmd.isNotBlank()) T(m.cmd, 12, p.muted, family = Mono, maxLines = 1)
            }
            when (m.status) {
                "running" -> CircularProgressIndicator(Modifier.size(14.dp), color = p.accent, strokeWidth = 1.5.dp)
                "pending" -> Box(Modifier.size(8.dp).clip(CircleShape).background(p.accent))
                "done" -> Ic(Icons.Outlined.Check, 15, p.ok)
                "fail", "denied" -> Ic(Icons.Outlined.Close, 15, p.bad)
                else -> Ic(Icons.Outlined.Close, 15, p.faint)
            }
            if (m.ms > 0) T(secs(m.ms), 11, p.faint, family = Mono)
        }
        if (pending) {
            Row(
                Modifier.fillMaxWidth().background(p.accentSoft).padding(start = 40.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
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
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.sunk).padding(start = 40.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
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
private fun Composer(core: Core, busy: Boolean, project: Project, draft: MutableState<String>, attachReq: String, onSettings: (String) -> Unit, onReqDone: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val s by core.state.collectAsState()
    var text by draft
    val files = remember { mutableStateListOf<Attach>() }
    var modeMenu by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val halfScreen = (LocalConfiguration.current.screenHeightDp / 2).dp

    fun addUri(uri: Uri) {
        if (files.size >= 8) { core.say("You can attach up to 8 items per message."); return }
        scope.launch {
            val (att, err) = withContext(Dispatchers.IO) { Attachments.read(ctx, uri) }
            if (att != null) files.add(att)
            if (err != null) core.say(err)
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> uris.forEach { addUri(it) } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach { addUri(it) } }
    LaunchedEffect(attachReq) {
        when (attachReq) {
            "image" -> imagePicker.launch("image/*")
            "file" -> filePicker.launch(arrayOf("*/*"))
        }
        if (attachReq.isNotEmpty()) onReqDone()
    }

    fun doSend() {
        if (busy || (text.isBlank() && files.isEmpty())) return
        core.send(text, files.toList())
        text = ""
        files.clear()
    }

    // ---- voice: the typing bar itself turns into a recorder (no system pop-up)
    val voice = remember { VoiceController(ctx.applicationContext, core) }
    DisposableEffect(Unit) { onDispose { voice.cancel() } }
    val haptic = LocalHapticFeedback.current
    var voiceBase by remember { mutableStateOf("") }
    fun merge(base: String, spoken: String) = if (base.isBlank()) spoken else base.trimEnd() + " " + spoken
    fun startVoice() {
        voiceBase = text
        val ok = voice.start(
            onUpdate = { text = merge(voiceBase, it) },
            onDone = { spoken ->
                text = if (spoken.isBlank()) voiceBase else merge(voiceBase, spoken)
                if (spoken.isNotBlank() && s.settings.voiceAutoSend) doSend()
            },
        )
        if (ok) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startVoice() else core.say("Microphone permission is needed for voice input.")
    }
    fun onMic() {
        if (voice.phase != VoicePhase.Idle) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
    val recording = voice.phase != VoicePhase.Idle

    val can = text.isNotBlank() || files.isNotEmpty()
    val sendSrc = remember { MutableInteractionSource() }
    val sendPressed by sendSrc.collectIsPressedAsState()
    val sendScale by animateFloatAsState(if (sendPressed) .9f else 1f, krowSpring(), label = "send")
    val btnMode = when { busy -> 0; can -> 1; else -> 2 } // 0 stop · 1 send · 2 mic
    val solidA by animateFloatAsState(if (btnMode == 2) 0f else 1f, tween(220), label = "solid")
    val borderCol by animateColorAsState(
        when { recording -> p.accent.copy(alpha = .55f); focused -> p.accent.copy(alpha = .7f); else -> p.line2 },
        tween(240), label = "border",
    )
    val shape = RoundedCornerShape(30.dp)
    val hasMsgs = s.chats.firstOrNull { it.id == s.activeChat }?.msgs?.isNotEmpty() == true

    BoxWithConstraints(Modifier.fillMaxWidth().background(p.bg)) {
        // The typing area grows with the text (wrapped lines included) up to half the screen, then scrolls inside itself.
        // On small windows / with the keyboard open it also leaves room for the top bar, toolbar row and a few lines of chat.
        val fieldMax = minOf(halfScreen, maxHeight - 250.dp).coerceAtLeast(96.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = p.threadMax).fillMaxWidth()
                    .clip(shape).background(p.field)
                    .border(1.dp, borderCol, shape),
            ) {
                AnimatedContent(
                    targetState = recording,
                    transitionSpec = { (fadeIn(tween(200, delayMillis = 70)) + scaleIn(tween(200, delayMillis = 70), initialScale = .95f)) togetherWith fadeOut(tween(90)) },
                    label = "composerMode",
                ) { rec ->
                    if (rec) VoiceBar(voice, onCancel = { voice.cancel(); text = voiceBase }, onConfirm = { voice.confirm() })
                    else Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
                        AttachPreview(files) { f -> files.remove(f); f.image?.let { Attachments.delete(it) } }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 24.dp, max = fieldMax)
                                .focusRequester(focus),
                            textStyle = TextStyle(color = p.ink, fontSize = 16.sp, lineHeight = 23.sp),
                            cursorBrush = SolidColor(p.accent),
                            interactionSource = interaction,
                            minLines = 1,
                            maxLines = Int.MAX_VALUE,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Default,
                            ),
                            keyboardActions = KeyboardActions(onSend = { doSend() }),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (text.isEmpty()) T(if (files.isEmpty()) (if (hasMsgs) "Reply to Krow…" else "Message Krow") else "Add a message or just send…", 16, p.faint)
                                    inner()
                                }
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box {
                                BarBtn(Icons.Outlined.Add, "Attach") { attachMenu = true }
                                DropdownMenu(attachMenu, { attachMenu = false }, containerColor = p.raised, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, p.line2)) {
                                    DropdownMenuItem(
                                        { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Ic(Icons.Outlined.Image, 18, p.accent); T("Photos", 14, weight = FontWeight.Medium) } },
                                        { attachMenu = false; imagePicker.launch("image/*") },
                                    )
                                    DropdownMenuItem(
                                        { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Ic(Icons.Outlined.Description, 18, p.accent); Column { T("Files & code", 14, weight = FontWeight.Medium); T("Text, code, images", 12, p.muted) } } },
                                        { attachMenu = false; filePicker.launch(arrayOf("*/*")) },
                                    )
                                }
                            }
                            if (Tools.enabled) Box {
                                BarBtn(modeIcon(s.settings.mode), "Mode: ${modeLabel(s.settings.mode)}", active = s.settings.mode != "ask") { modeMenu = true }
                                DropdownMenu(modeMenu, { modeMenu = false }, containerColor = p.raised, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, p.line2)) {
                                    listOf("ask", "auto", "read").forEach { k ->
                                        DropdownMenuItem(
                                            {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                    Ic(modeIcon(k), 18, if (s.settings.mode == k) p.accent else p.muted)
                                                    Column {
                                                        T(modeLabel(k) + if (s.settings.mode == k) "  ✓" else "", 14, if (s.settings.mode == k) p.accent else p.ink, FontWeight.Medium)
                                                        T(modeHint(k), 12, p.muted)
                                                    }
                                                }
                                            },
                                            { core.setMode(k); modeMenu = false },
                                        )
                                    }
                                }
                            }
                            ModelPill(core, onSettings)
                            Spacer(Modifier.weight(1f))
                            // One button that morphs: mic (empty) → send (has text) → stop (AI is working)
                            Box(
                                Modifier
                                    .scale(sendScale)
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(p.bubble.copy(alpha = 1f - solidA))
                                    .background(p.brand, alpha = solidA)
                                    .clickable(interactionSource = sendSrc, indication = null) {
                                        when (btnMode) { 0 -> core.stop(); 1 -> doSend(); else -> onMic() }
                                    },
                                Alignment.Center,
                            ) {
                                AnimatedContent(
                                    targetState = btnMode,
                                    transitionSpec = { (fadeIn(tween(160)) + scaleIn(tween(160), initialScale = .6f)) togetherWith (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = .6f)) },
                                    label = "btnIcon",
                                ) { m ->
                                    Ic(
                                        when (m) { 0 -> Icons.Outlined.Stop; 1 -> Icons.Outlined.ArrowUpward; else -> Icons.Outlined.Mic },
                                        22, if (m == 2) p.ink else p.accentInk,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            T(
                when {
                    recording -> if (voice.phase == VoicePhase.Recording) "Listening… tap ✓ when you're done" else "Turning your voice into text…"
                    project.kind == "repo" -> "${project.owner}/${project.repo} · ${modeLabel(s.settings.mode)} · Krow can make mistakes"
                    Tools.enabled -> "${project.name} · ${modeLabel(s.settings.mode)} · Krow can make mistakes"
                    else -> "Krow can make mistakes. Check important information."
                },
                11, p.faint,
                modifier = Modifier.widthIn(max = p.threadMax).fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ voice recorder bar
/** Replaces the text box while recording: cancel · pulsing dot + timer · live waveform · done. */
@Composable
private fun VoiceBar(voice: VoiceController, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val p = LocalPal.current
    val listening = voice.phase == VoicePhase.Recording
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listening) {
        if (listening) {
            val t0 = System.currentTimeMillis()
            while (true) {
                elapsed = System.currentTimeMillis() - t0
                if (elapsed >= VOICE_MAX_MS) { onConfirm(); break }
                delay(100)
            }
        }
    }
    val tr = rememberInfiniteTransition(label = "voice")
    val pulse by tr.animateFloat(1f, .25f, infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "dot")
    val dots by tr.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dots")
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        AnimatedVisibility(voice.live.isNotBlank()) {
            T(voice.live, 15, p.ink, maxLines = 4, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp))
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VoiceRound(Icons.Outlined.Close, "Cancel", filled = false, onClick = onCancel)
            if (listening) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(p.bad.copy(alpha = pulse)))
                    val sec = (elapsed / 1000).toInt()
                    T("%d:%02d".format(sec / 60, sec % 60), 14, p.ink, FontWeight.Medium, Mono)
                }
                Waveform(voice.levels, p.accent, Modifier.weight(1f).fillMaxHeight())
                VoiceRound(Icons.Outlined.Check, "Done", filled = true, onClick = onConfirm)
            } else {
                Box(Modifier.weight(1f), Alignment.CenterStart) {
                    T("Transcribing" + ".".repeat(dots.toInt().coerceIn(0, 2) + 1), 14, p.muted, FontWeight.Medium)
                }
                Box(Modifier.size(44.dp), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = p.accent, strokeWidth = 2.5.dp)
                }
            }
        }
    }
}

@Composable
private fun VoiceRound(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, filled: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) .9f else 1f, krowSpring(), label = "voiceRound")
    Box(
        Modifier
            .scale(sc)
            .size(44.dp)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(p.brand) else Modifier.background(p.line).border(1.dp, p.line2, CircleShape))
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        Alignment.Center,
    ) { Ic(icon, 22, if (filled) p.accentInk else p.muted) }
}

/** Scrolling loudness bars, newest on the right. */
@Composable
private fun Waveform(levels: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val barW = 3.dp.toPx()
        val step = barW + 3.dp.toPx()
        val n = (size.width / step).toInt().coerceAtLeast(1)
        val start = levels.size - n
        for (i in 0 until n) {
            val v = levels.getOrNull(start + i) ?: 0f
            val h = (size.height * (.1f + .9f * v)).coerceAtLeast(barW)
            val x = size.width - (n - i) * step + (step - barW) / 2f
            drawRoundRect(
                color = color.copy(alpha = .3f + .7f * (i.toFloat() / n)),
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}


/** Round button used on the typing bar. */
@Composable
private fun BarBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, active: Boolean = false, onClick: () -> Unit) {
    val p = LocalPal.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) .9f else 1f, krowSpring(), label = "barBtn")
    Box(
        Modifier
            .scale(sc)
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) p.accentSoft else p.bubble)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        Alignment.Center,
    ) { Ic(icon, 20, if (active) p.accent else p.ink) }
}
