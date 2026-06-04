import pytest

from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=5.0, max_bytes=10_000)


async def test_ping_ok(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/ping", json={"ok": True, "data": {"device": "Pixel"}})
    client = AndroidClient(cfg())
    result = await tools.android_ping(client)
    assert result == {"ok": True, "data": {"device": "Pixel"}}
    await client.aclose()


async def test_ping_service_unavailable(httpx_mock):
    httpx_mock.add_response(status_code=503, json={"ok": False, "error": "service_unavailable"})
    client = AndroidClient(cfg())
    result = await tools.android_ping(client)
    assert result["ok"] is False
    assert result["error"] == "service_unavailable"
    await client.aclose()


async def test_tap_posts_coordinates(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/tap", json={"ok": True, "data": {"tapped": [10, 20]}})
    client = AndroidClient(cfg())
    result = await tools.android_tap(client, x=10, y=20)
    assert result["ok"] is True
    import json
    sent = json.loads(httpx_mock.get_requests()[0].content)
    assert sent == {"x": 10, "y": 20, "node_id": None}
    await client.aclose()


async def test_type_no_focused_field_surfaced(httpx_mock):
    httpx_mock.add_response(json={"ok": False, "error": "no_focused_field", "message": "No input field is focused"})
    client = AndroidClient(cfg())
    result = await tools.android_type(client, text="hi")
    assert result["ok"] is False
    assert result["error"] == "no_focused_field"
    await client.aclose()


async def test_read_screen_returns_tree(httpx_mock):
    tree = {"id": "0", "children": [{"id": "0.0", "children": []}]}
    httpx_mock.add_response(url="http://phone:8765/screen?bounds=true", json={"ok": True, "data": tree})
    client = AndroidClient(cfg())
    result = await tools.android_read_screen(client)
    assert result == {"ok": True, "data": tree}
    await client.aclose()


def test_schemas_cover_all_tools():
    names = {s["name"] for s in tools.TOOL_SCHEMAS}
    assert names == {
        "android_ping", "android_read_screen", "android_tap", "android_type",
        "android_long_press", "android_drag", "android_pinch", "android_swipe", "android_scroll",
        "android_tap_text", "android_find_nodes", "android_describe_node",
        "android_screen_hash", "android_diff_screen",
        "android_open_app", "android_press_key", "android_current_app", "android_get_apps",
        "android_wait", "android_screenshot", "android_screen_record",
        "android_clipboard_read", "android_clipboard_write", "android_send_intent",
        "android_broadcast", "android_send_sms", "android_call", "android_search_contacts",
        "android_location", "android_media", "android_speak", "android_speak_stop",
        "android_notifications", "android_events", "android_widgets", "android_event_stream",
    }
