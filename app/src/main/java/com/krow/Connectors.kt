package com.krow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A REST API the user connected. Calls are locked to this base URL's host; the token lives only in encrypted Secrets ("conn|<id>"). */
@Serializable
data class Connector(val id: String, val name: String, val baseUrl: String, val auth: String = "none", // none | bearer | header | query
    val authName: String = "", val about: String = "", val write: Boolean = false)

val CONNECTOR_PRESETS = listOf(
    Connector("", "Hacker News", "https://hacker-news.firebaseio.com/v0", about = "Tech news: /topstories.json, /item/<id>.json"),
    Connector("", "Wikipedia", "https://en.wikipedia.org/api/rest_v1", about = "Encyclopedia REST API: /page/summary/<title>"),
    Connector("", "GitHub (public)", "https://api.github.com", about = "Public GitHub REST API: /search/repositories?q=, /repos/<owner>/<repo>"),
    Connector("", "Custom API", "https://", about = ""),
)

object Connectors {
    fun key(c: Connector) = "conn|${c.id}"
    fun find(core: Core, name: String): Connector =
        core.settings.connectors.firstOrNull { it.name.equals(name.trim(), true) || it.id == name.trim() } ?: throw IllegalArgumentException("No connector named '$name'. Available: " + core.settings.connectors.joinToString { it.name })

    fun call(core: Core, c: Connector, method: String, path: String, query: JsonObject?, body: String?): ToolOut {
        val base = c.baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://")) { "Connector must use https://" }
        val p = path.trim().trimStart('/')
        require(!p.contains("://") && !p.contains("..") && !path.trim().startsWith("//")) { "Path must be relative to the connector's base URL" }
        val b = (base + "/" + p).toHttpUrl().newBuilder()
        require(b.build().host == base.toHttpUrl().host) { "Host mismatch" }
        WebPage.checkHost(b.build().host)
        query?.forEach { (k, v) -> b.addQueryParameter(k, (v as? JsonPrimitive)?.content ?: v.toString()) }
        val token = core.secrets.get(key(c))
        if (c.auth == "query" && token != null) b.addQueryParameter(c.authName.ifBlank { "api_key" }, token)
        val rb = Request.Builder().url(b.build()).header("User-Agent", UA_MOBILE).header("Accept", "application/json, text/plain;q=0.9, */*;q=0.5")
        if (token != null) when (c.auth) { "bearer" -> rb.header("Authorization", "Bearer $token"); "header" -> rb.header(c.authName.ifBlank { "X-API-Key" }, token) }
        val m = method.uppercase()
        if (m == "GET") rb.get() else rb.method(m, (body ?: "").toRequestBody("application/json".toMediaType()))
        val client = core.http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        client.newCall(rb.build()).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (r.code in 300..399) return ToolOut("HTTP ${r.code}: redirect refused (connectors never leave their host).", ok = false)
            val out = "HTTP ${r.code}\n" + if (txt.length > 20_000) txt.take(20_000) + "\n…[truncated]" else txt
            return ToolOut(out.replace(token ?: "\u0000", "[token]"), ok = r.isSuccessful)
        }
    }
}

class ConnectorGet : Tool {
    override val spec = ToolSpec("connector_get", "Read data (HTTP GET) from one of the user's connected APIs. Path is relative to that connector's base URL.",
        schema(listOf("connector", "path"), "connector" to prop("string", "Connector name"), "path" to prop("string", "Relative path, e.g. /user/repos"),
            "query" to buildJsonObject { put("type", "object"); put("description", "Query parameters as key/value pairs") }))
    override val kind = "link"
    override fun title(a: JsonObject) = a.str("connector") ?: "Connector"
    override fun cmd(a: JsonObject) = "GET " + (a.str("path") ?: "")
    override suspend fun run(c: ToolCtx, a: JsonObject) = Connectors.call(c.core, Connectors.find(c.core, a.req("connector")), "GET", a.req("path"), a["query"] as? JsonObject, null)
}

class ConnectorSend : Tool {
    override val spec = ToolSpec("connector_send", "Change data (POST/PUT/PATCH/DELETE) on a connected API that the user allowed writes for. The user is always asked first.",
        schema(listOf("connector", "method", "path"), "connector" to prop("string", "Connector name"), "method" to prop("string", "POST | PUT | PATCH | DELETE"),
            "path" to prop("string", "Relative path"), "body" to prop("string", "JSON body as a string")))
    override val kind = "link"
    override val mutating = true
    override val alwaysAsk = true
    override fun title(a: JsonObject) = a.str("connector") ?: "Connector"
    override fun cmd(a: JsonObject) = "${a.str("method")} ${a.str("path")}"
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val con = Connectors.find(c.core, a.req("connector"))
        if (!con.write) return ToolOut("Writes are not enabled for ${con.name}.", ok = false)
        val m = a.req("method").uppercase(); if (m !in listOf("POST", "PUT", "PATCH", "DELETE")) return ToolOut("Unsupported method", ok = false)
        return Connectors.call(c.core, con, m, a.req("path"), null, a.str("body"))
    }
}
