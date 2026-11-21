package com.mymoney.app.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class BudgetInsightsTest {
    private val nov = YearMonth.of(2026, 11) // 30 days
    private val food = 1L; private val fuel = 2L; private val shopping = 3L

    @Test fun midMonthPaceAllowanceAndStatus() {
        // 21 Nov: 21 days elapsed, 10 left (incl. today).
        val s = BudgetInsights.summarize(
            nov, budgets = mapOf(food to 6_000_00, fuel to 3_000_00),
            spentByCategory = mapOf(food to 5_250_00, fuel to 1_400_00, shopping to 900_00),
            lastMonthByCategory = mapOf(food to 4_200_00, fuel to 2_000_00),
            today = LocalDate.of(2026, 11, 21),
        )
        val f = s.lines.first { it.categoryId == food }
        assertThat(f.usedPercent).isEqualTo(87)
        assertThat(f.remainingMinor).isEqualTo(750_00)
        assertThat(f.perDayLeftMinor).isEqualTo(75_00)             // ₹750 over 10 days
        assertThat(f.projectedMinor).isEqualTo(7_500_00)           // 5250 / 21 * 30
        assertThat(f.status).isEqualTo(BudgetStatus.AT_RISK)        // under the limit, but the pace lands above it
        assertThat(f.vsLastMonthPercent).isEqualTo(25)             // 5250 vs 4200
        val fu = s.lines.first { it.categoryId == fuel }
        assertThat(fu.status).isEqualTo(BudgetStatus.ON_TRACK)
        assertThat(fu.vsLastMonthPercent).isEqualTo(-30)
        assertThat(s.unbudgetedMinor).isEqualTo(900_00)
        assertThat(s.daysLeft).isEqualTo(10)
        assertThat(s.lines.first().categoryId).isEqualTo(food)     // most urgent first
    }

    @Test fun overBudgetIsReportedAsPercentAbove() {
        val s = BudgetInsights.summarize(nov, mapOf(food to 5_000_00), mapOf(food to 5_600_00), null, LocalDate.of(2026, 11, 25))
        val f = s.lines.single()
        assertThat(f.status).isEqualTo(BudgetStatus.OVER)
        assertThat(f.vsLimitPercent).isEqualTo(12)
        assertThat(f.perDayLeftMinor).isEqualTo(0)
        assertThat(s.status).isEqualTo(BudgetStatus.OVER)
    }

    @Test fun finishedMonthUsesFinalNumbers() {
        val s = BudgetInsights.summarize(YearMonth.of(2026, 10), mapOf(food to 5_000_00), mapOf(food to 4_000_00), null, LocalDate.of(2026, 11, 2))
        assertThat(s.finished).isTrue()
        assertThat(s.daysLeft).isEqualTo(0)
        assertThat(s.lines.single().projectedMinor).isEqualTo(4_000_00)
        assertThat(s.lines.single().vsLimitPercent).isEqualTo(-20)
        assertThat(s.status).isEqualTo(BudgetStatus.ON_TRACK)
    }

    @Test fun earlyMonthDoesNotCryWolf() {
        // Day 1: a big grocery run is not "at risk" yet.
        val s = BudgetInsights.summarize(nov, mapOf(food to 6_000_00), mapOf(food to 1_500_00), null, LocalDate.of(2026, 11, 1))
        assertThat(s.lines.single().status).isEqualTo(BudgetStatus.ON_TRACK)
    }
}
