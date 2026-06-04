import sys

from .client import AndroidClient, BridgeError


async def _run(coro):
    try:
        data = await coro
        return {"ok": True, "data": data}
    except BridgeError as e:
        return {"ok": False, "error": e.error, "message": e.message}


async def android_ping(client: AndroidClient):
    """Check that the phone is reachable and the accessibility service is on."""
    return await _run(client.ping())


async def android_read_screen(
    client: AndroidClient,
    include_bounds: bool = True,
    include_system_ui: bool = False,
):
    """Read the active app accessibility tree; optionally include system UI windows."""
    return await _run(
        client.read_screen(
            include_bounds=include_bounds,
            include_system_ui=include_system_ui,
        )
    )


async def android_tap(client: AndroidClient, x=None, y=None, node_id=None):
    """Tap by absolute (x, y) coordinate or by a node id from a prior read_screen."""
    return await _run(client.tap(x=x, y=y, node_id=node_id))


async def android_type(client: AndroidClient, text: str, clear_first: bool = False):
    """Type text into the currently focused input field."""
    return await _run(client.type_text(text, clear_first=clear_first))


async def android_long_press(client, x=None, y=None, node_id=None, duration_ms=600):
    """Long-press by coordinate or node_id."""
    return await _run(client.long_press(x=x, y=y, node_id=node_id, duration_ms=duration_ms))


async def android_drag(client, from_x, from_y, to_x, to_y, duration_ms=300):
    """Drag from one point to another."""
    return await _run(client.drag(from_x, from_y, to_x, to_y, duration_ms=duration_ms))


async def android_pinch(client, x, y, scale):
    """Pinch zoom at (x,y); scale<1 zooms out, >1 zooms in."""
    return await _run(client.pinch(x, y, scale))


async def android_swipe(client, direction, distance=0.5):
    """Swipe up/down/left/right across the screen (distance is a 0..1 fraction)."""
    return await _run(client.swipe(direction, distance=distance))


async def android_scroll(client, direction, node_id=None):
    """Scroll the screen (or a node) up/down/left/right."""
    return await _run(client.scroll(direction, node_id=node_id))


async def android_tap_text(client, text, exact=False):
    """Tap the first element whose visible text matches."""
    return await _run(client.tap_text(text, exact=exact))


async def android_find_nodes(client, text=None, class_name=None, clickable=False):
    """Search the screen for nodes by text/class/clickable."""
    return await _run(client.find_nodes(text=text, class_name=class_name, clickable=clickable))


async def android_describe_node(client, node_id):
    """Get full details of a node by id."""
    return await _run(client.describe_node(node_id))


async def android_screen_hash(client):
    """Get a stable SHA-256 digest of the current screen for change detection."""
    return await _run(client.screen_hash())


async def android_diff_screen(client, hash):
    """Compare the current screen against a previously captured hash."""
    return await _run(client.diff_screen(hash))


async def android_open_app(client, package):
    """Launch an app by package name."""
    return await _run(client.open_app(package))


async def android_press_key(client, key):
    """Press a system key: back, home, recents, notifications, quick_settings, power_dialog, lock_screen."""
    return await _run(client.press_key(key))


async def android_current_app(client):
    """Get the foreground app's package name."""
    return await _run(client.current_app())


async def android_get_apps(client):
    """List installed launchable apps."""
    return await _run(client.get_apps())


async def android_wait(client, text=None, class_name=None, timeout_ms=5000):
    """Wait for an element with the given text/class to appear (up to timeout_ms)."""
    return await _run(client.wait(text=text, class_name=class_name, timeout_ms=timeout_ms))


async def android_screenshot(client, media=None):
    """Capture a screenshot; returns a local file path to the PNG."""
    from .media import MediaStore
    _plugin = sys.modules[__package__]
    store = media if media is not None else _plugin.get_media()
    try:
        data = await client.screenshot()
    except BridgeError as e:
        return {"ok": False, "error": e.error, "message": e.message}
    path = store.write_base64(data["png_base64"], suffix=".png")
    return {"ok": True, "data": {"media_path": path}}


async def android_screen_record(client, duration_ms=5000, media=None):
    """Record the screen for a duration; returns a local file path to the MP4."""
    _plugin = sys.modules[__package__]
    store = media if media is not None else _plugin.get_media()
    try:
        data = await client.screen_record(duration_ms=duration_ms)
    except BridgeError as e:
        return {"ok": False, "error": e.error, "message": e.message}
    path = store.write_base64(data["mp4_base64"], suffix=".mp4")
    return {"ok": True, "data": {"media_path": path}}


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


TOOL_SCHEMAS = [
    {
        "name": "android_ping",
        "description": "Check that the phone is reachable and the accessibility service is on.",
        "parameters": {"type": "object", "properties": {}, "required": []},
        "handler": android_ping,
    },
    {
        "name": "android_read_screen",
        "description": "Read the active app accessibility tree. Set include_system_ui=true to include system UI windows.",
        "parameters": {
            "type": "object",
            "properties": {
                "include_bounds": {"type": "boolean", "default": True},
                "include_system_ui": {"type": "boolean", "default": False},
            },
            "required": [],
        },
        "handler": android_read_screen,
    },
    {
        "name": "android_tap",
        "description": "Tap by (x, y) coordinate or by a node_id from android_read_screen.",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer"},
                "y": {"type": "integer"},
                "node_id": {"type": "string"},
            },
            "required": [],
        },
        "handler": android_tap,
    },
    {
        "name": "android_type",
        "description": "Type text into the currently focused input field.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {"type": "string"},
                "clear_first": {"type": "boolean", "default": False},
            },
            "required": ["text"],
        },
        "handler": android_type,
    },
    {"name": "android_long_press", "description": "Long-press by coordinate or node_id.",
     "parameters": {"type": "object", "properties": {
         "x": {"type": "integer"}, "y": {"type": "integer"}, "node_id": {"type": "string"},
         "duration_ms": {"type": "integer", "default": 600}}, "required": []},
     "handler": android_long_press},
    {"name": "android_drag", "description": "Drag from one point to another.",
     "parameters": {"type": "object", "properties": {
         "from_x": {"type": "integer"}, "from_y": {"type": "integer"},
         "to_x": {"type": "integer"}, "to_y": {"type": "integer"},
         "duration_ms": {"type": "integer", "default": 300}},
         "required": ["from_x", "from_y", "to_x", "to_y"]},
     "handler": android_drag},
    {"name": "android_pinch", "description": "Pinch zoom at (x,y); scale<1 out, >1 in.",
     "parameters": {"type": "object", "properties": {
         "x": {"type": "integer"}, "y": {"type": "integer"}, "scale": {"type": "number"}},
         "required": ["x", "y", "scale"]},
     "handler": android_pinch},
    {"name": "android_swipe", "description": "Swipe up/down/left/right.",
     "parameters": {"type": "object", "properties": {
         "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
         "distance": {"type": "number", "default": 0.5}}, "required": ["direction"]},
     "handler": android_swipe},
    {"name": "android_scroll", "description": "Scroll the screen or a node.",
     "parameters": {"type": "object", "properties": {
         "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
         "node_id": {"type": "string"}}, "required": ["direction"]},
     "handler": android_scroll},
    {"name": "android_tap_text", "description": "Tap the first element whose visible text matches.",
     "parameters": {"type": "object", "properties": {
         "text": {"type": "string"}, "exact": {"type": "boolean", "default": False}},
         "required": ["text"]}, "handler": android_tap_text},
    {"name": "android_find_nodes", "description": "Search nodes by text/class/clickable.",
     "parameters": {"type": "object", "properties": {
         "text": {"type": "string"}, "class_name": {"type": "string"},
         "clickable": {"type": "boolean", "default": False}}, "required": []},
     "handler": android_find_nodes},
    {"name": "android_describe_node", "description": "Get full details of a node by id.",
     "parameters": {"type": "object", "properties": {"node_id": {"type": "string"}},
         "required": ["node_id"]}, "handler": android_describe_node},
    {"name": "android_screen_hash", "description": "Stable SHA-256 digest of the current screen.",
     "parameters": {"type": "object", "properties": {}, "required": []},
     "handler": android_screen_hash},
    {"name": "android_diff_screen", "description": "Compare current screen to a prior hash.",
     "parameters": {"type": "object", "properties": {"hash": {"type": "string"}},
         "required": ["hash"]}, "handler": android_diff_screen},
    {"name": "android_open_app", "description": "Launch an app by package name.",
     "parameters": {"type": "object", "properties": {"package": {"type": "string"}},
         "required": ["package"]}, "handler": android_open_app},
    {"name": "android_press_key", "description": "Press a system key (back/home/recents/...).",
     "parameters": {"type": "object", "properties": {"key": {"type": "string"}},
         "required": ["key"]}, "handler": android_press_key},
    {"name": "android_current_app", "description": "Foreground app package name.",
     "parameters": {"type": "object", "properties": {}, "required": []},
     "handler": android_current_app},
    {"name": "android_get_apps", "description": "List installed launchable apps.",
     "parameters": {"type": "object", "properties": {}, "required": []},
     "handler": android_get_apps},
    {"name": "android_wait", "description": "Wait for an element to appear.",
     "parameters": {"type": "object", "properties": {
         "text": {"type": "string"}, "class_name": {"type": "string"},
         "timeout_ms": {"type": "integer", "default": 5000}}, "required": []},
     "handler": android_wait},
    {"name": "android_screenshot", "description": "Capture a screenshot (returns a PNG file path).",
     "parameters": {"type": "object", "properties": {}, "required": []},
     "handler": android_screenshot},
    # Temporarily disabled: Android 14+ requires fresh MediaProjection consent for
    # each independent recording session. Re-enable after designing a compliant
    # one-shot or live-capture-session flow.
    # {"name": "android_screen_record", "description": "Record the screen (returns an MP4 file path).",
    #  "parameters": {"type": "object", "properties": {
    #      "duration_ms": {"type": "integer", "default": 5000}}, "required": []},
    #  "handler": android_screen_record},
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
]
