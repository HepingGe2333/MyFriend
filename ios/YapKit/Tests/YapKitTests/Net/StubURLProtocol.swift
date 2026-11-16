import Foundation
import XCTest

/// 假 HTTP 传输：用自定义 `URLProtocol` 假装一个流式服务端。
///
/// 为什么不用假的 `ChatCompleting`：那样只能测「聊天服务怎么处理文字」，
/// 测不到「URLSession + SSEParser + 报文 + 记账」这一整条真实链路——
/// 而那正是最容易出半包、CRLF、usage 帧问题的部分。
///
/// 用法：`StubURLProtocol.enqueue(...)` 压入若干份响应，测试结束后 `reset()`。
final class StubURLProtocol: URLProtocol {

    /// 一份预设响应。
    struct Stub {
        var status: Int = 200
        var headers: [String: String] = ["Content-Type": "text/event-stream"]
        /// 依次写回的字节块：故意拆成多块来模拟半包。
        var chunks: [Data]
        /// 每块之间的停顿（秒）。用来测「流到一半被停止」。
        var delayPerChunk: TimeInterval = 0

        static func sse(_ text: String, splitEvery: Int = 0, delay: TimeInterval = 0) -> Stub {
            let data = Data(text.utf8)
            var chunks: [Data] = []
            if splitEvery > 0 {
                var start = 0
                while start < data.count {
                    let end = Swift.min(start + splitEvery, data.count)
                    chunks.append(data.subdata(in: start..<end))
                    start = end
                }
            } else {
                chunks = [data]
            }
            return Stub(chunks: chunks, delayPerChunk: delay)
        }

        static func json(_ text: String, status: Int = 200) -> Stub {
            Stub(status: status, headers: ["Content-Type": "application/json"],
                 chunks: [Data(text.utf8)])
        }
    }

    private static let lock = NSLock()
    private static var queue: [Stub] = []
    private static var requests: [URLRequest] = []
    private static var bodies: [Data] = []

    static func enqueue(_ stubs: Stub...) {
        lock.lock()
        queue.append(contentsOf: stubs)
        lock.unlock()
    }

    static func reset() {
        lock.lock()
        queue.removeAll()
        requests.removeAll()
        bodies.removeAll()
        lock.unlock()
    }

    static var recordedRequests: [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return requests
    }

    static var recordedBodies: [Data] {
        lock.lock()
        defer { lock.unlock() }
        return bodies
    }

    static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 10
        return URLSession(configuration: configuration)
    }

    private static func next(for request: URLRequest) -> Stub {
        lock.lock()
        defer { lock.unlock() }
        requests.append(request)
        // URLProtocol 拿不到 httpBody（被转成 stream 了），这里顺手把 body 读出来存好。
        if let body = request.httpBody {
            bodies.append(body)
        } else if let stream = request.httpBodyStream {
            bodies.append(Self.readAll(stream))
        } else {
            bodies.append(Data())
        }
        if queue.isEmpty {
            return Stub(status: 500, headers: ["Content-Type": "application/json"],
                        chunks: [Data(#"{"error":{"message":"stub exhausted"}}"#.utf8)])
        }
        return queue.removeFirst()
    }

    private static func readAll(_ stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let stub = Self.next(for: request)
        guard let url = request.url,
              let response = HTTPURLResponse(
                url: url, statusCode: stub.status, httpVersion: "HTTP/1.1",
                headerFields: stub.headers
              ) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        for (index, chunk) in stub.chunks.enumerated() {
            if stub.delayPerChunk > 0 && index > 0 {
                Thread.sleep(forTimeInterval: stub.delayPerChunk)
            }
            if isStopped { return }
            client?.urlProtocol(self, didLoad: chunk)
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {
        isStopped = true
    }

    private var isStopped = false
}
