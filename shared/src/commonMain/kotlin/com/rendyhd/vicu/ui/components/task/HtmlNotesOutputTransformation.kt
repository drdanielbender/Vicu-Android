package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

internal enum class HtmlNoteStyle {
    Bold,
    Italic,
    Strike,
    Code,
    Underline,
}

internal data class HtmlNoteStyleRange(
    val style: HtmlNoteStyle,
    val start: Int,
    val end: Int,
)

internal data class HtmlNoteReplacement(
    val start: Int,
    val end: Int,
    val text: String,
)

internal data class HtmlNoteRenderPlan(
    val text: String,
    val replacements: List<HtmlNoteReplacement>,
    val styles: List<HtmlNoteStyleRange>,
)

private val htmlNoteTokenRegex = Regex(
    pattern = """<!--.*?-->|</?(?:p|br|strong|b|em|i|s|strike|del|code|a|u|ul|ol|li|div|blockquote|h[1-6]|pre|span|mark|hr|img)(?:\s[^>]*)?\s*/?>|&(?:#\d+|#x[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]+);""",
    options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val htmlTagNameRegex = Regex("""^<\s*(/?)\s*([a-zA-Z][a-zA-Z0-9]*)""")
private val blockTags = setOf("p", "div", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6")

/**
 * Builds the visual representation of Vikunja's HTML notes while retaining edit operations
 * against the original source. Applying those operations individually lets Compose maintain the
 * cursor mapping between visible text and the HTML stored in TextFieldState.
 */
internal fun renderHtmlNotes(source: String): HtmlNoteRenderPlan {
    if (source.isEmpty()) return HtmlNoteRenderPlan("", emptyList(), emptyList())

    val rendered = StringBuilder(source.length)
    val replacements = mutableListOf<HtmlNoteReplacement>()
    val styles = mutableListOf<HtmlNoteStyleRange>()
    val openStyles = mutableMapOf<HtmlNoteStyle, MutableList<Int>>()
    val listStack = mutableListOf<ListContext>()
    var sourceCursor = 0

    fun appendBreak(): String {
        if (rendered.isEmpty() || rendered.last() == '\n') return ""
        rendered.append('\n')
        return "\n"
    }

    fun openStyle(style: HtmlNoteStyle) {
        openStyles.getOrPut(style) { mutableListOf() }.add(rendered.length)
    }

    fun closeStyle(style: HtmlNoteStyle) {
        val starts = openStyles[style] ?: return
        if (starts.isEmpty()) return
        val start = starts.removeAt(starts.lastIndex)
        if (start < rendered.length) styles += HtmlNoteStyleRange(style, start, rendered.length)
    }

    for (match in htmlNoteTokenRegex.findAll(source)) {
        if (match.range.first > sourceCursor) {
            rendered.append(source, sourceCursor, match.range.first)
        }

        val token = match.value
        val replacement = when {
            token.startsWith("<!--") -> ""
            token.startsWith("&") -> decodeHtmlEntity(token).also(rendered::append)
            else -> {
                val tagMatch = htmlTagNameRegex.find(token)
                val closing = tagMatch?.groupValues?.get(1) == "/"
                val tag = tagMatch?.groupValues?.get(2)?.lowercase().orEmpty()
                val selfClosing = token.trimEnd().endsWith("/>") || tag in setOf("br", "hr", "img")

                when {
                    tag == "br" -> appendBreak()
                    tag == "hr" -> {
                        val before = appendBreak()
                        rendered.append("———")
                        val after = appendBreak()
                        before + "———" + after
                    }
                    tag == "img" -> {
                        rendered.append("[image]")
                        "[image]"
                    }
                    tag == "ul" && !closing -> {
                        listStack += ListContext(ordered = false)
                        appendBreak()
                    }
                    tag == "ol" && !closing -> {
                        listStack += ListContext(ordered = true)
                        appendBreak()
                    }
                    tag in setOf("ul", "ol") && closing -> {
                        if (listStack.isNotEmpty()) listStack.removeAt(listStack.lastIndex)
                        appendBreak()
                    }
                    tag == "li" && !closing -> {
                        val lineBreak = appendBreak()
                        val context = listStack.lastOrNull()
                        val prefix = if (context?.ordered == true) {
                            "${context.nextIndex++}. "
                        } else {
                            "• "
                        }
                        rendered.append(prefix)
                        lineBreak + prefix
                    }
                    tag == "li" && closing -> appendBreak()
                    tag in blockTags -> appendBreak()
                    else -> {
                        val style = styleForTag(tag)
                        if (style != null) {
                            if (closing) closeStyle(style) else if (!selfClosing) openStyle(style)
                        }
                        ""
                    }
                }
            }
        }

        replacements += HtmlNoteReplacement(
            start = match.range.first,
            end = match.range.last + 1,
            text = replacement,
        )
        sourceCursor = match.range.last + 1
    }

    if (sourceCursor < source.length) rendered.append(source, sourceCursor, source.length)

    for ((style, starts) in openStyles) {
        for (start in starts) {
            if (start < rendered.length) styles += HtmlNoteStyleRange(style, start, rendered.length)
        }
    }

    // Structural closing tags should not create an empty line at the bottom of the editor.
    while (rendered.endsWith("\n")) {
        rendered.deleteAt(rendered.lastIndex)
        val lastBreak = replacements.indexOfLast { it.text.endsWith("\n") }
        if (lastBreak < 0) break
        val operation = replacements[lastBreak]
        replacements[lastBreak] = operation.copy(text = operation.text.dropLast(1))
    }

    val maxLength = rendered.length
    return HtmlNoteRenderPlan(
        text = rendered.toString(),
        replacements = replacements,
        styles = styles.mapNotNull { range ->
            val start = range.start.coerceIn(0, maxLength)
            val end = range.end.coerceIn(start, maxLength)
            range.copy(start = start, end = end).takeIf { start < end }
        },
    )
}

/** Shows formatted notes while [TextFieldBuffer] continues to contain the lossless HTML source. */
internal object HtmlNotesOutputTransformation : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val plan = renderHtmlNotes(asCharSequence().toString())
        for (replacement in plan.replacements.asReversed()) {
            replace(replacement.start, replacement.end, replacement.text)
        }
        for (range in plan.styles) {
            addStyle(range.style.toSpanStyle(), range.start, range.end)
        }
    }
}

private data class ListContext(
    val ordered: Boolean,
    var nextIndex: Int = 1,
)

private fun styleForTag(tag: String): HtmlNoteStyle? = when (tag) {
    "strong", "b" -> HtmlNoteStyle.Bold
    "em", "i" -> HtmlNoteStyle.Italic
    "s", "strike", "del" -> HtmlNoteStyle.Strike
    "code", "pre" -> HtmlNoteStyle.Code
    "a", "u" -> HtmlNoteStyle.Underline
    else -> null
}

private fun HtmlNoteStyle.toSpanStyle(): SpanStyle = when (this) {
    HtmlNoteStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
    HtmlNoteStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
    HtmlNoteStyle.Strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    HtmlNoteStyle.Code -> SpanStyle(fontFamily = FontFamily.Monospace)
    HtmlNoteStyle.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
}

private fun decodeHtmlEntity(entity: String): String = when (entity.lowercase()) {
    "&amp;" -> "&"
    "&lt;" -> "<"
    "&gt;" -> ">"
    "&quot;" -> "\""
    "&apos;", "&#39;" -> "'"
    "&nbsp;" -> "\u00a0"
    else -> decodeNumericEntity(entity) ?: entity
}

private fun decodeNumericEntity(entity: String): String? {
    if (!entity.startsWith("&#") || !entity.endsWith(';')) return null
    val body = entity.substring(2, entity.length - 1)
    val codePoint = if (body.startsWith('x', ignoreCase = true)) {
        body.drop(1).toIntOrNull(16)
    } else {
        body.toIntOrNull()
    } ?: return null
    if (codePoint !in 0..0x10ffff || codePoint in 0xd800..0xdfff) return null
    return if (codePoint <= 0xffff) {
        codePoint.toChar().toString()
    } else {
        val adjusted = codePoint - 0x10000
        val high = ((adjusted ushr 10) + 0xd800).toChar()
        val low = ((adjusted and 0x3ff) + 0xdc00).toChar()
        "$high$low"
    }
}
