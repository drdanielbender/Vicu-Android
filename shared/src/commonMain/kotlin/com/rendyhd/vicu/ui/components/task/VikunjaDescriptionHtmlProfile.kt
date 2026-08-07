@file:OptIn(ExperimentalCascadeHtmlApi::class)

package com.rendyhd.vicu.ui.components.task

import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockAttributes
import io.github.linreal.cascade.editor.core.BlockContent
import io.github.linreal.cascade.editor.core.BlockId
import io.github.linreal.cascade.editor.core.BlockType
import io.github.linreal.cascade.editor.core.UnknownBlockType
import io.github.linreal.cascade.editor.htmlserialization.BlockEncoder
import io.github.linreal.cascade.editor.htmlserialization.BlockGroupEncoder
import io.github.linreal.cascade.editor.htmlserialization.BlockSeparator
import io.github.linreal.cascade.editor.htmlserialization.ExperimentalCascadeHtmlApi
import io.github.linreal.cascade.editor.htmlserialization.Html
import io.github.linreal.cascade.editor.htmlserialization.HtmlDecodeWarning
import io.github.linreal.cascade.editor.htmlserialization.HtmlEmit
import io.github.linreal.cascade.editor.htmlserialization.HtmlEncodeContext
import io.github.linreal.cascade.editor.htmlserialization.HtmlNodeView
import io.github.linreal.cascade.editor.htmlserialization.HtmlProfile
import io.github.linreal.cascade.editor.htmlserialization.HtmlProfileSupportSet
import io.github.linreal.cascade.editor.htmlserialization.InlineRoot
import io.github.linreal.cascade.editor.htmlserialization.TagDecodeContext
import io.github.linreal.cascade.editor.htmlserialization.TagDecodeResult
import io.github.linreal.cascade.editor.htmlserialization.TagDecoder
import io.github.linreal.cascade.editor.htmlserialization.UnknownTagPolicy

/**
 * HTML dialect used by Vikunja task descriptions
 *
 * Adds the two pieces
 * Vikunja needs:
 *
 * - legacy/plain descriptions are wrapped in a paragraph instead of being dropped;
 * - TipTap task-list markup maps to Cascade todo blocks and back.
 *
 * Safe table HTML is recursively sanitized and retained as an opaque block.
 * Other unknown elements are stripped or unwrapped by policy so executable markup
 * can never return to Vikunja through the custom raw-block encoder.
 */
internal object VikunjaDescriptionHtmlProfile {
    internal const val PRESERVED_HTML_TYPE_ID = "html.preserved"

    private val defaultProfile = HtmlProfile.Default
    private val defaultUlDecoder = requireNotNull(defaultProfile.tagDecoderFor("ul"))
    private val defaultAnchorDecoder = requireNotNull(defaultProfile.tagDecoderFor("a"))

    val Profile: HtmlProfile = defaultProfile
        .withParserPolicy(BlockSeparator.Newline)
        .withParserPolicy(InlineRoot.WrapInParagraph)
        .withTagDecoder(
            "ul",
            taskListDecoder(defaultUlDecoder),
        )
        .withTagDecoder("a", safeLinkDecoder(defaultAnchorDecoder))
        .withTagDecoder("table", sanitizedOpaqueBlockDecoder("table"))
        .withTagDecoder("script", droppedUnsafeTagDecoder("script"))
        .withTagDecoder("style", droppedUnsafeTagDecoder("style"))
        .withUnknownTagPolicy(UnknownTagPolicy.WarnAndStrip)
        .withCustomBlockEncoder(
            PRESERVED_HTML_TYPE_ID,
            BlockEncoder<BlockType> { _, _, content ->
                val rawHtml = (content as? BlockContent.Custom)
                    ?.data
                    ?.get("rawHtml") as? String
                rawHtml?.let(HtmlEmit::Raw) ?: HtmlEmit.Skip
            },
        )
        .withBlockGroupEncoder(
            name = "vikunjaTaskList",
            encoder = VikunjaTaskListEncoder,
        )
        .withSupportSet(vikunjaSupportSet())

    private fun droppedUnsafeTagDecoder(tag: String): TagDecoder = TagDecoder { context, _, _ ->
        context.warn(
            HtmlDecodeWarning.DroppedContent(
                reason = "Dropped unsafe <$tag> content",
                charOffset = context.charOffset,
            ),
        )
        TagDecodeResult.Drop
    }

    private fun safeLinkDecoder(defaultDecoder: TagDecoder): TagDecoder =
        TagDecoder { context, attributes, children ->
            val safeAttributes = buildMap {
                attributes["href"]
                    ?.takeIf(::isSafeDescriptionHref)
                    ?.let { put("href", it) }
                attributes["title"]?.let { put("title", it) }
            }
            defaultDecoder.decode(context, safeAttributes, children)
        }

    private fun sanitizedOpaqueBlockDecoder(tag: String): TagDecoder =
        TagDecoder { _, attributes, children ->
            TagDecodeResult.AsBlock(
                preservedHtmlBlock(
                    rawHtml = sanitizeOpaqueElement(tag, attributes, children),
                    tagName = tag,
                ),
            )
        }

    private fun taskListDecoder(defaultDecoder: TagDecoder): TagDecoder =
        TagDecoder { context, attributes, children ->
            if (!attributes.isTaskList()) {
                defaultDecoder.decode(context, attributes, children)
            } else {
                val blocks = decodeTaskList(
                    context = context,
                    children = children,
                    depth = BlockAttributes.MIN_INDENTATION_LEVEL,
                )
                if (blocks != null) {
                    TagDecodeResult.AsBlocks(blocks)
                } else {
                    defaultDecoder.decode(context, attributes, children)
                }
            }
        }

    private fun decodeTaskList(
        context: TagDecodeContext,
        children: List<HtmlNodeView>,
        depth: Int,
    ): List<Block>? {
        if (depth > BlockAttributes.MAX_INDENTATION_LEVEL) return null

        val blocks = mutableListOf<Block>()
        for (child in children) {
            when {
                child is HtmlNodeView.Text && child.text.isBlank() -> Unit
                child is HtmlNodeView.Element && child.tag == "li" -> {
                    blocks += decodeTaskItem(context, child, depth) ?: return null
                }
                else -> return null
            }
        }
        return blocks
    }

    private fun decodeTaskItem(
        context: TagDecodeContext,
        item: HtmlNodeView.Element,
        expectedDepth: Int,
    ): List<Block>? {
        val itemType = item.attrs["data-type"]
        if (itemType != null && !itemType.equals("taskItem", ignoreCase = true)) return null

        val explicitDepth = item.attrs["data-cascade-indent"]?.toIntOrNull()
        val depth = explicitDepth ?: expectedDepth
        if (depth !in BlockAttributes.MIN_INDENTATION_LEVEL..BlockAttributes.MAX_INDENTATION_LEVEL) {
            return null
        }

        val contentContainers = item.children
            .filterIsInstance<HtmlNodeView.Element>()
            .filter { it.tag == "div" }
        if (contentContainers.size > 1) return null
        val contentContainer = contentContainers.singleOrNull()
        if (contentContainer != null) {
            val hasUnexpectedSibling = item.children.any { node ->
                when {
                    node === contentContainer -> false
                    node is HtmlNodeView.Text && node.text.isBlank() -> false
                    node is HtmlNodeView.Element && node.tag == "label" -> false
                    else -> true
                }
            }
            if (hasUnexpectedSibling) return null
        }
        val contentAndNested = contentContainer?.children ?: item.children
        val nestedLists = contentAndNested
            .filterIsInstance<HtmlNodeView.Element>()
            .filter { it.tag == "ul" && it.attrs.isTaskList() }
        val contentNodes = contentAndNested.filterNot { node ->
            (node is HtmlNodeView.Text && node.text.isBlank()) ||
                (
                    node is HtmlNodeView.Element &&
                        (node.tag == "label" || (node.tag == "ul" && node.attrs.isTaskList()))
                )
        }

        val inlineNodes = when {
            contentNodes.isEmpty() -> emptyList()
            contentNodes.size == 1 &&
                contentNodes.single() is HtmlNodeView.Element &&
                (contentNodes.single() as HtmlNodeView.Element).tag == "p" -> {
                (contentNodes.single() as HtmlNodeView.Element).children
            }
            contentNodes.all { it.isSafeInlineNode() } -> contentNodes
            else -> return null
        }
        if (inlineNodes.any { !it.isSafeInlineNode() }) return null

        val inline = context.collectInlineText(
            children = inlineNodes,
            trimEdges = true,
            trimSingleTrailingNewline = true,
        )
        val checked = item.attrs["data-checked"]?.isCheckedValue()
            ?: item.children.any { it.containsCheckedInput() }

        val block = Block(
            id = BlockId.generate(),
            type = BlockType.Todo(checked = checked),
            content = BlockContent.Text(
                text = inline.text,
                spans = inline.spans,
            ),
            attributes = BlockAttributes(indentationLevel = depth),
        )

        val result = mutableListOf(block)
        for (nestedList in nestedLists) {
            result += decodeTaskList(
                context = context,
                children = nestedList.children,
                depth = depth + 1,
            ) ?: return null
        }
        return result
    }

    private fun Map<String, String>.isTaskList(): Boolean =
        this["data-type"]?.equals("taskList", ignoreCase = true) == true

    private fun String?.isCheckedValue(): Boolean = when (this?.lowercase()) {
        "", "true", "checked", "1" -> true
        else -> false
    }

    private fun HtmlNodeView.containsCheckedInput(): Boolean = when (this) {
        is HtmlNodeView.Text -> false
        is HtmlNodeView.Element -> {
            if (tag == "ul" && attrs.isTaskList()) {
                false
            } else {
                (tag == "input" && ("checked" in attrs || attrs["aria-checked"].isCheckedValue())) ||
                    children.any { it.containsCheckedInput() }
            }
        }
    }

    private fun HtmlNodeView.isSafeInlineNode(): Boolean = when (this) {
        is HtmlNodeView.Text -> true
        is HtmlNodeView.Element -> tag in SAFE_INLINE_TAGS && children.all { it.isSafeInlineNode() }
    }

    private fun sanitizeOpaqueElement(
        tag: String,
        attributes: Map<String, String>,
        children: List<HtmlNodeView>,
    ): String = buildString {
        appendSanitizedElement(tag, attributes, children)
    }

    private fun StringBuilder.appendSanitizedNode(node: HtmlNodeView) {
        when (node) {
            is HtmlNodeView.Text -> append(node.text.escapeHtmlText())
            is HtmlNodeView.Element -> {
                val tag = node.tag.lowercase()
                when {
                    tag in DROP_WITH_CONTENT_TAGS -> Unit
                    tag in SAFE_OPAQUE_TAGS -> appendSanitizedElement(tag, node.attrs, node.children)
                    else -> node.children.forEach { appendSanitizedNode(it) }
                }
            }
        }
    }

    private fun StringBuilder.appendSanitizedElement(
        tag: String,
        attributes: Map<String, String>,
        children: List<HtmlNodeView>,
    ) {
        append('<')
        append(tag)
        appendSafeAttributes(tag, attributes)
        append('>')
        if (tag !in VOID_TAGS) {
            children.forEach { appendSanitizedNode(it) }
            append("</")
            append(tag)
            append('>')
        }
    }

    private fun StringBuilder.appendSafeAttributes(
        tag: String,
        attributes: Map<String, String>,
    ) {
        fun attr(name: String, value: String = "") {
            append(' ')
            append(name)
            append("=\"")
            append(Html.escapeAttr(value))
            append('"')
        }

        when (tag) {
            "a" -> {
                attributes["href"]?.takeIf(::isSafeDescriptionHref)?.let { attr("href", it) }
                attributes["title"]?.let { attr("title", it) }
            }
            "ol" -> attributes["start"]?.toIntOrNull()?.let { attr("start", it.toString()) }
            "ul" -> if (attributes.isTaskList()) attr("data-type", "taskList")
            "li" -> {
                if (attributes["data-type"]?.equals("taskItem", ignoreCase = true) == true) {
                    attr("data-type", "taskItem")
                    attr("data-checked", attributes["data-checked"].isCheckedValue().toString())
                }
            }
            "label" -> if (attributes["contenteditable"] == "false") attr("contenteditable", "false")
            "input" -> if (attributes["type"]?.equals("checkbox", ignoreCase = true) == true) {
                attr("type", "checkbox")
                if ("checked" in attributes) attr("checked", "checked")
                attr("disabled")
            }
            "th", "td" -> {
                attributes["colspan"]?.toIntOrNull()?.takeIf { it in 1..100 }?.let {
                    attr("colspan", it.toString())
                }
                attributes["rowspan"]?.toIntOrNull()?.takeIf { it in 1..100 }?.let {
                    attr("rowspan", it.toString())
                }
            }
        }
    }

    private fun String.escapeHtmlText(): String = buildString(length) {
        for (char in this@escapeHtmlText) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    private fun preservedHtmlBlock(
        rawHtml: String,
        tagName: String,
    ): Block = Block(
        id = BlockId.generate(),
        type = UnknownBlockType(
            typeId = PRESERVED_HTML_TYPE_ID,
            rawTypeJson = """{"id":"$PRESERVED_HTML_TYPE_ID"}""",
        ),
        content = BlockContent.Custom(
            typeId = PRESERVED_HTML_TYPE_ID,
            data = mapOf(
                "tagName" to tagName,
                "rawHtml" to rawHtml,
            ),
        ),
    )

    private val SAFE_INLINE_TAGS = setOf(
        "a",
        "b",
        "br",
        "code",
        "del",
        "em",
        "i",
        "mark",
        "s",
        "strike",
        "strong",
        "u",
    )

    private val SAFE_OPAQUE_TAGS = setOf(
        "table",
        "thead",
        "tbody",
        "tfoot",
        "tr",
        "th",
        "td",
        "p",
        "br",
        "strong",
        "b",
        "em",
        "i",
        "s",
        "strike",
        "del",
        "u",
        "a",
        "code",
        "pre",
        "ul",
        "ol",
        "li",
        "blockquote",
        "span",
        "div",
        "label",
        "input",
    )

    private val DROP_WITH_CONTENT_TAGS = setOf(
        "script",
        "style",
        "iframe",
        "object",
        "embed",
        "svg",
        "math",
        "form",
        "template",
    )

    private val VOID_TAGS = setOf("br", "input")

    private fun vikunjaSupportSet(): HtmlProfileSupportSet = HtmlProfileSupportSet(
        supportsBlockPredicate = { block ->
            HtmlProfileSupportSet.Default.supportsBlock(block) ||
                (
                    block.type is BlockType.Todo &&
                        block.attributes.indentationLevel in
                        BlockAttributes.MIN_INDENTATION_LEVEL..BlockAttributes.MAX_INDENTATION_LEVEL
                )
        },
        supportsSpanPredicate = HtmlProfileSupportSet.Default::supportsSpan,
    )

    private object VikunjaTaskListEncoder : BlockGroupEncoder {
        override fun groupKey(block: Block): Any? =
            if (block.type is BlockType.Todo) "vikunjaTaskList" else null

        override fun encodeGroup(
            ctx: HtmlEncodeContext,
            blocks: List<Block>,
        ): HtmlEmit {
            if (blocks.isEmpty()) return HtmlEmit.Skip
            val roots = blocks.toTaskListForest()
            return HtmlEmit.Raw(
                buildString {
                    appendTaskList(
                        context = ctx,
                        nodes = roots,
                    )
                },
            )
        }

        private fun List<Block>.toTaskListForest(): List<TaskListNode> {
            val roots = mutableListOf<TaskListNode>()
            val stack = mutableListOf<TaskListNode>()
            for (block in this) {
                val depth = block.attributes.indentationLevel
                while (stack.isNotEmpty() && stack.last().depth >= depth) {
                    stack.removeAt(stack.lastIndex)
                }
                val node = TaskListNode(block = block, depth = depth)
                stack.lastOrNull()?.children?.add(node) ?: roots.add(node)
                stack.add(node)
            }
            return roots
        }

        private fun StringBuilder.appendTaskList(
            context: HtmlEncodeContext,
            nodes: List<TaskListNode>,
        ) {
            append("<ul data-type=\"taskList\">")
            for (node in nodes) {
                val todo = node.block.type as BlockType.Todo
                append("<li data-type=\"taskItem\" data-checked=\"${todo.checked}\"")
                append('>')
                append("<label contenteditable=\"false\"><input type=\"checkbox\"")
                if (todo.checked) append(" checked=\"checked\"")
                append("><span></span></label><div><p>")
                append(context.encodeInline(node.block))
                append("</p>")
                if (node.children.isNotEmpty()) {
                    appendTaskList(
                        context = context,
                        nodes = node.children,
                    )
                }
                append("</div></li>")
            }
            append("</ul>")
        }
    }

    private data class TaskListNode(
        val block: Block,
        val depth: Int,
        val children: MutableList<TaskListNode> = mutableListOf(),
    )
}
