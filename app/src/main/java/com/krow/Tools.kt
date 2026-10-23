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
        ListFiles(), ReadFile(), SearchFiles(), WriteFile(), EditFile(), MakeDir(), RenamePath(), DeletePath(),
        ZipCreate(), Unzip(), RunCommand(), SendArtifact(),
    )

    private fun git(): List<Tool> = listOf(GitStatus(), GitDiff(), GitLog(), GitCommit(), GitPull(), GitRestore())

    private fun actions(): List<Tool> = listOf(
        GhListWorkflows(), GhTriggerWorkflow(), GhListRuns(), GhRunStatus(), GhDownloadArtifact(), EnsureApkWorkflow(),
    )

    fun all(core: Core, p: Project): List<Tool> {
        val l = ArrayList(local())
        if (p.kind == "repo") {
            l += git()
            l += actions()
        }
        if (core.githubToken() != null) l += GitHubCreateRepo()
        return l
    }

    fun available(core: Core, p: Project, mode: String): List<Tool> =
        all(core, p).filter { mode != "read" || !it.mutating }

    fun systemNotes(core: Core, p: Project): String = buildString {
        append("\n\nYou have tools. Your working folder is the project \"${p.name}\" on the user's Android phone; every file path is relative to its root and you cannot reach outside it. ")
        append("Use tools to do real work instead of describing it: inspect before you change, make the change, then verify (read the file back or run a command). ")
        append("Prefer edit_file for small changes and write_file for new files. ")
        append("When you produce something important the user should keep, copy, or share (a finished script, config, document, patch summary, generated code), call send_artifact so it appears as an artifact card — do not only paste huge blobs in the chat prose. ")
        append("run_command uses Android's /system/bin/sh, so only Android's built-in commands exist (no git, python, node or package managers); ")
        append("do not claim to have run something you did not run, and report errors exactly as they appear. ")
        append("If the user's mode is read-only, you only have tools that read.")
        if (p.kind == "repo") {
            append("\n\nThis project is a local copy of the GitHub repository ${p.owner}/${p.repo} on branch ${p.branch}. You can only work on this repository. ")
            append("Edit files with the normal file tools, then use git_status and git_diff to review. git_commit commits AND pushes to the remote branch in one step (there is no local-only commit); ")
            append("if it says the remote has new commits, run git_pull first. Only commit when the user asks you to or has clearly agreed. Git cannot store empty folders: to add a folder, create a file inside it (for example .gitkeep). ")
            append("Use git_restore only to discard changes the user wants discarded. Never ask for or print the GitHub token.")
            append("\n\nGitHub Actions: you can list workflows, trigger workflow_dispatch (always ask), list/check runs, and download artifacts into artifacts/ci/. ")
            append("To build an Android APK via CI: ensure_apk_workflow → git_commit the workflow file → github_trigger_workflow with workflow=build-apk.yml → poll with github_list_runs / github_run_status → github_download_artifact when the run succeeds. ")
            append("Only trigger workflows the user asked for. Never touch other repositories.")
        }
        if (core.githubToken() != null) append("\n\ngithub_create_repo creates a new repository on the user's account only when they ask; it does not select or modify any repository.")
    }
}
