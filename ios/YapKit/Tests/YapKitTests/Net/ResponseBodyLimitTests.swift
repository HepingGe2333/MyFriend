import Foundation
import XCTest
@testable import YapKit

/// 错误响应体的体积上限（0.10.2 审计 P2-2）。
///
/// `readAll` 原来是「逐字节 append、没有上限」：网关/代理返回一个没有 Content-Length、
/// 一直往外吐的错误页时，这里会一直吃内存（SSE 那条路径有 1MB 单行上限，这条没有）。
final class ResponseBodyLimitTests: XCTestCase {

    func testShortBodyIsReturnedVerbatim() {
        let text = #"{"error":{"message":"限流"}}"#
        XCTAssertEqual(OpenAICompatibleClient.decodeBody(Data(text.utf8)), text)
    }

    func testOversizedBodyIsClippedAndMarked() throws {
        let big = Data(repeating: UInt8(ascii: "a"), count: OpenAICompatibleClient.maxResponseBodyBytes + 4_096)
        let text = OpenAICompatibleClient.decodeBody(big)
        XCTAssertTrue(text.hasSuffix("…（响应过长，已截断）"), "超限要给出明确提示")
        XCTAssertLessThanOrEqual(
            text.utf8.count,
            OpenAICompatibleClient.maxResponseBodyBytes + 64,
            "截断之后不能再把整段留在内存里"
        )
    }

    func testExactLimitIsNotTruncated() {
        let exact = Data(repeating: UInt8(ascii: "b"), count: OpenAICompatibleClient.maxResponseBodyBytes)
        let text = OpenAICompatibleClient.decodeBody(exact)
        XCTAssertFalse(text.hasSuffix("…（响应过长，已截断）"))
        XCTAssertEqual(text.utf8.count, OpenAICompatibleClient.maxResponseBodyBytes)
    }

    func testLimitIsOneMegabyte() {
        XCTAssertEqual(OpenAICompatibleClient.maxResponseBodyBytes, 1_048_576)
    }
}
