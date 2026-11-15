package com.mymoney.app

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.app.NotificationManagerCompat
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.notifications.NotificationDispatcher
import kotlinx.coroutines.runBlocking
import com.mymoney.app.security.AppLockManager
import com.mymoney.app.security.PinManager
import com.mymoney.app.ui.navigation.AppRoot
import com.mymoney.app.ui.navigation.PendingRoute
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FragmentActivity is required by BiometricPrompt. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var lock: AppLockManager
    @Inject lateinit var pins: PinManager
    @Inject lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { AppRoot() }
        // Foreground inactivity auto-lock.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(5_000)
                    val s = pins.current()
                    lock.checkInactivity(s.autoLockSeconds, s.pinSet)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val historyId = intent.getLongExtra(NotificationDispatcher.EXTRA_HISTORY_ID, -1)
        if (historyId > 0) {
            lifecycleScope.launch { db.notificationDao().markRead(historyId) }
            NotificationManagerCompat.from(this).cancel(intent.getIntExtra(NotificationDispatcher.EXTRA_SYSTEM_ID, 0))
        }
        // Actions that change financial records (e.g. "Mark as paid") always require the PIN/biometric first —
        // also when such a route arrives from outside the app, since this activity is exported.
        val route = intent.getStringExtra(NotificationDispatcher.EXTRA_ROUTE)
        if (intent.getBooleanExtra(NotificationDispatcher.EXTRA_REQUIRE_AUTH, false) || route?.contains("pay=true") == true) {
            val pinSet = runBlocking { pins.current().pinSet }
            if (pinSet) lock.lock()
        }
        route?.let { PendingRoute.route.value = it }
        // Consume the extras so a configuration change doesn't replay the action.
        intent.removeExtra(NotificationDispatcher.EXTRA_ROUTE)
        intent.removeExtra(NotificationDispatcher.EXTRA_REQUIRE_AUTH)
        intent.removeExtra(NotificationDispatcher.EXTRA_HISTORY_ID)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        lock.onUserInteraction()
        return super.dispatchTouchEvent(ev)
    }
}
