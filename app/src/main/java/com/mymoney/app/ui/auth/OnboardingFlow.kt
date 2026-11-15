package com.mymoney.app.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoney.app.core.Money
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.PillTabs
import com.mymoney.app.ui.components.PrimaryButton
import kotlinx.coroutines.launch

@Composable
fun OnboardingFlow(onFinished: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()) {
        AnimatedContent(s.step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "onboarding") { step ->
            when (step) {
                OnboardingStep.INTRO -> IntroPager { vm.next(OnboardingStep.PROFILE) }
                OnboardingStep.PROFILE -> ProfileStep(s, vm)
                OnboardingStep.CREATE_PIN, OnboardingStep.CONFIRM_PIN -> PinStep(s, vm)
                OnboardingStep.BIOMETRIC -> BiometricStep(vm)
                OnboardingStep.RECOVERY -> RecoveryStep(s.recoveryCode ?: "") { vm.finish(onFinished) }
            }
        }
    }
}

private data class IntroPage(val icon: ImageVector, val title: String, val text: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IntroPager(onDone: () -> Unit) {
    val pages = listOf(
        IntroPage(Icons.Filled.AccountBalanceWallet, "All your money, one place", "Track bank accounts, credit cards and cash. Know exactly what you can spend."),
        IntroPage(Icons.Filled.EventRepeat, "Never miss a payment", "Schedule EMIs, SIPs and bills. Get reminders and mark them paid in one tap."),
        IntroPage(Icons.Filled.Insights, "See where it goes", "Budgets, savings goals and clear reports — all private and offline on your phone."),
    )
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDone) { Text("Skip") } }
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (i == 0) AppLogo(120.dp) else Box(Modifier.size(120.dp).clip(RoundedCornerShape(34.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(p.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(60.dp))
                }
                Spacer(Modifier.height(32.dp))
                Text(p.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(p.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(Modifier.padding(4.dp).size(width = if (pager.currentPage == i) 22.dp else 8.dp, height = 8.dp).clip(CircleShape)
                    .background(if (pager.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
            }
        }
        PrimaryButton(if (pager.currentPage == pages.lastIndex) "Get started" else "Next", onClick = {
            if (pager.currentPage == pages.lastIndex) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
        })
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ProfileStep(s: OnboardingState, vm: OnboardingViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(24.dp))
        StepHeader(Icons.Filled.Person, "Let's set you up",
            "Everything stays on this device. No account or internet needed.")
        Spacer(Modifier.height(8.dp))
        AppTextField(s.name, vm::setName, "Your name", error = s.error, capitalization = KeyboardCapitalization.Words, maxLength = 40)
        DropdownField("Currency", Money.supportedCurrencies, s.currency, { "$it (${Money.symbolFor(it).trim()})" }, vm::setCurrency)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Continue", onClick = vm::saveProfile)
    }
}

@Composable
private fun PinStep(s: OnboardingState, vm: OnboardingViewModel) {
    val confirming = s.step == OnboardingStep.CONFIRM_PIN
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        StepHeader(Icons.Filled.Shield, if (confirming) "Confirm your PIN" else "Create a PIN", if (confirming) "Enter the same PIN again" else "You'll use this PIN to open FinFox")
        Spacer(Modifier.height(20.dp))
        if (!confirming) {
            PillTabs(listOf(4, 6), s.pinLength, { "$it digits" }, vm::setPinLength, Modifier.width(220.dp))
            Spacer(Modifier.height(20.dp))
        }
        PinDots(s.pinLength, if (confirming) s.confirm.length else s.pin.length, s.error != null)
        Spacer(Modifier.height(12.dp))
        Text(s.error ?: " ", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        PinPad(onDigit = vm::digit, onBackspace = vm::backspace, enabled = !s.saving)
    }
}

@Composable
private fun BiometricStep(vm: OnboardingViewModel) {
    val context = LocalContext.current
    val available = remember { Biometrics.available(context) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        StepHeader(Icons.Filled.Fingerprint, "Unlock with fingerprint", if (available) "Use your fingerprint or face to open the app quickly. Your PIN still works." else "Biometric unlock isn't set up on this device. You can enable it later in Settings.")
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(32.dp))
        if (available) PrimaryButton("Enable biometric unlock", onClick = {
            Biometrics.prompt(context, "Enable biometric unlock", "Confirm it's you", onSuccess = { vm.setBiometric(true) }, onError = { error = it })
        })
        TextButton(onClick = { vm.setBiometric(false) }, modifier = Modifier.padding(top = 8.dp)) { Text(if (available) "Not now" else "Continue") }
    }
}

@Composable
private fun RecoveryStep(code: String, onDone: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        StepHeader(Icons.Filled.Key, "Save your recovery code", "If you forget your PIN, this code lets you reset it. It's shown only once — write it down and keep it safe.")
        Spacer(Modifier.height(24.dp))
        SelectionContainer {
            Text(
                code, fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 20.dp, vertical = 16.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy code") }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).toggleable(saved, role = Role.Checkbox) { saved = it }) {
            Checkbox(saved, null)
            Text("I've saved my recovery code somewhere safe", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Start using FinFox", onClick = onDone, enabled = saved)
    }
}
