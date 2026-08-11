package com.rendyhd.vicu.auth

internal const val ERROR_INVALID_TOTP = 1017L
internal const val ERROR_USED_TOTP = 1025L
internal const val TOTP_PASSCODE_LENGTH = 6

internal fun isTotpProblemCode(code: Long?): Boolean =
    code == ERROR_INVALID_TOTP || code == ERROR_USED_TOTP

internal fun sanitizeTotpPasscode(value: String): String =
    value.filter(Char::isDigit).take(TOTP_PASSCODE_LENGTH)

internal fun isTotpPasscodeComplete(value: String): Boolean =
    value.length == TOTP_PASSCODE_LENGTH && value.all(Char::isDigit)
