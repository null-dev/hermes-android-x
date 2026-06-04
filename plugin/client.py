import httpx

from .config import Config


class BridgeError(RuntimeError):
    """A request to the phone failed (transport or app-level)."""

    def __init__(self, error: str, message: str = "", status: int | None = None):
        super().__init__(f"{error}: {message}")
        self.error = error
        self.message = message
        self.status = status


class AndroidClient:
    """Async HTTP client for the phone bridge. Safe to construct in a sync context."""

    def __init__(self, config: Config):
        self._config = config
        # No asyncio.Lock here — httpx is concurrency-safe and a lock created
        # outside a running loop is the prototype's bug #2.
        self._client = httpx.AsyncClient(
            base_url=config.base_url,
            timeout=config.timeout,
            headers={"Authorization": f"Bearer {config.token}"},
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _request(self, method: str, path: str, *, params=None, json=None):
        # Stream so we can enforce the size cap before buffering the whole body (bug #11).
        async with self._client.stream(method, path, params=params, json=json) as resp:
            chunks = []
            total = 0
            async for chunk in resp.aiter_bytes():
                total += len(chunk)
                if total > self._config.max_bytes:
                    raise BridgeError(
                        "response_too_large",
                        f"response exceeded {self._config.max_bytes} bytes",
                        resp.status_code,
                    )
                chunks.append(chunk)
            body = b"".join(chunks)
            status = resp.status_code
        return self._handle(status, body)

    def _handle(self, status: int, body: bytes):
        if status == 401:
            raise BridgeError("unauthorized", "bad or missing token", 401)
        if status == 408:
            raise BridgeError("timeout", "device command timed out", 408)
        if status == 413:
            raise BridgeError("request_too_large", "request body too large", 413)
        if status == 429:
            raise BridgeError("rate_limited", "too many auth failures; temporarily blocked", 429)
        if status == 503:
            raise BridgeError("service_unavailable", "accessibility service not enabled", 503)
        if status >= 400:
            raise BridgeError("http_error", f"unexpected status {status}", status)

        import json as _json

        data = _json.loads(body.decode("utf-8"))
        if not data.get("ok", False):
            raise BridgeError(data.get("error", "unknown"), data.get("message", ""), status)
        return data.get("data")

    # --- high-level operations (this plan) ---

    async def ping(self):
        return await self._request("GET", "/ping")

    async def read_screen(self, include_bounds: bool = True):
        return await self._request("GET", "/screen", params={"bounds": str(include_bounds).lower()})

    async def tap(self, x=None, y=None, node_id=None):
        return await self._request("POST", "/tap", json={"x": x, "y": y, "node_id": node_id})

    async def type_text(self, text: str, clear_first: bool = False):
        return await self._request("POST", "/type", json={"text": text, "clear_first": clear_first})

    async def long_press(self, x=None, y=None, node_id=None, duration_ms=600):
        return await self._request("POST", "/long_press",
                                   json={"x": x, "y": y, "node_id": node_id, "duration_ms": duration_ms})

    async def drag(self, from_x, from_y, to_x, to_y, duration_ms=300):
        return await self._request("POST", "/drag",
                                   json={"from_x": from_x, "from_y": from_y, "to_x": to_x, "to_y": to_y, "duration_ms": duration_ms})

    async def pinch(self, x, y, scale):
        return await self._request("POST", "/pinch", json={"x": x, "y": y, "scale": scale})

    async def swipe(self, direction, distance=0.5):
        return await self._request("POST", "/swipe", json={"direction": direction, "distance": distance})

    async def scroll(self, direction, node_id=None):
        return await self._request("POST", "/scroll", json={"direction": direction, "node_id": node_id})

    async def tap_text(self, text, exact=False):
        return await self._request("POST", "/tap_text", json={"text": text, "exact": exact})

    async def find_nodes(self, text=None, class_name=None, clickable=False):
        params = {"clickable": str(clickable).lower()}
        if text is not None: params["text"] = text
        if class_name is not None: params["class"] = class_name
        return await self._request("GET", "/find_nodes", params=params)

    async def describe_node(self, node_id):
        return await self._request("GET", "/describe_node", params={"node_id": node_id})

    async def screen_hash(self):
        return await self._request("GET", "/screen_hash")

    async def diff_screen(self, previous_hash):
        return await self._request("POST", "/diff_screen", json={"hash": previous_hash})

    async def open_app(self, package):
        return await self._request("POST", "/open_app", json={"package_name": package})

    async def press_key(self, key):
        return await self._request("POST", "/press_key", json={"key": key})

    async def current_app(self):
        return await self._request("GET", "/current_app")

    async def get_apps(self):
        return await self._request("GET", "/apps")

    async def wait(self, text=None, class_name=None, timeout_ms=5000):
        return await self._request("POST", "/wait",
                                   json={"text": text, "class_name": class_name, "timeout_ms": timeout_ms})

    async def screenshot(self):
        return await self._request("GET", "/screenshot")

    async def screen_record(self, duration_ms=5000):
        return await self._request("POST", "/screen_record", json={"duration_ms": duration_ms})

    async def clipboard_read(self):
        return await self._request("GET", "/clipboard")

    async def clipboard_write(self, text):
        return await self._request("POST", "/clipboard", json={"text": text})

    async def send_intent(self, action, data=None, extras=None):
        return await self._request("POST", "/intent", json={"action": action, "data": data, "extras": extras or {}})

    async def broadcast(self, action, extras=None):
        return await self._request("POST", "/broadcast", json={"action": action, "extras": extras or {}})

    async def send_sms(self, number, text):
        return await self._request("POST", "/sms", json={"number": number, "text": text})

    async def call(self, number):
        return await self._request("POST", "/call", json={"number": number})

    async def search_contacts(self, query):
        return await self._request("GET", "/contacts", params={"q": query})

    async def location(self):
        return await self._request("GET", "/location")

    async def media(self, action):
        return await self._request("POST", "/media", json={"action": action})

    async def speak(self, text):
        return await self._request("POST", "/speak", json={"text": text})

    async def speak_stop(self):
        return await self._request("POST", "/speak/stop", json={})

    async def notifications(self):
        return await self._request("GET", "/notifications")

    async def events(self, since=0):
        return await self._request("GET", "/events", params={"since": since})

    async def widgets(self):
        return await self._request("GET", "/widgets")

    async def event_stream(self, since=0):
        """Yield event dicts from the SSE stream until cancelled."""
        import json as _json
        async with self._client.stream("GET", "/events/stream", params={"since": since}) as resp:
            if resp.status_code == 401:
                raise BridgeError("unauthorized", "bad or missing token", 401)
            async for line in resp.aiter_lines():
                if line.startswith("data: "):
                    yield _json.loads(line[len("data: "):])
