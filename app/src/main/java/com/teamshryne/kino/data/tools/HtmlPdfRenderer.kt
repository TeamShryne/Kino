package com.teamshryne.kino.data.tools

import android.content.Context
import com.teamshryne.ironpress.IronpressConverter
import com.teamshryne.kino.data.libraries.RuntimeLibraries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Creates a paginated PDF with the native Ironpress renderer. */
class HtmlPdfRenderer(private val context: Context) {
    suspend fun create(title: String, input: String): String = withContext(Dispatchers.IO) {
        RuntimeLibraries(context).loadIronpress()
        require(input.isNotBlank()) { "HTML content is empty." }
        require(input.length <= LocalToolRegistry.MAX_HTML_CHARS) { "HTML is too large to render." }
        val directory = File(context.filesDir, "pdf-exports").apply { mkdirs() }
        check(directory.isDirectory) { "Could not prepare PDF export storage." }
        val output = File(directory, "kino-${UUID.randomUUID()}.pdf")
        try {
            val document = htmlDocument(title, input)
            val pdf = withTimeout(PDF_RENDER_TIMEOUT_MS) {
                IronpressConverter().use { converter ->
                    converter.pageSize(IronpressConverter.PAGE_A4)
                        .margins(51f, 45f, 51f, 45f)
                        .autoResizeImages(true)
                        .convertHtml(document)
                }
            }
            require(pdf.size in MIN_PDF_BYTES..MAX_PDF_BYTES) {
                "PDF output is empty or exceeds the 25 MB export limit."
            }
            FileOutputStream(output).use { it.write(pdf) }
            output.name
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private fun htmlDocument(title: String, body: String): String = """
        <!doctype html><html><head><meta charset="utf-8"><title>${escapeHtml(title)}</title>
        <style>
        @page { size: A4; margin: 18mm 16mm; }
        * { box-sizing: border-box; }
        body { font-family: sans-serif; color: #202124; font-size: 11pt; line-height: 1.55; overflow-wrap: anywhere; }
        img,svg { max-width: 100%; height: auto; }
        table { border-collapse: collapse; max-width: 100%; }
        th,td { border: 1px solid #c8cdd3; padding: 5pt 7pt; vertical-align: top; }
        thead { display: table-header-group; } tr,img,blockquote,pre { break-inside: avoid; }
        h1,h2,h3,h4 { break-after: avoid; line-height: 1.2; }
        pre,code { white-space: pre-wrap; overflow-wrap: anywhere; }
        a { color: #174ea6; }
        </style></head><body>$body</body></html>
    """.trimIndent()

    private fun escapeHtml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private companion object {
        const val PDF_RENDER_TIMEOUT_MS = 90_000L
        const val MIN_PDF_BYTES = 500
        const val MAX_PDF_BYTES = 25 * 1024 * 1024
    }
}
