package com.mymoney.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoney.app.ui.account.AccountViewModel
import com.mymoney.app.ui.account.LegalLinks
import com.mymoney.app.ui.theme.FinFoxTheme

/**
 * Welcome / login page. Google sign-in is optional; the app is fully usable offline. Either way the
 * next steps secure the app with a PIN and (optionally) fingerprint, which also unlock it every time.
 */
@Composable
fun LoginStep(onGoogle: (name: String?, email: String?) -> Unit, onOffline: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val signingIn by vm.signingIn.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember { Biometrics.findActivity(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp)).background(FinFoxTheme.colors.headerGradient)
                .padding(top = 48.dp, bottom = 36.dp, start = 24.dp, end = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppLogo(124.dp)
                Spacer(Modifier.height(18.dp))
                Text("Welcome to FinFox", color = FinFoxTheme.colors.onGradient, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("Your personal finance tracker — private, secure and offline-first.", color = FinFoxTheme.colors.onGradient.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GoogleButton(loading = signingIn, enabled = s.configured && activity != null) {
                activity?.let { a -> vm.signIn(a) { info -> onGoogle(info.displayName, info.email) } }
            }
            if (!s.configured) Text("Google Sign-In isn't configured in this build. You can continue offline and sign in later from Settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onOffline, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Filled.CloudOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Continue Offline")
            }
            Spacer(Modifier.height(4.dp))
            Feature(Icons.Filled.Lock, "PIN protection", "Next, you'll create a 4 or 6 digit PIN to open the app.")
            Feature(Icons.Filled.Fingerprint, "Fingerprint unlock", "Use your fingerprint or face instead of the PIN, if your phone supports it.")
            Feature(Icons.Filled.Shield, "Your data stays yours", "Records never leave this device unless you back up to your own Google Drive, encrypted.")
            LegalLinks(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun GoogleButton(loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick, enabled = enabled && !loading, modifier = Modifier.fillMaxWidth().height(52.dp), shape = com.mymoney.app.ui.theme.FinFoxShapes.button,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else {
            // Official Google "G" mark (Google sign-in branding guidelines).
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.mymoney.app.R.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Continue with Google", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
