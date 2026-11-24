package dhp.morphe.updater

import kotlinx.serialization.Serializable

// ---- GitHub API ----
@Serializable
data class GhRelease(val tag_name: String, val assets: List<GhAsset> = emptyList())

@Serializable
data class GhAsset(val name: String, val browser_download_url: String, val size: Long = 0)

// ---- versions.json (published by CI in every release) ----
@Serializable
data class VersionsDoc(val manager: ManagerMeta? = null, val apps: List<AppMeta> = emptyList())

@Serializable
data class AppMeta(
    val id: String, val name: String, val `package`: String,
    val appVersion: String, val appVersionCode: Long = 0,
    val patchesRepo: String = "", val patchesVersion: String,
    val asset: String, val sha256: String, val size: Long = 0,
)

@Serializable
data class ManagerMeta(
    val versionCode: Long, val versionName: String,
    val asset: String, val sha256: String, val size: Long = 0,
)

// ---- UI state ----
enum class Kind { APP, MICROG, MANAGER }
enum class Status { NOT_INSTALLED, UPDATE, UP_TO_DATE, UNKNOWN, CONFLICT }   // CONFLICT = original app with another signature is installed

data class Item(
    val id: String,
    val name: String,
    val kind: Kind,
    val pkg: String,
    val status: Status,
    val installedApp: String?,
    val availableApp: String,
    val installedPatches: String?,
    val availablePatches: String?,   // null for MicroG / manager
    val url: String,
    val sha256: String?,
    val size: Long,
    val progress: Float? = null,     // null = idle, 0..1 downloading, -1 = installing
    val error: String? = null,
    val patchesRepo: String = "",    // GitHub "owner/repo" of the patch bundle used for this app
)
