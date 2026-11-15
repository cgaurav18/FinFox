package com.mymoney.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object Dates {
    val zone: ZoneId get() = ZoneId.systemDefault()

    private val dayFmt = DateTimeFormatter.ofPattern("dd MMM yyyy")
    private val dayShortFmt = DateTimeFormatter.ofPattern("dd MMM")
    private val timeFmt = DateTimeFormatter.ofPattern("hh:mm a")
    private val monthFmt = DateTimeFormatter.ofPattern("MMM yyyy")
    private val monthShortFmt = DateTimeFormatter.ofPattern("MMM")

    fun today(): LocalDate = LocalDate.now(zone)
    fun nowMillis(): Long = System.currentTimeMillis()

    fun toLocalDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    fun toLocalDateTime(millis: Long): LocalDateTime = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()
    fun startOfDay(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
    /** Exclusive end: start of the next day. */
    fun endOfDayExclusive(date: LocalDate): Long = startOfDay(date.plusDays(1))
    fun toMillis(date: LocalDate, time: LocalTime): Long = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

    fun formatDay(date: LocalDate): String = date.format(dayFmt)
    fun formatDay(millis: Long): String = formatDay(toLocalDate(millis))
    fun formatDayShort(date: LocalDate): String = date.format(dayShortFmt)
    fun formatTime(millis: Long): String = toLocalDateTime(millis).format(timeFmt)
    fun formatMonth(ym: YearMonth): String = ym.format(monthFmt)
    fun formatMonthShort(ym: YearMonth): String = ym.format(monthShortFmt)

    fun relativeDayLabel(date: LocalDate, today: LocalDate = today()): String = when (date) {
        today -> "Today, ${formatDay(date)}"
        today.minusDays(1) -> "Yesterday, ${formatDay(date)}"
        else -> formatDay(date)
    }

    fun dueLabel(due: LocalDate, today: LocalDate = today()): String {
        val d = ChronoUnit.DAYS.between(today, due)
        return when {
            d == 0L -> "Due today"
            d == 1L -> "Due tomorrow"
            d > 1 -> "Due in $d days"
            d == -1L -> "Overdue by 1 day"
            else -> "Overdue by ${-d} days"
        }
    }

    fun ymKey(ym: YearMonth): Int = ym.year * 100 + ym.monthValue
    fun ymFromKey(key: Int): YearMonth = YearMonth.of(key / 100, key % 100)
}

/** Half-open millisecond range [startMillis, endMillisExclusive). */
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    val startMillis: Long get() = Dates.startOfDay(start)
    val endMillisExclusive: Long get() = Dates.endOfDayExclusive(endInclusive)
    fun contains(millis: Long) = millis >= startMillis && millis < endMillisExclusive

    companion object {
        fun month(ym: YearMonth) = DateRange(ym.atDay(1), ym.atEndOfMonth())
    }
}

enum class PeriodFilter(val label: String) {
    TODAY("Today"), YESTERDAY("Yesterday"), LAST_7_DAYS("Last 7 Days"),
    THIS_MONTH("This Month"), LAST_MONTH("Last Month"), CUSTOM("Custom");

    fun range(today: LocalDate, custom: DateRange?): DateRange = when (this) {
        TODAY -> DateRange(today, today)
        YESTERDAY -> today.minusDays(1).let { DateRange(it, it) }
        LAST_7_DAYS -> DateRange(today.minusDays(6), today)
        THIS_MONTH -> DateRange.month(YearMonth.from(today))
        LAST_MONTH -> DateRange.month(YearMonth.from(today).minusMonths(1))
        CUSTOM -> custom ?: DateRange.month(YearMonth.from(today))
    }
}
