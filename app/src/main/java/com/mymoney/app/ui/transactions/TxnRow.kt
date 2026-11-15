package com.mymoney.app.ui.transactions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.TransactionDetail
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.ListRow
import com.mymoney.app.ui.components.MoneyText
import com.mymoney.app.ui.theme.FinanceColors
import com.mymoney.app.ui.theme.FinanceTheme

data class TxnLook(val title: String, val subtitle: String, val icon: ImageVector, val color: Color, val signedAmount: Long, val amountColor: Color)

fun txnTypeLabel(t: TxnType) = when (t) {
    TxnType.INCOME -> "Income"; TxnType.EXPENSE -> "Expense"; TxnType.TRANSFER -> "Transfer"
    TxnType.LOAN_GIVEN -> "Money lent"; TxnType.LOAN_TAKEN -> "Money borrowed"
    TxnType.LOAN_REPAYMENT_RECEIVED -> "Repayment received"; TxnType.LOAN_REPAYMENT_PAID -> "Repayment paid"
}

/** Presentation rules for a ledger row; [contextAccountId] flips transfer signs on an account's own history. */
fun txnLook(d: TransactionDetail, fin: FinanceColors, neutral: Color, contextAccountId: Long? = null, showTime: Boolean = true): TxnLook {
    val t = d.txn
    val time = if (showTime) Dates.formatTime(t.dateMillis) else Dates.formatDay(t.dateMillis)
    val acct = d.accountName + if (d.accountType == AccountType.CREDIT_CARD) " (card)" else ""
    return when (t.type) {
        TxnType.INCOME -> TxnLook(t.merchant ?: d.categoryName ?: "Income", "$acct • $time", AppIcons.forKey(d.categoryIconKey), Color(d.categoryColor ?: 0xFF16A34A), t.amountMinor, fin.income)
        TxnType.EXPENSE -> TxnLook(t.merchant ?: d.categoryName ?: "Expense", "${d.categoryName ?: ""} • $acct • $time".trimStart(' ', '•'), AppIcons.forKey(d.categoryIconKey), Color(d.categoryColor ?: 0xFFDC2626), -t.amountMinor, fin.expense)
        TxnType.TRANSFER -> {
            val toCard = d.toAccountType == AccountType.CREDIT_CARD
            val sign = when (contextAccountId) { null -> 0; t.accountId -> -1; else -> 1 }
            TxnLook(
                if (toCard) "Card bill: ${d.toAccountName}" else "Transfer to ${d.toAccountName}",
                "From ${d.accountName} • $time", Icons.Filled.SwapHoriz, fin.transfer,
                if (sign == 0) t.amountMinor else sign * t.amountMinor, if (sign > 0) fin.income else if (sign < 0) fin.expense else neutral,
            )
        }
        TxnType.LOAN_GIVEN -> TxnLook("Lent to ${d.personName ?: t.merchant ?: "someone"}", "$acct • $time", Icons.AutoMirrored.Filled.CallMade, fin.warning, -t.amountMinor, fin.expense)
        TxnType.LOAN_TAKEN -> TxnLook("Borrowed from ${d.personName ?: t.merchant ?: "someone"}", "$acct • $time", Icons.AutoMirrored.Filled.CallReceived, fin.warning, t.amountMinor, fin.income)
        TxnType.LOAN_REPAYMENT_RECEIVED -> TxnLook("Repaid by ${d.personName ?: t.merchant ?: "someone"}", "$acct • $time", Icons.AutoMirrored.Filled.CallReceived, fin.income, t.amountMinor, fin.income)
        TxnType.LOAN_REPAYMENT_PAID -> TxnLook("Repaid to ${d.personName ?: t.merchant ?: "someone"}", "$acct • $time", Icons.AutoMirrored.Filled.CallMade, fin.expense, -t.amountMinor, fin.expense)
    }
}

@Composable
fun TxnRow(d: TransactionDetail, onClick: () -> Unit, contextAccountId: Long? = null, showTime: Boolean = true) {
    val look = txnLook(d, FinanceTheme.colors, MaterialTheme.colorScheme.onSurface, contextAccountId, showTime)
    ListRow(look.icon, look.color, look.title, look.subtitle, onClick = onClick) {
        MoneyText(look.signedAmount, color = look.amountColor, signed = look.signedAmount > 0 && (d.txn.type != TxnType.TRANSFER || contextAccountId != null), style = MaterialTheme.typography.titleSmall)
    }
}
