import os
import pytest


def pytest_collection_modifyitems(config, items):
    if os.environ.get("ANDROID_BRIDGE_URL"):
        return
    skip = pytest.mark.skip(reason="device tests require ANDROID_BRIDGE_URL")
    for item in items:
        if "device" in item.keywords:
            item.add_marker(skip)
