package com.mymoney.app.domain

import com.mymoney.app.core.DateRange
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.TransactionDetail
import com.mymoney.app.data.db.TxnType
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class Summary(val incomeMinor: Long, val expenseMinor: Long, val transferMinor: Long) {
    val netMinor: Long get() = incomeMinor - expenseMinor
}

data class Slice(val key: Long, val label: String, val colorArgb: Long, val valueMinor: Long, val percent: Int)

data class SeriesPoint(val label: String, val incomeMinor: Long, val expenseMinor: Long) {
    val netMinor get() = incomeMinor - expenseMinor
}

/** Pure aggregation helpers for dashboards and reports. Only INCOME/EXPENSE count as income/expense. */
object Reports {
    fun summary(txns: List<TransactionDetail>): Summary {
        var inc = 0L; var exp = 0L; var tr = 0L
        txns.forEach {
            when (it.txn.type) {
                TxnType.INCOME -> inc += it.txn.amountMinor
                TxnType.EXPENSE -> exp += it.txn.amountMinor
                TxnType.TRANSFER -> tr += it.txn.amountMinor
                else -> Unit
            }
        }
        return Summary(inc, exp, tr)
    }

    fun categoryBreakdown(txns: List<TransactionDetail>, type: TxnType = TxnType.EXPENSE): List<Slice> {
        val filtered = txns.filter { it.txn.type == type }
        val total = filtered.sumOf { it.txn.amountMinor }
        return filtered.groupBy { it.txn.categoryId ?: -1L }.map { (id, list) ->
            val first = list.first()
            val v = list.sumOf { it.txn.amountMinor }
            Slice(id, first.categoryName ?: "Uncategorised", first.categoryColor ?: 0xFF64748B, v, com.mymoney.app.core.Money.percent(v, total))
        }.sortedByDescending { it.valueMinor }
    }

    private val accountPalette = listOf(0xFF2E6F68, 0xFF4F7CAC, 0xFFC98A3D, 0xFF8A6FB0, 0xFF5E9C5A, 0xFFB46A8C, 0xFF4E9AA8, 0xFF64748B) // FinFox brand first

    fun accountSpending(txns: List<TransactionDetail>): List<Slice> {
        val filtered = txns.filter { it.txn.type == TxnType.EXPENSE }
        val total = filtered.sumOf { it.txn.amountMinor }
        return filtered.groupBy { it.txn.accountId }.entries.mapIndexed { i, (id, list) ->
            val v = list.sumOf { it.txn.amountMinor }
            Slice(id, list.first().accountName, accountPalette[i % accountPalette.size], v, com.mymoney.app.core.Money.percent(v, total))
        }.sortedByDescending { it.valueMinor }
    }

    /** Per-day points for short ranges, per-month points for ranges longer than ~2 months. */
    fun series(txns: List<TransactionDetail>, range: DateRange): List<SeriesPoint> {
        val days = ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1
        return if (days <= 62) {
            val byDay = txns.groupBy { Dates.toLocalDate(it.txn.dateMillis) }
            (0 until days).map { i ->
                val d = range.start.plusDays(i)
                val s = summary(byDay[d].orEmpty())
                SeriesPoint(d.dayOfMonth.toString(), s.incomeMinor, s.expenseMinor)
            }
        } else monthly(txns, YearMonth.from(range.start), YearMonth.from(range.endInclusive))
    }

    fun monthly(txns: List<TransactionDetail>, from: YearMonth, to: YearMonth): List<SeriesPoint> {
        val byMonth = txns.groupBy { YearMonth.from(Dates.toLocalDate(it.txn.dateMillis)) }
        val out = mutableListOf<SeriesPoint>()
        var ym = from
        while (!ym.isAfter(to)) {
            val s = summary(byMonth[ym].orEmpty())
            out += SeriesPoint(Dates.formatMonthShort(ym), s.incomeMinor, s.expenseMinor)
            ym = ym.plusMonths(1)
        }
        return out
    }

    fun groupByDay(txns: List<TransactionDetail>): List<Pair<LocalDate, List<TransactionDetail>>> =
        txns.groupBy { Dates.toLocalDate(it.txn.dateMillis) }.toList()

    /** Highest budget threshold (80/90/100) reached, or null. */
    fun budgetThreshold(spentMinor: Long, limitMinor: Long): Int? {
        if (limitMinor <= 0) return null
        val p = com.mymoney.app.core.Money.percent(spentMinor, limitMinor)
        return listOf(100, 90, 80).firstOrNull { p >= it }
    }
}
