package com.teamshryne.kino.data.tools

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.print.PrintAttributes
import android.print.pdf.PrintedPdfDocument
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Renders self-contained HTML with Chromium, then paginates it to a print-resolution PDF. */
class HtmlPdfRenderer(private val context: Context) {
    suspend fun create(title: String, input: String): String = withContext(Dispatchers.Main) {
        require(input.length <= LocalToolRegistry.MAX_HTML_CHARS) { "HTML is too large to render." }
        val dir = File(context.filesDir, "pdf-exports").apply { mkdirs() }
        val filename = "kino-${UUID.randomUUID()}.pdf"
        val output = File(dir, filename)
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.useWideViewPort = true
        }
        var pdf: PrintedPdfDocument? = null
        try {
            val document = htmlDocument(title, sanitize(input))
            withTimeout(PAGE_LOAD_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val scheme = request?.url?.scheme?.lowercase()
                            return if (scheme == "data" || scheme == "blob" || scheme == "about") null
                            else WebResourceResponse("text/plain", "UTF-8", null)
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            if (request?.isForMainFrame == true && continuation.isActive) continuation.resumeWithException(IllegalStateException("Could not render HTML."))
                        }
                    }
                    continuation.invokeOnCancellation { webView.stopLoading() }
                    webView.loadDataWithBaseURL("https://kino.local/", document, "text/html", "UTF-8", null)
                }
            }

            val attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("kino", "Kino PDF", PDF_DPI, PDF_DPI))
                .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()
            val printDocument = PrintedPdfDocument(context, attributes)
            pdf = printDocument
            val density = webView.resources.displayMetrics.density
            val cssToPdf = PDF_DPI / CSS_DPI
            val marginX = (PAGE_MARGIN_X_MM / MM_PER_INCH * PDF_DPI).roundToInt()
            val marginY = (PAGE_MARGIN_Y_MM / MM_PER_INCH * PDF_DPI).roundToInt()
            val contentWidthPdf = printDocument.pageWidth - marginX * 2
            val contentHeightPdf = printDocument.pageHeight - marginY * 2
            val viewToPdf = cssToPdf / density
            val contentWidthCss = contentWidthPdf / cssToPdf
            val viewWidth = (contentWidthCss * density).roundToInt()
            webView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(viewWidth, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            val contentHeight = webView.measuredHeight
            require(contentHeight > 0) { "The HTML document has no printable content." }
            webView.layout(0, 0, viewWidth, contentHeight)
            val pageContentHeightView = contentHeightPdf / viewToPdf
            val pageCount = ceil(contentHeight / pageContentHeightView).toInt().coerceAtLeast(1)
            require(pageCount <= MAX_PDF_PAGES) { "PDF is longer than $MAX_PDF_PAGES pages." }

            for (pageIndex in 0 until pageCount) {
                val page = printDocument.startPage(pageIndex + 1)
                val canvas = page.canvas
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.save()
                canvas.translate(marginX.toFloat(), marginY.toFloat())
                canvas.scale(viewToPdf, viewToPdf)
                canvas.clipRect(0f, 0f, viewWidth.toFloat(), pageContentHeightView)
                canvas.translate(0f, -pageIndex * pageContentHeightView)
                webView.draw(canvas)
                canvas.restore()
                drawPageNumber(page, pageIndex + 1, pageCount, marginX, marginY)
                printDocument.finishPage(page)
            }

            withTimeout(PDF_WRITE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { FileOutputStream(output).use { printDocument.writeTo(it) } }
            }
            require(output.length() in MIN_PDF_BYTES..MAX_PDF_BYTES) { "PDF output is empty or exceeds the 25 MB export limit." }
            filename
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            pdf?.close()
            webView.stopLoading()
            webView.destroy()
        }
    }

    private fun drawPageNumber(page: PdfDocument.Page, pageNumber: Int, pageCount: Int, marginX: Int, marginY: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.GRAY
            textSize = FOOTER_TEXT_SIZE
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        val canvas = page.canvas
        canvas.drawText(
            "$pageNumber / $pageCount",
            (page.info.pageWidth - marginX).toFloat(),
            (page.info.pageHeight - marginY / 2).toFloat(),
            paint,
        )
    }

    private fun htmlDocument(title: String, body: String): String {
        val safeTitle = escapeHtml(title)
        return """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; font-src data:;">
            <title>$safeTitle</title><style>
            * { box-sizing: border-box; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
            html { font-family: Arial, Helvetica, sans-serif; color: #202124; background: #fff; }
            body { margin: 0; font-size: 11pt; line-height: 1.55; overflow-wrap: anywhere; }
            img, svg { max-width: 100% !important; height: auto; }
            table { border-collapse: collapse; max-width: 100%; }
            th, td { border: 1px solid #c8cdd3; padding: 5pt 7pt; vertical-align: top; }
            thead { display: table-header-group; } tr, img, blockquote, pre { break-inside: avoid; }
            h1,h2,h3,h4 { break-after: avoid; line-height: 1.2; }
            pre, code { white-space: pre-wrap; overflow-wrap: anywhere; }
            a { color: #174ea6; text-decoration: underline; }
            </style></head><body>$body</body></html>
        """.trimIndent()
    }

    private fun sanitize(html: String): String {
        var safe = html
        listOf("script", "iframe", "object", "embed", "form", "video", "audio").forEach { tag ->
            safe = safe.replace(Regex("(?is)<$tag\\b[^>]*>.*?</$tag\\s*>"), "")
            safe = safe.replace(Regex("(?is)<$tag\\b[^>]*/?>"), "")
        }
        return safe.replace(Regex("(?is)<(base|meta)\\b[^>]*>"), "")
            .replace(Regex("(?is)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"), "")
            .replace(Regex("(?i)javascript\\s*:"), "blocked:")
    }

    private fun escapeHtml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private companion object {
        const val PAGE_LOAD_TIMEOUT_MS = 30_000L
        const val PDF_WRITE_TIMEOUT_MS = 60_000L
        const val PDF_DPI = 300
        const val CSS_DPI = 96f
        const val MM_PER_INCH = 25.4f
        const val PAGE_MARGIN_X_MM = 16f
        const val PAGE_MARGIN_Y_MM = 18f
        const val FOOTER_TEXT_SIZE = 24f
        const val MAX_PDF_PAGES = 100
        const val MIN_PDF_BYTES = 500L
        const val MAX_PDF_BYTES = 25L * 1024 * 1024
    }
}
