package com.mymoney.app.notifications

import com.google.common.truth.Truth.assertThat
import com.mymoney.app.core.Dates
import com.mymoney.app.data.Env
import com.mymoney.app.data.R
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.data.repo.TxnInput
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonthlyNotificationsTest {
    private lateinit var e: Env
    private var bank = 0L
    private val oct = YearMonth.of(2026, 10)
    private val nov2 = LocalDate.of(2026, 11, 2)

    @Before fun setUp() = runTest { e = Env.inMemory(); e.seed(); bank = e.bank(opening = 100_000 * R) }
    @After fun tearDown() = e.db.close()

    private suspend fun add(type: TxnType, amount: Long, cat: String, day: LocalDate) {
        val catType = if (type == TxnType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
        e.txns.save(TxnInput(type = type, amountMinor = amount, accountId = bank, categoryId = e.cat(cat, catType), dateMillis = Dates.startOfDay(day) + 12 * 3_600_000L))
    }

    @Test fun summaryAndBudgetReminderOnTheFirstDaysOfAMonth() = runTest {
        add(TxnType.EXPENSE, 20_000 * R, "Rent", LocalDate.of(2026, 9, 5))
        add(TxnType.INCOME, 50_000 * R, "Salary", oct.atDay(1))
        add(TxnType.EXPENSE, 15_000 * R, "Rent", oct.atDay(3))
        add(TxnType.EXPENSE, 6_200 * R, "Food", oct.atDay(20))
        e.budgets.save(null, e.cat("Food"), oct, 5_000 * R)
        e.budgets.save(null, e.cat("Rent"), oct, 15_000 * R)

        val engine = ReminderEngine(e.db, e.notifier)
        engine.run(nov2, LocalTime.of(10, 0))

        val (title, text) = e.notifier.summaries.single()
        assertThat(title).startsWith("October summary: you saved")
        assertThat(text).contains("Saved 57%")                 // 28,800 of 50,000
        assertThat(text).contains("Budgets: 1 of 2 kept")
        assertThat(text).contains("Food over by")
        assertThat(text).contains("(24%)")                     // 6,200 vs 5,000
        assertThat(text).contains("Biggest spend: Rent")
        assertThat(text).contains("Spending up 6% vs September") // 21,200 vs 20,000
        assertThat(e.notifier.budgetSetups.single()).isEqualTo("November" to 2)

        // Once per month only.
        engine.run(LocalDate.of(2026, 11, 3), LocalTime.of(10, 0))
        assertThat(e.notifier.summaries).hasSize(1)
        assertThat(e.notifier.budgetSetups).hasSize(1)
    }

    @Test fun noSummaryForAnEmptyMonthAndNoReminderOnceBudgetsExist() = runTest {
        e.budgets.save(null, e.cat("Food"), YearMonth.of(2026, 11), 4_000 * R)
        ReminderEngine(e.db, e.notifier).run(nov2, LocalTime.of(10, 0))
        assertThat(e.notifier.summaries).isEmpty()
        assertThat(e.notifier.budgetSetups).isEmpty()
    }

    @Test fun nothingMidMonth() = runTest {
        add(TxnType.EXPENSE, 500 * R, "Food", oct.atDay(4))
        ReminderEngine(e.db, e.notifier).run(LocalDate.of(2026, 11, 15), LocalTime.of(10, 0))
        assertThat(e.notifier.summaries).isEmpty()
        assertThat(e.notifier.budgetSetups).isEmpty()
    }
}
