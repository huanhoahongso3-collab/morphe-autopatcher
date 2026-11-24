#!/usr/bin/env python3
"""Own minimal APKMirror client (plain HTTP, browser-like headers, no bot-protection tricks).

release listing -> release page -> best variant (arm64/universal, optional min Android) -> download page -> file.
Prints the HTTP status of every step so CI logs show exactly where a block happens.
"""
import argparse, html as H, http.cookiejar, os, re, sys, time, urllib.error, urllib.parse, urllib.request

BASE = "https://www.apkmirror.com"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/124.0 Safari/537.36")
jar = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def get(url, ref=None, binary=False):
    last = None
    for n in range(3):
        req = urllib.request.Request(url, headers={
            "User-Agent": UA, "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9", **({"Referer": ref} if ref else {})})
        try:
            r = op.open(req, timeout=60)
            print(f"apkm: {r.status} {url[:110]}", flush=True)
            return r if binary else r.read().decode("utf8", "replace")
        except urllib.error.HTTPError as e:
            last = e
            print(f"apkm: HTTP {e.code} {url[:110]}", flush=True)
            if e.code in (403, 404):
                break
        except Exception as e:  # network hiccup
            last = e
        time.sleep(3 * (n + 1))
    sys.exit(f"apkm: failed {url}: {last}")


def find_release(org, repo, version, prefix=""):
    prefix = prefix or repo   # release slugs are not always "<repo>-<ver>-release" (TikTok: "tiktok-47-9-3-release")
    dashed = version.replace(".", "-")
    for page in (f"{BASE}/apk/{org}/{repo}/", f"{BASE}/uploads/?appcategory={repo}"):
        body = get(page, BASE)
        for m in re.finditer(r'href="(/apk/%s/%s/[^"]*release/)"' % (re.escape(org), re.escape(repo)), body):
            p = m.group(1)
            if re.search(r"-%s(-\d+)*-release/$" % re.escape(dashed), p) or f"-{dashed}-" in p:
                return BASE + p
    # direct guess
    return f"{BASE}/apk/{org}/{repo}/{prefix}-{dashed}-release/"


def pick_variant(body, min_android, prefer_bundle):
    rows = re.split(r'<div class="table-row headerFont">', body)[1:]
    best = None
    for row in rows:
        link = re.search(r'href="(/apk/[^"]*-android-apk-download/)"', row)
        if not link:
            continue
        text = H.unescape(re.sub(r"<[^>]+>", " ", row))
        is_bundle = "BUNDLE" in text.upper()
        arch = ("arm64" in text) or ("universal" in text.lower()) or ("noarch" in text.lower())
        if not arch:
            continue
        mn = re.search(r"Android (\d+)(?:\.\d+)?\+", text)
        if min_android and mn and int(mn.group(1)) < min_android:
            continue
        score = (is_bundle == prefer_bundle) * 2 + ("arm64" in text)
        if best is None or score > best[0]:
            best = (score, link.group(1), is_bundle)
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--org", required=True)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--release-prefix", default="")
    ap.add_argument("--min-android", type=int, default=0)
    ap.add_argument("--out", required=True)
    ap.add_argument("--id", required=True)
    a = ap.parse_args()

    rel = find_release(a.org, a.repo, a.version, a.release_prefix)
    body = get(rel, BASE)
    v = None
    for pb in (True, False):
        v = pick_variant(body, a.min_android, pb)
        if v:
            break
    if not v:
        sys.exit("apkm: no arm64/universal variant on the release page")
    _, vpath, is_bundle = v
    vpage = get(BASE + vpath, rel)
    d = re.search(r'href="([^"]*/download/\?key=[^"]+)"', vpage) or re.search(r'href="([^"]*/download/[^"]*)"[^>]*downloadButton', vpage)
    if not d:
        sys.exit("apkm: download button not found")
    dpage_url = urllib.parse.urljoin(BASE, H.unescape(d.group(1)))
    dpage = get(dpage_url, BASE + vpath)
    f = re.search(r'href="(/wp-content/themes/APKMirror/download\.php\?[^"]+)"', dpage)
    if not f:
        sys.exit("apkm: final download link not found")
    furl = BASE + H.unescape(f.group(1))
    r = get(furl, dpage_url, binary=True)
    ext = "apkm" if is_bundle else "apk"
    os.makedirs(a.out, exist_ok=True)
    dest = os.path.join(a.out, f"{a.id}.{ext}")
    with open(dest, "wb") as o:
        while True:
            c = r.read(1 << 20)
            if not c:
                break
            o.write(c)
    if os.path.getsize(dest) < 1_000_000:
        os.remove(dest)
        sys.exit("apkm: downloaded file too small (likely an error page)")
    print("saved", dest)


if __name__ == "__main__":
    main()
