package com.mymoney.app.ui.settings

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.mymoney.app.backup.BackupManager
import com.mymoney.app.backup.BackupPayload
import com.mymoney.app.backup.Exporter
import com.mymoney.app.core.DateRange
import com.mymoney.app.data.db.CategoryEntity
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.SettingsEntity
import com.mymoney.app.data.db.ThemeMode
import com.mymoney.app.data.repo.CategoryRepository
import com.mymoney.app.data.repo.SettingsRepository
import com.mymoney.app.data.repo.TransactionRepository
import com.mymoney.app.notifications.ReminderWorker
import com.mymoney.app.security.AppLockManager
import com.mymoney.app.security.PinManager
import com.mymoney.app.security.PinResult
import com.mymoney.app.security.SecurityState
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.MessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val pins: PinManager,
    private val backup: BackupManager,
    private val lock: AppLockManager,
    @ApplicationContext private val context: android.content.Context,
    bus: MessageBus,
) : BaseViewModel(bus) {
    val state: StateFlow<SettingsEntity?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val security: StateFlow<SecurityState> = pins.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityState())

    fun update(transform: (SettingsEntity) -> SettingsEntity) = launchSafe { settings.update(transform) }
    fun setName(n: String) = launchSafe("Profile updated") { if (n.isBlank()) throw com.mymoney.app.core.FinanceException("Name can't be empty"); settings.update { it.copy(profileName = n.trim().take(40)) } }
    fun setTheme(t: ThemeMode) = update { it.copy(themeMode = t) }
    fun setNotifications(on: Boolean) = launchSafe { settings.update { it.copy(notificationsEnabled = on) }; if (on) ReminderWorker.schedule(context) }
    fun setBiometric(on: Boolean) = launchSafe(if (on) "Biometric unlock enabled" else "Biometric unlock disabled") { pins.setBiometric(on) }
    fun setAutoLock(s: Int) = launchSafe { pins.setAutoLock(s) }
    fun lockNow() = lock.lock()
    fun eraseAll() = launchSafe { backup.resetAll(); pins.clearAll() }
}

@HiltViewModel
class CategoriesViewModel @Inject constructor(private val repo: CategoryRepository, bus: MessageBus) : BaseViewModel(bus) {
    val type = MutableStateFlow(CategoryType.EXPENSE)
    val all = repo.observe(null, true).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun save(id: Long?, name: String, icon: String, color: Long) = launchSafe(if (id == null) "Category added" else "Category updated") { repo.save(id, name, type.value, icon, color) }
    fun setArchived(c: CategoryEntity, a: Boolean) = launchSafe(if (a) "${c.name} archived" else "${c.name} restored") { repo.setArchived(c.id, a) }
    fun delete(c: CategoryEntity) = launchSafe("${c.name} deleted") { repo.delete(c.id) }
}

@HiltViewModel
class ChangePinViewModel @Inject constructor(private val pins: PinManager, bus: MessageBus) : BaseViewModel(bus) {
    private val _code = MutableStateFlow<String?>(null)
    val recoveryCode: StateFlow<String?> = _code.asStateFlow()
    val security = pins.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityState())

    private suspend fun checkCurrent(current: String): Boolean = when (val r = pins.verify(current)) {
        PinResult.Success -> true
        is PinResult.Wrong -> { bus.show("Current PIN is incorrect (${r.attemptsLeft} attempts left)"); false }
        is PinResult.LockedOut -> { bus.show("Too many attempts. Try again later."); false }
    }

    fun change(current: String, new: String, confirm: String) = viewModelScope.launch {
        when {
            !PinManager.isValidPin(new) -> bus.show("New PIN must be 4 or 6 digits")
            new != confirm -> bus.show("New PINs don't match")
            PinManager.isWeakPin(new) -> bus.show("That PIN is too easy to guess")
            checkCurrent(current) -> { pins.setPin(new); bus.show("PIN changed"); finish() }
        }
    }

    fun regenerateRecovery(current: String) = viewModelScope.launch {
        if (checkCurrent(current)) _code.value = pins.generateRecoveryCode()
    }
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backup: BackupManager,
    private val exporter: Exporter,
    private val txns: TransactionRepository,
    private val settings: SettingsRepository,
    private val lock: AppLockManager,
    @ApplicationContext private val context: android.content.Context,
    bus: MessageBus,
) : BaseViewModel(bus) {
    private val _pending = MutableStateFlow<BackupPayload?>(null)
    /** A decrypted backup waiting for the user's confirmation before it replaces current data. */
    val pending: StateFlow<BackupPayload?> = _pending.asStateFlow()

    fun beforeExternalActivity() { lock.suppressNextBackgroundLock = true }

    fun export(uri: Uri, password: String) = launchSafe {
        val stats = backup.exportTo(uri, password.toCharArray())
        bus.show("Encrypted backup saved (${stats.accounts} accounts, ${stats.transactions} transactions)")
    }

    fun read(uri: Uri, password: String) = launchSafe { _pending.value = backup.readFrom(uri, password.toCharArray()) }
    fun cancelRestore() { _pending.value = null }
    fun confirmRestore() = launchSafe("Backup restored") {
        val p = _pending.value ?: return@launchSafe
        backup.replaceAll(p)
        _pending.value = null
        ReminderWorker.schedule(context)
    }

    private val allTime = DateRange(LocalDate.of(1970, 1, 1), LocalDate.of(2200, 1, 1))
    fun exportCsv(uri: Uri) = launchSafe("CSV exported") { exporter.writeCsv(uri, txns.detailsInRange(allTime), settings.get().currency) }
    fun exportPdf(uri: Uri) = launchSafe("PDF exported") {
        val list = txns.detailsInRange(allTime)
        val first = list.minOfOrNull { it.txn.dateMillis }?.let(com.mymoney.app.core.Dates::toLocalDate) ?: com.mymoney.app.core.Dates.today()
        val s = settings.get()
        exporter.writePdf(uri, list, DateRange(first, com.mymoney.app.core.Dates.today()), s.currency, s.profileName)
    }
}
