# Android control

Control a paired Android phone over your local network.

## Setup
1. Install the Hermes Bridge app on the phone and enable its accessibility service.
2. Tap **Start bridge**. The app shows a URL (`http://<phone-ip>:8765`) and a token.
3. Set `ANDROID_BRIDGE_URL` and `ANDROID_BRIDGE_TOKEN` in the agent environment.
4. Call `android_ping` to confirm connectivity.

The phone must be on the same LAN or VPN as the agent.

## Rules

### CRITICAL: Do not loop
- **Do NOT keep taking screenshots in a loop.** Take ONE screenshot, analyze it, act, then report.
- **If an action doesn't work after 2 attempts, STOP and tell the user** what happened.
- **After completing the user's request, STOP and report the result.** Do not keep interacting with the screen.

### Workflow pattern
For any task, follow this pattern and then STOP:
1. `android_open_app(package)` - open the app
2. `android_read_screen()` - see what's on screen
3. 1-3 actions (tap, type, swipe) - do what the user asked
4. `android_read_screen()` or `android_screenshot()` - verify the result
5. **Report to the user and STOP.** Do not take further actions unless the user asks.

### Other rules
1. **ALWAYS open apps with `android_open_app(package)`** - never try to find and tap the icon on the home screen or app drawer.
2. **Prefer `android_read_screen()` over `android_screenshot()`** - read_screen is faster and structured. Only use screenshot when the accessibility tree is insufficient (canvas/image-heavy apps).
3. **Prefer `android_tap_text("Button Text")` over coordinates** - it's more reliable.
4. **If you don't know a package name**, call `android_get_apps()` and search the results.
5. **Confirm destructive actions** (purchases, sends, deletions) with the user before executing.
6. **Handle permission dialogs** - look for "Allow"/"Deny" buttons. Tap "Allow" or "While using the app".
7. **Go back**: `android_press_key("back")`. **Go home**: `android_press_key("home")`.

---

## Tools

### Core / navigation
- `android_ping` — connectivity + device info
- `android_read_screen` — active app accessibility tree with top-level `window` metadata (each node has an `id` usable by `android_tap`; pass `include_system_ui=true` for system UI windows)
- `android_tap` — tap by `(x, y)` or `node_id`
- `android_type` — type into the focused field
- `android_tap_text` — tap the first node whose text matches a string
- `android_long_press` — long-press at `(x, y)` or `node_id`
- `android_drag` — drag from one point to another
- `android_pinch` — two-finger pinch/zoom gesture
- `android_swipe` — swipe in a direction or between two points
- `android_scroll` — scroll a node or the screen

### Screen inspection
- `android_screenshot` — capture a PNG screenshot
- `android_find_nodes` — search the accessibility tree by text/class/id
- `android_describe_node` — get full details for one node by id
- `android_screen_hash` — SHA-256 digest of the current screen content (change detection)
- `android_diff_screen` — diff two screen hashes / detect changes

### App management
- `android_open_app` — launch an app by package name
- `android_press_key` — press a hardware or virtual key (back, home, etc.)
- `android_current_app` — return the foreground package name
- `android_get_apps` — list installed apps
- `android_wait` — wait a specified number of milliseconds

### Clipboard
- `android_clipboard_read` — read the current clipboard contents
- `android_clipboard_write` — write text to the clipboard

### Intents & system
- `android_send_intent` — fire an explicit or implicit `startActivity` intent
- `android_broadcast` — send a broadcast intent

### Communications
- `android_send_sms` — send an SMS message
- `android_call` — initiate a phone call
- `android_search_contacts` — search the contacts database

### Sensors / media / speech
- `android_location` — get the current GPS/network location
- `android_media` — play, pause, or control media playback
- `android_speak` — speak text via TTS
- `android_speak_stop` — stop ongoing TTS speech

### Notifications & events
- `android_notifications` — read current notifications (from `BridgeNotificationListener`)
- `android_events` — read recent accessibility events newer than a given seq number
- `android_event_stream` — collect events from the live SSE stream up to a limit
- `android_widgets` — list installed home-screen widget providers

> Long-press the **Accessibility** button in the app to open Notification access settings
> (where you enable `BridgeNotificationListener`).
