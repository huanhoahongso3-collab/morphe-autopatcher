#!/usr/bin/env python3
"""Download the latest APK/XAPK for a package from apkcombo.com using plain requests.

Flow (same as a browser): GET download page -> POST its form token -> 303 -> file.
Exits non-zero if the page's version differs from --version (we never patch a wrong build)
or the file's SHA-256 does not match the one APKCombo shows.
"""
import argparse, hashlib, http.cookiejar, os, re, sys, urllib.error, urllib.parse, urllib.request

UA = "Mozilla/5.0 (X11; Linux x86_64) morphe-autopatcher/1.0"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("package")
    ap.add_argument("--slug", required=True, help="APKCombo app slug, e.g. facebook")
    ap.add_argument("--version", default="")
    ap.add_argument("--min-android", type=int, default=0, help="reject builds whose minimum Android is lower")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    page = f"https://apkcombo.com/{a.slug}/{a.package}/download/apk"
    jar = http.cookiejar.CookieJar()
    follow = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    manual = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect)

    html = follow.open(urllib.request.Request(page, headers={"User-Agent": UA}), timeout=60).read().decode("utf8", "replace")
    ver = re.search(r'ak-card__h[^>]*>.*?<bdi dir="ltr">([\d.]+)</bdi>', html, re.S)
    ver = ver.group(1) if ver else re.search(r'title="([\d.]{5,})"', html).group(1)
    print(f"apkcombo: {a.package} {ver}", flush=True)
    if a.version and ver != a.version:
        sys.exit(f"apkcombo has {ver}, wanted {a.version}")

    mn = re.search(r"nu-download-request.*?Android (\d+)(?:\.\d+)?\+", html, re.S)
    if a.min_android and mn and int(mn.group(1)) < a.min_android:
        sys.exit(f"apkcombo build is Android {mn.group(1)}+, need Android {a.min_android}+")

    token = re.search(r'name="soqplay_download_file" value="([0-9a-f]{64})"', html).group(1)
    want_sha = re.search(r"<dt>SHA-256</dt><dd[^>]*><code>([0-9a-f]{64})</code>", html)
    badge = re.search(r"Download (XAPK|APK)", html)
    ext = "xapk" if badge and badge.group(1) == "XAPK" else "apk"

    req = urllib.request.Request(page, data=urllib.parse.urlencode({"soqplay_download_file": token}).encode(),
                                 headers={"User-Agent": UA, "Referer": page})
    try:
        manual.open(req, timeout=60)
        sys.exit("no redirect from download form")
    except urllib.error.HTTPError as e:
        if e.code not in (301, 302, 303, 307):
            sys.exit(f"download form returned HTTP {e.code}")
        loc = e.headers["Location"]

    os.makedirs(a.out, exist_ok=True)
    dest = os.path.join(a.out, f"{a.package}-{ver}.{ext}")
    h = hashlib.sha256()
    with follow.open(urllib.request.Request(loc, headers={"User-Agent": UA}), timeout=300) as r, open(dest, "wb") as o:
        for chunk in iter(lambda: r.read(1 << 20), b""):
            o.write(chunk)
            h.update(chunk)
    if want_sha and h.hexdigest() != want_sha.group(1):
        os.remove(dest)
        sys.exit(f"sha256 mismatch: got {h.hexdigest()} expected {want_sha.group(1)}")
    print("saved", dest)


if __name__ == "__main__":
    main()
