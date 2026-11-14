import Foundation
import XCTest
@testable import MyFriendKit

/// 联网工具（DeepSeek Search / Fetch）网络客户端。
///
/// 为什么这些要钉死：
/// 1. 端点归一化——用户在设置页会把 `https://api.deepseek.com`、带 `/v1`、带尾斜杠、
///    甚至把整个 `/v1/tools/search` 粘进来，这四种都得能拼对，否则「联网打不开」；
/// 2. 报文形状——`ori_question` 只在有时才发、`need_content` 始终在场（与 Android 同）；
/// 3. 错误文案——`message` 会被工具执行器原样回灌给模型，出现英文系统报错就会被抄进回复。
final class WebToolsClientTests: XCTestCase {

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
    }

    override func tearDownWithError() throws {
        StubURLProtocol.reset()
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func makeClient() -> WebToolsClient {
        WebToolsClient(session: StubURLProtocol.makeSession())
    }

    private func access(
        baseUrl: String = "https://api.deepseek.com",
        apiKey: String = "sk-test",
        allowInsecureHttp: Bool = false
    ) -> WebToolsAccess {
        WebToolsAccess(baseUrl: baseUrl, apiKey: apiKey, allowInsecureHttp: allowInsecureHttp)
    }

    private func recordedRequest(index: Int = 0) throws -> URLRequest {
        let requests = StubURLProtocol.recordedRequests
        XCTAssertGreaterThan(requests.count, index, "应该发出过一次请求")
        return try XCTUnwrap(requests.indices.contains(index) ? requests[index] : nil)
    }

    private func recordedBody(index: Int = 0) throws -> [String: Any] {
        let bodies = StubURLProtocol.recordedBodies
        XCTAssertGreaterThan(bodies.count, index, "应该发出过一次请求")
        guard bodies.indices.contains(index) else { return [:] }
        let object = try JSONSerialization.jsonObject(with: bodies[index])
        return try XCTUnwrap(object as? [String: Any], "请求体应该是 JSON 对象")
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
            XCTAssertFalse(failure.message.isEmpty, "message 不能为空", file: file, line: line)
            return failure
        } catch {
            XCTFail("抛出了意料之外的错误：\(error)", file: file, line: line)
            return nil
        }
    }

    // MARK: - 端点归一化

    /// 同一批用户输入必须拼出同一个端点（Android `toolsEndpoint` 的行为）。
    func testToolsEndpointNormalizesBaseUrlShapes() {
        let expectedSearch = "https://api.deepseek.com/v1/tools/search"
        let cases = [
            "https://api.deepseek.com",
            "https://api.deepseek.com/",
            "https://api.deepseek.com/v1",
            "https://api.deepseek.com/v1/",
            "https://api.deepseek.com/v1//",
            "  https://api.deepseek.com/v1  ",
            "https://api.deepseek.com/v1/tools/search",
            "https://api.deepseek.com/tools/search",
            "https://api.deepseek.com/v1/tools/fetch"
        ]
        for baseUrl in cases {
            XCTAssertEqual(
                WebToolsClient.toolsEndpoint(baseUrl: baseUrl, tool: "search")?.absoluteString,
                expectedSearch,
                "baseUrl=\(baseUrl)"
            )
        }
        // fetch 端点，以及 tool 自带路径/带前导斜杠两种写法。
        let expectedFetch = "https://api.deepseek.com/v1/tools/fetch"
        for tool in ["fetch", "/fetch", "tools/fetch", "/tools/fetch"] {
            XCTAssertEqual(
                WebToolsClient.toolsEndpoint(baseUrl: "https://api.deepseek.com", tool: tool)?.absoluteString,
                expectedFetch,
                "tool=\(tool)"
            )
        }
    }

    /// 非法地址、以及「是 http 但没打开允许不安全 HTTP」都返回 nil（不发请求）。
    func testToolsEndpointRejectsInvalidAndInsecureAddresses() {
        for baseUrl in ["", "   ", "api.deepseek.com", "ftp://api.deepseek.com", "http://127.0.0.1:8787"] {
            XCTAssertNil(
                WebToolsClient.toolsEndpoint(baseUrl: baseUrl, tool: "search"),
                "baseUrl=\(baseUrl) 不该被接受"
            )
        }
        XCTAssertEqual(
            WebToolsClient.toolsEndpoint(
                baseUrl: "http://127.0.0.1:8787", tool: "search", allowInsecureHttp: true
            )?.absoluteString,
            "http://127.0.0.1:8787/v1/tools/search",
            "显式允许不安全 HTTP 时，本地参考服务必须能用"
        )
    }

    // MARK: - search 报文

    /// 请求头 + 请求体字段：`ori_question` 真的有时才发，`need_content` 始终在场。
    func testSearchRequestShapeAndHeaders() async throws {
        StubURLProtocol.enqueue(.json(#"{"results":[]}"#))
        let hits = try await makeClient().search(
            query: "  今天天气  ",
            count: 0,
            oriQuestion: " 我该出门吗 ",
            needContent: true,
            access: access()
        )
        XCTAssertEqual(hits, [])

        let request = try recordedRequest()
        XCTAssertEqual(request.url?.absoluteString, "https://api.deepseek.com/v1/tools/search")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer sk-test")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(request.timeoutInterval, WebToolsClient.requestTimeout, accuracy: 0.001)

        let body = try recordedBody()
        XCTAssertEqual(body["query"] as? String, "今天天气", "query 要 trim 后发")
        XCTAssertEqual(body["count"] as? Int, 6, "count<=0 用默认 6（与 Android 一致）")
        XCTAssertEqual(body["need_content"] as? Bool, true)
        XCTAssertEqual(body["ori_question"] as? String, "我该出门吗", "ori_question 要 trim 后发")
    }

    /// `ori_question` 为 nil/空白时不该出现；count 上限夹到 12、负数回落 6。
    func testSearchOmitsBlankOriQuestionAndClampsCount() async throws {
        StubURLProtocol.enqueue(.json(#"{"results":[]}"#), .json(#"{"results":[]}"#))
        let client = makeClient()
        _ = try await client.search(query: "你好", count: 99, oriQuestion: "   ", access: access())
        _ = try await client.search(query: "你好", count: -3, access: access())

        let first = try recordedBody(index: 0)
        XCTAssertNil(first["ori_question"], "空白 ori_question 不该出现在请求体里")
        XCTAssertEqual(first["count"] as? Int, 12, "count 上限夹到 12")
        XCTAssertEqual(first["need_content"] as? Bool, false, "没要求正文时也要显式带上 false（同 Android）")

        let second = try recordedBody(index: 1)
        XCTAssertNil(second["ori_question"])
        XCTAssertEqual(second["count"] as? Int, 6, "负数 count 回落 6")
    }

    // MARK: - search 解析

    /// 正常解析：`site_name` / `rerank_score` 映射到驼峰字段，类型不符或缺失走默认值。
    func testSearchParsesHitsAndToleratesMissingFields() async throws {
        let payload = """
        {
          "results": [
            {
              "title": "一条完整的参考",
              "url": "https://example.com/a",
              "snippet": "摘要",
              "summary": "更长的摘要",
              "site_name": "示例站点",
              "date": "2026-11-14",
              "authority": 3,
              "rerank_score": 0.92
            },
            {
              "name": "只有标题和链接",
              "link": "https://example.com/b"
            },
            {
              "title": "数字是字符串",
              "url": "https://example.com/c",
              "authority": "4",
              "rerank_score": "0.5"
            },
            "这一条不是对象，应该被跳过"
          ]
        }
        """
        StubURLProtocol.enqueue(.json(payload))
        let hits = try await makeClient().search(query: "示例", access: access())

        XCTAssertEqual(hits.count, 3, "非对象的条目要跳过，坏数据不能拖垮整次搜索")
        let first = hits[0]
        XCTAssertEqual(first.title, "一条完整的参考")
        XCTAssertEqual(first.url, "https://example.com/a")
        XCTAssertEqual(first.snippet, "摘要")
        XCTAssertEqual(first.summary, "更长的摘要")
        XCTAssertEqual(first.siteName, "示例站点")
        XCTAssertEqual(first.date, "2026-11-14")
        XCTAssertEqual(first.authority, 3)
        XCTAssertEqual(first.rerankScore, 0.92, accuracy: 0.0001)

        let second = hits[1]
        XCTAssertEqual(second.title, "只有标题和链接")
        XCTAssertEqual(second.url, "https://example.com/b")
        XCTAssertEqual(second.snippet, "")
        XCTAssertEqual(second.summary, "")
        XCTAssertNil(second.siteName)
        XCTAssertNil(second.date)
        XCTAssertEqual(second.authority, -1, "缺省 -1（不是 0）")
        XCTAssertEqual(second.rerankScore, 0)

        let third = hits[2]
        XCTAssertEqual(third.authority, 4, "字符串形式的数字也要认")
        XCTAssertEqual(third.rerankScore, 0.5, accuracy: 0.0001)
    }

    /// `results` 为空数组 / 干脆没有这个字段：返回空数组，不抛。
    func testSearchReturnsEmptyWhenNoResults() async throws {
        StubURLProtocol.enqueue(.json(#"{"results":[]}"#), .json(#"{}"#))
        let client = makeClient()
        let first = try await client.search(query: "没结果", access: access())
        XCTAssertEqual(first, [])
        let second = try await client.search(query: "还是没结果", access: access())
        XCTAssertEqual(second, [])
    }

    /// 坏 JSON 不崩、不抛，返回空数组。
    func testSearchToleratesBrokenJson() async throws {
        StubURLProtocol.enqueue(.json("这不是 JSON"))
        let hits = try await makeClient().search(query: "坏响应", access: access())
        XCTAssertEqual(hits, [])
    }

    // MARK: - fetch

    /// fetch 正常解析；标题空白时兜底成 url（否则工具结果里是「《》」这种半句话）。
    func testFetchParsesPageAndFallsBackToUrlForBlankTitle() async throws {
        // 注意用 ## 分隔原始字符串：正文里以 `"#` 开头（`"markdown":"# 标题`）会提前终止 #"…"#。
        StubURLProtocol.enqueue(.json(
            ##"{"url":"https://example.com/post","title":"   ","markdown":"# 抓到的标题\n\n正文"}"##
        ))
        let page = try await makeClient().fetch(url: "https://example.com/post", access: access())
        XCTAssertEqual(page.url, "https://example.com/post")
        XCTAssertEqual(page.title, "https://example.com/post")
        XCTAssertEqual(page.markdown, "# 抓到的标题\n\n正文")
    }

    /// 服务端没回 url、正文还只给了 `content` 字段：url/title 用请求里那个，不抛。
    func testFetchFallsBackToRequestedUrlWhenResponseIsSparse() async throws {
        StubURLProtocol.enqueue(.json(#"{"content":"只有正文"}"#), .json("彻底不是 JSON"))
        let client = makeClient()
        let first = try await client.fetch(url: "https://example.com/sparse", access: access())
        XCTAssertEqual(first.url, "https://example.com/sparse")
        XCTAssertEqual(first.title, "https://example.com/sparse")
        XCTAssertEqual(first.markdown, "只有正文")

        let second = try await client.fetch(url: "https://example.com/broken", access: access())
        XCTAssertEqual(second.url, "https://example.com/broken")
        XCTAssertEqual(second.title, "https://example.com/broken")
        XCTAssertEqual(second.markdown, "")
    }

    /// fetch 的报文：端点、url 字段、Authorization 都要对。
    func testFetchRequestShape() async throws {
        StubURLProtocol.enqueue(.json(#"{"url":"https://example.com/x","title":"标题","markdown":"正文"}"#))
        _ = try await makeClient().fetch(url: "  https://example.com/x  ", access: access())
        let request = try recordedRequest()
        XCTAssertEqual(request.url?.absoluteString, "https://api.deepseek.com/v1/tools/fetch")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer sk-test")
        let body = try recordedBody()
        XCTAssertEqual(body["url"] as? String, "https://example.com/x", "url 要 trim 后发")
        XCTAssertNil(body["query"], "fetch 只发 url")
    }

    // MARK: - 错误

    /// HTTP 非 2xx：message 取响应体里的 error.message；拿不到就用中文兜底。
    func testHttpErrorUsesServerMessageOrChineseFallback() async throws {
        StubURLProtocol.enqueue(
            .json(#"{"error":{"message":"联网工具服务开小差了"}}"#, status: 500),
            .json("oops", status: 500)
        )
        let client = makeClient()

        let withServerMessage = await assertFailure(
            .http(status: 500, message: "联网工具服务开小差了")
        ) {
            _ = try await client.search(query: "坏服务", access: self.access())
        }
        XCTAssertEqual(withServerMessage?.errorDescription, "联网工具服务开小差了",
                       "有 error.message 就用它")

        let fallback = await assertFailure(.http(status: 500, message: "联网工具返回了 HTTP 500")) {
            _ = try await client.search(query: "坏服务", access: self.access())
        }
        // 兜底文案必须是中文（模型会把 message 原样抄进回复）。
        XCTAssertNotNil(fallback?.message.range(of: "\\p{Han}", options: .regularExpression))
    }

    /// http:// 但没打开「允许不安全 HTTP」：直接判配置错误，一个字节都不发。
    func testInsecureHttpBaseUrlRejectedUnlessAllowed() async throws {
        let insecure = access(baseUrl: "http://127.0.0.1:8787", allowInsecureHttp: false)
        await assertFailure(.invalidBaseUrl("http://127.0.0.1:8787")) {
            _ = try await self.makeClient().search(query: "本地服务", access: insecure)
        }
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "不允许 http 时不该把 Key 明文发出去")

        StubURLProtocol.enqueue(.json(#"{"results":[]}"#))
        let allowed = access(baseUrl: "http://127.0.0.1:8787", allowInsecureHttp: true)
        _ = try await makeClient().search(query: "本地服务", access: allowed)
        XCTAssertEqual(
            try recordedRequest().url?.absoluteString,
            "http://127.0.0.1:8787/v1/tools/search",
            "显式允许后要能连本地参考服务"
        )
    }

    /// 没填 Key：抛 `.missingAPIKey`，不发请求（空 Key 只会换来 401）。
    func testMissingAPIKeyRejectedBeforeSending() async throws {
        await assertFailure(.missingAPIKey) {
            _ = try await self.makeClient().search(query: "你好", access: self.access(apiKey: "   "))
        }
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty)
    }

    /// 空关键词：抛 `.emptyQuery`，不发请求。
    func testEmptyQueryRejectedBeforeSending() async throws {
        await assertFailure(.emptyQuery) {
            _ = try await self.makeClient().search(query: "   ", access: self.access())
        }
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty)
    }

    /// 要抓的地址不是 http(s)：抛 `.invalidUrl`，不发请求。
    func testFetchRejectsInvalidPageUrl() async throws {
        for badUrl in ["", "   ", "example.com/post", "javascript:alert(1)", "file:///etc/passwd"] {
            await assertFailure(.invalidUrl(badUrl.trimmingCharacters(in: .whitespacesAndNewlines))) {
                _ = try await self.makeClient().fetch(url: badUrl, access: self.access())
            }
        }
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty)
    }

    /// 错误都能给出「一句人话」，且没有一个是空的（工具执行器直接回灌给模型）。
    func testEveryFailureHasReadableChineseMessage() {
        let failures: [WebToolsFailure] = [
            .invalidBaseUrl("http://x"),
            .invalidUrl("javascript:1"),
            .emptyQuery,
            .missingAPIKey,
            .http(status: 500, message: ""),
            .network("")
        ]
        for failure in failures {
            XCTAssertFalse(failure.message.isEmpty, "\(failure) 的 message 不能为空")
            XCTAssertNotNil(failure.message.range(of: "\\p{Han}", options: .regularExpression),
                            "\(failure) 的 message 应该是中文：\(failure.message)")
            XCTAssertEqual(failure.errorDescription, failure.message)
        }
    }
}
