package com.rendyhd.vicu.ui.components.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.util.RecurrenceCustomUnit
import com.rendyhd.vicu.util.RecurrencePreset
import com.rendyhd.vicu.util.RecurrenceValue
import com.rendyhd.vicu.util.customRecurrence
import com.rendyhd.vicu.util.detectRecurrencePreset
import com.rendyhd.vicu.util.formatRecurrence
import com.rendyhd.vicu.util.recurrenceForPreset

private data class RecurrenceOption(
    val preset: RecurrencePreset,
    val label: String,
)

@Composable
fun RecurrencePickerDialog(
    repeatAfter: Long,
    repeatMode: Int,
    onPick: (RecurrenceValue) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = remember(repeatAfter, repeatMode) { RecurrenceValue(repeatAfter, repeatMode) }
    val initialPreset = remember(current) { detectRecurrencePreset(current) }
    var selectedPreset by remember(current) { mutableStateOf(initialPreset) }
    var fromCompletion by remember(current) { mutableStateOf(current.fromCompletion) }
    var customUnit by remember(current) {
        mutableStateOf(
            if (current.repeatAfter > 0 &&
                current.repeatAfter % RecurrenceCustomUnit.WEEKS.seconds == 0L
            ) {
                RecurrenceCustomUnit.WEEKS
            } else {
                RecurrenceCustomUnit.DAYS
            },
        )
    }
    var customInterval by remember(current) {
        val divisor = customUnit.seconds
        val exact = current.repeatAfter.takeIf { it > 0 && it % divisor == 0L }?.div(divisor)
        mutableStateOf((exact ?: 2L).coerceIn(1L, 365L).toString())
    }

    val parsedCustomInterval = customInterval.toIntOrNull()
    val customIsValid = parsedCustomInterval != null && parsedCustomInterval in 1..365
    val canApply = selectedPreset != RecurrencePreset.CUSTOM || customIsValid
    val options = remember {
        listOf(
            RecurrenceOption(RecurrencePreset.NONE, "None"),
            RecurrenceOption(RecurrencePreset.DAILY, "Daily"),
            RecurrenceOption(RecurrencePreset.WEEKLY, "Weekly"),
            RecurrenceOption(RecurrencePreset.MONTHLY, "Monthly"),
            RecurrenceOption(RecurrencePreset.YEARLY, "Yearly"),
            RecurrenceOption(RecurrencePreset.CUSTOM, "Custom"),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeat") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (current.isRecurring) {
                    Text(
                        text = "Current: ${formatRecurrence(current)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedPreset = option.preset }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedPreset == option.preset,
                            onClick = { selectedPreset = option.preset },
                        )
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                if (selectedPreset == RecurrencePreset.CUSTOM) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customInterval,
                        onValueChange = { value ->
                            if (value.all(Char::isDigit) && value.length <= 3) customInterval = value
                        },
                        label = { Text("Every") },
                        supportingText = {
                            if (!customIsValid) Text("Enter a number from 1 to 365")
                        },
                        isError = !customIsValid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = customUnit == RecurrenceCustomUnit.DAYS,
                            onClick = { customUnit = RecurrenceCustomUnit.DAYS },
                            label = { Text("Days") },
                        )
                        FilterChip(
                            selected = customUnit == RecurrenceCustomUnit.WEEKS,
                            onClick = { customUnit = RecurrenceCustomUnit.WEEKS },
                            label = { Text("Weeks") },
                        )
                    }
                }

                if (selectedPreset !in setOf(RecurrencePreset.NONE, RecurrencePreset.MONTHLY)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fromCompletion = !fromCompletion }
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = fromCompletion,
                            onCheckedChange = { fromCompletion = it },
                        )
                        Text("Repeat from completion date")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canApply,
                onClick = {
                    val value = if (selectedPreset == RecurrencePreset.CUSTOM) {
                        customRecurrence(
                            interval = checkNotNull(parsedCustomInterval),
                            unit = customUnit,
                            fromCompletion = fromCompletion,
                        )
                    } else {
                        recurrenceForPreset(selectedPreset, fromCompletion)
                    }
                    onPick(value)
                    onDismiss()
                },
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
