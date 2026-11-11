package com.teamshryne.kino.data.tools

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.teamshryne.kino.office.render.PageGeometry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Renders model HTML to PDF by drawing an off-screen Android System WebView
 * into a [PdfDocument]. JavaScript is enabled so model scripts run and render
 * before drawing; navigation, popups, downloads, and file access stay off.
 *
 * Two modes:
 * - paged (legacy `html_to_pdf` with one HTML entry per page): each entry is
 *   wrapped in a fixed-height `.kino-page` section, so every entry becomes
 *   exactly one PDF page.
 * - flow (`create_document` with format "pdf"): a single continuous document
 *   that Kino paginates itself. Forced page breaks are honoured, and the
 *   renderer reports back anything that lands badly on a page boundary so the
 *   model can fix the document in a follow-up turn.
 */
class HtmlPdfRenderer(
    private val context: Context,
    private val limits: PdfLimits = PdfLimits(),
) {

    /** Legacy entry point used by the `html_to_pdf` tool. */
    suspend fun create(title: String, pages: List<String>, paged: Boolean): String {
        val safeLimits = limits.sanitized()
        require(pages.isNotEmpty()) { "HTML content is empty." }
        require(pages.all { it.isNotBlank() }) { "HTML content is empty." }
        require(pages.size <= safeLimits.maxPages) { "pages must contain 1–${safeLimits.maxPages} HTML pages." }
        require(pages.none { it.length > safeLimits.maxPageChars }) { "A single HTML page is too large to render." }
        require(pages.sumOf { it.length } <= safeLimits.maxHtmlChars) { "HTML is too large to render." }

        val directory = File(context.filesDir, "pdf-exports").apply { mkdirs() }
        check(directory.isDirectory) { "Could not prepare PDF export storage." }
        val output = File(directory, "kino-${UUID.randomUUID()}.pdf")
        try {
            val document = htmlDocument(title, pages, paged)
            withTimeout(PDF_RENDER_TIMEOUT_MS) {
                renderWithWebView(document, title, output, PageGeometry.A4) { webView, geometry ->
                    printPaged(webView, geometry, output)
                }
            }
            checkPdf(output)
            return output.name
        } catch (error: CancellationException) {
            output.delete()
            throw error
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    /**
     * Renders a complete HTML document and paginates it.
     *
     * The document flows continuously; Kino decides where the pages break and
     * returns a short list of layout problems (a table split across pages, a
     * heading stranded at the bottom of a page) so the caller can report them.
     */
    suspend fun createFromHtml(
        title: String,
        html: String,
        geometry: PageGeometry,
        decorations: PdfDecorations,
        output: File,
    ): PdfOutcome {
        val safeLimits = limits.sanitized()
        require(html.isNotBlank()) { "The document is empty." }
        require(html.length <= safeLimits.maxHtmlChars) { "The document is too large to render." }
        output.parentFile?.mkdirs()
        try {
            val outcome = withTimeout(PDF_RENDER_TIMEOUT_MS) {
                renderWithWebView(html, title, output, geometry) { webView, pageGeometry ->
                    printFlow(webView, pageGeometry, decorations, output)
                }
            }
            checkPdf(output)
            return outcome
        } catch (error: CancellationException) {
            output.delete()
            throw error
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private suspend fun renderWithWebView(
        document: String,
        title: String,
        output: File,
        geometry: PageGeometry,
        draw: suspend (WebView, PageGeometry) -> PdfOutcome,
    ): PdfOutcome = withContext(Dispatchers.Main.immediate) {
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
            // A4 at 96 CSS px/inch: the page is 794x1123 CSS px, and WebView maps
            // 1 CSS px to 1dp, so the view must be 794dp wide. Sizing it to 794
            // *physical* px made the CSS viewport only ~260-450dp wide on
            // high-density phones, which clipped every desktop-width layout.
            val density = appContext.resources.displayMetrics.density
            val widthPx = dpToPx(geometry.widthPx, density)
            val heightPx = dpToPx(geometry.contentHeightPx, density)
            val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            webView.measure(widthSpec, heightSpec)
            webView.layout(0, 0, widthPx, heightPx)

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
            fitToWidth(webView)
            draw(webView, geometry)
        } finally {
            try {
                webView.onPause()
                webView.removeJavascriptInterface("Android")
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

    /**
     * Guarantees the model layout fits the page width before drawing: if
     * anything sticks out horizontally, shrink the page with CSS `zoom` (which
     * reflows and wraps text, unlike transform) until it fits. This repairs
     * desktop-width flex rows, timelines, tables, and missing `max-width`
     * mistakes that would otherwise clip on the right.
     */
    private suspend fun fitToWidth(webView: WebView) {
        var zoom = 1f
        val textLength = evaluate(webView, """(() => (document.body ? document.body.innerText : '').trim().length)()""").toIntOrNull() ?: 0
        val imageCount = evaluate(webView, """(() => document.images.length)()""").toIntOrNull() ?: 0
        require(textLength > 0 || imageCount > 0) { "HTML rendered an empty page." }
        repeat(FIT_ATTEMPTS) {
            val raw = evaluate(webView, OVERFLOW_SCRIPT)
            val viewport = raw.substringAfter("\"vw\":").takeWhile(Char::isDigit).toIntOrNull()?.toFloat()
            val scroll = raw.substringAfter("\"sw\":").takeWhile(Char::isDigit).toIntOrNull()?.toFloat()
            if (viewport == null || scroll == null || scroll <= viewport + OVERFLOW_TOLERANCE_PX) return
            val factor = (viewport / scroll).coerceIn(MIN_ZOOM, 0.99f)
            zoom = (zoom * factor).coerceAtLeast(MIN_ZOOM)
            evaluate(webView, "document.body.style.zoom = '${String.format(Locale.US, "%.3f", zoom)}'; 'ok';")
            delay(300)
        }
    }

    /**
     * Renders the legacy per-page sections. Each `.kino-page` section is
     * rendered on its own: hide all other sections, size the view to that
     * section's height, then draw its slices. Scrolling an off-screen WebView
     * instead leaves pages after the first blank (its tiles never repaint
     * off-viewport), and sizing the view to the whole document would allocate
     * a pages×1123dp bitmap (hundreds of MB at high density).
     */
    private suspend fun printPaged(webView: WebView, geometry: PageGeometry, output: File): PdfOutcome {
        val density = context.resources.displayMetrics.density
        val maxPages = limits.maxPages.coerceIn(1, HARD_MAX_PAGES)
        val sectionCount = evaluate(webView, "String(document.querySelectorAll('.kino-page').length)").toIntOrNull() ?: 1
        require(sectionCount in 1..maxPages) { "PDF exceeds the $maxPages page limit." }
        val widthPx = dpToPx(geometry.widthPx, density)
        val pageHeightPx = dpToPx(geometry.heightPx, density)
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        val pdf = PdfDocument()
        try {
            val scale = geometry.widthPt.toFloat() / widthPx.toFloat()
            var pdfPage = 0
            for (sectionIndex in 0 until sectionCount) {
                val sectionHeightCss = sectionHeight(webView, sectionIndex, geometry.heightPx)
                val slices = (sectionHeightCss + geometry.heightPx - 1) / geometry.heightPx
                require(pdfPage + slices <= maxPages) { "PDF exceeds the $maxPages page limit." }
                val sectionHeightPx = dpToPx(sectionHeightCss, density)
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(sectionHeightPx, View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, widthPx, sectionHeightPx)
                delay(PAGE_RENDER_DELAY_MS) // let the newly shown section paint
                for (slice in 0 until slices) {
                    val info = PdfDocument.PageInfo.Builder(geometry.widthPt.toInt(), geometry.heightPt.toInt(), pdfPage).create()
                    val page = pdf.startPage(info)
                    val canvas = page.canvas
                    canvas.save()
                    canvas.scale(scale, scale)
                    canvas.translate(0f, -slice * pageHeightPx.toFloat())
                    webView.draw(canvas)
                    canvas.restore()
                    pdf.finishPage(page)
                    delay(PAGE_RENDER_DELAY_MS)
                    pdfPage++
                }
            }
            // File encoding happens off the main thread so it never janks chat UI.
            withContext(Dispatchers.IO) {
                output.outputStream().use { pdf.writeTo(it) }
            }
            return PdfOutcome(output.name, pdfPage, emptyList())
        } finally {
            pdf.close()
        }
    }

    /**
     * Renders a continuous document, choosing the page breaks and reporting
     * anything that lands badly.
     *
     * The view stays one page tall; the body is shifted up for each page so
     * memory stays bounded no matter how long the document is.
     */
    private suspend fun printFlow(
        webView: WebView,
        geometry: PageGeometry,
        decorations: PdfDecorations,
        output: File,
    ): PdfOutcome {
        val density = context.resources.displayMetrics.density
        val maxPages = limits.maxPages.coerceIn(1, HARD_MAX_PAGES)
        val measurement = evaluate(webView, MEASURE_SCRIPT)
        val contentHeight = measurement.substringAfter("\"height\":").takeWhile(Char::isDigit).toIntOrNull() ?: 0
        require(contentHeight > 0) { "The document rendered empty." }
        val breaks = measurement.substringAfter("\"breaks\":[").substringBefore(']')
            .split(',').mapNotNull { it.trim().toIntOrNull() }.sorted()
        val blocks = parseBlocks(measurement)

        val pageHeight = geometry.contentHeightPx
        val starts = mutableListOf(0)
        var start = 0
        while (start < contentHeight) {
            var end = minOf(start + pageHeight, contentHeight)
            val forced = breaks.filter { it > start && it < end }.minOrNull()
            if (forced != null) end = forced
            if (end <= start) break
            start = end
            if (start < contentHeight) starts += start
        }
        require(starts.size <= maxPages) {
            "This document needs ${starts.size} pages but the limit is $maxPages. Shorten it or split it into two documents."
        }

        val issues = layoutIssues(starts, blocks, pageHeight)

        val widthPx = dpToPx(geometry.widthPx, density)
        val viewHeightPx = dpToPx(geometry.contentHeightPx, density)
        val marginTopPx = dpToPx(geometry.marginTopPx, density)
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        val pdf = PdfDocument()
        try {
            val scale = geometry.widthPt.toFloat() / widthPx.toFloat()
            starts.forEachIndexed { index, pageStart ->
                evaluate(webView, "document.body.style.transform = 'translateY(-${pageStart}px)'; document.body.offsetHeight; 'ok';")
                webView.invalidate()
                delay(PAGE_RENDER_DELAY_MS)
                val info = PdfDocument.PageInfo.Builder(geometry.widthPt.toInt(), geometry.heightPt.toInt(), index).create()
                val page = pdf.startPage(info)
                val canvas = page.canvas
                canvas.save()
                canvas.scale(scale, scale)
                canvas.translate(0f, marginTopPx.toFloat())
                webView.draw(canvas)
                canvas.restore()
                drawFurniture(canvas, decorations, index, starts.size, geometry, density)
                pdf.finishPage(page)
                delay(PAGE_RENDER_DELAY_MS)
            }
            withContext(Dispatchers.IO) {
                output.outputStream().use { pdf.writeTo(it) }
            }
            return PdfOutcome(output.name, starts.size, issues)
        } finally {
            pdf.close()
        }
    }

    /** Draws the running header and footer on top of the page content. */
    private fun drawFurniture(
        canvas: android.graphics.Canvas,
        decorations: PdfDecorations,
        pageIndex: Int,
        pageCount: Int,
        geometry: PageGeometry,
        density: Float,
    ) {
        val scale = geometry.widthPt.toFloat() / dpToPx(geometry.widthPx, density).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(120, 110, 120, 134)
            textSize = 8.5f * density * scale * (96f / density)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        val header = decorations.header?.takeIf { it.isNotBlank() }
        val footer = decorations.footer?.takeIf { it.isNotBlank() }
        canvas.save()
        canvas.scale(scale, scale)
        val marginTop = geometry.marginTopPx.toFloat()
        val marginBottom = geometry.marginBottomPx.toFloat()
        val contentWidth = geometry.contentWidthPx.toFloat()
        if (header != null) {
            canvas.drawText(header, 0f, marginTop - 10f, paint)
            val line = Paint(paint).apply { color = android.graphics.Color.argb(60, 110, 120, 134); strokeWidth = 1f }
            canvas.drawLine(0f, marginTop - 4f, contentWidth, marginTop - 4f, line)
        }
        val footerY = geometry.heightPx - marginBottom + 18f
        if (footer != null) canvas.drawText(footer, 0f, footerY, paint)
        if (decorations.pageNumbers) {
            val label = "${pageIndex + 1} / $pageCount"
            val width = paint.measureText(label)
            canvas.drawText(label, contentWidth - width, footerY, paint)
        }
        canvas.restore()
    }

    /** Reports blocks that land badly on a page boundary. */
    private fun layoutIssues(starts: List<Int>, blocks: List<BlockBounds>, pageHeight: Int): List<String> {
        val issues = mutableListOf<String>()
        fun pageOf(offset: Int): Int {
            var page = 0
            for (index in starts.indices) {
                if (starts[index] <= offset) page = index else break
            }
            return page
        }
        blocks.forEach { block ->
            val startPage = pageOf(block.top)
            val endPage = pageOf(block.bottom)
            if (endPage > startPage) {
                issues += when (block.label) {
                    "table" -> "A table is split across pages ${startPage + 1} and ${endPage + 1}. Split it into two tables or shorten the rows."
                    "heading" -> "A heading is split across pages ${startPage + 1} and ${endPage + 1}. Shorten the text above it or add a page break."
                    "figure" -> "An image is split across pages ${startPage + 1} and ${endPage + 1}. Move it so it fits on one page."
                    else -> "A ${block.label} is split across pages ${startPage + 1} and ${endPage + 1}."
                }
            }
            if (block.label == "heading") {
                val remaining = starts.getOrElse(startPage) { 0 } + pageHeight - block.top
                if (remaining < 90) {
                    issues += "A heading sits ${remaining}px from the bottom of page ${startPage + 1}. Give it more room so it is not stranded."
                }
            }
        }
        return issues.distinct().take(6)
    }

    private data class BlockBounds(val label: String, val top: Int, val bottom: Int)

    private fun parseBlocks(measurement: String): List<BlockBounds> {
        val marker = "\"blocks\":["
        val start = measurement.indexOf(marker)
        if (start < 0) return emptyList()
        val end = measurement.indexOf("]", start)
        if (end < 0) return emptyList()
        return Regex("\\{\"label\":\"([a-z]+)\",\"top\":(-?\\d+),\"bottom\":(-?\\d+)}")
            .findAll(measurement.substring(start, end))
            .map { match ->
                BlockBounds(match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3].toInt())
            }
            .toList()
    }

    /** Shows only section [index] and returns its height in CSS px. */
    private suspend fun sectionHeight(webView: WebView, index: Int, fallback: Int): Int {
        val value = evaluate(
            webView,
            """(() => {
              const secs = document.querySelectorAll('.kino-page');
              secs.forEach((el, j) => { el.style.display = (j === $index) ? '' : 'none'; });
              const sec = secs[$index];
              const h = sec ? Math.max(sec.offsetHeight, sec.getBoundingClientRect().height) : $fallback;
              return String(Math.ceil(h));
            })()""",
        )
        return (value.toIntOrNull() ?: fallback).coerceIn(1, HARD_MAX_PAGES * fallback)
    }

    private fun dpToPx(cssPx: Int, density: Float): Int = (cssPx * density).roundToInt()

    private fun checkPdf(output: File) {
        require(output.isFile) { "PDF output is empty." }
        val maxBytes = limits.sanitized().maxPdfBytes
        require(output.length() in MIN_PDF_BYTES..maxBytes) {
            "PDF output is empty or exceeds the ${maxBytes / (1024 * 1024)} MB export limit."
        }
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
            .kino-page { width: ${PageGeometry.A4.widthPx}px; ${if (paged) "min-height: ${PageGeometry.A4.contentHeightPx}px; page-break-after: always; break-after: page;" else ""}
                          padding: 14mm 12mm; overflow-x: hidden; overflow-wrap: anywhere; word-wrap: break-word; }
            html, body { overflow-x: hidden; }
            .kino-page:last-child { page-break-after: auto; break-after: auto; }
            img, svg, video, canvas { max-width: 100% !important; height: auto; }
            .kino-page *, .kino-page *::before, .kino-page *::after { max-width: 100%; }
            p, li, h1, h2, h3, h4, td, th, span, div, a { overflow-wrap: anywhere; word-break: break-word; }
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
        const val HARD_MAX_PAGES = 100
        const val PAGE_RENDER_DELAY_MS = 160L
        private const val FIT_ATTEMPTS = 4
        private const val MIN_ZOOM = 0.5f
        private const val OVERFLOW_TOLERANCE_PX = 2f
        private const val OVERFLOW_SCRIPT = """(() => {
            try {
              const root = document.documentElement;
              const vw = root.clientWidth;
              let worst = root.scrollWidth;
              document.querySelectorAll('.kino-page').forEach(sec => {
                const r = sec.getBoundingClientRect();
                sec.querySelectorAll('*').forEach(el => {
                  const b = el.getBoundingClientRect();
                  if (b.width > 1 && b.right > r.right + 1) worst = Math.max(worst, root.scrollWidth + (b.right - r.right));
                });
              });
              return JSON.stringify({vw: Math.round(vw), sw: Math.round(worst)});
            } catch (e) { return JSON.stringify({vw: 794, sw: 794}); }
          })()"""
        private const val READY_SCRIPT = """(() => {
            try {
              if (window.__kinoPdfReady === false) return 'waiting';
              const imgs = Array.from(document.images);
              const imagesDone = imgs.every(i => i.complete);
              const domDone = document.readyState === 'complete' || document.readyState === 'interactive';
              if (domDone && imagesDone) return window.__kinoPdfReady === true ? 'ready' : 'settled';
              return 'pending';
            } catch (e) { return 'settled'; }
          })()"""
        private const val MEASURE_SCRIPT = """(() => {
            try {
              const root = document.documentElement;
              const body = document.body;
              const y = window.scrollY || 0;
              const breaks = Array.from(document.querySelectorAll('.kino-break')).map(el => Math.round(el.getBoundingClientRect().top + y));
              const label = el => {
                if (el.classList.contains('kino-table')) return 'table';
                if (el.classList.contains('kino-h')) return 'heading';
                if (el.classList.contains('kino-figure') || el.classList.contains('kino-chart-wrap')) return 'figure';
                return 'block';
              };
              const blocks = Array.from(document.querySelectorAll('.kino-table, .kino-h1, .kino-h2, .kino-h3, .kino-h4, .kino-figure, .kino-chart-wrap')).map(el => {
                const r = el.getBoundingClientRect();
                return '{"label":"' + label(el) + '","top":' + Math.round(r.top + y) + ',"bottom":' + Math.round(r.bottom + y) + '}';
              });
              const height = Math.max(body.scrollHeight, root.scrollHeight);
              return JSON.stringify({height: height, breaks: breaks, blocks: blocks});
            } catch (e) { return JSON.stringify({height: 0, breaks: [], blocks: []}); }
          })()"""
    }
}
