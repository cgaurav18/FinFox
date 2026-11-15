package com.mymoney.app.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountWithBalance
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.data.db.RepaymentDetail
import com.mymoney.app.data.repo.SettleMode
import com.mymoney.app.ui.components.AmountField
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.DateField
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.IconBadge
import com.mymoney.app.ui.components.KeyValueRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.LocalCurrency
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.ProgressBar
import com.mymoney.app.ui.components.SecondaryButton
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinFoxTheme
import java.time.LocalDate

@Composable
fun LoanDetailScreen(nav: NavController, vm: LoanDetailViewModel = hiltViewModel()) {
    val l by vm.loan.collectAsStateWithLifecycle()
    val reps by vm.repayments.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var addRep by remember { mutableStateOf(false) }
    var editRep by remember { mutableStateOf<RepaymentDetail?>(null) }
    var delRep by remember { mutableStateOf<RepaymentDetail?>(null) }
    var settle by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    val currency = LocalCurrency.current
    val fin = FinFoxTheme.colors
    LaunchedEffect(Unit) { vm.done.collect { nav.popBackStack() } }
    AppScaffold(
        title = "Loan Details", onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = { nav.navigate(Routes.loanEdit(vm.id)) }) { Icon(Icons.Filled.Edit, "Edit") }
            IconButton(onClick = { del = true }) { Icon(Icons.Filled.Delete, "Delete") }
        },
    ) { pad ->
        val d = l ?: run { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        val gave = d.loan.direction == LoanDirection.GAVE
        val due = d.loan.dueEpochDay?.let(LocalDate::ofEpochDay)
        val overdue = !d.loan.settled && due != null && due.isBefore(Dates.today())
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { nav.navigate(Routes.person(d.loan.personId)) }) {
                        IconBadge(Icons.Filled.Person, if (gave) fin.income else fin.warning, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.personName, style = MaterialTheme.typography.titleMedium)
                            Text(if (gave) "You gave money (lent)" else "You received money (borrowed)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (d.loan.settled) Chip("Settled", fin.income) else if (overdue) Chip("Overdue", fin.expense)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(if (gave) "Outstanding to receive" else "Outstanding to pay", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Money.format(d.outstandingMinor, currency), style = MaterialTheme.typography.headlineMedium, color = if (gave) fin.income else fin.expense)
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(if (d.loan.amountMinor > 0) d.repaidMinor.toFloat() / d.loan.amountMinor else 0f, fin.income)
                    Spacer(Modifier.height(4.dp))
                    Text("${Money.format(d.repaidMinor, currency)} of ${Money.format(d.loan.amountMinor, currency)} repaid", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!d.loan.settled) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(if (gave) "Record repayment received" else "Record repayment made", { addRep = true }, loading = busy, icon = Icons.Filled.Payments)
                    SecondaryButton("Mark as settled", { settle = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.CheckCircle)
                }
            } else if (d.loan.writtenOffMinor > 0 || d.repaidMinor < d.loan.amountMinor) item {
                SecondaryButton("Reopen entry", vm::reopen, Modifier.fillMaxWidth(), icon = Icons.Filled.Replay)
            }
            item {
                AppCard {
                    KeyValueRow("Amount", Money.format(d.loan.amountMinor, currency))
                    KeyValueRow("Date", Dates.formatDay(d.loan.dateMillis))
                    KeyValueRow("Due date", due?.let { Dates.formatDay(it) + if (!d.loan.settled) " (${Dates.dueLabel(it)})" else "" } ?: "Not set", if (overdue) fin.expense else Color.Unspecified)
                    KeyValueRow(if (gave) "Paid from" else "Received into", d.accountName)
                    d.personPhone?.let { KeyValueRow("Phone", it) }
                    d.loan.description?.let { KeyValueRow("Description", it) }
                    KeyValueRow("Reminder", if (d.loan.reminderEnabled) "On" else "Off")
                    if (d.loan.writtenOffMinor > 0) KeyValueRow("Written off", Money.format(d.loan.writtenOffMinor, currency))
                    d.loan.settledAt?.let { KeyValueRow("Settled on", Dates.formatDay(it)) }
                }
            }
            item { SectionHeader("Repayment history") }
            item {
                AppCard {
                    val list = reps
                    when {
                        list == null -> LoadingState()
                        list.isEmpty() -> Text("No repayments yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> list.forEach { r ->
                            Row(Modifier.fillMaxWidth().clickable { editRep = r }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(Money.format(r.repayment.amountMinor, currency), style = MaterialTheme.typography.titleSmall, color = if (gave) fin.income else fin.expense)
                                    Text("${Dates.formatDay(r.repayment.dateMillis)} • ${r.accountName}" + (r.repayment.note?.let { " • $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { delRep = r }) { Icon(Icons.Filled.Delete, "Delete repayment", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }
    val d = l
    if (addRep && d != null) RepaymentDialog("Record repayment", accounts, d.outstandingMinor, null, d.loan.accountId, { addRep = false }) { a, date, acc, note -> vm.addRepayment(a, date, acc, note) }
    editRep?.let { r -> RepaymentDialog("Edit repayment", accounts, null, r, r.repayment.accountId, { editRep = null }) { a, date, acc, note -> vm.updateRepayment(r.repayment.id, a, date, acc, note) } }
    delRep?.let { r -> ConfirmDialog("Delete repayment?", "The ${Money.format(r.repayment.amountMinor, currency)} repayment and its account entry will be removed and the outstanding amount increases.", "Delete", { vm.deleteRepayment(r.repayment.id) }, { delRep = null }) }
    if (settle && d != null) SettleDialog(d.outstandingMinor, d.loan.direction == LoanDirection.GAVE, accounts, d.loan.accountId, { settle = false }, vm::settle)
    if (del) ConfirmDialog("Delete entry?", "This removes the entry, all its repayments and their account transactions. Balances are recalculated.", "Delete", vm::delete, { del = false })
}

@Composable
private fun RepaymentDialog(
    title: String,
    accounts: List<AccountWithBalance>,
    outstanding: Long?,
    existing: RepaymentDetail?,
    defaultAccount: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long, LocalDate, Long?, String) -> Unit,
) {
    var amount by remember { mutableStateOf(existing?.repayment?.amountMinor?.let(Money::toInput) ?: outstanding?.let(Money::toInput) ?: "") }
    var date by remember { mutableStateOf(existing?.repayment?.dateMillis?.let(Dates::toLocalDate) ?: Dates.today()) }
    var accountId by remember { mutableStateOf<Long?>(existing?.repayment?.accountId ?: accounts.firstOrNull { it.account.id == defaultAccount }?.account?.id ?: accounts.firstOrNull()?.account?.id) }
    var note by remember { mutableStateOf(existing?.repayment?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val currency = LocalCurrency.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                outstanding?.let { Text("Outstanding: ${Money.format(it, currency)}. Enter a smaller amount for a partial repayment.", style = MaterialTheme.typography.bodySmall) }
                AmountField(amount, { amount = it; error = null }, error = error)
                DropdownField("Account", accounts, accounts.firstOrNull { it.account.id == accountId }, { it.account.name }, { accountId = it.account.id },
                    leading = { Icon(AppIcons.forAccount(it.account.type), null, tint = Color(it.account.colorArgb)) })
                DateField("Date", date, { date = it }, maxDate = Dates.today())
                AppTextField(note, { note = it }, "Note (optional)")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val r = Money.parse(amount)) {
                    is Money.ParseResult.Ok -> { onConfirm(r.minor, date, accountId, note); onDismiss() }
                    is Money.ParseResult.Error -> error = r.message
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettleDialog(outstanding: Long, gave: Boolean, accounts: List<AccountWithBalance>, defaultAccount: Long, onDismiss: () -> Unit, onSettle: (SettleMode) -> Unit) {
    var record by remember { mutableStateOf(true) }
    var accountId by remember { mutableStateOf(accounts.firstOrNull { it.account.id == defaultAccount }?.account?.id ?: accounts.firstOrNull()?.account?.id) }
    val currency = LocalCurrency.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark as settled") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${Money.format(outstanding, currency)} is still outstanding.", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { record = true }) {
                    RadioButton(record, { record = true }); Text(if (gave) "I received the remaining amount" else "I paid the remaining amount")
                }
                if (record) DropdownField("Account", accounts, accounts.firstOrNull { it.account.id == accountId }, { it.account.name }, { accountId = it.account.id })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { record = false }) {
                    RadioButton(!record, { record = false }); Text(if (gave) "Forgive / write off (no money received)" else "Forgiven (no payment made)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val acc = accountId
                if (record && acc != null) onSettle(SettleMode.RecordRemaining(acc)) else if (!record) onSettle(SettleMode.WriteOff)
                onDismiss()
            }) { Text("Settle") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
