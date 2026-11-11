package com.teamshryne.kino.office.odf

import com.teamshryne.kino.office.ReportIssue
import com.teamshryne.kino.office.model.CalloutBlock
import com.teamshryne.kino.office.model.CalloutKind
import com.teamshryne.kino.office.model.ChartBlock
import com.teamshryne.kino.office.model.ChartKind
import com.teamshryne.kino.office.model.ChartSeries
import com.teamshryne.kino.office.model.CodeBlock
import com.teamshryne.kino.office.model.ColumnAlignment
import com.teamshryne.kino.office.model.DocumentSpec
import com.teamshryne.kino.office.model.HeadingBlock
import com.teamshryne.kino.office.model.ImageBlock
import com.teamshryne.kino.office.model.KeyValueBlock
import com.teamshryne.kino.office.model.KeyValueItem
import com.teamshryne.kino.office.model.ListBlock
import com.teamshryne.kino.office.model.PageBreakBlock
import com.teamshryne.kino.office.model.ParagraphBlock
import com.teamshryne.kino.office.model.QuoteBlock
import com.teamshryne.kino.office.model.StatBlock
import com.teamshryne.kino.office.model.StatItem
import com.teamshryne.kino.office.model.TableBlock
import com.teamshryne.kino.office.model.TableColumn
import com.teamshryne.kino.office.render.PageGeometry
import com.teamshryne.kino.office.theme.Themes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

class OdfTextWriterTest {

    private val theme = Themes.byId("corporate")
    private val geometry = PageGeometry()

    private fun sampleDocument(): DocumentSpec = DocumentSpec(
        title = "Quarterly Review",
        subtitle = "Numbers that matter",
        author = "Kino",
        date = "2024-06-01",
        cover = true,
        toc = true,
        header = "Quarterly Review",
        footer = "Confidential",
        pageNumbers = true,
        blocks = listOf(
            HeadingBlock(1, "Introduction"),
            ParagraphBlock("A **bold** and *italic* line with a [link](https://example.com)."),
            HeadingBlock(2, "Findings"),
            ListBlock(listOf("First point", "Second point")),
            TableBlock(
                columns = listOf(
                    TableColumn("Region", alignment = ColumnAlignment.LEFT),
                    TableColumn("Revenue", alignment = ColumnAlignment.RIGHT),
                ),
                rows = listOf(
                    listOf("North", "1,200"),
                    listOf("South", "980"),
                ),
                caption = "Revenue by region",
            ),
            KeyValueBlock(listOf(KeyValueItem("Owner", "Ada Lovelace", "Data team"))),
            CalloutBlock(CalloutKind.WARNING, "Careful", "Numbers are provisional."),
            QuoteBlock("Simplicity is the ultimate sophistication.", "Leonardo da Vinci"),
            CodeBlock("fun main() {\n    println(\"hi\")\n}"),
            StatBlock(
                listOf(
                    StatItem("Revenue", "\$1.2M", "+8%", true),
                    StatItem("Churn", "4%", "-1%", false),
                ),
            ),
            ChartBlock(
                kind = ChartKind.COLUMN,
                categories = listOf("Q1", "Q2"),
                series = listOf(ChartSeries("Revenue", listOf(10.0, 14.0))),
                title = "Revenue trend",
            ),
            PageBreakBlock,
        ),
    )

    private fun write(
        document: DocumentSpec = sampleDocument(),
        issues: MutableList<ReportIssue> = mutableListOf(),
    ): ByteArray = OdfTextWriter.write(document, theme, geometry, issues)

    private fun names(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result += entry.name
            }
        }
        return result
    }

    private fun entries(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return result
    }

    @Test
    fun `the package holds the required parts`() {
        val parts = names(write())
        assertEquals(
            listOf(
                "mimetype",
                "META-INF/manifest.xml",
                "content.xml",
                "styles.xml",
                "meta.xml",
                "settings.xml",
            ),
            parts,
        )
    }

    @Test
    fun `the mimetype entry comes first and is stored uncompressed`() {
        val entries = mutableListOf<ZipEntry>()
        val contents = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(write())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry
                contents[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals("mimetype", entries.first().name)
        assertEquals(ZipEntry.STORED, entries.first().method)
        assertEquals("application/vnd.oasis.opendocument.text", contents.getValue("mimetype"))
    }

    @Test
    fun `every xml part parses`() {
        val parts = entries(write())
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        parts.entries.filter { it.key.endsWith(".xml") }.forEach { (name, xml) ->
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            assertNotNull(document.documentElement, name)
        }
    }

    @Test
    fun `the manifest declares every part`() {
        val bytes = write()
        val manifest = entries(bytes).getValue("META-INF/manifest.xml")
        listOf("content.xml", "styles.xml", "meta.xml", "settings.xml").forEach {
            assertTrue(manifest.contains("manifest:full-path=\"$it\""), "$it missing from the manifest")
        }
        assertTrue(manifest.contains("manifest:media-type=\"application/vnd.oasis.opendocument.text\""))
    }

    @Test
    fun `the content holds the expected blocks`() {
        val content = entries(write()).getValue("content.xml")
        assertEquals(1, Regex("<office:text>").findAll(content).count())
        assertEquals(1, Regex("<office:body>").findAll(content).count())
        assertTrue(content.contains("Quarterly Review"))
        assertTrue(content.contains("<text:h "), "heading missing")
        assertTrue(content.contains("text:outline-level=\"1\""))
        assertTrue(content.contains("<text:list "), "list missing")
        assertTrue(content.contains("<text:list-item>"))
        assertTrue(content.contains("<table:table "), "table missing")
        assertTrue(content.contains("<table:table-column"))
        assertTrue(content.contains("<table:table-header-rows>"))
        assertTrue(content.contains("Region"), "table header missing")
        assertTrue(content.contains("Revenue by region"), "caption missing")
        assertTrue(content.contains("fo:font-weight=\"bold\""), "bold span missing")
        assertTrue(content.contains("fo:font-style=\"italic\""), "italic span missing")
        assertTrue(content.contains("<text:a xlink:href=\"https://example.com\""), "link missing")
        assertTrue(content.contains("text:style-name=\"Preformatted_20_Text\""), "code missing")
        assertTrue(content.contains("fun main()"))
        assertTrue(content.contains("Simplicity is the ultimate sophistication."))
        assertTrue(content.contains("Revenue trend"), "chart caption missing")
        assertTrue(content.contains("<text:soft-page-break/>"), "page break missing")
    }

    @Test
    fun `the master page carries the running header and the page numbers`() {
        val styles = entries(write()).getValue("styles.xml")
        assertTrue(styles.contains("style:master-page"), "the master page is missing")
        assertTrue(styles.contains("Quarterly Review"), "the running header is missing")
        assertTrue(styles.contains("Confidential"), "the running footer is missing")
        assertTrue(styles.contains("<text:page-number"), "page number missing")
        assertTrue(styles.contains("<text:page-count"), "page count missing")
    }

    @Test
    fun `the styles define the standard style and the page layout`() {
        val styles = entries(write()).getValue("styles.xml")
        assertTrue(styles.contains("style:name=\"Standard\""))
        assertTrue(styles.contains("style:page-layout"))
        assertTrue(styles.contains("fo:page-width"))
        assertTrue(styles.contains("style:master-page"))
    }

    @Test
    fun `a chart becomes a table and reports a warning`() {
        val issues = mutableListOf<ReportIssue>()
        write(issues = issues)
        val chartIssue = issues.firstOrNull { it.message.contains("chart", ignoreCase = true) }
        assertNotNull(chartIssue)
        assertEquals(ReportIssue.Severity.WARNING, chartIssue!!.severity)
    }

    @Test
    fun `an embedded image is stored in the package and declared in the manifest`() {
        val dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png())
        val document = sampleDocument().copy(
            blocks = sampleDocument().blocks + ImageBlock(dataUri, alt = "Logo", caption = "The logo"),
        )
        val bytes = write(document)
        val parts = names(bytes)
        assertTrue(parts.any { it.startsWith("Pictures/") }, "image not stored")
        val manifest = entries(bytes).getValue("META-INF/manifest.xml")
        assertTrue(manifest.contains("manifest:full-path=\"Pictures/image1.png\""))
        val content = entries(bytes).getValue("content.xml")
        assertTrue(content.contains("<draw:frame"))
        assertTrue(content.contains("<draw:image"))
        assertTrue(content.contains("xlink:href=\"Pictures/image1.png\""))
    }

    @Test
    fun `an image that cannot be embedded is reported`() {
        val issues = mutableListOf<ReportIssue>()
        val document = sampleDocument().copy(
            blocks = sampleDocument().blocks + ImageBlock("https://example.com/logo.png"),
        )
        write(document, issues = issues)
        assertTrue(issues.any { it.severity == ReportIssue.Severity.WARNING })
    }

    private fun png(): ByteArray {
        val image = BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB)
        val output = java.io.ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
    }
}
