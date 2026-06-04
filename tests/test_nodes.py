from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=5.0, max_bytes=100_000)


async def test_tap_text_not_found_surfaced(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/tap_text",
                            json={"ok": False, "error": "text_not_found", "message": "no node"})
    client = AndroidClient(cfg())
    r = await tools.android_tap_text(client, "Buy")
    assert r["ok"] is False and r["error"] == "text_not_found"
    await client.aclose()


async def test_find_nodes_passes_query(httpx_mock):
    httpx_mock.add_response(json={"ok": True, "data": {"nodes": []}})
    client = AndroidClient(cfg())
    await tools.android_find_nodes(client, text="OK", clickable=True)
    req = httpx_mock.get_requests()[0]
    assert "text=OK" in str(req.url) and "clickable=true" in str(req.url)
    await client.aclose()
