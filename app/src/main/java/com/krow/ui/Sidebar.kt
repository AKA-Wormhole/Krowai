package com.krow.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krow.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Drawer: wordmark, quick nav (Chats · Projects · Images · Artifacts), Pinned + Recents, and a floating
 * footer with the profile avatar (opens Settings) and a New chat button.
 */
@Composable
fun Sidebar(core: Core, close: () -> Unit, openSettings: (String) -> Unit, openFiles: () -> Unit = {}) {
    val p = LocalPal.current
    val s by core.state.collectAsState()
    val busy by core.busy.collectAsState()
    var newProj by remember { mutableStateOf(false) }
    var repoPicker by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Chat?>(null) }
    var delProj by remember { mutableStateOf<Project?>(null) }
    var projMenu by remember { mutableStateOf(false) }
    var delChat by remember { mutableStateOf<Chat?>(null) }
    val active = s.projects.firstOrNull { it.id == s.activeProject }
    var searching by rememberSaveable { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) runCatching { focus.requestFocus() } }
    val query = q.trim()
    val chats = s.chats.filter { (s.activeProject.isEmpty() || it.projectId == s.activeProject) && (query.isEmpty() || it.title.contains(query, true) || it.msgs.any { m -> m.text.contains(query, true) }) }
        .sortedByDescending { it.updated }
    val pinned = chats.filter { it.pinned }
    val recent = chats.filter { !it.pinned }
    val initials = remember(s.settings.userName) {
        s.settings.userName.trim().split(' ').filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString("").ifBlank { "K" }
    }

    Box(Modifier.fillMaxHeight().background(p.side)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 12.dp, top = 22.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                T("Krow", 30, weight = FontWeight.Medium, family = Read, modifier = Modifier.weight(1f))
                IconBtn(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "Search chats", 44, p.ink) { searching = !searching; if (!searching) q = "" }
            }
            if (searching) Row(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.raised).border(1.dp, p.line, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Ic(Icons.Outlined.Search, 18, p.faint)
                BasicTextField(q, { q = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true, textStyle = TextStyle(color = p.ink, fontSize = 15.sp), cursorBrush = SolidColor(p.accent),
                    decorationBox = { inner -> Box { if (q.isEmpty()) T("Search chats", 15, p.faint); inner() } })
            }
            Box {
                NavRow(Icons.Outlined.FolderOpen, "Projects", chevron = true, trailing = core.linkedRepo()?.repo ?: if (s.activeProject.isEmpty()) "All" else active?.name) { projMenu = true }
                DropdownMenu(projMenu, { projMenu = false }, containerColor = p.raised, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, p.line2)) {
                    DropdownMenuItem({ T("All projects" + if (s.activeProject.isEmpty()) "  ✓" else "", 14, if (s.activeProject.isEmpty()) p.accent else p.ink) }, { projMenu = false; core.setActiveProject("") })
                    s.projects.forEach { pr ->
                        DropdownMenuItem({
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(pr.color)))
                                T(pr.name + if (s.activeProject == pr.id) "  ✓" else "", 14, if (s.activeProject == pr.id) p.accent else p.ink, maxLines = 1)
                            }
                        }, { projMenu = false; core.setActiveProject(pr.id) })
                    }
                    DropdownMenuItem({ T("New project…", 14, p.muted) }, { projMenu = false; newProj = true })
                    DropdownMenuItem({ T("Add GitHub repo…", 14, p.muted) }, { projMenu = false; repoPicker = true })
                    if (active != null && active.id != "scratch") DropdownMenuItem({ T("Delete “${active.name}”…", 14, p.bad) }, { projMenu = false; delProj = active })
                }
            }
            NavRow(Icons.Outlined.Inventory2, "Artifacts") { close(); openFiles() }
            Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth().height(1.dp).background(p.line))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 120.dp)) {
                if (chats.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Ic(Icons.Outlined.ChatBubbleOutline, 32, p.faint)
                        T(if (query.isEmpty()) "No chats yet" else "Nothing matches “$query”", 15, p.ink, FontWeight.Medium)
                        if (query.isEmpty()) T("Tap New chat to start one.", 13, p.muted)
                    }
                }
                if (pinned.isNotEmpty()) {
                    item(key = "hPinned") { SideLabel("Pinned") }
                    items(pinned, key = { it.id }) { c -> ChatItem(core, c, s, busy, close, { renaming = c }, { delChat = c }) }
                }
                if (recent.isNotEmpty()) {
                    item(key = "hRecent") { SideLabel("Recents") }
                    items(recent, key = { it.id }) { c -> ChatItem(core, c, s, busy, close, { renaming = c }, { delChat = c }) }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(p.side.copy(alpha = 0f), p.side)))
                .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(54.dp).clip(CircleShape).background(p.brand).clickable { close(); openSettings("home") }, Alignment.Center) {
                T(initials, 18, p.accentInk, FontWeight.SemiBold, lh = 1f)
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.height(54.dp).clip(CircleShape).background(p.ink).clickable { core.newChat(); close() }.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) { Ic(Icons.Outlined.Add, 22, p.bg); T("New chat", 17, p.bg, FontWeight.SemiBold) }
        }
    }
    if (repoPicker) RepoPicker(core, onClose = { repoPicker = false }, onDone = { repoPicker = false; close() })
    if (newProj) TextDialog("New project", "", "Project name", "Create", { newProj = false }) { core.addProject(it); newProj = false }
    renaming?.let { c -> TextDialog("Rename chat", c.title, "Title", "Save", { renaming = null }) { core.renameChat(c.id, it); renaming = null } }
    delChat?.let { c -> ConfirmDialog("Delete this chat?", "“${c.title}” will be removed. This cannot be undone.", "Delete", { delChat = null }) { core.deleteChat(c.id); delChat = null } }
    delProj?.let { pr -> ConfirmDialog("Delete “${pr.name}”?", (if (pr.kind == "repo") "This deletes the local copy (including uncommitted changes) and every chat in it. Nothing on GitHub is touched." else "This deletes the project folder with all its files, and every chat in it. This cannot be undone."), "Delete", { delProj = null }) { core.deleteProject(pr.id); delProj = null } }
}

fun hostName(u: String): String = u.removePrefix("https://").removePrefix("http://").substringBefore('/').ifBlank { "Provider" }

@Composable
private fun NavRow(icon: ImageVector, label: String, chevron: Boolean = false, trailing: String? = null, selected: Boolean = false, trailingIcon: ImageVector? = null, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 1.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) p.accentSoft else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Ic(icon, 24, if (selected) p.accent else p.ink)
        T(label, 17, weight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
        if (trailing != null) T(trailing, 13, p.faint, maxLines = 1, modifier = Modifier.widthIn(max = 110.dp))
        if (chevron) Ic(Icons.Outlined.ExpandMore, 20, p.muted)
        if (trailingIcon != null) Ic(trailingIcon, 20, p.muted)
    }
}

@Composable
private fun SideLabel(t: String) {
    val p = LocalPal.current
    T(t, 14, p.faint, FontWeight.Medium, modifier = Modifier.padding(start = 28.dp, end = 24.dp, top = 8.dp, bottom = 6.dp))
}

@Composable
private fun ChatItem(core: Core, c: Chat, s: AppState, busy: String?, close: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val dot = if (s.activeProject.isEmpty() && s.projects.size > 1) s.projects.firstOrNull { it.id == c.projectId }?.let { Color(it.color) } else null
    ChatRow(core, c, s.activeChat == c.id, busy == c.id, dot, close, onRename, onDelete)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(core: Core, c: Chat, current: Boolean, running: Boolean, dot: Color?, close: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val p = LocalPal.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 12.dp, vertical = 1.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (current) p.raised else Color.Transparent)
                .combinedClickable(onClick = { core.openChat(c.id); close() }, onLongClick = { menu = true }).padding(start = 14.dp, end = if (current) 4.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (running) CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
            else if (dot != null) Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            else Ic(if (c.pinned) Icons.Outlined.PushPin else Icons.Outlined.ChatBubbleOutline, 18, if (current) p.accent else p.faint)
            T(c.title, 16, p.ink, if (current) FontWeight.Medium else FontWeight.Normal, maxLines = 1, modifier = Modifier.weight(1f).padding(vertical = 13.dp))
            if (current) IconBtn(Icons.Outlined.MoreVert, "More", 36, p.muted) { menu = true }
        }
        DropdownMenu(menu, { menu = false }, containerColor = p.raised, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, p.line2)) {
            MenuItem(Icons.Outlined.PushPin, if (c.pinned) "Unpin" else "Pin") { menu = false; core.pinChat(c.id) }
            MenuItem(Icons.Outlined.Edit, "Rename") { menu = false; onRename() }
            MenuItem(Icons.Outlined.Delete, "Delete", p.bad) { menu = false; onDelete() }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, color: Color = LocalPal.current.ink, onClick: () -> Unit) {
    DropdownMenuItem({
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Ic(icon, 18, color); T(label, 14, color) }
    }, onClick)
}
