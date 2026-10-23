package com.krow.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krow.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private fun groupOf(t: Long): String {
    val d = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when { d == today -> "Today"; d == today.minusDays(1) -> "Yesterday"; else -> "Earlier" }
}

private fun fmtTok(n: Int) = if (n >= 1000) String.format("%.1fk", n / 1000.0) else n.toString()

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun Sidebar(core: Core, close: () -> Unit, openSettings: () -> Unit) {
    val p = LocalPal.current
    val s by core.state.collectAsState()
    val busy by core.busy.collectAsState()
    var tab by rememberSaveable { mutableStateOf("chats") }
    var q by rememberSaveable { mutableStateOf("") }
    var newProj by remember { mutableStateOf(false) }
    var repoPicker by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Chat?>(null) }
    var delProj by remember { mutableStateOf<Project?>(null) }
    val activeProject = s.projects.firstOrNull { it.id == s.activeProject }
    val runs = remember(s.chats) { s.chats.flatMap { c -> c.msgs.filter { it.role == "tool" }.map { c to it } }.sortedByDescending { it.second.at }.take(200) }

    Column(Modifier.fillMaxHeight().background(p.side)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Mark(28)
            Column(Modifier.weight(1f)) { T("Krow", 15, weight = FontWeight.SemiBold); T(activeProject?.name ?: "All projects", 12, p.muted, maxLines = 1) }
            IconBtn(Icons.Outlined.Close, "Close", onClick = close)
        }
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clip(CircleShape).background(p.accent).clickable { core.newChat(); tab = "chats"; close() }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Ic(Icons.Outlined.Add, 16, p.accentInk); T("New chat", 14, p.accentInk, FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth().clip(CircleShape).background(p.sunk).border(1.dp, p.line, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Ic(Icons.Outlined.Search, 16)
                BasicTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, textStyle = TextStyle(color = p.ink, fontSize = 14.sp), cursorBrush = SolidColor(p.accent),
                    decorationBox = { inner -> Box { if (q.isEmpty()) T("Search chats", 14, p.muted); inner() } })
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(CircleShape).background(p.sunk).border(1.dp, p.line, CircleShape).padding(2.dp)) {
            for ((k, label) in listOf("chats" to "Chats", "runs" to "Runs")) {
                val sel = tab == k
                Row(Modifier.weight(1f).clip(CircleShape).background(if (sel) p.raised else Color.Transparent).clickable { tab = k }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    T(label, 13, if (sel) p.ink else p.muted, FontWeight.Medium)
                    if (k == "runs") { Spacer(Modifier.width(6.dp)); T("${runs.size}", 11, p.accent, family = Mono) }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (tab == "chats") {
                val query = q.trim()
                val chats = s.chats.filter { (s.activeProject.isEmpty() || it.projectId == s.activeProject) && (query.isEmpty() || it.title.contains(query, true) || it.msgs.any { m -> m.text.contains(query, true) }) }
                    .sortedByDescending { it.updated }
                val pinned = chats.filter { it.pinned }
                val groups = listOf("Today", "Yesterday", "Earlier").map { g -> g to chats.filter { !it.pinned && groupOf(it.updated) == g } }.filter { it.second.isNotEmpty() }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    item {
                        SideLabel("Projects")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                            Pill("All", small = true, selected = s.activeProject.isEmpty()) { core.setActiveProject("") }
                            s.projects.forEach { pr ->
                                var m by remember { mutableStateOf(false) }
                                Box {
                                    Row(Modifier.clip(CircleShape).background(if (s.activeProject == pr.id) p.accentSoft else Color.Transparent)
                                        .border(1.dp, if (s.activeProject == pr.id) Color.Transparent else p.line, CircleShape)
                                        .combinedClickable(onClick = { core.setActiveProject(pr.id) }, onLongClick = { if (pr.id != "scratch") m = true })
                                        .padding(start = 4.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(Modifier.size(18.dp).clip(CircleShape).background(Color(pr.color)), Alignment.Center) { T(pr.name.take(1).uppercase(), 10, p.bg, FontWeight.Medium, Mono, lh = 1f) }
                                        T(pr.name, 12, if (s.activeProject == pr.id) p.ink else p.muted, FontWeight.Medium, maxLines = 1)
                                    }
                                    DropdownMenu(m, { m = false }, containerColor = p.raised, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, p.line2)) {
                                        DropdownMenuItem({ T("Delete project…", 14, p.bad) }, { m = false; delProj = pr })
                                    }
                                }
                            }
                            Pill("Project", small = true, icon = Icons.Outlined.Add) { newProj = true }
                            Pill("Repo", small = true, icon = Icons.Outlined.AccountTree) { repoPicker = true }
                        }
                    }
                    if (chats.isEmpty()) item { T(if (query.isEmpty()) "No chats yet. Start one above." else "Nothing matches “$query”.", 13, p.muted, modifier = Modifier.padding(8.dp)) }
                    if (pinned.isNotEmpty()) { item { SideLabel("Pinned") }; items(pinned, key = { it.id }) { c -> ChatRow(core, c, s.activeChat == c.id, busy == c.id, close) { renaming = c } } }
                    groups.forEach { (g, list) -> item { SideLabel(g) }; items(list, key = { it.id }) { c -> ChatRow(core, c, s.activeChat == c.id, busy == c.id, close) { renaming = c } } }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    if (runs.isEmpty()) item { T(if (Tools.enabled) "No runs yet. Commands and file changes Krow performs show up here." else "Runs appear here once Krow can run tools (added in the next build stage).", 13, p.muted, modifier = Modifier.padding(8.dp)) }
                    items(runs) { (c, m) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { core.openChat(c.id); close() }.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(p.sunk).border(1.dp, p.line, RoundedCornerShape(7.dp)), Alignment.Center) { Ic(kindIcon(m.kind), 14) }
                            Column(Modifier.weight(1f)) {
                                T(m.title, 13, weight = FontWeight.Medium, maxLines = 1)
                                T(m.cmd.ifBlank { c.title }, 11, p.muted, family = Mono, maxLines = 1)
                            }
                            when (m.status) { "done" -> Ic(Icons.Outlined.Check, 13, p.ok); "fail", "denied" -> Ic(Icons.Outlined.Close, 13, p.bad); else -> Ic(Icons.Outlined.Remove, 13, p.faint) }
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.sunk).border(1.dp, p.line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                T("Tokens today", 12, p.muted)
                T(if (core.tokensToday(s) > 0) fmtTok(core.tokensToday(s)) else "—", 12, family = Mono)
            }
        }
        HLine()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(p.raised).border(1.dp, p.line2, CircleShape), Alignment.Center) { T("K", 12, weight = FontWeight.SemiBold) }
            Column(Modifier.weight(1f)) { T(if (s.settings.provider == "anthropic") "Anthropic" else hostName(s.settings.baseUrl), 13, weight = FontWeight.Medium, maxLines = 1); T(s.settings.model.ifBlank { "no model" }, 11, p.muted, family = Mono, maxLines = 1) }
            Pill(when (s.settings.theme) { "dark" -> "Dark"; "light" -> "Light"; else -> "Auto" }, small = true) {
                core.setSettings { it.copy(theme = when (it.theme) { "system" -> "light"; "light" -> "dark"; else -> "system" }) }
            }
            Pill("Settings", small = true) { openSettings() }
        }
    }
    if (repoPicker) RepoPicker(core, onClose = { repoPicker = false }, onDone = { repoPicker = false; close() })
    if (newProj) TextDialog("New project", "", "Project name", "Create", { newProj = false }) { core.addProject(it); newProj = false }
    renaming?.let { c -> TextDialog("Rename chat", c.title, "Title", "Save", { renaming = null }) { core.renameChat(c.id, it); renaming = null } }
    delProj?.let { pr -> ConfirmDialog("Delete “${pr.name}”?", (if (pr.kind == "repo") "This deletes the local copy (including uncommitted changes) and every chat in it. Nothing on GitHub is touched." else "This deletes the project folder with all its files, and every chat in it. This cannot be undone."), "Delete", { delProj = null }) { core.deleteProject(pr.id); delProj = null } }
}

fun hostName(u: String): String = u.removePrefix("https://").removePrefix("http://").substringBefore('/').ifBlank { "Provider" }

@Composable
private fun SideLabel(t: String) {
    T(t.uppercase(), 11, LocalPal.current.faint, FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp))
}

@Composable
private fun ChatRow(core: Core, c: Chat, current: Boolean, running: Boolean, close: () -> Unit, onRename: () -> Unit) {
    val p = LocalPal.current
    var menu by remember { mutableStateOf(false) }
    val shape = CircleShape
    Row(Modifier.fillMaxWidth().clip(shape).background(if (current) p.raised else Color.Transparent).border(1.dp, if (current) p.line else Color.Transparent, shape)
        .clickable { core.openChat(c.id); close() }.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running) CircularProgressIndicator(Modifier.size(12.dp), color = p.accent, strokeWidth = 1.5.dp)
        T(c.title, 14, if (current) p.ink else p.muted, if (current) FontWeight.Medium else FontWeight.Normal, maxLines = 1, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (c.pinned) Ic(Icons.Outlined.PushPin, 12, p.accent)
        Box {
            IconBtn(Icons.Outlined.MoreHoriz, "More", 32) { menu = true }
            DropdownMenu(menu, { menu = false }, containerColor = p.raised, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, p.line2)) {
                DropdownMenuItem({ T(if (c.pinned) "Unpin" else "Pin", 14) }, { menu = false; core.pinChat(c.id) })
                DropdownMenuItem({ T("Rename", 14) }, { menu = false; onRename() })
                DropdownMenuItem({ T("Delete", 14, p.bad) }, { menu = false; core.deleteChat(c.id) })
            }
        }
    }
}
