package com.mymoney.app.widgets

import android.content.Context
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mymoney.app.core.Dates
import com.mymoney.app.data.Env
import com.mymoney.app.data.R
import com.mymoney.app.data.db.AccountEntity
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AccountWithBalance
import com.mymoney.app.data.db.CategorySpend
import com.mymoney.app.data.db.Frequency
import com.mymoney.app.data.db.ScheduleCategory
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.data.db.UpcomingItem
import com.mymoney.app.data.repo.ScheduleInput
import com.mymoney.app.data.repo.TxnInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val inr = WidgetFormat("INR", masked = false)

    private fun acc(id: Long, type: AccountType, balance: Long, limit: Long? = null, dueDay: Int? = null, archived: Boolean = false) = AccountWithBalance(
        AccountEntity(id = id, type = type, name = "A$id", openingBalanceMinor = 0, colorArgb = 0, iconKey = "x", creditLimitMinor = limit, dueDay = dueDay, archived = archived, createdAt = 0, updatedAt = 0),
        balance,
    )

    // ---- Models ----

    @Test fun balanceCountsBankAndCashButNeverCreditLimits() {
        val m = WidgetModels.balance(
            listOf(acc(1, AccountType.BANK, 100_000 * R), acc(2, AccountType.CASH, 2_000 * R), acc(3, AccountType.CREDIT_CARD, -5_000 * R, limit = 200_000 * R),
                acc(4, AccountType.BANK, 9_999 * R, archived = true)),
            inr, hidden = false, now = 0,
        )
        assertThat(m.availableMinor).isEqualTo(102_000 * R)
        assertThat(m.cardOutstandingMinor).isEqualTo(5_000 * R)
    }

    @Test fun cardsSummaryAvailableCreditAndNextDue() {
        val today = java.time.LocalDate.of(2026, 11, 15)
        val m = WidgetModels.cards(listOf(acc(1, AccountType.CREDIT_CARD, -40_000 * R, 100_000 * R, dueDay = 20), acc(2, AccountType.CREDIT_CARD, -10_000 * R, 50_000 * R, dueDay = 18),
            acc(3, AccountType.BANK, 1)), today, inr)
        assertThat(m.outstandingMinor).isEqualTo(50_000 * R)
        assertThat(m.availableCreditMinor).isEqualTo(100_000 * R)
        assertThat(m.nextDue!!.id).isEqualTo(2)
        assertThat(m.nextDue!!.nextDue).isEqualTo(java.time.LocalDate.of(2026, 11, 18))
    }

    @Test fun upcomingFlagsOverdueAndPrivacyMasksAmounts() {
        val today = Dates.today()
        val m = WidgetModels.upcoming(listOf(
            UpcomingItem(1, "EMI", ScheduleCategory.EMI, 25_000 * R, today.minusDays(2).toEpochDay()),
            UpcomingItem(2, "SIP", ScheduleCategory.SIP, 5_000 * R, today.plusDays(3).toEpochDay()),
        ), today, WidgetFormat("INR", masked = true))
        assertThat(m.overdueCount).isEqualTo(1)
        assertThat(m.rows.first().overdue).isTrue()
        assertThat(m.fmt.money(25_000 * R)).doesNotContain("25")
        assertThat(m.fmt.compact(25_000 * R)).isEqualTo("••••")
    }

    // ---- Rendering across sizes ----

    private val balance = BalanceModel(150_000 * R, 140_000 * R, 10_000 * R, 3_000 * R, inr, hidden = false, updatedAt = 0)

    @Test fun totalBalanceSmallAndMedium() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(WidgetSizes.SMALL)
        provideComposable { WidgetTheme { TotalBalanceContent(balance) } }
        onNode(hasText("₹1,50,000")).assertExists()
        onNode(hasText("Card due")).assertDoesNotExist()
        onNode(hasContentDescription("Hide balance")).assertExists()
    }

    @Test fun totalBalanceMediumShowsBreakdown() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(WidgetSizes.MEDIUM)
        provideComposable { WidgetTheme { TotalBalanceContent(balance) } }
        onNode(hasText("Total available balance")).assertExists()
        onNode(hasText("Card due")).assertExists()
    }

    @Test fun privacyModeMasksEveryAmount() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(WidgetSizes.MEDIUM)
        provideComposable { WidgetTheme { TotalBalanceContent(balance.copy(fmt = WidgetFormat("INR", masked = true), hidden = true)) } }
        onNode(hasText("1,50,000", true)).assertDoesNotExist()
        onNode(hasText("₹ ••••")).assertExists()
        onNode(hasContentDescription("Show balance")).assertExists()
    }

    @Test fun budgetLargeShowsCategoriesAndOverspending() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(WidgetSizes.LARGE)
        val m = BudgetModel(YearMonth.of(2026, 11), listOf(BudgetRow("Food", 0xFFF97316, 6_000 * R, 5_000 * R), BudgetRow("Fuel", 0xFFEF4444, 1_000 * R, 4_000 * R)), inr)
        provideComposable { WidgetTheme { BudgetContent(m) } }
        onNode(hasText("1 over")).assertExists()
        onNode(hasText("Food ⚠")).assertExists()
        onNode(hasText("Fuel")).assertExists()
    }

    @Test fun budgetMediumHidesCategoryRows() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(WidgetSizes.MEDIUM)
        val m = BudgetModel(YearMonth.of(2026, 11), listOf(BudgetRow("Food", 0, 6_000 * R, 5_000 * R)), inr)
        provideComposable { WidgetTheme { BudgetContent(m) } }
        onNode(hasText("Food ⚠")).assertDoesNotExist()
        onNode(hasText("Overspending in 1 category", true)).assertExists()
    }

    @Test fun incomeExpenseLargeShowsTopSpending() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(WidgetSizes.LARGE)
        val m = MonthModel(YearMonth.of(2026, 11), 80_000 * R, 20_000 * R, listOf(CategorySpend(1, "Rent", 0xFF8B5CF6, 15_000 * R)), inr)
        provideComposable { WidgetTheme { IncomeExpenseContent(m) } }
        onNode(hasText("Nov 2026")).assertExists()
        onNode(hasText("25% of income spent")).assertExists()
        onNode(hasText("Rent")).assertExists()
    }

    @Test fun quickActionsOfferAllFourEntries() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(WidgetSizes.SMALL)
        provideComposable { WidgetTheme { QuickActionsContent() } }
        listOf("Add expense", "Add income", "Transfer money", "Add lending or borrowing entry").forEach { onNode(hasContentDescription(it)).assertExists() }
    }

    @Test fun upcomingAndPeopleAndCardsRender() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(WidgetSizes.LARGE)
        val today = Dates.today()
        val up = WidgetModels.upcoming(listOf(UpcomingItem(1, "Home EMI", ScheduleCategory.EMI, 25_000 * R, today.minusDays(1).toEpochDay())), today, inr)
        provideComposable { WidgetTheme { UpcomingContent(up) } }
        onNode(hasText("Home EMI")).assertExists()
        onNode(hasTextEqualTo("Overdue")).assertExists()
    }

    @Test fun emptyStatesRender() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(WidgetSizes.MEDIUM)
        provideComposable { WidgetTheme { CreditCardContent(CardModel(emptyList(), inr, Dates.today())) } }
        onNode(hasText("No credit cards added yet.")).assertExists()
    }

    // ---- RemoteViews translation (RemoteViews drop children past 10 per Row/Column) ----

    @OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)
    private fun inflate(size: androidx.compose.ui.unit.DpSize, content: @androidx.compose.runtime.Composable () -> Unit): android.view.View = runBlocking {
        val rv = androidx.glance.appwidget.GlanceRemoteViews().compose(ctx, size) {
            WidgetTheme { androidx.compose.runtime.CompositionLocalProvider(LocalInteractive provides false) { content() } }
        }.remoteViews
        rv.apply(ctx, android.widget.FrameLayout(ctx))
    }

    private fun android.view.View.count(cls: Class<*>): Int =
        (if (cls.isInstance(this)) 1 else 0) + ((this as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).sumOf { g.getChildAt(it).count(cls) } } ?: 0)

    @Test fun largeWidgetsKeepEveryListRowAfterRemoteViewsTranslation() {
        val rows = (1..4).map { BudgetRow("Cat$it", 0xFF5B0FA6, it * 1_000 * R, 5_000 * R) }
        val budget = inflate(WidgetSizes.LARGE) { BudgetContent(BudgetModel(YearMonth.of(2026, 11), rows, inr)) }
        assertThat(budget.count(android.widget.ProgressBar::class.java)).isEqualTo(1 + 4) // overall + every category

        val cats = (1..4).map { CategorySpend(it.toLong(), "C$it", 0xFFFF7417, it * 100 * R) }
        val month = inflate(WidgetSizes.LARGE) { IncomeExpenseContent(MonthModel(YearMonth.of(2026, 11), 1_000 * R, 500 * R, cats, inr)) }
        assertThat(month.count(android.widget.ProgressBar::class.java)).isEqualTo(1 + 4)

        val today = java.time.LocalDate.of(2026, 11, 15)
        val cards = inflate(WidgetSizes.LARGE) {
            CreditCardContent(WidgetModels.cards((1..3L).map { acc(it, AccountType.CREDIT_CARD, -1_000 * R, 10_000 * R, dueDay = 20) }, today, inr))
        }
        assertThat(cards.count(android.widget.ProgressBar::class.java)).isEqualTo(3)
    }

    // ---- Live data & refresh ----

    @Test fun widgetsRefreshAfterTransactionsWithoutPolling() = runBlocking {
        val e = Env.inMemory(); e.seed()
        val bank = e.bank(opening = 1_000 * R)
        val refreshes = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        WidgetUpdater.observe(ctx, e.db, scope, debounceMs = 50) { refreshes.incrementAndGet() }
        delay(200)
        val before = refreshes.get()
        e.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = 250 * R, accountId = bank, categoryId = e.cat("Food"), dateMillis = Dates.nowMillis()))
        withTimeout(5_000) { while (refreshes.get() == before) delay(20) }
        // A burst of changes is coalesced into few refreshes, and widget data reflects the new balance.
        assertThat(WidgetRepository(e.db).balance().availableMinor).isEqualTo(750 * R)
        delay(300)
        val settled = refreshes.get()
        delay(300)
        assertThat(refreshes.get()).isEqualTo(settled) // no polling: nothing happens without changes
        e.schedules.save(ScheduleInput(title = "Rent", category = ScheduleCategory.RENT, amountMinor = 100 * R, accountId = bank, start = Dates.today(), frequency = Frequency.MONTHLY))
        withTimeout(5_000) { while (refreshes.get() == settled) delay(20) }
        assertThat(WidgetRepository(e.db).upcoming().rows.single().title).isEqualTo("Rent")
        scope.cancel(); e.db.close()
    }
}
