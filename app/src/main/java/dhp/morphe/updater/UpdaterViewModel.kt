package dhp.morphe.updater

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val loading: Boolean = true,
    val items: List<Item> = emptyList(),
    val error: String? = null,
    val conflict: Item? = null,   // original app with another signature installed -> ask to uninstall it first
    val uninstallRequest: Item? = null,   // UI should open the system uninstall dialog for this item's package
    val mode: InstallMode = InstallMode.NORMAL,
    val autoUpdate: Boolean = false,
    val autoUpdateOnStart: Boolean = false,
    val priv: PrivStatus = PrivStatus(),
    val note: String? = null,
)

class UpdaterViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(
        UiState(
            mode = Settings.mode(app),
            autoUpdate = Settings.autoUpdate(app),
            autoUpdateOnStart = Settings.autoUpdateOnStart(app),
        ),
    )
    val state = _state.asStateFlow()
    private val ctx get() = getApplication<Application>()
    private var startupDone = false
    private val lastErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        runCatching {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener { _, _ -> refreshStatus() }
            rikka.shizuku.Shizuku.addBinderReceivedListener { refreshStatus() }
        }
        AutoUpdate.schedule(ctx)
        refreshStatus()
        refresh()
    }

    fun refreshStatus(checkRoot: Boolean = false) {
        viewModelScope.launch {
            val known = _state.value.priv.rootAvailable
            val s = withContext(Dispatchers.IO) {
                runCatching { Privileged.status(checkRoot || _state.value.mode == InstallMode.ROOT && !known, known) }
                    .getOrDefault(PrivStatus())
            }
            // identical status -> no state change -> no recomposition while the settings sheet animates
            if (s != _state.value.priv) _state.update { it.copy(priv = s) }
        }
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { Repository.check(ctx) }
                .onSuccess { items ->
                    // keep the last install error visible instead of wiping it on refresh
                    val shown = items.map { i -> lastErrors[i.id]?.let { e -> i.copy(error = e) } ?: i }
                    _state.update { it.copy(loading = false, items = shown) }
                    if (!startupDone) {
                        startupDone = true
                        if (Settings.autoUpdateOnStart(ctx)) runAutoUpdate()
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "check failed") } }
        }
    }

    /** Silent install of everything pending (apps, then the manager). Needs Shizuku/Root. */
    private fun runAutoUpdate() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val n = withContext(Dispatchers.IO) { runCatching { AutoUpdate.runNow(ctx) }.getOrDefault(-1) }
            _state.update {
                it.copy(note = when {
                    n < 0 -> tr("Auto update needs Shizuku or Root (see settings).", "Tự động cập nhật cần Shizuku hoặc Root (xem cài đặt).")
                    n == 0 -> null
                    else -> tr("Auto-installed $n update(s).", "Đã tự cài $n bản cập nhật.")
                })
            }
            runCatching { Repository.check(ctx) }.onSuccess { items -> _state.update { it.copy(items = items) } }
            _state.update { it.copy(loading = false) }
        }
    }

    // ---- settings ----
    fun setMode(m: InstallMode) {
        Settings.setMode(ctx, m)
        _state.update { it.copy(mode = m) }
        if (m == InstallMode.SHIZUKU) Privileged.requestShizuku()
        refreshStatus(checkRoot = m == InstallMode.ROOT)
    }

    fun setAutoUpdate(v: Boolean) {
        Settings.setAutoUpdate(ctx, v)
        AutoUpdate.schedule(ctx)
        _state.update { it.copy(autoUpdate = v) }
    }

    fun setAutoUpdateOnStart(v: Boolean) {
        Settings.setAutoUpdateOnStart(ctx, v)
        _state.update { it.copy(autoUpdateOnStart = v) }
    }

    // ---- install ----
    private fun patch(id: String, f: (Item) -> Item) =
        _state.update { s -> s.copy(items = s.items.map { if (it.id == id) f(it) else it }) }

    fun update(item: Item) {
        // The original app (other signature) is installed: never try to install over it, explain first.
        if (item.status == Status.CONFLICT) { _state.update { it.copy(conflict = item) }; return }
        // Runs in the process-wide scope + a foreground service: keeps going with the screen closed, shows a notification.
        UpdateService.begin(ctx)
        var ended = false
        fun end() { if (!ended) { ended = true; UpdateService.end(ctx) } }
        UpdateEngine.scope.launch {
            lastErrors.remove(item.id)
            patch(item.id) { it.copy(progress = 0f, error = null) }
            var lastPct = -1
            runCatching {
                val file = Repository.download(ctx, item) { p ->
                    patch(item.id) { it.copy(progress = p) }
                    val pct = (p * 100).toInt()
                    if (pct != lastPct) { lastPct = pct; Notifier.progress(ctx, item.name, pct) }
                }
                // Nothing can confirm an install while the app is in the background (normal mode): keep the downloaded
                // file in the cache and let the user finish with one tap when they come back.
                val mode0 = Settings.mode(ctx)
                val silent = mode0 != InstallMode.NORMAL && Privileged.usable(mode0)
                if (!silent && !AppState.foreground) {
                    end()
                    Notifier.finish(ctx, item.name, tr("Downloaded - open the app and tap Install", "Đã tải xong - mở ứng dụng và chạm Cài đặt"))
                    patch(item.id) { it.copy(progress = null) }
                    return@runCatching
                }
                patch(item.id) { it.copy(progress = -1f) }
                Notifier.progress(ctx, item.name, null)
                Installer.install(ctx, item.id, file) { ok, msg, status ->
                    end()
                    if (ok) {
                        Notifier.finish(ctx, item.name, tr("Updated", "Đã cập nhật"))
                        (item.availablePatches ?: item.availableApp).let { Repository.savePatches(ctx, item.id, it) }
                        refresh()
                    } else {
                        Notifier.finish(ctx, item.name, msg ?: tr("Install failed", "Cài đặt thất bại"))
                        // Same package already installed but signed with another key (e.g. the stock app).
                        val m = (msg ?: "").lowercase()
                        val conflict = item.installedApp != null && item.kind != Kind.MANAGER &&
                            (status == android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT ||
                                status == android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ||
                                "signature" in m || "certificate" in m || "incompatible" in m)
                        val text = (msg ?: tr("Install failed", "Cài đặt thất bại")) + " (code $status)"
                        if (!conflict) lastErrors[item.id] = text   // survives refreshes until the next attempt
                        patch(item.id) { it.copy(progress = null, error = if (conflict) null else text) }
                        if (conflict) _state.update { it.copy(conflict = item) }
                    }
                }
            }.onFailure { e ->
                end()
                lastErrors[item.id] = e.message ?: tr("Download failed", "Tải thất bại")
                Notifier.finish(ctx, item.name, e.message ?: tr("Download failed", "Tải thất bại"))
                patch(item.id) { it.copy(progress = null, error = e.message) }
            }
        }
    }

    fun dismissConflict() = _state.update { it.copy(conflict = null) }

    /** User confirmed: remove the original app (silently in Shizuku/Root mode), then install the mod. */
    fun replaceOriginal(item: Item) {
        dismissConflict()
        val mode = Settings.mode(ctx)
        if (mode != InstallMode.NORMAL && Privileged.usable(mode)) {
            viewModelScope.launch {
                val ok = withContext(Dispatchers.IO) { Privileged.uninstall(mode, item.pkg) }
                if (ok) update(item.copy(status = Status.NOT_INSTALLED, installedApp = null))
                else patch(item.id) { it.copy(error = tr("Could not uninstall the original app.", "Không gỡ được ứng dụng gốc.")) }
            }
        } else {
            // System uninstall dialog (opened by the screen so it can report back); the install follows automatically.
            pendingReplace = item
            _state.update { it.copy(uninstallRequest = item) }
        }
    }

    private var pendingReplace: Item? = null

    fun clearUninstallRequest() = _state.update { it.copy(uninstallRequest = null) }

    /** The system uninstall dialog closed: if the original app is gone, install the mod right away. */
    fun afterUninstall() {
        val item = pendingReplace ?: return
        pendingReplace = null
        val gone = try { ctx.packageManager.getPackageInfo(item.pkg, 0); false } catch (_: android.content.pm.PackageManager.NameNotFoundException) { true }
        if (gone) update(item.copy(status = Status.NOT_INSTALLED, installedApp = null)) else refresh()
    }

    fun updateAll() = _state.value.items
        .filter { it.status == Status.UPDATE && it.kind != Kind.MANAGER && it.progress == null }
        .forEach(::update)
}
