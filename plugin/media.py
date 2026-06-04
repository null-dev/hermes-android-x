import base64
import os
import shutil
import tempfile
import uuid


class MediaStore:
    """Owns a per-process temp directory for media; cleanup() removes it entirely."""

    def __init__(self) -> None:
        self.root = tempfile.mkdtemp(prefix="hermes-android-")

    def write_bytes(self, data: bytes, suffix: str = "") -> str:
        name = f"{uuid.uuid4().hex}{suffix}"
        path = os.path.join(self.root, name)
        with open(path, "wb") as f:
            f.write(data)
        return path

    def write_base64(self, b64: str, suffix: str = "") -> str:
        return self.write_bytes(base64.b64decode(b64), suffix=suffix)

    def cleanup(self) -> None:
        shutil.rmtree(self.root, ignore_errors=True)
