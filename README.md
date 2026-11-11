# Kino

Kino is an Android chat app for everyday conversations with a small amount of agent-style tool activity. It has a native Compose interface, durable local chat history, and a DeepSeek web-session bridge.

## Current foundation

- Native Kotlin and Jetpack Compose UI
- Chats home with search, create, open, and delete actions
- SQLite-backed conversation and ordered message history
- Tool activity stored in the message timeline and shown in expandable cards
- Local calculator, date/time, current-chat search, and HTML-to-PDF tools
- Structured office export: Word, OpenDocument, PDF, web, e-book, Markdown, RTF, text, Excel, CSV, TSV, PowerPoint, JSON, YAML and XML
- Settings pages for general behavior, appearance, chat activity, provider, storage, and app info
- Light and dark palettes, with settings persisted on device
- GitHub Actions builds and publishes a debug APK artifact, with Gradle output/configuration caches and automatic retention of the 15 newest Actions caches

The first install includes a clearly labeled example conversation so the chat and tool activity UI has content to explore. It can be deleted like any other chat.

## Project layout

```text
app/src/main/java/com/teamshryne/kino/
  data/                 Conversation repository and local SQLite storage
  data/tools/           Local tool registry, PDF renderer, office export
  domain/model/         Chat, message, and tool activity models
  ui/                   ViewModel and app navigation
  ui/components/        Shared UI components
  ui/screens/           Chats, chat timeline, and settings screens
  ui/theme/             Kino color schemes
office/src/main/kotlin/com/teamshryne/kino/office/
  json/                 Dependency-free JSON reader
  model/                Document, sheet and deck structures
  parse/                Payload parser with path-aware errors
  render/               HTML, SVG chart, Markdown, RTF, EPUB and CSV writers
  ooxml/                DOCX, XLSX and PPTX writers
  odf/                  ODT, ODS and ODP writers
  theme/                Document design systems
  zip/, xml/, image/    Package, markup and image helpers
```

The `office` module is a plain Kotlin/JVM library so the document pipeline can
be unit-tested off-device; the app depends on it like any other module.

## Build

Builds run in GitHub Actions on pushes to `main`, pull requests, or manual dispatch. The workflow uploads `kino-debug.apk` as an artifact. Kino is not built locally as part of the current workflow.

## UI direction

Kino's screen structure and compact work-log treatment take inspiration from [T3 Code Android](https://github.com/Shrawan-13/t3code-android). Kino has its own app structure and local conversation model.

## Local tool calling

The current DeepSeek bridge does not expose a native function-calling API. Each local chat receives a compact tool contract once, on its first tool-enabled turn. Kino accepts only a complete `[[KINO_TOOL_CALL]]…[[/KINO_TOOL_CALL]]` JSON response, validates it against a local allowlist, executes one of the local tools, then sends a bounded result back for the model to continue. Tool output is labeled untrusted in the continuation prompt.

Chat search reads saved messages for the active local chat and returns at most eight short excerpts (capped again before being sent to the model). The app downloads Kino Core from the [Kino library catalog](https://github.com/TeamShryne/kino-libraries) on demand and checks its size and SHA-256 checksum before installation. The chat exposes Open and Share actions using a temporary content URI grant.

## Office file generation

Handing a model a blank page and asking for HTML makes bad documents: the model
has to invent the layout, guess the widths, and hand-tune every pixel. Kino
splits the job instead. The model describes **what the document says** as
structured content, and Kino decides **how it looks** with a built-in design
system.

The model calls `document_help` once to read the payload schema for the family
it needs, then calls one of four tools:

| Tool | Formats |
| --- | --- |
| `create_document` | `docx`, `odt`, `pdf`, `html`, `epub`, `md`, `rtf`, `txt` |
| `create_spreadsheet` | `xlsx`, `ods`, `csv`, `tsv` |
| `create_presentation` | `pptx`, `odp` |
| `create_data_file` | `json`, `yaml`, `xml` |

A document payload is a small JSON object: a title, optional theme and page
setup, and a list of blocks - headings, paragraphs, bullets, tables, key
values, callouts, quotes, code, images, KPI tiles and charts. Text supports
`**bold**`, `*italic*`, `` `code` ``, `~~strike~~` and `[label](url)`.

Because the model never writes markup, every export lands in the same
house style: a cover page, an optional table of contents, a real type scale,
zebra tables with an accent header, and charts drawn as SVG. Eight themes
(`corporate`, `academic`, `minimal`, `vibrant`, `editorial`, `financial`,
`technical`, `midnight`) cover most briefs, and `accent` overrides the theme
colour when a brand colour is known.

The Word, Excel, PowerPoint and OpenDocument writers build the OOXML and ODF
packages by hand, so there is no third-party dependency and no runtime
service. Spreadsheets keep their column types, number formats, formulas,
frozen headers, filters and totals row. Presentations cover title, section,
content, two column, quote and metrics layouts plus speaker notes.

PDF output goes through the Android System WebView. Kino paginates the
document itself: it honours forced page breaks, draws the running header,
footer and page numbers on every page, and reports anything that landed badly
- a table split across pages, a heading stranded at the bottom of a page - so
the model can fix the payload and build the file again. The legacy
`html_to_pdf` tool remains available when pixel-level control over raw HTML
is what the user asked for.

Everything is written to app-private storage and shared through a temporary
content URI grant.
