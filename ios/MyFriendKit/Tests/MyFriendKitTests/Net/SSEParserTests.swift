import Foundation
import XCTest
@testable import MyFriendKit

/// SSE 分帧：半包、CRLF、多帧粘在一起、`[DONE]`、UTF-8 被切开。
///
/// 这些不是「边角情况」，而是线上每天都会发生的事：`URLSession.bytes` 的块边界
/// 完全由系统决定，一个中文字符、一个 `\r\n` 都可能正好跨在两块之间。
final class SSEParserTests: XCTestCase {

    private func payloads(_ events: [SSEEvent]) -> [String] {
        events.compactMap { event in
            if case .data(let value) = event { return value }
            return nil
        }
    }

    func testSingleCompleteFrame() {
        var parser = SSEParser()
        let events = parser.feed("data: {\"a\":1}\n")
        XCTAssertEqual(payloads(events), [#"{"a":1}"#])
        XCTAssertTrue(parser.flush().isEmpty)
    }

    /// 行被拆成两半：第一块只到 JSON 中间，解析器不能吐出半截。
    func testHalfPacketIsAssembled() {
        var parser = SSEParser()
        XCTAssertTrue(parser.feed("data: {\"choices\":[{\"del").isEmpty)
        let events = parser.feed("ta\":{\"content\":\"你\"}}]}\n")
        XCTAssertEqual(payloads(events), [#"{"choices":[{"delta":{"content":"你"}}]}"#])
    }

    /// CRLF 的 `\r` 与 `\n` 分属两块：不能把 `\r` 留在 payload 里（JSON 会解析失败）。
    func testCRLFSplitAcrossChunks() {
        var parser = SSEParser()
        XCTAssertTrue(parser.feed("data: {\"a\":1}\r").isEmpty)
        let events = parser.feed("\ndata: {\"b\":2}\r\n")
        XCTAssertEqual(payloads(events), [#"{"a":1}"#, #"{"b":2}"#])
    }

    /// 一块里粘着多帧（服务端批量 flush 时很常见）。
    func testMultipleFramesInOneChunk() {
        var parser = SSEParser()
        let events = parser.feed("data: {\"n\":1}\n\ndata: {\"n\":2}\n")
        XCTAssertEqual(payloads(events), [#"{"n":1}"#, #"{"n":2}"#])
    }

    /// `[DONE]` 是结束信号，不是要解析的数据。
    func testDoneMarker() {
        var parser = SSEParser()
        let events = parser.feed("data: {\"n\":1}\ndata: [DONE]\n")
        XCTAssertEqual(events, [.data(#"{"n":1}"#), .done])
    }

    /// 注释行（心跳 `:`）、`event:` / `id:` 行一律忽略；空 data 也忽略。
    func testIgnoresCommentsAndOtherFields() {
        var parser = SSEParser()
        let events = parser.feed(": ping\nevent: message\nid: 7\nretry: 1000\ndata:\ndata:  \ndata: {\"ok\":true}\n")
        XCTAssertEqual(payloads(events), [#"{"ok":true}"#])
    }

    /// 流结束时最后一行可能没有换行符：`flush()` 必须把它吐出来，否则最后一段正文会丢。
    func testFlushReturnsUnterminatedTail() {
        var parser = SSEParser()
        XCTAssertTrue(parser.feed("data: {\"tail\":1}").isEmpty)
        XCTAssertEqual(payloads(parser.flush()), [#"{"tail":1}"#])
        XCTAssertTrue(parser.flush().isEmpty, "flush 之后缓冲应清空")
    }

    /// 一个 UTF-8 汉字（3 字节）被切成三块：按字节拼回去才对。
    func testUTF8CharacterSplitAcrossChunks() {
        var parser = SSEParser()
        let data = Data("data: 你好\n".utf8)
        var events: [SSEEvent] = []
        for byte in data {
            events += parser.feed(Data([byte]))
        }
        XCTAssertEqual(payloads(events), ["你好"])
    }

    /// 非流式响应（一整份 JSON、中间没有换行）不能无声地占内存：
    /// 超长行按「一行」吐出来，让上层当成完整回复解析。
    func testLongLineWithoutNewlineIsEmitted() {
        // 0.10.2 安全审计 P2-10：与 Android 的 8 MB 阈值对齐（原来 iOS 是 1 MB）。
        XCTAssertEqual(SSEParser.MAX_LINE_BYTES, 8 << 20)
        var parser = SSEParser()
        let long = "x" + String(repeating: "y", count: SSEParser.MAX_LINE_BYTES + 10)
        let events = parser.feed("data: " + long)
        XCTAssertEqual(payloads(events).count, 1)
        XCTAssertEqual(payloads(events).first?.count, long.count)
    }

    /// 一次性解析整段文本（测试与兜底路径用）。
    func testEventsInText() {
        let events = SSEParser.events(in: "data: a\ndata: [DONE]\n")
        XCTAssertEqual(events, [.data("a"), .done])
    }

    /// usage 帧（choices 为空、只有 usage）必须原样送到上层——记账全靠它。
    func testUsageFramePayload() {
        let frame = #"{"id":"x","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":34}}"#
        var parser = SSEParser()
        let events = parser.feed("data: \(frame)\n")
        XCTAssertEqual(payloads(events), [frame])
    }
}
