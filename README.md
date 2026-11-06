# Kino

Kino is an Android chat app for everyday conversations with a small amount of agent-style tool activity. The current milestone focuses on the native interface and durable local chat history. DeepSeek connection and login are intentionally not implemented yet.

## Current foundation

- Native Kotlin and Jetpack Compose UI
- Chats home with search, create, open, and delete actions
- SQLite-backed conversation and ordered message history
- Tool activity stored in the message timeline and shown in expandable cards
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
