# BubbleClaw — Android floating bubble for OpenClaw

**Read this file completely before writing code.**

## 1. Product

A persistent floating chat-head bubble (Messenger style) that lives over every app on Android.
Tap the bubble → BubbleClaw captures the screen **first**, then opens a chat panel already
aware of the screen context, and talks to the user's own OpenClaw gateway (a local agent
exposed over an OpenAI-compatible HTTP API).

Default behaviour (no configuration needed at runtime):

1. User taps the bubble.
2. App captures a screenshot of the current screen (Accessibility API, no MediaProjection popup).
3. App resolves: foreground app name, and the current web page URL when the foreground app is a browser.
4. Chat panel opens with a small context line at the top: `Écran : <app> — <url|title>` plus the screenshot thumbnail.
5. User types a question; request is sent to the gateway with the screenshot as an image part.
6. Answer streams back into the panel. Conversation is continuous (stable session id).
7. Optional "control mode" (off by default, toggled from the panel): the agent may act on the
   screen through function tools (tap, swipe, type, open app, back/home, scroll). When it is off,
   no tool is offered to the model at all.

## 2. Stack and hard constraints

- Kotlin, **classic Views only** (no Jetpack Compose), single `app` module.
- `minSdk 30`, `targetSdk 35`, `compileSdk 35`, AGP 8.7.3, Kotlin 2.0.21, JVM target 17.
- Dependencies: **only** androidx core-ktx / appcompat / material / constraintlayout / lifecycle-runtime-ktx
  (already declared). No OkHttp, no Retrofit, no Gson, no Kotlin coroutines library beyond what androidx pulls:
  use `java.net.HttpURLConnection`, `org.json`, `Thread`/`Handler` and `Executors`.
- Gradle build must stay green with `./gradlew assembleDebug`. Do **not** touch `gradle.properties`
  (memory is tuned: -Xmx1536m, 2 workers, no daemon).
- SDK path: `/home/dev/android-sdk` (already in `local.properties`).
- Package `dev.bubbleclaw`, sources under `app/src/main/java/dev/bubbleclaw/`.
- All user-visible strings in **French**. Code comments in English.
- Do not break the existing manifest entries: accessibility service `canTakeScreenshot` and
  `canRetrieveWindowContent` must stay true, and the bubble foreground service must stay
  `foregroundServiceType="specialUse"`.

## 3. Components

### 3.1 `ScreenReaderService : AccessibilityService`

- Singleton accessor (`ScreenReaderService.instance`) so the rest of the app can reach it.
- Tracks the foreground package: `TYPE_WINDOW_STATE_CHANGED`, keep the last package name and
  resolve a human label through `PackageManager` (`ApplicationInfo.loadLabel`) — cache it.
  Ignore our own package.
- `fun captureScreenshot(callback: (Bitmap?) -> Unit)`:
  `takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, TakeScreenshotCallback)` (API 30),
  convert `HardwareBuffer` → `Bitmap` → scale so width ≤ 1080 → compress JPEG quality 70 →
  also keep a small thumbnail (width ≤ 320) for the UI. Close the buffer.
- `fun currentPageUrl(): String?` — only when the foreground package is a known browser
  (`com.android.chrome`, `org.mozilla.firefox`, `com.brave.browser`, `com.microsoft.emmx`,
  `com.sec.android.app.sbrowser`, `com.opera.browser`, `com.opera.gx`, `com.duckduckgo.mobile.android`,
  `com.vivaldi.browser`, `org.chromium.chrome`):
  walk `rootInActiveWindow` nodes, take the first `EditText` in the top 35 % of the screen whose
  text looks like a URL or a search query (`contains("://")`, `startsWith("www.")`, or
  `^[\w.-]+\.[a-z]{2,}(/.*)?$`), strip the leading "Rechercher ou saisir une URL" placeholder and
  any `http(s)://` prefix noise. Fall back to the window title. Return `null` if nothing usable.
- `fun visibleTextDigest(maxChars: Int = 1500): String?` — concatenate node texts (text/contentDescription)
  from the active window, collapse whitespace, truncate. Used only when the setting is enabled.
- `fun performTap(x: Float, y: Float)`, `performSwipe(...)`, `performScroll(direction, amount)`,
  `performGlobal(action: String)` (back/home/recents/notifications), `typeText(text)` and
  `openApp(query)` — all through `dispatchGesture` / `performGlobalAction` / launching the
  package. Every one of them returns a short human result string, and must fail softly
  (return an error string instead of throwing) when the service is not connected.

### 3.2 `BubbleService : Service`

- Foreground service (`START_STICKY`), low-importance notification channel with an action to stop.
- `WindowManager` overlay `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`, draggable,
  snaps to the nearest horizontal edge on release, remembers its Y position in prefs.
- The bubble is a custom view `LobsterBubbleView` (see 3.6) with three visual states:
  `Idle`, `Thinking`, `AnswerReady` (small badge).
- Single tap → `captureScreenshot` then open `ChatActivity` (start it with the screenshot URI and
  the context string as extras; also pass them through a small in-memory `ContextHolder` object
  to avoid Binder size limits — the file is written to `cacheDir` and its path is passed).
- Long press → small popup menu anchored to the bubble: `Ouvrir la discussion`,
  `Mode contrôle : on/off`, `Réglages`, `Masquer la bulle`.
- Expose `fun setState(state)` for the chat activity to reflect thinking/answering.

### 3.3 `ChatActivity`

- Translucent, full-screen, `windowSoftInputMode=adjustResize`, dark UI, closes with back.
- Layout: header with `LobsterView` (small) + title `OpenClaw` + status line (`En ligne` / `Injoignable`),
  then a context card (app icon, app name, URL or window title, screenshot thumbnail — tap it to
  open full screen), then the message list, then the input row (text field + send button).
- The context card is sent as part of the **first** user message: text part =
  `[Contexte écran] App : <app>\nPage : <url|title>\nHeure : <HH:mm>` followed by the user question,
  and image part = the screenshot (`data:image/jpeg;base64,...`).
- Messages: user (right, coral), assistant (left, dark card), error (red outline with the reason
  and a `Réessayer` action), and a tool-activity line (`⚙︎ tap(540, 1200)`) in small caps.
- Streaming display: as SSE chunks arrive, append to the current assistant bubble.

### 3.4 `ApiClient`

- `POST {baseUrl}/v1/chat/completions`, header `Authorization: Bearer <token>`, JSON body:
  `{"model": <model>, "user": <sessionId>, "stream": true, "messages": [...]}`.
- `baseUrl` default `http://192.168.1.57:18789/v1`, `model` default `openclaw/boss`,
  `sessionId` default `android:bubbleclaw` (stable across launches, editable in settings).
- Parse SSE lines `data: {...}` → `choices[0].delta.content`; stop on `data: [DONE]`.
  Also support `choices[0].delta.tool_calls` fragments and `finish_reason`.
- Non-stream fallback when `stream` fails or is disabled in settings.
- 8 s connect timeout, 120 s read timeout. Map failures to French messages:
  timeout/unreachable → `OpenClaw injoignable. Vérifie que le gateway écoute et que le téléphone est sur le même réseau (Tailscale ou Wi-Fi).`,
  401 → `Jeton invalide.`, other → status code + short body.
- `fun listModels(): List<String>` for the settings "Tester" button.

### 3.5 Phone control (function tools, only when control mode is ON)

Send a `tools` array in the request; when `finish_reason == "tool_calls"` or tool-call deltas are
complete, execute locally, append `{"role":"tool","tool_call_id":...}` messages and re-send.
Max 6 iterations per user turn; stop with a message if the loop exceeds it.

Tool set (JSON schema, French descriptions):

- `tap(x: number, y: number)` — coordinates in **screen pixels of the captured screenshot**.
  Always include the screenshot width/height in the prompt so the model can compute coordinates.
- `swipe(x1,y1,x2,y2,durationMs)`
- `scroll(direction: "up"|"down"|"left"|"right", amount: number)`
- `type_text(text: string)` — into the focused field, via `ACTION_SET_TEXT` on the focused node, fallback to clipboard+paste.
- `press_key(key: "back"|"home"|"recents"|"enter")`
- `open_app(query: string)` — label or package; resolve with PackageManager, launch, report ambiguity.
- `screenshot()` — returns a fresh capture as a tool result, so the agent can re-read the screen after acting.

Everything runs only when `controlMode` is true **and** the accessibility service is connected.
When it is disabled the `tools` key is absent from the request entirely.

### 3.6 `LobsterView` (mascot) — important, this is the identity of the app

Custom `View` drawing a **cute cartoon lobster** with `Canvas` (no image assets): coral-red body,
two big glossy eyes with highlights, small claw arms, antennae, tail fan. Animated with a
`ValueAnimator` loop:

- `Idle`: gentle vertical bobbing (2 px), antennae swaying, eyes blinking every 3-5 s.
- `Thinking`: claws tapping alternately, eyes half-closed, three dots under it.
- `Happy`: quick double bounce + eyes as `^ ^` arcs, used when an answer arrives.

Also produce `LobsterBubbleView` = circular coral badge (48 dp) containing the lobster head,
with a subtle pulsing halo in `Thinking` and a small green dot badge in `AnswerReady`.

Requirement: it must look deliberately cute and hand-drawn, not geometric. Anti-alias everything,
support `dp` scaling, and keep it smooth (no allocations inside `onDraw`).

### 3.7 `SettingsActivity` (or a tab inside `MainActivity`)

Fields persisted in `SharedPreferences`:
`baseUrl`, `token`, `model`, `sessionId`, toggles `autoScreenshot` (default on),
`includeVisibleText` (default off), `controlMode` (default off), `bubbleEnabled` (default on).

- `Tester la connexion` → GET `/v1/models`, show the reachable targets or the error.
- Permission checklist with **live** status and a button per line:
  overlay (`Settings.canDrawOverlays`), accessibility service enabled (read
  `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`), notifications (API 33+).
- `Démarrer la bulle` / `Arrêter la bulle`.
- A short "Comment ça marche" block explaining that the whole app talks only to the configured
  gateway and that nothing leaves the device otherwise.

### 3.8 `MainActivity`

Onboarding + settings host. First launch: three-step card explaining bubble → screenshot →
conversation, then the permission checklist. Launcher icon and splash use the lobster.

## 4. Quality bar

- No `// TODO` left in a user-facing path; no dead code left behind.
- Everything network-related runs off the main thread; UI updates on the main thread.
- No crash when the accessibility service is disabled: the app must explain what is missing.
- The chat must survive rotation (keep messages in a `ViewModel` or a retained object).
- Add `README.md` at the repo root: what it does, how to configure, how to build, permissions
  requested and why, and the fact that it is a personal client for a self-hosted gateway.
- Add `dist/` output instructions in the README (`./gradlew assembleDebug`, APK path).

## 5. Phases and commits

Work in this order, committing after each step with a clear message:

1. **Structure** — package layout, manifest finalisation, theme, `ApiClient` skeleton, settings storage. Build green.
2. **Screen context** — `ScreenReaderService` (screenshot, app label, browser URL, digest). Build green.
3. **Bubble** — overlay service, drag/snap, states, long-press menu. Build green.
4. **Chat** — activity, context card, SSE streaming, errors. Build green.
5. **Control mode** — function tools + executor. Build green.
6. **Polish** — `LobsterView`/`LobsterBubbleView`, launcher icon, onboarding, animations, French copy pass. Build green.

Never leave the repo in a non-building state at the end of a phase.
Run `./gradlew assembleDebug` yourself after each phase and report the APK path.
