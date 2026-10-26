package com.krow

import kotlinx.serialization.Serializable
import java.util.UUID

fun uid(): String = UUID.randomUUID().toString().replace("-", "").take(12)
fun now(): Long = System.currentTimeMillis()

@Serializable
data class ToolCall(val id: String, val name: String, val args: String, val sig: String? = null)

/** One stored message. role = user | assistant | tool. Tool messages double as the "step" cards in the UI. */
@Serializable
data class Msg(
    val role: String,
    val text: String = "",
    val calls: List<ToolCall> = emptyList(),
    val callId: String? = null,
    val tool: String? = null,
    val kind: String = "terminal",
    val title: String = "",
    val cmd: String = "",
    val exit: Int? = null,
    val status: String = "done", // done | fail | denied | stopped | pending | running
    val ms: Long = 0,
    val at: Long = now(),
    val model: String? = null,
    val inTok: Int = 0,
    val outTok: Int = 0,
    val genMs: Long = 0,
    val thought: String = "",   // model reasoning summary (Gemini thinking), shown collapsed
    val images: List<String> = emptyList(), // stored image file names (see Attachments)
    val files: List<FileRef> = emptyList(),  // attached text / code files
)

@Serializable
data class FileRef(val name: String, val text: String)

@Serializable
data class Chat(
    val id: String,
    val title: String = "New chat",
    val projectId: String = "scratch",
    val pinned: Boolean = false,
    val created: Long = now(),
    val updated: Long = now(),
    val msgs: List<Msg> = emptyList(),
)

/** A project is a working folder. kind = scratch (plain local folder) | repo (GitHub clone, added in stage 3). */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val kind: String = "scratch",
    val owner: String = "",
    val repo: String = "",
    val branch: String = "",
    val color: Long = 0xFFD9A066,
)

@Serializable
data class Settings(
    val provider: String = "openai", // openai (any OpenAI-compatible API) | anthropic
    val baseUrl: String = "https://api.openai.com/v1",
    val models: List<String> = listOf("gpt-4o-mini"),
    val model: String = "gpt-4o-mini",
    val theme: String = "system", // system | light | dark
    val accent: String = "emerald", // emerald | ember | blue | rose | gold | violet
    val mode: String = "ask", // ask | auto | read
    val instructions: String = "",
    val webSearch: Boolean = true,          // give the AI web_search + fetch_url
    val searchProvider: String = "auto",    // auto | searxng | 4get | librey | google | ddg
    val searxUrl: String = "",
    val fourgetUrl: String = "",
    val libreyUrl: String = "",
    val googleCx: String = "",              // Google Custom Search engine id (the API key itself lives in Secrets)
    val memory: String = "auto",            // off | ask (confirm each save) | auto
    val connectors: List<Connector> = emptyList(),
    val imgQuality: String = "balanced",    // chat image attachments: fast | balanced | high
    val voiceEngine: String = "gemini",     // gemini (Google AI Studio, streamed) | device (Android recogniser)
    val voiceModel: String = "gemini-2.5-flash",
    val userName: String = "",              // shown as initials on the sidebar avatar
    val voiceAutoSend: Boolean = false,     // send the message as soon as the transcript is ready
    val repoProject: String = "",           // id of the repo project Krow is locked to (Settings → GitHub → Repository); "" = none
)

@Serializable
data class AppState(
    val projects: List<Project> = listOf(Project("scratch", "Workspace")),
    val chats: List<Chat> = emptyList(),
    val settings: Settings = Settings(),
    val activeChat: String? = null,
    val activeProject: String = "scratch", // "" = all projects
    val tokDay: String = "",
    val tokens: Int = 0,
)

val PROJECT_COLORS = longArrayOf(0xFFE07B57, 0xFFF0A35E, 0xFF6DBE86, 0xFF8DB6F2, 0xFFC9A0DC, 0xFFD9C37A)

/** A pending attachment in the composer. image != null means a stored, downscaled JPEG. */
class Attach(val name: String, val text: String, val image: String? = null)
data class Live(val chatId: String, val text: String, val thought: String = "", val since: Long = now())
