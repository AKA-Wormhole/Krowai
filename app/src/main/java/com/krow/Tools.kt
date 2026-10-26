package com.krow

import kotlinx.serialization.json.*
import java.io.File

class ToolCtx(val core: Core, val project: Project, val dir: File)
class ToolOut(val text: String, val ok: Boolean = true, val exit: Int? = null)

interface Tool {
    val spec: ToolSpec
    val kind: String get() = "terminal"       // icon: terminal | search | file | edit | folder | zip | git | actions | artifact
    val mutating: Boolean get() = false        // changes files / runs commands / touches remote
    val alwaysAsk: Boolean get() = false       // asks for approval even in Auto-run mode
    val trusted: Boolean get() = false         // never asks in Ask mode (only for actions confined to the linked repository)
    fun title(a: JsonObject): String = spec.name
    fun cmd(a: JsonObject): String = ""
    suspend fun run(c: ToolCtx, a: JsonObject): ToolOut
}

fun prop(type: String, desc: String): JsonObject = buildJsonObject { put("type", type); put("description", desc) }
fun schema(required: List<String>, vararg p: Pair<String, JsonObject>): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { p.forEach { (k, v) -> put(k, v) } }
    putJsonArray("required") { required.forEach { add(it) } }
}

fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

/** Registry of everything the AI can do. */
object Tools {
    val enabled: Boolean = true

    private fun local(): List<Tool> = listOf(
        ListFiles(), ReadFile(), SearchFiles(), WriteFile(), EditFile(), MultiEdit(), InsertLines(), ReplaceLines(), AppendFile(), CopyPath(), MakeDir(), RenamePath(), DeletePath(),
        ZipCreate(), Unzip(), RunCommand(), SendArtifact(), ShareFile(), ImageInfo(), ProcessImage(),
    )

    private fun web(): List<Tool> = listOf(WebSearchTool(), FetchUrlTool(), WikipediaTool(), WeatherTool(), CurrencyTool(), DefineTool())

    private fun git(): List<Tool> = listOf(GitStatus(), GitDiff(), GitLog(), GitCommit(), GitPull(), GitRestore())

    private fun actions(): List<Tool> = listOf(
        GhBuildApk(), GhRunLogs(), GhListWorkflows(), GhTriggerWorkflow(), GhListRuns(), GhRunStatus(), GhDownloadArtifact(), EnsureApkWorkflow(), GhCancelRun(), GhRerun(), GhCreateRelease(),
    )

    fun all(core: Core, p: Project): List<Tool> {
        val l = ArrayList(local())
        if (core.settings.webSearch) l += web()
        if (p.kind == "repo") {
            l += git()
            l += actions()
        }
        if (core.settings.memory != "off") l += listOf(MemorySave(core.settings.memory == "ask"), MemorySearch(), MemoryForget())
        if (core.settings.connectors.isNotEmpty()) l += ConnectorGet()
        if (core.settings.connectors.any { it.write }) l += ConnectorSend()
        if (core.githubToken() != null) l += GitHubCreateRepo()
        return l
    }

    fun available(core: Core, p: Project, mode: String): List<Tool> =
        all(core, p).filter { mode != "read" || !it.mutating }

    fun systemNotes(core: Core, p: Project): String = buildString {
        append("\n\nYou have tools. Your working folder is the project \"${p.name}\" on the user's Android phone; every file path is relative to its root and you cannot reach outside it. ")
        append("Use tools to do real work instead of describing it: inspect before you change, make the change, then verify (read the file back or run a command). ")
        append("File editing rules: always read_file (or search_files) before you change a file so your edits match its real text and line numbers. ")
        append("Small change = edit_file; several changes in one file = multi_edit (all-or-nothing); add lines = insert_lines (by line number or after/before an anchor); swap or delete a block by line numbers = replace_lines; add to the end = append_file; new or fully rewritten file = write_file (give the COMPLETE content, never a placeholder like '...rest unchanged'). ")
        append("Never resend a whole large file just to change a few lines. If an edit fails, read the error: it shows the real text and line, so fix the match and retry instead of rewriting the file. After editing, verify by reading the changed region back or running a check, and tell the user exactly what changed. ")
        append("For multi-step work, state a short plan first, then do the steps with tools, then summarise what is done and what is left. ")
        append("When you produce something important the user should keep, copy, or share (a finished script, config, document, patch summary, generated code), call send_artifact so it appears as an artifact card — do not only paste huge blobs in the chat prose. ")
        append("Name every file card beautifully: pass a short, human title in sentence case with no extension (\"Krow voice update\", \"Quarterly budget\", \"Login screen\"), never a raw file name. When you share the same file again after changing it, reuse the same title; the app labels it Update 1, Update 2 and so on by itself. ")
        append("When you create or find a file the user asked for (a ZIP, APK, image, PDF, document, any file), call share_file with its path so it shows up as a downloadable file card; the user can then download it to their phone or share it. ")
        append("Every tool accepts an optional step_name: always fill it with a short, specific, human-friendly name for that action (2-5 words, e.g. \"Build debug APK\", \"Fix compile errors\"), because it is what the user sees in the chat. ")
        append("run_command uses Android's /system/bin/sh, so only Android's built-in commands exist (no git, python, node or package managers); ")
        append("do not claim to have run something you did not run, and report errors exactly as they appear. ")
        append("If the user's mode is read-only, you only have tools that read.")
        if (p.kind != "repo" && core.githubToken() != null) {
            append("\n\nNo GitHub repository is selected yet, so you have no git tools and cannot push. If the user wants code on GitHub, tell them once, in one sentence: Settings → GitHub → Repository → pick the repo (or ask you to create one with github_create_repo). Files already in this workspace are copied into the repository when it is selected. Do not describe any other steps.")
        }
        if (p.kind == "repo") {
            append("\n\nThis project is a local copy of the GitHub repository ${p.owner}/${p.repo} on branch ${p.branch}. You can only work on this repository. ")
            append("Edit files with the normal file tools, then use git_status and git_diff to review. git_commit commits AND pushes to the remote branch in one step (there is no local-only commit); it pulls new remote commits by itself first and only stops if they conflict with the user's own changes. ")
            append("You have FULL authority over this repository: committing, pushing and pulling never need the user's permission, so never ask 'should I push?' and never send the user to a sidebar, menu or setting for it. When the user says push, commit, upload, save to GitHub or ship it, DO IT YOURSELF right away: git_status, then git_commit with a clear message (imperative, under 72 characters, optional short body), then tell the user the commit id. If the files they mean are not in the project yet, create them first with the file tools. If git_commit fails, read the error, fix the cause and retry; do not claim you pushed unless git_commit returned success. Git cannot store empty folders: to add a folder, create a file inside it (for example .gitkeep). ")
            append("Use git_restore only to discard changes the user wants discarded. Never ask for or print the GitHub token.")
            append("\n\nGitHub Actions: you can list workflows, trigger workflow_dispatch (always ask), list/check runs, and download artifacts into artifacts/ci/. ")
            append("To build an Android APK: just call github_build_apk (pass commit_message if there are uncommitted changes). It commits & pushes, triggers the workflow, waits, downloads the APK, saves it to the phone's Downloads folder and removes every temporary copy, and returns a file card the user can open or share. If it reports a failed build, read the errors, fix the code, and call it again (up to a few attempts) before asking the user. Use github_run_logs for the log of any other run, github_rerun for flaky failures, github_cancel_run to stop a build. ")
            append("Only trigger workflows the user asked for. Never touch other repositories.")
        }
        if (core.settings.webSearch) {
            append("\n\nLive web: your training data is out of date. For anything recent or changeable (news, prices, versions, releases, scores, laws, docs, people in current roles) or whenever you are not sure, call web_search FIRST, then fetch_url on the 1-3 most relevant results and answer from what you read. ")
            append("Cite sources as Markdown links like [title](url). Never invent URLs; only link pages that appeared in results. If search fails, say so plainly instead of guessing. When the user gives you a link, open it with fetch_url.")
        }
        if (core.settings.memory != "off") append("\n\nMemory: when the user shares a lasting preference, goal, project or says 'remember', call memory_save with one short fact (don't save trivia or secrets, don't announce it at length). If they say 'forget', call memory_forget.")
        if (core.settings.connectors.isNotEmpty()) {
            append("\n\nConnected APIs (use connector_get${if (core.settings.connectors.any { it.write }) " / connector_send" else ""}; never ask for or print tokens):")
            core.settings.connectors.forEach { append("\n- ${it.name} (${it.baseUrl})${if (it.about.isNotBlank()) ": " + it.about else ""}${if (it.write) " [writes allowed]" else " [read-only]"}") }
        }
        append("\n\nImages: image_info and process_image compress, resize, convert (jpeg/webp/png), rotate, crop and color-adjust image files in the project and write a new file.")
        if (core.githubToken() != null) append("\n\ngithub_create_repo creates a new repository on the user's account only when they ask, then links it as the working repository.")
    }
}
