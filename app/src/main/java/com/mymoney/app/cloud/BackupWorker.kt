package com.mymoney.app.cloud

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mymoney.app.data.db.BackupFrequency
import com.mymoney.app.data.db.BackupPreferencesEntity
import com.mymoney.app.data.db.BackupTrigger
import com.mymoney.app.notifications.BackupEvent
import com.mymoney.app.notifications.Notifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Uploads an encrypted backup. Runs for "Backup Now" (one-off) and automatic backups (periodic).
 * Uses Play services' cached Drive grant silently; if the user must act, it notifies and stops instead of
 * retrying. Network/server failures retry with exponential backoff. Timing is up to WorkManager — exact
 * execution times are not guaranteed.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val service: CloudBackupService,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)?.let { runCatching { BackupTrigger.valueOf(it) }.getOrNull() } ?: BackupTrigger.AUTO
        if (trigger == BackupTrigger.AUTO && !service.getPrefs().autoBackupEnabled) return Result.success()
        return try {
            val out = service.backupNow(trigger).metadata
            if (trigger == BackupTrigger.AUTO) {
                notifier.backupEvent(BackupEvent.SUCCESS, "${out.transactionCount} transactions backed up (${formatSize(out.sizeBytes)}).")
            }
            Result.success(workDataOf(KEY_SIZE to out.sizeBytes, KEY_TXNS to out.transactionCount))
        } catch (e: CloudBackupException.NeedsReauth) {
            notifier.backupEvent(BackupEvent.AUTH_EXPIRED, null)
            Result.failure(workDataOf(KEY_ERROR to e.message, KEY_NEEDS_REAUTH to true))
        } catch (e: CloudBackupException) {
            if (e.retryable && runAttemptCount < MAX_ATTEMPTS - 1) return Result.retry()
            notifier.backupEvent(BackupEvent.FAILED, e.message)
            Result.failure(workDataOf(KEY_ERROR to e.message))
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS - 1) return Result.retry()
            notifier.backupEvent(BackupEvent.FAILED, "Backup failed: ${e.message ?: e.javaClass.simpleName}")
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Backup failed")))
        }
    }

    companion object {
        const val UNIQUE_AUTO = "my_money_auto_backup"
        const val UNIQUE_MANUAL = "my_money_backup_now"
        const val KEY_TRIGGER = "trigger"
        const val KEY_ERROR = "error"
        const val KEY_NEEDS_REAUTH = "needs_reauth"
        const val KEY_SIZE = "size"
        const val KEY_TXNS = "txns"
        const val MAX_ATTEMPTS = 3

        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
        }

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** (Re)schedules or cancels automatic backups to match [prefs]. */
        fun syncSchedule(context: Context, prefs: BackupPreferencesEntity) {
            val wm = WorkManager.getInstance(context)
            if (!prefs.autoBackupEnabled || prefs.frequency == BackupFrequency.MANUAL) { wm.cancelUniqueWork(UNIQUE_AUTO); return }
            val days = prefs.frequency.days.toLong()
            val req = PeriodicWorkRequestBuilder<BackupWorker>(days, TimeUnit.DAYS, (days * 24 / 4).coerceAtLeast(2), TimeUnit.HOURS)
                .setConstraints(constraints(prefs.wifiOnly))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_TRIGGER to BackupTrigger.AUTO.name))
                .addTag(UNIQUE_AUTO)
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE_AUTO, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** "Backup Now": survives leaving the screen or the app being killed. */
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_TRIGGER to BackupTrigger.MANUAL.name))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_MANUAL, ExistingWorkPolicy.KEEP, req)
        }
    }
}
