# agentcore

The shared agent decision layer: **one implementation**, used by the Android app and by the
`agentd` service. Plain Kotlin/JVM with **zero third-party dependencies**, so it can be linked into
an Android app, a JVM service, or a test harness without dragging in a runtime.

```
agentcore/src/main/kotlin/com/myfriend/app/
├── domain/      decisions and pure helpers (sleep, defer_reply, proactive cap, mood, heartbeat,
│                memory lifecycle, vectors, search ranking/snippets, backup copy, erasure plan,
│                error model, path/URL policies, …)
└── core/json/   minimal recursive-descent JSON parser/encoder (shared by agentd and the
                 remote clients so there is exactly one parser in the repository)
```

Package names are deliberately unchanged from when these files lived in `app/.../domain/`, so
neither `:app` nor its tests needed import changes.

## Who depends on it

| Consumer | How it uses the layer |
| --- | --- |
| `:app` (Android) | `AgentCoreClient` — `InProcessAgentCoreClient` (default) or `RemoteAgentCoreClient` (OkHttp → `agentd`). Injected with Hilt; the remote implementation falls back in-process on any failure. |
| `:agentd` | Wraps `InProcessAgentCore` in HTTP (`POST /v1/core/*`) with the shared `AgentCoreProtocol` constants and `AgentCoreCodec`. |

`AgentCore` itself is a small synchronous interface:

```kotlin
interface AgentCore {
    fun sleepDecision(input: SleepInput): SleepDecision
    fun deferPlan(minutes: Int?, reason: String?, now: Long): DeferPlan
    fun proactiveCap(input: ProactiveCapInput): Int
    fun clampToolArgs(tool: String, args: Map<String, Any?>): Map<String, Any?>
}
```

`InProcessAgentCore` only shapes inputs/outputs and delegates to the existing pure functions
(`SleepPolicy`, `DeferReply`, `ProactiveLevel`/`MoodBehavior`, `ToolArgClamp`). No rule is duplicated.

## What does *not* belong here

- Anything touching Android, Room, Hilt, coroutines or kotlinx-serialization (those live in `:app`).
- State. Sessions, memories, intents and usage counters stay in the client database; this layer is
  decision-only, which is what makes embedding or serving it safe.
- Network calls. `agentd` owns transport; this module owns the codec constants and shape.

## Guarantees

| Mechanism | What it protects |
| --- | --- |
| `spec/agent-core/cases/*.json` (94 cases) | behaviour: the same JSON is asserted by this module's tests and by the iOS suite |
| `AgentCoreProtocol` field-name constants | the wire contract: `agentd`'s route table and both clients reference the same constants; a golden test cross-checks the Kotlin constants against the iOS client |
| `InProcessAgentCore` delegation | no second copy of a rule — the pure functions remain the only source of truth |

## Build & test

```bash
./gradlew :agentcore:test        # JUnit 4, no Android, no network
./gradlew :agentcore:build
```

Adding a decision: add the pure function here (never in `:app`), extend `AgentCore` +
`InProcessAgentCore`, add the field constants to `AgentCoreProtocol`, add cases to
`spec/agent-core/`, then wire the client on both platforms.
