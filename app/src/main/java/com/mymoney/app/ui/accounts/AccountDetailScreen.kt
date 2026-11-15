package com.mymoney.app.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.ui.components.AmountEntryDialog
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.KeyValueRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.ProgressBar
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.components.TonalButton
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinanceTheme
import com.mymoney.app.ui.transactions.TxnRow

@Composable
fun AccountDetailScreen(nav: NavController, vm: AccountDetailViewModel = hiltViewModel()) {
    val a by vm.account.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val adjustments by vm.adjustments.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    var reconcile by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.done.collect { nav.popBackStack() } }
    val acc = a?.account
    AppScaffold(
        title = acc?.name ?: "Account", onBack = { nav.popBackStack() },
        actions = {
            if (acc != null) {
                IconButton(onClick = { nav.navigate(Routes.accountEdit(acc.id)) }) { Icon(Icons.Filled.Edit, "Edit") }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(if (acc.archived) "Restore from archive" else "Archive") }, leadingIcon = { Icon(if (acc.archived) Icons.Filled.Unarchive else Icons.Filled.Archive, null) },
                        onClick = { menu = false; if (acc.archived) vm.setArchived(false) else confirmArchive = true })
                    DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; confirmDelete = true })
                }
            }
        },
    ) { pad ->
        val awb = a
        if (awb == null || acc == null) { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        val isCard = acc.type == AccountType.CREDIT_CARD
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(acc.colorArgb), Color(acc.colorArgb).copy(alpha = 0.75f)))).padding(20.dp)) {
                    Text(if (isCard) "Outstanding" else "Current balance", color = Color.White.copy(alpha = 0.85f))
                    Text(Money.format(if (isCard) awb.outstandingMinor else awb.balanceMinor, acc.currency), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    if (isCard && acc.creditLimitMinor != null) {
                        val used = awb.outstandingMinor.coerceAtLeast(0)
                        Spacer(Modifier.height(10.dp))
                        ProgressBar(used.toFloat() / acc.creditLimitMinor, Color.White)
                        Spacer(Modifier.height(6.dp))
                        Text("${Money.percent(used, acc.creditLimitMinor)}% used • Available credit ${Money.format((acc.creditLimitMinor - used).coerceAtLeast(0), acc.currency)}", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                    }
                    if (acc.archived) Text("Archived — hidden from totals and pickers", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item {
                AppCard {
                    KeyValueRow("Type", when (acc.type) { AccountType.BANK -> "Bank account" + (acc.bankAccountType?.let { " ($it)" } ?: ""); AccountType.CREDIT_CARD -> "Credit card"; AccountType.CASH -> "Cash wallet" })
                    acc.bankName?.let { KeyValueRow("Bank", it) }
                    acc.last4?.let { KeyValueRow("Number", "•••• $it") }
                    if (isCard) {
                        acc.creditLimitMinor?.let { KeyValueRow("Credit limit", Money.format(it, acc.currency)) }
                        acc.statementDay?.let { KeyValueRow("Statement day", "Day $it of each month") }
                        acc.dueDay?.let { KeyValueRow("Payment due", "Day $it of each month") }
                    }
                    KeyValueRow(if (isCard) "Opening outstanding" else "Opening balance", Money.format(if (isCard) -acc.openingBalanceMinor else acc.openingBalanceMinor, acc.currency))
                    acc.description?.let { KeyValueRow("Description", it) }
                    KeyValueRow("Currency", acc.currency)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonalButton(if (isCard) "Pay bill" else "Add entry", {
                        nav.navigate(if (isCard) Routes.txnEdit(type = "TRANSFER") else Routes.txnEdit(type = "EXPENSE"))
                    }, Modifier.weight(1f))
                    TonalButton("Fix balance", { reconcile = true }, Modifier.weight(1f), icon = Icons.Filled.Tune)
                }
            }
            if (adjustments.isNotEmpty()) {
                item { SectionHeader("Balance adjustments (audit trail)") }
                item {
                    AppCard {
                        adjustments.forEach { adj ->
                            val sign = if (isCard) -1 else 1
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text("${Money.format(sign * adj.oldOpeningMinor, acc.currency)} → ${Money.format(sign * adj.newOpeningMinor, acc.currency)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("${adj.reason ?: "Adjusted"} • ${Dates.formatDay(adj.createdAt)} ${Dates.formatTime(adj.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Transaction history") }
            val h = history
            when {
                h == null -> item { LoadingState() }
                h.isEmpty() -> item { AppCard { EmptyState(Icons.Filled.ReceiptLong, "No transactions", "Transactions using this account will appear here.") } }
                else -> item { AppCard(contentPadding = 0.dp) { h.take(200).forEach { TxnRow(it, { nav.navigate(Routes.txnDetail(it.txn.id)) }, contextAccountId = acc.id, showTime = false) } } }
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete account?", "Accounts with transactions, schedules or loans can't be deleted — archive them instead so your history stays intact.", "Delete", vm::delete, { confirmDelete = false })
    if (confirmArchive) ConfirmDialog("Archive account?", "It will be hidden from totals and pickers. Its transactions remain in your history and you can restore it any time.", "Archive", { vm.setArchived(true) }, { confirmArchive = false }, destructive = false)
    if (reconcile && acc != null) {
        val isCard = acc.type == AccountType.CREDIT_CARD
        val current = if (isCard) a?.outstandingMinor ?: 0 else a?.balanceMinor ?: 0
        AmountEntryDialog(
            title = if (isCard) "Set actual outstanding" else "Set actual balance",
            confirmLabel = "Update",
            onDismiss = { reconcile = false },
            onConfirm = { amount, _, note -> vm.reconcile(amount, isCard, note.ifBlank { "Balance reconciliation" }) },
            initialAmount = Money.toInput(current.coerceAtLeast(0)),
            showDate = false, noteLabel = "Reason", allowZero = true,
            helper = "Enter what your bank/statement shows. The difference adjusts the opening amount and is recorded in the audit trail — no income or expense is created.",
        )
    }
}
