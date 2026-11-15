package com.mymoney.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mymoney.app.MainActivity
import com.mymoney.app.R
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.NotificationCategory
import com.mymoney.app.data.db.NotificationHistoryEntity
import com.mymoney.app.data.db.NotificationPreferencesEntity
import com.mymoney.app.data.db.SettingsEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

enum class NotificationAction { VIEW, MARK_PAID, REMIND_LATER, DISMISS, RECONNECT }

data class NotificationSpec(
    val systemId: Int,
    val category: NotificationCategory,
    val title: String,
    val text: String,
    /** Lock-screen safe versions without amounts. */
    val publicTitle: String,
    val publicText: String,
    val route: String?,
    val refType: String? = null,
    val refId: Long? = null,
    val actions: List<NotificationAction> = listOf(NotificationAction.VIEW, NotificationAction.DISMISS),
)

/** Pure policy rules, unit tested. */
object NotificationPolicy {
    fun enabled(settings: SettingsEntity, prefs: NotificationPreferencesEntity, category: NotificationCategory): Boolean {
        if (!settings.notificationsEnabled) return false
        return when (category) {
            NotificationCategory.PAYMENT -> prefs.paymentReminders
            NotificationCategory.LOAN -> prefs.loanReminders
            NotificationCategory.BUDGET -> settings.budgetAlertsEnabled
            NotificationCategory.GOAL -> prefs.savingsAlerts
            NotificationCategory.BACKUP -> prefs.backupAlerts
        }
    }

    /** Quiet hours may wrap past midnight, e.g. 22:00 – 07:00. */
    fun inQuietHours(prefs: NotificationPreferencesEntity, now: LocalTime): Boolean {
        if (!prefs.quietHoursEnabled) return false
        val m = now.hour * 60 + now.minute
        val s = prefs.quietStartMinute; val e = prefs.quietEndMinute
        return when {
            s == e -> false
            s < e -> m in s until e
            else -> m >= s || m < e
        }
    }
}

/**
 * Single exit point for every notification: applies preferences, records the in-app history, honours
 * quiet hours (posts silently instead of dropping) and lock-screen privacy, then posts to the system.
 */
@Singleton
class NotificationDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
) {
    companion object {
        /** FinFox Primary Purple accent for notification icons. */
        private val BRAND_COLOR = com.mymoney.app.ui.theme.FinFoxPalette.PrimaryPurple.toArgb()
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_BUDGET = "budget"
        const val CHANNEL_GOALS = "goals"
        const val CHANNEL_BACKUP = "backup"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_HISTORY_ID = "history_id"
        const val EXTRA_SYSTEM_ID = "system_id"
        /** The target screen changes financial records: lock the app so the user must authenticate. */
        const val EXTRA_REQUIRE_AUTH = "require_auth"

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "EMI, SIP, bills, card dues and lending/borrowing due dates" },
                    NotificationChannel(CHANNEL_BUDGET, context.getString(R.string.channel_budget), NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "Alerts at 80%, 90% and 100% of a budget, and when it's exceeded" },
                    NotificationChannel(CHANNEL_GOALS, context.getString(R.string.channel_goals), NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Savings goal milestones and target dates" },
                    NotificationChannel(CHANNEL_BACKUP, context.getString(R.string.channel_backup), NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Google Drive backup and restore results" },
                )
            )
        }

        fun channelFor(c: NotificationCategory) = when (c) {
            NotificationCategory.PAYMENT, NotificationCategory.LOAN -> CHANNEL_REMINDERS
            NotificationCategory.BUDGET -> CHANNEL_BUDGET
            NotificationCategory.GOAL -> CHANNEL_GOALS
            NotificationCategory.BACKUP -> CHANNEL_BACKUP
        }

        fun canPostToSystem(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /** Returns true when the notification was accepted (recorded and, if permitted, shown). */
    suspend fun post(spec: NotificationSpec, now: LocalTime = LocalTime.now()): Boolean {
        val settings = db.settingsDao().get() ?: SettingsEntity()
        val prefs = db.notificationDao().getPrefs() ?: NotificationPreferencesEntity()
        if (!NotificationPolicy.enabled(settings, prefs, spec.category)) return false
        val historyId = db.notificationDao().insert(
            NotificationHistoryEntity(
                category = spec.category, title = spec.title, body = spec.text, route = spec.route, refType = spec.refType,
                refId = spec.refId, systemId = spec.systemId, createdAt = Dates.nowMillis(),
            )
        )
        if (!canPostToSystem(context)) return true
        val quiet = NotificationPolicy.inQuietHours(prefs, now)
        val n = build(spec, historyId, prefs.hideAmountsOnLockScreen, quiet)
        try { NotificationManagerCompat.from(context).notify(spec.systemId, n) } catch (_: SecurityException) { }
        return true
    }

    private fun activityIntent(route: String?, historyId: Long, systemId: Int, requireAuth: Boolean, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // A unique action keeps PendingIntents for different targets from overwriting each other's extras.
            action = "com.mymoney.app.OPEN.$requestCode"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            route?.let { putExtra(EXTRA_ROUTE, it) }
            putExtra(EXTRA_HISTORY_ID, historyId)
            putExtra(EXTRA_SYSTEM_ID, systemId)
            putExtra(EXTRA_REQUIRE_AUTH, requireAuth)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun build(spec: NotificationSpec, historyId: Long, hideAmounts: Boolean, quiet: Boolean): Notification {
        val channel = channelFor(spec.category)
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND_COLOR)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.text))
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis()).setShowWhen(true)
            .setCategory(if (spec.category == NotificationCategory.BACKUP) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(activityIntent(spec.route, historyId, spec.systemId, false, spec.systemId))
            .setDeleteIntent(NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_SWIPED, spec, historyId))
        if (quiet) b.setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW)
        if (hideAmounts) {
            b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            b.setPublicVersion(
                NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification).setColor(BRAND_COLOR)
                    .setContentTitle(spec.publicTitle).setContentText(spec.publicText).build()
            )
        }
        // Android shows at most three buttons; tapping the notification itself always opens the record.
        if (NotificationAction.VIEW in spec.actions && spec.route != null && spec.actions.count { it != NotificationAction.VIEW } < 3) {
            b.addAction(0, "View details", activityIntent(spec.route, historyId, spec.systemId, false, spec.systemId + 70_000_000))
        }
        spec.actions.forEach { a ->
            when (a) {
                NotificationAction.VIEW -> Unit // the content intent already opens the record
                NotificationAction.MARK_PAID -> b.addAction(
                    0, "Mark as paid",
                    activityIntent(spec.route?.let { "$it?pay=true" }, historyId, spec.systemId, requireAuth = true, requestCode = spec.systemId + 50_000_000),
                )
                NotificationAction.RECONNECT -> b.addAction(0, "Reconnect", activityIntent(spec.route, historyId, spec.systemId, false, spec.systemId + 60_000_000))
                NotificationAction.REMIND_LATER -> b.addAction(0, "Remind later", NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_SNOOZE, spec, historyId))
                NotificationAction.DISMISS -> b.addAction(0, "Dismiss", NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_DISMISS, spec, historyId))
            }
        }
        return b.build()
    }

    fun cancel(systemId: Int) = NotificationManagerCompat.from(context).cancel(systemId)
}
