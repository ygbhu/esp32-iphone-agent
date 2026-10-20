"""Audit actual XML attributes, ignoring comments; never log credential contents."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
app = root / "android/app"
android = "{http://schemas.android.com/apk/res/android}"
forbidden = {
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.accessibilityservice.AccessibilityService",
}

def audit(path):
    manifest = ET.parse(path).getroot()
    for element in manifest.iter():
        for key in ("name", "permission"):
            assert element.get(android + key) not in forbidden, f"Forbidden declaration in {path}"
    features = {
        element.get(android + "name"): element.get(android + "required", "true")
        for element in manifest.findall("uses-feature")
    }
    assert features.get("android.hardware.bluetooth_le") == "false", "BLE must be optional for installation"
    print(f"PASS manifest: {path.relative_to(root)}")

audit(app / "src/main/AndroidManifest.xml")
merged = sorted((app / "build/intermediates/merged_manifests/debug").glob("**/AndroidManifest.xml"))
if "--require-merged" in sys.argv:
    assert merged, "Merged manifest missing; assembleDebug must complete before this audit"
for path in merged:
    audit(path)

for path in (app / "src/main").rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    assert not re.search(r"^\s*import\s+android\.accessibilityservice\.", text, re.M), str(path)
    assert not re.search(r"Runtime\.getRuntime\(\)\.exec|ProcessBuilder\(|/system/bin/su\b", text), str(path)
print("PASS app source audit: no accessibility imports or process execution")
