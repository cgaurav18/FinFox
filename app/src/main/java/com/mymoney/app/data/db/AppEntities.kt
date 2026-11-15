package com.mymoney.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Version 2 tables: Google account profile, cloud backup bookkeeping, notification center and
 * preferences, widget preferences and reminder snooze state. None of these hold financial balances;
 * Room's ledger tables stay the single source of truth for money.
 */

/**
 * The Google identity currently linked to this device's data (single row, id = 1). Holds only
 * public profile data returned by Firebase Authentication — never passwords or OAuth tokens.
 */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    val googleUid: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val signedIn: Boolean = false,
    val signedInAt: Long? = null,
    /** Drive authorization is a separate, explicit grant (drive.appdata scope). */
    val driveConnected: Boolean = false,
    val driveConnectedAt: Long? = null,
    /** Set when a background backup found the Drive grant expired/revoked and needs the user. */
    val driveNeedsReauth: Boolean = false,
)

enum class BackupTrigger { MANUAL, AUTO }
enum class BackupStatus { SUCCESS, FAILED }
enum class BackupFrequency(val label: String, val days: Int) {
    MANUAL("Manual only", 0), DAILY("Daily", 1), WEEKLY("Weekly", 7), MONTHLY("Monthly", 30)
}

/** One row per backup attempt (successful uploads carry the Drive file id). */
@Entity(tableName = "backup_metadata", indices = [Index("createdAt"), Index("driveFileId")])
data class BackupMetadataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val driveFileId: String? = null,
    val fileName: String,
    val accountEmail: String?,
    val createdAt: Long,
    val sizeBytes: Long,
    val sha256: String?,
    val trigger: BackupTrigger,
    val status: BackupStatus,
    val error: String? = null,
    val accountCount: Int = 0,
    val transactionCount: Int = 0,
)

@Entity(tableName = "backup_preferences")
data class BackupPreferencesEntity(
    @PrimaryKey val id: Int = 1,
    val autoBackupEnabled: Boolean = false,
    val frequency: BackupFrequency = BackupFrequency.WEEKLY,
    val wifiOnly: Boolean = true,
    /** Number of backups kept in Drive; older ones are pruned after a successful upload. */
    val keepCount: Int = 10,
    val lastSuccessAt: Long? = null,
    val lastSizeBytes: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
)

enum class NotificationCategory(val label: String) {
    PAYMENT("Payments"), LOAN("Lending & borrowing"), BUDGET("Budgets"), GOAL("Savings goals"), BACKUP("Backup")
}

/** In-app notification center history (local only, never backed up). */
@Entity(tableName = "notification_history", indices = [Index("createdAt"), Index("read"), Index("category")])
data class NotificationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: NotificationCategory,
    val title: String,
    val body: String,
    /** In-app route to the related record, e.g. "schedule/12". */
    val route: String?,
    val refType: String?,
    val refId: Long?,
    /** System notification id, so the center can cancel the matching system notification. */
    val systemId: Int,
    val createdAt: Long,
    val read: Boolean = false,
)

@Serializable
@Entity(tableName = "notification_preferences")
data class NotificationPreferencesEntity(
    @PrimaryKey val id: Int = 1,
    val paymentReminders: Boolean = true,
    val loanReminders: Boolean = true,
    val savingsAlerts: Boolean = true,
    val backupAlerts: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    /** Minutes after midnight. Quiet hours may wrap past midnight (e.g. 22:00 – 07:00). */
    val quietStartMinute: Int = 22 * 60,
    val quietEndMinute: Int = 7 * 60,
    /** Default lead time for loan due dates and card bills (schedules carry their own). */
    val defaultReminderDays: Int = 1,
    val snoozeHours: Int = 3,
    /** Hide amounts from notifications on the lock screen. */
    val hideAmountsOnLockScreen: Boolean = true,
)

@Serializable
@Entity(tableName = "widget_preferences")
data class WidgetPreferencesEntity(
    @PrimaryKey val id: Int = 1,
    /** Masks every amount on every widget. */
    val privacyMode: Boolean = false,
    /** Eye toggle on the Total Balance widget. */
    val balanceHidden: Boolean = false,
    val lastRefreshedAt: Long? = null,
)

/** "Remind later" state per reminder target. */
@Entity(tableName = "reminder_state", primaryKeys = ["refType", "refId"])
data class ReminderStateEntity(
    val refType: String,
    val refId: Long,
    val snoozedUntil: Long? = null,
    val snoozeCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)
