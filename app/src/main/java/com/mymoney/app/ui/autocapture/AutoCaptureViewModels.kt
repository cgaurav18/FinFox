package com.mymoney.app.ui.autocapture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.app.autocapture.CaptureRepository
import com.mymoney.app.autocapture.SmsInboxImporter
import com.mymoney.app.data.db.AccountWithBalance
import com.mymoney.app.data.db.AutoCapturePreferencesEntity
import com.mymoney.app.data.db.CapturedMessageEntity
import com.mymoney.app.data.db.CategoryEntity
import com.mymoney.app.data.repo.AccountRepository
import com.mymoney.app.data.repo.CategoryRepository
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.MessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutoCaptureViewModel @Inject constructor(
    private val capture: CaptureRepository,
    private val importer: SmsInboxImporter,
    accounts: AccountRepository,
    categories: CategoryRepository,
    bus: MessageBus,
) : BaseViewModel(bus) {
    val prefs: StateFlow<AutoCapturePreferencesEntity?> = capture.observePrefs().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pending: StateFlow<List<CapturedMessageEntity>> = capture.observePending().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recent: StateFlow<List<CapturedMessageEntity>> = capture.observeRecent(40).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accounts: StateFlow<List<AccountWithBalance>> = accounts.observeAccounts(false).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val categories: StateFlow<List<CategoryEntity>> = categories.observe(null).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun update(transform: (AutoCapturePreferencesEntity) -> AutoCapturePreferencesEntity) {
        viewModelScope.launch { capture.savePrefs(transform(capture.prefs())) }
    }

    fun approve(c: CapturedMessageEntity, accountId: Long?, categoryId: Long?, merchant: String) {
        if (accountId == null) { bus.show("Choose an account"); return }
        if (categoryId == null) { bus.show("Choose a category"); return }
        launchSafe(success = "Added to transactions") { capture.approve(c.id, accountId, categoryId, merchant.trim().ifBlank { null }) }
    }

    fun ignore(c: CapturedMessageEntity) = launchSafe { capture.ignore(c.id) }

    fun approveAll() = launchSafe {
        val n = capture.approveAll()
        if (n == 0) bus.show("Nothing could be added automatically. Pick an account and category for each entry.")
        else bus.success("Added $n ${if (n == 1) "entry" else "entries"}")
    }

    fun importInbox(days: Int) = launchSafe {
        // Importing is an explicit request to use auto-capture.
        capture.prefs().let { if (!it.enabled) capture.savePrefs(it.copy(enabled = true)) }
        val r = importer.import(days)
        bus.show(if (r.found == 0) "No new payments found in ${r.scanned} messages" else "Found ${r.found} payments to review")
    }

    fun clearHistory() = launchSafe(success = "History cleared") { capture.clearHistory() }
}

/** Badge for the home screen: how many captured payments wait for review. */
@HiltViewModel
class CaptureBadgeViewModel @Inject constructor(capture: CaptureRepository) : ViewModel() {
    val pending: StateFlow<Int> = capture.observePendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val prefs: StateFlow<AutoCapturePreferencesEntity?> = capture.observePrefs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
