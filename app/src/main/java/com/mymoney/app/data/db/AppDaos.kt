package com.mymoney.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun observe(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun get(): UserProfileEntity?

    @Upsert
    suspend fun upsert(p: UserProfileEntity)
}

@Dao
interface CloudBackupDao {
    @Query("SELECT * FROM backup_preferences WHERE id = 1")
    fun observePrefs(): Flow<BackupPreferencesEntity?>

    @Query("SELECT * FROM backup_preferences WHERE id = 1")
    suspend fun getPrefs(): BackupPreferencesEntity?

    @Upsert
    suspend fun upsertPrefs(p: BackupPreferencesEntity)

    @Insert
    suspend fun insertMetadata(m: BackupMetadataEntity): Long

    @Query("SELECT * FROM backup_metadata ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeHistory(limit: Int = 100): Flow<List<BackupMetadataEntity>>

    @Query("SELECT * FROM backup_metadata WHERE status = 'SUCCESS' ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestSuccess(): BackupMetadataEntity?

    @Query("DELETE FROM backup_metadata WHERE driveFileId = :fileId")
    suspend fun deleteForFile(fileId: String)

    /** Keeps the local attempt log bounded. */
    @Query("DELETE FROM backup_metadata WHERE id NOT IN (SELECT id FROM backup_metadata ORDER BY createdAt DESC, id DESC LIMIT :keep)")
    suspend fun trimHistory(keep: Int = 200)
}

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(n: NotificationHistoryEntity): Long

    @Query("SELECT * FROM notification_history ORDER BY createdAt DESC, id DESC LIMIT 500")
    fun observeAll(): Flow<List<NotificationHistoryEntity>>

    @Query("SELECT COUNT(*) FROM notification_history WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM notification_history WHERE id = :id")
    suspend fun get(id: Long): NotificationHistoryEntity?

    @Query("UPDATE notification_history SET read = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("UPDATE notification_history SET read = 1 WHERE systemId = :systemId")
    suspend fun markReadBySystemId(systemId: Int)

    @Query("UPDATE notification_history SET read = 1 WHERE read = 0")
    suspend fun markAllRead()

    @Query("DELETE FROM notification_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM notification_history")
    suspend fun deleteAll()

    @Query("DELETE FROM notification_history WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT * FROM notification_preferences WHERE id = 1")
    fun observePrefs(): Flow<NotificationPreferencesEntity?>

    @Query("SELECT * FROM notification_preferences WHERE id = 1")
    suspend fun getPrefs(): NotificationPreferencesEntity?

    @Upsert
    suspend fun upsertPrefs(p: NotificationPreferencesEntity)

    @Query("SELECT * FROM reminder_state WHERE refType = :refType AND refId = :refId")
    suspend fun getState(refType: String, refId: Long): ReminderStateEntity?

    @Upsert
    suspend fun upsertState(s: ReminderStateEntity)

    @Query("DELETE FROM reminder_state WHERE refType = :refType AND refId = :refId")
    suspend fun clearState(refType: String, refId: Long)

    @Query(
        """SELECT g.*, COALESCE((SELECT SUM(c.amountMinor) FROM savings_contributions c WHERE c.goalId = g.id), 0) AS savedMinor,
          a.name AS linkedAccountName
        FROM savings_goals g LEFT JOIN accounts a ON a.id = g.linkedAccountId WHERE g.archived = 0"""
    )
    suspend fun activeGoals(): List<GoalWithSaved>

    @Query(
        """SELECT g.*, COALESCE((SELECT SUM(c.amountMinor) FROM savings_contributions c WHERE c.goalId = g.id), 0) AS savedMinor,
          a.name AS linkedAccountName
        FROM savings_goals g LEFT JOIN accounts a ON a.id = g.linkedAccountId WHERE g.id = :id"""
    )
    suspend fun goal(id: Long): GoalWithSaved?
}

data class CategorySpend(val categoryId: Long, val name: String, val colorArgb: Long, val amountMinor: Long)

data class UpcomingItem(
    val id: Long,
    val title: String,
    val category: ScheduleCategory,
    val amountMinor: Long,
    val nextDueEpochDay: Long,
)

data class LoanDueItem(
    val id: Long,
    val personName: String,
    val direction: LoanDirection,
    val dueEpochDay: Long,
    val outstandingMinor: Long,
)

/** Snapshot queries used by home screen widgets (suspend, no Flow: widgets render a snapshot). */
@Dao
interface WidgetDao {
    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM transactions WHERE type = :type AND dateMillis >= :from AND dateMillis < :to")
    suspend fun sum(type: String, from: Long, to: Long): Long

    @Query(
        """SELECT c.id AS categoryId, c.name AS name, c.colorArgb AS colorArgb, SUM(t.amountMinor) AS amountMinor
        FROM transactions t JOIN categories c ON c.id = t.categoryId
        WHERE t.type = 'EXPENSE' AND t.dateMillis >= :from AND t.dateMillis < :to
        GROUP BY c.id ORDER BY amountMinor DESC LIMIT :limit"""
    )
    suspend fun categorySpend(from: Long, to: Long, limit: Int): List<CategorySpend>

    @Query(
        """SELECT id, title, category, amountMinor, nextDueEpochDay FROM scheduled_payments
        WHERE status = 'ACTIVE' AND (endEpochDay IS NULL OR nextDueEpochDay <= endEpochDay)
        ORDER BY nextDueEpochDay, id LIMIT :limit"""
    )
    suspend fun upcoming(limit: Int): List<UpcomingItem>

    @Query(
        """SELECT COALESCE(SUM(l.amountMinor - COALESCE((SELECT SUM(r.amountMinor) FROM loan_repayments r WHERE r.loanId = l.id), 0)), 0)
        FROM loan_ledger l WHERE l.direction = :direction AND l.settled = 0"""
    )
    suspend fun loanOutstanding(direction: String): Long

    @Query(
        """SELECT l.id AS id, p.name AS personName, l.direction AS direction, l.dueEpochDay AS dueEpochDay,
          l.amountMinor - COALESCE((SELECT SUM(r.amountMinor) FROM loan_repayments r WHERE r.loanId = l.id), 0) AS outstandingMinor
        FROM loan_ledger l JOIN people p ON p.id = l.personId
        WHERE l.settled = 0 AND l.dueEpochDay IS NOT NULL ORDER BY l.dueEpochDay, l.id LIMIT :limit"""
    )
    suspend fun loansWithDue(limit: Int): List<LoanDueItem>

    @Query(
        """SELECT b.*, c.name AS categoryName, c.iconKey AS categoryIconKey, c.colorArgb AS categoryColor,
          COALESCE((SELECT SUM(t.amountMinor) FROM transactions t WHERE t.categoryId = b.categoryId AND t.type = 'EXPENSE'
                    AND t.dateMillis >= :from AND t.dateMillis < :to), 0) AS spentMinor
        FROM budgets b JOIN categories c ON c.id = b.categoryId
        WHERE b.yearMonth = :yearMonth ORDER BY spentMinor * 1.0 / b.limitMinor DESC"""
    )
    suspend fun budgets(yearMonth: Int, from: Long, to: Long): List<BudgetWithSpent>

    @Query("SELECT * FROM widget_preferences WHERE id = 1")
    suspend fun getPrefs(): WidgetPreferencesEntity?

    @Query("SELECT * FROM widget_preferences WHERE id = 1")
    fun observePrefs(): Flow<WidgetPreferencesEntity?>

    @Upsert
    suspend fun upsertPrefs(p: WidgetPreferencesEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPrefsIfMissing(p: WidgetPreferencesEntity)
}
