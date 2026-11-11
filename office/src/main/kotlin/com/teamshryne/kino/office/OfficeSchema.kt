package com.teamshryne.kino.office

import com.teamshryne.kino.office.theme.Themes

/**
 * The schema reference the model reads before it writes a document payload.
 *
 * The tool contract in the chat prompt stays short on purpose; this full
 * reference is fetched on demand so the model is never asked to remember a
 * long schema, and so the schema can grow without bloating every prompt.
 */
object OfficeSchema {

    val documentHelp: String = """
        create_document builds a Word, OpenDocument, PDF, web, e-book, Markdown, RTF or text file.
        You describe CONTENT and STRUCTURE; Kino applies the typography, spacing and colours.

        document = {
          title?: string            // used for the cover, file name and page title
          subtitle?: string
          author?: string
          date?: string             // "March 2026" or "2026-03-14"
          theme?: string            // one of ${Themes.ids.joinToString(", ")}
          accent?: string           // "#1F4E79" overrides the theme accent
          cover?: boolean           // default true
          toc?: boolean             // table of contents
          header?: string           // running header text
          footer?: string           // running footer text
          page_numbers?: boolean    // default true
          page_size?: string        // "a4" | "letter" | "legal" | "a3"
          orientation?: string      // "portrait" | "landscape"
          blocks?: Block[]
        }

        Block =
          {type:"heading", level:1..4, text, numbered?:boolean}
        | {type:"paragraph", text}
        | {type:"bullets", items:[string], ordered?:boolean, start_at?:number}
        | {type:"table", columns:[string|{header,width,align,type}], rows:[[string]], caption?:string, zebra?:boolean}
        | {type:"key_values", items:[{label,value,hint?}], columns?:1..4}
        | {type:"callout", kind:"info"|"note"|"warning"|"success"|"danger", title?:string, text}
        | {type:"quote", text, attribution?:string}
        | {type:"code", text, language?:string}
        | {type:"image", src:"https://...|data:image/png;base64,...", caption?:string, alt?:string, width?:10..100}
        | {type:"stats", items:[{label,value,delta?,positive?}]}
        | {type:"chart", kind:"bar"|"column"|"line"|"area"|"pie", categories:[string], series:[{name,values:[number]}], title?:string, x_label?, y_label?}
        | {type:"divider"}
        | {type:"page_break"}
        | {type:"spacer", size?:1..3}
        | {type:"toc"}

        Text supports inline markup: **bold**, *italic*, `code`, ~~strike~~, [label](https://link).
        Write real prose and real numbers. Do not describe the layout in words, and never
        write raw HTML, CSS or XML - Kino renders it.

        Example:
        {
          "title": "Q3 Revenue Review", "subtitle": "Prepared for the board",
          "author": "Analytics", "date": "October 2026", "theme": "corporate", "toc": true,
          "blocks": [
            {"type": "stats", "items": [
              {"label": "Revenue", "value": "$4.2M", "delta": "+12% vs Q2", "positive": true},
              {"label": "Churn", "value": "1.8%", "delta": "-0.4pts", "positive": true}]},
            {"type": "heading", "level": 1, "text": "Highlights", "numbered": true},
            {"type": "paragraph", "text": "Revenue grew **12%** quarter over quarter, driven by *enterprise*."},
            {"type": "bullets", "items": ["Enterprise grew 24%", "SMB was flat", "EMEA lagged by 3 points"]},
            {"type": "chart", "kind": "column", "categories": ["Jul","Aug","Sep"],
             "series": [{"name": "Revenue", "values": [120, 138, 162]}], "title": "Monthly revenue"},
            {"type": "table", "columns": ["Region","Revenue","Share"], "rows": [["NA", "$2.1M","50%"],["EMEA","$1.4M","33%"]]},
            {"type": "callout", "kind": "warning", "title": "Watch", "text": "EMEA growth slowed for a second quarter."}
          ]
        }
    """.trimIndent()

    val sheetHelp: String = """
        create_spreadsheet builds an Excel, OpenDocument, CSV or TSV file.
        Rows are plain arrays; columns describe the header and the type of each column.

        document = {
          title?: string
          theme?: string
          sheets?: [ Sheet ]
        }

        Sheet = {
          name?: string             // tab name, <= 31 characters
          columns?: [ Column ]      // the header row
          rows?: [ Row ]            // data rows, the first row is right under the header
          freeze?: string           // "A2" freezes the header row
          autofilter?: boolean
          total_row?: boolean       // appends a bold SUM row for numeric columns
          tab_color?: string        // "#1F4E79"
          notes?: { "B4": "Context for this cell" }
        }

        Column = { header: string, width?: number, type?: "text"|"number"|"currency"|"percent"|"date"|"bool", format?: string }
        Cell   = number | boolean | string | "=SUM(B2:B9)" | { value, format?, bold?, align? }

        align is "left" | "center" | "right". format is an Excel number format such as
        "#,##0.00", "0.0%", "yyyy-mm-dd". Formulas start with = and are written into the sheet.

        Example:
        {
          "title": "FY26 Plan", "theme": "financial",
          "sheets": [{
            "name": "Revenue", "freeze": "A2", "autofilter": true, "total_row": true,
            "columns": [
              {"header": "Month", "type": "text"},
              {"header": "Revenue", "type": "currency", "format": "#,##0.00"},
              {"header": "Growth", "type": "percent"}],
            "rows": [
              ["January", 120000, 0.08],
              ["February", 138500, 0.154],
              ["Total", "=SUM(B2:B3)", "=AVERAGE(C2:C3)"]]
          }]
        }
    """.trimIndent()

    val deckHelp: String = """
        create_presentation builds a PowerPoint or OpenDocument presentation.

        document = {
          title?: string
          subtitle?: string
          author?: string
          theme?: string
          accent?: string
          slides?: [ Slide ]
        }

        Slide = {
          layout?: "title"|"section"|"content"|"two_column"|"quote"|"metrics"
          title?: string
          subtitle?: string
          blocks?: Block[]           // same blocks as create_document
          columns?: [ Block[] ]      // two_column only: one block list per column
          notes?: string             // speaker notes
        }

        Use layout "title" for the opening slide, "section" between chapters, "quote" for a
        pull quote, "metrics" for KPI tiles (a stats block), "two_column" when comparing two
        ideas, and "content" for everything else. Keep slides to one idea and at most six
        bullets.

        Example:
        {
          "title": "FY26 Product Strategy", "theme": "midnight",
          "slides": [
            {"layout": "title", "title": "FY26 Product Strategy", "subtitle": "Where we play and why"},
            {"layout": "metrics", "title": "Where we are today",
             "blocks": [{"type": "stats", "items": [
               {"label": "ARR", "value": "$18M"}, {"label": "NPS", "value": "54"}]}]},
            {"layout": "content", "title": "Three bets",
             "blocks": [{"type": "bullets", "items": ["Own the workflow, not the feature", "Sell to the buyer, not the user", "Price on outcomes"]}],
             "notes": "Lead with the first bet."}
          ]
        }
    """.trimIndent()

    val dataHelp: String = """
        create_data_file writes a plain data file: JSON, YAML or XML.

        document = { title?: string, content?: string }
        content is the complete file body. For JSON it must be valid JSON, for XML valid XML.
        Kino checks the content and tells you when it does not parse.
    """.trimIndent()

    /** Compact per-format guidance used by the tool contract. */
    fun helpFor(format: OfficeFormat): String = when (format.family) {
        OfficeFamily.DOCUMENT -> documentHelp
        OfficeFamily.SHEET -> sheetHelp
        OfficeFamily.DECK -> deckHelp
        OfficeFamily.DATA -> dataHelp
    }
}
