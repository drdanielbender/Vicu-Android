package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.toTextFieldBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlNotesOutputTransformationTest {

    @Test
    fun desktopParagraphsRenderAsEditableLinesWithoutTags() {
        val source = "<p>First note</p><p>Second &amp; third</p>"
        val plan = renderHtmlNotes(source)

        assertEquals("First note\nSecond & third", plan.text)
        assertEquals(plan.text, applyReplacements(source, plan.replacements))
        assertFalse(plan.text.contains("<p>"))
    }

    @Test
    fun inlineFormattingProducesVisibleStyleRanges() {
        val plan = renderHtmlNotes("<p><strong>Bold</strong> and <em>italic</em></p>")

        assertEquals("Bold and italic", plan.text)
        assertTrue(plan.styles.contains(HtmlNoteStyleRange(HtmlNoteStyle.Bold, 0, 4)))
        assertTrue(plan.styles.contains(HtmlNoteStyleRange(HtmlNoteStyle.Italic, 9, 15)))
    }

    @Test
    fun listsAndEntitiesProduceConsistentLosslessEditOperations() {
        val source = "<ol><li>One</li><li>Two&nbsp;items</li></ol>"
        val plan = renderHtmlNotes(source)

        assertEquals("1. One\n2. Two\u00a0items", plan.text)
        assertEquals(plan.text, applyReplacements(source, plan.replacements))
    }

    @Test
    fun plainTextRemainsUnchanged() {
        val plan = renderHtmlNotes("A plain Android note")

        assertEquals("A plain Android note", plan.text)
        assertTrue(plan.replacements.isEmpty())
    }

    @Test
    fun outputTransformationLeavesTheStoredHtmlUntouched() {
        val source = "<p><strong>Stored</strong> HTML</p>"
        val state = TextFieldState(source)
        val output = state.toTextFieldBuffer()

        with(HtmlNotesOutputTransformation) { output.transformOutput() }

        assertEquals("Stored HTML", output.asCharSequence().toString())
        assertEquals(source, state.text.toString())
    }

    private fun applyReplacements(
        source: String,
        replacements: List<HtmlNoteReplacement>,
    ): String = buildString {
        append(source)
        for (replacement in replacements.asReversed()) {
            replace(replacement.start, replacement.end, replacement.text)
        }
    }
}
