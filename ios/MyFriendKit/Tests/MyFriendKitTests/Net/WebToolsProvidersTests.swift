import Foundation
import XCTest
@testable import MyFriendKit

/// 0.12 的五个搜索服务（`spec/web-search/README.md`）。
///
/// 这里全部用自定义 `URLProtocol` 桩，不发真网：请求形状、响应解析、归一化、错误映射
/// 都必须逐字对得上规范 —— 两家实现的差异一旦漏掉，用户看到的就是「联网打不开」。
final class WebToolsProvidersTests: XCTestCase {

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
    }

    override func tearDownWithError() throws {
        StubURLProtocol.reset()
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func makeClient(log: @escaping (String) -> Void = { _ in }) -> WebToolsClient {
        WebToolsClient(session: StubURLProtocol.makeSession(), log: log)
    }

    private func access(
        provider: WebToolsProvider,
        baseUrl: String = "",
        apiKey: String = "sk-test"
    ) -> WebToolsAccess {
        WebToolsAccess(baseUrl: baseUrl, apiKey: apiKey, provider: provider)
    }

    private func request(_ index: Int = 0) throws -> URLRequest {
        let requests = StubURLProtocol.recordedRequests
        XCTAssertGreaterThan(requests.count, index)
        return try XCTUnwrap(requests.indices.contains(index) ? requests[index] : nil)
    }

    private func body(_ index: Int = 0) throws -> [String: Any] {
        let bodies = StubURLProtocol.recordedBodies
        XCTAssertGreaterThan(bodies.count, index)
        guard bodies.indices.contains(index) else { return [:] }
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: bodies[index]) as? [String: Any])
    }

    @discardableResult
    private func assertFailure(
        _ expected: WebToolsFailure,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ block: () async throws -> Void
    ) async -> WebToolsFailure? {
        do {
            try await block()
            XCTFail("应该抛 \(expected)", file: file, line: line)
            return nil
        } catch let failure as WebToolsFailure {
            XCTAssertEqual(failure, expected, file: file, line: line)
            return failure
        } catch {
            XCTFail("抛了意料之外的错误：\(error)", file: file, line: line)
            return nil
        }
    }

    // MARK: - provider 本身

    func testDefaultBaseUrlsMatchTheContract() {
        XCTAssertEqual(
            WebToolsProvider.messageAPI.resolvedBaseUrl(llmBaseUrl: "https://api.deepseek.com"),
            "https://api.deepseek.com"
        )
        XCTAssertEqual(
            WebToolsProvider.messageAPI.resolvedBaseUrl(llmBaseUrl: "  "),
            "https://api.deepseek.com",
            "LLM 地址也没填时回落官方地址"
        )
        XCTAssertEqual(WebToolsProvider.tavily.resolvedBaseUrl(), "https://api.tavily.com")
        XCTAssertEqual(WebToolsProvider.brave.resolvedBaseUrl(), "https://api.search.brave.com/res/v1")
        XCTAssertEqual(WebToolsProvider.serper.resolvedBaseUrl(), "https://google.serper.dev")
        XCTAssertNil(WebToolsProvider.generic.resolvedBaseUrl(), "GENERIC 没有默认地址，必须让用户填")
        XCTAssertFalse(WebToolsProvider.generic.requiresAPIKey, "自建服务可以不要 Key")
        XCTAssertTrue(WebToolsProvider.tavily.requiresAPIKey)
        XCTAssertTrue(WebToolsProvider.messageAPI.usesServerSideFetch)
        XCTAssertFalse(WebToolsProvider.tavily.usesServerSideFetch)
    }

    func testStoredProviderIsTolerant() {
        XCTAssertEqual(WebToolsProvider(stored: " tavily "), .tavily)
        XCTAssertEqual(WebToolsProvider(stored: "BRAVE"), .brave)
        XCTAssertEqual(WebToolsProvider(stored: nil), .messageAPI)
        XCTAssertEqual(WebToolsProvider(stored: ""), .messageAPI)
        XCTAssertEqual(WebToolsProvider(stored: "SOMETHING_NEW"), .messageAPI, "不认识的值退回默认")
    }

    /// 0.12 改名：旧库/旧备份里的 `DEEPSEEK`（含大小写、空白变体）读进来等价于 MESSAGEAPI；
    /// 写回时一律规范成 `MESSAGEAPI`，不会把历史名字继续写出去。
    func testLegacyDeepSeekStoredValueNormalizesToMessageAPI() {
        XCTAssertEqual(WebToolsProvider(stored: "DEEPSEEK"), .messageAPI)
        XCTAssertEqual(WebToolsProvider(stored: " deepseek "), .messageAPI)
        XCTAssertEqual(WebToolsProvider(stored: "DeepSeek\n"), .messageAPI)
        XCTAssertEqual(WebToolsProvider(stored: "MESSAGEAPI"), .messageAPI)
        XCTAssertEqual(WebToolsProvider(stored: " messageapi "), .messageAPI)

        XCTAssertEqual(WebToolsProvider.normalizedStored("DEEPSEEK"), "MESSAGEAPI")
        XCTAssertEqual(WebToolsProvider.normalizedStored(" deepseek "), "MESSAGEAPI")
        XCTAssertEqual(WebToolsProvider.normalizedStored(nil), "MESSAGEAPI")
        XCTAssertEqual(WebToolsProvider.normalizedStored(" tavily "), "TAVILY")
        XCTAssertEqual(WebToolsProvider.normalizedStored("GENERIC"), "GENERIC")
    }

    // MARK: - TAVILY

    func testTavilyRequestAndParsing() async throws {
        StubURLProtocol.enqueue(.json("""
        {"results":[
          {"title":"塔维利结果","url":"https://example.com/t","content":"来自 Tavily 的摘要"},
          {"title":"缺链接","content":"没有 url 的条目"},
          "不是对象"
        ]}
        """))
        let hits = try await makeClient().search(
            query: "  今天天气  ", count: 3, access: access(provider: .tavily)
        )

        let request = try request()
        XCTAssertEqual(request.url?.absoluteString, "https://api.tavily.com/search")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer sk-test")
        let payload = try body()
        XCTAssertEqual(payload["api_key"] as? String, "sk-test")
        XCTAssertEqual(payload["query"] as? String, "今天天气")
        XCTAssertEqual(payload["max_results"] as? Int, 3)
        XCTAssertEqual(payload["include_answer"] as? Bool, false)

        XCTAssertEqual(hits.count, 1, "没有 url 的条目要在归一化时丢掉")
        XCTAssertEqual(hits[0].title, "塔维利结果")
        XCTAssertEqual(hits[0].url, "https://example.com/t")
        XCTAssertEqual(hits[0].snippet, "来自 Tavily 的摘要")
    }

    // MARK: - BRAVE

    func testBraveRequestAndParsing() async throws {
        StubURLProtocol.enqueue(.json("""
        {"web":{"results":[
          {"title":"Brave 结果","url":"https://example.com/b","description":"Brave 摘要"}
        ]}}
        """))
        let hits = try await makeClient().search(
            query: "Swift 并发", count: 6, access: access(provider: .brave)
        )

        let request = try request()
        let components = try XCTUnwrap(URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.scheme, "https")
        XCTAssertEqual(components.host, "api.search.brave.com")
        XCTAssertEqual(components.path, "/res/v1/web/search")
        XCTAssertEqual(components.queryItems?.first { $0.name == "q" }?.value, "Swift 并发")
        XCTAssertEqual(components.queryItems?.first { $0.name == "count" }?.value, "6")
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Subscription-Token"), "sk-test")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"), "Brave 用专用头，不发 Bearer")

        XCTAssertEqual(hits.count, 1)
        XCTAssertEqual(hits[0].title, "Brave 结果")
        XCTAssertEqual(hits[0].snippet, "Brave 摘要")
    }

    // MARK: - SERPER

    func testSerperRequestAndParsing() async throws {
        StubURLProtocol.enqueue(.json("""
        {"organic":[
          {"title":"Serper 结果","link":"https://example.com/s","snippet":"Serper 摘要"}
        ]}
        """))
        let hits = try await makeClient().search(
            query: "serper", count: 99, access: access(provider: .serper)
        )

        let request = try request()
        XCTAssertEqual(request.url?.absoluteString, "https://google.serper.dev/search")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-API-KEY"), "sk-test")
        let payload = try body()
        XCTAssertEqual(payload["q"] as? String, "serper")
        XCTAssertEqual(payload["num"] as? Int, 12, "count 上限夹到 12")

        XCTAssertEqual(hits.first?.url, "https://example.com/s")
        XCTAssertEqual(hits.first?.snippet, "Serper 摘要")
    }

    // MARK: - GENERIC（自建服务）

    func testGenericUsesBaseUrlExactlyAndAllowsNoKey() async throws {
        StubURLProtocol.enqueue(.json("""
        {"results":[{"title":"自建结果","url":"https://example.com/g","snippet":"自建摘要"}]}
        """))
        let hits = try await makeClient().search(
            query: "自建",
            count: 2,
            access: access(provider: .generic, baseUrl: "https://search.internal.example/api/search", apiKey: "   ")
        )

        let request = try request()
        XCTAssertEqual(
            request.url?.absoluteString, "https://search.internal.example/api/search",
            "GENERIC 的 base URL 原样使用，不追加路径"
        )
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"), "没有 Key 时不发空 Bearer")
        let payload = try body()
        XCTAssertEqual(payload["query"] as? String, "自建")
        XCTAssertEqual(payload["count"] as? Int, 2)
        XCTAssertEqual(hits.first?.url, "https://example.com/g")
    }

    func testGenericWithKeySendsAuthorization() async throws {
        StubURLProtocol.enqueue(.json(#"{"results":[]}"#))
        _ = try await makeClient().search(
            query: "自建",
            access: access(provider: .generic, baseUrl: "https://search.example.com", apiKey: "sk-generic")
        )
        XCTAssertEqual(
            try request().value(forHTTPHeaderField: "Authorization"), "Bearer sk-generic"
        )
    }

    func testGenericWithoutBaseUrlIsRejectedBeforeSending() async throws {
        await assertFailure(.invalidBaseUrl("")) {
            _ = try await self.makeClient().search(
                query: "自建", access: self.access(provider: .generic, baseUrl: "")
            )
        }
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty)
    }

    // MARK: - 缺 Key

    func testMissingKeyFailsForEveryProviderExceptGeneric() async throws {
        for provider in [WebToolsProvider.messageAPI, .tavily, .brave, .serper] {
            await assertFailure(.missingAPIKey) {
                _ = try await self.makeClient().search(
                    query: "你好", access: self.access(provider: provider, apiKey: "  ")
                )
            }
        }
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "缺 Key 不该发任何请求")

        // GENERIC 无 Key 是合法的（自建服务可以不鉴权）。
        StubURLProtocol.enqueue(.json(#"{"results":[]}"#))
        _ = try await makeClient().search(
            query: "自建", access: access(provider: .generic, baseUrl: "https://search.example.com", apiKey: "")
        )
        XCTAssertEqual(StubURLProtocol.recordedRequests.count, 1)
    }

    // MARK: - HTTP 错误映射

    func test401And429MapToProviderAndStatus() async throws {
        StubURLProtocol.enqueue(
            .json(#"{"error":"bad key"}"#, status: 401),
            .json(#"{"error":"forbidden"}"#, status: 403),
            .json(#"{"error":"rate limited"}"#, status: 429),
            .json("server down", status: 503)
        )
        let client = makeClient()

        let unauthorized = await assertFailure(
            .http(status: 401, message: WebToolsClient.httpMessage(provider: .tavily, status: 401))
        ) {
            _ = try await client.search(query: "x", access: self.access(provider: .tavily))
        }
        XCTAssertTrue(unauthorized?.message.contains("TAVILY") == true)
        XCTAssertTrue(unauthorized?.message.contains("401") == true)

        let forbidden = await assertFailure(
            .http(status: 403, message: WebToolsClient.httpMessage(provider: .tavily, status: 403))
        ) {
            _ = try await client.search(query: "x", access: self.access(provider: .tavily))
        }
        XCTAssertTrue(forbidden?.message.contains("403") == true)

        let limited = await assertFailure(
            .http(status: 429, message: WebToolsClient.httpMessage(provider: .brave, status: 429))
        ) {
            _ = try await client.search(query: "x", access: self.access(provider: .brave))
        }
        XCTAssertTrue(limited?.message.contains("BRAVE") == true)

        let down = await assertFailure(
            .http(status: 503, message: WebToolsClient.httpMessage(provider: .serper, status: 503))
        ) {
            _ = try await client.search(query: "x", access: self.access(provider: .serper))
        }
        XCTAssertTrue(down?.message.contains("SERPER") == true)
        XCTAssertNotNil(down?.message.range(of: "\\p{Han}", options: .regularExpression))
    }

    // MARK: - 响应过大

    func testOversizedResponseIsRejected() async throws {
        let tooLarge = 2 * 1024 * 1024 + 1
        StubURLProtocol.enqueue(StubURLProtocol.Stub(
            status: 200,
            headers: ["Content-Type": "application/json"],
            chunks: [Data(repeating: 0x61, count: tooLarge)]
        ))
        await assertFailure(.responseTooLarge(tooLarge)) {
            _ = try await self.makeClient().search(query: "大响应", access: self.access(provider: .tavily))
        }
    }

    // MARK: - 归一化

    func testNormalizationDropsNonHTTPDeduplicatesAndTruncatesSnippet() {
        let longSnippet = String(repeating: "字", count: 600)
        let hits = [
            WebSearchHit(title: "a", url: "https://example.com/a", snippet: longSnippet),
            WebSearchHit(title: "ftp", url: "ftp://example.com/f", snippet: "丢掉"),
            WebSearchHit(title: "dup", url: "https://example.com/a", snippet: "重复"),
            WebSearchHit(title: "no-url", url: "", snippet: "丢掉"),
            WebSearchHit(title: "c", url: "https://example.com/c", snippet: "c")
        ]
        let normalized = WebToolsClient.normalized(hits, limit: 2)
        XCTAssertEqual(normalized.map(\.url), ["https://example.com/a", "https://example.com/c"])
        XCTAssertEqual(normalized[0].title, "a", "重复 URL 保留第一次出现的条目")
        XCTAssertEqual(normalized[0].snippet.count, 500, "snippet 截到 500 字符")
    }

    func testParsersTolerateBadJSONAndEmptyPayloads() {
        XCTAssertEqual(WebToolsClient.parseTavilySearch("不是 JSON"), [])
        XCTAssertEqual(WebToolsClient.parseBraveSearch(#"{"web":{}}"#), [])
        XCTAssertEqual(WebToolsClient.parseSerperSearch(#"{"organic":"怪数据"}"#), [])
        XCTAssertEqual(WebToolsClient.parseGenericSearch("{}"), [])
        // 缺字段的条目能被解析出来（URL 为空），但归一化会丢掉它 —— 两条合起来就是「坏数据不崩」。
        let missing = WebToolsClient.parseGenericSearch(#"{"results":[{"title":"只有标题"}]}"#)
        XCTAssertEqual(missing.count, 1)
        XCTAssertEqual(WebToolsClient.normalized(missing, limit: 6), [])
    }

    // MARK: - 客户端抓网页（非 MESSAGEAPI）

    func testClientSideFetchStripsHTMLAndCapsAt8000Characters() async throws {
        let body = String(repeating: "正文内容 ", count: 2_000)
        let html = """
        <html><head><title>  一个 网页 </title><style>.x{color:red}</style></head>
        <body><script>alert(1)</script><h1>标题</h1><p>\(body)</p></body></html>
        """
        StubURLProtocol.enqueue(.json(html)) // 桩只负责把字符串写回，Content-Type 不重要
        let page = try await makeClient().fetch(
            url: "https://example.com/post", access: access(provider: .tavily)
        )

        let request = try request()
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.value(forHTTPHeaderField: "User-Agent"), "MyFriend/1.0")
        XCTAssertEqual(request.timeoutInterval, 10, accuracy: 0.001)
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"), "抓网页不带搜索 Key")
        XCTAssertNil(request.httpBody)

        XCTAssertEqual(page.title, "一个 网页")
        XCTAssertFalse(page.markdown.contains("<"), "标签要去掉：\(page.markdown.prefix(80))")
        XCTAssertFalse(page.markdown.contains("alert(1)"), "脚本要去掉")
        XCTAssertFalse(page.markdown.contains("color:red"), "样式要去掉")
        XCTAssertLessThanOrEqual(page.markdown.count, 8_000)
        XCTAssertFalse(page.markdown.contains("\n"), "空白要压缩成单空格")
    }

    func testClientSideFetchRejectsLocalAndCredentialURLsBeforeRequest() async throws {
        for (url, expected) in [
            ("http://127.0.0.1:8080/admin", WebToolsFailure.localUrl("http://127.0.0.1:8080/admin")),
            ("https://user:pass@example.com/x", WebToolsFailure.credentialsInURL("https://user:pass@example.com/x")),
            ("javascript:alert(1)", WebToolsFailure.invalidUrl("javascript:alert(1)"))
        ] {
            await assertFailure(expected) {
                _ = try await self.makeClient().fetch(
                    url: url, access: self.access(provider: .tavily)
                )
            }
        }
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty)
    }

    func testMessageAPIFetchStillUsesServerSideEndpoint() async throws {
        StubURLProtocol.enqueue(.json(#"{"url":"https://example.com/d","title":"深寻","markdown":"正文"}"#))
        let page = try await makeClient().fetch(
            url: "https://example.com/d", access: access(provider: .messageAPI)
        )
        let request = try request()
        XCTAssertEqual(request.url?.absoluteString, "https://api.deepseek.com/v1/tools/fetch")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(page.title, "深寻")
        XCTAssertEqual(page.markdown, "正文")
    }

    func testMessageAPIFetchRequiresKey() async throws {
        await assertFailure(.missingAPIKey) {
            _ = try await self.makeClient().fetch(
                url: "https://example.com/d", access: self.access(provider: .messageAPI, apiKey: "")
            )
        }
    }

    // MARK: - 搜索 base URL 形状

    func testSearchBaseUrlMustBeHTTPAndNotLocalOnlyWhenInsecureDisabled() async throws {
        await assertFailure(.invalidBaseUrl("ftp://api.tavily.com")) {
            _ = try await self.makeClient().search(
                query: "x", access: self.access(provider: .tavily, baseUrl: "ftp://api.tavily.com")
            )
        }
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty)

        // 显式允许不安全 HTTP 时，自建服务（最常见是内网地址）必须能用。
        StubURLProtocol.enqueue(.json(#"{"results":[]}"#))
        _ = try await makeClient().search(
            query: "x",
            access: WebToolsAccess(
                baseUrl: "http://192.168.1.8:8088/search",
                apiKey: "sk",
                allowInsecureHttp: true,
                provider: .generic
            )
        )
        XCTAssertEqual(try request().url?.absoluteString, "http://192.168.1.8:8088/search")
    }
}
