package com.krow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.krow.*

@Composable
fun MemorySection(core: Core) {
    val p = LocalPal.current
    val s by core.state.collectAsState()
    val items by core.memory.items.collectAsState()
    var add by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    Section("Memory") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Auto-save", "ask" to "Ask first", "off" to "Off").forEach { (k, l) -> Pill(l, selected = s.settings.memory == k) { core.setSettings { it.copy(memory = k) } } }
        }
        T("Krow keeps short facts about you (preferences, projects) on this phone and uses only the relevant ones in each chat. Auto-save lets the AI add them, Ask first confirms every save, Off disables memory completely.", 12, p.muted)
        Field(add, { add = it }, "Add a fact, e.g. I prefer Kotlin and short answers")
        Pill("Add", filled = true) { if (add.isNotBlank()) { runCatching { core.memory.add(add, "preference") }; add = "" } }
        Label("${items.size} saved · pin to always include")
        items.take(60).forEach { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (e.pinned) "Pinned" else "Pin", selected = e.pinned, small = true) { core.memory.pin(e.id) }
                Column(Modifier.weight(1f)) { T(e.text, 13); T(e.tag, 11, p.faint) }
                IconBtn(Icons.Outlined.Close, "Delete") { core.memory.remove(e.id) }
            }
        }
        if (items.isNotEmpty()) Pill("Clear all memory", color = p.bad) { confirm = true }
    }
    if (confirm) ConfirmDialog("Clear all memory?", "This removes every saved fact.", "Clear", { confirm = false }) { core.memory.clear(); confirm = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectorsSection(core: Core) {
    val p = LocalPal.current
    val s by core.state.collectAsState()
    val list = s.settings.connectors
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("https://") }
    var about by rememberSaveable { mutableStateOf("") }
    var auth by rememberSaveable { mutableStateOf("none") }
    var authName by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var write by rememberSaveable { mutableStateOf(false) }
    Section("Connectors") {
        T("Connect any HTTPS REST API. Krow can read from it (and write, only if you allow it and approve each call). Calls never leave the API's own host; tokens are stored encrypted.", 12, p.muted)
        list.forEach { c ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) { T(c.name, 14); T(c.baseUrl + if (c.write) "  · writes on" else "  · read-only", 11, p.faint, family = Mono) }
                IconBtn(Icons.Outlined.Close, "Remove") { core.secrets.remove(Connectors.key(c)); core.setSettings { st -> st.copy(connectors = st.connectors - c) } }
            }
        }
        Label("Add connector — start from a preset")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CONNECTOR_PRESETS.forEach { pr -> Pill(pr.name) { name = if (pr.name == "Custom API") "" else pr.name; url = pr.baseUrl; about = pr.about } }
        }
        Field(name, { name = it }, "Name"); Field(url, { url = it }, "Base URL", mono = true); Field(about, { about = it }, "What it's for (helps the AI)", singleLine = false)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("none" to "No auth", "bearer" to "Bearer", "header" to "Header", "query" to "Query").forEach { (k, l) -> Pill(l, selected = auth == k) { auth = k } }
        }
        if (auth == "header" || auth == "query") Field(authName, { authName = it }, if (auth == "header") "Header name, e.g. X-API-Key" else "Parameter name, e.g. api_key", mono = true)
        if (auth != "none") Field(token, { token = it }, "Token / key", mono = true, password = true)
        Pill("Allow writes (POST/PUT/DELETE)", selected = write) { write = !write }
        Pill("Save connector", filled = true) {
            val n = name.trim(); val u = url.trim()
            if (n.isEmpty() || !u.startsWith("https://") || u.length < 12) core.say("Give it a name and an https:// URL")
            else if (list.any { it.name.equals(n, true) }) core.say("A connector with this name exists")
            else {
                val c = Connector(uid(), n, u, auth, authName.trim(), about.trim(), write)
                if (auth != "none" && token.isNotBlank()) core.secrets.put(Connectors.key(c), token.trim())
                core.setSettings { it.copy(connectors = it.connectors + c) }
                name = ""; url = "https://"; about = ""; token = ""; authName = ""; write = false; auth = "none"; core.say("Connector added")
            }
        }
    }
}
