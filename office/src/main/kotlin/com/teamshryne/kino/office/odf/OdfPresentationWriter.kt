package com.teamshryne.kino.office.odf

import com.teamshryne.kino.office.ReportIssue
import com.teamshryne.kino.office.image.ImageData
import com.teamshryne.kino.office.image.Images
import com.teamshryne.kino.office.model.Block
import com.teamshryne.kino.office.model.CalloutBlock
import com.teamshryne.kino.office.model.CalloutKind
import com.teamshryne.kino.office.model.ChartBlock
import com.teamshryne.kino.office.model.ChartKind
import com.teamshryne.kino.office.model.CodeBlock
import com.teamshryne.kino.office.model.DeckSpec
import com.teamshryne.kino.office.model.DividerBlock
import com.teamshryne.kino.office.model.HeadingBlock
import com.teamshryne.kino.office.model.ImageBlock
import com.teamshryne.kino.office.model.InlineMarkup
import com.teamshryne.kino.office.model.KeyValueBlock
import com.teamshryne.kino.office.model.ListBlock
import com.teamshryne.kino.office.model.PageBreakBlock
import com.teamshryne.kino.office.model.ParagraphBlock
import com.teamshryne.kino.office.model.QuoteBlock
import com.teamshryne.kino.office.model.SlideLayout
import com.teamshryne.kino.office.model.SlideSpec
import com.teamshryne.kino.office.model.Span
import com.teamshryne.kino.office.model.SpacerBlock
import com.teamshryne.kino.office.model.StatBlock
import com.teamshryne.kino.office.model.TableBlock
import com.teamshryne.kino.office.model.TocBlock
import com.teamshryne.kino.office.render.Colors
import com.teamshryne.kino.office.theme.OfficeTheme
import com.teamshryne.kino.office.xml.Xml
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Writes a deck as an OpenDocument Presentation (.odp) file.
 *
 * The package is a plain ZIP that follows the OpenDocument conventions: the
 * `mimetype` entry comes first and is stored uncompressed, the manifest
 * declares every part, and each slide is a `draw:page` inside
 * `office:presentation`. Every element is an absolutely positioned frame, so
 * the slides look the same in LibreOffice Impress, Keynote and Google Slides.
 */
object OdfPresentationWriter {

    fun write(
        deck: DeckSpec,
        theme: OfficeTheme,
        issues: MutableList<ReportIssue> = mutableListOf(),
    ): ByteArray {
        val builder = OdpBuilder(deck, theme, issues)
        return builder.build()
    }
}

/* -------------------------------------------------------------------------- */
/* Constants                                                                  */
/* -------------------------------------------------------------------------- */

private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

private const val MIMETYPE = "application/vnd.oasis.opendocument.presentation"

private const val NS_OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
private const val NS_DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
private const val NS_PRESENTATION = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
private const val NS_STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
private const val NS_TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
private const val NS_SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
private const val NS_FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
private const val NS_TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
private const val NS_XLINK = "http://www.w3.org/1999/xlink"
private const val NS_DC = "http://purl.org/dc/elements/1.1/"
private const val NS_META = "urn:oasis:names:tc:opendocument:xmlns:meta:1.0"
private const val NS_MANIFEST = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"

private const val ODF_VERSION = "1.2"

/* The slide is 16:9, the same canvas the PowerPoint writer uses. */
private const val PAGE_WIDTH_CM = 33.867
private const val PAGE_HEIGHT_CM = 19.05
private const val MARGIN_CM = 1.905
private const val CONTENT_WIDTH_CM = PAGE_WIDTH_CM - 2 * MARGIN_CM

private const val PT_TO_CM = 2.54 / 72.0

private fun hex(color: String): String = color.removePrefix("#").trim().uppercase()

private fun cm(value: Double): String = String.format(Locale.US, "%.3fcm", value)

private fun pt(value: Double): String = String.format(Locale.US, "%.2fpt", value)

/* -------------------------------------------------------------------------- */
/* Builder                                                                    */
/* -------------------------------------------------------------------------- */

private class OdpBuilder(
    private val deck: DeckSpec,
    private val theme: OfficeTheme,
    private val issues: MutableList<ReportIssue>,
) {

    private val automaticStyles = StringBuilder()
    private var graphicCounter = 0
    private var textCounter = 0
    private var tableCounter = 0
    private val pictures = mutableListOf<Pair<String, ImageData>>()

    fun build(): ByteArray {
        val content = content()
        val parts = listOf(
            "META-INF/manifest.xml" to manifest(),
            "content.xml" to content,
            "styles.xml" to styles(),
            "meta.xml" to meta(),
        )
        return zip(parts)
    }

    /* ------------------------------ packaging ------------------------------ */

    private fun zip(parts: List<Pair<String, String>>): ByteArray {
        val output = ByteArrayOutputStream(64 * 1024)
        ZipOutputStream(output).use { zip ->
            // The mimetype entry must come first and must not be compressed.
            val mimetype = MIMETYPE.toByteArray(Charsets.UTF_8)
            val entry = ZipEntry("mimetype")
            entry.method = ZipEntry.STORED
            entry.size = mimetype.size.toLong()
            entry.compressedSize = mimetype.size.toLong()
            entry.crc = CRC32().apply { update(mimetype) }.value
            entry.time = 0L
            zip.putNextEntry(entry)
            zip.write(mimetype)
            zip.closeEntry()

            parts.forEach { (name, text) ->
                val part = ZipEntry(name)
                part.time = 0L
                zip.putNextEntry(part)
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            pictures.forEach { (name, data) ->
                val part = ZipEntry("Pictures/$name")
                part.time = 0L
                zip.putNextEntry(part)
                zip.write(data.bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun manifest(): String = buildString {
        append(XML_DECLARATION)
        append("<manifest:manifest xmlns:manifest=\"$NS_MANIFEST\" manifest:version=\"$ODF_VERSION\">")
        append("<manifest:file-entry manifest:full-path=\"/\" manifest:version=\"$ODF_VERSION\" manifest:media-type=\"$MIMETYPE\"/>")
        append("<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>")
        append("<manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/>")
        append("<manifest:file-entry manifest:full-path=\"meta.xml\" manifest:media-type=\"text/xml\"/>")
        pictures.forEach { (name, data) ->
            append(
                "<manifest:file-entry manifest:full-path=\"Pictures/$name\" " +
                    "manifest:media-type=\"${Xml.escape(data.mimeType)}\"/>",
            )
        }
        append("</manifest:manifest>")
    }

    private fun meta(): String = buildString {
        append(XML_DECLARATION)
        append(
            "<office:document-meta xmlns:office=\"$NS_OFFICE\" xmlns:dc=\"$NS_DC\" " +
                "xmlns:meta=\"$NS_META\" office:version=\"$ODF_VERSION\">",
        )
        append("<office:meta>")
        append("<dc:title>${Xml.escape(deck.title)}</dc:title>")
        append("<dc:creator>${Xml.escape(deck.author ?: "Kino")}</dc:creator>")
        append("<dc:date>2024-01-01T00:00:00</dc:date>")
        append("<meta:generator>Kino</meta:generator>")
        append("</office:meta></office:document-meta>")
    }

    /* -------------------------------- styles ------------------------------- */

    private fun styles(): String = buildString {
        append(XML_DECLARATION)
        append(
            "<office:document-styles xmlns:office=\"$NS_OFFICE\" xmlns:draw=\"$NS_DRAW\" " +
                "xmlns:presentation=\"$NS_PRESENTATION\" xmlns:style=\"$NS_STYLE\" xmlns:text=\"$NS_TEXT\" " +
                "xmlns:svg=\"$NS_SVG\" xmlns:fo=\"$NS_FO\" xmlns:table=\"$NS_TABLE\" " +
                "xmlns:xlink=\"$NS_XLINK\" xmlns:dc=\"$NS_DC\" xmlns:meta=\"$NS_META\" office:version=\"$ODF_VERSION\">",
        )

        append("<office:styles>")
        append("<style:style style:name=\"dp1\" style:family=\"drawing-page\"/>")
        append(
            "<style:style style:name=\"Standard\" style:family=\"paragraph\" style:class=\"text\">" +
                "<style:paragraph-properties fo:margin-top=\"0cm\" fo:margin-bottom=\"0cm\" fo:line-height=\"115%\"/>" +
                "<style:text-properties fo:font-family=\"${Xml.escape(theme.sansFont)}\" fo:font-size=\"16pt\" " +
                "fo:color=\"${hex(theme.ink)}\"/></style:style>",
        )
        append(
            "<style:style style:name=\"Internet_20_link\" style:family=\"text\">" +
                "<style:text-properties fo:color=\"${hex(theme.accent)}\" " +
                "style:text-underline-style=\"solid\" style:text-underline-width=\"auto\" style:text-underline-color=\"font\"/>" +
                "</style:style>",
        )
        append("</office:styles>")

        append("<office:automatic-styles>")
        append(
            "<style:page-layout style:name=\"PM1\">" +
                "<style:page-layout-properties fo:page-width=\"${cm(PAGE_WIDTH_CM)}\" " +
                "fo:page-height=\"${cm(PAGE_HEIGHT_CM)}\" style:print-orientation=\"landscape\" " +
                "fo:margin-top=\"0cm\" fo:margin-bottom=\"0cm\" fo:margin-left=\"0cm\" fo:margin-right=\"0cm\" " +
                "style:writing-mode=\"lr-tb\"/></style:page-layout>",
        )
        append("</office:automatic-styles>")

        append("<office:master-styles>")
        append("<style:master-page style:name=\"Standard\" style:page-layout-name=\"PM1\"/>")
        append("</office:master-styles>")

        append("</office:document-styles>")
    }

    /* ------------------------------- content ------------------------------- */

    private fun content(): String {
        val body = StringBuilder()
        deck.slides.forEachIndexed { index, slide ->
            val page = StringBuilder()
            paintSlide(page, slide, index)
            body.append("<draw:page draw:name=\"Slide ${index + 1}\" draw:style-name=\"dp1\" ")
            body.append("presentation:presentation-page-layout-name=\"PM1\">")
            slide.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                body.append("<presentation:notes>")
                body.append(
                    "<draw:frame draw:style-name=\"${graphicStyle()}\" svg:x=\"${cm(MARGIN_CM)}\" " +
                        "svg:y=\"${cm(MARGIN_CM)}\" svg:width=\"${cm(CONTENT_WIDTH_CM)}\" svg:height=\"${cm(6.0)}\">",
                )
                body.append("<draw:text-box>")
                body.append("<text:p text:style-name=\"${textStyle(sizePt = 12.0, color = theme.ink)}\">")
                body.append(Xml.escape(notes))
                body.append("</text:p></draw:text-box></draw:frame></presentation:notes>")
            }
            body.append(page)
            body.append("</draw:page>")
        }

        return buildString {
            append(XML_DECLARATION)
            append(
                "<office:document-content xmlns:office=\"$NS_OFFICE\" xmlns:draw=\"$NS_DRAW\" " +
                    "xmlns:presentation=\"$NS_PRESENTATION\" xmlns:style=\"$NS_STYLE\" xmlns:text=\"$NS_TEXT\" " +
                    "xmlns:svg=\"$NS_SVG\" xmlns:fo=\"$NS_FO\" xmlns:table=\"$NS_TABLE\" " +
                    "xmlns:xlink=\"$NS_XLINK\" xmlns:dc=\"$NS_DC\" xmlns:meta=\"$NS_META\" " +
                    "office:version=\"$ODF_VERSION\">",
            )
            append("<office:automatic-styles>")
            append(automaticStyles)
            append("</office:automatic-styles>")
            append("<office:body><office:presentation>")
            append(body)
            append("</office:presentation></office:body>")
            append("</office:document-content>")
        }
    }

    /* ------------------------------- slides ------------------------------- */

    private fun paintSlide(page: StringBuilder, slide: SlideSpec, index: Int) {
        when (slide.layout) {
            SlideLayout.TITLE -> paintTitle(page, slide)
            SlideLayout.SECTION -> paintSection(page, slide)
            SlideLayout.QUOTE -> paintQuote(page, slide)
            SlideLayout.METRICS -> paintMetrics(page, slide)
            SlideLayout.TWO_COLUMN -> paintTwoColumn(page, slide)
            SlideLayout.CONTENT -> paintContent(page, slide)
        }
        paintFooter(page, index + 1)
    }

    private fun paintTitle(page: StringBuilder, slide: SlideSpec) {
        val title = slide.title.orEmpty().ifBlank { deck.title }
        val subtitle = slide.subtitle.orEmpty()
        val boxWidth = PAGE_WIDTH_CM - 2 * 3.81
        val titleHeight = textHeight(title, 40.0, boxWidth)
        val subtitleHeight = if (subtitle.isBlank()) 0.0 else textHeight(subtitle, 20.0, boxWidth)
        val ruleGap = 0.7
        val ruleThickness = 0.09
        val gap = if (subtitle.isBlank()) 0.0 else 0.6
        val total = titleHeight + ruleGap + ruleThickness + gap + subtitleHeight
        var y = (PAGE_HEIGHT_CM - total) / 2

        textBox(
            page = page,
            x = 3.81,
            y = y,
            width = boxWidth,
            height = titleHeight,
            paragraphs = paragraph(textStyle(sizePt = 40.0, color = theme.accent, bold = true, font = theme.serifFont, align = "center")),
            content = title,
        )
        y += titleHeight + ruleGap
        rectangle(page, 3.81 + (boxWidth - 2.54) / 2, y, 2.54, ruleThickness, theme.accent)
        y += ruleThickness + gap
        if (subtitle.isNotBlank()) {
            textBox(
                page = page,
                x = 3.81,
                y = y,
                width = boxWidth,
                height = subtitleHeight,
                paragraphs = paragraph(textStyle(sizePt = 20.0, color = theme.inkSoft, align = "center")),
                content = subtitle,
            )
        }
    }

    private fun paintSection(page: StringBuilder, slide: SlideSpec) {
        val title = slide.title.orEmpty().ifBlank { "Section" }
        val subtitle = slide.subtitle.orEmpty()
        val bandHeight = 4.2
        val bandY = (PAGE_HEIGHT_CM - bandHeight) / 2 - 1.2

        textBox(
            page = page,
            x = MARGIN_CM,
            y = bandY - 1.2,
            width = CONTENT_WIDTH_CM,
            height = 0.7,
            paragraphs = paragraph(textStyle(sizePt = 12.0, color = theme.muted, bold = true, spacing = 0.12)),
            content = "SECTION",
        )
        rectangle(page, MARGIN_CM, bandY, CONTENT_WIDTH_CM, bandHeight, theme.accent)
        val bandColor = if (Colors.isDark(theme.accent)) "#FFFFFF" else "#1A1A1A"
        textBox(
            page = page,
            x = MARGIN_CM + 0.6,
            y = bandY,
            width = CONTENT_WIDTH_CM - 1.2,
            height = bandHeight,
            paragraphs = paragraph(
                textStyle(sizePt = 32.0, color = bandColor, bold = true, font = theme.serifFont, align = "center"),
                verticalAlign = "middle",
            ),
            content = title,
        )
        if (subtitle.isNotBlank()) {
            val height = textHeight(subtitle, 18.0, CONTENT_WIDTH_CM)
            textBox(
                page = page,
                x = MARGIN_CM,
                y = bandY + bandHeight + 0.8,
                width = CONTENT_WIDTH_CM,
                height = height,
                paragraphs = paragraph(textStyle(sizePt = 18.0, color = theme.inkSoft, align = "center")),
                content = subtitle,
            )
        }
    }

    private fun paintQuote(page: StringBuilder, slide: SlideSpec) {
        val title = slide.title.orEmpty()
        val quote = slide.subtitle.orEmpty().ifBlank {
            slide.blocks.filterIsInstance<QuoteBlock>().firstOrNull()?.text.orEmpty()
        }
        val attribution = slide.blocks.filterIsInstance<QuoteBlock>().firstOrNull()?.attribution
        var y = MARGIN_CM
        if (title.isNotBlank()) {
            val height = textHeight(title, 28.0, CONTENT_WIDTH_CM)
            textBox(
                page = page,
                x = MARGIN_CM,
                y = y,
                width = CONTENT_WIDTH_CM,
                height = height,
                paragraphs = paragraph(textStyle(sizePt = 28.0, color = theme.accent, bold = true, font = theme.serifFont)),
                content = title,
            )
            y += height + 0.9
        }
        val textX = MARGIN_CM + 0.9
        val textWidth = CONTENT_WIDTH_CM - 0.9
        val quoteHeight = textHeight(quote, 26.0, textWidth)
        val attributionHeight = if (attribution.isNullOrBlank()) 0.0 else textHeight(attribution, 14.0, textWidth)
        val total = quoteHeight + (if (attributionHeight > 0) 0.5 + attributionHeight else 0.0)
        val start = max(y, (PAGE_HEIGHT_CM - total) / 2)

        rectangle(page, MARGIN_CM, start, 0.14, total, theme.accent)
        textBox(
            page = page,
            x = textX,
            y = start,
            width = textWidth,
            height = quoteHeight,
            paragraphs = paragraph(textStyle(sizePt = 26.0, color = theme.ink, italic = true, font = theme.serifFont)),
            content = quote,
        )
        if (attributionHeight > 0) {
            textBox(
                page = page,
                x = textX,
                y = start + quoteHeight + 0.5,
                width = textWidth,
                height = attributionHeight,
                paragraphs = paragraph(textStyle(sizePt = 14.0, color = theme.muted)),
                content = "— $attribution",
            )
        }
    }

    private fun paintMetrics(page: StringBuilder, slide: SlideSpec) {
        val stats = slide.blocks.filterIsInstance<StatBlock>().firstOrNull()
        if (stats == null || stats.items.isEmpty()) {
            paintContent(page, slide)
            return
        }
        var y = MARGIN_CM
        val title = slide.title.orEmpty()
        if (title.isNotBlank()) {
            val height = textHeight(title, 28.0, CONTENT_WIDTH_CM)
            textBox(
                page = page,
                x = MARGIN_CM,
                y = y,
                width = CONTENT_WIDTH_CM,
                height = height,
                paragraphs = paragraph(textStyle(sizePt = 28.0, color = theme.accent, bold = true, font = theme.serifFont)),
                content = title,
            )
            y += height + 0.35
            rectangle(page, MARGIN_CM, y, 2.2, 0.09, theme.accent)
            y += 0.09 + 0.9
        }
        val subtitle = slide.subtitle.orEmpty()
        if (subtitle.isNotBlank()) {
            val height = textHeight(subtitle, 16.0, CONTENT_WIDTH_CM)
            textBox(
                page = page,
                x = MARGIN_CM,
                y = y,
                width = CONTENT_WIDTH_CM,
                height = height,
                paragraphs = paragraph(textStyle(sizePt = 16.0, color = theme.inkSoft)),
                content = subtitle,
            )
            y += height + 0.7
        }

        val items = stats.items.take(4)
        val gap = 0.6
        val tileWidth = (CONTENT_WIDTH_CM - gap * (items.size - 1)) / items.size
        val tileHeight = 4.4
        items.forEachIndexed { index, item ->
            val x = MARGIN_CM + index * (tileWidth + gap)
            roundedRectangle(page, x, y, tileWidth, tileHeight, theme.surface, theme.border)
            val inner = 0.5
            val labelHeight = 0.7
            val valueHeight = textHeight(item.value, 30.0, tileWidth - inner * 2)
            textBox(
                page = page,
                x = x + inner,
                y = y + inner,
                width = tileWidth - inner * 2,
                height = labelHeight,
                paragraphs = paragraph(textStyle(sizePt = 11.0, color = theme.muted, bold = true, spacing = 0.1)),
                content = item.label.uppercase(),
            )
            textBox(
                page = page,
                x = x + inner,
                y = y + inner + labelHeight,
                width = tileWidth - inner * 2,
                height = valueHeight,
                paragraphs = paragraph(textStyle(sizePt = 30.0, color = theme.accent, bold = true, font = theme.serifFont)),
                content = item.value,
            )
            val delta = item.delta
            if (!delta.isNullOrBlank()) {
                val deltaColor = when (item.positive) {
                    true -> theme.positive
                    false -> theme.negative
                    null -> theme.muted
                }
                textBox(
                    page = page,
                    x = x + inner,
                    y = y + inner + labelHeight + valueHeight,
                    width = tileWidth - inner * 2,
                    height = 0.7,
                    paragraphs = paragraph(textStyle(sizePt = 12.0, color = deltaColor, bold = true)),
                    content = delta,
                )
            }
        }
        y += tileHeight + 0.7

        val rest = slide.blocks.filterNot { it is StatBlock }
        if (rest.isNotEmpty()) {
            y = paintBlocks(page, rest, MARGIN_CM, y, CONTENT_WIDTH_CM)
        }
    }

    private fun paintTwoColumn(page: StringBuilder, slide: SlideSpec) {
        var y = MARGIN_CM
        val title = slide.title.orEmpty()
        if (title.isNotBlank()) {
            val height = textHeight(title, 28.0, CONTENT_WIDTH_CM)
            textBox(
                page = page,
                x = MARGIN_CM,
                y = y,
                width = CONTENT_WIDTH_CM,
                height = height,
                paragraphs = paragraph(textStyle(sizePt = 28.0, color = theme.accent, bold = true, font = theme.serifFont)),
                content = title,
            )
            y += height + 0.35
            rectangle(page, MARGIN_CM, y, 2.2, 0.09, theme.accent)
            y += 0.09 + 0.8
        }
        val subtitle = slide.subtitle.orEmpty()
        if (subtitle.isNotBlank()) {
            val height = textHeight(subtitle, 16.0, CONTENT_WIDTH_CM)
            textBox(
                page = page,
                x = MARGIN_CM,
                y = y,
                width = CONTENT_WIDTH_CM,
                height = height,
                paragraphs = paragraph(textStyle(sizePt = 16.0, color = theme.inkSoft)),
                content = subtitle,
            )
            y += height + 0.7
        }

        val columnGap = 1.2
        val columnWidth = (CONTENT_WIDTH_CM - columnGap) / 2
        val columns: List<List<Block>> = when {
            slide.columns.size >= 2 -> slide.columns.take(2)
            slide.columns.size == 1 -> listOf(slide.columns.first(), emptyList())
            else -> {
                val half = slide.blocks.size / 2
                listOf(slide.blocks.take(half), slide.blocks.drop(half))
            }
        }
        rectangle(page, MARGIN_CM + columnWidth + columnGap / 2 - 0.02, y, 0.04, 9.5, theme.border)
        columns.forEachIndexed { index, blocks ->
            val x = MARGIN_CM + index * (columnWidth + columnGap)
            paintBlocks(page, blocks, x, y, columnWidth)
        }
    }

    private fun paintContent(page: StringBuilder, slide: SlideSpec) {
        var y = MARGIN_CM
        val title = slide.title.orEmpty()
        if (title.isNotBlank()) {
            val height = textHeight(title, 28.0, CONTENT_WIDTH_CM)
            textBox(
                page = page,
                x = MARGIN_CM,
                y = y,
                width = CONTENT_WIDTH_CM,
                height = height,
                paragraphs = paragraph(textStyle(sizePt = 28.0, color = theme.accent, bold = true, font = theme.serifFont)),
                content = title,
            )
            y += height + 0.35
            rectangle(page, MARGIN_CM, y, 2.2, 0.09, theme.accent)
            y += 0.09 + 0.8
        }
        val subtitle = slide.subtitle.orEmpty()
        if (subtitle.isNotBlank()) {
            val height = textHeight(subtitle, 16.0, CONTENT_WIDTH_CM)
            textBox(
                page = page,
                x = MARGIN_CM,
                y = y,
                width = CONTENT_WIDTH_CM,
                height = height,
                paragraphs = paragraph(textStyle(sizePt = 16.0, color = theme.inkSoft)),
                content = subtitle,
            )
            y += height + 0.7
        }
        paintBlocks(page, slide.blocks, MARGIN_CM, y, CONTENT_WIDTH_CM)
    }

    private fun paintFooter(page: StringBuilder, number: Int) {
        val y = PAGE_HEIGHT_CM - 1.35
        rectangle(page, MARGIN_CM, y - 0.35, CONTENT_WIDTH_CM, 0.03, theme.border)
        textBox(
            page = page,
            x = MARGIN_CM,
            y = y,
            width = CONTENT_WIDTH_CM - 3.0,
            height = 0.7,
            paragraphs = paragraph(textStyle(sizePt = 10.0, color = theme.muted)),
            content = deck.title,
        )
        textBox(
            page = page,
            x = PAGE_WIDTH_CM - MARGIN_CM - 3.0,
            y = y,
            width = 3.0,
            height = 0.7,
            paragraphs = paragraph(textStyle(sizePt = 10.0, color = theme.muted, align = "end")),
            content = number.toString(),
        )
    }

    /* ------------------------------- blocks ------------------------------- */

    private fun paintBlocks(page: StringBuilder, blocks: List<Block>, x: Double, y: Double, width: Double): Double {
        var cursor = y
        blocks.forEach { block ->
            cursor = paintBlock(page, block, x, cursor, width)
        }
        return cursor
    }

    private fun paintBlock(page: StringBuilder, block: Block, x: Double, y: Double, width: Double): Double = when (block) {
        is ParagraphBlock -> {
            val height = textHeight(block.text, 16.0, width)
            textBox(
                page = page,
                x = x,
                y = y,
                width = width,
                height = height,
                paragraphs = paragraph(textStyle(sizePt = 16.0, color = theme.ink)),
                content = block.text,
            )
            y + height + 0.42
        }

        is ListBlock -> {
            val items = block.items.joinToString("") { item ->
                "<text:list-item><text:p text:style-name=\"${listItemStyle(block.ordered)}\">${spans(item)}</text:p></text:list-item>"
            }
            val height = block.items.sumOf { textHeight(it, 16.0, width - 0.7) } + block.items.size * 0.12
            textBox(
                page = page,
                x = x,
                y = y,
                width = width,
                height = height,
                paragraphs = "<text:list text:style-name=\"${listStyle(block.ordered)}\">$items</text:list>",
                content = null,
            )
            y + height + 0.45
        }

        is HeadingBlock -> {
            val size = when {
                block.level <= 1 -> 22.0
                block.level == 2 -> 19.0
                else -> 17.0
            }
            val text = if (block.numbered) "${block.level}. ${block.text}" else block.text
            val height = textHeight(text, size, width)
            textBox(
                page = page,
                x = x,
                y = y,
                width = width,
                height = height,
                paragraphs = paragraph(textStyle(sizePt = size, color = theme.ink, bold = true, font = theme.serifFont)),
                content = text,
            )
            y + height + 0.36
        }

        is TableBlock -> {
            val height = paintTable(page, block, x, y, width)
            y + height + 0.5
        }

        is StatBlock -> {
            val items = block.items.take(4)
            if (items.isEmpty()) return y
            val gap = 0.5
            val tileWidth = (width - gap * (items.size - 1)) / items.size
            val tileHeight = 3.6
            items.forEachIndexed { index, item ->
                val tileX = x + index * (tileWidth + gap)
                roundedRectangle(page, tileX, y, tileWidth, tileHeight, theme.surface, theme.border)
                val inner = 0.45
                textBox(
                    page = page,
                    x = tileX + inner,
                    y = y + inner,
                    width = tileWidth - inner * 2,
                    height = 0.65,
                    paragraphs = paragraph(textStyle(sizePt = 11.0, color = theme.muted, bold = true, spacing = 0.1)),
                    content = item.label.uppercase(),
                )
                textBox(
                    page = page,
                    x = tileX + inner,
                    y = y + inner + 0.65,
                    width = tileWidth - inner * 2,
                    height = textHeight(item.value, 26.0, tileWidth - inner * 2),
                    paragraphs = paragraph(textStyle(sizePt = 26.0, color = theme.accent, bold = true, font = theme.serifFont)),
                    content = item.value,
                )
            }
            y + tileHeight + 0.45
        }

        is ChartBlock -> paintChart(page, block, x, y, width)

        is ImageBlock -> paintImage(page, block, x, y, width)

        is KeyValueBlock -> {
            val labelWidth = width * 0.35
            var cursor = y
            block.items.forEach { item ->
                val labelHeight = textHeight(item.label, 16.0, labelWidth - 0.4)
                val valueHeight = textHeight(item.value, 16.0, width - labelWidth - 0.4)
                val rowHeight = max(labelHeight, valueHeight)
                textBox(
                    page = page,
                    x = x,
                    y = cursor,
                    width = labelWidth - 0.4,
                    height = rowHeight,
                    paragraphs = paragraph(textStyle(sizePt = 16.0, color = theme.inkSoft, bold = true)),
                    content = item.label,
                )
                textBox(
                    page = page,
                    x = x + labelWidth,
                    y = cursor,
                    width = width - labelWidth,
                    height = rowHeight,
                    paragraphs = paragraph(textStyle(sizePt = 16.0, color = theme.ink)),
                    content = item.value,
                )
                cursor += rowHeight + 0.15
                rectangle(page, x, cursor, width, 0.02, theme.border)
                cursor += 0.15
                if (!item.hint.isNullOrBlank()) {
                    val hintHeight = textHeight(item.hint, 12.0, width)
                    textBox(
                        page = page,
                        x = x,
                        y = cursor,
                        width = width,
                        height = hintHeight,
                        paragraphs = paragraph(textStyle(sizePt = 12.0, color = theme.muted)),
                        content = item.hint,
                    )
                    cursor += hintHeight + 0.15
                }
            }
            cursor + 0.3
        }

        is QuoteBlock -> {
            val textWidth = width - 0.9
            val height = textHeight(block.text, 20.0, textWidth)
            val attributionHeight = if (block.attribution.isNullOrBlank()) 0.0 else textHeight(block.attribution, 13.0, textWidth)
            rectangle(page, x, y, 0.13, height + attributionHeight, theme.accent)
            textBox(
                page = page,
                x = x + 0.9,
                y = y,
                width = textWidth,
                height = height,
                paragraphs = paragraph(textStyle(sizePt = 20.0, color = theme.ink, italic = true, font = theme.serifFont)),
                content = block.text,
            )
            if (attributionHeight > 0) {
                textBox(
                    page = page,
                    x = x + 0.9,
                    y = y + height + 0.25,
                    width = textWidth,
                    height = attributionHeight,
                    paragraphs = paragraph(textStyle(sizePt = 13.0, color = theme.muted)),
                    content = "— ${block.attribution}",
                )
            }
            y + height + attributionHeight + 0.5
        }

        is CodeBlock -> {
            val inner = 0.55
            val height = textHeight(block.text, 13.0, width - inner * 2) + inner * 2
            rectangle(page, x, y, width, height, theme.surfaceAlt, theme.border)
            var cursor = y + inner - 0.15
            if (!block.language.isNullOrBlank()) {
                textBox(
                    page = page,
                    x = x + inner,
                    y = cursor,
                    width = width - inner * 2,
                    height = 0.6,
                    paragraphs = paragraph(textStyle(sizePt = 10.0, color = theme.muted, bold = true, spacing = 0.1)),
                    content = block.language.uppercase(),
                )
                cursor += 0.5
            }
            textBox(
                page = page,
                x = x + inner,
                y = cursor,
                width = width - inner * 2,
                height = height - inner * 2,
                paragraphs = paragraph(textStyle(sizePt = 13.0, color = theme.ink, font = theme.monoFont)),
                content = block.text,
            )
            y + height + 0.45
        }

        is CalloutBlock -> {
            val accent = calloutColor(block.kind)
            val inner = 0.6
            val titleHeight = if (block.title.isNullOrBlank()) 0.0 else textHeight(block.title, 16.0, width - inner * 2)
            val height = textHeight(block.text, 16.0, width - inner * 2) + titleHeight + inner * 2
            roundedRectangle(page, x, y, width, height, Colors.lighten(accent, 0.88), Colors.lighten(accent, 0.55))
            rectangle(page, x, y, 0.16, height, accent)
            var cursor = y + inner - 0.1
            if (titleHeight > 0) {
                textBox(
                    page = page,
                    x = x + inner,
                    y = cursor,
                    width = width - inner * 2,
                    height = titleHeight,
                    paragraphs = paragraph(textStyle(sizePt = 16.0, color = Colors.darken(accent, 0.25), bold = true)),
                    content = block.title,
                )
                cursor += titleHeight
            }
            textBox(
                page = page,
                x = x + inner,
                y = cursor,
                width = width - inner * 2,
                height = height - inner * 2,
                paragraphs = paragraph(textStyle(sizePt = 16.0, color = theme.inkSoft)),
                content = block.text,
            )
            y + height + 0.45
        }

        is DividerBlock -> {
            rectangle(page, x, y + 0.2, width, 0.02, theme.border)
            y + 0.7
        }

        is SpacerBlock -> y + block.size * 0.45

        is PageBreakBlock -> y

        is TocBlock -> y
    }

    private fun paintTable(page: StringBuilder, block: TableBlock, x: Double, y: Double, width: Double): Double {
        if (block.columns.isEmpty()) return 0.0
        val columnCount = block.columns.size
        val widths = columnWidths(block, width)
        val headerHeight = max(
            1.05,
            block.columns.mapIndexed { index, column ->
                textHeight(column.header, 12.0, widths.getOrElse(index) { widths.last() } - 0.4)
            }.maxOrNull() ?: 1.05,
        )
        val rowHeights = block.rows.map { row ->
            var height = 0.95
            row.forEachIndexed { index, cell ->
                val cellWidth = widths.getOrElse(index) { widths.last() }
                height = max(height, textHeight(cell, 11.0, cellWidth - 0.4) + 0.35)
            }
            height
        }
        val totalHeight = headerHeight + rowHeights.sum()

        val tableName = "Table${++tableCounter}"
        val tableStyle = "ta$tableCounter"
        val columnStyles = widths.mapIndexed { index, columnWidth ->
            val name = "co${tableCounter}_$index"
            automaticStyles.append(
                "<style:style style:name=\"$name\" style:family=\"table-column\">" +
                    "<style:table-column-properties style:column-width=\"${cm(columnWidth)}\" fo:break-before=\"auto\"/>" +
                    "</style:style>",
            )
            name
        }
        val headerCell = "ce${tableCounter}_h"
        val bodyCell = "ce${tableCounter}_b"
        val zebraCell = "ce${tableCounter}_z"
        automaticStyles.append(
            "<style:style style:name=\"$tableStyle\" style:family=\"table\">" +
                "<style:table-properties table:align=\"margins\" style:writing-mode=\"lr-tb\"/>" +
                "</style:style>",
        )
        automaticStyles.append(
            "<style:style style:name=\"$headerCell\" style:family=\"table-cell\">" +
                "<style:table-cell-properties fo:border=\"0.5pt solid ${hex(theme.border)}\" fo:padding=\"0.15cm\" " +
                "fo:background-color=\"${hex(theme.accent)}\"/></style:style>",
        )
        automaticStyles.append(
            "<style:style style:name=\"$bodyCell\" style:family=\"table-cell\">" +
                "<style:table-cell-properties fo:border=\"0.5pt solid ${hex(theme.border)}\" fo:padding=\"0.15cm\" " +
                "fo:background-color=\"${hex(theme.background)}\"/></style:style>",
        )
        automaticStyles.append(
            "<style:style style:name=\"$zebraCell\" style:family=\"table-cell\">" +
                "<style:table-cell-properties fo:border=\"0.5pt solid ${hex(theme.border)}\" fo:padding=\"0.15cm\" " +
                "fo:background-color=\"${hex(theme.surface)}\"/></style:style>",
        )

        page.append("<draw:frame draw:style-name=\"${graphicStyle()}\" ")
        page.append("svg:x=\"${cm(x)}\" svg:y=\"${cm(y)}\" svg:width=\"${cm(width)}\" svg:height=\"${cm(totalHeight)}\">")
        page.append("<table:table table:name=\"$tableName\" table:style-name=\"$tableStyle\">")
        columnStyles.forEach { name ->
            page.append("<table:table-column table:style-name=\"$name\"/>")
        }
        page.append("<table:table-header-rows><table:table-row>")
        block.columns.forEachIndexed { index, column ->
            val cellWidth = widths.getOrElse(index) { widths.last() }
            page.append("<table:table-cell table:style-name=\"$headerCell\" office:value-type=\"string\">")
            page.append(
                "<text:p text:style-name=\"${textStyle(sizePt = 12.0, color = "#FFFFFF", bold = true)}\">" +
                    Xml.escape(InlineMarkup.plain(column.header)) + "</text:p>",
            )
            page.append("</table:table-cell>")
        }
        page.append("</table:table-row></table:table-header-rows>")
        block.rows.forEachIndexed { rowIndex, row ->
            page.append("<table:table-row>")
            row.forEachIndexed { index, cell ->
                val cellStyle = if (block.zebra && rowIndex % 2 == 1) zebraCell else bodyCell
                page.append("<table:table-cell table:style-name=\"$cellStyle\" office:value-type=\"string\">")
                page.append(
                    "<text:p text:style-name=\"${textStyle(sizePt = 11.0, color = theme.ink)}\">" +
                        Xml.escape(InlineMarkup.plain(cell)) + "</text:p>",
                )
                page.append("</table:table-cell>")
            }
            page.append("</table:table-row>")
        }
        page.append("</table:table></draw:frame>")
        return totalHeight
    }

    private fun columnWidths(block: TableBlock, width: Double): List<Double> {
        val weights = block.columns.map { it.width }
        val usable = if (weights.isNotEmpty() && weights.all { it != null && it > 0 }) {
            val total = weights.sumOf { it ?: 1.0 }
            weights.map { ((it ?: 1.0) / total * width) }
        } else {
            List(block.columns.size) { width / block.columns.size }
        }
        val fixed = usable.map { max(1.5, it) }
        val sum = fixed.sum()
        val scaled = fixed.map { it / sum * width }
        scaled[0] += width - scaled.sum()
        return scaled
    }

    private fun paintChart(page: StringBuilder, block: ChartBlock, x: Double, y: Double, width: Double): Double {
        var cursor = y
        if (!block.title.isNullOrBlank()) {
            val height = textHeight(block.title, 13.0, width)
            textBox(
                page = page,
                x = x,
                y = cursor,
                width = width,
                height = height,
                paragraphs = paragraph(textStyle(sizePt = 13.0, color = theme.ink, bold = true)),
                content = block.title,
            )
            cursor += height + 0.3
        }
        val kindLabel = when (block.kind) {
            ChartKind.COLUMN -> "a column chart"
            ChartKind.BAR -> "a bar chart"
            ChartKind.LINE -> "a line chart"
            ChartKind.AREA -> "an area chart"
            ChartKind.PIE -> "a pie chart"
        }
        issues += ReportIssue(
            ReportIssue.Severity.WARNING,
            "Impress cannot embed the generated chart, so $kindLabel was drawn with shapes.",
        )
        cursor += when (block.kind) {
            ChartKind.COLUMN -> paintColumnChart(page, block, x, cursor, width)
            ChartKind.BAR -> paintBarChart(page, block, x, cursor, width)
            else -> paintChartTable(page, block, x, cursor, width)
        }
        return cursor + 0.5
    }

    private fun paintColumnChart(page: StringBuilder, block: ChartBlock, x: Double, y: Double, width: Double): Double {
        val categories = block.categories.ifEmpty {
            block.series.firstOrNull()?.values?.indices?.map { "Item ${it + 1}" }.orEmpty()
        }
        if (categories.isEmpty() || block.series.isEmpty()) return 0.0
        val plotHeight = 6.2
        val labelHeight = 0.8
        val valueHeight = 0.6
        val baseline = y + plotHeight
        val values = block.series.flatMap { it.values }
        val maxValue = values.maxOrNull()?.let { max(abs(it), 0.0001) } ?: 1.0
        val groupWidth = width / categories.size
        val barCount = block.series.size
        val barGap = 0.12
        val barWidth = max(0.2, (groupWidth * 0.66 - barGap * (barCount - 1)) / barCount)
        val colors = chartPalette(block.series.size)

        rectangle(page, x, baseline, width, 0.02, theme.border)
        categories.forEachIndexed { index, category ->
            val groupStart = x + groupWidth * index + (groupWidth - (barWidth * barCount + barGap * (barCount - 1))) / 2
            block.series.forEachIndexed { seriesIndex, series ->
                val value = series.values.getOrNull(index) ?: 0.0
                val barHeight = (abs(value) / maxValue) * (plotHeight - valueHeight)
                val barX = groupStart + seriesIndex * (barWidth + barGap)
                val barY = baseline - barHeight
                rectangle(page, barX, barY, barWidth, barHeight, colors[seriesIndex])
                textBox(
                    page = page,
                    x = barX - barWidth,
                    y = max(y, barY - valueHeight),
                    width = barWidth * 3,
                    height = valueHeight,
                    paragraphs = paragraph(textStyle(sizePt = 9.0, color = theme.muted, align = "center")),
                    content = formatValue(value),
                )
            }
            textBox(
                page = page,
                x = x + groupWidth * index,
                y = baseline + 0.15,
                width = groupWidth,
                height = labelHeight,
                paragraphs = paragraph(textStyle(sizePt = 10.0, color = theme.muted, align = "center")),
                content = category,
            )
        }
        return plotHeight + labelHeight + 0.3
    }

    private fun paintBarChart(page: StringBuilder, block: ChartBlock, x: Double, y: Double, width: Double): Double {
        val categories = block.categories.ifEmpty {
            block.series.firstOrNull()?.values?.indices?.map { "Item ${it + 1}" }.orEmpty()
        }
        if (categories.isEmpty() || block.series.isEmpty()) return 0.0
        val labelWidth = min(5.0, width * 0.28)
        val rowHeight = 1.4
        val values = block.series.flatMap { it.values }
        val maxValue = values.maxOrNull()?.let { max(abs(it), 0.0001) } ?: 1.0
        val colors = chartPalette(block.series.size)
        val plotWidth = width - labelWidth - 2.2

        categories.forEachIndexed { index, category ->
            val rowTop = y + index * rowHeight
            textBox(
                page = page,
                x = x,
                y = rowTop,
                width = labelWidth - 0.3,
                height = rowHeight - 0.3,
                paragraphs = paragraph(textStyle(sizePt = 10.0, color = theme.inkSoft, align = "end")),
                content = category,
            )
            block.series.forEachIndexed { seriesIndex, series ->
                val value = series.values.getOrNull(index) ?: 0.0
                val barHeight = max(0.25, ((rowHeight - 0.4) / block.series.size))
                val barY = rowTop + 0.15 + seriesIndex * barHeight
                val barWidth = max(0.06, (abs(value) / maxValue) * plotWidth)
                rectangle(page, x + labelWidth, barY, barWidth, barHeight - 0.08, colors[seriesIndex])
                textBox(
                    page = page,
                    x = x + labelWidth + barWidth + 0.2,
                    y = barY - 0.08,
                    width = 2.0,
                    height = barHeight,
                    paragraphs = paragraph(textStyle(sizePt = 9.0, color = theme.muted)),
                    content = formatValue(value),
                )
            }
        }
        return categories.size * rowHeight + 0.3
    }

    private fun paintChartTable(page: StringBuilder, block: ChartBlock, x: Double, y: Double, width: Double): Double {
        val categories = block.categories.ifEmpty {
            block.series.firstOrNull()?.values?.indices?.map { "Item ${it + 1}" }.orEmpty()
        }
        if (categories.isEmpty()) return 0.0
        val columns = listOf(com.teamshryne.kino.office.model.TableColumn("Category")) +
            block.series.map { com.teamshryne.kino.office.model.TableColumn(it.name) }
        val rows = categories.mapIndexed { index, category ->
            listOf(category) + block.series.map { formatValue(it.values.getOrNull(index) ?: 0.0) }
        }
        val table = TableBlock(columns = columns, rows = rows, zebra = true)
        return paintTable(page, table, x, y, width)
    }

    private fun chartPalette(count: Int): List<String> {
        val anchors = listOf(
            theme.accent,
            Colors.lighten(theme.accent, 0.55),
            Colors.darken(theme.accent, 0.28),
            Colors.mix(theme.accent, "#E17055", 0.55),
            Colors.mix(theme.accent, "#4E7CD9", 0.5),
        )
        return (0 until max(1, count)).map { anchors[it % anchors.size] }
    }

    private fun paintImage(page: StringBuilder, block: ImageBlock, x: Double, y: Double, width: Double): Double {
        val data = Images.decodeDataUri(block.source)
        if (data == null) {
            issues += ReportIssue(
                ReportIssue.Severity.WARNING,
                "The image \"${block.source.take(80)}\" was skipped because its bytes are not available in the deck.",
            )
            return y
        }
        if (data.extension == "svg") {
            issues += ReportIssue(
                ReportIssue.Severity.WARNING,
                "Impress cannot embed SVG images, so \"${block.source.take(80)}\" was skipped.",
            )
            return y
        }
        val name = "image${pictures.size + 1}.${data.extension}"
        pictures += name to data
        val pictureWidth = width * block.maxWidthPercent.coerceIn(10, 100) / 100
        val size = imageSize(data.bytes)
        val (pixelWidth, pixelHeight) = size ?: (1600 to 900)
        val pictureHeight = max(1.0, pictureWidth * pixelHeight / max(1, pixelWidth))
        page.append("<draw:frame draw:style-name=\"${graphicStyle()}\" ")
        page.append("svg:x=\"${cm(x)}\" svg:y=\"${cm(y)}\" svg:width=\"${cm(pictureWidth)}\" svg:height=\"${cm(pictureHeight)}\">")
        page.append("<draw:image xlink:href=\"Pictures/$name\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/>")
        page.append("</draw:frame>")
        var cursor = y + pictureHeight + 0.2
        if (!block.caption.isNullOrBlank()) {
            val height = textHeight(block.caption, 12.0, width)
            textBox(
                page = page,
                x = x,
                y = cursor,
                width = width,
                height = height,
                paragraphs = paragraph(textStyle(sizePt = 12.0, color = theme.muted, align = "center")),
                content = block.caption,
            )
            cursor += height
        }
        return cursor + 0.3
    }

    /* ------------------------------ primitives ----------------------------- */

    private fun calloutColor(kind: CalloutKind): String = when (kind) {
        CalloutKind.INFO -> theme.accent
        CalloutKind.NOTE -> "#4E7CD9"
        CalloutKind.WARNING -> theme.warning
        CalloutKind.SUCCESS -> theme.positive
        CalloutKind.DANGER -> theme.negative
    }

    private fun formatValue(value: Double): String = when {
        abs(value) >= 1_000_000 -> "%.1fM".format(value / 1_000_000)
        abs(value) >= 1_000 -> "%.1fK".format(value / 1_000)
        abs(value) >= 100 -> "%.0f".format(value)
        abs(value) >= 1 -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }

    private fun textHeight(text: String, sizePt: Double, widthCm: Double): Double {
        if (text.isBlank()) return 0.0
        val lines = estimateLines(text, sizePt, widthCm)
        return lines * sizePt * 1.25 * PT_TO_CM
    }

    private fun estimateLines(text: String, sizePt: Double, widthCm: Double): Int {
        if (text.isBlank()) return 1
        val charWidthCm = sizePt * 0.5 * PT_TO_CM
        val usable = max(0.5, widthCm)
        var lines = 0
        text.split("\n").forEach { line ->
            if (line.isEmpty()) {
                lines += 1
            } else {
                lines += max(1, ceil(line.length * charWidthCm / usable).toInt())
            }
        }
        return max(1, lines)
    }

    private fun graphicStyle(
        fill: String? = null,
        stroke: String? = null,
        strokeWidthCm: Double = 0.03,
        verticalAlign: String = "top",
        paddingCm: Double = 0.0,
    ): String {
        graphicCounter += 1
        val name = "gr$graphicCounter"
        automaticStyles.append("<style:style style:name=\"$name\" style:family=\"graphic\">")
        automaticStyles.append("<style:graphic-properties")
        if (fill != null) {
            automaticStyles.append(" draw:fill=\"solid\" draw:fill-color=\"${hex(fill)}\"")
        } else {
            automaticStyles.append(" draw:fill=\"none\"")
        }
        if (stroke != null) {
            automaticStyles.append(
                " draw:stroke=\"solid\" draw:stroke-color=\"${hex(stroke)}\" svg:stroke-width=\"${cm(strokeWidthCm)}\"",
            )
        } else {
            automaticStyles.append(" draw:stroke=\"none\"")
        }
        automaticStyles.append(" style:vertical-align=\"$verticalAlign\">")
        automaticStyles.append("<style:text-properties fo:padding=\"${cm(paddingCm)}\"/>")
        automaticStyles.append("</style:style>")
        return name
    }

    private fun textStyle(
        sizePt: Double,
        color: String,
        bold: Boolean = false,
        italic: Boolean = false,
        font: String = theme.sansFont,
        align: String = "start",
        spacing: Double = 0.0,
        marginBottomCm: Double = 0.0,
        verticalAlign: String = "top",
    ): String {
        textCounter += 1
        val name = "P$textCounter"
        automaticStyles.append("<style:style style:name=\"$name\" style:family=\"paragraph\">")
        automaticStyles.append(
            "<style:paragraph-properties fo:text-align=\"$align\" fo:margin-top=\"0cm\" " +
                "fo:margin-bottom=\"${cm(marginBottomCm)}\" fo:line-height=\"115%\"/>",
        )
        automaticStyles.append(
            "<style:text-properties fo:font-family=\"${Xml.escape(font)}\" fo:font-size=\"${pt(sizePt)}\" " +
                "fo:color=\"${hex(color)}\"" +
                (if (bold) " fo:font-weight=\"bold\"" else "") +
                (if (italic) " fo:font-style=\"italic\"" else "") +
                (if (spacing > 0) " fo:letter-spacing=\"${String.format(Locale.US, "%.3fcm", spacing)}\"" else "") +
                "/>",
        )
        automaticStyles.append("</style:style>")
        return name
    }

    private fun listStyle(ordered: Boolean): String {
        val name = if (ordered) "LNumbered" else "LBullet"
        if (listStyles.contains(name)) return name
        listStyles += name
        automaticStyles.append("<text:list-style style:name=\"$name\">")
        if (ordered) {
            automaticStyles.append(
                "<text:list-level-style-number text:level=\"1\" style:num-format=\"1\" style:num-suffix=\".\" " +
                    "style:num-letter-sync=\"true\">" +
                    "<style:list-level-properties text:space-before=\"0cm\" text:min-label-width=\"0.5cm\" " +
                    "fo:margin-left=\"0.75cm\" fo:text-indent=\"-0.5cm\"/>" +
                    "<style:text-properties fo:font-family=\"${Xml.escape(theme.sansFont)}\"/>" +
                    "</text:list-level-style-number>",
            )
        } else {
            automaticStyles.append(
                "<text:list-level-style-bullet text:level=\"1\" style:num-prefix=\"• \" style:num-bullet-char=\"•\">" +
                    "<style:list-level-properties text:space-before=\"0cm\" text:min-label-width=\"0.5cm\" " +
                    "fo:margin-left=\"0.75cm\" fo:text-indent=\"-0.5cm\"/>" +
                    "<style:text-properties fo:font-family=\"${Xml.escape(theme.sansFont)}\"/>" +
                    "</text:list-level-style-bullet>",
            )
        }
        automaticStyles.append("</text:list-style>")
        return name
    }

    private fun listItemStyle(ordered: Boolean): String {
        val key = if (ordered) "li_numbered" else "li_bullet"
        itemStyles[key]?.let { return it }
        textCounter += 1
        val name = "P$textCounter"
        itemStyles[key] = name
        automaticStyles.append("<style:style style:name=\"$name\" style:family=\"paragraph\">")
        automaticStyles.append(
            "<style:paragraph-properties fo:margin-top=\"0cm\" fo:margin-bottom=\"0.06cm\" fo:line-height=\"115%\"/>",
        )
        automaticStyles.append(
            "<style:text-properties fo:font-family=\"${Xml.escape(theme.sansFont)}\" fo:font-size=\"16pt\" " +
                "fo:color=\"${hex(theme.ink)}\"/>",
        )
        automaticStyles.append("</style:style>")
        return name
    }

    private val listStyles = mutableSetOf<String>()
    private val itemStyles = mutableMapOf<String, String>()

    private fun paragraph(styleName: String, verticalAlign: String = "top"): String = styleName

    private fun textBox(
        page: StringBuilder,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        paragraphs: String,
        content: String?,
        verticalAlign: String = "top",
    ) {
        page.append("<draw:frame draw:style-name=\"${graphicStyle(verticalAlign = verticalAlign)}\" ")
        page.append("svg:x=\"${cm(x)}\" svg:y=\"${cm(y)}\" svg:width=\"${cm(width)}\" svg:height=\"${cm(height)}\">")
        page.append("<draw:text-box>")
        if (content != null) {
            page.append("<text:p text:style-name=\"$paragraphs\">${spans(content)}</text:p>")
        } else {
            page.append(paragraphs)
        }
        page.append("</draw:text-box></draw:frame>")
    }

    private fun rectangle(
        page: StringBuilder,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        fill: String,
        stroke: String? = null,
    ) {
        page.append("<draw:frame draw:style-name=\"${graphicStyle(fill = fill, stroke = stroke)}\" ")
        page.append("svg:x=\"${cm(x)}\" svg:y=\"${cm(y)}\" svg:width=\"${cm(width)}\" svg:height=\"${cm(height)}\">")
        page.append("<draw:custom-shape><svg:title>${Xml.escape("Shape")}</svg:title>")
        page.append("<draw:enhanced-geometry draw:type=\"rectangle\"/></draw:custom-shape></draw:frame>")
    }

    private fun roundedRectangle(
        page: StringBuilder,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        fill: String,
        stroke: String,
    ) {
        page.append("<draw:frame draw:style-name=\"${graphicStyle(fill = fill, stroke = stroke, strokeWidthCm = 0.05)}\" ")
        page.append("svg:x=\"${cm(x)}\" svg:y=\"${cm(y)}\" svg:width=\"${cm(width)}\" svg:height=\"${cm(height)}\">")
        page.append("<draw:custom-shape><svg:title>${Xml.escape("Shape")}</svg:title>")
        page.append("<draw:enhanced-geometry draw:type=\"round-rectangle\" draw:corner-radius=\"0.2cm\"/>")
        page.append("</draw:custom-shape></draw:frame>")
    }

    private fun spans(text: String): String =
        InlineMarkup.parse(text).joinToString("") { span -> spanXml(span) }

    private fun spanXml(span: Span): String {
        val link = span.link
        if (!link.isNullOrBlank()) {
            val content = Xml.escape(Xml.sanitize(span.text))
            return "<text:a xlink:href=\"${Xml.escape(link)}\" text:style-name=\"Internet_20_link\">$content</text:a>"
        }
        val attributes = mutableListOf<String>()
        if (span.bold) attributes += "fo:font-weight=\"bold\""
        if (span.italic) attributes += "fo:font-style=\"italic\""
        if (span.strike) attributes += "style:text-line-through-style=\"solid\""
        if (span.code) {
            attributes += "fo:font-family=\"${Xml.escape(theme.monoFont)}\""
            attributes += "fo:background-color=\"${hex(theme.surfaceAlt)}\""
        }
        if (span.muted) attributes += "fo:color=\"${hex(theme.muted)}\""
        val parts = span.text.split("\n")
        val content = buildString {
            parts.forEachIndexed { index, part ->
                if (index > 0) append("<text:line-break/>")
                append(Xml.escape(Xml.sanitize(part)))
            }
        }
        if (attributes.isEmpty()) return content
        return "<text:span " + attributes.joinToString(" ") + ">$content</text:span>"
    }
}

/* -------------------------------------------------------------------------- */
/* Image helpers                                                              */
/* -------------------------------------------------------------------------- */

private fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 16) return null
    if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
        val w = int32(bytes, 16)
        val h = int32(bytes, 20)
        return if (w > 0 && h > 0) w to h else null
    }
    if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) {
        val w = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val h = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        return if (w > 0 && h > 0) w to h else null
    }
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
        var index = 2
        while (index + 9 < bytes.size) {
            if ((bytes[index].toInt() and 0xFF) != 0xFF) {
                index += 1
                continue
            }
            val marker = bytes[index + 1].toInt() and 0xFF
            val length = ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)
            val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrame) {
                val h = ((bytes[index + 5].toInt() and 0xFF) shl 8) or (bytes[index + 6].toInt() and 0xFF)
                val w = ((bytes[index + 7].toInt() and 0xFF) shl 8) or (bytes[index + 8].toInt() and 0xFF)
                return if (w > 0 && h > 0) w to h else null
            }
            if (length <= 0) break
            index += 2 + length
        }
    }
    return null
}

private fun int32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
