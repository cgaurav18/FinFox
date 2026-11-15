package com.mymoney.app.ui.auth

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymoney.app.R
import com.mymoney.app.ui.theme.Purple
import com.mymoney.app.ui.theme.PurpleDark
import com.mymoney.app.ui.theme.Violet

@Composable
fun AppLogo(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.5f)).background(Brush.linearGradient(listOf(Violet, Purple))),
        contentAlignment = Alignment.Center,
    ) { Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "My Money logo", modifier = Modifier.size(size * 1.25f)) }
}

@Composable
fun SplashContent() {
    val scale = remember { Animatable(0.8f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, tween(600)) }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Violet, Purple, PurpleDark))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.scale(scale.value)) {
            Box(Modifier.size(112.dp).clip(RoundedCornerShape(32.dp)).background(Color.White.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(140.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("My Money", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Track • Manage • Save", color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
        }
    }
}
