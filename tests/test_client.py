import asyncio

import pytest

from plugin.client import AndroidClient, BridgeError
from plugin.config import Config, ConfigError, load_config


def cfg(max_bytes=32 * 1024 * 1024):
    return Config(base_url="http://phone:8765", token="SECRET", timeout=5.0, max_bytes=max_bytes)


def test_load_config_requires_url(monkeypatch):
    monkeypatch.delenv("ANDROID_BRIDGE_URL", raising=False)
    monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "x")
    with pytest.raises(ConfigError):
        load_config()


def test_load_config_requires_token(monkeypatch):
    monkeypatch.setenv("ANDROID_BRIDGE_URL", "http://phone:8765")
    monkeypatch.delenv("ANDROID_BRIDGE_TOKEN", raising=False)
    with pytest.raises(ConfigError):
        load_config()


def test_client_constructs_without_running_event_loop():
    # Bug #2 regression: constructing the client in a sync context must not raise.
    client = AndroidClient(cfg())
    assert client is not None


async def test_ping_returns_data(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/ping", json={"ok": True, "data": {"device": "Pixel"}})
    client = AndroidClient(cfg())
    assert await client.ping() == {"device": "Pixel"}
    await client.aclose()


async def test_sends_bearer_token(httpx_mock):
    httpx_mock.add_response(json={"ok": True, "data": None})
    client = AndroidClient(cfg())
    await client.ping()
    assert httpx_mock.get_requests()[0].headers["Authorization"] == "Bearer SECRET"
    await client.aclose()


async def test_401_raises_unauthorized(httpx_mock):
    httpx_mock.add_response(status_code=401, json={"ok": False, "error": "unauthorized"})
    client = AndroidClient(cfg())
    with pytest.raises(BridgeError) as e:
        await client.ping()
    assert e.value.error == "unauthorized"
    await client.aclose()


async def test_app_level_failure_raises(httpx_mock):
    httpx_mock.add_response(json={"ok": False, "error": "no_focused_field", "message": "nope"})
    client = AndroidClient(cfg())
    with pytest.raises(BridgeError) as e:
        await client.type_text("hi")
    assert e.value.error == "no_focused_field"
    await client.aclose()


async def test_oversized_response_raises(httpx_mock):
    big = b'{"ok": true, "data": "' + b"x" * 5000 + b'"}'
    httpx_mock.add_response(content=big)
    client = AndroidClient(cfg(max_bytes=1000))
    with pytest.raises(BridgeError) as e:
        await client.ping()
    assert e.value.error == "response_too_large"
    await client.aclose()
