package com.mymoney.app.domain

import java.time.LocalDate
import java.time.YearMonth

/** Where a budget stands for the month. */
enum class BudgetStatus { ON_TRACK, AT_RISK, OVER }

/**
 * One category budget with the numbers people actually ask about: how much is used, what's left, how much
 * can still be spent per day, where spending will land at the current pace, and how it compares to last month.
 */
data class BudgetLine(
    val categoryId: Long,
    val limitMinor: Long,
    val spentMinor: Long,
    /** Spent in the same category last month (null when unknown). */
    val lastMonthMinor: Long?,
    /** Expected month-end spending at the current daily pace; equals [spentMinor] for a finished month. */
    val projectedMinor: Long,
    /** What can still be spent per remaining day (0 when nothing is left or the month is over). */
    val perDayLeftMinor: Long,
    val status: BudgetStatus,
) {
    val remainingMinor get() = limitMinor - spentMinor
    /** Share of the limit used, e.g. 112 when 12% over. */
    val usedPercent: Int get() = if (limitMinor <= 0) 0 else ((spentMinor * 100) / limitMinor).toInt()
    /** Positive: % above the limit; negative: % below it. */
    val vsLimitPercent: Int get() = usedPercent - 100
    /** Change against last month, in % (null without last month's figure or when it was zero). */
    val vsLastMonthPercent: Int? get() = lastMonthMinor?.takeIf { it > 0 }?.let { (((spentMinor - it) * 100) / it).toInt() }
}

data class BudgetSummary(
    val month: YearMonth,
    val lines: List<BudgetLine>,
    val totalLimitMinor: Long,
    val totalSpentMinor: Long,
    val projectedMinor: Long,
    val perDayLeftMinor: Long,
    /** Expense spending in categories that have no budget this month. */
    val unbudgetedMinor: Long,
    val daysLeft: Int,
    /** The month has ended: numbers are final. */
    val finished: Boolean,
) {
    val remainingMinor get() = totalLimitMinor - totalSpentMinor
    val usedPercent: Int get() = if (totalLimitMinor <= 0) 0 else ((totalSpentMinor * 100) / totalLimitMinor).toInt()
    val onTrack get() = lines.count { it.status == BudgetStatus.ON_TRACK }
    val atRisk get() = lines.count { it.status == BudgetStatus.AT_RISK }
    val over get() = lines.count { it.status == BudgetStatus.OVER }
    val status: BudgetStatus get() = when {
        totalSpentMinor > totalLimitMinor -> BudgetStatus.OVER
        projectedMinor > totalLimitMinor -> BudgetStatus.AT_RISK
        else -> BudgetStatus.ON_TRACK
    }
}

object BudgetInsights {
    /**
     * @param budgets categoryId → monthly limit
     * @param spentByCategory this month's expenses per category (all categories, budgeted or not)
     * @param lastMonthByCategory last month's expenses per category
     * @param today used to work out the pace; a month in the past is treated as finished, a future one as not started
     */
    fun summarize(
        month: YearMonth,
        budgets: Map<Long, Long>,
        spentByCategory: Map<Long, Long>,
        lastMonthByCategory: Map<Long, Long>?,
        today: LocalDate,
    ): BudgetSummary {
        val days = month.lengthOfMonth()
        val current = YearMonth.from(today)
        val (elapsed, finished) = when {
            month.isBefore(current) -> days to true
            month.isAfter(current) -> 0 to false
            else -> today.dayOfMonth to false
        }
        // Days left including today, so the allowance covers today's spending too.
        val daysLeft = if (finished) 0 else days - elapsed + 1

        fun project(spent: Long): Long = when {
            finished -> spent
            elapsed <= 0 -> spent
            else -> spent * days / elapsed
        }
        fun perDay(limit: Long, spent: Long): Long = if (daysLeft <= 0 || spent >= limit) 0 else (limit - spent) / daysLeft
        fun status(limit: Long, spent: Long, projected: Long) = when {
            spent > limit -> BudgetStatus.OVER
            // Only judge the pace once a few days have passed; day-one spending says little.
            !finished && elapsed >= 3 && projected > limit -> BudgetStatus.AT_RISK
            else -> BudgetStatus.ON_TRACK
        }

        val lines = budgets.map { (cat, limit) ->
            val spent = spentByCategory[cat] ?: 0
            val projected = project(spent)
            BudgetLine(cat, limit, spent, lastMonthByCategory?.let { it[cat] ?: 0 }, projected, perDay(limit, spent), status(limit, spent, projected))
        }.sortedWith(compareBy<BudgetLine>({ it.status != BudgetStatus.OVER }, { it.status != BudgetStatus.AT_RISK }, { -it.usedPercent }))

        val totalLimit = lines.sumOf { it.limitMinor }
        val totalSpent = lines.sumOf { it.spentMinor }
        return BudgetSummary(
            month = month, lines = lines, totalLimitMinor = totalLimit, totalSpentMinor = totalSpent,
            projectedMinor = project(totalSpent), perDayLeftMinor = perDay(totalLimit, totalSpent),
            unbudgetedMinor = spentByCategory.filterKeys { it !in budgets }.values.sum(),
            daysLeft = daysLeft, finished = finished,
        )
    }
}
