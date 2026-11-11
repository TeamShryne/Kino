package com.teamshryne.kino.office

/** The families of document a payload can describe. */
enum class OfficeFamily { DOCUMENT, SHEET, DECK, DATA }

/**
 * Every export the office pipeline can produce.
 *
 * A single registry keeps format knowledge in one place: how to name the file,
 * what MIME type to report, which payload shape the model must send, and which
 * renderer produces the bytes.
 */
enum class OfficeFormat(
    val id: String,
    val extension: String,
    val mimeType: String,
    val family: OfficeFamily,
    val label: String,
) {
    DOCX("docx", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", OfficeFamily.DOCUMENT, "Word document"),
    ODT("odt", "odt", "application/vnd.oasis.opendocument.text", OfficeFamily.DOCUMENT, "OpenDocument text"),
    PDF("pdf", "pdf", "application/pdf", OfficeFamily.DOCUMENT, "PDF document"),
    HTML("html", "html", "text/html", OfficeFamily.DOCUMENT, "Web page"),
    EPUB("epub", "epub", "application/epub+zip", OfficeFamily.DOCUMENT, "E-book"),
    MARKDOWN("md", "md", "text/markdown", OfficeFamily.DOCUMENT, "Markdown"),
    RTF("rtf", "rtf", "application/rtf", OfficeFamily.DOCUMENT, "Rich text"),
    TEXT("txt", "txt", "text/plain", OfficeFamily.DOCUMENT, "Plain text"),

    XLSX("xlsx", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", OfficeFamily.SHEET, "Excel workbook"),
    ODS("ods", "ods", "application/vnd.oasis.opendocument.spreadsheet", OfficeFamily.SHEET, "OpenDocument spreadsheet"),
    CSV("csv", "csv", "text/csv", OfficeFamily.SHEET, "CSV data"),
    TSV("tsv", "tsv", "text/tab-separated-values", OfficeFamily.SHEET, "TSV data"),

    PPTX("pptx", "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", OfficeFamily.DECK, "PowerPoint deck"),
    ODP("odp", "odp", "application/vnd.oasis.opendocument.presentation", OfficeFamily.DECK, "OpenDocument presentation"),

    JSON("json", "json", "application/json", OfficeFamily.DATA, "JSON data"),
    YAML("yaml", "yaml", "application/yaml", OfficeFamily.DATA, "YAML data"),
    XML("xml", "xml", "application/xml", OfficeFamily.DATA, "XML data"),
    ;

    companion object {
        fun byId(id: String?): OfficeFormat? {
            if (id.isNullOrBlank()) return null
            val needle = id.trim().lowercase()
            return entries.firstOrNull { it.id == needle }
                ?: entries.firstOrNull { it.extension == needle }
                ?: entries.firstOrNull { it.name.lowercase() == needle }
        }

        fun byFamily(family: OfficeFamily): List<OfficeFormat> = entries.filter { it.family == family }
    }
}
