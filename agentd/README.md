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
| `--data <dir>` | `./agentd-data` | Server-authoritative state directory (see [Server-authoritative mode](#server-authoritative-mode-服务端权威模式-0120)). A missing/empty directory is seeded with 3 personas + 1 session; an existing one is read back as-is. Also `MYFRIEND_AGENTD_DATA`. |
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

## Server-authoritative mode (服务端权威模式, 0.12.0)

This is the server side of `docs/design/server-authoritative.md` phase P1 (+ the server half of P2):
state, block-paged history, streaming chat and the incremental event stream all live in `agentd`.
Clients keep only UI and caches. The data format is plain JSON/JSONL under a data directory —
**deliberately no SQLite/JDBC** (the module has zero third-party dependencies; see
"Why this shape"). The trade-offs of that choice are listed under [Limits and risks](#limits-and-risks-of-this-phase).

### Data directory

```bash
agentd --data /var/lib/myfriend            # or: MYFRIEND_AGENTD_DATA=/var/lib/myfriend agentd
```

Default is `./agentd-data` (relative to the process working directory). The flag wins over the
environment variable, which wins over the default — same precedence as every other option.

```
<data>/
  meta.json                      {"version":1,"cursor":<long>,"seeded":<bool>}   ← global event cursor
  personas.json                  {"personas":[…]}                                ← the 3 seeds on first start
  sessions.json                  {"sessions":[…]}                                ← lastMessageAt / unread / lastBlockIndex
  moods.json  presence.json      {"moods":[…]} / {"presence":[…]}                ← mood.changed / presence.changed sources
  usage.json                     {"usage":{personaId:{promptTokens,completionTokens,replies}}}
  settings.json                  {"settings":{…}}                                ← whitelist only; never API keys
  api_configs.json               {"configs":[…]}                                 ← metadata only, **no keys**
  secrets.json                   {"keys":{…}}                                    ← 0600, server-side only, never in a response
  memories.json                  {"memories":[{id,personaId,content,importance,tags,createdAt,updatedAt}]}
  presets.json                   {"presets":[{id,name,…,worldJson,speechStyleJson}]}
  push_tokens.json               {"tokens":[{platform,token,bundleId,createdAt,updatedAt}]}
  events.jsonl                   one event per line, append-only                 ← GET /v1/events cursor
  sessions/<sessionId>/block_index.json   {"sessionId":…,"blocks":[{index,firstMessageId,lastMessageId,firstAt,lastAt,count}]}
  sessions/<sessionId>/blocks/<blockIndex>.jsonl   one message per line (append-only, UTF-8)
  media/                         directory only — the upload endpoint is a later phase
```

`memories.json`, `presets.json` and `push_tokens.json` are optional for old data directories
(missing = empty). `GET /v1/settings` and `GET /v1/backup` filter `settings.json` through a
whitelist, so even a hand-edited `apiKey` key never appears in a response or a backup. The
whitelist is: `themeMode`, `accentColor`, `fontScale`, `bubbleStyle`, `animationsEnabled`,
`hapticsEnabled`, `chatBackgroundColor`, `agentLoopEnabled`, `activePersonaId`,
`autoTranslatePersona`.

**Seeding.** A missing/empty directory is seeded with three personas and one empty session, so the
`curl` examples below work immediately. `:agentd` has no Android resources, so the ids are fixed
strings the server defines itself; the names match the Android seeds in `app/src/main/res/values/seed.xml`
(`values-en/seed.xml`) so the two sides can be compared:

| persona id | `name` | `nameEn` | default | seed mood | seed presence |
| --- | --- | --- | --- | --- | --- |
| `seed-linshen` | 林深 | Elias | yes | 平静 (2) | `IDLE` |
| `seed-alan` | 阿岚 | Finn | no | — | — |
| `seed-mo` | 墨 | Mo | no | — | — |

The seeded session is `seed-session-linshen` ("和林深的对话"). Seeding runs **once**: a directory
with data is read back as-is (`seeded` stays in `meta.json`). A directory that has data files but no
`meta.json` is refused instead of silently re-seeded, and a `meta.json` with an unknown `version`
fails startup with a readable message.

**Atomic writes.** Every whole-file JSON write goes to `<name>.json.tmp` first and is then moved over
the target with `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` (falling back to a plain replace on file
systems without atomic rename). Block files are append-only for new messages; delete/mark-read rewrites
one block file atomically. All mutations share a single `ReentrantLock`, so readers never observe a
half-written block index, and two requests cannot append the same block concurrently. There is **no
fsync**: a killed process keeps the data (rename is atomic), a power cut may lose the last few writes.
`*.tmp` files are never left behind by a completed write.

**Block maintenance** (same contract as `spec/messages-blocks/README.md`, `BLOCK_SIZE = 200`):
append fills the newest block; a full block starts `blockIndex + 1`; deleting a message recomputes
`first*/last*/count` of its block; deleting the last message of a block deletes the block's `.jsonl`
and removes the row from `block_index.json`, leaving a **numbering hole**. Blocks are never
rebalanced and never renumbered. Paging always asks the block table for the neighbouring existing
index, so holes are skipped. A missing `block_index.json` is rebuilt from the block files on first
access (manual recovery for the crash window described below).

### API

All endpoints sit behind the same `--token` / rate-limit / 1 MiB body cap as `/v1/core/*`, and errors
use the same `{"error":{"message":"…","code":"…"}}` shape.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/v1/state?since=&personaId=` | Snapshot. `since` filters personas/sessions by `updatedAt > since` and messages by `createdAt > since`. `serverCursor` is the current global event cursor. |
| `GET` | `/v1/sessions?personaId=` | Session list with `lastMessageAt`, `unread`, `lastBlockIndex` (plus `messageCount`/timestamps). |
| `POST` | `/v1/sessions` | `{personaId,title?}`. One session per persona; if it already exists the existing row is returned (`created:false`). |
| `POST` | `/v1/sessions/{id}/read` | `{upTo?}` → mark assistant messages read, `unread:0`, returns `{unread,readAt,marked,session}`. |
| `GET` | `/v1/sessions/{id}/messages?beforeBlock=&limitBlocks=1` | One block per page. No offset. `beforeBlock` = older page, `afterBlock` = newer page (additive extension for `hasMoreNewer` navigation). |
| `POST` | `/v1/sessions/{id}/messages` | Send a message; responds with SSE (`accepted → delta* → tool* → done`). |
| `GET` | `/v1/personas` | Persona list, including client fields (`worldJson`, `speechStyleJson`, `traits`, `translationJson`, …) and parsed `activity`. |
| `POST` | `/v1/personas` | Create (no `id`) or update (`id` must exist). Known core fields are typed; every other field is kept verbatim. |
| `DELETE` | `/v1/personas/{id}` | Delete persona + its sessions/messages/memories/activity/proactive counters/mood/presence. Media files are **not** deleted. |
| `POST` | `/v1/personas/{id}/activity` | Report/clear the current activity (M3 client integration). |
| `GET` | `/v1/personas/{id}/memories?limit=&since=` | Memories for a persona, newest `updatedAt` first. |
| `POST` | `/v1/personas/{id}/memories` | Create (no `id`) or update (`id` must exist and belong to this persona). |
| `DELETE` | `/v1/memories/{id}` | Delete one memory (404 when absent). |
| `GET` | `/v1/presets` | Persona presets including `worldJson` / `speechStyleJson`. |
| `POST` | `/v1/presets` | Create/update a preset; unknown fields are kept verbatim. |
| `DELETE` | `/v1/presets/{id}` | Delete a preset (404 when absent). |
| `GET` | `/v1/settings` | Whitelisted global settings (no API keys/secrets, ever). |
| `POST` | `/v1/settings` | Merge whitelisted fields; unknown keys (including `apiKey`) are ignored. |
| `GET` | `/v1/backup` | Export a v5 backup JSON (personas/sessions/messages/memories/presets/settings, **no secrets**). |
| `POST` | `/v1/backup` | `{mode:"merge"|"replace",data:{…}}`; validate everything first, then write. |
| `POST` | `/v1/push/register` | `{platform:"ios"|"android",token,bundleId?}`; idempotent per `platform+token`. |
| `DELETE` | `/v1/push/register` | Unregister by body `{token,platform?}` or query `?token=&platform=`; idempotent. |
| `GET` | `/v1/events?since=&timeoutSeconds=&limit=` | Incremental events (`message.created`, `mood.changed`, `presence.changed`). Long poll, 25 s max by default. |
| `POST` | `/v1/tools/web-search` | Server-side search; the search key never leaves the server (see below). |
| `POST` | `/v1/tools/web-fetch` | Server-side fetch of a public page, body text extracted. |
| `POST` | `/v1/proactive/tick` | Run one proactive heartbeat now (debug/testing; production is timer-driven). |

```bash
# 1. full snapshot (first sync: newest block per session + cursor for /v1/events)
curl -s "http://127.0.0.1:8788/v1/state"
# 2. incremental sync: only what changed after the last cursor/timestamp
curl -s "http://127.0.0.1:8788/v1/state?since=1700000000000&personaId=seed-linshen"
# 3. sessions
curl -s "http://127.0.0.1:8788/v1/sessions"
# 4. one block page (older: beforeBlock; newer: afterBlock)
curl -s "http://127.0.0.1:8788/v1/sessions/seed-session-linshen/messages?beforeBlock=1&limitBlocks=1"
# 5. send a message and read the stream (curl -N disables buffering)
curl -N -s -X POST "http://127.0.0.1:8788/v1/sessions/seed-session-linshen/messages" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"clientMessageId":"0b1c2d3e-0000-4000-8000-000000000001","content":"在吗"}'
# 6. long poll for events (wakes up as soon as something happens, max 25 s)
curl -s "http://127.0.0.1:8788/v1/events?since=0&timeoutSeconds=25"
# 7. server-side search (provider/base/key come from the server environment, not from this body)
curl -s -X POST "http://127.0.0.1:8788/v1/tools/web-search" \
  -H 'Content-Type: application/json' -d '{"query":"杭州 天气","count":6}'
# 8. fetch a public page; the answer is {"url","title","text"}
curl -s -X POST "http://127.0.0.1:8788/v1/tools/web-fetch" \
  -H 'Content-Type: application/json' -d '{"url":"https://example.com/"}'
# 9. run one proactive heartbeat immediately (debug endpoint; same now only fires once)
curl -s -X POST "http://127.0.0.1:8788/v1/proactive/tick" \
  -H 'Content-Type: application/json' -d '{"now":1700000000000}'
# 10. one session per persona; creating twice returns the existing row
curl -s -X POST "http://127.0.0.1:8788/v1/sessions" \
  -H 'Content-Type: application/json' -d '{"personaId":"seed-linshen"}'
# 11. mark a session read (unread → 0)
curl -s -X POST "http://127.0.0.1:8788/v1/sessions/seed-session-linshen/read" \
  -H 'Content-Type: application/json' -d '{}'
# 12. persona / memory / preset CRUD
curl -s -X POST "http://127.0.0.1:8788/v1/personas" \
  -H 'Content-Type: application/json' \
  -d '{"name":"新朋友","worldJson":"{\"worldName\":\"临港\"}","traits":["慢热"]}'
curl -s -X POST "http://127.0.0.1:8788/v1/personas/seed-linshen/memories" \
  -H 'Content-Type: application/json' -d '{"content":"他喜欢雨天","importance":80,"tags":["天气"]}'
curl -s "http://127.0.0.1:8788/v1/personas/seed-linshen/memories?limit=20"
curl -s -X POST "http://127.0.0.1:8788/v1/presets" \
  -H 'Content-Type: application/json' -d '{"name":"夏夜","worldJson":"{}"}'
# 13. backup (v5, no secrets) and merge/replace import
curl -s "http://127.0.0.1:8788/v1/backup" > backup.json
curl -s -X POST "http://127.0.0.1:8788/v1/backup" \
  -H 'Content-Type: application/json' \
  -d "{\"mode\":\"merge\",\"data\":$(cat backup.json)}"
# 14. push token registration (APNs/FCM delivery is deployment-side)
curl -s -X POST "http://127.0.0.1:8788/v1/push/register" \
  -H 'Content-Type: application/json' -d '{"platform":"ios","token":"device-token","bundleId":"com.example.myfriend"}'
curl -s -X DELETE "http://127.0.0.1:8788/v1/push/register" \
  -H 'Content-Type: application/json' -d '{"platform":"ios","token":"device-token"}'
```

### CRUD, backup and push (M3–M6 server side)

The routes in the table above follow the same token/rate-limit/1 MiB/error-body rules as the rest
of `/v1`. All writes go through the single `FileStore` lock, so a read-modify-write update (for
example merging fields into one persona) cannot lose a concurrent request's field. Errors are
`404 not_found` for unknown ids, `400 missing_field` / `invalid_argument` / `invalid_json` for bad
input, `409 unsupported_backup_version` when an import is newer than v5, and `405
method_not_allowed` for a known path with the wrong verb.

**Personas, memories and presets.** These endpoints accept the client field names directly.
- `POST /v1/personas`: no/`null` `id` creates a new persona (server-generated id); a non-null `id`
  updates that existing persona and is `404` when it does not exist. Core fields (`name`,
  `nameEn`, `description`, `avatarUrl`, `isDefault`, `activityJson`) are typed; **every other key**
  (`worldJson`, `speechStyleJson`, `traits`, `likes`, `behaviors`, `backstory`, `quirks`,
  `translationJson`, `translationLang`, `personaCardJson`, unknown future fields) is stored and
  returned as raw JSON. Missing fields on update keep their old value. Setting `isDefault:true`
  clears the other personas' default flag.
- `DELETE /v1/personas/{id}` removes the persona's sessions, block files/messages, memories,
  activity (stored on the persona row), proactive counters, mood and presence. `media/` is left
  alone — the client owns media GC (contract §3.6).
- Memories live in `memories.json`; `since` filters on `updatedAt > since`, `limit` is 1–500
  (default 200). `tags` is a JSON string array on the wire (the client database stores it as a
  JSON string, but the HTTP/backup contract uses an array).
- Presets live in `presets.json`. They carry `worldJson` / `speechStyleJson` and keep unknown
  fields verbatim.
- Settings are merged through a whitelist and filtered on every read/export. There is **no API
  key route**: `apiKey`, `apiKeys`, `secrets` and friends are ignored by the write path and can
  never appear in a response or backup.

**Backup v5.** `GET /v1/backup` returns:

```json
{
  "format": "myfriend.backup",
  "formatVersion": 5,
  "appVersion": "0.12.0",
  "exportedAt": 1700000000000,
  "apiKeysOmitted": true,
  "personas": [ … ],
  "sessions": [ … ],
  "messages": [ … ],
  "memories": [ … ],
  "presets": [ … ],
  "settings": { … }
}
```

`POST /v1/backup` takes `{"mode":"merge"|"replace","data":{…}}` (the `data` object is exactly the
export shape). `merge` updates/inserts by id and keeps everything not mentioned; `replace` clears
personas/sessions/messages/memories/presets/settings (and orphaned mood/presence/proactive rows)
before importing. Validation of ids, references, uniqueness, roles and importance happens **before
any mutation**, so a bad request leaves the data directory untouched. It is not a database
transaction: a process kill between the multiple atomic file writes can leave a partially imported
directory (see risks). Only `formatVersion == 5` is accepted; anything else is `409
unsupported_backup_version`. `usage.json`, `api_configs.json`, `secrets.json`, `push_tokens.json`
and media are not part of a backup and are preserved by `replace`.

> Format-version note: the client's `BACKUP_FORMAT_VERSION` is **5** and the M3–M6 write-path task
> asks for v5, so this implementation reads/writes v5. The design document's endpoint table still
> says "v6"; if that becomes the intended contract, bump the client constant, this constant and the
> accepted version together (never silently accept both and guess).

**Push registration (M6 server half).** `POST /v1/push/register` stores
`{platform,token,bundleId}` in `push_tokens.json` (idempotent on `platform+token`); `DELETE`
removes it by body or query. `agentd` does **not** implement APNs or FCM itself. It exposes a
pluggable interface:

```kotlin
fun interface PushSender {
    fun send(tokens: List<PushRegistration>, message: PushMessage)
}
```

The default is `NoopPushSender`, so without credentials the server simply persists the proactive
message and clients catch up via `/v1/state`, `/v1/events` or SSE. When tokens exist,
`ProactiveTicker` calls the injected sender after the proactive message is committed; a sender
failure is logged and never rolls back the message or breaks `POST /v1/proactive/tick`. Production
credentials and the actual APNs/FCM HTTP client are **deployment-side** — wire a real `PushSender`
through `AgentdServices.pushSender`; nothing secret belongs in this repository.

### Server-side search, fetch and proactive tick (0.12.0)

The mobile clients no longer hold search credentials or call providers themselves: these three
routes run on the server. The request/response shapes are intentionally small:

| Method | Path | Request | Response |
| --- | --- | --- | --- |
| `POST` | `/v1/tools/web-search` | `{"query":"…","count":6,"provider":"MESSAGEAPI\|TAVILY\|SEARXNG\|BING\|ZHIHU\|TOUTIAO\|QUORA"}` (`count`, `provider` optional) | `{"provider":"…","results":[{"title","url","snippet"}]}` |
| `POST` | `/v1/tools/web-fetch` | `{"url":"https://…"}` | `{"url","title","text"}` (scripts/styles/tags removed, whitespace collapsed, ≤ 8000 chars) |
| `POST` | `/v1/proactive/tick` | optional `{"now":<epoch millis>}` (defaults to the server clock) | `{"now","fired","decisions":[{"personaId","sessionId","messageId","reason"}]}` |

Provider configuration is **server-side only**:

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `MYFRIEND_AGENTD_SEARCH_PROVIDER` | `MESSAGEAPI` | Default provider when the request body does not name one. Dirty values are normalised like every other provider setting. |
| `MYFRIEND_AGENTD_SEARCH_BASE` | — | Provider base URL. `MESSAGEAPI` / `SEARXNG` / `GENERIC` have no fixed default and need it; Tavily/Brave/Serper/Bing fall back to their public endpoints. |
| `MYFRIEND_AGENTD_SEARCH_KEY` | — | Provider API key. **The client never sends it and the server never accepts one from the request body.** |

- **Only `SEARXNG` may override the base per request** (`{"provider":"SEARXNG","baseUrl":"http://…"}`):
  a self-hosted instance address is not a secret, and one deployment may point at different instances.
  For every other provider the request `baseUrl` is ignored — otherwise a client could aim the server's
  key at an arbitrary host (SSRF + credential exfiltration).
- A body-level `apiKey` is ignored for the same reason: the key always comes from
  `MYFRIEND_AGENTD_SEARCH_KEY`. Neither the key nor the upstream error body (which may echo it) appears
  in responses or logs; the HTTP client's log preview replaces the secret with `***`.
- Results are normalised exactly like the clients' `WebSearchClient`: non-http(s) URLs dropped,
  deduplicated by URL (first wins), snippets tag-stripped and capped at 500 chars, at most `count`
  (1–12) items.
- Error mapping: `400` for an empty query, missing key, missing/invalid base or a rejected URL;
  `401` when the provider rejects the key; `429` when the provider rate limits; every other upstream
  failure (`403`, `4xx`, `5xx`, timeouts, unparsable responses…) is `502`. Error bodies keep the usual
  `{"error":{"message","code"}}` shape (`search_bad_request` / `search_unauthorized` /
  `search_rate_limited` / `search_upstream_error`).
- `web-fetch` always connects **directly** (`config = null`; it does not use `MESSAGEAPI`'s
  `tools/fetch`) and goes through the same `WebUrlPolicy` as the clients: http(s) only, no loopback /
  private hosts / userinfo.
- `proactive/tick` reads personas, sessions, the latest `lastMessageAt` and mood from the data
  directory, then runs the same `ProactiveEngine` as the timer. A fired decision persists one
  `role=ASSISTANT, source=PROACTIVE` message into that persona's most recently active session and
  emits the usual `message.created` event. The same `now` only fires once (in-process `lastTickAt`
  dedupe); the route answers `503 store_disabled` when the server was started without `--data`.


Trimmed responses (0.12.0, `EchoReplyGenerator`):

```json
{"serverTime":1700000000000,
 "personas":[{"id":"seed-linshen","name":"林深","nameEn":"Elias","avatarUrl":null,"isDefault":true,"updatedAt":1000}],
 "sessions":[{"id":"seed-session-linshen","personaId":"seed-linshen","title":"和林深的对话",
              "lastMessageAt":null,"unread":0,"lastBlockIndex":-1}],
 "messages":[],
 "mood":{"personaId":"seed-linshen","label":"平静","intensity":2,"updatedAt":1000},
 "presence":{"personaId":"seed-linshen","kind":"IDLE","detail":"在家，安静地待着","updatedAt":1000},
 "serverCursor":0,
 "hasMoreMessages":false}
```

`hasMoreMessages` is an additive field: the first snapshot returns **only the newest block per
session** (the same rule as the clients' initial block load), so clients continue with the paging
endpoint for older history. `since > 0` walks blocks from newest to oldest and stops at
`MAX_STATE_MESSAGES = 500` (also flagged by `hasMoreMessages`).

```json
{"sessionId":"seed-session-linshen","blockIndex":0,"hasMoreOlder":false,"hasMoreNewer":false,
 "messages":[{"id":"…","sessionId":"seed-session-linshen","blockIndex":0,"role":"USER",
              "content":"在吗","createdAt":1001,"readAt":null,"attachments":[],
              "source":"CHAT","clientMessageId":"0b1c…","replyTo":null}]}
```

`blockIndex` is `null` when the requested page does not exist (paging past either end, or an empty
session); `hasMoreOlder`/`hasMoreNewer` then tell the client which direction still has content.
`limitBlocks` only accepts `1` (otherwise `400 unsupported_limit_blocks`) — a page is one block by
contract.

SSE response (`curl -N`; `Content-Length` is intentionally absent, the stream ends when the server
closes the connection):

```
event: accepted
data: {"userMessageId":"…","createdAt":1001}

event: delta
data: {"text":"你说：「在吗」\n收到啦，我在。"}

event: delta
data: {"text":"（agentd 回显回复：还没接 LLM…）"}

event: done
data: {"assistantMessageId":"…","usage":{"promptTokens":2,"completionTokens":34}}
```

### SSE: idempotency and "keep generating after the client leaves"

- `clientMessageId` is the idempotency key (required, ≤ 128 chars). The user message is persisted
  **before** the first byte is written, and the `accepted` event carries its id.
- The assistant message is persisted with `source: "CHAT"` and `replyTo: <userMessageId>`; a second
  POST with the same `clientMessageId` finds both and replays `accepted` + `done` **without calling
  the generator again**. Concurrent duplicates are serialised on a per-key lock and also generate
  exactly once (the follower replays).
- If the user message is there but its reply is not (the process died between the two writes), the
  next request generates the missing reply once.
- **Disconnect handling:** the generator keeps running when the socket is gone. `HttpSseSink` turns a
  failed write into a `broken` flag (logged once) instead of an exception, and `ChatService`'s
  `SafeSink` swallows write failures, so the delta loop always reaches the persist step. The assistant
  message, its usage and its event land in the store even if nobody received the stream; the client
  picks it up via `/v1/state` or `/v1/events` on the next sync.
- `event: error` (`generation_failed`) ends the stream without `done`; whatever text was already
  generated is still persisted and counted. A generator that produced *nothing* persists no empty
  assistant message.
- Token usage (`promptTokens`/`completionTokens`) is supplied by the generator and accumulated per
  persona in `usage.json`; the same numbers are stored on the assistant message so a replayed `done`
  stays truthful.

### Events

Each line of `events.jsonl` (and each `events[]` item) is:

```json
{"cursor":3,"type":"message.created","at":1002,"sessionId":"seed-session-linshen","personaId":"seed-linshen",
 "payload":{"message":{…}}}
```

`mood.changed` / `presence.changed` carry `"payload":{"mood":{…}}` / `"payload":{"presence":{…}}` and
have `sessionId: null`. Cursors come from `meta.cursor`, increase by one per event and never get
reused; `GET /v1/events?since=<cursor>` returns events with a strictly larger cursor. When the long
poll times out, it answers `200` with an empty `events` array and **the same cursor** — clients must
not advance their cursor on an empty page. `AgentdServer` keeps a 20 000-event in-memory window and
falls back to reading `events.jsonl` for older cursors.

### Limits and risks of this phase

Honest list; most of these are deliberate scope cuts, not oversights.

- **One page = one block, no offset.** `limitBlocks` must be `1`; `beforeBlock`/`afterBlock` pick the
  neighbouring *existing* block, so a hole (deleted block) is skipped. Blocks shrink but never merge.
- **Long poll and SSE occupy HTTP worker threads** (`WORKER_THREADS = 4`, fixed). A 25 s long poll
  holds one worker for its whole wait, and a generation holds one while streaming; five concurrent
  `/v1/events` therefore starve `/health`/other requests until they time out. Fine for the intended
  single-user deployment, but moving long poll/SSE onto their own threads is a P2/P3 follow-up.
- **SSE has no write timeout.** Java sockets only expose a read timeout, so a client that opens the
  stream and then stops reading can block one worker on a full send buffer for as long as the OS
  keeps the connection. Combined with the point above this is a denial-of-service risk on a hostile
  network; keep the service behind the token/reverse proxy until streaming moves to dedicated
  threads with a write deadline.
- **No SQL, no transactions.** All state is JSON/JSONL with one process-wide write lock. Queries are
  bounded per block, but idempotency lookups build a per-session in-memory index by scanning blocks
  once (then maintained incrementally). Running two `agentd` processes against one data directory is
  **not** supported — the lock is in-process only. Backup import validates everything before mutating
  memory/disk, but the multi-file write itself is not an atomic transaction: a process kill in the
  middle can leave a partially imported directory (re-import from the same backup to finish).
- **No fsync / crash window.** Rename gives atomic replacement, and a killed process keeps
  consistent files, but a message line that was appended before `block_index.json`/`sessions.json`
  were rewritten can leave the block count off by one. Recovery: delete that session's
  `block_index.json` and let the server rebuild it from the block files (it is not automatic). A torn
  trailing line in a block/event file is skipped with a warning instead of losing the file.
- **No event compaction, no delete events.** `events.jsonl` grows forever (`message.created` for
  user and assistant, `mood.changed`, `presence.changed` only). Deleting a message (`FileStore.deleteMessage`,
  used by tests) does not emit an event, so an incremental client will not learn about deletions
  until a full sync / a P3 delete-event contract.
- **Read receipts are all-or-nothing per session.** `POST /v1/sessions/{id}/read` always sets
  `unread = 0`; `upTo` only limits which messages get a `readAt` timestamp. This matches the
  contract phrase `{upTo?} → unread=0`, but it means a client cannot represent "I read only the
  older half and still have something new" through this endpoint.
- **No push protocol, no background scheduler wiring.** `PushSender` is an interface and
  `NoopPushSender` is the default; APNs/FCM credentials, HTTP/2 clients and retry policy are
  deployment-side. Tokens are stored, but a proactive message is only pushed if a real sender was
  injected; without one, clients catch up on the next foreground sync. The proactive timer itself is
  still the existing debug tick path (`POST /v1/proactive/tick` / in-process scheduler), not a new
  daemon.
- **No media upload yet.** `media/` exists and attachments are persisted as opaque `mediaId`
  strings; `/v1/media/{id}` is a later phase.
- **No LLM and no prompts.** `EchoReplyGenerator` echoes the user text plus one fixed Chinese
  sentence and estimates tokens by character count. The server neither accepts prompts from clients
  nor stores them; prompt construction is the next phase's job. `secrets.json` (0600, best-effort on
  non-POSIX file systems) is the only place an API key may live and it is never part of a response.
- **Search credentials are environment config, not a settings API.** `MYFRIEND_AGENTD_SEARCH_*` is
  read through `AgentdServices.env` (production: `System.getenv`) on every request, but a JVM cannot
  change its own environment, so rotating the key or the default provider needs a restart. There is
  also no per-persona search configuration yet: the whole server shares one provider/base/key. The
  search/fetch paths are only verified against local fake HTTP servers in CI — no real provider or
  web page is contacted by the tests.
- **`proactive/tick` is a debug entry point, not the production scheduler.** A server timer is the
  next phase; the HTTP route is what the tests and `curl` use. De-duplication of the same `now` and
  the "just fired" overlay are **in-process** (`ProactiveTicker` → `ProactiveScheduler`), so they do
  not survive a restart and are not shared between processes (running two processes on one data
  directory is unsupported anyway). Today's proactive count and last-fired timestamp **are**
  persisted in `proactive_state.json` (0.12.0); the remaining in-process pieces are only the
  same-`now` dedupe and the "just fired" overlay. A production timer still needs to be wired to
  `ProactiveTicker`; the HTTP route is the current debug/test entry point.
- **Still plain HTTP.** The token/rate-limit docs above apply unchanged; TLS belongs to the reverse
  proxy.

### Tests for this phase

`FileStoreTest` (19) covers seeding/read-back, block splitting at 200, delete boundary recomputation,
emptied-block holes, missing-index rebuild, atomic writes (no `*.tmp`, parseable JSON), 8 threads ×
125 concurrent appends with no loss, idempotency lookup, `secrets.json` permissions, usage,
event cursors/long-poll timeout, torn-line tolerance and path-traversal guards.
`StateApiTest` (18) covers full/incremental state, persona filtering, session list fields, both paging
directions, hole skipping, invalid paging parameters, events long poll (immediate/timeout/wake-up)
and the "no data directory → 503" path. `ChatSseTest` (9) covers event order, tool forwarding,
sequential and concurrent idempotency, orphan-reply completion, a dead sink, attachment persistence
and validation-before-stream. `AuthorityServerTest` (6) is the real-socket half: SSE over HTTP, a
client that disconnects mid-stream still gets a persisted reply, token/rate limit on the new routes,
long poll over HTTP and JSON errors before a stream starts. `MainOptionsTest` grew to 11 with the
`--data` precedence/validation cases. `ToolsRoutesTest` (17) covers the three new routes: provider /
base / key resolution (`MESSAGEAPI` default, `SEARXNG` base override, request `baseUrl` ignored for
every other provider, client `apiKey` ignored), result normalisation, the 400/401/429/502 error
mapping, "the key never appears in a response or a log", tag-stripped fetch + `URL_REJECTED`, and
proactive tick (one `source=PROACTIVE` assistant message persisted + event emitted, same `now` fires
once, `now` defaults to the server clock, `503` without a data directory), plus one real-socket case
proving `AgentdServer` dispatches the new paths behind the same `--token`.
`CrudRoutesTest` (23) covers sessions (`POST`, one-per-persona, read/upTo), persona create/update/
delete with unknown-field preservation and cascade cleanup, memory/preset/settings CRUD, 400/404/405,
concurrent updates of one persona (no lost fields), and restart persistence. `BackupApiTest` (12)
covers v5 export contents/no secrets, replace/merge semantics, export→import→export equality,
version-6 → 409, reference validation before any write, and restart. `PushRoutesTest` (8) covers
registration/idempotency/unregistration/persistence, the `PushSender` invocation after a proactive
message, no-token silence, and a throwing sender that must not break the tick.

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
| 400 | `invalid_backup` | Backup export body failed validation (duplicate ids, missing persona/session references, bad role/importance…). No data was written. |
| 401 | `unauthorized` | `--token` is set and the request has no/incorrect `Authorization: Bearer …`. |
| 404 | `not_found` | Unknown route (the path is not echoed back). |
| 405 | `method_not_allowed` | Known route with the wrong method. |
| 408 | `request_timeout` | The request body did not arrive within `READ_TIMEOUT_MS`. |
| 409 | `unsupported_backup_version` | `POST /v1/backup` received a `formatVersion` other than 5 (newer backups are refused instead of half-imported). |
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
- **No SQL/Room.** Since 0.12.0 it *does* persist server-authoritative state (personas, sessions,
  block-paged messages, memories, presets, mood/presence, push tokens, usage, API config metadata) as
  JSON/JSONL under `--data`, and expose v5 backup export/import. It still does not touch the clients'
  Room database, migrate existing client data automatically, or store media files/thoughts. Earlier
  versions only exposed deterministic pure functions; see
  [Server-authoritative mode](#server-authoritative-mode-服务端权威模式-0120).
- No LLM calls: `POST /v1/sessions/{id}/messages` streams, but the reply comes from the injectable
  `EchoReplyGenerator` (no prompting, no model call, no tool loop yet).
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
- `FileStoreTest` — 19 storage tests: seeding/read-back, block split at 200, delete boundary
  recomputation, emptied-block holes (no renumbering), missing-`block_index.json` rebuild, atomic
  writes (no `*.tmp`, parseable JSON), 8×125 concurrent appends with no loss/duplication,
  `clientMessageId` lookup, `secrets.json` 0600 permissions, usage accumulation, event cursor +
  long-poll timeout, torn-line tolerance, and path-traversal rejection.
- `StateApiTest` — 18 handler-level tests for `/v1/state` (full/incremental/persona filter/bad
  params/`hasMoreMessages`/no secrets), `/v1/sessions` (fields, filter), one-block paging in both
  directions (hole skipping, `blockIndex: null` past the ends, invalid `limitBlocks`/offsets) and
  `/v1/events` (immediate/timeout/wake-up/negative cursor), plus `503` without a data directory.
- `ChatSseTest` — 9 tests for the SSE send path: event order (`accepted → delta → done`), tool
  event forwarding, sequential **and concurrent** `clientMessageId` idempotency, orphaned
  user-message completion, a dead sink that must not stop generation, attachment persistence, and
  validation-before-stream.
- `AuthorityServerTest` — 6 real-socket tests: state + SSE chat over HTTP (`text/event-stream`, no
  `Content-Length`, frame order), a client disconnecting mid-stream still getting the reply
  persisted, `--token`/rate limiting on the new routes, HTTP long poll waking up on a new message,
  and JSON errors before a stream starts.
- `MainOptionsTest` — 11 tests for flag/environment parsing and precedence (`--rate-limit`,
  `--rate-burst`, `--data`/`MYFRIEND_AGENTD_DATA`, invalid values rejected before the server starts).
- `RateLimiterTest` — 7 tests for the token bucket itself (burst, refill, capped refill, per-source
  isolation, disabling, bounded map / LRU eviction) with an injected clock, so none of them sleep.
- `ToolsRoutesTest` — 17 tests for the three 0.12.0 routes (`/v1/tools/web-search`,
  `/v1/tools/web-fetch`, `/v1/proactive/tick`): provider/base/key resolution and overrides, result
  normalisation, 400/401/429/502 mapping, "the key never leaks", fetch text extraction and
  `URL_REJECTED`, and proactive tick persistence/dedupe/`503`. All upstreams are local
  `com.sun.net.httpserver` fakes; the proactive cases use a temporary `--data` directory.

`:agentd:test` is 329 tests in total in 0.12.0 — the authoritative-mode suites above plus the
`search/` (52) and `proactive/` (68) suites added in the same release. The 43 new tests for
M3–M6 server write paths are `CrudRoutesTest`, `BackupApiTest` and `PushRoutesTest`.
