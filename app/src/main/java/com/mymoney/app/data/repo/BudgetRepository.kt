package com.mymoney.app.data.repo

import androidx.room.withTransaction
import com.mymoney.app.core.DateRange
import com.mymoney.app.core.Dates
import com.mymoney.app.core.FinanceException
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.BudgetEntity
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.ReminderRefType
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetRepository @Inject constructor(private val db: AppDatabase, private val alerts: BudgetAlertChecker) {
    private val dao = db.budgetDao()

    fun observeMonth(ym: YearMonth) = DateRange.month(ym).let { dao.observeForMonth(Dates.ymKey(ym), it.startMillis, it.endMillisExclusive) }
    fun observeMonths() = dao.observeMonths()

    suspend fun save(id: Long?, categoryId: Long?, ym: YearMonth, limitMinor: Long): Long {
        val savedId = db.withTransaction {
            val cat = categoryId?.let { db.categoryDao().getById(it) } ?: throw FinanceException("Select a category")
            if (cat.type != CategoryType.EXPENSE) throw FinanceException("Budgets apply to expense categories")
            if (limitMinor <= 0) throw FinanceException("Budget must be greater than zero")
            if (limitMinor > Money.MAX_MINOR) throw FinanceException("Amount is too large")
            val key = Dates.ymKey(ym)
            val clash = dao.getFor(cat.id, key)
            if (clash != null && clash.id != id) throw FinanceException("${cat.name} already has a budget for ${Dates.formatMonth(ym)}")
            val now = Dates.nowMillis()
            if (id == null) dao.insert(BudgetEntity(categoryId = cat.id, yearMonth = key, limitMinor = limitMinor, createdAt = now, updatedAt = now))
            else {
                val old = dao.getById(id) ?: throw FinanceException("Budget not found")
                dao.update(old.copy(categoryId = cat.id, yearMonth = key, limitMinor = limitMinor, updatedAt = now))
                // Limit changed: thresholds should be re-evaluated against the new limit.
                db.reminderDao().deleteFor(ReminderRefType.BUDGET.name, id)
                id
            }
        }
        alerts.check(categoryId, Dates.startOfDay(ym.atDay(1)))
        return savedId
    }

    suspend fun delete(id: Long) = db.withTransaction {
        dao.deleteById(id)
        db.reminderDao().deleteFor(ReminderRefType.BUDGET.name, id)
    }

    /** Copies last month's budgets into [ym] for categories that don't have one yet. Returns count copied. */
    suspend fun copyFromPrevious(ym: YearMonth): Int = db.withTransaction {
        val prev = dao.getForMonth(Dates.ymKey(ym.minusMonths(1)))
        if (prev.isEmpty()) throw FinanceException("No budgets found for ${Dates.formatMonth(ym.minusMonths(1))}")
        val key = Dates.ymKey(ym)
        val existing = dao.getForMonth(key).map { it.categoryId }.toSet()
        val now = Dates.nowMillis()
        var n = 0
        prev.filter { it.categoryId !in existing }.forEach {
            dao.insert(BudgetEntity(categoryId = it.categoryId, yearMonth = key, limitMinor = it.limitMinor, createdAt = now, updatedAt = now)); n++
        }
        n
    }
}
