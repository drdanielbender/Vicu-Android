package com.rendyhd.vicu.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun Modifier.imagePasteReceiver(onImagePasted: (String) -> Unit): Modifier
