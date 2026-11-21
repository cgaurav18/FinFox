package com.mymoney.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class, BalanceAdjustmentEntity::class, CategoryEntity::class, PersonEntity::class,
        ScheduledPaymentEntity::class, LoanEntity::class, LoanRepaymentEntity::class, TransactionEntity::class,
        ScheduledPaymentOccurrenceEntity::class, BudgetEntity::class, SavingsGoalEntity::class,
        SavingsContributionEntity::class, NotificationReminderEntity::class, SettingsEntity::class,
        // v2
        UserProfileEntity::class, BackupMetadataEntity::class, BackupPreferencesEntity::class,
        NotificationHistoryEntity::class, NotificationPreferencesEntity::class, WidgetPreferencesEntity::class,
        ReminderStateEntity::class,
        // v3
        CapturedMessageEntity::class, MerchantRuleEntity::class, UpiPaymentEntity::class, AutoCapturePreferencesEntity::class,
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
    abstract fun profileDao(): ProfileDao
    abstract fun cloudBackupDao(): CloudBackupDao
    abstract fun notificationDao(): NotificationDao
    abstract fun widgetDao(): WidgetDao
    abstract fun captureDao(): CaptureDao

    companion object {
        const val VERSION = 3
        const val NAME = "my_money.db"
    }
}

/**
 * Schema migrations. Every schema change must bump [AppDatabase.VERSION], export the new schema JSON
 * (app/schemas) and add an explicit Migration here — destructive fallback is intentionally NOT enabled,
 * so user financial data is never silently dropped.
 */
object Migrations {
    /**
     * v1 → v2: Google profile, cloud backup, notification center/preferences, widget preferences and
     * reminder snooze state. Purely additive: no existing financial row is touched.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `settings` ADD COLUMN `hideBalances` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE IF NOT EXISTS `user_profile` (`id` INTEGER NOT NULL, `googleUid` TEXT, `email` TEXT, `displayName` TEXT, `photoUrl` TEXT, `signedIn` INTEGER NOT NULL, `signedInAt` INTEGER, `driveConnected` INTEGER NOT NULL, `driveConnectedAt` INTEGER, `driveNeedsReauth` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `backup_metadata` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `driveFileId` TEXT, `fileName` TEXT NOT NULL, `accountEmail` TEXT, `createdAt` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, `sha256` TEXT, `trigger` TEXT NOT NULL, `status` TEXT NOT NULL, `error` TEXT, `accountCount` INTEGER NOT NULL, `transactionCount` INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_backup_metadata_createdAt` ON `backup_metadata` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_backup_metadata_driveFileId` ON `backup_metadata` (`driveFileId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `backup_preferences` (`id` INTEGER NOT NULL, `autoBackupEnabled` INTEGER NOT NULL, `frequency` TEXT NOT NULL, `wifiOnly` INTEGER NOT NULL, `keepCount` INTEGER NOT NULL, `lastSuccessAt` INTEGER, `lastSizeBytes` INTEGER, `lastAttemptAt` INTEGER, `lastError` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `notification_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `category` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `route` TEXT, `refType` TEXT, `refId` INTEGER, `systemId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `read` INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_history_createdAt` ON `notification_history` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_history_read` ON `notification_history` (`read`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_history_category` ON `notification_history` (`category`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `notification_preferences` (`id` INTEGER NOT NULL, `paymentReminders` INTEGER NOT NULL, `loanReminders` INTEGER NOT NULL, `savingsAlerts` INTEGER NOT NULL, `backupAlerts` INTEGER NOT NULL, `quietHoursEnabled` INTEGER NOT NULL, `quietStartMinute` INTEGER NOT NULL, `quietEndMinute` INTEGER NOT NULL, `defaultReminderDays` INTEGER NOT NULL, `snoozeHours` INTEGER NOT NULL, `hideAmountsOnLockScreen` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `widget_preferences` (`id` INTEGER NOT NULL, `privacyMode` INTEGER NOT NULL, `balanceHidden` INTEGER NOT NULL, `lastRefreshedAt` INTEGER, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `reminder_state` (`refType` TEXT NOT NULL, `refId` INTEGER NOT NULL, `snoozedUntil` INTEGER, `snoozeCount` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`refType`, `refId`))")
        }
    }

    /**
     * v2 → v3: automatic payment capture (bank SMS / UPI notifications) and Scan & Pay. Purely additive.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `captured_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, `sender` TEXT, `body` TEXT NOT NULL, `textHash` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL, `direction` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `accountLast4` TEXT, `isCard` INTEGER NOT NULL, `counterparty` TEXT, `vpa` TEXT, `refNo` TEXT, `kind` TEXT NOT NULL, `status` TEXT NOT NULL, `suggestedAccountId` INTEGER, `suggestedCategoryId` INTEGER, `transactionId` INTEGER, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_captured_messages_receivedAt` ON `captured_messages` (`receivedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_captured_messages_status` ON `captured_messages` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_captured_messages_textHash` ON `captured_messages` (`textHash`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_captured_messages_refNo` ON `captured_messages` (`refNo`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_captured_messages_transactionId` ON `captured_messages` (`transactionId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `merchant_rules` (`merchantKey` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`merchantKey`), FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_merchant_rules_categoryId` ON `merchant_rules` (`categoryId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `upi_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `vpa` TEXT NOT NULL, `payeeName` TEXT, `amountMinor` INTEGER NOT NULL, `categoryId` INTEGER, `accountId` INTEGER, `note` TEXT, `txnRef` TEXT NOT NULL, `appPackage` TEXT, `status` TEXT NOT NULL, `transactionId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_upi_payments_status` ON `upi_payments` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_upi_payments_createdAt` ON `upi_payments` (`createdAt`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `auto_capture_preferences` (`id` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `readSms` INTEGER NOT NULL, `readNotifications` INTEGER NOT NULL, `autoAdd` INTEGER NOT NULL, `notifyOnCapture` INTEGER NOT NULL, `defaultAccountId` INTEGER, PRIMARY KEY(`id`))")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
