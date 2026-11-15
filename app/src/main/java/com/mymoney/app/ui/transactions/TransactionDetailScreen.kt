package com.mymoney.app.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.IconBadge
import com.mymoney.app.ui.components.KeyValueRow
import com.mymoney.app.ui.components.LocalCurrency
import com.mymoney.app.ui.components.SecondaryButton
import com.mymoney.app.ui.components.TonalButton
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinFoxTheme

@Composable
fun TransactionDetailScreen(nav: NavController, vm: TransactionDetailViewModel = hiltViewModel()) {
    val d by vm.detail.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val currency = LocalCurrency.current
    LaunchedEffect(Unit) { vm.done.collect { nav.popBackStack() } }
    AppScaffold(title = "Transaction Details", onBack = { nav.popBackStack() }) { pad ->
        val detail = d
        if (detail == null) { EmptyState(Icons.Filled.ReceiptLong, "Transaction not found", "It may have been deleted.", Modifier.padding(pad)); return@AppScaffold }
        val t = detail.txn
        val look = txnLook(detail, FinFoxTheme.colors, MaterialTheme.colorScheme.onSurface)
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IconBadge(look.icon, look.color, 64.dp)
                Spacer(Modifier.height(12.dp))
                Text(look.title, style = MaterialTheme.typography.titleMedium)
                Text(Money.format(t.amountMinor, currency), style = MaterialTheme.typography.headlineLarge, color = look.amountColor)
            }
            AppCard {
                KeyValueRow("Type", txnTypeLabel(t.type))
                detail.categoryName?.let { KeyValueRow("Category", it) }
                KeyValueRow(if (t.type == TxnType.TRANSFER) "From" else "Account", detail.accountName)
                detail.toAccountName?.let { KeyValueRow("To", it) }
                KeyValueRow("Date", Dates.formatDay(t.dateMillis))
                KeyValueRow("Time", Dates.formatTime(t.dateMillis))
                (t.merchant ?: detail.personName)?.let { KeyValueRow(if (t.type.isLoan) "Person" else "Merchant", it) }
                t.note?.let { KeyValueRow("Note", it) }
                if (t.tags.isNotBlank()) KeyValueRow("Tags", t.tags.split(',').joinToString("  ") { "#$it" })
                if (t.scheduleId != null) KeyValueRow("Source", "Scheduled payment")
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                KeyValueRow("Recorded", Dates.formatDay(t.createdAt) + " " + Dates.formatTime(t.createdAt))
                if (t.updatedAt != t.createdAt) KeyValueRow("Last edited", Dates.formatDay(t.updatedAt) + " " + Dates.formatTime(t.updatedAt))
            }
            if (t.loanId != null) {
                Text("This entry belongs to a lending/borrowing record. Edit or delete it from Money with People so repayments stay consistent.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TonalButton("Open in Money with People", { nav.navigate(Routes.loanDetail(t.loanId)) }, Modifier.fillMaxWidth(), icon = Icons.Filled.Groups)
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Edit", { nav.navigate(Routes.txnEdit(t.id)) }, Modifier.weight(1f), icon = Icons.Filled.Edit)
                SecondaryButton("Delete", { confirm = true }, Modifier.weight(1f), icon = Icons.Filled.Delete, destructive = true)
            }
        }
    }
    if (confirm) ConfirmDialog(
        "Delete transaction?",
        "The account balance will be recalculated" + if (d?.txn?.scheduleId != null) " and the scheduled installment will be marked unpaid again." else ".",
        "Delete", vm::delete, { confirm = false },
    )
}
