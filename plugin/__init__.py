import functools
import asyncio
import inspect
import json
from pathlib import Path
import threading

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


class _AsyncRunner:
    def __init__(self):
        self._lock = threading.Lock()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None

    def run(self, factory):
        loop = self._ensure_loop()
        future = asyncio.run_coroutine_threadsafe(self._call(factory), loop)
        return future.result()

    def _ensure_loop(self):
        with self._lock:
            if (
                self._loop is not None
                and not self._loop.is_closed()
                and self._thread is not None
                and self._thread.is_alive()
            ):
                return self._loop

            ready = threading.Event()

            def run_loop():
                loop = asyncio.new_event_loop()
                asyncio.set_event_loop(loop)
                self._loop = loop
                ready.set()
                loop.run_forever()

            self._thread = threading.Thread(target=run_loop, name="hermes-android-plugin", daemon=True)
            self._thread.start()
            ready.wait()
            return self._loop

    async def _call(self, factory):
        result = factory()
        if inspect.isawaitable(result):
            return await result
        return result


_runner = _AsyncRunner()


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
                result = _runner.run(lambda: _handler(_get_client(), **(args or {})))
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
