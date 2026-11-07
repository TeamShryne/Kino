# Kino

Kino is an Android chat app for everyday conversations with a small amount of agent-style tool activity. It has a native Compose interface, durable local chat history, and a DeepSeek web-session bridge.

## Current foundation

- Native Kotlin and Jetpack Compose UI
- Chats home with search, create, open, and delete actions
- SQLite-backed conversation and ordered message history
- Tool activity stored in the message timeline and shown in expandable cards
- Local calculator, date/time, current-chat search, and HTML-to-PDF tools
- Settings pages for general behavior, appearance, chat activity, provider, storage, and app info
- Light and dark palettes, with settings persisted on device
- GitHub Actions builds and publishes a debug APK artifact, with Gradle output/configuration caches and automatic retention of the 15 newest Actions caches

The first install includes a clearly labeled example conversation so the chat and tool activity UI has content to explore. It can be deleted like any other chat.

## Project layout

```text
app/src/main/java/com/teamshryne/kino/
  data/                 Conversation repository and local SQLite storage
  domain/model/         Chat, message, and tool activity models
  ui/                   ViewModel and app navigation
  ui/components/        Shared UI components
  ui/screens/           Chats, chat timeline, and settings screens
  ui/theme/             Kino color schemes
```

## Build

Builds run in GitHub Actions on pushes to `main`, pull requests, or manual dispatch. The workflow uploads `kino-debug.apk` as an artifact. Kino is not built locally as part of the current workflow.

## UI direction

Kino's screen structure and compact work-log treatment take inspiration from [T3 Code Android](https://github.com/Shrawan-13/t3code-android). Kino has its own app structure and local conversation model.

## Local tool calling

The current DeepSeek bridge does not expose a native function-calling API. Each local chat receives a compact tool contract once, on its first tool-enabled turn. Kino accepts only a complete `[[KINO_TOOL_CALL]]…[[/KINO_TOOL_CALL]]` JSON response, validates it against a local allowlist, executes one of four local tools, then sends a bounded result back for the model to continue. A reply is limited to four tool calls. Tool output is labeled untrusted in the continuation prompt.

Chat search reads saved messages for the active local chat and returns at most eight short excerpts (capped again before being sent to the model). HTML-to-PDF uses the native Ironpress paged renderer through a separately released Android FFI library. Kino limits input to 200,000 characters and output to 25 MB, then saves the PDF in app-private storage. CI downloads a version-pinned AAR from the [Ironpress Android fork](https://github.com/TeamShryne/ironpress-android) and verifies its SHA-256 checksum. The chat exposes Open and Share actions using a temporary content URI grant.
