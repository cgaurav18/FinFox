package com.mymoney.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.mymoney.app.domain.SeriesPoint
import com.mymoney.app.domain.Slice
import com.mymoney.app.ui.auth.PinDots
import com.mymoney.app.ui.auth.PinPad
import com.mymoney.app.ui.components.AmountField
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.DonutChart
import com.mymoney.app.ui.components.IncomeExpenseBarChart
import com.mymoney.app.ui.components.PillTabs
import com.mymoney.app.ui.theme.MyMoneyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentsUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pinPadEntersDigitsDeletesAndOffersBiometric() {
        var pin = ""
        var bio = 0
        rule.setContent {
            MyMoneyTheme {
                var p by remember { mutableStateOf("") }
                PinDots(4, p.length, false)
                PinPad(onDigit = { p += it; pin = p }, onBackspace = { p = p.dropLast(1); pin = p }, onBiometric = { bio++ })
            }
        }
        rule.onNodeWithText("4").performClick()
        rule.onNodeWithText("8").performClick()
        rule.onNodeWithText("2").performClick()
        rule.onNodeWithContentDescription("3 of 4 digits entered").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.onNodeWithText("6").performClick()
        rule.onNodeWithText("0").performClick()
        assertThat(pin).isEqualTo("4860")
        rule.onNodeWithContentDescription("Use fingerprint").performClick()
        assertThat(bio).isEqualTo(1)
    }

    @Test fun amountFieldRejectsInvalidCharactersAndExtraDecimals() {
        var value = ""
        rule.setContent { MyMoneyTheme { var v by remember { mutableStateOf("") }; AmountField(v, { v = it; value = it }) } }
        rule.onNodeWithText("Amount").performTextInput("12a")
        assertThat(value).isEqualTo("")
        rule.onNodeWithText("Amount").performTextInput("1200.5")
        assertThat(value).isEqualTo("1200.5")
        rule.onNodeWithText("Amount").performTextInput("55")
        assertThat(value).isEqualTo("1200.5") // a third decimal digit is refused
    }

    @Test fun confirmDialogRequiresExplicitConfirmation() {
        var deleted = false
        var dismissed = false
        rule.setContent { MyMoneyTheme { ConfirmDialog("Delete transaction?", "Balance will be recalculated.", "Delete", { deleted = true }, { dismissed = true }) } }
        rule.onNodeWithText("Delete transaction?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertThat(deleted).isFalse(); assertThat(dismissed).isTrue()
        rule.onNodeWithText("Delete").performClick()
        assertThat(deleted).isTrue()
    }

    @Test fun pillTabsSwitchSelection() {
        var selected = "All"
        rule.setContent { MyMoneyTheme { var s by remember { mutableStateOf("All") }; PillTabs(listOf("All", "Income", "Expense"), s, { it }, { s = it; selected = it }) } }
        rule.onNodeWithText("Expense").performClick()
        assertThat(selected).isEqualTo("Expense")
    }

    @Test fun chartsRenderAndLegendSelectsSlice() {
        rule.setContent {
            MyMoneyTheme {
                androidx.compose.foundation.layout.Column {
                    DonutChart(listOf(Slice(1, "Food", 0xFFF97316, 2_500_000, 32), Slice(2, "Bills", 0xFFEF4444, 5_350_000, 68)), "Expenses", 7_850_000)
                    IncomeExpenseBarChart(listOf(SeriesPoint("Oct", 100, 50), SeriesPoint("Nov", 200, 80)))
                }
            }
        }
        rule.onNodeWithText("₹78,500").assertIsDisplayed()
        rule.onNodeWithText("Food").performClick()
        rule.onNodeWithText("Food • 32%").assertIsDisplayed()
        // Latest month with data is pre-selected: its label shows on the axis and in the header.
        rule.onAllNodesWithText("Nov").assertCountEquals(2)
        rule.onNodeWithText("In ₹2").assertIsDisplayed()
    }
}
