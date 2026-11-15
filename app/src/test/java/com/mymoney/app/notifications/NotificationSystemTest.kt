package com.mymoney.app.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mymoney.app.core.Dates
import com.mymoney.app.data.Env
import com.mymoney.app.data.R
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.Frequency
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.data.db.NotificationCategory
import com.mymoney.app.data.db.NotificationPreferencesEntity
import com.mymoney.app.data.db.ReminderRefType
import com.mymoney.app.data.db.ScheduleCategory
import com.mymoney.app.data.repo.AccountInput
import com.mymoney.app.data.repo.GoalInput
import com.mymoney.app.data.repo.LoanInput
import com.mymoney.app.data.repo.ScheduleInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationSystemTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var e: Env
    private lateinit var dispatcher: NotificationDispatcher
    private lateinit var notifier: AndroidNotifier
    private val nm get() = shadowOf(ctx.getSystemService(NotificationManager::class.java))
    private val today = Dates.today()

    @Before fun setUp() = runTest {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationDispatcher.createChannels(ctx)
        e = Env.inMemory(); e.seed()
        dispatcher = NotificationDispatcher(ctx, e.db)
        notifier = AndroidNotifier(dispatcher)
    }
    @After fun tearDown() { e.db.close() }

    private suspend fun prefs(t: (NotificationPreferencesEntity) -> NotificationPreferencesEntity) =
        e.db.notificationDao().upsertPrefs(t(e.db.notificationDao().getPrefs() ?: NotificationPreferencesEntity()))

    @Test fun scheduleReminderHasPaymentActionsAndIsRecordedInHistory() = runTest {
        notifier.scheduleReminder(7, "Home loan EMI", 25_000 * R, today.plusDays(1), false, "INR", ScheduleCategory.EMI)
        val n = nm.allNotifications.single()
        assertThat(n.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("Home loan EMI due tomorrow")
        assertThat(n.actions.map { it.title.toString() }).containsExactly("Mark as paid", "Remind later", "Dismiss").inOrder()
        val h = e.db.notificationDao().observeAll().first().single()
        assertThat(h.category).isEqualTo(NotificationCategory.PAYMENT)
        assertThat(h.route).isEqualTo("schedule/7")
        assertThat(h.read).isFalse()
    }

    @Test fun lockScreenPrivacyHidesAmounts() = runTest {
        notifier.budgetAlert(1, "Food", 90, 4_500 * R, 5_000 * R, "INR")
        val n = nm.allNotifications.single()
        assertThat(n.extras.getString(Notification.EXTRA_TEXT)).contains("₹4,500")
        assertThat(n.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
        val pub = n.publicVersion!!
        assertThat(pub.extras.getString(Notification.EXTRA_TITLE) + pub.extras.getString(Notification.EXTRA_TEXT)).doesNotContain("₹")

        ctx.getSystemService(NotificationManager::class.java).cancelAll()
        prefs { it.copy(hideAmountsOnLockScreen = false) }
        notifier.budgetAlert(2, "Fuel", 80, 1, 2, "INR")
        assertThat(nm.allNotifications.single().publicVersion).isNull()
    }

    @Test fun categoryAndMasterSwitchesAreRespected() = runTest {
        prefs { it.copy(backupAlerts = false) }
        notifier.backupEvent(BackupEvent.SUCCESS, null)
        assertThat(nm.allNotifications).isEmpty()
        assertThat(e.db.notificationDao().observeAll().first()).isEmpty()
        e.db.settingsDao().upsert(e.db.settingsDao().get()!!.copy(notificationsEnabled = false))
        notifier.loanReminder(1, "Amit", 1, true, today, "INR")
        assertThat(nm.allNotifications).isEmpty()
    }

    @Test fun historyIsKeptEvenWhenSystemNotificationsAreBlocked() = runTest {
        shadowOf(ctx as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.backupEvent(BackupEvent.AUTH_EXPIRED, null)
        assertThat(nm.allNotifications).isEmpty()
        assertThat(e.db.notificationDao().observeAll().first().single().title).contains("authorization expired")
    }

    @Test fun quietHoursPolicyWrapsMidnightAndSilencesAlerts() = runTest {
        val p = NotificationPreferencesEntity(quietHoursEnabled = true, quietStartMinute = 22 * 60, quietEndMinute = 7 * 60)
        assertThat(NotificationPolicy.inQuietHours(p, LocalTime.of(23, 30))).isTrue()
        assertThat(NotificationPolicy.inQuietHours(p, LocalTime.of(6, 59))).isTrue()
        assertThat(NotificationPolicy.inQuietHours(p, LocalTime.of(7, 0))).isFalse()
        assertThat(NotificationPolicy.inQuietHours(p.copy(quietHoursEnabled = false), LocalTime.of(23, 0))).isFalse()
        prefs { p }
        dispatcher.post(NotificationSpec(5, NotificationCategory.BUDGET, "t", "x", "t", "x", null), LocalTime.of(23, 0))
        // Posted (never dropped) but silent: NotificationCompat.setSilent → summary-only alerting.
        val n = nm.allNotifications.single()
        assertThat(n.groupAlertBehavior).isEqualTo(Notification.GROUP_ALERT_SUMMARY)
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
        dispatcher.post(NotificationSpec(6, NotificationCategory.BUDGET, "t", "x", "t", "x", null), LocalTime.of(12, 0))
        assertThat(nm.allNotifications.single().groupAlertBehavior).isEqualTo(Notification.GROUP_ALERT_ALL)
    }

    @Test fun engineDefersDuringQuietHoursAndPostsAfterwards() = runTest {
        val bank = e.bank(opening = 1_000 * R)
        e.schedules.save(ScheduleInput(title = "Rent", category = ScheduleCategory.RENT, amountMinor = 100 * R, accountId = bank, start = today, frequency = Frequency.MONTHLY, nextDue = today, reminderDaysBefore = 0))
        prefs { it.copy(quietHoursEnabled = true, quietStartMinute = 9 * 60, quietEndMinute = 11 * 60) }
        val engine = ReminderEngine(e.db, e.notifier)
        assertThat(engine.run(today, LocalTime.of(10, 0))).isEqualTo(0)
        assertThat(engine.run(today, LocalTime.of(11, 30))).isEqualTo(1)
        assertThat(engine.run(today, LocalTime.of(15, 0))).isEqualTo(0) // duplicate prevention
    }

    @Test fun remindLaterSnoozesThenRepostsExactlyOnce() = runTest {
        val bank = e.bank(opening = 1_000 * R)
        val id = e.schedules.save(ScheduleInput(title = "Netflix", category = ScheduleCategory.SUBSCRIPTION, amountMinor = 649 * R, accountId = bank, start = today, frequency = Frequency.MONTHLY, nextDue = today.plusDays(1), reminderDaysBefore = 1))
        var clock = Dates.nowMillis()
        val engine = ReminderEngine(e.db, e.notifier) { clock }
        assertThat(engine.run(today, LocalTime.of(10, 0))).isEqualTo(1)
        NotificationActionReceiver.snooze(ctx, e.db, ReminderRefType.SCHEDULE.name, id)
        assertThat(e.db.notificationDao().getState("SCHEDULE", id)!!.snoozedUntil).isGreaterThan(clock)
        assertThat(engine.run(today, LocalTime.of(11, 0))).isEqualTo(0)
        clock += 4 * 3_600_000L // default snooze is 3 hours
        assertThat(engine.run(today, LocalTime.of(14, 0))).isEqualTo(1)
        assertThat(engine.run(today, LocalTime.of(15, 0))).isEqualTo(0)
        assertThat(e.notifier.schedules.map { it.first }).containsExactly(id, id)
        // Snoozing/dismissing never records a payment.
        assertThat(e.balance(bank)).isEqualTo(1_000 * R)
        assertThat(e.schedules.get(id)!!.nextDueEpochDay).isEqualTo(today.plusDays(1).toEpochDay())
    }

    @Test fun categoryTogglesStopEngineReminders() = runTest {
        val bank = e.bank(opening = 1_000 * R)
        e.schedules.save(ScheduleInput(title = "SIP", category = ScheduleCategory.SIP, amountMinor = 1, accountId = bank, start = today, frequency = Frequency.MONTHLY, nextDue = today, reminderDaysBefore = 0))
        e.loans.saveLoan(LoanInput(personName = "Ravi", direction = LoanDirection.RECEIVED, amountMinor = 500 * R, dateMillis = Dates.nowMillis(), due = today, accountId = bank, reminder = true))
        prefs { it.copy(paymentReminders = false, loanReminders = false) }
        assertThat(ReminderEngine(e.db, e.notifier).run(today, LocalTime.of(12, 0))).isEqualTo(0)
        prefs { it.copy(loanReminders = true) }
        assertThat(ReminderEngine(e.db, e.notifier).run(today, LocalTime.of(12, 0))).isEqualTo(1)
        assertThat(e.notifier.loans).hasSize(1)
    }

    @Test fun cardBillAndGoalTargetReminders() = runTest {
        val due = today.plusDays(1)
        val card = e.accounts.save(AccountInput(type = AccountType.CREDIT_CARD, name = "Amex", bankName = "American Express", openingMinor = 3_000 * R, creditLimitMinor = 50_000 * R, dueDay = due.dayOfMonth, colorArgb = 0, iconKey = "credit_card"))
        val g = e.goals.save(GoalInput(name = "Bike", targetMinor = 10_000 * R, targetDate = today.plusDays(5), colorArgb = 0))
        val engine = ReminderEngine(e.db, e.notifier)
        assertThat(engine.run(today, LocalTime.of(12, 0))).isEqualTo(2)
        assertThat(e.notifier.cards.single()).isEqualTo(card to ReminderEngine.nextDueDate(due.dayOfMonth, today))
        assertThat(e.notifier.goals.single()).isEqualTo(g to GoalAlert.TARGET_SOON)
        assertThat(engine.run(today, LocalTime.of(13, 0))).isEqualTo(0)
    }

    @Test fun goalMilestonesAndRepaymentsNotifyOnce() = runTest {
        val bank = e.bank(opening = 100_000 * R)
        val g = e.goals.save(GoalInput(name = "Laptop", targetMinor = 1_000 * R, colorArgb = 0))
        e.goals.contribute(g, 300 * R, Dates.nowMillis(), null)  // 30%
        e.goals.contribute(g, 300 * R, Dates.nowMillis(), null)  // 60%
        e.goals.contribute(g, 100 * R, Dates.nowMillis(), null)  // 70%: no new milestone
        e.goals.contribute(g, 400 * R, Dates.nowMillis(), null)  // 110%
        assertThat(e.notifier.goals.map { it.second }).containsExactly(GoalAlert.MILESTONE_25, GoalAlert.MILESTONE_50, GoalAlert.COMPLETED).inOrder()

        val loan = e.loans.saveLoan(LoanInput(personName = "Amit", direction = LoanDirection.GAVE, amountMinor = 1_000 * R, dateMillis = Dates.nowMillis(), accountId = bank))
        e.loans.addRepayment(loan, 400 * R, Dates.nowMillis(), bank, null)
        assertThat(e.notifier.repayments.single()).isEqualTo(loan to 600 * R)
    }

    @Test fun nextCardDueDateClampsShortMonths() {
        assertThat(ReminderEngine.nextDueDate(31, LocalDate.of(2026, 2, 10))).isEqualTo(LocalDate.of(2026, 2, 28))
        assertThat(ReminderEngine.nextDueDate(5, LocalDate.of(2026, 2, 10))).isEqualTo(LocalDate.of(2026, 3, 5))
        assertThat(ReminderEngine.nextDueDate(10, LocalDate.of(2026, 2, 10))).isEqualTo(LocalDate.of(2026, 2, 10))
    }
}
