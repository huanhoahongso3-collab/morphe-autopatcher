package dhp.morphe.updater

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

object Repository {
    /** MicroG-RE package + source; adjust here if upstream changes. */
    const val MICROG_REPO = "MorpheApp/MicroG-RE"
    const val MICROG_PKG = "app.revanced.android.gms"

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()

    private fun get(url: String): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        if (url.startsWith("https://api.github.com")) b.header("Accept", "application/vnd.github+json")
        val req = b.build()
        http.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} for $url" }
            return r.body!!.string()
        }
    }

    /**
     * Latest release WITHOUT the REST API: api.github.com allows only 60 requests/hour per IP for anonymous
     * clients and answers HTTP 403 once that is used up. github.com's own pages have no such limit:
     * /releases/latest redirects to the tag, /releases/expanded_assets/<tag> lists the asset links.
     */
    private fun latestWeb(repo: String): GhRelease {
        val tag = http.newCall(Request.Builder().url("https://github.com/$repo/releases/latest").header("User-Agent", UA).build())
            .execute().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code}" }
                r.request.url.pathSegments.last()
            }
        val html = http.newCall(Request.Builder().url("https://github.com/$repo/releases/expanded_assets/$tag").header("User-Agent", UA).build())
            .execute().use { r -> check(r.isSuccessful) { "HTTP ${r.code}" }; r.body!!.string() }
        val assets = Regex("href=\"(/${Regex.escape(repo)}/releases/download/[^\"]+)\"").findAll(html).map { it.groupValues[1] }.distinct()
            .map { path -> GhAsset(path.substringAfterLast('/'), "https://github.com$path") }.toList()
        check(assets.isNotEmpty()) { "no assets found" }
        return GhRelease(tag, assets)
    }

    private const val UA = "AppUpdater/1.0"

    private fun latest(repo: String): GhRelease =
        runCatching { latestWeb(repo) }.getOrElse {
            // fall back to the REST API (works while the anonymous quota lasts)
            json.decodeFromString<GhRelease>(get("https://api.github.com/repos/$repo/releases/latest"))
        }

    // ---- version helpers ----
    fun newer(candidate: String, installed: String): Boolean {
        val a = Regex("\\d+").findAll(candidate).map { it.value.toBigInteger() }.toList()
        val b = Regex("\\d+").findAll(installed).map { it.value.toBigInteger() }.toList()
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i) ?: 0.toBigInteger()
            val y = b.getOrNull(i) ?: 0.toBigInteger()
            if (x != y) return x > y
        }
        return false
    }

    private fun installed(ctx: Context, pkg: String): Pair<String, Long>? = try {
        val i = ctx.packageManager.getPackageInfo(pkg, 0)
        (i.versionName ?: "?") to i.longVersionCode
    } catch (_: PackageManager.NameNotFoundException) { null }

    /** SHA-256 hashes of the signing certificates of [pkg], or null when it is not installed. */
    @Suppress("DEPRECATION")
    private fun certs(ctx: Context, pkg: String): Set<String>? = try {
        val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val i = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            i.signingInfo?.apkContentsSigners
        } else ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        sigs?.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }?.toSet()
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("installed", Context.MODE_PRIVATE)
    fun savedPatches(ctx: Context, id: String): String? = prefs(ctx).getString(id, null)
    fun savePatches(ctx: Context, id: String, v: String) = prefs(ctx).edit().putString(id, v).apply()

    // ---- check ----
    suspend fun check(ctx: Context): List<Item> = withContext(Dispatchers.IO) {
        val items = mutableListOf<Item>()
        val rel = latest(BuildConfig.GITHUB_REPO)
        fun url(name: String) = rel.assets.first { it.name == name }.browser_download_url
        val doc = json.decodeFromString<VersionsDoc>(url("versions.json").let(::get))

        // 1) the manager itself
        doc.manager?.let { m ->
            val self = BuildConfig.VERSION_CODE.toLong()
            items += Item(
                "manager", "App Updater", Kind.MANAGER, ctx.packageName,
                if (m.versionCode > self) Status.UPDATE else Status.UP_TO_DATE,
                BuildConfig.VERSION_NAME, m.versionName, null, null,
                url(m.asset), m.sha256, m.size,
            )
        }

        // 2) MicroG-RE straight from its own GitHub releases
        runCatching {
            val mg = latest(MICROG_REPO)
            val asset = mg.assets.firstOrNull { Regex("^microg-[\\d.]+\\.apk$").matches(it.name) }
                ?: mg.assets.first { it.name.endsWith(".apk") && !it.name.contains("arm") }
            val inst = installed(ctx, MICROG_PKG)
            val saved = savedPatches(ctx, "microg")
            items += Item(
                "microg", "MicroG-RE", Kind.MICROG, MICROG_PKG,
                when {
                    inst == null -> Status.NOT_INSTALLED
                    saved != mg.tag_name -> Status.UPDATE
                    else -> Status.UP_TO_DATE
                },
                inst?.first, mg.tag_name, null, null, asset.browser_download_url, null, asset.size,
            )
        }

        // 3) patched apps (all signed with our key; an installed app with another signature is the original)
        val mine = certs(ctx, ctx.packageName)
        for (a in doc.apps) {
            val inst = installed(ctx, a.`package`)
            val other = inst != null && mine != null && certs(ctx, a.`package`)?.let { it != mine } == true
            val savedP = savedPatches(ctx, a.id)
            val appNewer = inst != null && (if (a.appVersionCode > 0) a.appVersionCode > inst.second else newer(a.appVersion, inst.first))
            val patchNewer = inst != null && (savedP == null || newer(a.patchesVersion, savedP))
            items += Item(
                a.id, a.name, Kind.APP, a.`package`,
                when {
                    inst == null -> Status.NOT_INSTALLED
                    other -> Status.CONFLICT                  // original app present: mod cannot be installed over it
                    appNewer || patchNewer -> Status.UPDATE   // either one rising triggers the prompt
                    else -> Status.UP_TO_DATE
                },
                inst?.first, a.appVersion, savedP, a.patchesVersion, url(a.asset), a.sha256, a.size,
                patchesRepo = a.patchesRepo,
            )
        }
        items
    }

    // ---- download ----
    suspend fun download(ctx: Context, item: Item, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val out = File(ctx.cacheDir, "${item.id}.apk")
        val md = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(item.url).build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            val total = r.body!!.contentLength().takeIf { it > 0 } ?: item.size
            r.body!!.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        item.sha256?.let {
            val got = md.digest().joinToString("") { b -> "%02x".format(b) }
            check(got.equals(it, true)) { "checksum mismatch" }
        }
        out
    }
}
