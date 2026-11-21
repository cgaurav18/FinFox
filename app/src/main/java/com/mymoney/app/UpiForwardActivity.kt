package com.mymoney.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.mymoney.app.data.db.ThemeMode
import com.mymoney.app.data.repo.AppearanceRepository
import com.mymoney.app.ui.pay.ForwardPayScreen
import com.mymoney.app.ui.pay.PayViewModel
import com.mymoney.app.ui.theme.FinFoxTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * FinFox listed as a UPI app: shopping apps that offer "pay with any UPI app" (Blinkit, Zepto, …) can send their
 * upi://pay link here. FinFox records the payment under a category and forwards the link to the user's real
 * UPI app, then returns that app's result to the caller. FinFox never handles the UPI PIN or moves money itself.
 *
 * Runs in the caller's task (standard launch mode) so the result reaches it.
 */
@AndroidEntryPoint
class UpiForwardActivity : ComponentActivity() {
    @Inject lateinit var appearance: AppearanceRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Until the UPI app answers, the caller sees a cancelled payment (as if the user backed out).
        if (savedInstanceState == null) setResult(RESULT_CANCELED)
        val link = intent?.dataString
        val requestedBy = callingPackage?.takeIf { it != packageName }?.let { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }
        setContent {
            val mode by appearance.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            FinFoxTheme(mode) {
                val vm: PayViewModel = hiltViewModel()
                if (savedInstanceState == null && !vm.isForwarded && !vm.startForwarded(link)) { finish(); return@FinFoxTheme }
                ForwardPayScreen(vm, requestedBy, onResult = { code, data -> setResult(code, data) }, onClose = { finish() })
            }
        }
    }
}
