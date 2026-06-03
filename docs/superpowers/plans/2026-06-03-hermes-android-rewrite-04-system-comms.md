# Hermes Android Rewrite — Plan 4/5: System & Comms

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the system/comms tools: `send_intent`, `broadcast`, `clipboard_read`/`_write`, `send_sms`, `call`, `search_contacts`, `location`, `media`, `speak`/`speak_stop` — each degrading gracefully when the hardware/permission is unavailable.

**Architecture:** A `SystemController` maps these commands to typed results behind a `SystemServices` seam. The mapping (capability/permission gating → `CommandResult`) is pure and unit-tested with a fake; the real `AndroidSystemServices` is device code.

**Tech Stack:** As prior plans. Adds Telephony, ClipboardManager, LocationManager, MediaSession, TextToSpeech.

> **Prerequisite:** Plans 1–3 complete and green. Spec §4.7, §6.2.
> **VCS:** jj. 5-layer pattern from Plan 2.

---

## Task 1: SystemServices seam + SystemController (pure mapping)

**Files:**
- Modify: `command/Command.kt`
- Create: `system/SystemServices.kt`, `system/SystemController.kt`, `system/MediaActionMap.kt`
- Test: `system/SystemControllerTest.kt`, `system/MediaActionMapTest.kt`

- [ ] **Step 1: Add `Command` variants** to `Command.kt`

```kotlin
    data object ClipboardRead : Command
    data class ClipboardWrite(val text: String) : Command
    data class SendIntent(val action: String, val data: String?, val extras: Map<String, String>) : Command
    data class Broadcast(val action: String, val extras: Map<String, String>) : Command
    data class SendSms(val number: String, val text: String) : Command
    data class Call(val number: String) : Command
    data class SearchContacts(val query: String) : Command
    data object GetLocation : Command
    data class MediaControl(val action: String) : Command
    data class Speak(val text: String) : Command
    data object SpeakStop : Command
```

- [ ] **Step 2: Write `SystemServices.kt`** (seam + result types)

```kotlin
package com.hermesandroid.bridge.system

enum class MediaAction { PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS, STOP }

enum class SmsResult { SENT, FAILED, NO_PERMISSION, UNSUPPORTED }
enum class CallResult { STARTED, NO_PERMISSION, UNSUPPORTED }

data class Contact(val name: String, val number: String)
data class GeoLocation(val latitude: Double, val longitude: Double, val accuracy: Float)

/** The Android system surface SystemController needs; real impl is device code. */
interface SystemServices {
    fun readClipboard(): String?
    fun writeClipboard(text: String)
    fun sendIntent(action: String, data: String?, extras: Map<String, String>): Boolean
    fun sendBroadcast(action: String, extras: Map<String, String>): Boolean
    fun sendSms(number: String, text: String): SmsResult
    fun startCall(number: String): CallResult
    /** null = unsupported or permission denied. */
    fun searchContacts(query: String): List<Contact>?
    /** null = unavailable (no permission / no fix). */
    fun lastLocation(): GeoLocation?
    fun mediaAction(action: MediaAction): Boolean
    fun speak(text: String): Boolean
    fun stopSpeaking()
}
```

- [ ] **Step 3: Write the failing `MediaActionMapTest.kt`**

```kotlin
package com.hermesandroid.bridge.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaActionMapTest {
    @Test fun parsesKnown() {
        assertEquals(MediaAction.PLAY, MediaActionMap.parse("play"))
        assertEquals(MediaAction.PLAY_PAUSE, MediaActionMap.parse("play_pause"))
        assertEquals(MediaAction.NEXT, MediaActionMap.parse("NEXT"))
    }
    @Test fun unknownIsNull() { assertNull(MediaActionMap.parse("rewind")) }
}
```

- [ ] **Step 4: Implement `MediaActionMap.kt`**, run the test green

```kotlin
package com.hermesandroid.bridge.system

object MediaActionMap {
    fun parse(s: String): MediaAction? = when (s.lowercase()) {
        "play" -> MediaAction.PLAY
        "pause" -> MediaAction.PAUSE
        "play_pause", "playpause", "toggle" -> MediaAction.PLAY_PAUSE
        "next" -> MediaAction.NEXT
        "previous", "prev" -> MediaAction.PREVIOUS
        "stop" -> MediaAction.STOP
        else -> null
    }
}
```

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*MediaActionMapTest" && cd ..`
Expected: PASS (2 tests).

- [ ] **Step 5: Write the failing `SystemControllerTest.kt`**

```kotlin
package com.hermesandroid.bridge.system

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSys(
    var clip: String? = "hi",
    var sms: SmsResult = SmsResult.SENT,
    var call: CallResult = CallResult.STARTED,
    var contacts: List<Contact>? = listOf(Contact("Ann", "123")),
    var loc: GeoLocation? = GeoLocation(1.0, 2.0, 5f),
    var mediaOk: Boolean = true,
    var ttsOk: Boolean = true,
) : SystemServices {
    var wroteClip: String? = null
    var lastMedia: MediaAction? = null
    override fun readClipboard() = clip
    override fun writeClipboard(text: String) { wroteClip = text }
    override fun sendIntent(action: String, data: String?, extras: Map<String, String>) = true
    override fun sendBroadcast(action: String, extras: Map<String, String>) = true
    override fun sendSms(number: String, text: String) = sms
    override fun startCall(number: String) = call
    override fun searchContacts(query: String) = contacts
    override fun lastLocation() = loc
    override fun mediaAction(action: MediaAction): Boolean { lastMedia = action; return mediaOk }
    override fun speak(text: String) = ttsOk
    override fun stopSpeaking() {}
}

class SystemControllerTest {
    @Test fun clipboardReadReturnsText() {
        val r = SystemController(FakeSys(clip = "yo")).clipboardRead()
        assertEquals("yo", ((r as CommandResult.Ok).data as Map<*, *>)["text"])
    }
    @Test fun clipboardWriteStores() {
        val sys = FakeSys()
        SystemController(sys).clipboardWrite(Command.ClipboardWrite("copied"))
        assertEquals("copied", sys.wroteClip)
    }
    @Test fun smsSentOk() {
        val r = SystemController(FakeSys(sms = SmsResult.SENT)).sendSms(Command.SendSms("1", "hi"))
        assertTrue(r is CommandResult.Ok)
    }
    @Test fun smsNoPermissionMapsToError() {
        val r = SystemController(FakeSys(sms = SmsResult.NO_PERMISSION)).sendSms(Command.SendSms("1", "hi"))
        assertEquals("permission_denied", (r as CommandResult.Err).error)
    }
    @Test fun smsUnsupportedMapsToError() {
        val r = SystemController(FakeSys(sms = SmsResult.UNSUPPORTED)).sendSms(Command.SendSms("1", "hi"))
        assertEquals("unsupported", (r as CommandResult.Err).error)
    }
    @Test fun contactsUnsupportedWhenNull() {
        val r = SystemController(FakeSys(contacts = null)).searchContacts(Command.SearchContacts("a"))
        assertEquals("unsupported", (r as CommandResult.Err).error)
    }
    @Test fun locationUnavailableWhenNull() {
        val r = SystemController(FakeSys(loc = null)).location()
        assertEquals("location_unavailable", (r as CommandResult.Err).error)
    }
    @Test fun locationReturnsCoords() {
        val r = SystemController(FakeSys(loc = GeoLocation(10.0, 20.0, 3f))).location()
        assertEquals(10.0, ((r as CommandResult.Ok).data as Map<*, *>)["latitude"])
    }
    @Test fun mediaUnknownActionErrors() {
        val r = SystemController(FakeSys()).media(Command.MediaControl("rewind"))
        assertEquals("unknown_action", (r as CommandResult.Err).error)
    }
    @Test fun mediaDispatches() {
        val sys = FakeSys()
        SystemController(sys).media(Command.MediaControl("next"))
        assertEquals(MediaAction.NEXT, sys.lastMedia)
    }
    @Test fun speakUnavailableErrors() {
        val r = SystemController(FakeSys(ttsOk = false)).speak(Command.Speak("hi"))
        assertEquals("tts_unavailable", (r as CommandResult.Err).error)
    }
}
```

- [ ] **Step 6: Run (fail), implement `SystemController.kt`, run (pass)**

```kotlin
package com.hermesandroid.bridge.system

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult

/** Pure mapping from system/comms commands to results; Android specifics via [sys]. */
class SystemController(private val sys: SystemServices) {

    fun clipboardRead(): CommandResult = CommandResult.Ok(mapOf("text" to sys.readClipboard()))

    fun clipboardWrite(cmd: Command.ClipboardWrite): CommandResult {
        sys.writeClipboard(cmd.text)
        return CommandResult.Ok(mapOf("written" to true))
    }

    fun sendIntent(cmd: Command.SendIntent): CommandResult =
        if (sys.sendIntent(cmd.action, cmd.data, cmd.extras)) CommandResult.Ok(mapOf("sent" to true))
        else CommandResult.Err("intent_failed", "could not start intent ${cmd.action}")

    fun broadcast(cmd: Command.Broadcast): CommandResult =
        if (sys.sendBroadcast(cmd.action, cmd.extras)) CommandResult.Ok(mapOf("sent" to true))
        else CommandResult.Err("broadcast_failed", "could not send broadcast ${cmd.action}")

    fun sendSms(cmd: Command.SendSms): CommandResult = when (sys.sendSms(cmd.number, cmd.text)) {
        SmsResult.SENT -> CommandResult.Ok(mapOf("sent" to true))
        SmsResult.FAILED -> CommandResult.Err("sms_failed", "sending failed")
        SmsResult.NO_PERMISSION -> CommandResult.Err("permission_denied", "SMS permission not granted")
        SmsResult.UNSUPPORTED -> CommandResult.Err("unsupported", "device has no telephony")
    }

    fun call(cmd: Command.Call): CommandResult = when (sys.startCall(cmd.number)) {
        CallResult.STARTED -> CommandResult.Ok(mapOf("calling" to cmd.number))
        CallResult.NO_PERMISSION -> CommandResult.Err("permission_denied", "Call permission not granted")
        CallResult.UNSUPPORTED -> CommandResult.Err("unsupported", "device has no telephony")
    }

    fun searchContacts(cmd: Command.SearchContacts): CommandResult {
        val list = sys.searchContacts(cmd.query)
            ?: return CommandResult.Err("unsupported", "contacts unavailable or permission denied")
        return CommandResult.Ok(mapOf("contacts" to list.map { mapOf("name" to it.name, "number" to it.number) }))
    }

    fun location(): CommandResult {
        val loc = sys.lastLocation()
            ?: return CommandResult.Err("location_unavailable", "no location permission or fix")
        return CommandResult.Ok(mapOf("latitude" to loc.latitude, "longitude" to loc.longitude, "accuracy" to loc.accuracy))
    }

    fun media(cmd: Command.MediaControl): CommandResult {
        val action = MediaActionMap.parse(cmd.action)
            ?: return CommandResult.Err("unknown_action", "no media action '${cmd.action}'")
        return if (sys.mediaAction(action)) CommandResult.Ok(mapOf("media" to action.name))
        else CommandResult.Err("media_failed", "no active media session")
    }

    fun speak(cmd: Command.Speak): CommandResult =
        if (sys.speak(cmd.text)) CommandResult.Ok(mapOf("speaking" to true))
        else CommandResult.Err("tts_unavailable", "text-to-speech not ready")

    fun stopSpeaking(): CommandResult {
        sys.stopSpeaking()
        return CommandResult.Ok(mapOf("stopped" to true))
    }
}
```

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*SystemControllerTest" && cd ..`
Expected: PASS (11 tests).

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat: add SystemController and SystemServices seam with graceful degradation"
```

---

## Task 2: Wire system/comms commands (handler + routes + client + tools)

**Files:**
- Modify: `accessibility/BridgeAccessibilityService.kt`, `server/BridgeServer.kt`,
  `plugin/client.py`, `plugin/tools.py`
- Test: `tests/test_comms.py`

- [ ] **Step 1: Give the service a `SystemController`**

In `BridgeAccessibilityService`, add a field built from the real services (created in
Task 3):
```kotlin
private val systemController by lazy {
    com.hermesandroid.bridge.system.SystemController(
        com.hermesandroid.bridge.system.AndroidSystemServices(this)
    )
}
```

Add handler branches in `handle`'s `when`:
```kotlin
        is Command.ClipboardRead -> systemController.clipboardRead()
        is Command.ClipboardWrite -> systemController.clipboardWrite(command)
        is Command.SendIntent -> systemController.sendIntent(command)
        is Command.Broadcast -> systemController.broadcast(command)
        is Command.SendSms -> systemController.sendSms(command)
        is Command.Call -> systemController.call(command)
        is Command.SearchContacts -> systemController.searchContacts(command)
        is Command.GetLocation -> systemController.location()
        is Command.MediaControl -> systemController.media(command)
        is Command.Speak -> systemController.speak(command)
        is Command.SpeakStop -> systemController.stopSpeaking()
```

- [ ] **Step 2: Add routes** in `BridgeServer`

```kotlin
                get("/clipboard") { call.guarded { BridgeAccessibilityService.current()!!.submit(Command.ClipboardRead) } }
                post("/clipboard") {
                    val b = gson.fromJson(call.receiveText(), TextBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.ClipboardWrite(b.text)) }
                }
                post("/intent") {
                    val b = gson.fromJson(call.receiveText(), IntentBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(
                        Command.SendIntent(b.action, b.data, b.extras ?: emptyMap())) }
                }
                post("/broadcast") {
                    val b = gson.fromJson(call.receiveText(), BroadcastBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(
                        Command.Broadcast(b.action, b.extras ?: emptyMap())) }
                }
                post("/sms") {
                    val b = gson.fromJson(call.receiveText(), SmsBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.SendSms(b.number, b.text)) }
                }
                post("/call") {
                    val b = gson.fromJson(call.receiveText(), CallBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.Call(b.number)) }
                }
                get("/contacts") {
                    val q = call.request.queryParameters["q"] ?: ""
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.SearchContacts(q)) }
                }
                get("/location") { call.guarded { BridgeAccessibilityService.current()!!.submit(Command.GetLocation) } }
                post("/media") {
                    val b = gson.fromJson(call.receiveText(), MediaBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.MediaControl(b.action)) }
                }
                post("/speak") {
                    val b = gson.fromJson(call.receiveText(), TextBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.Speak(b.text)) }
                }
                post("/speak/stop") { call.guarded { BridgeAccessibilityService.current()!!.submit(Command.SpeakStop) } }
```

Bodies in `BridgeServer`:
```kotlin
    private data class TextBody(val text: String)
    private data class IntentBody(val action: String, val data: String?, val extras: Map<String, String>?)
    private data class BroadcastBody(val action: String, val extras: Map<String, String>?)
    private data class SmsBody(val number: String, val text: String)
    private data class CallBody(val number: String)
    private data class MediaBody(val action: String)
```

- [ ] **Step 3: Add client methods** to `plugin/client.py`

```python
    async def clipboard_read(self):
        return await self._request("GET", "/clipboard")
    async def clipboard_write(self, text):
        return await self._request("POST", "/clipboard", json={"text": text})
    async def send_intent(self, action, data=None, extras=None):
        return await self._request("POST", "/intent", json={"action": action, "data": data, "extras": extras or {}})
    async def broadcast(self, action, extras=None):
        return await self._request("POST", "/broadcast", json={"action": action, "extras": extras or {}})
    async def send_sms(self, number, text):
        return await self._request("POST", "/sms", json={"number": number, "text": text})
    async def call(self, number):
        return await self._request("POST", "/call", json={"number": number})
    async def search_contacts(self, query):
        return await self._request("GET", "/contacts", params={"q": query})
    async def location(self):
        return await self._request("GET", "/location")
    async def media(self, action):
        return await self._request("POST", "/media", json={"action": action})
    async def speak(self, text):
        return await self._request("POST", "/speak", json={"text": text})
    async def speak_stop(self):
        return await self._request("POST", "/speak/stop", json={})
```

- [ ] **Step 4: Add tools + schemas** to `plugin/tools.py`

```python
async def android_clipboard_read(client):
    """Read the clipboard."""
    return await _run(client.clipboard_read())
async def android_clipboard_write(client, text):
    """Write text to the clipboard."""
    return await _run(client.clipboard_write(text))
async def android_send_intent(client, action, data=None, extras=None):
    """Send an Android intent."""
    return await _run(client.send_intent(action, data=data, extras=extras))
async def android_broadcast(client, action, extras=None):
    """Send a broadcast intent."""
    return await _run(client.broadcast(action, extras=extras))
async def android_send_sms(client, number, text):
    """Send an SMS (telephony devices only)."""
    return await _run(client.send_sms(number, text))
async def android_call(client, number):
    """Start a phone call (telephony devices only)."""
    return await _run(client.call(number))
async def android_search_contacts(client, query):
    """Search contacts by name."""
    return await _run(client.search_contacts(query))
async def android_location(client):
    """Get the phone's last known location."""
    return await _run(client.location())
async def android_media(client, action):
    """Control media playback: play, pause, play_pause, next, previous, stop."""
    return await _run(client.media(action))
async def android_speak(client, text):
    """Speak text aloud via TTS."""
    return await _run(client.speak(text))
async def android_speak_stop(client):
    """Stop any in-progress TTS."""
    return await _run(client.speak_stop())
```
```python
    {"name": "android_clipboard_read", "description": "Read the clipboard.",
     "parameters": {"type": "object", "properties": {}, "required": []}, "handler": android_clipboard_read},
    {"name": "android_clipboard_write", "description": "Write text to the clipboard.",
     "parameters": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
     "handler": android_clipboard_write},
    {"name": "android_send_intent", "description": "Send an Android intent.",
     "parameters": {"type": "object", "properties": {
         "action": {"type": "string"}, "data": {"type": "string"},
         "extras": {"type": "object"}}, "required": ["action"]}, "handler": android_send_intent},
    {"name": "android_broadcast", "description": "Send a broadcast intent.",
     "parameters": {"type": "object", "properties": {
         "action": {"type": "string"}, "extras": {"type": "object"}}, "required": ["action"]},
     "handler": android_broadcast},
    {"name": "android_send_sms", "description": "Send an SMS (telephony only).",
     "parameters": {"type": "object", "properties": {
         "number": {"type": "string"}, "text": {"type": "string"}}, "required": ["number", "text"]},
     "handler": android_send_sms},
    {"name": "android_call", "description": "Start a phone call (telephony only).",
     "parameters": {"type": "object", "properties": {"number": {"type": "string"}}, "required": ["number"]},
     "handler": android_call},
    {"name": "android_search_contacts", "description": "Search contacts by name.",
     "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]},
     "handler": android_search_contacts},
    {"name": "android_location", "description": "Get last known location.",
     "parameters": {"type": "object", "properties": {}, "required": []}, "handler": android_location},
    {"name": "android_media", "description": "Control media playback.",
     "parameters": {"type": "object", "properties": {"action": {"type": "string"}}, "required": ["action"]},
     "handler": android_media},
    {"name": "android_speak", "description": "Speak text via TTS.",
     "parameters": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
     "handler": android_speak},
    {"name": "android_speak_stop", "description": "Stop TTS.",
     "parameters": {"type": "object", "properties": {}, "required": []}, "handler": android_speak_stop},
```

- [ ] **Step 5: Write + run `tests/test_comms.py`**

```python
import json
from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=10.0, max_bytes=100_000)


async def test_sms_unsupported_surfaced(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/sms",
                            json={"ok": False, "error": "unsupported", "message": "no telephony"})
    client = AndroidClient(cfg())
    r = await tools.android_send_sms(client, "123", "hi")
    assert r["ok"] is False and r["error"] == "unsupported"
    await client.aclose()


async def test_clipboard_write_posts_text(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/clipboard", json={"ok": True, "data": {"written": True}})
    client = AndroidClient(cfg())
    await tools.android_clipboard_write(client, "hello")
    assert json.loads(httpx_mock.get_requests()[0].content) == {"text": "hello"}
    await client.aclose()


async def test_location_unavailable_surfaced(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/location",
                            json={"ok": False, "error": "location_unavailable", "message": "no fix"})
    client = AndroidClient(cfg())
    r = await tools.android_location(client)
    assert r["ok"] is False and r["error"] == "location_unavailable"
    await client.aclose()
```

Run: `python -m pytest tests/test_comms.py -q`
Expected: PASS (3 tests). (Kotlin won't compile until Task 3 adds `AndroidSystemServices`.)

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat: wire system/comms routes, client methods, and tools"
```

---

## Task 3: AndroidSystemServices (device impl) + permissions

**Files:**
- Create: `system/AndroidSystemServices.kt`
- Modify: `AndroidManifest.xml`, `ui/MainActivity.kt`

This is device code (validated manually / on-device). It implements the seam and
performs runtime capability + permission checks so unsupported hardware degrades
gracefully instead of crashing.

- [ ] **Step 1: Add permissions** to `AndroidManifest.xml`

```xml
    <uses-permission android:name="android.permission.SEND_SMS" />
    <uses-permission android:name="android.permission.CALL_PHONE" />
    <uses-permission android:name="android.permission.READ_CONTACTS" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-feature android:name="android.hardware.telephony" android:required="false" />
```

- [ ] **Step 2: Implement `AndroidSystemServices.kt`**

```kotlin
package com.hermesandroid.bridge.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat

class AndroidSystemServices(private val context: Context) : SystemServices {

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    private fun hasTelephony() =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    override fun readClipboard(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    override fun writeClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("hermes", text))
    }

    override fun sendIntent(action: String, data: String?, extras: Map<String, String>): Boolean = try {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (data != null) intent.data = Uri.parse(data)
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
        context.startActivity(intent)
        true
    } catch (e: Exception) { false }

    override fun sendBroadcast(action: String, extras: Map<String, String>): Boolean = try {
        val intent = Intent(action)
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
        context.sendBroadcast(intent)
        true
    } catch (e: Exception) { false }

    override fun sendSms(number: String, text: String): SmsResult {
        if (!hasTelephony()) return SmsResult.UNSUPPORTED
        if (!granted(android.Manifest.permission.SEND_SMS)) return SmsResult.NO_PERMISSION
        return try {
            val sms = context.getSystemService(SmsManager::class.java)
            sms.sendTextMessage(number, null, text, null, null)
            SmsResult.SENT
        } catch (e: Exception) { SmsResult.FAILED }
    }

    override fun startCall(number: String): CallResult {
        if (!hasTelephony()) return CallResult.UNSUPPORTED
        if (!granted(android.Manifest.permission.CALL_PHONE)) return CallResult.NO_PERMISSION
        return try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            CallResult.STARTED
        } catch (e: Exception) { CallResult.NO_PERMISSION }
    }

    override fun searchContacts(query: String): List<Contact>? {
        if (!granted(android.Manifest.permission.READ_CONTACTS)) return null
        val results = mutableListOf<Contact>()
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        context.contentResolver.query(uri, projection, selection, arrayOf("%$query%"), null)?.use { c ->
            while (c.moveToNext()) results.add(Contact(c.getString(0) ?: "", c.getString(1) ?: ""))
        }
        return results
    }

    override fun lastLocation(): GeoLocation? {
        if (!granted(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            val providers = lm.getProviders(true)
            val best = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            best?.let { GeoLocation(it.latitude, it.longitude, it.accuracy) }
        } catch (e: SecurityException) { null }
    }

    override fun mediaAction(action: MediaAction): Boolean {
        // Dispatch a media key event via audio manager.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val keyCode = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaAction.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        return try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (e: Exception) { false }
    }

    @Volatile private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var ttsReady = false

    private fun ensureTts() {
        if (tts == null) {
            tts = android.speech.tts.TextToSpeech(context) { status ->
                ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
            }
        }
    }

    override fun speak(text: String): Boolean {
        ensureTts()
        val engine = tts ?: return false
        if (!ttsReady) return false
        return engine.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "hermes") ==
            android.speech.tts.TextToSpeech.SUCCESS
    }

    override fun stopSpeaking() { tts?.stop() }
}
```

- [ ] **Step 3: Add a permissions prompt** to `MainActivity` (best-effort runtime grant)

In `MainActivity.onCreate`, after the existing buttons:
```kotlin
        requestPermissions(
            arrayOf(
                android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.CALL_PHONE,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            42,
        )
```

- [ ] **Step 4: Verify compile + full suite (Kotlin now compiles)**

Run: `cd android && ./gradlew :app:testDebugUnitTest assembleDebug && cd .. && python -m pytest -q`
Expected: all green; APK builds.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add AndroidSystemServices device impl and runtime permissions"
```

---

## Final verification (Plan 4)

- [ ] **Schema count**

Run: `python -c "from plugin.tools import TOOL_SCHEMAS; print(len(TOOL_SCHEMAS))"`
Expected: `32` (19 + 11 + 2 clipboard counted individually — verify the exact set:
clipboard_read, clipboard_write, send_intent, broadcast, send_sms, call,
search_contacts, location, media, speak, speak_stop = 11 new).

- [ ] **Whole suite + APK**

Run: `mise run test-py && mise run test-android && mise run build-apk`
Expected: all green.

- [ ] **Manual smoke** (on a phone with SIM + permissions granted): `android_clipboard_write`
  then `android_clipboard_read`; `android_media("play_pause")`; `android_speak("hello")`;
  `android_location`. On AAOS / no-SIM hardware, confirm `android_send_sms` returns
  `unsupported` rather than crashing.

## What Plan 4 delivers

Eleven system/comms tools (32 total) with graceful degradation, mapping logic
unit-tested behind the `SystemServices` seam and the Android impl validated on-device.
Only notifications & events remain (Plan 5).