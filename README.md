# hermes-android-x

Give your Hermes agent hands on a real Android phone.

Re-write of https://github.com/raulvidis/hermes-android, with the goal of improving stability and performance.

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

## Tools

### Core / navigation
- `android_ping` — connectivity and device info
- `android_read_screen` — active app accessibility tree with top-level `window` metadata
- `android_tap` — tap by coordinates or `node_id`
- `android_type` — type into the focused field
- `android_tap_text` — tap the first node whose text matches
- `android_long_press` — long-press by coordinates or `node_id`
- `android_drag` — drag between two points
- `android_pinch` — two-finger pinch/zoom
- `android_swipe` — swipe in a direction
- `android_scroll` — scroll a node or the screen

### Screen inspection
- `android_screenshot` — capture a PNG screenshot
- `android_find_nodes` — search the accessibility tree
- `android_describe_node` — get full details for one node
- `android_screen_hash` — SHA-256 digest of current screen content
- `android_diff_screen` — compare current screen to a prior hash

### Apps / system
- `android_open_app` — launch an app by package name
- `android_press_key` — press system keys such as back, home, recents
- `android_current_app` — return the foreground package
- `android_get_apps` — list installed launchable apps
- `android_wait` — wait for an element to appear
- `android_send_intent` — fire an Android intent
- `android_broadcast` — send a broadcast intent

### Clipboard / comms / sensors
- `android_clipboard_read` — read clipboard text
- `android_clipboard_write` — write clipboard text
- `android_send_sms` — send an SMS on telephony devices
- `android_call` — initiate a phone call
- `android_search_contacts` — search contacts
- `android_location` — get last known location
- `android_media` — control media playback
- `android_speak` — speak text via TTS
- `android_speak_stop` — stop TTS

### Notifications / events
- `android_notifications` — read current notifications
- `android_events` — read recent accessibility events
- `android_event_stream` — collect live SSE events
- `android_widgets` — list installed widget providers

## Develop
- `mise run test-py` — Python unit tests
- `mise run test-android` — Kotlin unit tests
- `mise run test-device` — instrumented tests on a connected phone
- `mise run build-release-apk` — assemble the release APK
- `mise run build-debug-apk` — assemble the debug APK
