import json
from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=5.0, max_bytes=10_000)


async def test_swipe_posts_direction(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/swipe", json={"ok": True, "data": {"swiped": "UP"}})
    client = AndroidClient(cfg())
    r = await tools.android_swipe(client, "up")
    assert r["ok"] is True
    assert json.loads(httpx_mock.get_requests()[0].content) == {"direction": "up", "distance": 0.5}
    await client.aclose()


async def test_drag_posts_endpoints(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/drag", json={"ok": True, "data": {"dragged": True}})
    client = AndroidClient(cfg())
    await tools.android_drag(client, 1, 2, 3, 4)
    sent = json.loads(httpx_mock.get_requests()[0].content)
    assert sent == {"from_x": 1, "from_y": 2, "to_x": 3, "to_y": 4, "duration_ms": 300}
    await client.aclose()


async def test_scroll_surfaces_stale_node(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/scroll",
                            json={"ok": False, "error": "stale_node", "message": "gone"})
    client = AndroidClient(cfg())
    r = await tools.android_scroll(client, "down", node_id="0.9")
    assert r["ok"] is False and r["error"] == "stale_node"
    await client.aclose()
