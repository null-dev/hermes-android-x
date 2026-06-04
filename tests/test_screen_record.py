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
