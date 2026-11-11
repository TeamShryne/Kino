package com.teamshryne.kino.office

import com.teamshryne.kino.office.image.ImageData
import com.teamshryne.kino.office.json.JsonException
import com.teamshryne.kino.office.json.KinoJson
import com.teamshryne.kino.office.json.JsonValue
import com.teamshryne.kino.office.model.DeckSpec
import com.teamshryne.kino.office.model.DocumentSpec
import com.teamshryne.kino.office.model.WorkbookSpec
import com.teamshryne.kino.office.odf.OdfPresentationWriter
import com.teamshryne.kino.office.odf.OdfSpreadsheetWriter
import com.teamshryne.kino.office.odf.OdfTextWriter
import com.teamshryne.kino.office.ooxml.DocxWriter
import com.teamshryne.kino.office.ooxml.PptxWriter
import com.teamshryne.kino.office.ooxml.XlsxWriter
import com.teamshryne.kino.office.parse.SpecParser
import com.teamshryne.kino.office.render.CsvWriter
import com.teamshryne.kino.office.render.EpubWriter
import com.teamshryne.kino.office.render.HtmlRenderer
import com.teamshryne.kino.office.render.MarkdownWriter
import com.teamshryne.kino.office.render.PageGeometry
import com.teamshryne.kino.office.render.PlainTextWriter
import com.teamshryne.kino.office.render.RtfWriter
import com.teamshryne.kino.office.theme.OfficeTheme
import com.teamshryne.kino.office.theme.Themes
import java.util.Locale
import java.util.UUID

/**
 * Turns a JSON payload from the model into a finished office file.
 *
 * This is the single entry point the app uses: it parses, validates, renders,
 * and reports back what happened so the model can fix a file it is not happy
 * with instead of guessing.
 */
object OfficeBuilder {

    /** Parses a document payload without rendering it. */
    fun parseDocument(payload: String): Pair<DocumentSpec, List<ReportIssue>> {
        val issues = mutableListOf<ReportIssue>()
        val root = objectOf(payload)
        return SpecParser.parseDocument(root, issues) to issues
    }

    /** Parses a workbook payload without rendering it. */
    fun parseWorkbook(payload: String): Pair<WorkbookSpec, List<ReportIssue>> {
        val issues = mutableListOf<ReportIssue>()
        val root = objectOf(payload)
        return SpecParser.parseWorkbook(root, issues) to issues
    }

    /** Parses a deck payload without rendering it. */
    fun parseDeck(payload: String): Pair<DeckSpec, List<ReportIssue>> {
        val issues = mutableListOf<ReportIssue>()
        val root = objectOf(payload)
        return SpecParser.parseDeck(root, issues) to issues
    }

    /** Renders a document to a complete HTML page. [print] switches to print styling. */
    fun renderHtml(document: DocumentSpec, print: Boolean): String {
        val theme = themeFor(document.theme, document.accent)
        val geometry = PageGeometry(document.pageSize, document.orientation)
        return HtmlRenderer(theme, geometry, print).render(document)
    }

    /**
     * Builds a file for every format except PDF, which needs the Android
     * WebView print pipeline and is handled by the app.
     */
    fun build(
        format: OfficeFormat,
        payload: String,
        images: Map<String, ImageData> = emptyMap(),
    ): OfficeResult {
        require(format != OfficeFormat.PDF) { "PDF is rendered by the host application." }
        val issues = mutableListOf<ReportIssue>()
        return when (format.family) {
            OfficeFamily.DOCUMENT -> buildDocument(format, payload, images, issues)
            OfficeFamily.SHEET -> buildSheet(format, payload, issues)
            OfficeFamily.DECK -> buildDeck(format, payload, issues)
            OfficeFamily.DATA -> buildData(format, payload, issues)
        }
    }

    private fun buildDocument(
        format: OfficeFormat,
        payload: String,
        images: Map<String, ImageData>,
        issues: MutableList<ReportIssue>,
    ): OfficeResult {
        val root = objectOf(payload)
        val document = SpecParser.parseDocument(root, issues)
        val theme = themeFor(document.theme, document.accent)
        val geometry = PageGeometry(document.pageSize, document.orientation)
        val bytes = when (format) {
            OfficeFormat.DOCX -> DocxWriter.write(document, theme, geometry, images, issues)
            OfficeFormat.ODT -> OdfTextWriter.write(document, theme, geometry, issues)
            OfficeFormat.HTML -> HtmlRenderer(theme, geometry, print = false).render(document).toByteArray(Charsets.UTF_8)
            OfficeFormat.EPUB -> EpubWriter.write(document, theme, geometry, issues)
            OfficeFormat.MARKDOWN -> MarkdownWriter.write(document).toByteArray(Charsets.UTF_8)
            OfficeFormat.RTF -> RtfWriter.write(document, theme, geometry).toByteArray(Charsets.UTF_8)
            OfficeFormat.TEXT -> PlainTextWriter.write(document).toByteArray(Charsets.UTF_8)
            else -> throw SpecException("Unsupported document format: ${format.id}")
        }
        val summary = describeDocument(document, issues)
        return OfficeResult(
            format = format,
            fileName = fileName(document.title, format),
            mimeType = format.mimeType,
            bytes = bytes,
            title = document.title,
            summary = summary,
            issues = issues.toList(),
        )
    }

    private fun buildSheet(format: OfficeFormat, payload: String, issues: MutableList<ReportIssue>): OfficeResult {
        val root = objectOf(payload)
        val workbook = SpecParser.parseWorkbook(root, issues)
        val theme = themeFor(workbook.theme, null)
        val bytes = when (format) {
            OfficeFormat.XLSX -> XlsxWriter.write(workbook, theme, issues)
            OfficeFormat.ODS -> OdfSpreadsheetWriter.write(workbook, theme, issues)
            OfficeFormat.CSV -> CsvWriter.write(workbook.sheets.first(), ',').toByteArray(Charsets.UTF_8)
            OfficeFormat.TSV -> CsvWriter.write(workbook.sheets.first(), '\t').toByteArray(Charsets.UTF_8)
            else -> throw SpecException("Unsupported sheet format: ${format.id}")
        }
        val sheetSummary = workbook.sheets.joinToString(", ") { it.name }
        return OfficeResult(
            format = format,
            fileName = fileName(workbook.title, format),
            mimeType = format.mimeType,
            bytes = bytes,
            title = workbook.title,
            summary = "${workbook.sheets.size} sheet(s): $sheetSummary",
            issues = issues.toList(),
        )
    }

    private fun buildDeck(format: OfficeFormat, payload: String, issues: MutableList<ReportIssue>): OfficeResult {
        val root = objectOf(payload)
        val deck = SpecParser.parseDeck(root, issues)
        val theme = themeFor(deck.theme, deck.accent)
        val bytes = when (format) {
            OfficeFormat.PPTX -> PptxWriter.write(deck, theme, issues)
            OfficeFormat.ODP -> OdfPresentationWriter.write(deck, theme, issues)
            else -> throw SpecException("Unsupported presentation format: ${format.id}")
        }
        return OfficeResult(
            format = format,
            fileName = fileName(deck.title, format),
            mimeType = format.mimeType,
            bytes = bytes,
            title = deck.title,
            summary = "${deck.slides.size} slide(s)",
            issues = issues.toList(),
        )
    }

    private fun buildData(format: OfficeFormat, payload: String, issues: MutableList<ReportIssue>): OfficeResult {
        val root = objectOf(payload)
        val content = SpecParser.parseData(root)
        val text = when (format) {
            OfficeFormat.JSON -> validateJson(content, issues)
            OfficeFormat.YAML -> content
            OfficeFormat.XML -> validateXml(content, issues)
            else -> throw SpecException("Unsupported data format: ${format.id}")
        }
        return OfficeResult(
            format = format,
            fileName = fileName(rootTitle(root, "data"), format),
            mimeType = format.mimeType,
            bytes = text.toByteArray(Charsets.UTF_8),
            title = rootTitle(root, "data"),
            summary = "${text.length} characters",
            issues = issues.toList(),
        )
    }

    private fun validateJson(content: String, issues: MutableList<ReportIssue>): String = try {
        KinoJson.parse(content)
        content
    } catch (error: JsonException) {
        issues += ReportIssue(ReportIssue.Severity.WARNING, "The JSON content could not be parsed: ${error.message}. It was written as-is.")
        content
    }

    private fun validateXml(content: String, issues: MutableList<ReportIssue>): String {
        return try {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                java.io.ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
            )
            content
        } catch (error: Exception) {
            issues += ReportIssue(ReportIssue.Severity.WARNING, "The XML content could not be parsed: ${error.message}. It was written as-is.")
            content
        }
    }

    private fun rootTitle(root: JsonValue.Obj, fallback: String): String =
        (root["title"] as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() } ?: fallback

    private fun objectOf(payload: String): JsonValue.Obj {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) throw SpecException("The document payload is empty.")
        val parsed = try {
            KinoJson.parse(trimmed)
        } catch (error: JsonException) {
            throw SpecException("The document payload is not valid JSON: ${error.message}")
        }
        return parsed as? JsonValue.Obj
            ?: throw SpecException("The document payload must be a JSON object.")
    }

    private fun describeDocument(document: DocumentSpec, issues: List<ReportIssue>): String {
        val words = document.blocks.sumOf { block ->
            when (block) {
                is com.teamshryne.kino.office.model.ParagraphBlock -> block.text.split(Regex("\\s+")).count { it.isNotBlank() }
                is com.teamshryne.kino.office.model.HeadingBlock -> block.text.split(Regex("\\s+")).count { it.isNotBlank() }
                else -> 0
            }
        }
        val parts = mutableListOf("${document.blocks.size} block(s)")
        if (words > 0) parts += "$words words"
        if (issues.isNotEmpty()) parts += "${issues.size} note(s)"
        return parts.joinToString(", ")
    }

    /** Resolves the theme and applies an optional accent override. */
    fun themeFor(themeId: String?, accent: String?): OfficeTheme {
        val base = Themes.byId(themeId)
        val hex = accent?.trim()?.takeIf { it.matches(Regex("^#?[0-9a-fA-F]{6}$")) }?.let {
            if (it.startsWith("#")) it else "#$it"
        } ?: return base
        return base.copy(
            accent = hex,
            accentSoft = com.teamshryne.kino.office.render.Colors.lighten(hex, 0.86),
        )
    }

    /** Builds a safe, shareable file name. */
    fun fileName(title: String, format: OfficeFormat): String {
        val slug = title.trim().lowercase(Locale.US)
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .take(48)
            .trim('-')
            .ifBlank { "document" }
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "$slug-$suffix.${format.extension}"
    }
}
