# agentd — the shared agent core as a standalone JVM service

`agentd` is a small HTTP service that exposes the **shared agent decisions** over the wire.
Since 0.11.0 those decisions live in their own module, `:agentcore` (pure Kotlin/JVM, **zero
third-party runtime dependencies**), and both clients compile against it:

```
                 ┌─────────────────────────────┐
                 │  :agentcore  (pure Kotlin)  │
                 │  com.myfriend.app.domain    │
                 │  SleepPolicy / DeferReply   │
                 │  ProactiveLevel / Clamp …   │
                 │  AgentCore + InProcess impl │
                 │  AgentCoreProtocol / Codec  │
                 └──────────┬──────────────────┘
                            │ implementation(project(":agentcore"))
             ┌──────────────┴───────────────┐
             │                              │
   ┌───────────────────┐          ┌───────────────────┐
   │       :app        │          │      :agentd      │
   │ Android client    │          │ JVM HTTP service  │
   │ InProcess default │          │ validates request │
   │ Remote optional   │◄─HTTP────►│ → InProcessCore   │
   └───────────────────┘ /v1/core  └───────────────────┘
```

- `:app` never depends on `:agentd` — the Android build is unaffected by the service. It depends
  on `:agentcore` and calls it **in-process by default**.
- `:agentd` never re-implements a rule: its handlers validate the HTTP request and then call
  `InProcessAgentCore` (the same object Android uses in-process).
- iOS mirrors the same rules in Swift and reads the same `spec/agent-core/cases/*.json`
  contract files (see the root README); it is not routed through `:agentcore` (which is JVM-only).

The HTTP server is the JDK's own `com.sun.net.httpserver`, and JSON is parsed/serialized by the
recursive-descent parser in `:agentcore` (`com/myfriend/app/core/json/Json.kt`). There is no web
framework, no JSON library, no DI container, no logging framework, no coroutines.

Runtime classpath of the distribution is exactly `agentd.jar` + `agentcore.jar` + Kotlin stdlib
(+ the stdlib's `annotations` transitive).

Version: `0.11.0`. Included in the root build as `:agentd`, deliberately **not** a dependency
of `:app`.

## Run it

From the repository root:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home
export GRADLE_USER_HOME=$PWD/.tooling/build-home TMPDIR=$PWD/.tooling/tmp

./.tooling/gradle-8.9/bin/gradle :agentd:run --args="--port 8788"
```

Or build a runnable distribution:

```bash
./.tooling/gradle-8.9/bin/gradle :agentd:installDist
agentd/build/install/agentd/bin/agentd --port 8788
```

Or build the self-contained jar (Kotlin stdlib + agentcore bundled, `Main-Class` set):

```bash
./.tooling/gradle-8.9/bin/gradle :agentd:fatJar
java -jar agentd/build/libs/agentd-all.jar --port 8788
```

Options:

| Flag | Default | Meaning |
| --- | --- | --- |
| `--host` | `127.0.0.1` | Bind address. Use `0.0.0.0` only when deploying behind your own auth/proxy. |
| `--port` | `8788` | Listen port. |
| `--help` / `-h` | — | Usage. |

Smoke test:

```bash
curl -s http://127.0.0.1:8788/health
# {"ok":true,"service":"myfriend-agentd","version":"0.11.0"}
```

## Point the Android client at it

The Android app can use this service instead of computing the four decisions in-process.
It is a **build switch**, not a user-visible setting (release builds stay in-process):

```bash
# default: in-process, no network involved
./.tooling/gradle-8.9/bin/gradle :app:assembleDebug

# remote mode: sleep / defer / proactive-cap / tool-args POST to agentd
./.tooling/gradle-8.9/bin/gradle :app:assembleDebug \
  -PagentCoreRemote=true \
  -PagentCoreBaseUrl=http://10.0.2.2:8788
```

- `10.0.2.2` is the host machine as seen from the Android emulator. For a physical device use
  the LAN IP and start the service with `--host 0.0.0.0`.
- Cleartext HTTP is allowed only in debug builds (`app/src/debug/AndroidManifest.xml`); a release
  build talks HTTPS or falls back to in-process.
- The switch becomes `BuildConfig.AGENT_CORE_REMOTE`; the URL becomes
  `BuildConfig.AGENT_CORE_BASE_URL` (`RemoteAgentCoreClient`).
- Every remote call has a short timeout (1.5 s by default). **Any** failure — connection refused,
  timeout, 4xx/5xx, malformed JSON, missing field — is logged under the `MyFriendAgentCore`
  logcat tag and answered by the in-process implementation instead, so chat never hangs or breaks
  because the service is down.
- When the switch is off, `AgentCoreClients.create` returns the in-process client and no HTTP
  client is even constructed (there is a unit test asserting zero requests in that case).

Optional end-to-end check against this service (runs the real `RemoteAgentCoreClient` from
`:app`, no fallback allowed):

```bash
./.tooling/gradle-8.9/bin/gradle :app:testDebugUnitTest \
  --tests "com.myfriend.app.core.AgentCoreRemoteE2ETest" \
  -PagentCoreE2eUrl=http://127.0.0.1:8788 --rerun
```

## Endpoints

All request bodies are JSON objects. All responses are JSON with
`Content-Type: application/json; charset=utf-8`.

Path and field names are not duplicated in the service: both sides read them from
`AgentCoreProtocol` in `:agentcore`, and `AgentdApiTest` asserts that the route table equals
`AgentCoreProtocol.CORE_PATHS`.

### `GET /health`

```json
{"ok":true,"service":"myfriend-agentd","version":"0.11.0"}
```

### `POST /v1/core/sleep-decision`

Wraps `SleepPolicy.evaluate` (via `InProcessAgentCore.sleepDecision`; Swift `SleepGate.evaluate`).

```json
{
  "sleepEnabled": true,
  "sleepMinutes": null,
  "wakeMinutes": null,
  "nowMinutes": 120,
  "dndOnSleep": false,
  "userMessagesInWindow": 6,
  "wakeThreshold": 6
}
```

```json
{"wake":true,"moodRelaxed":true,"reason":"被你叫醒了，缓了缓","marksRead":true}
```

- `sleepMinutes` / `wakeMinutes`: minutes after midnight, nullable (null = 23:30 / 08:00).
  Values are wrapped mod 1440; a sleep/wake pair on the same minute falls back to the default
  window (same rule as Android).
- `nowMinutes`: minutes after midnight; wrapped mod 1440.
- `wake` = "this round he will reply" (`Decision.shouldReply`); `moodRelaxed` = he was woken and
  his mood is pulled back (`Decision.woken`).
- `marksRead`: whether the user message gets a read receipt. **Added in 0.11.0** (compatible,
  additive) because remote mode would otherwise silently lose the "do not disturb leaves no read
  receipt" rule (spec §6.2). Older clients ignore it; when it is missing, clients default to
  `true`.
- `reason` is a stable Chinese sentence for clients that want to log/display it:
  `"醒着，会正常回复"`, `"免打扰中，不会醒"`, `"被你叫醒了，缓了缓"`,
  `"睡着了 · 08:00 醒"` (wake clock derived from the persona's schedule).

### `POST /v1/core/defer-plan`

Wraps `DeferReply.plan` (Kotlin / Swift `DeferReply.plan`).

```json
{"minutes": 90, "reason": "现在不想说话", "now": 1700000000000}
```

```json
{"earliestAt":1700005400000,"latestAt":1700007200000,"minutes":90,"reason":"现在不想说话"}
```

- `minutes` is optional (default 45) and clamped to 5–720.
- `reason` is optional, trimmed and truncated to 80 characters.
- `now` is **required** and explicit (milliseconds) so the call is deterministic; this service
  does not read the wall clock for pure functions.
- `earliestAt` / `latestAt` are epoch milliseconds: `now + minutes*60_000`, and the decision
  window is 30 minutes wide.

### `POST /v1/core/proactive-cap`

Wraps `ProactiveLevel.dailyCap` and, when a mood is given, `MoodBehavior.effectiveDailyCap`
(via `InProcessAgentCore.proactiveCap`).

```json
{"level": 3, "moodLabel": "雀跃", "moodIntensity": 4}
```

```json
{"dailyCap":4}
```

- `level` is clamped to 1–5.
- Mood needs **both** `moodLabel` and `moodIntensity` to move the cap, because the domain model
  decides "raise / lower / no change" from the label's valence and energy — an intensity alone
  carries no direction. If only `moodIntensity` is sent, the base cap is returned.
- `moodIntensity` defaults to 2 when a label is present.

### `POST /v1/core/tool-args`

Wraps `ToolArgClamp` for the numeric boundaries the two clients must share
(via `InProcessAgentCore.clampToolArgs`).

```json
{"tool": "remember", "args": {"content": "她妹妹叫小雨", "drawer": "USER", "importance": 9}}
```

```json
{"args":{"content":"她妹妹叫小雨","drawer":"USER","importance":5}}
```

Supported tools and clamps:

| `tool` | Numeric field | Range | Default when missing |
| --- | --- | --- | --- |
| `remember` | `importance` | 1–5 | 3 |
| `web_search` | `count` | 1–12 | 6 |
| `defer_reply` | `minutes` | 5–720 | 45 |

All other keys in `args` are preserved untouched (including their original JSON number spelling).
Unknown tools return 400 `unknown_tool`.

## Errors

Every error is an object with a Chinese `message` and a stable `code`, plus a 4xx/5xx status:

```json
{"error":{"message":"字段 now 必须是整数","code":"invalid_argument"}}
```

| Status | `code` | When |
| --- | --- | --- |
| 400 | `empty_body` | No request body. |
| 400 | `invalid_json` | Body is not valid JSON. |
| 400 | `invalid_body` | Body is valid JSON but not an object. |
| 400 | `missing_field` | A required field is absent. |
| 400 | `invalid_argument` | A field has the wrong type / can't be parsed. |
| 400 | `unknown_tool` | `/v1/core/tool-args` got an unsupported tool. |
| 404 | `not_found` | Unknown route. |
| 405 | `method_not_allowed` | Known route with the wrong method. |
| 500 | `internal_error` | Unexpected server-side failure. |

## Why this shape

- **Zero dependencies** keeps the deployment story simple: a JDK 17 `java -jar` is enough, no
  framework, no transitive CVE surface, no version skew with the Android build.
- **One implementation, not two copies.** Before 0.11.0 the service compiled the Android source
  tree via `kotlin.srcDir("../app/src/main/java/com/myfriend/app/domain")` plus an exclude list of
  14 files. That worked but had two failure modes: the exclude list could drift, and `:app` /
  `:agentd` produced two separate copies of the same classes. Now the 36 portable files live in
  `:agentcore` and both modules compile the *same* classes.
- **Handler layer is a pure function** (`AgentdApi.handle(method, path, body)`) so tests cover
  every route and error without opening a port; one integration test still starts a real server
  on an ephemeral port with the JDK `HttpClient`.

### What stayed in `:app`

These domain files cannot leave Android and remain in `app/src/main/java/com/myfriend/app/domain/`:

| File | Why it can't be shared |
| --- | --- |
| `AgentActions.kt` | `kotlinx.serialization` (model action protocol). |
| `ApiCost.kt` | `kotlinx.serialization` + pricing/usage types. |
| `Attachments.kt` | `kotlinx.serialization` (attachment codec). |
| `BackupCodec.kt` | Room entities (`AgentIntentEntity`, ...). |
| `ImportanceScoring.kt` | `kotlinx.serialization` (model output parsing). |
| `LlmConcurrencyGate.kt` | `kotlinx.coroutines`. |
| `MemoryScreening.kt` | `kotlinx.serialization` (`MemoryTags`). |
| `Models.kt` | Room entities (`ChatSessionEntity`, `MessageEntity`). |
| `OpenSessionTracker.kt` | `javax.inject` (Hilt). |
| `PersonaCard.kt` | `kotlinx.serialization`. |
| `PersonaProfile.kt` | Room entity `PersonaEntity`. |
| `Recall.kt` | Depends on `MemoryScreening.MemoryRow`. |
| `ToolProtocol.kt` | `kotlinx.serialization` (tool protocol). |
| `ToolTrace.kt` | `kotlinx.serialization` (tool traces). |

Everything else (36 files: `SleepPolicy`, `DeferReply`, `ProactiveLevel`, `Heartbeat`, `Mood`,
`MoodBehavior`, `MemoryLifecycle`, `MemoryDrawer`, `ToolArgClamp`, `ChineseTerms`,
`HistoryCompression`, `LocalEmbedding`, ...) is in `:agentcore` under the same package
`com.myfriend.app.domain`, so Android imports and tests did not change at all.

## What this service does **not** do yet

- No authentication, TLS, rate limiting, or request size cap. Bind to `127.0.0.1` by default.
- No persistence: it does not touch SQLite/Room, does not write `agent_intents`, does not store
  memories. It only exposes deterministic pure functions.
- No LLM calls, no prompting, no streaming.
- Android does not start it as an embedded service; remote mode is a development build switch.
  iOS has its own Swift mirror and does not call this service.
- Only four functions are exposed. Adding more means adding an `AgentCore` method, a route in
  `Api.kt`, and a test — the shared implementation is already in `:agentcore`.

## Tests

```bash
./.tooling/gradle-8.9/bin/gradle :agentcore:test :agentd:test
```

- `:agentcore` — `AgentCoreSpecTest` (20 assertions groups over `spec/agent-core/cases/*.json`
  through the `AgentCore` interface, protocol pinning, JSON codec round-trips, malformed-response
  rejection). 14 tests.
- `AgentdApiTest` — handler-level: every route, defaults, clamping, error codes, and a check that
  the route table comes from `AgentCoreProtocol`. 19 tests.
- `AgentdServerTest` — one integration test: real `HttpServer` on port 0 + JDK `HttpClient`.
