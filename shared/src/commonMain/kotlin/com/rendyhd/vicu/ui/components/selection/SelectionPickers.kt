package com.rendyhd.vicu.ui.components.selection

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.rendyhd.vicu.ui.components.picker.LabelPickerDialog
import com.rendyhd.vicu.ui.components.picker.PriorityPickerDialog
import com.rendyhd.vicu.ui.components.picker.ProjectPickerDialog
import com.rendyhd.vicu.ui.components.picker.VicuDatePickerDialog

/** Dialog-based actions available from the multi-select overflow menu. */
enum class SelectionAction {
    SCHEDULE,
    SET_PRIORITY,
    MOVE_PROJECT,
    APPLY_LABEL,
    REMOVE,
}

@Composable
fun SelectionPickers(
    selectionVm: SelectionViewModel,
    action: SelectionAction?,
    selectedCount: Int,
    onDismiss: () -> Unit,
) {
    when (action) {
        SelectionAction.SCHEDULE -> VicuDatePickerDialog(
            currentDate = null,
            onDateSelected = selectionVm::bulkSchedule,
            onClearDate = {},
            onDismiss = onDismiss,
        )

        SelectionAction.SET_PRIORITY -> PriorityPickerDialog(
            current = null,
            onPick = selectionVm::bulkSetPriority,
            onDismiss = onDismiss,
        )

        SelectionAction.MOVE_PROJECT -> {
            val projects by selectionVm.projects.collectAsState()
            ProjectPickerDialog(
                projects = projects,
                selectedProjectId = null,
                onProjectSelected = selectionVm::bulkMove,
                onDismiss = onDismiss,
            )
        }

        SelectionAction.APPLY_LABEL -> {
            val labels by selectionVm.labels.collectAsState()
            LabelPickerDialog(
                allLabels = labels,
                selectedLabelIds = emptySet(),
                onToggleLabel = {
                    selectionVm.bulkApplyLabel(it)
                    onDismiss()
                },
                onCreateLabel = { _, _ -> },
                onDismiss = onDismiss,
            )
        }

        SelectionAction.REMOVE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Remove selected tasks?") },
            text = {
                Text(
                    if (selectedCount == 1) {
                        "This task will be permanently removed."
                    } else {
                        "$selectedCount tasks will be permanently removed."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectionVm.bulkRemove()
                        onDismiss()
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )

        null -> Unit
    }
}
