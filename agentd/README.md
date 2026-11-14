# agentd — the shared agent core as a standalone JVM service

`agentd` is a small HTTP service that runs the **same Kotlin source files** Android uses for its
pure agent logic (`app/src/main/java/com/myfriend/app/domain/`). It is built with the Kotlin/JVM
Gradle plugin and has **no third-party runtime dependency beyond the Kotlin stdlib**: the HTTP
server is the JDK's own `com.sun.net.httpserver`, and JSON is parsed/serialized by a ~200-line
parser in `agentd/src/main/kotlin/com/myfriend/agentd/Json.kt`. There is no web framework, no JSON
library, no DI container, no logging framework, no coroutines.

Runtime classpath of the distribution is exactly `agentd.jar` + `kotlin-stdlib-2.0.21.jar` +
`annotations-13.0.jar` (the last one is a transitive of the Kotlin stdlib).

It is a **skeleton**, not a finished agent backend. It proves three things:

1. the "two platforms must agree" logic can be compiled once and run on a plain JVM;
2. Android can use it as an in-process / localhost embedded service (not wired yet);
3. iOS or any third party can call the same logic over HTTP on a standalone server (client not
   wired yet).

Version: `0.10.0`. It is included in the root build as `:agentd` and is deliberately **not** a
dependency of `:app` — the Android build is unaffected.

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

Or build the self-contained jar (Kotlin stdlib bundled, `Main-Class` set):

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
# {"ok":true,"service":"myfriend-agentd","version":"0.10.0"}
```

## Endpoints

All request bodies are JSON objects. All responses are JSON. `Content-Type:
application/json; charset=utf-8`.

### `GET /health`

```json
{"ok":true,"service":"myfriend-agentd","version":"0.10.0"}
```

### `POST /v1/core/sleep-decision`

Wraps `SleepPolicy.evaluate` (Kotlin `domain/SleepPolicy.kt`, Swift `SleepGate.evaluate`).

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
{"wake":true,"moodRelaxed":true,"reason":"被你叫醒了，缓了缓"}
```

- `sleepMinutes` / `wakeMinutes`: minutes after midnight, nullable (null = 23:30 / 08:00).
  Values are wrapped mod 1440; a sleep/wake pair on the same minute falls back to the default
  window (same rule as Android).
- `nowMinutes`: minutes after midnight; wrapped mod 1440.
- `wake` = "this round he will reply" (`Decision.shouldReply`); `moodRelaxed` = he was woken and
  his mood is pulled back (`Decision.woken`).
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
- `earliestAt` / `latestAt` are epoch milliseconds: `now + minutes*60_000`,
  and the decision window is 30 minutes wide.

### `POST /v1/core/proactive-cap`

Wraps `ProactiveLevel.dailyCap` and, when a mood is given, `MoodBehavior.effectiveDailyCap`.

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

Wraps `ToolArgClamp` for the numeric boundaries the two clients must share.

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

All other keys in `args` are preserved untouched. Unknown tools return 400 `unknown_tool`.

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
- **Reusing Android's domain sources instead of copying them** is the whole point: the service
  and the app compile the exact same files. A change to `SleepPolicy` is immediately in both.
  The `sourceSet` points at `../app/src/main/java/com/myfriend/app/domain` and excludes the files
  that legitimately cannot leave Android — see below.
- **Handler layer is a pure function** (`AgentdApi.handle(method, path, body)`) so tests cover
  every route and error without opening a port; one integration test still starts a real server
  on an ephemeral port with the JDK `HttpClient`.

### Domain files that are NOT compiled into `agentd`

The source set is an exclude list (not an include allow-list): a new pure domain file is picked up
automatically, and a new file that secretly imports Android fails the build loudly instead of
silently existing only on one side.

| File | Why it can't be shared |
| --- | --- |
| `AgentActions.kt` | `kotlinx.serialization` (model action protocol). |
| `ApiCost.kt` | `kotlinx.serialization` + pricing/usage types. |
| `Attachments.kt` | `kotlinx.serialization` (attachment codec). |
| `BackupCodec.kt` | Room entities (`AgentIntentEntity`, ...). |
| `ImportanceScoring.kt` | `kotlinx.serialization` (model output parsing). |
| `LlmConcurrencyGate.kt` | `kotlinx.coroutines`. |
| `MemoryScreening.kt` | `kotlinx.serialization` (`MemoryTags`); `MemoryDrawer` was extracted to its own file so `MemoryLifecycle` can be shared. |
| `Models.kt` | Room entities (`ChatSessionEntity`, `MessageEntity`). |
| `OpenSessionTracker.kt` | `javax.inject` (Hilt). |
| `PersonaCard.kt` | `kotlinx.serialization`. |
| `PersonaProfile.kt` | Room entity `PersonaEntity`. |
| `Recall.kt` | Depends on `MemoryScreening.MemoryRow`. |
| `ToolProtocol.kt` | `kotlinx.serialization` (tool protocol). |
| `ToolTrace.kt` | `kotlinx.serialization` (tool traces). |

What remains is genuinely pure: `SleepPolicy`, `DeferReply`, `ProactiveLevel`, `Heartbeat`, `Mood`,
`MoodBehavior`, `MemoryLifecycle`, `MemoryDrawer`, `ToolArgClamp`, plus other stdlib-only domain
files (`ChineseTerms`, `HistoryCompression`, `LocalEmbedding`, ...).

## What this service does **not** do yet

- No authentication, TLS, rate limiting, or request size cap. Bind to `127.0.0.1` by default.
- No persistence: it does not touch SQLite/Room, does not write `agent_intents`, does not store
  memories. It only exposes deterministic pure functions.
- No LLM calls, no prompting, no streaming.
- Android does not start it yet (no foreground service, no localhost client); iOS has no client.
- Only four functions are exposed. Adding more means adding a route in `Api.kt` and a test —
  the domain function is already shared.

## Tests

```bash
./.tooling/gradle-8.9/bin/gradle :agentd:test
```

- `AgentdApiTest` — handler-level: every route, defaults, clamping, and error codes (18 tests).
- `AgentdServerTest` — one integration test: real `HttpServer` on port 0 + JDK `HttpClient`.
