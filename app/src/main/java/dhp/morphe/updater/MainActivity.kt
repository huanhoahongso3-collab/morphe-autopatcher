@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dhp.morphe.updater

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive()) {
                Surface(Modifier.fillMaxSize()) { UpdaterScreen() }
            }
        }
    }
}

@Composable
fun UpdaterScreen(vm: UpdaterViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    // Ask for "install unknown apps" once; PackageInstaller needs it.
    LaunchedEffect(Unit) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
        }
    }
    val pending = st.items.count { it.status == Status.UPDATE && it.kind != Kind.MANAGER }

    // Re-check after the user comes back (e.g. from the system uninstall screen).
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) SettingsDialog(st, vm) { showSettings = false }

    st.conflict?.let { c ->
        AlertDialog(
            onDismissRequest = vm::dismissConflict,
            title = { Text(tr("Mod not installed", "Bản mod chưa được cài")) },
            text = {
                Text(
                    tr("${c.name} was not installed: the app on your phone is the original (signed with a different key), ", "${c.name} chưa được cài: ứng dụng trên máy là bản gốc (ký bằng khóa khác), ") +
                        tr("so Android will not let the mod replace it.\n\n", "nên Android không cho bản mod ghi đè lên.\n\n") +
                        tr("Uninstall the original app, then install the mod.\n\n", "Hãy gỡ ứng dụng gốc rồi cài bản mod.\n\n") +
                        tr("Warning: you will lose that app's data.", "Cảnh báo: bạn sẽ mất dữ liệu của ứng dụng đó."),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.replaceOriginal(c) }) { Text(tr("Uninstall original & install", "Gỡ bản gốc & cài mod")) }
            },
            dismissButton = { TextButton(onClick = vm::dismissConflict) { Text(tr("Cancel", "Hủy")) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Updater", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { vm.refreshStatus(); showSettings = true }) { Icon(Icons.Filled.Settings, "Settings") }
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Refresh") }
                },
            )
        },
        floatingActionButton = {
            if (pending > 0) ExtendedFloatingActionButton(
                onClick = vm::updateAll,
                icon = { Icon(Icons.Filled.SystemUpdate, null) },
                text = { Text(tr("Update all ($pending)", "Cập nhật tất cả ($pending)")) },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (st.loading) LoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                st.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                st.note?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                items(st.items, key = { it.id }) { ItemCard(it, onAction = { vm.update(it) }) }
                item {
                    Text(
                        "GitHub: ${BuildConfig.GITHUB_REPO}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.GITHUB_REPO}")))
                        },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun ItemCard(item: Item, onAction: () -> Unit) {
    val update = item.status == Status.UPDATE
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (update) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(statusText(item.status), style = MaterialTheme.typography.labelLarge)
                }
                when (val p = item.progress) {
                    null -> if (item.status != Status.UP_TO_DATE) {
                        Button(onClick = onAction) {
                            Text(when (item.status) { Status.NOT_INSTALLED -> tr("Install", "Cài đặt"); Status.CONFLICT -> tr("Replace original", "Thay bản gốc"); else -> tr("Update", "Cập nhật") })
                        }
                    }
                    else -> if (p < 0f) LoadingIndicator(Modifier.size(40.dp))
                }
            }
            Text(versionLine(tr("App", "Ứng dụng"), item.installedApp, item.availableApp), style = MaterialTheme.typography.bodyMedium)
            item.availablePatches?.let {
                Text(versionLine(tr("Patches", "Bản vá"), item.installedPatches, it), style = MaterialTheme.typography.bodyMedium)
            }
            if (item.patchesRepo.isNotEmpty()) {
                val c = LocalContext.current
                Text(
                    tr("Patches from ${item.patchesRepo}", "Bản vá từ ${item.patchesRepo}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${item.patchesRepo}")))
                    },
                )
            }
            item.progress?.takeIf { it in 0f..1f }?.let { LinearWavyProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            item.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun statusText(s: Status) = when (s) {
    Status.NOT_INSTALLED -> tr("Not installed", "Chưa cài đặt")
    Status.UPDATE -> tr("Update available", "Có bản cập nhật")
    Status.UP_TO_DATE -> tr("Up to date", "Đã là mới nhất")
    Status.UNKNOWN -> tr("Unknown", "Không rõ")
    Status.CONFLICT -> tr("Mod not installed - the original app is installed (different signature)", "Bản mod chưa cài - đang cài bản gốc (khác chữ ký)")
}

private fun versionLine(label: String, old: String?, new: String) =
    if (old == null || old == new) "$label  $new" else "$label  $old → $new"

@Composable
private fun SettingsDialog(st: UiState, vm: UpdaterViewModel, onClose: () -> Unit) {
    val privOk = st.mode != InstallMode.NORMAL
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Settings", "Cài đặt")) },
        text = {
            Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(tr("Install mode", "Chế độ cài đặt"), style = MaterialTheme.typography.titleSmall)
                ModeRow(tr("Normal (system prompt)", "Thường (hộp thoại hệ thống)"), tr("Android asks to confirm every install.", "Android hỏi xác nhận mỗi lần cài."), st.mode == InstallMode.NORMAL) { vm.setMode(InstallMode.NORMAL) }
                ModeRow(
                    "Shizuku",
                    when {
                        !st.priv.shizukuRunning -> tr("Shizuku is not running", "Shizuku chưa chạy")
                        !st.priv.shizukuGranted -> tr("Running - tap to allow access", "Đang chạy - chạm để cấp quyền")
                        else -> tr("Running and allowed", "Đang chạy và đã cấp quyền")
                    },
                    st.mode == InstallMode.SHIZUKU,
                ) { vm.setMode(InstallMode.SHIZUKU) }
                ModeRow(
                    "Root",
                    if (st.priv.rootAvailable) tr("Root available", "Có root") else tr("Root not available / not granted", "Không có root / chưa cấp quyền"),
                    st.mode == InstallMode.ROOT,
                ) { vm.setMode(InstallMode.ROOT) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(tr("Language", "Ngôn ngữ"), style = MaterialTheme.typography.titleSmall)
                val c = LocalContext.current
                ModeRow(tr("System default", "Mặc định hệ thống"), tr("Vietnamese on a Vietnamese phone, otherwise English.", "Tiếng Việt nếu máy dùng tiếng Việt, ngược lại tiếng Anh."), Lang.current == AppLang.SYSTEM) { Lang.set(c, AppLang.SYSTEM) }
                ModeRow("English", "", Lang.current == AppLang.ENGLISH) { Lang.set(c, AppLang.ENGLISH) }
                ModeRow("Tiếng Việt", "", Lang.current == AppLang.VIETNAMESE) { Lang.set(c, AppLang.VIETNAMESE) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SwitchRow(tr("Auto update", "Tự động cập nhật"), tr("Checks twice a week and installs updates silently.", "Kiểm tra 2 lần mỗi tuần và tự cài cập nhật."), st.autoUpdate, privOk) { vm.setAutoUpdate(it) }
                SwitchRow(tr("Auto update at app startup", "Tự động cập nhật khi mở ứng dụng"), tr("Checks and installs when this app opens.", "Kiểm tra và cài khi mở ứng dụng này."), st.autoUpdateOnStart, privOk) { vm.setAutoUpdateOnStart(it) }
                if (!privOk) Text(tr("Auto update needs Shizuku or Root mode.", "Tự động cập nhật cần chế độ Shizuku hoặc Root."), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Close", "Đóng")) } },
    )
}

@Composable
private fun ModeRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column { Text(title); Text(sub, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(sub, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}
