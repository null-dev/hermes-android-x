from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=10.0, max_bytes=1_000_000)


async def test_events_passes_since(httpx_mock):
    httpx_mock.add_response(json={"ok": True, "data": {"events": []}})
    client = AndroidClient(cfg())
    await tools.android_events(client, since=42)
    assert "since=42" in str(httpx_mock.get_requests()[0].url)
    await client.aclose()


async def test_notifications_returns_list(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/notifications",
                            json={"ok": True, "data": {"notifications": [{"package": "com.x", "title": "Hi"}]}})
    client = AndroidClient(cfg())
    r = await tools.android_notifications(client)
    assert r["data"]["notifications"][0]["title"] == "Hi"
    await client.aclose()


async def test_event_stream_collects_until_limit(httpx_mock):
    body = "data: {\"seq\": 1, \"text\": \"a\"}\n\ndata: {\"seq\": 2, \"text\": \"b\"}\n\n"
    httpx_mock.add_response(url="http://phone:8765/events/stream?since=0",
                            text=body, headers={"Content-Type": "text/event-stream"})
    client = AndroidClient(cfg())
    r = await tools.android_event_stream(client, since=0, limit=2)
    assert r["ok"] is True
    assert [e["seq"] for e in r["data"]["events"]] == [1, 2]
    await client.aclose()
