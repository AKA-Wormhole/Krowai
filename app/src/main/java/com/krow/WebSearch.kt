package com.krow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import java.net.InetAddress
import java.net.URLDecoder

const val UA_MOBILE = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 Krow/0.3"

class SearchHit(val title: String, val url: String, val snippet: String = "", val engine: String = "", val thumb: String? = null, val date: String? = null)
class SearchPage(val hits: List<SearchHit>, val engine: String, val answers: List<String> = emptyList(), val suggestions: List<String> = emptyList(), val note: String? = null)

val SEARCH_PROVIDERS = listOf("auto" to "Auto", "searxng" to "SearXNG", "4get" to "4get", "librey" to "LibreY", "public" to "Public SearXNG", "google" to "Google", "ddg" to "DuckDuckGo")

/** Categories each provider can really serve (the UI only offers these). */
fun categoriesFor(provider: String, settings: Settings): List<Pair<String, String>> {
    val general = "general" to "Web"; val images = "images" to "Images"; val news = "news" to "News"
    return when (provider) {
        "searxng" -> listOf(general, images, news, "videos" to "Videos", "science" to "Science", "it" to "IT")
        "4get" -> listOf(general, images, news)
        "librey" -> listOf(general, images, "videos" to "Videos")
        "google" -> if (settings.googleCx.isNotBlank()) listOf(general, images) else listOf(general)
        "public" -> listOf(general, images, news, "videos" to "Videos", "science" to "Science", "it" to "IT")
        "ddg" -> listOf(general)
        else -> listOf(general, images, news)
    }
}

/**
 * Meta-search client. Talks to the user's own SearXNG / 4get / LibreY instance, Google (a best-effort parse of google.com first, then the official Custom Search API
 * if a key + cx are saved), and DuckDuckGo's HTML endpoint
 * as the zero-setup fallback. "auto" tries them in that order and uses the first that returns results.
 */
class WebSearch(private val core: Core) {
    private val npt = HashMap<String, String>() // 4get next-page tokens

    private fun get(url: String, ua: String = UA_MOBILE, accept: String = "*/*"): Pair<Int, String> {
        val req = Request.Builder().url(url).header("User-Agent", ua).header("Accept", accept).header("Accept-Language", "en-US,en;q=0.9").build()
        core.http.newCall(req).execute().use { r -> return r.code to (r.body?.string().orEmpty()) }
    }

    private fun base(u: String): String {
        val t = u.trim().trimEnd('/')
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    private fun JsonElement?.s(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonElement?.o(): JsonObject? = this as? JsonObject
    private fun JsonElement?.a(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(q: String, category: String = "general", page: Int = 1, provider: String = core.settings.searchProvider): SearchPage = withContext(Dispatchers.IO) {
        val st = core.settings
        val query = q.trim()
        require(query.isNotEmpty()) { "Empty search query" }
        val order = if (provider == "auto") buildList {
            if (category == "general") add("google-web")   // google.com parsing goes first for web search
            if (st.googleCx.isNotBlank() && googleKey() != null) add("google")
            if (st.searxUrl.isNotBlank()) add("searxng")
            if (st.fourgetUrl.isNotBlank()) add("4get")
            if (st.libreyUrl.isNotBlank()) add("librey")
            add("public")                                   // free public SearXNG pool, no setup
            if (category == "general" || isEmpty()) add("ddg")
        } else listOf(provider)
        val errors = ArrayList<String>()
        for (p in order) {
            currentCoroutineContext().ensureActive()
            try {
                val r = when (p) {
                    "searxng" -> searx(query, category, page)
                    "4get" -> fourget(query, category, page)
                    "librey" -> librey(query, category, page)
                    "public" -> PublicSearx.search(core, query, category, page)
                    "google" -> if (st.googleCx.isNotBlank() && googleKey() != null) googleApi(query, category, page) else googleWeb(query, page)
                    "google-web" -> googleWeb(query, page)
                    else -> ddg(query, page)
                }
                if (r.hits.isNotEmpty() || r.answers.isNotEmpty()) return@withContext r
                errors += "${p}: no results"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { errors += "$p: ${e.message ?: e.javaClass.simpleName}" }
        }
        throw IllegalStateException("Search failed. " + errors.joinToString("; ").take(600))
    }

    private fun googleKey() = core.secrets.get(Secrets.GOOGLE_KEY)

    // ------------------------------------------------------------------ SearXNG
    private fun searx(q: String, cat: String, page: Int): SearchPage {
        val url = base(core.settings.searxUrl).toHttpUrl().newBuilder().addPathSegment("search")
            .addQueryParameter("q", q).addQueryParameter("format", "json").addQueryParameter("categories", cat).addQueryParameter("pageno", page.toString()).build()
        val (code, body) = get(url.toString(), accept = "application/json")
        if (code == 403) error("HTTP 403 — this SearXNG instance has JSON output disabled. Add 'json' under search.formats in its settings.yml, or use your own instance.")
        if (code == 429) error("HTTP 429 — rate limited by the instance")
        if (code !in 200..299) error("HTTP $code")
        val root = json.parseToJsonElement(body).o() ?: error("unexpected response")
        val hits = root["results"].a().mapNotNull { r ->
            val o = r.o() ?: return@mapNotNull null
            val u = o["url"].s(); if (u.isBlank()) return@mapNotNull null
            SearchHit(o["title"].s().ifBlank { u }, u, o["content"].s(), o["engine"].s(), o["thumbnail_src"].s().ifBlank { o["img_src"].s() }.ifBlank { o["thumbnail"].s() }.ifBlank { null }, o["publishedDate"].s().ifBlank { null })
        }
        val answers = root["answers"].a().map { it.s().ifBlank { it.o()?.get("answer").s() } }.filter { it.isNotBlank() } +
            root["infoboxes"].a().mapNotNull { it.o()?.get("content").s().ifBlank { null } }
        return SearchPage(hits, "SearXNG", answers, root["suggestions"].a().map { it.s() }.filter { it.isNotBlank() })
    }

    // ------------------------------------------------------------------ 4get
    private fun fourget(q: String, cat: String, page: Int): SearchPage {
        val path = when (cat) { "images" -> "images"; "news" -> "news"; else -> "web" }
        val key = "$q|$path"
        val b = base(core.settings.fourgetUrl).toHttpUrl().newBuilder().addPathSegments("api/v1/$path")
        if (page > 1) {
            val t = npt["$key|${page - 1}"] ?: error("4get paginates with tokens — open page ${page - 1} first")
            b.addQueryParameter("npt", t)
        } else b.addQueryParameter("s", q)
        val (code, body) = get(b.build().toString(), accept = "application/json")
        if (code !in 200..299) error("HTTP $code")
        val root = json.parseToJsonElement(body).o() ?: error("unexpected response")
        val status = root["status"].s()
        if (status.isNotEmpty() && status != "ok") error("4get said: $status")
        root["npt"].s().takeIf { it.isNotBlank() && it != "null" }?.let { npt["$key|$page"] = it }
        val hits = when (path) {
            "images" -> root["image"].a().mapNotNull { r ->
                val o = r.o() ?: return@mapNotNull null
                val src = o["source"].a().mapNotNull { it.o()?.get("url").s().ifBlank { null } }
                val u = o["url"].s().ifBlank { src.firstOrNull().orEmpty() }; if (u.isBlank()) return@mapNotNull null
                SearchHit(o["title"].s().ifBlank { u }, u, "", "4get", src.lastOrNull())
            }
            "news" -> root["news"].a().mapNotNull { r -> val o = r.o() ?: return@mapNotNull null; val u = o["url"].s(); if (u.isBlank()) null else SearchHit(o["title"].s().ifBlank { u }, u, o["description"].s(), "4get", o["thumb"].o()?.get("url").s().ifBlank { null }, o["date"].s().ifBlank { null }) }
            else -> root["web"].a().mapNotNull { r -> val o = r.o() ?: return@mapNotNull null; val u = o["url"].s(); if (u.isBlank()) null else SearchHit(o["title"].s().ifBlank { u }, u, o["description"].s(), "4get", o["thumb"].o()?.get("url").s().ifBlank { null }, o["date"].s().ifBlank { null }) }
        }
        val answers = root["answer"].a().mapNotNull { r ->
            val o = r.o() ?: return@mapNotNull null
            val txt = o["description"].a().mapNotNull { n -> n.o()?.let { if (it["type"].s() in listOf("text", "quote", "title", "inline_code")) it["value"].s() else null } }.joinToString(" ").trim()
            (o["title"].s() + (if (txt.isNotBlank()) ": $txt" else "")).trim(':', ' ').ifBlank { null }
        }
        return SearchPage(hits, "4get", answers, root["related"].a().map { it.s() }.filter { it.isNotBlank() }, root["spelling"].o()?.get("correction").s().takeIf { it.isNotBlank() }?.let { "Did you mean: $it" })
    }

    // ------------------------------------------------------------------ LibreY
    private fun librey(q: String, cat: String, page: Int): SearchPage {
        val t = when (cat) { "images" -> "1"; "videos" -> "2"; else -> "0" }
        val url = base(core.settings.libreyUrl).toHttpUrl().newBuilder().addPathSegment("api.php")
            .addQueryParameter("q", q).addQueryParameter("p", (page - 1).toString()).addQueryParameter("t", t).build()
        val (code, body) = get(url.toString(), accept = "application/json")
        if (code == 403 || body.contains("API is disabled", true)) error("the API is disabled on this LibreY instance (or blocked)")
        if (code !in 200..299) error("HTTP $code")
        val root = json.parseToJsonElement(body)
        val arr = if (root is JsonArray) root else root.o()?.get("results").a()
        val hits = arr.mapNotNull { r ->
            val o = r.o() ?: return@mapNotNull null
            val u = o["url"].s(); if (u.isBlank() || o["special_response"] != null) return@mapNotNull null
            SearchHit(o["title"].s().ifBlank { o["alt"].s() }.ifBlank { u }, u, o["description"].s(), "LibreY", if (t == "1") u else null)
        }
        return SearchPage(hits, "LibreY")
    }

    // ------------------------------------------------------------------ Google (official API)
    private fun googleApi(q: String, cat: String, page: Int): SearchPage {
        val b = "https://www.googleapis.com/customsearch/v1".toHttpUrl().newBuilder()
            .addQueryParameter("key", googleKey()).addQueryParameter("cx", core.settings.googleCx.trim()).addQueryParameter("q", q)
            .addQueryParameter("num", "10").addQueryParameter("start", ((page - 1) * 10 + 1).toString())
        if (cat == "images") b.addQueryParameter("searchType", "image")
        val (code, body) = get(b.build().toString(), accept = "application/json")
        val root = json.parseToJsonElement(body).o() ?: error("unexpected response")
        root["error"].o()?.let { error("Google API: ${it["message"].s().ifBlank { "HTTP $code" }}") }
        val hits = root["items"].a().mapNotNull { r ->
            val o = r.o() ?: return@mapNotNull null
            val link = o["link"].s(); if (link.isBlank()) return@mapNotNull null
            if (cat == "images") SearchHit(o["title"].s().ifBlank { link }, o["image"].o()?.get("contextLink").s().ifBlank { link }, o["snippet"].s(), "Google", o["image"].o()?.get("thumbnailLink").s().ifBlank { link })
            else SearchHit(o["title"].s().ifBlank { link }, link, o["snippet"].s().replace('\n', ' '), "Google", o["pagemap"].o()?.get("cse_thumbnail").a().firstOrNull()?.o()?.get("src").s().ifBlank { null })
        }
        return SearchPage(hits, "Google")
    }

    // ------------------------------------------------------------------ Google.com (best-effort parse)
    private fun googleWeb(q: String, page: Int): SearchPage {
        val url = "https://www.google.com/search".toHttpUrl().newBuilder().addQueryParameter("q", q).addQueryParameter("hl", "en")
            .addQueryParameter("num", "10").addQueryParameter("start", ((page - 1) * 10).toString()).addQueryParameter("gbv", "1").build()
        val (code, body) = get(url.toString(), ua = "Lynx/2.9.0 libwww-FM/2.14 SSL-MM/1.4.1 OpenSSL/3.0", accept = "text/html")
        if (code == 429 || body.contains("unusual traffic") || body.contains("/sorry/")) error("Google blocked the request (captcha). Add a Google API key + cx in Settings, or use another engine")
        if (code !in 200..299) error("HTTP $code")
        val doc = Jsoup.parse(body, "https://www.google.com/")
        if (doc.location().contains("consent.google")) error("Google asked for cookie consent")
        val seen = HashSet<String>(); val hits = ArrayList<SearchHit>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            val target = when {
                href.startsWith("/url?") -> Regex("[?&](?:q|url)=([^&]+)").find(href)?.groupValues?.get(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                href.startsWith("http") && !href.contains("google.") -> href
                else -> null
            } ?: continue
            if (!target.startsWith("http") || target.contains("google.com/") || !seen.add(target)) continue
            val h = a.selectFirst("h3, div[role=heading]")
            val title = (h?.text() ?: a.text()).trim()
            if (title.length < 3) continue
            var box: Element? = a.parent(); var n = 0
            var snippet = ""
            while (box != null && n < 4 && snippet.isEmpty()) {
                val t = box.text().replace(title, "").replace(target, "").trim()
                if (t.length > 40) snippet = t.take(280)
                box = box.parent(); n++
            }
            hits += SearchHit(title, target, snippet, "Google")
            if (hits.size >= 10) break
        }
        return SearchPage(hits, "Google")
    }

    // ------------------------------------------------------------------ DuckDuckGo (HTML endpoint, no setup)
    private fun ddg(q: String, page: Int): SearchPage {
        val form = FormBody.Builder().add("q", q).apply { if (page > 1) add("s", ((page - 1) * 30).toString()) }.build()
        val req = Request.Builder().url("https://html.duckduckgo.com/html/").post(form).header("User-Agent", UA_MOBILE).header("Accept-Language", "en-US,en;q=0.9").build()
        val body = core.http.newCall(req).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body?.string().orEmpty() }
        val doc = Jsoup.parse(body, "https://duckduckgo.com/")
        if (doc.selectFirst("form#challenge-form, .anomaly-modal__modal") != null) error("DuckDuckGo asked for a captcha")
        val hits = doc.select("div.result").mapNotNull { r ->
            val a = r.selectFirst("a.result__a") ?: return@mapNotNull null
            var href = a.attr("href")
            Regex("[?&]uddg=([^&]+)").find(href)?.groupValues?.get(1)?.let { href = runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(href) }
            if (href.startsWith("//")) href = "https:$href"
            if (!href.startsWith("http") || href.contains("duckduckgo.com/y.js")) return@mapNotNull null
            SearchHit(a.text().trim().ifBlank { href }, href, r.selectFirst(".result__snippet")?.text().orEmpty(), "DuckDuckGo")
        }
        return SearchPage(hits, "DuckDuckGo", note = "DuckDuckGo fallback: web results only")
    }
}

// ====================================================================================================== page fetch
object WebPage {
    private const val MAX_BYTES = 2_000_000L

    /** Refuses loopback / private / link-local targets so a model can't be tricked into probing the phone's network. */
    internal fun checkHost(host: String) {
        val all = InetAddress.getAllByName(host)
        for (a in all) {
            val b = a.address
            val ula = b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress || ula)
                throw IllegalArgumentException("Refusing to open a local/private network address ($host).")
        }
    }

    class Page(val url: String, val title: String, val text: String, val links: List<Pair<String, String>>, val type: String)

    fun fetch(core: Core, rawUrl: String, wantLinks: Boolean = false): Page {
        var url = rawUrl.trim()
        if (!url.contains("://")) url = "https://$url"
        if (url.startsWith("http://")) url = "https://" + url.removePrefix("http://") // cleartext is blocked on Android
        val client = core.http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var hops = 0
        while (true) {
            val u = url.toHttpUrl()
            checkHost(u.host)
            val req = Request.Builder().url(u).header("User-Agent", UA_MOBILE).header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.5").header("Accept-Language", "en-US,en;q=0.9").build()
            client.newCall(req).execute().use { r ->
                if (r.code in 300..399) {
                    val loc = r.header("Location") ?: throw IllegalStateException("Redirect without Location")
                    if (++hops > 6) throw IllegalStateException("Too many redirects")
                    url = u.resolve(loc)?.toString() ?: throw IllegalStateException("Bad redirect")
                    if (url.startsWith("http://")) url = "https://" + url.removePrefix("http://")
                    return@use
                }
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} from ${u.host}")
                val ct = (r.header("Content-Type") ?: "").lowercase()
                val src = r.body ?: throw IllegalStateException("Empty response")
                val bytes = src.source().let { s -> val buf = okio.Buffer(); while (buf.size < MAX_BYTES && s.read(buf, 8192) != -1L) { }; buf.readByteArray() }
                val charset = Regex("charset=([\\w-]+)").find(ct)?.groupValues?.get(1)?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
                val text = String(bytes, charset)
                return when {
                    "html" in ct || ct.isEmpty() && text.trimStart().startsWith("<") -> parseHtml(u.toString(), text, wantLinks)
                    ct.startsWith("text/") || "json" in ct || "xml" in ct -> Page(u.toString(), "", text, emptyList(), ct)
                    "pdf" in ct -> throw IllegalStateException("This is a PDF; Krow cannot read PDFs from the web yet. Give the link to the user instead.")
                    else -> throw IllegalStateException("Unsupported content type: $ct")
                }
            }
        }
    }

    private fun parseHtml(url: String, html: String, wantLinks: Boolean): Page {
        val doc = Jsoup.parse(html, url)
        val title = doc.title().trim()
        val links = if (wantLinks) doc.select("a[href]").mapNotNull { a -> val h = a.absUrl("href"); val t = a.text().trim(); if (h.startsWith("http") && t.isNotEmpty()) t.take(80) to h else null }.distinctBy { it.second }.take(60) else emptyList()
        doc.select("script, style, noscript, svg, iframe, form, nav, footer, header, aside, [role=navigation], [aria-hidden=true]").remove()
        val root: Element = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body() ?: doc
        val sb = StringBuilder()
        root.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is TextNode) { val t = node.text(); if (t.isNotBlank()) { if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' '); sb.append(t.trim()) } }
                else if (node is Element) when (node.normalName()) {
                    "h1" -> sb.append("\n\n# "); "h2" -> sb.append("\n\n## "); "h3", "h4", "h5", "h6" -> sb.append("\n\n### ")
                    "li" -> sb.append("\n- "); "p", "div", "section", "tr", "br", "pre", "blockquote" -> sb.append('\n')
                }
            }
            override fun tail(node: Node, depth: Int) { if (node is Element && node.normalName() in listOf("p", "h1", "h2", "h3", "h4", "table")) sb.append('\n') }
        })
        val text = sb.toString().replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        return Page(url, title, text, links, "text/html")
    }
}

// ====================================================================================================== AI tools
class WebSearchTool : Tool {
    override val spec = ToolSpec(
        "web_search",
        "Search the live web (SearXNG / 4get / LibreY / Google / DuckDuckGo, whichever the user configured) for CURRENT information: news, prices, versions, docs, sports, anything after your training data or that you are unsure about. " +
            "Returns titles, URLs and snippets. Follow up with fetch_url on the best results to read them before answering, and cite sources as Markdown links.",
        schema(listOf("query"),
            "query" to prop("string", "Search query, short and specific"),
            "category" to prop("string", "general (default) | images | news | videos | science | it — only engines that support it are used"),
            "page" to prop("integer", "Result page, default 1"),
            "count" to prop("integer", "How many results to return, 1-15, default 8")),
    )
    override val kind = "search"
    override fun title(a: JsonObject) = "Web search"
    override fun cmd(a: JsonObject) = a.str("query") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val q = a.req("query")
        val page = (a.int("page") ?: 1).coerceAtLeast(1)
        val n = (a.int("count") ?: 8).coerceIn(1, 15)
        val r = WebSearch(c.core).search(q, a.str("category") ?: "general", page)
        val sb = StringBuilder("Results from ${r.engine} for \"$q\" (page $page):\n")
        r.note?.let { sb.append("($it)\n") }
        r.answers.take(3).forEach { sb.append("\nAnswer box: ").append(it.take(600)).append('\n') }
        r.hits.take(n).forEachIndexed { i, h ->
            sb.append('\n').append(i + 1).append(". ").append(h.title).append('\n').append("   ").append(h.url).append('\n')
            if (h.date != null) sb.append("   ").append(h.date).append('\n')
            if (h.snippet.isNotBlank()) sb.append("   ").append(h.snippet.take(300)).append('\n')
        }
        if (r.suggestions.isNotEmpty()) sb.append("\nRelated: ").append(r.suggestions.take(6).joinToString("; "))
        return ToolOut(sb.toString())
    }
}

class FetchUrlTool : Tool {
    override val spec = ToolSpec(
        "fetch_url",
        "Open a web page by direct URL and read its text (HTML is reduced to readable text; JSON/plain text returned as is). Use it after web_search to read the actual page, or when the user gives you a link. Cannot read PDFs, log in, or run JavaScript.",
        schema(listOf("url"),
            "url" to prop("string", "The page address"),
            "max_chars" to prop("integer", "Maximum characters to return, 1000-40000, default 12000"),
            "links" to prop("boolean", "Also list the links found on the page")),
    )
    override val kind = "search"
    override fun title(a: JsonObject) = "Open page"
    override fun cmd(a: JsonObject) = a.str("url") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val max = (a.int("max_chars") ?: 12_000).coerceIn(1000, 40_000)
        val p = WebPage.fetch(c.core, a.req("url"), a.bool("links") ?: false)
        val sb = StringBuilder()
        if (p.title.isNotBlank()) sb.append("Title: ").append(p.title).append('\n')
        sb.append("URL: ").append(p.url).append("\n\n")
        sb.append(if (p.text.length > max) p.text.take(max) + "\n…[truncated, ${p.text.length - max} more characters]" else p.text)
        if (p.links.isNotEmpty()) { sb.append("\n\nLinks:\n"); p.links.forEach { sb.append("- ").append(it.first).append(": ").append(it.second).append('\n') } }
        return ToolOut(sb.toString())
    }
}
