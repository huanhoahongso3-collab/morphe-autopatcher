#!/usr/bin/env python3
"""Build one patched app: resolve version -> download stock APK -> patch -> sign.

Writes <out>/status.json  ({"status": "built"|"skipped"|"failed", ...})
and, when built, <out>/<asset>.apk + <out>/meta.json.
"""
import argparse, fnmatch, glob, hashlib, json, os, re, shutil, subprocess, sys, zipfile
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


def supported_version(cli_jar, bundle, package):
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
    best = max(found, key=vkey)
    return best, found[best]


def download_stock(app, version, vcode, work, dl_tools):
    dl = Path(work) / "dl"
    errors = []
    # 0) apkeep (EFF) from APKPure, pinned by version name, then latest
    for spec in (f"{app['package']}@{version}", app["package"]):
        shutil.rmtree(dl, ignore_errors=True)
        dl.mkdir(parents=True, exist_ok=True)
        try:
            run([dl_tools["apkeep"], "-a", spec, "-d", "apk-pure", str(dl)])
            found = [p for p in glob.glob(str(dl / "*")) if os.path.isfile(p)]
            if found:
                return found[0], "apkpure"
        except subprocess.CalledProcessError as e:
            errors.append(f"apkeep/{spec}: {e}")
    # 1) apkdgo (APKPure, then APKCombo) pinned by version code, then unpinned
    for source in ("apkcombo",):
        for pkg in ([f"{app['package']}:{vcode}"] if vcode else []) + [app["package"]]:
            shutil.rmtree(dl, ignore_errors=True)
            dl.mkdir(parents=True, exist_ok=True)
            try:
                run([dl_tools["apkd"], "-s", source, "-p", pkg, "-O", str(dl), "-F"])
                found = [p for p in glob.glob(str(dl / "*")) if os.path.isfile(p)]
                if found:
                    return found[0], source
            except subprocess.CalledProcessError as e:
                errors.append(f"{source}/{pkg}: {e}")
    # 2) legacy APKMirror downloader (works only where APKMirror allows the client)
    shutil.rmtree(dl, ignore_errors=True)
    dl.mkdir(parents=True, exist_ok=True)
    for provider in ("apkmirror",):
        src = app[provider]
        spec = {
            "options": {"outDir": str(dl), "overwrite": True},
            "apps": [{"org": src["org"], "repo": src["repo"], "version": version, "outFile": app["id"], "type": "apk"}],
        }
        # bundle fallback: a second attempt as bundle if plain apk is unavailable
        for typ in ("apk", "bundle"):
            spec["apps"][0]["type"] = typ
            cfg = Path(work) / f"{provider}.json"
            cfg.write_text(json.dumps(spec))
            try:
                run([dl_tools[provider], str(cfg)])
                found = [p for p in glob.glob(str(dl / (app["id"] + "*"))) if os.path.isfile(p)]
                if found:
                    return found[0], provider
            except subprocess.CalledProcessError as e:
                errors.append(f"{provider}/{typ}: {e}")
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
        version, vcode = (a.version, "") if a.version else supported_version(a.cli, bundle, app["package"])

        prev = {}
        if os.path.exists(a.prev):
            prev = next((x for x in json.load(open(a.prev)).get("apps", []) if x["id"] == app["id"]), {})
        if not a.force and prev.get("patchesVersion") == tag and prev.get("appVersion") == version:
            status(status="skipped", reason="no change", patchesVersion=tag, appVersion=version)
            return

        stock, provider = download_stock(app, version, vcode, work, {"apkeep": a.apkeep, "apkd": a.apkd, "apkmirror": a.apkmd})
        stock = ensure_apk(stock, work, a.editor)

        patched = str(work / "patched.apk")
        # default patch set: no -e / -d flags
        run(["java", "-jar", a.cli, "patch", "-p", bundle, "-o", patched,
             "--keystore", os.environ["KEYSTORE_PATH"],
             "--keystore-password", os.environ["KEYSTORE_PASSWORD"],
             "--keystore-entry-alias", os.environ["KEY_ALIAS"],
             "--keystore-entry-password", os.environ["KEY_PASSWORD"],
             stock])

        pkg, vcode, vname = badging(patched)
        asset = f"{app['id']}-{version}-patches-{tag.lstrip('v')}.apk"
        final = out / asset
        os.replace(patched, final)
        meta = {
            "id": app["id"], "name": app["name"], "package": pkg,
            "appVersion": version, "appVersionCode": vcode,
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
