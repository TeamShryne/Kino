package com.teamshryne.kino.office.render

import com.teamshryne.kino.office.model.Block
import com.teamshryne.kino.office.model.CalloutBlock
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
import com.teamshryne.kino.office.model.SheetCell
import com.teamshryne.kino.office.model.SheetSpec
import com.teamshryne.kino.office.model.SpacerBlock
import com.teamshryne.kino.office.model.StatBlock
import com.teamshryne.kino.office.model.TableBlock
import com.teamshryne.kino.office.model.TocBlock

/** Markdown output, including GFM tables. */
object MarkdownWriter {

    fun write(document: DocumentSpec): String = buildString {
        document.subtitle?.let { append("# ").append(escape(it)).append("\n\n") }
        val lines = mutableListOf<String>()
        document.blocks.forEach { block -> lines += renderBlock(block) }
        val body = lines.filter { it.isNotEmpty() }.joinToString("\n\n")
        append("# ").append(escape(document.title)).append("\n\n")
        document.author?.let { append("_").append(escape(it)).append("_") }
        document.date?.let { append("  \n_").append(escape(it)).append("_") }
        if (document.author != null || document.date != null) append("\n")
        append(body)
        if (!body.endsWith("\n")) append("\n")
    }

    private fun renderBlock(block: Block): List<String> = when (block) {
        is HeadingBlock -> listOf("${"#".repeat(block.level.coerceIn(1, 6))} ${escape(InlineMarkup.plain(block.text))}")
        is ParagraphBlock -> listOf(escape(InlineMarkup.plain(block.text)))
        is ListBlock -> block.items.mapIndexed { index, item ->
            val marker = if (block.ordered) "${block.startAt + index}." else "-"
            "$marker ${escape(InlineMarkup.plain(item))}"
        }
        is TableBlock -> buildList {
            if (block.columns.isNotEmpty()) {
                add("| " + block.columns.joinToString(" | ") { escape(it.header) } + " |")
                add("| " + block.columns.joinToString(" | ") { "---" } + " |")
            }
            block.rows.forEach { row ->
                add("| " + row.joinToString(" | ") { escape(InlineMarkup.plain(it)) } + " |")
            }
            block.caption?.let { add("_${escape(it)}_") }
        }
        is KeyValueBlock -> buildList {
            add("| | |")
            add("| --- | --- |")
            block.items.forEach { add("| ${escape(it.label)} | ${escape(InlineMarkup.plain(it.value))} |") }
        }
        is CalloutBlock -> listOf(
            buildString {
                append("> ")
                block.title?.let { append("**").append(escape(it)).append("** ") }
                append(escape(InlineMarkup.plain(block.text)))
            },
        )
        is QuoteBlock -> buildList {
            add("> " + escape(InlineMarkup.plain(block.text)))
            block.attribution?.let { add("> — " + escape(it)) }
        }
        is CodeBlock -> listOf("```" + (block.language ?: "") + "\n" + block.text.trimEnd() + "\n```")
        is ImageBlock -> listOf("![${escape(block.alt ?: "")}](${block.source})" + (block.caption?.let { "\n\n*${escape(it)}*" } ?: ""))
        is StatBlock -> buildList {
            add("| Metric | Value | Change |")
            add("| --- | --- | --- |")
            block.items.forEach { add("| ${escape(it.label)} | ${escape(it.value)} | ${escape(it.delta ?: "")} |") }
        }
        is ChartBlock -> buildList {
            block.title?.let { add("**${escape(it)}**") }
            add("| Category | " + block.series.joinToString(" | ") { escape(it.name) } + " |")
            add("| --- | " + block.series.joinToString(" | ") { "---" } + " |")
            val categories = block.categories.ifEmpty { List(block.series.firstOrNull()?.values?.size ?: 0) { "Item" } }
            categories.forEachIndexed { index, category ->
                val values = block.series.joinToString(" | ") { series ->
                    val value = series.values.getOrNull(index) ?: 0.0
                    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)
                }
                add("| ${escape(category)} | $values |")
            }
        }
        is DividerBlock -> listOf("---")
        is PageBreakBlock -> listOf("<!-- page break -->")
        is SpacerBlock -> emptyList()
        is TocBlock -> listOf("## Contents")
    }

    private fun escape(value: String): String = value.replace("|", "\\|").replace("\n", " ")
}

/** Plain text output for terminals and notes. */
object PlainTextWriter {

    fun write(document: DocumentSpec): String = buildString {
        append(document.title).append('\n')
        document.subtitle?.let { append(it).append('\n') }
        document.author?.let { append("By ").append(it).append('\n') }
        document.date?.let { append(it).append('\n') }
        append('\n')
        document.blocks.forEach { block -> append(renderBlock(block)).append('\n') }
    }

    private fun renderBlock(block: Block): String = when (block) {
        is HeadingBlock -> "\n${InlineMarkup.plain(block.text)}\n${"-".repeat(block.text.length.coerceAtMost(72))}"
        is ParagraphBlock -> InlineMarkup.plain(block.text)
        is ListBlock -> block.items.mapIndexed { index, item ->
            val marker = if (block.ordered) "${block.startAt + index}." else "-"
            "$marker ${InlineMarkup.plain(item)}"
        }.joinToString("\n")
        is TableBlock -> buildString {
            block.columns.forEach { append(renderCell(it.header)).append("\t") }
            append('\n')
            block.rows.forEach { row ->
                row.forEach { append(renderCell(InlineMarkup.plain(it))).append('\t') }
                append('\n')
            }
            block.caption?.let { append(it).append('\n') }
        }
        is KeyValueBlock -> block.items.joinToString("\n") { "${it.label}: ${InlineMarkup.plain(it.value)}" }
        is CalloutBlock -> buildString {
            block.title?.let { append(it).append(": ") }
            append(InlineMarkup.plain(block.text))
        }
        is QuoteBlock -> buildString {
            append("\" ").append(InlineMarkup.plain(block.text)).append("\"")
            block.attribution?.let { append(" — ").append(it) }
        }
        is CodeBlock -> block.text
        is ImageBlock -> block.caption ?: block.alt ?: "[image]"
        is StatBlock -> block.items.joinToString("  ") { "${it.label}: ${it.value}" }
        is ChartBlock -> buildString {
            block.title?.let { append(it).append('\n') }
            block.series.forEach { series ->
                append(series.name).append(": ")
                series.values.forEach { append(formatNumber(it)).append(' ') }
                append('\n')
            }
        }
        is DividerBlock -> "-".repeat(40)
        is PageBreakBlock -> "\u000C"
        is SpacerBlock -> ""
        is TocBlock -> "Contents"
    }

    private fun renderCell(value: String): String = value.replace('\t', ' ').replace('\n', ' ')

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)
}

/** CSV and TSV output from a sheet. */
object CsvWriter {

    fun write(sheet: SheetSpec, delimiter: Char = ','): String = buildString {
        if (sheet.columns.isNotEmpty()) {
            append(sheet.columns.joinToString(delimiter.toString()) { quote(it.header, delimiter) })
            append('\n')
        }
        sheet.rows.forEach { row ->
            append(row.joinToString(delimiter.toString()) { cell -> quote(cellValue(cell), delimiter) })
            append('\n')
        }
    }

    private fun cellValue(cell: SheetCell): String = when (cell) {
        is SheetCell.Text -> cell.value
        is SheetCell.Number -> formatNumber(cell.value)
        is SheetCell.Bool -> if (cell.value) "TRUE" else "FALSE"
        is SheetCell.Formula -> "=" + cell.expression
        is SheetCell.Blank -> ""
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun quote(value: String, delimiter: Char): String {
        val needsQuotes = value.contains(delimiter) || value.contains('"') || value.contains('\n') || value.contains('\r')
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
