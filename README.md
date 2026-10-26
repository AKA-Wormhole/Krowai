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
- Stage 5 = DONE (this zip, still **NOT COMPILED** — see Step 0 below): daily-use upgrade.
  - **Image Studio screen was removed in Stage 11** (owner request). The AI image tools (`ImageTools.kt`, `ImageAiTools.kt`: `image_info`, `process_image`) and chat image attachments + viewer stay.
  - **Chat images**: Settings → Images (Fast 1024 / Balanced 1568 / Sharp 2048 px); viewer has pinch-zoom, double-tap, Save, Share.
  - **Web search** (`WebSearch.kt`, `ui/SearchScreen.kt`, `ui/WebSettings.kt`): engines = your **SearXNG** (`/search?format=json`),
    **4get** (`/api/v1/web|images|news`), **LibreY** (`/api.php?q&p&t`), **Google** (official Custom Search JSON API with key+cx, or a
    best-effort parse of google.com that may hit a captcha), **DuckDuckGo** HTML (zero setup). `Auto` tries google.com parsing first, then Google API, SearXNG, 4get, LibreY, DuckDuckGo (images/news skip the google.com parse).
    Search screen with categories (only those the engine really supports), suggestions, answer boxes, thumbnails, pagination,
    Copy link / Share / Ask Krow; typing a URL opens it directly.
  - **Live info for the AI**: tools `web_search` and `fetch_url` (reads a page to text; blocks localhost/private IPs, follows redirects safely,
    upgrades http→https, no PDFs/JS). System prompt tells the model to search first for anything recent and cite Markdown links.
    Toggle: Settings → Web search & live info.
  - **Chat day-to-day**: Copy / Select text / Share on every reply, Copy / Select / **Edit & resend** on your messages, bare URLs are
    clickable, text/links/images shared from other apps land in the composer (text only; also "Krow" in the text-selection menu).
  - Files from the owner's loose upload were merged into `app/src/main/java/com/krow/**` (newer `Chat/Core/Llm/Models/Sidebar/Theme/Ui/MainActivity`,
    split `Attachments`), fonts into `res/font/`.
  - 4get / LibreY / SearXNG JSON shapes were checked against their docs but not against live instances: test each with Settings → Test search.
- Stage 6 = DONE (still NOT COMPILED): `PublicApis.kt` — **Public SearXNG pool** (provider `public`, also last step of `Auto` before DuckDuckGo): live list from searx.space,
  cached 6 h, races 4 instances in parallel, benches failures 30 min. New keyless AI tools: `wikipedia`, `weather` (Open-Meteo), `currency` (Frankfurter/ECB), `define_word`.
  `Krow_Chat.html` is a working single-file web version of the chat (no settings screen; edit `CONFIG` at the top).
- Stage 7 = DONE (still NOT COMPILED): **Memory** (`Memory.kt`: local JSON facts, near-duplicate merge, pinned + relevance-ranked injection, tools `memory_save/search/forget`,
  modes Off / Ask first / Auto-save, "Remember this" button on messages, manager in Settings → Memory). **Connectors** (`Connectors.kt`: user-added HTTPS REST APIs with none/bearer/header/query auth,
  token in encrypted Secrets, host-locked, tools `connector_get` and approval-gated `connector_send`; presets Hacker News / Wikipedia / GitHub public). **Settings** is now a home list with one short page per topic
  (`SettingsScreen.kt`, `ui/MemoryConnectors.kt`). Not implemented: MCP protocol connectors, OAuth flows. `Krow_Chat.html` also has memory + export. `Krow_Full_Preview.html` = full web version incl. settings, Files, GitHub repo, Search screen (its Image Studio is obsolete).
- Stage 8 = DONE (still NOT COMPILED): **ChatGPT-style layout, Meridian colors.** Top bar = round menu button (accent dot while a reply is running) · centered `Chat | Work` pill (Work opens Files) · round new-chat button;
  model + repo line under it. Empty chat = bottom-aligned action rows (Write or edit, Search the web, Review my code, Summarize a file). Drawer = "Krow" + round search button, nav rows
  (Search, Files, Projects, Runs, Memory, Connectors), divider, plain chat list, floating `Chat` pill + avatar (opens Settings). Composer placeholder "Ask Krow". Same layout in `Krow_Full_Preview.html`.
- Stage 9 = DONE (still NOT COMPILED): chat top bar is now only a round menu button (accent dot while running) and a round new-chat button — no Chat|Work pill, no title/model/repo header.
  Model picker and Settings moved into the drawer (Model row with dropdown, Settings row). New-chat and conversation screens share the same bare layout.
- Stage 10 = DONE (still NOT COMPILED): **Linked repository + autonomous push.** Settings → GitHub → Repository: pick a repo once (`Core.linkRepo`, `Settings.repoProject`);
  from then on every chat, the Files screen and all AI tools work inside that repo project only (`Core.workProject`), whatever project the chat was created in. Files from the plain
  Workspace are copied in (as uncommitted changes) the first time a repo is linked. `git_commit`, `git_pull`, `github_build_apk`, `github_trigger_workflow` are `trusted` tools: they never ask for approval in Ask mode
  (Read-only mode still hides them; `git_restore`, `github_create_repo`, releases still ask). Empty GitHub repos are initialised with a README on first link. `github_create_repo` now clones + links the new repo itself.
  System prompt tells the AI it has full push authority and must never send the user to the sidebar. Composer grows with wrapped text up to half the screen height, then scrolls; the "K" mark was removed from the empty chat.
- Stage 11 = DONE (still NOT COMPILED): **Image Studio screen removed** (`ui/ImageStudio.kt`, sidebar row, home chip, share-image intent filters). `image_info` / `process_image` tools and their prompt line are kept.
- **NOT COMPILED.** The author had no Kotlin compiler or Android SDK. Step 0 for whoever continues: open in Android
  Studio (Ladybug+, JDK 17), sync, fix any compile errors (likely small API/import slips), run on a device, and test
  GitHub + Actions with a throwaway repo before trusting it with real work.
  `gradle-wrapper.jar` is not included; Android Studio downloads Gradle 8.9 from `gradle-wrapper.properties`.
  CI uses `gradle/actions/setup-gradle` with `gradle-version: 8.9` so the missing wrapper jar does not block Actions.
- Possible follow-ups: branch create/switch, pull-request creation, `.gitignore` in status, cancel in-flight HTTP on Stop,
  release-signing for release APKs, poll-until-done helper for long Actions runs.

## Build
Android Studio → Open this folder → Sync → Run. AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, minSdk 26,
targetSdk 34. Dependencies: OkHttp, jsoup, kotlinx-serialization, coroutines, security-crypto, material3 + icons-extended.
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

## Build note (R8 fix)
CI builds a minified debug APK. R8 failed on missing `com.google.errorprone.annotations.*` (from Tink via security-crypto).
Fixed with `app/proguard-rules.pro` (picked up by the workflow's init script) and a `compileOnly` errorprone dependency. If R8 reports other "Missing class" errors, add `-dontwarn` lines to that file.

## Attachments (images, text, code)
`Attachments.kt` stores picked images as downscaled JPEGs in `filesDir/attachments` (messages only keep the file name in `Msg.images`) and reads text/code files (<=400 KB) into `Msg.files`. `Llm.kt` sends images as `image_url` data URIs (OpenAI-compatible) or `image` blocks (Anthropic). The model must support vision. New-chat screen: greeting + suggestion chips; composer has a "+" menu (Photos, Files & code), New chat, Mode, Mic, Send.
- Stage 10 = DONE (not compiled here; CI is the compiler): **Native Gemini + thinking, file tools, reliable push, answer font.**
  - `Gemini.kt` (`GeminiLlm`): used automatically whenever the base URL is Google AI Studio (also the old `/v1beta/openai` URL, so saved keys keep working). Streams the answer via `streamGenerateContent`
    and streams the model's thought summaries separately (`includeThoughts`, only for gemini-2.5 and newer). Function calling with thought signatures, image input, safety-block errors surfaced. Model list uses the native `/models`.
    Thoughts are stored on `Msg.thought` and shown as a collapsed **Thought process** card (tap to expand; live "Thinking…" while streaming). OpenAI-compatible `reasoning_content` is shown the same way.
  - `FileTools.kt`: `edit_file` (tolerant of trailing whitespace/indentation/CRLF, errors show the real text and line), `multi_edit` (all-or-nothing), `insert_lines` (by line or after/before an anchor), `replace_lines`, `append_file`, `copy_path`.
  - Git: `git_commit` no longer always asks (Ask mode still asks, Auto-run pushes by itself) and pulls new remote commits automatically before committing; it stops only if upstream touched files you also changed. Scratch projects now tell the AI to ask the user to select a repo.
  - Answers use Poppins at 15sp (was Lora serif 17sp).
