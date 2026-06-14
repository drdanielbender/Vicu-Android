package com.rendyhd.vicu.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun Modifier.imagePasteReceiver(onImagePasted: (String) -> Unit): Modifier {
    return this
}
