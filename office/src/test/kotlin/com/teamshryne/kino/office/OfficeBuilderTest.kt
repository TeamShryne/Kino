package com.teamshryne.kino.office

import com.teamshryne.kino.office.image.ImageData
import com.teamshryne.kino.office.json.KinoJson
import com.teamshryne.kino.office.model.Orientation
import com.teamshryne.kino.office.model.PageSize
import com.teamshryne.kino.office.render.PageGeometry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class OfficeBuilderTest {

    private val documentPayload = """
        {
          "title": "Q3 Revenue Review",
          "subtitle": "Prepared for the board",
          "author": "Analytics",
          "date": "October 2026",
          "theme": "corporate",
          "toc": true,
          "blocks": [
            {"type": "stats", "items": [
              {"label": "Revenue", "value": "$4.2M", "delta": "+12%", "positive": true},
              {"label": "Churn", "value": "1.8%"}]},
            {"type": "heading", "level": 1, "text": "Highlights", "numbered": true},
            {"type": "paragraph", "text": "Revenue grew **12%** quarter over quarter, driven by *enterprise* and [the new tier](https://example.com)."},
            {"type": "bullets", "items": ["Enterprise grew 24%", "SMB was flat"]},
            {"type": "chart", "kind": "column", "categories": ["Jul", "Aug", "Sep"], "series": [{"name": "Revenue", "values": [120, 138, 162]}]},
            {"type": "table", "columns": ["Region", "Revenue", "Share"], "rows": [["NA", "$2.1M", "50%"], ["EMEA", "$1.4M", "33%"]]},
            {"type": "key_values", "items": [{"label": "Owner", "value": "Analytics"}]},
            {"type": "callout", "kind": "warning", "title": "Watch", "text": "EMEA growth slowed."},
            {"type": "quote", "text": "Revenue is a lagging indicator.", "attribution": "Finance"},
            {"type": "code", "text": "sum(1, 2)", "language": "python"},
            {"type": "image", "src": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "caption": "A pixel"},
            {"type": "divider"},
            {"type": "page_break"},
            {"type": "heading", "level": 2, "text": "Outlook"},
            {"type": "paragraph", "text": "We expect flat growth."}
          ]
        }
    """.trimIndent()

    private val sheetPayload = """
        {
          "title": "FY26 Plan",
          "theme": "financial",
          "sheets": [{
            "name": "Revenue", "freeze": "A2", "autofilter": true, "total_row": true,
            "columns": [
              {"header": "Month", "type": "text"},
              {"header": "Revenue", "type": "currency", "format": "#,##0.00"},
              {"header": "Growth", "type": "percent"}],
            "rows": [
              ["January", 120000, 0.08],
              ["February", 138500, 0.154],
              ["Total", "=SUM(B2:B3)", "=AVERAGE(C2:C3)"]]
          }]
        }
    """.trimIndent()

    private val deckPayload = """
        {
          "title": "FY26 Product Strategy",
          "theme": "midnight",
          "slides": [
            {"layout": "title", "title": "FY26 Product Strategy", "subtitle": "Where we play and why"},
            {"layout": "metrics", "title": "Where we are today",
             "blocks": [{"type": "stats", "items": [{"label": "ARR", "value": "$18M"}, {"label": "NPS", "value": "54"}]}]},
            {"layout": "content", "title": "Three bets",
             "blocks": [{"type": "bullets", "items": ["Own the workflow", "Sell to the buyer", "Price on outcomes"]}],
             "notes": "Lead with the first bet."}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesADocumentPayload() {
        val (document, issues) = OfficeBuilder.parseDocument(documentPayload)
        assertEquals("Q3 Revenue Review", document.title)
        assertEquals("corporate", document.theme)
        assertEquals(17, document.blocks.size)
        assertTrue(issues.none { it.severity == ReportIssue.Severity.ERROR })
    }

    @Test
    fun rejectsAnUnknownBlockType() {
        val payload = """{"title":"X","blocks":[{"type":"nonsense"}]}"""
        val error = runCatching { OfficeBuilder.parseDocument(payload) }.exceptionOrNull()
        assertTrue(error is SpecException, "expected a SpecException, got $error")
        assertTrue(error!!.message!!.contains("nonsense"))
    }

    @Test
    fun rejectsInvalidJson() {
        val error = runCatching { OfficeBuilder.parseDocument("{not json") }.exceptionOrNull()
        assertTrue(error is SpecException)
        assertTrue(error!!.message!!.contains("not valid JSON"))
    }

    @Test
    fun padsRowsThatDoNotMatchTheColumnCount() {
        val payload = """{"title":"X","blocks":[{"type":"table","columns":["A","B"],"rows":[["1"]]}]}"""
        val (document, issues) = OfficeBuilder.parseDocument(payload)
        assertEquals(1, issues.count { it.severity == ReportIssue.Severity.WARNING })
        assertEquals(2, document.blocks.first().let { (it as com.teamshryne.kino.office.model.TableBlock).rows.first().size })
    }

    @Test
    fun rendersHtmlWithTheTheme() {
        val (document, _) = OfficeBuilder.parseDocument(documentPayload)
        val html = OfficeBuilder.renderHtml(document, print = false)
        assertTrue(html.contains("Q3 Revenue Review"))
        assertTrue(html.contains("<strong>12%</strong>"))
        assertTrue(html.contains("kino-chart"))
        assertTrue(html.contains("#1F4E79"))
    }

    @Test
    fun buildsEveryDocumentFormat() {
        val formats = listOf(
            OfficeFormat.DOCX,
            OfficeFormat.ODT,
            OfficeFormat.HTML,
            OfficeFormat.EPUB,
            OfficeFormat.MARKDOWN,
            OfficeFormat.RTF,
            OfficeFormat.TEXT,
        )
        formats.forEach { format ->
            val result = OfficeBuilder.build(format, documentPayload)
            assertTrue(result.bytes.isNotEmpty(), "$format produced no bytes")
            assertTrue(result.fileName.endsWith(".${format.extension}"))
            if (format == OfficeFormat.HTML || format == OfficeFormat.MARKDOWN || format == OfficeFormat.TEXT || format == OfficeFormat.RTF) {
                assertTrue(result.bytes.size > 200, "$format looks empty")
            } else {
                val entries = zipEntries(result.bytes)
                assertTrue(entries.isNotEmpty(), "$format is not a zip")
                entries.filter { it.endsWith(".xml") || it.endsWith(".xhtml") || it.endsWith(".opf") || it.endsWith(".ncx") }
                    .forEach { entry ->
                        val content = zipText(result.bytes, entry)
                        assertXmlParses(content, "$format/$entry")
                    }
            }
        }
    }

    @Test
    fun buildsEverySheetFormat() {
        listOf(OfficeFormat.XLSX, OfficeFormat.ODS, OfficeFormat.CSV, OfficeFormat.TSV).forEach { format ->
            val result = OfficeBuilder.build(format, sheetPayload)
            assertTrue(result.bytes.isNotEmpty(), "$format produced no bytes")
            if (format == OfficeFormat.XLSX || format == OfficeFormat.ODS) {
                zipText(result.bytes, "xl/workbook.xml")
                val entries = zipEntries(result.bytes)
                assertTrue(entries.any { it.endsWith("sheet1.xml") })
            }
        }
    }

    @Test
    fun buildsEveryDeckFormat() {
        listOf(OfficeFormat.PPTX, OfficeFormat.ODP).forEach { format ->
            val result = OfficeBuilder.build(format, deckPayload)
            assertTrue(result.bytes.isNotEmpty(), "$format produced no bytes")
            val entries = zipEntries(result.bytes)
            assertTrue(entries.isNotEmpty())
        }
    }

    @Test
    fun buildsDataFiles() {
        val payload = """{"title":"Config","content":"{\"a\":1}"}"""
        val result = OfficeBuilder.build(OfficeFormat.JSON, payload)
        assertTrue(String(result.bytes).contains("\"a\""))
    }

    @Test
    fun namesFilesSafely() {
        val name = OfficeBuilder.fileName("Q3 / Revenue: Review!", OfficeFormat.DOCX)
        assertTrue(name.matches(Regex("[a-z0-9][a-z0-9-]{0,79}\\.[a-z0-9]{1,10}")))
        assertTrue(name.endsWith(".docx"))
    }

    @Test
    fun parsesInlineMarkup() {
        val spans = com.teamshryne.kino.office.model.InlineMarkup.parse("a **b** *c* `d` [e](https://x)")
        assertEquals(5, spans.size)
        assertTrue(spans[1].bold)
        assertTrue(spans[2].italic)
        assertTrue(spans[3].code)
        assertEquals("https://x", spans[4].link)
    }

    @Test
    fun geometryMatchesA4() {
        val geometry = PageGeometry(PageSize.A4, Orientation.PORTRAIT)
        assertEquals(794, geometry.widthPx)
        assertEquals(1123, geometry.heightPx)
        assertEquals(794 - geometry.marginLeftPx - geometry.marginRightPx, geometry.contentWidthPx)
    }

    @Test
    fun jsonParserHandlesNestedValues() {
        val value = KinoJson.parse("""{"a":[1,2,{"b":"c"}],"d":true,"e":null}""")
        assertTrue(value is com.teamshryne.kino.office.json.JsonValue.Obj)
    }

    @Test
    fun imageDataUriIsDecoded() {
        val image = com.teamshryne.kino.office.image.Images.decodeDataUri(
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )
        assertTrue(image != null)
        assertEquals("png", image!!.extension)
    }

    private fun zipEntries(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.closeEntry()
            }
        }
        return names
    }

    private fun zipText(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) return zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        throw IllegalStateException("Missing zip entry: $name")
    }

    private fun assertXmlParses(content: String, label: String) {
        try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
        } catch (error: Exception) {
            throw AssertionError("$label is not valid XML: ${error.message}", error)
        }
    }
}
