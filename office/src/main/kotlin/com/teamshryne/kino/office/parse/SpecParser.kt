package com.teamshryne.kino.office.parse

import com.teamshryne.kino.office.ReportIssue
import com.teamshryne.kino.office.SpecException
import com.teamshryne.kino.office.json.JsonValue
import com.teamshryne.kino.office.json.asArrayOrNull
import com.teamshryne.kino.office.json.asBooleanOrNull
import com.teamshryne.kino.office.json.asDoubleOrNull
import com.teamshryne.kino.office.json.asIntOrNull
import com.teamshryne.kino.office.json.asObjectOrNull
import com.teamshryne.kino.office.json.asStringOrNull
import com.teamshryne.kino.office.model.Block
import com.teamshryne.kino.office.model.CalloutBlock
import com.teamshryne.kino.office.model.CalloutKind
import com.teamshryne.kino.office.model.ChartBlock
import com.teamshryne.kino.office.model.ChartKind
import com.teamshryne.kino.office.model.ChartSeries
import com.teamshryne.kino.office.model.CodeBlock
import com.teamshryne.kino.office.model.ColumnAlignment
import com.teamshryne.kino.office.model.ColumnType
import com.teamshryne.kino.office.model.DeckSpec
import com.teamshryne.kino.office.model.DividerBlock
import com.teamshryne.kino.office.model.DocumentSpec
import com.teamshryne.kino.office.model.HeadingBlock
import com.teamshryne.kino.office.model.ImageBlock
import com.teamshryne.kino.office.model.KeyValueBlock
import com.teamshryne.kino.office.model.KeyValueItem
import com.teamshryne.kino.office.model.ListBlock
import com.teamshryne.kino.office.model.Orientation
import com.teamshryne.kino.office.model.PageBreakBlock
import com.teamshryne.kino.office.model.PageSize
import com.teamshryne.kino.office.model.ParagraphBlock
import com.teamshryne.kino.office.model.QuoteBlock
import com.teamshryne.kino.office.model.SheetCell
import com.teamshryne.kino.office.model.SheetColumn
import com.teamshryne.kino.office.model.SheetSpec
import com.teamshryne.kino.office.model.SlideLayout
import com.teamshryne.kino.office.model.SlideSpec
import com.teamshryne.kino.office.model.SpacerBlock
import com.teamshryne.kino.office.model.StatBlock
import com.teamshryne.kino.office.model.StatItem
import com.teamshryne.kino.office.model.TableBlock
import com.teamshryne.kino.office.model.TableColumn
import com.teamshryne.kino.office.model.TocBlock
import com.teamshryne.kino.office.model.WorkbookSpec

/**
 * Reads the JSON payload the model sends and turns it into typed documents.
 *
 * Every error carries a path such as `blocks[2].rows[0]` so a failed export can
 * tell the model exactly which field to fix instead of asking it to guess.
 */
object SpecParser {

    /* ------------------------------- documents ------------------------------ */

    fun parseDocument(root: JsonValue.Obj, issues: MutableList<ReportIssue>): DocumentSpec {
        val blocks = root["blocks"]?.let { value ->
            val array = value.asArrayOrNull() ?: throw SpecException("blocks must be an array of content blocks.", "blocks")
            array.mapIndexed { index, item -> parseBlock(item, "blocks[$index]", issues) }
        }.orEmpty()
        if (blocks.isEmpty()) {
            issues += ReportIssue(ReportIssue.Severity.WARNING, "The document has no content blocks; the file will be a title page only.")
        }
        return DocumentSpec(
            title = root.string("title") ?: "Document",
            subtitle = root.string("subtitle"),
            author = root.string("author"),
            date = root.string("date"),
            theme = root.string("theme") ?: "corporate",
            accent = root.string("accent"),
            cover = root.bool("cover", true),
            toc = root.bool("toc", false),
            header = root.string("header"),
            footer = root.string("footer"),
            pageNumbers = root.bool("page_numbers", true),
            pageSize = root.string("page_size")?.let { value ->
                PageSize.entries.firstOrNull { it.id == value.trim().lowercase() }
                    ?: throw SpecException("page_size must be one of ${PageSize.entries.joinToString(", ") { it.id }}.", "page_size")
            } ?: PageSize.A4,
            orientation = root.string("orientation")?.let { value ->
                when (value.trim().lowercase()) {
                    "portrait" -> Orientation.PORTRAIT
                    "landscape" -> Orientation.LANDSCAPE
                    else -> throw SpecException("orientation must be portrait or landscape.", "orientation")
                }
            } ?: Orientation.PORTRAIT,
            blocks = blocks,
        )
    }

    private fun parseBlock(value: JsonValue, path: String, issues: MutableList<ReportIssue>): Block {
        if (value is JsonValue.Str) {
            return ParagraphBlock(value.value)
        }
        val obj = value.asObjectOrNull() ?: throw SpecException("Each block must be an object with a \"type\" field.", path)
        val type = obj.string("type")?.trim()?.lowercase()
            ?: throw SpecException("Each block needs a \"type\" (paragraph, heading, bullets, table, key_values, callout, quote, code, image, stats, chart, divider, page_break, spacer, toc).", path)
        return when (type) {
            "heading", "h" -> HeadingBlock(
                level = (obj.int("level") ?: 1).coerceIn(1, 4),
                text = obj.string("text") ?: obj.string("title") ?: "",
                numbered = obj.bool("numbered", false),
            )

            "paragraph", "p", "text" -> ParagraphBlock(obj.string("text") ?: obj.string("content") ?: "")

            "bullets", "list", "bullet_list", "numbered_list" -> ListBlock(
                items = obj.stringList("items").ifEmpty { obj.stringList("content") },
                ordered = type == "numbered_list" || obj.bool("ordered", false),
                startAt = obj.int("start_at") ?: 1,
            )

            "table" -> parseTable(obj, path, issues)

            "key_values", "keyvalue", "facts", "metrics" -> KeyValueBlock(
                items = obj["items"]?.asArrayOrNull().orEmpty().mapIndexed { index, item ->
                    val entry = item.asObjectOrNull() ?: throw SpecException("Each key/value item must be an object with \"label\" and \"value\".", "$path.items[$index]")
                    KeyValueItem(
                        label = entry.string("label") ?: entry.string("key") ?: "",
                        value = entry.string("value") ?: "",
                        hint = entry.string("hint"),
                    )
                },
                columns = (obj.int("columns") ?: 2).coerceIn(1, 4),
            )

            "callout", "note", "warning", "info", "success", "danger" -> CalloutBlock(
                kind = when {
                    type != "callout" && type != "note" -> CalloutKind.entries.firstOrNull { it.name.equals(type, true) } ?: CalloutKind.INFO
                    else -> obj.string("kind")?.trim()?.lowercase()?.let { key ->
                        CalloutKind.entries.firstOrNull { it.name.equals(key, true) }
                    } ?: CalloutKind.INFO
                },
                title = obj.string("title"),
                text = obj.string("text") ?: obj.string("content") ?: "",
            )

            "quote" -> QuoteBlock(
                text = obj.string("text") ?: obj.string("content") ?: "",
                attribution = obj.string("attribution") ?: obj.string("author"),
            )

            "code", "code_block" -> CodeBlock(
                text = obj.string("text") ?: obj.string("code") ?: obj.string("content") ?: "",
                language = obj.string("language"),
            )

            "image", "figure" -> ImageBlock(
                source = obj.string("src") ?: obj.string("source") ?: obj.string("url")
                    ?: throw SpecException("An image block needs a \"src\" (https URL or data: URI).", path),
                caption = obj.string("caption"),
                alt = obj.string("alt"),
                maxWidthPercent = (obj.int("width") ?: 100).coerceIn(10, 100),
            )

            "stats", "kpis", "tiles" -> StatBlock(
                items = obj["items"]?.asArrayOrNull().orEmpty().mapIndexed { index, item ->
                    val entry = item.asObjectOrNull() ?: throw SpecException("Each stat needs \"label\" and \"value\".", "$path.items[$index]")
                    StatItem(
                        label = entry.string("label") ?: "",
                        value = entry.string("value") ?: "",
                        delta = entry.string("delta"),
                        positive = entry.bool("positive", true),
                    )
                },
            )

            "chart", "graph" -> parseChart(obj, path)

            "divider", "hr", "rule" -> DividerBlock

            "page_break", "pagebreak", "break" -> PageBreakBlock

            "spacer", "space" -> SpacerBlock((obj.int("size") ?: 1).coerceIn(1, 3))

            "toc", "contents" -> TocBlock

            else -> throw SpecException("Unsupported block type \"$type\".", path)
        }
    }

    private fun parseTable(obj: JsonValue.Obj, path: String, issues: MutableList<ReportIssue>): TableBlock {
        val columnObjects = obj["columns"]?.asArrayOrNull()
        val columns: List<TableColumn> = when {
            columnObjects != null -> columnObjects.mapIndexed { index, item ->
                when (item) {
                    is JsonValue.Str -> TableColumn(header = item.value)
                    else -> {
                        val column = item.asObjectOrNull() ?: throw SpecException("Each column must be a string or an object.", "$path.columns[$index]")
                        TableColumn(
                            header = column.string("header") ?: column.string("name") ?: "",
                            width = column.double("width"),
                            alignment = column.string("align")?.let { alignment(it, "$path.columns[$index].align") } ?: ColumnAlignment.LEFT,
                            type = column.string("type")?.let { columnType(it, "$path.columns[$index].type") } ?: ColumnType.TEXT,
                        )
                    }
                }
            }

            else -> obj.stringList("headers").map { TableColumn(header = it) }
        }
        val rawRows = obj["rows"]?.asArrayOrNull()
            ?: throw SpecException("A table block needs a \"rows\" array.", path)
        val rows = rawRows.mapIndexed { rowIndex, row ->
            val cells = row.asArrayOrNull() ?: throw SpecException("Each table row must be an array of cells.", "$path.rows[$rowIndex]")
            val texts = cells.map { cell ->
                when (cell) {
                    is JsonValue.Str -> cell.value
                    else -> cell.asStringOrNull().orEmpty()
                }
            }
            if (columns.isNotEmpty() && texts.size != columns.size) {
                issues += ReportIssue(
                    ReportIssue.Severity.WARNING,
                    "Row ${rowIndex + 1} has ${texts.size} cells but the table has ${columns.size} columns; it was padded to match.",
                    "$path.rows[$rowIndex]",
                    fixable = true,
                )
            }
            texts
        }
        val width = columns.size
        val padded = rows.map { row ->
            when {
                width == 0 -> row
                row.size >= width -> row.take(width)
                else -> row + List(width - row.size) { "" }
            }
        }
        return TableBlock(
            columns = columns,
            rows = padded,
            caption = obj.string("caption"),
            zebra = obj.bool("zebra", true),
            alignments = obj["alignments"]?.asArrayOrNull()?.map { alignment(it.asStringOrNull().orEmpty(), "$path.alignments") }.orEmpty(),
        )
    }

    private fun parseChart(obj: JsonValue.Obj, path: String): ChartBlock {
        val kind = obj.string("kind")?.trim()?.lowercase()?.let { key ->
            ChartKind.entries.firstOrNull { it.name.equals(key, true) }
        } ?: ChartKind.COLUMN
        val categories = obj.stringList("categories")
        val series = obj["series"]?.asArrayOrNull().orEmpty().mapIndexed { index, item ->
            val entry = item.asObjectOrNull() ?: throw SpecException("Each chart series must be an object with \"name\" and \"values\".", "$path.series[$index]")
            val values = entry["values"]?.asArrayOrNull().orEmpty().map { cell ->
                cell.asDoubleOrNull() ?: throw SpecException("Chart values must be numbers.", "$path.series[$index].values")
            }
            ChartSeries(name = entry.string("name") ?: "Series ${index + 1}", values = values)
        }
        if (series.isEmpty()) throw SpecException("A chart needs at least one series.", path)
        if (categories.isNotEmpty() && series.any { it.values.size != categories.size }) {
            throw SpecException("Every chart series must have the same number of values as there are categories (${categories.size}).", path)
        }
        return ChartBlock(
            kind = kind,
            categories = categories,
            series = series,
            title = obj.string("title"),
            xLabel = obj.string("x_label"),
            yLabel = obj.string("y_label"),
            stacked = obj.bool("stacked", false),
        )
    }

    /* ------------------------------ workbooks ------------------------------- */

    fun parseWorkbook(root: JsonValue.Obj, issues: MutableList<ReportIssue>): WorkbookSpec {
        val sheetValues = root["sheets"]?.asArrayOrNull()
            ?: throw SpecException("A workbook needs a \"sheets\" array.", "sheets")
        if (sheetValues.isEmpty()) throw SpecException("A workbook needs at least one sheet.", "sheets")
        val sheets = sheetValues.mapIndexed { index, item ->
            val sheet = item.asObjectOrNull() ?: throw SpecException("Each sheet must be an object.", "sheets[$index]")
            parseSheet(sheet, "sheets[$index]", index, issues)
        }
        return WorkbookSpec(
            title = root.string("title") ?: "Workbook",
            theme = root.string("theme") ?: "corporate",
            sheets = sheets,
        )
    }

    private fun parseSheet(sheet: JsonValue.Obj, path: String, index: Int, issues: MutableList<ReportIssue>): SheetSpec {
        val columnValues = sheet["columns"]?.asArrayOrNull()
        val columns = columnValues.orEmpty().mapIndexed { columnIndex, item ->
            when (item) {
                is JsonValue.Str -> SheetColumn(header = item.value)
                else -> {
                    val column = item.asObjectOrNull() ?: throw SpecException("Each column must be a string or an object.", "$path.columns[$columnIndex]")
                    SheetColumn(
                        header = column.string("header") ?: column.string("name") ?: "",
                        width = column.double("width"),
                        type = column.string("type")?.let { columnType(it, "$path.columns[$columnIndex].type") } ?: ColumnType.TEXT,
                        format = column.string("format"),
                    )
                }
            }
        }
        val rowValues = sheet["rows"]?.asArrayOrNull().orEmpty()
        val rows = rowValues.mapIndexed { rowIndex, row ->
            val cells = row.asArrayOrNull() ?: throw SpecException("Each row must be an array of cell values.", "$path.rows[$rowIndex]")
            cells.mapIndexed { cellIndex, cell -> parseCell(cell, columns.getOrNull(cellIndex), "$path.rows[$rowIndex][$cellIndex]") }
        }
        val name = sheet.string("name")?.take(31)?.ifBlank { null } ?: "Sheet${index + 1}"
        if (columns.isNotEmpty() && rows.any { it.size != columns.size }) {
            issues += ReportIssue(
                ReportIssue.Severity.WARNING,
                "Some rows have a different cell count than the column list; they were padded or trimmed.",
                path,
                fixable = true,
            )
        }
        return SheetSpec(
            name = name,
            columns = columns,
            rows = rows,
            freeze = sheet.string("freeze"),
            autoFilter = sheet.bool("autofilter", false) || sheet.bool("auto_filter", false),
            notes = sheet["notes"]?.asObjectOrNull()?.entries.orEmpty()
                .mapNotNull { (key, value) -> value.asStringOrNull()?.let { key to it } }
                .toMap(),
            tabColor = sheet.string("tab_color"),
            totalRow = sheet.bool("total_row", false),
        )
    }

    private fun parseCell(cell: JsonValue, column: SheetColumn?, path: String): SheetCell {
        val format = column?.format
        val type = column?.type ?: ColumnType.TEXT
        return when (cell) {
            is JsonValue.Num -> when (type) {
                ColumnType.PERCENT -> SheetCell.Number(cell.value, format ?: "0.0%", align = ColumnAlignment.RIGHT)
                ColumnType.CURRENCY -> SheetCell.Number(cell.value, format ?: "#,##0.00", align = ColumnAlignment.RIGHT)
                ColumnType.DATE -> SheetCell.Number(cell.value, format ?: "yyyy-mm-dd", align = ColumnAlignment.RIGHT)
                ColumnType.BOOL -> SheetCell.Bool(cell.value != 0.0, format, align = ColumnAlignment.CENTER)
                else -> SheetCell.Number(cell.value, format, align = ColumnAlignment.RIGHT)
            }

            is JsonValue.Bool -> SheetCell.Bool(cell.value, format, align = ColumnAlignment.CENTER)

            is JsonValue.Str -> {
                val text = cell.value
                if (text.startsWith("=")) {
                    SheetCell.Formula(text.removePrefix("="), format)
                } else if (type == ColumnType.NUMBER || type == ColumnType.CURRENCY || type == ColumnType.PERCENT) {
                    val number = text.trim().toDoubleOrNull()
                    if (number != null) {
                        SheetCell.Number(number, format ?: when (type) {
                            ColumnType.PERCENT -> "0.0%"
                            ColumnType.CURRENCY -> "#,##0.00"
                            else -> null
                        }, align = ColumnAlignment.RIGHT)
                    } else {
                        SheetCell.Text(text, format)
                    }
                } else {
                    SheetCell.Text(text, format)
                }
            }

            else -> {
                val obj = cell.asObjectOrNull() ?: throw SpecException("A cell must be a number, string, boolean, or object.", path)
                val value = obj["value"] ?: throw SpecException("A cell object needs a \"value\".", path)
                val cellFormat = obj.string("format") ?: format
                val bold = obj.bool("bold", false)
                val align = obj.string("align")?.let { alignment(it, "$path.align") }
                when (value) {
                    is JsonValue.Num -> SheetCell.Number(value.value, cellFormat, bold, align ?: ColumnAlignment.RIGHT)
                    is JsonValue.Bool -> SheetCell.Bool(value.value, cellFormat, bold, align ?: ColumnAlignment.CENTER)
                    is JsonValue.Str -> {
                        if (value.value.startsWith("=")) SheetCell.Formula(value.value.removePrefix("="), cellFormat, bold, align)
                        else SheetCell.Text(value.value, cellFormat, bold, align)
                    }

                    else -> throw SpecException("A cell \"value\" must be a number, string, or boolean.", "$path.value")
                }
            }
        }
    }

    /* -------------------------------- decks --------------------------------- */

    fun parseDeck(root: JsonValue.Obj, issues: MutableList<ReportIssue>): DeckSpec {
        val slideValues = root["slides"]?.asArrayOrNull()
            ?: throw SpecException("A presentation needs a \"slides\" array.", "slides")
        if (slideValues.isEmpty()) throw SpecException("A presentation needs at least one slide.", "slides")
        val slides = slideValues.mapIndexed { index, item ->
            val slide = item.asObjectOrNull() ?: throw SpecException("Each slide must be an object.", "slides[$index]")
            val layout = slide.string("layout")?.trim()?.lowercase()?.let { key ->
                SlideLayout.entries.firstOrNull { it.name.equals(key, true) }
            } ?: when {
                slide.string("title") != null && slide["blocks"] == null && slide["columns"] == null -> SlideLayout.CONTENT
                else -> SlideLayout.CONTENT
            }
            val blocks = slide["blocks"]?.let { value ->
                val array = value.asArrayOrNull() ?: throw SpecException("Slide blocks must be an array.", "slides[$index].blocks")
                array.mapIndexed { blockIndex, block -> parseBlock(block, "slides[$index].blocks[$blockIndex]", issues) }
            }.orEmpty()
            val columns = slide["columns"]?.let { value ->
                val array = value.asArrayOrNull() ?: throw SpecException("Slide columns must be an array of block arrays.", "slides[$index].columns")
                array.mapIndexed { columnIndex, column ->
                    val blocks = column.asArrayOrNull() ?: throw SpecException("Each slide column must be an array of blocks.", "slides[$index].columns[$columnIndex]")
                    blocks.mapIndexed { blockIndex, block -> parseBlock(block, "slides[$index].columns[$columnIndex][$blockIndex]", issues) }
                }
            }.orEmpty()
            SlideSpec(
                layout = layout,
                title = slide.string("title"),
                subtitle = slide.string("subtitle"),
                blocks = blocks,
                columns = columns,
                notes = slide.string("notes"),
            )
        }
        return DeckSpec(
            title = root.string("title") ?: "Presentation",
            subtitle = root.string("subtitle"),
            author = root.string("author"),
            theme = root.string("theme") ?: "corporate",
            accent = root.string("accent"),
            slides = slides,
        )
    }

    /* -------------------------------- data ---------------------------------- */

    fun parseData(root: JsonValue.Obj): String {
        val content = root.string("content") ?: root.string("text") ?: root.string("data")
            ?: throw SpecException("A data file needs a \"content\" string with the full file body.", "content")
        return content
    }

    /* ------------------------------- helpers -------------------------------- */

    private fun alignment(value: String, path: String): ColumnAlignment = when (value.trim().lowercase()) {
        "left", "start" -> ColumnAlignment.LEFT
        "center", "centre", "middle" -> ColumnAlignment.CENTER
        "right", "end" -> ColumnAlignment.RIGHT
        else -> throw SpecException("Alignment must be left, center, or right.", path)
    }

    private fun columnType(value: String, path: String): ColumnType = when (value.trim().lowercase()) {
        "text", "string" -> ColumnType.TEXT
        "number", "numeric", "int", "integer", "decimal" -> ColumnType.NUMBER
        "currency", "money" -> ColumnType.CURRENCY
        "percent", "percentage" -> ColumnType.PERCENT
        "date", "datetime" -> ColumnType.DATE
        "bool", "boolean" -> ColumnType.BOOL
        else -> throw SpecException("Column type must be text, number, currency, percent, date, or bool.", path)
    }

    private fun JsonValue.Obj.string(key: String): String? = this[key]?.asStringOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonValue.Obj.bool(key: String, fallback: Boolean): Boolean = this[key]?.asBooleanOrNull() ?: fallback

    private fun JsonValue.Obj.int(key: String): Int? = this[key]?.asIntOrNull()

    private fun JsonValue.Obj.double(key: String): Double? = this[key]?.asDoubleOrNull()

    private fun JsonValue.Obj.stringList(key: String): List<String> =
        this[key]?.asArrayOrNull().orEmpty().mapNotNull { item ->
            when (item) {
                is JsonValue.Str -> item.value
                else -> item.asStringOrNull()
            }
        }
}
