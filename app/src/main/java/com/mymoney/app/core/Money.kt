package com.mymoney.app.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * All money is stored as Long minor units (paise for INR). Floating point is never used for money.
 */
object Money {
    /** Upper bound to keep sums comfortably inside Long: ₹1,000 crore per single amount. */
    const val MAX_MINOR: Long = 1_000_000_000_000L

    sealed interface ParseResult {
        data class Ok(val minor: Long) : ParseResult
        data class Error(val message: String) : ParseResult
    }

    /**
     * Parses user input like "1,200", "1200.5", "₹ 12,500.75" into minor units.
     * Rejects more than 2 decimals, negatives, zero (unless allowZero) and absurd values.
     */
    fun parse(input: String, allowZero: Boolean = false): ParseResult {
        val cleaned = input.trim().replace(",", "").replace("₹", "").replace(" ", "")
        if (cleaned.isEmpty()) return ParseResult.Error("Enter an amount")
        if (!Regex("^\\d+(\\.\\d{0,2})?$").matches(cleaned)) {
            return if (Regex("^\\d+\\.\\d{3,}$").matches(cleaned)) ParseResult.Error("Use at most 2 decimal places")
            else ParseResult.Error("Enter a valid amount")
        }
        val bd = try { BigDecimal(cleaned) } catch (e: NumberFormatException) { return ParseResult.Error("Enter a valid amount") }
        val minorBd = bd.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY)
        if (minorBd > BigDecimal.valueOf(MAX_MINOR)) return ParseResult.Error("Amount is too large")
        val minor = minorBd.longValueExact()
        if (minor == 0L && !allowZero) return ParseResult.Error("Amount must be greater than zero")
        return ParseResult.Ok(minor)
    }

    fun parseOrNull(input: String, allowZero: Boolean = false): Long? =
        (parse(input, allowZero) as? ParseResult.Ok)?.minor

    /** Plain editable representation, e.g. 120050 -> "1200.50", 120000 -> "1200". */
    fun toInput(minor: Long): String {
        val bd = BigDecimal.valueOf(minor).movePointLeft(2)
        return if (minor % 100 == 0L) bd.setScale(0).toPlainString() else bd.setScale(2).toPlainString()
    }

    fun symbolFor(currency: String): String = when (currency) {
        "INR" -> "₹"; "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; "AED" -> "AED "; "JPY" -> "¥"
        "SGD" -> "S$"; "AUD" -> "A$"; "CAD" -> "C$"
        else -> "$currency "
    }

    val supportedCurrencies = listOf("INR", "USD", "EUR", "GBP", "AED", "SGD", "AUD", "CAD", "JPY")

    /**
     * Formats minor units. INR uses Indian digit grouping (1,20,000), others use groups of three.
     * Decimals are shown only when non-zero unless [alwaysDecimals].
     */
    fun format(
        minor: Long,
        currency: String = "INR",
        showSymbol: Boolean = true,
        alwaysDecimals: Boolean = false,
        signed: Boolean = false,
    ): String {
        val negative = minor < 0
        val abs = if (minor == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(minor)
        val whole = abs / 100
        val frac = abs % 100
        val grouped = if (currency == "INR") groupIndian(whole.toString()) else groupWestern(whole.toString())
        val decimals = if (frac != 0L || alwaysDecimals) "." + frac.toString().padStart(2, '0') else ""
        val sign = when {
            negative -> "-"
            signed && minor > 0 -> "+"
            else -> ""
        }
        val sym = if (showSymbol) symbolFor(currency) else ""
        return "$sign$sym$grouped$decimals"
    }

    /** Compact form for chart axes: ₹1.2L, ₹3.4Cr, ₹12K. */
    fun compact(minor: Long, currency: String = "INR"): String {
        val sym = symbolFor(currency)
        val v = BigDecimal.valueOf(minor).movePointLeft(2)
        val a = v.abs()
        val sign = if (minor < 0) "-" else ""
        fun f(d: BigDecimal, div: Long, suffix: String) =
            sign + sym + d.divide(BigDecimal.valueOf(div), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + suffix
        return if (currency == "INR") when {
            a >= BigDecimal.valueOf(10_000_000) -> f(a, 10_000_000, "Cr")
            a >= BigDecimal.valueOf(100_000) -> f(a, 100_000, "L")
            a >= BigDecimal.valueOf(1_000) -> f(a, 1_000, "K")
            else -> sign + sym + a.setScale(0, RoundingMode.HALF_UP).toPlainString()
        } else when {
            a >= BigDecimal.valueOf(1_000_000_000) -> f(a, 1_000_000_000, "B")
            a >= BigDecimal.valueOf(1_000_000) -> f(a, 1_000_000, "M")
            a >= BigDecimal.valueOf(1_000) -> f(a, 1_000, "K")
            else -> sign + sym + a.setScale(0, RoundingMode.HALF_UP).toPlainString()
        }
    }

    /** Integer percentage (0..n) without floating point; denominators <= 0 give 0. */
    fun percent(part: Long, whole: Long): Int =
        if (whole <= 0) 0 else BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(whole), 0, RoundingMode.DOWN).toInt()

    private fun groupWestern(s: String): String = s.reversed().chunked(3).joinToString(",").reversed()

    private fun groupIndian(s: String): String {
        if (s.length <= 3) return s
        val last3 = s.takeLast(3)
        val rest = s.dropLast(3)
        val restGrouped = rest.reversed().chunked(2).joinToString(",").reversed()
        return "$restGrouped,$last3"
    }
}
