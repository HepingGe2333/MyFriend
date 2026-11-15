# agentcore

The shared agent decision layer as a **Kotlin Multiplatform** module: **one implementation** used by
the Android app (`:app`), the JVM service (`:agentd`) and **iOS** (`AgentCoreKit.framework`).
Zero third-party runtime dependencies: `commonMain` uses only the Kotlin standard library, `jvmMain`
only the JDK, `iosMain` only Foundation.

```
agentcore/
├── build.gradle.kts                      kotlin("multiplatform")
│                                         targets: jvm() · iosX64() · iosArm64() · iosSimulatorArm64()
│                                         each iOS target exports a static AgentCoreKit.framework
└── src/
    ├── commonMain/kotlin/com/myfriend/app/
    │   ├── domain/                       47 files — every decision both platforms must share
    │   └── core/json/Json.kt              1 file — recursive-descent JSON parser/encoder
    ├── jvmMain/kotlin/com/myfriend/app/domain/
    │   ├── AgentTickLock.kt               java.util.concurrent.atomic
    │   ├── SessionGenerationRegistry.kt   synchronized
    │   ├── MediaOwnership.kt              java.io.File canonical paths
    │   ├── BoundedTextRead.kt             java.io InputStream
    │   ├── LanguageDirective.kt           java.util.Locale display names + String.format
    │   └── PlatformTime.jvm.kt            actual: System.currentTimeMillis / TimeZone
    ├── iosMain/kotlin/com/myfriend/app/domain/
    │   └── PlatformTime.ios.kt            actual: NSDate / NSTimeZone
    ├── commonTest/                        4 files / 42 tests (pure Kotlin; run on JVM)
    └── jvmTest/                           2 files / 18 tests (JDK APIs + spec/agent-core cases)
```

Package names are deliberately unchanged from when these files lived in `app/.../domain/`, so
neither `:app` nor its tests needed import changes.

## Source sets: what is shared and what is not

### `commonMain` — the decisions both platforms must agree on

All of the decision logic that iOS needs is here, including the ones that used to depend on JVM-only
APIs (they were rewritten, not stubbed): `SleepPolicy`, `DeferReply`, `ProactiveLevel`,
`Mood`/`MoodBehavior`/`MoodLexicon`, `MoodRecovery`, `ToolArgClamp`, `ReadReceipt`, `Heartbeat`,
`MessagePaging`/`MessageOrder`/`MessageJump`/`MessageWindows`, `SearchSnippet`/`SearchRanking`,
`ChineseTerms` (tokenizer **and** time-span parsing), `LocalEmbedding`, `VectorMath`,
`MemoryLifecycle`/`MemoryDrawer`/`MemoryReconsolidation`, `DataErasure`, `WebUrlPolicy`,
`LinkDetector`, `Presence`, `HistoryCompression`, the error model (`UserError`), plus
`AgentCore`/`InProcessAgentCore`/`AgentCoreCodec`/`AgentCoreProtocol` and the JSON parser.

Platform differences are expressed with **exactly two** `expect`/`actual` functions
(`Domain/PlatformTime.kt`), not by moving whole features to a platform source set:

| `expect` | JVM `actual` | iOS `actual` |
| --- | --- | --- |
| `nowMillis(): Long` | `System.currentTimeMillis()` | `NSDate().timeIntervalSince1970 * 1000` |
| `localUtcOffsetMillis(epochMillis): Int` | `TimeZone.getDefault().getOffset(...)` | `NSTimeZone.localTimeZone.secondsFromGMTForDate(...)` |

Everything else is pure Kotlin. In particular the calendar arithmetic (`LocalTimeMath`) is
hand-written (Howard Hinnant's civil-from-days), because both `java.util.Calendar` and `NSCalendar`
would have forced platform-specific code into every time-related decision. `jvmTest`'s
`LocalTimeMathParityTest` cross-checks it against `java.util.Calendar` day by day — including
America/New_York DST transitions.

### `jvmMain` — the five files that genuinely need a JVM

| File | Why it stays on the JVM |
| --- | --- |
| `AgentTickLock.kt` | `AtomicBoolean.compareAndSet`; backs the Android background loop's "one tick at a time" gate. |
| `SessionGenerationRegistry.kt` | `synchronized` over a shared map; session-generation guarding for Android background writes. |
| `MediaOwnership.kt` | `File.canonicalFile` + `File.separator`; the "is this file inside our media dir" delete guard is filesystem-specific (iOS uses its own sandbox APIs). |
| `BoundedTextRead.kt` | `java.io.InputStream`/`ByteArrayOutputStream`; bounded backup reading for the Android file picker. |
| `LanguageDirective.kt` | `java.util.Locale` display names (`getDisplayName`) and `String.format`; prompt-snippet assembly for the Android/iOS app UI. |

The iOS app does not call these; the decisions it needs are all in `commonMain`.

### API changes for common code

The rewrites below are behaviour-preserving; the original tests still pin them.

| Was | Now | Why |
| --- | --- | --- |
| `java.net.URI` / `URISyntaxException` in `WebUrlPolicy` | pure string parser (`isValidUriSyntax` + authority split) | `java.net` is JVM-only. The audit bypass table (`WebUrlPolicyHardeningTest`) is unchanged and green. |
| `java.nio.ByteBuffer` little-endian in `VectorMath` | hand-written little-endian via `Float.toBits`/`fromBits` | common Kotlin has no `ByteBuffer`; byte format is identical. |
| `java.util.PriorityQueue` in `VectorMath.topK` | small binary min-heap (same O(n log k), same tie-breaking) | `java.util` is JVM-only. |
| `java.util.Calendar` in `ChineseTerms` | `LocalTimeMath` + `expect localUtcOffsetMillis` | `Calendar` is JVM-only. |
| `java.time.Instant`/`ZoneId` in `MoodBehavior` | `LocalTimeMath.localHourOfDay` | `java.time` is JVM-only. |
| `System.currentTimeMillis()` default args | `nowMillis()` | `System` is JVM-only. |
| `String.format("%02d:%02d")` in `SleepPolicy` | `padTwo` string building | `String.format` is JVM-only. |
| `Locale.ROOT` lowercase in `ApiConfigRules` | `String.lowercase()` | common `lowercase()` is locale-independent already. |
| `Math.floor` in `Json` | `kotlin.math.floor` | `Math` is JVM-only. |

## Who depends on it

| Consumer | How it uses the layer |
| --- | --- |
| `:app` (Android) | `implementation(project(":agentcore"))` resolves the **`jvm()` variant** (Kotlin's `androidJvm` consumer is compatible with the `jvm` producer). `AgentCoreClient` — `InProcessAgentCoreClient` (default) or `RemoteAgentCoreClient` (OkHttp → `agentd`). |
| `:agentd` | Same project dependency, also the **`jvm()` variant**; its `installDist`/`fatJar` therefore ship `agentcore-jvm.jar` / the same classes. Wraps `InProcessAgentCore` in HTTP (`POST /v1/core/*`). |
| iOS (`ios/MyFriendKit`) | Links `AgentCoreKit.framework` (Kotlin/Native, static) and calls the Kotlin objects directly — see below. |

`AgentCore` itself is unchanged:

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

## Build & test

```bash
# JVM tests (commonTest + jvmTest) — still the single entry point, 60 tests
./gradlew :agentcore:test

# JVM jar + all iOS framework slices (no test execution)
./gradlew :agentcore:assemble
```

(`:agentcore:build` also runs the iOS simulator test binaries; on an Apple Silicon host the
`iosX64` test run needs Rosetta, so CI should use `:agentcore:test` + `:agentcore:assemble`.)

`:agentcore:test` is an alias for KMP's `jvmTest` task (registered in `build.gradle.kts`), so CI
scripts that call `:agentcore:test` keep working. Test source sets:

| Source set | Files | Tests | Content |
| --- | --- | --- | --- |
| `commonTest` | 4 | 42 | URL-policy hardening table, JSON/codec limits, and the KMP rewrite guards (`KmpCommonDecisionsTest`: vector encoding/topK, sleep clock text, defer clamp, Chinese time hints) |
| `jvmTest` | 2 | 18 | `AgentCoreSpecTest` (the shared `spec/agent-core/cases/*.json` contract through `AgentCore`, protocol pinning, JSON codec round-trips, malformed-response rejection) + `LocalTimeMathParityTest` (calendar arithmetic vs `java.util.Calendar`, default zone and DST) |

## iOS framework (`AgentCoreKit`)

All three iOS targets produce a **static** framework named `AgentCoreKit` (`isStatic = true`).

```bash
# one-shot: builds all three slices, copies them under ios/.build/agentcore/ and creates an xcframework
ios/scripts/build-agentcore-framework.sh

# or a single target through Gradle
./gradlew :agentcore:linkDebugFrameworkIosSimulatorArm64
./gradlew :agentcore:linkDebugFrameworkIosArm64
./gradlew :agentcore:linkReleaseFrameworkIosArm64
```

Output (default; `AGENTCORE_FRAMEWORK_OUT` overrides the directory):

```
ios/.build/agentcore/
├── AgentCoreKit.xcframework/
│   ├── ios-arm64/AgentCoreKit.framework                        # device
│   └── ios-arm64_x86_64-simulator/AgentCoreKit.framework       # lipo'd simulator slice
├── iosSimulatorArm64/AgentCoreKit.framework
├── iosArm64/AgentCoreKit.framework
└── iosX64/AgentCoreKit.framework
```

The script also honours `AGENTCORE_FRAMEWORK_CONFIGURATION=Release` and
`AGENTCORE_FRAMEWORK_TARGETS="iosSimulatorArm64"`. Kotlin/Native's toolchain lives in
`KONAN_DATA_DIR` (the script defaults it to `<repo>/.tooling/konan` because `~/.konan` may not be
writable in sandboxed CI; set it to `~/.konan` for a normal local setup).

Swift usage (the Kotlin `object` is exported as `shared`; a Kotlin `Int?` parameter is `KotlinInt?`):

```swift
import AgentCoreKit

let input = SleepInput(
    sleepEnabled: true,
    dndOnSleep: true,
    sleepMinutes: nil,
    wakeMinutes: nil,
    nowMinutes: 23 * 60 + 45,
    userMessagesInWindow: 0,
    wakeThreshold: 3
)
let decision = InProcessAgentCore.shared.sleepDecision(input: input)
// decision.wake == false, decision.asleep == true, decision.dnd == true, decision.marksRead == false

let plan = InProcessAgentCore.shared.deferPlan(
    minutes: KotlinInt(int: 9999), reason: String(repeating: "x", count: 100), now: 1_000
)
// plan.minutes == 720, plan.reason.count == 80

let rejection = WebUrlPolicy.shared.check(raw: "http://127.0.0.1/") // .localHost
```

### iOS integration checklist (for the Xcode / SPM owner)

The framework is **static** (`isStatic = true`, `MinimumOSVersion = 14.0`, module map
`AgentCoreKit.framework/Modules/module.modulemap`, umbrella header
`AgentCoreKit.framework/Headers/AgentCoreKit.h`). Static means **link it, do not embed it**.

**Option A — Xcode project file reference (simplest):**

1. Run `ios/scripts/build-agentcore-framework.sh` (or point `AGENTCORE_FRAMEWORK_OUT` at a
   directory inside the repo you want the output in).
2. Drag `ios/.build/agentcore/AgentCoreKit.xcframework` into the Xcode project navigator
   (uncheck “Copy items if needed”; it is a generated, git-ignored artifact).
3. Target → *General* → *Frameworks, Libraries, and Embedded Content* → add `AgentCoreKit`.
   Set **Embed = Do Not Embed** (a static framework has no runtime binary to ship).
4. No extra linker flags are needed when the xcframework is a project reference; Xcode selects
   the right slice itself. If you prefer a script-only setup (no project reference):
   - `FRAMEWORK_SEARCH_PATHS = $(inherited) $(SRCROOT)/../.build/agentcore/universal-simulator`
     for the simulator and `.../iosArm64` for device (or the matching
     `AgentCoreKit.xcframework/ios-…` slice directories);
   - `OTHER_LDFLAGS = $(inherited) -framework AgentCoreKit`;
   - `LD_RUNPATH_SEARCH_PATHS` / `Embed Frameworks` phase: **not** needed (static).
5. Optional build phase: a “Run Script” before *Compile Sources* calling
   `ios/scripts/build-agentcore-framework.sh`, with
   `AGENTCORE_FRAMEWORK_CONFIGURATION=${CONFIGURATION}` and the xcframework as an output file, so
   `Cmd-B` rebuilds it when the Kotlin sources change.
6. Swift 6 / Xcode 27: the Kotlin module is imported as a plain Objective-C framework. If strict
   concurrency diagnostics appear, build the app with `SWIFT_VERSION = 5` (what the smoke test
   uses) or `SWIFT_STRICT_CONCURRENCY = minimal`; the framework itself is Swift-concurrency
   agnostic.

**Option B — Swift Package (`binaryTarget`):**

`binaryTarget(path:)` must point **inside the package root**, so sync the generated xcframework
there first (e.g. via a script or a symlink-free copy):

```bash
AGENTCORE_FRAMEWORK_OUT="$PWD/ios/MyFriendKit/Vendor" ios/scripts/build-agentcore-framework.sh
# -> ios/MyFriendKit/Vendor/AgentCoreKit.xcframework  (add Vendor/ to .gitignore)
```

```swift
// Package.swift — MyFriendKit currently also builds for macOS (its `swift test` logic suite),
// so keep the iOS-only binary behind a platform condition and guard the import.
let package = Package(
    name: "MyFriendKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "MyFriendKit", targets: ["MyFriendKit"])],
    targets: [
        .binaryTarget(name: "AgentCoreKit", path: "Vendor/AgentCoreKit.xcframework"),
        .target(
            name: "MyFriendKit",
            dependencies: [
                .target(name: "AgentCoreKit", condition: .when(platforms: [.iOS]))
            ],
            path: "Sources/MyFriendKit"
        ),
        .testTarget(name: "MyFriendKitTests", dependencies: ["MyFriendKit"], path: "Tests/MyFriendKitTests")
    ]
)
```

```swift
#if canImport(AgentCoreKit)
import AgentCoreKit
#endif
```

- The import **must** be guarded (and the dependency platform-conditioned) as long as the package
  also supports macOS: `swift test` on macOS cannot link an iOS-only xcframework.
- Kit tests that exercise the Kotlin layer should run on an iOS simulator destination:
  `xcodebuild test -scheme MyFriendKit -destination 'platform=iOS Simulator,name=iPhone 17 Pro'`
  (a plain `swift test` runs on macOS). The current macOS logic tests can stay framework-free.
- App target: adding the package product is enough; the static framework is linked, not embedded.

**Swift interop cheat-sheet** (names verified against the generated header):

| Kotlin | Swift |
| --- | --- |
| `object InProcessAgentCore` | `InProcessAgentCore.shared` |
| `fun sleepDecision(input: SleepInput)` | `sleepDecision(input:)` |
| `Int?` parameter | `KotlinInt?` (`KotlinInt(int: 42)`, `nil`) |
| `Long` | `Int64` |
| `FloatArray` | `KotlinFloatArray` (`.size`, `.get(index:)`) |
| `List<T>` / `Map<K,V>` | `NSArray` / `NSDictionary` |
| nested enum `WebUrlPolicy.Rejection` | `WebUrlPolicy.Rejection` (`swift_name` in the header) |
| Kotlin exceptions | not exported (no `@Throws` in this module) |

**Verification recipe** (what was used to prove the framework works; requires a booted simulator):

```bash
SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
swiftc -swift-version 5 -target arm64-apple-ios15.0-simulator -sdk "$SDK" \
  -F ios/.build/agentcore/iosSimulatorArm64 -framework AgentCoreKit \
  /tmp/AgentCoreSwiftSmoke.swift -o /tmp/AgentCoreSwiftSmoke
xcrun simctl spawn booted /tmp/AgentCoreSwiftSmoke
```

## What does *not* belong here

- Anything touching Android, Room, Hilt, coroutines or kotlinx-serialization (those live in `:app`).
- State. Sessions, memories, intents and usage counters stay in the client database; this layer is
  decision-only, which is what makes embedding or serving it safe.
- Network calls. `agentd` owns transport; this module owns the codec constants and shape.

## Guarantees

| Mechanism | What it protects |
| --- | --- |
| `spec/agent-core/cases/*.json` | behaviour: the same JSON cases are asserted by this module's JVM tests and by the iOS suite |
| `AgentCoreProtocol` field-name constants | the wire contract: `agentd`'s route table and both clients reference the same constants |
| `InProcessAgentCore` delegation | no second copy of a rule — the pure functions remain the only source of truth |
| `LocalTimeMathParityTest` | the pure-Kotlin calendar arithmetic stays byte-for-byte equivalent to `java.util.Calendar` |

Adding a decision: add the pure function here (never in `:app`), extend `AgentCore` +
`InProcessAgentCore`, add the field constants to `AgentCoreProtocol`, add cases to
`spec/agent-core/`, then wire the client on both platforms. Keep it `commonMain`-clean — if it needs
a platform API, prefer a pure rewrite or a tiny `expect`/`actual` over moving it to `jvmMain`.
