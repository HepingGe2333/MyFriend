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
  JSON parser moved into `:agentcore` too (`com.myfriend.app.core.json`), so there is one copy in the repository.
- Added the `AgentCore` interface (four kinds of decisions), `InProcessAgentCore` (shaping only, delegating to the
  existing pure functions), `AgentCoreProtocol` (path and field-name constants shared by the `agentd` route table and
  both clients) and `AgentCoreCodec` (HTTP encode/decode).
- **Android client**: `AgentCoreClient` (`InProcess` by default / `Remote` over OkHttp to `agentd`), selected by the
  `-PagentCoreRemote=true` build flag (`BuildConfig.AGENT_CORE_REMOTE`); remote failures **fall back in-process** and log,
  and with the flag off the remote client is never constructed (**zero requests**). Wired for sleep decisions,
  `defer_reply`, tool argument clamping and the daily proactive cap (three call sites).
- **iOS client**: a Kit `AgentCoreClient` protocol with `Native` / `Remote` (URLSession) / `Fallback` implementations,
  selected by `MYFRIEND_AGENT_CORE` (`native|remote|remote-strict`) or Info.plist, **native by default (zero requests)**;
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
