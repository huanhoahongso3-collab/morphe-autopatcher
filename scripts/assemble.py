#!/usr/bin/env python3
"""Merge fresh builds with the previous release into the next release folder + versions.json.

Layout in: <artifacts>/app-<id>/{status.json,meta.json,*.apk}, <artifacts>/manager/{status.json,meta.json,*.apk}
Previous release assets are expected in <prev_dir> (versions.json + apks).
Prints 'changed=true|false' to $GITHUB_OUTPUT.
"""
import argparse, datetime, json, os, shutil
from pathlib import Path


def load(p):
    return json.load(open(p)) if os.path.exists(p) else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="config/apps.json")
    ap.add_argument("--artifacts", default="artifacts")
    ap.add_argument("--prev", default="prev")
    ap.add_argument("--out", default="release")
    a = ap.parse_args()

    cfg = json.load(open(a.config))
    prev = load(Path(a.prev) / "versions.json") or {"apps": []}
    prev_apps = {x["id"]: x for x in prev["apps"]}
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    changed, apps = False, []

    for app in cfg["apps"]:
        d = Path(a.artifacts) / f"app-{app['id']}"
        st = load(d / "status.json") or {}
        if st.get("status") == "built":
            meta = load(d / "meta.json")
            shutil.copy(d / meta["asset"], out / meta["asset"])
            apps.append(meta)
            changed = True
        elif app["id"] in prev_apps and (Path(a.prev) / prev_apps[app["id"]]["asset"]).exists():
            e = prev_apps[app["id"]]
            shutil.copy(Path(a.prev) / e["asset"], out / e["asset"])
            apps.append(e)
        else:
            print(f"::warning::{app['id']}: no new build and nothing to carry over")

    manager = None
    md = Path(a.artifacts) / "manager"
    mst = load(md / "status.json") or {}
    if mst.get("status") == "built":
        manager = load(md / "meta.json")
        shutil.copy(md / manager["asset"], out / manager["asset"])
        changed = True
    elif prev.get("manager") and (Path(a.prev) / prev["manager"]["asset"]).exists():
        manager = prev["manager"]
        shutil.copy(Path(a.prev) / manager["asset"], out / manager["asset"])

    doc = {
        "schema": 1,
        "generated": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "manager": manager,
        "apps": apps,
    }
    json.dump(doc, open(out / "versions.json", "w"), indent=2)
    with open(os.environ.get("GITHUB_OUTPUT", os.devnull), "a") as f:
        f.write(f"changed={'true' if changed else 'false'}\n")
    print(json.dumps(doc, indent=2))


if __name__ == "__main__":
    main()
