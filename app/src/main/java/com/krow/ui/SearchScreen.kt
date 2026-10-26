package com.krow.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlin.math.max

private val thumbCache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }

@Composable
fun RemoteImage(core: Core, url: String, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    val img by produceState<ImageBitmap?>(thumbCache.get(url)?.asImageBitmap(), url) {
        if (value == null && url.startsWith("https://")) value = withContext(Dispatchers.IO) {
            runCatching {
                core.http.newCall(Request.Builder().url(url).header("User-Agent", UA_MOBILE).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val bytes = r.body?.source()?.let { s -> val b = okio.Buffer(); while (b.size < 3_000_000 && s.read(b, 8192) != -1L) { }; b.readByteArray() } ?: return@use null
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var sample = 1
                    while (max(o.outWidth, o.outHeight) / (sample * 2) >= 480) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.also { thumbCache.put(url, it) }?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(p.sunk)) { img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
}

private fun host(u: String) = runCatching { java.net.URI(u).host.orEmpty().removePrefix("www.") }.getOrDefault("")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(core: Core, onBack: () -> Unit, onAsk: (String) -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val s by core.state.collectAsState()
    val st = s.settings
    var q by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf("general") }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var shownQuery by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<SearchPage?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val cats = categoriesFor(st.searchProvider, st)

    fun run(query: String, c: String, pg: Int) {
        val t = query.trim(); if (t.isEmpty()) return
        focus.clearFocus()
        if (looksLikeUrl(t)) { openUrl(ctx, t); return }
        job?.cancel()
        loading = true; err = null; shownQuery = t; page = pg
        job = scope.launch {
            try { result = core.search.search(t, c, pg) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { result = null; err = e.message ?: e.toString() }
            finally { loading = false }
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconBtn(Icons.Outlined.ArrowBack, "Back", onClick = onBack)
            Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(p.sunk).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Ic(Icons.Outlined.Search, 18)
                BasicTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, textStyle = TextStyle(color = p.ink, fontSize = 15.sp), cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run(q, cat, 1) }),
                    decorationBox = { inner -> Box { if (q.isEmpty()) T("Search the web or paste a link", 15, p.faint); inner() } })
                if (q.isNotEmpty()) Ic(Icons.Outlined.Close, 16, modifier = Modifier.clickable { q = "" })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SEARCH_PROVIDERS.forEach { (k, l) -> Pill(l, selected = st.searchProvider == k) {
                core.setSettings { it.copy(searchProvider = k) }
                if (cats.none { it.first == cat }) cat = "general"
                if (shownQuery.isNotBlank()) run(shownQuery, if (categoriesFor(k, core.settings).any { it.first == cat }) cat else "general", 1)
            } }
        }
        if (cats.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cats.forEach { (k, l) -> Pill(l, selected = cat == k) { cat = k; if (shownQuery.isNotBlank()) run(shownQuery, k, 1) } }
        }
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = p.accent, strokeWidth = 1.5.dp); T("Searching…", 14, p.muted)
            }
            err?.let { T(it, 13, p.bad) }
            val r = result
            if (r == null && !loading && err == null) {
                T("Search with ${SEARCH_PROVIDERS.firstOrNull { it.first == st.searchProvider }?.second ?: "Auto"}", 13, p.muted)
                T("Add your own SearXNG, 4get or LibreY instance, or a Google API key, in Settings → Web search. With no setup, Auto uses Google/DuckDuckGo.", 12, p.faint)
            }
            if (r != null) {
                T("${r.engine} · ${r.hits.size} results · page $page", 11, p.faint, family = Mono)
                r.note?.let { T(it, 12, p.muted) }
                if (shownQuery.isNotBlank()) Pill("Answer with Krow", filled = true) { onAsk("Search the web for \"$shownQuery\" and answer with sources. Open the best results to read them first.") }
                r.answers.take(2).forEach { a ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.accentSoft).padding(12.dp)) { T(a.take(500), 14, lh = 1.45f) }
                }
                if (cat == "images" && r.hits.any { it.thumb != null }) {
                    r.hits.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { h -> RemoteImage(core, h.thumb.orEmpty(), Modifier.weight(1f).aspectRatio(1f).clickable { openUrl(ctx, h.url) }) }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else r.hits.forEach { h ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            T(host(h.url) + (if (h.engine.isNotBlank() && h.engine != r.engine) "  ·  ${h.engine}" else "") + (h.date?.let { "  ·  ${it.take(10)}" } ?: ""), 11, p.faint, family = Mono, maxLines = 1)
                            T(h.title, 16, p.accent, FontWeight.Medium, modifier = Modifier.clickable { openUrl(ctx, h.url) })
                            if (h.snippet.isNotBlank()) T(h.snippet, 13, p.muted, lh = 1.4f, maxLines = 4)
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 2.dp)) {
                                T("Copy link", 12, p.faint, modifier = Modifier.clickable { clip.setText(AnnotatedString(h.url)); core.say("Link copied") })
                                T("Share", 12, p.faint, modifier = Modifier.clickable { shareText(ctx, h.url) })
                                T("Ask Krow", 12, p.faint, modifier = Modifier.clickable { onAsk("Open ${h.url} and summarize it. Then tell me anything important I should know.") })
                            }
                        }
                        h.thumb?.let { RemoteImage(core, it, Modifier.size(72.dp)) }
                    }
                }
                if (r.suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.suggestions.take(8).forEach { sg -> Pill(sg) { q = sg; run(sg, cat, 1) } }
                }
                if (r.hits.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (page > 1) Pill("‹ Previous") { run(shownQuery, cat, page - 1) }
                    Pill("Next ›") { run(shownQuery, cat, page + 1) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
