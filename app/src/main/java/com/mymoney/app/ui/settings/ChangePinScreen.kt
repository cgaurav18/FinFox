package com.mymoney.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.SecondaryButton
import com.mymoney.app.ui.components.SectionHeader

@Composable
fun ChangePinScreen(nav: NavController, vm: ChangePinViewModel = hiltViewModel()) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var recoveryPin by remember { mutableStateOf("") }
    val code by vm.recoveryCode.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.done.collect { nav.popBackStack() } }
    fun digits(v: String) = v.filter(Char::isDigit).take(6)
    AppScaffold(title = "Change PIN", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTextField(current, { current = digits(it) }, "Current PIN", keyboardType = KeyboardType.NumberPassword, maxLength = 6)
                    AppTextField(new, { new = digits(it) }, "New PIN (4 or 6 digits)", keyboardType = KeyboardType.NumberPassword, maxLength = 6)
                    AppTextField(confirm, { confirm = digits(it) }, "Confirm new PIN", keyboardType = KeyboardType.NumberPassword, maxLength = 6)
                    PrimaryButton("Change PIN", { vm.change(current, new, confirm) })
                }
            }
            SectionHeader("Recovery code")
            AppCard {
                Text("Lost your recovery code? Generate a new one. The old code stops working.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                AppTextField(recoveryPin, { recoveryPin = digits(it) }, "Current PIN", keyboardType = KeyboardType.NumberPassword, maxLength = 6)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Generate new recovery code", { vm.regenerateRecovery(recoveryPin); recoveryPin = "" })
            }
        }
    }
    code?.let { c ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("New recovery code") },
            text = {
                Column {
                    Text("Write this down and keep it safe. It's shown only once.")
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer {
                        Text(c, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { nav.popBackStack() }) { Text("I've saved it") } },
        )
    }
}
