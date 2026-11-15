package com.mymoney.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        AccountEntity::class, BalanceAdjustmentEntity::class, CategoryEntity::class, PersonEntity::class,
        ScheduledPaymentEntity::class, LoanEntity::class, LoanRepaymentEntity::class, TransactionEntity::class,
        ScheduledPaymentOccurrenceEntity::class, BudgetEntity::class, SavingsGoalEntity::class,
        SavingsContributionEntity::class, NotificationReminderEntity::class, SettingsEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun loanDao(): LoanDao
    abstract fun budgetDao(): BudgetDao
    abstract fun goalDao(): GoalDao
    abstract fun reminderDao(): ReminderDao
    abstract fun settingsDao(): SettingsDao
    abstract fun backupDao(): BackupDao

    companion object {
        const val VERSION = 1
        const val NAME = "my_money.db"
    }
}

/**
 * Schema migrations. Every schema change must bump [AppDatabase.VERSION], export the new schema JSON
 * (app/schemas) and add an explicit Migration here — destructive fallback is intentionally NOT enabled,
 * so user financial data is never silently dropped.
 *
 * Example for a future version 2:
 * val MIGRATION_1_2 = object : Migration(1, 2) {
 *     override fun migrate(db: SupportSQLiteDatabase) {
 *         db.execSQL("ALTER TABLE accounts ADD COLUMN ifsc TEXT")
 *     }
 * }
 */
object Migrations {
    val ALL: Array<Migration> = arrayOf()
}
