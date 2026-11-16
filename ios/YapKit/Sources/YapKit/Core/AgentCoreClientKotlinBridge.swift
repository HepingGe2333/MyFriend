import Foundation

// iOS App 构建里 `KotlinBoolean` 用来区分「Kotlin 装箱的 Boolean」与 CFBoolean；
// macOS `swift test` 没有这个模块，`isBoolean` 里那段会被条件编译掉。
#if os(iOS)
import AgentCoreKit
#endif

// ---------------------------------------------------------------------------
// `[String: JSONValue]` ⇄ Kotlin `Map<String, Any?>` 的桥（0.12.0）。
//
// 为什么单独一层：Kotlin 侧 `InProcessAgentCore.clampToolArgs(tool:args:)` 的 `args` 跨 ObjC
// 桥之后就是一个 `NSDictionary`（Swift 侧 `[String: Any]`）。这一层只做「JSON 值树 ⇄ ObjC
// 对象图」的搬运，不引用任何 Kotlin 符号 —— 所以它在 macOS `swift test` 里也能单测
// （`AgentCoreKotlinBridgeTests`），不必起模拟器。
//
// 两个已知陷阱（写死在 `box` / `isBoolean` 里）：
// 1. Swift 的 `Int` 跨桥会变成 Kotlin `Long`。Kotlin 的 `intArgument` 只认 Int / Long /
//    整数值 Double / 数字字符串，送错类型会**静默**回落到缺省值 —— 不报错，只是 iOS 和
//    Android 给出不同答案；
// 2. Kotlin 的 `Boolean` 回到 Swift 是 `KotlinBoolean`（`NSNumber` 子类，但**不是** CFBoolean）。
//    只按 `CFGetTypeID == CFBooleanGetTypeID()` 判断会把 `true` 读成整数 1。
// ---------------------------------------------------------------------------

// MARK: - JSON 桥（纯 Foundation，macOS 上也能单测）

/// `[String: JSONValue]` ⇄ Kotlin `Map<String, Any?>` 的转换。
///
/// Kotlin `InProcessAgentCore.clampToolArgs(tool:args:)` 的 `args` 跨 ObjC 桥之后就是一个
/// `NSDictionary`（Swift 侧 `[String: Any]`）。这里只做「值树 ⇄ ObjC 对象图」的搬运：
///
/// - 进来的 `JSONValue` 全部换成 ObjC 桥能认的 Foundation 值（`NSNull` / `Bool` /
///   `Int64` / `Double` / `String` / `[Any]` / `[String: Any]`）；
/// - 回去的字典按「JSON 值树」重新归类，保证 `.int` / `.double` / `.bool` / `.null`
///   与 Swift 实现的 `AgentCoreJSON.value(from:)` 同语义（整数值的 Double 仍按整数读）。
///
/// 为什么值类型要写死：Kotlin 侧 `intArgument` 只认 `Int` / `Long` / 整数值 `Double` /
/// 数字字符串，而 `true` / `null` / 对象一律当「没给」，回落到缺省值。桥如果送错类型，
/// 夹取就会静默退回默认值 —— 行为差异不会报错，只会让 iOS 和 Android 给出不同答案。
enum AgentCoreKotlinJSONBridge {

    // MARK: JSONValue → Kotlin Map<String, Any?>

    /// 工具参数表 → 跨桥字典。
    static func boxedArguments(_ args: [String: JSONValue]) -> [String: Any] {
        args.mapValues(box)
    }

    /// 单个 JSON 值 → ObjC 桥能携带的值。
    static func box(_ value: JSONValue) -> Any {
        switch value {
        case .null:
            return NSNull()
        case .bool(let flag):
            // 刻意用 Swift `Bool`（桥成 NSNumber 的布尔型），不用 `KotlinBoolean`：
            // 后者的装箱实例回到 Swift 时不是 CFBoolean，布尔字段会被读成 1/0。
            return flag
        case .int(let number):
            // Kotlin 侧是 `Long`（`Int64` 桥过去正好是 Long），数值语义与 Swift 的 `Int` 一致。
            return Int64(number)
        case .double(let number):
            return number
        case .string(let text):
            return text
        case .array(let items):
            return items.map(box)
        case .object(let fields):
            return fields.mapValues(box)
        }
    }

    // MARK: Kotlin Map<String, Any?> → JSONValue

    /// 跨桥字典 → 工具参数表。
    static func jsonArguments(_ map: [String: Any]) -> [String: JSONValue] {
        map.mapValues(jsonValue)
    }

    /// 单个跨桥值 → JSON 值树。
    static func jsonValue(_ raw: Any) -> JSONValue {
        switch raw {
        case is NSNull:
            return .null
        case let number as NSNumber:
            if isBoolean(number) { return .bool(number.boolValue) }
            let double = number.doubleValue
            if double.isFinite, double.rounded() == double, let int = Int64(exactly: double) {
                return .int(int)
            }
            return .double(double)
        case let text as String:
            return .string(text)
        // `NSDictionary` / `NSArray` 在这里会桥成 Swift 集合（Kotlin 原样传回来的嵌套结构
        // 也可能是 ObjC 的 `_SwiftDeferredNSDictionary`），两种形状都由同一个 case 收。
        case let fields as [String: Any]:
            return .object(fields.mapValues(jsonValue))
        case let items as [Any]:
            return .array(items.map(jsonValue))
        default:
            // Kotlin 侧不该出现别的类型；真的出现也不抛错（工具参数夹取不该因为一个
            // 不认识的字段让整轮聊天失败），按 null 处理并在下面留了测试。
            return .null
        }
    }

    /// 这个 `NSNumber` 是不是布尔。
    ///
    /// 两条来源：Swift 的 `Bool`（桥成 CFBoolean）与 Kotlin 的 `Boolean`（装箱成
    /// `KotlinBoolean`，是 `NSNumber` 子类但**没有** CFBoolean 的 type id）。
    /// 只判断前者的话，`true` 会掉进数值分支变成 1 —— 工具参数里的布尔字段会被改写。
    static func isBoolean(_ number: NSNumber) -> Bool {
        if CFGetTypeID(number) == CFBooleanGetTypeID() { return true }
        #if os(iOS)
        return number is KotlinBoolean
        #else
        // macOS（swift test）上不存在 KotlinBoolean：这里只剩 CFBoolean 一种布尔来源，
        // 上面那行已经把 Swift `Bool` 收掉了。
        return false
        #endif
    }
}
