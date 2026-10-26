package com.krow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import com.krow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceSection(core: Core) {
    val p = LocalPal.current
    val s by core.state.collectAsState()
    val st = s.settings
    val scope = rememberCoroutineScope()
    var keyIn by rememberSaveable { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var model by remember(st.voiceModel) { mutableStateOf(st.voiceModel) }
    val saved = remember(tick) { core.secrets.get(Secrets.GEMINI) }

    Section("Voice input") {
        Label("Engine")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Gemini (Google AI Studio)", selected = st.voiceEngine == "gemini") { core.setSettings { it.copy(voiceEngine = "gemini") } }
            Pill("On-device (Android)", selected = st.voiceEngine == "device") { core.setSettings { it.copy(voiceEngine = "device") } }
        }
        T(
            if (st.voiceEngine == "gemini") "Tap the mic, speak, then tap ✓. Your recording is sent to Google's Gemini API and the transcript streams into the message box word by word. Needs a free AI Studio key."
            else "Uses the speech recogniser built into your phone. Words appear live while you talk; it stops by itself when you pause. No key needed.",
            12, p.muted,
        )
        Pill("Send automatically when done", selected = st.voiceAutoSend) { core.setSettings { it.copy(voiceAutoSend = !it.voiceAutoSend) } }
    }
    Section("Gemini (AI Studio)") {
        Label("API key" + if (saved != null) "  ·  saved (…${saved.takeLast(4)})" else "  ·  not saved")
        Field(keyIn, { keyIn = it }, "Paste AI Studio key", mono = true, password = true)
        Label("Model")
        Field(model, { model = it }, "gemini-2.5-flash", mono = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro").forEach { m -> Pill(m, mono = true, small = true, selected = model == m) { model = m } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Save", filled = true) {
                core.setSettings { it.copy(voiceModel = model.trim().ifBlank { "gemini-2.5-flash" }) }
                if (keyIn.isNotBlank()) { core.secrets.put(Secrets.GEMINI, keyIn.trim()); keyIn = ""; tick++ }
                core.say("Voice settings saved")
            }
            if (saved != null) Pill("Remove key") { core.secrets.remove(Secrets.GEMINI); tick++ }
            Pill(if (testing) "Testing…" else "Test key") {
                if (testing) return@Pill
                scope.launch {
                    testing = true
                    if (keyIn.isNotBlank()) { core.secrets.put(Secrets.GEMINI, keyIn.trim()); keyIn = ""; tick++ }
                    val k = core.secrets.get(Secrets.GEMINI)
                    core.say(if (k == null) "Paste a key first." else try { GeminiStt.check(core.http, k); "Key works." } catch (e: Throwable) { GeminiStt.errText(e) })
                    testing = false
                }
            }
        }
        T("Get a free key at aistudio.google.com/apikey. It is stored encrypted on this device and only sent to generativelanguage.googleapis.com. If your main provider is already Gemini, that key is used when this one is empty.", 12, p.muted)
    }
}
