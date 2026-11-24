@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dhp.morphe.updater

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("App Updater") },
                actions = { IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Refresh") } },
            )
        },
        floatingActionButton = {
            if (pending > 0) ExtendedFloatingActionButton(
                onClick = vm::updateAll,
                icon = { Icon(Icons.Filled.SystemUpdate, null) },
                text = { Text("Update all ($pending)") },
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
                items(st.items, key = { it.id }) { ItemCard(it, onAction = { vm.update(it) }) }
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
                        Button(onClick = onAction) { Text(if (item.status == Status.NOT_INSTALLED) "Install" else "Update") }
                    }
                    else -> if (p < 0f) LoadingIndicator(Modifier.size(40.dp))
                }
            }
            Text(versionLine("App", item.installedApp, item.availableApp), style = MaterialTheme.typography.bodyMedium)
            item.availablePatches?.let {
                Text(versionLine("Patches", item.installedPatches, it), style = MaterialTheme.typography.bodyMedium)
            }
            item.progress?.takeIf { it in 0f..1f }?.let { LinearWavyProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            item.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun statusText(s: Status) = when (s) {
    Status.NOT_INSTALLED -> "Not installed"
    Status.UPDATE -> "Update available"
    Status.UP_TO_DATE -> "Up to date"
    Status.UNKNOWN -> "Unknown"
}

private fun versionLine(label: String, old: String?, new: String) =
    if (old == null || old == new) "$label  $new" else "$label  $old → $new"
