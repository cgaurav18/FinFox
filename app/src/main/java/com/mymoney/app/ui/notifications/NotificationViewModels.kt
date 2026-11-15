package com.mymoney.app.ui.notifications

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.NotificationCategory
import com.mymoney.app.data.db.NotificationHistoryEntity
import com.mymoney.app.data.db.NotificationPreferencesEntity
import com.mymoney.app.data.db.SettingsEntity
import com.mymoney.app.data.repo.SettingsRepository
import com.mymoney.app.notifications.NotificationDispatcher
import com.mymoney.app.notifications.ReminderWorker
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.MessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class NotificationFilter(val label: String) {
    ALL("All"), UNREAD("Unread"), PAYMENTS("Payments"), LOANS("Lending"), BUDGETS("Budgets"), GOALS("Savings"), BACKUP("Backup");

    fun matches(n: NotificationHistoryEntity) = when (this) {
        ALL -> true
        UNREAD -> !n.read
        PAYMENTS -> n.category == NotificationCategory.PAYMENT
        LOANS -> n.category == NotificationCategory.LOAN
        BUDGETS -> n.category == NotificationCategory.BUDGET
        GOALS -> n.category == NotificationCategory.GOAL
        BACKUP -> n.category == NotificationCategory.BACKUP
    }
}

data class NotificationCenterState(
    val loaded: Boolean = false,
    val filter: NotificationFilter = NotificationFilter.ALL,
    val items: List<NotificationHistoryEntity> = emptyList(),
    val unread: Int = 0,
)

@HiltViewModel
class NotificationCenterViewModel @Inject constructor(
    private val db: AppDatabase,
    private val dispatcher: NotificationDispatcher,
    bus: MessageBus,
) : BaseViewModel(bus) {
    private val dao = db.notificationDao()
    val filter = MutableStateFlow(NotificationFilter.ALL)

    val state: StateFlow<NotificationCenterState> = combine(dao.observeAll(), filter) { all, f ->
        NotificationCenterState(true, f, all.filter(f::matches), all.count { !it.read })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationCenterState())

    fun open(n: NotificationHistoryEntity) = launchSafe { dao.markRead(n.id); dispatcher.cancel(n.systemId) }
    fun markRead(n: NotificationHistoryEntity) = launchSafe { dao.markRead(n.id) }
    fun markAllRead() = launchSafe("All marked as read") { dao.markAllRead() }
    fun delete(n: NotificationHistoryEntity) = launchSafe { dao.delete(n.id); dispatcher.cancel(n.systemId) }
    fun clearAll() = launchSafe("Notification history cleared") { dao.deleteAll() }
}

/** Unread badge for the dashboard bell. */
@HiltViewModel
class UnreadCountViewModel @Inject constructor(db: AppDatabase) : androidx.lifecycle.ViewModel() {
    val unread: StateFlow<Int> = db.notificationDao().observeUnreadCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

data class NotificationPrefsState(val loaded: Boolean = false, val settings: SettingsEntity = SettingsEntity(), val prefs: NotificationPreferencesEntity = NotificationPreferencesEntity())

@HiltViewModel
class NotificationPreferencesViewModel @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
    bus: MessageBus,
) : BaseViewModel(bus) {
    private val dao = db.notificationDao()
    val state: StateFlow<NotificationPrefsState> = combine(settings.settings, dao.observePrefs().map { it ?: NotificationPreferencesEntity() }) { s, p ->
        NotificationPrefsState(true, s, p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationPrefsState())

    fun updatePrefs(t: (NotificationPreferencesEntity) -> NotificationPreferencesEntity) = launchSafe {
        dao.upsertPrefs(t(dao.getPrefs() ?: NotificationPreferencesEntity()))
        ReminderWorker.schedule(context) // re-evaluate with the new rules
    }

    fun updateSettings(t: (SettingsEntity) -> SettingsEntity) = launchSafe {
        settings.update(t)
        ReminderWorker.schedule(context)
    }

    fun canPostToSystem() = NotificationDispatcher.canPostToSystem(context)
}
