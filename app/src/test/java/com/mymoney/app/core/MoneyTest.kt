package com.mymoney.app.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MoneyTest {
    @Test fun parsesWholeAndDecimalAmountsToPaise() {
        assertThat(Money.parseOrNull("1200")).isEqualTo(120_000L)
        assertThat(Money.parseOrNull("1,20,000.50")).isEqualTo(12_000_050L)
        assertThat(Money.parseOrNull("0.1")).isEqualTo(10L)
        assertThat(Money.parseOrNull("₹ 99.99")).isEqualTo(9_999L)
    }

    @Test fun rejectsInvalidAmounts() {
        assertThat(Money.parse("")).isInstanceOf(Money.ParseResult.Error::class.java)
        assertThat(Money.parse("abc")).isInstanceOf(Money.ParseResult.Error::class.java)
        assertThat(Money.parse("-5")).isInstanceOf(Money.ParseResult.Error::class.java)
        assertThat(Money.parse("1.234")).isEqualTo(Money.ParseResult.Error("Use at most 2 decimal places"))
        assertThat(Money.parse("0")).isEqualTo(Money.ParseResult.Error("Amount must be greater than zero"))
        assertThat(Money.parse("0", allowZero = true)).isEqualTo(Money.ParseResult.Ok(0))
        assertThat(Money.parse("99999999999999")).isEqualTo(Money.ParseResult.Error("Amount is too large"))
    }

    @Test fun noFloatingPointDrift() {
        // 0.1 + 0.2 in paise is exactly 30.
        assertThat(Money.parseOrNull("0.1")!! + Money.parseOrNull("0.2")!!).isEqualTo(30L)
        val many = (1..1000).sumOf { Money.parseOrNull("0.01")!! }
        assertThat(many).isEqualTo(1000L)
    }

    @Test fun formatsIndianGrouping() {
        assertThat(Money.format(24_500_000)).isEqualTo("₹2,45,000")
        assertThat(Money.format(120_000)).isEqualTo("₹1,200")
        assertThat(Money.format(1_00_00_000_00)).isEqualTo("₹1,00,00,000")
        assertThat(Money.format(12_345)).isEqualTo("₹123.45")
        assertThat(Money.format(-4_250_000)).isEqualTo("-₹42,500")
        assertThat(Money.format(500, signed = true)).isEqualTo("+₹5")
        assertThat(Money.format(100_000_000, "USD")).isEqualTo("$1,000,000")
        assertThat(Money.format(5, alwaysDecimals = true)).isEqualTo("₹0.05")
    }

    @Test fun compactAndPercent() {
        assertThat(Money.compact(7_850_000)).isEqualTo("₹78.5K")
        assertThat(Money.compact(24_500_000)).isEqualTo("₹2.5L")
        assertThat(Money.percent(78_500, 100_000)).isEqualTo(78)
        assertThat(Money.percent(1, 0)).isEqualTo(0)
    }

    @Test fun toInputRoundTrips() {
        listOf(0L, 5L, 100L, 120_050L, 99_999_999L).forEach { v ->
            assertThat(Money.parseOrNull(Money.toInput(v), allowZero = true)).isEqualTo(v)
        }
    }
}
