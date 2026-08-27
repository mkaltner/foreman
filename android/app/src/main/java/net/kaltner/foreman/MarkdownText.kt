package net.kaltner.foreman

import java.net.URI
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
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
    data class TaskItem(val checked: Boolean, val text: String) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data class Code(val language: String?, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class AppDirective(
        val name: String,
        val attributes: Map<String, String>,
    ) : MarkdownBlock
}

internal data class WorkspaceFileTarget(
    val path: String,
    val line: Int? = null,
)

internal data class WorkspaceFile(
    val path: String,
    val content: String,
    val line: Int? = null,
)

private val displayedDirectives =
    setOf(
        "created-thread",
        "git-stage",
        "git-commit",
        "git-create-branch",
        "git-push",
        "git-create-pr",
    )

internal fun parseMarkdown(source: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val lines = source.replace("\r\n", "\n").split('\n')
    val quotePattern = Regex("^\\s*>\\s?(.*)$")
    var index = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
            paragraph.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        val tableHeaders = parseTableRow(line)
        val tableDelimiter = lines.getOrNull(index + 1)?.let(::parseTableRow)
        if (
            tableHeaders != null &&
            tableDelimiter?.size == tableHeaders.size &&
            tableDelimiter.all { Regex("^:?-{3,}:?$").matches(it) }
        ) {
            flushParagraph()
            index += 2
            val rows = mutableListOf<List<String>>()
            while (index < lines.size) {
                val row = parseTableRow(lines[index]) ?: break
                rows += row.take(tableHeaders.size) + List((tableHeaders.size - row.size).coerceAtLeast(0)) { "" }
                index++
            }
            blocks += MarkdownBlock.Table(tableHeaders, rows)
            continue
        }
        val directives = parseAppDirectiveLine(line)
        if (directives != null && directives.all { it.name in displayedDirectives }) {
            flushParagraph()
            blocks += directives
            index++
            continue
        }
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
        val task = Regex("^\\s*[-*+]\\s+\\[([ xX])]\\s+(.+)$").matchEntire(line)
        val bullet = Regex("^\\s*[-*+]\\s+(.+)$").matchEntire(line)
        val numbered = Regex("^\\s*(\\d+[.)])\\s+(.+)$").matchEntire(line)
        val quote = quotePattern.matchEntire(line)
        when {
            line.isBlank() -> flushParagraph()
            heading != null -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }
            task != null -> {
                flushParagraph()
                blocks += MarkdownBlock.TaskItem(task.groupValues[1].equals("x", ignoreCase = true), task.groupValues[2])
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
                val quotedLines = mutableListOf<String>()
                while (index < lines.size) {
                    val quotedLine = quotePattern.matchEntire(lines[index]) ?: break
                    quotedLines += quotedLine.groupValues[1]
                    index++
                }
                val quotedText = quotedLines.joinToString("\n")
                if (quotedText.isNotBlank()) blocks += MarkdownBlock.Quote(quotedText)
                continue
            }
            else -> paragraph += line
        }
        index++
    }
    flushParagraph()
    return blocks
}

private fun parseTableRow(line: String): List<String>? {
    val trimmed = line.trim()
    if ('|' !in trimmed || trimmed.isBlank()) return null
    val cells = trimmed.removePrefix("|").removeSuffix("|").split('|').map(String::trim)
    return cells.takeIf { it.size >= 2 }
}

private fun parseAppDirectiveLine(line: String): List<MarkdownBlock.AppDirective>? {
    val directives = mutableListOf<MarkdownBlock.AppDirective>()
    var cursor = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
    if (!line.startsWith("::", cursor)) return null

    while (cursor < line.length) {
        if (!line.startsWith("::", cursor)) return null
        cursor += 2
        val nameStart = cursor
        while (cursor < line.length && (line[cursor].isLetterOrDigit() || line[cursor] == '-')) cursor++
        val name = line.substring(nameStart, cursor)
        if (name.isEmpty() || line.getOrNull(cursor) != '{') return null
        cursor++

        val bodyStart = cursor
        var quoted = false
        var escaped = false
        while (cursor < line.length) {
            val character = line[cursor]
            when {
                escaped -> escaped = false
                character == '\\' && quoted -> escaped = true
                character == '"' -> quoted = !quoted
                character == '}' && !quoted -> break
            }
            cursor++
        }
        if (cursor >= line.length) return null
        directives += MarkdownBlock.AppDirective(name, parseDirectiveAttributes(line.substring(bodyStart, cursor)))
        cursor++
        while (cursor < line.length && line[cursor].isWhitespace()) cursor++
    }
    return directives
}

private fun parseDirectiveAttributes(source: String): Map<String, String> {
    val pattern = Regex("""([A-Za-z][\w-]*)=(?:"((?:\\.|[^"\\])*)"|([^\s]+))""")
    return pattern.findAll(source).associate { match ->
        val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
        match.groupValues[1] to value.replace("\\\"", "\"").replace("\\\\", "\\")
    }
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

internal fun workspaceFileTarget(raw: String): WorkspaceFileTarget? =
    runCatching {
        val uri = URI(raw)
        if (uri.scheme != null || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null) {
            return@runCatching null
        }
        val decoded = uri.path ?: return@runCatching null
        if (!decoded.startsWith('/') || decoded.any { it == '\u0000' || it == '?' || it == '#' }) return@runCatching null
        val location = Regex("^(.*?):(\\d+)(?::\\d+)?$").matchEntire(decoded)
        val path = location?.groupValues?.get(1) ?: decoded
        val line = location?.groupValues?.get(2)?.toIntOrNull()
        if (path.isEmpty() || (location != null && (line == null || line < 1))) return@runCatching null
        WorkspaceFileTarget(path, line)
    }.getOrNull()

private val InlineToken =
    Regex("\\[([^]\\n]+)]\\(([^)\\s]+)\\)|\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__|`([^`\\n]+)`|\\*([^*\\n]+)\\*|_([^_\\n]+)_|\\b(https?://[^\\s<>\"']+)", RegexOption.IGNORE_CASE)

internal fun trimTrailingUrlPunctuation(raw: String): String {
    var value = raw.trimEnd('.', ',', ';', ':', '!', '?')
    fun unbalanced(open: Char, close: Char): Boolean = value.count { it == close } > value.count { it == open }
    while (
        (value.endsWith(')') && unbalanced('(', ')')) ||
            (value.endsWith(']') && unbalanced('[', ']')) ||
            (value.endsWith('}') && unbalanced('{', '}'))
    ) value = value.dropLast(1)
    return value
}

@Composable
internal fun inlineMarkdown(text: String, color: Color): AnnotatedString =
    styledInlineMarkdown(
        text = text,
        color = color,
        linkColor = MaterialTheme.colorScheme.primary,
        codeColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

@Composable
internal fun inlineMarkdown(
    text: String,
    color: Color,
    onOpenWorkspaceFile: ((WorkspaceFileTarget) -> Unit)?,
): AnnotatedString =
    styledInlineMarkdown(
        text = text,
        color = color,
        linkColor = MaterialTheme.colorScheme.primary,
        codeColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onOpenWorkspaceFile = onOpenWorkspaceFile,
    )

internal fun styledInlineMarkdown(
    text: String,
    color: Color,
    linkColor: Color,
    codeColor: Color,
    onOpenWorkspaceFile: ((WorkspaceFileTarget) -> Unit)? = null,
): AnnotatedString =
    buildAnnotatedString {
        pushStyle(SpanStyle(color = color))
        var cursor = 0
        InlineToken.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            val groups = match.groupValues
            when {
                groups[1].isNotEmpty() -> {
                    val workspaceFile = workspaceFileTarget(groups[2])
                    val safeUrl = safeMarkdownUrl(groups[2])
                    if (workspaceFile != null && onOpenWorkspaceFile != null) {
                        pushLink(
                            LinkAnnotation.Clickable(
                                tag = groups[2],
                                styles =
                                    TextLinkStyles(
                                        style =
                                            SpanStyle(
                                                color = linkColor,
                                                textDecoration = TextDecoration.Underline,
                                            ),
                                    ),
                                linkInteractionListener = { onOpenWorkspaceFile(workspaceFile) },
                            ),
                        )
                        append(groups[1])
                        pop()
                    } else if (safeUrl == null) {
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
                groups[6].isNotEmpty() || groups[7].isNotEmpty() -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(groups[6].ifEmpty { groups[7] })
                    pop()
                }
                else -> {
                    val raw = groups[8]
                    val candidate = trimTrailingUrlPunctuation(raw)
                    val safeUrl = safeMarkdownUrl(candidate)
                    if (safeUrl == null) {
                        append(raw)
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
                        append(candidate)
                        pop()
                        append(raw.substring(candidate.length))
                    }
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
    onOpenWorkspaceFile: ((WorkspaceFileTarget) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        parseMarkdown(text).forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph ->
                    Text(
                        inlineMarkdown(block.text, contentColor, onOpenWorkspaceFile),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                is MarkdownBlock.Heading ->
                    Text(
                        inlineMarkdown(block.text, contentColor, onOpenWorkspaceFile),
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
                            inlineMarkdown(block.text, contentColor, onOpenWorkspaceFile),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                is MarkdownBlock.TaskItem ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(
                            checked = block.checked,
                            onCheckedChange = null,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            inlineMarkdown(block.text, contentColor, onOpenWorkspaceFile),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                is MarkdownBlock.Table -> {
                    val horizontal = rememberScrollState()
                    Column(Modifier.horizontalScroll(horizontal)) {
                        (listOf(block.headers) + block.rows).forEachIndexed { rowIndex, cells ->
                            Row {
                                block.headers.indices.forEach { columnIndex ->
                                    Surface(
                                        modifier = Modifier.width(180.dp),
                                        color =
                                            if (rowIndex == 0) MaterialTheme.colorScheme.surfaceVariant
                                            else Color.Transparent,
                                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                    ) {
                                        Text(
                                            inlineMarkdown(cells.getOrElse(columnIndex) { "" }, contentColor, onOpenWorkspaceFile),
                                            modifier = Modifier.padding(8.dp),
                                            fontWeight = if (rowIndex == 0) FontWeight.Bold else null,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                is MarkdownBlock.Quote ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(2.dp),
                                ),
                        )
                        Text(
                            inlineMarkdown(block.text, MaterialTheme.colorScheme.onSurfaceVariant, onOpenWorkspaceFile),
                            modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                            style = MaterialTheme.typography.bodyLarge,
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
                is MarkdownBlock.AppDirective -> {
                    val label =
                        when (block.name) {
                            "created-thread" -> "Task created"
                            "git-stage" -> "Changes staged"
                            "git-commit" -> "Changes committed"
                            "git-create-branch" -> "Branch created"
                            "git-push" -> "Branch pushed"
                            "git-create-pr" ->
                                if (block.attributes["isDraft"] == "true") "Draft PR opened" else "Pull request opened"
                            else -> "Action completed"
                        }
                    val detail =
                        block.attributes["branch"]
                            ?: block.attributes["cwd"]?.trimEnd('/')?.substringAfterLast('/')
                    val url = block.attributes["url"]?.let(::safeMarkdownUrl)
                    Surface(
                        modifier =
                            Modifier.fillMaxWidth().let { base ->
                                if (url == null) base else base.clickable { uriHandler.openUri(url) }
                            },
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                detail?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                                    )
                                }
                            }
                            if (url != null) Text("↗", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
