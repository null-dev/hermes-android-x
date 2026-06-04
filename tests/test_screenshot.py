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
