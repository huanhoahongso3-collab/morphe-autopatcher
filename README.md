# Morphe Auto-Patcher + App Updater

Every 2 days GitHub Actions downloads stock APKs (APKMirror via
[AzimsTech/apkmirror-downloader](https://github.com/AzimsTech/apkmirror-downloader), falling back to
[APKCombo](https://github.com/nirewen/apkcombo-downloader)), patches them with the Morphe CLI using each
app's **default patches**, signs them with your keystore and publishes one GitHub Release containing
**all** APKs + `versions.json`. The **App Updater** app (Compose, Material 3 Expressive) reads the latest
release and prompts only when the app version **or** the patch version rose.

| App | Patch bundle |
|---|---|
| YouTube | MorpheApp/morphe-patches |
| Google Photos | Akash-Sriram/morphe-google-photos |
| TikTok | SysAdminDoc/HushFeed |
| Facebook | SysAdminDoc/HushFacebook |
| Messenger | SysAdminDoc/HushMessenger |
| Instagram | SysAdminDoc/HushGram |

MicroG-RE is installed/updated straight from `MorpheApp/MicroG-RE` releases (not rebuilt).
The manager itself is built from `main` (when `app/**` changes) and shipped in the same release; it updates itself.

## Setup
1. Push this repo to GitHub.
2. Add secrets (run in PowerShell, replace the alias/passwords; the keystore file never leaves your machine unencoded):
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("D:\key\your.jks")) | gh secret set KEYSTORE_BASE64
   gh secret set KEYSTORE_PASSWORD
   gh secret set KEY_ALIAS
   gh secret set KEY_PASSWORD
   ```
3. Run **Actions → Build patched apps + manager → Run workflow** (tick *force* the first time).
4. Install the `app-updater-*.apk` from the release once; it handles everything after that.

## Things to verify on first run
- APKMirror/APKCombo slugs in `config/apps.json` (best-effort).
- `list-versions` output parsing in `scripts/build_app.py` (`supported_version`) and the MicroG-RE package name (`Repository.MICROG_PKG`).
- APKMirror may block GitHub runner IPs; the job then falls back to APKCombo, and a failed app simply keeps its previous release APK.
- Patched YouTube / Google Photos sign-in needs MicroG-RE (the updater lists it).
