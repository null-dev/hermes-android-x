# Hermes Android — Relay-Free Rewrite Design

> **Status:** Approved design, pre-implementation.
> **Date:** 2026-06-03
> **Supersedes:** the relay-based `hermes-android` prototype.

A ground-up rewrite of the bridge that lets a Hermes agent control an Android
phone. The relay is eliminated: the phone runs an HTTP server bound to all
interfaces, and the agent connects to it directly over a trusted LAN/VPN.

---

## 1. Goals & Non-Goals

### Goals
- **Eliminate the relay.** The phone listens directly; the agent dials the phone.
- **Reliability first.** Every bug found in the prototype review (see §9) has a
  named fix and a regression test.
- **Full tool parity.** Reimplement the ~37 `android_*` tools reliably.
- **Mandatory authentication.** The server cannot run without a token. The
  "no auth when token unset" failure mode is made structurally impossible.
- **Survive in the background.** A promoted foreground service keeps the bridge
  alive on Android 12+ under battery optimization.
- **Testable.** Three tiers: mock/unit (CI), instrumented on-device (real phone
  over ADB), and a short manual smoke checklist.
- **mise for all tooling.**

### Non-Goals
- **NAT traversal / public-internet exposure.** Reachability is the user's
  responsibility (same LAN, or an overlay VPN such as Tailscale/WireGuard). We
  bind `0.0.0.0` and document "trusted network only."
- **TLS / encryption.** Plaintext HTTP. Confidentiality is delegated to the
  LAN/VPN. (A future `https` toggle is possible but out of scope.)
- **Auto-discovery (mDNS).** The user copies the URL + token from the app screen
  into the agent's env. Out of scope for v1.
- **Multi-device, scheduling, on-device models, iOS.** Future roadmap, not here.

---

## 2. Topology & Data Flow

```
┌─────────────────────────┐         ┌────────────────────────────────────┐
│ Hermes Agent (server)   │         │ Android phone (same LAN / VPN)       │
│                         │  HTTP   │                                      │
│ android_* tools         │ ──────▶ │ Ktor server  :8765  (bind 0.0.0.0)   │
│  └ AndroidClient        │ ◀────── │  └ Auth filter (mandatory token)     │
│    (httpx, async)       │  JSON   │  └ Router → CommandExecutor (actor)  │
└─────────────────────────┘         │       └ AccessibilityService         │
                                     │  Foreground service (persistent)     │
                                     └────────────────────────────────────┘
```

**One request, one response.** No envelopes with request-id correlation, no
WebSocket, no reconnect logic, no pending-future map. A tool call is a plain
synchronous HTTP request straight to the phone.

### Data flow for a tool call
1. Agent calls e.g. `android_tap(x, y)`.
2. `AndroidClient` issues `POST http://<phone>:8765/tap` with
   `Authorization: Bearer <token>` and a JSON body.
3. Ktor auth filter checks the token (constant-time); 401 if absent/wrong.
4. Router validates the body and submits a `Command` to the `CommandExecutor`
   channel.
5. The executor runs it on the single accessibility dispatcher and returns a
   typed result.
6. Ktor serializes the result to JSON; the client parses it.

### Discovery / setup
The app screen displays its reachable URL (`http://<lan-ip>:8765`) and the token.
The user puts both into the agent's environment (`ANDROID_BRIDGE_URL`,
`ANDROID_BRIDGE_TOKEN`). There is **no `android_setup` tool** and no pairing
handshake. `android_ping` is the connectivity check.

---

## 3. Repository Structure

```
hermes-android-x/
├── .mise.toml                     # java, kotlin, gradle, python (fixed JAVA_HOME) + tasks
├── android/                       # Kotlin app
│   └── app/src/
│       ├── main/kotlin/com/hermesandroid/bridge/
│       │   ├── server/         # Ktor server, routes, auth filter, rate limiter
│       │   ├── command/        # CommandExecutor actor, Command/Result types
│       │   ├── accessibility/  # service, ScreenReader, ActionExecutor, node RAII
│       │   ├── capture/        # screenshot, screen record (MediaProjection)
│       │   ├── system/         # apps, intents, clipboard, sms, call, contacts,
│       │   │                   #   location, media, tts
│       │   ├── notifications/  # listener + bounded store
│       │   ├── lifecycle/      # foreground service, wakelock
│       │   └── ui/             # MainActivity: status, URL, token, permission shortcuts
│       ├── test/               # JVM unit tests (+ Robolectric)
│       └── androidTest/        # instrumented on-device tests (real phone via ADB)
├── plugin/                        # Python hermes-agent plugin
│   ├── __init__.py             # register(ctx)
│   ├── client.py               # async AndroidClient (httpx)
│   ├── tools.py                # android_* handlers + schemas
│   ├── plugin.yaml
│   └── skill.md
├── tests/                         # pytest (python unit + device-marked e2e)
├── docs/superpowers/specs/        # this spec, plus the implementation plan
└── .github/workflows/             # CI: python + kotlin unit tests, assembleDebug
```

Single source of truth: there is **no** duplicate `tools/` directory (the
prototype carried two copies of the Python code — the rewrite has one).

---

## 4. Android App Internals

### 4.1 Foreground service & lifecycle
A single `BridgeForegroundService` is the app's spine. When the user enables the
bridge it is started with `startForegroundService()` + `startForeground(notif)`,
posting a persistent low-priority notification
("Hermes Bridge active — `http://<ip>:8765`"). It owns the Ktor server lifecycle
and a partial `WakeLock`. This guarantees survival on Android 12+ under battery
optimization — the prototype declared `FOREGROUND_SERVICE` but never promoted the
service, so the OS killed it on backgrounding.

### 4.2 CommandExecutor actor (the core of the design)
A single coroutine consuming a `Channel<Command>` on a dedicated single-thread
dispatcher. Every accessibility read/mutate is a `Command` returning a typed
`Result`. Because there is exactly one consumer, concurrent mutation of
accessibility state is impossible — this structurally eliminates the prototype's
EventStore capacity race, reconnect race, and node-recycle races. Each command
carries its own timeout: a slow command yields a timeout error result and the
queue keeps draining; it cannot wedge forever.

`/events/stream` (SSE) is the **only** exception: it is a read-only tail of the
bounded event store and runs outside the actor so it never blocks UI commands.

### 4.3 AccessibilityNodeInfo lifetime (RAII)
Two inline helpers enforce recycling:
- `useNode(node) { ... }` — recycles the node in a `finally`, even on throw.
- `withWindows { ... }` — recycles window/root lists even when `buildNode()`
  throws.

`ScreenReader` and `ActionExecutor` never hand a raw node to a path that can leak
it. Enforced by convention + review, and covered by recycle unit tests.

### 4.4 ScreenReader
Builds an immutable `ScreenNode` tree snapshot inside the executor, then releases
all native nodes before returning. Node hashing for `screen_hash` / `diff_screen`
uses a **stable 64-bit content hash** (FNV-1a or truncated SHA-256 over
normalized text + class + bounds) — not `Object.hashCode()`, which is 32-bit and
collided, causing false "no change" diffs.

`node_id` is a stable identifier assigned during a `/screen` read (index path or
content hash), valid until the screen changes, so `tap(node_id)` is meaningful
across a read→act round trip.

### 4.5 ActionExecutor
Tap (coord/node), long-press, drag, pinch, swipe, scroll, type, key press, app
launch, wait-for-element. Gesture dispatch uses `dispatchGesture` with completion
callbacks bridged back to the executor. `type` **fails loudly** when no field is
focused — returns `{ok:false, error:"no_focused_field"}` instead of the
prototype's silent false-success.

### 4.6 WakeLock
A single **partial** wake lock acquired around each command and released in a
`finally` — no fixed 10s timeout, no deprecated `SCREEN_BRIGHT_WAKE_LOCK`. Held
only for the command's duration plus a small grace.

### 4.7 Capture, system, notifications
- **Screenshot:** `takeScreenshot` API (Android 11+), `MediaProjection` fallback.
- **Screen record:** `MediaProjection` → MP4.
- **System tools** (apps, intents, clipboard, SMS, call, contacts, location,
  media, TTS): focused single-purpose classes. Each checks capability and returns
  a graceful `unsupported` error on hardware that lacks it (e.g. Android
  Automotive). No tool fabricates success.
- **Notifications:** the listener writes to a bounded store guarded by the same
  single-consumer discipline (a `Channel`/actor), not a raced deque. Readers
  tolerate the listener being absent (503), never crash on a null instance.

---

## 5. Python Toolset

### 5.1 `AndroidClient` (async, httpx)
A thin async wrapper around one `httpx.AsyncClient`. Fixes the prototype's async
bugs:
- **No `asyncio.Lock` in `__init__`.** The client holds no lock at construction;
  httpx is concurrency-safe for separate requests. Any needed synchronization is
  created lazily inside the running loop. Kills the
  `RuntimeError: no current event loop` crash.
- **No pending-future map, no manual cancellation.** With request/response HTTP,
  httpx handles timeouts and cancellation natively. The "future leaked on
  timeout / second waiter hangs" class disappears with the relay.
- **Mandatory token.** Every request sends `Authorization: Bearer <token>`. If
  `ANDROID_BRIDGE_TOKEN` is unset, the client raises a clear configuration error
  at first use — never a silent unauthenticated request.
- **Bounded response size.** Responses read with a max-bytes cap
  (`ANDROID_BRIDGE_MAX_BYTES`, default 32 MiB); oversized bodies raise instead of
  buffering unbounded — fixes the OOM risk from a misbehaving phone.
- **Sane per-request timeouts**, with a longer cap for `screen_record` / `wait`.

### 5.2 `tools.py` — the ~37 `android_*` handlers
Each tool: validate args → call one `AndroidClient` method → map the phone's typed
result into the agent's tool-result shape. Media tools (`screenshot`,
`screen_record`) write to a `tempfile`-managed path under a session temp dir that
is removed on teardown — fixes the `/tmp` leak. Phone-side `{ok:false}` is
surfaced as a tool error carrying the phone's reason; no tool fabricates success.

### 5.3 `__init__.py` — plugin registration
`register(ctx)` registers all tools via the hermes-agent plugin API, same
contract as the prototype. No legacy duplicate directory.

### 5.4 Config (environment variables only)

| Var | Default | Purpose |
|-----|---------|---------|
| `ANDROID_BRIDGE_URL` | *(required)* | `http://<phone-ip>:8765` |
| `ANDROID_BRIDGE_TOKEN` | *(required)* | bearer token shown in the app |
| `ANDROID_BRIDGE_TIMEOUT` | `30` | per-request seconds |
| `ANDROID_BRIDGE_MAX_BYTES` | `33554432` | response size cap (32 MiB) |

Missing `URL` or `TOKEN` → clear startup error, never a silent no-op.

---

## 6. HTTP API Surface

### 6.1 Conventions
All requests carry `Authorization: Bearer <token>`. Bodies and responses are JSON.
One envelope shape:

```jsonc
// success
{ "ok": true, "data": { ... } }
// app-level failure (HTTP 200 — the action ran and failed)
{ "ok": false, "error": "no_focused_field", "message": "No input field is focused" }
```

Transport-level problems use real HTTP status codes, separating "couldn't accept
your call" from "the action ran and failed":

| Code | Meaning |
|------|---------|
| 401 | missing / wrong token |
| 400 | malformed body |
| 404 | unknown path |
| 408 | command timeout |
| 413 | request too large |
| 503 | accessibility service not enabled |

### 6.2 Endpoints (~37 tools)

| Method · Path | Tool(s) | Body / params |
|---|---|---|
| `GET /ping` | `android_ping` | → `{device, android_version, service_enabled}` |
| `GET /screen` | `android_read_screen` | `?bounds=true` |
| `GET /screenshot` | `android_screenshot` | → PNG (base64 in envelope) |
| `GET /apps` | `android_get_apps` | — |
| `GET /current_app` | `android_current_app` | — |
| `POST /tap` | `android_tap` | `{x,y}` or `{node_id}` |
| `POST /tap_text` | `android_tap_text` | `{text, exact}` |
| `POST /long_press` | `android_long_press` | `{x,y}` or `{node_id}` |
| `POST /drag` | `android_drag` | `{from:{x,y}, to:{x,y}, duration_ms}` |
| `POST /pinch` | `android_pinch` | `{x,y,scale}` |
| `POST /type` | `android_type` | `{text, clear_first}` |
| `POST /swipe` | `android_swipe` | `{direction, distance}` |
| `POST /scroll` | `android_scroll` | `{direction, node_id?}` |
| `POST /open_app` | `android_open_app` | `{package}` |
| `POST /press_key` | `android_press_key` | `{key}` |
| `POST /wait` | `android_wait` | `{text?, class?, timeout_ms}` |
| `GET /find_nodes` | `android_find_nodes` | `?text=&class=&clickable=` |
| `GET /describe_node` | `android_describe_node` | `?node_id=` |
| `GET /screen_hash` | `android_screen_hash` | — |
| `POST /diff_screen` | `android_diff_screen` | `{hash}` |
| `GET /location` | `android_location` | — |
| `GET /contacts` | `android_search_contacts` | `?q=` |
| `POST /sms` | `android_send_sms` | `{number, text}` |
| `POST /call` | `android_call` | `{number}` |
| `POST /media` | `android_media` | `{action}` |
| `POST /intent` | `android_send_intent` | `{action, data, extras}` |
| `POST /broadcast` | `android_broadcast` | `{action, extras}` |
| `GET /clipboard` · `POST /clipboard` | `android_clipboard_read` / `_write` | `{text}` |
| `GET /notifications` | `android_notifications` | — |
| `GET /events` | `android_events` | `?since=` |
| `GET /events/stream` | `android_event_stream` | SSE stream |
| `POST /screen_record` | `android_screen_record` | `{duration_ms}` → MP4 |
| `GET /widgets` | `android_read_widgets` | — |
| `POST /speak` · `POST /speak/stop` | `android_speak` / `_stop` | `{text}` |

---

## 7. Security

- **Mandatory token, phone side.** The server **refuses to start without a
  token.** On first enable, if none is set, the app generates a high-entropy
  random token (160-bit, base32) and persists it. The user can regenerate it from
  the UI (restarts the server).
- **Constant-time compare** of the bearer token in the auth filter. "No token
  configured" cannot mean "open access" — that state cannot exist.
- **Rate-limited failures:** N failed auths per IP per window → temporary block,
  with periodic cleanup of stale records. (Carried from the prototype — the one
  well-designed security piece — but now guarding a mandatory gate.)
- **Bind `0.0.0.0`** intentionally (LAN/VPN), documented as "trusted network
  only." Plaintext HTTP; confidentiality delegated to the network.

---

## 8. Error Philosophy

- Transport failures → real HTTP status codes (§6.1).
- Action-ran-but-failed → `{ok:false}` with a machine-readable `error` and a
  human `message`.
- The agent always learns the truth. No tool, client, or handler ever fabricates
  success.

---

## 9. Reliability: prototype bugs → fixes (regression-tested)

Each row gets a named regression test (see §10).

| # | Prototype bug | Fix |
|---|---|---|
| 1 | ScreenReader node leak on exception | `useNode{}` / `withWindows{}` RAII, recycle in `finally` |
| 2 | `asyncio.Lock` created pre-loop | No locks in `__init__`; httpx client is loop-safe |
| 3 | EventStore non-atomic capacity check | Single-consumer store via actor/Channel; no check-then-act |
| 4 | RelayClient reconnect race | Relay deleted entirely |
| 5 | No auth when token unset | Server won't start without a token; mandatory gate |
| 6 | Foreground service never promoted | `startForeground()` at enable; persistent notification |
| 7 | WakeLock 10s hard timeout / deprecated flag | Partial lock, per-command scope, released in `finally` |
| 8 | MainActivity leaked via singleton listener | No long-lived singleton holding views; lifecycle-scoped, clearable callbacks |
| 9 | Futures leaked on timeout | Relay deleted; httpx native timeout/cancel |
| 10 | `type()` false success | `{ok:false,"no_focused_field"}` when nothing focused |
| 11 | Unbounded payload OOM | `MAX_BYTES` cap on client; size limits on server |
| 12 | Temp files never deleted | `tempfile` + session cleanup on teardown |
| 13 | 32-bit hashCode collisions | Stable 64-bit+ content hash |
| 14 | NotificationListener.instance null races | Lifecycle-managed reference; readers get 503, not a crash |

---

## 10. Testing Strategy

Three tiers. CI runs tier 1 always; tier 2 runs on demand against a real phone
connected over ADB; tier 3 is a short manual checklist.

### Tier 1 — Mock / unit (CI, always)
**Python (TDD, pytest)** against a fake phone server (`pytest-httpx` or a local
`http.server` fixture returning canned envelopes):
- `AndroidClient`: token always sent; missing token raises config error; timeout
  maps to a clean error; response over `MAX_BYTES` raises; 401/400/408/413/503
  each map to the right tool error.
- Each `android_*` tool: arg validation, correct path/body, success mapping,
  `{ok:false}` surfaced as a tool error, media tools write to a temp path **and
  clean it up** (assert no leftover files).
- Regression tests named for §9 bugs (e.g.
  `test_no_lock_created_outside_event_loop`, `test_missing_token_raises`).

**Kotlin (JVM unit + Robolectric):**
- `ScreenNode` content-hash stability & collision resistance (bug 13).
- `CommandExecutor`: serialization (one-at-a-time), per-command timeout → error
  result, queue keeps draining after a failure (bug 3).
- Node RAII helpers recycle even when the block throws (bug 1).
- Auth filter: constant-time compare; reject missing/empty/wrong token; rate
  limit (bug 5).
- Bounded event/notification store: no loss under concurrent writers, cap
  respected (bugs 3, 14).
- `type()` returns `no_focused_field` when focus is null (bug 10).

### Tier 2 — Instrumented on-device (`mise run test:device`, real phone via ADB)
`android/app/src/androidTest/` `connectedAndroidTest` suite, run against a real
device the user connects over ADB. Not in CI.
- **End-to-end HTTP round trip:** launch the real Ktor server on-device, hit
  `http://localhost:8765` (via `adb forward` or in-process), assert the auth gate
  (401 without token, 200 with) and that `/ping`, `/screen`, `/tap`, `/type`
  produce real accessibility effects.
- **Accessibility reality checks:** open a known app (e.g. Settings); `read_screen`
  returns a non-trivial tree; `tap_text` on a real label navigates; `type` into a
  real focused field inserts text; `no_focused_field` returned when nothing is
  focused.
- **Resource-leak soak:** N hundred `read_screen` / `tap` cycles; assert no
  `AccessibilityNodeInfo` leak warnings and stable memory (real validation of the
  RAII fixes, bug 1).
- **Lifecycle:** background the app; confirm the foreground service survives and
  the server still answers (bug 6).

**Python real-phone tier:** `pytest -m device` e2e tests that hit an actual phone
at `ANDROID_BRIDGE_URL` (skipped unless the env var points at a live device) — the
true integration test of client + tools + phone together.

### Tier 3 — Manual smoke checklist
For hardware bits not worth automating: MediaProjection consent prompt, screen
record output, TTS audio output, SMS/call on a phone with a SIM.

---

## 11. Tooling (mise)

- **Fix `.mise.toml` first.** `JAVA_HOME` currently uses an undefined
  `{{env.MISE_DATA_DIR}}` template, so every mise command errors. Replace with a
  portable form (mise's `java` tool sets `JAVA_HOME` automatically; drop the
  manual `[env]` override or use the documented data-dir variable).
- Pinned tools: `java 21`, `kotlin 2.0`, `gradle 8.6`, `python 3.12`.
- **mise tasks:** `test:py`, `test:android` (JVM unit), `test:device`
  (connected), `build:apk`, `lint`.
- **CI** (`.github/workflows`): Python tests + Kotlin unit tests on every push;
  Gradle `assembleDebug` to catch compile breakage; debug APK published to a
  `latest-build` release.

---

## 12. Risks & Open Questions

- **Screenshot API availability.** `takeScreenshot` is Android 11+; older devices
  fall back to `MediaProjection`, which requires a consent prompt. Acceptable;
  documented in the manual checklist.
- **In-process server testing.** Running Ktor inside an instrumentation process
  vs. reaching it via `adb forward` — the plan should pick one early.
- **SSE through agent tooling.** `android_event_stream` returns a stream; confirm
  the hermes-agent tool API can express a streaming/long-poll result, else fall
  back to `android_events?since=` polling.
- **node_id stability window.** Defined as valid until the screen changes; rapid
  UI churn between read and act will invalidate ids — tools should return a clear
  "stale node" error rather than tapping the wrong element.
