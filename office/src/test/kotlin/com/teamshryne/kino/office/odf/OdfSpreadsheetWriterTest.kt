package com.teamshryne.kino.office.odf

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

class OdfSpreadsheetWriterTest {

    private val theme = Themes.byId("corporate")

    private fun workbook(): WorkbookSpec {
        val regions = listOf("North", "South", "East", "West")
        val rows = regions.mapIndexed { index, region ->
            listOf(
                SheetCell.Text(region, bold = index == 0),
                SheetCell.Number(100.0 + index * 25),
                SheetCell.Number(19.5 + index, align = ColumnAlignment.RIGHT),
                SheetCell.Number(0.1 * (index + 1), format = "0.0%"),
                SheetCell.Bool(index % 2 == 0),
                if (index == 0) {
                    SheetCell.Number(0.0)
                } else {
                    SheetCell.Formula("(B${index + 2}-B${index + 1})/B${index + 1}")
                },
            )
        }
        return WorkbookSpec(
            title = "Quarterly numbers",
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
                    rows = listOf(listOf(SheetCell.Text("Plain sheet without a header row."))),
                ),
            ),
        )
    }

    private data class Part(val name: String, val bytes: ByteArray, val method: Int)

    private fun parts(bytes: ByteArray): List<Part> {
        val result = mutableListOf<Part>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                result += Part(entry.name, zip.readBytes(), entry.method)
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
    fun `the mimetype comes first and is stored uncompressed`() {
        val parts = parts(OdfSpreadsheetWriter.write(workbook(), theme))
        val first = parts.first()

        assertEquals("mimetype", first.name, "the mimetype must be the first entry")
        assertEquals(ZipEntry.STORED, first.method, "the mimetype must not be compressed")
        assertEquals(
            "application/vnd.oasis.opendocument.spreadsheet",
            String(first.bytes, Charsets.US_ASCII),
        )
    }

    @Test
    fun `the package holds every open document part`() {
        val parts = parts(OdfSpreadsheetWriter.write(workbook(), theme)).map { it.name }
        listOf(
            "mimetype",
            "META-INF/manifest.xml",
            "content.xml",
            "styles.xml",
            "meta.xml",
        ).forEach { name ->
            assertTrue(parts.contains(name), "missing part $name")
        }
    }

    @Test
    fun `every xml part is well formed`() {
        parts(OdfSpreadsheetWriter.write(workbook(), theme)).forEach { part ->
            if (part.name.endsWith(".xml")) {
                val document = parse(part.bytes)
                assertNotNull(document.documentElement, "${part.name} has no root element")
            }
        }
    }

    @Test
    fun `the content holds both tables with their headers`() {
        val parts = parts(OdfSpreadsheetWriter.write(workbook(), theme))
        val content = String(parts.first { it.name == "content.xml" }.bytes, Charsets.UTF_8)

        assertTrue(content.contains("table:name=\"Revenue\""), "the first table is missing")
        assertTrue(content.contains("table:name=\"Notes\""), "the second table is missing")
        listOf("Region", "Units", "Price", "Share", "Booked", "Growth").forEach { header ->
            assertTrue(content.contains(">$header</text:p>"), "the header $header is missing")
        }
        assertTrue(content.contains("KinoHeader"), "the header style is missing")
        assertTrue(content.contains("number:number-style"), "the number styles are missing")
        assertTrue(content.contains("style:column-width"), "the column widths are missing")
        assertTrue(content.contains("table:formula="), "the formulas are missing")
        assertTrue(content.contains("of:=SUM([.B2:.B5])"), "the totals formula is missing")
        assertTrue(content.contains("office:annotation"), "the note is missing")
    }

    @Test
    fun `the manifest lists the parts of the document`() {
        val parts = parts(OdfSpreadsheetWriter.write(workbook(), theme))
        val manifest = String(parts.first { it.name == "META-INF/manifest.xml" }.bytes, Charsets.UTF_8)

        assertTrue(manifest.contains("manifest:full-path=\"/\""))
        assertTrue(manifest.contains("manifest:full-path=\"content.xml\""))
        assertTrue(manifest.contains("manifest:full-path=\"styles.xml\""))
        assertTrue(manifest.contains("manifest:full-path=\"meta.xml\""))
    }

    @Test
    fun `a sheet name that is too long is shortened and reported`() {
        val issues = mutableListOf<ReportIssue>()
        val long = "A very long sheet name that will not fit into excel either"
        val broken = WorkbookSpec(
            sheets = listOf(SheetSpec(name = long, rows = listOf(listOf(SheetCell.Text("x"))))),
        )
        val parts = parts(OdfSpreadsheetWriter.write(broken, theme, issues))
        val content = String(parts.first { it.name == "content.xml" }.bytes, Charsets.UTF_8)

        assertTrue(content.contains(long.take(31)), "the name was not shortened")
        assertTrue(issues.any { it.severity == ReportIssue.Severity.WARNING }, "the repair was not reported")
    }
}
