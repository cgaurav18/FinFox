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
import com.mymoney.app.notifications.AndroidNotifier
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
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
        intent?.getStringExtra(AndroidNotifier.EXTRA_ROUTE)?.let { PendingRoute.route.value = it }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        lock.onUserInteraction()
        return super.dispatchTouchEvent(ev)
    }
}
