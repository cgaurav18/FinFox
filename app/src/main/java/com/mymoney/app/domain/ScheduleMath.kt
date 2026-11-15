package com.mymoney.app.domain

import com.mymoney.app.data.db.Frequency
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Occurrence dates are always computed from the start date (start + n periods) so month-end dates
 * don't drift: a schedule starting 31 Jan falls on 28/29 Feb and back on 31 Mar.
 */
object ScheduleMath {
    fun nth(start: LocalDate, f: Frequency, n: Long): LocalDate = when (f) {
        Frequency.DAILY -> start.plusDays(n)
        Frequency.WEEKLY -> start.plusWeeks(n)
        Frequency.MONTHLY -> start.plusMonths(n)
        Frequency.QUARTERLY -> start.plusMonths(3 * n)
        Frequency.YEARLY -> start.plusYears(n)
    }

    private fun estimate(start: LocalDate, f: Frequency, date: LocalDate): Long {
        if (!date.isAfter(start)) return 0
        val k = when (f) {
            Frequency.DAILY -> ChronoUnit.DAYS.between(start, date)
            Frequency.WEEKLY -> ChronoUnit.WEEKS.between(start, date)
            Frequency.MONTHLY -> ChronoUnit.MONTHS.between(start, date)
            Frequency.QUARTERLY -> ChronoUnit.MONTHS.between(start, date) / 3
            Frequency.YEARLY -> ChronoUnit.YEARS.between(start, date)
        }
        return (k - 1).coerceAtLeast(0)
    }

    /** First occurrence on or after [date]. */
    fun firstOnOrAfter(start: LocalDate, f: Frequency, date: LocalDate): LocalDate {
        var n = estimate(start, f, date)
        var d = nth(start, f, n)
        while (d.isBefore(date)) { n++; d = nth(start, f, n) }
        return d
    }

    /** First occurrence strictly after [date]. */
    fun nextAfter(start: LocalDate, f: Frequency, date: LocalDate): LocalDate =
        firstOnOrAfter(start, f, date.plusDays(1))

    /** Occurrences within [from, to] (inclusive), beginning at [firstDue] and bounded by [end]. */
    fun occurrencesBetween(
        start: LocalDate, f: Frequency, firstDue: LocalDate, end: LocalDate?, from: LocalDate, to: LocalDate, limit: Int = 400,
    ): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var d = if (firstDue.isBefore(from)) firstOnOrAfter(start, f, from) else firstDue
        while (!d.isAfter(to) && (end == null || !d.isAfter(end)) && out.size < limit) {
            out += d
            d = nextAfter(start, f, d)
        }
        return out
    }

    /** Number of unpaid installments that are already past due as of [today]. */
    fun overdueCount(start: LocalDate, f: Frequency, nextDue: LocalDate, end: LocalDate?, today: LocalDate): Int {
        if (!nextDue.isBefore(today)) return 0
        return occurrencesBetween(start, f, nextDue, end, nextDue, today.minusDays(1)).size
    }
}
