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


async def android_read_screen(client: AndroidClient, include_bounds: bool = True):
    """Read the accessibility tree of the current screen."""
    return await _run(client.read_screen(include_bounds=include_bounds))


async def android_tap(client: AndroidClient, x=None, y=None, node_id=None):
    """Tap by absolute (x, y) coordinate or by a node id from a prior read_screen."""
    return await _run(client.tap(x=x, y=y, node_id=node_id))


async def android_type(client: AndroidClient, text: str, clear_first: bool = False):
    """Type text into the currently focused input field."""
    return await _run(client.type_text(text, clear_first=clear_first))


TOOL_SCHEMAS = [
    {
        "name": "android_ping",
        "description": "Check that the phone is reachable and the accessibility service is on.",
        "parameters": {"type": "object", "properties": {}, "required": []},
        "handler": android_ping,
    },
    {
        "name": "android_read_screen",
        "description": "Read the accessibility tree of the current screen.",
        "parameters": {
            "type": "object",
            "properties": {"include_bounds": {"type": "boolean", "default": True}},
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
]
