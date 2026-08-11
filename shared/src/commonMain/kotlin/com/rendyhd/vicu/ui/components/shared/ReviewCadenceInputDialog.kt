package com.rendyhd.vicu.ui.components.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

internal const val MIN_REVIEW_CADENCE_DAYS = 1
internal const val MAX_REVIEW_CADENCE_DAYS = 365

internal fun parseReviewCadenceDays(value: String): Int? =
    value.toIntOrNull()?.takeIf { it in MIN_REVIEW_CADENCE_DAYS..MAX_REVIEW_CADENCE_DAYS }

@Composable
fun ReviewCadenceInputDialog(
    title: String,
    initialDays: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember(initialDays) { mutableStateOf(initialDays.toString()) }
    val days = parseReviewCadenceDays(input)

    fun confirm() {
        days?.let(onConfirm)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { value ->
                    if (value.all(Char::isDigit) && value.length <= 3) input = value
                },
                label = { Text("Cadence in days") },
                supportingText = {
                    if (days == null) Text("Enter a number from 1 to 365")
                },
                isError = days == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = days != null,
                onClick = { confirm() },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
