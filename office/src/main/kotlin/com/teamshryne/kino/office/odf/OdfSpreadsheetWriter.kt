package com.teamshryne.kino.office.odf

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
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.floor

/**
 * Writes a workbook as an OpenDocument Spreadsheet (`.ods`).
 *
 * The package follows the OpenDocument layout: the `mimetype` entry comes first
 * and is stored uncompressed, then the manifest, the content, the styles, and
 * the metadata. The theme drives the header band, the banding, and the fonts so
 * the file looks like the rest of the Kino exports.
 */
object OdfSpreadsheetWriter {

    private const val MIMETYPE = "application/vnd.oasis.opendocument.spreadsheet"

    private const val NS_OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val NS_TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private const val NS_TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val NS_STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val NS_NUMBER = "urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0"
    private const val NS_FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    private const val NS_SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
    private const val NS_META = "urn:oasis:names:tc:opendocument:xmlns:meta:1.0"
    private const val NS_DC = "http://purl.org/dc/elements/1.1/"
    private const val NS_MANIFEST = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"
    private const val NS_CONFIG = "urn:oasis:names:tc:opendocument:xmlns:config:1.0"
    private const val NS_CALC = "urn:org:documentfoundation:names:experimental:calc:xmlns:calcext:1.0"

    private const val DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"

    /** One Excel column unit is roughly 0.2 cm in a spreadsheet font. */
    private const val CM_PER_COLUMN_UNIT = 0.2

    private const val DEFAULT_COLUMN_WIDTH = 14.0
    private const val FORMAT_NUMBER = "0"
    private const val FORMAT_CURRENCY = "#,##0.00"
    private const val FORMAT_PERCENT = "0.0%"
    private const val FORMAT_DATE = "yyyy-mm-dd"

    private val REF_PATTERN = Regex("\\\$?[A-Za-z]{1,3}\\\$?[0-9]{1,7}(?::\\\$?[A-Za-z]{1,3}\\\$?[0-9]{1,7})?")

    /**
     * Builds the `.ods` bytes for [workbook].
     *
     * Anything that had to be adjusted on the way is reported through [issues].
     */
    fun write(
        workbook: WorkbookSpec,
        theme: OfficeTheme,
        issues: MutableList<ReportIssue> = mutableListOf(),
    ): ByteArray {
        val book = StyleBook(theme)
        val plans = planSheets(workbook.sheets, issues)
        val tables = plans.map { plan -> renderTable(plan, book, issues) }

        val entries = mutableListOf<Pair<String, ByteArray>>()
        entries += "META-INF/manifest.xml" to manifest(plans.any { it.freeze != null })
            .toByteArray(Charsets.UTF_8)
        entries += "content.xml" to contentXml(plans, tables, book)
            .toByteArray(Charsets.UTF_8)
        entries += "styles.xml" to stylesXml(theme)
            .toByteArray(Charsets.UTF_8)
        entries += "meta.xml" to metaXml(workbook.title)
            .toByteArray(Charsets.UTF_8)
        if (plans.any { it.freeze != null }) {
            entries += "settings.xml" to settingsXml(plans)
                .toByteArray(Charsets.UTF_8)
        }
        return buildZip(entries)
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
        val notes: Map<Long, String>,
        val tabColor: String?,
        val numericColumns: Set<Int>,
    )

    private class Freeze(val columns: Int, val rows: Int)

    private fun planSheets(sheets: List<SheetSpec>, issues: MutableList<ReportIssue>): List<SheetPlan> {
        val used = mutableSetOf<String>()
        return sheets.mapIndexed { index, sheet ->
            val raw = sheet.name.trim()
            var name = raw.replace(Regex("[\\[\\]:*?/\\\\]"), "_").take(31)
            if (name.isBlank()) name = "Sheet${index + 1}"
            if (name != raw) {
                issues += ReportIssue(
                    ReportIssue.Severity.WARNING,
                    "Sheet name \"$raw\" was shortened to \"$name\" so it stays a valid sheet name.",
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
            val notes = sheet.notes.mapNotNull { (ref, text) ->
                val parsed = parseRef(ref)
                when {
                    parsed == null -> {
                        issues += ReportIssue(
                            ReportIssue.Severity.WARNING,
                            "Note reference \"$ref\" is not a cell reference; the note was skipped.",
                            "sheets[$index].notes",
                        )
                        null
                    }

                    text.isBlank() -> null
                    else -> noteKey(parsed.rowNumber, parsed.columnIndex) to text
                }
            }.toMap()

            val freeze = sheet.freeze?.let { reference ->
                val parsed = parseRef(reference)
                when {
                    parsed == null -> {
                        issues += ReportIssue(
                            ReportIssue.Severity.WARNING,
                            "Freeze reference \"$reference\" is not a cell reference; the sheet was written without frozen panes.",
                            "sheets[$index].freeze",
                        )
                        null
                    }

                    parsed.columnIndex == 0 && parsed.rowNumber <= 1 -> null
                    else -> Freeze(parsed.columnIndex, parsed.rowNumber - 1)
                }
            }

            val tabColor = sheet.tabColor?.let { value ->
                val clean = value.trim().removePrefix("#")
                val valid = clean.length == 6 && clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                if (!valid) {
                    issues += ReportIssue(
                        ReportIssue.Severity.WARNING,
                        "Tab colour \"$value\" is not a six digit hex colour; the tab keeps the default colour.",
                        "sheets[$index].tab_color",
                    )
                    null
                } else {
                    "#${clean.lowercase(Locale.US)}"
                }
            }

            SheetPlan(
                sheet = sheet,
                index = index,
                name = name,
                columnCount = columnCount,
                hasHeader = sheet.columns.isNotEmpty(),
                firstDataRow = if (sheet.columns.isNotEmpty()) 2 else 1,
                freeze = freeze,
                notes = notes,
                tabColor = tabColor,
                numericColumns = numericColumns(sheet),
            )
        }
    }

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

    /* ------------------------------- rendering ------------------------------ */

    private class RenderedTable(val plan: SheetPlan, val xml: String)

    private fun renderTable(plan: SheetPlan, book: StyleBook, issues: MutableList<ReportIssue>): RenderedTable {
        val sheet = plan.sheet
        val banded = sheet.rows.size > 6
        val xml = StringBuilder()

        xml.append("<table:table table:name=\"${Xml.attribute(plan.name)}\"")
        plan.tabColor?.let { xml.append(" table:style-name=\"${book.tableStyle(it)}\"") }
        xml.append(">")

        for (index in 0 until plan.columnCount) {
            val width = sheet.columns.getOrNull(index)?.width ?: DEFAULT_COLUMN_WIDTH
            xml.append(
                "<table:table-column table:style-name=\"${book.columnStyle(width)}\" " +
                    "table:number-columns-repeated=\"1\" table:default-cell-style-name=\"Default\"/>",
            )
        }

        if (plan.hasHeader) {
            xml.append("<table:table-row>")
            sheet.columns.forEach { column ->
                xml.append(
                    "<table:table-cell table:style-name=\"${book.headerStyle()}\" " +
                        "office:value-type=\"string\" calcext:value-type=\"string\">" +
                        "<text:p>${Xml.escape(column.header)}</text:p></table:table-cell>",
                )
            }
            xml.append("</table:table-row>")
        }

        sheet.rows.forEachIndexed { rowIndex, row ->
            val rowNumber = plan.firstDataRow + rowIndex
            val rowBanded = banded && rowNumber % 2 == 0
            xml.append("<table:table-row>")
            for (columnIndex in 0 until plan.columnCount) {
                val cell = row.getOrNull(columnIndex)
                if (cell == null || cell is SheetCell.Blank) {
                    xml.append("<table:table-cell/>")
                    continue
                }
                val column = sheet.columns.getOrNull(columnIndex)
                xml.append(cellXml(cell, column, columnIndex, rowNumber, rowBanded, book, plan, issues))
            }
            xml.append("</table:table-row>")
        }

        if (sheet.totalRow && sheet.rows.isNotEmpty()) {
            val rowNumber = plan.firstDataRow + sheet.rows.size
            xml.append("<table:table-row>")
            xml.append(
                "<table:table-cell table:style-name=\"${book.totalLabelStyle()}\" " +
                    "office:value-type=\"string\" calcext:value-type=\"string\">" +
                    "<text:p>Total</text:p></table:table-cell>",
            )
            for (columnIndex in 1 until plan.columnCount) {
                val column = sheet.columns.getOrNull(columnIndex)
                if (columnIndex in plan.numericColumns) {
                    val style = book.totalValueStyle(column)
                    val letter = columnLetters(columnIndex)
                    val first = plan.firstDataRow
                    val last = plan.firstDataRow + sheet.rows.size - 1
                    val sum = columnSum(sheet, columnIndex)
                    xml.append(
                        "<table:table-cell table:formula=\"of:=SUM([.$letter$first:.$letter$last])\" " +
                            "office:value-type=\"float\" office:value=\"${numberText(sum)}\" " +
                            "calcext:value-type=\"float\" table:style-name=\"$style\">" +
                            "<text:p>${numberText(sum)}</text:p></table:table-cell>",
                    )
                } else {
                    xml.append(
                        "<table:table-cell table:style-name=\"${book.totalLabelStyle()}\" " +
                            "office:value-type=\"string\" calcext:value-type=\"string\">" +
                            "<text:p>\u2014</text:p></table:table-cell>",
                    )
                }
            }
            xml.append("</table:table-row>")
        }

        xml.append("</table:table>")
        return RenderedTable(plan, xml.toString())
    }

    private fun cellXml(
        cell: SheetCell,
        column: SheetColumn?,
        columnIndex: Int,
        rowNumber: Int,
        banded: Boolean,
        book: StyleBook,
        plan: SheetPlan,
        issues: MutableList<ReportIssue>,
    ): String {
        val style = book.cellStyle(cell, column, banded)
        val note = plan.notes[noteKey(rowNumber, columnIndex)]
        val annotation = note?.let {
            "<office:annotation><text:p>${Xml.escape(it)}</text:p></office:annotation>"
        }.orEmpty()

        return when (cell) {
            is SheetCell.Text -> {
                val date = dateValue(cell.value, column)
                if (date != null) {
                    "<table:table-cell table:style-name=\"${book.dateStyle()}\" office:value-type=\"date\" " +
                        "office:date-value=\"$date\" calcext:value-type=\"date\">" +
                        "<text:p>$date</text:p>$annotation</table:table-cell>"
                } else {
                    "<table:table-cell table:style-name=\"$style\" office:value-type=\"string\" " +
                        "calcext:value-type=\"string\"><text:p>${Xml.escape(cell.value)}</text:p>" +
                        "$annotation</table:table-cell>"
                }
            }

            is SheetCell.Number -> {
                val value = cell.value
                if (value.isNaN() || value.isInfinite()) {
                    "<table:table-cell table:style-name=\"$style\" office:value-type=\"string\" " +
                        "calcext:value-type=\"string\"><text:p>$value</text:p>$annotation</table:table-cell>"
                } else {
                    val text = displayNumber(value, column)
                    "<table:table-cell table:style-name=\"$style\" office:value-type=\"float\" " +
                        "office:value=\"${numberText(value)}\" calcext:value-type=\"float\">" +
                        "<text:p>$text</text:p>$annotation</table:table-cell>"
                }
            }

            is SheetCell.Bool -> {
                val flag = if (cell.value) "true" else "false"
                "<table:table-cell table:style-name=\"$style\" office:value-type=\"boolean\" " +
                    "office:boolean-value=\"$flag\" calcext:value-type=\"boolean\">" +
                    "<text:p>${if (cell.value) "TRUE" else "FALSE"}</text:p>$annotation</table:table-cell>"
            }

            is SheetCell.Formula -> {
                val expression = cell.expression.removePrefix("=").trim()
                val formula = openFormula(expression)
                when {
                    formula == null -> {
                        issues += ReportIssue(
                            ReportIssue.Severity.WARNING,
                            "The formula \"$expression\" uses a reference the OpenDocument export cannot translate; it was written as text.",
                            "sheets[${plan.index}].rows[$rowNumber][$columnIndex]",
                        )
                        "<table:table-cell table:style-name=\"$style\" office:value-type=\"string\" " +
                            "calcext:value-type=\"string\"><text:p>${Xml.escape(expression)}</text:p>" +
                            "$annotation</table:table-cell>"
                    }

                    else -> {
                        val number = cachedFormulaValue(expression, columnIndex, plan)
                        if (number == null) {
                            "<table:table-cell table:formula=\"$formula\" table:style-name=\"$style\">" +
                                "<text:p>${Xml.escape(expression)}</text:p>$annotation</table:table-cell>"
                        } else {
                            "<table:table-cell table:formula=\"$formula\" office:value-type=\"float\" " +
                                "office:value=\"${numberText(number)}\" calcext:value-type=\"float\" " +
                                "table:style-name=\"$style\"><text:p>${numberText(number)}</text:p>" +
                                "$annotation</table:table-cell>"
                        }
                    }
                }
            }

            SheetCell.Blank -> "<table:table-cell/>"
        }
    }

    /** Only `SUM` over a single column can be evaluated without a formula engine. */
    private fun cachedFormulaValue(expression: String, columnIndex: Int, plan: SheetPlan): Double? {
        val match = Regex("^SUM\\(\\s*([A-Za-z]{1,3})\\$?([0-9]+)\\s*:\\s*([A-Za-z]{1,3})\\$?([0-9]+)\\s*\\)$")
            .matchEntire(expression.trim())
            ?: return null
        val first = match.groupValues[1].uppercase(Locale.US)
        val last = match.groupValues[3].uppercase(Locale.US)
        if (first != last) return null
        if (columnLetters(columnIndex) != first) return null
        val from = match.groupValues[2].toIntOrNull() ?: return null
        val to = match.groupValues[4].toIntOrNull() ?: return null
        var sum = 0.0
        plan.sheet.rows.forEachIndexed { index, row ->
            val rowNumber = plan.firstDataRow + index
            if (rowNumber in from..to) {
                val cell = row.getOrNull(columnIndex)
                if (cell is SheetCell.Number && !cell.value.isNaN() && !cell.value.isInfinite()) sum += cell.value
            }
        }
        return sum
    }

    private fun columnSum(sheet: SheetSpec, columnIndex: Int): Double {
        var sum = 0.0
        sheet.rows.forEach { row ->
            val cell = row.getOrNull(columnIndex)
            if (cell is SheetCell.Number && !cell.value.isNaN() && !cell.value.isInfinite()) sum += cell.value
        }
        return sum
    }

    /* --------------------------------- parts ------------------------------- */

    private fun manifest(hasSettings: Boolean): String {
        val xml = StringBuilder()
        xml.append(DECLARATION)
        xml.append("<manifest:manifest xmlns:manifest=\"$NS_MANIFEST\" manifest:version=\"1.3\">")
        xml.append(
            "<manifest:file-entry manifest:full-path=\"/\" manifest:version=\"1.3\" manifest:media-type=\"$MIMETYPE\"/>",
        )
        xml.append("<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>")
        xml.append("<manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/>")
        xml.append("<manifest:file-entry manifest:full-path=\"meta.xml\" manifest:media-type=\"text/xml\"/>")
        if (hasSettings) {
            xml.append("<manifest:file-entry manifest:full-path=\"settings.xml\" manifest:media-type=\"text/xml\"/>")
        }
        xml.append("</manifest:manifest>")
        return xml.toString()
    }

    private fun contentXml(
        plans: List<SheetPlan>,
        tables: List<RenderedTable>,
        book: StyleBook,
    ): String {
        val xml = StringBuilder()
        xml.append(DECLARATION)
        xml.append(
            "<office:document-content xmlns:office=\"$NS_OFFICE\" xmlns:table=\"$NS_TABLE\" " +
                "xmlns:text=\"$NS_TEXT\" xmlns:style=\"$NS_STYLE\" xmlns:number=\"$NS_NUMBER\" " +
                "xmlns:fo=\"$NS_FO\" xmlns:svg=\"$NS_SVG\" xmlns:calcext=\"$NS_CALC\" office:version=\"1.3\">",
        )
        xml.append("<office:automatic-styles>")
        xml.append(book.automaticStyles())
        xml.append("</office:automatic-styles>")
        xml.append("<office:body><office:spreadsheet>")
        tables.forEach { table -> xml.append(table.xml) }
        val filtered = plans.filter { it.sheet.autoFilter && it.sheet.rows.isNotEmpty() }
        if (filtered.isNotEmpty()) {
            xml.append("<table:database-ranges>")
            filtered.forEach { plan ->
                val startRow = if (plan.hasHeader) 1 else plan.firstDataRow
                val endRow = plan.firstDataRow + plan.sheet.rows.size - 1
                val lastColumn = columnLetters((plan.columnCount - 1).coerceAtLeast(0))
                xml.append(
                    "<table:database-range table:target-range-address=\"\$${plan.name}.A$startRow:$lastColumn$endRow\" " +
                        "table:display-filter-buttons=\"true\"/>",
                )
            }
            xml.append("</table:database-ranges>")
        }
        xml.append("</office:spreadsheet></office:body>")
        xml.append("</office:document-content>")
        return xml.toString()
    }

    private fun stylesXml(theme: OfficeTheme): String {
        val xml = StringBuilder()
        xml.append(DECLARATION)
        xml.append(
            "<office:document-styles xmlns:office=\"$NS_OFFICE\" xmlns:style=\"$NS_STYLE\" " +
                "xmlns:text=\"$NS_TEXT\" xmlns:fo=\"$NS_FO\" xmlns:number=\"$NS_NUMBER\" " +
                "xmlns:svg=\"$NS_SVG\" office:version=\"1.3\">",
        )
        xml.append("<office:font-face-decls>")
        xml.append(
            "<style:font-face style:name=\"${Xml.attribute(theme.sansFont)}\" " +
                "svg:font-family=\"'${Xml.attribute(theme.sansFont)}'\"/>",
        )
        xml.append("</office:font-face-decls>")
        xml.append("<office:styles>")
        xml.append(
            "<style:style style:name=\"Default\" style:family=\"table-cell\">" +
                "<style:table-cell-properties fo:padding=\"0.05cm\"/>" +
                "<style:text-properties fo:font-family=\"${Xml.attribute(theme.sansFont)}\" " +
                "fo:font-size=\"10pt\" fo:color=\"${hex(theme.ink)}\"/>" +
                "</style:style>",
        )
        xml.append("</office:styles>")
        xml.append("</office:document-styles>")
        return xml.toString()
    }

    private fun metaXml(title: String): String {
        val stamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))
        val xml = StringBuilder()
        xml.append(DECLARATION)
        xml.append(
            "<office:document-meta xmlns:office=\"$NS_OFFICE\" xmlns:meta=\"$NS_META\" " +
                "xmlns:dc=\"$NS_DC\" office:version=\"1.3\">",
        )
        xml.append("<office:meta>")
        xml.append("<meta:generator>Kino</meta:generator>")
        xml.append("<dc:title>${Xml.escape(title)}</dc:title>")
        xml.append("<dc:creator>Kino</dc:creator>")
        xml.append("<meta:creation-date>$stamp</meta:creation-date>")
        xml.append("</office:meta>")
        xml.append("</office:document-meta>")
        return xml.toString()
    }

    /** LibreOffice stores the frozen panes per table in the settings part. */
    private fun settingsXml(plans: List<SheetPlan>): String {
        val xml = StringBuilder()
        xml.append(DECLARATION)
        xml.append(
            "<office:document-settings xmlns:office=\"$NS_OFFICE\" xmlns:config=\"$NS_CONFIG\" office:version=\"1.3\">",
        )
        xml.append("<office:settings>")
        xml.append("<config:config-item-set config:name=\"Views\">")
        xml.append("<config:config-item-map config:name=\"Tables\">")
        plans.filter { it.freeze != null }.forEach { plan ->
            val freeze = plan.freeze ?: return@forEach
            xml.append("<config:config-item-map-entry config:name=\"${Xml.attribute(plan.name)}\">")
            xml.append(
                "<config:config-item config:name=\"HorizontalSplitMode\" config:type=\"int\">" +
                    "${if (freeze.columns > 0) 2 else 0}</config:config-item>",
            )
            xml.append("<config:config-item config:name=\"VerticalSplitMode\" config:type=\"int\">2</config:config-item>")
            if (freeze.columns > 0) {
                xml.append(
                    "<config:config-item config:name=\"HorizontalSplitPosition\" config:type=\"int\">" +
                        "${freeze.columns}</config:config-item>",
                )
            }
            xml.append(
                "<config:config-item config:name=\"VerticalSplitPosition\" config:type=\"int\">${freeze.rows}</config:config-item>",
            )
            xml.append("<config:config-item config:name=\"ActiveSplitRange\" config:type=\"int\">2</config:config-item>")
            xml.append("<config:config-item config:name=\"ShowGrid\" config:type=\"boolean\">true</config:config-item>")
            xml.append("</config:config-item-map-entry>")
        }
        xml.append("</config:config-item-map>")
        xml.append("</config:config-item-set>")
        xml.append("</office:settings>")
        xml.append("</office:document-settings>")
        return xml.toString()
    }

    /* -------------------------------- styles ------------------------------- */

    /**
     * The OpenDocument styles the workbook needs: number styles, cell styles
     * for the header, the bands, and the totals, plus the column styles.
     */
    private class StyleBook(private val theme: OfficeTheme) {

        private val numberStyles = LinkedHashMap<String, String>()
        private val numberStyleXml = LinkedHashMap<String, String>()
        private val cellStyles = LinkedHashMap<String, String>()
        private val cellStyleXml = LinkedHashMap<String, String>()
        private val columnStyles = LinkedHashMap<String, String>()
        private val columnStyleXml = LinkedHashMap<String, String>()
        private val tableStyles = LinkedHashMap<String, String>()
        private val tableStyleXml = LinkedHashMap<String, String>()

        fun headerStyle(): String = defineCellStyle("header", "KinoHeader", null) {
            "<style:table-cell-properties fo:background-color=\"${hex(theme.accent)}\" " +
                "fo:padding=\"0.08cm\" style:vertical-align=\"middle\"/>" +
                "<style:text-properties fo:font-family=\"${Xml.attribute(theme.sansFont)}\" " +
                "fo:font-size=\"10pt\" fo:font-weight=\"bold\" fo:color=\"#ffffff\"/>"
        }

        fun totalLabelStyle(): String = defineCellStyle("total-label", "KinoTotalLabel", null) {
            "<style:table-cell-properties fo:background-color=\"${hex(theme.accentSoft)}\" " +
                "fo:padding=\"0.05cm\" style:vertical-align=\"middle\"/>" +
                "<style:text-properties fo:font-family=\"${Xml.attribute(theme.sansFont)}\" " +
                "fo:font-size=\"10pt\" fo:font-weight=\"bold\" fo:color=\"${hex(theme.ink)}\"/>"
        }

        fun totalValueStyle(column: SheetColumn?): String {
            val data = numberStyle(defaultFormat(column))
            return defineCellStyle("total-value|$data", "KinoTotalValue", data) {
                "<style:table-cell-properties fo:background-color=\"${hex(theme.accentSoft)}\" " +
                    "fo:padding=\"0.05cm\" style:vertical-align=\"middle\"/>" +
                    "<style:text-properties fo:font-family=\"${Xml.attribute(theme.sansFont)}\" " +
                    "fo:font-size=\"10pt\" fo:font-weight=\"bold\" fo:color=\"${hex(theme.ink)}\"/>"
            }
        }

        fun dateStyle(): String {
            val data = numberStyle(FORMAT_DATE)
            return defineCellStyle("date|$data", "KinoDate", data) {
                "<style:table-cell-properties fo:padding=\"0.05cm\" style:vertical-align=\"middle\"/>" +
                    "<style:text-properties fo:font-family=\"${Xml.attribute(theme.sansFont)}\" " +
                    "fo:font-size=\"10pt\" fo:color=\"${hex(theme.ink)}\"/>"
            }
        }

        fun cellStyle(cell: SheetCell, column: SheetColumn?, banded: Boolean): String {
            val format = cell.format?.trim()?.takeIf { it.isNotEmpty() } ?: defaultFormat(column)
            val data = numberStyle(format)
            val bold = cell.bold
            val align = cell.align
            val alignment = when {
                align != null -> alignmentText(align)
                cell is SheetCell.Number -> "end"
                cell is SheetCell.Bool -> "center"
                else -> "start"
            }
            val background = if (banded) hex(theme.surfaceAlt) else null
            val key = "cell|$data|$bold|$align|$background"
            return defineCellStyle(key, "KinoCell", data) {
                val backgroundXml = background?.let { " fo:background-color=\"$it\"" }.orEmpty()
                val boldXml = if (bold) " fo:font-weight=\"bold\"" else ""
                "<style:table-cell-properties$backgroundXml fo:padding=\"0.05cm\" " +
                    "style:vertical-align=\"middle\"/>" +
                    "<style:text-properties fo:font-family=\"${Xml.attribute(theme.sansFont)}\" " +
                    "fo:font-size=\"10pt\" fo:color=\"${hex(theme.ink)}\"$boldXml/>"
            }
        }

        fun columnStyle(width: Double): String {
            val key = widthText(width)
            return columnStyles.getOrPut(key) {
                val name = "KinoCol${columnStyles.size + 1}"
                val cm = BigDecimal(width * CM_PER_COLUMN_UNIT)
                    .setScale(2, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString()
                columnStyles[key] = name
                columnStyleXml[name] = "<style:style style:name=\"$name\" style:family=\"table-column\">" +
                    "<style:table-column-properties style:column-width=\"${cm}cm\" fo:break-before=\"auto\"/>" +
                    "</style:style>"
                name
            }
        }

        fun tableStyle(color: String): String {
            val key = color.lowercase(Locale.US)
            return tableStyles.getOrPut(key) {
                val name = "KinoTable${tableStyles.size + 1}"
                tableStyles[key] = name
                tableStyleXml[name] = "<style:style style:name=\"$name\" style:family=\"table\">" +
                    "<style:table-properties table:background-color=\"$color\" fo:break-before=\"auto\"/>" +
                    "</style:style>"
                name
            }
        }

        fun automaticStyles(): String {
            val xml = StringBuilder()
            numberStyleXml.values.forEach { xml.append(it) }
            tableStyleXml.values.forEach { xml.append(it) }
            columnStyleXml.values.forEach { xml.append(it) }
            cellStyleXml.values.forEach { xml.append(it) }
            return xml.toString()
        }

        /**
         * Registers a cell style. [name] is the readable base name the style
         * gets in the package; a counter is added when the same base name is
         * needed for more than one variant.
         */
        private fun defineCellStyle(key: String, name: String, dataStyle: String?, definition: () -> String): String {
            return cellStyles.getOrPut(key) {
                val unique = if (cellStyles.values.contains(name)) {
                    var index = 2
                    while (cellStyles.values.contains("$name$index")) index++
                    "$name$index"
                } else {
                    name
                }
                cellStyles[key] = unique
                val dataAttribute = dataStyle?.let { " style:data-style-name=\"$it\"" }.orEmpty()
                cellStyleXml[unique] = "<style:style style:name=\"$unique\" style:family=\"table-cell\" " +
                    "style:parent-style-name=\"Default\"$dataAttribute>${definition()}</style:style>"
                unique
            }
        }

        private fun numberStyle(format: String): String {
            val key = format.lowercase(Locale.US)
            return numberStyles.getOrPut(key) {
                val name = "KinoNumber${numberStyles.size + 1}"
                numberStyles[key] = name
                numberStyleXml[name] = when (format) {
                    FORMAT_PERCENT ->
                        "<number:percentage-style style:name=\"$name\">" +
                            "<number:percentage-style number:decimal-places=\"1\"/>" +
                            "</number:percentage-style>"

                    FORMAT_DATE ->
                        "<number:date-style style:name=\"$name\">" +
                            "<number:day number:style=\"long\"/>" +
                            "<number:month number:style=\"long\"/>" +
                            "<number:year number:style=\"long\"/>" +
                            "</number:date-style>"

                    else ->
                        "<number:number-style style:name=\"$name\">" +
                            "<number:number number:decimal-places=\"${decimalPlaces(format)}\" " +
                            "number:min-integer-digits=\"1\" number:grouping=\"${grouping(format)}\"/>" +
                            "</number:number-style>"
                }
                name
            }
        }

        private fun defaultFormat(column: SheetColumn?): String = when (column?.type ?: ColumnType.TEXT) {
            ColumnType.CURRENCY -> FORMAT_CURRENCY
            ColumnType.PERCENT -> FORMAT_PERCENT
            ColumnType.DATE -> FORMAT_DATE
            else -> FORMAT_NUMBER
        }

        private fun decimalPlaces(format: String): Int {
            val dot = format.indexOf('.')
            if (dot < 0) return 0
            var count = 0
            for (index in dot + 1 until format.length) {
                if (format[index] == '0' || format[index] == '#') count++ else break
            }
            return count.coerceIn(0, 6)
        }

        private fun grouping(format: String): Boolean = format.contains(',')

        private fun alignmentText(alignment: ColumnAlignment): String = when (alignment) {
            ColumnAlignment.LEFT -> "start"
            ColumnAlignment.CENTER -> "center"
            ColumnAlignment.RIGHT -> "end"
        }
    }

    /* ------------------------------- helpers ------------------------------- */

    private fun noteKey(rowNumber: Int, columnIndex: Int): Long = rowNumber * 100000L + columnIndex

    private fun hex(color: String): String {
        val (r, g, b) = Colors.rgb(color)
        return "#%02x%02x%02x".format(r, g, b)
    }

    private fun widthText(width: Double): String = BigDecimal(width)
        .setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()

    private fun numberText(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        return BigDecimal(value).round(MathContext(15)).stripTrailingZeros().toPlainString()
    }

    /** The text a reader sees inside a numeric cell. */
    private fun displayNumber(value: Double, column: SheetColumn?): String {
        val format = column?.format?.trim()?.takeIf { it.isNotEmpty() }
            ?: when (column?.type ?: ColumnType.TEXT) {
                ColumnType.CURRENCY -> FORMAT_CURRENCY
                ColumnType.PERCENT -> FORMAT_PERCENT
                else -> FORMAT_NUMBER
            }
        return when (format) {
            FORMAT_PERCENT -> "%.1f%%".format(Locale.US, value * 100.0)
            FORMAT_CURRENCY -> grouped(value, 2)
            else -> numberText(value)
        }
    }

    private fun grouped(value: Double, decimals: Int): String {
        val rounded = BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP)
        val plain = rounded.toPlainString()
        val negative = plain.startsWith("-")
        val body = if (negative) plain.substring(1) else plain
        val parts = body.split(".")
        val digits = parts[0].reversed().chunked(3).joinToString(",").reversed()
        val text = if (parts.size > 1) "$digits.${parts[1]}" else digits
        return if (negative) "-$text" else text
    }

    private fun parseRef(reference: String): CellRef? {
        val match = Regex("^\\$?([A-Za-z]{1,3})\\$?([0-9]{1,7})$").matchEntire(reference.trim()) ?: return null
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

    /**
     * Translates an Excel style formula into OpenFormula.
     *
     * Returns null when the formula uses something the translation cannot
     * handle, so the caller can fall back to plain text.
     */
    private fun openFormula(expression: String): String? {
        val clean = expression.removePrefix("=").trim()
        if (clean.isEmpty()) return null
        if (clean.contains('!') || clean.contains('[')) return null
        val out = StringBuilder()
        var index = 0
        while (index < clean.length) {
            val character = clean[index]
            if (character == '"') {
                val end = stringEnd(clean, index) ?: return null
                out.append(clean.substring(index, end))
                index = end
                continue
            }
            val match = REF_PATTERN.find(clean, index)
            if (match != null && match.range.first == index && isReference(clean, match)) {
                out.append(referenceText(match.value))
                index = match.range.last + 1
                continue
            }
            out.append(character)
            index++
        }
        return "of:=$out"
    }

    private fun stringEnd(text: String, start: Int): Int? {
        var index = start + 1
        while (index < text.length) {
            if (text[index] == '"') {
                if (index + 1 < text.length && text[index + 1] == '"') {
                    index += 2
                    continue
                }
                return index + 1
            }
            index++
        }
        return null
    }

    /** A reference must stand on its own, not be part of a longer name. */
    private fun isReference(text: String, match: MatchResult): Boolean {
        val before = match.range.first - 1
        if (before >= 0) {
            val character = text[before]
            if (character.isLetterOrDigit() || character == '_' || character == '$' || character == '.') return false
        }
        val after = match.range.last + 1
        if (after < text.length) {
            val character = text[after]
            if (character == '(' || character == '!') return false
        }
        return true
    }

    /** `B2` becomes `[.B2]` and `B2:B9` becomes `[.B2:.B9]`. */
    private fun referenceText(reference: String): String {
        val parts = reference.split(":")
        if (parts.size == 1) return "[.${parts[0].replace("$", "")}]"
        return "[.${parts[0].replace("$", "")}:.${parts[1].replace("$", "")}]"
    }

    /* --------------------------------- zip --------------------------------- */

    /**
     * Writes the package with the `mimetype` entry first and uncompressed, the
     * way the OpenDocument specification requires.
     */
    private fun buildZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream(64 * 1024)
        ZipOutputStream(output).use { zip ->
            zip.setLevel(9)
            val mimetype = MIMETYPE.toByteArray(Charsets.US_ASCII)
            val first = ZipEntry("mimetype")
            first.method = ZipEntry.STORED
            first.size = mimetype.size.toLong()
            first.compressedSize = mimetype.size.toLong()
            first.crc = CRC32().apply { update(mimetype) }.value
            first.time = 0L
            zip.putNextEntry(first)
            zip.write(mimetype)
            zip.closeEntry()

            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                entry.time = 0L
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
