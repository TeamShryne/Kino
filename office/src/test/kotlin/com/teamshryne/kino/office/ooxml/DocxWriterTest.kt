package com.teamshryne.kino.office.ooxml

import com.teamshryne.kino.office.ReportIssue
import com.teamshryne.kino.office.image.ImageData
import com.teamshryne.kino.office.model.CalloutBlock
import com.teamshryne.kino.office.model.CalloutKind
import com.teamshryne.kino.office.model.ChartBlock
import com.teamshryne.kino.office.model.ChartSeries
import com.teamshryne.kino.office.model.CodeBlock
import com.teamshryne.kino.office.model.ColumnAlignment
import com.teamshryne.kino.office.model.DocumentSpec
import com.teamshryne.kino.office.model.HeadingBlock
import com.teamshryne.kino.office.model.ImageBlock
import com.teamshryne.kino.office.model.KeyValueBlock
import com.teamshryne.kino.office.model.KeyValueItem
import com.teamshryne.kino.office.model.ListBlock
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
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

class DocxWriterTest {

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
            ListBlock(listOf("First point", "Second point"), ordered = false),
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
                kind = com.teamshryne.kino.office.model.ChartKind.COLUMN,
                categories = listOf("Q1", "Q2"),
                series = listOf(ChartSeries("Revenue", listOf(10.0, 14.0))),
                title = "Revenue trend",
            ),
            com.teamshryne.kino.office.model.PageBreakBlock,
        ),
    )

    private fun write(
        document: DocumentSpec = sampleDocument(),
        images: Map<String, ImageData> = emptyMap(),
        issues: MutableList<ReportIssue> = mutableListOf(),
    ): ByteArray = DocxWriter.write(document, theme, geometry, images, issues)

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

    @Test
    fun `produces the required package parts in order`() {
        val bytes = write()
        val parts = names(bytes)
        assertEquals(
            listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "docProps/core.xml",
                "docProps/app.xml",
                "word/document.xml",
                "word/_rels/document.xml.rels",
                "word/styles.xml",
                "word/numbering.xml",
                "word/settings.xml",
                "word/header1.xml",
                "word/footer1.xml",
            ),
            parts,
        )
        assertTrue(parts.none { it.startsWith("word/media/") })
    }

    @Test
    fun `every xml part parses`() {
        val bytes = write()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        names(bytes).filter { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { name ->
            val xml = entries(bytes).getValue(name)
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            assertNotNull(document.documentElement, name)
        }
    }

    @Test
    fun `document body holds the expected content`() {
        val xml = entries(write()).getValue("word/document.xml")
        assertEquals(1, Regex("<w:body>").findAll(xml).count())
        assertTrue(xml.contains("Quarterly Review"), "title missing")
        assertTrue(xml.contains("Region"), "table header missing")
        assertTrue(xml.contains("Revenue"), "table header missing")
        assertTrue(xml.contains("Revenue by region"), "caption missing")
        assertTrue(xml.contains("Kino"), "author missing")
        assertTrue(xml.contains("<w:tbl>"), "table missing")
        assertTrue(xml.contains("TOC \\o \"1-3\""), "toc field missing")
        assertTrue(xml.contains("<w:br w:type=\"page\"/>"), "page break missing")
        assertTrue(xml.contains("fun main()"), "code missing")
        assertTrue(xml.contains("<w:b/>"), "bold run missing")
        assertTrue(xml.contains("<w:i/>"), "italic run missing")
        assertTrue(xml.contains("<w:hyperlink"), "hyperlink missing")
        assertTrue(xml.contains("Revenue trend"), "chart caption missing")
        assertTrue(xml.contains("<w:tblHeader/>"), "table header row missing")
        assertTrue(xml.contains("w:val=\"TableGrid\""), "table style missing")
    }

    @Test
    fun `styles and numbering define everything the document uses`() {
        val parts = entries(write())
        val styles = parts.getValue("word/styles.xml")
        listOf(
            "Normal", "Title", "Subtitle", "Heading1", "Heading2", "Heading3", "Heading4",
            "ListParagraph", "Quote", "QuoteAttribution", "Code", "Caption", "Hyperlink",
            "TableGrid", "Header", "Footer", "TOCHeading",
        ).forEach { style ->
            assertTrue(styles.contains("w:styleId=\"$style\""), "style $style missing")
        }
        val numbering = parts.getValue("word/numbering.xml")
        assertTrue(numbering.contains("w:numFmt w:val=\"bullet\""))
        assertTrue(numbering.contains("w:numFmt w:val=\"decimal\""))
        assertTrue(numbering.contains("<w:num w:numId="))
    }

    @Test
    fun `settings ask word to refresh fields`() {
        val settings = entries(write()).getValue("word/settings.xml")
        assertTrue(settings.contains("<w:updateFields w:val=\"true\"/>"))
        assertTrue(settings.contains("<w:zoom w:percent=\"100\"/>"))
        assertTrue(settings.contains("<w:defaultTabStop w:val=\"720\"/>"))
        assertTrue(settings.contains("<w:compat/>"))
    }

    @Test
    fun `header and footer carry the page fields`() {
        val parts = entries(write())
        val header = parts.getValue("word/header1.xml")
        assertTrue(header.contains("Quarterly Review"))
        val footer = parts.getValue("word/footer1.xml")
        assertTrue(footer.contains("Confidential"))
        assertTrue(footer.contains("PAGE"))
        assertTrue(footer.contains("NUMPAGES"))
    }

    @Test
    fun `relationships referenced by the document all exist`() {
        val bytes = write()
        val parts = entries(bytes)
        val document = parts.getValue("word/document.xml")
        val rels = parts.getValue("word/_rels/document.xml.rels")

        val referenced = Regex("r:(?:id|embed)=\"(rId\\d+)\"").findAll(document).map { it.groupValues[1] }.toSet()
        assertTrue(referenced.isNotEmpty())
        referenced.forEach { id ->
            assertTrue(rels.contains("Id=\"$id\""), "missing relationship $id")
        }

        // Every internal relationship points at a part that is really there.
        Regex("<Relationship ([^>]+)/>").findAll(rels).forEach { match ->
            val attributes = match.groupValues[1]
            val target = Regex("Target=\"([^\"]+)\"").find(attributes)?.groupValues?.get(1) ?: return@forEach
            val external = attributes.contains("TargetMode=\"External\"")
            if (external) return@forEach
            assertTrue(names(bytes).contains("word/$target"), "missing part word/$target")
        }
    }

    @Test
    fun `hyperlinks are external relationships`() {
        val parts = entries(write())
        val rels = parts.getValue("word/_rels/document.xml.rels")
        assertTrue(rels.contains("Target=\"https://example.com\""))
        assertTrue(rels.contains("TargetMode=\"External\""))
        assertTrue(rels.contains("relationships/hyperlink"))
    }

    @Test
    fun `images are embedded with a drawing`() {
        val document = sampleDocument().copy(
            blocks = sampleDocument().blocks + ImageBlock("logo.png", alt = "Logo", caption = "The logo"),
        )
        val bytes = write(document, images = mapOf("logo.png" to png()))
        val documentXml = entries(bytes).getValue("word/document.xml")
        assertTrue(documentXml.contains("<w:drawing>"))
        assertTrue(documentXml.contains("xmlns:wp="))
        assertTrue(documentXml.contains("r:embed="))
        assertTrue(names(bytes).any { it.startsWith("word/media/image1") })
    }

    @Test
    fun `a missing image becomes a note and a warning`() {
        val issues = mutableListOf<ReportIssue>()
        val document = sampleDocument().copy(
            blocks = sampleDocument().blocks + ImageBlock("missing.png", caption = "Nope"),
        )
        val bytes = write(document, issues = issues)
        val xml = entries(bytes).getValue("word/document.xml")
        assertTrue(xml.contains("Image unavailable"))
        assertTrue(issues.any { it.severity == ReportIssue.Severity.WARNING })
        assertTrue(names(bytes).none { it.startsWith("word/media/") })
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
    fun `the page size follows the geometry`() {
        val xml = entries(write()).getValue("word/document.xml")
        val width = (geometry.widthPt * 20).roundToInt()
        val height = (geometry.heightPt * 20).roundToInt()
        assertTrue(xml.contains("w:pgSz w:w=\"$width\" w:h=\"$height\""), "page size missing")
        assertTrue(xml.contains("w:pgMar"))
        assertTrue(xml.contains("<w:sectPr>"))
    }

    @Test
    fun `without page numbers no footer part is written`() {
        val document = sampleDocument().copy(pageNumbers = false, footer = null, header = null)
        val parts = names(write(document))
        assertTrue(parts.none { it == "word/header1.xml" })
        assertTrue(parts.none { it == "word/footer1.xml" })
    }

    private fun png(): ImageData {
        val image = BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB)
        val output = java.io.ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return ImageData(output.toByteArray(), "image/png", "png")
    }

    @Test
    fun `an image block embeds the picture from the image map`() {
        val document = sampleDocument().copy(
            blocks = sampleDocument().blocks + ImageBlock("logo.png", alt = "Logo", caption = "The logo"),
        )
        val bytes = write(document, images = mapOf("logo.png" to png()))
        val documentXml = entries(bytes).getValue("word/document.xml")
        assertTrue(documentXml.contains("<w:drawing>"))
        assertTrue(documentXml.contains("wp:extent"))
        assertTrue(documentXml.contains("The logo"))
        assertTrue(names(bytes).contains("word/media/image1.png"))
    }
}
