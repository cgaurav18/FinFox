package com.mymoney.app.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLockManagerTest {
    private var now = 1_000_000L
    private val lock = AppLockManager { now }

    @Test fun startsLockedAndUnlocks() {
        assertThat(lock.locked.value).isTrue()
        lock.unlock()
        assertThat(lock.locked.value).isFalse()
    }

    @Test fun locksAfterBackgroundTimeout() {
        lock.unlock()
        lock.onBackground(); now += 10_000; lock.onForeground(30, pinSet = true)
        assertThat(lock.locked.value).isFalse()
        lock.onBackground(); now += 31_000; lock.onForeground(30, pinSet = true)
        assertThat(lock.locked.value).isTrue()
    }

    @Test fun immediateLockAndSuppressionForPickers() {
        lock.unlock()
        lock.suppressNextBackgroundLock = true
        lock.onBackground(); now += 60_000; lock.onForeground(0, pinSet = true)
        assertThat(lock.locked.value).isFalse()
        lock.onBackground(); now += 1; lock.onForeground(0, pinSet = true)
        assertThat(lock.locked.value).isTrue()
    }

    @Test fun inactivityLocks() {
        lock.unlock()
        now += 60_000; lock.checkInactivity(30, true)
        assertThat(lock.locked.value).isFalse()
        lock.onUserInteraction(); now += 121_000; lock.checkInactivity(30, true)
        assertThat(lock.locked.value).isTrue()
    }
}
