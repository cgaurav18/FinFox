package com.mymoney.app.data

import com.google.common.truth.Truth.assertThat
import com.mymoney.app.core.DateRange
import com.mymoney.app.core.Dates
import com.mymoney.app.core.FinanceException
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.Frequency
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.data.db.OccurrenceStatus
import com.mymoney.app.data.db.ScheduleCategory
import com.mymoney.app.data.db.ScheduleStatus
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.data.repo.AccountInput
import com.mymoney.app.data.repo.AccountRepository
import com.mymoney.app.data.repo.GoalInput
import com.mymoney.app.data.repo.LoanInput
import com.mymoney.app.data.repo.ScheduleInput
import com.mymoney.app.data.repo.SettleMode
import com.mymoney.app.data.repo.TxnFilter
import com.mymoney.app.data.repo.TxnInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceRepositoryTest {
    private lateinit var e: Env
    private val now = Dates.nowMillis()

    @Before fun setUp() = runTest { e = Env.inMemory(); e.seed() }
    @After fun tearDown() { e.db.close() }

    private suspend fun expense(acc: Long, amount: Long, cat: String = "Food", date: Long = now) =
        e.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = amount, accountId = acc, categoryId = e.cat(cat), dateMillis = date))
    private suspend fun income(acc: Long, amount: Long) =
        e.txns.save(TxnInput(type = TxnType.INCOME, amountMinor = amount, accountId = acc, categoryId = e.cat("Salary", CategoryType.INCOME), dateMillis = now))
    private suspend fun transfer(from: Long, to: Long, amount: Long) =
        e.txns.save(TxnInput(type = TxnType.TRANSFER, amountMinor = amount, accountId = from, toAccountId = to, dateMillis = now))
    private fun <T> fails(block: suspend () -> T): FinanceException = assertThrows(FinanceException::class.java) { kotlinx.coroutines.runBlocking { block() } }

    // ---- Accounts ----

    @Test fun accountCrudWithAuditTrailAndArchive() = runTest {
        val id = e.bank("HDFC", 125_000 * R)
        assertThat(e.balance(id)).isEqualTo(125_000 * R)
        e.accounts.save(AccountInput(id = id, type = AccountType.BANK, name = "Salary A/c", bankName = "HDFC", openingMinor = 120_000 * R, colorArgb = 1, iconKey = "bank", adjustmentReason = "Typo"))
        val acc = e.accounts.get(id)!!
        assertThat(acc.name).isEqualTo("Salary A/c")
        assertThat(e.balance(id)).isEqualTo(120_000 * R)
        val audit = e.accounts.observeAdjustments(id).first()
        assertThat(audit).hasSize(1)
        assertThat(audit[0].oldOpeningMinor).isEqualTo(125_000 * R)
        assertThat(audit[0].reason).isEqualTo("Typo")

        e.accounts.setArchived(id, true)
        assertThat(e.accounts.observeAccounts(false).first()).isEmpty()
        assertThat(e.accounts.observeAccounts(true).first()).hasSize(1)

        e.accounts.delete(id)
        assertThat(e.accounts.get(id)).isNull()
    }

    @Test fun accountWithHistoryCannotBeDeleted() = runTest {
        val id = e.bank(opening = 1_000 * R)
        expense(id, 100 * R)
        val ex = fails { e.accounts.delete(id) }
        assertThat(ex.message).contains("Archive")
        assertThat(e.accounts.get(id)).isNotNull()
        assertThat(e.db.transactionDao().count()).isEqualTo(1)
    }

    @Test fun accountValidation() = runTest {
        fails { e.accounts.save(AccountInput(type = AccountType.BANK, name = "", bankName = "X", openingMinor = 0, colorArgb = 0, iconKey = "bank")) }
        fails { e.accounts.save(AccountInput(type = AccountType.BANK, name = "A", bankName = "X", last4 = "12", openingMinor = 0, colorArgb = 0, iconKey = "bank")) }
        fails { e.accounts.save(AccountInput(type = AccountType.CREDIT_CARD, name = "C", bankName = "X", openingMinor = 0, creditLimitMinor = null, colorArgb = 0, iconKey = "cc")) }
    }

    @Test fun reconcileAdjustsOpeningWithoutCreatingIncome() = runTest {
        val id = e.bank(opening = 1_000 * R)
        expense(id, 200 * R)
        e.accounts.reconcileBalance(id, 900 * R, "Bank statement")
        assertThat(e.balance(id)).isEqualTo(900 * R)
        assertThat(e.db.transactionDao().count()).isEqualTo(1)
        assertThat(e.accounts.observeAdjustments(id).first().first().reason).isEqualTo("Bank statement")
    }

    // ---- Transactions & balances ----

    @Test fun incomeExpenseAndEditDeleteRecalculate() = runTest {
        val bank = e.bank(opening = 10_000 * R)
        val inc = income(bank, 50_000 * R)
        val exp = expense(bank, 1_200 * R)
        assertThat(e.balance(bank)).isEqualTo((10_000 + 50_000 - 1_200) * R)

        e.txns.save(TxnInput(id = exp, type = TxnType.EXPENSE, amountMinor = 2_000 * R, accountId = bank, categoryId = e.cat("Fuel"), dateMillis = now))
        assertThat(e.balance(bank)).isEqualTo((10_000 + 50_000 - 2_000) * R)
        assertThat(e.db.transactionDao().getById(exp)!!.categoryId).isEqualTo(e.cat("Fuel"))

        // Move the expense to cash: bank gets it back, cash goes down.
        val cash = e.cash(5_000 * R)
        e.txns.save(TxnInput(id = exp, type = TxnType.EXPENSE, amountMinor = 2_000 * R, accountId = cash, categoryId = e.cat("Fuel"), dateMillis = now))
        assertThat(e.balance(bank)).isEqualTo(60_000 * R)
        assertThat(e.balance(cash)).isEqualTo(3_000 * R)

        e.txns.delete(exp)
        assertThat(e.balance(cash)).isEqualTo(5_000 * R)
        e.txns.delete(inc)
        assertThat(e.balance(bank)).isEqualTo(10_000 * R)
    }

    @Test fun creditCardPurchaseAndBillPayment() = runTest {
        val bank = e.bank(opening = 100_000 * R)
        val card = e.card(outstanding = 42_500 * R)
        assertThat(e.db.accountDao().getWithBalance(card)!!.outstandingMinor).isEqualTo(42_500 * R)

        expense(card, 3_000 * R, "Fuel")
        val cardAwb = e.db.accountDao().getWithBalance(card)!!
        assertThat(cardAwb.outstandingMinor).isEqualTo(45_500 * R)
        assertThat(e.balance(bank)).isEqualTo(100_000 * R) // purchase doesn't touch bank

        transfer(bank, card, 45_500 * R) // bill payment
        assertThat(e.balance(bank)).isEqualTo(54_500 * R)
        assertThat(e.db.accountDao().getWithBalance(card)!!.outstandingMinor).isEqualTo(0)

        // Spending counted once: the purchase only; the bill payment is a transfer.
        val month = DateRange.month(YearMonth.now())
        assertThat(e.txns.observeSum(TxnType.EXPENSE, month).first()).isEqualTo(3_000 * R)

        val totals = AccountRepository.totals(e.accounts.observeAccounts().first())
        assertThat(totals.availableMinor).isEqualTo(54_500 * R) // unused credit limit is not money
        assertThat(totals.creditOutstandingMinor).isEqualTo(0)
    }

    @Test fun transfersMoveMoneyButAreNotIncomeOrExpense() = runTest {
        val a = e.bank("A", 10_000 * R)
        val b = e.bank("B", 0)
        val t = transfer(a, b, 4_000 * R)
        assertThat(e.balance(a)).isEqualTo(6_000 * R)
        assertThat(e.balance(b)).isEqualTo(4_000 * R)
        val month = DateRange.month(YearMonth.now())
        assertThat(e.txns.observeSum(TxnType.EXPENSE, month).first()).isEqualTo(0)
        assertThat(e.txns.observeSum(TxnType.INCOME, month).first()).isEqualTo(0)
        e.txns.delete(t)
        assertThat(e.balance(a)).isEqualTo(10_000 * R)
        assertThat(e.balance(b)).isEqualTo(0)
    }

    @Test fun invalidTransactionsRejected() = runTest {
        val a = e.bank(opening = 100 * R)
        val card = e.card()
        fails { transfer(a, a, 10 * R) }
        fails { transfer(card, a, 10 * R) }
        fails { e.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = 0, accountId = a, categoryId = e.cat("Food"), dateMillis = now)) }
        fails { e.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = 10, accountId = a, categoryId = e.cat("Salary", CategoryType.INCOME), dateMillis = now)) }
        e.accounts.setArchived(a, true)
        fails { expense(a, 10 * R) }
        assertThat(e.db.transactionDao().count()).isEqualTo(0)
    }

    @Test fun filteringSearchAndDateRanges() = runTest {
        val a = e.bank(opening = 0)
        val today = Dates.today()
        val lastMonth = Dates.toMillis(today.minusMonths(1).withDayOfMonth(10), java.time.LocalTime.NOON)
        e.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = 500 * R, accountId = a, categoryId = e.cat("Food"), dateMillis = now, merchant = "Cafe Coffee", tags = "work"))
        e.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = 5_000 * R, accountId = a, categoryId = e.cat("Shopping"), dateMillis = lastMonth))
        income(a, 20_000 * R)
        val all = DateRange(LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1))
        assertThat(e.txns.observeFiltered(TxnFilter(all)).first()).hasSize(3)
        assertThat(e.txns.observeFiltered(TxnFilter(all, type = TxnType.EXPENSE)).first()).hasSize(2)
        assertThat(e.txns.observeFiltered(TxnFilter(all, query = "coffee")).first()).hasSize(1)
        assertThat(e.txns.observeFiltered(TxnFilter(all, query = "work")).first()).hasSize(1)
        assertThat(e.txns.observeFiltered(TxnFilter(all, categoryId = e.cat("Shopping"))).first()).hasSize(1)
        assertThat(e.txns.observeFiltered(TxnFilter(all, minAmountMinor = 1_000 * R, maxAmountMinor = 6_000 * R)).first()).hasSize(1)
        assertThat(e.txns.observeFiltered(TxnFilter(DateRange.month(YearMonth.from(today)))).first()).hasSize(2)
        val lm = com.mymoney.app.core.PeriodFilter.LAST_MONTH.range(today, null)
        assertThat(e.txns.observeFiltered(TxnFilter(lm)).first().single().txn.amountMinor).isEqualTo(5_000 * R)
    }

    // ---- Loans ----

    @Test fun lendingWithPartialAndFullRepayment() = runTest {
        val bank = e.bank(opening = 50_000 * R)
        val loan = e.loans.saveLoan(LoanInput(personName = "Amit Shah", direction = LoanDirection.GAVE, amountMinor = 10_000 * R, dateMillis = now, accountId = bank))
        assertThat(e.balance(bank)).isEqualTo(40_000 * R)
        assertThat(e.loans.observeOutstanding(LoanDirection.GAVE).first()).isEqualTo(10_000 * R)
        val month = DateRange.month(YearMonth.now())
        assertThat(e.txns.observeSum(TxnType.EXPENSE, month).first()).isEqualTo(0) // lending isn't an expense

        e.loans.addRepayment(loan, 4_000 * R, now, bank, null)
        assertThat(e.balance(bank)).isEqualTo(44_000 * R)
        assertThat(e.loans.getLoan(loan)!!.outstandingMinor).isEqualTo(6_000 * R)

        fails { e.loans.addRepayment(loan, 7_000 * R, now, bank, null) } // more than outstanding

        e.loans.settle(loan, SettleMode.RecordRemaining(bank))
        val l = e.loans.getLoan(loan)!!
        assertThat(l.loan.settled).isTrue()
        assertThat(l.outstandingMinor).isEqualTo(0)
        assertThat(e.balance(bank)).isEqualTo(50_000 * R)
        assertThat(e.loans.observeOutstanding(LoanDirection.GAVE).first()).isEqualTo(0)
    }

    @Test fun borrowingEditsAndDeletesKeepBalancesAccurate() = runTest {
        val cash = e.cash(1_000 * R)
        val loan = e.loans.saveLoan(LoanInput(personName = "Ravi", direction = LoanDirection.RECEIVED, amountMinor = 15_000 * R, dateMillis = now, accountId = cash))
        assertThat(e.balance(cash)).isEqualTo(16_000 * R)
        val rep = e.loans.addRepayment(loan, 5_000 * R, now, cash, "first")
        assertThat(e.balance(cash)).isEqualTo(11_000 * R)
        assertThat(e.loans.observeOutstanding(LoanDirection.RECEIVED).first()).isEqualTo(10_000 * R)

        fails { e.loans.saveLoan(LoanInput(id = loan, personName = "Ravi", direction = LoanDirection.RECEIVED, amountMinor = 4_000 * R, dateMillis = now, accountId = cash)) }
        e.loans.saveLoan(LoanInput(id = loan, personName = "Ravi", direction = LoanDirection.RECEIVED, amountMinor = 12_000 * R, dateMillis = now, accountId = cash))
        assertThat(e.balance(cash)).isEqualTo(8_000 * R)

        e.loans.updateRepayment(rep, 2_000 * R, now, cash, null)
        assertThat(e.balance(cash)).isEqualTo(11_000 * R)
        e.loans.deleteRepayment(rep)
        assertThat(e.balance(cash)).isEqualTo(13_000 * R)

        e.loans.deleteLoan(loan)
        assertThat(e.balance(cash)).isEqualTo(1_000 * R)
        assertThat(e.db.transactionDao().count()).isEqualTo(0)
    }

    @Test fun loanTransactionsCantBeEditedFromTransactions() = runTest {
        val bank = e.bank(opening = 100 * R)
        val loan = e.loans.saveLoan(LoanInput(personName = "P", direction = LoanDirection.GAVE, amountMinor = 50 * R, dateMillis = now, accountId = bank))
        val txId = e.db.transactionDao().getLoanPrincipal(loan)!!.id
        fails { e.txns.delete(txId) }
        assertThat(e.balance(bank)).isEqualTo(50 * R)
    }

    @Test fun writeOffSettlesWithoutMovingMoney() = runTest {
        val bank = e.bank(opening = 1_000 * R)
        val loan = e.loans.saveLoan(LoanInput(personName = "Z", direction = LoanDirection.GAVE, amountMinor = 300 * R, dateMillis = now, accountId = bank))
        e.loans.settle(loan, SettleMode.WriteOff)
        assertThat(e.loans.getLoan(loan)!!.loan.writtenOffMinor).isEqualTo(300 * R)
        assertThat(e.balance(bank)).isEqualTo(700 * R)
        e.loans.reopen(loan)
        assertThat(e.loans.observeOutstanding(LoanDirection.GAVE).first()).isEqualTo(300 * R)
    }

    // ---- Scheduled payments ----

    @Test fun markPaidCreatesTransactionAdvancesAndPreventsDuplicates() = runTest {
        val bank = e.bank(opening = 50_000 * R)
        val start = Dates.today().minusMonths(1)
        val id = e.schedules.save(ScheduleInput(title = "Car EMI", category = ScheduleCategory.EMI, amountMinor = 12_500 * R, accountId = bank, start = start, frequency = Frequency.MONTHLY, nextDue = start))
        assertThat(e.balance(bank)).isEqualTo(50_000 * R) // nothing deducted automatically

        e.schedules.markPaid(id, start)
        assertThat(e.balance(bank)).isEqualTo(37_500 * R)
        val s = e.schedules.get(id)!!
        assertThat(LocalDate.ofEpochDay(s.nextDueEpochDay)).isEqualTo(start.plusMonths(1))

        val dup = fails { e.schedules.markPaid(id, start) }
        assertThat(dup.message).contains("already paid")
        assertThat(e.balance(bank)).isEqualTo(37_500 * R)
        assertThat(e.db.transactionDao().count()).isEqualTo(1)

        // Skip the next one, no money moves.
        e.schedules.skip(id)
        assertThat(e.balance(bank)).isEqualTo(37_500 * R)
        assertThat(LocalDate.ofEpochDay(e.schedules.get(id)!!.nextDueEpochDay)).isEqualTo(start.plusMonths(2))
        val hist = e.schedules.observeOccurrences(id).first()
        assertThat(hist.map { it.occurrence.status }).containsExactly(OccurrenceStatus.SKIPPED, OccurrenceStatus.PAID)
    }

    @Test fun deletingPaidTransactionReopensInstallment() = runTest {
        val bank = e.bank(opening = 10_000 * R)
        val due = Dates.today()
        val id = e.schedules.save(ScheduleInput(title = "Rent", category = ScheduleCategory.RENT, amountMinor = 5_000 * R, accountId = bank, start = due, frequency = Frequency.MONTHLY, nextDue = due))
        val tx = e.schedules.markPaid(id, due)
        e.txns.delete(tx)
        assertThat(e.balance(bank)).isEqualTo(10_000 * R)
        assertThat(e.schedules.get(id)!!.nextDueEpochDay).isEqualTo(due.toEpochDay())
        e.schedules.markPaid(id, due) // can be paid again
        assertThat(e.balance(bank)).isEqualTo(5_000 * R)
    }

    @Test fun creditCardBillScheduleIsATransferAndScheduleCompletes() = runTest {
        val bank = e.bank(opening = 20_000 * R)
        val card = e.card(outstanding = 8_000 * R)
        val today = Dates.today()
        val id = e.schedules.save(ScheduleInput(title = "Card bill", category = ScheduleCategory.CREDIT_CARD_BILL, amountMinor = 8_000 * R, accountId = bank, toAccountId = card,
            start = today, end = today, frequency = Frequency.MONTHLY, nextDue = today))
        e.schedules.markPaid(id)
        assertThat(e.db.accountDao().getWithBalance(card)!!.outstandingMinor).isEqualTo(0)
        assertThat(e.txns.observeSum(TxnType.EXPENSE, DateRange.month(YearMonth.now())).first()).isEqualTo(0)
        assertThat(e.schedules.get(id)!!.status).isEqualTo(ScheduleStatus.COMPLETED)
        // A card bill must target a card.
        fails { e.schedules.save(ScheduleInput(title = "x", category = ScheduleCategory.CREDIT_CARD_BILL, amountMinor = 1, accountId = bank, start = today, frequency = Frequency.MONTHLY)) }
    }

    @Test fun recurringOptionCreatesSchedule() = runTest {
        val bank = e.bank(opening = 1_000 * R)
        e.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = 599 * R, accountId = bank, categoryId = e.cat("Bills"), dateMillis = now, merchant = "Mobile Recharge", recurring = Frequency.MONTHLY))
        val s = e.schedules.observeAll().first().single().schedule
        assertThat(s.title).isEqualTo("Mobile Recharge")
        assertThat(LocalDate.ofEpochDay(s.nextDueEpochDay)).isEqualTo(Dates.today().plusMonths(1))
    }

    @Test fun remindersPostOnceAndNeverMoveMoney() = runTest {
        val bank = e.bank(opening = 1_000 * R)
        val today = Dates.today()
        val id = e.schedules.save(ScheduleInput(title = "Internet", category = ScheduleCategory.INTERNET, amountMinor = 700 * R, accountId = bank, start = today.plusDays(1), frequency = Frequency.MONTHLY, nextDue = today.plusDays(1), reminderDaysBefore = 1))
        val engine = com.mymoney.app.notifications.ReminderEngine(e.db, e.notifier)
        assertThat(engine.run(today, java.time.LocalTime.of(10, 0))).isEqualTo(1)
        assertThat(engine.run(today, java.time.LocalTime.of(12, 0))).isEqualTo(0)
        assertThat(e.notifier.schedules).containsExactly(id to false)
        // Next day after due: an overdue reminder fires once.
        assertThat(engine.run(today.plusDays(2), java.time.LocalTime.of(10, 0))).isEqualTo(1)
        assertThat(e.notifier.schedules.last()).isEqualTo(id to true)
        // Before the configured reminder hour nothing is posted.
        assertThat(engine.run(today.plusDays(3), java.time.LocalTime.of(6, 0))).isEqualTo(0)
        assertThat(e.balance(bank)).isEqualTo(1_000 * R)
    }

    // ---- Budgets ----

    @Test fun budgetsCountOnlyCategoryExpensesAndAlertOnce() = runTest {
        val bank = e.bank(opening = 100_000 * R)
        val card = e.card()
        val ym = YearMonth.now()
        e.budgets.save(null, e.cat("Food"), ym, 5_000 * R)
        expense(bank, 3_000 * R, "Food")
        expense(card, 1_100 * R, "Food") // 82%
        expense(bank, 9_000 * R, "Shopping") // other category
        transfer(bank, card, 1_100 * R) // card bill, not spending
        e.loans.saveLoan(LoanInput(personName = "X", direction = LoanDirection.GAVE, amountMinor = 2_000 * R, dateMillis = now, accountId = bank))
        val b = e.budgets.observeMonth(ym).first().single()
        assertThat(b.spentMinor).isEqualTo(4_100 * R)
        assertThat(e.notifier.budget).containsExactly("Food" to 80)

        expense(bank, 50 * R, "Food") // still 83%: no new alert
        assertThat(e.notifier.budget).hasSize(1)
        expense(bank, 900 * R, "Food") // 101%: jumps to 100 alert
        assertThat(e.notifier.budget.last()).isEqualTo("Food" to 100)
        assertThat(e.notifier.budget).hasSize(2)

        fails { e.budgets.save(null, e.cat("Food"), ym, 1 * R) } // duplicate for the month
        assertThat(e.budgets.copyFromPrevious(ym.plusMonths(1))).isEqualTo(1)
    }

    // ---- Savings goals ----

    @Test fun savingsContributionsAreEarmarksNotMovements() = runTest {
        val bank = e.bank(opening = 10_000 * R)
        val g = e.goals.save(GoalInput(name = "Trip", targetMinor = 8_000 * R, initialSavedMinor = 2_000 * R, linkedAccountId = bank, colorArgb = 0))
        e.goals.contribute(g, 3_000 * R, now, null)
        assertThat(e.db.goalDao().savedFor(g)).isEqualTo(5_000 * R)
        assertThat(e.balance(bank)).isEqualTo(10_000 * R) // no double counting

        val g2 = e.goals.save(GoalInput(name = "Phone", targetMinor = 9_000 * R, linkedAccountId = bank, colorArgb = 0))
        fails { e.goals.contribute(g2, 6_000 * R, now, null) } // only 5,000 unallocated
        e.goals.contribute(g2, 5_000 * R, now, null)

        fails { e.goals.withdraw(g, 6_000 * R, now, null) }
        e.goals.withdraw(g, 1_000 * R, now, null)
        assertThat(e.db.goalDao().savedFor(g)).isEqualTo(4_000 * R)
        val goal = e.goals.observeGoal(g).first()!!
        assertThat(goal.savedMinor).isEqualTo(4_000 * R)
        e.goals.setArchived(g, true)
        assertThat(e.goals.observeGoals(false).first().map { it.goal.id }).containsExactly(g2)
        e.goals.delete(g)
        assertThat(e.goals.get(g)).isNull()
    }

    // ---- Categories ----

    @Test fun usedCategoriesCanOnlyBeArchived() = runTest {
        val bank = e.bank(opening = 100 * R)
        val id = e.categories.save(null, "Pets", CategoryType.EXPENSE, "pets", 0)
        fails { e.categories.save(null, "pets", CategoryType.EXPENSE, "pets", 0) } // duplicate name
        e.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = 10 * R, accountId = bank, categoryId = id, dateMillis = now))
        fails { e.categories.delete(id) }
        e.categories.setArchived(id, true)
        fails { e.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = 10 * R, accountId = bank, categoryId = id, dateMillis = now)) }
        fails { e.categories.delete(e.cat("Food")) } // built-in
    }
}
