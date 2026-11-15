package com.mymoney.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mymoney.app.core.Money
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import com.mymoney.app.ui.theme.AmountStyle
import com.mymoney.app.ui.theme.FinFoxPalette
import com.mymoney.app.ui.theme.FinFoxShapes
import com.mymoney.app.ui.theme.FinFoxTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = actions,
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        // Responsive: on tablets/landscape keep content at a readable width, centred.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = MaxContentWidth).fillMaxSize()) { content(pad) }
        }
    }
}

/** Maximum width of screen content on large displays. */
val MaxContentWidth = 720.dp

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val elevation = CardDefaults.cardElevation(defaultElevation = if (FinFoxTheme.colors.isDark) 0.dp else 1.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = colors, elevation = elevation) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = colors, elevation = elevation) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 42.dp, contentDescription: String? = null) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.2f)).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = contentDescription, tint = color, modifier = Modifier.size(size * 0.52f)) }
}

/** Amount with sign-aware colouring. */
@Composable
fun MoneyText(
    amountMinor: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = Color.Unspecified,
    signed: Boolean = false,
    currency: String = LocalCurrency.current,
) {
    Text(Money.format(amountMinor, currency, signed = signed), modifier = modifier, style = style.merge(AmountStyle), color = color, maxLines = 1)
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** Purple pill tabs as in the design: selected tab is filled, others are flat. */
@Composable
fun <T> PillTabs(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, scrollable: Boolean = false) {
    val rowMod = if (scrollable) modifier.fillMaxWidth().horizontalScroll(rememberScrollState()) else modifier.fillMaxWidth()
    Row(
        rowMod.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val fin = FinFoxTheme.colors
        options.forEach { o ->
            val isSel = o == selected
            val tabMod = if (scrollable) Modifier else Modifier.weight(1f)
            Box(
                tabMod.clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) fin.primaryButton else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(o) }
                    .padding(horizontal = if (scrollable) 14.dp else 6.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    color = if (isSel) fin.onPrimaryButton else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Subtle press feedback for buttons (skipped when the user disabled animations). */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val reduced = FinFoxTheme.reducedMotion
    val s by animateFloatAsState(if (pressed && !reduced) 0.97f else 1f, tween(120), label = "press")
    return this.scale(s)
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, icon: ImageVector? = null) {
    val fin = FinFoxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick, enabled = enabled && !loading, modifier = modifier.fillMaxWidth().height(52.dp).pressScale(interaction),
        shape = FinFoxShapes.button, interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(containerColor = fin.primaryButton, contentColor = fin.onPrimaryButton),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = fin.onPrimaryButton)
        else {
            if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** Light-purple secondary button; [destructive] makes it a red button for irreversible actions. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, destructive: Boolean = false, enabled: Boolean = true) {
    val fin = FinFoxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val container = if (destructive) FinFoxPalette.Expense else fin.secondaryButton
    val content = if (destructive) Color.White else fin.onSecondaryButton
    Button(
        onClick = onClick, modifier = modifier.height(48.dp).pressScale(interaction), shape = FinFoxShapes.button, enabled = enabled,
        interactionSource = interaction, elevation = null,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val fin = FinFoxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick, modifier = modifier.height(48.dp).pressScale(interaction), shape = FinFoxShapes.button, enabled = enabled,
        interactionSource = interaction,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = fin.secondaryButton, contentColor = fin.onSecondaryButton),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, maxLines = 1, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction, shape = FinFoxShapes.button, modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FinFoxTheme.colors.primaryButton, contentColor = FinFoxTheme.colors.onPrimaryButton)) { Text(action) }
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val anim by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(if (FinFoxTheme.reducedMotion) 0 else 600), label = "progress")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(anim).height(height).clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor, modifier = Modifier.weight(1f))
    }
}

@Composable
fun StatTile(label: String, amountMinor: Long, color: Color, icon: ImageVector, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    AppCard(modifier = modifier, onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color, 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                MoneyText(amountMinor, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun ListRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val m = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Row(m.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, iconColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
