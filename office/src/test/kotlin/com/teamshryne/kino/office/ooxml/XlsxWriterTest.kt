package com.teamshryne.kino.office.ooxml

import com.teamshryne.kino.office.ReportIssue
import com.teamshryne.kino.office.model.ColumnAlignment
import com.teamshryne.kino.office.model.ColumnType
import com.teamshryne.kino.office.model.SheetCell
import com.teamshryne.kino.office.model.SheetColumn
import com.teamshryne.kino.office.model.SheetSpec
import com.teamshryne.kino.office.model.WorkbookSpec
import com.teamshryne.kino.office.theme.Themes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XlsxWriterTest {

    private val theme = Themes.byId("corporate")

    private fun workbook(): WorkbookSpec {
        val regions = listOf("North", "South", "East", "West", "Central", "Online", "Retail")
        val rows = regions.mapIndexed { index, region ->
            val growth = if (index == 0) {
                SheetCell.Number(0.0)
            } else {
                val row = index + 2
                SheetCell.Formula("(B$row-B${row - 1})/B${row - 1}", align = ColumnAlignment.RIGHT)
            }
            listOf(
                SheetCell.Text(region, bold = index == 0, align = ColumnAlignment.LEFT),
                SheetCell.Number(100.0 + index * 25),
                SheetCell.Number(19.5 + index, align = ColumnAlignment.RIGHT),
                SheetCell.Number(0.1 * (index + 1), format = "0.0%"),
                if (index == 2) SheetCell.Blank else SheetCell.Bool(index % 2 == 0),
                growth,
            )
        }
        return WorkbookSpec(
            title = "Quarterly numbers",
            theme = "corporate",
            sheets = listOf(
                SheetSpec(
                    name = "Revenue",
                    columns = listOf(
                        SheetColumn("Region", width = 18.0),
                        SheetColumn("Units", width = 12.0, type = ColumnType.NUMBER),
                        SheetColumn("Price", width = 14.0, type = ColumnType.CURRENCY),
                        SheetColumn("Share", width = 10.0, type = ColumnType.PERCENT),
                        SheetColumn("Booked", type = ColumnType.BOOL),
                        SheetColumn("Growth", width = 12.0, type = ColumnType.NUMBER),
                    ),
                    rows = rows,
                    freeze = "A2",
                    autoFilter = true,
                    notes = mapOf("B2" to "Figures exclude returns."),
                    tabColor = "#1F4E79",
                    totalRow = true,
                ),
                SheetSpec(
                    name = "Notes",
                    columns = emptyList(),
                    rows = listOf(
                        listOf(SheetCell.Text("Plain sheet without a header row.")),
                        listOf(SheetCell.Number(42.0)),
                    ),
                ),
            ),
        )
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    @Test
    fun `writes every part of a spreadsheet package`() {
        val bytes = XlsxWriter.write(workbook(), theme)
        assertTrue(bytes.size > 0, "the workbook should not be empty")

        val parts = entries(bytes)
        listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "docProps/core.xml",
            "docProps/app.xml",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/styles.xml",
            "xl/worksheets/sheet1.xml",
            "xl/worksheets/sheet2.xml",
        ).forEach { name ->
            assertTrue(parts.containsKey(name), "missing part $name")
        }
    }

    @Test
    fun `every xml part is well formed`() {
        val parts = entries(XlsxWriter.write(workbook(), theme))
        parts.forEach { (name, bytes) ->
            if (name.endsWith(".xml")) {
                val document = parse(bytes)
                assertNotNull(document.documentElement, "$name has no root element")
            }
        }
    }

    @Test
    fun `the first sheet carries the header and the totals formula`() {
        val parts = entries(XlsxWriter.write(workbook(), theme))
        val sheet = String(parts.getValue("xl/worksheets/sheet1.xml"), Charsets.UTF_8)

        listOf("Region", "Units", "Price", "Share", "Booked", "Growth").forEach { header ->
            assertTrue(sheet.contains(">$header<"), "the header $header is missing")
        }
        assertTrue(sheet.contains("SUM(B2:B8)"), "the totals formula is missing")
        assertTrue(sheet.contains("r=\"A2\""), "the first data cell should be A2")
        assertTrue(sheet.contains("r=\"F8\""), "the last data cell should be F8")
        assertTrue(sheet.contains("<pane"), "the frozen pane is missing")
        assertTrue(sheet.contains("<autoFilter"), "the auto filter is missing")
        assertTrue(sheet.contains("<tabColor"), "the tab colour is missing")
    }

    @Test
    fun `the workbook lists the sheets in order`() {
        val parts = entries(XlsxWriter.write(workbook(), theme))
        val workbook = String(parts.getValue("xl/workbook.xml"), Charsets.UTF_8)

        val revenue = workbook.indexOf("name=\"Revenue\"")
        val notes = workbook.indexOf("name=\"Notes\"")
        assertTrue(revenue >= 0, "the first sheet is missing")
        assertTrue(notes > revenue, "the sheets are out of order")
        assertTrue(workbook.contains("r:id=\"rId1\""))
        assertTrue(workbook.contains("r:id=\"rId2\""))
    }

    @Test
    fun `the styles keep the two fills excel needs`() {
        val parts = entries(XlsxWriter.write(workbook(), theme))
        val styles = String(parts.getValue("xl/styles.xml"), Charsets.UTF_8)

        assertTrue(styles.contains("patternType=\"none\""), "the none fill is missing")
        assertTrue(styles.contains("patternType=\"gray125\""), "the gray125 fill is missing")
        assertTrue(styles.contains("<numFmt "), "the number formats are missing")
        assertTrue(styles.contains("formatCode=\"#,##0.00\""), "the currency format is missing")
        assertTrue(styles.contains("formatCode=\"0.0%\""), "the percent format is missing")
    }

    @Test
    fun `notes become real comments`() {
        val parts = entries(XlsxWriter.write(workbook(), theme))
        assertTrue(parts.containsKey("xl/comments1.xml"), "the comments part is missing")
        assertTrue(parts.containsKey("xl/drawings/vmlDrawing1.vml"), "the comment drawing is missing")
        assertTrue(parts.containsKey("xl/worksheets/_rels/sheet1.xml.rels"), "the sheet relationships are missing")

        val comments = String(parts.getValue("xl/comments1.xml"), Charsets.UTF_8)
        assertTrue(comments.contains("ref=\"B2\""), "the comment is not anchored to B2")
        assertTrue(comments.contains("Figures exclude returns."), "the comment text is missing")

        val sheet = String(parts.getValue("xl/worksheets/sheet1.xml"), Charsets.UTF_8)
        assertTrue(sheet.contains("<legacyDrawing"), "the legacy drawing link is missing")
    }

    @Test
    fun `a sheet name excel cannot use is repaired and reported`() {
        val issues = mutableListOf<ReportIssue>()
        val broken = WorkbookSpec(
            sheets = listOf(SheetSpec(name = "Bad:Name/With*Chars", rows = listOf(listOf(SheetCell.Text("x"))))),
        )
        val parts = entries(XlsxWriter.write(broken, theme, issues))

        val workbook = String(parts.getValue("xl/workbook.xml"), Charsets.UTF_8)
        assertTrue(workbook.contains("name=\"Bad_Name_With_Chars\""), "the name was not repaired")
        assertTrue(issues.any { it.severity == ReportIssue.Severity.WARNING }, "the repair was not reported")
    }
}
