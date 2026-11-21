package com.mymoney.app.ui.receive

import androidx.lifecycle.viewModelScope
import com.mymoney.app.autocapture.UpiUri
import com.mymoney.app.core.Money
import com.mymoney.app.data.repo.MyUpiId
import com.mymoney.app.data.repo.MyUpiRepository
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.MessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ReceiveState(
    val loading: Boolean = true,
    val ids: List<MyUpiId> = emptyList(),
    val selected: MyUpiId? = null,
    val amount: String = "",
    val note: String = "",
) {
    /** A typed amount, when it is a valid one. */
    val amountMinor: Long? get() = (Money.parse(amount) as? Money.ParseResult.Ok)?.minor?.takeIf { it > 0 }
    val qrPayload: String? get() = selected?.let { UpiUri.receiveUri(it.vpa, it.name, amountMinor, note) }
}

@HiltViewModel
class ReceiveViewModel @Inject constructor(private val repo: MyUpiRepository, bus: MessageBus) : BaseViewModel(bus) {
    private val pick = MutableStateFlow<String?>(null)
    val amount = MutableStateFlow("")
    val note = MutableStateFlow("")

    val state: StateFlow<ReceiveState> = combine(repo.ids, pick, amount, note) { ids, p, a, n ->
        ReceiveState(false, ids, ids.firstOrNull { it.vpa == p } ?: ids.firstOrNull(), a, n)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiveState())

    fun select(vpa: String) { pick.value = vpa }
    fun add(vpa: String, name: String, label: String, onDone: () -> Unit) = launchSafe("UPI ID saved") { repo.add(vpa, name, label); pick.value = vpa.trim().lowercase(); onDone() }
    fun remove(vpa: String) = launchSafe("UPI ID removed") { repo.remove(vpa); if (pick.value == vpa) pick.value = null }
    fun makeDefault(vpa: String) = launchSafe("Default UPI ID updated") { repo.makeDefault(vpa) }
}
