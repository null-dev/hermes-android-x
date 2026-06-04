from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=30.0, max_bytes=10_000)


async def test_wait_reports_found(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/wait",
                            json={"ok": True, "data": {"found": True, "node_id": "0.3"}})
    client = AndroidClient(cfg())
    r = await tools.android_wait(client, text="Done")
    assert r["data"]["found"] is True
    await client.aclose()
