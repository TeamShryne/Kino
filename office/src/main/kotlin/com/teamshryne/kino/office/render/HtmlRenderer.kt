package com.teamshryne.kino.office.render

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
 * Renders a document specification into a complete HTML page.
 *
 * The model never writes CSS: it describes structure and content, and this
 * renderer applies the theme's type scale, spacing, and colour system. That is
 * what keeps every export looking deliberate instead of "whatever the model
 * typed into a `<div>`".
 */
class HtmlRenderer(
    private val theme: OfficeTheme,
    private val geometry: PageGeometry,
    private val printMode: Boolean,
) {

    fun render(document: DocumentSpec): String {
        val counters = IntArray(5)
        val outline = collectOutline(document.blocks, counters)
        val body = StringBuilder()
        if (document.cover) body.append(cover(document))
        if (document.toc) body.append(contents(outline, document))
        body.append("<main class=\"kino-content\">")
        val tocBlockIndex = document.blocks.indexOfFirst { it is TocBlock }
        val blocks = document.blocks.filterIndexed { index, block -> !(block is TocBlock && index == tocBlockIndex) }
        blocks.forEach { block ->
            if (block is TocBlock) body.append(contents(outline, document))
            else body.append(renderBlock(block, counters))
        }
        body.append("</main>")
        return page(document, body.toString())
    }

    /** Renders only the body content, used for EPUB chapters and DOCX fallbacks. */
    fun renderBlocks(blocks: List<Block>): String {
        val counters = IntArray(5)
        return buildString {
            blocks.forEach { append(renderBlock(it, counters)) }
        }
    }

    /* ------------------------------ structure ------------------------------ */

    private data class OutlineEntry(val level: Int, val number: String, val text: String)

    private fun collectOutline(blocks: List<Block>, counters: IntArray): List<OutlineEntry> {
        val entries = mutableListOf<OutlineEntry>()
        blocks.filterIsInstance<HeadingBlock>().forEach { heading ->
            if (heading.numbered) {
                counters[heading.level]++
                for (level in heading.level + 1..4) counters[level] = 0
                val number = (1..heading.level).joinToString(".") { counters[it].toString() }
                entries += OutlineEntry(heading.level, number, InlineMarkup.plain(heading.text))
            } else {
                entries += OutlineEntry(heading.level, "", InlineMarkup.plain(heading.text))
            }
        }
        return entries
    }

    private fun cover(document: DocumentSpec): String = buildString {
        append("<section class=\"kino-cover\">")
        append("<div class=\"kino-cover-eyebrow\">")
        document.author?.let { append(escapeHtml(it)).append(" &#183; ") }
        document.date?.let { append(escapeHtml(it)) }
        append("</div>")
        append("<h1 class=\"kino-cover-title\">").append(escapeHtml(document.title)).append("</h1>")
        document.subtitle?.let { append("<p class=\"kino-cover-subtitle\">").append(escapeHtml(it)).append("</p>") }
        append("<div class=\"kino-cover-rule\"></div>")
        append("</section>")
        append("<div class=\"kino-break\"></div>")
    }

    private fun contents(outline: List<OutlineEntry>, document: DocumentSpec): String {
        if (outline.isEmpty()) return ""
        return buildString {
            append("<section class=\"kino-toc\">")
            append("<h2 class=\"kino-toc-title\">Contents</h2>")
            append("<ul class=\"kino-toc-list\">")
            outline.filter { it.level <= 3 }.forEach { entry ->
                append("<li class=\"kino-toc-item kino-toc-level-${entry.level}\">")
                append("<span class=\"kino-toc-number\">").append(escapeHtml(entry.number)).append("</span>")
                append("<span class=\"kino-toc-text\">").append(escapeHtml(entry.text)).append("</span>")
                append("</li>")
            }
            append("</ul>")
            append("</section>")
            append("<div class=\"kino-break\"></div>")
        }
    }

    private fun renderBlock(block: Block, counters: IntArray): String = when (block) {
        is HeadingBlock -> {
            if (block.numbered) {
                counters[block.level]++
                for (level in block.level + 1..4) counters[level] = 0
            }
            val number = if (block.numbered) (1..block.level).joinToString(".") { counters[it].toString() } + " " else ""
            "<h${block.level} class=\"kino-h kino-h${block.level}\"><span class=\"kino-h-number\">$number</span>${spans(block.text)}</h${block.level}>\n"
        }

        is ParagraphBlock -> "<p class=\"kino-p\">${spans(block.text)}</p>\n"

        is ListBlock -> {
            val tag = if (block.ordered) "ol" else "ul"
            val start = if (block.ordered && block.startAt != 1) " start=\"${block.startAt}\"" else ""
            buildString {
                append("<$tag class=\"kino-list\"$start>")
                block.items.forEach { append("<li>").append(spans(it)).append("</li>") }
                append("</$tag>\n")
            }
        }

        is TableBlock -> table(block)

        is KeyValueBlock -> buildString {
            append("<div class=\"kino-kv kino-kv-${block.columns}\">")
            block.items.forEach { item ->
                append("<div class=\"kino-kv-item\">")
                append("<span class=\"kino-kv-label\">").append(escapeHtml(item.label)).append("</span>")
                append("<span class=\"kino-kv-value\">").append(spans(item.value)).append("</span>")
                item.hint?.let { append("<span class=\"kino-kv-hint\">").append(escapeHtml(it)).append("</span>") }
                append("</div>")
            }
            append("</div>\n")
        }

        is CalloutBlock -> buildString {
            append("<aside class=\"kino-callout kino-callout-${block.kind.name.lowercase()}\">")
            block.title?.let { append("<div class=\"kino-callout-title\">").append(escapeHtml(it)).append("</div>") }
            append("<div class=\"kino-callout-body\">").append(spans(block.text)).append("</div>")
            append("</aside>\n")
        }

        is QuoteBlock -> buildString {
            append("<blockquote class=\"kino-quote\">")
            append("<p>").append(spans(block.text)).append("</p>")
            block.attribution?.let { append("<cite>").append(escapeHtml(it)).append("</cite>") }
            append("</blockquote>\n")
        }

        is CodeBlock -> buildString {
            append("<pre class=\"kino-code\"><code>")
            append(escapeHtml(block.text))
            append("</code></pre>\n")
        }

        is ImageBlock -> buildString {
            append("<figure class=\"kino-figure\">")
            append("<img src=\"").append(escapeHtml(block.source)).append("\" alt=\"").append(escapeHtml(block.alt ?: "")).append("\"")
            append(" style=\"max-width:${block.maxWidthPercent}%\" />")
            block.caption?.let { append("<figcaption>").append(escapeHtml(it)).append("</figcaption>") }
            append("</figure>\n")
        }

        is StatBlock -> buildString {
            append("<div class=\"kino-stats\">")
            block.items.forEach { stat ->
                append("<div class=\"kino-stat\">")
                append("<div class=\"kino-stat-label\">").append(escapeHtml(stat.label)).append("</div>")
                append("<div class=\"kino-stat-value\">").append(escapeHtml(stat.value)).append("</div>")
                stat.delta?.let {
                    val tone = when (stat.positive) {
                        true -> "up"
                        false -> "down"
                        null -> "flat"
                    }
                    append("<div class=\"kino-stat-delta kino-stat-$tone\">").append(escapeHtml(it)).append("</div>")
                }
                append("</div>")
            }
            append("</div>\n")
        }

        is ChartBlock -> buildString {
            append("<figure class=\"kino-chart-wrap\">")
            append(SvgChart.render(block, theme))
            block.title?.let { append("<figcaption>").append(escapeHtml(it)).append("</figcaption>") }
            append("</figure>\n")
        }

        is DividerBlock -> "<hr class=\"kino-divider\" />\n"

        is PageBreakBlock -> "<div class=\"kino-break\"></div>\n"

        is SpacerBlock -> "<div class=\"kino-spacer kino-spacer-${block.size}\"></div>\n"

        is TocBlock -> ""
    }

    private fun table(table: TableBlock): String = buildString {
        append("<table class=\"kino-table${if (table.zebra) " kino-zebra" else ""}\">")
        if (table.columns.isNotEmpty()) {
            append("<thead><tr>")
            table.columns.forEachIndexed { index, column ->
                val align = table.effectiveAlignments.getOrElse(index) { com.teamshryne.kino.office.model.ColumnAlignment.LEFT }
                append("<th class=\"kino-align-${align.name.lowercase()}\">").append(escapeHtml(column.header)).append("</th>")
            }
            append("</tr></thead>")
        }
        append("<tbody>")
        table.rows.forEach { row ->
            append("<tr>")
            row.forEachIndexed { index, cell ->
                val align = table.effectiveAlignments.getOrElse(index) { com.teamshryne.kino.office.model.ColumnAlignment.LEFT }
                append("<td class=\"kino-align-${align.name.lowercase()}\">").append(spans(cell)).append("</td>")
            }
            append("</tr>")
        }
        append("</tbody></table>")
        table.caption?.let { append("<p class=\"kino-table-caption\">").append(escapeHtml(it)).append("</p>") }
        append("\n")
    }

    private fun spans(text: String): String = InlineMarkup.parse(text).joinToString("") { span ->
        val body = if (span.code) "<code>${escapeHtml(span.text)}</code>" else escapeHtml(span.text)
        var result = body
        if (span.bold) result = "<strong>$result</strong>"
        if (span.italic) result = "<em>$result</em>"
        if (span.strike) result = "<s>$result</s>"
        if (span.link != null) result = "<a href=\"${escapeHtml(span.link)}\">$result</a>"
        result
    }

    /* --------------------------------- page --------------------------------- */

    private fun page(document: DocumentSpec, body: String): String {
        val sizeName = document.pageSize.id
        val orientation = if (document.orientation == com.teamshryne.kino.office.model.Orientation.LANDSCAPE) "landscape" else "portrait"
        return buildString {
            append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>").append(escapeHtml(document.title)).append("</title>")
            append("<style>").append(styles(sizeName, orientation)).append("</style>")
            append("</head><body class=\"kino-body\">")
            append(body)
            append("</body></html>")
        }
    }

    private fun styles(sizeName: String, orientation: String): String {
        val t = theme
        val contentPadding = if (printMode) {
            // The renderer owns the top and bottom margins; the page keeps the
            // side margins in CSS so the WebView viewport matches the paper.
            "0 ${geometry.marginRightPx}px 0 ${geometry.marginLeftPx}px"
        } else {
            "${geometry.marginTopPx}px ${geometry.marginLeftPx}px"
        }
        val maxWidth = if (printMode) "none" else "${geometry.contentWidthPx}px"
        val coverHeight = if (printMode) "${geometry.contentHeightPx}px" else "min(78vh, ${geometry.contentHeightPx}px)"
        return """
        :root {
          --accent: ${t.accent};
          --accent-soft: ${t.accentSoft};
          --ink: ${t.ink};
          --ink-soft: ${t.inkSoft};
          --muted: ${t.muted};
          --surface: ${t.surface};
          --surface-alt: ${t.surfaceAlt};
          --border: ${t.border};
          --bg: ${t.background};
          --positive: ${t.positive};
          --negative: ${t.negative};
          --warning: ${t.warning};
          --sans: ${t.sansStack};
          --serif: ${t.serifStack};
          --mono: ${t.monoStack};
        }
        * { box-sizing: border-box; }
        html, body { margin: 0; padding: 0; }
        body {
          background: ${if (printMode) t.background else t.surface};
          color: ${t.ink};
          font-family: var(--sans);
          font-size: 10.5pt;
          line-height: 1.58;
          -webkit-print-color-adjust: exact;
          print-color-adjust: exact;
          -webkit-font-smoothing: antialiased;
        }
        .kino-content {
          max-width: ${maxWidth};
          margin: 0 auto;
          padding: ${contentPadding};
          background: ${t.background};
        }
        .kino-p { margin: 0 0 11px; text-align: justify; hyphens: auto; }
        .kino-h { font-family: var(--serif); font-weight: 600; color: ${t.ink}; letter-spacing: -0.01em; }
        .kino-h1 { font-size: ${(21 * t.headingScale).format1()}pt; line-height: 1.2; margin: 26px 0 12px; }
        .kino-h2 { font-size: ${(15.5 * t.headingScale).format1()}pt; line-height: 1.25; margin: 22px 0 10px; padding-bottom: 5px; border-bottom: ${t.ruleWeight}px solid ${t.border}; }
        .kino-h3 { font-size: ${(12.5 * t.headingScale).format1()}pt; line-height: 1.3; margin: 18px 0 8px; color: ${t.accent}; }
        .kino-h4 { font-family: var(--sans); font-size: 9.5pt; font-weight: 700; letter-spacing: 0.09em; text-transform: uppercase; color: ${t.inkSoft}; margin: 16px 0 6px; }
        .kino-h-number { color: ${t.accent}; }
        .kino-list { margin: 0 0 13px; padding-left: 20px; }
        .kino-list li { margin-bottom: 5px; }
        .kino-list li::marker { color: ${t.accent}; font-weight: 700; }
        .kino-table { width: 100%; border-collapse: collapse; margin: 4px 0 8px; font-size: 9.8pt; }
        .kino-table th {
          background: ${t.accent}; color: #FFFFFF; text-align: left; font-weight: 600;
          padding: 7px 9px; border: 1px solid ${t.accent}; font-size: 9.3pt; letter-spacing: 0.02em;
        }
        .kino-table td { padding: 6px 9px; border: 1px solid ${t.border}; vertical-align: top; }
        .kino-zebra tbody tr:nth-child(even) td { background: ${t.surface}; }
        .kino-align-center { text-align: center; }
        .kino-align-right { text-align: right; }
        .kino-table-caption { margin: 2px 0 14px; font-size: 8.8pt; color: ${t.muted}; font-style: italic; }
        .kino-kv { display: flex; flex-wrap: wrap; gap: 10px; margin: 4px 0 14px; }
        .kino-kv-item {
          flex: 1 1 ${if (t.id == "midnight") "45%" else "46%"}; min-width: 150px;
          background: ${t.surface}; border: 1px solid ${t.border}; border-left: 3px solid ${t.accent};
          padding: 9px 12px; border-radius: 3px;
        }
        .kino-kv-label { display: block; font-size: 8.6pt; text-transform: uppercase; letter-spacing: 0.07em; color: ${t.muted}; margin-bottom: 3px; }
        .kino-kv-value { display: block; font-size: 11pt; font-weight: 600; color: ${t.ink}; }
        .kino-kv-hint { display: block; font-size: 8.8pt; color: ${t.muted}; margin-top: 2px; }
        .kino-callout { margin: 4px 0 14px; padding: 10px 14px; border-radius: 3px; border-left: 4px solid ${t.accent}; background: ${t.accentSoft}; }
        .kino-callout-title { font-weight: 700; font-size: 9.6pt; margin-bottom: 3px; color: ${t.accent}; }
        .kino-callout-body { font-size: 10pt; }
        .kino-callout-warning { border-left-color: ${t.warning}; background: ${Colors.lighten(t.warning, 0.88)}; }
        .kino-callout-warning .kino-callout-title { color: ${t.warning}; }
        .kino-callout-success { border-left-color: ${t.positive}; background: ${Colors.lighten(t.positive, 0.88)}; }
        .kino-callout-success .kino-callout-title { color: ${t.positive}; }
        .kino-callout-danger { border-left-color: ${t.negative}; background: ${Colors.lighten(t.negative, 0.9)}; }
        .kino-callout-danger .kino-callout-title { color: ${t.negative}; }
        .kino-callout-note { border-left-color: ${t.inkSoft}; background: ${t.surface}; }
        .kino-callout-note .kino-callout-title { color: ${t.inkSoft}; }
        .kino-quote { margin: 6px 0 14px; padding: 4px 0 4px 16px; border-left: 3px solid ${t.accent}; color: ${t.inkSoft}; }
        .kino-quote p { margin: 0 0 6px; font-family: var(--serif); font-size: 11.5pt; font-style: italic; }
        .kino-quote cite { font-size: 9pt; color: ${t.muted}; font-style: normal; }
        .kino-code {
          margin: 4px 0 14px; padding: 11px 13px; background: ${t.surfaceAlt}; border: 1px solid ${t.border};
          border-radius: 3px; font-family: var(--mono); font-size: 8.9pt; line-height: 1.5;
          white-space: pre-wrap; overflow-wrap: anywhere;
        }
        .kino-code code { font-family: inherit; }
        .kino-figure { margin: 6px 0 14px; text-align: center; }
        .kino-figure img { max-width: 100%; height: auto; border-radius: 3px; }
        .kino-figure figcaption { margin-top: 6px; font-size: 8.8pt; color: ${t.muted}; font-style: italic; }
        .kino-chart-wrap { margin: 6px 0 16px; }
        .kino-chart { width: 100%; height: auto; display: block; }
        .kino-stats { display: flex; flex-wrap: wrap; gap: 10px; margin: 6px 0 16px; }
        .kino-stat { flex: 1 1 28%; min-width: 120px; background: ${t.surface}; border: 1px solid ${t.border}; border-top: 3px solid ${t.accent}; padding: 11px 13px; border-radius: 3px; }
        .kino-stat-label { font-size: 8.6pt; text-transform: uppercase; letter-spacing: 0.07em; color: ${t.muted}; }
        .kino-stat-value { font-size: 19pt; font-weight: 700; font-family: var(--serif); color: ${t.ink}; line-height: 1.2; margin: 2px 0; }
        .kino-stat-delta { font-size: 9pt; font-weight: 600; }
        .kino-stat-up { color: ${t.positive}; }
        .kino-stat-down { color: ${t.negative}; }
        .kino-stat-flat { color: ${t.muted}; }
        .kino-divider { border: none; border-top: ${t.ruleWeight}px solid ${t.border}; margin: 18px 0; }
        .kino-spacer { height: 10px; }
        .kino-spacer-2 { height: 20px; }
        .kino-spacer-3 { height: 34px; }
        .kino-break { height: 0; }
        .kino-cover {
          min-height: ${coverHeight}; display: flex; flex-direction: column; justify-content: center;
          padding: ${if (printMode) "0 ${geometry.marginRightPx}px 0 ${geometry.marginLeftPx}px" else "${geometry.marginTopPx}px ${geometry.marginLeftPx}px"};
          max-width: ${maxWidth}; margin: 0 auto; background: ${t.background};
        }
        .kino-cover-eyebrow { font-size: 9pt; text-transform: uppercase; letter-spacing: 0.18em; color: ${t.accent}; font-weight: 700; margin-bottom: 14px; }
        .kino-cover-title { font-family: var(--serif); font-size: ${(30 * t.headingScale).format1()}pt; line-height: 1.12; margin: 0 0 12px; color: ${t.ink}; max-width: 90%; }
        .kino-cover-subtitle { font-size: 13pt; color: ${t.inkSoft}; margin: 0 0 22px; max-width: 80%; font-family: var(--serif); }
        .kino-cover-rule { width: 78px; height: 4px; background: ${t.accent}; border-radius: 2px; }
        .kino-toc { max-width: ${maxWidth}; margin: 0 auto; padding: ${contentPadding}; background: ${t.background}; }
        .kino-toc-title { font-family: var(--serif); font-size: 17pt; margin: 0 0 14px; padding: 0; border: none; }
        .kino-toc-list { list-style: none; margin: 0; padding: 0; }
        .kino-toc-item { display: flex; gap: 10px; align-items: baseline; padding: 4px 0; border-bottom: 1px dotted ${t.border}; font-size: 10pt; }
        .kino-toc-level-2 { padding-left: 18px; font-size: 9.6pt; color: ${t.inkSoft}; }
        .kino-toc-level-3 { padding-left: 36px; font-size: 9.2pt; color: ${t.muted}; }
        .kino-toc-number { color: ${t.accent}; font-weight: 700; min-width: 22px; font-variant-numeric: tabular-nums; }
        .kino-toc-text { flex: 1; }
        a { color: ${t.accent}; text-decoration: none; border-bottom: 1px solid ${Colors.withAlpha(t.accent, 0.35)}; }
        code { font-family: var(--mono); font-size: 0.92em; background: ${t.surfaceAlt}; padding: 1px 4px; border-radius: 3px; }
        @page { size: ${sizeName} ${orientation}; margin: 0; }
        @media print {
          body { background: ${t.background}; }
          .kino-content, .kino-cover, .kino-toc { max-width: none; }
        }
        """.trimIndent()
    }

    companion object {
        fun escapeHtml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}

private fun Double.format1(): String = "%.1f".format(this)
