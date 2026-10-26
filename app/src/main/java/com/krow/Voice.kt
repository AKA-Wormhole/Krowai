package com.krow

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

enum class VoicePhase { Idle, Recording, Transcribing }

const val VOICE_MAX_MS = 5 * 60 * 1000L
const val VOICE_RATE = 16000

/** Records 16 kHz mono PCM and reports a 0..1 loudness level about every 50 ms (for the waveform). */
class PcmRecorder(private val onLevel: (Float) -> Unit) {
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private val out = ByteArrayOutputStream()
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(VOICE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, VOICE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, VOICE_RATE * 2))
        } catch (e: Throwable) { return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        return try {
            r.startRecording()
            rec = r
            running = true
            thread = Thread {
                val buf = ByteArray(1600) // 800 samples = 50 ms
                while (running) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    synchronized(out) { out.write(buf, 0, n) }
                    var sum = 0.0
                    var i = 0
                    while (i + 1 < n) {
                        val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                        sum += s.toDouble() * s
                        i += 2
                    }
                    val rms = sqrt(sum / max(1, n / 2)) / 32768.0
                    val db = 20.0 * log10(rms + 1e-6)
                    onLevel(((db + 52.0) / 46.0).coerceIn(0.0, 1.0).toFloat())
                }
            }.also { it.start() }
            true
        } catch (e: Throwable) { runCatching { r.release() }; false }
    }

    /** Stops and returns everything recorded as raw PCM. */
    fun stop(): ByteArray {
        running = false
        runCatching { thread?.join(300) }
        rec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        rec = null
        return synchronized(out) { out.toByteArray() }
    }
}

fun pcmToWav(pcm: ByteArray): ByteArray {
    val o = ByteArrayOutputStream(pcm.size + 44)
    fun i32(v: Int) { o.write(v and 255); o.write((v shr 8) and 255); o.write((v shr 16) and 255); o.write((v shr 24) and 255) }
    fun i16(v: Int) { o.write(v and 255); o.write((v shr 8) and 255) }
    o.write("RIFF".toByteArray()); i32(36 + pcm.size); o.write("WAVE".toByteArray())
    o.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(VOICE_RATE); i32(VOICE_RATE * 2); i16(2); i16(16)
    o.write("data".toByteArray()); i32(pcm.size); o.write(pcm)
    return o.toByteArray()
}

/** Streams a transcript of an audio clip from Google AI Studio (Gemini API, streamGenerateContent over SSE). */
object GeminiStt {
    private val JSON_MT = "application/json".toMediaType()
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val PROMPT = "Transcribe this audio exactly as spoken. Keep the speaker's language (do not translate). " +
        "Add normal punctuation and capitalisation. Output ONLY the transcript text: no quotes, no labels, no timestamps, no commentary. " +
        "If there is no speech, output nothing."

    /** Calls onText with the transcript-so-far each time a new chunk arrives; returns the final transcript. */
    suspend fun transcribe(http: OkHttpClient, key: String, model: String, wav: ByteArray, onText: (String) -> Unit): String {
        val m = model.trim().removePrefix("models/").ifBlank { "gemini-2.5-flash" }
        val body = withContext(Dispatchers.Default) {
            buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            addJsonObject { put("text", PROMPT) }
                            addJsonObject { putJsonObject("inline_data") { put("mime_type", "audio/wav"); put("data", Base64.encodeToString(wav, Base64.NO_WRAP)) } }
                        }
                    }
                }
                putJsonObject("generationConfig") {
                    put("temperature", 0)
                    if (m.contains("flash")) putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
                }
            }.toString()
        }
        val req = Request.Builder().url("$BASE/models/$m:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", key)
            .post(body.toRequestBody(JSON_MT)).build()
        val sb = StringBuilder()
        sse(http, req) { data ->
            val o = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@sse
            val parts = (((o["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("content") as? JsonObject)?.get("parts") as? JsonArray
            var grew = false
            parts?.forEach { pt ->
                val po = pt as? JsonObject ?: return@forEach
                if ((po["thought"] as? JsonPrimitive)?.booleanOrNull == true) return@forEach
                (po["text"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotEmpty()) { sb.append(it); grew = true } }
            }
            if (grew) onText(sb.toString().trimStart())
        }
        return sb.toString().trim()
    }

    /** Cheap key check: lists one model. Throws ApiError if the key is rejected. */
    suspend fun check(http: OkHttpClient, key: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/models?pageSize=1").header("x-goog-api-key", key).build()
        http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError(r.code, t)
        }
    }

    fun errText(e: Throwable): String = when (e) {
        is ApiError -> "Gemini ${e.code}: " + (runCatching { Json.parseToJsonElement(e.body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull() ?: e.body).take(180)
        else -> (e.message ?: "failed").take(180)
    }
}

/**
 * Drives the in-bar voice recorder. Two engines:
 *  - "gemini": records audio, then streams the transcript back from Google AI Studio into the text box.
 *  - "device": Android's built-in recogniser with live partial results (no pop-up, no key needed).
 */
class VoiceController(private val ctx: Context, private val core: Core) {
    var phase by mutableStateOf(VoicePhase.Idle)
        private set
    var live by mutableStateOf("")
        private set
    val levels = mutableStateListOf<Float>()

    private val main = Handler(Looper.getMainLooper())
    private var rec: PcmRecorder? = null
    private var sr: SpeechRecognizer? = null
    private var job: Job? = null
    private var engine = "gemini"
    private var smooth = 0f
    private var onUpdate: (String) -> Unit = {}
    private var onDone: (String) -> Unit = {}

    fun geminiKey(): String? =
        core.secrets.get(Secrets.GEMINI)?.takeIf { it.isNotBlank() }
            ?: core.state.value.settings.baseUrl.takeIf { it.contains("generativelanguage.googleapis.com", true) }?.let { core.apiKey() }

    private fun push(raw: Float) {
        smooth = smooth * .45f + raw * .55f
        levels.add(smooth)
        if (levels.size > 120) levels.removeAt(0)
    }

    private fun reset() {
        phase = VoicePhase.Idle; live = ""; levels.clear(); smooth = 0f
    }

    private fun releaseSr() {
        sr?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        sr = null
    }

    private fun complete(text: String) {
        releaseSr(); reset()
        onDone(text.trim())
    }

    /** Returns false (and tells the user why) when recording can't start. */
    fun start(onUpdate: (String) -> Unit, onDone: (String) -> Unit): Boolean {
        if (phase != VoicePhase.Idle) return false
        this.onUpdate = onUpdate; this.onDone = onDone
        engine = core.state.value.settings.voiceEngine
        levels.clear(); live = ""; smooth = 0f
        if (engine == "device") {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { core.say("No speech recognition service on this device. Switch to Gemini in Settings → Voice input."); return false }
            val s = SpeechRecognizer.createSpeechRecognizer(ctx)
            sr = s
            s.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { push(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (phase == VoicePhase.Recording) phase = VoicePhase.Transcribing }
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (t.isNotBlank()) { live = t; this@VoiceController.onUpdate(t) }
                }
                override fun onResults(results: Bundle?) {
                    if (phase == VoicePhase.Idle) return
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    complete(t.ifBlank { live })
                }
                override fun onError(error: Int) {
                    if (phase == VoicePhase.Idle) return
                    if (live.isNotBlank()) { complete(live); return }
                    val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    cancel()
                    core.say(if (quiet) "Didn't catch that. Try again." else "Speech recognition error ($error).")
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            }
            phase = VoicePhase.Recording
            s.startListening(i)
            return true
        }
        if (geminiKey() == null) { core.say("Add your Google AI Studio key in Settings → Voice input (or switch to the on-device engine)."); return false }
        val r = PcmRecorder { l -> main.post { if (phase == VoicePhase.Recording) push(l) } }
        if (!r.start()) { core.say("Could not open the microphone."); return false }
        rec = r
        phase = VoicePhase.Recording
        return true
    }

    /** Stop listening and turn what was said into text. */
    fun confirm() {
        if (phase != VoicePhase.Recording) return
        if (engine == "device") { phase = VoicePhase.Transcribing; sr?.stopListening(); return }
        val pcm = rec?.stop() ?: return
        rec = null
        if (pcm.size < VOICE_RATE / 2) { reset(); core.say("Too short. Hold on a little longer."); return }
        val key = geminiKey() ?: run { reset(); return }
        val model = core.state.value.settings.voiceModel
        phase = VoicePhase.Transcribing
        levels.clear()
        job = core.scope.launch {
            try {
                val txt = GeminiStt.transcribe(core.http, key, model, pcmToWav(pcm)) { part -> main.post { if (phase == VoicePhase.Transcribing) { live = part; onUpdate(part) } } }
                main.post { if (phase == VoicePhase.Transcribing) { if (txt.isBlank()) { reset(); core.say("No speech detected.") } else complete(txt) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                main.post { if (phase == VoicePhase.Transcribing) { reset(); core.say(GeminiStt.errText(e)) } }
            }
        }
    }

    /** Throw the recording away. */
    fun cancel() {
        runCatching { rec?.stop() }
        rec = null
        job?.cancel(); job = null
        releaseSr()
        reset()
    }
}
