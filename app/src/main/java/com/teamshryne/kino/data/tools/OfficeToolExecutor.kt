package com.teamshryne.kino.data.tools

import android.content.Context
import com.teamshryne.kino.office.OfficeBuilder
import com.teamshryne.kino.office.OfficeFamily
import com.teamshryne.kino.office.OfficeFormat
import com.teamshryne.kino.office.OfficeResult
import com.teamshryne.kino.office.OfficeSchema
import com.teamshryne.kino.office.ReportIssue
import com.teamshryne.kino.office.image.ImageData
import com.teamshryne.kino.office.image.Images
import com.teamshryne.kino.office.model.DeckSpec
import com.teamshryne.kino.office.model.DocumentSpec
import com.teamshryne.kino.office.model.ImageBlock
import com.teamshryne.kino.office.render.PageGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the office document tools: it turns the model's payload into a real
 * file on disk and reports back what was produced.
 *
 * The model never writes HTML, CSS or XML by hand. It describes structure and
 * content, Kino applies the design system, and this class returns a short
 * report - including layout problems - so the model can improve the file in a
 * follow-up turn instead of guessing.
 */
class OfficeToolExecutor(
    private val context: Context,
    private val renderPdf: suspend (title: String, html: String, geometry: PageGeometry, decorations: PdfDecorations) -> PdfOutcome,
) {

    private val imageCache = ConcurrentHashMap<String, ImageData?>()

    /** Builds an office file and returns the tool detail payload. */
    suspend fun create(
        toolName: String,
        format: OfficeFormat,
        payload: String,
        themeOverride: String?,
    ): LocalToolResult = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "office-exports").apply { mkdirs() }
        check(directory.isDirectory) { "Could not prepare the export folder." }

        val effectivePayload = applyTheme(payload, themeOverride)
        val result = when (format) {
            OfficeFormat.PDF -> buildPdf(effectivePayload, directory)
            else -> {
                val built = buildFile(format, effectivePayload)
                val target = File(directory, built.fileName)
                target.outputStream().use { output -> output.write(built.bytes) }
                built
            }
        }
        val file = File(directory, result.fileName)
        check(file.isFile) { "The generated file was not saved." }
        LocalToolResult(toolName, "Created ${format.label}", detailJson(format, result))
    }

    /** Returns the schema reference for one document family. */
    fun help(topic: String?): LocalToolResult {
        val text = when (topic?.trim()?.lowercase()) {
            "sheet", "spreadsheet", "xlsx", "ods", "csv" -> OfficeSchema.sheetHelp
            "deck", "presentation", "pptx", "odp" -> OfficeSchema.deckHelp
            "data", "json", "yaml", "xml" -> OfficeSchema.dataHelp
            "document", "docx", "odt", "pdf", "html", "md", "epub" -> OfficeSchema.documentHelp
            else -> listOf(
                OfficeSchema.documentHelp,
                OfficeSchema.sheetHelp,
                OfficeSchema.deckHelp,
                OfficeSchema.dataHelp,
            ).joinToString("\n\n")
        }
        return LocalToolResult("document_help", "Read the document schema", text)
    }

    private suspend fun buildFile(format: OfficeFormat, payload: String): OfficeResult {
        val images = resolveImages(format, payload)
        return OfficeBuilder.build(format, payload, images)
    }

    private suspend fun buildPdf(payload: String, directory: File): OfficeResult {
        val (document, parseIssues) = OfficeBuilder.parseDocument(payload)
        val geometry = PageGeometry(document.pageSize, document.orientation)
        val html = OfficeBuilder.renderHtml(document, print = true)
        val fileName = OfficeBuilder.fileName(document.title, OfficeFormat.PDF)
        val output = File(directory, fileName)
        val decorations = PdfDecorations(
            header = document.header,
            footer = document.footer,
            pageNumbers = document.pageNumbers,
            title = document.title,
        )
        val outcome = renderPdf(document.title, html, geometry, decorations)
        if (!output.isFile) throw IllegalStateException("The PDF renderer did not produce a file.")
        val issues = parseIssues.toMutableList()
        outcome.issues.forEach { issues += ReportIssue(ReportIssue.Severity.WARNING, it) }
        return OfficeResult(
            format = OfficeFormat.PDF,
            fileName = fileName,
            mimeType = OfficeFormat.PDF.mimeType,
            bytes = ByteArray(0),
            title = document.title,
            summary = "${outcome.pages} page(s)",
            issues = issues,
            sizeBytes = output.length().toInt(),
        )
    }

    /** Collects and resolves every image the payload references. */
    private suspend fun resolveImages(format: OfficeFormat, payload: String): Map<String, ImageData> {
        val sources = try {
            when (format.family) {
                OfficeFamily.DECK -> OfficeBuilder.parseDeck(payload).first.imageSources()
                OfficeFamily.DOCUMENT -> OfficeBuilder.parseDocument(payload).first.imageSources()
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
        if (sources.isEmpty()) return emptyMap()
        val resolved = mutableMapOf<String, ImageData>()
        for (source in sources.distinct()) {
            val image = imageCache.getOrPut(source) {
                if (Images.isDataUri(source)) Images.decodeDataUri(source) else fetchImage(source)
            }
            if (image != null) resolved[source] = image
        }
        return resolved
    }

    private fun fetchImage(source: String): ImageData? {
        if (!Images.isRemote(source)) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Kino/1.0")
            }
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytesLimited(MAX_IMAGE_BYTES) } ?: return null
            val mimeType = connection.contentType?.substringBefore(';')?.trim()?.lowercase() ?: "image/png"
            val extension = Images.extensionFor(mimeType) ?: return null
            ImageData(bytes, mimeType, extension)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /** Applies a theme override to the payload without rewriting its content. */
    private fun applyTheme(payload: String, themeOverride: String?): String {
        if (themeOverride.isNullOrBlank()) return payload
        return try {
            val root = JSONObject(payload)
            root.put("theme", themeOverride.trim().lowercase())
            root.toString()
        } catch (_: Exception) {
            payload
        }
    }

    private fun detailJson(format: OfficeFormat, result: OfficeResult): String {
        val detail = JSONObject()
            .put("filename", result.fileName)
            .put("title", result.title)
            .put("format", format.id)
            .put("label", format.label)
            .put("mime", result.mimeType)
            .put("summary", result.summary)
            .put("bytes", result.sizeBytes)
        val issues = JSONArray()
        result.issues.forEach { issue ->
            issues.put(
                JSONObject()
                    .put("severity", if (issue.severity == ReportIssue.Severity.ERROR) "error" else "warning")
                    .put("message", issue.describe()),
            )
        }
        detail.put("issues", issues)
        return detail.toString()
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
    }
}

/** Header and footer content drawn on every PDF page. */
data class PdfDecorations(
    val header: String?,
    val footer: String?,
    val pageNumbers: Boolean,
    val title: String,
)

/** What the PDF renderer produced, including layout problems worth reporting. */
data class PdfOutcome(
    val fileName: String,
    val pages: Int,
    val issues: List<String>,
)

/** Collects every image source referenced by a document. */
fun DocumentSpec.imageSources(): List<String> = blocks.mapNotNull { (it as? ImageBlock)?.source }

/** Collects every image source referenced by a deck. */
fun DeckSpec.imageSources(): List<String> = slides.flatMap { slide ->
    val fromBlocks = slide.blocks.mapNotNull { (it as? ImageBlock)?.source }
    val fromColumns = slide.columns.flatten().mapNotNull { (it as? ImageBlock)?.source }
    fromBlocks + fromColumns
}
