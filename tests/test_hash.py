from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=5.0, max_bytes=10_000)


async def test_diff_reports_change(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/diff_screen",
                            json={"ok": True, "data": {"changed": True, "hash": "abc"}})
    client = AndroidClient(cfg())
    r = await tools.android_diff_screen(client, "old")
    assert r["data"]["changed"] is True
    await client.aclose()
