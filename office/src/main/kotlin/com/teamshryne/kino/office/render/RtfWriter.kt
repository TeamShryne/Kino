package com.teamshryne.kino.office.render

import com.teamshryne.kino.office.ReportIssue
import com.teamshryne.kino.office.model.Block
import com.teamshryne.kino.office.model.CalloutBlock
import com.teamshryne.kino.office.model.CalloutKind
import com.teamshryne.kino.office.model.ChartBlock
import com.teamshryne.kino.office.model.CodeBlock
import com.teamshryne.kino.office.model.DividerBlock
import com.teamshryne.kino.office.model.DocumentSpec
import com.teamshryne.kino.office.model.HeadingBlock
import com.teamshryne.kino.office.model.ImageBlock
import com.teamshryne.kino.office.model.InlineMarkup
import com.teamshryne.kino.office.model.KeyValueBlock
import com.teamshryne.kino.office.model.ListBlock
import com.teamshryne.kino.office.model.PageBreakBlock
import com.teamshryne.kino.office.model.ParagraphBlock
import com.teamshryne.kino.office.model.QuoteBlock
import com.teamshryne.kino.office.model.SpacerBlock
import com.teamshryne.kino.office.model.StatBlock
import com.teamshryne.kino.office.model.TableBlock
import com.teamshryne.kino.office.model.TocBlock
import com.teamshryne.kino.office.theme.OfficeTheme

/**
 * Rich Text Format output.
 *
 * RTF still opens in Word, WordPad, TextEdit and most mail clients, so it is a
 * dependable fallback when a consumer cannot handle OOXML.
 */
object RtfWriter {

    fun write(document: DocumentSpec, theme: OfficeTheme, geometry: PageGeometry): String {
        val colors = ColorTable(theme)
        val body = StringBuilder()
        if (document.cover) {
            body.append(paragraph(document.title, font = FONT_SERIF, size = 56, bold = true, color = colors.accent, align = Align.LEFT, spaceAfter = 240))
            document.subtitle?.let { body.append(paragraph(it, font = FONT_SANS, size = 26, color = colors.inkSoft, spaceAfter = 240)) }
            body.append(paragraph(listOfNotNull(document.author, document.date).joinToString("  |  "), font = FONT_SANS, size = 18, color = colors.muted, spaceAfter = 240))
            body.append("\\page ")
        }
        if (document.toc) {
            body.append(paragraph("Contents", font = FONT_SERIF, size = 32, bold = true, spaceAfter = 120))
            document.blocks.filterIsInstance<HeadingBlock>().filter { it.level <= 3 }.forEach { heading ->
                val indent = (heading.level - 1) * 240
                body.append(paragraph(InlineMarkup.plain(heading.text), font = FONT_SANS, size = 20, color = colors.inkSoft, indentLeft = indent, spaceAfter = 40))
            }
            body.append("\\page ")
        }
        document.blocks.forEach { body.append(renderBlock(it, theme, colors)) }

        val header = document.header ?: document.title
        val footer = document.footer ?: ""
        return buildString {
            append("{\\rtf1\\ansi\\ansicpg1252\\deff0")
            append("{\\fonttbl")
            append("{\\f0\\fswiss\\fcharset0 ").append(theme.sansFont).append(";}")
            append("{\\f1\\froman\\fcharset0 ").append(theme.serifFont).append(";}")
            append("{\\f2\\fmodern\\fcharset0 ").append(theme.monoFont).append(";}")
            append("}")
            append(colors.table())
            append("\\paperw").append(twips(geometry.widthPt)).append("\\paperh").append(twips(geometry.heightPt))
            append("\\margl").append(twips(geometry.marginLeftPx / 96.0 * 72.0))
            append("\\margr").append(twips(geometry.marginRightPx / 96.0 * 72.0))
            append("\\margt").append(twips(geometry.marginTopPx / 96.0 * 72.0))
            append("\\margb").append(twips(geometry.marginBottomPx / 96.0 * 72.0))
            append("{\\header \\pard\\plain\\f0\\fs16\\cf").append(colors.muted).append(" ").append(escape(header)).append("\\par}")
            append("{\\footer \\pard\\plain\\f0\\fs16\\cf").append(colors.muted).append(" ")
            if (footer.isNotBlank()) append(escape(footer)).append("\\tab")
            if (document.pageNumbers) append("\\chpgn\\tab ")
            append("\\par}")
            append("{\\info{\\title ").append(escape(document.title)).append("}}")
            append(body)
            append("}")
        }
    }

    private fun renderBlock(block: Block, theme: OfficeTheme, colors: ColorTable): String = when (block) {
        is HeadingBlock -> paragraph(
            InlineMarkup.plain(block.text),
            font = FONT_SERIF,
            size = when (block.level) {
                1 -> 36
                2 -> 28
                3 -> 24
                else -> 22
            },
            bold = true,
            color = if (block.level <= 3) colors.accent else colors.ink,
            spaceBefore = 240,
            spaceAfter = 100,
        )

        is ParagraphBlock -> paragraph(InlineMarkup.plain(block.text), font = FONT_SANS, size = 21, color = colors.ink, spaceAfter = 120)

        is ListBlock -> buildString {
            block.items.forEachIndexed { index, item ->
                val marker = if (block.ordered) "${block.startAt + index}." else "\\'B7"
                append("\\pard\\plain\\fi-360\\li360\\f0\\fs21\\cf").append(colors.ink)
                if (block.ordered) append(" ").append(marker).append(" ")
                else append("{\\pntext\\f0\\'B7\\tab}")
                append(escape(InlineMarkup.plain(item))).append("\\par\n")
            }
        }

        is TableBlock -> table(block, colors)

        is KeyValueBlock -> buildString {
            block.items.forEach { item ->
                append("\\pard\\plain\\f0\\fs20\\cf").append(colors.muted).append(escape(item.label))
                append("\\tab \\cf").append(colors.ink).append("\\b ").append(escape(InlineMarkup.plain(item.value))).append("\\b0\\par\n")
            }
        }

        is CalloutBlock -> {
            val tone = when (block.kind) {
                CalloutKind.WARNING -> colors.warning
                CalloutKind.SUCCESS -> colors.positive
                CalloutKind.DANGER -> colors.negative
                CalloutKind.NOTE -> colors.inkSoft
                CalloutKind.INFO -> colors.accent
            }
            val label = block.title ?: block.kind.name.lowercase().replaceFirstChar { it.uppercase() }
            buildString {
                append("\\pard\\plain\\li360\\fi-360\\brdrs\\brdrw20\\brdrcf").append(tone)
                append("\\f0\\fs20\\cf").append(tone).append("\\b ").append(escape(label)).append("\\b0 ")
                append("\\cf").append(colors.ink).append(escape(InlineMarkup.plain(block.text))).append("\\par\n")
            }
        }

        is QuoteBlock -> buildString {
            append("\\pard\\plain\\li360\\brdrs\\brdrw15\\brdrcf").append(colors.accent)
            append("\\f1\\fs22\\i\\cf").append(colors.inkSoft).append(escape(InlineMarkup.plain(block.text))).append("\\i0\\par\n")
            block.attribution?.let {
                append("\\pard\\plain\\li360\\f0\\fs18\\cf").append(colors.muted).append(escape(it)).append("\\par\n")
            }
        }

        is CodeBlock -> buildString {
            append("\\pard\\plain\\f2\\fs18\\cf").append(colors.ink)
            block.text.split("\n").forEach { append(escape(it)).append("\\line\n") }
            append("\\par\n")
        }

        is ImageBlock -> paragraph(
            "[Image: ${block.caption ?: block.alt ?: block.source}]",
            font = FONT_SANS,
            size = 19,
            color = colors.muted,
            align = Align.CENTER,
        )

        is StatBlock -> buildString {
            block.items.forEach { stat ->
                append("\\pard\\plain\\f0\\fs18\\cf").append(colors.muted).append(escape(stat.label)).append("\\par\n")
                append("\\pard\\plain\\f1\\fs32\\b\\cf").append(colors.ink).append(escape(stat.value)).append("\\b0\\par\n")
            }
        }

        is ChartBlock -> buildString {
            block.title?.let { append(paragraph(it, font = FONT_SANS, size = 22, bold = true, color = colors.ink, spaceAfter = 60)) }
            block.series.forEach { series ->
                append("\\pard\\plain\\f0\\fs19\\cf").append(colors.inkSoft).append(escape(series.name)).append("\\par\n")
                val values = series.values.joinToString("   ") { value ->
                    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)
                }
                append("\\pard\\plain\\f2\\fs18\\cf").append(colors.muted).append(escape(values)).append("\\par\n")
            }
        }

        is DividerBlock -> "\\pard\\plain\\brdrb\\brdrs\\brdrw10\\brdrcf${colors.border}\\par\n"

        is PageBreakBlock -> "\\page "

        is SpacerBlock -> "\\pard\\plain\\f0\\fs${12 * block.size}\\par\n"

        is TocBlock -> ""
    }

    private fun table(table: TableBlock, colors: ColorTable): String = buildString {
        val columnCount = maxOf(table.columns.size, table.rows.maxOfOrNull { it.size } ?: 0)
        if (columnCount == 0) return@buildString
        val totalWidth = 9360
        val widths = IntArray(columnCount) { columnCount / 2 }
        widths[0] = totalWidth - (columnCount - 1) * (totalWidth / columnCount)
        var edge = 0
        val edges = IntArray(columnCount)
        for (index in 0 until columnCount) {
            edge += widths[index]
            edges[index] = edge
        }
        table.columns.forEachIndexed { index, column ->
            append("\\pard\\plain\\trowd\\trgaph80\\trleft0\\trqc")
            edges.forEach { append("\\cellx").append(it) }
            append("\\clcbpat").append(colors.accent)
            append(" ")
            append(cell(column.header, bold = true, color = colors.onAccent))
            append("\\cell ")
        }
        append("\\row\n")
        table.rows.forEach { row ->
            append("\\pard\\plain\\trowd\\trgaph80\\trleft0\\trqc")
            edges.forEach { append("\\cellx").append(it) }
            repeat(columnCount) { index ->
                val value = row.getOrNull(index).orEmpty()
                val align = table.effectiveAlignments.getOrElse(index) { com.teamshryne.kino.office.model.ColumnAlignment.LEFT }
                append(cell(value, align = align))
                append("\\cell ")
            }
            append("\\row\n")
        }
    }

    private fun cell(text: String, bold: Boolean = false, color: Int = 0, align: Align = Align.LEFT): String = buildString {
        append("\\pard\\plain\\intbl\\f0\\fs19\\cf").append(color)
        if (bold) append("\\b")
        when (align) {
            Align.LEFT -> append("\\ql")
            Align.CENTER -> append("\\qc")
            Align.RIGHT -> append("\\qr")
        }
        append(" ")
        append(escape(InlineMarkup.plain(text)))
        if (bold) append("\\b0")
    }

    private fun paragraph(
        text: String,
        font: Int = FONT_SANS,
        size: Int = 21,
        bold: Boolean = false,
        color: Int = 0,
        align: Align = Align.LEFT,
        spaceBefore: Int = 0,
        spaceAfter: Int = 0,
        indentLeft: Int = 0,
    ): String = buildString {
        append("\\pard\\plain\\f").append(font).append("\\fs").append(size)
        append("\\cf").append(color)
        if (bold) append("\\b")
        when (align) {
            Align.LEFT -> append("\\ql")
            Align.CENTER -> append("\\qc")
            Align.RIGHT -> append("\\qr")
        }
        if (spaceBefore > 0) append("\\sb").append(spaceBefore)
        if (spaceAfter > 0) append("\\sa").append(spaceAfter)
        if (indentLeft > 0) append("\\li").append(indentLeft)
        append(" ")
        append(escape(text))
        if (bold) append("\\b0")
        append("\\par\n")
    }

    private enum class Align { LEFT, CENTER, RIGHT }

    private class ColorTable(private val theme: OfficeTheme) {
        val ink: Int = 1
        val inkSoft: Int = 2
        val muted: Int = 3
        val accent: Int = 4
        val onAccent: Int = 5
        val border: Int = 6
        val positive: Int = 7
        val negative: Int = 8
        val warning: Int = 9

        fun table(): String {
            val entries = listOf(
                theme.ink,
                theme.inkSoft,
                theme.muted,
                theme.accent,
                Colors.onColor(theme.accent),
                theme.border,
                theme.positive,
                theme.negative,
                theme.warning,
            )
            return buildString {
                append("{\\colortbl;")
                entries.forEach { hex ->
                    val (r, g, b) = Colors.rgb(hex)
                    append("\\red").append(r).append("\\green").append(g).append("\\blue").append(b).append(";")
                }
                append("}")
            }
        }
    }

    private fun twips(points: Double): Int = (points * 20).toInt()

    private fun escape(value: String): String = buildString(value.length + 16) {
        value.forEach { c ->
            when {
                c == '\\' -> append("\\\\")
                c == '{' -> append("\\{")
                c == '}' -> append("\\}")
                c.code < 128 -> append(c)
                c.code <= 0xFF -> append("\\'").append("%02X".format(c.code))
                else -> {
                    // Non-latin-1 characters are written as unicode escapes.
                    val bytes = c.toString().toByteArray(Charsets.UTF_16LE)
                    var index = 0
                    while (index < bytes.size) {
                        val code = (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)
                        append("\\u").append(code).append("?")
                        index += 2
                    }
                }
            }
        }
    }

    private const val FONT_SANS = 0
    private const val FONT_SERIF = 1
    private const val FONT_MONO = 2
}
