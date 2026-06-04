import os
from dataclasses import dataclass


class ConfigError(RuntimeError):
    """Raised when required configuration is missing."""


@dataclass(frozen=True)
class Config:
    base_url: str
    token: str
    timeout: float
    max_bytes: int


def load_config(env=None) -> Config:
    env = os.environ if env is None else env
    url = env.get("ANDROID_BRIDGE_URL")
    token = env.get("ANDROID_BRIDGE_TOKEN")
    if not url:
        raise ConfigError("ANDROID_BRIDGE_URL is required (e.g. http://192.168.1.50:8765)")
    if not token:
        raise ConfigError("ANDROID_BRIDGE_TOKEN is required (shown in the phone app)")
    return Config(
        base_url=url.rstrip("/"),
        token=token,
        timeout=float(env.get("ANDROID_BRIDGE_TIMEOUT", "30")),
        max_bytes=int(env.get("ANDROID_BRIDGE_MAX_BYTES", str(32 * 1024 * 1024))),
    )
