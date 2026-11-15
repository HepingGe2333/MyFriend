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
  `:agentcore`'s iOS targets (`ios/scripts/build-agentcore-framework.sh`). The older Swift mirror
  (`NativeAgentCoreClient`) is **compiled only in the macOS test build** as of 0.12.0 — the iOS app
  runtime has no second, executable decision implementation; both sides still read the same
  `spec/agent-core/cases/*.json` contract files.

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

Version: `0.11.1`. Included in the root build as `:agentd`, deliberately **not** a dependency
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
| `--rate-limit <n>` | `60` | Per-source requests per minute. `0` turns rate limiting **off**. Also `MYFRIEND_AGENTD_RATE_LIMIT`. |
| `--rate-burst <n>` | `30` | Token-bucket capacity (how many idle requests may burst). Must be ≥ 1. Also `MYFRIEND_AGENTD_RATE_BURST`. |
| `--help` / `-h` | — | Usage. |

Flags win over environment variables, which win over the defaults (so a container can pin the
values via env and still override one of them for a one-off test).

Smoke test:

```bash
curl -s http://127.0.0.1:8788/health
# {"ok":true,"service":"myfriend-agentd","version":"0.11.1"}

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
| Rate limit | 60 req/min per source, burst 30 (`RateLimiter`) | Over budget → `429 rate_limited` + `Retry-After: <seconds>`; `/health` has its own bucket; `--rate-limit 0` disables it (see [Rate limiting](#rate-limiting-0120)) |

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
and accidental exposure, not an active network attacker; for that you still need TLS/reverse proxy
(see [Deployment and TLS](#deployment-and-tls-0120)). The Android/iOS remote clients do not send
`Authorization` yet, so keep `--token` off until the clients are updated — or let the reverse proxy
inject the `Authorization: Bearer …` header (the proxy holds the token, clients only speak HTTPS).

## Rate limiting (0.12.0)

Every source (the request's TCP peer address, as a string) gets its own **token bucket**:

- refill rate `--rate-limit` per minute (default **60**, i.e. one token per second);
- capacity `--rate-burst` (default **30**): a client that has been idle may burst up to 30
  requests, then it is throttled to the refill rate;
- each request takes one token. When the bucket is empty the server answers
  `429 rate_limited` with a `Retry-After: <seconds>` header (always ≥ 1) **before** reading the
  request body, so an over-quota client cannot make the service read 1 MiB or parse JSON;
- the limiter runs **before authentication**: an over-quota client gets `429` even with a wrong
  token (it saves the constant-time compare, and leaves no `401` vs `200` difference to probe);
- `--rate-limit 0` / `MYFRIEND_AGENTD_RATE_LIMIT=0` disables it completely (no buckets are kept).

`GET /health` is rate limited **in its own bucket**. The trade-off:

- sharing one bucket would let business traffic (or — behind a reverse proxy — *everyone*, because
  all requests then come from the proxy's IP) exhaust the quota and turn health probes into `429`,
  which looks like "the service is down" to an orchestrator;
- a separate bucket keeps probes meaningful while still bounding `/health` (it is not a free
  bypass), at the cost of one extra counter per source.

Implementation notes (`agentd/src/main/kotlin/com/myfriend/agentd/RateLimiter.kt`):

- state per source is `(tokens, lastRefillNanos)` in a `LinkedHashMap(accessOrder = true)`; nothing
  runs in the background — a bucket refills lazily when that source next appears;
- the map is **bounded** (`DEFAULT_MAX_TRACKED_CLIENTS = 4096`). On overflow it first drops buckets
  that have already refilled to full (dropping those is equivalent to keeping them), then evicts
  the least-recently-used source. Scanning many spoofed sources therefore cannot grow memory;
- the limiter is checked per connection on the worker thread; a throttled client only gets an
  immediate `429`, so it never blocks other sources and never holds a worker for long;
- the clock is injectable (`clock: () -> Long`), so the unit tests never sleep.

**Proxy caveat:** this is *per TCP peer*, not per real user. Behind Caddy/Nginx/Traefik every
request arrives from `127.0.0.1`, so all users share one bucket — either raise `--rate-limit`
accordingly or do the per-user limiting at the proxy and treat `agentd`'s limiter as a
backstop. `X-Forwarded-For` is deliberately **not** trusted (it is trivially spoofable unless you
validate the proxy's address); if you need per-client limits behind a proxy, do it there.

## Deployment and TLS (0.12.0)

> **The service itself speaks plain HTTP only. Never expose it directly to the public internet.**
>
> There is no built-in TLS and no plan to add self-signed certificates: a hand-rolled TLS
> listener would mean owning certificate loading, ALPN/HTTP/2, cipher configuration and renewal —
> all of which a reverse proxy already does correctly. `agentd` stays a loopback-bound plaintext
> service; TLS terminates at the proxy.

Recommended shape:

```
App (HTTPS) ──► Caddy / Nginx / Traefik  (TLS, HTTP/2, edge limits)
                                   │  plain HTTP to 127.0.0.1
                                   ▼
                            agentd --host 127.0.0.1 --port 8788
```

Rules that matter:

1. **Bind to loopback.** Keep the default `--host 127.0.0.1` (or bind the proxy and `agentd` to
   distinct loopback addresses). `--host 0.0.0.0` is only for a trusted LAN / container network,
   and even then put auth in front.
2. **Terminate TLS in the proxy**, not in `agentd`. Get certificates from your proxy's ACME
   support (Caddy automatic HTTPS, certbot for Nginx, Traefik's resolver).
3. **Keep the token.** With a proxy in front, `--token` (or `MYFRIEND_AGENTD_TOKEN`) is still
   useful as a second factor, and you can keep it out of the client entirely: let the proxy add
   `Authorization: Bearer …` on the upstream request. Do not put the token in a URL or in the
   repository.
4. **Keep request buffering on** (Nginx default) so `agentd` receives `Content-Length`; with
   `proxy_request_buffering off` a chunked request from the client is forwarded as chunked and
   `agentd` answers `411 length_required` by design.
5. **Limit at both layers.** Keep `agentd`'s per-source bucket (it is the only thing that survives
   a proxy misconfiguration) and add an edge limit keyed by real client IP at the proxy.
6. **Log and monitor 429/5xx**, and expose only the four `/v1/core/*` routes plus `/health`.

Minimal Nginx (TLS + HTTP/2 + edge rate limit):

```nginx
# http {} block:
limit_req_zone $binary_remote_addr zone=agentd:10m rate=60r/m;

server {
    listen 443 ssl;
    http2 on;                       # nginx ≥ 1.25.1; older: `listen 443 ssl http2;`
    server_name agent.example.com;

    ssl_certificate     /etc/letsencrypt/live/agent.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/agent.example.com/privkey.pem;

    client_max_body_size 1m;        # agentd 自己也会拒绝 >1 MiB
    add_header Strict-Transport-Security "max-age=31536000" always;

    location / {
        limit_req zone=agentd burst=20 nodelay;

        proxy_pass http://127.0.0.1:8788;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        # 若启用了 --token，由代理注入（客户端不需要知道 token）：
        # proxy_set_header Authorization "Bearer <token>";
        proxy_request_buffering on;  # 上游必须收到 Content-Length（agentd 拒绝 chunked）
        proxy_read_timeout 10s;
    }
}
```

Minimal Caddy (automatic TLS, HTTP/2 and HTTP/3):

```caddyfile
agent.example.com {
    encode zstd gzip
    reverse_proxy 127.0.0.1:8788 {
        header_up Host {host}
        # 若启用了 --token，由 Caddy 注入；客户端只走 HTTPS：
        # header_up Authorization "Bearer <token>"
    }
}
```

Caddy's core has no rate limiter; either keep `agentd`'s bucket (default 60/min) or use a
rate-limit plugin at the edge. Traefik: use `reverseProxy` + `rateLimit` middleware the same way.

### Configure the clients against the proxy

Both mobile clients build the remote base URL from a build switch / environment variable, and the
release builds only allow **HTTPS** (cleartext is limited to debug builds / local development):

- **Android** (build switch; release forces in-process, so this is a debug/QA path):

  ```bash
  ./gradlew :app:assembleDebug     -PagentCoreRemote=true     -PagentCoreBaseUrl=https://agent.example.com
  ```

  `http://10.0.2.2:8788` still works for the emulator against a local, plaintext `agentd`;
  anything non-loopback should be `https://…` because the Android release manifest forbids
  cleartext. The path is appended to the base URL (`/v1/core/...`), so the proxy must forward
  those paths untouched (the examples above do).

- **iOS** (same `AgentCoreClient` layer, configured at launch):

  ```bash
  SIMCTL_CHILD_MYFRIEND_AGENT_CORE=remote \
  SIMCTL_CHILD_MYFRIEND_AGENT_CORE_URL=https://agent.example.com \
  xcrun simctl launch booted com.myfriend.app.ios
  ```

  The release Info.plist only keeps `NSAllowsLocalNetworking`; arbitrary cleartext is a
  debug-build-only thing (`Config/Info-Debug.plist`). Use an `https://` base URL in any build
  that leaves the device.

**Token vs. clients:** neither remote client sends `Authorization` today, so a token-enabled
`agentd` answers `401` to them. Either (a) keep `--token` off and rely on proxy auth (mTLS,
OAuth2-proxy, IP allowlist) — acceptable only because the service is not publicly reachable — or
(b) enable the token and have the proxy inject the header as shown above. Do not disable the token
"because it is only HTTPS": the proxy is exactly the place where an injected header keeps the
secret out of the clients while still authenticating them.

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
{"ok":true,"service":"myfriend-agentd","version":"0.11.1"}
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
| 429 | `rate_limited` | This source exceeded its token bucket; the response carries `Retry-After: <seconds>` (see “Rate limiting”). |
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

- **No TLS of its own.** It binds to `127.0.0.1` by default and speaks plain HTTP; TLS and
  HTTP/2 belong in the reverse proxy in front of it (see “Deployment and TLS” above). Since 0.11.1
  there is also an optional shared token (`--token`), a 1 MiB request-body cap, a 5 s read timeout
  and 4 fixed worker threads; since 0.12.0 a per-source token bucket (`--rate-limit` /
  `--rate-burst`). A token over plain HTTP is still not a substitute for TLS, and `0.0.0.0`
  remains “trusted network / behind a proxy only”.
- No persistence: it does not touch SQLite/Room, does not write `agent_intents`, does not store
  memories. It only exposes deterministic pure functions.
- No LLM calls, no prompting, no streaming.
- Android does not start it as an embedded service; remote mode is a development build switch.
  iOS does not call this service by default either: its in-process decisions run the same Kotlin
  `:agentcore` code, and remote mode (also a build/launch switch) points at this service. The old
  Swift mirror only exists in the macOS test build now — see `docs/IOS-PARITY.md`.
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
- `AgentdServerTest` — 8 integration tests on a real `ServerSocket`-based server (port 0): normal
  requests, half-open body (health stays fast, then `408`), oversized declared body (`413`),
  chunked body (`411`), the optional token (`401`/`200`), and 0.12.0 rate limiting
  (`429` + `Retry-After`, `/health` in its own bucket, `--rate-limit 0` disabling it).
- `MainOptionsTest` — 9 tests for flag/environment parsing and precedence (`--rate-limit`,
  `--rate-burst`, `MYFRIEND_AGENTD_RATE_LIMIT`, invalid values rejected before the server starts).
- `RateLimiterTest` — 7 tests for the token bucket itself (burst, refill, capped refill, per-source
  isolation, disabling, bounded map / LRU eviction) with an injected clock, so none of them sleep.

`:agentd:test` is 53 tests in total.
