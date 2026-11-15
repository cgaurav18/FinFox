package com.mymoney.app.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.cloud.BackupWorker
import com.mymoney.app.cloud.RemoteBackup
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.BackupStatus
import com.mymoney.app.data.db.BackupTrigger
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.IconBadge
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.components.TonalButton
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinanceTheme

@Composable
fun BackupHistoryScreen(nav: NavController, vm: BackupHistoryViewModel = hiltViewModel()) {
    val remote by vm.remote.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    var delete by remember { mutableStateOf<RemoteBackup?>(null) }
    AppScaffold(title = "Backup History", onBack = { nav.popBackStack() }, actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionHeader("In your Google Drive") }
            when {
                remote == null -> item { Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Spacer(Modifier.width(12.dp)); Text("Loading backups…") } }
                error != null -> item { AppCard { Text(error!!, color = MaterialTheme.colorScheme.error); TonalButton("Try again", { vm.refresh() }) } }
                remote!!.isEmpty() -> item { AppCard { EmptyState(Icons.Filled.CloudQueue, "No backups yet", "Use Backup Now to create your first encrypted Drive backup.") } }
                else -> items(remote!!, key = { it.fileId }) { b ->
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Filled.CloudQueue, MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${Dates.formatDay(b.createdAt)}, ${Dates.formatTime(b.createdAt)}", style = MaterialTheme.typography.titleSmall)
                                Text(listOfNotNull(BackupWorker.formatSize(b.sizeBytes), b.transactions?.let { "$it transactions" }, b.device).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { delete = b }) { Icon(Icons.Filled.Delete, "Delete backup", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        TonalButton("Restore this backup", { nav.navigate(Routes.restoreDrive(b.fileId)) }, Modifier.padding(top = 6.dp), icon = Icons.Filled.Restore)
                    }
                }
            }
            if (local.isNotEmpty()) {
                item { SectionHeader("Activity on this device") }
                items(local.take(50), key = { "l${it.id}" }) { m ->
                    val ok = m.status == BackupStatus.SUCCESS
                    AppCard(contentPadding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null, tint = if (ok) FinanceTheme.colors.income else MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text((if (m.trigger == BackupTrigger.AUTO) "Automatic" else "Manual") + " backup · ${Dates.formatDay(m.createdAt)} ${Dates.formatTime(m.createdAt)}", style = MaterialTheme.typography.bodyMedium)
                                Text(if (ok) "${BackupWorker.formatSize(m.sizeBytes)} · ${m.transactionCount} transactions" else m.error ?: "Failed",
                                    style = MaterialTheme.typography.bodySmall, color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    delete?.let { b ->
        ConfirmDialog("Delete this backup?", "The backup from ${Dates.formatDay(b.createdAt)} will be permanently removed from your Google Drive.", "Delete",
            onConfirm = { vm.delete(b) }, onDismiss = { delete = null })
    }
}
