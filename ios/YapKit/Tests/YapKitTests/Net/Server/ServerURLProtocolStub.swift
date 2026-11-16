import Foundation

/// 服务端传输层测试用的假 HTTP 传输。
///
/// 与 `Net/StubURLProtocol.swift` 的区别：那份是同步写完所有字节的，适合测「半包」，
/// 但没法在写流的中途响应 `stopLoading`（同一个线程被 sleep 占住时，取消根本进不来）。
/// 这里把写字节放到后台队列，并用锁保护「已停止」标记，才能验证：
/// - SSE 分片/延迟到达时消费者仍在等；
/// - 消费方取消时 URLSessionDataTask 真的被 cancel（`stopLoading` 被调用）。
///
/// 不依赖网络、不启动模拟器；每个测试 `setUp` / `tearDown` 都 `reset()`。
final class ServerURLProtocolStub: URLProtocol {

    struct Response {
        var status: Int = 200
        var headers: [String: String] = ["Content-Type": "application/json"]
        var chunks: [Data] = []
        /// 每一块之间的间隔（秒）。用于取消测试。
        var delayPerChunk: TimeInterval = 0
        /// 第 index 块写出去之后回调（测试用它等「流已经开始了」）。
        var onChunk: ((Int) -> Void)?
        /// `stopLoading` 被调用时回调一次（取消测试用）。
        var onStop: (() -> Void)?
        /// 非 nil 时不做 HTTP 响应，直接让 URLSession 失败（网络错误映射测试用）。
        var failure: URLError?

        static func json(_ text: String, status: Int = 200) -> Response {
            Response(
                status: status,
                headers: ["Content-Type": "application/json"],
                chunks: [Data(text.utf8)]
            )
        }

        static func sse(_ text: String, splitEvery: Int = 0, delay: TimeInterval = 0) -> Response {
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
            return Response(
                headers: ["Content-Type": "text/event-stream"],
                chunks: chunks,
                delayPerChunk: delay
            )
        }
    }

    private static let queueLock = NSLock()
    private static var queue: [Response] = []
    private static var requests: [URLRequest] = []
    private static var bodies: [Data] = []

    static func enqueue(_ responses: Response...) {
        queueLock.lock()
        queue.append(contentsOf: responses)
        queueLock.unlock()
    }

    static func reset() {
        queueLock.lock()
        queue.removeAll()
        requests.removeAll()
        bodies.removeAll()
        queueLock.unlock()
    }

    static var recordedRequests: [URLRequest] {
        queueLock.lock()
        defer { queueLock.unlock() }
        return requests
    }

    static var recordedBodies: [Data] {
        queueLock.lock()
        defer { queueLock.unlock() }
        return bodies
    }

    static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ServerURLProtocolStub.self]
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 10
        return URLSession(configuration: configuration)
    }

    private static func next(for request: URLRequest) -> Response {
        queueLock.lock()
        defer { queueLock.unlock() }
        requests.append(request)
        // URLProtocol 拿到的是 stream 形式的 body，这里顺手读出来给测试断言。
        if let body = request.httpBody {
            bodies.append(body)
        } else if let stream = request.httpBodyStream {
            bodies.append(readAll(stream))
        } else {
            bodies.append(Data())
        }
        guard !queue.isEmpty else {
            return Response(
                status: 500,
                headers: ["Content-Type": "application/json"],
                chunks: [Data(#"{"error":{"message":"stub exhausted","code":"stub_exhausted"}}"#.utf8)]
            )
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

    // MARK: - URLProtocol

    private let stateLock = NSLock()
    private var stopped = false
    private var response: Response?
    private var workItem: DispatchWorkItem?

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let response = Self.next(for: request)
        stateLock.lock()
        self.response = response
        stateLock.unlock()

        if let failure = response.failure {
            client?.urlProtocol(self, didFailWithError: failure)
            return
        }
        guard let url = request.url,
              let http = HTTPURLResponse(
                url: url,
                statusCode: response.status,
                httpVersion: "HTTP/1.1",
                headerFields: response.headers
              ) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)

        // 不能在 startLoading 里 sleep：URLSession 可能在同一队列上派发 stopLoading，
        // 阻塞住就永远测不到取消。所以把写字节放到后台，startLoading 立刻返回。
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            for (index, chunk) in response.chunks.enumerated() {
                if index > 0, response.delayPerChunk > 0 {
                    Thread.sleep(forTimeInterval: response.delayPerChunk)
                }
                guard !self.isStopped else { return }
                self.client?.urlProtocol(self, didLoad: chunk)
                response.onChunk?(index)
            }
            guard !self.isStopped else { return }
            self.client?.urlProtocolDidFinishLoading(self)
        }
        stateLock.lock()
        workItem = work
        stateLock.unlock()
        DispatchQueue.global(qos: .userInitiated).async(execute: work)
    }

    override func stopLoading() {
        stateLock.lock()
        if stopped {
            stateLock.unlock()
            return
        }
        stopped = true
        let work = workItem
        let callback = response?.onStop
        stateLock.unlock()
        work?.cancel()
        callback?()
    }

    private var isStopped: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return stopped
    }
}
