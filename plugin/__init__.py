import functools

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


def register(ctx):
    """hermes-agent plugin entry point. Registers all android_* tools."""
    for schema in TOOL_SCHEMAS:
        handler = schema["handler"]

        @functools.wraps(handler)
        async def bound(*args, _handler=handler, **kwargs):
            return await _handler(_get_client(), *args, **kwargs)

        ctx.register_tool(
            name=schema["name"],
            description=schema["description"],
            parameters=schema["parameters"],
            handler=bound,
        )
