package com.krow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiError(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(400)}")
class ToolSpec(val name: String, val description: String, val schema: JsonObject)
class TurnResult(val text: String, val calls: List<ToolCall>, val inTok: Int, val outTok: Int)

interface Llm {
    suspend fun stream(system: String, msgs: List<Msg>, tools: List<ToolSpec>, onText: (String) -> Unit): TurnResult
}

private val JSON_MT = "application/json".toMediaType()
private val lenient = Json { ignoreUnknownKeys = true }

/** Reads a Server-Sent-Events response and calls onData for every `data:` line. Cancelling the coroutine cancels the HTTP call. */
suspend fun sse(http: OkHttpClient, req: Request, onData: (String) -> Unit) =
    suspendCancellableCoroutine<Unit> { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        if (!r.isSuccessful) throw ApiError(r.code, r.body?.string().orEmpty())
                        val src = r.body!!.source()
                        while (true) {
                            val line = src.readUtf8Line() ?: break
                            if (line.startsWith("data:")) onData(line.removePrefix("data:").trim())
                        }
                    }
                    if (cont.isActive) cont.resume(Unit)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        })
    }

private fun parseObj(s: String): JsonObject? = runCatching { lenient.parseToJsonElement(s).jsonObject }.getOrNull()
private fun JsonElement?.prim(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Any OpenAI-compatible /chat/completions endpoint (OpenAI, OpenRouter, Groq, Together, DeepSeek, Gemini-compat, Ollama...). */
class OpenAiLlm(private val http: OkHttpClient, base: String, private val key: String, private val model: String) : Llm {
    private val url = base.trim().trimEnd('/') + "/chat/completions"

    override suspend fun stream(system: String, msgs: List<Msg>, tools: List<ToolSpec>, onText: (String) -> Unit): TurnResult {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                for (m in msgs) when (m.role) {
                    "user" -> addJsonObject { put("role", "user"); put("content", m.text) }
                    "assistant" -> addJsonObject {
                        put("role", "assistant")
                        if (m.text.isNotEmpty()) put("content", m.text) else put("content", JsonNull)
                        if (m.calls.isNotEmpty()) putJsonArray("tool_calls") {
                            m.calls.forEach { c ->
                                addJsonObject {
                                    put("id", c.id); put("type", "function")
                                    putJsonObject("function") { put("name", c.name); put("arguments", c.args) }
                                }
                            }
                        }
                    }
                    "tool" -> addJsonObject {
                        put("role", "tool"); put("tool_call_id", m.callId)
                        put("content", m.text.ifEmpty { "(no output)" })
                    }
                }
            }
            if (tools.isNotEmpty()) putJsonArray("tools") {
                tools.forEach { t ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") { put("name", t.name); put("description", t.description); put("parameters", t.schema) }
                    }
                }
            }
        }
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON_MT)).build()

        val text = StringBuilder()
        val cid = HashMap<Int, String>(); val cname = HashMap<Int, String>(); val cargs = HashMap<Int, StringBuilder>()
        var inTok = 0; var outTok = 0
        sse(http, req) { data ->
            if (data == "[DONE]") return@sse
            val o = parseObj(data) ?: return@sse
            (o["usage"] as? JsonObject)?.let { u ->
                inTok = (u["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: inTok
                outTok = (u["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: outTok
            }
            val d = (o["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("delta") as? JsonObject ?: return@sse
            d["content"].prim()?.let { if (it.isNotEmpty()) { text.append(it); onText(it) } }
            (d["tool_calls"] as? JsonArray)?.forEach { tc ->
                val t = tc.jsonObject
                val i = (t["index"] as? JsonPrimitive)?.intOrNull ?: 0
                t["id"].prim()?.let { if (it.isNotEmpty() && i !in cid) cid[i] = it }
                (t["function"] as? JsonObject)?.let { f ->
                    f["name"].prim()?.let { if (it.isNotEmpty() && i !in cname) cname[i] = it }
                    f["arguments"].prim()?.let { cargs.getOrPut(i) { StringBuilder() }.append(it) }
                }
            }
        }
        val calls = (cid.keys + cname.keys + cargs.keys).toSortedSet().mapNotNull { i ->
            val n = cname[i] ?: return@mapNotNull null
            ToolCall(cid[i] ?: "call_${uid()}", n, cargs[i]?.toString()?.ifBlank { "{}" } ?: "{}")
        }
        return TurnResult(text.toString(), calls, inTok, outTok)
    }
}

/** Native Anthropic Messages API. */
class AnthropicLlm(private val http: OkHttpClient, base: String, private val key: String, private val model: String) : Llm {
    private val url = base.trim().trimEnd('/').removeSuffix("/v1") + "/v1/messages"

    override suspend fun stream(system: String, msgs: List<Msg>, tools: List<ToolSpec>, onText: (String) -> Unit): TurnResult {
        val body = buildJsonObject {
            put("model", model); put("max_tokens", 8192); put("stream", true); put("system", system)
            putJsonArray("messages") {
                var i = 0
                while (i < msgs.size) {
                    val m = msgs[i]
                    when (m.role) {
                        "user" -> { addJsonObject { put("role", "user"); put("content", m.text) }; i++ }
                        "assistant" -> {
                            addJsonObject {
                                put("role", "assistant")
                                putJsonArray("content") {
                                    if (m.text.isNotEmpty()) addJsonObject { put("type", "text"); put("text", m.text) }
                                    m.calls.forEach { c ->
                                        addJsonObject {
                                            put("type", "tool_use"); put("id", c.id); put("name", c.name)
                                            put("input", runCatching { lenient.parseToJsonElement(c.args.ifBlank { "{}" }) }.getOrElse { JsonObject(emptyMap()) })
                                        }
                                    }
                                }
                            }
                            i++
                        }
                        else -> addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                while (i < msgs.size && msgs[i].role == "tool") {
                                    val t = msgs[i]
                                    addJsonObject {
                                        put("type", "tool_result"); put("tool_use_id", t.callId)
                                        put("content", t.text.ifEmpty { "(no output)" })
                                        if (t.status != "done") put("is_error", true)
                                    }
                                    i++
                                }
                            }
                        }
                    }
                }
            }
            if (tools.isNotEmpty()) putJsonArray("tools") {
                tools.forEach { t ->
                    addJsonObject { put("name", t.name); put("description", t.description); put("input_schema", t.schema) }
                }
            }
        }
        val req = Request.Builder().url(url)
            .header("x-api-key", key).header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON_MT)).build()

        val text = StringBuilder()
        val cid = HashMap<Int, String>(); val cname = HashMap<Int, String>(); val cargs = HashMap<Int, StringBuilder>()
        var inTok = 0; var outTok = 0
        sse(http, req) { data ->
            val o = parseObj(data) ?: return@sse
            when (o["type"].prim()) {
                "message_start" -> ((o["message"] as? JsonObject)?.get("usage") as? JsonObject)?.let {
                    inTok = (it["input_tokens"] as? JsonPrimitive)?.intOrNull ?: inTok
                }
                "content_block_start" -> {
                    val i = (o["index"] as? JsonPrimitive)?.intOrNull ?: 0
                    val b = o["content_block"] as? JsonObject
                    if (b?.get("type").prim() == "tool_use") { cid[i] = b?.get("id").prim() ?: "call_${uid()}"; cname[i] = b?.get("name").prim() ?: "" }
                }
                "content_block_delta" -> {
                    val i = (o["index"] as? JsonPrimitive)?.intOrNull ?: 0
                    val d = o["delta"] as? JsonObject
                    when (d?.get("type").prim()) {
                        "text_delta" -> d?.get("text").prim()?.let { text.append(it); onText(it) }
                        "input_json_delta" -> d?.get("partial_json").prim()?.let { cargs.getOrPut(i) { StringBuilder() }.append(it) }
                    }
                }
                "message_delta" -> (o["usage"] as? JsonObject)?.let { outTok = (it["output_tokens"] as? JsonPrimitive)?.intOrNull ?: outTok }
                "error" -> throw IOException((o["error"] as? JsonObject)?.get("message").prim() ?: "Anthropic stream error")
            }
        }
        val calls = cname.keys.sorted().map { i -> ToolCall(cid[i] ?: "call_${uid()}", cname[i] ?: "", cargs[i]?.toString()?.ifBlank { "{}" } ?: "{}") }
            .filter { it.name.isNotEmpty() }
        return TurnResult(text.toString(), calls, inTok, outTok)
    }
}

/** Lists model ids from the provider (GET /models). */
suspend fun fetchModelIds(http: OkHttpClient, provider: String, base: String, key: String): List<String> = withContext(Dispatchers.IO) {
    val b = base.trim().trimEnd('/')
    val rb = Request.Builder()
    if (provider == "anthropic") rb.url(b.removeSuffix("/v1") + "/v1/models?limit=100").header("x-api-key", key).header("anthropic-version", "2023-06-01")
    else rb.url("$b/models").header("Authorization", "Bearer $key")
    http.newCall(rb.build()).execute().use { r ->
        val txt = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw ApiError(r.code, txt)
        (parseObj(txt)?.get("data") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("id").prim() }?.sorted() ?: emptyList()
    }
}
