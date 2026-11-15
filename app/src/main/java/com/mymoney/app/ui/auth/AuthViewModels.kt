package com.mymoney.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.app.backup.BackupManager
import com.mymoney.app.data.repo.SettingsRepository
import com.mymoney.app.security.AppLockManager
import com.mymoney.app.security.PinManager
import com.mymoney.app.security.PinResult
import com.mymoney.app.security.SecurityState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep { INTRO, LOGIN, PROFILE, CREATE_PIN, CONFIRM_PIN, BIOMETRIC, RECOVERY }

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.INTRO,
    val name: String = "",
    val currency: String = "INR",
    val pinLength: Int = 4,
    val pin: String = "",
    val confirm: String = "",
    val error: String? = null,
    val recoveryCode: String? = null,
    val saving: Boolean = false,
    /** Email of the Google account chosen on the login step, or null for offline use. */
    val googleEmail: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val pins: PinManager,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _s = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _s.asStateFlow()

    fun next(step: OnboardingStep) = _s.update { it.copy(step = step, error = null) }
    fun setName(v: String) = _s.update { it.copy(name = v) }
    fun onGoogleSignedIn(name: String?, email: String?) =
        _s.update { it.copy(name = it.name.ifBlank { name?.trim()?.take(40) ?: "" }, googleEmail = email, step = OnboardingStep.PROFILE, error = null) }
    fun setCurrency(v: String) = _s.update { it.copy(currency = v) }
    fun setPinLength(n: Int) = _s.update { it.copy(pinLength = n, pin = "", error = null) }

    fun saveProfile() {
        val s = _s.value
        if (s.name.isBlank()) { _s.update { it.copy(error = "Please enter your name") }; return }
        viewModelScope.launch {
            settings.update { it.copy(profileName = s.name.trim(), currency = s.currency) }
            next(OnboardingStep.CREATE_PIN)
        }
    }

    fun digit(c: Char) {
        val s = _s.value
        when (s.step) {
            OnboardingStep.CREATE_PIN -> if (s.pin.length < s.pinLength) {
                val p = s.pin + c
                _s.update { it.copy(pin = p, error = null) }
                if (p.length == s.pinLength) {
                    if (PinManager.isWeakPin(p)) _s.update { it.copy(pin = "", error = "That PIN is too easy to guess. Try another.") }
                    else viewModelScope.launch { delay(150); _s.update { it.copy(step = OnboardingStep.CONFIRM_PIN, confirm = "") } }
                }
            }
            OnboardingStep.CONFIRM_PIN -> if (s.confirm.length < s.pinLength) {
                val p = s.confirm + c
                _s.update { it.copy(confirm = p, error = null) }
                if (p.length == s.pinLength) {
                    if (p != s.pin) _s.update { it.copy(step = OnboardingStep.CREATE_PIN, pin = "", confirm = "", error = "PINs didn't match. Please try again.") }
                    else viewModelScope.launch {
                        _s.update { it.copy(saving = true) }
                        pins.setPin(p)
                        val code = pins.generateRecoveryCode()
                        _s.update { it.copy(saving = false, recoveryCode = code, step = OnboardingStep.BIOMETRIC) }
                    }
                }
            }
            else -> Unit
        }
    }

    fun backspace() = _s.update {
        when (it.step) {
            OnboardingStep.CREATE_PIN -> it.copy(pin = it.pin.dropLast(1))
            OnboardingStep.CONFIRM_PIN -> it.copy(confirm = it.confirm.dropLast(1))
            else -> it
        }
    }

    fun setBiometric(enabled: Boolean) = viewModelScope.launch { pins.setBiometric(enabled); next(OnboardingStep.RECOVERY) }

    fun finish(onDone: () -> Unit) = viewModelScope.launch { pins.completeOnboarding(); onDone() }
}

data class LockState(
    val entered: String = "",
    val error: String? = null,
    val lockedUntil: Long = 0,
    val showForgot: Boolean = false,
)

@HiltViewModel
class LockViewModel @Inject constructor(
    private val pins: PinManager,
    private val lock: AppLockManager,
    private val backup: BackupManager,
    profiles: com.mymoney.app.data.repo.ProfileRepository,
) : ViewModel() {
    val profile = profiles.profile.stateIn(viewModelScope, SharingStarted.Eagerly, com.mymoney.app.data.db.UserProfileEntity())
    val security: StateFlow<SecurityState> = pins.state.stateIn(viewModelScope, SharingStarted.Eagerly, SecurityState())
    private val _s = MutableStateFlow(LockState())
    val state: StateFlow<LockState> = _s.asStateFlow()

    init { viewModelScope.launch { val until = pins.current().lockoutUntil; if (until > System.currentTimeMillis()) _s.update { it.copy(lockedUntil = until) } } }

    fun digit(c: Char) {
        val s = _s.value
        if (s.lockedUntil > System.currentTimeMillis()) return
        val len = security.value.pinLength
        if (s.entered.length >= len) return
        val p = s.entered + c
        _s.update { it.copy(entered = p, error = null) }
        if (p.length == len) viewModelScope.launch {
            when (val r = pins.verify(p)) {
                PinResult.Success -> { _s.value = LockState(); lock.unlock() }
                is PinResult.Wrong -> _s.update { it.copy(entered = "", error = "Incorrect PIN. ${r.attemptsLeft} attempt(s) left.") }
                is PinResult.LockedOut -> _s.update { it.copy(entered = "", lockedUntil = r.untilMillis, error = null) }
            }
        }
    }

    fun backspace() = _s.update { it.copy(entered = it.entered.dropLast(1)) }
    fun clearLockout() = _s.update { it.copy(lockedUntil = 0) }

    fun biometricSuccess() = viewModelScope.launch { pins.onBiometricSuccess(); _s.value = LockState(); lock.unlock() }
    fun showForgot(show: Boolean) = _s.update { it.copy(showForgot = show) }

    /** Returns an error message, or null on success (app is then unlocked). */
    suspend fun resetWithCode(code: String, newPin: String, confirm: String): String? {
        if (!PinManager.isValidPin(newPin)) return "PIN must be 4 or 6 digits"
        if (newPin != confirm) return "PINs don't match"
        if (PinManager.isWeakPin(newPin)) return "That PIN is too easy to guess"
        return when (val r = pins.resetWithRecoveryCode(code, newPin)) {
            PinResult.Success -> { _s.value = LockState(); lock.unlock(); null }
            is PinResult.Wrong -> "Recovery code is incorrect. ${r.attemptsLeft} attempt(s) left."
            is PinResult.LockedOut -> { _s.update { it.copy(lockedUntil = r.untilMillis) }; "Too many attempts. Try again later." }
        }
    }

    /** Biometric reset path: user proved identity with an enrolled biometric. */
    suspend fun resetWithBiometric(newPin: String, confirm: String): String? {
        if (!PinManager.isValidPin(newPin)) return "PIN must be 4 or 6 digits"
        if (newPin != confirm) return "PINs don't match"
        if (PinManager.isWeakPin(newPin)) return "That PIN is too easy to guess"
        pins.setPin(newPin); _s.value = LockState(); lock.unlock(); return null
    }

    /** Last resort: erases all local data and returns to onboarding. */
    fun eraseEverything() = viewModelScope.launch { backup.resetAll(); pins.clearAll(); _s.value = LockState() }
}
