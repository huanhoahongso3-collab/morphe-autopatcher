#!/usr/bin/env python3
"""Write meta.json/status.json for the manager APK (or a skipped status)."""
import argparse, hashlib, json, os
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("--out", required=True)
ap.add_argument("--apk")
ap.add_argument("--version-code", type=int)
ap.add_argument("--version-name")
ap.add_argument("--source-sha", required=True)
ap.add_argument("--skipped", action="store_true")
a = ap.parse_args()

out = Path(a.out)
out.mkdir(parents=True, exist_ok=True)
if a.skipped:
    json.dump({"status": "skipped"}, open(out / "status.json", "w"))
else:
    h = hashlib.sha256(open(a.apk, "rb").read()).hexdigest()
    name = f"app-updater-{a.version_name}.apk"
    os.replace(a.apk, out / name)
    json.dump({
        "id": "manager", "name": "App Updater", "package": "dhp.morphe.updater",
        "versionCode": a.version_code, "versionName": a.version_name,
        "sourceSha": a.source_sha, "asset": name, "sha256": h,
        "size": (out / name).stat().st_size,
    }, open(out / "meta.json", "w"), indent=2)
    json.dump({"status": "built"}, open(out / "status.json", "w"))
