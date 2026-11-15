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
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.data.db.NotificationPreferencesEntity
import com.mymoney.app.data.db.NotificationReminderEntity
import com.mymoney.app.data.db.ReminderRefType
import com.mymoney.app.data.db.ScheduleCategory
import com.mymoney.app.data.db.ScheduleStatus
import com.mymoney.app.data.db.SettingsEntity
import com.mymoney.app.widgets.WidgetUpdater
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.concurrent.TimeUnit

/**
 * Checks scheduled payments, card bills, loans and goals and posts each due reminder once
 * (deduplicated through notification_reminders). It only reminds: money is never moved automatically.
 * "Remind later" snoozes are honoured through reminder_state.
 */
class ReminderEngine(private val db: AppDatabase, private val notifier: Notifier, private val clock: () -> Long = Dates::nowMillis) {

    /** Overdue reminders use a distinct key so the same installment can remind once before and once after the due date. */
    private fun key(due: LocalDate, today: LocalDate) = if (due.isBefore(today)) due.toEpochDay() + 10_000_000 else due.toEpochDay()

    /**
     * Posts the reminder unless it was already posted (dedupe) or is snoozed. An expired snooze re-posts
     * an already-sent reminder exactly once.
     */
    private suspend fun remind(type: ReminderRefType, refId: Long, dueKey: Long, post: suspend () -> Unit): Boolean {
        val nd = db.notificationDao()
        val state = nd.getState(type.name, refId)
        val now = clock()
        if (state?.snoozedUntil != null && state.snoozedUntil > now) return false
        val fresh = db.reminderDao().insertIgnore(NotificationReminderEntity(refType = type, refId = refId, dueKey = dueKey, notifiedAt = now)) != -1L
        val snoozeExpired = state?.snoozedUntil != null
        if (!fresh && !snoozeExpired) return false
        post()
        if (snoozeExpired) nd.clearState(type.name, refId)
        return true
    }

    suspend fun run(today: LocalDate = Dates.today(), now: LocalTime = LocalTime.now()): Int {
        val settings = db.settingsDao().get() ?: SettingsEntity()
        val prefs = db.notificationDao().getPrefs() ?: NotificationPreferencesEntity()
        // Housekeeping: keep the notification center to ~90 days.
        db.notificationDao().deleteOlderThan(clock() - 90L * 86_400_000)
        if (!settings.notificationsEnabled) return 0
        if (now.hour < settings.reminderHour) return 0
        // Reminders are deferrable: during quiet hours wait for the next run instead of disturbing the user.
        if (NotificationPolicy.inQuietHours(prefs, now)) return 0
        val currency = settings.currency
        var posted = 0

        if (prefs.paymentReminders) {
            val active = db.scheduleDao().getActive()
            active.forEach { s ->
                if (s.endEpochDay != null && s.nextDueEpochDay > s.endEpochDay) return@forEach
                val due = LocalDate.ofEpochDay(s.nextDueEpochDay)
                if (today.isBefore(due.minusDays(s.reminderDaysBefore.toLong()))) return@forEach
                if (remind(ReminderRefType.SCHEDULE, s.id, key(due, today)) {
                        notifier.scheduleReminder(s.id, s.title, s.amountMinor, due, due.isBefore(today), currency, s.category)
                    }) posted++
            }
            // Card bills from the card's due day, unless a "Credit card bill" schedule already covers that card.
            val coveredCards = active.filter { it.category == ScheduleCategory.CREDIT_CARD_BILL && it.status == ScheduleStatus.ACTIVE }.mapNotNull { it.toAccountId }.toSet()
            db.accountDao().getAllWithBalance().filter {
                it.account.type == AccountType.CREDIT_CARD && !it.account.archived && it.account.dueDay != null &&
                    it.outstandingMinor > 0 && it.account.id !in coveredCards
            }.forEach { c ->
                val due = nextDueDate(c.account.dueDay!!, today)
                if (today.isBefore(due.minusDays(prefs.defaultReminderDays.toLong()))) return@forEach
                if (remind(ReminderRefType.CARD, c.account.id, due.toEpochDay()) {
                        notifier.cardDueReminder(c.account.id, c.account.name, c.outstandingMinor, due, currency)
                    }) posted++
            }
        }

        if (prefs.loanReminders) {
            db.loanDao().getOpenLoansWithReminders().forEach { l ->
                val due = LocalDate.ofEpochDay(l.loan.dueEpochDay ?: return@forEach)
                if (today.isBefore(due.minusDays(prefs.defaultReminderDays.toLong()))) return@forEach
                if (remind(ReminderRefType.LOAN, l.loan.id, key(due, today)) {
                        notifier.loanReminder(l.loan.id, l.personName, l.outstandingMinor, l.loan.direction == LoanDirection.GAVE, due, currency)
                    }) posted++
            }
        }

        if (prefs.savingsAlerts) {
            db.notificationDao().activeGoals().forEach { g ->
                val target = g.goal.targetEpochDay?.let(LocalDate::ofEpochDay) ?: return@forEach
                if (g.savedMinor >= g.goal.targetMinor) return@forEach
                val days = target.toEpochDay() - today.toEpochDay()
                if (days !in 0..7) return@forEach
                // Negative key so it never collides with the milestone percent keys of the same goal.
                if (remind(ReminderRefType.GOAL, g.goal.id, -target.toEpochDay()) {
                        notifier.goalAlert(g.goal.id, g.goal.name, GoalAlert.TARGET_SOON, g.savedMinor, g.goal.targetMinor, target, currency)
                    }) posted++
            }
        }
        return posted
    }

    companion object {
        /** Next date (today or later) falling on [dueDay], clamped to short months. */
        fun nextDueDate(dueDay: Int, today: LocalDate): LocalDate {
            val ym = YearMonth.from(today)
            val thisMonth = ym.atDay(dueDay.coerceAtMost(ym.lengthOfMonth()))
            if (!thisMonth.isBefore(today)) return thisMonth
            val next = ym.plusMonths(1)
            return next.atDay(dueDay.coerceAtMost(next.lengthOfMonth()))
        }
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
        ReminderEngine(db, notifier).run()
        // The periodic run also rolls widgets over to a new day/month (overdue flags, month totals).
        WidgetUpdater.updateAll(applicationContext)
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        private const val PERIODIC = "my_money_reminders"
        private const val NOW = "my_money_reminders_now"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ReminderWorker>(2, TimeUnit.HOURS).build())
            wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ReminderWorker>().build())
        }

        /** A deferrable one-off check around [atMillis] (used for "Remind later"). Not exact by design. */
        fun scheduleCheckAt(context: Context, atMillis: Long, uniqueName: String) {
            val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0) + 60_000
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
            )
        }
    }
}
