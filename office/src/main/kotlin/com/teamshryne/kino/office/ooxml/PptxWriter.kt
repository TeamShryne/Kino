package com.teamshryne.kino.office.ooxml

import com.teamshryne.kino.office.ReportIssue
import com.teamshryne.kino.office.image.Images
import com.teamshryne.kino.office.model.Block
import com.teamshryne.kino.office.model.CalloutBlock
import com.teamshryne.kino.office.model.CalloutKind
import com.teamshryne.kino.office.model.ChartBlock
import com.teamshryne.kino.office.model.ChartKind
import com.teamshryne.kino.office.model.CodeBlock
import com.teamshryne.kino.office.model.ColumnAlignment
import com.teamshryne.kino.office.model.DeckSpec
import com.teamshryne.kino.office.model.DividerBlock
import com.teamshryne.kino.office.model.HeadingBlock
import com.teamshryne.kino.office.model.ImageBlock
import com.teamshryne.kino.office.model.InlineMarkup
import com.teamshryne.kino.office.model.KeyValueBlock
import com.teamshryne.kino.office.model.ListBlock
import com.teamshryne.kino.office.model.PageBreakBlock
import com.teamshryne.kino.office.model.ParagraphBlock
import com.teamshryne.kino.office.model.QuoteBlock
import com.teamshryne.kino.office.model.SlideLayout
import com.teamshryne.kino.office.model.SlideSpec
import com.teamshryne.kino.office.model.SpacerBlock
import com.teamshryne.kino.office.model.StatBlock
import com.teamshryne.kino.office.model.TableBlock
import com.teamshryne.kino.office.model.TocBlock
import com.teamshryne.kino.office.render.Colors
import com.teamshryne.kino.office.theme.OfficeTheme
import com.teamshryne.kino.office.xml.Xml
import com.teamshryne.kino.office.zip.ZipWriter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Writes a deck as a PowerPoint package.
 *
 * The file is produced by hand: a ZIP container holding PresentationML parts.
 * Every slide is drawn with explicit shapes instead of placeholders, because a
 * placeholder that is not declared in the slide layout renders as an empty box
 * and makes PowerPoint offer to "repair" the file.
 */
object PptxWriter {

    fun write(
        deck: DeckSpec,
        theme: OfficeTheme,
        issues: MutableList<ReportIssue> = mutableListOf(),
    ): ByteArray {
        val writer = PptxPackage(deck, theme, issues)
        return ZipWriter.build(writer.build())
    }
}

/* ------------------------------- constants ------------------------------- */

private const val SLIDE_WIDTH = 12192000
private const val SLIDE_HEIGHT = 6858000
private const val MARGIN = 685800
private const val CONTENT_WIDTH = SLIDE_WIDTH - 2 * MARGIN
private const val FOOTER_TOP = SLIDE_HEIGHT - 520000

private const val SIZE_TITLE = 4400
private const val SIZE_SECTION = 3600
private const val SIZE_SLIDE_TITLE = 3000
private const val SIZE_QUOTE = 2600
private const val SIZE_HEADING1 = 2200
private const val SIZE_HEADING2 = 1900
private const val SIZE_BODY = 1600
private const val SIZE_SMALL = 1200
private const val SIZE_TINY = 1000
private const val SIZE_STAT_VALUE = 2800

private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

private const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val NS_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
private const val NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"

/* -------------------------------- package -------------------------------- */

private class PptxPackage(
    private val deck: DeckSpec,
    private val theme: OfficeTheme,
    private val issues: MutableList<ReportIssue>,
) {

    private val media = mutableListOf<Pair<String, ByteArray>>()
    private var mediaCounter = 0

    fun addMedia(bytes: ByteArray, extension: String): String {
        mediaCounter += 1
        val name = "image$mediaCounter.$extension"
        media += "ppt/media/$name" to bytes
        return "../media/$name"
    }

    fun build(): List<ZipWriter.Entry> {
        val entries = mutableListOf<ZipWriter.Entry>()
        val slides = deck.slides
        val hasNotes = slides.any { !it.notes.isNullOrBlank() }
        slides.forEachIndexed { index, slide ->
            val canvas = SlideCanvas(this)
            val painter = SlidePainter(theme, canvas, issues)
            painter.paintSlide(slide, index, deck)

            val number = index + 1
            entries += ZipWriter.Entry(
                "ppt/slides/slide$number.xml",
                slideXml(canvas).toByteArray(Charsets.UTF_8),
            )

            val relationships = mutableListOf<String>()
            relationships += relationship(
                "rId1",
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout",
                "../slideLayouts/slideLayout1.xml",
            )
            var nextRel = 2
            if (!slide.notes.isNullOrBlank()) {
                relationships += relationship(
                    "rId$nextRel",
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide",
                    "../notesSlides/notesSlide$number.xml",
                )
            }
            canvas.imageRelationships.forEach { (id, target) ->
                relationships += relationship(
                    id,
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image",
                    target,
                )
            }
            entries += ZipWriter.Entry(
                "ppt/slides/_rels/slide$number.xml.rels",
                relationshipsXml(relationships).toByteArray(Charsets.UTF_8),
            )

            if (!slide.notes.isNullOrBlank()) {
                entries += ZipWriter.Entry(
                    "ppt/notesSlides/notesSlide$number.xml",
                    notesSlideXml(slide.notes.orEmpty()).toByteArray(Charsets.UTF_8),
                )
                entries += ZipWriter.Entry(
                    "ppt/notesSlides/_rels/notesSlide$number.xml.rels",
                    relationshipsXml(
                        listOf(
                            relationship(
                                "rId1",
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster",
                                "../notesMasters/notesMaster1.xml",
                            ),
                            relationship(
                                "rId2",
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide",
                                "../slides/slide$number.xml",
                            ),
                        ),
                    ).toByteArray(Charsets.UTF_8),
                )
            }
        }

        val presentationRelationships = mutableListOf<String>()
        presentationRelationships += relationship(
            "rId1",
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster",
            "slideMasters/slideMaster1.xml",
        )
        slides.forEachIndexed { index, _ ->
            presentationRelationships += relationship(
                "rId${index + 2}",
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide",
                "slides/slide${index + 1}.xml",
            )
        }
        val themeRel = "rId${slides.size + 2}"
        presentationRelationships += relationship(
            themeRel,
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme",
            "theme/theme1.xml",
        )
        val notesRel: String? = if (hasNotes) {
            val id = "rId${slides.size + 3}"
            presentationRelationships += relationship(
                id,
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster",
                "notesMasters/notesMaster1.xml",
            )
            id
        } else {
            null
        }

        /* --- the parts, in the order a reader expects them --- */

        entries += ZipWriter.Entry("[Content_Types].xml", contentTypes(slides).toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry(
            "_rels/.rels",
            relationshipsXml(
                listOf(
                    relationship(
                        "rId1",
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument",
                        "ppt/presentation.xml",
                    ),
                    relationship(
                        "rId2",
                        "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties",
                        "docProps/core.xml",
                    ),
                    relationship(
                        "rId3",
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties",
                        "docProps/app.xml",
                    ),
                ),
            ).toByteArray(Charsets.UTF_8),
        )
        entries += ZipWriter.Entry("docProps/core.xml", coreProperties().toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("docProps/app.xml", appProperties().toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("ppt/presentation.xml", presentationXml(slides.size, notesRel).toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("ppt/_rels/presentation.xml.rels", relationshipsXml(presentationRelationships).toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("ppt/theme/theme1.xml", themeXml().toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry("ppt/slideMasters/slideMaster1.xml", slideMasterXml().toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry(
            "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            relationshipsXml(
                listOf(
                    relationship(
                        "rId1",
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout",
                        "../slideLayouts/slideLayout1.xml",
                    ),
                    relationship(
                        "rId2",
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme",
                        "../theme/theme1.xml",
                    ),
                ),
            ).toByteArray(Charsets.UTF_8),
        )
        entries += ZipWriter.Entry("ppt/slideLayouts/slideLayout1.xml", slideLayoutXml().toByteArray(Charsets.UTF_8))
        entries += ZipWriter.Entry(
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
            relationshipsXml(
                listOf(
                    relationship(
                        "rId1",
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster",
                        "../slideMasters/slideMaster1.xml",
                    ),
                ),
            ).toByteArray(Charsets.UTF_8),
        )
        if (hasNotes) {
            entries += ZipWriter.Entry("ppt/notesMasters/notesMaster1.xml", notesMasterXml().toByteArray(Charsets.UTF_8))
            entries += ZipWriter.Entry(
                "ppt/notesMasters/_rels/notesMaster1.xml.rels",
                relationshipsXml(
                    listOf(
                        relationship(
                            "rId1",
                            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme",
                            "../theme/theme1.xml",
                        ),
                    ),
                ).toByteArray(Charsets.UTF_8),
            )
        }

        /* --- slides come last so the package reads top down --- */
        val slideEntries = entries.filter { it.name.startsWith("ppt/slides/") }
        val remaining = entries.filterNot { it.name.startsWith("ppt/slides/") }
        return remaining + slideEntries
    }

    private fun relationship(id: String, type: String, target: String): String =
        "<Relationship Id=\"$id\" Type=\"$type\" Target=\"${Xml.attribute(target)}\"/>"

    private fun relationshipsXml(items: List<String>): String = buildString {
        append(XML_DECL)
        append("<Relationships xmlns=\"$NS_REL\">")
        items.forEach { append(it) }
        append("</Relationships>")
    }

    private fun contentTypes(slides: List<SlideSpec>): String = buildString {
        val hasNotes = slides.any { !it.notes.isNullOrBlank() }
        append(XML_DECL)
        append("<Types xmlns=\"$NS_CT\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
        append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
        append("<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>")
        append("<Default Extension=\"gif\" ContentType=\"image/gif\"/>")
        append("<Default Extension=\"bmp\" ContentType=\"image/bmp\"/>")
        append("<Default Extension=\"webp\" ContentType=\"image/webp\"/>")
        append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
        append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
        append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
        append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
        if (hasNotes) {
            append("<Override PartName=\"/ppt/notesMasters/notesMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml\"/>")
        }
        for (index in 1..slides.size) {
            append("<Override PartName=\"/ppt/slides/slide$index.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
            if (!slides[index - 1].notes.isNullOrBlank()) {
                append("<Override PartName=\"/ppt/notesSlides/notesSlide$index.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml\"/>")
            }
        }
        append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        append("</Types>")
    }

    private fun coreProperties(): String {
        val title = Xml.escape(deck.title)
        val creator = Xml.escape(deck.author.orEmpty().ifBlank { "Kino" })
        return buildString {
            append(XML_DECL)
            append("<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" ")
            append("xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">")
            append("<dc:title>$title</dc:title>")
            append("<dc:creator>$creator</dc:creator>")
            append("<cp:lastModifiedBy>$creator</cp:lastModifiedBy>")
            append("<dcterms:created xsi:type=\"dcterms:W3CDTF\">2024-01-01T00:00:00Z</dcterms:created>")
            append("<dcterms:modified xsi:type=\"dcterms:W3CDTF\">2024-01-01T00:00:00Z</dcterms:modified>")
            append("</cp:coreProperties>")
        }
    }

    private fun appProperties(): String = buildString {
        val slideCount = deck.slides.size
        val words = deck.slides.sumOf { slide ->
            (slide.title?.length ?: 0) + slide.blocks.size * 12
        }
        append(XML_DECL)
        append("<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" ")
        append("xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">")
        append("<Company></Company>")
        append("<Words>$words</Words>")
        append("<PresentationFormat>Widescreen</PresentationFormat>")
        append("<Paragraphs>${deck.slides.sumOf { it.blocks.size }}</Paragraphs>")
        append("<Slides>$slideCount</Slides>")
        append("<TitlesOfParts><vt:vector size=\"1\" baseType=\"lpstr\"><vt:lpstr>${Xml.escape(deck.title)}</vt:lpstr></vt:vector></TitlesOfParts>")
        append("<Application>Kino</Application>")
        append("<AppVersion>16.0000</AppVersion>")
        append("</Properties>")
    }

    private fun presentationXml(slideCount: Int, notesRel: String?): String = buildString {
        append(XML_DECL)
        append("<p:presentation xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\" saveSubsetFonts=\"1\">")
        append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>")
        if (notesRel != null) {
            append("<p:notesMasterIdLst><p:notesMasterId r:id=\"$notesRel\"/></p:notesMasterIdLst>")
        }
        append("<p:sldIdLst>")
        for (index in 0 until slideCount) {
            append("<p:sldId id=\"${256 + index}\" r:id=\"rId${index + 2}\"/>")
        }
        append("</p:sldIdLst>")
        append("<p:sldSz cx=\"$SLIDE_WIDTH\" cy=\"$SLIDE_HEIGHT\"/>")
        append("<p:notesSz cx=\"6858000\" cy=\"9144000\"/>")
        append("<p:defaultTextStyle>")
        append("<a:defPPr><a:defRPr lang=\"en-US\"/></a:defPPr>")
        append("<a:lvl1pPr marL=\"0\" algn=\"l\" defTabSz=\"914400\" rtl=\"0\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\">")
        append("<a:defRPr sz=\"1600\" kern=\"1200\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill>")
        append("<a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/><a:cs typeface=\"+mn-cs\"/></a:defRPr></a:lvl1pPr>")
        append("</p:defaultTextStyle>")
        append("</p:presentation>")
    }

    private fun themeXml(): String {
        val accent = theme.accent
        val accents = listOf(
            accent,
            Colors.mix(accent, "#4E7CD9", 0.45),
            Colors.lighten(accent, 0.42),
            Colors.darken(accent, 0.25),
            Colors.mix(accent, "#E17055", 0.55),
            Colors.lighten(Colors.darken(accent, 0.1), 0.35),
        )
        return buildString {
            append(XML_DECL)
            append("<a:theme xmlns:a=\"$NS_A\" name=\"${Xml.attribute(theme.label)}\">")
            append("<a:themeElements>")
            append("<a:clrScheme name=\"${Xml.attribute(theme.label)}\">")
            append("<a:dk1><a:srgbClr val=\"${color(theme.ink)}\"/></a:dk1>")
            append("<a:lt1><a:srgbClr val=\"${color(theme.background)}\"/></a:lt1>")
            append("<a:dk2><a:srgbClr val=\"${color(theme.inkSoft)}\"/></a:dk2>")
            append("<a:lt2><a:srgbClr val=\"${color(theme.surface)}\"/></a:lt2>")
            accents.forEachIndexed { index, value ->
                append("<a:accent${index + 1}><a:srgbClr val=\"${color(value)}\"/></a:accent${index + 1}>")
            }
            append("<a:hlink><a:srgbClr val=\"${color(theme.accent)}\"/></a:hlink>")
            append("<a:folHlink><a:srgbClr val=\"${color(theme.muted)}\"/></a:folHlink>")
            append("</a:clrScheme>")
            append("<a:fontScheme name=\"${Xml.attribute(theme.label)}\">")
            append("<a:majorFont><a:latin typeface=\"${Xml.attribute(theme.serifFont)}\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>")
            append("<a:minorFont><a:latin typeface=\"${Xml.attribute(theme.sansFont)}\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>")
            append("</a:fontScheme>")
            append("<a:fmtScheme name=\"${Xml.attribute(theme.label)}\">")
            append("<a:fillStyleLst>")
            repeat(3) { append("<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>") }
            append("</a:fillStyleLst>")
            append("<a:lnStyleLst>")
            repeat(3) { index ->
                append("<a:ln w=\"${6350 * (index + 1)}\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\">")
                append("<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>")
                append("<a:prstDash val=\"solid\"/><a:miter lim=\"800000\"/></a:ln>")
            }
            append("</a:lnStyleLst>")
            append("<a:effectStyleLst>")
            repeat(3) { append("<a:effectStyle><a:effectLst/></a:effectStyle>") }
            append("</a:effectStyleLst>")
            append("<a:bgFillStyleLst>")
            repeat(3) { append("<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>") }
            append("</a:bgFillStyleLst>")
            append("</a:fmtScheme>")
            append("</a:themeElements>")
            append("<a:objectDefaults/>")
            append("<a:extraClrSchemeLst/>")
            append("</a:theme>")
        }
    }

    private fun slideMasterXml(): String = buildString {
        append(XML_DECL)
        append("<p:sldMaster xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\">")
        append("<p:cSld>")
        append("<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"${color(theme.background)}\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>")
        append(spTreeBoilerplate())
        append("</p:cSld>")
        append("<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" ")
        append("accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>")
        append("<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>")
        append("<p:txStyles>")
        append("<p:titleStyle><a:lvl1pPr algn=\"l\"><a:defRPr sz=\"3000\" b=\"1\"><a:latin typeface=\"+mj-lt\"/></a:defRPr></a:lvl1pPr></p:titleStyle>")
        append("<p:bodyStyle><a:lvl1pPr marL=\"0\" indent=\"0\"><a:defRPr sz=\"1600\"><a:latin typeface=\"+mn-lt\"/></a:defRPr></a:lvl1pPr></p:bodyStyle>")
        append("<p:otherStyle><a:lvl1pPr marL=\"0\" indent=\"0\"><a:defRPr sz=\"1600\"><a:latin typeface=\"+mn-lt\"/></a:defRPr></a:lvl1pPr></p:otherStyle>")
        append("</p:txStyles>")
        append("</p:sldMaster>")
    }

    private fun slideLayoutXml(): String = buildString {
        append(XML_DECL)
        append("<p:sldLayout xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\" type=\"blank\" preserve=\"1\">")
        append("<p:cSld name=\"Kino\">")
        append(spTreeBoilerplate())
        append("</p:cSld>")
        append("<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>")
        append("</p:sldLayout>")
    }

    private fun notesMasterXml(): String = buildString {
        append(XML_DECL)
        append("<p:notesMaster xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\">")
        append("<p:cSld>")
        append("<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"${color(theme.background)}\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>")
        append("<p:spTree>")
        append("<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>")
        append("<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>")
        append("<p:sp>")
        append("<p:nvSpPr><p:cNvPr id=\"2\" name=\"Notes Placeholder\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>")
        append("<p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr>")
        append("<p:spPr><a:xfrm><a:off x=\"685800\" y=\"4343400\"/><a:ext cx=\"5486400\" cy=\"4114800\"/></a:xfrm>")
        append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>")
        append("<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></p:txBody>")
        append("</p:sp>")
        append("</p:spTree>")
        append("</p:cSld>")
        append("<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" ")
        append("accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>")
        append("<p:notesStyle><a:lvl1pPr marL=\"0\" algn=\"l\"><a:defRPr sz=\"1200\"><a:latin typeface=\"+mn-lt\"/></a:defRPr></a:lvl1pPr></p:notesStyle>")
        append("</p:notesMaster>")
    }

    private fun notesSlideXml(notes: String): String = buildString {
        append(XML_DECL)
        append("<p:notes xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\">")
        append("<p:cSld>")
        append("<p:spTree>")
        append("<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>")
        append("<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>")
        append("<p:sp>")
        append("<p:nvSpPr><p:cNvPr id=\"2\" name=\"Notes Placeholder 1\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>")
        append("<p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr>")
        append("<p:spPr/>")
        append("<p:txBody><a:bodyPr/><a:lstStyle/>")
        notes.split("\n").filter { it.isNotBlank() }.forEach { line ->
            append("<a:p><a:r><a:rPr lang=\"en-US\" sz=\"1200\" dirty=\"0\"/><a:t>${Xml.escape(line)}</a:t></a:r></a:p>")
        }
        append("</p:txBody>")
        append("</p:sp>")
        append("</p:spTree>")
        append("</p:cSld>")
        append("<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>")
        append("</p:notes>")
    }

    private fun slideXml(canvas: SlideCanvas): String = buildString {
        append(XML_DECL)
        append("<p:sld xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\">")
        append("<p:cSld>")
        append("<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"${color(theme.background)}\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>")
        append("<p:spTree>")
        append("<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>")
        append("<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>")
        append(canvas.shapes)
        append("</p:spTree>")
        append("</p:cSld>")
        append("<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>")
        append("</p:sld>")
    }

    private fun spTreeBoilerplate(): String = buildString {
        append("<p:spTree>")
        append("<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>")
        append("<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>")
        append("</p:spTree>")
    }
}

/* --------------------------------- canvas -------------------------------- */

private class SlideCanvas(
    private val pkg: PptxPackage,
) {
    val shapes = StringBuilder()
    private var shapeId = 2
    private var relId = 3

    val imageRelationships = mutableListOf<Pair<String, String>>()

    fun nextShapeId(): Int = shapeId++

    fun addImage(bytes: ByteArray, extension: String): String {
        val id = "rId${relId++}"
        imageRelationships += id to pkg.addMedia(bytes, extension)
        return id
    }

    fun add(xml: String) {
        shapes.append(xml)
    }
}

/* -------------------------------- painter -------------------------------- */

/**
 * Draws one slide. Everything is an explicit shape with absolute coordinates so
 * the result looks the same in PowerPoint, Keynote and LibreOffice.
 */
private class SlidePainter(
    private val theme: OfficeTheme,
    private val canvas: SlideCanvas,
    private val issues: MutableList<ReportIssue>,
) {

    /* ----------------------------- slide kinds ---------------------------- */

    fun paintSlide(slide: SlideSpec, index: Int, deck: DeckSpec) {
        when (slide.layout) {
            SlideLayout.TITLE -> paintTitle(slide)
            SlideLayout.SECTION -> paintSection(slide)
            SlideLayout.QUOTE -> paintQuote(slide)
            SlideLayout.METRICS -> paintMetrics(slide)
            SlideLayout.TWO_COLUMN -> paintTwoColumn(slide)
            SlideLayout.CONTENT -> paintContent(slide)
        }
        paintFooter(index + 1, deck.title)
    }

    private fun paintTitle(slide: SlideSpec) {
        val title = slide.title.orEmpty().ifBlank { "Presentation" }
        val subtitle = slide.subtitle.orEmpty()
        val boxWidth = SLIDE_WIDTH - 2 * 1143000
        val titleHeight = measure(title, SIZE_TITLE / 100.0, boxWidth)
        val subtitleHeight = if (subtitle.isBlank()) 0 else measure(subtitle, 20.0, boxWidth)
        val ruleGap = 220000
        val ruleHeight = 40000
        val gap = if (subtitle.isBlank()) 0 else 200000
        val total = titleHeight + ruleGap + ruleHeight + gap + subtitleHeight
        var y = (SLIDE_HEIGHT - total) / 2

        textBox(
            x = 1143000,
            y = y,
            w = boxWidth,
            h = titleHeight,
            paragraphs = paragraph(runs(title, SIZE_TITLE, theme.accent, theme.serifFont, bold = true), align = "ctr"),
        )
        y += titleHeight + ruleGap
        shape(x = SLIDE_WIDTH / 2 - 571500, y = y, w = 1143000, h = ruleHeight, fill = theme.accent)
        y += ruleHeight + gap
        if (subtitle.isNotBlank()) {
            textBox(
                x = 1143000,
                y = y,
                w = boxWidth,
                h = subtitleHeight,
                paragraphs = paragraph(runs(subtitle, 2000, theme.inkSoft, theme.sansFont), align = "ctr"),
            )
        }
    }

    private fun paintSection(slide: SlideSpec) {
        val title = slide.title.orEmpty().ifBlank { "Section" }
        val subtitle = slide.subtitle.orEmpty()
        val bandHeight = 1300000
        var y = (SLIDE_HEIGHT - bandHeight) / 2 - 260000

        textBox(
            x = MARGIN,
            y = y - 320000,
            w = CONTENT_WIDTH,
            h = 240000,
            paragraphs = paragraph(run("SECTION", SIZE_SMALL, theme.muted, theme.sansFont, bold = true, spacing = 300)),
        )
        shape(x = MARGIN, y = y, w = CONTENT_WIDTH, h = bandHeight, fill = theme.accent)
        val bandText = colorsOn(theme.accent)
        textBox(
            x = MARGIN + 200000,
            y = y,
            w = CONTENT_WIDTH - 400000,
            h = bandHeight,
            paragraphs = paragraph(runs(title, SIZE_SECTION, bandText, theme.serifFont, bold = true), align = "ctr"),
            anchor = "ctr",
        )
        if (subtitle.isNotBlank()) {
            textBox(
                x = MARGIN,
                y = y + bandHeight + 240000,
                w = CONTENT_WIDTH,
                h = measure(subtitle, 18.0, CONTENT_WIDTH),
                paragraphs = paragraph(runs(subtitle, 1800, theme.inkSoft, theme.sansFont), align = "ctr"),
            )
        }
    }

    private fun paintQuote(slide: SlideSpec) {
        val title = slide.title.orEmpty()
        val quote = slide.subtitle.orEmpty().ifBlank { slide.blocks.filterIsInstance<QuoteBlock>().firstOrNull()?.text.orEmpty() }
        val attribution = slide.blocks.filterIsInstance<QuoteBlock>().firstOrNull()?.attribution
        var y = MARGIN
        if (title.isNotBlank()) {
            val height = measure(title, SIZE_SLIDE_TITLE / 100.0, CONTENT_WIDTH)
            textBox(
                x = MARGIN,
                y = y,
                w = CONTENT_WIDTH,
                h = height,
                paragraphs = paragraph(runs(title, SIZE_SLIDE_TITLE, theme.accent, theme.serifFont, bold = true)),
            )
            y += height + 300000
        }
        val textX = MARGIN + 320000
        val textWidth = CONTENT_WIDTH - 320000
        val quoteHeight = measure(quote, SIZE_QUOTE / 100.0, textWidth)
        val attributionHeight = if (attribution.isNullOrBlank()) 0 else measure(attribution, 14.0, textWidth)
        val total = quoteHeight + (if (attributionHeight > 0) 200000 + attributionHeight else 0)
        val start = max(y, (SLIDE_HEIGHT - total) / 2)

        shape(x = MARGIN, y = start, w = 50800, h = total, fill = theme.accent)
        textBox(
            x = textX,
            y = start,
            w = textWidth,
            h = quoteHeight,
            paragraphs = paragraph(runs(quote, SIZE_QUOTE, theme.ink, theme.serifFont, italic = true)),
        )
        if (attributionHeight > 0) {
            textBox(
                x = textX,
                y = start + quoteHeight + 200000,
                w = textWidth,
                h = attributionHeight,
                paragraphs = paragraph(runs("— $attribution", 1400, theme.muted, theme.sansFont)),
            )
        }
    }

    private fun paintMetrics(slide: SlideSpec) {
        val stats = slide.blocks.filterIsInstance<StatBlock>().firstOrNull()
        if (stats == null || stats.items.isEmpty()) {
            paintContent(slide)
            return
        }
        var y = MARGIN
        val title = slide.title.orEmpty()
        if (title.isNotBlank()) {
            val height = measure(title, SIZE_SLIDE_TITLE / 100.0, CONTENT_WIDTH)
            textBox(
                x = MARGIN,
                y = y,
                w = CONTENT_WIDTH,
                h = height,
                paragraphs = paragraph(runs(title, SIZE_SLIDE_TITLE, theme.accent, theme.serifFont, bold = true)),
            )
            y += height + 120000
            shape(x = MARGIN, y = y, w = 900000, h = 40000, fill = theme.accent)
            y += 40000 + 320000
        }
        val subtitle = slide.subtitle.orEmpty()
        if (subtitle.isNotBlank()) {
            val height = measure(subtitle, 16.0, CONTENT_WIDTH)
            textBox(x = MARGIN, y = y, w = CONTENT_WIDTH, h = height, paragraphs = paragraph(runs(subtitle, SIZE_BODY, theme.inkSoft, theme.sansFont)))
            y += height + 260000
        }

        val items = stats.items.take(4)
        val gap = 228600
        val tileWidth = (CONTENT_WIDTH - gap * (items.size - 1)) / items.size
        val tileHeight = 1500000
        items.forEachIndexed { index, item ->
            val x = MARGIN + index * (tileWidth + gap)
            shape(
                x = x,
                y = y,
                w = tileWidth,
                h = tileHeight,
                geom = "roundRect",
                adj = 8000,
                fill = theme.surface,
                line = theme.border,
                name = "Stat tile",
            )
            val inner = 180000
            val paragraphs = buildString {
                append(paragraph(run(item.label.uppercase(), SIZE_SMALL, theme.muted, theme.sansFont, bold = true, spacing = 120), align = "l"))
                append(paragraph(runs(item.value, SIZE_STAT_VALUE, theme.accent, theme.serifFont, bold = true)))
                val delta = item.delta
                if (!delta.isNullOrBlank()) {
                    val deltaColor = when (item.positive) {
                        true -> theme.positive
                        false -> theme.negative
                        null -> theme.muted
                    }
                    append(paragraph(run(delta, SIZE_SMALL, deltaColor, theme.sansFont, bold = true)))
                }
            }
            textBox(
                x = x + inner,
                y = y + inner,
                w = tileWidth - inner * 2,
                h = tileHeight - inner * 2,
                paragraphs = paragraphs,
                anchor = "ctr",
            )
        }
        y += tileHeight + 260000

        val rest = slide.blocks.filterNot { it is StatBlock }
        if (rest.isNotEmpty()) {
            flow(rest, MARGIN, y, CONTENT_WIDTH)
        }
    }

    private fun paintTwoColumn(slide: SlideSpec) {
        var y = MARGIN
        val title = slide.title.orEmpty()
        if (title.isNotBlank()) {
            val height = measure(title, SIZE_SLIDE_TITLE / 100.0, CONTENT_WIDTH)
            textBox(
                x = MARGIN,
                y = y,
                w = CONTENT_WIDTH,
                h = height,
                paragraphs = paragraph(runs(title, SIZE_SLIDE_TITLE, theme.accent, theme.serifFont, bold = true)),
            )
            y += height + 120000
            shape(x = MARGIN, y = y, w = 900000, h = 40000, fill = theme.accent)
            y += 40000 + 300000
        }
        val subtitle = slide.subtitle.orEmpty()
        if (subtitle.isNotBlank()) {
            val height = measure(subtitle, 16.0, CONTENT_WIDTH)
            textBox(x = MARGIN, y = y, w = CONTENT_WIDTH, h = height, paragraphs = paragraph(runs(subtitle, SIZE_BODY, theme.inkSoft, theme.sansFont)))
            y += height + 260000
        }

        val columnGap = 457200
        val columnWidth = (CONTENT_WIDTH - columnGap) / 2
        val columns: List<List<Block>> = when {
            slide.columns.size >= 2 -> slide.columns.take(2)
            slide.columns.size == 1 -> listOf(slide.columns.first(), emptyList())
            else -> {
                val half = slide.blocks.size / 2
                listOf(slide.blocks.take(half), slide.blocks.drop(half))
            }
        }
        shape(x = MARGIN + columnWidth + columnGap / 2 - 6350, y = y, w = 12700, h = 3900000, fill = theme.border)
        columns.forEachIndexed { index, blocks ->
            val x = MARGIN + index * (columnWidth + columnGap)
            flow(blocks, x, y, columnWidth)
        }
    }

    private fun paintContent(slide: SlideSpec) {
        var y = MARGIN
        val title = slide.title.orEmpty()
        if (title.isNotBlank()) {
            val height = measure(title, SIZE_SLIDE_TITLE / 100.0, CONTENT_WIDTH)
            textBox(
                x = MARGIN,
                y = y,
                w = CONTENT_WIDTH,
                h = height,
                paragraphs = paragraph(runs(title, SIZE_SLIDE_TITLE, theme.accent, theme.serifFont, bold = true)),
            )
            y += height + 120000
            shape(x = MARGIN, y = y, w = 900000, h = 40000, fill = theme.accent)
            y += 40000 + 300000
        }
        val subtitle = slide.subtitle.orEmpty()
        if (subtitle.isNotBlank()) {
            val height = measure(subtitle, 16.0, CONTENT_WIDTH)
            textBox(x = MARGIN, y = y, w = CONTENT_WIDTH, h = height, paragraphs = paragraph(runs(subtitle, SIZE_BODY, theme.inkSoft, theme.sansFont)))
            y += height + 260000
        }
        flow(slide.blocks, MARGIN, y, CONTENT_WIDTH)
    }

    private fun paintFooter(number: Int, deckTitle: String) {
        shape(x = MARGIN, y = FOOTER_TOP - 70000, w = CONTENT_WIDTH, h = 9525, fill = theme.border, name = "Footer rule")
        textBox(
            x = MARGIN,
            y = FOOTER_TOP,
            w = CONTENT_WIDTH - 1200000,
            h = 220000,
            paragraphs = paragraph(run(deckTitle, SIZE_TINY, theme.muted, theme.sansFont)),
        )
        textBox(
            x = SLIDE_WIDTH - MARGIN - 1200000,
            y = FOOTER_TOP,
            w = 1200000,
            h = 220000,
            paragraphs = paragraph(run(number.toString(), SIZE_TINY, theme.muted, theme.sansFont), align = "r"),
        )
    }

    /* ------------------------------- blocks ------------------------------- */

    private fun flow(blocks: List<Block>, x: Int, y: Int, width: Int): Int {
        var cursor = y
        blocks.forEach { block ->
            cursor = paintBlock(block, x, cursor, width)
        }
        return cursor
    }

    private fun paintBlock(block: Block, x: Int, y: Int, width: Int): Int { return when (block) {
        is ParagraphBlock -> {
            val height = measure(block.text, SIZE_BODY / 100.0, width)
            textBox(x, y, width, height, paragraph(runs(block.text, SIZE_BODY, theme.ink, theme.sansFont)))
            y + height + 150000
        }

        is ListBlock -> {
            val paragraphs = block.items.joinToString("") { item ->
                paragraph(
                    runs(item, SIZE_BODY, theme.ink, theme.sansFont),
                    bullet = if (block.ordered) null else "-",
                    ordered = block.ordered,
                    bulletColor = theme.accent,
                    marL = 228600,
                    indent = -228600,
                    spaceAfter = 700,
                )
            }
            val height = block.items.sumOf { measure(it, SIZE_BODY / 100.0, width - 228600) } + block.items.size * 9000
            textBox(x, y, width, height, paragraphs)
            y + height + 170000
        }

        is HeadingBlock -> {
            val size = when {
                block.level <= 1 -> SIZE_HEADING1
                block.level == 2 -> SIZE_HEADING2
                else -> SIZE_BODY + 200
            }
            val text = if (block.numbered) "${block.level}. ${block.text}" else block.text
            val height = measure(text, size / 100.0, width)
            textBox(x, y, width, height, paragraph(runs(text, size, theme.ink, theme.serifFont, bold = true)))
            y + height + 130000
        }

        is TableBlock -> {
            val height = paintTable(block, x, y, width)
            y + height + 200000
        }

        is StatBlock -> {
            val items = block.items.take(4)
            if (items.isEmpty()) return y
            val gap = 228600
            val tileWidth = (width - gap * (items.size - 1)) / items.size
            val tileHeight = 1300000
            items.forEachIndexed { index, item ->
                val tileX = x + index * (tileWidth + gap)
                shape(tileX, y, tileWidth, tileHeight, geom = "roundRect", adj = 8000, fill = theme.surface, line = theme.border, name = "Stat tile")
                val inner = 160000
                val paragraphs = buildString {
                    append(paragraph(run(item.label.uppercase(), SIZE_SMALL, theme.muted, theme.sansFont, bold = true, spacing = 120)))
                    append(paragraph(runs(item.value, SIZE_STAT_VALUE - 600, theme.accent, theme.serifFont, bold = true)))
                    val delta = item.delta
                    if (!delta.isNullOrBlank()) {
                        val deltaColor = when (item.positive) {
                            true -> theme.positive
                            false -> theme.negative
                            null -> theme.muted
                        }
                        append(paragraph(run(delta, SIZE_SMALL, deltaColor, theme.sansFont, bold = true)))
                    }
                }
                textBox(tileX + inner, y + inner, tileWidth - inner * 2, tileHeight - inner * 2, paragraphs, anchor = "ctr")
            }
            y + tileHeight + 200000
        }

        is ChartBlock -> paintChart(block, x, y, width)

        is ImageBlock -> paintImage(block, x, y, width)

        is KeyValueBlock -> {
            val labelWidth = (width * 35) / 100
            var cursor = y
            block.items.forEach { item ->
                val labelHeight = measure(item.label, SIZE_BODY / 100.0, labelWidth - 120000)
                val valueHeight = measure(item.value, SIZE_BODY / 100.0, width - labelWidth - 120000)
                val rowHeight = max(labelHeight, valueHeight)
                textBox(x, cursor, labelWidth - 120000, rowHeight, paragraph(runs(item.label, SIZE_BODY, theme.inkSoft, theme.sansFont, bold = true)))
                textBox(x + labelWidth, cursor, width - labelWidth, rowHeight, paragraph(runs(item.value, SIZE_BODY, theme.ink, theme.sansFont)))
                cursor += rowHeight + 60000
                shape(x = x, y = cursor - 30000, w = width, h = 9525, fill = theme.border)
                cursor += 30000
                if (!item.hint.isNullOrBlank()) {
                    val hintHeight = measure(item.hint, SIZE_SMALL / 100.0, width)
                    textBox(x, cursor, width, hintHeight, paragraph(run(item.hint, SIZE_SMALL, theme.muted, theme.sansFont)))
                    cursor += hintHeight + 60000
                }
            }
            cursor + 120000
        }

        is QuoteBlock -> {
            val textWidth = width - 320000
            val height = measure(block.text, 2000.0, textWidth)
            val attributionHeight = if (block.attribution.isNullOrBlank()) 0 else measure(block.attribution, 13.0, textWidth)
            shape(x = x, y = y, w = 50800, h = height + attributionHeight, fill = theme.accent)
            textBox(x + 320000, y, textWidth, height, paragraph(runs(block.text, 2000, theme.ink, theme.serifFont, italic = true)))
            if (attributionHeight > 0) {
                textBox(
                    x + 320000,
                    y = y + height + 90000,
                    w = textWidth,
                    h = attributionHeight,
                    paragraphs = paragraph(runs("— ${block.attribution}", 1300, theme.muted, theme.sansFont)),
                )
            }
            y + height + attributionHeight + 180000
        }

        is CodeBlock -> {
            val inner = 200000
            val height = measure(block.text, 1300.0, width - inner * 2) + inner * 2
            shape(x, y, width, height, fill = theme.surfaceAlt, line = theme.border, name = "Code")
            val paragraphs = buildString {
                if (!block.language.isNullOrBlank()) {
                    append(paragraph(run(block.language.uppercase(), SIZE_TINY, theme.muted, theme.sansFont, bold = true, spacing = 100)))
                }
                append(paragraph(runs(block.text, 1300, theme.ink, theme.monoFont)))
            }
            textBox(x + inner, y + inner - 20000, width - inner * 2, height - inner * 2 + 40000, paragraphs)
            y + height + 180000
        }

        is CalloutBlock -> {
            val accent = calloutColor(block.kind)
            val inner = 220000
            val titleHeight = if (block.title.isNullOrBlank()) 0 else measure(block.title, SIZE_BODY / 100.0, width - inner * 2)
            val height = measure(block.text, SIZE_BODY / 100.0, width - inner * 2) + titleHeight + inner * 2
            shape(x, y, width, height, geom = "roundRect", adj = 6000, fill = Colors.lighten(accent, 0.88), line = Colors.lighten(accent, 0.55), name = "Callout")
            shape(x = x, y = y, w = 60000, h = height, fill = accent, name = "Callout accent")
            val paragraphs = buildString {
                if (titleHeight > 0) {
                    append(paragraph(runs(block.title.orEmpty(), SIZE_BODY, Colors.darken(accent, 0.25), theme.sansFont, bold = true)))
                }
                append(paragraph(runs(block.text, SIZE_BODY, theme.inkSoft, theme.sansFont)))
            }
            textBox(x + inner, y + inner - 20000, width - inner * 2, height - inner * 2 + 40000, paragraphs)
            y + height + 180000
        }

        is DividerBlock -> {
            shape(x, y + 80000, width, 9525, fill = theme.border, name = "Divider")
            y + 260000
        }

        is SpacerBlock -> y + block.size * 160000

        is PageBreakBlock -> y

        is TocBlock -> y
    }

    private fun paintTable(block: TableBlock, x: Int, y: Int, width: Int): Int {
        val columnCount = max(1, block.columns.size)
        val widths = columnWidths(block, width, columnCount)
        val headerHeight = max(
            330000,
            block.columns.mapIndexed { index, column ->
                measure(column.header, SIZE_SMALL / 100.0, widths.getOrElse(index) { widths.last() } - 200000)
            }.maxOrNull() ?: 330000,
        )
        val rowHeights = block.rows.map { row ->
            var height = 300000
            row.forEachIndexed { index, cell ->
                val cellWidth = widths.getOrElse(index) { widths.last() }
                height = max(height, measure(cell, 1100.0, cellWidth - 200000) + 120000)
            }
            height
        }
        val totalHeight = headerHeight + rowHeights.sum()

        val rows = buildString {
            append("<a:tr h=\"$headerHeight\">")
            block.columns.forEachIndexed { index, column ->
                append(cellXml(column.header, widths.getOrElse(index) { widths.last() }, headerHeight, true, "l", theme.background, SIZE_SMALL))
            }
            append("</a:tr>")
            block.rows.forEachIndexed { rowIndex, row ->
                val rowHeight = rowHeights.getOrElse(rowIndex) { 300000 }
                val background = if (block.zebra && rowIndex % 2 == 1) theme.surface else theme.background
                append("<a:tr h=\"$rowHeight\">")
                row.forEachIndexed { index, cell ->
                    val cellWidth = widths.getOrElse(index) { widths.last() }
                    val alignment = block.effectiveAlignments.getOrElse(index) { ColumnAlignment.LEFT }
                    val algn = when (alignment) {
                        ColumnAlignment.LEFT -> "l"
                        ColumnAlignment.CENTER -> "ctr"
                        ColumnAlignment.RIGHT -> "r"
                    }
                    append(cellXml(cell, cellWidth, rowHeight, false, algn, background, 1100))
                }
                append("</a:tr>")
            }
        }

        val grid = widths.joinToString("") { "<a:gridCol w=\"$it\"/>" }
        val frame = buildString {
            append("<p:graphicFrame>")
            append("<p:nvGraphicFramePr><p:cNvPr id=\"${canvas.nextShapeId()}\" name=\"Table\"/>")
            append("<p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>")
            append("<p:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$width\" cy=\"$totalHeight\"/></p:xfrm>")
            append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\">")
            append("<a:tbl><a:tblPr firstRow=\"1\" bandRow=\"1\"/><a:tblGrid>$grid</a:tblGrid>$rows</a:tbl>")
            append("</a:graphicData></a:graphic></p:graphicFrame>")
        }
        canvas.add(frame)
        return totalHeight
    }

    private fun cellXml(
        text: String,
        cellWidth: Int,
        rowHeight: Int,
        header: Boolean,
        align: String,
        background: String,
        size: Int,
    ): String = buildString {
        append("<a:tc>")
        append("<a:txBody><a:bodyPr/><a:lstStyle/>")
        append(
            paragraph(
                runs(text, size, if (header) "#FFFFFF" else theme.ink, theme.sansFont, bold = header),
                align = align,
            ),
        )
        append("</a:txBody>")
        append("<a:tcPr marL=\"91440\" marR=\"91440\" marT=\"45720\" marB=\"45720\" anchor=\"ctr\">")
        append("<a:lnL w=\"12700\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:srgbClr val=\"${color(theme.border)}\"/></a:solidFill><a:prstDash val=\"solid\"/></a:lnL>")
        append("<a:lnR w=\"12700\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:srgbClr val=\"${color(theme.border)}\"/></a:solidFill><a:prstDash val=\"solid\"/></a:lnR>")
        append("<a:lnT w=\"12700\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:srgbClr val=\"${color(theme.border)}\"/></a:solidFill><a:prstDash val=\"solid\"/></a:lnT>")
        append("<a:lnB w=\"12700\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:srgbClr val=\"${color(theme.border)}\"/></a:solidFill><a:prstDash val=\"solid\"/></a:lnB>")
        append("<a:solidFill><a:srgbClr val=\"${color(background)}\"/></a:solidFill>")
        append("</a:tcPr>")
        append("</a:tc>")
    }

    private fun columnWidths(block: TableBlock, width: Int, columnCount: Int): List<Int> {
        val weights = block.columns.map { it.width }
        val usable = if (weights.isNotEmpty() && weights.all { it != null && it > 0 }) {
            val total = weights.sumOf { it ?: 1.0 }
            weights.map { ((it ?: 1.0) / total * width).roundToInt() }
        } else {
            List(columnCount) { width / columnCount }
        }
        val fixed = usable.map { max(400000, it) }
        val total = fixed.sum()
        if (total == width) return fixed
        val scale = width.toDouble() / total
        val scaled = fixed.map { (it * scale).roundToInt() }.toMutableList()
        scaled[0] += width - scaled.sum()
        return scaled
    }

    private fun paintChart(block: ChartBlock, x: Int, y: Int, width: Int): Int {
        var cursor = y
        if (!block.title.isNullOrBlank()) {
            val height = measure(block.title, 13.0, width)
            textBox(x, cursor, width, height, paragraph(runs(block.title, 1300, theme.ink, theme.sansFont, bold = true)))
            cursor += height + 100000
        }
        val kindLabel = when (block.kind) {
            ChartKind.COLUMN -> "a column chart"
            ChartKind.BAR -> "a bar chart"
            ChartKind.LINE -> "a line chart"
            ChartKind.AREA -> "an area chart"
            ChartKind.PIE -> "a pie chart"
        }
        issues += ReportIssue(
            ReportIssue.Severity.WARNING,
            "PowerPoint cannot embed the generated chart, so $kindLabel was drawn with shapes.",
        )
        cursor += when (block.kind) {
            ChartKind.COLUMN -> paintColumnChart(block, x, cursor, width)
            ChartKind.BAR -> paintBarChart(block, x, cursor, width)
            else -> paintChartTable(block, x, cursor, width)
        }
        return cursor + 180000
    }

    private fun paintColumnChart(block: ChartBlock, x: Int, y: Int, width: Int): Int {
        val categories = block.categories.ifEmpty { block.series.firstOrNull()?.values?.indices?.map { "Item ${it + 1}" }.orEmpty() }
        if (categories.isEmpty() || block.series.isEmpty()) return 0
        val plotHeight = 2400000
        val labelHeight = 260000
        val valueLabelHeight = 200000
        val baseline = y + plotHeight
        val values = block.series.flatMap { it.values }
        val maxValue = values.maxOrNull()?.let { max(abs(it), 0.0001) } ?: 1.0
        val groupWidth = width / categories.size
        val barCount = block.series.size
        val barGap = 40000
        val barWidth = max(60000, (groupWidth * 0.66 - barGap * (barCount - 1)) / barCount)
        val colors = chartPalette(block.series.size)

        shape(x = x, y = baseline, w = width, h = 9525, fill = theme.border, name = "Chart axis")

        categories.forEachIndexed { index, category ->
            val groupStart = x + groupWidth * index + (groupWidth - (barWidth * barCount + barGap * (barCount - 1))) / 2
            block.series.forEachIndexed { seriesIndex, series ->
                val value = series.values.getOrNull(index) ?: 0.0
                val barHeight = ((abs(value) / maxValue) * (plotHeight - valueLabelHeight)).roundToInt()
                val barX = groupStart + seriesIndex * (barWidth + barGap)
                val barY = baseline - barHeight
                shape(barX, barY, barWidth, barHeight, fill = colors[seriesIndex], name = "Bar")
                textBox(
                    x = barX - barWidth,
                    y = max(y, barY - valueLabelHeight),
                    w = barWidth * 3,
                    h = valueLabelHeight,
                    paragraphs = paragraph(run(formatValue(value), 900, theme.muted, theme.sansFont), align = "ctr"),
                )
            }
            textBox(
                x = x + groupWidth * index,
                y = baseline + 60000,
                w = groupWidth,
                h = labelHeight,
                paragraphs = paragraph(run(category, 1000, theme.muted, theme.sansFont), align = "ctr"),
            )
        }
        return plotHeight + labelHeight + 80000
    }

    private fun paintBarChart(block: ChartBlock, x: Int, y: Int, width: Int): Int {
        val categories = block.categories.ifEmpty { block.series.firstOrNull()?.values?.indices?.map { "Item ${it + 1}" }.orEmpty() }
        if (categories.isEmpty() || block.series.isEmpty()) return 0
        val labelWidth = min(2000000, (width * 28) / 100)
        val rowHeight = 480000
        val values = block.series.flatMap { it.values }
        val maxValue = values.maxOrNull()?.let { max(abs(it), 0.0001) } ?: 1.0
        val colors = chartPalette(block.series.size)
        val plotWidth = width - labelWidth - 700000

        categories.forEachIndexed { index, category ->
            val rowTop = y + index * rowHeight
            textBox(
                x = x,
                y = rowTop,
                w = labelWidth - 100000,
                h = rowHeight - 120000,
                paragraphs = paragraph(run(category, 1000, theme.inkSoft, theme.sansFont), align = "r"),
            )
            block.series.forEachIndexed { seriesIndex, series ->
                val value = series.values.getOrNull(index) ?: 0.0
                val barHeight = ((rowHeight - 180000) / block.series.size).coerceAtLeast(60000)
                val barY = rowTop + 90000 + seriesIndex * barHeight
                val barWidth = max(20000, ((abs(value) / maxValue) * plotWidth).roundToInt())
                shape(x + labelWidth, barY, barWidth, barHeight - 20000, fill = colors[seriesIndex], name = "Bar")
                textBox(
                    x = x + labelWidth + barWidth + 80000,
                    y = barY - 20000,
                    w = 700000,
                    h = barHeight,
                    paragraphs = paragraph(run(formatValue(value), 900, theme.muted, theme.sansFont)),
                )
            }
        }
        return categories.size * rowHeight + 80000
    }

    private fun paintChartTable(block: ChartBlock, x: Int, y: Int, width: Int): Int {
        val categories = block.categories.ifEmpty { block.series.firstOrNull()?.values?.indices?.map { "Item ${it + 1}" }.orEmpty() }
        if (categories.isEmpty()) return 0
        val columns = listOf(com.teamshryne.kino.office.model.TableColumn("Category")) +
            block.series.map { com.teamshryne.kino.office.model.TableColumn(it.name) }
        val rows = categories.mapIndexed { index, category ->
            listOf(category) + block.series.map { formatValue(it.values.getOrNull(index) ?: 0.0) }
        }
        val table = TableBlock(columns = columns, rows = rows, zebra = true)
        return paintTable(table, x, y, width)
    }

    private fun chartPalette(count: Int): List<String> {
        val anchors = listOf(
            theme.accent,
            Colors.lighten(theme.accent, 0.55),
            Colors.darken(theme.accent, 0.28),
            Colors.mix(theme.accent, "#E17055", 0.55),
            Colors.mix(theme.accent, "#4E7CD9", 0.5),
        )
        return (0 until max(1, count)).map { anchors[it % anchors.size] }
    }

    private fun paintImage(block: ImageBlock, x: Int, y: Int, width: Int): Int {
        val data = Images.decodeDataUri(block.source)
        if (data == null) {
            issues += ReportIssue(
                ReportIssue.Severity.WARNING,
                "The image \"${block.source.take(80)}\" was skipped because its bytes are not available in the deck.",
            )
            return y
        }
        if (data.extension == "svg") {
            issues += ReportIssue(
                ReportIssue.Severity.WARNING,
                "PowerPoint cannot embed SVG images, so \"${block.source.take(80)}\" was skipped.",
            )
            return y
        }
        val relId = canvas.addImage(data.bytes, data.extension)
        val maxWidth = (width * block.maxWidthPercent.coerceIn(10, 100)) / 100
        val size = imageSize(data.bytes)
        val (pixelWidth, pixelHeight) = size ?: (1600 to 900)
        val pictureWidth = maxWidth
        val pictureHeight = max(
            200000,
            (pictureWidth.toDouble() * pixelHeight / max(1, pixelWidth)).roundToInt(),
        )
        val picture = buildString {
            append("<p:pic>")
            append("<p:nvPicPr><p:cNvPr id=\"${canvas.nextShapeId()}\" name=\"${Xml.attribute(block.alt ?: "Picture")}\"/>")
            append("<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>")
            append("<p:blipFill><a:blip r:embed=\"$relId\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>")
            append("<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$pictureWidth\" cy=\"$pictureHeight\"/></a:xfrm>")
            append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>")
            append("</p:pic>")
        }
        canvas.add(picture)
        var cursor = y + pictureHeight + 60000
        if (!block.caption.isNullOrBlank()) {
            val height = measure(block.caption, SIZE_SMALL / 100.0, width)
            textBox(x, cursor, width, height, paragraph(run(block.caption, SIZE_SMALL, theme.muted, theme.sansFont), align = "ctr"))
            cursor += height
        }
        return cursor + 120000
    }

    /* ------------------------------- helpers ------------------------------ */

    private fun calloutColor(kind: CalloutKind): String = when (kind) {
        CalloutKind.INFO -> theme.accent
        CalloutKind.NOTE -> "#4E7CD9"
        CalloutKind.WARNING -> theme.warning
        CalloutKind.SUCCESS -> theme.positive
        CalloutKind.DANGER -> theme.negative
    }

    private fun colorsOn(background: String): String = if (Colors.isDark(background)) "#FFFFFF" else "#1A1A1A"

    private fun formatValue(value: Double): String = when {
        abs(value) >= 1_000_000 -> "%.1fM".format(value / 1_000_000)
        abs(value) >= 1_000 -> "%.1fK".format(value / 1_000)
        abs(value) >= 100 -> "%.0f".format(value)
        abs(value) >= 1 -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }

    private fun measure(text: String, sizePt: Double, widthEmu: Int): Int {
        if (text.isBlank()) return 0
        val lines = estimateLines(text, sizePt, widthEmu)
        return (lines * sizePt * 1.25 * 12700).roundToInt()
    }

    private fun estimateLines(text: String, sizePt: Double, widthEmu: Int): Int {
        if (text.isBlank()) return 1
        val charWidth = sizePt * 0.5 * 12700
        val usable = max(1.0, widthEmu.toDouble())
        var lines = 0
        text.split("\n").forEach { line ->
            if (line.isEmpty()) {
                lines += 1
            } else {
                lines += max(1, ceil(line.length * charWidth / usable).toInt())
            }
        }
        return max(1, lines)
    }

    /* ------------------------------ primitives --------------------------- */

    private fun shape(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        paragraphs: String = "",
        geom: String = "rect",
        adj: Int? = null,
        fill: String? = null,
        line: String? = null,
        lineWidth: Int = 12700,
        name: String = "Shape",
        anchor: String = "t",
        inset: Int = 0,
    ) {
        canvas.add(
            buildString {
                append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(canvas.nextShapeId()).append("\" name=\"")
                append(Xml.attribute(name)).append("\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>")
                append("<a:xfrm><a:off x=\"").append(x).append("\" y=\"").append(y).append("\"/>")
                append("<a:ext cx=\"").append(w).append("\" cy=\"").append(h).append("\"/></a:xfrm>")
                append("<a:prstGeom prst=\"").append(geom).append("\">")
                if (adj != null) {
                    append("<a:avLst><a:gd name=\"adj\" fmla=\"val ").append(adj).append("\"/></a:avLst>")
                } else {
                    append("<a:avLst/>")
                }
                append("</a:prstGeom>")
                if (fill != null) {
                    append("<a:solidFill><a:srgbClr val=\"").append(color(fill)).append("\"/></a:solidFill>")
                } else {
                    append("<a:noFill/>")
                }
                if (line != null) {
                    append("<a:ln w=\"").append(lineWidth).append("\"><a:solidFill><a:srgbClr val=\"")
                    append(color(line)).append("\"/></a:solidFill></a:ln>")
                } else {
                    append("<a:ln><a:noFill/></a:ln>")
                }
                append("</p:spPr>")
                append(
                    "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"").append(inset).append("\" tIns=\"").append(inset)
                        .append("\" rIns=\"").append(inset).append("\" bIns=\"").append(inset)
                        .append("\" anchor=\"").append(anchor).append("\"><a:noAutofit/></a:bodyPr><a:lstStyle/>")
                        .append(paragraphs).append("</p:txBody></p:sp>")
            },
        )
    }

    private fun textBox(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        paragraphs: String,
        anchor: String = "t",
    ) {
        canvas.add(
            buildString {
                append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(canvas.nextShapeId()).append("\" name=\"TextBox\"/>")
                append("<p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr>")
                append("<a:xfrm><a:off x=\"").append(x).append("\" y=\"").append(y).append("\"/>")
                append("<a:ext cx=\"").append(w).append("\" cy=\"").append(h).append("\"/></a:xfrm>")
                append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>")
                append(
                    "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"")
                        .append(anchor).append("\"><a:noAutofit/></a:bodyPr><a:lstStyle/>")
                        .append(paragraphs).append("</p:txBody></p:sp>")
            },
        )
    }

    private fun paragraph(
        runs: String,
        align: String = "l",
        bullet: String? = null,
        ordered: Boolean = false,
        bulletColor: String? = null,
        marL: Int = 0,
        indent: Int = 0,
        lineSpacing: Int = 100000,
        spaceBefore: Int = 0,
        spaceAfter: Int = 0,
    ): String = buildString {
        append("<a:p><a:pPr")
        if (marL != 0) append(" marL=\"$marL\"")
        if (indent != 0) append(" indent=\"$indent\"")
        append(" algn=\"$align\">")
        append("<a:lnSpc><a:spcPct val=\"$lineSpacing\"/></a:lnSpc>")
        if (spaceBefore > 0) append("<a:spcBef><a:spcPts val=\"$spaceBefore\"/></a:spcBef>")
        if (spaceAfter > 0) append("<a:spcAft><a:spcPts val=\"$spaceAfter\"/></a:spcAft>")
        if (bulletColor != null) {
            append("<a:buClr><a:srgbClr val=\"").append(color(bulletColor)).append("\"/></a:buClr>")
        }
        if (ordered) {
            append("<a:buFont typeface=\"+mj-lt\"/><a:buAutoNum type=\"arabicPeriod\"/>")
        } else if (bullet != null) {
            append("<a:buFont typeface=\"Arial\" pitchFamily=\"34\" charset=\"0\"/>")
            append("<a:buChar char=\"").append(Xml.attribute(bullet)).append("\"/>")
        } else {
            append("<a:buNone/>")
        }
        append("</a:pPr>")
        append(runs)
        append("</a:p>")
    }

    private fun run(
        text: String,
        size: Int,
        color: String,
        font: String,
        bold: Boolean = false,
        italic: Boolean = false,
        strike: Boolean = false,
        underline: Boolean = false,
        spacing: Int = 0,
    ): String {
        if (text.isEmpty()) return ""
        return buildString {
            append("<a:r><a:rPr lang=\"en-US\" sz=\"").append(size)
            append("\" b=\"").append(if (bold) 1 else 0)
            append("\" i=\"").append(if (italic) 1 else 0)
            append("\" strike=\"").append(if (strike) "sngStrike" else "noStrike")
            append("\" u=\"").append(if (underline) "sng" else "none").append("\" spc=\"").append(spacing)
            append("\" dirty=\"0\"><a:solidFill><a:srgbClr val=\"").append(color(color))
            append("\"/></a:solidFill><a:latin typeface=\"").append(Xml.attribute(font))
            append("\"/><a:cs typeface=\"").append(Xml.attribute(font)).append("\"/></a:rPr>")
            append("<a:t>").append(Xml.escape(text)).append("</a:t></a:r>")
        }
    }

    private fun runs(
        text: String,
        size: Int,
        color: String,
        font: String,
        bold: Boolean = false,
        italic: Boolean = false,
    ): String {
        val spans = InlineMarkup.parse(text)
        if (spans.isEmpty()) return ""
        return spans.joinToString("") { span ->
            val spanFont = if (span.code) theme.monoFont else font
            val spanColor = when {
                span.link != null -> theme.accent
                span.code -> Colors.darken(color, 0.1)
                else -> color
            }
            run(
                text = span.text,
                size = size,
                color = spanColor,
                font = spanFont,
                bold = bold || span.bold,
                italic = italic || span.italic,
                strike = span.strike,
                underline = span.link != null,
            )
        }
    }
}

/* -------------------------------- helpers -------------------------------- */

private fun color(value: String): String = value.removePrefix("#").uppercase()

private fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 16) return null
    if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
        val w = int32(bytes, 16)
        val h = int32(bytes, 20)
        return if (w > 0 && h > 0) w to h else null
    }
    if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) {
        val w = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val h = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        return if (w > 0 && h > 0) w to h else null
    }
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
        var index = 2
        while (index + 9 < bytes.size) {
            if ((bytes[index].toInt() and 0xFF) != 0xFF) {
                index += 1
                continue
            }
            val marker = bytes[index + 1].toInt() and 0xFF
            val length = ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)
            val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrame) {
                val h = ((bytes[index + 5].toInt() and 0xFF) shl 8) or (bytes[index + 6].toInt() and 0xFF)
                val w = ((bytes[index + 7].toInt() and 0xFF) shl 8) or (bytes[index + 8].toInt() and 0xFF)
                return if (w > 0 && h > 0) w to h else null
            }
            if (length <= 0) break
            index += 2 + length
        }
    }
    return null
}

private fun int32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
