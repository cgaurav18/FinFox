package com.mymoney.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mymoney.app.core.Money
import com.mymoney.app.domain.SeriesPoint
import com.mymoney.app.domain.Slice
import com.mymoney.app.ui.theme.FinFoxTheme
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/** Donut chart; tap a slice (or legend row) to highlight it and show its value in the centre. */
@Composable
fun DonutChart(
    slices: List<Slice>,
    centerLabel: String,
    totalMinor: Long,
    modifier: Modifier = Modifier,
    showLegend: Boolean = true,
) {
    var selected by remember(slices) { mutableStateOf<Int?>(null) }
    val sweep = remember(slices) { Animatable(0f) }
    val reducedsweep = com.mymoney.app.ui.theme.FinFoxTheme.reducedMotion
    LaunchedEffect(slices) { if (reducedsweep) sweep.snapTo(1f) else { sweep.snapTo(0f); sweep.animateTo(1f, tween(700)) } }
    val total = slices.sumOf { it.valueMinor }.coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val currency = LocalCurrency.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(200.dp).semantics { contentDescription = "$centerLabel ${Money.format(totalMinor, currency)}" }, contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.size(200.dp).pointerInput(slices) {
                    detectTapGestures { p ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val r = hypot(p.x - c.x, p.y - c.y)
                        if (r < size.width * 0.28f || r > size.width / 2f) { selected = null; return@detectTapGestures }
                        var ang = (atan2(p.y - c.y, p.x - c.x) * 180 / PI).toFloat() + 90f
                        if (ang < 0) ang += 360f
                        var acc = 0f
                        slices.forEachIndexed { i, s ->
                            val a = 360f * s.valueMinor / total
                            if (ang >= acc && ang < acc + a) { selected = if (selected == i) null else i; return@detectTapGestures }
                            acc += a
                        }
                    }
                }
            ) {
                val stroke = size.width * 0.14f
                val inset = stroke / 2 + 6f
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                var start = -90f
                slices.forEachIndexed { i, s ->
                    val a = 360f * s.valueMinor / total * sweep.value
                    val w = if (selected == i) stroke * 1.25f else stroke
                    val alpha = if (selected == null || selected == i) 1f else 0.35f
                    drawArc(Color(s.colorArgb).copy(alpha = alpha), start, (a - 1.2f).coerceAtLeast(0.5f), false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Butt))
                    start += a
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val sel = selected?.let { slices.getOrNull(it) }
                Text(Money.format(sel?.valueMinor ?: totalMinor, currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(sel?.let { "${it.label} • ${it.percent}%" } ?: centerLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp))
            }
        }
        if (showLegend) {
            Spacer(Modifier.height(12.dp))
            slices.forEachIndexed { i, s ->
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { selected = if (selected == i) null else i }
                        .background(if (selected == i) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(s.colorArgb)))
                    Spacer(Modifier.width(10.dp))
                    Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.percent}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
                    Text(Money.format(s.valueMinor, currency), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Grouped income/expense bars. Tap a group to see exact values. */
@Composable
fun IncomeExpenseBarChart(points: List<SeriesPoint>, modifier: Modifier = Modifier) {
    val fin = FinFoxTheme.colors
    val currency = LocalCurrency.current
    var selected by remember(points) { mutableStateOf<Int?>(points.indexOfLast { it.incomeMinor != 0L || it.expenseMinor != 0L }.takeIf { it >= 0 }) }
    val grow = remember(points) { Animatable(0f) }
    val reducedgrow = com.mymoney.app.ui.theme.FinFoxTheme.reducedMotion
    LaunchedEffect(points) { if (reducedgrow) grow.snapTo(1f) else { grow.snapTo(0f); grow.animateTo(1f, tween(600)) } }
    val max = (points.maxOfOrNull { maxOf(it.incomeMinor, it.expenseMinor) } ?: 0L).coerceAtLeast(1)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    Column(modifier.fillMaxWidth()) {
        val sel = selected?.let { points.getOrNull(it) }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(sel?.label ?: "Tap a bar", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            LegendDot(fin.income, "In " + Money.compact(sel?.incomeMinor ?: 0, currency))
            Spacer(Modifier.width(12.dp))
            LegendDot(fin.expense, "Out " + Money.compact(sel?.expenseMinor ?: 0, currency))
        }
        Canvas(
            Modifier.fillMaxWidth().height(170.dp).pointerInput(points) {
                detectTapGestures { p ->
                    if (points.isEmpty()) return@detectTapGestures
                    val i = (p.x / (size.width / points.size)).toInt().coerceIn(0, points.lastIndex)
                    selected = i
                }
            }
        ) {
            val n = points.size.coerceAtLeast(1)
            val groupW = size.width / n
            val barW = (groupW * 0.32f).coerceAtMost(28f)
            val h = size.height - 4f
            for (g in 1..3) {
                val y = h - h * g / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
            points.forEachIndexed { i, p ->
                val cx = groupW * i + groupW / 2
                if (selected == i) drawRoundRect(highlight, Offset(groupW * i + 2, 0f), Size(groupW - 4, size.height), CornerRadius(12f))
                val ih = h * p.incomeMinor / max * grow.value
                val eh = h * p.expenseMinor / max * grow.value
                drawRoundRect(fin.income, Offset(cx - barW - 2, h - ih), Size(barW, ih), CornerRadius(8f))
                drawRoundRect(fin.expense, Offset(cx + 2, h - eh), Size(barW, eh), CornerRadius(8f))
            }
        }
        // Axis labels: one per bar, or one per group of bars when there are many (e.g. 30 days).
        val step = if (points.size > 12) (points.size + 5) / 6 else 1
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            points.indices.chunked(step).forEach { idx ->
                val first = idx.first()
                Text(
                    points[first].label, style = MaterialTheme.typography.labelSmall,
                    color = if (selected in idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(idx.size.toFloat()), maxLines = 1,
                    textAlign = if (step == 1) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
                )
            }
        }
    }
}

/** Line chart of a running value (e.g. cumulative net cash flow). Drag or tap to inspect points. */
@Composable
fun LineChart(values: List<Long>, labels: List<String>, title: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val currency = LocalCurrency.current
    var selected by remember(values) { mutableStateOf(values.lastIndex.takeIf { it >= 0 }) }
    val draw = remember(values) { Animatable(0f) }
    val reduceddraw = com.mymoney.app.ui.theme.FinFoxTheme.reducedMotion
    LaunchedEffect(values) { if (reduceddraw) draw.snapTo(1f) else { draw.snapTo(0f); draw.animateTo(1f, tween(700)) } }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            selected?.let { i ->
                Text("${labels.getOrNull(i) ?: ""}  ${Money.format(values[i], currency)}", style = MaterialTheme.typography.labelLarge, color = color)
            }
        }
        if (values.size < 2) {
            Text("Not enough data for a trend yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            return@Column
        }
        val maxV = values.max(); val minV = minOf(values.min(), 0L)
        val span = (maxV - minV).coerceAtLeast(1)
        fun pick(x: Float, width: Float) { selected = ((x / width) * (values.size - 1) + 0.5f).toInt().coerceIn(0, values.lastIndex) }
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(2.4f)
                .pointerInput(values) { detectTapGestures { pick(it.x, size.width.toFloat()) } }
                .pointerInput(values) { detectDragGestures { change, _ -> pick(change.position.x, size.width.toFloat()) } }
        ) {
            val h = size.height - 8f
            fun px(i: Int) = size.width * i / (values.size - 1)
            fun py(v: Long) = 4f + h - h * (v - minV).toFloat() / span
            for (g in 0..3) { val y = 4f + h * g / 3f; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
            if (minV < 0) drawLine(zeroColor, Offset(0f, py(0)), Offset(size.width, py(0)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val visible = (values.size * draw.value).toInt().coerceIn(2, values.size)
            val path = Path().apply { moveTo(px(0), py(values[0])); for (i in 1 until visible) lineTo(px(i), py(values[i])) }
            val fill = Path().apply { addPath(path); lineTo(px(visible - 1), size.height); lineTo(0f, size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent)))
            drawPath(path, color, style = Stroke(5f, cap = StrokeCap.Round))
            selected?.takeIf { it < visible }?.let { i ->
                drawLine(color.copy(alpha = 0.4f), Offset(px(i), 0f), Offset(px(i), size.height), 2f)
                drawCircle(Color.White, 9f, Offset(px(i), py(values[i])))
                drawCircle(color, 6f, Offset(px(i), py(values[i])))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(labels.firstOrNull() ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(labels.lastOrNull() ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
