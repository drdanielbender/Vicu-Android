package com.rendyhd.vicu.util

import java.util.UUID

actual fun randomUuid(): String = UUID.randomUUID().toString()
