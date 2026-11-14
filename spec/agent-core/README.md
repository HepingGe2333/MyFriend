# Agent core contract (`spec/agent-core/`, 0.10.0)

This directory is the **executable contract** for the agent logic that Android and iOS must agree
on. It contains language-neutral JSON fixtures (`cases/`) and nothing else: two test suites — one in
the Android app, one in `ios/MyFriendKit` — read the *same files* and feed them into their own
implementations. If one side changes behavior without the other, one of the two suites goes red on
the next test run.

The product spec (`docs/ROADMAP-1.9.md`) stays the source of truth for *what should happen*. These
fixtures pin down the concrete values that both implementations must produce for that spec.

## Layout

```
spec/agent-core/
├── README.md
└── cases/
    ├── sleep-decision.json   # 睡眠 / 免打扰 / 唤醒阈值 / 被叫醒后的心情
    ├── defer-plan.json       # defer_reply 时间计划（5–720、默认 45、窗口 +30 分钟）
    ├── proactive-cap.json    # proactiveLevel 1–5 → 每日上限 / 心跳权重 / 人话解释
    ├── heartbeat.json        # 心跳曲线采样（含心情放大、深夜折扣、per-beat 换算）
    ├── memory-decay.json     # 记忆衰减、retention 系数、沉底/归档/遗忘
    └── tool-args.json        # 工具参数夹取（importance / count / minutes）
```

Every file is plain JSON. A file looks like this:

```json
{
  "contract": "agent-core/sleep-decision",
  "version": "0.10.0",
  "title": "睡眠 / 免打扰 / 唤醒阈值判定",
  "authority": "android",
  "authorityNote": "规格见 docs/ROADMAP-1.9.md §六；…",
  "kotlin": { "file": "app/src/main/java/com/myfriend/app/domain/SleepPolicy.kt",
              "symbol": "SleepPolicy.evaluate（心情恢复：MoodRecovery.afterWake）" },
  "swift":  { "file": "ios/MyFriendKit/Sources/MyFriendKit/Domain/SleepSchedule.swift",
              "symbol": "SleepGate.evaluate（心情恢复：SleepGate.moodAfterWaking）" },
  "inputSpec": { "sleepEnabled": "…", "nowMinutes": "…" },
  "tolerance": 0.000000001,
  "cases": [
    {
      "name": "dnd-never-wakes-and-never-marks-read",
      "why": "免打扰：不回、不唤醒、也不留已读（§6.2）",
      "input": { "sleepEnabled": true, "sleepMinutes": null, "wakeMinutes": null,
                 "nowMinutes": 120, "dndOnSleep": true, "userMessagesInWindow": 99,
                 "wakeThreshold": 1 },
      "expect": { "asleep": true, "dnd": true, "woken": false,
                  "shouldReply": false, "marksRead": false }
    }
  ]
}
```

Top-level fields (all required except `tolerance`):

| Field | Meaning |
| --- | --- |
| `contract` | Stable id, also used as the test's display name. |
| `version` | Contract version; bumped together with the app version. |
| `authority` | `android` = Android is currently the implementation the other side aligns to. |
| `authorityNote` | In one sentence: which part of the roadmap is the product truth, and why. |
| `kotlin` / `swift` | File + symbol under test on each platform. Keep these exact and current. |
| `inputSpec` | Field-by-field dictionary; the JSON itself cannot carry comments. |
| `tolerance` | Absolute tolerance for floating-point expectations (default `1e-9`). |
| `cases[]` | `name` (unique, kebab-case), `input`, `expect`, optional `why`. |

## Rules for cases

1. **Pure functions only.** No database, no network, no file system, no locale-dependent clock.
2. **Time is an explicit input.** Anywhere a timestamp is needed, the case passes `now` (millis) or
   `nowMinutes`; tests never call `System.currentTimeMillis()` for expectations.
3. **Floating point is compared with a tolerance.** `exp()` on the JVM and on Darwin can differ in
   the last bit. `tolerance` exists for that; never assert bit-exact doubles.
4. **Chinese strings are part of the contract when both platforms own them** (e.g. the
   `proactiveLevel` description, the forget reasons). If only one platform has a string, the case
   must not assert it.
5. **Cover the edges:** lower/upper clamp, out-of-range and non-integer-ish inputs, empty/null
   values, cross-midnight windows, mood effects, provider/field changes, and "dirty data"
   (sleep == wake, negative minutes, threshold 0/99).
6. **Do not encode implementation details.** A case asserts observable output (`dailyCap`,
   `earliestAt`, `probability`), not internal counters.

## How to add a case

1. Pick the file whose `kotlin`/`swift` symbols own the behavior (add a new file only for a new
   contract area).
2. Append a case with a unique `name` and a one-line `why` when the boundary is not obvious.
3. Compute `expect` from the **authority** implementation, not from the platform you happen to be
   editing.
4. Run both suites (below). If the other platform disagrees, list the difference and align it
   (see the report convention in `docs/`); never "fix" the case to make one platform pass.
5. If you add/rename a function, update `kotlin`/`swift` in the file header.

## How to run

Android (JUnit 4, `app/src/test/java/com/myfriend/app/spec/AgentCoreSpecTest.kt`):

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home \
  ANDROID_HOME=$PWD/.tooling/android-sdk ANDROID_USER_HOME=$PWD/.tooling/android-user \
  GRADLE_USER_HOME=$PWD/.tooling/build-home TMPDIR=$PWD/.tooling/tmp
./.tooling/gradle-8.9/bin/gradle -p . :app:testDebugUnitTest \
  --tests com.myfriend.app.spec.AgentCoreSpecTest --console=plain
```

The Android test resolves the repository root by walking up from `System.getProperty("user.dir")`
until it finds `settings.gradle.kts` + `spec/agent-core/cases` (Gradle's working directory for unit
tests is usually `app/`, but it can be the repo root in an IDE). If it cannot find the root it fails
loudly with the starting directory; it never hard-codes an absolute path.

iOS (XCTest, `ios/MyFriendKit/Tests/MyFriendKitTests/Spec/AgentCoreSpecTests.swift`):

```bash
cd ios/MyFriendKit
TMPDIR=$PWD/../.build/tmp CLANG_MODULE_CACHE_PATH=$PWD/../.build/clang-cache \
SWIFT_MODULE_CACHE_PATH=$PWD/../.build/swift-cache \
swift test --disable-sandbox --filter AgentCoreSpecTests
```

The iOS test derives the repository root from `#filePath` (no absolute paths): it walks up from the
test source file until it finds `spec/agent-core/cases` + `settings.gradle.kts`.

Both suites print the file name and case `name` in every failure message, so a red test tells you
exactly which contract entry broke.

## Coverage

| File | Cases | Kotlin under test | Swift under test |
| --- | ---: | --- | --- |
| `sleep-decision.json` | 16 | `SleepPolicy.evaluate`, `MoodRecovery.afterWake` | `SleepGate.evaluate`, `SleepGate.moodAfterWaking` |
| `defer-plan.json` | 9 | `DeferReply.plan` | `DeferReply.plan` |
| `proactive-cap.json` | 14 | `ProactiveLevel.*`, `MoodBehavior.effectiveDailyCap` | `ProactiveLevel.*`, `MoodBehavior.effectiveDailyCap` |
| `heartbeat.json` | 17 | `Heartbeat.probability / perBeatProbability / shouldFire / minutesBetween` | same |
| `memory-decay.json` | 22 | `MemoryLifecycle.effectiveStrength / decayStep / retentionFactor / nextStatus` | same |
| `tool-args.json` | 16 | `ToolArgClamp.*` | `ToolArgClamp.*` |
| **total** | **94** | | |

## Design notes (0.10.0, measured on this machine)

1. **Why contract-first instead of going straight to KMP.** The two codebases are not two skins on
   one core: Android is Kotlin + Room + Compose, iOS is Swift + SQLite + SwiftUI, and the domain
   directory is only a *slice* of the agent logic (the orchestration lives in `data/repo/*` and
   `Services/*`). A KMP conversion would have to rewrite storage, DI and serialization before a
   single invariant was protected. The fixtures deliver protection **now**, with zero production
   risk: no app behavior changes just to run them.
2. **What the contract actually verifies.** 94 cases across 6 areas; both suites read the same
   file bytes. In this round the Android suite (`AgentCoreSpecTest`, 6 test methods) runs inside
   the normal `:app:testDebugUnitTest` (639 tests total, 0 failures), and the iOS suite
   (`AgentCoreSpecTests`, 6 methods) runs inside `swift test` (826 tests total after the i18n
   changes of the same round).
3. **`kotlin("jvm")` availability.** The plugin *marker*
   (`org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.0.21`) was **not** in the
   local Gradle cache at first, so an offline build failed to resolve it. Network was reachable in
   this environment; resolving once cached the marker and the plugin, and subsequent builds succeed
   with `--offline`. On a truly air-gapped machine, either pre-seed that marker or apply the
   already-cached `org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21` via `buildscript` classpath
   and `apply(plugin = "org.jetbrains.kotlin.jvm")` (not verified here).
4. **Which domain files cannot leave Android, and why.** 14 of the 35 Kotlin domain files are
   excluded from `agentd`: `AgentActions`, `ApiCost`, `Attachments`, `ImportanceScoring`,
   `MemoryScreening`, `PersonaCard`, `ToolProtocol`, `ToolTrace` (kotlinx-serialization);
   `BackupCodec`, `Models`, `PersonaProfile` (Room entities); `LlmConcurrencyGate`
   (kotlinx-coroutines); `OpenSessionTracker` (Hilt/`javax.inject`); `Recall` (depends on
   `MemoryScreening.MemoryRow`). The remaining 21 are stdlib/JDK-only and compile unchanged.
5. **One small extraction was required.** `MemoryDrawer` used to live in `MemoryScreening.kt`
   (which needs kotlinx-serialization). It moved to `domain/MemoryDrawer.kt` so
   `MemoryLifecycle.kt` can be reused by `agentd` without dragging the serialization library in.
   Same package, same enum names — Android behavior and DB/backup encodings are untouched.
6. **The service is real, but deliberately stateless.** `agentd` builds (`:agentd:build`), runs
   (`:agentd:run`, `installDist`, or the self-contained `fatJar`), and answers
   `GET /health` + 4 `POST /v1/core/*` routes over a real JDK `HttpServer`. Its runtime classpath is
   `agentd.jar` + `kotlin-stdlib` (+ the stdlib's `annotations` transitive); there is no web
   framework, JSON library, DI or coroutines. It stores nothing, calls no LLM and has no auth.
7. **Embedded vs standalone is a client-side choice, not a fork.** The service exposes pure
   functions, so the same code can be (a) linked into an Android process (same classpath, direct
   calls) or (b) called over localhost/HTTP by Android/iOS. State stays where it already lives:
   Room/SQLite on the client. That is what keeps offline-first intact: losing the service only
   loses a calculator, not the user's data.
8. **KMP vs service.** KMP is the right tool for sharing *stateful orchestration* (agent loop,
   prompt assembly, streaming) once the storage layer has a common shape. A standalone service is
   the right tool for heavy or privileged JVM work (vector search, backup codecs, server-side
   deployment) and for non-Kotlin clients. This round chose the contract because it is the only one
   of the three that is both zero-risk and immediately enforceable.
9. **Two-sided failures were found and fixed instead of hidden.** The contract surfaced real
   divergences (DND read receipts, wake-mood intensity, `minutesUntilWake` when awake, missing
   `memoryRetention` decay on Android, missing `proactiveLevel` heartbeat weight on iOS, and the
   same-sleep/wake fallback). Each fix is documented in the delivery report, and the affected
   existing tests were updated only where they encoded the old, divergent behavior.
10. **Next step (not done in this round).** Turn the four endpoints into a small client interface
    on both platforms (`AgentCoreClient` with `embedded` / `http` implementations), then move the
    remaining pure algorithms (`BackupCodec` minus Room types, `Recall` scoring) into the shared
    source set. Only after that is it worth evaluating KMP for the orchestrator.
