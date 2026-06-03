# Hermes Android Rewrite — Plan 5/5: Notifications & Events

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `notifications`, `events`, `event_stream` (SSE), and `widgets` — completing the ~37-tool surface — on a bounded, race-free event/notification store (bugs #3, #14).

**Architecture:** Process-wide `EventBus` holds two bounded, thread-safe stores. The accessibility service appends UI events; a lifecycle-managed `NotificationListenerService` appends notifications. Because the stores live in a singleton independent of any service instance, readers never hit a null instance (bug #14), and bounded append/trim happens under a lock (bug #3). `event_stream` tails the store as Server-Sent Events, outside the command executor.

**Tech Stack:** As prior plans. Adds `NotificationListenerService`, Ktor SSE (`respondTextWriter`), `AppWidgetManager`.

> **Prerequisite:** Plans 1–4 complete and green. Spec §4.2, §4.7, §6.2.
> **VCS:** jj.

---

## Task 1: Bounded, race-free stores (bugs #3, #14)

**Files:**
- Create: `event/EventStore.kt`, `event/NotificationStore.kt`, `event/EventBus.kt`
- Test: `event/EventStoreTest.kt`, `event/NotificationStoreTest.kt`

- [ ] **Step 1: Write the failing `EventStoreTest.kt`**

```kotlin
package com.hermesandroid.bridge.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class EventStoreTest {
    @Test fun appendAssignsMonotonicSeq() {
        val s = EventStore(capacity = 10)
        val a = s.append(EventRecord(0, 1L, "x", "p", "t1"))
        val b = s.append(EventRecord(0, 1L, "x", "p", "t2"))
        assertTrue(b.seq > a.seq)
    }

    @Test fun sinceReturnsOnlyNewer() {
        val s = EventStore(capacity = 10)
        val first = s.append(EventRecord(0, 1L, "x", "p", "a"))
        s.append(EventRecord(0, 1L, "x", "p", "b"))
        val newer = s.since(first.seq)
        assertEquals(listOf("b"), newer.map { it.text })
    }

    @Test fun capacityIsBounded() {
        val s = EventStore(capacity = 5)
        repeat(100) { s.append(EventRecord(0, 1L, "x", "p", "t$it")) }
        assertEquals(5, s.snapshot().size)
        assertEquals("t99", s.snapshot().last().text) // newest retained
    }

    @Test fun concurrentWritersRespectCapacityAndDontLose() {
        val s = EventStore(capacity = 200)
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(8)
        repeat(8) { w ->
            pool.execute {
                repeat(500) { s.append(EventRecord(0, 1L, "x", "p", "w$w-$it")) }
                latch.countDown()
            }
        }
        latch.await()
        pool.shutdown()
        // Never exceeds capacity; seqs are unique and strictly increasing in insertion order.
        assertTrue(s.snapshot().size <= 200)
        val seqs = s.snapshot().map { it.seq }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(seqs.toSet().size, seqs.size)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*EventStoreTest" && cd ..`
Expected: FAIL — `EventStore` / `EventRecord` unresolved.

- [ ] **Step 3: Implement `EventStore.kt`**

```kotlin
package com.hermesandroid.bridge.event

/** One accessibility/UI event. [seq] is assigned by the store on append. */
data class EventRecord(
    val seq: Long,
    val timestamp: Long,
    val type: String,
    val packageName: String?,
    val text: String?,
)

/**
 * Bounded, thread-safe event log. Append + trim happen atomically under one lock
 * (fixes the prototype's check-then-act capacity race, bug #3).
 */
class EventStore(private val capacity: Int) {
    private val lock = Any()
    private val items = ArrayDeque<EventRecord>()
    private var nextSeq = 1L

    fun append(record: EventRecord): EventRecord = synchronized(lock) {
        val stamped = record.copy(seq = nextSeq++)
        items.addLast(stamped)
        while (items.size > capacity) items.removeFirst()
        stamped
    }

    fun since(seq: Long): List<EventRecord> = synchronized(lock) {
        items.filter { it.seq > seq }
    }

    fun snapshot(): List<EventRecord> = synchronized(lock) { items.toList() }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*EventStoreTest" && cd ..`
Expected: PASS (4 tests).

- [ ] **Step 5: Write + implement `NotificationStore.kt`** (same discipline)

Test `NotificationStoreTest.kt`:
```kotlin
package com.hermesandroid.bridge.event

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationStoreTest {
    @Test fun boundedAndNewestRetained() {
        val s = NotificationStore(capacity = 3)
        repeat(5) { s.add(NotificationRecord(it.toLong(), "pkg", "title$it", "body")) }
        val snap = s.snapshot()
        assertEquals(3, snap.size)
        assertEquals("title4", snap.last().title)
    }
}
```
Implementation:
```kotlin
package com.hermesandroid.bridge.event

data class NotificationRecord(
    val postedAt: Long,
    val packageName: String,
    val title: String?,
    val text: String?,
)

/** Bounded, thread-safe notification log. */
class NotificationStore(private val capacity: Int) {
    private val lock = Any()
    private val items = ArrayDeque<NotificationRecord>()

    fun add(record: NotificationRecord) = synchronized(lock) {
        items.addLast(record)
        while (items.size > capacity) items.removeFirst()
    }

    fun snapshot(): List<NotificationRecord> = synchronized(lock) { items.toList() }
}
```

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*NotificationStoreTest" && cd ..`
Expected: PASS.

- [ ] **Step 6: Create `EventBus.kt`** (process-wide singleton; independent of service lifecycle — bug #14)

```kotlin
package com.hermesandroid.bridge.event

/** Process-wide stores. Always present, so readers never see a null service instance. */
object EventBus {
    val events = EventStore(capacity = 500)
    val notifications = NotificationStore(capacity = 200)
}
```

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat: add bounded race-free event and notification stores"
```

---

## Task 2: Feed the stores (accessibility events + notification listener)

**Files:**
- Modify: `accessibility/BridgeAccessibilityService.kt`, `command/Command.kt`,
  `AndroidManifest.xml`
- Create: `notifications/BridgeNotificationListener.kt`
- (device code; validated on-device)

- [ ] **Step 1: Append accessibility events** — replace the empty `onAccessibilityEvent` in
  `BridgeAccessibilityService`:

```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val e = event ?: return
    com.hermesandroid.bridge.event.EventBus.events.append(
        com.hermesandroid.bridge.event.EventRecord(
            seq = 0,
            timestamp = System.currentTimeMillis(),
            type = AccessibilityEvent.eventTypeToString(e.eventType),
            packageName = e.packageName?.toString(),
            text = e.text?.joinToString(" ")?.ifBlank { null },
        )
    )
}
```

- [ ] **Step 2: Write `BridgeNotificationListener.kt`** (lifecycle-managed; writes to EventBus)

```kotlin
package com.hermesandroid.bridge.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hermesandroid.bridge.event.EventBus
import com.hermesandroid.bridge.event.NotificationRecord
import java.util.concurrent.atomic.AtomicReference

class BridgeNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() { ref.set(this) }
    override fun onListenerDisconnected() { ref.compareAndSet(this, null) }
    override fun onDestroy() { ref.compareAndSet(this, null); super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val extras = n.notification.extras
        EventBus.notifications.add(
            NotificationRecord(
                postedAt = n.postTime,
                packageName = n.packageName,
                title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
                text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
            )
        )
    }

    companion object {
        // Kept for parity/observability; readers use EventBus, not this reference (bug #14).
        private val ref = AtomicReference<BridgeNotificationListener?>(null)
        fun isConnected(): Boolean = ref.get() != null
    }
}
```

- [ ] **Step 3: Register the listener** in `AndroidManifest.xml` (inside `<application>`)

```xml
        <service
            android:name=".notifications.BridgeNotificationListener"
            android:exported="false"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
```

- [ ] **Step 4: Add `Command` variants** to `Command.kt`

```kotlin
    data object Notifications : Command
    data class Events(val since: Long) : Command
    data object Widgets : Command
```

- [ ] **Step 5: Handle them** in `BridgeAccessibilityService.handle`

```kotlin
        is Command.Notifications -> CommandResult.Ok(mapOf("notifications" to
            com.hermesandroid.bridge.event.EventBus.notifications.snapshot().map {
                mapOf("package" to it.packageName, "title" to it.title, "text" to it.text, "posted_at" to it.postedAt)
            }))
        is Command.Events -> CommandResult.Ok(mapOf("events" to
            com.hermesandroid.bridge.event.EventBus.events.since(command.since).map {
                mapOf("seq" to it.seq, "type" to it.type, "package" to it.packageName, "text" to it.text, "timestamp" to it.timestamp)
            }))
        is Command.Widgets -> {
            val mgr = android.appwidget.AppWidgetManager.getInstance(this)
            val list = try {
                mgr.installedProviders.map {
                    mapOf("label" to it.loadLabel(packageManager), "package" to it.provider.packageName)
                }
            } catch (e: Exception) { emptyList() }
            CommandResult.Ok(mapOf("widgets" to list))
        }
```

- [ ] **Step 6: Verify compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin && cd ..`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat: feed event/notification stores and handle notifications/events/widgets"
```

---

## Task 3: Routes (incl. SSE), client, tools

**Files:**
- Modify: `server/BridgeServer.kt`, `plugin/client.py`, `plugin/tools.py`
- Test: `tests/test_events.py`

- [ ] **Step 1: Add the simple routes** in `BridgeServer`

```kotlin
                get("/notifications") { call.guarded { BridgeAccessibilityService.current()!!.submit(Command.Notifications) } }
                get("/events") {
                    val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.Events(since)) }
                }
                get("/widgets") { call.guarded { BridgeAccessibilityService.current()!!.submit(Command.Widgets) } }
```

- [ ] **Step 2: Add the SSE route** (reads the store directly — outside the executor)

```kotlin
                get("/events/stream") {
                    val ip = call.request.local.remoteHost
                    when (authenticator.authenticate(ip, call.request.headers["Authorization"])) {
                        AuthResult.Blocked -> call.respond(io.ktor.http.HttpStatusCode.TooManyRequests, mapOf("ok" to false, "error" to "blocked"))
                        AuthResult.Unauthorized -> call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("ok" to false, "error" to "unauthorized"))
                        AuthResult.Ok -> {
                            call.respondTextWriter(contentType = io.ktor.http.ContentType.Text.EventStream) {
                                var lastSeq = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                                try {
                                    while (true) {
                                        val batch = com.hermesandroid.bridge.event.EventBus.events.since(lastSeq)
                                        for (e in batch) {
                                            lastSeq = e.seq
                                            write("data: ${gson.toJson(e)}\n\n")
                                            flush()
                                        }
                                        kotlinx.coroutines.delay(500)
                                    }
                                } catch (e: Exception) {
                                    // client disconnected — end the stream
                                }
                            }
                        }
                    }
                }
```

> The SSE route does **not** go through `guarded`/the command executor: it is a
> read-only tail of `EventBus`, so a long-lived stream never blocks UI commands
> (spec §4.2). Auth is still enforced inline.

- [ ] **Step 3: Add client methods** to `plugin/client.py`

```python
    async def notifications(self):
        return await self._request("GET", "/notifications")

    async def events(self, since=0):
        return await self._request("GET", "/events", params={"since": since})

    async def widgets(self):
        return await self._request("GET", "/widgets")

    async def event_stream(self, since=0):
        """Yield event dicts from the SSE stream until cancelled."""
        import json as _json
        async with self._client.stream("GET", "/events/stream", params={"since": since}) as resp:
            if resp.status_code == 401:
                from .client import BridgeError
                raise BridgeError("unauthorized", "bad or missing token", 401)
            async for line in resp.aiter_lines():
                if line.startswith("data: "):
                    yield _json.loads(line[len("data: "):])
```

- [ ] **Step 4: Add tools + schemas** to `plugin/tools.py`

```python
async def android_notifications(client):
    """Read current notifications."""
    return await _run(client.notifications())

async def android_events(client, since=0):
    """Read recent accessibility events newer than `since` (a seq number)."""
    return await _run(client.events(since=since))

async def android_widgets(client):
    """List installed home-screen widget providers."""
    return await _run(client.widgets())

async def android_event_stream(client, since=0, limit=20):
    """Collect up to `limit` events from the live stream, then return them."""
    collected = []
    try:
        async for event in client.event_stream(since=since):
            collected.append(event)
            if len(collected) >= limit:
                break
    except Exception as e:
        return {"ok": False, "error": "stream_error", "message": str(e)}
    return {"ok": True, "data": {"events": collected}}
```
```python
    {"name": "android_notifications", "description": "Read current notifications.",
     "parameters": {"type": "object", "properties": {}, "required": []}, "handler": android_notifications},
    {"name": "android_events", "description": "Read recent accessibility events since a seq.",
     "parameters": {"type": "object", "properties": {"since": {"type": "integer", "default": 0}},
         "required": []}, "handler": android_events},
    {"name": "android_widgets", "description": "List installed home-screen widget providers.",
     "parameters": {"type": "object", "properties": {}, "required": []}, "handler": android_widgets},
    {"name": "android_event_stream", "description": "Collect events from the live stream.",
     "parameters": {"type": "object", "properties": {
         "since": {"type": "integer", "default": 0}, "limit": {"type": "integer", "default": 20}},
         "required": []}, "handler": android_event_stream},
```

- [ ] **Step 5: Write + run `tests/test_events.py`**

```python
from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=10.0, max_bytes=1_000_000)


async def test_events_passes_since(httpx_mock):
    httpx_mock.add_response(json={"ok": True, "data": {"events": []}})
    client = AndroidClient(cfg())
    await tools.android_events(client, since=42)
    assert "since=42" in str(httpx_mock.get_requests()[0].url)
    await client.aclose()


async def test_notifications_returns_list(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/notifications",
                            json={"ok": True, "data": {"notifications": [{"package": "com.x", "title": "Hi"}]}})
    client = AndroidClient(cfg())
    r = await tools.android_notifications(client)
    assert r["data"]["notifications"][0]["title"] == "Hi"
    await client.aclose()


async def test_event_stream_collects_until_limit(httpx_mock):
    body = "data: {\"seq\": 1, \"text\": \"a\"}\n\ndata: {\"seq\": 2, \"text\": \"b\"}\n\n"
    httpx_mock.add_response(url="http://phone:8765/events/stream?since=0",
                            text=body, headers={"Content-Type": "text/event-stream"})
    client = AndroidClient(cfg())
    r = await tools.android_event_stream(client, since=0, limit=2)
    assert r["ok"] is True
    assert [e["seq"] for e in r["data"]["events"]] == [1, 2]
    await client.aclose()
```

Run: `python -m pytest tests/test_events.py -q`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat: add notifications, events, event_stream (SSE), widgets tools"
```

---

## Task 4: Final wiring, docs, and full-suite verification

**Files:**
- Modify: `plugin/skill.md`, `README.md` (create), `ui/MainActivity.kt`

- [ ] **Step 1: Add a notification-access shortcut** to `MainActivity.onCreate`

```kotlin
        findViewById<Button>(R.id.btnAccessibility).setOnLongClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            true
        }
```
> Long-press the accessibility button to open Notification access settings (where the
> user enables `BridgeNotificationListener`). Document this in the README.

- [ ] **Step 2: Update `plugin/skill.md`** tool list to the full ~37-tool set

Append the remaining tools under "## Tools" so the agent's skill doc is complete:
`android_screenshot`, `android_screen_record`, `android_swipe`, `android_scroll`,
`android_long_press`, `android_drag`, `android_pinch`, `android_tap_text`,
`android_find_nodes`, `android_describe_node`, `android_screen_hash`,
`android_diff_screen`, `android_open_app`, `android_press_key`, `android_current_app`,
`android_get_apps`, `android_wait`, `android_clipboard_read`, `android_clipboard_write`,
`android_send_intent`, `android_broadcast`, `android_send_sms`, `android_call`,
`android_search_contacts`, `android_location`, `android_media`, `android_speak`,
`android_speak_stop`, `android_notifications`, `android_events`, `android_event_stream`,
`android_widgets`.

- [ ] **Step 3: Write `README.md`** (top-level project doc)

```markdown
# hermes-android-x

Give your Hermes agent hands on a real Android phone — over your LAN/VPN, no relay.

## How it works
The phone runs a token-authenticated HTTP server (`0.0.0.0:8765`); the agent connects
directly. All actions serialize through one command queue for reliability.

## Setup
1. Build/install the app (`mise run build-apk`; `adb install ...`).
2. Enable the accessibility service; optionally grant SMS/Call/Contacts/Location,
   screen recording, and Notification access.
3. Tap **Start bridge**; copy the URL + token shown.
4. Set `ANDROID_BRIDGE_URL` and `ANDROID_BRIDGE_TOKEN` in the agent env.
5. `android_ping` to confirm.

## Develop
- `mise run test-py` — Python unit tests
- `mise run test-android` — Kotlin unit tests
- `mise run test-device` — instrumented tests on a connected phone
- `mise run build-apk` — assemble the debug APK

Trusted networks only: traffic is plaintext HTTP, secured by the token + your LAN/VPN.
```

- [ ] **Step 4: Full verification**

Run:
```bash
python -c "from plugin.tools import TOOL_SCHEMAS; print(len(TOOL_SCHEMAS))"
mise run test-py
mise run test-android
mise run build-apk
```
Expected: schema count `36`; all Python tests PASS; all Kotlin unit tests PASS; APK builds.

> Tool tally: Plan 1 (4) + Plan 2 (15) + Plan 3 (2) + Plan 4 (11) + Plan 5 (4) = **36**
> `android_*` tools. (`ping`, `read_screen`, `tap`, `type`, `tap_text`, `long_press`,
> `drag`, `pinch`, `swipe`, `scroll`, `find_nodes`, `describe_node`, `screen_hash`,
> `diff_screen`, `open_app`, `press_key`, `current_app`, `get_apps`, `wait`,
> `screenshot`, `screen_record`, `clipboard_read`, `clipboard_write`, `send_intent`,
> `broadcast`, `send_sms`, `call`, `search_contacts`, `location`, `media`, `speak`,
> `speak_stop`, `notifications`, `events`, `event_stream`, `widgets`.)

- [ ] **Step 5: On-device verification** (phone via ADB)

With accessibility + notification access enabled: `mise run test-device`, then drive
`android_events`, `android_notifications` (trigger a real notification), and
`android_event_stream` and confirm live data.

- [ ] **Step 6: Commit**

```bash
jj commit -m "docs: complete skill/readme and finalize 36-tool surface"
```

---

## What Plan 5 delivers

The final four tools on a bounded, race-free store: notifications and UI events are
captured independently of service lifecycle (bug #14), append/trim is atomic (bug #3),
and `event_stream` tails events as SSE without blocking the command queue. The rewrite
is complete — **36 `android_*` tools**, every prototype bug fixed and regression-tested
across the three test tiers.

## Bug-fix ledger (all 14)

| # | Bug | Fixed in |
|---|-----|----------|
| 1 | Node leak on exception | Plan 1 (RAII helpers) |
| 2 | asyncio.Lock pre-loop | Plan 1 (no lock in `__init__`) |
| 3 | EventStore check-then-act race | Plan 5 (locked append/trim) |
| 4 | RelayClient reconnect race | Plan 1 (relay deleted) |
| 5 | No auth when token unset | Plan 1 (mandatory token) |
| 6 | Foreground service not promoted | Plan 1 (`startForeground`) |
| 7 | WakeLock 10s/deprecated | Plan 1 (per-command partial lock) |
| 8 | Activity leak via singleton | Plan 1 (no view-holding singletons) |
| 9 | Futures leaked on timeout | Plan 1 (relay deleted; httpx native) |
| 10 | type() false success | Plan 1 (`no_focused_field`) |
| 11 | Unbounded payload OOM | Plan 1 (`MAX_BYTES` cap) |
| 12 | Temp files never deleted | Plan 3 (`MediaStore` cleanup) |
| 13 | 32-bit hash collisions | Plan 1 (64-bit content hash) |
| 14 | Listener null races | Plan 5 (lifecycle-independent `EventBus`) |