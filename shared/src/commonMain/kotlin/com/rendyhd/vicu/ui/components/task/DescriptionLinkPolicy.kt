package com.rendyhd.vicu.ui.components.task

private val safeDescriptionScheme = Regex("^(https?://|mailto:)", RegexOption.IGNORE_CASE)
private val bareDescriptionHost = Regex(
    "^(?:localhost|(?:[a-z0-9-]+\\.)+[a-z]{2,})(?::\\d+)?(?:[/?#]\\S*)?$",
    RegexOption.IGNORE_CASE,
)

internal fun isSafeDescriptionHref(raw: String?): Boolean {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
        return false
    }
    if (!safeDescriptionScheme.containsMatchIn(value)) return false
    return when {
        value.startsWith("http://", ignoreCase = true) ->
            value.substringAfter("://").isNotEmpty()
        value.startsWith("https://", ignoreCase = true) ->
            value.substringAfter("://").isNotEmpty()
        value.startsWith("mailto:", ignoreCase = true) ->
            value.substringAfter(':').isNotEmpty()
        else -> false
    }
}

internal fun normalizeDescriptionHref(raw: String): String? {
    val value = raw.trim()
    if (isSafeDescriptionHref(value)) return value
    val withScheme = if (bareDescriptionHost.matches(value)) "https://$value" else return null
    return withScheme.takeIf(::isSafeDescriptionHref)
}
