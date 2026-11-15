package com.mymoney.app.ui.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AccountWithBalance
import com.mymoney.app.data.db.ScheduledPaymentEntity
import com.mymoney.app.ui.components.AmountField
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.DateField
import com.mymoney.app.ui.components.DropdownField
import java.time.LocalDate

fun frequencyText(s: ScheduledPaymentEntity): String {
    val start = LocalDate.ofEpochDay(s.startEpochDay)
    return when (s.frequency) {
        com.mymoney.app.data.db.Frequency.DAILY -> "Every day"
        com.mymoney.app.data.db.Frequency.WEEKLY -> "Every ${start.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}"
        com.mymoney.app.data.db.Frequency.MONTHLY -> "${ordinal(start.dayOfMonth)} every month"
        com.mymoney.app.data.db.Frequency.QUARTERLY -> "${ordinal(start.dayOfMonth)} every quarter"
        com.mymoney.app.data.db.Frequency.YEARLY -> "Every ${Dates.formatDayShort(start)}"
    }
}

fun ordinal(n: Int) = n.toString() + when { n in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

/** Confirms the amount, account and date before recording an installment as paid. */
@Composable
fun MarkPaidDialog(
    s: ScheduledPaymentEntity,
    accounts: List<AccountWithBalance>,
    onDismiss: () -> Unit,
    onConfirm: (amount: Long, accountId: Long, date: LocalDate) -> Unit,
) {
    var amount by remember { mutableStateOf(Money.toInput(s.amountMinor)) }
    var accountId by remember { mutableStateOf(s.accountId) }
    var date by remember { mutableStateOf(Dates.today()) }
    var error by remember { mutableStateOf<String?>(null) }
    // Card bills (transfers) can't be paid from a credit card.
    val options = accounts.filter { s.toAccountId == null || (it.account.type != AccountType.CREDIT_CARD && it.account.id != s.toAccountId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark as paid") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${s.title} due ${Dates.formatDay(LocalDate.ofEpochDay(s.nextDueEpochDay))}. A transaction will be recorded and the account balance updated.", style = MaterialTheme.typography.bodySmall)
                AmountField(amount, { amount = it; error = null }, error = error)
                DropdownField("Paid from", options, options.firstOrNull { it.account.id == accountId }, { it.account.name }, { accountId = it.account.id },
                    leading = { Icon(AppIcons.forAccount(it.account.type), null, tint = Color(it.account.colorArgb)) })
                DateField("Paid on", date, { date = it }, maxDate = Dates.today())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val r = Money.parse(amount)) {
                    is Money.ParseResult.Ok -> { onConfirm(r.minor, accountId, date); onDismiss() }
                    is Money.ParseResult.Error -> error = r.message
                }
            }) { Text("Mark paid") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
