# Morphe Auto-Patcher + App Updater

GitHub Actions downloads stock APKs, patches them with the **Morphe CLI** using each app's default
patches, signs them with your keystore and publishes **one GitHub Release** with all APKs plus a
`versions.json`. The **App Updater** manager (Kotlin, Jetpack Compose, Material 3 Expressive, package
`dhp.morphe.updater`) reads the latest release and offers an update when the app version **or** the patch
version rose.

* **Schedule:** once a week (Monday 03:00 UTC), plus on every push that touches app/scripts/config, plus manual runs.
* **Architecture:** arm64-v8a (armv8) only. Stock APKs must contain arm64-v8a libraries (a 32-bit-only download is
  rejected), the patched APKs keep only `arm64-v8a`, and the manager itself only installs on 64-bit ARM devices.
* Only the 3 newest `build-*` releases are kept.

## Apps

| App | Patch bundle |
|---|---|
| YouTube (GmsCore support always on) | MorpheApp/morphe-patches |
| Google Photos (GmsCore support always on) | Akash-Sriram/morphe-google-photos |
| TikTok | SysAdminDoc/HushFeed |
| Facebook | SysAdminDoc/HushFacebook |
| Messenger | SysAdminDoc/HushMessenger |
| Instagram | SysAdminDoc/HushGram |
| Zalo | zeldrisho/morphe-patches |
| Block Blast! | ImNoammm/Noams-Morphe-Patches |
| CapCut | durgesh0505/chiggi_morphe_patches |

MicroG-RE is installed/updated straight from `MorpheApp/MicroG-RE` releases (not rebuilt). The manager is built
from `main` and shipped in the same release; it updates itself. `config/apps.json` is the single source of truth.

## Where stock APKs come from (tried in order)

1. APKMirror clients: `AzimsTech`, `tanishqmanuja`, `DeNcHiK3713` `apkmirror-downloader`, then `scripts/apkm.py` (own client).
   APKMirror often blocks GitHub runner IPs; nothing here works around that.
2. Google Play via [gplaydl](https://github.com/rehmatworks/gplaydl) with the **exact version code** (needs the
   `GPLAYDL_API_KEY` secret; the linked account must own the app).
3. Aptoide public API, APKCombo, your own uploads (release `stock-apks`, file `<id>-<version>.apkm|xapk|apks|apk`),
   Uptodown/APK20 via justapk, APKPure via apkeep (only for apps with `"allow_apkpure": true`; APKPure has been
   reported modifying apps, so use at your own risk), apkd.

Every download is checked: the version (code) must match what the patch bundle supports, arm64-v8a must be
present, and the patcher must actually apply patches (a build with 0 applied patches fails instead of shipping).
Base APKs are cached (`actions/cache`) so an unchanged version is not downloaded again.

## Manager (App Updater)

* Material 3 Expressive UI, English / Vietnamese (**Settings → Language**: system default, English, Tiếng Việt).
* Shows app and patch versions per app, and the patch repo each app uses; footer links to this repo.
* **Install mode** (Settings): Normal (system prompt), **Shizuku**, or **Root** for silent installs.
* **Auto update** (Shizuku/Root only): checks twice a week and installs updates in the background.
  **Auto update at app startup**: checks and installs when the app opens. Only installed apps with our signature are
  updated; apps that are not installed or carry another signature are never touched.
* Updates download and install in a foreground service with a progress notification, even with the screen closed.
* If the installed app has a different signature (the original/stock app), the mod can't be installed over it: the app
  explains that, warns that data will be lost, and offers to uninstall the original and install the mod.
* Failed installs keep their error code on the card; the list refreshes with a spinning icon / pull-to-refresh.

## Setup

1. Push this repo to GitHub.
2. Secrets (set from bash/Git Bash so no CRLF sneaks into them):
   ```bash
   base64 -w0 /path/to/your.jks | gh secret set KEYSTORE_BASE64
   printf '%s' 'store-password' | gh secret set KEYSTORE_PASSWORD
   printf '%s' 'key-alias'      | gh secret set KEY_ALIAS
   printf '%s' 'key-password'   | gh secret set KEY_PASSWORD
   ```
3. Optional, for Google Play downloads: `pip install gplaydl`, link it with the gplaydl Authenticator phone app
   (`gplaydl link`), then set `GPLAYDL_API_KEY` from `~/.config/gplaydl/config.json` (`api_key`).
4. Run **Actions → Build patched apps + manager → Run workflow** (tick *force* the first time; *only* limits it to one app id).
5. Install `app-updater-*.apk` from the release once; it handles everything after that.

## Notes

* Patched YouTube / Google Photos sign-in needs MicroG-RE (the manager lists it).
* Adding an app: add an entry to `config/apps.json` (`package`, `bundle_repo`, `bundle_asset`, source slugs;
  optional `enable`, `min_android`, `any_version`, `allow_apkpure`, `version_codes` for bundles that list no version code).
* Fixed per-app version codes (`version_codes`) are only needed when the patch bundle does not list one.
