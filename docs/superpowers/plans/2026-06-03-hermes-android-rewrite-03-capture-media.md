# Hermes Android Rewrite — Plan 3/5: Capture & Media

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `screenshot` and `screen_record`, with media bytes returned in the envelope and the Python side writing them to a managed temp dir that is always cleaned up (bug #12).

**Architecture:** Capture runs through the `CommandExecutor` like every other command. The Android capture (accessibility `takeScreenshot`, `MediaProjection` recording) is device code validated on-device; the Python media handling (base64 decode → managed temp file → cleanup) is pure and unit-tested.

**Tech Stack:** As Plans 1–2. Adds `MediaProjection`, `MediaRecorder`, `java.util.Base64`.

> **Prerequisite:** Plans 1–2 complete and green. Spec §4.7, §6.2.
> **VCS:** jj. Uses the 5-layer pattern from Plan 2.

---

## Task 1: Managed temp files (bug #12)

**Files:**
- Create: `plugin/media.py`
- Test: `tests/test_media.py`

The prototype wrote screenshots/recordings to `/tmp` and never deleted them. A
`MediaStore` owns one per-process temp dir and removes everything on `cleanup()`.

- [ ] **Step 1: Write the failing test**

```python
import base64
import os

from plugin.media import MediaStore


def test_writes_and_reads_back():
    store = MediaStore()
    try:
        path = store.write_bytes(b"hello", suffix=".png")
        assert os.path.exists(path)
        with open(path, "rb") as f:
            assert f.read() == b"hello"
    finally:
        store.cleanup()


def test_write_base64():
    store = MediaStore()
    try:
        path = store.write_base64(base64.b64encode(b"abc").decode(), suffix=".mp4")
        with open(path, "rb") as f:
            assert f.read() == b"abc"
    finally:
        store.cleanup()


def test_cleanup_removes_all_files_and_dir():
    store = MediaStore()
    p1 = store.write_bytes(b"a", suffix=".png")
    p2 = store.write_bytes(b"b", suffix=".png")
    root = store.root
    assert os.path.exists(p1) and os.path.exists(p2)
    store.cleanup()
    assert not os.path.exists(p1)
    assert not os.path.exists(p2)
    assert not os.path.exists(root)


def test_cleanup_is_idempotent():
    store = MediaStore()
    store.write_bytes(b"a", suffix=".png")
    store.cleanup()
    store.cleanup()  # must not raise
```

- [ ] **Step 2: Run to verify it fails**

Run: `python -m pytest tests/test_media.py -q`
Expected: FAIL — `plugin.media` import error.

- [ ] **Step 3: Implement `plugin/media.py`**

```python
import base64
import os
import shutil
import tempfile
import uuid


class MediaStore:
    """Owns a per-process temp directory for media; cleanup() removes it entirely."""

    def __init__(self) -> None:
        self.root = tempfile.mkdtemp(prefix="hermes-android-")

    def write_bytes(self, data: bytes, suffix: str = "") -> str:
        name = f"{uuid.uuid4().hex}{suffix}"
        path = os.path.join(self.root, name)
        with open(path, "wb") as f:
            f.write(data)
        return path

    def write_base64(self, b64: str, suffix: str = "") -> str:
        return self.write_bytes(base64.b64decode(b64), suffix=suffix)

    def cleanup(self) -> None:
        shutil.rmtree(self.root, ignore_errors=True)
```

- [ ] **Step 4: Run to verify it passes**

Run: `python -m pytest tests/test_media.py -q`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add MediaStore with guaranteed temp-file cleanup"
```

---

## Task 2: screenshot

**Files:**
- Modify: `command/Command.kt`, `accessibility/ActionExecutor.kt`* (see note),
  `accessibility/BridgeAccessibilityService.kt`, `server/BridgeServer.kt`,
  `plugin/client.py`, `plugin/tools.py`, `plugin/__init__.py`
- Create: `android/app/src/androidTest/kotlin/com/hermesandroid/bridge/ScreenshotInstrumentedTest.kt`
- Test: `tests/test_screenshot.py`

> *The capture seam method lives on `AccessibilityActions`; screenshot is handled
> directly in the service `handle()` since it has no pure logic to test in isolation.

- [ ] **Step 1: Add the `Command` variant** to `Command.kt`

```kotlin
    data object Screenshot : Command
```

- [ ] **Step 2: Add the capture method to `AccessibilityActions`** (in `ActionExecutor.kt`)

```kotlin
    /** PNG bytes of the current screen, or null if capture failed/unsupported. */
    suspend fun takeScreenshotPng(): ByteArray?
```

Add a stub to every existing `AccessibilityActions` fake in the test sources so they
still compile (`ActionExecutorTest`, `ActionExecutorGesturesTest`, `ActionExecutorNodeTest`,
`ActionExecutorHashTest`, `ActionExecutorSystemTest`, `ActionExecutorWaitTest`):

```kotlin
override suspend fun takeScreenshotPng(): ByteArray? = null
```

- [ ] **Step 3: Implement on `BridgeAccessibilityService`**

Add the handler branch (in `handle`'s `when`):
```kotlin
        is Command.Screenshot -> {
            val png = takeScreenshotPng()
            if (png == null) CommandResult.Err("screenshot_failed", "could not capture screen")
            else CommandResult.Ok(mapOf("png_base64" to java.util.Base64.getEncoder().encodeToString(png)))
        }
```

Implement the capture (append to the class; add imports as noted):
```kotlin
// imports:
// import android.graphics.Bitmap
// import android.hardware.display.DisplayManager
// import android.view.Display
// import java.io.ByteArrayOutputStream

override suspend fun takeScreenshotPng(): ByteArray? = suspendCancellableCoroutine { cont ->
    takeScreenshot(
        Display.DEFAULT_DISPLAY,
        java.util.concurrent.Executors.newSingleThreadExecutor(),
        object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bytes = try {
                    val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val out = ByteArrayOutputStream()
                    bitmap?.compress(Bitmap.CompressFormat.PNG, 100, out)
                    bitmap?.recycle()
                    out.toByteArray()
                } catch (e: Exception) {
                    null
                } finally {
                    result.hardwareBuffer.close()
                }
                if (cont.isActive) cont.resume(bytes)
            }
            override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
        },
    )
}
```

> `takeScreenshot` is `AccessibilityService` API (Android 11+) and needs **no**
> MediaProjection consent. The `accessibility_service_config.xml` already grants
> `canRetrieveWindowContent`.

- [ ] **Step 4: Add route** in `BridgeServer`

```kotlin
                get("/screenshot") {
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.Screenshot) }
                }
```

- [ ] **Step 5: Add client method** to `plugin/client.py`

```python
    async def screenshot(self):
        return await self._request("GET", "/screenshot")
```

- [ ] **Step 6: Add the tool** (uses the shared `MediaStore`)

In `plugin/__init__.py`, create a process-wide `MediaStore` and expose it to tools.
Replace the client-binding section so tools that need media get the store too:

```python
from .media import MediaStore

_client: AndroidClient | None = None
_media: MediaStore | None = None


def _get_client() -> AndroidClient:
    global _client
    if _client is None:
        _client = AndroidClient(load_config())
    return _client


def get_media() -> MediaStore:
    global _media
    if _media is None:
        _media = MediaStore()
    return _media
```

In `plugin/tools.py`:
```python
from . import __init__ as _plugin  # for get_media at call time


async def android_screenshot(client, media=None):
    """Capture a screenshot; returns a local file path to the PNG."""
    from .media import MediaStore
    store = media if media is not None else _plugin.get_media()
    try:
        data = await client.screenshot()
    except BridgeError as e:
        return {"ok": False, "error": e.error, "message": e.message}
    path = store.write_base64(data["png_base64"], suffix=".png")
    return {"ok": True, "data": {"media_path": path}}
```
Schema:
```python
    {"name": "android_screenshot", "description": "Capture a screenshot (returns a PNG file path).",
     "parameters": {"type": "object", "properties": {}, "required": []},
     "handler": android_screenshot},
```

> The `media=None` parameter lets tests inject a `MediaStore`; in production the
> plugin's shared store is used.

- [ ] **Step 7: Write + run `tests/test_screenshot.py`**

```python
import base64
import os

from plugin.client import AndroidClient
from plugin.config import Config
from plugin.media import MediaStore
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=10.0, max_bytes=10_000_000)


async def test_screenshot_writes_png_then_cleanup(httpx_mock):
    png_b64 = base64.b64encode(b"\x89PNG fake").decode()
    httpx_mock.add_response(url="http://phone:8765/screenshot",
                            json={"ok": True, "data": {"png_base64": png_b64}})
    store = MediaStore()
    client = AndroidClient(cfg())
    try:
        r = await tools.android_screenshot(client, media=store)
        assert r["ok"] is True
        path = r["data"]["media_path"]
        assert os.path.exists(path) and path.endswith(".png")
    finally:
        store.cleanup()
        await client.aclose()
    assert not os.path.exists(path)  # cleanup removed it (bug #12)


async def test_screenshot_failure_surfaced(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/screenshot",
                            json={"ok": False, "error": "screenshot_failed", "message": "nope"})
    store = MediaStore()
    client = AndroidClient(cfg())
    try:
        r = await tools.android_screenshot(client, media=store)
        assert r["ok"] is False and r["error"] == "screenshot_failed"
    finally:
        store.cleanup()
        await client.aclose()
```

Run: `python -m pytest tests/test_screenshot.py -q`
Expected: PASS (2 tests).

- [ ] **Step 8: Write the on-device instrumented test** `ScreenshotInstrumentedTest.kt`

```kotlin
package com.hermesandroid.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.bridge.accessibility.BridgeAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotInstrumentedTest {
    @Test
    fun capturesNonEmptyPng() {
        val svc = BridgeAccessibilityService.current()
        assumeTrue("enable accessibility service", svc != null)
        val png = runBlocking { svc!!.takeScreenshotPng() }
        assertTrue("expected PNG bytes", png != null && png.size > 100)
    }
}
```

- [ ] **Step 9: Verify compile + units, then commit**

Run: `cd android && ./gradlew :app:testDebugUnitTest assembleDebug && cd .. && python -m pytest -q`
Expected: all green.

```bash
jj commit -m "feat: add screenshot tool with managed temp file"
```

---

## Task 3: screen_record (MediaProjection)

**Files:**
- Modify: `command/Command.kt`, `accessibility/BridgeAccessibilityService.kt`,
  `server/BridgeServer.kt`, `plugin/client.py`, `plugin/tools.py`,
  `AndroidManifest.xml`, `ui/MainActivity.kt`, `app/src/main/res/layout/activity_main.xml`
- Create: `lifecycle/MediaProjectionHolder.kt`, `capture/ScreenRecorder.kt`,
  `ui/ScreenCaptureActivity.kt`
- Test: `tests/test_screen_record.py`

Screen recording needs one-time `MediaProjection` consent (a system dialog), so this is
device code with a manual consent step. Without consent it returns a graceful error.

- [ ] **Step 1: Add the `Command` variant** to `Command.kt`

```kotlin
    data class ScreenRecord(val durationMs: Long) : Command
```

- [ ] **Step 2: Add the FGS media-projection permission** to `AndroidManifest.xml`

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
```

- [ ] **Step 3: Write `MediaProjectionHolder.kt`** (holds consent token process-wide)

```kotlin
package com.hermesandroid.bridge.lifecycle

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

/** Stores the user's MediaProjection consent so the recorder can use it later. */
object MediaProjectionHolder {
    private var resultCode: Int = 0
    private var resultData: Intent? = null

    fun store(resultCode: Int, data: Intent) {
        this.resultCode = resultCode
        this.resultData = data
    }

    fun hasConsent(): Boolean = resultData != null

    /** Creates a fresh MediaProjection from stored consent, or null if not granted. */
    fun acquire(context: Context): MediaProjection? {
        val data = resultData ?: return null
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mgr.getMediaProjection(resultCode, data)
    }
}
```

- [ ] **Step 4: Write `ScreenCaptureActivity.kt`** (requests consent)

```kotlin
package com.hermesandroid.bridge.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.hermesandroid.bridge.lifecycle.MediaProjectionHolder

class ScreenCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            MediaProjectionHolder.store(resultCode, data)
        }
        finish()
    }

    companion object {
        private const val REQ = 7001
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, ScreenCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
```

Register it in `AndroidManifest.xml` (inside `<application>`):
```xml
        <activity android:name=".ui.ScreenCaptureActivity" android:exported="false"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
```

- [ ] **Step 5: Write `ScreenRecorder.kt`**

```kotlin
package com.hermesandroid.bridge.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.delay
import java.io.File

/** Records the screen to an MP4 for a fixed duration using a MediaProjection. */
class ScreenRecorder(private val context: Context) {

    suspend fun record(projection: MediaProjection, durationMs: Long): ByteArray? {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val file = File.createTempFile("rec", ".mp4", context.cacheDir)

        val recorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(metrics.widthPixels, metrics.heightPixels)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(4_000_000)
            setOutputFile(file.absolutePath)
            prepare()
        }
        var display: VirtualDisplay? = null
        return try {
            display = projection.createVirtualDisplay(
                "hermes-rec", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.surface, null, null,
            )
            recorder.start()
            delay(durationMs)
            recorder.stop()
            file.readBytes()
        } catch (e: Exception) {
            null
        } finally {
            try { recorder.reset(); recorder.release() } catch (_: Exception) {}
            display?.release()
            projection.stop()
            file.delete() // on-device temp removed immediately after reading (bug #12, device side)
        }
    }
}
```

- [ ] **Step 6: Handle the command** in `BridgeAccessibilityService.handle`

```kotlin
        is Command.ScreenRecord -> {
            val projection = com.hermesandroid.bridge.lifecycle.MediaProjectionHolder.acquire(this)
            if (projection == null) {
                CommandResult.Err("screen_record_unavailable", "grant screen recording in the app first")
            } else {
                val mp4 = com.hermesandroid.bridge.capture.ScreenRecorder(this).record(projection, command.durationMs)
                if (mp4 == null) CommandResult.Err("screen_record_failed", "recording failed")
                else CommandResult.Ok(mapOf("mp4_base64" to java.util.Base64.getEncoder().encodeToString(mp4)))
            }
        }
```

- [ ] **Step 7: Add route + client + tool**

Route in `BridgeServer`:
```kotlin
                post("/screen_record") {
                    val b = gson.fromJson(call.receiveText(), ScreenRecordBody::class.java)
                    call.guarded {
                        BridgeAccessibilityService.current()!!.submit(Command.ScreenRecord(b.duration_ms ?: 5_000))
                    }
                }
```
Body: `private data class ScreenRecordBody(val duration_ms: Long?)`

> Recording can exceed the 25s default command budget. In `BridgeServer`, submit
> screen_record with a larger budget by giving the service a dedicated path, OR raise
> the executor timeout. Simplest: cap `duration_ms` to 20s in the route
> (`val d = (b.duration_ms ?: 5000).coerceAtMost(20_000)`), keeping it under budget.
> Use that clamped value.

Client:
```python
    async def screen_record(self, duration_ms=5000):
        return await self._request("POST", "/screen_record", json={"duration_ms": duration_ms})
```
Tool:
```python
async def android_screen_record(client, duration_ms=5000, media=None):
    """Record the screen for a duration; returns a local file path to the MP4."""
    store = media if media is not None else _plugin.get_media()
    try:
        data = await client.screen_record(duration_ms=duration_ms)
    except BridgeError as e:
        return {"ok": False, "error": e.error, "message": e.message}
    path = store.write_base64(data["mp4_base64"], suffix=".mp4")
    return {"ok": True, "data": {"media_path": path}}
```
Schema:
```python
    {"name": "android_screen_record", "description": "Record the screen (returns an MP4 file path).",
     "parameters": {"type": "object", "properties": {
         "duration_ms": {"type": "integer", "default": 5000}}, "required": []},
     "handler": android_screen_record},
```

- [ ] **Step 8: Add the consent button** to the UI

In `activity_main.xml`, add:
```xml
    <Button android:id="@+id/btnScreenRecord"
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:text="Grant screen recording" android:layout_marginTop="8dp" />
```
In `MainActivity.onCreate`:
```kotlin
        findViewById<Button>(R.id.btnScreenRecord).setOnClickListener {
            com.hermesandroid.bridge.ui.ScreenCaptureActivity.launch(this)
        }
```

- [ ] **Step 9: Write + run `tests/test_screen_record.py`**

```python
import base64
import os

from plugin.client import AndroidClient
from plugin.config import Config
from plugin.media import MediaStore
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=60.0, max_bytes=50_000_000)


async def test_record_writes_mp4(httpx_mock):
    mp4 = base64.b64encode(b"\x00\x00\x00 ftypmp42").decode()
    httpx_mock.add_response(url="http://phone:8765/screen_record",
                            json={"ok": True, "data": {"mp4_base64": mp4}})
    store = MediaStore()
    client = AndroidClient(cfg())
    try:
        r = await tools.android_screen_record(client, duration_ms=2000, media=store)
        assert r["ok"] is True and r["data"]["media_path"].endswith(".mp4")
        assert os.path.exists(r["data"]["media_path"])
    finally:
        store.cleanup()
        await client.aclose()


async def test_record_unavailable_surfaced(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/screen_record",
                            json={"ok": False, "error": "screen_record_unavailable", "message": "no consent"})
    store = MediaStore()
    client = AndroidClient(cfg())
    try:
        r = await tools.android_screen_record(client, media=store)
        assert r["ok"] is False and r["error"] == "screen_record_unavailable"
    finally:
        store.cleanup()
        await client.aclose()
```

Run: `python -m pytest tests/test_screen_record.py -q`
Expected: PASS (2 tests).

- [ ] **Step 10: Verify compile + build, commit**

Run: `cd android && ./gradlew :app:testDebugUnitTest assembleDebug && cd .. && python -m pytest -q`
Expected: all green; APK builds.

```bash
jj commit -m "feat: add screen_record via MediaProjection with managed temp file"
```

---

## Manual smoke (Plan 3)

1. Install the APK, enable accessibility.
2. `android_screenshot` → confirm the returned PNG path opens to the current screen.
3. Tap **Grant screen recording** in the app, approve the system dialog.
4. `android_screen_record(duration_ms=3000)` → confirm the MP4 plays the screen.

## What Plan 3 delivers

`screenshot` and `screen_record` (21 tools total). Media bytes travel in the envelope;
the Python `MediaStore` guarantees temp files are cleaned up (bug #12), unit-tested.
Screenshot is validated on-device; screen recording follows the documented consent flow.