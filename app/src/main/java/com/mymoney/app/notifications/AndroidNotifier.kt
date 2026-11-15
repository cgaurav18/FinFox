package com.mymoney.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mymoney.app.MainActivity
import com.mymoney.app.R
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidNotifier @Inject constructor(@ApplicationContext private val context: Context) : Notifier {
    companion object {
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_BUDGET = "budget"
        const val EXTRA_ROUTE = "route"

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Upcoming and overdue scheduled payments and loan due dates" })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_BUDGET, context.getString(R.string.channel_budget), NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Alerts at 80%, 90% and 100% of a budget" })
        }
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun post(id: Int, channel: String, title: String, text: String, route: String) {
        if (!canNotify()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ROUTE, route)
        }
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF5B21B6.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (_: SecurityException) { }
    }

    override fun budgetAlert(budgetId: Long, categoryName: String, percent: Int, spentMinor: Long, limitMinor: Long, currency: String) {
        val title = if (percent >= 100) "$categoryName budget exceeded" else "$categoryName budget at $percent%"
        post((300_000 + budgetId).toInt(), CHANNEL_BUDGET, title, "Spent ${Money.format(spentMinor, currency)} of ${Money.format(limitMinor, currency)} this month.", "budgets")
    }

    override fun scheduleReminder(scheduleId: Long, title: String, amountMinor: Long, due: LocalDate, overdue: Boolean, currency: String) {
        val head = if (overdue) "Overdue: $title" else "$title ${Dates.dueLabel(due).replaceFirstChar { it.lowercase() }}"
        post((100_000 + scheduleId).toInt(), CHANNEL_REMINDERS, head, "${Money.format(amountMinor, currency)} due on ${Dates.formatDay(due)}. Tap to mark it as paid.", "schedule/$scheduleId")
    }

    override fun loanReminder(loanId: Long, person: String, outstandingMinor: Long, gave: Boolean, due: LocalDate, currency: String) {
        val head = if (gave) "$person owes you ${Money.format(outstandingMinor, currency)}" else "You owe $person ${Money.format(outstandingMinor, currency)}"
        post((200_000 + loanId).toInt(), CHANNEL_REMINDERS, head, "${Dates.dueLabel(due)} (${Dates.formatDay(due)}).", "loan/$loanId")
    }
}
