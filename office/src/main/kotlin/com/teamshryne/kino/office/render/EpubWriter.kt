package com.teamshryne.kino.office.render

import com.teamshryne.kino.office.ReportIssue
import com.teamshryne.kino.office.model.Block
import com.teamshryne.kino.office.model.DocumentSpec
import com.teamshryne.kino.office.model.HeadingBlock
import com.teamshryne.kino.office.model.InlineMarkup
import com.teamshryne.kino.office.theme.OfficeTheme
import com.teamshryne.kino.office.zip.ZipWriter
import java.util.UUID

/**
 * EPUB 3 output.
 *
 * The document is split into chapters at top level headings so an e-reader
 * gets a usable table of contents instead of one very long file.
 */
object EpubWriter {

    fun write(
        document: DocumentSpec,
        theme: OfficeTheme,
        geometry: PageGeometry,
        issues: MutableList<ReportIssue> = mutableListOf(),
    ): ByteArray {
        val renderer = HtmlRenderer(theme, geometry, printMode = false)
        val chapters = split(document.blocks)
        val bookId = "urn:uuid:${UUID.randomUUID()}"
        val entries = mutableListOf<ZipWriter.Entry>()

        entries += ZipWriter.Entry("mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII), stored = true)
        entries += ZipWriter.Entry(
            "META-INF/container.xml",
            containerXml().toByteArray(Charsets.UTF_8),
        )

        val manifest = StringBuilder()
        val spine = StringBuilder()
        val navItems = StringBuilder()
        chapters.forEachIndexed { index, chapter ->
            val id = "chapter${index + 1}"
            val file = "$id.xhtml"
            manifest.append("<item id=\"$id\" href=\"$file\" media-type=\"application/xhtml+xml\"/>")
            spine.append("<itemref idref=\"$id\"/>")
            navItems.append("<li><a href=\"$file\">").append(escapeXml(chapter.title)).append("</a></li>")
            entries += ZipWriter.Entry("EPUB/$file", chapterXhtml(chapter, renderer).toByteArray(Charsets.UTF_8))
        }

        val contentOpf = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\">\n")
            append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
            append("<dc:identifier id=\"bookid\">").append(bookId).append("</dc:identifier>\n")
            append("<dc:title>").append(escapeXml(document.title)).append("</dc:title>\n")
            document.subtitle?.let { append("<dc:description>").append(escapeXml(it)).append("</dc:description>\n") }
            document.author?.let { append("<dc:creator>").append(escapeXml(it)).append("</dc:creator>\n") }
            append("<dc:language>en</dc:language>\n")
            append("<meta property=\"dcterms:modified\">2026-01-01T00:00:00Z</meta>\n")
            append("</metadata>\n")
            append("<manifest>\n")
            append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
            append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
            append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
            append(manifest)
            append("</manifest>\n")
            append("<spine toc=\"ncx\">\n").append(spine).append("</spine>\n")
            append("</package>\n")
        }
        entries += ZipWriter.Entry("EPUB/content.opf", contentOpf.toByteArray(Charsets.UTF_8))

        val nav = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n")
            append("<head><title>").append(escapeXml(document.title)).append("</title><link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head>\n")
            append("<body><nav epub:type=\"toc\" id=\"toc\"><h1>Contents</h1><ol>").append(navItems).append("</ol></nav></body>\n")
            append("</html>\n")
        }
        entries += ZipWriter.Entry("EPUB/nav.xhtml", nav.toByteArray(Charsets.UTF_8))

        val ncx = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n")
            append("<head><meta name=\"dtb:uid\" content=\"").append(bookId).append("\"/></head>\n")
            append("<docTitle><text>").append(escapeXml(document.title)).append("</text></docTitle>\n")
            append("<navMap>\n")
            chapters.forEachIndexed { index, _ ->
                append("<navPoint id=\"nav").append(index + 1).append("\" playOrder=\"").append(index + 1).append("\">")
                append("<navLabel><text>").append(escapeXml(chapters[index].title)).append("</text></navLabel>")
                append("<content src=\"chapter").append(index + 1).append(".xhtml\"/></navPoint>\n")
            }
            append("</navMap>\n</ncx>\n")
        }
        entries += ZipWriter.Entry("EPUB/toc.ncx", ncx.toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("EPUB/style.css", stylesheet(theme).toByteArray(Charsets.UTF_8))

        return ZipWriter.build(entries)
    }

    private class Chapter(val title: String, val blocks: List<Block>)

    private fun split(blocks: List<Block>): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val current = mutableListOf<Block>()
        var title = "Start"
        blocks.forEach { block ->
            if (block is HeadingBlock && block.level == 1 && current.isNotEmpty()) {
                chapters += Chapter(title, current.toList())
                current.clear()
            }
            if (block is HeadingBlock && block.level == 1) {
                title = InlineMarkup.plain(block.text)
            }
            current += block
        }
        if (current.isNotEmpty()) chapters += Chapter(title, current.toList())
        if (chapters.isEmpty()) chapters += Chapter("Document", emptyList())
        return chapters
    }

    private fun chapterXhtml(chapter: Chapter, renderer: HtmlRenderer): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\">\n<head>\n")
        append("<title>").append(escapeXml(chapter.title)).append("</title>\n")
        append("<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/>\n</head>\n<body>\n")
        append(renderer.renderBlocks(chapter.blocks))
        append("\n</body>\n</html>\n")
    }

    private fun containerXml(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
            "  <rootfiles>\n" +
            "    <rootfile full-path=\"EPUB/content.opf\" media-type=\"application/oebps-package+xml\"/>\n" +
            "  </rootfiles>\n" +
            "</container>\n"

    private fun stylesheet(theme: OfficeTheme): String = """
        body { font-family: ${theme.sansStack}; color: ${theme.ink}; line-height: 1.55; margin: 1.2em; }
        h1, h2, h3, h4 { font-family: ${theme.serifStack}; color: ${theme.ink}; line-height: 1.25; }
        h3 { color: ${theme.accent}; }
        h4 { font-family: ${theme.sansStack}; text-transform: uppercase; letter-spacing: 0.08em; color: ${theme.inkSoft}; }
        p { margin: 0 0 0.8em; }
        table { border-collapse: collapse; width: 100%; font-size: 0.92em; }
        th { background: ${theme.accent}; color: #FFFFFF; text-align: left; padding: 0.45em 0.6em; }
        td { border: 1px solid ${theme.border}; padding: 0.4em 0.6em; vertical-align: top; }
        blockquote { margin: 0.8em 0; padding-left: 0.9em; border-left: 3px solid ${theme.accent}; color: ${theme.inkSoft}; font-style: italic; }
        pre { background: ${theme.surfaceAlt}; border: 1px solid ${theme.border}; padding: 0.7em; font-size: 0.85em; white-space: pre-wrap; }
        figure { margin: 1em 0; text-align: center; }
        figcaption { font-size: 0.85em; color: ${theme.muted}; font-style: italic; }
        .kino-callout { border-left: 4px solid ${theme.accent}; background: ${theme.accentSoft}; padding: 0.7em 0.9em; margin: 0.9em 0; }
        .kino-kv-item { border: 1px solid ${theme.border}; border-left: 3px solid ${theme.accent}; padding: 0.6em 0.8em; margin: 0.4em 0; }
        .kino-stat { border: 1px solid ${theme.border}; border-top: 3px solid ${theme.accent}; padding: 0.7em 0.9em; margin: 0.4em 0; }
        .kino-chart { width: 100%; height: auto; }
    """.trimIndent()

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
