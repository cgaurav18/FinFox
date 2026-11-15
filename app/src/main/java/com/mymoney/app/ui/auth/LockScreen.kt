package com.mymoney.app.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.mymoney.app.ui.account.Avatar
import com.mymoney.app.ui.account.LegalLinks
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.PillTabs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LockScreen(vm: LockViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val sec by vm.security.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val bioAvailable = remember { Biometrics.available(context) }
    val canBio = sec.biometricEnabled && bioAvailable
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.lockedUntil) {
        while (s.lockedUntil > System.currentTimeMillis()) { now = System.currentTimeMillis(); delay(500) }
        if (s.lockedUntil != 0L) vm.clearLockout()
        now = System.currentTimeMillis()
    }
    val bio = { Biometrics.prompt(context, "Unlock FinFox", "Confirm it's you", onSuccess = vm::biometricSuccess) }
    LaunchedEffect(canBio) { if (canBio) bio() }
    BackHandler { Biometrics.findActivity(context)?.moveTaskToBack(true) }

    val lockedOut = s.lockedUntil > now
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .clickable(MutableInteractionSource(), null) {} // swallow touches meant for the app underneath
            .systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        AppLogo(76.dp)
        Spacer(Modifier.height(20.dp))
        Text("Welcome back" + (profile.displayName?.takeIf { profile.signedIn }?.let { ", ${it.substringBefore(' ')}" } ?: ""), style = MaterialTheme.typography.headlineSmall)
        Text(if (canBio) "Enter your PIN or use your fingerprint" else "Enter your PIN to continue", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (profile.signedIn) { Avatar(profile.photoUrl, profile.displayName, 22.dp); Spacer(Modifier.width(6.dp)) }
            Text(if (profile.signedIn) profile.email ?: "Google account" else "Offline mode", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(28.dp))
        PinDots(sec.pinLength, s.entered.length, s.error != null)
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                lockedOut -> "Too many attempts. Try again in ${((s.lockedUntil - now) / 1000) + 1}s"
                else -> s.error ?: " "
            },
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        PinPad(onDigit = vm::digit, onBackspace = vm::backspace, onBiometric = if (canBio) bio else null, enabled = !lockedOut)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { vm.showForgot(true) }) { Text("Forgot PIN?") }
        LegalLinks()
    }
    if (s.showForgot) ForgotPinDialog(vm, canBio) { vm.showForgot(false) }
}

@Composable
private fun ForgotPinDialog(vm: LockViewModel, canBio: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("Recovery code") }
    var bioVerified by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmErase by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset your PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (canBio) PillTabs(listOf("Recovery code", "Fingerprint"), mode, { it }, { mode = it; error = null })
                if (mode == "Recovery code") {
                    Text("Enter the recovery code you saved when setting up the app.", style = MaterialTheme.typography.bodySmall)
                    AppTextField(code, { code = it.uppercase() }, "Recovery code", placeholder = "XXXX-XXXX-XXXX-XXXX", capitalization = KeyboardCapitalization.Characters, maxLength = 24)
                } else if (!bioVerified) {
                    Text("Verify with your fingerprint, then choose a new PIN.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { Biometrics.prompt(context, "Verify identity", "Reset your PIN", onSuccess = { bioVerified = true }, onError = { error = it }) }) { Text("Verify fingerprint") }
                } else Text("Verified. Choose a new PIN.", color = MaterialTheme.colorScheme.primary)
                AppTextField(pin, { if (it.all(Char::isDigit)) pin = it }, "New PIN (4 or 6 digits)", keyboardType = KeyboardType.NumberPassword, maxLength = 6)
                AppTextField(confirm, { if (it.all(Char::isDigit)) confirm = it }, "Confirm new PIN", keyboardType = KeyboardType.NumberPassword, maxLength = 6)
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirmErase = true }) { Text("Can't recover? Erase all data", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = if (mode == "Recovery code") vm.resetWithCode(code, pin, confirm)
                    else if (!bioVerified) "Verify your fingerprint first" else vm.resetWithBiometric(pin, confirm)
                    if (error == null) onDismiss()
                }
            }) { Text("Reset PIN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmErase) ConfirmDialog(
        title = "Erase all data?",
        text = "This permanently deletes every account, transaction and setting on this device. Restore from an encrypted backup afterwards if you have one.",
        confirmLabel = "Erase everything",
        onConfirm = { vm.eraseEverything(); onDismiss() },
        onDismiss = { confirmErase = false },
    )
}
