import json
from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=5.0, max_bytes=100_000)


async def test_open_app_posts_package(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/open_app", json={"ok": True, "data": {"opened": "com.foo"}})
    client = AndroidClient(cfg())
    await tools.android_open_app(client, "com.foo")
    assert json.loads(httpx_mock.get_requests()[0].content) == {"package_name": "com.foo"}
    await client.aclose()


async def test_get_apps_returns_list(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/apps",
                            json={"ok": True, "data": {"apps": [{"label": "Foo", "package": "com.foo"}]}})
    client = AndroidClient(cfg())
    r = await tools.android_get_apps(client)
    assert r["data"]["apps"][0]["package"] == "com.foo"
    await client.aclose()
