package com.krow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

private val pjson = Json { ignoreUnknownKeys = true; isLenient = true }
private fun JsonElement?.s(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonElement?.o(): JsonObject? = this as? JsonObject
private fun JsonElement?.a(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

private fun getText(core: Core, url: String, json: Boolean = true): Pair<Int, String> {
    val req = Request.Builder().url(url).header("User-Agent", UA_MOBILE).header("Accept", if (json) "application/json" else "*/*").build()
    core.http.newCall(req).execute().use { r -> return r.code to (r.body?.string().orEmpty()) }
}
private fun getJson(core: Core, url: String): JsonElement {
    val (code, body) = getText(core, url)
    if (code !in 200..299) error("HTTP $code")
    return pjson.parseToJsonElement(body)
}

/**
 * Public SearXNG pool: zero setup. Pulls the live instance list from searx.space (cached 6 h, falls back to a built-in seed),
 * ranks by speed/success rate, then races the best few in parallel; the first that returns results wins. Instances that
 * fail (JSON disabled, rate limit, timeout) are benched for 30 min.
 */
object PublicSearx {
    private val seed = listOf("https://searx.be", "https://search.sapti.me", "https://priv.au", "https://searx.tiekoetter.com", "https://search.inetol.net", "https://paulgo.io", "https://opnxng.com", "https://searx.work")
    @Volatile private var pool: List<String> = emptyList()
    @Volatile private var loadedAt = 0L
    private val benched = ConcurrentHashMap<String, Long>()

    private fun instances(core: Core): List<String> {
        val now = System.currentTimeMillis()
        if (pool.isEmpty() || now - loadedAt > 6 * 3_600_000L) {
            pool = runCatching {
                val inst = getJson(core, "https://searx.space/data/instances.json").o()?.get("instances").o() ?: error("no instances")
                inst.entries.mapNotNull { (u, v) ->
                    val o = v.o() ?: return@mapNotNull null
                    if (".onion" in u || ".i2p" in u) return@mapNotNull null
                    if (o["http"].o()?.get("status_code").s() != "200") return@mapNotNull null
                    val t = o["timing"].o()?.get("search").o()
                    val ok = t?.get("success_percentage").s().toDoubleOrNull() ?: return@mapNotNull null
                    val med = t?.get("all").o()?.get("median").s().toDoubleOrNull() ?: return@mapNotNull null
                    if (ok < 85.0) null else Triple(u.trimEnd('/'), ok, med)
                }.sortedBy { it.third }.map { it.first }.take(40)
            }.getOrDefault(emptyList()).ifEmpty { seed }
            loadedAt = now
        }
        return pool.filter { (benched[it] ?: 0L) < now }.ifEmpty { benched.clear(); pool }
    }

    private fun one(core: Core, base: String, q: String, cat: String, page: Int): SearchPage {
        val url = base.toHttpUrl().newBuilder().addPathSegment("search").addQueryParameter("q", q).addQueryParameter("format", "json")
            .addQueryParameter("categories", cat).addQueryParameter("pageno", page.toString()).build()
        val (code, body) = getText(core, url.toString())
        if (code !in 200..299) error("HTTP $code")
        val root = pjson.parseToJsonElement(body).o() ?: error("bad response")
        val hits = root["results"].a().mapNotNull { r ->
            val o = r.o() ?: return@mapNotNull null
            val u = o["url"].s(); if (u.isBlank()) return@mapNotNull null
            SearchHit(o["title"].s().ifBlank { u }, u, o["content"].s(), o["engine"].s(),
                o["thumbnail_src"].s().ifBlank { o["img_src"].s() }.ifBlank { o["thumbnail"].s() }.ifBlank { null }, o["publishedDate"].s().ifBlank { null })
        }
        val answers = root["answers"].a().map { it.s().ifBlank { it.o()?.get("answer").s() } }.filter { it.isNotBlank() } +
            root["infoboxes"].a().mapNotNull { it.o()?.get("content").s().ifBlank { null } }
        return SearchPage(hits, base.removePrefix("https://"), answers, root["suggestions"].a().map { it.s() }.filter { it.isNotBlank() })
    }

    suspend fun search(core: Core, q: String, cat: String, page: Int): SearchPage {
        val cands = instances(core).shuffled().take(4)
        val ch = Channel<SearchPage?>(cands.size)
        return coroutineScope {
            val jobs = cands.map { u -> launch {
                val r = try { one(core, u, q, cat, page).takeIf { it.hits.isNotEmpty() } } catch (e: CancellationException) { throw e } catch (e: Throwable) { null }
                if (r == null) benched[u] = System.currentTimeMillis() + 30 * 60_000L
                ch.send(r)
            } }
            var win: SearchPage? = null
            repeat(cands.size) { if (win == null) win = ch.receive() }
            jobs.forEach { it.cancel() }
            win ?: error("no public SearXNG instance returned results (most disable JSON)")
        }
    }
}

// ====================================================================================================== keyless public-API tools
abstract class PublicTool(name: String, desc: String, schema: JsonObject) : Tool {
    override val spec = ToolSpec(name, desc, schema)
    override val kind = "search"
    override fun cmd(a: JsonObject) = a.str("query") ?: a.str("city") ?: a.str("word") ?: a.str("from") ?: ""
}

class WikipediaTool : PublicTool("wikipedia", "Look up a topic on Wikipedia (free, reliable) and get the intro summary with the article link. Prefer it for people, places, concepts, history.",
    schema(listOf("query"), "query" to prop("string", "Topic or title"), "lang" to prop("string", "Language code, default en"))) {
    override fun title(a: JsonObject) = "Wikipedia"
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val lang = (a.str("lang") ?: "en").filter { it.isLetter() }.take(3).ifEmpty { "en" }
        val q = a.req("query")
        val s = "https://$lang.wikipedia.org/w/api.php".toHttpUrl().newBuilder().addQueryParameter("action", "opensearch").addQueryParameter("search", q)
            .addQueryParameter("limit", "3").addQueryParameter("format", "json").build().toString()
        val titles = getJson(c.core, s).a().getOrNull(1).a().map { it.s() }
        if (titles.isEmpty()) return ToolOut("No Wikipedia article found for \"$q\".", ok = false)
        val u = "https://$lang.wikipedia.org/api/rest_v1/page/summary/".toHttpUrl().newBuilder().addPathSegment(titles[0]).build()
        val o = getJson(c.core, u.toString()).o() ?: return ToolOut("Bad response", ok = false)
        val link = o["content_urls"].o()?.get("desktop").o()?.get("page").s()
        return ToolOut("${o["title"].s()} — ${o["description"].s()}\n\n${o["extract"].s()}\n\nSource: $link" +
            if (titles.size > 1) "\nOther matches: ${titles.drop(1).joinToString("; ")}" else "")
    }
}

class WeatherTool : PublicTool("weather", "Current weather and 3-day forecast for any city (Open-Meteo, no key).",
    schema(listOf("city"), "city" to prop("string", "City name, e.g. Lagos"), "units" to prop("string", "metric (default) or imperial"))) {
    override fun title(a: JsonObject) = "Weather"
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val g = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder().addQueryParameter("name", a.req("city")).addQueryParameter("count", "1").build()
        val p = getJson(c.core, g.toString()).o()?.get("results").a().firstOrNull().o() ?: return ToolOut("City not found.", ok = false)
        val imp = a.str("units") == "imperial"
        val f = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder().addQueryParameter("latitude", p["latitude"].s()).addQueryParameter("longitude", p["longitude"].s())
            .addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code")
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,precipitation_probability_max").addQueryParameter("forecast_days", "3").addQueryParameter("timezone", "auto")
            .apply { if (imp) { addQueryParameter("temperature_unit", "fahrenheit"); addQueryParameter("wind_speed_unit", "mph") } }.build()
        val r = getJson(c.core, f.toString()).o() ?: return ToolOut("Bad response", ok = false)
        val cu = r["current"].o(); val d = r["daily"].o(); val t = if (imp) "°F" else "°C"
        val sb = StringBuilder("${p["name"].s()}, ${p["country"].s()}: ${cu?.get("temperature_2m").s()}$t (feels ${cu?.get("apparent_temperature").s()}$t), humidity ${cu?.get("relative_humidity_2m").s()}%, wind ${cu?.get("wind_speed_10m").s()}, WMO code ${cu?.get("weather_code").s()}\n")
        d?.get("time").a().forEachIndexed { i, day -> sb.append("$day: ${d?.get("temperature_2m_min").a().getOrNull(i).s()}–${d?.get("temperature_2m_max").a().getOrNull(i).s()}$t, rain ${d?.get("precipitation_probability_max").a().getOrNull(i).s()}%\n") }
        return ToolOut(sb.toString())
    }
}

class CurrencyTool : PublicTool("currency", "Convert money using ECB reference rates (Frankfurter, no key).",
    schema(listOf("from", "to"), "from" to prop("string", "ISO code, e.g. USD"), "to" to prop("string", "ISO code, e.g. NGN"), "amount" to prop("number", "Amount, default 1"))) {
    override fun title(a: JsonObject) = "Currency"
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val amt = (a["amount"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
        val u = "https://api.frankfurter.app/latest".toHttpUrl().newBuilder().addQueryParameter("amount", amt.toString()).addQueryParameter("from", a.req("from").uppercase()).addQueryParameter("to", a.req("to").uppercase()).build()
        val o = getJson(c.core, u.toString()).o() ?: return ToolOut("Bad response", ok = false)
        val rate = o["rates"].o()?.entries?.firstOrNull() ?: return ToolOut("Unsupported currency pair (ECB list only).", ok = false)
        return ToolOut("$amt ${o["base"].s()} = ${rate.value.s()} ${rate.key} (ECB rate, ${o["date"].s()})")
    }
}

class DefineTool : PublicTool("define_word", "English dictionary definitions, phonetics and examples (Free Dictionary API).",
    schema(listOf("word"), "word" to prop("string", "A single English word"))) {
    override fun title(a: JsonObject) = "Dictionary"
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val u = "https://api.dictionaryapi.dev/api/v2/entries/en/".toHttpUrl().newBuilder().addPathSegment(a.req("word").trim()).build()
        val (code, body) = getText(c.core, u.toString())
        if (code == 404) return ToolOut("No definition found.", ok = false)
        val e = pjson.parseToJsonElement(body).a().firstOrNull().o() ?: return ToolOut("Bad response", ok = false)
        val sb = StringBuilder("${e["word"].s()} ${e["phonetic"].s()}\n")
        e["meanings"].a().take(3).forEach { m -> val mo = m.o(); sb.append("\n(${mo?.get("partOfSpeech").s()})\n")
            mo?.get("definitions").a().take(2).forEach { d -> sb.append("- ${d.o()?.get("definition").s()}").also { d.o()?.get("example").s().takeIf { x -> x.isNotBlank() }?.let { x -> sb.append(" e.g. \"$x\"") } }.append('\n') } }
        return ToolOut(sb.toString())
    }
}
