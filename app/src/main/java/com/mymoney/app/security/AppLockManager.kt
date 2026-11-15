package com.mymoney.app.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the UI must show the lock screen. The app locks when it returns from the background
 * after the configured delay, and after a period of inactivity while in the foreground.
 */
class AppLockManager(private val clock: () -> Long = System::currentTimeMillis) {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundAt: Long? = null
    @Volatile private var lastInteraction: Long = clock()

    /** Set while a system picker (SAF, biometric) is open so returning from it doesn't re-lock. */
    @Volatile var suppressNextBackgroundLock: Boolean = false

    fun unlock() { _locked.value = false; lastInteraction = clock(); backgroundAt = null }
    fun lock() { _locked.value = true }
    fun onUserInteraction() { lastInteraction = clock() }

    fun onBackground() { backgroundAt = clock() }

    fun onForeground(autoLockSeconds: Int, pinSet: Boolean) {
        val bg = backgroundAt ?: return
        backgroundAt = null
        if (!pinSet) return
        if (suppressNextBackgroundLock) { suppressNextBackgroundLock = false; return }
        if (clock() - bg >= autoLockSeconds * 1000L) lock()
    }

    /** Foreground inactivity check. "Immediately" still allows 60 s of idle time on screen. */
    fun checkInactivity(autoLockSeconds: Int, pinSet: Boolean) {
        if (!pinSet || _locked.value) return
        val idleLimit = maxOf(autoLockSeconds, 60) * 1000L * 2
        if (clock() - lastInteraction >= idleLimit) lock()
    }
}
