# Changelog

> 中文版：[CHANGELOG.md](CHANGELOG.md)

Version rules live in [CONTRIBUTING.en.md](../CONTRIBUTING.en.md): during the beta the major version stays `0`, small changes increment the last digit, large changes increment the middle digit.

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

## Project renamed to YetAnotherPal (Yap)

The project is now **YetAnotherPal**, abbreviated **Yap**. The repository is
<https://github.com/HepingGe2333/YetAnotherPal>; old links redirect there on GitHub.

- Android application ID is now `com.yap.app`; the iOS bundle ID is `com.yap.app.ios`.
- The iOS Swift module is now `YapKit`, and the Xcode project is `YetAnotherPal.xcodeproj`.
- The backup format string **`myfriend.backup` is unchanged** so historical backups remain readable. The
  format name is a protocol identifier and is not renamed with the product.
- For searchability, historical entries below use the current product, package and path names. The only
  retained legacy string is the compatibility format name `myfriend.backup`.

## 0.12.1

Patch release: **automatic two-way synchronisation when switching to a remote server**, **new clients pull their history
automatically**, **user avatar upload**, and **streamed server backups with bounded memory**.

- **Two-way sync**: on first connection to a server the client decides one of four cases (both empty / server empty →
  push local / client empty → pull server / both non-empty → merge with server winning conflicts). Personas, presets,
  memories, settings, sessions and messages (200 per batch) are pushed; a new client walks every block backwards to
  pull the full history while keeping unread/read state and timestamps. After that, incremental sync every 5 minutes
  plus write-through.
- **History import API**: `POST /v1/sessions/{id}/messages/import` (≤200 per batch, idempotent by id, keeps
  createdAt/readAt/attachments, never calls the LLM, leaves unread untouched).
- **User avatar**: pick an image → compress (≤512 px/400 KB) → `POST /v1/media?kind=USER_AVATAR` → `userAvatarUri`
  (server settings whitelist, legacy `myAvatarUri` accepted); rendered with auth and a local fallback; local mode unchanged.
- **Server backup memory**: export is chunked/streamed (k-way message merge, chunked base64 media, no full JSON tree);
  import spools the body to a temp file and parses incrementally (media decoded per entry). The test round-trips 10k
  messages + 50 images over real HTTP under `-Xmx256m`.
- **Version**: 0.12.0 → 0.12.1, build 18 → 19; schema stays v14, backup v5.

## 0.12.0

A large release: **the shared decision layer moves to Kotlin Multiplatform (iOS reuses the same Kotlin)**,
**history is stored in blocks**, **search loses its candidate cap**, **backup v3 carries images and avatars** — plus a
cleanup of the README's known-limitations list. Version `0.12.0`, build numbers **18**; schema **v11**, backup **v3**.
(Developed on `dev/0.12.0`; not merged into `main`, no release tag.)

### Shared decision layer: Kotlin Multiplatform

- `agentcore/` moved from `kotlin("jvm")` to `kotlin("multiplatform")`: `jvm()` + `iosX64()` + `iosArm64()` +
  `iosSimulatorArm64()`, producing the static framework **`AgentCoreKit`**. `commonMain` holds the shared decisions;
  `jvmMain` keeps only the five files that genuinely need `java.*` (background lock, session generation, file-path guard,
  bounded read, locale name), and `PlatformTime` is an `expect`/`actual`.
- `WebUrlPolicy` depended on `java.net.URI`; it was **rewritten as pure string parsing** to enter `commonMain` (all
  security semantics and 12 contract tests preserved). Little-endian byte handling, calendar arithmetic and the min-heap
  in `LocalEmbedding`/`VectorMath` were also made platform-neutral.
- **The iOS app's four agent decisions now execute the same Kotlin code** (`KotlinAgentCoreClient` → `InProcessAgentCore`);
  the Swift implementation remains only as the macOS `swift test` fallback and as the other half of the parity test.
  A simulator run showed Kotlin agreeing with all 189 assertions of `spec/agent-core`, and the app logs `agent core: kotlin`.
- Build: `ios/scripts/build-agentcore-framework.sh` produces `ios/.build/agentcore/AgentCoreKit.xcframework`; the Xcode
  project gained framework search paths and `-framework AgentCoreKit` (static, Do Not Embed), plus a Run Script that
  builds it when missing.

### History storage: blocks with two-way paging

- Schema **v11**: `messages.blockIndex` plus
  `message_blocks(sessionId, blockIndex, first…, last…, messageCount)`, identical on both platforms (iOS has
  `SchemaParityTests` comparing against Android's exported `11.json`).
- `BLOCK_SIZE = 200`; writes go through one block-maintenance path (new block when full, anchored insert, boundary
  recomputation on delete, deleted empty block leaving a numbering gap, no rebalancing), covering send/reply/interrupt/
  failure/regenerate/edit/delete/clear/delete-session/delete-persona/backup-import/proactive messages.
- **A page is a block**: the first screen reads only the newest block, scrolling up/down loads one block at a time
  (looking up the real neighbour when numbering has gaps), and no offset is ever used; unread/receipts/DND still query
  the full table.
- The v10→v11 migration backfills with `ROW_NUMBER()/200` and builds the block rows while streaming; both platforms have
  tests for "no data lost, correct boundaries, idempotent, empty database, orphan cleanup".

### Search: no candidate cap

- The old "`LIKE` pre-filter + 500 candidates" cap is gone. Search walks **block by block**: enumerate the (persona/time
  filtered) blocks → load one block into memory → rank with `SearchRanking` → page with a **keyset cursor**. Peak memory
  ≈ one block + one page + frontier, independent of history length.
- Cases with more than 500 hits are covered: 620 hits paged completely, a hit in the oldest block is never lost, new
  messages during paging neither duplicate nor reorder; "one query reads exactly one block" is asserted structurally.
- The cost is documented in the README: every page re-walks the blocks, so longer histories get slower — the direct
  consequence of "no cap, no offset".

### Backup v3: images and avatars travel with the backup

- New top-level `media[]` (`id/kind/personaId/fileName/mime/bytes/data`, standard base64): persona avatars, the user's
  own avatar, preset avatars and chat images all travel; `avatarUri` and `attachmentsJson` store bare file names and are
  rewritten to freshly-named local files on import.
- Limits: 128 MiB total, 8 MiB per image, JPEG/PNG/WebP only; oversized or corrupt entries are skipped with bilingual
  warnings; import cap raised to 256 MiB.
- v1/v2 backups remain readable (no `media` → old warnings); `fixtures/backup/` gained v3 golden samples read both ways.
- Media is written before the database transaction and rolled back (files deleted) if the transaction fails; file names
  and directories go through the ownership/traversal guards.

### Documentation and the limitations list

- The README's "known limitations" were re-audited: entries that are **not** limitations (no build artefacts in the repo,
  token-statistics only, remote mode off by default, no cleartext in release builds, simulator Keychain) moved to the
  design-stance section; entries this release fixed (search cap, paging cost, images not backed up, iOS second
  implementation) were removed; genuine limits kept and written precisely (no CI, iOS background scheduling, per-page
  block rescan, block accumulation while browsing both ways, no rebalancing, backup image limits).
- `docs/ARCHITECTURE` gained "History storage: blocks" and a rewritten "Search" section and now describes the KMP agent
  core; `docs/README` indexes `spec/messages-blocks`; `CONTRIBUTING` gained the "build AgentCoreKit before the iOS build"
  step and its data/backup checklists now say v11 / v3.

### Tests and builds

| Target | Result |
| --- | --- |
| Android | **1044 tests / 0 failures** (plus 28 instrumented: v10→v11 migration, block maintenance) |
| `:agentcore` (KMP jvmTest) | **60 tests / 0 failures** |
| `:agentd` | **34 tests / 0 failures** |
| iOS | **1263 tests / 0 failures** (Debug/Release builds, zero Swift warnings) |
| Builds | `assembleDebug/Release`, `lintDebug`, `:agentd:fatJar`, iOS Debug/Release, `AgentCoreKit.xcframework` all succeed |

---

### 0.12 follow-up 1 (no version bump): CI gates, third-party search providers, schema v12 / backup v4, media compression, UI tests and benchmarks

A large release: **CI gates**, **third-party search APIs for `web_search` (schema v12 / backup v4)**, **media compression
on import and storage**, **UI behaviour tests on both platforms**, **large-history benchmarks**, **agentd rate limiting
and TLS deployment notes**, and the iOS Swift second implementation narrowed to "macOS tests only".
No version bump yet (not approved): both platforms stay at `0.12.0` / build **18**; schema **v12**, backup **v4**. Still on `dev/0.12.0`; `main` is not merged and no tag is created.

### CI (GitHub Actions)

- New `.github/workflows/ci.yml`:
  - **Linux**: JDK 17 + Gradle, running `:app:testDebugUnitTest`, `:agentcore:test`, `:agentd:test`, `assembleDebug`,
    `:agentd:build`, `lintDebug`, uploading test and lint reports even on failure;
  - **macOS**: builds `AgentCoreKit.xcframework` first, then `swift test --disable-sandbox`, then
    `xcodebuild build-for-testing` (skipping UI tests); caches `~/.gradle`, `~/.konan` and the SwiftPM build directory;
    `CODE_SIGNING_ALLOWED=NO`;
  - triggers on `push(main, dev/**)` and `pull_request(main)`, `concurrency` cancels stale runs, plus a `ci-ok` summary
    job intended as the required check.
- `CONTRIBUTING` explains what CI runs and how to reproduce it locally; "no CI" is gone from the README limitations.

### `web_search`: third-party search APIs

- New normative `spec/web-search/README.md`: `api_configs.webToolsProvider` (`MESSAGEAPI` / `TAVILY` / `BRAVE` / `SERPER` /
  `GENERIC`), default base URLs, per-provider request/response shapes, normalised results, `web_fetch` behaviour, errors
  and limits.
- Both platforms implement the same override semantics: `DEEPSEEK` keeps the server-side search/fetch; other providers
  call their own APIs; `web_fetch` is fetched client-side for non-DeepSeek providers (10 s, ≤2 MiB, tag stripping,
  ≤8000 chars). Missing keys fail loudly, 401/403/429/5xx carry the provider name, raw bodies are truncated into logs,
  and every URL passes `WebUrlPolicy` before any request.
- Settings → API config gained a "search service" selector, per-provider base URL placeholders/hints, and a key reusing the
  existing `webToolsApiKeyCipher` (encrypted locally, never in a backup); `GENERIC` requires a base URL with an optional key.
- **Schema v12**: `api_configs.webToolsProvider` (default `DEEPSEEK`), migrations and schema-parity tests on both platforms.
- **Backup v4**: `apiConfigs[].webToolsProvider` (default `DEEPSEEK`, always written), keys still excluded; v1–v3 remain
  readable; `fixtures/backup/{android,ios}-export-v4.json` generated and read both ways.
- `harness/server.mjs` gained mock branches for every provider for local integration.

### Media compression (import and storage)

- Backup import and picking share one compressor: avatars ≤512 px / ≤400 KB, chat images ≤1600 px / ≤800 KB, JPEG quality
  ladder 0.85→0.50, transparent images flattened onto white; corrupt or over-limit images are skipped per item with a
  warning instead of failing the whole import.
- Content-addressed de-duplication: file names carry the first 16 hex chars of SHA-256 and identical content is stored
  once (no new table); avatars keep one file per reference to avoid deleting a shared file when changing a photo.
- The existing 8 MiB per image / 128 MiB total input gates are unchanged; images from v3 backups still import.

### Tests and benchmarks

- **UI behaviour tests** (new):
  - Android: six Compose behaviour tests under `app/src/androidTest/.../ui/` (first screen reads one block, load older /
    newer, jump-to-message highlight, send failure keeps the draft, clear + wipe behaviour), plus `HiltTestRunner`,
    Compose test dependencies, and a debug-only manifest that no longer registers `BootReceiver` (the install broadcast
    used to crash the process before Hilt came up);
  - iOS: six `YetAnotherPalTests/ChatUiBehaviorTests` with the same scope, executed on the simulator (this sandbox uses
    `simctl spawn xctest` because `xcodebuild test` needs a pty).
- **Large-history benchmarks**: one per platform (100k-message migration, search pages 1/10/100, backup with 500 images
  peak memory); numbers and conclusions live in `docs/performance.md`.
- Drive-by fix: a flaky agentd socket test under parallel builds (wait for EOF → accept timeout once data arrived).

### Platform and security

- **agentd rate limiting**: token bucket per source IP (60/min, burst 30 by default; `--rate-limit` / `--rate-burst` / env,
  0 disables), 429 + `Retry-After` when exceeded; `/health` gets its own bucket so probes stay usable; rate limiting runs
  before authentication and before the body is read.
- **TLS deployment notes**: agentd serves plain HTTP only; the README gives minimal Caddy/Nginx reverse-proxy configs that
  terminate TLS, explains that `X-Forwarded-For` is deliberately not trusted, and recommends keeping the token on.
- **iOS Swift second implementation narrowed**: `NativeAgentCoreClient` is fully wrapped in `#if !os(iOS)`; the iOS app
  now runs Kotlin only (the built binary no longer contains the symbol), Swift remains for macOS `swift test`, and a
  source guard prevents regressions.
- **Version guards pinned to exact values**: `versionCode = 19` / `versionName = 0.13.0` / `CURRENT_PROJECT_VERSION = 19` /
  `MARKETING_VERSION = 0.13.0` (Debug and Release), with one exact assertion and one structural assertion per platform.
- **Android now matches iOS on "delete all data reseeds personas"**: Android used to keep the "already seeded" marker and
  left an empty home screen; it now reseeds three personas in the current language
  (`resetAll(preserveSeedMarker = false)` + `seedIfNeeded()`).

### Tests and builds

| Target | Result |
| --- | --- |
| Android | **1085 unit tests / 97 classes / 0 failures** (media reference counting +13); instrumented tests compile, device batch deferred |
| `:agentcore` (KMP jvmTest) | **60 tests / 0 failures** |
| `:agentd` | **53 tests / 0 failures** |
| iOS | **1315 tests / 0 failures** (2 benchmark tests skipped by default; Debug/Release, zero Swift warnings) |
| Builds | `assembleDebug/Release`, `lintDebug`, `:agentd:build`, iOS Debug/Release, `AgentCoreKit.xcframework` all succeed |

---

### 0.12 follow-up 2 (no version bump): provider renamed to MessageAPI, live API checks, media reference counting

- **`DEEPSEEK` → `MESSAGEAPI`**: the provider targets the `/v1/tools/search` + `/v1/tools/fetch` tools API, which any
  compatible vendor can expose, so it was renamed. **Old values are read, new values are written**: legacy `DEEPSEEK`
  (any case/whitespace) is normalised on read, and every write stores `MESSAGEAPI`; the DB column default stays
  `'DEEPSEEK'` because changing it would break already-installed v12 databases.
- **Live API checks**: `docs/web-search-live-check.md` records real calls against MessageAPI (DeepSeek official) and
  Tavily (status codes, latency, response shapes, 401 behaviour); Brave/Serper were not reachable from this machine.
- **Media reference counting** (fixes deleting a shared image after de-duplication): both platforms gained a
  `MediaReferences` query plus a single `deleteIfUnreferenced` guard; every file deletion now runs
  "delete DB rows → check references → delete only when zero". No new tables, no schema bump.
- **Local search write-up**: `docs/local-search.md` documents the framework (plain Kotlin + SQLite block scanning, no
  FTS or third-party search engine), the capability matrix, the explicit non-goals and the upgrade path.



### 0.12 follow-up 3 (no version bump): server-authoritative P1 — storage/state/SSE/search/proactive + client transports

The goal is to turn the client into "UI and interaction only" and move state and logic to `agentd`, so iOS/Android can
keep running without any background process (contract: `docs/design/server-authoritative.md`).

- **Server storage and API**: `--data` file store (atomic writes, single write lock, block maintenance, event cursor,
  0600 secrets), `GET /v1/state`, `/v1/sessions`, two-way block paging, `POST /v1/sessions/{id}/messages` (SSE, idempotent
  `clientMessageId`, generation continues and persists after the client disconnects), `GET /v1/events` (long polling).
- **Server-side search**: `MESSAGEAPI` / `TAVILY` / `SEARXNG` (self-hosted, no key) / `BING` plus
  `ZHIHU` / `TOUTIAO` / `QUORA` site-scoped sources; `POST /v1/tools/web-search`, `/v1/tools/web-fetch`; keys live on the
  server (`YAP_AGENTD_SEARCH_*`), never sent by clients.
- **Server-side proactive**: `ProactiveEngine` (sleep/DND/cooldown/daily cap/probability, following `spec/agent-core`),
  a timer independent of client connections, `POST /v1/proactive/tick` (debug/test entry point).
- **Client transports**: Android `data/remote/` and iOS `Net/Server/` with `ServerConfig` (https required, cleartext only
  for local hosts in debug), `ServerApi`, incremental SSE parsing, cancel-closes-connection and state mappers; **UI not
  wired yet** (P1b).
- **Tests**: full batch — Android **1139 / 101 classes / 0 failures** (+54), `:agentcore` 60, `:agentd` **244** (+17 route cases), iOS **1386 / 0 failures** (+71, 2 benchmark tests skipped by default); Debug/Release and lint all pass.


### 0.12 follow-up 4 (no version bump): persona guidance — world presets, speech-style learning, activity tool, prompt rewrite

This round upgrades "does the model sound like this person" from a single persona blurb to
"identity + world + current state + speech style + relationship + boundaries", validated against the DeepSeek official
API from this machine (contract and live notes: `spec/persona-guidance/README.md`).

- **World preset**: the world/city the persona lives in, era, schedule, relationships and obligations; schema v13
  (`personas` and `persona_presets` gain `worldJson`), backup v5; the preset editor gained a "world" card
  (hand-written or AI-expanded).
- **Speech-style learning**: paste a WeChat/QQ transcript → the LLM summarises `tone` / `phrases` / `habits` / `avoid`
  (prompt verified to return valid JSON); without an API key or on failure a local heuristic fallback is used.
  **The raw transcript is never stored, logged or backed up.**
- **`set_activity` tool**: the persona can declare what it is doing for the next while (not sleep):
  `kind/activity/minutes/availability`; `BUSY` suppresses proactive messages, shortens replies and states when it is free
  again; `LIMITED` lowers the probability; it expires automatically.
- **Prompt rewrite**: `# who you are → # the world you live in → # your state right now → # how you speak →
  # your relationship → # boundaries`; first person, no narration, no persona summary, no deciding for the user,
  at most one signature phrase per reply. Both `PromptBuilder`s updated.
- **Backup v5**: `personas[]` carries world/style/activity, `presets[]` carries world/style; v1–v4 still readable.
- Leftovers fixed: agentd proactive counters are now persisted (survive restarts, reset across local midnight, concurrent ticks fire once), both platforms serialise "write file + return reference" with "check references + delete file" behind one file lock (media TOCTOU), and Android proactive rules now respect the `BUSY` gate.
- Tests: full batch — Android **1210 / 111 classes / 0 failures**, `:agentcore` 60, `:agentd` **249**, iOS **1453 (2 skipped) / 0 failures**; Debug/Release and lint all pass.


### 0.12 follow-up 5 (no version bump): visual world editor, persona translation, server mode M3/M4 and CRUD

- **Visual world editor**: schedule/relationships are now row-based editors (time pickers, relation dropdowns, intimacy),
  obligations are list items, and a free-text "other background" (`notes`, ≤1200 chars) captures unstructured world
  data; the editor is integrated into the persona preset screen (Android preset/persona pages share it; iOS gained a
  preset list and editor).
- **Persona prompt localisation**: schema v14 (`personas.translationJson/translationLang`); before opening a chat or
  generating a proactive message the app asks the LLM to translate the persona and world into the UI language; cache
  invalidation uses a canonical-JSON SHA-256 shared by both platforms (`hash-cases.json`), `phrases` are never
  translated; missing key/failure/timeout falls back to the original text and the feature can be turned off.
- **Single prompt scaffold**: `:agentcore` gained `PersonaPromptScaffold` (byte-identical to
  `spec/persona-guidance/scaffold.json`); both `PromptBuilder`s use the same Chinese wording with contract tests, plus a
  new `world_name` line.
- **Server BUSY gate**: `agentd` personas carry `activityJson`, `POST /v1/personas/{id}/activity`; the proactive engine
  skips `BUSY`, scales `LIMITED` by 0.35 and recovers after expiry; `/v1/proactive/tick` returns `skipped` reasons;
  counters and activity are persisted.
- **Server CRUD (M5 server side)**: session create/mark-read, persona/memory/preset/settings CRUD, backup export/import
  (v5, no secrets), `/v1/push/register` with a pluggable `PushSender` (Noop by default).
- **Client server mode (M3/M4)**: both platforms configure server URL/token (Keystore / Keychain), test the connection,
  sync state incrementally into the local cache (timestamp cursor + event cursor), chat over the server SSE stream
  (idempotent `clientMessageId`, offline outbox), proactive messages arrive through the event stream and become local
  notifications, mood/presence come from the server; local mode remains as the offline form.
- **Tests**: Android **1366 / 126 classes / 0 failures**, `:agentcore` **90**, `:agentd` **329**, iOS **1588 (2 skipped) / 0 failures**; Debug/Release and lint all pass.



### 0.12 follow-up 6 (no version bump): M5 client writes, media upload, iOS truncation removed, tests completed

- **M5 client**: in server mode, persona/memory/preset/settings/backup writes all go through `/v1/*` and only after the
  server succeeds is the local cache updated; failures surface explicitly instead of silently falling back to local
  writes. Local (offline) mode is unchanged.
- **Media**: `agentd` gained `POST /v1/media` (raw bytes, type/size validation, sha256 de-duplication),
  `GET /v1/media/{id}` (ETag/304) and `DELETE` (409 while referenced); backup v5 carries media (8 MiB each, 128 MiB
  total, oversized entries skipped and counted). In server mode both clients **compress before upload** (avatars
  512 px/400 KB, chat images 1600 px/800 KB) and render through an authenticated media cache; local mode keeps local files.
- **iOS truncation removed**: world/speech/persona fields are no longer truncated while parsing, storing or hashing
  (only prompt rendering trims), matching Android; Android also stopped truncating during parsing. Shared
  `hash-cases.json` and long-text round-trip tests cover it.
- **M6 push**: kept as "configured by the server deployer" — the server provides token registration and a pluggable
  `PushSender` (Noop by default); APNs/FCM protocol, certificates and delivery stay out of the repository. Without push,
  the client falls back to foreground event sync.
- **Tests completed**: 56 Android instrumented tests on the emulator (v13→v14 migration, UI behaviour, media
  compression); 16 iOS App-hosted tests (10 Kotlin parity + 6 UI behaviour) run via `simctl spawn xctest`.
- **Tests**: Android 1412 / 132 classes / 0 failures + 56 instrumented, `:agentcore` 90, `:agentd` 358, iOS 1625
  (2 skipped) + 16 App-hosted; Debug/Release and lint all pass.

### 0.12 follow-up 7 (no version bump): fix for "read but never answered" at night after a fresh install

- **Root cause**: seed personas (Android `SeedPersonas.build`, iOS `SeedPersonas.build`) never wrote the sleep fields,
  so they picked up the entity default `sleepEnabled = true`. Combined with `SleepPolicy`'s default window
  **23:30–08:00** and a wake threshold of **6**, the first one to five messages after 23:30 were marked read and left
  unanswered (`GenerationEvent.Silent`). Renaming the project to Yap (new applicationId / bundleId) meant a fresh
  install, so every previously customised setting was gone — which is what turned this into "he never reacts".
  A simulator check showed the row was `Elias|1|||6` (`sleepEnabled=1`, both schedule columns NULL, threshold 6).
- **Asleep is off by default**: seed personas (both platforms) set `sleepEnabled = false` explicitly, and the new-persona
  form defaults to `false` too (Android `PersonaForm`, iOS `PersonaEditorScreen`). The data-class defaults in the entity
  and backup models stay `true` so existing decoding and migration paths are untouched — sleep is opt-in, switched on in
  the persona editor.
- **One-time migration**: existing personas that never had sleep configured are turned off. Condition:
  `sleepEnabled = 1` (a NULL column in old iOS databases counts as 1)
  `AND quietSleepMinutes IS NULL AND quietWakeMinutes IS NULL AND version = 1`. User-set schedules and edited personas
  (`version > 1`) are left alone, and `updatedAt` is not touched so home-screen ordering does not churn. Android runs it
  from `PersonaRepositoryInitializer.seedIfNeeded()` (`PersonaDao.disableSleepFor`), iOS from `AppEnvironment` at launch
  (`SleepDefaultsMigration.runIfNeeded`). Both use the one-shot marker `sleep_defaults_migrated_v1`
  (DataStore / UserDefaults) and write one debug log line (counts only, no user content).
- **Wake threshold 6 → 3 by default**: Android `PersonaEntity.wakeThreshold` and iOS
  `PersonaFieldLimits.wakeThresholdDefault`. New and seed personas only; the migration never rewrites existing values,
  and the column's `DEFAULT 6` stays (changing it would need a database migration).
- **Readability**: when a turn was silenced by sleep, the chat screen shows one line **above the composer**:
  "He's asleep (up around 08:00). A few messages in a row will wake him." It is not a message bubble, it is not written
  to the database, it stays hidden for `defer_reply` and for do-not-disturb (which never wakes him), and it disappears
  once he replies or wakes up. The existing presence subtitle ("Asleep · up at 08:00") stays — the two say different
  things.
- **Tests**: Android adds `SeedPersonasTest`, `PersonaEntityDefaultsTest`, `PersonaFormDefaultsTest`,
  `SleepDefaultsMigrationTest` (one-shot / conditions / user schedules / `version > 1`), `ChatSleepHintTest` and a wiring
  contract test; iOS adds `SeedPersonasTests`, `SleepDefaultsMigrationTests` (real database: conditions, runs once,
  legacy NULL rows, `updatedAt` unchanged) and `SleepHintTests`. Full `:app:testDebugUnitTest` (**1444 tests / 140 classes / 0 failures**) and `swift test`
  (**1647 tests, 2 skipped / 0 failures**) pass; `:app:assembleDebug` and `:app:lintDebug` (0 errors, 67 warnings, none in
the files touched here) also pass.
- **The other "new persona" entry points were fixed too**: "copy to persona" from a preset (Android `PersonaDraft`
  defaults, iOS `PersonaPresetApplier.newPersona`) and old-format JSON persona import
  (`PersonaRepository.importPersonas`) never go through the editor form and used to inherit the entity default `true` —
  a copied/imported persona would also be read-only-after-23:30. They now create an awake persona.
- **Known limitation**: personas restored from a **backup** keep the backup model's default (`true`), because a backup has
  to restore who he was at that time; the sleep toggle has to be checked by the user.


## 0.11.2

A small release that **separates "operating instructions for AI agents" from "documentation for developers"** and cleans
workspace traces out of the published docs. Version `0.11.2`, build numbers **17**; schema stays v10, backup format stays
v2; **no behaviour changes**.

### Documentation structure

- **Operating instructions for AI agents and automation moved out of the repository**: artefact directories, where a
  version bump has to be applied, workspace build commands, parallel-work conventions, the data/contract checklist, the
  audit procedure and the definition of done belong to the maintainer's local workspace (`.agents/`, not shipped).
  The repository keeps human-facing documentation only; the maintainer-facing version policy and release checklist stay
  in the "Versioning and releases" section of `CONTRIBUTING`.
- Removed `docs/VERSIONING.md` / `.en.md`: the maintainer content moved into `CONTRIBUTING`, the rest is local workspace guidance.
- New **`docs/README.md`**: a human-facing index (project docs, cross-platform contracts, sub-project docs, doc-writing
  rules).
- `docs/ROADMAP-1.x.md` moved to **`docs/design/`** with a "historical design record" banner: they describe the behaviour
  spec of a past release, kept for tracing decisions, not as current documentation.
- `docs/audits/README.md` rewritten for readers: how to read the severity levels, which release fixed which report, and
  the **accepted risks**. How to run an audit is no longer documented in the repository.

### Documentation hygiene

- Public docs no longer contain workspace paths or machine-only commands (`.tooling/` Gradle, local SDK/JDK paths, local
  simulator names). Build commands in `agentd/README.md` and `spec/agent-core/README.md` now use `./gradlew`.
- The "do not commit" list in `CONTRIBUTING` is phrased for contributors (local toolchain/cache directories) instead of
  pointing at a specific workspace directory.
- Added `.github/`: a PR template (platforms, both test suites, data/backup/string checklists) and bug/feature issue
  templates (including "does it conflict with the design stance?" to avoid re-litigating deliberately removed knobs).
- `.gitignore` gained an agent-workspace block (`/.agents/`, `/.scratch/`, `/workspace/`, `*.agent-notes.md`) while
  keeping the existing ignores for `.tooling/`, `dist/`, `docs/screenshots/` and build caches.

### Version and release

- `versionCode` / `CURRENT_PROJECT_VERSION` are **17**; `versionName` / `MARKETING_VERSION` are `0.11.2`.
- Tagged `v0.11.2` with this changelog as the GitHub Release body (`prerelease`).

### Tests

No code changes, so the counts match 0.11.1: Android 970 (91 classes), `:agentcore` 47, `:agentd` 34, iOS 1187. Both
platforms' Debug/Release builds and `lintDebug` were re-run before committing this release.

---

## 0.11.1

A small release: **three independent audits of the new 0.11.0 code** (`:agentcore` extraction, both agent clients,
the P2 work) — 0 P0, **10 P1**, 51 P2; every P1 fixed, most P2 fixed. Version `0.11.1`, build numbers **16**,
schema stays **v10**, backup format stays **v2**. (This release **is tagged and the changelog is published in the
GitHub Release**.)

### Security and robustness (server / shared policies)

- **`agentd` HTTP layer rewritten** (still zero third-party dependencies): the JDK `HttpServer` default single thread
  plus a synchronous body read let **one half-open connection** take the service down (`/health` timed out after 3 s).
  Now: 4 fixed workers with a bounded queue, a **5-second total read deadline** per connection (408 on expiry),
  a **1 MiB request-body limit** (413), chunked bodies rejected (411), and an optional `--token`
  (`Authorization: Bearer`, 401 when missing). Measured: `/health` under a half-open connection went from 3.01 s to
  **0.8 ms**; an 8 MiB body went from "200 and 8 MB echoed back" to **413 with a 227-byte response**.
- **Error responses no longer echo input**: unknown tool names/paths and exception text are truncated or omitted; 500
  returns only "internal error (error id)" with details on stderr; `/health` keeps only service name and version.
- **`WebUrlPolicy` hardened** (the `web_fetch` URL guard): closes `http://127.0.0.1:8080\@example.com/`, `localhost.`,
  `2130706433`, `0x7f000001`, `0177.0.0.1`, `[::ffff:7f00:1]`, `127.0.0.1.nip.io`, percent-encoded hosts and userinfo;
  private IPv6 ranges (ULA, link-local, NAT64, 6to4) are covered too.
- **JSON limits**: depth 64, 256 KiB per string, 128-char number literals, 16 384 entries per container, duplicate keys
  rejected.
- **iOS media guard**: `MediaStorage.delete` and `AttachmentLocation.resolve` had no directory check (a probe could
  delete/point outside the app container); now confined to `attachments/`, rejecting `..`, percent-encoded traversal and
  symlink escapes.
- **iOS `validatePageUrl`** gained the same private-address and literal-form checks (independent implementation,
  same policy as Android).

### Correctness (P1)

- **Backup referential integrity** (Android): a backup whose `messages`/`memories` were non-empty but whose
  `sessions`/`personas` were missing passed the gate, so `REPLACE` wiped the device and then dropped the rows — device
  data gone, backup not restored. Cross-table references are now validated **before** wiping; failing backups are
  rejected whole (and the preview warns first).
- **Jump window ignored new messages** (Android): after jumping from search into a bounded window, your own messages or
  new replies were invisible with no way back. Sending/regenerating/editing now returns to the latest screen, and
  background writes make the "back to latest" affordance reappear.
- **Media deleted inside DB transactions** (Android): backup replace/persona delete removed files before commit, so a
  rollback left rows but no files. Files are now collected and deleted **after** a successful commit.
- **Window ignored external deletes** (iOS): clearing data from settings left the chat showing deleted messages and
  polling forever. Lightweight existence/count checks now reset the window or return home.
- **SSE 8 MB limit actually enforced** (iOS): the limit lived in the parser while the client accumulated bytes itself;
  it is now enforced where bytes are appended.
- **Connect timeout effective + circuit breaker** (iOS): the 3 s connect budget was overridden by the 8 s request budget,
  so every decision waited 8 s when agentd was unreachable; now 3 s applies and a single failure short-circuits to
  native for 30 s.
- **Export/import size symmetry** (iOS): export had no 64 MB cap while import did; aligned.
- **Backup parsing off the main thread** (both): Android JSON parse/map/encode and iOS file read + pre-parse could ANR;
  all moved to IO/`Task.detached`.

### Other fixes (P2 summary)

- Android: `hasKey` three-state wired into the UI (decryptable key counts as configured, unrecoverable keys show a clear
  state); notification deep links validate that the session exists and belongs to the active persona (`MainActivity` no
  longer trusts the extra; forged ids were tested on the emulator); `preset.avatarUri` also exports as a bare file name;
  clearing chats cancels notifications and closes the open session; backup replace bumps session generations;
  `WIPE_ALL` now includes `api_usage` with a two-way table guard; the remote client disables redirects, caps responses
  at 256 KB and exposes fallback counters; the 1024-px cap is really applied on API 26–27; duplicating a persona copies
  its avatar file; image drafting runs in a non-cancellable region; release builds force in-process decisions.
- iOS: strict remote failures are no longer swallowed by `try?` (diagnostic counters + visible errors); notification deep
  links are consumed-and-cleared and validated; deep recall reads a window; `marksRead` defaults from DND; `asleep`
  matches the Kotlin contract; `AsyncMutex` responds to cancellation; remote responses capped at 1 MB; home polling is
  visibility-gated; preview updates use targeted UPDATEs; focus windows cap at 240 messages; golden tests cover all four
  endpoints.
- i18n guards strengthened (both): Chinese punctuation/full-width characters in `values-en`/`en` now fail, positional
  format-specifier swaps fail, and resource keys mentioned only in comments no longer count as used.

### Tests

| Target | Result |
| --- | --- |
| Android | **970 tests / 91 classes / 0 failures** |
| `:agentcore` | **47 tests / 0 failures** (all 94 contract cases asserted) |
| `:agentd` | **34 tests / 0 failures** |
| iOS | **1187 tests / 0 failures** (Debug and Release, zero Swift warnings) |
| Other | `lintDebug` passes (one format-string lint error fixed), `assembleRelease`, `:agentd:fatJar` succeed |

### Audit reports

`docs/audits/0.11.1-{android,ios,security-contract}.md` (every finding is traceable).

---

## 0.11.0

A large release: **the agent decision layer becomes a single implementation (`:agentcore`) and both clients sit behind a
client/server boundary**, plus the remaining P2 items from the audits. Version `0.11.0`, build numbers **15**, schema stays
**v10**, backup format stays **v2**. (This release was not tagged.)

### Architecture: one decision implementation + client/server layering

- New Gradle module **`:agentcore`** (pure Kotlin/JVM, zero third-party dependencies) holding the **40 pure decision
  files** that used to live in `app/.../domain/` (sleep policy, `defer_reply`, proactive cap, mood, heartbeat, memory
  lifecycle, vectors, search, backup copy, erasure plan, error model, …). Package names are unchanged. Both `:app` and
  `:agentd` depend on it.
- `:agentd` no longer uses the `kotlin.srcDir(app/domain)` + 14-`exclude` trick — **the exclusion list is gone**; the
  JSON parser moved into `:agentcore` too (`com.yap.app.core.json`), so there is one copy in the repository.
- Added the `AgentCore` interface (four kinds of decisions), `InProcessAgentCore` (shaping only, delegating to the
  existing pure functions), `AgentCoreProtocol` (path and field-name constants shared by the `agentd` route table and
  both clients) and `AgentCoreCodec` (HTTP encode/decode).
- **Android client**: `AgentCoreClient` (`InProcess` by default / `Remote` over OkHttp to `agentd`), selected by the
  `-PagentCoreRemote=true` build flag (`BuildConfig.AGENT_CORE_REMOTE`); remote failures **fall back in-process** and log,
  and with the flag off the remote client is never constructed (**zero requests**). Wired for sleep decisions,
  `defer_reply`, tool argument clamping and the daily proactive cap (three call sites).
- **iOS client**: a Kit `AgentCoreClient` protocol with `Native` / `Remote` (URLSession) / `Fallback` implementations,
  selected by `YAP_AGENT_CORE` (`native|remote|remote-strict`) or Info.plist, **native by default (zero requests)**;
  the same four call sites are wired. On the simulator, `remote-strict` hit all four endpoints with HTTP 200.
- **No new user-facing settings**; the decision layer still holds no state (sessions, memories and usage stay in the
  client database).

### Android: P2 cleanup

- Chat images picked but never sent, and avatars picked then cancelled or replaced twice, are now reclaimed by the draft
  lifecycle (with the directory guard).
- `createSession` is an idempotent upsert (re-read on unique-index conflict), so tapping a contact never fails.
- `createdAt` collisions now use a stable `(createdAt, id)` order; `deleteAfter` uses the composite cursor so editing a
  message no longer leaves stale replies behind.
- The "read" badge no longer scans the window per message (O(n²)); the last assistant timestamp is computed once.
- Stopping a generation that only produced tool calls no longer writes an empty bubble (three-way decision).
- The multimodal context now has **count and size limits** (last 2 image messages, ≤4 images each, ≤8 total; ≤1024px,
  ≤320KB per image, ≤4MB overall) and images are re-encoded before sending.
- Search jump-to-message computes the window in one query and caps it at 240 messages (no more "target → latest"
  crawling), with a "back to latest" affordance.
- `hasKey` requires a decryptable non-empty key, so a lost key reports a clear error instead of a bare 401.
- `web_fetch` validates scheme/host (rejecting loopback/private literals and `file:`/`javascript:`/`data:`).
- Backup import is capped at **64 MB** (streamed read that aborts at the limit); export no longer writes absolute device
  paths (avatars keep only the file name); an empty backup is no longer exported as an empty file.
- Proactive page clock follows the locale (12-hour in English); notifications can be cancelled per persona or all at
  once; the "earlier messages above" hint is recomputed when the paging window slides.

### iOS: P2 cleanup

- Four long-session full-history reads became window queries plus exact "latest user/assistant message" queries
  (400-message session reads 200 rows).
- Chat polling changed from "always 4 s" to a policy: off in background/not visible, message polling off while streaming,
  20 s when idle.
- The paging window no longer drops loaded history when new messages arrive (append by id, capped at 600).
- Home unread/preview uses a lightweight digest poll (4 s while visible and active, redraw only on change).
- Notification cold-start deep links are persisted (`PendingDeepLink`) and verified end to end.
- In-app browser hardened: `nonPersistent` data store, **JavaScript off by default**, only http/https allowed, dangerous
  schemes cancelled, `mailto`/`tel` handed to the system.
- "Delete all data" now reseeds personas in the current language instead of leaving an empty app.
- The photo picker prefers a file URL and decodes at the target size (the original image is never fully loaded); oversized
  files are rejected before decoding.
- Backup import capped at 64 MB; export strips absolute device paths; the SSE line limit is aligned to 8 MB;
  `isConfigured` really reads the Keychain.

### Tests

| Target | Result |
| --- | --- |
| Android | **909 tests / 87 classes / 0 failures** |
| `:agentcore` | **14 tests / 0 failures** |
| `:agentd` | **20 tests / 0 failures** |
| iOS | **1098 tests / 0 failures** (Debug and Release builds, zero Swift warnings) |
| Builds | `:app:assembleDebug`, `:app:assembleRelease`, `:agentcore:build`, `:agentd:build`, `:agentd:fatJar`, iOS Debug/Release all succeed |

---

## 0.10.2

A small release: **chat-history search** (both platforms; iOS had none), **fixes from three independent audits**
(1 P0, 17 P1), and the tail of the i18n work. Version `0.10.2`, build numbers **14**, schema stays **v10**,
backup format stays **v2**.

### Chat-history search

- New cross-platform contract `spec/search/` (16 cases: 8 for snippet/highlight rules, 8 for ranking/filtering);
  each platform has a test reading the same files.
- **Semantics**: parameterised `LIKE` pre-filter (user input `%`/`_`/`\\` are escaped and treated literally) plus
  `ChineseTerms` token recall, then in-memory ranking (matched tokens → `relevance` → time → id) over at most
  500 candidates; when hits are truncated the UI says so.
- **Android**: results split into "messages / conversations", filters by persona and time (7 / 30 days), highlighted
  snippets, 30 per page, infinite scroll; tapping a hit **opens the conversation at that message** (reusing the paging
  cursor, 2-second highlight that fades) and the search page keeps its query and scroll position.
- **iOS**: there was no chat search at all; this adds a search screen with identical semantics, filters, paging and
  jump-to-message highlighting.
- Trade-off: no inverted index table (that would force schema v11 and touch every write path); search is a low-frequency
  action and a `LIKE` pre-filter over tens of thousands of messages is millisecond-scale.

### Audit fixes

Three independent audits (`docs/audits/`) produced 46 findings: **1 P0, 17 P1**, all fixed in this release.

**iOS (P0/P1)**

| Item | Problem | Fix |
| --- | --- | --- |
| P0-1 | SQLite transaction depth was a bare `Int` shared across MainActor, generation tasks, heartbeat and concurrent background work → double `BEGIN`, or a write swallowed by another transaction's rollback | Transactions are serialised behind a recursive lock; single-statement writes take the same lock; nested transactions still degrade to the outer one |
| P1-1 | Memory extraction / compression wrote the whole session row back, clobbering newer messages, unread counts and summaries | Targeted `UPDATE`s with optimistic checks (count by observed value, summary by monotonic position); deleted sessions are never resurrected |
| P1-2 | Cold start and foregrounding each ran catch-up → double heartbeat, double proactive message, double notification | `CatchUpGuard` (in-flight + 30 s interval) and a single-beat mutex |
| P1-3 | Docs claimed a 30-second foreground heartbeat; none existed | `ForegroundPulse` + foreground timer, stopped in background, relaxed to 2 minutes in low-power mode |
| P1-4 | BGTask reported "nothing to do" as failure and never called `setTaskCompleted` on expiry | Single-shot completion reporter: nothing-to-do counts as success, expiry reports failure exactly once |
| P1-5 | `ResilientKeychain` fell back to plaintext `UserDefaults` on real devices too | Fallback narrowed to `DEBUG && simulator`; on device the write fails loudly |
| P1-6 | Release `Info.plist` had `NSAllowsArbitraryLoads=true` | Release keeps only `NSAllowsLocalNetworking`; Debug uses its own plist; enabling insecure HTTP in Release now reports a clear error |
| P1-7 | "Delete everything" on the backup screen left images and avatars behind | Media cleanup moved into `DataReset`, shared by both entry points |
| P1-8 | `MediaStorage.deleteAll()` dropped `isExcludedFromBackup` after recreating the directory | Re-applied after recreation, with a test |
| P1-9 | Merge import overwrote the local session row and nil-ed `attachmentsJson` (merging your own backup lost every image reference) | Field-by-field merge that keeps local attachment references and newer values |
| P1-10 | A failed send swallowed the draft and picked images and left orphan files | The draft and images are only cleared once sending actually starts; failures keep them and offer a retry |
| P1-11 | Gate waits ignored cancellation (after "stop" a request could still be sent 20/60 s later) | `withTaskCancellationHandler`: cancellation removes the waiter without consuming a slot; background lane gets its own timeout copy |
| Security P1-0 | A backup import could rewrite an existing config's `baseUrl` or enable cleartext while keeping the local key | Address and cleartext flag are always kept local and reported as import warnings; new configs always import with cleartext off |
| Security P1-5 | Backups wrote six legacy sync fields (including `serverSessionId`) | Removed from the backup model and codec, matching Android exactly |
| Security P1-6 | Deleting a persona did not cascade to echoes/thought traces and did not transfer the default flag | Unified cascade in `PersonaDeletion` |

**Android (P1)**

| Item | Problem | Fix |
| --- | --- | --- |
| P1-1 | An old ViewModel's `onCleared` cleared the "currently open session" → the session you were reading was treated as closed: unread +1 and a notification for the message on screen | `OpenSessionTracker` clears only if it still owns the session; notification decisions moved into `ChatNotificationPolicy` |
| P1-2 | A running history compression wrote its summary back after "clear context" | Session generation counter plus pre-write validation (generation, existence, summary boundary) |
| P1-3 | "Clear chats", "delete everything", "delete persona" and "backup replace" missed `memory_versions`, `memory_echoes`, `memory_vectors`, `memory_consolidations`, `agent_ticks`, `agent_intents` and chat images | A single erasure plan (`ErasurePlan`) + `DataEraser` used by all four paths; `MemoryReconsolidator` gained a purge entry point |
| P1-4 | Chat export ran its DB loop and JSON encoding on the main thread | Moved to IO with chunked streaming and in-progress/failure states |
| P1-5 | `send()` blocked on `memoryJob.join()` for up to a full model round-trip | Memory extraction is fully background; sending no longer waits |
| P1-6 | Deleting a session raced with in-flight generation, whose cleanup wrote into the deleted session | Per-session cancellation plus generation/existence checks before every write-back |
| Security P1-0 | Same as iOS: a backup import could change a config's address or enable cleartext | Same validation and warning copy |
| Security P1-2 | Main manifest had `usesCleartextTraffic=true` | Disabled in the main manifest, enabled only in `src/debug`; Release rejects `http://` with a clear error |
| Security P2-1 | Custom headers allowed `Host` / `Content-Length` / etc. | Blocked both when saving and when building requests |
| Security P2-2 | Deleting attachments had no "only inside our own directory" guard | `MediaOwnership` checks (`..` and same-prefix directories) |
| P2-3 | Gate cancellation compensation could take the lock from an already-cancelled coroutine, leaking a slot | Compensation runs inside a `NonCancellable` critical section |
| P2-4 | `AgentLoop.tick` had no mutex (heartbeat / WorkManager / rules) | Non-reentrant `AgentTickLock` |
| P2-5 | Rule-driven proactive messages notified even while the conversation was open | Write + mark read, no notification |

### i18n tail

- Tool trace titles ("Web search", "Remembered", …) now come from resources while model-facing text stays Chinese.
- The remaining user-visible Chinese from the data layer is gone (session title fallback, message previews, default rule
  copy, extraction result lines, …).
- English plurals filled in for 0/1/2; long English copy reviewed on home, settings, search and persona editor.
- Guards strengthened: `values-en` / `en` resources **must not contain CJK**, format specifiers compared position by
  position, and unused resources fail the build.

### Tests

| Target | Result |
| --- | --- |
| Android | **823 tests / 73 classes / 0 failures** + 3 instrumented tests (real resources, zh/en titles) |
| iOS | **1016 tests / 0 failures** (Debug and Release builds, zero Swift warnings) |
| agentd | 19 tests / 0 failures |
| Builds | `:app:assembleDebug` / `:app:assembleRelease` / `:agentd:build`, iOS Debug and Release `xcodebuild` all succeed |

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
- iOS: added `ios/YetAnotherPal/Resources/zh-Hans.lproj/Localizable.strings` and `en.lproj/Localizable.strings`; `CFBundleDevelopmentRegion = zh-Hans`, `CFBundleLocalizations = [zh-Hans, en]`, and both `InfoPlist.strings` localise the local-network permission copy, all selected automatically by `Bundle.main`. There is no in-app switch here either.
- Model language: both platforms append a language-directive section to the end of the system prompt, instructing the model to answer in the same language the user writes in. Both provide Chinese and English templates following the UI language (Android: `prompt_language_directive` under `values{,-en}`; iOS: the same key in `Localizable.strings`). The wording is identical; only the placeholder syntax differs (`%1$s` / `%1$@`). Apart from this section, prompt scaffolding, logs and code comments are not translated.
- Resource guards: Android `StringResourceParityTest` (key sets and placeholders), `ChineseLiteralGuardTest` (no hard-coded Chinese in the UI layer), `LanguageDirectiveTest` (language directive appended to the end of the prompt); iOS `LocalizationResourceTests`, `UIChineseLiteralGuardTests`.
- String counts: Android `values` / `values-en` hold **876** entries each (856 `<string>` + 20 `<plurals>`), plus 12 `seed.xml` entries per language, a locale-specific `usage_tokens_compact_divisor`, and date/time patterns as resources. iOS holds **765** entries in each `Localizable.strings` (about 637 call sites in the app layer plus 233 kit keys) and one entry per `InfoPlist.strings`.
- Seed personas follow the UI language: Chinese installs get 林深 / 阿岚 / 墨; English installs get Elias / Finn / Ansel on Android and Rowan / Juno / Ellis on iOS. This only affects first installs; existing personas are never migrated or renamed.

### Agent core contract

- Added `spec/agent-core/cases/*.json`: **94** language-neutral cases (sleep decision 16, `defer_reply` 9, proactive cap 14, heartbeat 17, memory decay 22, tool-argument clamping 16). Each case has `name` / `input` / `expect` and an optional `why`; floating-point assertions carry a `tolerance`.
- Android test `app/src/test/java/com/yap/app/spec/AgentCoreSpecTest.kt` and iOS test `ios/YapKit/Tests/YapKitTests/Spec/AgentCoreSpecTests.swift` read the **same** JSON files and feed them into each platform's existing domain implementation. Changing behaviour on one side alone turns the other side red.

### Embedded service skeleton: agentd

- Added a standalone Gradle JVM module `agentd/` (Kotlin/JVM, only the JDK's `com.sun.net.httpserver`, zero third-party dependencies) that compiles Android's pure domain sources directly (the same source files, not a copy).
- Exposes `GET /health` plus four core endpoints (`sleep-decision` / `defer-plan` / `proactive-cap` / `tool-args`); it can run standalone or as an embedded service on a local port. It does not touch the database or call an LLM, and has no authentication or state persistence.
- Verified: `:agentd:test` runs **19** tests with no failures (18 handler tests plus 1 real-port integration test). Both `agentd/build/install/agentd/bin/agentd --port 8788` and `java -jar agentd-all.jar` start, and `/health` plus the four endpoints answer as specified (errors are always `{"error":{"message","code"}}` with 4xx/5xx).
- **Not wired this round**: the Android client does not start agentd, and the iOS side has no code calling it. Both apps keep their existing in-process implementation.

### Divergences the contract uncovered

Writing the cases revealed ten places where the two implementations quietly disagreed. The rule: the spec in
`docs/design/roadmap-1.9.md` wins; where both were reasonable, Android was treated as the reference and Swift was aligned.

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
| iOS | `cd ios/YapKit && swift test --disable-sandbox` | **827 tests / 0 failures** |
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
- Open-source preparation: Apache-2.0, Gradle Wrapper, `.gitignore` (toolchain and build artifacts excluded), `CONTRIBUTING.md`, `CONTRIBUTING.md`.

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

Design documents from the old numbering era are still under `docs/`: [ROADMAP-1.2](design/roadmap-1.2.md), [ROADMAP-1.3](design/roadmap-1.3.md), [ROADMAP-1.4](design/roadmap-1.4.md), [ROADMAP-1.5](design/roadmap-1.5.md), [ROADMAP-1.6](design/roadmap-1.6.md), [ROADMAP-1.7](design/roadmap-1.7.md), [ROADMAP-1.9](design/roadmap-1.9.md) (= 0.9.0).
