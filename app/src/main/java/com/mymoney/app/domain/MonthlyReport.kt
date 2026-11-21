package com.mymoney.app.domain

import com.mymoney.app.core.Money
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** The month-end summary sent as a notification on the first days of the next month. */
data class MonthlyReport(val title: String, val text: String)

object MonthlyReports {
    /**
     * @param expenseByCategory expenses of [month] per category id
     * @param previousExpense total expenses of the month before, for the trend (null if unknown)
     * @param budget the month's final budget summary, when budgets were set
     * @param categoryName names for category ids
     * Returns null for a month with no income or expenses: there's nothing to report.
     */
    fun build(
        month: YearMonth,
        income: Long,
        expense: Long,
        expenseByCategory: Map<Long, Long>,
        previousExpense: Long?,
        budget: BudgetSummary?,
        categoryName: (Long) -> String,
        currency: String,
    ): MonthlyReport? {
        if (income == 0L && expense == 0L) return null
        fun m(v: Long) = Money.format(v, currency)
        val name = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val net = income - expense
        val title = when {
            net >= 0 && income > 0 -> "$name summary: you saved ${m(net)}"
            net >= 0 -> "$name summary: you spent ${m(expense)}"
            else -> "$name summary: spent ${m(-net)} more than you earned"
        }
        val lines = mutableListOf<String>()
        lines += buildString {
            append("Income ${m(income)} · Expenses ${m(expense)}")
            if (income > 0 && net > 0) append(" · Saved ${(net * 100 / income)}%")
        }
        budget?.takeIf { it.lines.isNotEmpty() }?.let { b ->
            val kept = b.lines.count { it.spentMinor <= it.limitMinor }
            lines += buildString {
                append("Budgets: $kept of ${b.lines.size} kept")
                append(if (b.remainingMinor >= 0) ", ${m(b.remainingMinor)} under in total." else ", ${m(-b.remainingMinor)} (${b.usedPercent - 100}%) over in total.")
            }
            b.lines.filter { it.spentMinor > it.limitMinor }.sortedByDescending { it.spentMinor - it.limitMinor }.take(2).forEach {
                lines += "${categoryName(it.categoryId)} over by ${m(-it.remainingMinor)} (${it.vsLimitPercent}%)"
            }
        }
        expenseByCategory.maxByOrNull { it.value }?.let { (id, v) -> lines += "Biggest spend: ${categoryName(id)} ${m(v)}" }
        previousExpense?.takeIf { it > 0 }?.let { prev ->
            val pct = ((expense - prev) * 100 / prev).toInt()
            val prevName = month.minusMonths(1).month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            lines += when {
                pct > 0 -> "Spending up $pct% vs $prevName"
                pct < 0 -> "Spending down ${-pct}% vs $prevName"
                else -> "Spending the same as $prevName"
            }
        }
        return MonthlyReport(title, lines.joinToString("\n"))
    }
}
