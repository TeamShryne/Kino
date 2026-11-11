package com.teamshryne.kino.office.ooxml

import com.teamshryne.kino.office.ReportIssue
import com.teamshryne.kino.office.image.ImageData
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
import com.teamshryne.kino.office.zip.ZipWriter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Writes a document specification as a Word (.docx) file.
 *
 * The package is produced by hand: WordprocessingML parts are written as text
 * and packed into a ZIP. Doing it by hand keeps the office module dependency
 * free and lets the theme drive every colour, font and border in the file.
 *
 * Word-specific gaps are handled explicitly: charts become data tables (Word
 * cannot embed the SVG charts the HTML renderer draws), SVG images are not
 * embedded, and missing images become a visible note plus a report issue.
 */
object DocxWriter {

    fun write(
        document: DocumentSpec,
        theme: OfficeTheme,
        geometry: PageGeometry,
        images: Map<String, ImageData> = emptyMap(),
        issues: MutableList<ReportIssue> = mutableListOf(),
    ): ByteArray {
        val builder = DocxBuilder(theme, geometry, images, issues)
        return ZipWriter.build(builder.build(document))
    }
}

/* -------------------------------------------------------------------------- */
/* Package parts                                                              */
/* -------------------------------------------------------------------------- */

private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val NS_WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
private const val NS_PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"

private const val NS_PACKAGE_RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
private const val NS_CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
private const val NS_CORE = "http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
private const val NS_EXTENDED = "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"

private const val REL_STYLES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
private const val REL_NUMBERING = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering"
private const val REL_SETTINGS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings"
private const val REL_HEADER = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/header"
private const val REL_FOOTER = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer"
private const val REL_IMAGE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
private const val REL_HYPERLINK = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"

private const val TWIPS_PER_PX = 15
private const val EMU_PER_PX = 9525

private data class Relationship(
    val id: String,
    val type: String,
    val target: String,
    val external: Boolean = false,
)

private fun hex(color: String): String = color.removePrefix("#").trim().uppercase()

private fun ColumnAlignment.jc(): String = when (this) {
    ColumnAlignment.LEFT -> "left"
    ColumnAlignment.CENTER -> "center"
    ColumnAlignment.RIGHT -> "right"
}

/**
 * Run formatting, kept as data so span formatting can be merged with the
 * formatting of the surrounding block before it is serialised.
 */
private data class RunFormat(
    val style: String? = null,
    val font: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val caps: Boolean = false,
    val smallCaps: Boolean = false,
    val strike: Boolean = false,
    val color: String? = null,
    val size: Int? = null,
    val underline: Boolean = false,
    val shading: String? = null,
) {
    fun xml(): String {
        val parts = mutableListOf<String>()
        style?.let { parts += "<w:rStyle w:val=\"$it\"/>" }
        font?.let { parts += "<w:rFonts w:ascii=\"${Xml.escape(it)}\" w:hAnsi=\"${Xml.escape(it)}\" w:cs=\"${Xml.escape(it)}\"/>" }
        if (bold) {
            parts += "<w:b/>"
            parts += "<w:bCs/>"
        }
        if (italic) {
            parts += "<w:i/>"
            parts += "<w:iCs/>"
        }
        if (caps) parts += "<w:caps/>"
        if (smallCaps) parts += "<w:smallCaps/>"
        if (strike) parts += "<w:strike/>"
        color?.let { parts += "<w:color w:val=\"$it\"/>" }
        size?.let {
            parts += "<w:sz w:val=\"$it\"/>"
            parts += "<w:szCs w:val=\"$it\"/>"
        }
        if (underline) parts += "<w:u w:val=\"single\"/>"
        shading?.let { parts += "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$it\"/>" }
        return if (parts.isEmpty()) "" else "<w:rPr>${parts.joinToString("")}</w:rPr>"
    }
}

/** Numbering definitions, allocated while the body is written. */
private class Numbering {

    private val nums = mutableListOf<String>()
    private val orderedByStart = mutableMapOf<Int, Int>()
    private var nextId = 1

    fun bullet(): Int = allocate(ABSTRACT_BULLET)

    fun ordered(startAt: Int): Int = orderedByStart.getOrPut(startAt) { allocate(ABSTRACT_ORDERED, startAt) }

    fun headings(): Int = allocate(ABSTRACT_HEADINGS)

    private fun allocate(abstractId: Int, startOverride: Int? = null): Int {
        val id = nextId++
        val override = startOverride?.let {
            "<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"$it\"/></w:lvlOverride>"
        } ?: ""
        nums += "<w:num w:numId=\"$id\"><w:abstractNumId w:val=\"$abstractId\"/>$override</w:num>"
        return id
    }

    fun xml(): String = buildString {
        append(XML_DECLARATION)
        append("<w:numbering xmlns:w=\"$NS_W\">")
        append(abstractNumbering(ABSTRACT_BULLET, bulletLevels()))
        append(abstractNumbering(ABSTRACT_ORDERED, orderedLevels()))
        append(abstractNumbering(ABSTRACT_HEADINGS, headingLevels()))
        nums.forEach { append(it) }
        append("</w:numbering>")
    }

    private fun abstractNumbering(id: Int, levels: String): String =
        "<w:abstractNum w:abstractNumId=\"$id\"><w:multiLevelType w:val=\"hybridMultilevel\"/>$levels</w:abstractNum>"

    private fun bulletLevels(): String = buildString {
        append(bulletLevel(0, "\uF0B7", "Symbol", 720))
        append(bulletLevel(1, "o", "Courier New", 1440))
        append(bulletLevel(2, "\uF0A7", "Wingdings", 2160))
    }

    private fun bulletLevel(level: Int, marker: String, font: String, indent: Int): String =
        "<w:lvl w:ilvl=\"$level\">" +
            "<w:start w:val=\"1\"/>" +
            "<w:numFmt w:val=\"bullet\"/>" +
            "<w:lvlText w:val=\"$marker\"/>" +
            "<w:lvlJc w:val=\"left\"/>" +
            "<w:pPr><w:ind w:left=\"$indent\" w:hanging=\"360\"/></w:pPr>" +
            "<w:rPr><w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\" w:hint=\"default\"/></w:rPr>" +
            "</w:lvl>"

    private fun orderedLevels(): String = buildString {
        append(orderedLevel(0, "%1.", 720))
        append(orderedLevel(1, "%2.", 1440))
        append(orderedLevel(2, "%3.", 2160))
    }

    private fun orderedLevel(level: Int, text: String, indent: Int): String =
        "<w:lvl w:ilvl=\"$level\">" +
            "<w:start w:val=\"1\"/>" +
            "<w:numFmt w:val=\"decimal\"/>" +
            "<w:lvlText w:val=\"$text\"/>" +
            "<w:lvlJc w:val=\"left\"/>" +
            "<w:pPr><w:ind w:left=\"$indent\" w:hanging=\"360\"/></w:pPr>" +
            "</w:lvl>"

    private fun headingLevels(): String = buildString {
        append(headingLevel(0, "%1.", 432))
        append(headingLevel(1, "%1.%2.", 576))
        append(headingLevel(2, "%1.%2.%3.", 720))
        append(headingLevel(3, "%1.%2.%3.%4.", 864))
    }

    private fun headingLevel(level: Int, text: String, indent: Int): String =
        "<w:lvl w:ilvl=\"$level\">" +
            "<w:start w:val=\"1\"/>" +
            "<w:numFmt w:val=\"decimal\"/>" +
            "<w:lvlText w:val=\"$text\"/>" +
            "<w:lvlJc w:val=\"left\"/>" +
            "<w:pPr><w:ind w:left=\"$indent\" w:hanging=\"$indent\"/></w:pPr>" +
            "</w:lvl>"

    private companion object {
        const val ABSTRACT_BULLET = 0
        const val ABSTRACT_ORDERED = 1
        const val ABSTRACT_HEADINGS = 2
    }
}

/* -------------------------------------------------------------------------- */
/* Builder                                                                    */
/* -------------------------------------------------------------------------- */

private class DocxBuilder(
    private val theme: OfficeTheme,
    private val geometry: PageGeometry,
    private val images: Map<String, ImageData>,
    private val issues: MutableList<ReportIssue>,
) {

    private val relationships = mutableListOf<Relationship>()
    private val media = mutableListOf<ZipWriter.Entry>()
    private val numbering = Numbering()
    private var relationshipCounter = 0
    private var imageCounter = 0
    private var drawingCounter = 0

    private val contentWidthTwips: Int get() = geometry.contentWidthPx * TWIPS_PER_PX

    fun build(document: DocumentSpec): List<ZipWriter.Entry> {
        val headerText = document.header?.trim()?.takeIf { it.isNotEmpty() }
        val footerText = document.footer?.trim()?.takeIf { it.isNotEmpty() }
        val wantsFooter = footerText != null || document.pageNumbers

        // Fixed relationships come first so the identifiers stay predictable.
        addRelationship(REL_STYLES, "styles.xml")
        addRelationship(REL_NUMBERING, "numbering.xml")
        addRelationship(REL_SETTINGS, "settings.xml")
        val headerRel = headerText?.let { addRelationship(REL_HEADER, "header1.xml") }
        val footerRel = if (wantsFooter) addRelationship(REL_FOOTER, "footer1.xml") else null

        val body = buildBody(document, headerRel, footerRel)

        val entries = mutableListOf<ZipWriter.Entry>()
        entries += ZipWriter.Entry("[Content_Types].xml", contentTypes().toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("_rels/.rels", rootRelationships().toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("docProps/core.xml", coreProperties(document).toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("docProps/app.xml", appProperties(document).toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("word/document.xml", documentXml(body).toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("word/_rels/document.xml.rels", documentRelationships().toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("word/styles.xml", stylesXml().toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("word/numbering.xml", numbering.xml().toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("word/settings.xml", settingsXml().toByteArray(Charsets.UTF_8))
        if (headerText != null) {
            entries += ZipWriter.Entry("word/header1.xml", headerXml(headerText).toByteArray(Charsets.UTF_8))
        }
        if (wantsFooter) {
            entries += ZipWriter.Entry("word/footer1.xml", footerXml(footerText, document.pageNumbers).toByteArray(Charsets.UTF_8))
        }
        entries += media
        return entries
    }

    /* ----------------------------- relationships ---------------------------- */

    private fun addRelationship(type: String, target: String, external: Boolean = false): String {
        val id = "rId${++relationshipCounter}"
        relationships += Relationship(id, type, target, external)
        return id
    }

    private fun documentRelationships(): String = buildString {
        append(XML_DECLARATION)
        append("<Relationships xmlns=\"$NS_PACKAGE_RELS\">")
        relationships.forEach { rel ->
            append("<Relationship Id=\"${rel.id}\" Type=\"${rel.type}\" Target=\"${Xml.escape(rel.target)}\"")
            if (rel.external) append(" TargetMode=\"External\"")
            append("/>")
        }
        append("</Relationships>")
    }

    private fun rootRelationships(): String = buildString {
        append(XML_DECLARATION)
        append("<Relationships xmlns=\"$NS_PACKAGE_RELS\">")
        append(
            "<Relationship Id=\"rId1\" " +
                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
                "Target=\"word/document.xml\"/>",
        )
        append(
            "<Relationship Id=\"rId2\" " +
                "Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" " +
                "Target=\"docProps/core.xml\"/>",
        )
        append(
            "<Relationship Id=\"rId3\" " +
                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" " +
                "Target=\"docProps/app.xml\"/>",
        )
        append("</Relationships>")
    }

    private fun contentTypes(): String {
        val imageTypes = media
            .mapNotNull { entry ->
                when (entry.name.substringAfterLast('.', "").lowercase()) {
                    "png" -> "png" to "image/png"
                    "jpg", "jpeg" -> "jpeg" to "image/jpeg"
                    "gif" -> "gif" to "image/gif"
                    "bmp" -> "bmp" to "image/bmp"
                    "webp" -> "webp" to "image/webp"
                    else -> null
                }
            }
            .distinct()
        return buildString {
            append(XML_DECLARATION)
            append("<Types xmlns=\"$NS_CONTENT_TYPES\">")
            append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            imageTypes.forEach { (extension, type) ->
                append("<Default Extension=\"$extension\" ContentType=\"$type\"/>")
            }
            append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
            append("<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>")
            append("<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>")
            append("<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>")
            append("<Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>")
            append("<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>")
            append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
            append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
            append("</Types>")
        }
    }

    /* ------------------------------- body ---------------------------------- */

    private fun buildBody(document: DocumentSpec, headerRel: String?, footerRel: String?): String = buildString {
        if (document.cover) append(cover(document))
        var tocWritten = false
        if (document.toc) {
            append(tableOfContents())
            tocWritten = true
        }
        document.blocks.forEach { block ->
            if (block is TocBlock) {
                if (!tocWritten) {
                    append(tableOfContents())
                    tocWritten = true
                }
            } else {
                append(renderBlock(block))
            }
        }
        append(sectionProperties(headerRel, footerRel))
    }

    private fun cover(document: DocumentSpec): String = buildString {
        append(paragraph("Title", run(InlineMarkup.plain(document.title))))
        document.subtitle?.takeIf { it.isNotBlank() }?.let {
            append(paragraph("Subtitle", run(InlineMarkup.plain(it))))
        }
        val meta = listOfNotNull(
            document.author?.takeIf { it.isNotBlank() },
            document.date?.takeIf { it.isNotBlank() },
        ).joinToString("  \u00b7  ")
        if (meta.isNotEmpty()) {
            append(
                paragraph(
                    content = run(meta, RunFormat(smallCaps = true, color = hex(theme.muted), size = 18)),
                    spacing = "<w:spacing w:before=\"0\" w:after=\"480\"/>",
                ),
            )
        }
        append(
            paragraph(
                content = "",
                pBdr = "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"24\" w:space=\"1\" w:color=\"${hex(theme.accent)}\"/></w:pBdr>",
                spacing = "<w:spacing w:before=\"0\" w:after=\"240\"/>",
                ind = "<w:ind w:right=\"4536\"/>",
            ),
        )
        append(pageBreak())
    }

    private fun tableOfContents(): String = buildString {
        append(paragraph("TOCHeading", run("Contents")))
        append(
            "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>" +
                "<w:r><w:instrText xml:space=\"preserve\"> TOC \\o \"1-3\" \\h \\z \\u </w:instrText></w:r>" +
                "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" +
                "<w:r><w:t>Right-click and choose Update Field to build the table of contents.</w:t></w:r>" +
                "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>",
        )
    }

    private fun renderBlock(block: Block): String = when (block) {
        is HeadingBlock -> {
            val level = block.level.coerceIn(1, 4)
            val numPr = if (block.numbered) {
                "<w:numPr><w:ilvl w:val=\"${level - 1}\"/><w:numId w:val=\"${numbering.headings()}\"/></w:numPr>"
            } else {
                null
            }
            paragraph("Heading$level", runs(block.text), numPr = numPr)
        }

        is ParagraphBlock -> paragraph("Normal", runs(block.text))

        is ListBlock -> {
            val numId = if (block.ordered) numbering.ordered(block.startAt) else numbering.bullet()
            buildString {
                block.items.forEach { item ->
                    append(
                        paragraph(
                            style = "ListParagraph",
                            content = runs(item),
                            numPr = "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"$numId\"/></w:numPr>",
                        ),
                    )
                }
            }
        }

        is TableBlock -> table(block)

        is KeyValueBlock -> keyValueTable(block)

        is CalloutBlock -> callout(block)

        is QuoteBlock -> {
            buildString {
                append(paragraph("Quote", runs(block.text)))
                block.attribution?.takeIf { it.isNotBlank() }?.let {
                    append(paragraph("QuoteAttribution", run(InlineMarkup.plain(it))))
                }
            }
        }

        is CodeBlock -> codeBlock(block)

        is ImageBlock -> image(block)

        is StatBlock -> statTable(block)

        is ChartBlock -> chartTable(block)

        is DividerBlock -> paragraph(
            content = "",
            pBdr = "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"${(theme.ruleWeight * 8).coerceIn(4, 24)}\" w:space=\"1\" w:color=\"${hex(theme.border)}\"/></w:pBdr>",
            spacing = "<w:spacing w:before=\"180\" w:after=\"180\"/>",
        )

        is PageBreakBlock -> pageBreak()

        is SpacerBlock -> paragraph(
            content = "",
            spacing = "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>",
            markRPr = "<w:rPr><w:sz w:val=\"12\"/><w:szCs w:val=\"12\"/></w:rPr>",
        )

        is TocBlock -> ""
    }

    private fun table(block: TableBlock): String {
        val columns = block.columns
        if (columns.isEmpty()) return ""
        val widths = columnWidths(columns.map { it.width }, columns.size)
        val alignments = block.effectiveAlignments
        return buildString {
            append("<w:tbl><w:tblPr>")
            append("<w:tblStyle w:val=\"TableGrid\"/>")
            append("<w:tblW w:w=\"$contentWidthTwips\" w:type=\"dxa\"/>")
            append("<w:tblLayout w:type=\"fixed\"/>")
            append("<w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"1\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"1\"/>")
            append("</w:tblPr><w:tblGrid>")
            widths.forEach { append("<w:gridCol w:w=\"$it\"/>") }
            append("</w:tblGrid>")

            append("<w:tr><w:trPr><w:cantSplit/><w:tblHeader/></w:trPr>")
            columns.forEachIndexed { index, column ->
                val alignment = alignments.getOrElse(index) { ColumnAlignment.LEFT }
                append(
                    cell(
                        width = widths[index],
                        content = cellParagraph(
                            InlineMarkup.plain(column.header),
                            alignment,
                            RunFormat(bold = true, color = hex(theme.background), size = 19),
                        ),
                        fill = hex(theme.accent),
                    ),
                )
            }
            append("</w:tr>")

            block.rows.forEachIndexed { rowIndex, row ->
                append("<w:tr>")
                row.forEachIndexed { index, value ->
                    val alignment = alignments.getOrElse(index) { ColumnAlignment.LEFT }
                    val fill = if (block.zebra && rowIndex % 2 == 1) hex(theme.surface) else null
                    append(
                        cell(
                            width = widths.getOrElse(index) { widths.last() },
                            content = cellParagraph(value, alignment),
                            fill = fill,
                        ),
                    )
                }
                append("</w:tr>")
            }
            append("</w:tbl>")

            val caption = block.caption
            if (!caption.isNullOrBlank()) {
                append(paragraph("Caption", run(InlineMarkup.plain(caption))))
            } else {
                append(paragraph(content = "", spacing = "<w:spacing w:after=\"0\"/>"))
            }
        }
    }

    private fun keyValueTable(block: KeyValueBlock): String {
        if (block.items.isEmpty()) return ""
        val labelWidth = (contentWidthTwips * 0.34).roundToInt()
        val valueWidth = contentWidthTwips - labelWidth
        return buildString {
            append("<w:tbl><w:tblPr>")
            append("<w:tblW w:w=\"$contentWidthTwips\" w:type=\"dxa\"/>")
            append("<w:tblLayout w:type=\"fixed\"/>")
            append(noBorders())
            append("</w:tblPr><w:tblGrid><w:gridCol w:w=\"$labelWidth\"/><w:gridCol w:w=\"$valueWidth\"/></w:tblGrid>")
            block.items.forEach { item ->
                append("<w:tr>")
                append(
                    cell(
                        width = labelWidth,
                        content = paragraph(
                            content = run(
                                InlineMarkup.plain(item.label),
                                RunFormat(bold = true, smallCaps = true, color = hex(theme.muted), size = 17),
                            ),
                            spacing = "<w:spacing w:before=\"40\" w:after=\"40\" w:line=\"240\" w:lineRule=\"auto\"/>",
                        ),
                    ),
                )
                append(
                    cell(
                        width = valueWidth,
                        content = buildString {
                            append(
                                paragraph(
                                    content = runs(item.value, RunFormat(bold = true, color = hex(theme.ink))),
                                    spacing = "<w:spacing w:before=\"40\" w:after=\"40\" w:line=\"240\" w:lineRule=\"auto\"/>",
                                ),
                            )
                            item.hint?.takeIf { it.isNotBlank() }?.let {
                                append(
                                    paragraph(
                                        content = run(
                                            InlineMarkup.plain(it),
                                            RunFormat(italic = true, color = hex(theme.muted), size = 17),
                                        ),
                                        spacing = "<w:spacing w:before=\"0\" w:after=\"40\" w:line=\"240\" w:lineRule=\"auto\"/>",
                                    ),
                                )
                            }
                        },
                    ),
                )
                append("</w:tr>")
            }
            append("</w:tbl>")
            append(paragraph(content = "", spacing = "<w:spacing w:after=\"0\"/>"))
        }
    }

    private fun callout(block: CalloutBlock): String {
        val color = calloutColor(block.kind)
        val background = hex(Colors.lighten(color, 0.9))
        return buildString {
            append("<w:tbl><w:tblPr>")
            append("<w:tblW w:w=\"$contentWidthTwips\" w:type=\"dxa\"/>")
            append("<w:tblLayout w:type=\"fixed\"/>")
            append(
                "<w:tblBorders>" +
                    "<w:top w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>" +
                    "<w:left w:val=\"single\" w:sz=\"24\" w:space=\"0\" w:color=\"${hex(color)}\"/>" +
                    "<w:bottom w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>" +
                    "<w:right w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>" +
                    "<w:insideH w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>" +
                    "<w:insideV w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>" +
                    "</w:tblBorders>",
            )
            append("</w:tblPr><w:tblGrid><w:gridCol w:w=\"$contentWidthTwips\"/></w:tblGrid>")
            append("<w:tr><w:tc><w:tcPr>")
            append("<w:tcW w:w=\"$contentWidthTwips\" w:type=\"dxa\"/>")
            append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$background\"/>")
            append("<w:tcMar><w:top w:w=\"120\" w:type=\"dxa\"/><w:left w:w=\"180\" w:type=\"dxa\"/><w:bottom w:w=\"120\" w:type=\"dxa\"/><w:right w:w=\"180\" w:type=\"dxa\"/></w:tcMar>")
            append("</w:tcPr>")
            block.title?.takeIf { it.isNotBlank() }?.let {
                append(
                    paragraph(
                        content = run(
                            InlineMarkup.plain(it),
                            RunFormat(bold = true, color = hex(color), size = 19),
                        ),
                        spacing = "<w:spacing w:before=\"0\" w:after=\"40\" w:line=\"240\" w:lineRule=\"auto\"/>",
                    ),
                )
            }
            append(
                paragraph(
                    content = runs(block.text),
                    spacing = "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"264\" w:lineRule=\"auto\"/>",
                ),
            )
            append("</w:tc></w:tr></w:tbl>")
            append(paragraph(content = "", spacing = "<w:spacing w:after=\"0\"/>"))
        }
    }

    private fun codeBlock(block: CodeBlock): String {
        val lines = block.text.split("\n")
        val content = buildString {
            lines.forEachIndexed { index, line ->
                if (index > 0) append("<w:br/>")
                append("<w:t xml:space=\"preserve\">${Xml.escape(Xml.sanitize(line))}</w:t>")
            }
        }
        return paragraph(
            style = "Code",
            content = "<w:r>$content</w:r>",
        )
    }

    private fun image(block: ImageBlock): String {
        val data = images[block.source]
        if (data == null) {
            issues += ReportIssue(
                ReportIssue.Severity.WARNING,
                "The image \"${block.source}\" is not available, so it was replaced with a note.",
                path = "blocks.image",
            )
            return buildString {
                append(
                    paragraph(
                        style = "Normal",
                        content = run(
                            "Image unavailable: ${block.alt ?: block.source}",
                            RunFormat(italic = true, color = hex(theme.muted)),
                        ),
                        jc = "center",
                    ),
                )
                block.caption?.takeIf { it.isNotBlank() }?.let {
                    append(paragraph("Caption", run(InlineMarkup.plain(it))))
                }
            }
        }
        if (data.extension.equals("svg", ignoreCase = true) || data.mimeType.equals("image/svg+xml", ignoreCase = true)) {
            issues += ReportIssue(
                ReportIssue.Severity.WARNING,
                "Word cannot embed SVG images, so \"${block.source}\" was left out of the document.",
                path = "blocks.image",
            )
            return buildString {
                append(
                    paragraph(
                        style = "Normal",
                        content = run(
                            block.alt ?: "Vector image (not supported in Word)",
                            RunFormat(italic = true, color = hex(theme.muted)),
                        ),
                        jc = "center",
                    ),
                )
                block.caption?.takeIf { it.isNotBlank() }?.let {
                    append(paragraph("Caption", run(InlineMarkup.plain(it))))
                }
            }
        }

        val index = ++imageCounter
        val extension = data.extension.lowercase().ifBlank { "png" }
        val fileName = "word/media/image$index.$extension"
        media += ZipWriter.Entry(fileName, data.bytes)
        val relationshipId = addRelationship(REL_IMAGE, "media/image$index.$extension")

        val (widthPx, heightPx) = imageSize(data.bytes) ?: (geometry.contentWidthPx to (geometry.contentWidthPx * 0.6).roundToInt())
        val maxWidth = geometry.contentWidthPx * (block.maxWidthPercent.coerceIn(10, 100)) / 100
        val scale = if (widthPx > 0) minOf(1.0, maxWidth.toDouble() / widthPx) else 1.0
        val drawWidth = (widthPx * scale).roundToInt().coerceAtLeast(1)
        val drawHeight = (heightPx * scale).roundToInt().coerceAtLeast(1)
        val cx = (drawWidth * EMU_PER_PX).toLong()
        val cy = (drawHeight * EMU_PER_PX).toLong()
        val drawingId = ++drawingCounter

        return buildString {
            append(
                paragraph(
                    content = "<w:r><w:drawing>" +
                        "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
                        "<wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
                        "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>" +
                        "<wp:docPr id=\"$drawingId\" name=\"Picture $drawingId\" descr=\"${Xml.escape(block.alt ?: "")}\"/>" +
                        "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
                        "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                        "<pic:pic>" +
                        "<pic:nvPicPr><pic:cNvPr id=\"$drawingId\" name=\"image$index.$extension\" descr=\"${Xml.escape(block.alt ?: "")}\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                        "<pic:blipFill><a:blip r:embed=\"$relationshipId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                        "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
                        "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
                        "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>",
                    jc = "center",
                ),
            )
            block.caption?.takeIf { it.isNotBlank() }?.let {
                append(paragraph("Caption", run(InlineMarkup.plain(it))))
            }
        }
    }

    private fun statTable(block: StatBlock): String {
        if (block.items.isEmpty()) return ""
        val labelWidth = (contentWidthTwips * 0.36).roundToInt()
        val valueWidth = (contentWidthTwips * 0.38).roundToInt()
        val deltaWidth = contentWidthTwips - labelWidth - valueWidth
        return buildString {
            append("<w:tbl><w:tblPr>")
            append("<w:tblStyle w:val=\"TableGrid\"/>")
            append("<w:tblW w:w=\"$contentWidthTwips\" w:type=\"dxa\"/>")
            append("<w:tblLayout w:type=\"fixed\"/>")
            append("</w:tblPr><w:tblGrid>")
            append("<w:gridCol w:w=\"$labelWidth\"/><w:gridCol w:w=\"$valueWidth\"/><w:gridCol w:w=\"$deltaWidth\"/>")
            append("</w:tblGrid>")
            block.items.forEachIndexed { index, stat ->
                append("<w:tr>")
                append(
                    cell(
                        width = labelWidth,
                        content = paragraph(
                            content = run(
                                InlineMarkup.plain(stat.label),
                                RunFormat(bold = true, smallCaps = true, color = hex(theme.muted), size = 17),
                            ),
                            spacing = "<w:spacing w:before=\"60\" w:after=\"60\" w:line=\"240\" w:lineRule=\"auto\"/>",
                        ),
                    ),
                )
                append(
                    cell(
                        width = valueWidth,
                        content = paragraph(
                            content = run(
                                InlineMarkup.plain(stat.value),
                                RunFormat(bold = true, color = hex(theme.ink), size = 32),
                            ),
                            spacing = "<w:spacing w:before=\"60\" w:after=\"60\" w:line=\"240\" w:lineRule=\"auto\"/>",
                        ),
                    ),
                )
                val deltaColor = when (stat.positive) {
                    true -> hex(theme.positive)
                    false -> hex(theme.negative)
                    null -> hex(theme.muted)
                }
                append(
                    cell(
                        width = deltaWidth,
                        content = paragraph(
                            content = stat.delta?.takeIf { it.isNotBlank() }?.let {
                                run(InlineMarkup.plain(it), RunFormat(bold = true, color = deltaColor, size = 18))
                            } ?: "",
                            spacing = "<w:spacing w:before=\"60\" w:after=\"60\" w:line=\"240\" w:lineRule=\"auto\"/>",
                        ),
                    ),
                )
                append("</w:tr>")
            }
            append("</w:tbl>")
            append(paragraph(content = "", spacing = "<w:spacing w:after=\"0\"/>"))
        }
    }

    private fun chartTable(block: ChartBlock): String {
        issues += ReportIssue(
            ReportIssue.Severity.WARNING,
            "The chart${block.title?.let { " \"$it\"" } ?: ""} was converted to a table because Word cannot embed SVG charts.",
            path = "blocks.chart",
        )
        if (block.series.isEmpty()) {
            return paragraph(
                style = "Normal",
                content = run(
                    block.title ?: "Chart",
                    RunFormat(italic = true, color = hex(theme.muted)),
                ),
            )
        }
        val categoryCount = block.categories.size
        val columnCount = block.series.size + 1
        val firstWidth = (contentWidthTwips * 0.34).roundToInt()
        val otherWidth = (contentWidthTwips - firstWidth) / block.series.size
        val widths = buildList {
            add(firstWidth)
            repeat(block.series.size) { add(otherWidth) }
        }
        return buildString {
            append("<w:tbl><w:tblPr>")
            append("<w:tblStyle w:val=\"TableGrid\"/>")
            append("<w:tblW w:w=\"$contentWidthTwips\" w:type=\"dxa\"/>")
            append("<w:tblLayout w:type=\"fixed\"/>")
            append("</w:tblPr><w:tblGrid>")
            widths.forEach { append("<w:gridCol w:w=\"$it\"/>") }
            append("</w:tblGrid>")

            append("<w:tr><w:trPr><w:cantSplit/><w:tblHeader/></w:trPr>")
            append(
                cell(
                    width = widths[0],
                    content = cellParagraph(
                        "Category",
                        ColumnAlignment.LEFT,
                        RunFormat(bold = true, color = hex(theme.background), size = 19),
                    ),
                    fill = hex(theme.accent),
                ),
            )
            block.series.forEachIndexed { index, series ->
                append(
                    cell(
                        width = widths[index + 1],
                        content = cellParagraph(
                            InlineMarkup.plain(series.name),
                            ColumnAlignment.RIGHT,
                            RunFormat(bold = true, color = hex(theme.background), size = 19),
                        ),
                        fill = hex(theme.accent),
                    ),
                )
            }
            append("</w:tr>")

            val categories = block.categories.ifEmpty { List(categoryCount.coerceAtLeast(1)) { "Item" } }
            categories.forEachIndexed { rowIndex, category ->
                append("<w:tr>")
                append(
                    cell(
                        width = widths[0],
                        content = cellParagraph(category, ColumnAlignment.LEFT),
                        fill = if (rowIndex % 2 == 1) hex(theme.surface) else null,
                    ),
                )
                block.series.forEachIndexed { index, series ->
                    val value = series.values.getOrNull(rowIndex) ?: 0.0
                    append(
                        cell(
                            width = widths[index + 1],
                            content = cellParagraph(formatNumber(value), ColumnAlignment.RIGHT),
                            fill = if (rowIndex % 2 == 1) hex(theme.surface) else null,
                        ),
                    )
                }
                append("</w:tr>")
            }
            append("</w:tbl>")
            block.title?.takeIf { it.isNotBlank() }?.let {
                append(paragraph("Caption", run(InlineMarkup.plain(it))))
            } else {
                append(paragraph(content = "", spacing = "<w:spacing w:after=\"0\"/>"))
            }
        }
    }

    private fun calloutColor(kind: CalloutKind): String = when (kind) {
        CalloutKind.INFO -> theme.accent
        CalloutKind.NOTE -> theme.inkSoft
        CalloutKind.WARNING -> theme.warning
        CalloutKind.SUCCESS -> theme.positive
        CalloutKind.DANGER -> theme.negative
    }

    private fun columnWidths(widths: List<Double?>, count: Int): List<Int> {
        val total = contentWidthTwips
        val weights = widths.map { it ?: 0.0 }
        val sum = weights.sum()
        return if (sum > 0.0 && weights.all { it > 0.0 }) {
            weights.map { (total * (it / sum)).roundToInt().coerceAtLeast(1) }
        } else {
            val each = (total.toDouble() / count).roundToInt()
            List(count) { each }
        }
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)

    /* ------------------------------- helpers -------------------------------- */

    private fun noBorders(): String = buildString {
        append("<w:tblBorders>")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach {
            append("<w:$it w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>")
        }
        append("</w:tblBorders>")
    }

    private fun cell(width: Int, content: String, fill: String? = null): String = buildString {
        append("<w:tc><w:tcPr>")
        append("<w:tcW w:w=\"$width\" w:type=\"dxa\"/>")
        fill?.let { append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$it\"/>") }
        append("<w:vAlign w:val=\"center\"/>")
        append("</w:tcPr>")
        append(content)
        append("</w:tc>")
    }

    private fun cellParagraph(
        text: String,
        alignment: ColumnAlignment,
        format: RunFormat = RunFormat(),
    ): String = paragraph(
        content = runs(text, format),
        spacing = "<w:spacing w:before=\"40\" w:after=\"40\" w:line=\"240\" w:lineRule=\"auto\"/>",
        jc = alignment.jc(),
    )

    private fun pageBreak(): String = "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"

    private fun paragraph(
        style: String? = null,
        content: String = "",
        numPr: String? = null,
        pBdr: String? = null,
        shd: String? = null,
        tabs: String? = null,
        spacing: String? = null,
        ind: String? = null,
        contextualSpacing: Boolean = false,
        jc: String? = null,
        outlineLvl: Int? = null,
        markRPr: String? = null,
    ): String = buildString {
        append("<w:p>")
        val props = mutableListOf<String>()
        style?.let { props += "<w:pStyle w:val=\"$it\"/>" }
        numPr?.let { props += it }
        pBdr?.let { props += it }
        shd?.let { props += it }
        tabs?.let { props += it }
        spacing?.let { props += it }
        ind?.let { props += it }
        if (contextualSpacing) props += "<w:contextualSpacing/>"
        jc?.let { props += "<w:jc w:val=\"$it\"/>" }
        outlineLvl?.let { props += "<w:outlineLvl w:val=\"$it\"/>" }
        markRPr?.let { props += it }
        if (props.isNotEmpty()) append("<w:pPr>").append(props.joinToString("")).append("</w:pPr>")
        append(content)
        append("</w:p>")
    }

    private fun run(text: String, format: RunFormat = RunFormat()): String {
        val parts = text.split("\n")
        val content = buildString {
            parts.forEachIndexed { index, part ->
                if (index > 0) append("<w:br/>")
                append("<w:t xml:space=\"preserve\">${Xml.escape(Xml.sanitize(part))}</w:t>")
            }
        }
        return "<w:r>${format.xml()}$content</w:r>"
    }

    private fun runs(text: String, base: RunFormat = RunFormat()): String =
        InlineMarkup.parse(text).joinToString("") { span -> spanXml(span, base) }

    private fun spanXml(span: Span, base: RunFormat): String {
        val format = base.copy(
            bold = base.bold || span.bold,
            italic = base.italic || span.italic,
            strike = base.strike || span.strike,
            font = base.font ?: if (span.code) theme.monoFont else null,
            shading = base.shading ?: if (span.code) hex(theme.surfaceAlt) else null,
            color = base.color ?: if (span.muted) hex(theme.muted) else null,
        )
        val link = span.link
        return if (link.isNullOrBlank()) {
            run(span.text, format)
        } else {
            val relationshipId = addRelationship(REL_HYPERLINK, link, external = true)
            val linkFormat = format.copy(style = "Hyperlink")
            "<w:hyperlink r:id=\"$relationshipId\">${run(span.text, linkFormat)}</w:hyperlink>"
        }
    }

    /* ------------------------------ page setup ------------------------------ */

    private fun sectionProperties(headerRel: String?, footerRel: String?): String = buildString {
        append("<w:sectPr>")
        headerRel?.let { append("<w:headerReference w:type=\"default\" r:id=\"$it\"/>") }
        footerRel?.let { append("<w:footerReference w:type=\"default\" r:id=\"$it\"/>") }
        val width = (geometry.widthPt * 20).roundToInt()
        val height = (geometry.heightPt * 20).roundToInt()
        append("<w:pgSz w:w=\"$width\" w:h=\"$height\"")
        if (geometry.orientation == Orientation.LANDSCAPE) append(" w:orient=\"landscape\"")
        append("/>")
        val top = geometry.marginTopPx * TWIPS_PER_PX
        val bottom = geometry.marginBottomPx * TWIPS_PER_PX
        val left = geometry.marginLeftPx * TWIPS_PER_PX
        val right = geometry.marginRightPx * TWIPS_PER_PX
        append(
            "<w:pgMar w:top=\"$top\" w:right=\"$right\" w:bottom=\"$bottom\" w:left=\"$left\" " +
                "w:header=\"${top / 2}\" w:footer=\"${bottom / 2}\" w:gutter=\"0\"/>",
        )
        append("<w:cols w:space=\"708\"/>")
        append("<w:docGrid w:linePitch=\"360\"/>")
        append("</w:sectPr>")
    }

    private fun documentXml(body: String): String = buildString {
        append(XML_DECLARATION)
        append(
            "<w:document xmlns:w=\"$NS_W\" xmlns:r=\"$NS_R\" xmlns:wp=\"$NS_WP\" " +
                "xmlns:a=\"$NS_A\" xmlns:pic=\"$NS_PIC\">",
        )
        append("<w:body>")
        append(body)
        append("</w:body></w:document>")
    }

    private fun headerXml(text: String): String = buildString {
        append(XML_DECLARATION)
        append("<w:hdr xmlns:w=\"$NS_W\" xmlns:r=\"$NS_R\">")
        append(
            paragraph(
                style = "Header",
                content = run(InlineMarkup.plain(text)),
            ),
        )
        append("</w:hdr>")
    }

    private fun footerXml(text: String?, pageNumbers: Boolean): String = buildString {
        append(XML_DECLARATION)
        append("<w:ftr xmlns:w=\"$NS_W\" xmlns:r=\"$NS_R\">")
        val content = buildString {
            if (!text.isNullOrBlank()) {
                append(run(InlineMarkup.plain(text)))
                if (pageNumbers) append("<w:r><w:tab/></w:r>")
            }
            if (pageNumbers) {
                append(run("Page "))
                append(field("PAGE", "1"))
                append(run(" of "))
                append(field("NUMPAGES", "1"))
            }
        }
        append(paragraph(style = "Footer", content = content))
        append("</w:ftr>")
    }

    private fun field(instruction: String, placeholder: String): String =
        "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>" +
            "<w:r><w:instrText xml:space=\"preserve\"> $instruction </w:instrText></w:r>" +
            "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" +
            "<w:r><w:t>$placeholder</w:t></w:r>" +
            "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"

    /* -------------------------------- styles -------------------------------- */

    private fun stylesXml(): String {
        val t = theme
        val sans = Xml.escape(t.sansFont)
        val serif = Xml.escape(t.serifFont)
        val mono = Xml.escape(t.monoFont)
        val accent = hex(t.accent)
        val ink = hex(t.ink)
        val inkSoft = hex(t.inkSoft)
        val muted = hex(t.muted)
        val border = hex(t.border)
        val surface = hex(t.surface)
        val surfaceAlt = hex(t.surfaceAlt)
        val scale = t.headingScale
        val h1 = (21 * scale).roundToInt()
        val h2 = (15.5 * scale).roundToInt()
        val h3 = (12.5 * scale).roundToInt()
        val h4 = (9.5 * scale).roundToInt()
        val title = (30 * scale).roundToInt()

        return buildString {
            append(XML_DECLARATION)
            append("<w:styles xmlns:w=\"$NS_W\">")
            append(
                "<w:docDefaults><w:rPrDefault><w:rPr>" +
                    "<w:rFonts w:ascii=\"$sans\" w:hAnsi=\"$sans\" w:eastAsia=\"$sans\" w:cs=\"$sans\"/>" +
                    "<w:color w:val=\"$ink\"/><w:sz w:val=\"21\"/><w:szCs w:val=\"21\"/>" +
                    "<w:lang w:val=\"en-US\"/>" +
                    "</w:rPr></w:rPrDefault>" +
                    "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>" +
                    "</w:docDefaults>",
            )

            // Normal
            append(
                "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">" +
                    "<w:name w:val=\"Normal\"/><w:qFormat/>" +
                    "<w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr>" +
                    "<w:rPr><w:rFonts w:ascii=\"$sans\" w:hAnsi=\"$sans\"/><w:color w:val=\"$ink\"/><w:sz w:val=\"21\"/></w:rPr>" +
                    "</w:style>",
            )

            // Title / Subtitle
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"Title\">" +
                    "<w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                    "<w:pPr><w:spacing w:before=\"0\" w:after=\"240\" w:line=\"240\" w:lineRule=\"auto\"/><w:contextualSpacing/></w:pPr>" +
                    "<w:rPr><w:rFonts w:ascii=\"$serif\" w:hAnsi=\"$serif\"/><w:b/><w:bCs/><w:color w:val=\"$accent\"/>" +
                    "<w:sz w:val=\"${title * 2}\"/><w:szCs w:val=\"${title * 2}\"/></w:rPr>" +
                    "</w:style>",
            )
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"Subtitle\">" +
                    "<w:name w:val=\"Subtitle\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                    "<w:pPr><w:spacing w:before=\"0\" w:after=\"200\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr>" +
                    "<w:rPr><w:rFonts w:ascii=\"$serif\" w:hAnsi=\"$serif\"/><w:color w:val=\"$inkSoft\"/><w:sz w:val=\"26\"/></w:rPr>" +
                    "</w:style>",
            )

            // Headings
            append(
                headingStyle(1, "heading 1", h1 * 2, serif, ink, 0, 260, 120, border, rule = false),
            )
            append(
                headingStyle(2, "heading 2", h2 * 2, serif, ink, 1, 220, 100, border, rule = true),
            )
            append(
                headingStyle(3, "heading 3", h3 * 2, serif, accent, 2, 180, 80, border, rule = false),
            )
            append(
                headingStyle(4, "heading 4", h4 * 2, sans, inkSoft, 3, 160, 60, border, rule = false, caps = true),
            )

            // TOC heading keeps the look of Heading 1 but stays out of the TOC.
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"TOCHeading\">" +
                    "<w:name w:val=\"TOC Heading\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                    "<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before=\"0\" w:after=\"160\" w:line=\"240\" w:lineRule=\"auto\"/>" +
                    "<w:outlineLvl w:val=\"9\"/></w:pPr>" +
                    "<w:rPr><w:rFonts w:ascii=\"$serif\" w:hAnsi=\"$serif\"/><w:b/><w:bCs/><w:color w:val=\"$ink\"/><w:sz w:val=\"34\"/></w:rPr>" +
                    "</w:style>",
            )
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"TOC1\"><w:name w:val=\"toc 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/>" +
                    "<w:pPr><w:tabs><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"$contentWidthTwips\"/></w:tabs>" +
                    "<w:spacing w:before=\"80\" w:after=\"0\"/></w:pPr></w:style>",
            )
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"TOC2\"><w:name w:val=\"toc 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/>" +
                    "<w:pPr><w:tabs><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"$contentWidthTwips\"/></w:tabs>" +
                    "<w:spacing w:before=\"40\" w:after=\"0\"/><w:ind w:left=\"240\"/></w:pPr>" +
                    "<w:rPr><w:color w:val=\"$inkSoft\"/><w:sz w:val=\"19\"/></w:rPr></w:style>",
            )
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"TOC3\"><w:name w:val=\"toc 3\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/>" +
                    "<w:pPr><w:tabs><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"$contentWidthTwips\"/></w:tabs>" +
                    "<w:spacing w:before=\"40\" w:after=\"0\"/><w:ind w:left=\"480\"/></w:pPr>" +
                    "<w:rPr><w:color w:val=\"$muted\"/><w:sz w:val=\"18\"/></w:rPr></w:style>",
            )

            // List paragraph
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\">" +
                    "<w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
                    "<w:pPr><w:spacing w:before=\"0\" w:after=\"60\" w:line=\"276\" w:lineRule=\"auto\"/>" +
                    "<w:ind w:left=\"720\"/><w:contextualSpacing/></w:pPr></w:style>",
            )

            // Quote
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"Quote\">" +
                    "<w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                    "<w:pPr>" +
                    "<w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"10\" w:color=\"$accent\"/></w:pBdr>" +
                    "<w:spacing w:before=\"120\" w:after=\"60\" w:line=\"288\" w:lineRule=\"auto\"/><w:ind w:left=\"360\"/></w:pPr>" +
                    "<w:rPr><w:rFonts w:ascii=\"$serif\" w:hAnsi=\"$serif\"/><w:i/><w:iCs/><w:color w:val=\"$inkSoft\"/><w:sz w:val=\"23\"/></w:rPr>" +
                    "</w:style>",
            )
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"QuoteAttribution\">" +
                    "<w:name w:val=\"Quote Attribution\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/>" +
                    "<w:pPr><w:spacing w:before=\"0\" w:after=\"160\" w:line=\"240\" w:lineRule=\"auto\"/>" +
                    "<w:ind w:left=\"360\"/><w:jc w:val=\"right\"/></w:pPr>" +
                    "<w:rPr><w:i/><w:iCs/><w:color w:val=\"$muted\"/><w:sz w:val=\"18\"/></w:rPr>" +
                    "</w:style>",
            )

            // Code
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"Code\">" +
                    "<w:name w:val=\"Code\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/>" +
                    "<w:pPr>" +
                    "<w:pBdr><w:top w:val=\"single\" w:sz=\"4\" w:space=\"4\" w:color=\"$border\"/>" +
                    "<w:left w:val=\"single\" w:sz=\"4\" w:space=\"4\" w:color=\"$border\"/>" +
                    "<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"4\" w:color=\"$border\"/>" +
                    "<w:right w:val=\"single\" w:sz=\"4\" w:space=\"4\" w:color=\"$border\"/></w:pBdr>" +
                    "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$surfaceAlt\"/>" +
                    "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>" +
                    "<w:ind w:left=\"120\" w:right=\"120\"/></w:pPr>" +
                    "<w:rPr><w:rFonts w:ascii=\"$mono\" w:hAnsi=\"$mono\" w:cs=\"$mono\"/><w:color w:val=\"$ink\"/><w:sz w:val=\"18\"/></w:rPr>" +
                    "</w:style>",
            )

            // Caption
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"Caption\">" +
                    "<w:name w:val=\"caption\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                    "<w:pPr><w:spacing w:before=\"40\" w:after=\"200\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>" +
                    "<w:rPr><w:i/><w:iCs/><w:color w:val=\"$muted\"/><w:sz w:val=\"17\"/></w:rPr>" +
                    "</w:style>",
            )

            // Hyperlink
            append(
                "<w:style w:type=\"character\" w:styleId=\"Hyperlink\">" +
                    "<w:name w:val=\"Hyperlink\"/><w:basedOn w:val=\"DefaultParagraphFont\"/><w:uiPriority w:val=\"99\"/>" +
                    "<w:rPr><w:color w:val=\"$accent\"/><w:u w:val=\"single\"/></w:rPr></w:style>",
            )
            append(
                "<w:style w:type=\"character\" w:default=\"1\" w:styleId=\"DefaultParagraphFont\">" +
                    "<w:name w:val=\"Default Paragraph Font\"/><w:uiPriority w:val=\"1\"/><w:semiHidden/><w:unhideWhenUsed/></w:style>",
            )

            // Header / footer
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"Header\">" +
                    "<w:name w:val=\"header\"/><w:basedOn w:val=\"Normal\"/><w:uiPriority w:val=\"99\"/>" +
                    "<w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"4\" w:color=\"$border\"/></w:pBdr>" +
                    "<w:tabs><w:tab w:val=\"center\" w:pos=\"${contentWidthTwips / 2}\"/><w:tab w:val=\"right\" w:pos=\"$contentWidthTwips\"/></w:tabs>" +
                    "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>" +
                    "<w:rPr><w:color w:val=\"$muted\"/><w:sz w:val=\"16\"/></w:rPr></w:style>",
            )
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"Footer\">" +
                    "<w:name w:val=\"footer\"/><w:basedOn w:val=\"Normal\"/><w:uiPriority w:val=\"99\"/>" +
                    "<w:pPr><w:tabs><w:tab w:val=\"center\" w:pos=\"${contentWidthTwips / 2}\"/><w:tab w:val=\"right\" w:pos=\"$contentWidthTwips\"/></w:tabs>" +
                    "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>" +
                    "<w:rPr><w:color w:val=\"$muted\"/><w:sz w:val=\"16\"/></w:rPr></w:style>",
            )

            // Tables
            append(
                "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\">" +
                    "<w:name w:val=\"Normal Table\"/><w:uiPriority w:val=\"99\"/><w:semiHidden/><w:unhideWhenUsed/>" +
                    "<w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/>" +
                    "<w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/>" +
                    "<w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>",
            )
            append(
                "<w:style w:type=\"table\" w:styleId=\"TableGrid\">" +
                    "<w:name w:val=\"Table Grid\"/><w:basedOn w:val=\"TableNormal\"/><w:uiPriority w:val=\"39\"/>" +
                    "<w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>" +
                    "<w:tblPr><w:tblBorders>" +
                    "<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"$border\"/>" +
                    "<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"$border\"/>" +
                    "<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"$border\"/>" +
                    "<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"$border\"/>" +
                    "<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"$border\"/>" +
                    "<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"$border\"/>" +
                    "</w:tblBorders>" +
                    "<w:tblCellMar><w:top w:w=\"60\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/>" +
                    "<w:bottom w:w=\"60\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar>" +
                    "</w:tblPr></w:style>",
            )
            append("</w:styles>")
        }
    }

    private fun headingStyle(
        level: Int,
        name: String,
        size: Int,
        font: String,
        color: String,
        outline: Int,
        before: Int,
        after: Int,
        border: String,
        rule: Boolean,
        caps: Boolean = false,
    ): String = buildString {
        append("<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\">")
        append("<w:name w:val=\"$name\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>")
        append("<w:pPr><w:keepNext/><w:keepLines/>")
        if (rule) {
            append("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"4\" w:color=\"$border\"/></w:pBdr>")
        }
        append("<w:spacing w:before=\"$before\" w:after=\"$after\" w:line=\"252\" w:lineRule=\"auto\"/>")
        append("<w:outlineLvl w:val=\"$outline\"/></w:pPr>")
        append("<w:rPr><w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\"/><w:b/><w:bCs/>")
        if (caps) append("<w:caps/>")
        append("<w:color w:val=\"$color\"/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr>")
        append("</w:style>")
    }

    private fun settingsXml(): String = buildString {
        append(XML_DECLARATION)
        append("<w:settings xmlns:w=\"$NS_W\">")
        append("<w:zoom w:percent=\"100\"/>")
        append("<w:defaultTabStop w:val=\"720\"/>")
        append("<w:characterSpacingControl w:val=\"doNotCompress\"/>")
        append("<w:updateFields w:val=\"true\"/>")
        append("<w:compat/>")
        append("</w:settings>")
    }

    private fun coreProperties(document: DocumentSpec): String {
        val stamp = timestamp()
        return buildString {
            append(XML_DECLARATION)
            append(
                "<cp:coreProperties xmlns:cp=\"$NS_CORE\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                    "xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" " +
                    "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">",
            )
            append("<dc:title>${Xml.escape(document.title)}</dc:title>")
            append("<dc:creator>${Xml.escape(document.author ?: "Kino")}</dc:creator>")
            append("<cp:lastModifiedBy>${Xml.escape(document.author ?: "Kino")}</cp:lastModifiedBy>")
            append("<dcterms:created xsi:type=\"dcterms:W3CDTF\">$stamp</dcterms:created>")
            append("<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$stamp</dcterms:modified>")
            append("</cp:coreProperties>")
        }
    }

    private fun appProperties(document: DocumentSpec): String {
        val words = countWords(document)
        val paragraphs = document.blocks.count { it !is DividerBlock && it !is SpacerBlock }
        return buildString {
            append(XML_DECLARATION)
            append(
                "<Properties xmlns=\"$NS_EXTENDED\" " +
                    "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">",
            )
            append("<Application>Kino</Application>")
            append("<Pages>${estimatePages(document)}</Pages>")
            append("<Words>$words</Words>")
            append("<Characters>${words * 6}</Characters>")
            append("<Paragraphs>$paragraphs</Paragraphs>")
            append("<DocSecurity>0</DocSecurity>")
            append("<ScaleCrop>false</ScaleCrop>")
            append("<LinksUpToDate>false</LinksUpToDate>")
            append("<SharedDoc>false</SharedDoc>")
            append("<HyperlinksChanged>false</HyperlinksChanged>")
            append("<AppVersion>1.0</AppVersion>")
            append("</Properties>")
        }
    }

    private fun countWords(document: DocumentSpec): Int {
        val texts = mutableListOf(document.title, document.subtitle ?: "", document.author ?: "")
        document.blocks.forEach { block ->
            when (block) {
                is HeadingBlock -> texts += block.text
                is ParagraphBlock -> texts += block.text
                is ListBlock -> texts += block.items
                is QuoteBlock -> texts += block.text
                is CalloutBlock -> texts += block.text
                is StatBlock -> texts += block.items.map { "${it.label} ${it.value} ${it.delta ?: ""}" }
                is KeyValueBlock -> texts += block.items.map { "${it.label} ${it.value}" }
                else -> Unit
            }
        }
        return texts.joinToString(" ")
            .split(Regex("\\s+"))
            .count { it.isNotBlank() }
    }

    private fun estimatePages(document: DocumentSpec): Int {
        val blocks = document.blocks.size + if (document.cover) 2 else 1
        return (blocks / 6 + 1).coerceAtLeast(1)
    }

    private fun timestamp(): String = "2024-01-01T00:00:00Z"
}

/* -------------------------------------------------------------------------- */
/* Image dimensions                                                           */
/* -------------------------------------------------------------------------- */

/**
 * Reads the pixel size of an image straight from its header.
 *
 * The office module also runs on Android, where `javax.imageio` is not
 * available, so the handful of common formats are parsed by hand.
 */
private fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 16) return null
    // PNG
    if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
        if (bytes.size < 24) return null
        return beInt(bytes, 16) to beInt(bytes, 20)
    }
    // GIF
    if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) {
        if (bytes.size < 10) return null
        return leShort(bytes, 6) to leShort(bytes, 8)
    }
    // BMP
    if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
        if (bytes.size < 26) return null
        return leInt(bytes, 18) to abs(leInt(bytes, 22))
    }
    // WEBP
    if (bytes.size > 30 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
    ) {
        return webpSize(bytes)
    }
    // JPEG
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
        return jpegSize(bytes)
    }
    return null
}

private fun webpSize(bytes: ByteArray): Pair<Int, Int>? {
    val kind = String(bytes, 12, 4, Charsets.US_ASCII)
    return when (kind) {
        "VP8 " -> {
            if (bytes.size < 30) return null
            val width = leShort(bytes, 26) and 0x3FFF
            val height = leShort(bytes, 28) and 0x3FFF
            width to height
        }

        "VP8L" -> {
            if (bytes.size < 25) return null
            val bits = leInt(bytes, 21)
            val width = (bits and 0x3FFF) + 1
            val height = ((bits shr 14) and 0x3FFF) + 1
            width to height
        }

        "VP8X" -> {
            if (bytes.size < 30) return null
            val width = (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or ((bytes[26].toInt() and 0xFF) shl 16) + 1
            val height = (bytes[27].toInt() and 0xFF) or ((bytes[28].toInt() and 0xFF) shl 8) or ((bytes[29].toInt() and 0xFF) shl 16) + 1
            width to height
        }

        else -> null
    }
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
                val height = beShort(bytes, index + 3)
                val width = beShort(bytes, index + 5)
                return width to height
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
