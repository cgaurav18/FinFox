package com.mymoney.app.ui.auth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mymoney.app.R
import com.mymoney.app.ui.theme.FinFoxPalette
import com.mymoney.app.ui.theme.FinFoxTheme

/** The FinFox fox mascot (transparent artwork, works on light, dark and gradient backgrounds). */
@Composable
fun AppLogo(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.finfox_logo), contentDescription = "FinFox logo", modifier = modifier.size(size))
}

@Composable
fun SplashContent() {
    val reduced = FinFoxTheme.reducedMotion
    val scale = remember { Animatable(if (reduced) 1f else 0.6f) }
    val fade = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        fade.animateTo(1f, tween(350))
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    // Gentle idle "breathing" of the mascot while the app loads (disabled with reduced motion).
    val breathe by rememberInfiniteTransition(label = "splash").animateFloat(
        1f, if (reduced) 1f else 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breathe",
    )
    Box(
        Modifier.fillMaxSize().background(com.mymoney.app.ui.theme.BrandBrush),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.alpha(fade.value)) {
            AppLogo(168.dp, Modifier.scale(scale.value * breathe))
            Spacer(Modifier.height(20.dp))
            Text("FinFox", color = FinFoxTheme.colors.onGradient, style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(6.dp))
            Text("Track • Manage • Save", color = FinFoxTheme.colors.onGradient.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
