package com.teamshryne.kino.office.ooxml

import com.teamshryne.kino.office.ReportIssue
import com.teamshryne.kino.office.model.ColumnAlignment
import com.teamshryne.kino.office.model.ColumnType
import com.teamshryne.kino.office.model.SheetCell
import com.teamshryne.kino.office.model.SheetColumn
import com.teamshryne.kino.office.model.SheetSpec
import com.teamshryne.kino.office.model.WorkbookSpec
import com.teamshryne.kino.office.render.Colors
import com.teamshryne.kino.office.theme.OfficeTheme
import com.teamshryne.kino.office.xml.Xml
import com.teamshryne.kino.office.zip.ZipWriter
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Writes a workbook as a SpreadsheetML (`.xlsx`) package.
 *
 * The package is assembled by hand so every part is predictable: the content
 * types, the relationships, the styles, and the worksheets are all generated
 * from the same plan, which keeps Excel from showing its "repair" prompt and
 * lets the theme decide how the sheet actually looks.
 */
object XlsxWriter {

    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val NS_CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"

    private const val DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    /** Excel serial date zero: 1899-12-30. */
    private const val DATE_EPOCH_OFFSET = 25569L

    private val REF_PATTERN = Regex("^\\$?([A-Za-z]{1,3})\\$?([0-9]{1,7})$")

    /**
     * Builds the `.xlsx` bytes for [workbook].
     *
     * Problems that had to be repaired on the way (renamed sheets, dropped
     * notes, padded rows) are reported through [issues].
     */
    fun write(
        workbook: WorkbookSpec,
        theme: OfficeTheme,
        issues: MutableList<ReportIssue> = mutableListOf(),
    ): ByteArray {
        val book = StyleBook(theme)
        val plans = planSheets(workbook.sheets, issues)
        val rendered = plans.map { plan -> renderSheet(plan, book) }

        val parts = mutableListOf<Pair<String, String>>()
        parts += "[Content_Types].xml" to contentTypes(rendered)
        parts += "_rels/.rels" to rootRelationships()
        parts += "docProps/core.xml" to coreProperties(workbook.title)
        parts += "docProps/app.xml" to appProperties(plans)
        parts += "xl/workbook.xml" to workbookXml(plans)
        parts += "xl/_rels/workbook.xml.rels" to workbookRelationships(plans.size)
        parts += "xl/styles.xml" to book.stylesXml()
        rendered.forEach { sheet ->
            parts += "xl/worksheets/sheet${sheet.index + 1}.xml" to sheet.xml
            if (sheet.comments.isNotEmpty()) {
                parts += "xl/worksheets/_rels/sheet${sheet.index + 1}.xml.rels" to sheetRelationships(sheet)
                parts += sheet.commentsPart to sheet.commentsXml
                parts += sheet.vmlPart to sheet.vmlXml
            }
        }
        return ZipWriter.text(parts)
    }

    /* ------------------------------- planning ------------------------------- */

    private class SheetPlan(
        val sheet: SheetSpec,
        val index: Int,
        val name: String,
        val columnCount: Int,
        val hasHeader: Boolean,
        val firstDataRow: Int,
        val freeze: Freeze?,
        val notes: List<Note>,
        val tabColor: String?,
        val numericColumns: Set<Int>,
    )

    private class Freeze(val xSplit: Int, val ySplit: Int, val cell: String, val activePane: String)

    private val SheetPlan.hasTotalRow: Boolean
        get() = sheet.totalRow && sheet.rows.isNotEmpty()

    private class Note(val ref: String, val columnIndex: Int, val rowNumber: Int, val text: String)

    private fun planSheets(sheets: List<SheetSpec>, issues: MutableList<ReportIssue>): List<SheetPlan> {
        val used = mutableSetOf<String>()
        return sheets.mapIndexed { index, sheet ->
            val raw = sheet.name.trim()
            var name = raw.replace(Regex("[\\[\\]:*?/\\\\]"), "_").take(31)
            if (name.isBlank()) name = "Sheet${index + 1}"
            if (name != raw) {
                issues += ReportIssue(
                    ReportIssue.Severity.WARNING,
                    "Sheet name \"$raw\" is not a valid Excel sheet name; it was written as \"$name\".",
                    "sheets[$index].name",
                    fixable = true,
                )
            }
            if (!used.add(name.lowercase(Locale.US))) {
                var suffix = 2
                var candidate = "$name ($suffix)".take(31)
                while (!used.add(candidate.lowercase(Locale.US))) {
                    suffix++
                    candidate = "$name ($suffix)".take(31)
                }
                issues += ReportIssue(
                    ReportIssue.Severity.WARNING,
                    "Two sheets are called \"$name\"; the second one was written as \"$candidate\".",
                    "sheets[$index].name",
                    fixable = true,
                )
                name = candidate
            }

            val columnCount = maxOf(
                sheet.columns.size,
                sheet.rows.maxOfOrNull { row -> row.size } ?: 0,
            )
            val hasHeader = sheet.columns.isNotEmpty()
            val firstDataRow = if (hasHeader) 2 else 1

            val freeze = sheet.freeze?.let { freezeOf(it, index, issues) }
            val notes = sheet.notes.mapNotNull { (ref, text) -> noteOf(ref, text, index, issues) }
            val tabColor = sheet.tabColor?.let { tabColorOf(it, index, issues) }
            val numericColumns = numericColumns(sheet)

            SheetPlan(
                sheet = sheet,
                index = index,
                name = name,
                columnCount = columnCount,
                hasHeader = hasHeader,
                firstDataRow = firstDataRow,
                freeze = freeze,
                notes = notes,
                tabColor = tabColor,
                numericColumns = numericColumns,
            )
        }
    }

    /** Columns that should get a `SUM` in the totals row. */
    private fun numericColumns(sheet: SheetSpec): Set<Int> {
        val count = maxOf(
            sheet.columns.size,
            sheet.rows.maxOfOrNull { row -> row.size } ?: 0,
        )
        val result = mutableSetOf<Int>()
        for (index in 0 until count) {
            val type = sheet.columns.getOrNull(index)?.type ?: ColumnType.TEXT
            if (type == ColumnType.NUMBER || type == ColumnType.CURRENCY || type == ColumnType.PERCENT) {
                result += index
                continue
            }
            val values = sheet.rows.mapNotNull { row -> row.getOrNull(index) }
                .filter { it !is SheetCell.Blank }
            if (values.isNotEmpty() && values.all { it is SheetCell.Number }) result += index
        }
        return result
    }

    private fun freezeOf(reference: String, index: Int, issues: MutableList<ReportIssue>): Freeze? {
        val ref = parseRef(reference)
        if (ref == null) {
            issues += ReportIssue(
                ReportIssue.Severity.WARNING,
                "Freeze reference \"$reference\" is not a cell reference; the sheet was written without frozen panes.",
                "sheets[$index].freeze",
            )
            return null
        }
        val xSplit = ref.columnIndex
        val ySplit = ref.rowNumber - 1
        if (xSplit <= 0 && ySplit <= 0) return null
        val activePane = when {
            xSplit > 0 && ySplit > 0 -> "bottomRight"
            xSplit > 0 -> "topRight"
            ySplit > 0 -> "bottomLeft"
            else -> "bottomLeft"
        }
        return Freeze(xSplit, ySplit, cellRef(ref.columnIndex, ref.rowNumber), activePane)
    }

    private fun noteOf(reference: String, text: String, index: Int, issues: MutableList<ReportIssue>): Note? {
        val ref = parseRef(reference)
        if (ref == null) {
            issues += ReportIssue(
                ReportIssue.Severity.WARNING,
                "Note reference \"$reference\" is not a cell reference; the note was skipped.",
                "sheets[$index].notes",
            )
            return null
        }
        if (text.isBlank()) return null
        return Note(cellRef(ref.columnIndex, ref.rowNumber), ref.columnIndex, ref.rowNumber, text)
    }

    private fun tabColorOf(value: String, index: Int, issues: MutableList<ReportIssue>): String? {
        val clean = value.trim().removePrefix("#")
        val valid = clean.length == 6 && clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (!valid) {
            issues += ReportIssue(
                ReportIssue.Severity.WARNING,
                "Tab colour \"$value\" is not a six digit hex colour; the tab keeps the default colour.",
                "sheets[$index].tab_color",
            )
            return null
        }
        return "FF${clean.uppercase(Locale.US)}"
    }

    /* ------------------------------- rendering ------------------------------ */

    private class RenderedSheet(
        val index: Int,
        val xml: String,
        val comments: List<Note>,
        val commentsXml: String,
        val vmlXml: String,
    ) {
        val commentsPart: String get() = "xl/comments${index + 1}.xml"
        val vmlPart: String get() = "xl/drawings/vmlDrawing${index + 1}.vml"
    }

    private fun renderSheet(plan: SheetPlan, book: StyleBook): RenderedSheet {
        val sheet = plan.sheet
        val xml = StringBuilder()
        val banded = sheet.rows.size > 6

        xml.append(DECLARATION)
        xml.append("<worksheet xmlns=\"$NS_MAIN\"")
        if (plan.notes.isNotEmpty()) xml.append(" xmlns:r=\"$NS_REL\"")
        xml.append(">")

        xml.append("<sheetPr>")
        plan.tabColor?.let { xml.append("<tabColor rgb=\"$it\"/>") }
        xml.append("<pageSetUpPr fitToPage=\"1\"/>")
        xml.append("</sheetPr>")

        val lastRow = plan.firstDataRow + sheet.rows.size - 1 + if (plan.hasTotalRow) 1 else 0
        val lastColumn = columnLetters((plan.columnCount - 1).coerceAtLeast(0))
        xml.append("<dimension ref=\"A1:${lastColumn}${lastRow.coerceAtLeast(1)}\"/>")

        xml.append("<sheetViews><sheetView workbookViewId=\"0\">")
        plan.freeze?.let { freeze ->
            if (freeze.xSplit > 0) xml.append("<pane xSplit=\"${freeze.xSplit}\"")
            else xml.append("<pane")
            xml.append(" ySplit=\"${freeze.ySplit}\" topLeftCell=\"${freeze.cell}\" activePane=\"${freeze.activePane}\" state=\"frozen\"/>")
            xml.append("<selection pane=\"${freeze.activePane}\" activeCell=\"${freeze.cell}\" sqref=\"${freeze.cell}\"/>")
        }
        xml.append("</sheetView></sheetViews>")

        xml.append("<sheetFormatPr defaultRowHeight=\"15\"/>")

        if (plan.columnCount > 0) {
            xml.append("<cols>")
            for (index in 0 until plan.columnCount) {
                val width = sheet.columns.getOrNull(index)?.width ?: DEFAULT_COLUMN_WIDTH
                xml.append(
                    "<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"${widthText(width)}\" customWidth=\"1\"/>",
                )
            }
            xml.append("</cols>")
        }

        xml.append("<sheetData>")
        if (plan.hasHeader) {
            xml.append("<row r=\"1\">")
            sheet.columns.forEachIndexed { index, column ->
                val style = book.xf(0, book.fontHeader, book.fillAccent, book.borderThin, null)
                xml.append(
                    "<c r=\"${cellRef(index, 1)}\" s=\"$style\" t=\"inlineStr\"><is><t>${Xml.escape(column.header)}</t></is></c>",
                )
            }
            xml.append("</row>")
        }

        sheet.rows.forEachIndexed { rowIndex, row ->
            val rowNumber = plan.firstDataRow + rowIndex
            val rowBanded = banded && rowNumber % 2 == 0
            xml.append("<row r=\"$rowNumber\">")
            for (columnIndex in 0 until plan.columnCount) {
                val cell = row.getOrNull(columnIndex) ?: continue
                if (cell is SheetCell.Blank) continue
                val column = sheet.columns.getOrNull(columnIndex)
                val style = styleFor(book, cell, column, rowBanded)
                xml.append(cellXml(cell, column, columnIndex, rowNumber, style))
            }
            xml.append("</row>")
        }

        if (plan.hasTotalRow) {
            val rowNumber = plan.firstDataRow + sheet.rows.size
            xml.append("<row r=\"$rowNumber\">")
            val labelStyle = book.xf(0, book.fontBold, book.fillTotal, book.borderThin, null)
            xml.append(
                "<c r=\"${cellRef(0, rowNumber)}\" s=\"$labelStyle\" t=\"inlineStr\"><is><t>Total</t></is></c>",
            )
            for (columnIndex in 1 until plan.columnCount) {
                val column = sheet.columns.getOrNull(columnIndex)
                if (columnIndex in plan.numericColumns) {
                    val format = formatId(book, null, column)
                    val style = book.xf(format, book.fontBold, book.fillTotal, book.borderThin, "right")
                    val letter = columnLetters(columnIndex)
                    val first = plan.firstDataRow
                    val last = plan.firstDataRow + sheet.rows.size - 1
                    val sum = columnSum(sheet, columnIndex)
                    xml.append(
                        "<c r=\"${cellRef(columnIndex, rowNumber)}\" s=\"$style\">" +
                            "<f>SUM($letter$first:$letter$last)</f><v>${numberText(sum)}</v></c>",
                    )
                } else {
                    val style = book.xf(0, book.fontMuted, book.fillTotal, book.borderThin, "right")
                    xml.append(
                        "<c r=\"${cellRef(columnIndex, rowNumber)}\" s=\"$style\" t=\"inlineStr\"><is><t>\u2014</t></is></c>",
                    )
                }
            }
            xml.append("</row>")
        }
        xml.append("</sheetData>")

        if (sheet.autoFilter && plan.columnCount > 0 && sheet.rows.isNotEmpty()) {
            val startRow = if (plan.hasHeader) 1 else plan.firstDataRow
            val endRow = plan.firstDataRow + sheet.rows.size - 1
            if (endRow >= startRow) {
                xml.append("<autoFilter ref=\"A$startRow:${lastColumn}$endRow\"/>")
            }
        }

        xml.append(
            "<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>",
        )
        val orientation = if (plan.columnCount > 5) "landscape" else "portrait"
        xml.append("<pageSetup orientation=\"$orientation\" fitToWidth=\"1\" fitToHeight=\"0\"/>")

        if (plan.notes.isNotEmpty()) {
            xml.append("<legacyDrawing r:id=\"rId1\"/>")
        }

        xml.append("</worksheet>")

        return RenderedSheet(
            index = plan.index,
            xml = xml.toString(),
            comments = plan.notes,
            commentsXml = commentsXml(plan.notes),
            vmlXml = vmlDrawing(plan.notes),
        )
    }

    private fun styleFor(
        book: StyleBook,
        cell: SheetCell,
        column: SheetColumn?,
        banded: Boolean,
    ): Int {
        val format = formatId(book, cell.format, column)
        val font = when {
            cell.bold -> book.fontBold
            else -> book.fontDefault
        }
        val fill = if (banded) book.fillBand else book.fillNone
        val align = cell.align
        val alignment = when {
            align != null -> alignmentText(align)
            cell is SheetCell.Number -> "right"
            cell is SheetCell.Bool -> "center"
            else -> null
        }
        return book.xf(format, font, fill, book.borderThin, alignment)
    }

    /** Resolves the number format of a cell: explicit format first, column type second. */
    private fun formatId(book: StyleBook, explicit: String?, column: SheetColumn?): Int {
        val given = explicit?.trim()
        if (!given.isNullOrEmpty()) return book.numFmtId(given)
        return when (column?.type ?: ColumnType.TEXT) {
            ColumnType.CURRENCY -> book.numFmtId(FORMAT_CURRENCY)
            ColumnType.PERCENT -> book.numFmtId(FORMAT_PERCENT)
            ColumnType.DATE -> book.numFmtId(FORMAT_DATE)
            else -> 0
        }
    }

    private fun cellXml(
        cell: SheetCell,
        column: SheetColumn?,
        columnIndex: Int,
        rowNumber: Int,
        style: Int,
    ): String {
        val ref = cellRef(columnIndex, rowNumber)
        return when (cell) {
            is SheetCell.Text -> {
                val date = dateValue(cell.value, column)
                if (date != null) {
                    "<c r=\"$ref\" s=\"$style\"><v>${numberText(date.toSerial())}</v></c>"
                } else {
                    val text = cell.value
                    val preserve = if (text != text.trim()) " xml:space=\"preserve\"" else ""
                    "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t$preserve>${Xml.escape(text)}</t></is></c>"
                }
            }

            is SheetCell.Number -> {
                val value = cell.value
                if (value.isNaN() || value.isInfinite()) {
                    "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t>$value</t></is></c>"
                } else {
                    "<c r=\"$ref\" s=\"$style\"><v>${numberText(value)}</v></c>"
                }
            }

            is SheetCell.Bool ->
                "<c r=\"$ref\" s=\"$style\" t=\"b\"><v>${if (cell.value) 1 else 0}</v></c>"

            is SheetCell.Formula -> {
                val expression = cell.expression.removePrefix("=").trim()
                "<c r=\"$ref\" s=\"$style\"><f>${Xml.escape(expression)}</f></c>"
            }

            SheetCell.Blank -> ""
        }
    }

    private fun columnSum(sheet: SheetSpec, columnIndex: Int): Double {
        var sum = 0.0
        sheet.rows.forEach { row ->
            val cell = row.getOrNull(columnIndex)
            if (cell is SheetCell.Number && !cell.value.isNaN() && !cell.value.isInfinite()) {
                sum += cell.value
            }
        }
        return sum
    }

    /* --------------------------------- parts ------------------------------- */

    private fun contentTypes(rendered: List<RenderedSheet>): String {
        val xml = StringBuilder()
        xml.append(DECLARATION)
        xml.append("<Types xmlns=\"$NS_CONTENT_TYPES\">")
        xml.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        xml.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        if (rendered.any { it.comments.isNotEmpty() }) {
            xml.append("<Default Extension=\"vml\" ContentType=\"application/vnd.openxmlformats-officedocument.vmlDrawing\"/>")
        }
        xml.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        xml.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        rendered.forEach { sheet ->
            xml.append(
                "<Override PartName=\"/xl/worksheets/sheet${sheet.index + 1}.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>",
            )
            if (sheet.comments.isNotEmpty()) {
                xml.append(
                    "<Override PartName=\"/${sheet.commentsPart}\" " +
                        "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.comments+xml\"/>",
                )
            }
        }
        xml.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        xml.append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        xml.append("</Types>")
        return xml.toString()
    }

    private fun rootRelationships(): String {
        val xml = StringBuilder()
        xml.append(DECLARATION)
        xml.append("<Relationships xmlns=\"$NS_PACKAGE_REL\">")
        xml.append("<Relationship Id=\"rId1\" Type=\"$NS_REL/officeDocument\" Target=\"xl/workbook.xml\"/>")
        xml.append("<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>")
        xml.append("<Relationship Id=\"rId3\" Type=\"$NS_REL/extended-properties\" Target=\"docProps/app.xml\"/>")
        xml.append("</Relationships>")
        return xml.toString()
    }

    private fun workbookXml(plans: List<SheetPlan>): String {
        val xml = StringBuilder()
        xml.append(DECLARATION)
        xml.append("<workbook xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\">")
        xml.append("<workbookPr/>")
        xml.append("<bookViews><workbookView activeTab=\"0\"/></bookViews>")
        xml.append("<sheets>")
        plans.forEachIndexed { index, plan ->
            xml.append(
                "<sheet name=\"${Xml.attribute(plan.name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>",
            )
        }
        xml.append("</sheets>")
        xml.append("<calcPr calcId=\"171027\" fullCalcOnLoad=\"1\"/>")
        xml.append("</workbook>")
        return xml.toString()
    }

    private fun workbookRelationships(sheetCount: Int): String {
        val xml = StringBuilder()
        xml.append(DECLARATION)
        xml.append("<Relationships xmlns=\"$NS_PACKAGE_REL\">")
        for (index in 1..sheetCount) {
            xml.append(
                "<Relationship Id=\"rId$index\" Type=\"$NS_REL/worksheet\" Target=\"worksheets/sheet$index.xml\"/>",
            )
        }
        xml.append("<Relationship Id=\"rId${sheetCount + 1}\" Type=\"$NS_REL/styles\" Target=\"styles.xml\"/>")
        xml.append("</Relationships>")
        return xml.toString()
    }

    private fun sheetRelationships(sheet: RenderedSheet): String {
        val xml = StringBuilder()
        xml.append(DECLARATION)
        xml.append("<Relationships xmlns=\"$NS_PACKAGE_REL\">")
        xml.append("<Relationship Id=\"rId1\" Type=\"$NS_REL/vmlDrawing\" Target=\"../drawings/vmlDrawing${sheet.index + 1}.vml\"/>")
        xml.append("<Relationship Id=\"rId2\" Type=\"$NS_REL/comments\" Target=\"../comments${sheet.index + 1}.xml\"/>")
        xml.append("</Relationships>")
        return xml.toString()
    }

    private fun coreProperties(title: String): String {
        val stamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))
        val xml = StringBuilder()
        xml.append(DECLARATION)
        xml.append(
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
                "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">",
        )
        xml.append("<dc:title>${Xml.escape(title)}</dc:title>")
        xml.append("<dc:creator>Kino</dc:creator>")
        xml.append("<cp:lastModifiedBy>Kino</cp:lastModifiedBy>")
        xml.append("<dcterms:created xsi:type=\"dcterms:W3CDTF\">$stamp</dcterms:created>")
        xml.append("<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$stamp</dcterms:modified>")
        xml.append("</cp:coreProperties>")
        return xml.toString()
    }

    private fun appProperties(plans: List<SheetPlan>): String {
        val xml = StringBuilder()
        xml.append(DECLARATION)
        xml.append(
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" " +
                "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">",
        )
        xml.append("<Application>Kino</Application>")
        xml.append("<DocSecurity>0</DocSecurity>")
        xml.append("<ScaleCrop>false</ScaleCrop>")
        xml.append("<HeadingPairs><vt:vector size=\"2\" baseType=\"variant\">")
        xml.append("<vt:variant><vt:lpstr>Worksheets</vt:lpstr></vt:variant>")
        xml.append("<vt:variant><vt:i4>${plans.size}</vt:i4></vt:variant>")
        xml.append("</vt:vector></HeadingPairs>")
        xml.append("<TitlesOfParts><vt:vector size=\"${plans.size}\" baseType=\"lpstr\">")
        plans.forEach { plan -> xml.append("<vt:lpstr>${Xml.escape(plan.name)}</vt:lpstr>") }
        xml.append("</vt:vector></TitlesOfParts>")
        xml.append("<Company></Company>")
        xml.append("<LinksUpToDate>false</LinksUpToDate>")
        xml.append("<SharedDoc>false</SharedDoc>")
        xml.append("<HyperlinksChanged>false</HyperlinksChanged>")
        xml.append("<AppVersion>16.0300</AppVersion>")
        xml.append("</Properties>")
        return xml.toString()
    }

    private fun commentsXml(notes: List<Note>): String {
        val xml = StringBuilder()
        xml.append(DECLARATION)
        xml.append("<comments xmlns=\"$NS_MAIN\">")
        xml.append("<authors><author>Kino</author></authors>")
        xml.append("<commentList>")
        notes.forEach { note ->
            xml.append("<comment ref=\"${note.ref}\" authorId=\"0\"><text><r>")
            xml.append("<rPr><sz val=\"10\"/><color rgb=\"FF000000\"/></rPr>")
            xml.append("<t xml:space=\"preserve\">${Xml.escape(note.text)}</t>")
            xml.append("</r></text></comment>")
        }
        xml.append("</commentList>")
        xml.append("</comments>")
        return xml.toString()
    }

    /** The legacy VML drawing Excel needs to show comment bubbles. */
    private fun vmlDrawing(notes: List<Note>): String {
        val xml = StringBuilder()
        xml.append("<xml xmlns:v=\"urn:schemas-microsoft-com:vml\" ")
        xml.append("xmlns:o=\"urn:schemas-microsoft-com:office:office\" ")
        xml.append("xmlns:x=\"urn:schemas-microsoft-com:office:excel\">")
        xml.append("<o:shapelayout v:ext=\"edit\"><o:idmap v:ext=\"edit\" data=\"1\"/></o:shapelayout>")
        xml.append("<v:shapetype id=\"_x0000_t202\" coordsize=\"21600,21600\" o:spt=\"202\" path=\"m,l,21600r21600,l21600,xe\">")
        xml.append("<v:stroke joinstyle=\"miter\"/>")
        xml.append("<v:path gradientshapeok=\"t\" o:connecttype=\"rect\"/>")
        xml.append("</v:shapetype>")
        notes.forEachIndexed { index, note ->
            val shapeId = 1025 + index
            val row = note.rowNumber - 1
            val column = note.columnIndex
            xml.append("<v:shape id=\"_x0000_s$shapeId\" type=\"#_x0000_t202\" ")
            xml.append("style=\"position:absolute;margin-left:80pt;margin-top:2pt;width:180pt;height:60pt;z-index:1;visibility:hidden\" ")
            xml.append("fillcolor=\"#ffffe1\" o:insetmode=\"auto\">")
            xml.append("<v:fill color2=\"#ffffe1\"/>")
            xml.append("<v:shadow on=\"t\" color=\"black\" obscured=\"t\"/>")
            xml.append("<v:path o:connecttype=\"none\"/>")
            xml.append("<v:textbox style=\"mso-direction-alt:auto\"><div style=\"text-align:left\"/></v:textbox>")
            xml.append("<x:ClientData ObjectType=\"Note\">")
            xml.append("<x:MoveWithCells/><x:SizeWithCells/>")
            xml.append("<x:Anchor>${column + 1}, 15, $row, 2, ${column + 3}, 15, ${row + 3}, 2</x:Anchor>")
            xml.append("<x:AutoFill>False</x:AutoFill>")
            xml.append("<x:Row>$row</x:Row>")
            xml.append("<x:Column>$column</x:Column>")
            xml.append("</x:ClientData>")
            xml.append("</v:shape>")
        }
        xml.append("</xml>")
        return xml.toString()
    }

    /* -------------------------------- styles ------------------------------- */

    private const val DEFAULT_COLUMN_WIDTH = 14.0
    private const val FORMAT_CURRENCY = "#,##0.00"
    private const val FORMAT_PERCENT = "0.0%"
    private const val FORMAT_DATE = "yyyy-mm-dd"

    /**
     * Collects the fonts, fills, borders, and cell formats the workbook uses.
     *
     * Excel insists on the two "mandatory" fills (`none` and `gray125`) and on
     * the default cell format being the first entry, so both are fixed here.
     */
    private class StyleBook(private val theme: OfficeTheme) {

        val fontDefault = 0
        val fontBold = 1
        val fontHeader = 2
        val fontMuted = 3

        val fillNone = 0
        val fillGray125 = 1
        val fillAccent = 2
        val fillBand = 3
        val fillTotal = 4

        val borderNone = 0
        val borderThin = 1

        private val numberFormats = LinkedHashMap<String, Int>()
        private val cellFormats = LinkedHashMap<CellFormat, Int>()

        init {
            // The default format has to stay at index 0.
            cellFormats[CellFormat(0, fontDefault, fillNone, borderNone, null)] = 0
        }

        fun numFmtId(code: String): Int {
            val clean = code.trim()
            if (clean.isEmpty() || clean.equals("general", ignoreCase = true)) return 0
            return numberFormats.getOrPut(clean) { 164 + numberFormats.size }
        }

        fun xf(numFmtId: Int, fontId: Int, fillId: Int, borderId: Int, horizontal: String?): Int {
            val key = CellFormat(numFmtId, fontId, fillId, borderId, horizontal)
            return cellFormats.getOrPut(key) { cellFormats.size }
        }

        fun stylesXml(): String {
            val xml = StringBuilder()
            xml.append(DECLARATION)
            xml.append("<styleSheet xmlns=\"$NS_MAIN\">")
            if (numberFormats.isNotEmpty()) {
                xml.append("<numFmts count=\"${numberFormats.size}\">")
                numberFormats.forEach { (code, id) ->
                    xml.append("<numFmt numFmtId=\"$id\" formatCode=\"${Xml.attribute(code)}\"/>")
                }
                xml.append("</numFmts>")
            }

            xml.append("<fonts count=\"4\">")
            xml.append(fontXml(theme.sansFont, 11.0, theme.ink))
            xml.append(fontXml(theme.sansFont, 11.0, theme.ink, bold = true))
            xml.append(fontXml(theme.sansFont, 11.0, "#FFFFFF", bold = true))
            xml.append(fontXml(theme.sansFont, 10.0, theme.muted, italic = true))
            xml.append("</fonts>")

            xml.append("<fills count=\"5\">")
            xml.append("<fill><patternFill patternType=\"none\"/></fill>")
            xml.append("<fill><patternFill patternType=\"gray125\"/></fill>")
            xml.append(solidFill(theme.accent))
            xml.append(solidFill(theme.surfaceAlt))
            xml.append(solidFill(theme.accentSoft))
            xml.append("</fills>")

            xml.append("<borders count=\"2\">")
            xml.append("<border><left/><right/><top/><bottom/><diagonal/></border>")
            xml.append(
                "<border>" +
                    "<left style=\"thin\"><color rgb=\"${argb(theme.border)}\"/></left>" +
                    "<right style=\"thin\"><color rgb=\"${argb(theme.border)}\"/></right>" +
                    "<top style=\"thin\"><color rgb=\"${argb(theme.border)}\"/></top>" +
                    "<bottom style=\"thin\"><color rgb=\"${argb(theme.border)}\"/></bottom>" +
                    "<diagonal/>" +
                    "</border>",
            )
            xml.append("</borders>")

            xml.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")

            xml.append("<cellXfs count=\"${cellFormats.size}\">")
            cellFormats.forEach { (key, index) ->
                val alignment = key.horizontal?.let { " horizontal=\"$it\" vertical=\"center\"" }.orEmpty()
                xml.append(
                    "<xf numFmtId=\"${key.numFmtId}\" fontId=\"${key.fontId}\" fillId=\"${key.fillId}\" " +
                        "borderId=\"${key.borderId}\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" " +
                        "applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">" +
                        "<alignment$alignment/></xf>",
                )
            }
            xml.append("</cellXfs>")

            xml.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
            xml.append("<dxfs count=\"0\"/>")
            xml.append("<tableStyles count=\"0\" defaultTableStyle=\"TableStyleMedium9\" defaultPivotStyle=\"PivotStyleLight16\"/>")
            xml.append("</styleSheet>")
            return xml.toString()
        }

        private fun fontXml(name: String, size: Double, color: String, bold: Boolean = false, italic: Boolean = false): String {
            val xml = StringBuilder()
            xml.append("<font>")
            if (bold) xml.append("<b/>")
            if (italic) xml.append("<i/>")
            xml.append("<sz val=\"${sizeText(size)}\"/>")
            xml.append("<color rgb=\"${argb(color)}\"/>")
            xml.append("<name val=\"${Xml.attribute(name)}\"/>")
            xml.append("<family val=\"2\"/>")
            xml.append("</font>")
            return xml.toString()
        }

        private fun solidFill(color: String): String =
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"${argb(color)}\"/><bgColor indexed=\"64\"/></patternFill></fill>"

        private data class CellFormat(
            val numFmtId: Int,
            val fontId: Int,
            val fillId: Int,
            val borderId: Int,
            val horizontal: String?,
        )
    }

    /* ------------------------------- helpers ------------------------------- */

    private fun alignmentText(alignment: ColumnAlignment): String = when (alignment) {
        ColumnAlignment.LEFT -> "left"
        ColumnAlignment.CENTER -> "center"
        ColumnAlignment.RIGHT -> "right"
    }

    private fun parseRef(reference: String): CellRef? {
        val match = REF_PATTERN.matchEntire(reference.trim()) ?: return null
        val letters = match.groupValues[1].uppercase(Locale.US)
        var column = 0
        letters.forEach { letter -> column = column * 26 + (letter - 'A' + 1) }
        val row = match.groupValues[2].toIntOrNull() ?: return null
        if (row < 1) return null
        return CellRef(column - 1, row)
    }

    private data class CellRef(val columnIndex: Int, val rowNumber: Int)

    private fun columnLetters(index: Int): String {
        var value = index + 1
        val letters = StringBuilder()
        while (value > 0) {
            val remainder = (value - 1) % 26
            letters.append(('A' + remainder))
            value = (value - 1) / 26
        }
        return letters.reverse().toString()
    }

    private fun cellRef(columnIndex: Int, rowNumber: Int): String = columnLetters(columnIndex) + rowNumber

    private fun widthText(width: Double): String = BigDecimal(width)
        .setScale(2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()

    private fun sizeText(size: Double): String = BigDecimal(size)
        .setScale(2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()

    /** Formats a number the way Excel expects to read it back. */
    private fun numberText(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        return BigDecimal(value).round(MathContext(15)).stripTrailingZeros().toPlainString()
    }

    private fun argb(color: String): String {
        val (r, g, b) = Colors.rgb(color)
        return "FF%02X%02X%02X".format(r, g, b)
    }

    /** Parses an ISO date so date columns can hold real date values. */
    private fun dateValue(text: String, column: SheetColumn?): LocalDate? {
        if (column?.type != ColumnType.DATE) return null
        val clean = text.trim()
        if (clean.length < 8) return null
        return try {
            LocalDate.parse(clean)
        } catch (error: Exception) {
            null
        }
    }

    private fun LocalDate.toSerial(): Double = (toEpochDay() + DATE_EPOCH_OFFSET).toDouble()
}
