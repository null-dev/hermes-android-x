import json
import asyncio
import threading
from pathlib import Path

import plugin
from plugin.tools import TOOL_SCHEMAS


class FakeContext:
    def __init__(self):
        self.tools = []
        self.skills = []

    def register_tool(self, **kwargs):
        self.tools.append(kwargs)

    def register_skill(self, name, path):
        self.skills.append((name, Path(path)))


def test_register_uses_hermes_tool_contract(monkeypatch):
    async def fake_ping(client):
        del client
        return {"ok": True, "data": {"device": "Pixel"}}

    monkeypatch.setattr(plugin, "TOOL_SCHEMAS", [
        {
            "name": "android_ping",
            "description": "Ping the phone.",
            "parameters": {"type": "object", "properties": {}, "required": []},
            "handler": fake_ping,
        }
    ])
    monkeypatch.setattr(plugin, "_get_client", lambda: object())

    ctx = FakeContext()
    plugin.register(ctx)

    assert len(ctx.tools) == 1
    registered = ctx.tools[0]
    assert registered["name"] == "android_ping"
    assert registered["toolset"] == "android"
    assert registered["schema"] == {
        "name": "android_ping",
        "description": "Ping the phone.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    }
    assert "description" not in registered
    assert "parameters" not in registered

    result = registered["handler"]({}, task_id="t1")
    assert json.loads(result) == {"ok": True, "data": {"device": "Pixel"}}


def test_registered_handler_passes_params_to_tool(monkeypatch):
    async def fake_tap(client, x=None, y=None, node_id=None):
        del client
        return {"ok": True, "data": {"x": x, "y": y, "node_id": node_id}}

    monkeypatch.setattr(plugin, "TOOL_SCHEMAS", [
        {
            "name": "android_tap",
            "description": "Tap.",
            "parameters": {"type": "object", "properties": {}, "required": []},
            "handler": fake_tap,
        }
    ])
    monkeypatch.setattr(plugin, "_get_client", lambda: object())

    ctx = FakeContext()
    plugin.register(ctx)

    result = ctx.tools[0]["handler"]({"x": 10, "y": 20})
    assert json.loads(result)["data"] == {"x": 10, "y": 20, "node_id": None}


def test_registered_handler_creates_and_uses_client_on_same_stable_loop(monkeypatch):
    calls = []

    def fake_get_client():
        calls.append(("client", threading.get_ident(), id(asyncio.get_running_loop())))
        return object()

    async def fake_ping(client):
        del client
        calls.append(("handler", threading.get_ident(), id(asyncio.get_running_loop())))
        return {"ok": True}

    monkeypatch.setattr(plugin, "_get_client", fake_get_client)
    monkeypatch.setattr(plugin, "TOOL_SCHEMAS", [
        {
            "name": "android_ping",
            "description": "Ping.",
            "parameters": {"type": "object", "properties": {}, "required": []},
            "handler": fake_ping,
        }
    ])

    ctx = FakeContext()
    plugin.register(ctx)
    handler = ctx.tools[0]["handler"]

    assert json.loads(handler({})) == {"ok": True}
    assert json.loads(handler({})) == {"ok": True}

    assert calls == [
        ("client", calls[0][1], calls[0][2]),
        ("handler", calls[0][1], calls[0][2]),
        ("client", calls[0][1], calls[0][2]),
        ("handler", calls[0][1], calls[0][2]),
    ]


def test_register_registers_bundled_skill():
    ctx = FakeContext()
    plugin.register(ctx)

    assert ("android", Path(plugin.__file__).parent / "SKILL.md") in ctx.skills


def test_manifest_uses_hermes_requires_env_key():
    manifest = (Path(plugin.__file__).parent / "plugin.yaml").read_text()
    assert "requires_env:" in manifest
    assert "\nenv:" not in manifest


def test_manifest_lists_active_tools():
    manifest = (Path(plugin.__file__).parent / "plugin.yaml").read_text()
    for schema in TOOL_SCHEMAS:
        assert f"  - {schema['name']}" in manifest
    assert "  - android_screen_record" not in manifest
