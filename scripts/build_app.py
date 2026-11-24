#!/usr/bin/env python3
"""Build one patched app: resolve version -> download stock APK -> patch -> sign.

Writes <out>/status.json  ({"status": "built"|"skipped"|"failed", ...})
and, when built, <out>/<asset>.apk + <out>/meta.json.
"""
import argparse, fnmatch, glob, hashlib, json, os, re, shutil, subprocess, sys, time, zipfile
from pathlib import Path


def run(cmd, **kw):
    print("+", " ".join(map(str, cmd)), flush=True)
    return subprocess.run(cmd, check=True, text=True, **kw)


def gh_tag(repo):
    out = subprocess.check_output(["gh", "release", "view", "-R", repo, "--json", "tagName", "-q", ".tagName"], text=True)
    return out.strip()


def gh_download(repo, pattern, dest, tag=None):
    cmd = ["gh", "release", "download", "-R", repo, "--pattern", pattern, "--dir", str(dest), "--clobber"]
    if tag:
        cmd.insert(3, tag)
    run(cmd)
    files = sorted(glob.glob(str(Path(dest) / pattern)))
    if not files:
        raise SystemExit(f"no asset {pattern} in {repo}")
    return files[-1]


def vkey(v):
    return tuple(int(x) for x in re.findall(r"\d+", v))


def supported_versions(cli_jar, bundle, package):
    out = subprocess.check_output(
        ["java", "-jar", cli_jar, "list-versions", "--patches", bundle, "-f", package], text=True)
    print(out)
    found = {}
    for m in re.finditer(r"(\d+(?:\.\d+){2,})\s*\[versionCodes:([^\]]*)\]", out):
        code = re.search(r"ARM64_V8A=(\d+)", m.group(2)) or re.search(r"=(\d+)", m.group(2))
        found[m.group(1)] = code.group(1) if code else ""
    for v in re.findall(r"\d+(?:\.\d+){2,}", out):
        found.setdefault(v, "")
    if not found:
        raise SystemExit("could not determine supported versions from list-versions output")
    return [(v, found[v]) for v in sorted(found, key=vkey, reverse=True)]


def _fresh(dl):
    shutil.rmtree(dl, ignore_errors=True)
    dl.mkdir(parents=True, exist_ok=True)


def _first_file(dl, prefix=""):
    found = [p for p in glob.glob(str(dl / (prefix + "*"))) if os.path.isfile(p)]
    return found[0] if found else None


STOCK_CACHE = Path(os.environ.get("STOCK_CACHE", "stock-cache"))


def download_stock(app, version, vcode, work, dl_tools):
    """Reuse a cached base APK for this exact version, else download (and cache) it."""
    for p in glob.glob(str(STOCK_CACHE / f"{app['id']}-{version}.*")):
        print(f"stock cache hit: {p}", flush=True)
        return p, "cache"
    path, provider = _download_stock(app, version, vcode, work, dl_tools)
    STOCK_CACHE.mkdir(parents=True, exist_ok=True)
    for old in glob.glob(str(STOCK_CACHE / f"{app['id']}-*")):   # keep one version per app
        os.remove(old)
    ext = os.path.splitext(path)[1] or ".apk"
    shutil.copy(path, STOCK_CACHE / f"{app['id']}-{version}{ext}")
    Path("stock-cache.changed").write_text("1")   # marker outside the cache: tells the workflow to save a new cache
    return path, provider


def _download_stock(app, version, vcode, work, dl_tools):
    """Try sources in order; APKMirror clients first, other stores as fallbacks."""
    dl = Path(work) / "dl"
    errors = []

    def attempt(label, cmd, prefix=""):
        _fresh(dl)
        try:
            run(cmd)
        except (subprocess.CalledProcessError, OSError) as e:
            errors.append(f"{label}: {e}")
            return None
        return _first_file(dl, prefix)

    # 1) APKMirror, two independent clients, normal requests, each retried (it fails at random)
    src = app["apkmirror"]
    for tool in ("apkmirror", "apkmirror_orig", "apkmirror_fork"):
        if not dl_tools.get(tool) or not os.path.exists(dl_tools[tool]):
            continue
        for typ in ("apk", "bundle"):
            for n in range(2):
                spec = {"options": {"outDir": str(dl), "overwrite": True},
                        "apps": [{"org": src["org"], "repo": src["repo"], "version": version,
                                  "outFile": app["id"], "type": typ}]}
                cfg = Path(work) / f"{tool}.json"
                _fresh(dl)
                cfg.write_text(json.dumps(spec))
                f = attempt(f"{tool}/{typ}#{n}", [dl_tools[tool], str(cfg)], app["id"])
                if f:
                    return f, "apkmirror"
                time.sleep(5)

    # 1b) own minimal APKMirror client (logs the HTTP status of each step)
    f = attempt("apkm", [sys.executable, str(Path(__file__).with_name("apkm.py")), "--org", src["org"], "--repo", src["repo"],
                         "--release-prefix", src.get("release", ""), "--version", version, "--min-android", str(app.get("min_android", 0)), "--id", app["id"], "--out", str(dl)], app["id"])
    if f:
        return f, "apkmirror"

    # 1c) Google Play via gplaydl (needs GPLAYDL_API_KEY). The version always follows the patch bundle:
    #     exact version code only (bundle or config version_codes), and every download is checked against the wanted versionCode before it is accepted.
    if os.environ.get("GPLAYDL_API_KEY"):
        # exact version code: from the bundle (list-versions) or the config's version_codes; never guessed
        code0 = vcode or app.get("version_codes", {}).get(version)
        cands = [str(code0)] if code0 else []
        tried = set()
        for code in cands:
            if code in tried:
                continue
            tried.add(code)
            _fresh(dl)
            try:
                run(["gplaydl", "download", app["package"], "-v", code, "-a", "arm64", "--no-extras", "-o", str(dl)])
            except (subprocess.CalledProcessError, OSError) as e:
                errors.append(f"gplaydl {code}: {e}")
                continue
            parts = sorted(glob.glob(str(dl / "*.apk")))
            if not parts:
                continue
            try:
                got = badging(max(parts, key=os.path.getsize))[1]   # versionCode
            except Exception as e:  # aapt2 problem: do not trust it
                errors.append(f"gplaydl {code}: cannot read version ({e})")
                continue
            if got != int(code):
                errors.append(f"gplaydl {code}: downloaded versionCode {got}, wanted {code}")
                continue
            bundle = dl / f"{app['id']}-gplay.apks"
            with zipfile.ZipFile(bundle, "w") as z:
                for p in parts:
                    z.write(p, os.path.basename(p))
            for p in parts:
                os.remove(p)
            return str(bundle), "googleplay"

    # 2) Aptoide public API (exact version only, md5-verified)
    f = attempt("aptoide", [sys.executable, str(Path(__file__).with_name("aptoide.py")), app["package"],
                            "--version", version, "--out", str(dl)])
    if f:
        return f, "aptoide"

    # 2b) APKCombo (apkcombo.com), plain requests, SHA-256 checked, exact version only
    slug = (app.get("apkcombo") or {}).get("org")
    if slug:
        f = attempt("apkcombo", [sys.executable, str(Path(__file__).with_name("apkcombo.py")), app["package"],
                                 "--slug", slug, "--version", "" if app.get("any_version") else version, "--min-android", str(app.get("min_android", 0)),
                                 "--out", str(dl)])
        if f:
            return f, "apkcombo"

    # 2c) your own uploads: release 'stock-apks' with <id>-<version>.(apk|apkm|xapk|apks)
    repo = os.environ.get("GITHUB_REPOSITORY")
    if repo:
        for ext in ("apkm", "xapk", "apks", "apk"):
            f = attempt(f"stock-apks/{ext}", ["gh", "release", "download", "stock-apks", "-R", repo,
                                              "--pattern", f"{app['id']}-{version}.{ext}", "--dir", str(dl)])
            if f:
                return f, "stock-apks"

    # 3) Uptodown / APK20 via justapk (sources without bot protection)
    for source in ("uptodown", "apk20"):
        for extra in (["-v", version], []):
            f = attempt(f"justapk/{source}{extra}",
                        ["justapk", "download", app["package"], "-s", source, "-o", str(dl), "--no-convert"] + extra)
            if f:
                return f, source

    # 4) APKPure via apkeep (needs acknowledge_dangers; only when the app config opts in)
    if app.get("allow_apkpure") and dl_tools.get("apkeep"):
        for spec in (f"{app['package']}@{version}",):
            f = attempt(f"apkeep/{spec}", [dl_tools["apkeep"], "-a", spec, "-d", "apk-pure",
                                          "-o", "acknowledge_dangers=true,arch=arm64-v8a", str(dl)])
            if f:
                return f, "apkpure"

    # 5) apkdgo / APKCombo
    for pkg in ([f"{app['package']}:{vcode}"] if vcode else []) + [app["package"]]:
        f = attempt(f"apkdgo/{pkg}", [dl_tools["apkd"], "-s", "apkcombo", "-p", pkg, "-O", str(dl), "-F"])
        if f:
            return f, "apkcombo"

    raise SystemExit("all downloads failed:\n" + "\n".join(errors))


def ensure_apk(path, work, editor_jar):
    """Merge split bundles (.apkm/.xapk/apks) into one installable apk."""
    with zipfile.ZipFile(path) as z:
        apks = [n for n in z.namelist() if n.endswith(".apk")]
    if not apks:
        return path  # already a plain apk
    merged = str(Path(work) / "merged.apk")
    run(["java", "-jar", editor_jar, "m", "-i", path, "-o", merged])
    return merged


def badging(apk):
    aapt2 = sorted(glob.glob(os.path.expandvars("$ANDROID_HOME/build-tools/*/aapt2")))[-1]
    out = subprocess.check_output([aapt2, "dump", "badging", apk], text=True)
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
    return m.group(1), int(m.group(2)), m.group(3)


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", required=True)
    ap.add_argument("--config", default="config/apps.json")
    ap.add_argument("--prev", default="prev/versions.json")
    ap.add_argument("--out", required=True)
    ap.add_argument("--work", default="work")
    ap.add_argument("--cli", required=True)
    ap.add_argument("--editor", required=True)
    ap.add_argument("--apkmd", required=True)
    ap.add_argument("--apkd", required=True)
    ap.add_argument("--apkeep", required=True)
    ap.add_argument("--apkmd-orig", default="")
    ap.add_argument("--apkmd-fork", default="")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--version", default="", help="pin stock app version")
    a = ap.parse_args()

    cfg = json.load(open(a.config))
    app = next(x for x in cfg["apps"] if x["id"] == a.app)
    out, work = Path(a.out), Path(a.work)
    out.mkdir(parents=True, exist_ok=True)
    work.mkdir(parents=True, exist_ok=True)

    def status(**kw):
        json.dump(kw, open(out / "status.json", "w"), indent=2)

    try:
        tag = gh_tag(app["bundle_repo"])
        bundle = gh_download(app["bundle_repo"], app["bundle_asset"], work, tag)
        cands = [(a.version, "")] if a.version else supported_versions(a.cli, bundle, app["package"])[:6]

        prev = {}
        if os.path.exists(a.prev):
            prev = next((x for x in json.load(open(a.prev)).get("apps", []) if x["id"] == app["id"]), {})
        newest = cands[0][0]
        if not a.force and prev.get("patchesVersion") == tag and (app.get("any_version") or prev.get("appVersion") == newest):
            status(status="skipped", reason="no change", patchesVersion=tag, appVersion=newest)
            return
        if not a.force and prev.get("patchesVersion") == tag and prev.get("appVersion"):
            # same patches: only look at builds newer than what we already ship (never downgrade)
            cands = [c for c in cands if vkey(c[0]) > vkey(prev["appVersion"])] or cands[:1]

        tools = {"apkeep": a.apkeep, "apkd": a.apkd, "apkmirror": a.apkmd, "apkmirror_orig": a.apkmd_orig, "apkmirror_fork": a.apkmd_fork}
        stock = provider = None
        for version, vcode in cands:
            print(f"::group::try {app['id']} {version}", flush=True)
            try:
                stock, provider = download_stock(app, version, vcode, work, tools)
                print("::endgroup::")
                break
            except SystemExit as e:
                print(f"no source has {version}: {str(e)[:300]}")
                print("::endgroup::")
        if not stock:
            raise SystemExit("no source could provide any of: " + ", ".join(v for v, _ in cands))
        stock = ensure_apk(stock, work, a.editor)
        # arm64-v8a is mandatory (other ABIs optional): an app with native libs but no arm64 build is a wrong download
        with zipfile.ZipFile(stock) as z:
            abis = {n.split("/")[1] for n in z.namelist() if n.startswith("lib/") and n.count("/") >= 2}
        if abis and "arm64-v8a" not in abis:
            for old in glob.glob(str(STOCK_CACHE / f"{app['id']}-*")):
                os.remove(old)
            Path("stock-cache.changed").write_text("1")   # persist the purge
            raise SystemExit(f"base APK has no arm64-v8a libs (only {sorted(abis)}): wrong ABI variant")
        # never patch (or cache) a base APK that is not the version we asked for
        try:
            _, sc, sn = badging(stock)
            bad = (sc != int(vcode)) if vcode else (sn != version)
        except Exception:
            bad = False
        if bad and not app.get("any_version"):
            for old in glob.glob(str(STOCK_CACHE / f"{app['id']}-*")):
                os.remove(old)
            Path("stock-cache.changed").write_text("1")   # persist the purge
            raise SystemExit(f"downloaded base APK is {sn} ({sc}), not the wanted {version}")

        patched = str(work / "patched.apk")
        # default patch set, plus any patches the app config forces on (e.g. GmsCore support)
        enable = [x for name in app.get("enable", []) for x in ("-e", name)]
        pcmd = ["java", "-jar", a.cli, "patch", "-p", bundle, "-o", patched,
                "--keystore", os.environ["KEYSTORE_PATH"],
                "--keystore-password", os.environ["KEYSTORE_PASSWORD"],
                "--keystore-entry-alias", os.environ["KEY_ALIAS"],
                "--keystore-entry-password", os.environ["KEY_PASSWORD"],
                *enable,
                stock]
        print("+", " ".join(map(str, pcmd)), flush=True)
        proc = subprocess.Popen(pcmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        applied0 = False
        for line in proc.stdout:
            print(line, end="", flush=True)
            if "Applying 0 patches" in line:
                applied0 = True
        if proc.wait() != 0:
            raise subprocess.CalledProcessError(proc.returncode, pcmd)
        if applied0:   # e.g. the app version is not one the patches support: never ship an unpatched build
            raise SystemExit("0 patches applied: this app version is not supported by the patch bundle")

        pkg, vcode, vname = badging(patched)
        real = vname or version
        asset = f"{app['id']}-{real}-patches-{tag.lstrip('v')}.apk"
        final = out / asset
        os.replace(patched, final)
        meta = {
            "id": app["id"], "name": app["name"], "package": pkg,
            "appVersion": real, "appVersionCode": vcode,
            "patchesRepo": app["bundle_repo"], "patchesVersion": tag,
            "asset": asset, "sha256": sha256(final), "size": final.stat().st_size,
            "source": provider,
        }
        json.dump(meta, open(out / "meta.json", "w"), indent=2)
        status(status="built", **{k: meta[k] for k in ("appVersion", "patchesVersion")})
    except BaseException as e:  # keep matrix going; release job carries over previous build
        status(status="failed", error=str(e))
        print("::error::" + str(e))
        sys.exit(0)


if __name__ == "__main__":
    main()
