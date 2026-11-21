package com.mymoney.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * FinFox colour system: "Sage & Slate". A calm teal-sage brand on soft neutral surfaces, so the
 * money colours (green income, red expense) are the only strong colours on screen.
 *
 * Every colour used by the UI is defined here; screens read them through MaterialTheme.colorScheme
 * or [FinFoxTheme.colors], never as raw hex values.
 */
object FinFoxPalette {
    // Brand
    /** Deep teal-sage: filled buttons, selected states, links. */
    val Primary = Color(0xFF2E6F68)
    /** Lighter teal for gradients and secondary emphasis. */
    val PrimaryBright = Color(0xFF3F8A82)
    /** Muted amber: rare highlights only. */
    val Accent = Color(0xFFC98A3D)
    val Gold = Color(0xFFC9A227)
    /** Deep slate-green ink: primary text in light mode, splash background. */
    val Ink = Color(0xFF1B2623)

    // Light theme
    val LightBackground = Color(0xFFF5F7F6)
    val LightCard = Color(0xFFFFFFFF)
    val LightTextPrimary = Ink
    val LightTextSecondary = Color(0xFF5C6B66)
    val LightBorder = Color(0xFFE3E8E6)

    // Dark theme
    val DarkBackground = Color(0xFF101513)
    val DarkCard = Color(0xFF182120)
    val DarkTextPrimary = Color(0xFFE8EEEC)
    val DarkTextSecondary = Color(0xFFA5B3AE)
    val DarkBorder = Color(0xFF26302D)
    /** Readable teal for text/icons on dark surfaces. */
    val DarkPrimaryText = Color(0xFF8FD0C6)

    // Financial status: identical hues in both themes.
    val Income = Color(0xFF1E9B5C)
    val Expense = Color(0xFFCC3D3D)
    val Transfer = Color(0xFF4F7CAC)
    val Upcoming = Color(0xFFC7861C)
    val Savings = Color(0xFF2A8C82)
    val Overdue = Color(0xFFB83A35)

    /**
     * Lightened text variants of the status colours, used ONLY for amounts/labels drawn as text on dark
     * surfaces so they meet WCAG contrast. Icons, bars and chart fills keep the canonical colours.
     */
    val IncomeOnDark = Color(0xFF5CD69A)
    val ExpenseOnDark = Color(0xFFFF8F87)
    val TransferOnDark = Color(0xFF8FB4E3)
    val UpcomingOnDark = Color(0xFFF0C066)
    val SavingsOnDark = Color(0xFF6FCFC2)
    val OverdueOnDark = Color(0xFFFF7F78)

    /** Hero gradient (balance card, splash): a quiet two-tone teal. */
    val BrandGradient = listOf(Color(0xFF245A54), Primary, Color(0xFF337870))

    /** Neutral data colour (e.g. "Others" slice). */
    val Neutral = Color(0xFF94A3B8)
}
