package com.teamshryne.kino.data.tools

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Renders self-contained HTML with Chromium's paginated print engine into app-private storage. */
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
            setLayerType(WebView.LAYER_TYPE_SOFTWARE, null)
        }
        try {
            val safeHtml = sanitize(input)
            val safeTitle = escapeHtml(title)
            val document = """
                <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; font-src data:;">
                <title>$safeTitle</title><style>
                @page { size: A4; margin: 18mm 16mm; }
                * { box-sizing: border-box; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
                html { font-family: Arial, Helvetica, sans-serif; color: #202124; background: #fff; }
                body { margin: 0; font-size: 11pt; line-height: 1.55; overflow-wrap: anywhere; }
                img, svg, video { max-width: 100% !important; height: auto; }
                table { border-collapse: collapse; max-width: 100%; }
                th, td { border: 1px solid #c8cdd3; padding: 5pt 7pt; vertical-align: top; }
                thead { display: table-header-group; } tr, img, blockquote, pre { break-inside: avoid; }
                h1,h2,h3,h4 { break-after: avoid; line-height: 1.2; }
                pre, code { white-space: pre-wrap; overflow-wrap: anywhere; }
                a { color: #174ea6; text-decoration: underline; }
                </style></head><body>$safeHtml</body></html>
            """.trimIndent()
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
                            view?.let { page ->
                                page.measure(
                                    android.view.View.MeasureSpec.makeMeasureSpec(794, android.view.View.MeasureSpec.EXACTLY),
                                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                                )
                                page.layout(0, 0, 794, page.measuredHeight)
                            }
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
            val adapter = webView.createPrintDocumentAdapter("Kino document")
            val attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("kino", "Kino PDF", 300, 300))
                .setMinMargins(PrintAttributes.Margins(600, 680, 600, 680))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()
            val descriptor = ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
            try { withTimeout(PDF_WRITE_TIMEOUT_MS) { print(adapter, attributes, descriptor) } } finally { descriptor.close() }
            require(output.length() > 500) { "PDF renderer produced an empty document." }
            require(output.length() <= MAX_PDF_BYTES) { "PDF exceeds the 25 MB export limit." }
            filename
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private suspend fun print(adapter: PrintDocumentAdapter, attributes: PrintAttributes, descriptor: ParcelFileDescriptor) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            adapter.onLayout(null, attributes, signal, object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: android.print.PrintDocumentInfo?, changed: Boolean) {
                    if (info == null) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("PDF layout failed.")); return }
                    if (info.pageCount > MAX_PDF_PAGES) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalArgumentException("PDF is longer than $MAX_PDF_PAGES pages."))
                        return
                    }
                    adapter.onWrite(arrayOf(PageRange.ALL_PAGES), descriptor, signal, object : PrintDocumentAdapter.WriteResultCallback() {
                        override fun onWriteFinished(pages: Array<PageRange>) { if (continuation.isActive) continuation.resume(Unit) }
                        override fun onWriteFailed(error: CharSequence?) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error?.toString() ?: "PDF export failed.")) }
                        override fun onWriteCancelled() { if (continuation.isActive) continuation.cancel() }
                    }, null)
                }
                override fun onLayoutFailed(error: CharSequence?) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error?.toString() ?: "PDF layout failed.")) }
                override fun onLayoutCancelled() { if (continuation.isActive) continuation.cancel() }
            }, null)
        }
    }

    private fun sanitize(html: String): String {
        var safe = html
        listOf("script", "iframe", "object", "embed", "form", "video", "audio").forEach { tag ->
            safe = safe.replace(Regex("(?is)<$tag\\b[^>]*>.*?</$tag\\s*>"), "")
            safe = safe.replace(Regex("(?is)<$tag\\b[^>]*/?>"), "")
        }
        safe = safe.replace(Regex("(?is)<(base|meta)\\b[^>]*>"), "")
            .replace(Regex("(?is)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"), "")
            .replace(Regex("(?i)javascript\\s*:"), "blocked:")
        return safe
    }

    private fun escapeHtml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private companion object {
        const val PAGE_LOAD_TIMEOUT_MS = 30_000L
        const val PDF_WRITE_TIMEOUT_MS = 60_000L
        const val MAX_PDF_PAGES = 100
        const val MAX_PDF_BYTES = 25L * 1024 * 1024
    }
}
