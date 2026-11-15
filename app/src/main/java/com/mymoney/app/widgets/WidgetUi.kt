package com.mymoney.app.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.mymoney.app.MainActivity
import com.mymoney.app.R
import com.mymoney.app.notifications.NotificationDispatcher
import com.mymoney.app.ui.theme.DarkColors
import com.mymoney.app.ui.theme.DarkFinFoxColors
import com.mymoney.app.ui.theme.FinFoxPalette
import com.mymoney.app.ui.theme.LightFinFoxColors
import com.mymoney.app.ui.theme.LightColors

object WidgetSizes {
    val SMALL = DpSize(110.dp, 110.dp)
    val MEDIUM = DpSize(250.dp, 110.dp)
    val LARGE = DpSize(250.dp, 220.dp)
}

/** False while rendering in-app previews (no click handlers there). */
val LocalInteractive = staticCompositionLocalOf { true }

/** FinFox colours for widgets: same tokens as the app (status colours lightened for text on dark). */
object WidgetColors {
    val scheme = ColorProviders(light = LightColors, dark = DarkColors)
    val income = ColorProvider(day = LightFinFoxColors.income, night = DarkFinFoxColors.income)
    val expense = ColorProvider(day = LightFinFoxColors.expense, night = DarkFinFoxColors.expense)
    val transfer = ColorProvider(day = LightFinFoxColors.transfer, night = DarkFinFoxColors.transfer)
    val warning = ColorProvider(day = LightFinFoxColors.warning, night = DarkFinFoxColors.warning)
    val overdue = ColorProvider(day = LightFinFoxColors.overdue, night = DarkFinFoxColors.overdue)
    /** Orange highlight (selected / emphasis), per the FinFox widget brief. */
    val highlight = ColorProvider(day = FinFoxPalette.FoxOrange, night = FinFoxPalette.FoxOrange)
    val brand = ColorProvider(day = FinFoxPalette.PrimaryPurple, night = FinFoxPalette.DarkPrimaryText)
}

@Composable
fun WidgetTheme(content: @Composable () -> Unit) = GlanceTheme(colors = WidgetColors.scheme, content = content)

/** Opens [route] in the app (behind the app lock, if enabled). */
fun routeIntent(context: Context, route: String): Intent = Intent(context, MainActivity::class.java).apply {
    action = "com.mymoney.app.WIDGET.$route" // unique per target so extras don't collide
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    putExtra(NotificationDispatcher.EXTRA_ROUTE, route)
}

@Composable
fun openAction(route: String): Action = actionStartActivity(routeIntent(LocalContext.current, route))

@Composable
fun GlanceModifier.clickTo(action: Action?): GlanceModifier = if (LocalInteractive.current && action != null) clickable(action) else this

@Composable
fun WidgetContainer(onClick: Action?, padding: Int = 10, content: @Composable ColumnScope.() -> Unit) {
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(24.dp)
            .background(ImageProvider(R.drawable.widget_background))
            .padding(padding.dp).clickTo(onClick),
        content = content,
    )
}

@Composable
fun WidgetHeader(title: String, subtitle: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(R.drawable.finfox_logo_small), contentDescription = "FinFox", modifier = GlanceModifier.size(28.dp))
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Label(title, size = 13.sp, bold = true, color = GlanceTheme.colors.onSurface)
            if (subtitle != null) Label(subtitle, size = 10.sp, color = GlanceTheme.colors.onSurfaceVariant)
        }
        trailing?.invoke(this)
    }
}

@Composable
fun Label(text: String, size: TextUnit = 12.sp, bold: Boolean = false, color: ColorProvider = GlanceTheme.colors.onSurfaceVariant, maxLines: Int = 1, modifier: GlanceModifier = GlanceModifier) {
    Text(text, modifier, style = TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal), maxLines = maxLines)
}

@Composable
fun Amount(text: String, size: TextUnit = 16.sp, color: ColorProvider = GlanceTheme.colors.onSurface) =
    Label(text, size = size, bold = true, color = color)

/** Small rounded tile with a caption and a value. */
@Composable
fun StatTile(caption: String, value: String, valueColor: ColorProvider, modifier: GlanceModifier = GlanceModifier, onClick: Action? = null) {
    Column(modifier.background(ImageProvider(R.drawable.widget_tile)).cornerRadius(14.dp).padding(horizontal = 8.dp, vertical = 4.dp).clickTo(onClick)) {
        Label(caption, size = 10.sp)
        Amount(value, size = 13.sp, color = valueColor)
    }
}

@Composable
fun Icon(res: Int, description: String?, tint: ColorProvider = GlanceTheme.colors.primary, size: Int = 18) =
    Image(ImageProvider(res), contentDescription = description, modifier = GlanceModifier.size(size.dp), colorFilter = ColorFilter.tint(tint))

@Composable
fun Progress(fraction: Float, color: ColorProvider, modifier: GlanceModifier = GlanceModifier.fillMaxWidth()) =
    LinearProgressIndicator(progress = fraction.coerceIn(0f, 1f), modifier = modifier.height(6.dp), color = color, backgroundColor = GlanceTheme.colors.surfaceVariant)

@Composable
fun Chip(text: String, color: ColorProvider) {
    Box(GlanceModifier.background(ImageProvider(R.drawable.widget_tile)).cornerRadius(8.dp).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Label(text, size = 10.sp, bold = true, color = color)
    }
}

@Composable
fun EmptyLine(text: String) = Label(text, size = 12.sp, maxLines = 2, modifier = GlanceModifier.padding(vertical = 6.dp))

@Composable
fun VSpace(dp: Int) = Spacer(GlanceModifier.height(dp.dp))
