package com.mymoney.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * FinFox colour system. Every colour used by the UI is defined here; screens read them through
 * MaterialTheme.colorScheme or [FinFoxTheme.colors] — never as raw hex values.
 */
object FinFoxPalette {
    // Brand
    val PrimaryPurple = Color(0xFF5B0FA6)
    val RoyalViolet = Color(0xFF7C20D9)
    val FoxOrange = Color(0xFFFF7417)
    val GoldenAmber = Color(0xFFFFB21C)
    val DeepPlum = Color(0xFF25083F)

    // Light theme
    val LightBackground = Color(0xFFF8F6FC)
    val LightCard = Color(0xFFFFFFFF)
    val LightTextPrimary = Color(0xFF25083F)
    val LightTextSecondary = Color(0xFF62576F)
    val LightBorder = Color(0xFFE9E1F0)

    // Dark theme
    val DarkBackground = Color(0xFF170B27)
    val DarkCard = Color(0xFF2B1645)
    val DarkTextPrimary = Color(0xFFFFFFFF)
    val DarkTextSecondary = Color(0xFFC8B7D9)
    val DarkBorder = Color(0xFF49305F)
    /** Readable violet for text/icons on dark surfaces (Royal Violet itself is reserved for filled buttons). */
    val DarkPrimaryText = Color(0xFFC9A6F7)

    // Financial status — identical hues in both themes.
    val Income = Color(0xFF16A34A)
    val Expense = Color(0xFFDC2626)
    val Transfer = Color(0xFF2563EB)
    val Upcoming = Color(0xFFD97706)
    val Savings = Color(0xFF0D9488)
    val Overdue = Color(0xFFB91C1C)

    /**
     * Lightened text variants of the status colours, used ONLY for amounts/labels drawn as text on dark
     * surfaces so they meet WCAG contrast. Icons, bars and chart fills keep the canonical colours.
     */
    val IncomeOnDark = Color(0xFF4ADE80)
    val ExpenseOnDark = Color(0xFFFF8A8A)
    val TransferOnDark = Color(0xFF7FA8FF)
    val UpcomingOnDark = Color(0xFFFBBF4D)
    val SavingsOnDark = Color(0xFF3FD1C1)
    val OverdueOnDark = Color(0xFFFF7A7A)

    /** Premium gradient: dashboard balance card, splash, login header. */
    val BrandGradient = listOf(DeepPlum, PrimaryPurple, RoyalViolet, FoxOrange)

    /** Accent palette for user categories/accounts (data colours, chosen by the user). */
    val Neutral = Color(0xFF94A3B8)
}
