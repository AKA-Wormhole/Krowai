package com.krow.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krow.*
import kotlinx.coroutines.launch

private class Preset(val name: String, val provider: String, val url: String, val models: List<String>)

private val PRESETS = listOf(
    Preset("OpenAI", "openai", "https://api.openai.com/v1", listOf("gpt-4o-mini", "gpt-4o")),
    Preset("OpenRouter", "openai", "https://openrouter.ai/api/v1", listOf("openai/gpt-4o-mini")),
    Preset("Groq", "openai", "https://api.groq.com/openai/v1", listOf("llama-3.3-70b-versatile")),
    Preset("Anthropic", "anthropic", "https://api.anthropic.com", listOf("claude-sonnet-5-5")),
    Preset("Custom", "openai", "", emptyList()),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(core: Core, onBack: () -> Unit, extra: @Composable ColumnScope.() -> Unit = {}) {
    val p = LocalPal.current
    val s by core.state.collectAsState()
    val st = s.settings
    val scope = rememberCoroutineScope()
    var keyIn by rememberSaveable { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var modelIn by rememberSaveable { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var urlIn by remember(st.baseUrl) { mutableStateOf(st.baseUrl) }
    val saved = remember(tick, st.baseUrl) { core.apiKey() }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconBtn(Icons.Outlined.ArrowBack, "Back", onClick = onBack)
            T("Settings", 16, weight = FontWeight.Medium)
        }
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Section("AI provider") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRESETS.forEach { pr ->
                        Pill(pr.name, selected = pr.url.isNotEmpty() && pr.url == st.baseUrl) {
                            core.setSettings { it.copy(provider = pr.provider, baseUrl = pr.url, models = pr.models, model = pr.models.firstOrNull() ?: "") }
                        }
                    }
                }
                Label("Base URL"); Field(urlIn, { urlIn = it }, "https://…/v1", mono = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("OpenAI-compatible", selected = st.provider == "openai") { core.setSettings { it.copy(provider = "openai") } }
                    Pill("Anthropic API", selected = st.provider == "anthropic") { core.setSettings { it.copy(provider = "anthropic") } }
                    Pill("Save URL", filled = true) { core.setSettings { it.copy(baseUrl = urlIn.trim()) }; core.say("Base URL saved") }
                }
                Label("API key" + if (saved != null) "  ·  saved (…${saved.takeLast(4)})" else "  ·  not saved")
                Field(keyIn, { keyIn = it }, "Paste key", mono = true, password = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Save key", filled = true) {
                        if (keyIn.isNotBlank()) { core.secrets.put(Secrets.apiKeyName(st.baseUrl), keyIn.trim()); keyIn = ""; tick++; core.say("Key saved (encrypted)") }
                    }
                    if (saved != null) Pill("Remove key") { core.secrets.remove(Secrets.apiKeyName(st.baseUrl)); tick++ }
                }
                T("Keys are stored encrypted on this device (Android Keystore) and are only sent to the Base URL above. Each Base URL has its own key.", 12, p.muted)
            }
            Section("Models") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    st.models.forEach { m ->
                        Row(Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(if (m == st.model) p.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                            .border(1.dp, if (m == st.model) androidx.compose.ui.graphics.Color.Transparent else p.line, androidx.compose.foundation.shape.CircleShape)
                            .clickable { core.setSettings { it.copy(model = m) } }.padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            T(m, 12, if (m == st.model) p.accent else p.muted, FontWeight.Medium, Mono)
                            Box(Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable {
                                core.setSettings { c -> val l = c.models - m; c.copy(models = l, model = if (c.model == m) l.firstOrNull() ?: "" else c.model) }
                            }, Alignment.Center) { Ic(Icons.Outlined.Close, 12) }
                        }
                    }
                }
                Field(modelIn, { modelIn = it }, "Model id, e.g. gpt-4o", mono = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Add model", filled = true) {
                        val m = modelIn.trim()
                        if (m.isNotEmpty()) { core.setSettings { it.copy(models = (it.models + m).distinct(), model = m) }; modelIn = "" }
                    }
                    Pill(if (fetching) "Fetching…" else "Fetch from API") {
                        if (!fetching) scope.launch {
                            fetching = true
                            try {
                                val ids = core.fetchModels()
                                core.setSettings { it.copy(models = ids, model = if (it.model in ids) it.model else ids.firstOrNull() ?: it.model) }
                                core.say("${ids.size} models loaded")
                            } catch (e: Throwable) { core.say("Could not fetch models: ${e.message?.take(160)}") }
                            fetching = false
                        }
                    }
                }
            }
            Section("Behavior") {
                Label("Custom instructions (added to every chat)")
                var ins by remember(st.instructions) { mutableStateOf(st.instructions) }
                Field(ins, { ins = it }, "e.g. Answer briefly. Prefer Kotlin.", singleLine = false)
                Pill("Save instructions", filled = true) { core.setSettings { it.copy(instructions = ins) }; core.say("Saved") }
            }
            extra()
            Section("Appearance") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "Auto", "light" to "Light", "dark" to "Dark").forEach { (k, l) -> Pill(l, selected = st.theme == k) { core.setSettings { it.copy(theme = k) } } }
                }
            }
            Section("Data") {
                Pill("Delete all chats", color = p.bad) { confirmClear = true }
                T("Everything (chats, projects, files) stays on this device. Nothing is sent anywhere except to the AI provider you configured and, if you use it, GitHub.", 12, p.muted)
            }
        }
    }
    if (confirmClear) ConfirmDialog("Delete all chats?", "Project folders and files are kept.", "Delete", { confirmClear = false }) { core.clearAllChats(); confirmClear = false }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, p.line, RoundedCornerShape(14.dp)).background(p.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T(title.uppercase(), 11, p.faint, FontWeight.Medium)
        content()
    }
}

@Composable
fun Label(t: String) { T(t, 12, LocalPal.current.muted) }
