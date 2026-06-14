package com.rendyhd.vicu.ui

import androidx.compose.runtime.Composable
import com.rendyhd.vicu.auth.OidcHandler

@Composable
expect fun rememberOidcLauncher(
    onResult: (code: String?, state: String?, error: String?) -> Unit
): (OidcHandler.AuthParams) -> Unit
