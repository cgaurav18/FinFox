package com.mymoney.app.ui.theme

import android.app.Activity
import android.provider.Settings
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymoney.app.R
import com.mymoney.app.data.db.ThemeMode
import com.mymoney.app.ui.theme.FinFoxPalette as P

// ---------------------------------------------------------------- Material 3 colour schemes

internal val LightColors: ColorScheme = lightColorScheme(
    primary = P.PrimaryPurple, onPrimary = Color.White,
    primaryContainer = Color(0xFFEFE4FB), onPrimaryContainer = P.DeepPlum,
    secondary = P.RoyalViolet, onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1E8FC), onSecondaryContainer = P.DeepPlum,
    tertiary = P.FoxOrange, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE8D9), onTertiaryContainer = Color(0xFF5A2300),
    background = P.LightBackground, onBackground = P.LightTextPrimary,
    surface = P.LightCard, onSurface = P.LightTextPrimary,
    surfaceVariant = Color(0xFFF2EDF8), onSurfaceVariant = P.LightTextSecondary,
    surfaceContainerLowest = P.LightCard, surfaceContainerLow = Color(0xFFFCFAFE),
    surfaceContainer = Color(0xFFF5F1FA), surfaceContainerHigh = Color(0xFFF0EAF7), surfaceContainerHighest = Color(0xFFEAE3F3),
    // outline is used for input borders (needs ≥3:1); outlineVariant is the subtle card/divider border.
    outline = Color(0xFF8E80A0), outlineVariant = P.LightBorder,
    error = P.Expense, onError = Color.White, errorContainer = Color(0xFFFDE2E2), onErrorContainer = P.Overdue,
    inverseSurface = P.DeepPlum, inverseOnSurface = Color.White, inversePrimary = P.DarkPrimaryText,
)

internal val DarkColors: ColorScheme = darkColorScheme(
    // Text-coloured "primary" uses a light violet for contrast; filled buttons use Royal Violet (see primaryButton).
    primary = P.DarkPrimaryText, onPrimary = P.DeepPlum,
    primaryContainer = Color(0xFF3D1A66), onPrimaryContainer = Color(0xFFEFE2FF),
    secondary = P.FoxOrange, onSecondary = P.DeepPlum,
    secondaryContainer = Color(0xFF4A2340), onSecondaryContainer = Color(0xFFFFE0CC),
    tertiary = P.GoldenAmber, onTertiary = P.DeepPlum,
    tertiaryContainer = Color(0xFF4A3410), onTertiaryContainer = Color(0xFFFFE6B3),
    background = P.DarkBackground, onBackground = P.DarkTextPrimary,
    surface = P.DarkCard, onSurface = P.DarkTextPrimary,
    surfaceVariant = Color(0xFF36204F), onSurfaceVariant = P.DarkTextSecondary,
    surfaceContainerLowest = Color(0xFF120820), surfaceContainerLow = Color(0xFF221136),
    surfaceContainer = P.DarkCard, surfaceContainerHigh = Color(0xFF331B50), surfaceContainerHighest = Color(0xFF3B2259),
    outline = Color(0xFF8C74A3), outlineVariant = P.DarkBorder,
    error = P.ExpenseOnDark, onError = P.DeepPlum, errorContainer = Color(0xFF4B1620), onErrorContainer = Color(0xFFFFD6D6),
    inverseSurface = Color(0xFFF1EAF9), inverseOnSurface = P.DeepPlum, inversePrimary = P.PrimaryPurple,
)

// ---------------------------------------------------------------- FinFox tokens beyond Material

@Immutable
data class FinFoxColors(
    val income: Color,
    val expense: Color,
    val transfer: Color,
    /** EMI / upcoming payment. */
    val warning: Color,
    val savings: Color,
    val overdue: Color,
    val incomeContainer: Color,
    val expenseContainer: Color,
    val primaryButton: Color,
    val onPrimaryButton: Color,
    val secondaryButton: Color,
    val onSecondaryButton: Color,
    val selectedNav: Color,
    val accent: Color,
    val gold: Color,
    val border: Color,
    val headerGradient: Brush,
    val onGradient: Color,
    val isDark: Boolean,
)

/** Brand gradient; orange is kept to the far corner so white text on the card stays readable. */
val BrandBrush = Brush.linearGradient(0f to P.DeepPlum, 0.38f to P.PrimaryPurple, 0.78f to P.RoyalViolet, 1f to P.FoxOrange)

val LightFinFoxColors = FinFoxColors(
    income = P.Income, expense = P.Expense, transfer = P.Transfer, warning = P.Upcoming, savings = P.Savings, overdue = P.Overdue,
    incomeContainer = Color(0xFFDCFCE7), expenseContainer = Color(0xFFFEE2E2),
    primaryButton = P.PrimaryPurple, onPrimaryButton = Color.White,
    secondaryButton = Color(0xFFEFE4FB), onSecondaryButton = P.PrimaryPurple,
    selectedNav = P.RoyalViolet, accent = P.FoxOrange, gold = P.GoldenAmber, border = P.LightBorder,
    headerGradient = BrandBrush, onGradient = Color.White, isDark = false,
)

val DarkFinFoxColors = FinFoxColors(
    income = P.IncomeOnDark, expense = P.ExpenseOnDark, transfer = P.TransferOnDark, warning = P.UpcomingOnDark,
    savings = P.SavingsOnDark, overdue = P.OverdueOnDark,
    incomeContainer = Color(0xFF123A26), expenseContainer = Color(0xFF451620),
    primaryButton = P.RoyalViolet, onPrimaryButton = Color.White,
    secondaryButton = Color(0xFF3D1A66), onSecondaryButton = Color(0xFFEFE2FF),
    selectedNav = P.FoxOrange, accent = P.GoldenAmber, gold = P.GoldenAmber, border = P.DarkBorder,
    headerGradient = BrandBrush, onGradient = Color.White, isDark = true,
)

val LocalFinFoxColors = staticCompositionLocalOf { LightFinFoxColors }

/** True when the user turned animations off (Developer options / accessibility "Remove animations"). */
val LocalReducedMotion = staticCompositionLocalOf { false }

// ---------------------------------------------------------------- Typography (Inter, bundled; system sans-serif fallback)

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: FontWeight) = Font(R.font.inter, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

@OptIn(ExperimentalTextApi::class)
val Inter = FontFamily(inter(FontWeight.Normal), inter(FontWeight.Medium), inter(FontWeight.SemiBold), inter(FontWeight.Bold))

private val AppTypography = Typography().let { t ->
    fun TextStyle.inter(size: Int, weight: FontWeight, line: Int) = copy(fontFamily = Inter, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp)
    t.copy(
        displayLarge = t.displayLarge.inter(40, FontWeight.Bold, 48),
        displayMedium = t.displayMedium.inter(34, FontWeight.Bold, 42),   // dashboard balance
        displaySmall = t.displaySmall.inter(30, FontWeight.Bold, 38),
        headlineLarge = t.headlineLarge.inter(28, FontWeight.Bold, 36),
        headlineMedium = t.headlineMedium.inter(24, FontWeight.Bold, 32),
        headlineSmall = t.headlineSmall.inter(22, FontWeight.SemiBold, 30),
        titleLarge = t.titleLarge.inter(22, FontWeight.SemiBold, 28),     // screen headings
        titleMedium = t.titleMedium.inter(17, FontWeight.SemiBold, 24),   // card headings
        titleSmall = t.titleSmall.inter(15, FontWeight.SemiBold, 22),
        bodyLarge = t.bodyLarge.inter(16, FontWeight.Normal, 24),
        bodyMedium = t.bodyMedium.inter(14, FontWeight.Normal, 20),
        bodySmall = t.bodySmall.inter(13, FontWeight.Normal, 18),
        labelLarge = t.labelLarge.inter(14, FontWeight.SemiBold, 20),
        labelMedium = t.labelMedium.inter(13, FontWeight.Medium, 18),
        labelSmall = t.labelSmall.inter(12, FontWeight.Medium, 16),     // captions
    )
}

val AmountStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp),
)

/** Corner radii used across FinFox components (8 dp grid). */
object FinFoxShapes {
    val button = RoundedCornerShape(14.dp)
    val input = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(20.dp)
    val heroCard = RoundedCornerShape(24.dp)
    val chip = RoundedCornerShape(8.dp)
}

@Composable
fun FinFoxTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        try { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (e: Exception) { false }
    }
    // Status/navigation bar icons follow the app theme (not just the system setting).
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    CompositionLocalProvider(LocalFinFoxColors provides if (dark) DarkFinFoxColors else LightFinFoxColors, LocalReducedMotion provides reducedMotion) {
        val scheme = if (dark) DarkColors else LightColors
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
            // Default text/icon colour for screens drawn outside a Scaffold/Surface (lock screen, onboarding).
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

object FinFoxTheme {
    val colors: FinFoxColors @Composable get() = LocalFinFoxColors.current
    val reducedMotion: Boolean @Composable get() = LocalReducedMotion.current
}
