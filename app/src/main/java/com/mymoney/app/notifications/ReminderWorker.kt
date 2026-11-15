package com.mymoney.app.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.data.db.NotificationReminderEntity
import com.mymoney.app.data.db.ReminderRefType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Checks scheduled payments and loans and posts each due reminder once (deduplicated through
 * notification_reminders). It only reminds: money is never moved automatically.
 */
class ReminderEngine(private val db: AppDatabase, private val notifier: Notifier) {
    suspend fun run(today: LocalDate = Dates.today(), now: LocalTime = LocalTime.now()): Int {
        val settings = db.settingsDao().get()
        if (settings?.notificationsEnabled == false) return 0
        if (now.hour < (settings?.reminderHour ?: 9)) return 0
        val currency = settings?.currency ?: "INR"
        var posted = 0
        val rd = db.reminderDao()
        db.scheduleDao().getActive().forEach { s ->
            val due = LocalDate.ofEpochDay(s.nextDueEpochDay)
            if (s.endEpochDay != null && s.nextDueEpochDay > s.endEpochDay) return@forEach
            val overdue = due.isBefore(today)
            if (!today.isBefore(due.minusDays(s.reminderDaysBefore.toLong()))) {
                // Distinct keys for the "upcoming" and "overdue" notification of the same installment.
                val key = if (overdue) s.nextDueEpochDay + 10_000_000 else s.nextDueEpochDay
                if (rd.insertIgnore(NotificationReminderEntity(refType = ReminderRefType.SCHEDULE, refId = s.id, dueKey = key, notifiedAt = Dates.nowMillis())) != -1L) {
                    notifier.scheduleReminder(s.id, s.title, s.amountMinor, due, overdue, currency); posted++
                }
            }
        }
        db.loanDao().getOpenLoansWithReminders().forEach { l ->
            val due = LocalDate.ofEpochDay(l.loan.dueEpochDay ?: return@forEach)
            if (!today.isBefore(due.minusDays(1))) {
                val key = if (due.isBefore(today)) due.toEpochDay() + 10_000_000 else due.toEpochDay()
                if (rd.insertIgnore(NotificationReminderEntity(refType = ReminderRefType.LOAN, refId = l.loan.id, dueKey = key, notifiedAt = Dates.nowMillis())) != -1L) {
                    notifier.loanReminder(l.loan.id, l.personName, l.outstandingMinor, l.loan.direction == LoanDirection.GAVE, due, currency); posted++
                }
            }
        }
        return posted
    }
}

@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        ReminderEngine(db, notifier).run(); Result.success()
    } catch (e: Exception) { Result.retry() }

    companion object {
        private const val PERIODIC = "my_money_reminders"
        private const val NOW = "my_money_reminders_now"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ReminderWorker>(2, TimeUnit.HOURS).build())
            wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ReminderWorker>().build())
        }
    }
}
