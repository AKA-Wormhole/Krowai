# Krow — handoff README (read this first, any AI)

Krow is a native **Kotlin / Jetpack Compose Android app**: an AI chat app that talks to real AI APIs, can run real
shell commands and edit files in a project folder, and integrates with GitHub through a user-provided Personal Access
Token. The UI reference is `Meridian_Chat.html` (in this same folder): warm dark/light palette, terracotta accent,
serif answers, mono details, pill shapes, collapsible "steps" cards for tool runs. No neon, no gimmicks.

## The owner's requirements (verbatim intent — do not water down)
- App name **Krow**, Kotlin, AI app that uses APIs. UI exactly like or better than `Meridian_Chat.html`.
- Able to run commands and do everything, built in **stages**; after each stage a zip with this README and
  `Meridian_Chat.html` in the same folder. The owner may continue with another AI if limits are hit.
- **No demo, no placeholders, no fake or missing features. Real features only.** If something cannot be real, hide it.
- GitHub via user-provided PAT: select/enter any repo the user owns or can access; AI can list, read, create, edit,
  rename, delete files; create folders; create ZIPs; commit, pull, push, inspect changes; create new repos when asked
  and permitted. Keep the token secure, request minimal permissions, **restrict all operations to the explicitly
  selected repository** — never touch other repos unless the user selects them.
- AI should send **artifacts** for important deliverables; trigger **GitHub Actions**; **build APK** via Actions and
  download/push flow. UI at ChatGPT/Claude level (auto-expanding composer, polished thread).

## Honest status
- Stage 1 = DONE: project, theme, full chat UI, streaming API client (OpenAI-compatible + Anthropic), settings, encrypted
  key storage, projects/chats/search/runs panel, attachments, agent loop with approval plumbing.
- Stage 2 = DONE: `LocalTools.kt` (list_files, read_file, search_files, write_file, edit_file, make_dir,
  rename_path, delete_path, zip_create, unzip, run_command via `/system/bin/sh`), `Diff.kt`, `ui/Files.kt` (browse, view/edit,
  create, rename, delete, import, share file / folder-as-ZIP through FileProvider). All file tools resolve paths through
  `resolveIn()` (no escaping the project folder; `.krow/` is reserved).
- Stage 3 = DONE: GitHub, scoped to one repository per project (`Git.kt`, `ui/Git.kt`, commit=push, pull, restore, create repo).
- Stage 4 = DONE (this zip): Artifacts + GitHub Actions + APK CI + UI polish.
  - **Artifacts** (`Artifacts.kt`): tool `send_artifact` writes important deliverables under `artifacts/` (or a path you
    choose) and renders a first-class card in chat (preview, language pill, Copy, Share via FileProvider). System prompt
    tells the model to use it for keep/copy/share outputs instead of dumping huge blobs in prose.
  - **GitHub Actions** (`Actions.kt`, repo projects only): `github_list_workflows`, `github_trigger_workflow`
    (always asks; `workflow_dispatch` on the project's branch), `github_list_runs`, `github_run_status`,
    `github_download_artifact` (artifact ZIP → `artifacts/ci/`, APKs extracted), `ensure_apk_workflow` (writes
    `.github/workflows/build-apk.yml` if missing). Owner/repo/branch always from the selected `Project`.
  - **APK build**: repo includes `.github/workflows/build-apk.yml` (JDK 17, Gradle 8.9, `assembleDebug`, upload
    `krow-debug-apk`). Flow for any Android repo project: ensure workflow → `git_commit` → `github_trigger_workflow`
    (`build-apk.yml`) → poll runs → `github_download_artifact`. Token needs Actions read/write (see Settings → GitHub).
  - **UI**: ChatGPT/Claude-level chat surface (warm Meridian only — **no neon**). Top bar: **New chat**, title +
    **owner/repo · branch**, mode picker, model picker. Centered ~780dp thread, day breaks, spring scroll physics,
    soft shadows, auto-growing composer, command/step cards, artifact cards, scroll-to-bottom chip, IME padding.
- **NOT COMPILED.** The author had no Kotlin compiler or Android SDK. Step 0 for whoever continues: open in Android
  Studio (Ladybug+, JDK 17), sync, fix any compile errors (likely small API/import slips), run on a device, and test
  GitHub + Actions with a throwaway repo before trusting it with real work.
  `gradle-wrapper.jar` is not included; Android Studio downloads Gradle 8.9 from `gradle-wrapper.properties`.
  CI uses `gradle/actions/setup-gradle` with `gradle-version: 8.9` so the missing wrapper jar does not block Actions.
- Possible follow-ups: branch create/switch, pull-request creation, `.gitignore` in status, cancel in-flight HTTP on Stop,
  release-signing for release APKs, poll-until-done helper for long Actions runs.

## Build
Android Studio → Open this folder → Sync → Run. AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, minSdk 26,
targetSdk 34. Dependencies: OkHttp, kotlinx-serialization, coroutines, security-crypto, material3 + icons-extended.
Cleartext HTTP is blocked (HTTPS APIs only).

**CI APK:** push this repo to GitHub (or trigger `Build APK` via Actions / Krow tools). Artifact name: `krow-debug-apk`.

## Architecture (package `com.krow`)
- `Core.kt` — single app-wide state holder (`StateFlow<AppState>`), chats/projects/settings mutations, debounced
  JSON persistence, **agent loop** (`runAgent`: stream model → append assistant msg → run tool calls → repeat, max 30
  steps), approval handling (`approvals` map of `CompletableDeferred`), stop/retry, token counter (real usage only).
- `Llm.kt` — `OpenAiLlm` (any OpenAI-compatible `/chat/completions`, SSE, tool calls) and `AnthropicLlm`
  (`/v1/messages`, SSE, tool_use). `fetchModelIds` lists models. Cancellation cancels the HTTP call.
- `Tools.kt` — `Tool` interface + `Tools` registry. Add tools to `Tools.all()` (git + actions tools for `kind == "repo"`).
  Modes: ask (approve `mutating` tools), auto (run all; `alwaysAsk` tools still ask), read (mutating tools hidden and refused).
- `Artifacts.kt` — `send_artifact` + payload parser for chat cards.
- `Actions.kt` — GitHub Actions REST helpers + tools + APK workflow template.
- `Models.kt` — `Msg` (role user/assistant/tool; tool msgs are the step cards), `Chat`, `Project`, `Settings`, `AppState`.
- `Store.kt` — `Store` (JSON file, no secrets) and `Secrets` (EncryptedSharedPreferences: API keys per base URL,
  GitHub PAT under `Secrets.GITHUB`). Never log or send secrets anywhere else.
- `ui/` — `Theme.kt` (palette from Meridian CSS vars), `Ui.kt` (primitives), `Markdown.kt`, `Chat.kt` (top bar, thread,
  steps cards, artifact cards, auto-growing composer), `Sidebar.kt`, `SettingsScreen.kt`.
- `MainActivity.kt` — drawer + chat/settings navigation.
- A **project** = a folder `filesDir/projects/<id>/`. `kind="repo"` projects are GitHub clones (owner/repo/branch) created by `Core.addRepoProject`.

## Rules for continuing
1. Real features only; hide anything not wired. Keep the Meridian look (use `LocalPal`, `T`, `Pill`, `IconBtn`).
2. Never put secrets in prompts, tool output, logs or the JSON store.
3. GitHub tools must take owner/repo from the selected `Project`, never from model arguments.
4. Update the "Honest status" section above and re-zip (with `Meridian_Chat.html`) after each change set.
