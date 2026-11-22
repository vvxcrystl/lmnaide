package dev.lmnaide.calendar.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.lmnaide.calendar.data.SyncLimits

private const val CUSTOM = -1

private enum class ReminderUnit(val label: String, val minutes: Int) {
    MINUTES("Minutes", 1), HOURS("Hours", 60), DAYS("Days", 24 * 60), WEEKS("Weeks", 7 * 24 * 60),
}

/** Preset notifications not yet in [existing], then "Custom…" for any amount of minutes, hours, days or weeks. */
@Composable
fun AddReminderDialog(
    presets: List<Int>,
    existing: List<Int>,
    allDay: Boolean,
    use24h: Boolean,
    onAdd: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        CustomReminderDialog(existing, allDay, use24h, onAdd, onDismiss)
    } else {
        OptionsDialog(
            title = "Add notification",
            options = presets.filter { it !in existing } + CUSTOM,
            selected = null,
            label = { if (it == CUSTOM) "Custom…" else Fmt.reminder(it, allDay, use24h) },
            onSelect = { if (it == CUSTOM) custom = true else onAdd(it) },
            // Picking Custom… swaps this list for the custom dialog instead of closing.
            onDismiss = { if (!custom) onDismiss() },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomReminderDialog(
    existing: List<Int>,
    allDay: Boolean,
    use24h: Boolean,
    onAdd: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(ReminderUnit.MINUTES) }
    val minutes = amount.toLongOrNull()?.times(unit.minutes)
    val problem = when {
        minutes == null -> null
        minutes > SyncLimits.REMINDER_MINUTES -> "Up to 1 year before"
        minutes.toInt() in existing -> "Already added"
        else -> null
    }
    val valid = minutes != null && problem == null
    val keyboard = LocalSoftwareKeyboardController.current
    // Hides the keyboard first, or it stays up and lands on the first field of the dialog underneath.
    fun close() {
        keyboard?.hide()
        onDismiss()
    }
    fun add() {
        if (!valid) return
        onAdd(checkNotNull(minutes).toInt())
        close()
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = ::close,
        title = { Text("Custom notification") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter(Char::isDigit).take(6) },
                    label = { Text("How long before") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = {
                        Text(problem ?: minutes?.let { Fmt.reminder(it.toInt(), allDay, use24h) } ?: " ")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReminderUnit.entries.forEach { option ->
                        FilterChip(
                            selected = unit == option,
                            onClick = { unit = option },
                            label = { Text(option.label) },
                            shape = CircleShape,
                            border = null,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::add, enabled = valid) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("Cancel") } },
    )
}
