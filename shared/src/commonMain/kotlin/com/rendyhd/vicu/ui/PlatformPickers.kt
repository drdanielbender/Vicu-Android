package com.rendyhd.vicu.ui

import androidx.compose.runtime.Composable

@Composable
expect fun rememberImagePicker(onImagePicked: (String) -> Unit): () -> Unit

@Composable
expect fun rememberFilePicker(onFilePicked: (String) -> Unit): () -> Unit
