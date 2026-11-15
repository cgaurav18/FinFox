package com.mymoney.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.app.data.db.ThemeMode
import com.mymoney.app.data.repo.SettingsRepository
import com.mymoney.app.security.AppLockManager
import com.mymoney.app.security.PinManager
import com.mymoney.app.ui.components.MessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RootPhase { SPLASH, ONBOARDING, MAIN }

data class RootState(val phase: RootPhase = RootPhase.SPLASH, val theme: ThemeMode = ThemeMode.SYSTEM, val currency: String = "INR")

/** Process-wide holder for a route requested by a notification tap. */
object PendingRoute {
    val route = MutableStateFlow<String?>(null)
}

@HiltViewModel
class RootViewModel @Inject constructor(
    pins: PinManager,
    settings: SettingsRepository,
    private val lock: AppLockManager,
    val bus: MessageBus,
) : ViewModel() {
    private val splashDone = MutableStateFlow(false)

    init { viewModelScope.launch { delay(900); splashDone.value = true } }

    val state: StateFlow<RootState> = combine(splashDone, pins.state, settings.settings) { done, sec, s ->
        RootState(
            phase = when { !done -> RootPhase.SPLASH; !sec.onboarded || !sec.pinSet -> RootPhase.ONBOARDING; else -> RootPhase.MAIN },
            theme = s.themeMode, currency = s.currency,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState())

    val locked: StateFlow<Boolean> = lock.locked
    val pendingRoute: StateFlow<String?> = PendingRoute.route.asStateFlow()

    fun consumePendingRoute() { PendingRoute.route.value = null }
    fun onboardingFinished() = lock.unlock()
}
