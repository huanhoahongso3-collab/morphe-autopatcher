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

    init {
        runCatching {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener { _, _ -> refreshStatus() }
            rikka.shizuku.Shizuku.addBinderReceivedListener { refreshStatus() }
        }
        AutoUpdate.schedule(ctx)
        refreshStatus()
        refresh()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) { runCatching { Privileged.status() }.getOrDefault(PrivStatus()) }
            _state.update { it.copy(priv = s) }
        }
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { Repository.check(ctx) }
                .onSuccess { items ->
                    _state.update { it.copy(loading = false, items = items) }
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
        refreshStatus()
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
        viewModelScope.launch {
            patch(item.id) { it.copy(progress = 0f, error = null) }
            runCatching {
                val file = Repository.download(ctx, item) { p -> patch(item.id) { it.copy(progress = p) } }
                patch(item.id) { it.copy(progress = -1f) }
                Installer.install(ctx, item.id, file) { ok, msg, status ->
                    if (ok) {
                        (item.availablePatches ?: item.availableApp).let { Repository.savePatches(ctx, item.id, it) }
                        refresh()
                    } else {
                        // Same package already installed but signed with another key (e.g. the stock app).
                        val m = (msg ?: "").lowercase()
                        val conflict = item.installedApp != null && item.kind != Kind.MANAGER &&
                            (status == android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT ||
                                status == android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ||
                                "signature" in m || "certificate" in m || "incompatible" in m)
                        patch(item.id) { it.copy(progress = null, error = if (conflict) null else msg ?: "install failed") }
                        if (conflict) _state.update { it.copy(conflict = item) }
                    }
                }
            }.onFailure { e -> patch(item.id) { it.copy(progress = null, error = e.message) } }
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
            // System uninstall screen; the list is re-checked when the user comes back.
            ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${item.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun updateAll() = _state.value.items
        .filter { it.status == Status.UPDATE && it.kind != Kind.MANAGER && it.progress == null }
        .forEach(::update)
}
