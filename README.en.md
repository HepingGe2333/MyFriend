# MyFriend

**English** · [简体中文](README.md)

MyFriend is a companion app for Android and iOS that models a "persona" as a long-lived contact rather than a set of tunable chat parameters. Both platforms share the same data model, the same behavioural rules, and the same backup file.

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B%20%7C%20iOS%2016%2B-lightgrey.svg)](#architecture--stack)
[![Tests](https://img.shields.io/badge/tests-1044%20Android%20%7C%201263%20iOS%20%7C%2060%20agentcore%20%7C%2034%20agentd-brightgreen.svg)](#build-and-test)

## Project status

| Item | Value |
| --- | --- |
| Version | `0.12.0` (beta series `0.x`; policy and release process in [CONTRIBUTING.en.md](CONTRIBUTING.en.md#versioning-and-releases)) |
| Database schema | v11 (messages stored in blocks of 200; Android Room `@Database(version = 11)` aligned with iOS SQLite `user_version = 11`) |
| Backup format | v3 (`format = "myfriend.backup"`, `formatVersion = 3`; images and avatars travel with the backup, v1/v2 still readable) |
| Build numbers | Android `versionCode` and iOS `CURRENT_PROJECT_VERSION` are kept equal |
| Unit tests | 1044 on Android (`:app:testDebugUnitTest`, plus 28 instrumented), 60 in `:agentcore`, 34 in `:agentd`, 1263 on iOS (`swift test`) |
| CI | None yet; all tests and builds run from local commands |

## Feature overview

| Area | Implemented |
| --- | --- |
| Home | Contact list with avatar, name, last message, timestamp and unread count; one conversation per contact |
| Search | Full-text chat-history search: highlighted snippets, persona and time filters, **block-by-block scanning with no candidate cap**, tap a hit to jump back to that message with a highlight (same semantics on both platforms, contract in `spec/search/`) |
| Chat | SSE streaming, stop generation, Markdown / code blocks / LaTeX, image messages (up to 4 per message), long-press actions (copy / edit & resend / regenerate / delete), link detection with an in-app browser |
| Read state and silence | `messages.readAt` read receipts; `defer_reply` lets the model skip a turn while the UI only shows "read" |
| Sleep | Persona-initiated sleep, do-not-disturb decided by the model, wake threshold inside a 30-minute window |
| Persona | Structured fields (traits / likes / behaviours / backstory / quirks) plus behavioural parameters (proactiveness, memory retention, extraction interval, sleep), full prompt editing, version snapshots, evolution proposals, persona card |
| Avatars | Persona avatar and user avatar; sampled, compressed and stored locally, rendered consistently, never included in backups |
| Long-term memory | Automatic extraction, lifecycle (ACTIVE / DORMANT / ARCHIVED), strength decay, daily consolidation, three-depth `recall`, recall-driven rewriting |
| Retrieval | Chinese bigram inverted index plus local 384-dimension hashed-embedding cosine reranking (`local-hash-v1`, offline and dependency-free) |
| Long conversations | The latest 24 messages verbatim, older history folded into a rolling summary; no original message is deleted |
| Proactive behaviour | 30-second heartbeat (dip-then-rise impulse curve, capped after three hours), proactive rules; the model decides whether to speak |
| Presence | Display state such as calm / spacing out / writing, plus "asleep · wakes 08:00" |
| Tools | `think` / `remember` / `recall` / `set_mood` / `set_quiet_hours` / `web_search` / `web_fetch` / `update_persona` / `defer_reply`; real executors on both platforms |
| Model access | Any OpenAI-compatible service: multiple profiles, base URL / key / model / custom headers, connectivity test, inference parameters; Gemini and Claude adapters are prepared |
| Usage | Token statistics only (monthly total, by purpose, by contact); no money, no quotas, no blocking |
| Concurrency | Global LLM gate of 3, background work may hold at most 2 slots, chat always keeps 1 |
| Backup | Export / import (merge / replace / preview) over one shared JSON contract; API keys never enter a backup |
| Appearance | Light / dark / system, accent colour, font size, bubble style, chat background, animation and haptics toggles |
| Languages | Chinese (default) and English, following the system language; no in-app language switch (see [docs/I18N.en.md](docs/I18N.en.md)) |

The two platforms are not strictly feature-for-feature equal; platform limits and remaining iOS gaps are tracked in [docs/IOS-PARITY.md](docs/IOS-PARITY.md).

## Design stance

Most decisions in this project follow one constraint: **the interface faces a contact, not a robot with dials.** Memory switches, reply pacing, daily proactive limits and monthly budgets were removed on purpose; those decisions now belong to persona fields and the model (`defer_reply`, sleep and wake thresholds, `proactiveLevel`). The stance is specified, together with the deletion list, in [docs/design/roadmap-1.9.md](docs/design/roadmap-1.9.md); it is enforced as a review constraint in [CONTRIBUTING.en.md](CONTRIBUTING.en.md), and the architectural trade-offs live in [docs/ARCHITECTURE.en.md](docs/ARCHITECTURE.en.md).

## Architecture & stack

| Layer | Android | iOS |
| --- | --- | --- |
| Language and UI | Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01), Material 3 | Swift 5.9, SwiftUI (iOS 16.0+) |
| Dependency injection / wiring | Hilt 2.52 | Manual wiring (`ios/MyFriend/App`) |
| Persistence | Room 2.6.1 (schema v10, exported to `app/schemas/`), DataStore | System `SQLite3` (schema aligned with v10), `UserDefaults` |
| Background work | WorkManager 2.10, foreground service (`specialUse`), boot restore | `BGAppRefreshTask` / `BGProcessingTask` (scheduled opportunistically) |
| Networking | OkHttp 4.12 (SSE), kotlinx.serialization 1.7.3 | `URLSession` (SSE), `Codable` |
| Secrets | Keystore-encrypted value in DataStore | Keychain (debug fallback on simulators without entitlements) |
| Minimum versions | Android 8.0 (`minSdk 26`), `targetSdk 35`, JDK 17 | iOS 16.0, Xcode 16+ |
| Tests | JUnit4 + kotlinx-coroutines-test; migration tests under `androidTest` | XCTest (local SPM package `ios/MyFriendKit`, runs on macOS) |
| Agent decisions | Shared `:agentcore` (Kotlin/JVM, the single implementation) | Contract-aligned Swift implementation; can switch to remote `agentd` |

Cross-platform contracts (three things that must stay identical):

1. **Database**: iOS `SQLiteStore` table and column names match Room's exported `10.json` byte for byte; both schemas are v10.
2. **Backup**: `BackupCodec` on both platforms and the v1 / v2 fixtures in `fixtures/backup/` are mutually readable; changing one side without the other turns the tests red.
3. **Agent core**: `spec/agent-core/cases/*.json` holds language-neutral cases. One Android test and one iOS test read the same files and feed them into their own domain implementations (sleep decision, `defer_reply`, proactive cap, heartbeat, memory decay, tool-argument clamping).

Decision logic has a single implementation: `agentcore/` (`:agentcore`, zero third-party dependencies), which both `:app` and `agentd/` depend on. `agentd/` adds an HTTP layer on top (only the JDK's `com.sun.net.httpserver`) exposing `GET /health` and four core endpoints (`sleep-decision` / `defer-plan` / `proactive-cap` / `tool-args`). Both clients go through an `AgentCoreClient` abstraction: **in-process / native by default** (zero network), switchable to the remote service via a build flag or environment variable, with automatic fallback — there is no user-facing setting.

## Repository layout

```
.
├── app/                    Android app (Kotlin · Compose · Hilt · Room · WorkManager)
│   ├── schemas/            Room-exported historical schemas (basis for migration tests)
│   └── src/main/java/com/myfriend/app/
│       ├── data/           Room entities / DAOs / migrations / repositories / DataStore / Keystore
│       ├── domain/         Pure algorithms: memory lifecycle, mood, heartbeat, link detection, vectors, backup codec…
│       ├── network/        OpenAI-compatible SSE (multimodal) + concurrency gate + usage
│       ├── service/        Foreground service, boot restore, agent loop control
│       ├── ui/             components / screens / theme
│       └── util/           Time, LaTeX, media compression and persistence, notifications
├── ios/                    iOS 16+ app
│   ├── MyFriendKit/        UI-free SPM package (Domain / Data / Net / Backup / Chat / Services), testable with swift test
│   ├── MyFriend/           SwiftUI layer (App / Views / Resources for localised strings)
│   └── MyFriend.xcodeproj  Deployment target 16.0, no third-party dependencies
├── agentcore/              Shared agent decision layer (pure Kotlin/JVM, zero deps; used by :app and :agentd)
├── agentd/                 Standalone agent core service (Kotlin/JVM, HTTP, built on :agentcore)
├── spec/agent-core/cases/  Cross-platform agent contract cases (JSON, read by both test suites)
├── harness/                Dependency-free Node.js reference server (OpenAI-compatible mock with tool and heartbeat branches)
├── fixtures/backup/        Golden backup fixtures (read-only v1 samples + v2 contract)
├── docs/                   Architecture, parity, changelog, versioning, i18n
└── gradle/                 Gradle Wrapper
```

## Build & test

**Android** (JDK 17, Android SDK Platform 35):

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # local SDK path; this file is not committed
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Migration tests (`app/src/androidTest`) need an emulator or a device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

**iOS** (Xcode 16+, deployment target iOS 16.0, zero third-party dependencies):

```bash
cd ios/MyFriendKit && swift test --disable-sandbox      # pure logic, runs on macOS in seconds
cd ios && xcodebuild -project MyFriend.xcodeproj -scheme MyFriend \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

**Local reference server** (exercises the whole pipeline without a real API key):

```bash
node harness/server.mjs        # http://127.0.0.1:8787/v1 , use model ID myfriend-reference
```

The server understands `remember` / `recall` / `set_mood` / `web_search` / `myfriend-wake` / `defer_reply` / memory extraction / memory consolidation / history compression; its `[chat]` log prints `systemChars` and the tools used this turn, which makes it easy to verify that persona and memory really arrive inside `system`.

## Documentation

The full index is [docs/README.md](docs/README.md) (in Chinese); the documents most people need:

| Document | Contents |
| --- | --- |
| [docs/ARCHITECTURE.en.md](docs/ARCHITECTURE.en.md) · [中文](docs/ARCHITECTURE.md) | Layers, data flow, cross-platform contracts, the three shapes of the agent core |
| [docs/I18N.en.md](docs/I18N.en.md) · [中文](docs/I18N.md) | Bilingual strategy, resource locations, rules for new strings |
| [docs/CHANGELOG.en.md](docs/CHANGELOG.en.md) · [中文](docs/CHANGELOG.md) | What actually changed in each version |
| [docs/IOS-PARITY.md](docs/IOS-PARITY.md) | What must match across platforms, what is platform-limited |
| [docs/audits/](docs/audits/README.md) | Audit records and accepted risks |
| [docs/design/](docs/design/roadmap-1.9.md) | Historical design records (behaviour specs from before 0.9.0) |
| [CONTRIBUTING.en.md](CONTRIBUTING.en.md) · [中文](CONTRIBUTING.md) | Environment, code style, commit conventions, release process |

## Known limitations

- **No CI**: tests, builds, migration tests and lint run locally; pull requests are reviewed against local results.
- **iOS background work is not real-time**: `BGAppRefreshTask` / `BGProcessingTask` are scheduled by the system, so background
  heartbeat and daily consolidation may run late; missed time is caught up once on foreground. This is an iOS platform
  constraint, not a setting.
- **Each search page re-walks the history blocks**: the direct cost of "no candidate cap, no offset" — the first page is
  fast, but the further you page through a long history the slower it gets; peak memory stays at "one block + one page".
- **Browsing up and down for a long time accumulates loaded blocks**: blocks stay in the window so the read range can stay
  contiguous; they are released when the conversation is reopened.
- **Blocks are never rebalanced**: after extreme deletions a block can hold only a few messages and block numbers can have
  gaps (paging looks up the block table and skips gaps).
- **Backup images are capped**: 128 MiB total, 8 MiB per image, JPEG/PNG/WebP only; anything over the limit or of an
  unsupported format is **skipped with an explicit warning** — GIF, HEIC and video never travel with a backup.

## Contributing & license

Read [CONTRIBUTING.en.md](CONTRIBUTING.en.md) ([中文](CONTRIBUTING.md)) before sending changes. Minimum requirements: both unit-test suites pass; database changes update schema, migration, migration test and fixtures together; secrets, `local.properties`, `dist/` and screenshots are never committed.

License: [Apache-2.0](LICENSE) © 2026 Heping Ge. Contributions are accepted under the same license.
