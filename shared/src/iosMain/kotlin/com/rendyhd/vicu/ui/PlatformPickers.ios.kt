package com.rendyhd.vicu.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberImagePicker(onImagePicked: (String) -> Unit): () -> Unit {
    return {}
}

@Composable
actual fun rememberFilePicker(onFilePicked: (String) -> Unit): () -> Unit {
    return {}
}
