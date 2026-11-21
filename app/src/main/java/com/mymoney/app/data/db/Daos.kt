package com.mymoney.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Balance = opening + signed effect of every transaction where the account is the source
 * + incoming transfers. Balances are derived, never stored, so edits/deletes can't drift.
 */
private const val BALANCE_EXPR = """
    a.openingBalanceMinor
    + COALESCE((SELECT SUM(CASE WHEN t.type IN ('INCOME','LOAN_TAKEN','LOAN_REPAYMENT_RECEIVED') THEN t.amountMinor ELSE -t.amountMinor END)
                FROM transactions t WHERE t.accountId = a.id), 0)
    + COALESCE((SELECT SUM(t2.amountMinor) FROM transactions t2 WHERE t2.toAccountId = a.id AND t2.type = 'TRANSFER'), 0)
"""

@Dao
interface AccountDao {
    @Query("SELECT a.*, $BALANCE_EXPR AS balanceMinor FROM accounts a WHERE (:includeArchived = 1 OR a.archived = 0) ORDER BY a.archived, a.type, a.name COLLATE NOCASE")
    fun observeWithBalance(includeArchived: Boolean): Flow<List<AccountWithBalance>>

    @Query("SELECT a.*, $BALANCE_EXPR AS balanceMinor FROM accounts a WHERE a.id = :id")
    fun observeWithBalance(id: Long): Flow<AccountWithBalance?>

    @Query("SELECT a.*, $BALANCE_EXPR AS balanceMinor FROM accounts a WHERE a.id = :id")
    suspend fun getWithBalance(id: Long): AccountWithBalance?

    @Query("SELECT a.*, $BALANCE_EXPR AS balanceMinor FROM accounts a ORDER BY a.type, a.name COLLATE NOCASE")
    suspend fun getAllWithBalance(): List<AccountWithBalance>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query(
        """SELECT (SELECT COUNT(*) FROM transactions WHERE accountId = :id OR toAccountId = :id)
         + (SELECT COUNT(*) FROM scheduled_payments WHERE accountId = :id OR toAccountId = :id)
         + (SELECT COUNT(*) FROM loan_ledger WHERE accountId = :id)
         + (SELECT COUNT(*) FROM loan_repayments WHERE accountId = :id)"""
    )
    suspend fun referenceCount(id: Long): Int

    @Insert
    suspend fun insertAdjustment(adj: BalanceAdjustmentEntity): Long

    @Query("SELECT * FROM balance_adjustments WHERE accountId = :accountId ORDER BY createdAt DESC")
    fun observeAdjustments(accountId: Long): Flow<List<BalanceAdjustmentEntity>>
}

private const val TXN_DETAIL_SELECT = """
    SELECT t.*, a.name AS accountName, a.type AS accountType,
           ta.name AS toAccountName, ta.type AS toAccountType,
           c.name AS categoryName, c.iconKey AS categoryIconKey, c.colorArgb AS categoryColor,
           p.name AS personName
    FROM transactions t
    JOIN accounts a ON a.id = t.accountId
    LEFT JOIN accounts ta ON ta.id = t.toAccountId
    LEFT JOIN categories c ON c.id = t.categoryId
    LEFT JOIN loan_ledger l ON l.id = t.loanId
    LEFT JOIN people p ON p.id = l.personId
"""

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(txn: TransactionEntity): Long

    @Update
    suspend fun update(txn: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("$TXN_DETAIL_SELECT WHERE t.id = :id")
    fun observeDetail(id: Long): Flow<TransactionDetail?>

    @Query(
        """$TXN_DETAIL_SELECT
        WHERE t.dateMillis >= :from AND t.dateMillis < :to
          AND (:type IS NULL OR t.type = :type)
          AND (:accountId IS NULL OR t.accountId = :accountId OR t.toAccountId = :accountId)
          AND (:categoryId IS NULL OR t.categoryId = :categoryId)
          AND (:minAmount IS NULL OR t.amountMinor >= :minAmount)
          AND (:maxAmount IS NULL OR t.amountMinor <= :maxAmount)
          AND (:q = '' OR t.merchant LIKE '%' || :q || '%' OR t.note LIKE '%' || :q || '%'
               OR t.tags LIKE '%' || :q || '%' OR c.name LIKE '%' || :q || '%'
               OR a.name LIKE '%' || :q || '%' OR p.name LIKE '%' || :q || '%')
        ORDER BY t.dateMillis DESC, t.id DESC"""
    )
    fun observeFiltered(
        from: Long, to: Long, type: String?, accountId: Long?, categoryId: Long?,
        minAmount: Long?, maxAmount: Long?, q: String,
    ): Flow<List<TransactionDetail>>

    @Query("$TXN_DETAIL_SELECT ORDER BY t.dateMillis DESC, t.id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionDetail>>

    @Query("$TXN_DETAIL_SELECT WHERE t.accountId = :accountId OR t.toAccountId = :accountId ORDER BY t.dateMillis DESC, t.id DESC")
    fun observeForAccount(accountId: Long): Flow<List<TransactionDetail>>

    @Query("$TXN_DETAIL_SELECT WHERE t.dateMillis >= :from AND t.dateMillis < :to ORDER BY t.dateMillis DESC, t.id DESC")
    suspend fun getDetailsInRange(from: Long, to: Long): List<TransactionDetail>

    @Query("$TXN_DETAIL_SELECT WHERE t.dateMillis >= :from AND t.dateMillis < :to ORDER BY t.dateMillis DESC, t.id DESC")
    fun observeDetailsInRange(from: Long, to: Long): Flow<List<TransactionDetail>>

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM transactions WHERE type = :type AND dateMillis >= :from AND dateMillis < :to")
    fun observeSum(type: String, from: Long, to: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM transactions WHERE type = 'EXPENSE' AND categoryId = :categoryId AND dateMillis >= :from AND dateMillis < :to")
    suspend fun expenseForCategory(categoryId: Long, from: Long, to: Long): Long

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun countForCategory(categoryId: Long): Int

    @Query("SELECT * FROM transactions WHERE repaymentId = :repaymentId LIMIT 1")
    suspend fun getByRepayment(repaymentId: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE loanId = :loanId AND repaymentId IS NULL LIMIT 1")
    suspend fun getLoanPrincipal(loanId: Long): TransactionEntity?

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE (:type IS NULL OR type = :type) AND (:includeArchived = 1 OR archived = 0) ORDER BY sortOrder, name COLLATE NOCASE")
    fun observe(type: String?, includeArchived: Boolean = false): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE AND type = :type LIMIT 1")
    suspend fun findByName(name: String, type: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM categories")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Insert
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)
}

private const val SCHEDULE_SELECT = """
    SELECT s.*, a.name AS accountName, ta.name AS toAccountName, c.name AS expenseCategoryName
    FROM scheduled_payments s
    JOIN accounts a ON a.id = s.accountId
    LEFT JOIN accounts ta ON ta.id = s.toAccountId
    LEFT JOIN categories c ON c.id = s.expenseCategoryId
"""

@Dao
interface ScheduleDao {
    @Query("$SCHEDULE_SELECT ORDER BY CASE s.status WHEN 'ACTIVE' THEN 0 WHEN 'PAUSED' THEN 1 ELSE 2 END, s.nextDueEpochDay")
    fun observeAll(): Flow<List<ScheduleDetail>>

    @Query("$SCHEDULE_SELECT WHERE s.id = :id")
    fun observe(id: Long): Flow<ScheduleDetail?>

    @Query("SELECT * FROM scheduled_payments WHERE id = :id")
    suspend fun getById(id: Long): ScheduledPaymentEntity?

    @Query("SELECT * FROM scheduled_payments WHERE status = 'ACTIVE'")
    suspend fun getActive(): List<ScheduledPaymentEntity>

    @Insert
    suspend fun insert(s: ScheduledPaymentEntity): Long

    @Update
    suspend fun update(s: ScheduledPaymentEntity)

    @Query("DELETE FROM scheduled_payments WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOccurrence(o: ScheduledPaymentOccurrenceEntity): Long

    @Update
    suspend fun updateOccurrence(o: ScheduledPaymentOccurrenceEntity)

    @Query("SELECT * FROM scheduled_payment_occurrences WHERE scheduleId = :scheduleId AND dueEpochDay = :due")
    suspend fun getOccurrence(scheduleId: Long, due: Long): ScheduledPaymentOccurrenceEntity?

    @Query(
        """SELECT o.*, t.dateMillis AS transactionDateMillis, a.name AS accountName
        FROM scheduled_payment_occurrences o
        LEFT JOIN transactions t ON t.id = o.transactionId
        LEFT JOIN accounts a ON a.id = t.accountId
        WHERE o.scheduleId = :scheduleId ORDER BY o.dueEpochDay DESC"""
    )
    fun observeOccurrences(scheduleId: Long): Flow<List<OccurrenceDetail>>

    @Query("SELECT * FROM scheduled_payment_occurrences WHERE transactionId = :txnId LIMIT 1")
    suspend fun getOccurrenceForTransaction(txnId: Long): ScheduledPaymentOccurrenceEntity?
}

private const val LOAN_SELECT = """
    SELECT l.*, p.name AS personName, p.phone AS personPhone, a.name AS accountName,
           COALESCE((SELECT SUM(r.amountMinor) FROM loan_repayments r WHERE r.loanId = l.id), 0) AS repaidMinor
    FROM loan_ledger l
    JOIN people p ON p.id = l.personId
    JOIN accounts a ON a.id = l.accountId
"""

@Dao
interface LoanDao {
    @Insert
    suspend fun insertPerson(p: PersonEntity): Long

    @Update
    suspend fun updatePerson(p: PersonEntity)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun deletePerson(id: Long)

    @Query("SELECT * FROM people WHERE id = :id")
    suspend fun getPerson(id: Long): PersonEntity?

    @Query("SELECT * FROM people WHERE id = :id")
    fun observePerson(id: Long): Flow<PersonEntity?>

    @Query("SELECT * FROM people WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findPersonByName(name: String): PersonEntity?

    @Query("SELECT * FROM people ORDER BY name COLLATE NOCASE")
    fun observePeople(): Flow<List<PersonEntity>>

    @Query("SELECT COUNT(*) FROM loan_ledger WHERE personId = :personId")
    suspend fun loanCountForPerson(personId: Long): Int

    @Query(
        """SELECT p.id AS personId, p.name AS name, p.phone AS phone,
          COALESCE(SUM(CASE WHEN l.direction = 'GAVE' AND l.settled = 0 THEN l.amountMinor - COALESCE((SELECT SUM(r.amountMinor) FROM loan_repayments r WHERE r.loanId = l.id), 0) ELSE 0 END), 0) AS lentOutstandingMinor,
          COALESCE(SUM(CASE WHEN l.direction = 'RECEIVED' AND l.settled = 0 THEN l.amountMinor - COALESCE((SELECT SUM(r.amountMinor) FROM loan_repayments r WHERE r.loanId = l.id), 0) ELSE 0 END), 0) AS borrowedOutstandingMinor,
          SUM(CASE WHEN l.settled = 0 THEN 1 ELSE 0 END) AS openCount
        FROM people p JOIN loan_ledger l ON l.personId = p.id
        GROUP BY p.id ORDER BY p.name COLLATE NOCASE"""
    )
    fun observePersonSummaries(): Flow<List<PersonSummary>>

    @Insert
    suspend fun insertLoan(l: LoanEntity): Long

    @Update
    suspend fun updateLoan(l: LoanEntity)

    @Query("DELETE FROM loan_ledger WHERE id = :id")
    suspend fun deleteLoan(id: Long)

    @Query("SELECT * FROM loan_ledger WHERE id = :id")
    suspend fun getLoan(id: Long): LoanEntity?

    @Query("$LOAN_SELECT WHERE l.id = :id")
    suspend fun getLoanDetail(id: Long): LoanDetail?

    @Query("$LOAN_SELECT WHERE l.id = :id")
    fun observeLoan(id: Long): Flow<LoanDetail?>

    @Query("$LOAN_SELECT WHERE l.direction = :direction ORDER BY l.settled, l.dateMillis DESC")
    fun observeLoans(direction: String): Flow<List<LoanDetail>>

    @Query("$LOAN_SELECT WHERE l.personId = :personId ORDER BY l.settled, l.dateMillis DESC")
    fun observeLoansForPerson(personId: Long): Flow<List<LoanDetail>>

    @Query("$LOAN_SELECT WHERE l.settled = 0 AND l.reminderEnabled = 1 AND l.dueEpochDay IS NOT NULL")
    suspend fun getOpenLoansWithReminders(): List<LoanDetail>

    @Query(
        """SELECT COALESCE(SUM(l.amountMinor - COALESCE((SELECT SUM(r.amountMinor) FROM loan_repayments r WHERE r.loanId = l.id), 0)), 0)
        FROM loan_ledger l WHERE l.direction = :direction AND l.settled = 0"""
    )
    fun observeOutstanding(direction: String): Flow<Long>

    @Query(
        """SELECT COALESCE(SUM(l.amountMinor - COALESCE((SELECT SUM(r.amountMinor) FROM loan_repayments r WHERE r.loanId = l.id), 0)), 0)
        FROM loan_ledger l WHERE l.direction = :direction AND l.settled = 0 AND l.dueEpochDay IS NOT NULL AND l.dueEpochDay < :today"""
    )
    fun observeOverdue(direction: String, today: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM loan_repayments WHERE loanId = :loanId")
    suspend fun repaidFor(loanId: Long): Long

    @Insert
    suspend fun insertRepayment(r: LoanRepaymentEntity): Long

    @Update
    suspend fun updateRepayment(r: LoanRepaymentEntity)

    @Query("DELETE FROM loan_repayments WHERE id = :id")
    suspend fun deleteRepayment(id: Long)

    @Query("SELECT * FROM loan_repayments WHERE id = :id")
    suspend fun getRepayment(id: Long): LoanRepaymentEntity?

    @Query(
        """SELECT r.*, a.name AS accountName FROM loan_repayments r JOIN accounts a ON a.id = r.accountId
        WHERE r.loanId = :loanId ORDER BY r.dateMillis DESC, r.id DESC"""
    )
    fun observeRepayments(loanId: Long): Flow<List<RepaymentDetail>>
}

@Dao
interface BudgetDao {
    @Query(
        """SELECT b.*, c.name AS categoryName, c.iconKey AS categoryIconKey, c.colorArgb AS categoryColor,
          COALESCE((SELECT SUM(t.amountMinor) FROM transactions t WHERE t.categoryId = b.categoryId AND t.type = 'EXPENSE'
                    AND t.dateMillis >= :from AND t.dateMillis < :to), 0) AS spentMinor
        FROM budgets b JOIN categories c ON c.id = b.categoryId
        WHERE b.yearMonth = :yearMonth ORDER BY c.name COLLATE NOCASE"""
    )
    fun observeForMonth(yearMonth: Int, from: Long, to: Long): Flow<List<BudgetWithSpent>>

    @Query("SELECT * FROM budgets WHERE categoryId = :categoryId AND yearMonth = :yearMonth")
    suspend fun getFor(categoryId: Long, yearMonth: Int): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth")
    suspend fun getForMonth(yearMonth: Int): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun getById(id: Long): BudgetEntity?

    @Query("SELECT DISTINCT yearMonth FROM budgets ORDER BY yearMonth DESC")
    fun observeMonths(): Flow<List<Int>>

    @Insert
    suspend fun insert(b: BudgetEntity): Long

    @Update
    suspend fun update(b: BudgetEntity)

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface GoalDao {
    @Query(
        """SELECT g.*, COALESCE((SELECT SUM(c.amountMinor) FROM savings_contributions c WHERE c.goalId = g.id), 0) AS savedMinor,
          a.name AS linkedAccountName
        FROM savings_goals g LEFT JOIN accounts a ON a.id = g.linkedAccountId
        WHERE g.archived = :archived ORDER BY g.createdAt DESC"""
    )
    fun observeGoals(archived: Boolean): Flow<List<GoalWithSaved>>

    @Query(
        """SELECT g.*, COALESCE((SELECT SUM(c.amountMinor) FROM savings_contributions c WHERE c.goalId = g.id), 0) AS savedMinor,
          a.name AS linkedAccountName
        FROM savings_goals g LEFT JOIN accounts a ON a.id = g.linkedAccountId WHERE g.id = :id"""
    )
    fun observeGoal(id: Long): Flow<GoalWithSaved?>

    @Query("SELECT * FROM savings_goals WHERE id = :id")
    suspend fun getById(id: Long): SavingsGoalEntity?

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM savings_contributions WHERE goalId = :goalId")
    suspend fun savedFor(goalId: Long): Long

    @Query(
        """SELECT COALESCE(SUM(c.amountMinor), 0) FROM savings_contributions c JOIN savings_goals g ON g.id = c.goalId
        WHERE g.linkedAccountId = :accountId AND g.archived = 0 AND g.id != :excludeGoalId"""
    )
    suspend fun earmarkedForAccount(accountId: Long, excludeGoalId: Long): Long

    @Insert
    suspend fun insert(g: SavingsGoalEntity): Long

    @Update
    suspend fun update(g: SavingsGoalEntity)

    @Query("DELETE FROM savings_goals WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert
    suspend fun insertContribution(c: SavingsContributionEntity): Long

    @Query("DELETE FROM savings_contributions WHERE id = :id")
    suspend fun deleteContribution(id: Long)

    @Query("SELECT * FROM savings_contributions WHERE id = :id")
    suspend fun getContribution(id: Long): SavingsContributionEntity?

    @Query("SELECT * FROM savings_contributions WHERE goalId = :goalId ORDER BY dateMillis DESC, id DESC")
    fun observeContributions(goalId: Long): Flow<List<SavingsContributionEntity>>
}

@Dao
interface ReminderDao {
    /** Returns -1 when this reminder was already recorded. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(r: NotificationReminderEntity): Long

    @Query("DELETE FROM notification_reminders WHERE refType = :refType AND refId = :refId")
    suspend fun deleteFor(refType: String, refId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM notification_reminders WHERE refType = :refType AND refId = :refId AND dueKey = :dueKey)")
    suspend fun exists(refType: String, refId: Long, dueKey: Long): Boolean
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): SettingsEntity?

    @Upsert
    suspend fun upsert(s: SettingsEntity)
}

/** Bulk access used by encrypted backup / restore and data reset. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM accounts") suspend fun accounts(): List<AccountEntity>
    @Query("SELECT * FROM balance_adjustments") suspend fun adjustments(): List<BalanceAdjustmentEntity>
    @Query("SELECT * FROM categories") suspend fun categories(): List<CategoryEntity>
    @Query("SELECT * FROM people") suspend fun people(): List<PersonEntity>
    @Query("SELECT * FROM scheduled_payments") suspend fun schedules(): List<ScheduledPaymentEntity>
    @Query("SELECT * FROM loan_ledger") suspend fun loans(): List<LoanEntity>
    @Query("SELECT * FROM loan_repayments") suspend fun repayments(): List<LoanRepaymentEntity>
    @Query("SELECT * FROM transactions") suspend fun transactions(): List<TransactionEntity>
    @Query("SELECT * FROM scheduled_payment_occurrences") suspend fun occurrences(): List<ScheduledPaymentOccurrenceEntity>
    @Query("SELECT * FROM budgets") suspend fun budgets(): List<BudgetEntity>
    @Query("SELECT * FROM savings_goals") suspend fun goals(): List<SavingsGoalEntity>
    @Query("SELECT * FROM savings_contributions") suspend fun contributions(): List<SavingsContributionEntity>
    @Query("SELECT * FROM notification_reminders") suspend fun reminders(): List<NotificationReminderEntity>
    @Query("SELECT * FROM settings") suspend fun settings(): List<SettingsEntity>

    @Insert suspend fun insertAccounts(l: List<AccountEntity>)
    @Insert suspend fun insertAdjustments(l: List<BalanceAdjustmentEntity>)
    @Insert suspend fun insertCategories(l: List<CategoryEntity>)
    @Insert suspend fun insertPeople(l: List<PersonEntity>)
    @Insert suspend fun insertSchedules(l: List<ScheduledPaymentEntity>)
    @Insert suspend fun insertLoans(l: List<LoanEntity>)
    @Insert suspend fun insertRepayments(l: List<LoanRepaymentEntity>)
    @Insert suspend fun insertTransactions(l: List<TransactionEntity>)
    @Insert suspend fun insertOccurrences(l: List<ScheduledPaymentOccurrenceEntity>)
    @Insert suspend fun insertBudgets(l: List<BudgetEntity>)
    @Insert suspend fun insertGoals(l: List<SavingsGoalEntity>)
    @Insert suspend fun insertContributions(l: List<SavingsContributionEntity>)
    @Insert suspend fun insertReminders(l: List<NotificationReminderEntity>)
    @Insert suspend fun insertSettings(l: List<SettingsEntity>)

    // Children first so foreign keys are never violated.
    @Query("DELETE FROM notification_reminders") suspend fun clearReminders()
    @Query("DELETE FROM savings_contributions") suspend fun clearContributions()
    @Query("DELETE FROM savings_goals") suspend fun clearGoals()
    @Query("DELETE FROM budgets") suspend fun clearBudgets()
    @Query("DELETE FROM scheduled_payment_occurrences") suspend fun clearOccurrences()
    @Query("DELETE FROM transactions") suspend fun clearTransactions()
    @Query("DELETE FROM loan_repayments") suspend fun clearRepayments()
    @Query("DELETE FROM loan_ledger") suspend fun clearLoans()
    @Query("DELETE FROM scheduled_payments") suspend fun clearSchedules()
    @Query("DELETE FROM people") suspend fun clearPeople()
    @Query("DELETE FROM balance_adjustments") suspend fun clearAdjustments()
    @Query("DELETE FROM accounts") suspend fun clearAccounts()
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("DELETE FROM settings") suspend fun clearSettings()
}
