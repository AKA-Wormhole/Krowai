package com.krow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val gjson = Json { ignoreUnknownKeys = true }

/**
 * Native Google Gemini client (generativelanguage.googleapis.com, streamGenerateContent over SSE).
 * Streams the answer token by token AND the model's thought summaries separately (shown collapsed in the chat),
 * supports function calling with thought signatures and image input.
 * Used automatically whenever the base URL points at Google AI Studio.
 */
class GeminiLlm(private val http: OkHttpClient, base: String, private val key: String, model: String) : Llm {
    private val m = model.trim().removePrefix("models/")
    private val root = geminiRoot(base)
    private val thinks = Regex("gemini-(2\\.5|[3-9])").containsMatchIn(m)
    private val g3 = Regex("gemini-[3-9]").containsMatchIn(m)

    private fun argsOf(s: String): JsonElement =
        runCatching { gjson.parseToJsonElement(s.ifBlank { "{}" }) }.getOrNull()?.takeIf { it is JsonObject } ?: JsonObject(emptyMap())

    override suspend fun stream(system: String, msgs: List<Msg>, tools: List<ToolSpec>, onThought: (String) -> Unit, onText: (String) -> Unit): TurnResult {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
            putJsonArray("contents") {
                var i = 0
                while (i < msgs.size) {
                    val mm = msgs[i]
                    when (mm.role) {
                        "user" -> {
                            addJsonObject {
                                put("role", "user")
                                putJsonArray("parts") {
                                    val t = mm.apiText()
                                    if (t.isNotBlank()) addJsonObject { put("text", t) }
                                    mm.images.mapNotNull { Attachments.base64(it) }.forEach { b ->
                                        addJsonObject { putJsonObject("inlineData") { put("mimeType", "image/jpeg"); put("data", b) } }
                                    }
                                    if (t.isBlank() && mm.images.isEmpty()) addJsonObject { put("text", " ") }
                                }
                            }
                            i++
                        }
                        "assistant" -> {
                            addJsonObject {
                                put("role", "model")
                                putJsonArray("parts") {
                                    if (mm.text.isNotEmpty()) addJsonObject { put("text", mm.text) }
                                    mm.calls.forEachIndexed { k, c ->
                                        addJsonObject {
                                            putJsonObject("functionCall") { put("name", c.name); put("args", argsOf(c.args)) }
                                            // Gemini 3 requires the thought signature to be sent back with the function call.
                                            val sig = c.sig ?: if (g3 && k == 0) "skip_thought_signature_validator" else null
                                            if (sig != null) put("thoughtSignature", sig)
                                        }
                                    }
                                    if (mm.text.isEmpty() && mm.calls.isEmpty()) addJsonObject { put("text", " ") }
                                }
                            }
                            i++
                        }
                        "tool" -> {
                            addJsonObject {
                                put("role", "user")
                                putJsonArray("parts") {
                                    while (i < msgs.size && msgs[i].role == "tool") {
                                        val t = msgs[i]
                                        addJsonObject {
                                            putJsonObject("functionResponse") {
                                                put("name", t.tool ?: "tool")
                                                putJsonObject("response") { put(if (t.status == "done") "output" else "error", t.text.ifEmpty { "(no output)" }) }
                                            }
                                        }
                                        i++
                                    }
                                }
                            }
                        }
                        else -> i++
                    }
                }
            }
            if (tools.isNotEmpty()) putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        tools.forEach { t -> addJsonObject { put("name", t.name); put("description", t.description); put("parameters", t.schema) } }
                    }
                }
            }
            if (thinks) putJsonObject("generationConfig") { putJsonObject("thinkingConfig") { put("includeThoughts", true) } }
        }
        val req = Request.Builder().url("$root/models/$m:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON_MT)).build()

        val text = StringBuilder(); val think = StringBuilder()
        val calls = ArrayList<ToolCall>()
        var inTok = 0; var outTok = 0
        var finish: String? = null
        var blocked: String? = null
        sse(http, req) { data ->
            val o = parseObj(data) ?: return@sse
            (o["error"] as? JsonObject)?.let { throw IOException(it["message"].prim() ?: "Gemini error") }
            (o["promptFeedback"] as? JsonObject)?.get("blockReason").prim()?.let { blocked = it }
            (o["usageMetadata"] as? JsonObject)?.let { u ->
                inTok = (u["promptTokenCount"] as? JsonPrimitive)?.intOrNull ?: inTok
                val c = (u["candidatesTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
                val th = (u["thoughtsTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
                if (c + th > 0) outTok = c + th
            }
            val cand = (o["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@sse
            cand["finishReason"].prim()?.let { finish = it }
            val parts = (cand["content"] as? JsonObject)?.get("parts") as? JsonArray ?: return@sse
            for (pe in parts) {
                val part = pe as? JsonObject ?: continue
                val fc = part["functionCall"] as? JsonObject
                if (fc != null) {
                    val name = fc["name"].prim().orEmpty()
                    if (name.isNotEmpty()) calls += ToolCall(fc["id"].prim() ?: "call_${uid()}", name, (fc["args"] ?: JsonObject(emptyMap())).toString(), part["thoughtSignature"].prim())
                    continue
                }
                val t = part["text"].prim()
                if (t.isNullOrEmpty()) continue
                if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) { think.append(t); onThought(t) }
                else { text.append(t); onText(t) }
            }
        }
        if (text.isEmpty() && calls.isEmpty()) {
            blocked?.let { throw IOException("Gemini blocked this request ($it). Try rephrasing.") }
            val f = finish
            if (f != null && f != "STOP" && f != "MAX_TOKENS") throw IOException("Gemini stopped without an answer ($f).")
        }
        return TurnResult(text.toString(), calls, inTok, outTok, think.toString())
    }

    companion object {
        /** Native API root for any Google AI Studio base URL (also accepts the OpenAI-compat one). */
        fun geminiRoot(base: String): String {
            val b = base.trim().trimEnd('/').removeSuffix("/openai")
            return if (b.contains("/v1")) b else "$b/v1beta"
        }

        /** Model ids that can generate content. */
        suspend fun listModels(http: OkHttpClient, base: String, key: String): List<String> = withContext(Dispatchers.IO) {
            val out = ArrayList<String>()
            var token: String? = null
            repeat(5) {
                val u = "${geminiRoot(base)}/models?pageSize=200" + (token?.let { "&pageToken=$it" } ?: "")
                val txt = http.newCall(Request.Builder().url(u).header("x-goog-api-key", key).build()).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw ApiError(r.code, t)
                    t
                }
                val o = parseObj(txt) ?: return@repeat
                (o["models"] as? JsonArray)?.forEach { e ->
                    val mo = e as? JsonObject ?: return@forEach
                    val ok = (mo["supportedGenerationMethods"] as? JsonArray)?.any { it.prim() == "generateContent" } ?: true
                    val id = mo["name"].prim()?.removePrefix("models/")
                    if (ok && id != null && id.startsWith("gemini")) out += id
                }
                token = o["nextPageToken"].prim()
                if (token.isNullOrEmpty()) return@withContext out.sorted()
            }
            out.sorted()
        }
    }
}
