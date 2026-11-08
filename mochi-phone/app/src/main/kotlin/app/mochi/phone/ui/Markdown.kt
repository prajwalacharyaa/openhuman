package app.mochi.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** One block of a reply. */
sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val text: String) : Block
    data class Item(val marker: String, val text: String) : Block
    data class Code(val text: String) : Block
}

/** Splits Markdown into blocks: paragraphs, headings, list items and fenced code. */
fun parseBlocks(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.Para(para.toString().trim())
        para.clear()
    }
    val lines = md.replace("\r\n", "\n").lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                out += Block.Code(code.toString().trimEnd())
            }
            t.isEmpty() -> flush()
            t.startsWith("#") -> { flush(); out += Block.Heading(t.trimStart('#').trim()) }
            Regex("""^[-*•]\s+""").containsMatchIn(t) -> {
                flush(); out += Block.Item("•", t.replaceFirst(Regex("""^[-*•]\s+"""), ""))
            }
            Regex("""^\d+[.)]\s+""").containsMatchIn(t) -> {
                flush()
                val n = t.takeWhile { it.isDigit() }
                out += Block.Item("$n.", t.replaceFirst(Regex("""^\d+[.)]\s+"""), ""))
            }
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(t) }
        }
        i++
    }
    flush()
    return out
}

/** Inline Markdown: **bold**, *italic*, `code`, [links](https://…). Only http(s) links become tappable. */
fun inline(text: String, c: MochiColors): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)
        when {
            rest.startsWith("**") && rest.indexOf("**", 2) > 2 -> {
                val end = rest.indexOf("**", 2)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(rest.substring(2, end), c)) }
                i += end + 2
            }
            rest.startsWith("`") && rest.indexOf('`', 1) > 1 -> {
                val end = rest.indexOf('`', 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = c.codeBg)) {
                    append(" " + rest.substring(1, end) + " ")
                }
                i += end + 1
            }
            (rest.startsWith("*") || rest.startsWith("_")) && rest.length > 2 && !rest[1].isWhitespace() &&
                rest.indexOf(rest[0], 1) > 1 -> {
                val end = rest.indexOf(rest[0], 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(rest.substring(1, end)) }
                i += end + 1
            }
            rest.startsWith("[") -> {
                val m = Regex("""^\[([^\]]+)]\((https?://[^)\s]+)\)""").find(rest)
                if (m != null) {
                    withLink(LinkAnnotation.Url(m.groupValues[2],
                        TextLinkStyles(SpanStyle(color = c.blueInk, textDecoration = TextDecoration.Underline)))) {
                        append(m.groupValues[1])
                    }
                    i += m.value.length
                } else {
                    append('['); i++
                }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

/** A Mochi reply rendered from Markdown, selectable. */
@Composable
fun MarkdownText(md: String, color: Color, modifier: Modifier = Modifier) {
    val c = LocalMochi.current
    val blocks = remember(md) { parseBlocks(md) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            for (b in blocks) when (b) {
                is Block.Para -> Text(inline(b.text, c), style = Type.body, color = color)
                is Block.Heading -> Text(inline(b.text, c), style = Type.title, color = color)
                is Block.Item -> Row {
                    Text(b.marker, style = Type.body, color = c.muted, modifier = Modifier.width(20.dp))
                    Text(inline(b.text, c), style = Type.body, color = color)
                }
                is Block.Code -> Text(b.text, style = Type.small.copy(fontFamily = FontFamily.Monospace), color = color,
                    modifier = Modifier.fillMaxWidth().background(c.codeBg, RoundedCornerShape(12.dp))
                        .horizontalScroll(rememberScrollState()).padding(Space.m))
            }
        }
    }
}
