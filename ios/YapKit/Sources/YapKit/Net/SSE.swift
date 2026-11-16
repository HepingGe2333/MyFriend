import Foundation

// ---------------------------------------------------------------------------
// SSE（text/event-stream）分帧。
//
// 来源：Android `network/OpenAiAdapter.kt` 里那段 `readUtf8Line()` 循环。
//
// 为什么单独抽出来：`URLSession.bytes(for:)` 交给我们的是**任意切分的字节块**——
// 一行可能被拆成两块（半包）、也可能一块里有十几行；CRLF 的 `\r` 可能正好落在块边界上。
// 如果直接在字符串上 `split("\n")`，半包会解析失败、`\r\n` 会留下一个 `\r` 污染 JSON。
// 所以这一层只做一件事：把字节流切成**完整的行**，并且按字节判断换行
// （UTF-8 多字节字符里不可能出现 0x0A/0x0D，所以按字节切是安全的）。
//
// 与 Android 的取舍一致：只认 `data:` 行，`event:` / `id:` / `retry:` / 注释行一概忽略；
// 一帧里有多行 data 时**不**合并（Android 也没合并）；`[DONE]` 就是结束信号。
// ---------------------------------------------------------------------------

/// 从 SSE 里读出的一条事件。
public enum SSEEvent: Equatable, Sendable {
    /// 一帧数据（`data:` 后面的内容，已 trim）。
    case data(String)
    /// 结束标记：`data: [DONE]`。
    case done
}

/// 逐块喂字节、吐出完整事件的解析器。
///
/// 用法：`feed(chunk)` → 处理返回的事件 → 流结束时 `flush()` 一次
/// （有些网关最后一帧没有换行符）。
public struct SSEParser {

    /// 还没拼成完整行的尾巴（半包）。
    private var buffer = Data()

    /// 单行上限：正常一行 JSON 也就几 KB，超过这个数说明对面根本不是 SSE。
    ///
    /// 8 MB 与 Android `network/SseLines.kt` 的阈值**逐字节一致**（0.10.2 安全审计 P2-10：
    /// 原来 iOS 是 1 MB、Android 是 8 MB，同一段异常响应在两端表现不同）。
    /// 超限时的行为也保持 iOS 原来的语义：把缓冲区当成**一整行**吐出去（上层 JSON 解析失败
    /// → 空 chunk），而不是让解析器丢掉已经收到的内容 —— 与 Android「抛 ResponseTooLong
    /// 并中止流」的差别记录在交付报告里（这是本端刻意保留的降级方式）。
    ///
    /// **0.11.1 审计 P1-1**：这个分支只在「直接喂给解析器的块里没有换行」时才可能执行，
    /// 而生产代码（`OpenAICompatibleClient`）是攒满一行才 feed 的，所以它是**不可达**的。
    /// 真正生效的上限在客户端那个累加循环里（同一个常量 `SSEParser.MAX_LINE_BYTES`）：
    /// 缓冲超过 8 MB 即抛 `ApiException` 并中止流。这条注释留在这里，免得下一个人
    /// 又以为「解析器兜住了」。`flush()` 的尾巴也照样受客户端上限保护。
    ///
    /// 错误响应体（非流式 `readAll`）的上限仍是 1 MB，那是另一条路径、另一个常量。
    public static let MAX_LINE_BYTES = 8 << 20

    public init() {}

    /// 喂一块字节，返回这块字节凑齐的所有事件。
    public mutating func feed(_ data: Data) -> [SSEEvent] {
        guard !data.isEmpty else { return [] }
        buffer.append(data)
        var events: [SSEEvent] = []
        while let newline = buffer.firstIndex(of: 0x0A) {
            var lineBytes = buffer[buffer.startIndex..<newline]
            // CRLF：`\r` 属于行尾标记，不属于内容。
            if lineBytes.last == 0x0D { lineBytes = lineBytes.dropLast() }
            let line = String(decoding: lineBytes, as: UTF8.self)
            buffer.removeSubrange(buffer.startIndex...newline)
            if let event = Self.event(forLine: line) { events.append(event) }
        }
        if buffer.count > Self.MAX_LINE_BYTES {
            // 说明对面不是逐行 SSE（比如把整个 JSON 一口气写过来，中间没有换行）：
            // 当成一行处理，剩下的交给上层去解析/报错，总比无声地占内存好。
            let line = String(decoding: buffer, as: UTF8.self)
            buffer.removeAll(keepingCapacity: true)
            if let event = Self.event(forLine: line) { events.append(event) }
        }
        return events
    }

    /// 字符串入口：测试与「非流式响应被误当成 SSE」时方便。
    public mutating func feed(_ text: String) -> [SSEEvent] {
        feed(Data(text.utf8))
    }

    /// 流结束时把最后一行（没有换行的尾巴）吐出来。
    public mutating func flush() -> [SSEEvent] {
        guard !buffer.isEmpty else { return [] }
        var lineBytes = buffer[...]
        if lineBytes.last == 0x0D { lineBytes = lineBytes.dropLast() }
        buffer.removeAll(keepingCapacity: true)
        if let event = Self.event(forLine: String(decoding: lineBytes, as: UTF8.self)) {
            return [event]
        }
        return []
    }

    /// 一次性解析一整段文本（测试、以及「网关直接返回完整 JSON」的兜底路径）。
    public static func events(in text: String) -> [SSEEvent] {
        var parser = SSEParser()
        var events = parser.feed(text)
        events += parser.flush()
        return events
    }

    // MARK: - 单行 → 事件

    /// 一行 SSE 文本到事件。不是 `data:` 行、或 payload 为空，都返回 nil（直接跳过）。
    static func event(forLine rawLine: String) -> SSEEvent? {
        let line = rawLine.hasSuffix("\r") ? String(rawLine.dropLast()) : rawLine
        // 注释行（`:` 开头）与心跳：忽略。SSE 规范里空行是「一帧结束」，
        // 但我们按行解析，空行本来就没有 payload，忽略即可。
        guard line.hasPrefix("data:") else { return nil }
        let payload = String(line.dropFirst("data:".count))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if payload.isEmpty { return nil }
        if payload == "[DONE]" { return .done }
        return .data(payload)
    }
}
