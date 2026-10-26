package com.krow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import com.krow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebSearchSection(core: Core) {
    val p = LocalPal.current
    val s by core.state.collectAsState()
    val st = s.settings
    val scope = rememberCoroutineScope()
    var searx by remember(st.searxUrl) { mutableStateOf(st.searxUrl) }
    var fg by remember(st.fourgetUrl) { mutableStateOf(st.fourgetUrl) }
    var lib by remember(st.libreyUrl) { mutableStateOf(st.libreyUrl) }
    var cx by remember(st.googleCx) { mutableStateOf(st.googleCx) }
    var keyIn by rememberSaveable { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    val gKey = remember(tick) { core.secrets.get(Secrets.GOOGLE_KEY) }

    Section("Web search & live info") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("AI can search the web", selected = st.webSearch) { core.setSettings { it.copy(webSearch = !it.webSearch) } }
        }
        T("When on, Krow gets web_search and fetch_url tools: it looks things up and reads pages itself, so answers are current and come with links. Search results and pages are fetched from this phone, never through Anthropic or anyone else's server except the engine you pick.", 12, p.muted)
        Label("Engine")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SEARCH_PROVIDERS.forEach { (k, l) -> Pill(l, selected = st.searchProvider == k) { core.setSettings { it.copy(searchProvider = k) } } }
        }
        T("Auto tries, in order: google.com (parsed directly), Google API, your SearXNG, 4get, LibreY, then DuckDuckGo — first one with results wins. Images/News skip google.com parsing.", 12, p.muted)
        Label("SearXNG instance (must allow format=json)"); Field(searx, { searx = it }, "https://searx.example.org", mono = true)
        Label("4get instance"); Field(fg, { fg = it }, "https://4get.example.org", mono = true)
        Label("LibreY instance (API enabled)"); Field(lib, { lib = it }, "https://librey.example.org", mono = true)
        Label("Google Custom Search engine id (cx)"); Field(cx, { cx = it }, "0123456789abcdef", mono = true)
        Label("Google API key" + if (gKey != null) "  ·  saved (…${gKey.takeLast(4)})" else "  ·  not saved")
        Field(keyIn, { keyIn = it }, "Paste key", mono = true, password = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Save", filled = true) {
                core.setSettings { it.copy(searxUrl = searx.trim(), fourgetUrl = fg.trim(), libreyUrl = lib.trim(), googleCx = cx.trim()) }
                if (keyIn.isNotBlank()) { core.secrets.put(Secrets.GOOGLE_KEY, keyIn.trim()); keyIn = ""; tick++ }
                core.say("Search settings saved")
            }
            if (gKey != null) Pill("Remove key") { core.secrets.remove(Secrets.GOOGLE_KEY); tick++ }
            Pill(if (testing) "Testing…" else "Test search") {
                if (!testing) scope.launch {
                    testing = true
                    core.setSettings { it.copy(searxUrl = searx.trim(), fourgetUrl = fg.trim(), libreyUrl = lib.trim(), googleCx = cx.trim()) }
                    if (keyIn.isNotBlank()) { core.secrets.put(Secrets.GOOGLE_KEY, keyIn.trim()); keyIn = ""; tick++ }
                    core.say(try { val r = core.search.search("weather today"); "OK via ${r.engine}: ${r.hits.size} results" } catch (e: Throwable) { (e.message ?: "failed").take(220) })
                    testing = false
                }
            }
        }
        T("Public instances often block apps or disable JSON/API output — your own instance (or one that allows it) is the reliable choice. Google's official Custom Search API (free tier, 100 queries/day) is the reliable way to get real Google results; direct google.com parsing is best-effort and Google may show a captcha.", 12, p.muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageSettingsSection(core: Core) {
    val p = LocalPal.current
    val st by core.state.collectAsState()
    Section("Images") {
        Label("Photos you attach to a chat")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("fast" to "Fast (1024px)", "balanced" to "Balanced (1568px)", "high" to "Sharp (2048px)").forEach { (k, l) ->
                Pill(l, selected = st.settings.imgQuality == k) { core.setSettings { it.copy(imgQuality = k) } }
            }
        }
        T("Smaller = faster and cheaper, sharper = better for reading small text in screenshots.", 12, p.muted)
    }
}
