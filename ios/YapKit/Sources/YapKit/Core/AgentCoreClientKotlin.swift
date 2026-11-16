import Foundation

// ---------------------------------------------------------------------------
// agent 决策层的「本机实现」在 iOS 上走 Kotlin（0.12.0）。
//
// 背景：`agentcore/` 从 0.12.0 起是 Kotlin Multiplatform（`commonMain` / `jvmMain` /
// `iosMain`），iOS 侧产出静态 framework `AgentCoreKit`。也就是说
// `InProcessAgentCore`（睡眠判定 / defer 计划 / 主动上限 / 工具参数夹取）现在只有**一份**
// 实现，Android、`agentd`（Linux/JVM）和 iOS 读的是同一批 `spec/agent-core/cases/*.json`。
//
// 这一层要解决的事：
//
// - `KotlinAgentCoreClient`：`AgentCoreClient` 的四个方法原样转发给 Kotlin
//   `InProcessAgentCore.shared`，只做**参数整形与返回类型转换**（不复制任何判定逻辑）。
//   与 `NativeAgentCoreClient`（Swift 实现，0.12.0 起只在 macOS 测试构建里存在）在同一张
//   协议后面，可以互换；iOS App 的运行时里只有这一份 Kotlin 实现。
// - `AgentCoreKotlinJSONBridge`（`AgentCoreClientKotlinBridge.swift`）：
//   `[String: JSONValue]` ⇄ Kotlin `Map<String, Any?>` 的桥。那一段是纯 Foundation
//   （不引用任何 Kotlin 符号），所以 macOS 的 `swift test` 也能单测。
//
// ## 为什么 Swift 实现还在
//
// `ios/YapKit` 是个本地 SPM 包，`swift test` 在 **macOS** 上跑；macOS 链接不了
// iOS 的 Kotlin/Native framework。所以：
//
// - iOS App 构建（Xcode，`FRAMEWORK_SEARCH_PATHS` 指向 `AgentCoreKit.xcframework`）：
//   `#if os(iOS)` 成立 → 工厂默认返回 `KotlinAgentCoreClient`；
// - macOS `swift test`：`os(iOS)` 不成立 → Swift `NativeAgentCoreClient`（那个类型被
//   `#if !os(iOS)` 包着，iOS 构建里根本不存在）。
//
// 判定为什么用 `os(iOS)` 而不是 `canImport(AgentCoreKit)`：`canImport` 在 framework 缺失时
// 是**静默**退成 Swift —— 编译通过、测试通过、只有 App 在悄悄跑另一份实现；更要命的是
// Swift 的模块缓存会把那次「canImport=false」的判定记住，之后 framework 回来了也不重编。
// 用 `os(iOS)` + 无条件 `import AgentCoreKit`，framework 缺失就是编译错误，一眼看得见。
//
// 两边共用 `spec/agent-core/cases/*.json`；`AgentCoreKotlinParityTests`（iOS 模拟器上跑）
// 把同一批用例同时喂给两条实现，逐字段比对。
//
// ## 类型转换对照（Kotlin/Native ⇄ Swift）
//
// | Kotlin                                   | Swift（这两个 Core/AgentCoreClientKotlin*.swift）       |
// | ---------------------------------------- | ------------------------------------------------------ |
// | `SleepInput.nowMinutes: Int`             | `Int32(clamping:)`（Swift 侧是 `Int`，越界夹住不崩）   |
// | `SleepInput.sleepMinutes: Int?`          | `KotlinInt(int:)` / `nil`（Kotlin 可空基本类型=装箱类） |
// | `SleepDecision.wake/moodRelaxed/marksRead` | `Bool`（直接映射）                                   |
// | `DeferPlan.minutes: Int`                 | `Int32` → `Int(plan.minutes)`                          |
// | `DeferPlan.earliestAt/latestAt: Long`    | `Int64`（直接映射）                                    |
// | `proactiveCap(input:)` 返回 `Int`        | `Int32` → `Int(...)`                                   |
// | `clampToolArgs` 的 `Map<String, Any?>`   | `NSDictionary<NSString *, id>` ⇄ `[String: Any]`        |
// | Kotlin `Boolean`（装箱）                 | `KotlinBoolean`（`NSNumber` 子类，**不是** CFBoolean）  |
// | Kotlin `Int/Long/Double`（装箱）         | `KotlinInt` / `KotlinLong` / `KotlinDouble`（都是 NSNumber） |
//
// 两个坑写在这里，免得下次再踩：
//
// 1. Kotlin/Native 把 Swift 的 `Int` 桥成 Kotlin `Long`（不是 `Int`）。Kotlin 侧
//    `intArgument` 对 `is Long` 有分支并做了 Int 范围检查，所以数值语义一致；
// 2. Kotlin 的 `Boolean` 回到 Swift 是 `KotlinBoolean`（`NSNumber` 子类，但不带
//    CFBoolean 的 type id）。只按 `CFGetTypeID == CFBooleanGetTypeID()` 判断会把
//    `true` 读成整数 1，工具参数里凡是布尔字段都会被悄悄改成 1/0。
// ---------------------------------------------------------------------------

// MARK: - Kotlin 实现

#if os(iOS)

import AgentCoreKit

/// `AgentCoreClient` 的 Kotlin 实现：四个方法原样转发 `InProcessAgentCore.shared`。
///
/// 与 `NativeAgentCoreClient` 的关系：**同一张协议背后的两条实现**，判定逻辑都来自同一份
/// `:agentcore` 真源（Swift 那份是 0.12.0 之前就已经对齐过的移植版，只给 macOS `swift test`
/// 用）。两个类都不含规则，只有参数整形 —— 改规则只改 `agentcore/`。
public struct KotlinAgentCoreClient: AgentCoreClient {

    public init() {}

    /// 它就是「本机实现」：零网络请求、零超时，只是内核换成了 Kotlin。
    ///
    /// 不新增 `AgentCoreMode` 取值：`AgentCoreMode` 描述的是「本机 / 远程」，不是「哪种语言的本机」。
    /// 调用点里所有「本机不会失败、不需要回退」的判断都按 `.native` 走，行为一字不差。
    public var agentCoreMode: AgentCoreMode { .native }

    // MARK: 睡眠

    public func sleepDecision(_ input: SleepInput) async throws -> SleepDecision {
        let decision = InProcessAgentCore.shared.sleepDecision(input: Self.kotlinInput(input))
        // `SleepDecision` 在 Kotlin 里是 commonMain 的 data class：四个字段一一对应。
        // `asleep` / `dnd` / `replyDecision` 都是 Swift 侧的计算属性，不跨桥。
        return SleepDecision(
            wake: decision.wake,
            moodRelaxed: decision.moodRelaxed,
            reason: decision.reason,
            marksRead: decision.marksRead
        )
    }

    // MARK: defer_reply

    public func deferPlan(minutes: Int?, reason: String?, now: Int64) async throws -> DeferPlan {
        let plan = InProcessAgentCore.shared.deferPlan(
            minutes: Self.kotlinOptionalInt(minutes),
            reason: reason,
            now: now
        )
        // `minutes` 在 Kotlin 是 `Int`（= Int32），`earliestAt` / `latestAt` 是 `Long`（= Int64）。
        return DeferPlan(
            minutes: Int(plan.minutes),
            reason: plan.reason,
            earliestAt: plan.earliestAt,
            latestAt: plan.latestAt
        )
    }

    // MARK: 主动上限

    public func proactiveCap(level: Int, moodIntensity: Int?) async throws -> Int {
        // `moodLabel` 恒为 nil：协议里只有强度、没有心情标签，Kotlin
        // `InProcessAgentCore.proactiveCap` 在标签为空时只按主动程度换算（与 Swift 实现一致，
        // 心情修正留在调用点的 `MoodBehavior.effectiveDailyCap` 里）。`moodIntensity` 照样传过去，
        // 让两端拿到**同一个**输入 —— 将来 Kotlin 侧如果用上它，这边不用改。
        let cap = InProcessAgentCore.shared.proactiveCap(
            input: AgentCoreKit.ProactiveCapInput(
                level: Int32(clamping: level),
                moodLabel: nil,
                moodIntensity: Self.kotlinOptionalInt(moodIntensity)
            )
        )
        return Int(cap)
    }

    // MARK: 工具参数

    public func clampToolArgs(tool: String, args: [String: JSONValue]) async throws -> [String: JSONValue] {
        // 值树的换入换出见 `AgentCoreKotlinJSONBridge`：Kotlin 只夹已知数值键，
        // 其余键（包括 null / 布尔 / 嵌套对象）原样带回来，所以桥必须是**无损**的。
        let clamped = InProcessAgentCore.shared.clampToolArgs(
            tool: tool,
            args: AgentCoreKotlinJSONBridge.boxedArguments(args)
        )
        return AgentCoreKotlinJSONBridge.jsonArguments(clamped)
    }

    // MARK: 参数整形

    static func kotlinInput(_ input: SleepInput) -> AgentCoreKit.SleepInput {
        AgentCoreKit.SleepInput(
            sleepEnabled: input.sleepEnabled,
            dndOnSleep: input.dndOnSleep,
            sleepMinutes: kotlinOptionalInt(input.sleepMinutes),
            wakeMinutes: kotlinOptionalInt(input.wakeMinutes),
            // Kotlin 侧这三个字段是 `Int`（Int32）：Swift 侧是 `Int`（Int64）。
            // 用 `Int32(clamping:)` 而不是 `Int32(truncatingIfNeeded:)`：超出范围时夹到边界，
            // 与「分钟数/阈值」这类有界值本身的语义一致，也不会产生负数回绕。
            nowMinutes: Int32(clamping: input.nowMinutes),
            userMessagesInWindow: Int32(clamping: input.userMessagesInWindow),
            wakeThreshold: Int32(clamping: input.wakeThreshold)
        )
    }

    /// Kotlin 的可空 `Int?` 在 Swift 侧是装箱类 `KotlinInt`（`nil` = Kotlin 的 `null`）。
    static func kotlinOptionalInt(_ value: Int?) -> KotlinInt? {
        guard let value else { return nil }
        return KotlinInt(int: Int32(clamping: value))
    }
}

#endif
