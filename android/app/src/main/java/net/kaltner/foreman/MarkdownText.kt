package net.kaltner.foreman

import java.net.URI
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

internal sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class ListItem(val marker: String, val text: String) : MarkdownBlock
    data class Code(val language: String?, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
}

internal fun parseMarkdown(source: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val lines = source.replace("\r\n", "\n").split('\n')
    var index = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
            paragraph.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        if (line.trimStart().startsWith("```")) {
            flushParagraph()
            val fence = line.trimStart()
            val language = fence.removePrefix("```").trim().ifBlank { null }
            val code = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                code += lines[index]
                index++
            }
            if (index < lines.size) index++
            blocks += MarkdownBlock.Code(language, code.joinToString("\n"))
            continue
        }
        val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
        val bullet = Regex("^\\s*[-*+]\\s+(.+)$").matchEntire(line)
        val numbered = Regex("^\\s*(\\d+[.)])\\s+(.+)$").matchEntire(line)
        val quote = Regex("^\\s*>\\s?(.*)$").matchEntire(line)
        when {
            line.isBlank() -> flushParagraph()
            heading != null -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }
            bullet != null -> {
                flushParagraph()
                blocks += MarkdownBlock.ListItem("\u2022", bullet.groupValues[1])
            }
            numbered != null -> {
                flushParagraph()
                blocks += MarkdownBlock.ListItem(numbered.groupValues[1], numbered.groupValues[2])
            }
            quote != null -> {
                flushParagraph()
                blocks += MarkdownBlock.Quote(quote.groupValues[1])
            }
            else -> paragraph += line
        }
        index++
    }
    flushParagraph()
    return blocks
}

internal fun safeMarkdownUrl(raw: String): String? =
    runCatching {
        val uri = URI(raw)
        if (uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            uri.toASCIIString()
        } else {
            null
        }
    }.getOrNull()

private val InlineToken =
    Regex("\\[([^]\\n]+)]\\(([^)\\s]+)\\)|\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__|`([^`\\n]+)`|\\*([^*\\n]+)\\*|_([^_\\n]+)_")

@Composable
internal fun inlineMarkdown(text: String, color: Color): AnnotatedString =
    styledInlineMarkdown(
        text = text,
        color = color,
        linkColor = MaterialTheme.colorScheme.primary,
        codeColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

internal fun styledInlineMarkdown(
    text: String,
    color: Color,
    linkColor: Color,
    codeColor: Color,
): AnnotatedString =
    buildAnnotatedString {
        pushStyle(SpanStyle(color = color))
        var cursor = 0
        InlineToken.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            val groups = match.groupValues
            when {
                groups[1].isNotEmpty() -> {
                    val safeUrl = safeMarkdownUrl(groups[2])
                    if (safeUrl == null) {
                        append(groups[1])
                    } else {
                        pushLink(
                            LinkAnnotation.Url(
                                safeUrl,
                                TextLinkStyles(
                                    style =
                                        SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline,
                                        ),
                                ),
                            ),
                        )
                        append(groups[1])
                        pop()
                    }
                }
                groups[3].isNotEmpty() || groups[4].isNotEmpty() -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(groups[3].ifEmpty { groups[4] })
                    pop()
                }
                groups[5].isNotEmpty() -> {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor))
                    append(groups[5])
                    pop()
                }
                else -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(groups[6].ifEmpty { groups[7] })
                    pop()
                }
            }
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
        pop()
    }

@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        parseMarkdown(text).forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph ->
                    Text(
                        inlineMarkdown(block.text, contentColor),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                is MarkdownBlock.Heading ->
                    Text(
                        inlineMarkdown(block.text, contentColor),
                        style =
                            when (block.level) {
                                1 -> MaterialTheme.typography.headlineMedium
                                2 -> MaterialTheme.typography.headlineSmall
                                else -> MaterialTheme.typography.titleMedium
                            },
                        fontWeight = FontWeight.Bold,
                    )
                is MarkdownBlock.ListItem ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(block.marker, color = contentColor)
                        Text(
                            inlineMarkdown(block.text, contentColor),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                is MarkdownBlock.Quote ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            inlineMarkdown(block.text, MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                is MarkdownBlock.Code ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
                            block.language?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                block.text,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                softWrap = false,
                            )
                        }
                    }
            }
        }
    }
}
