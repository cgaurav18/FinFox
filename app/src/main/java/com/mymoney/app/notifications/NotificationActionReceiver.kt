package com.mymoney.app.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.NotificationPreferencesEntity
import com.mymoney.app.data.db.ReminderStateEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles "Remind later" and "Dismiss" without opening the app. Neither ever changes financial
 * records — dismissing a payment reminder does NOT mark it as paid.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var db: AppDatabase
    @Inject lateinit var dispatcher: NotificationDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        val systemId = intent.getIntExtra(EXTRA_SYSTEM_ID, 0)
        val refType = intent.getStringExtra(EXTRA_REF_TYPE)
        val refId = intent.getLongExtra(EXTRA_REF_ID, -1)
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_SNOOZE -> {
                        dispatcher.cancel(systemId)
                        db.notificationDao().markReadBySystemId(systemId)
                        if (refType != null && refId >= 0) snooze(context, db, refType, refId)
                    }
                    ACTION_DISMISS -> { dispatcher.cancel(systemId); db.notificationDao().markReadBySystemId(systemId) }
                    ACTION_SWIPED -> Unit // swiping away keeps the entry unread in the notification center
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.mymoney.app.action.SNOOZE"
        const val ACTION_DISMISS = "com.mymoney.app.action.DISMISS"
        const val ACTION_SWIPED = "com.mymoney.app.action.SWIPED"
        private const val EXTRA_SYSTEM_ID = "system_id"
        private const val EXTRA_REF_TYPE = "ref_type"
        private const val EXTRA_REF_ID = "ref_id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun intent(context: Context, action: String, spec: NotificationSpec, historyId: Long): PendingIntent {
            val i = Intent(context, NotificationActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_SYSTEM_ID, spec.systemId)
                putExtra(EXTRA_REF_TYPE, spec.refType)
                putExtra(EXTRA_REF_ID, spec.refId ?: -1L)
                putExtra("history_id", historyId)
            }
            val code = action.hashCode() * 31 + spec.systemId
            return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** Records the snooze and schedules a deferrable re-check when it expires. */
        suspend fun snooze(context: Context, db: AppDatabase, refType: String, refId: Long) {
            val hours = (db.notificationDao().getPrefs() ?: NotificationPreferencesEntity()).snoozeHours.coerceIn(1, 48)
            val until = Dates.nowMillis() + hours * 3_600_000L
            val old = db.notificationDao().getState(refType, refId)
            db.notificationDao().upsertState(ReminderStateEntity(refType, refId, until, (old?.snoozeCount ?: 0) + 1, Dates.nowMillis()))
            ReminderWorker.scheduleCheckAt(context, until, "snooze_${refType}_$refId")
        }
    }
}
