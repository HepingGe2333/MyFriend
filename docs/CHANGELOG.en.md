# Changelog

> 中文版：[CHANGELOG.md](CHANGELOG.md)

Version rules live in [VERSIONING.en.md](VERSIONING.en.md): during the beta the major version stays `0`, small changes increment the last digit, large changes increment the middle digit.

> A note on numbering: versions before `0.9.0` used the old scheme (`1.0.0`–`1.8.0`). At open-source release the then-latest `1.9.0` was renamed to `0.9.0`; earlier versions keep their old numbers in the table below for reference only.

| Version | Highlights |
| --- | --- |
| `1.0.0` | Persona, sessions, streaming replies, Markdown / LaTeX, early long-term memory |
| `1.1.0` | Persona version snapshots, preset library, proactive-wakeup proposals, image messages |
| `1.2.0` | Memory lifecycle and daily consolidation (active / dormant / archived) |
| `1.3.0` | Three-depth `recall`, memory reconstruction (recalling changes the memory) |
| `1.4.0` | Persona card and memory screening, autonomous decision protocol with time windows, reply notifications |
| `1.5.0` | Mood drift, presence (what he is doing right now) |
| `1.6.0` | Backup and restore (merge / replace / preview), usage accounting |
| `1.7.0` | Links and in-app browser, U-shaped heartbeat curve, local vector retrieval, rolling summary for long conversations |
| `1.8.0` | Removed the native harness pipeline, iOS reached parity, bidirectional backup interop |
| `0.9.0` | Removed settings from the product: one conversation per persona, read receipts, `defer_reply`, sleep and wake thresholds, structured persona fields, concurrency gate ≤3, token-only usage |
| `0.9.1` | Optional avatars on both platforms (you and him), iOS caught up with Android, memory footprint work |
| `0.10.0` | English UI and bilingual resource guards, cross-platform agent contract cases and the agentd service skeleton, bilingual documentation |

---

## 0.10.1

A small release that closes the "not done yet" items each track admitted to during 0.10.0, and fixes the
inconsistencies that parallel work introduced. Version `0.10.1`, build numbers **13**, schema stays **v10**,
backup format stays **v2**.

### Android: error copy is no longer hard-coded Chinese

- Added `domain/UserError.kt`: `UserError` (`Domain(code, arg, number)` / `Network(kind)` / `Unknown`),
  **74** `DomainErrorCode` values and `NetworkErrorKind` (including `Http(status)` and `ResponseTooLong(limit)`).
- The data and network layers only throw structured errors; the UI maps them through `ui/common/ErrorText.kt`,
  `BackupText.kt` and `RevisionReasonText.kt`. **216 new keys × 2 languages.**
- **Persisted errors are localised too**: `messages.errorMessage`, `ToolTraceItem.detail` and
  `proactive_rules.lastError` now store ASCII tokens (`@mf-err:...` / `@rev:...`) that are rendered per language;
  Chinese values already in old databases keep displaying as-is (no rewrite, no migration).
- `ChineseLiteralGuardTest` now also scans `data/` and `network/`: 378 Chinese literals surfaced at once, every
  user-visible one is structured, and the 18 remaining prompt/persistence files are whitelisted with per-file reasons.
- Backup summaries, import warnings, persona revision reasons, connection tests, HTTP status codes, web-tool errors
  and chat error bubbles all go through resources.

### Both platforms: chat pagination

- The first screen loads the latest **60** messages; scrolling to the top fetches another 60 (`createdAt` + `id`
  composite cursor) until the oldest, with a "load earlier" row.
- **Unread counts, read receipts, DND and the wake threshold still query the full table**, unaffected by the window;
  search, export, history compression and backup keep their full-table paths.
- Both platforms have contract tests pinning these properties (Android `ChatPagingContractTest` /
  `MessagePagingTest`, iOS `ChatPagingTests`) so nobody windowises them later.
- Known cost: once the entire history has been paged through, the observed range equals the full history —
  pagination lowers the peak when entering and browsing a conversation.

### iOS: the three real gaps left from the 0.10.0 report

| Gap | Now |
| --- | --- |
| Importance scoring after `web_search` | Ported from Android `WebSearchScorer` (same criteria, floor 3, temperature 0.2, usage kind `SCORING`, failures degrade to "don't remember"), plus a similarity de-duplication pass before writing (stricter than Android) |
| Memory echo cleanup | After a successful `promote`, echoes for that persona are deleted (matching Android's `deleteByPersona`); `reinforce` is untouched |
| Proactive "try now" and failure reason | Each rule can be fired once from the list (success writes a message + notification and clears `lastError`; failure stores `lastError` and shows it; scheduling is unchanged; refused while asleep) |

- `ProactiveLevel` copy in the editor now calls the domain implementation instead of duplicating it.
- Usage semantics: autonomous heartbeat openings record `AGENT`; "try now" records `CHAT` (matching Android's
  `ProactiveRepository.triggerNow`).

### Both platforms: media and stale paths

- Changing/removing an avatar, or deleting a persona, deletes the avatar file; "clear all data" removes the avatar directory.
- On backup import, an `avatarUri` pointing at a missing file is cleared and reported in import warnings (both languages).
- iOS: an avatar picked in the editor but not saved is cleaned up; Android: attachments are deleted together with
  their message, cleared context or deleted session.

### Other fixes

- Android background paths now use precise queries (`lastUserMessage` / `lastUserMessageAt` / `recentUserMessages`)
  instead of scanning a window, removing the "no user message in the last 40 rows" edge case.
- Android SSE enforces an **8 MB** per-line limit (aborts as "reply too long" instead of buffering one huge line).
- iOS compiler warnings **9 → 0**: `LlmConcurrencyGate` locks inside a synchronous critical section;
  `AgentLoop` / `MemoryConsolidator` / `MemoryExtraction` use an `AsyncMutex` whose waiters suspend instead of
  blocking a thread. Gate semantics and timeouts are preserved item by tool.
- iOS English plurals completed (backup/reset summaries, rules, times), fixing "1 personas" style edges;
  `Recall` tool-detail prefixes are localised display variants (the prompt version stays Chinese).
- Chinese still visible in tool trace titles (e.g. "联网搜索", "记住了") is not handled in this release; see Known limitations.

### Tests

| Target | Result |
| --- | --- |
| Android | **683 tests / 0 failures** (639 baseline + 44) |
| iOS | **872 tests / 0 failures** (827 baseline + 45) |
| agentd | **19 tests / 0 failures** |
| Builds | `:app:assembleDebug`, `xcodebuild build`, `:agentd:build` all succeed; iOS clean build has zero Swift warnings |

---

## 0.10.0

Version `0.10.0` (a large change: internationalisation plus a cross-platform agent contract). Build numbers `versionCode` / `CURRENT_PROJECT_VERSION` are both **12**; database schema stays **v10** and backup format stays **v2**.

### Internationalisation: English UI

- Android: added `app/src/main/res/values-en/strings.xml` (same key set as `values/strings.xml`); `android:localeConfig` declares `zh` / `en`; the language follows the system and there is no in-app switch. Base resources in `values/` are Chinese, and unmatched locales fall back to Chinese.
- iOS: added `ios/MyFriend/Resources/zh-Hans.lproj/Localizable.strings` and `en.lproj/Localizable.strings`; `CFBundleDevelopmentRegion = zh-Hans`, `CFBundleLocalizations = [zh-Hans, en]`, and both `InfoPlist.strings` localise the local-network permission copy, all selected automatically by `Bundle.main`. There is no in-app switch here either.
- Model language: both platforms append a language-directive section to the end of the system prompt, instructing the model to answer in the same language the user writes in. Both provide Chinese and English templates following the UI language (Android: `prompt_language_directive` under `values{,-en}`; iOS: the same key in `Localizable.strings`). The wording is identical; only the placeholder syntax differs (`%1$s` / `%1$@`). Apart from this section, prompt scaffolding, logs and code comments are not translated.
- Resource guards: Android `StringResourceParityTest` (key sets and placeholders), `ChineseLiteralGuardTest` (no hard-coded Chinese in the UI layer), `LanguageDirectiveTest` (language directive appended to the end of the prompt); iOS `LocalizationResourceTests`, `UIChineseLiteralGuardTests`.
- String counts: Android `values` / `values-en` hold **876** entries each (856 `<string>` + 20 `<plurals>`), plus 12 `seed.xml` entries per language, a locale-specific `usage_tokens_compact_divisor`, and date/time patterns as resources. iOS holds **765** entries in each `Localizable.strings` (about 637 call sites in the app layer plus 233 kit keys) and one entry per `InfoPlist.strings`.
- Seed personas follow the UI language: Chinese installs get 林深 / 阿岚 / 墨; English installs get Elias / Finn / Ansel on Android and Rowan / Juno / Ellis on iOS. This only affects first installs; existing personas are never migrated or renamed.

### Agent core contract

- Added `spec/agent-core/cases/*.json`: **94** language-neutral cases (sleep decision 16, `defer_reply` 9, proactive cap 14, heartbeat 17, memory decay 22, tool-argument clamping 16). Each case has `name` / `input` / `expect` and an optional `why`; floating-point assertions carry a `tolerance`.
- Android test `app/src/test/java/com/myfriend/app/spec/AgentCoreSpecTest.kt` and iOS test `ios/MyFriendKit/Tests/MyFriendKitTests/Spec/AgentCoreSpecTests.swift` read the **same** JSON files and feed them into each platform's existing domain implementation. Changing behaviour on one side alone turns the other side red.

### Embedded service skeleton: agentd

- Added a standalone Gradle JVM module `agentd/` (Kotlin/JVM, only the JDK's `com.sun.net.httpserver`, zero third-party dependencies) that compiles Android's pure domain sources directly (the same source files, not a copy).
- Exposes `GET /health` plus four core endpoints (`sleep-decision` / `defer-plan` / `proactive-cap` / `tool-args`); it can run standalone or as an embedded service on a local port. It does not touch the database or call an LLM, and has no authentication or state persistence.
- Verified: `:agentd:test` runs **19** tests with no failures (18 handler tests plus 1 real-port integration test). Both `agentd/build/install/agentd/bin/agentd --port 8788` and `java -jar agentd-all.jar` start, and `/health` plus the four endpoints answer as specified (errors are always `{"error":{"message","code"}}` with 4xx/5xx).
- **Not wired this round**: the Android client does not start agentd, and the iOS side has no code calling it. Both apps keep their existing in-process implementation.

### Divergences the contract uncovered

Writing the cases revealed ten places where the two implementations quietly disagreed. The rule: the spec in
`docs/ROADMAP-1.9.md` wins; where both were reasonable, Android was treated as the reference and Swift was aligned.

| Item | Before | After |
| --- | --- | --- |
| Read receipt while in do-not-disturb | Android wrote it, iOS did not | Neither writes it (spec §6), decided by `SleepPolicy.Decision.marksRead` |
| Mood intensity after being woken | iOS forced 1, Android subtracted 1 | Both subtract 1 |
| Dirty equal sleep/wake minute | Android fell back to 23:30–08:00, iOS treated it as "never sleeps" | Both fall back to the defaults |
| `memoryRetention` affecting decay | Android ignored it, iOS applied a factor | Both apply it (×1.6 / 1.3 / 1.0 / 0.75 / 0.5) |
| `proactiveLevel` affecting the heartbeat | Android multiplied by 0.7–1.3, iOS did not | Both multiply; iOS gained a `ProactiveLevel` type |
| `defer_reply` clamping | iOS had three copies | Both use pure functions (`DeferReply` / `ToolArgClamp`) covered by the cases |
| Normalising dirty mood data | Android could persist out-of-range labels/intensity | Both normalise and clamp to 1–5 |

### Tests

| Target | Command | Result |
| --- | --- | --- |
| Android | `./gradlew :app:testDebugUnitTest` | **639 tests / 47 classes / 0 failures** |
| iOS | `cd ios/MyFriendKit && swift test --disable-sandbox` | **827 tests / 0 failures** |
| agentd | `./gradlew :agentd:test` | **19 tests / 0 failures** |
| Builds | `:app:assembleDebug`, `xcodebuild … build`, `:agentd:build` | all succeed |

### Documentation

- `README` and `CONTRIBUTING` are now bilingual (`README.md` / `README.en.md`, `CONTRIBUTING.md` / `CONTRIBUTING.en.md`) and target contributors: project status, architecture and stack, repository layout, build and test, known limitations.
- Added English versions of `docs/VERSIONING` and `docs/CHANGELOG`; added `docs/I18N.md` / `docs/I18N.en.md` and `docs/ARCHITECTURE.md` / `docs/ARCHITECTURE.en.md`.
- Documentation no longer embeds or links screenshots; the repository does not publish APKs or screenshots.

### Version and engineering

| Item | Value |
| --- | --- |
| Version | `0.10.0` |
| Build numbers | Android `versionCode` = iOS `CURRENT_PROJECT_VERSION` = `12` |
| Database schema | v10 (unchanged this round) |
| Backup format | v2 (unchanged this round) |
| Android unit tests | 639 |
| iOS unit tests | 827 |

---

## 0.9.1

The first minor release of the beta. Three things: **you and he can both have a face**, **iOS caught up with Android**, **less memory**.

### Avatars: he can have one, and so can you

- **He (the persona)**: while editing a persona you could pick one from the photo library; it was sampled and compressed into the app's private directory (long edge 512px, JPEG 85, ≤400KB) and rendered consistently across every screen (home contacts, chat, persona list, settings). "Restore default" went back to a colour block with the first character at any time. A missing file (new device, cleanup) did not crash anything; it simply fell back to the default.
- **You (yourself)**: settings gained a quiet "My avatar" row; after picking an image it was shown **next to your message bubbles**. It was not stored in the database (DataStore on Android, UserDefaults on iOS), so there was **no schema migration**.
- Both platforms agree: avatar images **do not enter backups** (only the path is stored); after switching phones you pick one again. The export/import screen carried this reminder in Chinese.

### iOS caught up with Android

| Capability | Before 0.9.1 | Now |
| --- | --- | --- |
| Image messages | Only the text "（发来一张图片）" | Pick from the library → long edge 1600px / JPEG 0.85 / ≤800KB → thumbnail bubble → full-screen viewer (zoom, paging) → still rendered after restart; a missing file shows "图片已不在本机" |
| In-chat tool execution | Always answered "这个工具用不了" | `think` / `web_search` / `web_fetch` / `remember` / `update_persona` / `set_quiet_hours` (including `dnd`) / `set_mood` / `recall` / `defer_reply`; argument clamping and result text match Android; failures carry a Chinese reason and never pretend to succeed |
| Web tools | None | `Net/WebToolsClient` (DeepSeek `/v1/tools/search`, `/v1/tools/fetch`); search really hit the network |
| Proactive rules | Proposals could only be displayed, not persisted | Persisted (daily / interval, `nextTriggerAt` in local time, duplicates updated rather than inserted), plus a new **"Proactive wakeups" screen**: list, toggle, approximate next trigger, long-press delete, manual creation |
| History compression | Algorithm only, no orchestration | Latest 24 messages verbatim, a chunk compressed once 12 messages or 24000 characters accumulated, at most 40 per pass, no original message deleted; with rejection guards for "tool-protocol shape / refusal / placeholder" output and a startup self-check repair |
| Persona card | Read-only | Structured editing (identity / traits / bond…), persisted and injected into the prompt, empty card falls back to the hand-written `prompt` |
| Notifications and background | None | Local notifications (no body on the lock screen, tap opens the matching chat) + `BGAppRefreshTask` / `BGProcessingTask` registration + catch-up for missed time when returning to the foreground |
| Two legacy tables | `memory_echoes` / `agent_ticks` were missing from the schema list and untested | Added to `Schema.allTables` (15 tables) with a migration test for "old database missing tables creates them on open"; `remember` echoes survive a process restart |

### Memory footprint

- **Android**: Coil became a lazily initialised singleton (memory cache `min(available memory×12%, 48MB)`, disk 128MB, `respectCacheHeaders(false)`); avatars and chat images are always decoded at display size (no full-size resident bitmaps); six background paths (agent loop / idle browsing / proactive wakeups / memory consolidation / memory reads and writes / persona evolution) changed from "read the whole history" to "latest 40 / 120 messages + windowed tools".
- **iOS**: images decoded at display size (ImageIO thumbnails) + `NSCache` (`min(physical memory×10%, 48MB)`) + purge on memory warnings; data URLs decoded only for the **latest 2** image messages; the attachment directory is marked as excluded from full-device iCloud backup.
- **Measured numbers (no glossing over)**:
  - iOS: a single chat image in a bubble added only about **5.1MB**; the full-screen viewer was the only large cost at **+21.3MB** (released on exit, cache bounded by the 48MB cap).
  - Android: in the two specified scenarios (home 30s / chat 30s) PSS changed by **<1%, i.e. noise level** — not because nothing changed, but because in those scenarios 58MB of anonymous dex and graphics buffers dominate, while the app's own Java heap is only about 11MB. The real gains are on the "long history / large images" paths (background reads went from thousands of messages to 40; images decode at display size), and those two scenarios never trigger them. **Known debt**: the chat screen still loads the whole message list at once, the largest remaining cost in long conversations, deferred to the next version as pagination.

### Version and engineering

- Version `0.9.1`, Android `versionCode 11`, iOS `CURRENT_PROJECT_VERSION 11` (equal build numbers, schema still v10).
- The About screen and the backup's `appVersion` derive from the build number instead of hand-written version strings.
- Open-source preparation: Apache-2.0, Gradle Wrapper, `.gitignore` (toolchain and build artifacts excluded), `CONTRIBUTING.md`, `docs/VERSIONING.md`.

---

## 0.9.0 (formerly 1.9.0)

**This release did one thing: remove "settings" from the product and turn him back into a person.**

Before `0.9.0` the UI exposed memory switches, reply pacing, a monthly budget and a daily proactive cap, which made the product look like a robot with dials rather than a contact. This release removed all of them.

- **The home screen became a contact list**: one row per person — avatar, name, last message, time, unread. No "recent conversations" and no "new conversation".
- **One conversation per persona**: on upgrade, older multi-session data merged into "keep the newest one"; the rest were deleted together with their messages.
- **He gained a more concrete shape**: traits, likes, behaviours, backstory, quirks, plus proactiveness, memory retention, extraction interval, whether he sleeps on his own, his sleep window and the wake threshold.
- **Whether to reply is his decision**: he can read your message, show "read", and then choose not to reply this turn (`defer_reply`). The UI never shows "typing…" to fake it.
- **He sleeps too**: messages wait while he sleeps; whether to enable do-not-disturb before sleeping is his decision; without DND, enough consecutive messages wake him, and his mood softens on waking.
- **Settings slimmed down**: only appearance, chat background, background service and notifications, API configuration, usage, backup and restore, data clearing, about.
- **At most three things at once, chat always has a slot**: global concurrency gate of 3 (background work may hold at most 2).
- **Backup moved to v2**: backups from 1.8 and earlier still load.

---

## Earlier versions

Design documents from the old numbering era are still under `docs/`: [ROADMAP-1.2](ROADMAP-1.2.md), [ROADMAP-1.3](ROADMAP-1.3.md), [ROADMAP-1.4](ROADMAP-1.4.md), [ROADMAP-1.5](ROADMAP-1.5.md), [ROADMAP-1.6](ROADMAP-1.6.md), [ROADMAP-1.7](ROADMAP-1.7.md), [ROADMAP-1.9](ROADMAP-1.9.md) (= 0.9.0).
