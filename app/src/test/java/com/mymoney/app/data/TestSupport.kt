package com.mymoney.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.DefaultData
import com.mymoney.app.data.repo.AccountInput
import com.mymoney.app.data.repo.AccountRepository
import com.mymoney.app.data.repo.BudgetAlertChecker
import com.mymoney.app.data.repo.BudgetRepository
import com.mymoney.app.data.repo.CategoryRepository
import com.mymoney.app.data.repo.GoalRepository
import com.mymoney.app.data.repo.LoanRepository
import com.mymoney.app.data.repo.ScheduleRepository
import com.mymoney.app.data.repo.TransactionRepository
import com.mymoney.app.notifications.BackupEvent
import com.mymoney.app.notifications.GoalAlert
import com.mymoney.app.notifications.Notifier
import com.mymoney.app.data.db.ScheduleCategory
import java.time.LocalDate
import java.util.concurrent.Executors

class FakeNotifier : Notifier {
    val budget = mutableListOf<Pair<String, Int>>()
    val schedules = mutableListOf<Pair<Long, Boolean>>()
    val loans = mutableListOf<Long>()
    val cards = mutableListOf<Pair<Long, LocalDate>>()
    val repayments = mutableListOf<Pair<Long, Long>>()
    val goals = mutableListOf<Pair<Long, GoalAlert>>()
    val backups = mutableListOf<BackupEvent>()
    override suspend fun budgetAlert(budgetId: Long, categoryName: String, percent: Int, spentMinor: Long, limitMinor: Long, currency: String) { budget += categoryName to percent }
    override suspend fun scheduleReminder(scheduleId: Long, title: String, amountMinor: Long, due: LocalDate, overdue: Boolean, currency: String, category: ScheduleCategory) { schedules += scheduleId to overdue }
    override suspend fun loanReminder(loanId: Long, person: String, outstandingMinor: Long, gave: Boolean, due: LocalDate, currency: String) { loans += loanId }
    override suspend fun cardDueReminder(accountId: Long, cardName: String, outstandingMinor: Long, due: LocalDate, currency: String) { cards += accountId to due }
    override suspend fun repaymentRecorded(loanId: Long, person: String, amountMinor: Long, outstandingMinor: Long, gave: Boolean, currency: String) { repayments += loanId to outstandingMinor }
    override suspend fun goalAlert(goalId: Long, name: String, alert: GoalAlert, savedMinor: Long, targetMinor: Long, targetDate: LocalDate?, currency: String) { goals += goalId to alert }
    override suspend fun backupEvent(event: BackupEvent, detail: String?) { backups += event }
}

/** Wires real repositories over a Room database, as Hilt does in the app. */
class Env(val db: AppDatabase) {
    val notifier = FakeNotifier()
    val alerts = BudgetAlertChecker(db, notifier)
    val accounts = AccountRepository(db)
    val categories = CategoryRepository(db)
    val schedules = ScheduleRepository(db, alerts)
    val txns = TransactionRepository(db, schedules, alerts)
    val loans = LoanRepository(db, notifier)
    val budgets = BudgetRepository(db, alerts)
    val goals = GoalRepository(db, notifier)

    suspend fun seed() = DefaultData.ensureSeeded(db)
    suspend fun cat(name: String, type: CategoryType = CategoryType.EXPENSE) = db.categoryDao().findByName(name, type.name)!!.id
    suspend fun balance(id: Long) = db.accountDao().getWithBalance(id)!!.balanceMinor

    suspend fun bank(name: String = "HDFC", opening: Long = 0) = accounts.save(AccountInput(type = AccountType.BANK, name = name, bankName = name, openingMinor = opening, colorArgb = 0, iconKey = "bank"))
    suspend fun cash(opening: Long = 0) = accounts.save(AccountInput(type = AccountType.CASH, name = "Wallet", openingMinor = opening, colorArgb = 0, iconKey = "cash"))
    suspend fun card(outstanding: Long = 0, limit: Long = 10_000_000) = accounts.save(AccountInput(type = AccountType.CREDIT_CARD, name = "ICICI Card", bankName = "ICICI", openingMinor = outstanding, creditLimitMinor = limit, colorArgb = 0, iconKey = "credit_card"))

    companion object {
        fun inMemory(): Env {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
                .setQueryExecutor(Executors.newSingleThreadExecutor())
                .setTransactionExecutor(Executors.newSingleThreadExecutor())
                .build()
            return Env(db)
        }
    }
}

const val R = 100L // one rupee in paise
