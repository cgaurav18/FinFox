package com.mymoney.app.domain

import com.google.common.truth.Truth.assertThat
import com.mymoney.app.data.db.Frequency
import org.junit.Test
import java.time.LocalDate

class ScheduleMathTest {
    private val jan31 = LocalDate.of(2026, 1, 31)

    @Test fun monthEndDoesNotDrift() {
        val feb = ScheduleMath.nextAfter(jan31, Frequency.MONTHLY, jan31)
        assertThat(feb).isEqualTo(LocalDate.of(2026, 2, 28))
        val mar = ScheduleMath.nextAfter(jan31, Frequency.MONTHLY, feb)
        assertThat(mar).isEqualTo(LocalDate.of(2026, 3, 31))
    }

    @Test fun allFrequencies() {
        val s = LocalDate.of(2026, 11, 15)
        assertThat(ScheduleMath.nextAfter(s, Frequency.DAILY, s)).isEqualTo(s.plusDays(1))
        assertThat(ScheduleMath.nextAfter(s, Frequency.WEEKLY, s)).isEqualTo(s.plusWeeks(1))
        assertThat(ScheduleMath.nextAfter(s, Frequency.QUARTERLY, s)).isEqualTo(LocalDate.of(2027, 2, 15))
        assertThat(ScheduleMath.nextAfter(s, Frequency.YEARLY, s)).isEqualTo(LocalDate.of(2027, 11, 15))
    }

    @Test fun firstOnOrAfterFindsCorrectInstallment() {
        val s = LocalDate.of(2025, 1, 10)
        assertThat(ScheduleMath.firstOnOrAfter(s, Frequency.MONTHLY, LocalDate.of(2026, 11, 15))).isEqualTo(LocalDate.of(2026, 12, 10))
        assertThat(ScheduleMath.firstOnOrAfter(s, Frequency.MONTHLY, LocalDate.of(2026, 11, 10))).isEqualTo(LocalDate.of(2026, 11, 10))
        assertThat(ScheduleMath.firstOnOrAfter(s, Frequency.MONTHLY, LocalDate.of(2024, 1, 1))).isEqualTo(s)
    }

    @Test fun occurrencesRespectEndDate() {
        val s = LocalDate.of(2026, 1, 5)
        val list = ScheduleMath.occurrencesBetween(s, Frequency.MONTHLY, s, LocalDate.of(2026, 3, 31), s, LocalDate.of(2026, 12, 31))
        assertThat(list).containsExactly(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 5), LocalDate.of(2026, 3, 5)).inOrder()
    }

    @Test fun overdueCount() {
        val s = LocalDate.of(2026, 8, 1)
        assertThat(ScheduleMath.overdueCount(s, Frequency.MONTHLY, LocalDate.of(2026, 9, 1), null, LocalDate.of(2026, 11, 15))).isEqualTo(3)
        assertThat(ScheduleMath.overdueCount(s, Frequency.MONTHLY, LocalDate.of(2026, 12, 1), null, LocalDate.of(2026, 11, 15))).isEqualTo(0)
    }
}
