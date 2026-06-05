import json
from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=10.0, max_bytes=100_000)


async def test_sms_unsupported_surfaced(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/sms",
                            json={"ok": False, "error": "unsupported", "message": "no telephony"})
    client = AndroidClient(cfg())
    r = await tools.android_send_sms(client, "123", "hi")
    assert r["ok"] is False and r["error"] == "unsupported"
    await client.aclose()


async def test_clipboard_write_posts_text(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/clipboard", json={"ok": True, "data": {"written": True}})
    client = AndroidClient(cfg())
    await tools.android_clipboard_write(client, "hello")
    assert json.loads(httpx_mock.get_requests()[0].content) == {"text": "hello"}
    await client.aclose()


async def test_send_intent_posts_target_package(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/intent", json={"ok": True, "data": {"sent": True}})
    client = AndroidClient(cfg())
    await tools.android_send_intent(
        client,
        action="android.intent.action.VIEW",
        data="https://example.com",
        extras={"k": "v"},
        package="com.android.chrome",
    )
    assert json.loads(httpx_mock.get_requests()[0].content) == {
        "action": "android.intent.action.VIEW",
        "data": "https://example.com",
        "extras": {"k": "v"},
        "package_name": "com.android.chrome",
    }
    await client.aclose()


async def test_location_unavailable_surfaced(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/location",
                            json={"ok": False, "error": "location_unavailable", "message": "no fix"})
    client = AndroidClient(cfg())
    r = await tools.android_location(client)
    assert r["ok"] is False and r["error"] == "location_unavailable"
    await client.aclose()
