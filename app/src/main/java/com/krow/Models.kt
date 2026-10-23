package com.krow

import kotlinx.serialization.Serializable
import java.util.UUID

fun uid(): String = UUID.randomUUID().toString().replace("-", "").take(12)
fun now(): Long = System.currentTimeMillis()

@Serializable
data class ToolCall(val id: String, val name: String, val args: String)

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
)

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
    val mode: String = "ask", // ask | auto | read
    val instructions: String = "",
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

val PROJECT_COLORS = longArrayOf(0xFFD9A066, 0xFF7FA8C9, 0xFF8FB58F, 0xFFC38FB5, 0xFFB5A27F, 0xFF9AA0D6)

class Attach(val name: String, val text: String)
data class Live(val chatId: String, val text: String)
