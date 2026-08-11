package com.rendyhd.vicu.util

import platform.Foundation.NSUUID

actual fun randomUuid(): String = NSUUID.UUID().UUIDString
