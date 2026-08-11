@file:OptIn(ExperimentalCascadeHtmlApi::class)

package com.rendyhd.vicu.ui.components.task

import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockAttributes
import io.github.linreal.cascade.editor.core.BlockContent
import io.github.linreal.cascade.editor.core.BlockType
import io.github.linreal.cascade.editor.core.SpanStyle
import io.github.linreal.cascade.editor.core.UnknownBlockType
import io.github.linreal.cascade.editor.htmlserialization.ExperimentalCascadeHtmlApi
import io.github.linreal.cascade.editor.htmlserialization.HtmlDecodeWarning
import io.github.linreal.cascade.editor.htmlserialization.HtmlSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VikunjaDescriptionHtmlProfileTest {
    private val profile = VikunjaDescriptionHtmlProfile.Profile

    @Test
    fun plainRootTextIsWrappedAndInlineFormattingSurvives() {
        val result = HtmlSchema.decodeWithReport(
            "Hello <strong>field</strong> agent",
            profile,
        )

        assertTrue(result.warnings.isEmpty())
        val block = result.blocks.single()
        assertEquals(BlockType.Paragraph, block.type)
        val content = assertIs<BlockContent.Text>(block.content)
        assertEquals("Hello field agent", content.text)
        assertEquals(SpanStyle.Bold, content.spans.single().style)
        assertEquals("field", content.text.substring(content.spans.single().start, content.spans.single().end))
    }

    @Test
    fun legacyPlainTextNewlinesRemainSeparateBlocks() {
        val decoded = HtmlSchema.decode("Line 1\nLine 2", profile)

        assertEquals(listOf("Line 1", "Line 2"), decoded.map(Block::text))
        assertTrue(decoded.all { it.type == BlockType.Paragraph })
    }

    @Test
    fun defaultNestedListsStillUseCascadeOutlineSemantics() {
        val html = "<ul><li>Parent<ul><li>Child</li></ul></li><li>Sibling</li></ul>"

        val decoded = HtmlSchema.decode(html, profile)

        assertEquals(listOf("Parent", "Child", "Sibling"), decoded.map(Block::text))
        assertEquals(
            listOf(0, 1, 0),
            decoded.map { it.attributes.indentationLevel },
        )
        assertTrue(decoded.all { it.type == BlockType.BulletList })

        val roundTrip = HtmlSchema.decode(
            HtmlSchema.encode(decoded, profile),
            profile,
        )
        assertEquals(decoded.semanticShape(), roundTrip.semanticShape())
    }

    @Test
    fun tipTapTaskListDecodesCheckedStateFormattingAndNesting() {
        val html = """
            <ul data-type="taskList">
              <li data-type="taskItem" data-checked="true" data-task-id="original">
                <label contenteditable="false"><input type="checkbox" checked="checked"><span></span></label>
                <div>
                  <p><strong>Ship</strong> release</p>
                  <ul data-type="taskList">
                    <li data-type="taskItem" data-checked="false">
                      <div><p>Write notes</p></div>
                    </li>
                  </ul>
                </div>
              </li>
            </ul>
        """.trimIndent()

        val result = HtmlSchema.decodeWithReport(html, profile)

        assertTrue(result.warnings.isEmpty())
        assertEquals(2, result.blocks.size, result.toString())
        assertEquals(BlockType.Todo(checked = true), result.blocks[0].type)
        assertEquals(BlockType.Todo(checked = false), result.blocks[1].type)
        assertEquals(listOf(0, 1), result.blocks.map { it.attributes.indentationLevel })
        assertEquals(listOf("Ship release", "Write notes"), result.blocks.map(Block::text))
        assertFalse(result.blocks.first().id.value == "original")
        val firstContent = assertIs<BlockContent.Text>(result.blocks.first().content)
        assertEquals(SpanStyle.Bold, firstContent.spans.single().style)
        assertEquals("Ship", firstContent.text.substring(0, 4))
    }

    @Test
    fun checkedNestedItemDoesNotMarkItsExplicitlyUncheckedParentChecked() {
        val html = """
            <ul data-type="taskList">
              <li data-type="taskItem" data-checked="false">
                <div>
                  <p>Parent</p>
                  <ul data-type="taskList">
                    <li data-type="taskItem" data-checked="true">
                      <label><input type="checkbox" checked="checked"></label>
                      <div><p>Child</p></div>
                    </li>
                  </ul>
                </div>
              </li>
            </ul>
        """.trimIndent()

        val decoded = HtmlSchema.decode(html, profile)

        assertEquals(
            listOf(BlockType.Todo(checked = false), BlockType.Todo(checked = true)),
            decoded.map(Block::type),
        )
    }

    @Test
    fun emptyDataCheckedAttributeUsesTipTapCheckedSemantics() {
        val decoded = HtmlSchema.decode(
            """
                <ul data-type="taskList">
                  <li data-type="taskItem" data-checked>
                    <div><p>Checked</p></div>
                  </li>
                </ul>
            """.trimIndent(),
            profile,
        )

        assertEquals(BlockType.Todo(checked = true), decoded.single().type)
    }

    @Test
    fun extendedTaskItemFallsBackToSafeListDecoding() {
        val html = """
            <ul data-type="taskList"><li data-type="taskItem" data-checked="false">Prefix<div><p>Main</p></div>Suffix</li></ul>
        """.trimIndent()

        val decoded = HtmlSchema.decodeWithReport(html, profile)

        assertFalse(HtmlSchema.encode(decoded.blocks, profile).contains("data-task-id"))
    }

    @Test
    fun todoBlocksEncodeAsVikunjaTaskListAndRoundTrip() {
        val blocks = listOf(
            Block.todo("Parent", checked = true),
            Block.todo("Child", checked = false).copy(
                attributes = BlockAttributes(indentationLevel = 1),
            ),
            Block.todo("Sibling", checked = false),
        )

        val encoded = HtmlSchema.encodeWithReport(blocks, profile)

        assertTrue(encoded.warnings.isEmpty())
        assertTrue(encoded.html.contains("""<ul data-type="taskList">"""))
        assertTrue(encoded.html.contains("""data-type="taskItem""""))
        assertTrue(encoded.html.contains("""data-checked="true""""))
        assertFalse(encoded.html.contains("""data-task-id=""""))
        assertTrue(encoded.html.contains("""checked="checked""""))

        val decoded = HtmlSchema.decode(encoded.html, profile)
        assertEquals(blocks.semanticShape(), decoded.semanticShape())
    }

    @Test
    fun reorderedTodoBlocksPreserveOrderAndNestingAcrossHtmlRoundTrip() {
        // Mirrors the document shape after dragging Sibling above Parent while
        // keeping Child nested below Parent.
        val reordered = listOf(
            Block.todo("Sibling", checked = false),
            Block.todo("Parent", checked = true),
            Block.todo("Child", checked = false).copy(
                attributes = BlockAttributes(indentationLevel = 1),
            ),
        )

        val encoded = HtmlSchema.encode(reordered, profile)
        val decoded = HtmlSchema.decode(encoded, profile)

        assertTrue(encoded.indexOf("Sibling") < encoded.indexOf("Parent"))
        assertTrue(encoded.indexOf("Parent") < encoded.indexOf("Child"))
        assertEquals(reordered.semanticShape(), decoded.semanticShape())
    }

    @Test
    fun safeTableHtmlIsPreservedAfterAttributeSanitization() {
        val html = "<table data-source=\"paste\"><tr><td>Alpha</td></tr></table>"

        val decoded = HtmlSchema.decodeWithReport(html, profile)

        assertTrue(decoded.warnings.isEmpty())
        val block = decoded.blocks.single()
        assertEquals("html.preserved", block.type.typeId)
        val content = assertIs<BlockContent.Custom>(block.content)
        assertEquals("html.preserved", content.typeId)
        val sanitized = "<table><tr><td>Alpha</td></tr></table>"
        assertEquals(sanitized, content.data["rawHtml"])
        assertEquals(sanitized, HtmlSchema.encode(decoded.blocks, profile))
    }

    @Test
    fun unsupportedInlineHtmlIsDroppedWithoutLosingSurroundingText() {
        val html = """<p>Before<img src="/files/1">after</p>"""

        val decoded = HtmlSchema.decodeWithReport(html, profile)

        assertTrue(decoded.warnings.any { it is HtmlDecodeWarning.UnknownTag })
        assertEquals("Beforeafter", decoded.blocks.single().text())
        assertFalse(HtmlSchema.encode(decoded.blocks, profile).contains("<img"))
    }

    @Test
    fun scriptAndStyleBodiesAreDropped() {
        val result = HtmlSchema.decodeWithReport(
            "<script>alert('x')</script><style>body{display:none}</style><p>Safe</p>",
            profile,
        )

        assertEquals(listOf("Safe"), result.blocks.map(Block::text))
        assertEquals(2, result.warnings.filterIsInstance<HtmlDecodeWarning.DroppedContent>().size)
        val encoded = HtmlSchema.encode(result.blocks, profile)
        assertFalse(encoded.contains("alert"))
        assertFalse(encoded.contains("display:none"))
    }

    @Test
    fun nestedUnsafeMarkupAndEventAttributesAreNotPreserved() {
        val decoded = HtmlSchema.decode(
            """
                <table onclick="alert(1)">
                  <tr><td><script>alert('x')</script><img src=x onerror=alert(2)>Safe</td></tr>
                </table>
            """.trimIndent(),
            profile,
        )

        val encoded = HtmlSchema.encode(decoded, profile)
        assertEquals("<table>\n  <tr><td>Safe</td></tr>\n</table>", encoded)
        assertFalse(encoded.contains("script", ignoreCase = true))
        assertFalse(encoded.contains("onerror", ignoreCase = true))
        assertFalse(encoded.contains("onclick", ignoreCase = true))
    }

    @Test
    fun unsafeLinkSchemesAreRemovedButSafeLinksSurvive() {
        val encoded = HtmlSchema.encode(
            HtmlSchema.decode(
                """<p><a href="javascript:alert(1)">bad</a> <a href="https://example.com">safe</a></p>""",
                profile,
            ),
            profile,
        )

        assertFalse(encoded.contains("javascript:", ignoreCase = true))
        assertTrue(encoded.contains("""href="https://example.com""""))
    }

    @Test
    fun supportSetAddsTodosWithoutOverclaimingOpaqueHtml() {
        assertTrue(profile.supportSet.supportsBlock(Block.todo("Supported")))
        assertTrue(profile.supportSet.supportsBlock(Block.paragraph("Supported")))

        val preserved = HtmlSchema.decode("<table><tr><td>x</td></tr></table>", profile).single()
        assertFalse(profile.supportSet.supportsBlock(preserved))
        assertSame(
            PreservedHtmlBlockRenderer,
            createVikunjaDescriptionEditorRegistry().getRenderer(preserved.type),
        )
    }
}

private fun Block.text(): String = (content as? BlockContent.Text)?.text.orEmpty()

private fun List<Block>.semanticShape(): List<Triple<BlockType, Int, BlockContent>> =
    map { block ->
        Triple(
            block.type,
            block.attributes.indentationLevel,
            block.content,
        )
    }
