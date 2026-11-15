# agentd — the shared agent core as a standalone JVM service

`agentd` is a small HTTP service that exposes the **shared agent decisions** over the wire.
Since 0.11.0 those decisions live in their own module, `:agentcore`; since 0.12.0 that module is a
Kotlin Multiplatform module and `agentd` consumes its **`jvm()` variant** through the same
`implementation(project(":agentcore"))` dependency (its `installDist`/`fatJar` therefore ship
`agentcore-jvm.jar` / the same classes). The module has **zero third-party runtime dependencies**,
and both JVM clients compile against it:

```
                 ┌─────────────────────────────┐
                 │  :agentcore  (KMP)          │
                 │  com.myfriend.app.domain    │
                 │  SleepPolicy / DeferReply   │
                 │  ProactiveLevel / Clamp …   │
                 │  AgentCore + InProcess impl │
                 │  AgentCoreProtocol / Codec  │
                 │  commonMain + jvmMain       │
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
- iOS now links the same Kotlin implementation as a static `AgentCoreKit.framework` built from
  `:agentcore`'s iOS targets (`ios/scripts/build-agentcore-framework.sh`); the older Swift mirror
  reads the same `spec/agent-core/cases/*.json` contract files and is being retired.

The HTTP layer is a ~250-line minimal HTTP/1.1 loop built on `ServerSocket` (one request per
connection, `Connection: close`; no keep-alive, no chunked, no TLS). It replaced the JDK's
`com.sun.net.httpserver` in 0.11.1 because that API has no read timeout, its request
`InputStream.available()` is unreliable, and closing a response drains the unread request body in a
blocking read that `exchange.close()` cannot interrupt — which made the audit's P1-1 half-open
denial of service impossible to fix reliably. JSON is parsed/serialized by the recursive-descent
parser in `:agentcore` (`com/myfriend/app/core/json/Json.kt`). There is no web framework, no JSON
library, no DI container, no logging framework, no coroutines.

Runtime classpath of the distribution is exactly `agentd.jar` + `agentcore.jar` + Kotlin stdlib
(+ the stdlib's `annotations` transitive).

Version: `0.11.0`. Included in the root build as `:agentd`, deliberately **not** a dependency
of `:app`.

## Run it

From the repository root:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home

./gradlew :agentd:run --args="--port 8788"
```

Or build a runnable distribution:

```bash
./gradlew :agentd:installDist
agentd/build/install/agentd/bin/agentd --port 8788
```

Or build the self-contained jar (Kotlin stdlib + agentcore bundled, `Main-Class` set):

```bash
./gradlew :agentd:fatJar
java -jar agentd/build/libs/agentd-all.jar --port 8788
```

Options:

| Flag | Default | Meaning |
| --- | --- | --- |
| `--host` | `127.0.0.1` | Bind address. `0.0.0.0` means **anyone on the network can call it** — there is no TLS and (unless `--token` is set) no auth. Only use it on a trusted LAN or behind your own auth/proxy. |
| `--port` | `8788` | Listen port. |
| `--token <secret>` | — | Optional shared token. When set, **every** request (including `/health`) must send `Authorization: Bearer <secret>`; anything else gets `401`. Can also be supplied as the `MYFRIEND_AGENTD_TOKEN` environment variable so it does not show up in `ps`. |
| `--help` / `-h` | — | Usage. |

Smoke test:

```bash
curl -s http://127.0.0.1:8788/health
# {"ok":true,"service":"myfriend-agentd","version":"0.11.0"}

# only when --token is set:
curl -s -H "Authorization: Bearer $MYFRIEND_AGENTD_TOKEN" http://127.0.0.1:8788/health
```

## Limits, timeouts and auth (0.11.1)

`agentd` is an embedded service, not a public API; 0.11.1 adds the minimum hardening needed so
one client cannot take it down or make it amplify traffic. All of it is covered by tests in
`AgentdApiTest` / `AgentdServerTest`.

| Limit | Value | Behaviour |
| --- | --- | --- |
| Request body | 1 MiB (`AgentdApi.MAX_BODY_BYTES`) | `Content-Length` over the cap → `413 content_too_large`, body is not read into memory |
| Read timeout | 5 s (`AgentdServer.READ_TIMEOUT_MS`) | A body that stalls mid-transfer → `408 request_timeout`, connection closed, worker released |
| Body framing | `Content-Length` only | `Transfer-Encoding: chunked` → `411 length_required` (both clients send `Content-Length`; a blocking chunked parse would reopen the slow-client hole) |
| Workers | 4 fixed threads + a bounded 64-connection queue | Slow clients can occupy at most the pool; `/health` keeps responding on the remaining threads, and a full queue closes new connections instead of growing memory |
| Error bodies | message ≤ 512 chars | Client input is never echoed (unknown tool / long path / invalid JSON are answered with fixed text); `500` is a fixed message + random `errorId`, details only go to stderr |
| Auth | optional `--token` | Missing/wrong `Authorization: Bearer …` → `401 unauthorized` (constant-time compare) |

The timeout is enforced as a **total per-request deadline** (request line + headers + body), not a
per-read timeout: a client that sends one byte every few seconds is cut off at 5 s as well. After
the response is flushed the server half-closes the socket, briefly drains whatever the client is
still sending (50 ms / 64 KiB) so a `413`/`401` answer is not lost to a TCP RST, then closes the
connection. All of this lives in `AgentdServer` and is covered by `AgentdServerTest`.

`/health` intentionally keeps reporting `service` and `version`: the Android/iOS remote clients use
it to tell "agentd is up" from "wrong service on this port". It exposes nothing beyond the service
name and the build version (the audit accepted this; see 0.11.1 P2-15). When `--token` is set,
`/health` requires the token too.

**Known limitation:** the token is a shared secret over plain HTTP. It stops random LAN clients
and accidental exposure, not an active network attacker; for that you still need TLS/reverse proxy.
The Android remote client does not send `Authorization` yet, so keep `--token` off until the client
is updated (or use it with a proxy that injects the header).

## Point the Android client at it

The Android app can use this service instead of computing the four decisions in-process.
It is a **build switch**, not a user-visible setting (release builds stay in-process):

```bash
# default: in-process, no network involved
./gradlew :app:assembleDebug

# remote mode: sleep / defer / proactive-cap / tool-args POST to agentd
./gradlew :app:assembleDebug \
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
./gradlew :app:testDebugUnitTest \
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

The version is exposed on purpose (clients and humans use it to verify which build is answering);
it is the only "internal" detail any response carries. When `--token` is set this endpoint needs
the token as well.

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
| 400 | `invalid_json` | Body is not valid JSON (too deep / too long / duplicate key / non-finite number also land here). |
| 400 | `invalid_body` | Body is valid JSON but not an object; also used when the body could not be read completely. |
| 400 | `missing_field` | A required field is absent. |
| 400 | `invalid_argument` | A field has the wrong type / can't be parsed. |
| 400 | `unknown_tool` | `/v1/core/tool-args` got an unsupported tool (the tool name is **not** echoed back). |
| 401 | `unauthorized` | `--token` is set and the request has no/incorrect `Authorization: Bearer …`. |
| 404 | `not_found` | Unknown route (the path is not echoed back). |
| 405 | `method_not_allowed` | Known route with the wrong method. |
| 408 | `request_timeout` | The request body did not arrive within `READ_TIMEOUT_MS`. |
| 411 | `length_required` | Request used `Transfer-Encoding: chunked`; only `Content-Length` bodies are accepted. |
| 413 | `content_too_large` | Declared or accumulated body exceeds 1 MiB. |
| 500 | `internal_error` | Unexpected server-side failure; the response is a fixed message plus a random `errorId`, never the exception text. |

## Why this shape

- **Zero dependencies** keeps the deployment story simple: a JDK 17 `java -jar` is enough, no
  framework, no transitive CVE surface, no version skew with the Android build.
- **One implementation, not two copies.** Before 0.11.0 the service compiled the Android source
  tree via `kotlin.srcDir("../app/src/main/java/com/myfriend/app/domain")` plus an exclude list of
  14 files. That worked but had two failure modes: the exclude list could drift, and `:app` /
  `:agentd` produced two separate copies of the same classes. Now the 36 portable files live in
  `:agentcore` and both modules compile the *same* classes.
- **Handler layer is a pure function** (`AgentdApi.handle(method, path, body)`) so tests cover
  every route and error without opening a port; a handful of integration tests still start a real
  server on an ephemeral port with the JDK `HttpClient` / raw sockets (slow-client, size limits,
  token).

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

- **No TLS and no rate limiting.** It binds to `127.0.0.1` by default; `--host 0.0.0.0` makes it
  reachable by the whole network. Since 0.11.1 there is an optional shared token (`--token`),
  a 1 MiB request-body cap, a 5 s read timeout and 4 fixed worker threads (see
  “Limits, timeouts and auth” above) — but a token over plain HTTP is not a substitute for TLS,
  and a determined client can still slow the service down. Treat `0.0.0.0` as “trusted network
  only”.
- No persistence: it does not touch SQLite/Room, does not write `agent_intents`, does not store
  memories. It only exposes deterministic pure functions.
- No LLM calls, no prompting, no streaming.
- Android does not start it as an embedded service; remote mode is a development build switch.
  iOS has its own Swift mirror and does not call this service.
- Only four functions are exposed. Adding more means adding an `AgentCore` method, a route in
  `Api.kt`, and a test — the shared implementation is already in `:agentcore`.

## Tests

```bash
./gradlew :agentcore:test :agentd:test
```

- `:agentcore` — 60 tests (`commonTest` + `jvmTest`; `:agentcore:test` is the `jvmTest` alias). `AgentCoreSpecTest` (the shared `spec/agent-core/cases/*.json`
  contract through the `AgentCore` interface, protocol pinning, JSON codec round-trips,
  malformed-response rejection), plus the 0.11.1 hardening suites:
  `WebUrlPolicyHardeningTest` (the audit's URL-bypass table and the “normal domains/public IPs
  still pass” side), `JsonLimitsTest` (nesting/string/number/container/dedup limits) and
  `AgentCoreCodecLimitsTest` (codec-level rejection + bounded error text).
- `AgentdApiTest` — 29 handler-level tests: every route, defaults, clamping, error codes, and a
  check that the route table comes from `AgentCoreProtocol`; 0.11.1 additions cover 413 (8 MiB
  body), no-echo of long tool names/paths, generic 500, deeply nested / `1e308` / `NaN` /
  duplicate-key / over-long-string JSON, and a legal 256 KiB body.
- `AgentdServerTest` — 5 integration tests on a real `ServerSocket`-based server (port 0): normal
  requests, half-open body (health stays fast, then `408`), oversized declared body (`413`),
  chunked body (`411`), and the optional token (`401`/`200`).
