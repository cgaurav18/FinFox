package com.mymoney.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mymoney.app.data.Env
import com.mymoney.app.data.db.ThemeMode
import com.mymoney.app.data.repo.AppearanceRepository
import com.mymoney.app.data.repo.SettingsRepository
import com.mymoney.app.ui.theme.DarkColors
import com.mymoney.app.ui.theme.DarkFinFoxColors
import com.mymoney.app.ui.theme.FinFoxPalette
import com.mymoney.app.ui.theme.LightColors
import com.mymoney.app.ui.theme.LightFinFoxColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinFoxThemeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble(); val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun assertContrast(name: String, fg: Color, bg: Color, min: Double) =
        assertWithMessage("$name contrast %.2f < $min".format(contrast(fg, bg))).that(contrast(fg, bg)).isAtLeast(min)

    @Test fun brandColoursMatchTheSpecification() {
        assertThat(FinFoxPalette.PrimaryPurple).isEqualTo(Color(0xFF5B0FA6))
        assertThat(FinFoxPalette.RoyalViolet).isEqualTo(Color(0xFF7C20D9))
        assertThat(FinFoxPalette.FoxOrange).isEqualTo(Color(0xFFFF7417))
        assertThat(FinFoxPalette.GoldenAmber).isEqualTo(Color(0xFFFFB21C))
        assertThat(FinFoxPalette.DeepPlum).isEqualTo(Color(0xFF25083F))
        // Light theme table
        assertThat(LightColors.background).isEqualTo(Color(0xFFF8F6FC))
        assertThat(LightColors.surface).isEqualTo(Color(0xFFFFFFFF))
        assertThat(LightColors.onSurface).isEqualTo(Color(0xFF25083F))
        assertThat(LightColors.onSurfaceVariant).isEqualTo(Color(0xFF62576F))
        assertThat(LightFinFoxColors.primaryButton).isEqualTo(Color(0xFF5B0FA6))
        assertThat(LightFinFoxColors.selectedNav).isEqualTo(Color(0xFF7C20D9))
        assertThat(LightFinFoxColors.accent).isEqualTo(Color(0xFFFF7417))
        assertThat(LightFinFoxColors.border).isEqualTo(Color(0xFFE9E1F0))
        // Dark theme table
        assertThat(DarkColors.background).isEqualTo(Color(0xFF170B27))
        assertThat(DarkColors.surface).isEqualTo(Color(0xFF2B1645))
        assertThat(DarkColors.onSurface).isEqualTo(Color.White)
        assertThat(DarkColors.onSurfaceVariant).isEqualTo(Color(0xFFC8B7D9))
        assertThat(DarkFinFoxColors.primaryButton).isEqualTo(Color(0xFF7C20D9))
        assertThat(DarkFinFoxColors.selectedNav).isEqualTo(Color(0xFFFF7417))
        assertThat(DarkFinFoxColors.accent).isEqualTo(Color(0xFFFFB21C))
        assertThat(DarkFinFoxColors.border).isEqualTo(Color(0xFF49305F))
        // Financial status colours (canonical, used as-is in light mode)
        assertThat(listOf(LightFinFoxColors.income, LightFinFoxColors.expense, LightFinFoxColors.transfer, LightFinFoxColors.warning, LightFinFoxColors.savings, LightFinFoxColors.overdue))
            .containsExactly(Color(0xFF16A34A), Color(0xFFDC2626), Color(0xFF2563EB), Color(0xFFD97706), Color(0xFF0D9488), Color(0xFFB91C1C)).inOrder()
    }

    @Test fun textMeetsWcagAaInBothThemes() {
        for ((name, scheme) in listOf("light" to LightColors, "dark" to DarkColors)) {
            assertContrast("$name onBackground", scheme.onBackground, scheme.background, 7.0)
            assertContrast("$name onSurface", scheme.onSurface, scheme.surface, 7.0)
            assertContrast("$name secondary text", scheme.onSurfaceVariant, scheme.surface, 4.5)
            assertContrast("$name secondary text on bg", scheme.onSurfaceVariant, scheme.background, 4.5)
            assertContrast("$name primary as text", scheme.primary, scheme.surface, 4.5)
            assertContrast("$name input border", scheme.outline, scheme.surface, 3.0)
        }
        for ((name, f, card) in listOf(Triple("light", LightFinFoxColors, LightColors.surface), Triple("dark", DarkFinFoxColors, DarkColors.surface))) {
            assertContrast("$name button label", f.onPrimaryButton, f.primaryButton, 4.5)
            assertContrast("$name secondary button label", f.onSecondaryButton, f.secondaryButton, 4.5)
            assertContrast("$name selected nav", f.selectedNav, card, 4.5)
        }
        assertContrast("balance on gradient start", Color.White, FinFoxPalette.DeepPlum, 7.0)
        assertContrast("destructive button", Color.White, FinFoxPalette.Expense, 4.5)
    }

    @Test fun statusColoursAreDistinguishableOnCards() {
        // Status colours are always paired with an icon or label; they must meet the 3:1 non-text minimum,
        // and the dark-theme text variants meet AA for normal text.
        for (c in listOf(LightFinFoxColors.income, LightFinFoxColors.expense, LightFinFoxColors.transfer, LightFinFoxColors.warning, LightFinFoxColors.savings, LightFinFoxColors.overdue))
            assertContrast("light status $c", c, LightColors.surface, 3.0)
        for (c in listOf(DarkFinFoxColors.income, DarkFinFoxColors.expense, DarkFinFoxColors.transfer, DarkFinFoxColors.warning, DarkFinFoxColors.savings, DarkFinFoxColors.overdue))
            assertContrast("dark status $c", c, DarkColors.surface, 4.5)
    }

    @Test fun themeChoicePersistsAcrossRestartAndMigratesOldSetting() = runTest {
        val e = Env.inMemory(); e.seed()
        val settings = SettingsRepository(e.db)
        settings.update { it.copy(themeMode = ThemeMode.LIGHT) } // value saved by an older version
        val file = File(tmp.root, "appearance.preferences_pb")
        val scope1 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val first = AppearanceRepository(PreferenceDataStoreFactory.create(scope = scope1) { file }, settings)
        assertThat(first.themeMode.first()).isEqualTo(ThemeMode.LIGHT)
        first.setThemeMode(ThemeMode.DARK)
        assertThat(first.themeMode.first()).isEqualTo(ThemeMode.DARK)
        assertThat(settings.get().themeMode).isEqualTo(ThemeMode.DARK) // kept in backups too
        scope1.cancel()

        // "Restart": a new DataStore over the same file.
        val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val second = AppearanceRepository(PreferenceDataStoreFactory.create(scope = scope2) { file }, SettingsRepository(e.db))
        assertThat(second.themeMode.first()).isEqualTo(ThemeMode.DARK)
        second.setThemeMode(ThemeMode.SYSTEM)
        assertThat(second.themeMode.first()).isEqualTo(ThemeMode.SYSTEM)
        scope2.cancel(); e.db.close()
    }
}
