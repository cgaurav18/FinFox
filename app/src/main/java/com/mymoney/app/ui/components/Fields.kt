package com.mymoney.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset

private val fieldShape = com.mymoney.app.ui.theme.FinFoxShapes.input

/** FinFox input colours: surface fill, purple focus indicator/label/cursor, red errors. */
@Composable
private fun finFoxFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedBorderColor = MaterialTheme.colorScheme.primary, focusedLabelColor = MaterialTheme.colorScheme.primary,
    cursorColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.outline,
)

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    leadingIcon: ImageVector? = null,
    placeholder: String? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    maxLength: Int = 120,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength) onValueChange(it) },
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        shape = fieldShape,
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon?.let { { Icon(it, null) } },
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default, capitalization = capitalization),
        colors = finFoxFieldColors(),
    )
}

/** Amount entry; only digits and one decimal point with max two decimals are accepted. */
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Amount",
    modifier: Modifier = Modifier,
    error: String? = null,
    currency: String = LocalCurrency.current,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val v = raw.replace(",", "")
            if (v.isEmpty() || Regex("^\\d{0,13}(\\.\\d{0,2})?$").matches(v)) onValueChange(v)
        },
        label = { Text(label) },
        prefix = { Text(Money.symbolFor(currency)) },
        modifier = modifier.fillMaxWidth(),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        shape = fieldShape,
        textStyle = MaterialTheme.typography.titleMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        colors = finFoxFieldColors(),
    )
}

/** Read-only field that opens a picker when tapped. */
@Composable
fun PickerField(label: String, value: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, error: String? = null, onClear: (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (onClear != null && value.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear $label") }
                else Icon(icon, null)
            },
            isError = error != null, supportingText = error?.let { { Text(it) } }, shape = fieldShape, singleLine = true,
            colors = finFoxFieldColors(),
        )
        // Transparent overlay captures taps (readOnly fields otherwise just focus).
        Box(Modifier.matchParentSize().padding(end = if (onClear != null && value.isNotEmpty()) 52.dp else 0.dp).clip(fieldShape).clickable(onClick = onClick))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null,
    error: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    PickerField(label, date?.let { Dates.formatDay(it) } ?: "", Icons.Filled.CalendarMonth, { open = true }, modifier, error, onClear)
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: Dates.today()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return (minDate == null || !d.isBefore(minDate)) && (maxDate == null || !d.isAfter(maxDate))
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, time: LocalTime, onTime: (LocalTime) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    PickerField(label, time.format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a")), Icons.Filled.Schedule, { open = true }, modifier)
    if (open) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { onTime(LocalTime.of(state.hour, state.minute)); open = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    leading: (@Composable (T) -> Unit)? = null,
    placeholder: String = "Select",
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            leadingIcon = if (leading != null && selected != null) ({ leading(selected) }) else null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            shape = fieldShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = finFoxFieldColors(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (options.isEmpty()) DropdownMenuItem(text = { Text("Nothing to choose yet") }, onClick = { expanded = false }, enabled = false)
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(optionLabel(o)) },
                    leadingIcon = leading?.let { { it(o) } },
                    onClick = { onSelect(o); expanded = false },
                )
            }
        }
    }
}

@Composable
fun MonthSelector(month: YearMonth, onChange: (YearMonth) -> Unit, modifier: Modifier = Modifier, allowFuture: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
        Text(Dates.formatMonth(month), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 4.dp))
        IconButton(onClick = { onChange(month.plusMonths(1)) }, enabled = allowFuture || month < YearMonth.now()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(selected: Long, onSelect: (Long) -> Unit, colors: List<Long> = AppIcons.palette) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            val isSel = c == selected
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                    .border(if (isSel) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), CircleShape)
                    .clickable { onSelect(c) },
                contentAlignment = Alignment.Center,
            ) { if (isSel) Icon(Icons.Filled.Check, "Selected", tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPicker(keys: List<String>, selected: String, color: Color, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { k ->
            val isSel = k == selected
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (isSel) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
                    .border(if (isSel) 2.dp else 0.dp, color, RoundedCornerShape(12.dp))
                    .clickable { onSelect(k) },
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.forKey(k), k, tint = if (isSel) color else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * Small form dialog for amount + date + note entry (repayments, contributions, reconciliations).
 */
@Composable
fun AmountEntryDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (amountMinor: Long, date: LocalDate, note: String) -> Unit,
    initialAmount: String = "",
    initialDate: LocalDate = Dates.today(),
    initialNote: String = "",
    showDate: Boolean = true,
    noteLabel: String = "Note (optional)",
    helper: String? = null,
    allowZero: Boolean = false,
    extra: @Composable () -> Unit = {},
) {
    var amount by remember { mutableStateOf(initialAmount) }
    var date by remember { mutableStateOf(initialDate) }
    var note by remember { mutableStateOf(initialNote) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (helper != null) Text(helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AmountField(amount, { amount = it; error = null }, error = error)
                extra()
                if (showDate) DateField("Date", date, { date = it }, maxDate = Dates.today())
                AppTextField(note, { note = it }, noteLabel)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val r = Money.parse(amount, allowZero)) {
                    is Money.ParseResult.Ok -> { onConfirm(r.minor, date, note); onDismiss() }
                    is Money.ParseResult.Error -> error = r.message
                }
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun FormColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
fun LabeledSwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
