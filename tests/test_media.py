import base64
import os

from plugin.media import MediaStore


def test_writes_and_reads_back():
    store = MediaStore()
    try:
        path = store.write_bytes(b"hello", suffix=".png")
        assert os.path.exists(path)
        with open(path, "rb") as f:
            assert f.read() == b"hello"
    finally:
        store.cleanup()


def test_write_base64():
    store = MediaStore()
    try:
        path = store.write_base64(base64.b64encode(b"abc").decode(), suffix=".mp4")
        with open(path, "rb") as f:
            assert f.read() == b"abc"
    finally:
        store.cleanup()


def test_cleanup_removes_all_files_and_dir():
    store = MediaStore()
    p1 = store.write_bytes(b"a", suffix=".png")
    p2 = store.write_bytes(b"b", suffix=".png")
    root = store.root
    assert os.path.exists(p1) and os.path.exists(p2)
    store.cleanup()
    assert not os.path.exists(p1)
    assert not os.path.exists(p2)
    assert not os.path.exists(root)


def test_cleanup_is_idempotent():
    store = MediaStore()
    store.write_bytes(b"a", suffix=".png")
    store.cleanup()
    store.cleanup()  # must not raise
