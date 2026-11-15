package com.mymoney.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymoney.app.data.db.ThemeMode

val Purple = Color(0xFF5B21B6)
val Violet = Color(0xFF7C3AED)
val PurpleDark = Color(0xFF2E1065)
val Lavender = Color(0xFFEDE9FE)

internal val LightColors = lightColorScheme(
    primary = Purple, onPrimary = Color.White,
    primaryContainer = Lavender, onPrimaryContainer = PurpleDark,
    secondary = Violet, onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3EEFF), onSecondaryContainer = PurpleDark,
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFFF8F9FC), onBackground = Color(0xFF111827),
    surface = Color.White, onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF3F4F8), onSurfaceVariant = Color(0xFF6B7280),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFAFF),
    surfaceContainer = Color(0xFFF6F4FB), surfaceContainerHigh = Color(0xFFF1EEF9), surfaceContainerHighest = Color(0xFFEAE6F4),
    outline = Color(0xFFD1D5DB), outlineVariant = Color(0xFFE9E7F0),
    error = Color(0xFFDC2626), onError = Color.White,
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFA78BFA), onPrimary = Color(0xFF1E0B4B),
    primaryContainer = Color(0xFF3B1E7A), onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = Color(0xFFC4B5FD), onSecondary = Color(0xFF1E0B4B),
    secondaryContainer = Color(0xFF2D1B5A), onSecondaryContainer = Color(0xFFEDE9FE),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0F0B1A), onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF1A1528), onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF252036), onSurfaceVariant = Color(0xFFA1A1B5),
    surfaceContainerLowest = Color(0xFF130F1F), surfaceContainerLow = Color(0xFF1A1528),
    surfaceContainer = Color(0xFF1F1A2E), surfaceContainerHigh = Color(0xFF262036), surfaceContainerHighest = Color(0xFF2E2840),
    outline = Color(0xFF4B4560), outlineVariant = Color(0xFF2E2840),
    error = Color(0xFFF87171), onError = Color(0xFF450A0A),
)

@Immutable
data class FinanceColors(
    val income: Color,
    val expense: Color,
    val transfer: Color,
    val warning: Color,
    val incomeContainer: Color,
    val expenseContainer: Color,
    val headerGradient: Brush,
    val isDark: Boolean,
)

val LocalFinanceColors = staticCompositionLocalOf {
    FinanceColors(Color(0xFF16A34A), Color(0xFFDC2626), Color(0xFF2563EB), Color(0xFFF59E0B), Color(0xFFDCFCE7), Color(0xFFFEE2E2),
        Brush.linearGradient(listOf(Violet, Purple)), false)
}

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val AmountStyle = TextStyle(fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MyMoneyTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val fin = if (dark) FinanceColors(
        income = Color(0xFF4ADE80), expense = Color(0xFFF87171), transfer = Color(0xFF60A5FA), warning = Color(0xFFFBBF24),
        incomeContainer = Color(0xFF14361F), expenseContainer = Color(0xFF3F1515),
        headerGradient = Brush.linearGradient(listOf(Color(0xFF6D28D9), Color(0xFF3B0764))), isDark = true,
    ) else FinanceColors(
        income = Color(0xFF16A34A), expense = Color(0xFFDC2626), transfer = Color(0xFF2563EB), warning = Color(0xFFD97706),
        incomeContainer = Color(0xFFDCFCE7), expenseContainer = Color(0xFFFEE2E2),
        headerGradient = Brush.linearGradient(listOf(Violet, Purple)), isDark = false,
    )
    CompositionLocalProvider(LocalFinanceColors provides fin) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object FinanceTheme {
    val colors: FinanceColors @Composable get() = LocalFinanceColors.current
}
