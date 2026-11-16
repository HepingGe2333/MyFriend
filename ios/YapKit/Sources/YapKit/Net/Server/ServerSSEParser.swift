import Foundation

// ---------------------------------------------------------------------------
// 服务端权威模式的 SSE 分帧（`text/event-stream`）。
//
// 为什么不复用 `Net/SSE.swift`：那个解析器服务的是 OpenAI 兼容网关，只认 `data:`，
// 不认 `event:`、不按空行分帧、也不合并多行 data。服务端的契约是**命名的**事件
// （`accepted` / `delta` / `tool` / `done` / `error`），所以这里按 W3C SSE 规则实现：
//
// - 行结束符：`\n`、`\r\n`、单独的 `\r` 都认（`\r` 正好落在网络块边界时不能提前分行）；
// - 空行 = 一帧结束；`event:` 与 `data:` 一起组成一帧；
// - 多行 `data:` 用 `\n` 连接（JSON 允许 token 之间换行，拆行不会破坏结构）；
// - `:` 开头的注释行（以及服务端心跳）忽略；`id:` / `retry:` / 未知字段也忽略；
// - 流结束时没有尾随空行的最后一帧，`flush()` 也会吐出来。
//
// 解析器只做字节 → 帧，不解释 JSON；事件语义在 `ServerAPIClient` 里。
// ---------------------------------------------------------------------------

/// SSE 的一帧：可选的 `event:` 名字 + 合并后的 `data:` 内容。
public struct ServerSSEFrame: Equatable, Sendable {
    public var event: String?
    public var data: String

    public init(event: String? = nil, data: String) {
        self.event = event
        self.data = data
    }
}

/// 增量 SSE 分帧器：任意切分的字节块 → 完整帧。
public struct ServerSSEParser {

    /// 单行上限。正常一行 JSON 是几 KB；超过这个数说明对面不是逐行 SSE，
    /// 与其无限占内存，不如把缓冲区当成一整行交给上层（上层解析失败会明确报错）。
    /// 与 `Net/SSE.swift` 的 `MAX_LINE_BYTES` 保持一致。
    public static let maxLineBytes = 8 << 20

    private var buffer = Data()
    /// 上一块以 `\r` 结尾，下一块若以 `\n` 开头要一起吃掉（CRLF 被拆开的情况）。
    private var pendingCarriageReturn = false
    private var eventName: String?
    private var dataLines: [String] = []

    public init() {}

    /// 喂一块字节，返回这次凑齐的所有帧。
    public mutating func feed(_ data: Data) -> [ServerSSEFrame] {
        if !data.isEmpty { buffer.append(data) }
        var frames: [ServerSSEFrame] = []

        if pendingCarriageReturn {
            // 空的 feed 不能把「等一个可能的 LF」这件事清掉：CRLF 可能被拆成三块。
            guard !buffer.isEmpty else { return frames }
            if buffer.first == 0x0A { buffer.removeFirst() }
            pendingCarriageReturn = false
        }

        var lineStart = 0
        var index = 0
        while index < buffer.count {
            let byte = buffer[buffer.startIndex + index]
            if byte == 0x0A {
                emitLine(buffer[(buffer.startIndex + lineStart)..<(buffer.startIndex + index)], into: &frames)
                index += 1
                lineStart = index
            } else if byte == 0x0D {
                emitLine(buffer[(buffer.startIndex + lineStart)..<(buffer.startIndex + index)], into: &frames)
                index += 1
                if index >= buffer.count {
                    // `\r` 正好是最后一块的最后一个字节：先按行结束处理，
                    // 下一块开头的 `\n` 由 pendingCarriageReturn 吃掉。
                    pendingCarriageReturn = true
                    lineStart = index
                    break
                }
                if buffer[buffer.startIndex + index] == 0x0A { index += 1 }
                lineStart = index
            } else {
                index += 1
            }
        }

        if lineStart > 0 {
            buffer.removeSubrange(buffer.startIndex..<(buffer.startIndex + lineStart))
        }

        if buffer.count > Self.maxLineBytes {
            // 没有换行的超长内容：当成一行处理，避免缓冲区无限增长。
            let line = String(decoding: buffer, as: UTF8.self)
            buffer.removeAll(keepingCapacity: true)
            consume(line: line, into: &frames)
        }
        return frames
    }

    /// 字符串入口：测试与非流式响应误当 SSE 时方便。
    public mutating func feed(_ text: String) -> [ServerSSEFrame] {
        feed(Data(text.utf8))
    }

    /// 流结束：把最后一行（没有换行）与最后一帧（没有尾随空行）吐出来。
    public mutating func flush() -> [ServerSSEFrame] {
        var frames: [ServerSSEFrame] = []
        pendingCarriageReturn = false
        if !buffer.isEmpty {
            let line = String(decoding: buffer, as: UTF8.self)
            buffer.removeAll(keepingCapacity: true)
            consume(line: line, into: &frames)
        }
        // 宽容处理：服务端如果忘了最后那个空行，也不能丢掉已收到的回复。
        if !dataLines.isEmpty {
            frames.append(ServerSSEFrame(event: eventName, data: dataLines.joined(separator: "\n")))
        }
        eventName = nil
        dataLines.removeAll(keepingCapacity: true)
        return frames
    }

    /// 一次性解析一整段文本（测试专用）。
    public static func frames(in text: String) -> [ServerSSEFrame] {
        var parser = ServerSSEParser()
        var frames = parser.feed(text)
        frames += parser.flush()
        return frames
    }

    // MARK: - 单行 / 单帧

    private mutating func emitLine(_ bytes: Data.SubSequence, into frames: inout [ServerSSEFrame]) {
        consume(line: String(decoding: bytes, as: UTF8.self), into: &frames)
    }

    private mutating func consume(line: String, into frames: inout [ServerSSEFrame]) {
        if line.isEmpty {
            if !dataLines.isEmpty {
                frames.append(ServerSSEFrame(event: eventName, data: dataLines.joined(separator: "\n")))
            }
            eventName = nil
            dataLines.removeAll(keepingCapacity: true)
            return
        }
        // 注释行 / 心跳。SSE 规范规定以 `:` 开头；不参与事件。
        if line.hasPrefix(":") { return }

        guard let colon = line.firstIndex(of: ":") else {
            // `field`（没有冒号）等价于 `field:`，值为空。
            apply(field: line, value: "")
            return
        }
        let field = String(line[..<colon])
        var value = String(line[line.index(after: colon)...])
        if value.hasPrefix(" ") { value.removeFirst() }
        apply(field: field, value: value)
    }

    private mutating func apply(field: String, value: String) {
        switch field {
        case "event":
            eventName = value
        case "data":
            dataLines.append(value)
        default:
            // `id:` / `retry:` / 未知字段：按规范忽略。
            break
        }
    }
}
