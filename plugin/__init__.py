import functools
import asyncio
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .client import AndroidClient
from .config import load_config
from .media import MediaStore
from .tools import TOOL_SCHEMAS

_client: AndroidClient | None = None
_media: MediaStore | None = None


def _get_client() -> AndroidClient:
    global _client
    if _client is None:
        _client = AndroidClient(load_config())
    return _client


def get_media() -> MediaStore:
    global _media
    if _media is None:
        _media = MediaStore()
    return _media


def _run_async(coro):
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.run(coro)

    with ThreadPoolExecutor(max_workers=1) as executor:
        return executor.submit(lambda: asyncio.run(coro)).result()


def register(ctx):
    """hermes-agent plugin entry point. Registers all android_* tools."""
    for schema in TOOL_SCHEMAS:
        handler = schema["handler"]
        tool_schema = {
            "name": schema["name"],
            "description": schema["description"],
            "parameters": schema["parameters"],
        }

        @functools.wraps(handler)
        def bound(args=None, _handler=handler, **kwargs):
            del kwargs
            try:
                result = _run_async(_handler(_get_client(), **(args or {})))
            except Exception as e:
                result = {"ok": False, "error": "plugin_error", "message": str(e)}
            return result if isinstance(result, str) else json.dumps(result)

        ctx.register_tool(
            name=schema["name"],
            toolset="android",
            schema=tool_schema,
            handler=bound,
        )

    skill_path = Path(__file__).parent / "SKILL.md"
    if skill_path.exists():
        ctx.register_skill("android", skill_path)
