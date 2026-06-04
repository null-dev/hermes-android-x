import functools

from .client import AndroidClient
from .config import load_config
from .tools import TOOL_SCHEMAS

_client: AndroidClient | None = None


def _get_client() -> AndroidClient:
    global _client
    if _client is None:
        _client = AndroidClient(load_config())
    return _client


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
