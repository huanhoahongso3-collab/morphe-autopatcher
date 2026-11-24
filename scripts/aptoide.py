#!/usr/bin/env python3
"""Download an APK from Aptoide's public API (no scraping, no bot protection).

Exits non-zero unless the published version equals the requested one
(or --any-version is given), so we never silently patch a wrong version.
"""
import argparse, hashlib, json, os, sys, urllib.parse, urllib.request

API = "https://ws75.aptoide.com/api/7/app/getMeta?package_name="
UA = {"User-Agent": "morphe-autopatcher/1.0"}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return r.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("package")
    ap.add_argument("--version", default="")
    ap.add_argument("--any-version", action="store_true")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    meta = json.loads(get(API + urllib.parse.quote(a.package)))["data"]
    f = meta["file"]
    print(f"aptoide: {meta['name']} {f['vername']} ({f['vercode']})", flush=True)
    if a.version and f["vername"] != a.version and not a.any_version:
        sys.exit(f"aptoide has {f['vername']}, wanted {a.version}")

    os.makedirs(a.out, exist_ok=True)
    dest = os.path.join(a.out, f"{a.package}-{f['vername']}.apk")
    h = hashlib.md5()
    with urllib.request.urlopen(urllib.request.Request(f["path"], headers=UA), timeout=120) as r, open(dest, "wb") as o:
        for chunk in iter(lambda: r.read(1 << 20), b""):
            o.write(chunk)
            h.update(chunk)
    want = f.get("md5sum")
    if want and h.hexdigest() != want:
        os.remove(dest)
        sys.exit(f"md5 mismatch: {h.hexdigest()} != {want}")
    print("saved", dest)


if __name__ == "__main__":
    main()
