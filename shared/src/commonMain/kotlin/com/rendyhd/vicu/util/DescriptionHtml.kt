package com.rendyhd.vicu.util

/**
 * Splits a Vikunja task description into three pieces so the rich-text editor
 * only sees the HTML body:
 *   - html body (what TipTap / richeditor owns)
 *   - image token refs ([[image:N]], [[image-pending:uuid]])
 *   - preserved note/page-link HTML comments + anchors
 *   - preserved Vicu routine metadata
 *
 * Inverse [merge] rejoins them in the same order Vikunja stored them.
 */
object DescriptionHtml {

    data class Split(
        val htmlBody: String,
        val imageRefs: List<ImageTokens.ImageRef>,
        val linkHtml: String,
        val routineHtml: String,
    )

    fun splitForEditor(raw: String?): Split {
        if (raw.isNullOrEmpty()) return Split("", emptyList(), "", "")
        val routineHtml = RoutineEnvelope.extractMarker(raw)
        val withoutRoutine = RoutineEnvelope.strip(raw)
        val linkHtml = TaskLinkParser.extractLinkHtml(withoutRoutine)
        val withoutLinks = TaskLinkParser.stripLinks(withoutRoutine)
        val (body, refs) = ImageTokens.parseValue(withoutLinks)
        return Split(body, refs, linkHtml, routineHtml)
    }

    fun merge(
        htmlBody: String,
        imageRefs: List<ImageTokens.ImageRef>,
        linkHtml: String,
        routineHtml: String = "",
    ): String {
        val withImages = ImageTokens.buildValue(htmlBody, imageRefs)
        return listOf(withImages, linkHtml, routineHtml)
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
