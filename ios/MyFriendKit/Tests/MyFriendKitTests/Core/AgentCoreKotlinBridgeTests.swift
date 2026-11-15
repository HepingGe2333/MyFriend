import Foundation
import XCTest
@testable import MyFriendKit

/// `[String: JSONValue]` ⇄ Kotlin `Map<String, Any?>` 的桥（`AgentCoreKotlinJSONBridge`）。
///
/// 这一段是纯 Foundation，不引用任何 Kotlin 符号，所以**在 macOS 上就能单测**；
/// 「真的调用了 Kotlin 的 `clampToolArgs`」由 `ios/MyFriendTests/AgentCoreKotlinParityTests.swift`
/// 在模拟器上比两条实现的输出（那边链接 `AgentCoreKit.framework`）。
///
/// 为什么值得单独测：桥只在「类型送错」时出错，而送错的后果不是崩溃，是 Kotlin 侧
/// `intArgument` 认不出这个值 → 静默回落到缺省值。iOS 与 Android 会给出不同答案，却谁都不报错。
final class AgentCoreKotlinBridgeTests: XCTestCase {

    // MARK: - 1. 换出去：JSONValue → 跨桥值

    func testBoxUsesTypesKotlinCanRecognize() {
        // Kotlin 的 `intArgument` 只认 Int / Long / 整数值 Double / 数字字符串。
        // Swift 的 `Int` 跨桥会变成 Kotlin `Long`，所以整数一律按 Int64 送。
        XCTAssertEqual(AgentCoreKotlinJSONBridge.box(.int(9)) as? Int64, 9)
        XCTAssertTrue(AgentCoreKotlinJSONBridge.box(.int(-3)) is Int64)
        XCTAssertEqual(AgentCoreKotlinJSONBridge.box(.double(1.5)) as? Double, 1.5)
        XCTAssertEqual(AgentCoreKotlinJSONBridge.box(.bool(true)) as? Bool, true)
        XCTAssertEqual(AgentCoreKotlinJSONBridge.box(.string("她妹妹叫小雨")) as? String, "她妹妹叫小雨")
        XCTAssertTrue(AgentCoreKotlinJSONBridge.box(.null) is NSNull)
    }

    func testBoxKeepsNestedShape() throws {
        let boxed = AgentCoreKotlinJSONBridge.box(.object([
            "a": .int(1),
            "b": .array([.bool(false), .string("x"), .null]),
            "c": .object(["d": .double(2.0)])
        ]))
        let fields = try XCTUnwrap(boxed as? [String: Any])
        XCTAssertEqual(fields["a"] as? Int64, 1)
        let array = try XCTUnwrap(fields["b"] as? [Any])
        XCTAssertEqual(array.count, 3)
        XCTAssertEqual(array[0] as? Bool, false)
        XCTAssertEqual(array[1] as? String, "x")
        XCTAssertTrue(array[2] is NSNull)
        let nested = try XCTUnwrap(fields["c"] as? [String: Any])
        XCTAssertEqual(nested["d"] as? Double, 2.0)
    }

    /// `Bool` 必须用 Swift 原生 `Bool`（桥成 CFBoolean），不能用 `KotlinBoolean`：
    /// 后者回到 Swift 时不是 CFBoolean，布尔字段会被读成 1/0。
    func testBoxedBooleanIsCFBoolean() throws {
        let boxed = try XCTUnwrap(AgentCoreKotlinJSONBridge.box(.bool(true)) as? NSNumber)
        XCTAssertEqual(CFGetTypeID(boxed), CFBooleanGetTypeID())
    }

    // MARK: - 2. 换回来：跨桥值 → JSONValue

    func testJSONValueReadsFoundationValues() {
        XCTAssertEqual(AgentCoreKotlinJSONBridge.jsonValue(NSNull()), .null)
        XCTAssertEqual(AgentCoreKotlinJSONBridge.jsonValue("x"), .string("x"))
        XCTAssertEqual(AgentCoreKotlinJSONBridge.jsonValue(NSNumber(value: true)), .bool(true))
        XCTAssertEqual(AgentCoreKotlinJSONBridge.jsonValue(NSNumber(value: false)), .bool(false))
        // 整数（含负数、超过 Int32 的值）：必须回到 `.int`，不能被当成 Double。
        XCTAssertEqual(AgentCoreKotlinJSONBridge.jsonValue(NSNumber(value: 1)), .int(1))
        XCTAssertEqual(AgentCoreKotlinJSONBridge.jsonValue(NSNumber(value: -7)), .int(-7))
        XCTAssertEqual(
            AgentCoreKotlinJSONBridge.jsonValue(NSNumber(value: Int64(9_000_000_000))),
            .int(9_000_000_000)
        )
        XCTAssertEqual(AgentCoreKotlinJSONBridge.jsonValue(NSNumber(value: 1.5)), .double(1.5))
        // 整数值的 Double 按整数读（与 `AgentCoreJSON.value(from:)` 同语义）。
        XCTAssertEqual(AgentCoreKotlinJSONBridge.jsonValue(NSNumber(value: 2.0)), .int(2))
    }

    func testJSONValueReadsCollections() {
        XCTAssertEqual(
            AgentCoreKotlinJSONBridge.jsonValue(["a": NSNumber(value: 1), "b": "x"] as NSDictionary),
            .object(["a": .int(1), "b": .string("x")])
        )
        XCTAssertEqual(
            AgentCoreKotlinJSONBridge.jsonValue([NSNumber(value: 1), NSNull()] as NSArray),
            .array([.int(1), .null])
        )
    }

    /// 不认识的类型不让整轮聊天失败：按 `null` 处理（工具参数只夹已知数值键）。
    func testJSONValueFallsBackToNullForUnknownTypes() {
        XCTAssertEqual(AgentCoreKotlinJSONBridge.jsonValue(Date()), .null)
    }

    // MARK: - 3. 往返无损（桥的核心断言）

    /// 工具参数的完整往返：Kotlin 只夹已知数值键、其余原样带回，所以桥必须无损。
    func testArgumentsRoundTripIsLossless() {
        let args: [String: JSONValue] = [
            "content": .string("她妹妹叫小雨"),
            "drawer": .string("USER"),
            "importance": .int(9),
            "ratio": .double(0.25),
            "flag": .bool(true),
            "off": .bool(false),
            "nothing": .null,
            "nested": .object(["a": .int(1), "b": .array([.string("x"), .int(2)])]),
            "items": .array([.int(1), .string("two"), .bool(true), .null])
        ]
        let boxed = AgentCoreKotlinJSONBridge.boxedArguments(args)
        let back = AgentCoreKotlinJSONBridge.jsonArguments(boxed)
        XCTAssertEqual(back, args)
    }

    /// 数字字符串不能被桥顺手改成数字：Kotlin 与 Swift 都把它当整数读，但桥只搬不改。
    func testNumericStringStaysString() {
        let args: [String: JSONValue] = ["count": .string("99")]
        let back = AgentCoreKotlinJSONBridge.jsonArguments(
            AgentCoreKotlinJSONBridge.boxedArguments(args)
        )
        XCTAssertEqual(back, args)
    }

    func testEmptyArgumentsRoundTrip() {
        XCTAssertEqual(AgentCoreKotlinJSONBridge.jsonArguments([:]), [:])
        XCTAssertTrue(AgentCoreKotlinJSONBridge.boxedArguments([:]).isEmpty)
    }

    // MARK: - 4. macOS 上工厂仍然走 Swift（iOS 上走 Kotlin，见模拟器测试）

    func testMakeNativeFallsBackToSwiftImplementationOnMacOS() {
        XCTAssertTrue(
            AgentCoreClientFactory.makeNative() is NativeAgentCoreClient,
            "macOS 链接不了 iOS framework：这里必须是 Swift 实现"
        )
        let client = AgentCoreClientFactory.make(mode: .native, environment: [:], info: nil)
        XCTAssertTrue(client is NativeAgentCoreClient)
        XCTAssertEqual(client.agentCoreMode, .native)
    }
}
