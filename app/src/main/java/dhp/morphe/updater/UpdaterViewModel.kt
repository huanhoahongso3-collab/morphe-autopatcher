package dhp.morphe.updater

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(val loading: Boolean = true, val items: List<Item> = emptyList(), val error: String? = null)

class UpdaterViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private val ctx get() = getApplication<Application>()

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { Repository.check(ctx) }
                .onSuccess { items -> _state.value = UiState(false, items) }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "check failed") } }
        }
    }

    private fun patch(id: String, f: (Item) -> Item) =
        _state.update { s -> s.copy(items = s.items.map { if (it.id == id) f(it) else it }) }

    fun update(item: Item) {
        viewModelScope.launch {
            patch(item.id) { it.copy(progress = 0f, error = null) }
            runCatching {
                val file = Repository.download(ctx, item) { p -> patch(item.id) { it.copy(progress = p) } }
                patch(item.id) { it.copy(progress = -1f) }
                Installer.install(ctx, item.id, file) { ok, msg ->
                    if (ok) {
                        (item.availablePatches ?: item.availableApp).let { Repository.savePatches(ctx, item.id, it) }
                        refresh()
                    } else patch(item.id) { it.copy(progress = null, error = msg ?: "install failed") }
                }
            }.onFailure { e -> patch(item.id) { it.copy(progress = null, error = e.message) } }
        }
    }

    fun updateAll() = _state.value.items
        .filter { it.status == Status.UPDATE && it.kind != Kind.MANAGER && it.progress == null }
        .forEach(::update)
}
