package com.teamshryne.kino.office.odf

import com.teamshryne.kino.office.ReportIssue
import com.teamshryne.kino.office.image.ImageData
import com.teamshryne.kino.office.image.Images
import com.teamshryne.kino.office.model.Block
import com.teamshryne.kino.office.model.CalloutBlock
import com.teamshryne.kino.office.model.CalloutKind
import com.teamshryne.kino.office.model.ChartBlock
import com.teamshryne.kino.office.model.CodeBlock
import com.teamshryne.kino.office.model.ColumnAlignment
import com.teamshryne.kino.office.model.DividerBlock
import com.teamshryne.kino.office.model.DocumentSpec
import com.teamshryne.kino.office.model.HeadingBlock
import com.teamshryne.kino.office.model.ImageBlock
import com.teamshryne.kino.office.model.InlineMarkup
import com.teamshryne.kino.office.model.KeyValueBlock
import com.teamshryne.kino.office.model.ListBlock
import com.teamshryne.kino.office.model.Orientation
import com.teamshryne.kino.office.model.PageBreakBlock
import com.teamshryne.kino.office.model.ParagraphBlock
import com.teamshryne.kino.office.model.QuoteBlock
import com.teamshryne.kino.office.model.Span
import com.teamshryne.kino.office.model.SpacerBlock
import com.teamshryne.kino.office.model.StatBlock
import com.teamshryne.kino.office.model.TableBlock
import com.teamshryne.kino.office.model.TocBlock
import com.teamshryne.kino.office.render.Colors
import com.teamshryne.kino.office.render.PageGeometry
import com.teamshryne.kino.office.theme.OfficeTheme
import com.teamshryne.kino.office.xml.Xml
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Writes a document specification as an OpenDocument Text (.odt) file.
 *
 * The package is a plain ZIP that follows the OpenDocument conventions: the
 * `mimetype` entry comes first and is stored uncompressed, the manifest
 * declares every part, and the content is flat ODF XML.
 *
 * Where OpenDocument has richer support than the document model needs (a live
 * table of contents, native charts) the writer falls back to a static,
 * dependable rendering instead of emitting structures readers may reject.
 */
object OdfTextWriter {

    fun write(
        document: DocumentSpec,
        theme: OfficeTheme,
        geometry: PageGeometry,
        issues: MutableList<ReportIssue> = emptyList(),
    ): ByteArray {
        val builder = OdtBuilder(theme, geometry, issues)
        return builder.build(document)
    }
}

/* -------------------------------------------------------------------------- */
/* Constants                                                                  */
/* -------------------------------------------------------------------------- */

private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

private const val MIMETYPE = "application/vnd.oasis.opendocument.text"

private const val NS_OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
private const val NS_TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
private const val NS_STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
private const val NS_FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
private const val NS_TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
private const val NS_DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
private const val NS_SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
private const val NS_XLINK = "http://www.w3.org/1999/xlink"
private const val NS_DC = "http://purl.org/dc/elements/1.1/"
private const val NS_META = "urn:oasis:names:tc:opendocument:xmlns:meta:1.0"
private const val NS_NUMBER = "urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0"
private const val NS_MANIFEST = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"

private const val ODF_VERSION = "1.2"

private fun hex(color: String): String = color.removePrefix("#").trim().uppercase()

private fun ColumnAlignment.align(): String = when (this) {
    ColumnAlignment.LEFT -> "start"
    ColumnAlignment.CENTER -> "center"
    ColumnAlignment.RIGHT -> "end"
}

/* -------------------------------------------------------------------------- */
/* Builder                                                                    */
/* -------------------------------------------------------------------------- */

private class OdtBuilder(
    private val theme: OfficeTheme,
    private val geometry: PageGeometry,
    private val issues: MutableList<ReportIssue>,
) {

    private val automaticStyles = StringBuilder()
    private val pictures = mutableListOf<Pair<String, ImageData>>()
    private var tableCounter = 0

    private val contentWidthCm: Double
        get() = geometry.contentWidthPx * 25.4 / 96.0

    fun build(document: DocumentSpec): ByteArray {
        val content = buildContent(document)
        val parts = listOf(
            "META-INF/manifest.xml" to manifest(),
            "content.xml" to content,
            "styles.xml" to styles(),
            "meta.xml" to meta(document),
            "settings.xml" to settings(),
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
        append("<manifest:file-entry manifest:full-path=\"settings.xml\" manifest:media-type=\"text/xml\"/>")
        pictures.forEach { (name, data) ->
            append(
                "<manifest:file-entry manifest:full-path=\"Pictures/$name\" " +
                    "manifest:media-type=\"${Xml.escape(data.mimeType)}\"/>",
            )
        }
        append("</manifest:manifest>")
    }

    private fun meta(document: DocumentSpec): String = buildString {
        append(XML_DECLARATION)
        append("<office:document-meta xmlns:office=\"$NS_OFFICE\" xmlns:dc=\"$NS_DC\" xmlns:meta=\"$NS_META\" office:version=\"$ODF_VERSION\">")
        append("<office:meta>")
        append("<dc:title>${Xml.escape(document.title)}</dc:title>")
        append("<dc:creator>${Xml.escape(document.author ?: "Kino")}</dc:creator>")
        append("<dc:date>2024-01-01T00:00:00</dc:date>")
        append("<meta:generator>Kino</meta:generator>")
        append("</office:meta></office:document-meta>")
    }

    private fun settings(): String = buildString {
        append(XML_DECLARATION)
        append(
            "<office:document-settings xmlns:office=\"$NS_OFFICE\" " +
                "xmlns:config=\"urn:oasis:names:tc:opendocument:xmlns:config:1.0\" office:version=\"$ODF_VERSION\">",
        )
        append(
            "<office:settings>" +
                "<config:config-item-set config:name=\"ooo:view-settings\">" +
                "<config:config-item-map-indexed config:name=\"Views\">" +
                "<config:config-item-map-entry>" +
                "<config:config-item config:name=\"ViewId\" config:type=\"string\">view1</config:config-item>" +
                "</config:config-item-map-entry></config:config-item-map-indexed>" +
                "</config:config-item-set></office:settings>",
        )
        append("</office:document-settings>")
    }

    /* -------------------------------- styles ------------------------------- */

    private fun styles(): String = buildString {
        append(XML_DECLARATION)
        append(
            "<office:document-styles xmlns:office=\"$NS_OFFICE\" xmlns:text=\"$NS_TEXT\" " +
                "xmlns:style=\"$NS_STYLE\" xmlns:fo=\"$NS_FO\" xmlns:table=\"$NS_TABLE\" " +
                "xmlns:draw=\"$NS_DRAW\" xmlns:svg=\"$NS_SVG\" xmlns:xlink=\"$NS_XLINK\" " +
                "xmlns:dc=\"$NS_DC\" xmlns:meta=\"$NS_META\" office:version=\"$ODF_VERSION\">",
        )

        append("<office:styles>")
        append(
            "<style:style style:name=\"Standard\" style:family=\"paragraph\" style:class=\"text\">" +
                "<style:paragraph-properties fo:margin-top=\"0cm\" fo:margin-bottom=\"0.2cm\" fo:line-height=\"115%\"/>" +
                "<style:text-properties fo:font-family=\"${Xml.escape(theme.sansFont)}\" fo:font-size=\"10.5pt\" " +
                "fo:color=\"${hex(theme.ink)}\"/></style:style>",
        )
        append(
            "<style:style style:name=\"Header\" style:family=\"paragraph\" style:parent-style-name=\"Standard\">" +
                "<style:paragraph-properties fo:border-bottom=\"0.5pt solid ${hex(theme.border)}\" " +
                "fo:padding=\"0cm 0cm 0.1cm 0cm\" fo:margin-bottom=\"0.15cm\"/>" +
                "<style:text-properties fo:font-size=\"8pt\" fo:color=\"${hex(theme.muted)}\"/></style:style>",
        )
        append(
            "<style:style style:name=\"Footer\" style:family=\"paragraph\" style:parent-style-name=\"Standard\">" +
                "<style:paragraph-properties fo:border-top=\"0.5pt solid ${hex(theme.border)}\" " +
                "fo:padding=\"0.1cm 0cm 0cm 0cm\" fo:margin-top=\"0.15cm\">" +
                "<style:tab-stops><style:tab-stop style:position=\"${cm(contentWidthCm)}\" style:type=\"right\"/></style:tab-stops>" +
                "</style:paragraph-properties>" +
                "<style:text-properties fo:font-size=\"8pt\" fo:color=\"${hex(theme.muted)}\"/></style:style>",
        )
        append(
            "<style:style style:name=\"Internet_20_link\" style:family=\"text\">" +
                "<style:text-properties fo:color=\"${hex(theme.accent)}\" " +
                "style:text-underline-style=\"solid\" style:text-underline-width=\"auto\" style:text-underline-color=\"font\"/>" +
                "</style:style>",
        )
        append("</office:styles>")

        append("<office:automatic-styles>")
        val landscape = geometry.orientation == Orientation.LANDSCAPE
        val width = cm(geometry.widthPt * 25.4 / 72.0)
        val height = cm(geometry.heightPt * 25.4 / 72.0)
        append(
            "<style:page-layout style:name=\"pm1\">" +
                "<style:page-layout-properties fo:page-width=\"$width\" fo:page-height=\"$height\" " +
                "style:print-orientation=\"${if (landscape) "landscape" else "portrait"}\" " +
                "fo:margin-top=\"${cm(geometry.marginTopPx * 25.4 / 96.0)}\" " +
                "fo:margin-bottom=\"${cm(geometry.marginBottomPx * 25.4 / 96.0)}\" " +
                "fo:margin-left=\"${cm(geometry.marginLeftPx * 25.4 / 96.0)}\" " +
                "fo:margin-right=\"${cm(geometry.marginRightPx * 25.4 / 96.0)}\" " +
                "style:writing-mode=\"lr-tb\"/></style:page-layout>",
        )
        append("</office:automatic-styles>")

        append("<office:master-styles>")
        append("<style:master-page style:name=\"Standard\" style:page-layout-name=\"pm1\">")
        append("<style:header><text:p text:style-name=\"Header\">${Xml.escape(headerText ?: "")}</text:p></style:header>")
        append("<style:footer>")
        append(
            "<text:p text:style-name=\"Footer\">" +
                footerText?.let { "<text:span>${Xml.escape(it)}</text:span><text:tab/>" } .orEmpty() +
                "<text:span>Page </text:span>" +
                "<text:page-number text:select-page=\"current\">1</text:page-number>" +
                "<text:span> of </text:span>" +
                "<text:page-count>1</text:page-count>" +
                "</text:p>",
        )
        append("</style:footer>")
        append("</style:master-page>")
        append("</office:master-styles>")

        append("</office:document-styles>")
    }

    private var headerText: String? = null
    private var footerText: String? = null

    /* ------------------------------- content ------------------------------- */

    private fun buildContent(document: DocumentSpec): String {
        headerText = document.header?.trim()?.takeIf { it.isNotEmpty() } ?: document.title
        footerText = document.footer?.trim()?.takeIf { it.isNotEmpty() }
        installStyles()

        val body = StringBuilder()
        if (document.cover) appendCover(body, document)
        var tocWritten = false
        if (document.toc) {
            appendContents(body, document)
            tocWritten = true
        }
        document.blocks.forEach { block ->
            if (block is TocBlock) {
                if (!tocWritten) {
                    appendContents(body, document)
                    tocWritten = true
                }
            } else {
                appendBlock(body, block)
            }
        }

        return buildString {
            append(XML_DECLARATION)
            append(
                "<office:document-content xmlns:office=\"$NS_OFFICE\" xmlns:text=\"$NS_TEXT\" " +
                    "xmlns:style=\"$NS_STYLE\" xmlns:fo=\"$NS_FO\" xmlns:table=\"$NS_TABLE\" " +
                    "xmlns:draw=\"$NS_DRAW\" xmlns:svg=\"$NS_SVG\" xmlns:xlink=\"$NS_XLINK\" " +
                    "xmlns:dc=\"$NS_DC\" xmlns:meta=\"$NS_META\" xmlns:number=\"$NS_NUMBER\" " +
                    "office:version=\"$ODF_VERSION\">",
            )
            append("<office:automatic-styles>")
            append(automaticStyles)
            append("</office:automatic-styles>")
            append("<office:body><office:text>")
            append(body)
            append("</office:text></office:body>")
            append("</office:document-content>")
        }
    }

    private fun appendCover(body: StringBuilder, document: DocumentSpec) {
        body.append("<text:p text:style-name=\"Title\">${Xml.escape(InlineMarkup.plain(document.title))}</text:p>")
        document.subtitle?.takeIf { it.isNotBlank() }?.let {
            body.append("<text:p text:style-name=\"Subtitle\">${Xml.escape(InlineMarkup.plain(it))}</text:p>")
        }
        val meta = listOfNotNull(
            document.author?.takeIf { it.isNotBlank() },
            document.date?.takeIf { it.isNotBlank() },
        ).joinToString("  \u00b7  ")
        if (meta.isNotEmpty()) {
            body.append("<text:p text:style-name=\"CoverMeta\">${Xml.escape(meta)}</text:p>")
        }
        body.append("<text:p text:style-name=\"CoverRule\"/>")
        body.append("<text:p text:style-name=\"Standard\"><text:soft-page-break/></text:p>")
    }

    private fun appendContents(body: StringBuilder, document: DocumentSpec) {
        body.append("<text:h text:style-name=\"Heading_20_1\" text:outline-level=\"1\">Contents</text:h>")
        val headings = document.blocks.filterIsInstance<HeadingBlock>()
        if (headings.isEmpty()) {
            body.append("<text:p text:style-name=\"Standard\">This document has no headings yet.</text:p>")
            return
        }
        headings.filter { it.level <= 3 }.forEach { heading ->
            val style = when (heading.level.coerceIn(1, 3)) {
                1 -> "Toc1"
                2 -> "Toc2"
                else -> "Toc3"
            }
            body.append(
                "<text:p text:style-name=\"$style\">${Xml.escape(InlineMarkup.plain(heading.text))}</text:p>",
            )
        }
        body.append("<text:p text:style-name=\"Standard\"><text:soft-page-break/></text:p>")
    }

    private fun appendBlock(body: StringBuilder, block: Block) {
        when (block) {
            is HeadingBlock -> {
                val level = block.level.coerceIn(1, 4)
                body.append(
                    "<text:h text:style-name=\"Heading_20_$level\" text:outline-level=\"$level\">" +
                        "${spans(block.text)}</text:h>",
                )
            }

            is ParagraphBlock -> body.append("<text:p text:style-name=\"Standard\">${spans(block.text)}</text:p>")

            is ListBlock -> appendList(body, block)

            is TableBlock -> appendTable(body, block)

            is KeyValueBlock -> appendKeyValue(body, block)

            is CalloutBlock -> appendCallout(body, block)

            is QuoteBlock -> {
                body.append("<text:p text:style-name=\"Quote\">${spans(block.text)}</text:p>")
                block.attribution?.takeIf { it.isNotBlank() }?.let {
                    body.append("<text:p text:style-name=\"QuoteAttribution\">${Xml.escape(InlineMarkup.plain(it))}</text:p>")
                }
            }

            is CodeBlock -> appendCode(body, block)

            is ImageBlock -> appendImage(body, block)

            is StatBlock -> appendStats(body, block)

            is ChartBlock -> appendChart(body, block)

            is DividerBlock -> body.append("<text:p text:style-name=\"Divider\"/>")

            is PageBreakBlock -> body.append("<text:p text:style-name=\"Standard\"><text:soft-page-break/></text:p>")

            is SpacerBlock -> body.append("<text:p text:style-name=\"Spacer\"/>")

            is TocBlock -> Unit
        }
    }

    private fun appendList(body: StringBuilder, block: ListBlock) {
        val style = if (block.ordered) "NumberedList" else "BulletList"
        val start = if (block.ordered && block.startAt != 1) " text:continue-numbering=\"${block.startAt}\"" else ""
        body.append("<text:list text:style-name=\"$style\"$start>")
        block.items.forEach { item ->
            body.append("<text:list-item><text:p text:style-name=\"Standard\">${spans(item)}</text:p></text:list-item>")
        }
        body.append("</text:list>")
    }

    private fun appendTable(body: StringBuilder, block: TableBlock) {
        if (block.columns.isEmpty()) return
        val name = "Table${++tableCounter}"
        val alignments = block.effectiveAlignments
        val widths = columnWidths(block.columns.map { it.width }, block.columns.size)
        val headerStyle = "${name}_Header"
        val cellStyle = "${name}_Cell"

        style(
            name = name,
            family = "table",
            properties = "<style:table-properties style:width=\"${cm(contentWidthCm)}\" fo:margin-left=\"0cm\"/>",
        )
        widths.forEachIndexed { index, width ->
            style(
                name = "${name}_Column$index",
                family = "table-column",
                properties = "<style:table-column-properties style:column-width=\"${cm(width)}\" " +
                    "style:use-optimal-column-width=\"false\"/>",
            )
        }
        style(
            name = headerStyle,
            family = "table-cell",
            properties = "<style:table-cell-properties fo:background-color=\"${hex(theme.accent)}\" " +
                "fo:padding=\"0.12cm\" fo:border=\"0.05pt solid ${hex(theme.accent)}\"/>",
            paragraph = "<style:paragraph-properties fo:text-align=\"start\"/>",
            text = "<style:text-properties fo:font-weight=\"bold\" fo:color=\"${hex(theme.background)}\" fo:font-size=\"9.5pt\"/>",
        )
        style(
            name = cellStyle,
            family = "table-cell",
            properties = "<style:table-cell-properties fo:padding=\"0.1cm\" fo:border=\"0.05pt solid ${hex(theme.border)}\"/>",
        )
        style(
            name = "${name}_CellAlt",
            family = "table-cell",
            properties = "<style:table-cell-properties fo:padding=\"0.1cm\" fo:border=\"0.05pt solid ${hex(theme.border)}\" " +
                "fo:background-color=\"${hex(theme.surface)}\"/>",
        )

        body.append("<table:table table:name=\"$name\" table:style-name=\"$name\">")
        widths.forEachIndexed { index, _ ->
            body.append("<table:table-column table:style-name=\"${name}_Column$index\"/>")
        }
        body.append("<table:table-header-rows><table:table-row>")
        block.columns.forEachIndexed { index, column ->
            val alignment = alignments.getOrElse(index) { ColumnAlignment.LEFT }
            body.append(
                "<table:table-cell table:style-name=\"$headerStyle\" office:value-type=\"string\">" +
                    "<text:p text:style-name=\"TableText\" fo:text-align=\"${alignment.align()}\">" +
                    "${Xml.escape(InlineMarkup.plain(column.header))}</text:p></table:table-cell>",
            )
        }
        body.append("</table:table-row></table:table-header-rows>")

        block.rows.forEachIndexed { rowIndex, row ->
            val rowStyle = if (block.zebra && rowIndex % 2 == 1) "${name}_CellAlt" else cellStyle
            body.append("<table:table-row>")
            row.forEachIndexed { index, value ->
                val alignment = alignments.getOrElse(index) { ColumnAlignment.LEFT }
                body.append(
                    "<table:table-cell table:style-name=\"$rowStyle\" office:value-type=\"string\">" +
                        "<text:p text:style-name=\"TableText\" fo:text-align=\"${alignment.align()}\">" +
                        "${spans(value)}</text:p></table:table-cell>",
                )
            }
            body.append("</table:table-row>")
        }
        body.append("</table:table>")
        body.append("<text:p text:style-name=\"Standard\"/>")
        block.caption?.takeIf { it.isNotBlank() }?.let {
            body.append("<text:p text:style-name=\"Caption\">${Xml.escape(InlineMarkup.plain(it))}</text:p>")
        }
    }

    private fun appendKeyValue(body: StringBuilder, block: KeyValueBlock) {
        if (block.items.isEmpty()) return
        val name = "KeyValue${++tableCounter}"
        val labelWidth = contentWidthCm * 0.34
        val valueWidth = contentWidthCm - labelWidth
        style(
            name = name,
            family = "table",
            properties = "<style:table-properties style:width=\"${cm(contentWidthCm)}\" fo:margin-left=\"0cm\"/>",
        )
        style(
            name = "${name}_Label",
            family = "table-cell",
            properties = "<style:table-cell-properties fo:padding=\"0.08cm 0.15cm 0.08cm 0cm\" fo:border=\"none\"/>",
            text = "<style:text-properties fo:font-weight=\"bold\" fo:font-size=\"8.5pt\" fo:color=\"${hex(theme.muted)}\"/>",
        )
        style(
            name = "${name}_Value",
            family = "table-cell",
            properties = "<style:table-cell-properties fo:padding=\"0.08cm 0cm\" fo:border=\"none\"/>",
            text = "<style:text-properties fo:font-weight=\"bold\" fo:color=\"${hex(theme.ink)}\"/>",
        )
        style(
            name = "${name}_Column0",
            family = "table-column",
            properties = "<style:table-column-properties style:column-width=\"${cm(labelWidth)}\"/>",
        )
        style(
            name = "${name}_Column1",
            family = "table-column",
            properties = "<style:table-column-properties style:column-width=\"${cm(valueWidth)}\"/>",
        )
        body.append("<table:table table:name=\"$name\" table:style-name=\"$name\">")
        body.append("<table:table-column table:style-name=\"${name}_Column0\"/>")
        body.append("<table:table-column table:style-name=\"${name}_Column1\"/>")
        block.items.forEach { item ->
            body.append("<table:table-row>")
            body.append(
                "<table:table-cell table:style-name=\"${name}_Label\" office:value-type=\"string\">" +
                    "<text:p text:style-name=\"TableText\">${Xml.escape(InlineMarkup.plain(item.label))}</text:p></table:table-cell>",
            )
            body.append(
                "<table:table-cell table:style-name=\"${name}_Value\" office:value-type=\"string\">" +
                    "<text:p text:style-name=\"TableText\">${spans(item.value)}</text:p>",
            )
            item.hint?.takeIf { it.isNotBlank() }?.let {
                body.append(
                    "<text:p text:style-name=\"TableHint\">${Xml.escape(InlineMarkup.plain(it))}</text:p>",
                )
            }
            body.append("</table:table-cell>")
            body.append("</table:table-row>")
        }
        body.append("</table:table>")
        body.append("<text:p text:style-name=\"Standard\"/>")
    }

    private fun appendCallout(body: StringBuilder, block: CalloutBlock) {
        val color = calloutColor(block.kind)
        val background = hex(Colors.lighten(color, 0.9))
        val name = "Callout${++tableCounter}"
        style(
            name = name,
            family = "table",
            properties = "<style:table-properties style:width=\"${cm(contentWidthCm)}\" fo:margin-left=\"0cm\"/>",
        )
        style(
            name = "${name}_Cell",
            family = "table-cell",
            properties = "<style:table-cell-properties fo:background-color=\"#$background\" fo:border=\"none\" " +
                "fo:border-left=\"0.06cm solid ${hex(color)}\" fo:padding=\"0.2cm\"/>",
        )
        style(
            name = "${name}_Column",
            family = "table-column",
            properties = "<style:table-column-properties style:column-width=\"${cm(contentWidthCm)}\"/>",
        )
        body.append("<table:table table:name=\"$name\" table:style-name=\"$name\">")
        body.append("<table:table-column table:style-name=\"${name}_Column\"/>")
        body.append("<table:table-row><table:table-cell table:style-name=\"${name}_Cell\" office:value-type=\"string\">")
        block.title?.takeIf { it.isNotBlank() }?.let {
            body.append("<text:p text:style-name=\"CalloutTitle\">${Xml.escape(InlineMarkup.plain(it))}</text:p>")
        }
        body.append("<text:p text:style-name=\"CalloutBody\">${spans(block.text)}</text:p>")
        body.append("</table:table-cell></table:table-row></table:table>")
        body.append("<text:p text:style-name=\"Standard\"/>")
    }

    private fun appendCode(body: StringBuilder, block: CodeBlock) {
        val lines = block.text.split("\n")
        val content = lines.joinToString("<text:line-break/>") { Xml.escape(Xml.sanitize(it)) }
        body.append("<text:p text:style-name=\"Preformatted_20_Text\">")
        body.append("<text:span text:style-name=\"CodeChar\" xml:space=\"preserve\">$content</text:span>")
        body.append("</text:p>")
    }

    private fun appendImage(body: StringBuilder, block: ImageBlock) {
        val data = resolveImage(block.source)
        if (data == null) {
            issues += ReportIssue(
                ReportIssue.Severity.WARNING,
                "The image \"${block.source}\" could not be embedded in the OpenDocument file.",
                path = "blocks.image",
            )
            body.append(
                "<text:p text:style-name=\"Figure\">" +
                    "<text:span text:style-name=\"Muted\">" +
                    "${Xml.escape(block.alt ?: "Image unavailable: ${block.source}")}</text:span></text:p>",
            )
        } else {
            val name = "image${pictures.size + 1}.${data.extension.lowercase().ifBlank { "png" }}"
            pictures += name to data
            val (widthPx, heightPx) = imageSize(data.bytes) ?: (600 to 400)
            val maxWidth = geometry.contentWidthPx * block.maxWidthPercent.coerceIn(10, 100) / 100
            val scale = if (widthPx > 0) minOf(1.0, maxWidth.toDouble() / widthPx) else 1.0
            val width = widthPx * scale * 25.4 / 96.0
            val height = heightPx * scale * 25.4 / 96.0
            body.append(
                "<text:p text:style-name=\"Figure\">" +
                    "<draw:frame draw:style-name=\"FigureFrame\" draw:name=\"Image${pictures.size}\" " +
                    "text:anchor-type=\"as-char\" svg:width=\"${cm(width)}\" svg:height=\"${cm(height)}\" draw:z-index=\"0\">" +
                    "<draw:image xlink:href=\"Pictures/$name\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/>" +
                    "</draw:frame></text:p>",
            )
        }
        block.caption?.takeIf { it.isNotBlank() }?.let {
            body.append("<text:p text:style-name=\"Caption\">${Xml.escape(InlineMarkup.plain(it))}</text:p>")
        }
    }

    private fun appendStats(body: StringBuilder, block: StatBlock) {
        if (block.items.isEmpty()) return
        val name = "Stats${++tableCounter}"
        val labelWidth = contentWidthCm * 0.36
        val valueWidth = contentWidthCm * 0.38
        val deltaWidth = contentWidthCm - labelWidth - valueWidth
        style(
            name = name,
            family = "table",
            properties = "<style:table-properties style:width=\"${cm(contentWidthCm)}\" fo:margin-left=\"0cm\"/>",
        )
        style(
            name = "${name}_Label",
            family = "table-cell",
            properties = "<style:table-cell-properties fo:padding=\"0.12cm\" fo:border=\"0.05pt solid ${hex(theme.border)}\"/>",
            text = "<style:text-properties fo:font-weight=\"bold\" fo:font-size=\"8.5pt\" fo:color=\"${hex(theme.muted)}\"/>",
        )
        style(
            name = "${name}_Value",
            family = "table-cell",
            properties = "<style:table-cell-properties fo:padding=\"0.12cm\" fo:border=\"0.05pt solid ${hex(theme.border)}\"/>",
            text = "<style:text-properties fo:font-weight=\"bold\" fo:font-size=\"19pt\" fo:color=\"${hex(theme.ink)}\"/>",
        )
        style(
            name = "${name}_Delta",
            family = "table-cell",
            properties = "<style:table-cell-properties fo:padding=\"0.12cm\" fo:border=\"0.05pt solid ${hex(theme.border)}\"/>",
            text = "<style:text-properties fo:font-weight=\"bold\" fo:font-size=\"9pt\"/>",
        )
        body.append("<table:table table:name=\"$name\" table:style-name=\"$name\">")
        body.append("<table:table-column table:style-name=\"${name}_Column0\"/>")
        body.append("<table:table-column table:style-name=\"${name}_Column1\"/>")
        body.append("<table:table-column table:style-name=\"${name}_Column2\"/>")
        style(
            name = "${name}_Column0",
            family = "table-column",
            properties = "<style:table-column-properties style:column-width=\"${cm(labelWidth)}\"/>",
        )
        style(
            name = "${name}_Column1",
            family = "table-column",
            properties = "<style:table-column-properties style:column-width=\"${cm(valueWidth)}\"/>",
        )
        style(
            name = "${name}_Column2",
            family = "table-column",
            properties = "<style:table-column-properties style:column-width=\"${cm(deltaWidth)}\"/>",
        )
        block.items.forEach { stat ->
            body.append("<table:table-row>")
            body.append(
                "<table:table-cell table:style-name=\"${name}_Label\" office:value-type=\"string\">" +
                    "<text:p text:style-name=\"TableText\">${Xml.escape(InlineMarkup.plain(stat.label))}</text:p></table:table-cell>",
            )
            body.append(
                "<table:table-cell table:style-name=\"${name}_Value\" office:value-type=\"string\">" +
                    "<text:p text:style-name=\"TableText\">${Xml.escape(InlineMarkup.plain(stat.value))}</text:p></table:table-cell>",
            )
            val color = when (stat.positive) {
                true -> hex(theme.positive)
                false -> hex(theme.negative)
                null -> hex(theme.muted)
            }
            body.append(
                "<table:table-cell table:style-name=\"${name}_Delta\" office:value-type=\"string\">" +
                    "<text:p text:style-name=\"TableText\" fo:color=\"#$color\">" +
                    "${stat.delta?.takeIf { it.isNotBlank() }?.let { Xml.escape(InlineMarkup.plain(it)) } ?: ""}" +
                    "</text:p></table:table-cell>",
            )
            body.append("</table:table-row>")
        }
        body.append("</table:table>")
        body.append("<text:p text:style-name=\"Standard\"/>")
    }

    private fun appendChart(body: StringBuilder, block: ChartBlock) {
        issues += ReportIssue(
            ReportIssue.Severity.WARNING,
            "The chart${block.title?.let { " \"$it\"" } ?: ""} was converted to a table because the " +
                "OpenDocument writer does not embed native charts.",
            path = "blocks.chart",
        )
        if (block.series.isEmpty()) {
            body.append(
                "<text:p text:style-name=\"Caption\">${Xml.escape(block.title ?: "Chart")}</text:p>",
            )
            return
        }
        val name = "Chart${++tableCounter}"
        val firstWidth = contentWidthCm * 0.34
        val otherWidth = (contentWidthCm - firstWidth) / block.series.size
        style(
            name = name,
            family = "table",
            properties = "<style:table-properties style:width=\"${cm(contentWidthCm)}\" fo:margin-left=\"0cm\"/>",
        )
        style(
            name = "${name}_Header",
            family = "table-cell",
            properties = "<style:table-cell-properties fo:background-color=\"${hex(theme.accent)}\" " +
                "fo:padding=\"0.12cm\" fo:border=\"0.05pt solid ${hex(theme.accent)}\"/>",
            text = "<style:text-properties fo:font-weight=\"bold\" fo:color=\"${hex(theme.background)}\" fo:font-size=\"9.5pt\"/>",
        )
        style(
            name = "${name}_Cell",
            family = "table-cell",
            properties = "<style:table-cell-properties fo:padding=\"0.1cm\" fo:border=\"0.05pt solid ${hex(theme.border)}\"/>",
        )
        body.append("<table:table table:name=\"$name\" table:style-name=\"$name\">")
        body.append("<table:table-column table:style-name=\"${name}_Column0\"/>")
        style(
            name = "${name}_Column0",
            family = "table-column",
            properties = "<style:table-column-properties style:column-width=\"${cm(firstWidth)}\"/>",
        )
        block.series.forEachIndexed { index, _ ->
            body.append("<table:table-column table:style-name=\"${name}_Column${index + 1}\"/>")
            style(
                name = "${name}_Column${index + 1}",
                family = "table-column",
                properties = "<style:table-column-properties style:column-width=\"${cm(otherWidth)}\"/>",
            )
        }
        body.append("<table:table-header-rows><table:table-row>")
        body.append(
            "<table:table-cell table:style-name=\"${name}_Header\" office:value-type=\"string\">" +
                "<text:p text:style-name=\"TableText\">Category</text:p></table:table-cell>",
        )
        block.series.forEach { series ->
            body.append(
                "<table:table-cell table:style-name=\"${name}_Header\" office:value-type=\"string\">" +
                    "<text:p text:style-name=\"TableText\" fo:text-align=\"end\">" +
                    "${Xml.escape(InlineMarkup.plain(series.name))}</text:p></table:table-cell>",
            )
        }
        body.append("</table:table-row></table:table-header-rows>")

        val categories = block.categories.ifEmpty { List(block.series.firstOrNull()?.values?.size ?: 0) { "Item" } }
        categories.forEachIndexed { rowIndex, category ->
            body.append("<table:table-row>")
            body.append(
                "<table:table-cell table:style-name=\"${name}_Cell\" office:value-type=\"string\">" +
                    "<text:p text:style-name=\"TableText\">${Xml.escape(InlineMarkup.plain(category))}</text:p></table:table-cell>",
            )
            block.series.forEach { series ->
                val value = series.values.getOrNull(rowIndex) ?: 0.0
                body.append(
                    "<table:table-cell table:style-name=\"${name}_Cell\" office:value-type=\"string\">" +
                        "<text:p text:style-name=\"TableText\" fo:text-align=\"end\">${formatNumber(value)}</text:p></table:table-cell>",
                )
            }
            body.append("</table:table-row>")
        }
        body.append("</table:table>")
        body.append("<text:p text:style-name=\"Standard\"/>")
        block.title?.takeIf { it.isNotBlank() }?.let {
            body.append("<text:p text:style-name=\"Caption\">${Xml.escape(InlineMarkup.plain(it))}</text:p>")
        }
    }

    /* ------------------------------- helpers ------------------------------- */

    private fun resolveImage(source: String): ImageData? {
        val direct = Images.decodeDataUri(source)
        if (direct != null) return direct
        return null
    }

    private fun calloutColor(kind: CalloutKind): String = when (kind) {
        CalloutKind.INFO -> theme.accent
        CalloutKind.NOTE -> theme.inkSoft
        CalloutKind.WARNING -> theme.warning
        CalloutKind.SUCCESS -> theme.positive
        CalloutKind.DANGER -> theme.negative
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)

    private fun columnWidths(widths: List<Double?>, count: Int): List<Double> {
        val weights = widths.map { it ?: 0.0 }
        val sum = weights.sum()
        return if (sum > 0.0 && weights.all { it > 0.0 }) {
            weights.map { contentWidthCm * (it / sum) }
        } else {
            List(count) { contentWidthCm / count }
        }
    }

    private fun cm(value: Double): String = "%.2fcm".format(value)

    private fun style(
        name: String,
        family: String,
        properties: String? = null,
        paragraph: String? = null,
        text: String? = null,
        parent: String? = null,
    ) {
        automaticStyles.append("<style:style style:name=\"$name\" style:family=\"$family\"")
        parent?.let { automaticStyles.append(" style:parent-style-name=\"$it\"") }
        automaticStyles.append(">")
        properties?.let { automaticStyles.append(it) }
        paragraph?.let { automaticStyles.append(it) }
        text?.let { automaticStyles.append(it) }
        automaticStyles.append("</style:style>")
    }

    private fun spans(text: String): String = InlineMarkup.parse(text).joinToString("") { span -> spanXml(span) }

    private fun spanXml(span: Span): String {
        val attributes = mutableListOf<String>()
        if (span.bold) attributes += "fo:font-weight=\"bold\""
        if (span.italic) attributes += "fo:font-style=\"italic\""
        if (span.strike) attributes += "style:text-line-through-style=\"solid\""
        if (span.code) {
            attributes += "fo:font-family=\"${Xml.escape(theme.monoFont)}\""
            attributes += "fo:background-color=\"${hex(theme.surfaceAlt)}\""
        }
        if (span.muted) attributes += "fo:color=\"${hex(theme.muted)}\""
        val link = span.link
        if (!link.isNullOrBlank()) {
            val content = Xml.escape(Xml.sanitize(span.text))
            return "<text:a xlink:href=\"${Xml.escape(link)}\" text:style-name=\"Internet_20_link\">$content</text:a>"
        }
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

    /** Installs the paragraph and list styles used by the content. */
    private fun installStyles() {
        val t = theme
        val scale = t.headingScale
        val sans = Xml.escape(t.sansFont)
        val serif = Xml.escape(t.serifFont)
        val mono = Xml.escape(t.monoFont)

        style(
            name = "Title",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-top=\"2.5cm\" fo:margin-bottom=\"0.3cm\" fo:keep-with-next=\"always\"/>",
            text = "<style:text-properties fo:font-family=\"$serif\" fo:font-weight=\"bold\" " +
                "fo:font-size=\"${(30 * scale).roundToInt()}pt\" fo:color=\"${hex(t.accent)}\"/>",
        )
        style(
            name = "Subtitle",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-bottom=\"0.5cm\"/>",
            text = "<style:text-properties fo:font-family=\"$serif\" fo:font-size=\"13pt\" fo:color=\"${hex(t.inkSoft)}\"/>",
        )
        style(
            name = "CoverMeta",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-bottom=\"0.4cm\"/>",
            text = "<style:text-properties fo:font-size=\"9pt\" fo:color=\"${hex(t.muted)}\" fo:font-variant=\"small-caps\"/>",
        )
        style(
            name = "CoverRule",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:border-bottom=\"0.1cm solid ${hex(t.accent)}\" " +
                "fo:margin-bottom=\"0.6cm\" fo:margin-right=\"8cm\"/>",
            text = "<style:text-properties fo:font-size=\"2pt\"/>",
        )

        listOf(
            Triple(1, 21.0, t.ink),
            Triple(2, 15.5, t.ink),
            Triple(3, 12.5, t.accent),
            Triple(4, 9.5, t.inkSoft),
        ).forEach { (level, size, color) ->
            style(
                name = "Heading_20_$level",
                family = "paragraph",
                parent = "Standard",
                properties = "<style:paragraph-properties fo:margin-top=\"0.35cm\" fo:margin-bottom=\"0.2cm\" " +
                    "fo:keep-with-next=\"always\"/>",
                text = "<style:text-properties fo:font-family=\"$serif\" fo:font-weight=\"bold\" " +
                    "fo:font-size=\"${(size * scale).roundToInt()}pt\" fo:color=\"${hex(color)}\"/>",
            )
        }

        style(
            name = "Toc1",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-bottom=\"0.05cm\" fo:border-bottom=\"0.05pt dotted ${hex(t.border)}\"/>",
            text = "<style:text-properties fo:font-size=\"10pt\"/>",
        )
        style(
            name = "Toc2",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-left=\"0.5cm\" fo:margin-bottom=\"0.05cm\"/>",
            text = "<style:text-properties fo:font-size=\"9.5pt\" fo:color=\"${hex(t.inkSoft)}\"/>",
        )
        style(
            name = "Toc3",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-left=\"1cm\" fo:margin-bottom=\"0.05cm\"/>",
            text = "<style:text-properties fo:font-size=\"9pt\" fo:color=\"${hex(t.muted)}\"/>",
        )

        style(
            name = "Quote",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-left=\"0.4cm\" fo:margin-top=\"0.15cm\" " +
                "fo:margin-bottom=\"0.1cm\" fo:padding-left=\"0.3cm\" " +
                "fo:border-left=\"0.04cm solid ${hex(t.accent)}\"/>",
            text = "<style:text-properties fo:font-family=\"$serif\" fo:font-style=\"italic\" " +
                "fo:font-size=\"11.5pt\" fo:color=\"${hex(t.inkSoft)}\"/>",
        )
        style(
            name = "QuoteAttribution",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-left=\"0.4cm\" fo:margin-bottom=\"0.3cm\" fo:text-align=\"end\"/>",
            text = "<style:text-properties fo:font-style=\"italic\" fo:font-size=\"9pt\" fo:color=\"${hex(t.muted)}\"/>",
        )

        style(
            name = "Preformatted_20_Text",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:background-color=\"${hex(t.surfaceAlt)}\" " +
                "fo:border=\"0.05pt solid ${hex(t.border)}\" fo:padding=\"0.2cm\" " +
                "fo:margin-top=\"0.15cm\" fo:margin-bottom=\"0.3cm\" fo:line-height=\"115%\"/>",
            text = "<style:text-properties fo:font-family=\"$mono\" fo:font-size=\"9pt\" fo:color=\"${hex(t.ink)}\"/>",
        )
        style(
            name = "CodeChar",
            family = "text",
            text = "<style:text-properties fo:font-family=\"$mono\" fo:font-size=\"9pt\" fo:color=\"${hex(t.ink)}\"/>",
        )

        style(
            name = "Caption",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-bottom=\"0.35cm\"/>",
            text = "<style:text-properties fo:font-style=\"italic\" fo:font-size=\"8.5pt\" fo:color=\"${hex(t.muted)}\"/>",
        )
        style(
            name = "TableText",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-top=\"0cm\" fo:margin-bottom=\"0cm\" fo:line-height=\"120%\"/>",
        )
        style(
            name = "TableHint",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-top=\"0cm\" fo:margin-bottom=\"0cm\"/>",
            text = "<style:text-properties fo:font-style=\"italic\" fo:font-size=\"8.5pt\" fo:color=\"${hex(t.muted)}\"/>",
        )
        style(
            name = "CalloutTitle",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-bottom=\"0.05cm\"/>",
            text = "<style:text-properties fo:font-weight=\"bold\" fo:font-size=\"9.5pt\"/>",
        )
        style(
            name = "CalloutBody",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-bottom=\"0cm\"/>",
        )
        style(
            name = "Divider",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-top=\"0.25cm\" fo:margin-bottom=\"0.25cm\" " +
                "fo:border-bottom=\"0.05pt solid ${hex(t.border)}\"/>",
            text = "<style:text-properties fo:font-size=\"2pt\"/>",
        )
        style(
            name = "Spacer",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:margin-top=\"0cm\" fo:margin-bottom=\"0cm\"/>",
            text = "<style:text-properties fo:font-size=\"6pt\"/>",
        )
        style(
            name = "Figure",
            family = "paragraph",
            parent = "Standard",
            properties = "<style:paragraph-properties fo:text-align=\"center\" fo:margin-bottom=\"0.1cm\"/>",
        )
        style(
            name = "FigureFrame",
            family = "graphic",
            properties = "<style:graphic-properties style:vertical-pos=\"top\" style:vertical-rel=\"baseline\" " +
                "fo:border=\"none\"/>",
        )
        style(
            name = "Muted",
            family = "text",
            text = "<style:text-properties fo:font-style=\"italic\" fo:color=\"${hex(t.muted)}\"/>",
        )

        automaticStyles.append(bulletListStyle())
        automaticStyles.append(numberedListStyle())
    }

    private fun bulletListStyle(): String = buildString {
        append("<text:list-style style:name=\"BulletList\">")
        listOf("\u2022" to 1, "o" to 2, "\u25e6" to 3).forEach { (marker, level) ->
            append(
                "<text:list-level-style-bullet text:level=\"$level\" style:num-format=\"$marker\" " +
                    "style:num-prefix=\"$marker \" style:num-suffix=\" \" style:num-letter-sync=\"true\">" +
                    "<style:list-level-properties text:list-level-position-and-space-mode=\"label-alignment\">" +
                    "<style:list-level-label-alignment text:label-followed-by=\"listitem\" " +
                    "text:list-item-position-and-space=\"space-between\"/>" +
                    "<style:list-level-label-number-format text:num-format=\"$marker\" text:num-letter-sync=\"true\"/>" +
                    "<style:list-level-label-text text:num-letter-sync=\"true\">$marker</style:list-level-label-text>" +
                    "<style:list-level-position space-before=\"0cm\" space-after=\"0cm\"/>" +
                    "<style:list-level-space text:min-label-width=\"0.4cm\" text:max-label-width=\"0.4cm\" " +
                    "text:min-list-label-width=\"0.4cm\" text:max-list-label-width=\"0.4cm\"/>" +
                    "</style:list-level-properties></text:list-level-style-bullet>",
            )
        }
        append("</text:list-style>")
    }

    private fun numberedListStyle(): String = buildString {
        append("<text:list-style style:name=\"NumberedList\">")
        listOf(1, 2, 3).forEach { level ->
            val text = "%$level."
            append(
                "<text:list-level-style-number text:level=\"$level\" style:num-format=\"1\" " +
                    "style:num-suffix=\".\" style:num-letter-sync=\"true\">" +
                    "<style:list-level-properties text:list-level-position-and-space-mode=\"label-alignment\">" +
                    "<style:list-level-label-alignment text:label-followed-by=\"listitem\" " +
                    "text:list-item-position-and-space=\"space-between\"/>" +
                    "<style:list-level-label-number-format text:num-format=\"1\" text:num-letter-sync=\"true\"/>" +
                    "<style:list-level-label-text text:num-letter-sync=\"true\">$text</style:list-level-label-text>" +
                    "<style:list-level-position space-before=\"0cm\" space-after=\"0cm\"/>" +
                    "<style:list-level-space text:min-label-width=\"0.5cm\" text:max-label-width=\"0.5cm\" " +
                    "text:min-list-label-width=\"0.5cm\" text:max-list-label-width=\"0.5cm\"/>" +
                    "</style:list-level-properties></text:list-level-style-number>",
            )
        }
        append("</text:list-style>")
    }
}

/* -------------------------------------------------------------------------- */
/* Image dimensions                                                           */
/* -------------------------------------------------------------------------- */

private fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 16) return null
    if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
        if (bytes.size < 24) return null
        return beInt(bytes, 16) to beInt(bytes, 20)
    }
    if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) {
        if (bytes.size < 10) return null
        return leShort(bytes, 6) to leShort(bytes, 8)
    }
    if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
        if (bytes.size < 26) return null
        return leInt(bytes, 18) to abs(leInt(bytes, 22))
    }
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return jpegSize(bytes)
    return null
}

private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
    var index = 2
    while (index + 9 < bytes.size) {
        if (bytes[index] != 0xFF.toByte()) {
            index++
            continue
        }
        val marker = bytes[index + 1].toInt() and 0xFF
        index += 2
        when {
            marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7) -> continue
            marker == 0xD9 || marker == 0xDA -> return null
            marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                if (index + 7 > bytes.size) return null
                return beShort(bytes, index + 5) to beShort(bytes, index + 3)
            }

            else -> {
                if (index + 1 >= bytes.size) return null
                val length = beShort(bytes, index)
                if (length < 2) return null
                index += length
            }
        }
    }
    return null
}

private fun beInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

private fun beShort(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

private fun leInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

private fun leShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
