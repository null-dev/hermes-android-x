import xml.etree.ElementTree as ET
from pathlib import Path


ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def test_accessibility_service_can_take_screenshots():
    path = Path("android/app/src/main/res/xml/accessibility_service_config.xml")
    root = ET.parse(path).getroot()
    assert root.attrib.get(f"{ANDROID_NS}canTakeScreenshot") == "true"
