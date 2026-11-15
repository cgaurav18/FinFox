package com.mymoney.app.ui.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object Biometrics {
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    fun findActivity(context: Context): FragmentActivity? {
        var c = context
        while (c is ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
        return null
    }

    fun prompt(context: Context, title: String, subtitle: String, onSuccess: () -> Unit, onError: (String) -> Unit = {}) {
        val activity = findActivity(context) ?: return onError("Biometrics unavailable")
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_CANCELED) onError(errString.toString())
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
                .setNegativeButtonText("Use PIN").setAllowedAuthenticators(BIOMETRIC_WEAK).build()
        )
    }
}
