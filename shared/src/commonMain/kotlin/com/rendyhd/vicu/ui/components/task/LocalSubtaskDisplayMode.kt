package com.rendyhd.vicu.ui.components.task

import androidx.compose.runtime.compositionLocalOf
import com.rendyhd.vicu.data.local.SubtaskDisplayMode

/** App-wide subtask presentation preference, supplied once by [com.rendyhd.vicu.ui.VicuApp]. */
val LocalSubtaskDisplayMode = compositionLocalOf { SubtaskDisplayMode.INSIDE_TASK }
