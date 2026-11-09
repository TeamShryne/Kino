package com.teamshryne.kino.data.tools

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.view.View
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Renders model HTML to PDF with an off-screen Android System WebView and
 * its print adapter. JavaScript is enabled so model scripts run and render
 * before printing; navigation, popups, downloads, and file access stay off.
 *
 * Two modes:
 * - paged ([pages] has one HTML entry per A4 page): each entry is wrapped in
 *   a fixed-height `.kino-page` section, so every entry becomes exactly one
 *   PDF page.
 * - flow (legacy `html` argument): one document that flows across pages with
 *   normal print pagination.
 */
class HtmlPdfRenderer(private val context: Context) {

    suspend fun create(title: String, pages: List<String>, paged: Boolean): String {
        require(pages.isNotEmpty()) { "HTML content is empty." }
        require(pages.all { it.isNotBlank() }) { "HTML content is empty." }
        require(pages.none { it.length > LocalToolRegistry.MAX_PAGE_CHARS }) { "A single HTML page is too large to render." }
        require(pages.sumOf { it.length } <= LocalToolRegistry.MAX_HTML_CHARS) { "HTML is too large to render." }

        val directory = File(context.filesDir, "pdf-exports").apply { mkdirs() }
        check(directory.isDirectory) { "Could not prepare PDF export storage." }
        val output = File(directory, "kino-${UUID.randomUUID()}.pdf")
        try {
            val document = htmlDocument(title, pages, paged)
            withTimeout(PDF_RENDER_TIMEOUT_MS) {
                renderWithWebView(document, title, output)
            }
            checkPdf(output)
            output.name
        } catch (error: CancellationException) {
            output.delete()
            throw error
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private suspend fun renderWithWebView(document: String, title: String, output: File): Unit =
        withContext(Dispatchers.Main.immediate) {
            val appContext = context.applicationContext ?: context
            val webView = WebView(appContext)
            try {
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = false
                    loadsImagesAutomatically = true
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    mediaPlaybackRequiresUserGesture = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                // A4 at 96dpi so print layout matches the .kino-page sections.
                val widthSpec = View.MeasureSpec.makeMeasureSpec(PAGE_WIDTH_PX, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(PAGE_HEIGHT_PX, View.MeasureSpec.EXACTLY)
                webView.measure(widthSpec, heightSpec)
                webView.layout(0, 0, PAGE_WIDTH_PX, PAGE_HEIGHT_PX)

                val pageFinished = CompletableDeferred<Unit>()
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        // Block file/content URLs; allow https/data/blob subresources.
                        val scheme = request.url.scheme?.lowercase().orEmpty()
                        return if (scheme in setOf("https", "data", "blob", "about")) null
                            else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                        handler.cancel()
                    }
                    override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
                        callback.backToSafety(true)
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) pageFinished.complete(Unit)
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        if (!pageFinished.isCompleted) pageFinished.complete(Unit)
                    }
                }

                webView.loadDataWithBaseURL(null, document, "text/html; charset=utf-8", "UTF-8", null)
                withTimeout(PAGE_LOAD_TIMEOUT_MS) { pageFinished.await() }
                awaitJavaScriptSettled(webView)
                printToFile(webView, title, output)
            } finally {
                try {
                    webView.stopLoading()
                    webView.removeAllViews()
                    webView.destroy()
                } catch (_: Throwable) {
                    // Best-effort teardown.
                }
            }
        }

    /** Waits for images/fonts/DOM, and for an optional async-ready flag, so JS renders into the PDF. */
    private suspend fun awaitJavaScriptSettled(webView: WebView) {
        // Give inline scripts a chance to run and paint on load.
        delay(900)
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val state = evaluate(webView, READY_SCRIPT)
            when (state) {
                "ready" -> break
                "waiting" -> { /* window.__kinoPdfReady === false; keep waiting */ }
                else -> {
                    // unset: DOM/images not settled yet, or only async-ready unset
                    if (state == "settled") break
                }
            }
            delay(180)
        }
        // Final paint beat after settling.
        delay(350)
    }

    private suspend fun evaluate(webView: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { value ->
                if (continuation.isActive) continuation.resume(value?.trim('"', '\\') ?: "")
            }
            continuation.invokeOnCancellation { /* WebView has no cancel; timed by caller */ }
        }

    private suspend fun printToFile(webView: WebView, title: String, output: File) {
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setResolution(PrintAttributes.Resolution("kino", "Kino", 300, 300))
            .build()
        val jobName = "Kino " + title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(48).ifBlank { "Document" }
        val cancellationSignal = CancellationSignal()
        val adapter = webView.createPrintDocumentAdapter(jobName)
        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { pfd ->
            suspendCancellableCoroutine<Unit> { continuation ->
                try {
                    adapter.onStart()
                    adapter.onLayout(
                        null, attributes, cancellationSignal,
                        object : PrintDocumentAdapter.LayoutResultCallback() {
                            override fun onLayoutFinished(info: PrintDocumentInfo, newAttributes: PrintAttributes) {
                                if (!continuation.isActive) return
                                val pageCount = info.pageCount
                                if (pageCount <= 0) {
                                    continuation.resumeWithException(IllegalStateException("PDF renderer returned no pages."))
                                    return
                                }
                                if (pageCount > MAX_PAGES) {
                                    continuation.resumeWithException(IllegalStateException("PDF exceeds the $MAX_PAGES page limit."))
                                    return
                                }
                                val range = PrintDocumentAdapter.PageRange(0, pageCount - 1)
                                try {
                                    adapter.onWrite(
                                        arrayOf(range), pfd, cancellationSignal,
                                        object : PrintDocumentAdapter.WriteResultCallback() {
                                            override fun onWriteFinished(pages: Array<PrintDocumentAdapter.PageRange>) {
                                                if (continuation.isActive) continuation.resume(Unit)
                                            }
                                            override fun onWriteFailed(error: CharSequence) {
                                                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error.toString()))
                                            }
                                            override fun onWriteCancelled() {
                                                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("PDF rendering was cancelled."))
                                            }
                                        },
                                    )
                                } catch (error: Throwable) {
                                    if (continuation.isActive) continuation.resumeWithException(error)
                                }
                            }
                            override fun onLayoutFailed(error: CharSequence) {
                                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error.toString()))
                            }
                            override fun onLayoutCancelled() {
                                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("PDF rendering was cancelled."))
                            }
                        },
                        0,
                    )
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                continuation.invokeOnCancellation {
                    cancellationSignal.cancel()
                    try { adapter.onFinish() } catch (_: Throwable) { }
                }
            }
        }
    }

    private fun checkPdf(output: File) {
        require(output.isFile) { "PDF output is empty." }
        require(output.length() in MIN_PDF_BYTES..MAX_PDF_BYTES) { "PDF output is empty or exceeds the 25 MB export limit." }
        val header = output.inputStream().use { input ->
            val buffer = ByteArray(5)
            var offset = 0
            while (offset < 5) {
                val read = input.read(buffer, offset, 5 - offset)
                if (read < 0) break
                offset += read
            }
            buffer.copyOf(offset)
        }
        require(header.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) { "PDF renderer did not produce a PDF." }
    }

    private fun htmlDocument(title: String, pages: List<String>, paged: Boolean): String {
        val sections = buildString {
            pages.forEachIndexed { index, page ->
                val body = extractBody(page)
                val styles = extractStyles(page)
                append("<section class=\"kino-page\">")
                if (styles.isNotBlank()) append("<style>").append(styles).append("</style>")
                append(body)
                append("</section>")
                if (paged && index != pages.lastIndex) append("\n")
            }
        }
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${escapeHtml(title)}</title>
            <style>
            @page { size: A4; margin: 0; }
            * { box-sizing: border-box; }
            html, body { margin: 0; padding: 0; }
            body { font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; color: #202124; font-size: 11pt; line-height: 1.5; }
            .kino-page { width: ${PAGE_WIDTH_PX}px; ${if (paged) "max-height: ${PAGE_HEIGHT_PX}px; overflow: hidden; min-height: ${PAGE_HEIGHT_PX}px; height: ${PAGE_HEIGHT_PX}px; page-break-after: always; break-after: page;" else ""}
                          padding: 14mm 12mm; overflow-wrap: anywhere; word-wrap: break-word; }
            .kino-page:last-child { page-break-after: auto; break-after: auto; }
            img, svg, video, canvas { max-width: 100%; height: auto; }
            table { border-collapse: collapse; width: 100%; max-width: 100%; }
            th, td { border: 1px solid #c8cdd3; padding: 5pt 7pt; vertical-align: top; }
            thead { display: table-header-group; } tr, img, blockquote, pre { break-inside: avoid; }
            h1, h2, h3, h4 { break-after: avoid; line-height: 1.2; }
            pre, code { white-space: pre-wrap; overflow-wrap: anywhere; }
            a { color: #174ea6; }
            </style></head><body>$sections</body></html>
        """.trimIndent()
    }

    private fun extractBody(page: String): String {
        val bodyMatch = Regex("(?is)<body[^>]*>(.*?)</body>").find(page)
        if (bodyMatch != null) return bodyMatch.groupValues[1].trim()
        // Fragment or full doc without explicit body: strip wrappers, keep content.
        var text = page.trim()
        text = text.replace(Regex("(?is)<!doctype[^>]*>"), "")
        text = text.replace(Regex("(?is)</?html[^>]*>"), "")
        text = text.replace(Regex("(?is)<head[^>]*>.*?</head>"), "")
        text = text.replace(Regex("(?is)<title[^>]*>.*?</title>"), "")
        return text.trim()
    }

    private fun extractStyles(page: String): String =
        Regex("(?is)<style[^>]*>(.*?)</style>").findAll(page)
            .joinToString("\n") { it.groupValues[1].trim() }
            .trim()

    private fun escapeHtml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private companion object {
        const val PDF_RENDER_TIMEOUT_MS = 90_000L
        const val PAGE_LOAD_TIMEOUT_MS = 20_000L
        const val SETTLE_TIMEOUT_MS = 7_000L
        const val MIN_PDF_BYTES = 500
        const val MAX_PDF_BYTES = 25 * 1024 * 1024
        const val MAX_PAGES = 20
        const val PAGE_WIDTH_PX = 794
        const val PAGE_HEIGHT_PX = 1123
        const val READY_SCRIPT = """(() => {
            try {
              if (window.__kinoPdfReady === false) return 'waiting';
              const imgs = Array.from(document.images);
              const imagesDone = imgs.every(i => i.complete);
              const domDone = document.readyState === 'complete' || document.readyState === 'interactive';
              if (domDone && imagesDone) return window.__kinoPdfReady === true ? 'ready' : 'settled';
              return 'pending';
            } catch (e) { return 'settled'; }
          })()"""
    }
}
