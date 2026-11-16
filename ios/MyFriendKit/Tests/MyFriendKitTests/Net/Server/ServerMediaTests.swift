import CoreGraphics
import Foundation
import ImageIO
import XCTest
@testable import MyFriendKit

/// M5 服务端媒体：上传请求形状 / 错误映射 / 下载 / 引用解析 / 磁盘缓存。
final class ServerMediaTests: XCTestCase {

    private var cacheDirectory: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerURLProtocolStub.reset()
        cacheDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("mf-server-media-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        ServerURLProtocolStub.reset()
        ServerMediaCache.shared.configure(client: nil)
        if let cacheDirectory { try? FileManager.default.removeItem(at: cacheDirectory) }
        try super.tearDownWithError()
    }

    private func client() -> ServerAPIClient {
        ServerAPIClient(
            config: ServerConfig(baseURL: ServerTestSupport.defaultBaseURL, token: "secret-token"),
            session: ServerURLProtocolStub.makeSession()
        )
    }

    private static let uploadResponseJSON = """
    {"id":"m-1","kind":"CHAT_IMAGE","mime":"image/jpeg","bytes":12345,
     "sha256":"\(String(repeating: "a", count: 64))","url":"/v1/media/m-1"}
    """

    // MARK: - 上传

    func testUploadSendsRawBytesAndQueryWithAuthorization() async throws {
        ServerURLProtocolStub.enqueue(.json(Self.uploadResponseJSON))
        let bytes = Data([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46])

        let media = try await client().uploadMedia(
            bytes: bytes, mime: "image/jpeg", kind: "CHAT_IMAGE", personaId: "p1"
        )
        XCTAssertEqual(media.id, "m-1")
        XCTAssertEqual(media.url, "/v1/media/m-1")
        XCTAssertEqual(media.kind, "CHAT_IMAGE")
        XCTAssertEqual(media.mime, "image/jpeg")
        XCTAssertEqual(media.bytes, 12345)

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/media")
        XCTAssertEqual(ServerTestSupport.queryItems(request)["kind"], "CHAT_IMAGE")
        XCTAssertEqual(ServerTestSupport.queryItems(request)["mime"], "image/jpeg")
        XCTAssertEqual(ServerTestSupport.queryItems(request)["personaId"], "p1")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "image/jpeg")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer secret-token")
        XCTAssertEqual(ServerURLProtocolStub.recordedBodies.last, bytes, "body 必须是原始字节，不是 base64/JSON")
    }

    func testUploadOmitsPersonaAndFallsBackToCanonicalURL() async throws {
        ServerURLProtocolStub.enqueue(.json(#"{"id":"m-2","kind":"PERSONA_AVATAR","mime":"image/png","bytes":3}"#))
        let media = try await client().uploadMedia(
            bytes: Data([1, 2, 3]), mime: "image/png", kind: "PERSONA_AVATAR"
        )
        XCTAssertEqual(media.url, "/v1/media/m-2", "服务端没给 url 时按 id 拼标准路径")
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertNil(ServerTestSupport.queryItems(request)["personaId"])
    }

    func testUploadRejectsEmptyTooLargeAndUnsupportedMimeLocally() async throws {
        let api = client()
        await XCTAssertThrowsErrorAsync({
            try await api.uploadMedia(bytes: Data(), mime: "image/jpeg", kind: "CHAT_IMAGE")
        }) { error in
            XCTAssertEqual((error as? ServerAPIError)?.code, ServerAPIError.Code.invalidArgument)
        }
        let tooLarge = Data(count: ServerAPIClient.maxMediaUploadBytes + 1)
        await XCTAssertThrowsErrorAsync({
            try await api.uploadMedia(bytes: tooLarge, mime: "image/jpeg", kind: "CHAT_IMAGE")
        }) { error in
            XCTAssertEqual((error as? ServerAPIError)?.code, ServerAPIError.Code.invalidArgument)
        }
        await XCTAssertThrowsErrorAsync({
            try await api.uploadMedia(bytes: Data([1]), mime: "image/gif", kind: "CHAT_IMAGE")
        }) { error in
            XCTAssertEqual((error as? ServerAPIError)?.code, ServerAPIError.Code.invalidArgument)
        }
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty, "本地能拒的请求不该发出去")
    }

    /// 服务端的错误体原样翻成中文（413 `content_too_large`）。
    func testUploadSurfacesServerErrorBody() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"message":"文件太大（上限 2 MiB）","code":"content_too_large"}}"#,
            status: 413
        ))
        do {
            _ = try await client().uploadMedia(
                bytes: Data([1, 2, 3]), mime: "image/jpeg", kind: "CHAT_IMAGE"
            )
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, "content_too_large")
            XCTAssertEqual(error.status, 413)
            XCTAssertEqual(error.message, "文件太大（上限 2 MiB）")
        }
    }

    // MARK: - 下载

    func testMediaDataDownloadsWithAuthorization() async throws {
        let payload = Data([0xFF, 0xD8, 0xFF, 0xD9])
        ServerURLProtocolStub.enqueue(ServerURLProtocolStub.Response(
            status: 200,
            headers: ["Content-Type": "image/jpeg"],
            chunks: [payload]
        ))
        let data = try await client().mediaData(uri: "/v1/media/m-1")
        XCTAssertEqual(data, payload)
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/v1/media/m-1")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer secret-token")
    }

    func testMediaDataUnknownIdIsNotFoundError() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"message":"没有这张图","code":"not_found"}}"#, status: 404
        ))
        do {
            _ = try await client().mediaData(mediaId: "gone")
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, "not_found")
            XCTAssertEqual(error.status, 404)
        }
    }

    // MARK: - 引用解析

    func testMediaReferenceParsing() {
        XCTAssertEqual(ServerMediaReference.mediaId(from: "/v1/media/abc"), "abc")
        XCTAssertEqual(ServerMediaReference.mediaId(from: "https://x.example/v1/media/abc"), "abc")
        XCTAssertEqual(ServerMediaReference.mediaId(from: "server:old-id"), "old-id", "M4 旧形状也认")
        XCTAssertEqual(ServerMediaReference.mediaId(from: " /v1/media/abc?x=1 "), "abc")
        XCTAssertNil(ServerMediaReference.mediaId(from: "file:///tmp/a.jpg"))
        XCTAssertNil(ServerMediaReference.mediaId(from: "/v1/media/"))
        XCTAssertNil(ServerMediaReference.mediaId(from: "/v1/media/a/b"))
        XCTAssertEqual(ServerMediaReference.uri(id: "abc"), "/v1/media/abc")
    }

    // MARK: - 缓存

    func testCacheDownloadsOnceThenReadsDisk() async throws {
        let jpeg = try makeJPEG(width: 40, height: 30)
        ServerURLProtocolStub.enqueue(ServerURLProtocolStub.Response(
            status: 200, headers: ["Content-Type": "image/jpeg"], chunks: [jpeg]
        ))
        let cache = ServerMediaCache(directory: cacheDirectory)
        cache.configure(client: client())

        let first = try await cache.data(for: "/v1/media/m-cache")
        XCTAssertEqual(first, jpeg)
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests.count, 1)

        // 第二次：不再发请求（磁盘命中）。
        let second = try await cache.data(for: "/v1/media/m-cache")
        XCTAssertEqual(second, jpeg)
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests.count, 1, "第二次数不应再打网络")
        XCTAssertTrue(cache.hasCachedData(for: "/v1/media/m-cache"))

        // 缩略图能解出来（按显示尺寸解码这条路真的通）。
        let thumbnail = await cache.thumbnail(for: "/v1/media/m-cache", maxPixelSize: 20)
        XCTAssertNotNil(thumbnail)
        XCTAssertLessThanOrEqual(max(thumbnail?.width ?? 0, thumbnail?.height ?? 0), 20)

        // 清空之后磁盘里不再有它。
        cache.clear()
        XCTAssertFalse(cache.hasCachedData(for: "/v1/media/m-cache"))
    }

    func testCacheWithoutClientThrowsNotConfigured() async throws {
        let cache = ServerMediaCache(directory: cacheDirectory)
        do {
            _ = try await cache.data(for: "/v1/media/whatever")
            XCTFail("没配置客户端应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.notConfigured)
        }
        let missingThumbnail = await cache.thumbnail(for: "/v1/media/whatever", maxPixelSize: 20)
        XCTAssertNil(missingThumbnail)
    }

    // MARK: - 本地附件 → 上传字节

    /// 选图路径已经压好的 JPEG：prepare 直接复用（不重编码、尺寸/体积都在限内）。
    func testLocalPreparerReusesAlreadyCompressedChatImage() throws {
        let directory = cacheDirectory.appendingPathComponent("attachments", isDirectory: true)
        let storage = MediaStorage(directory: directory)
        let source = try makeJPEG(width: 120, height: 90)
        let attachment = try storage.saveImage(data: source, kind: .chatImage)

        let prepared = try XCTUnwrap(
            LocalChatMediaPreparer(storage: storage)
                .prepare(attachment: attachment, kind: .chatImage)
        )
        XCTAssertFalse(prepared.bytes.isEmpty)
        XCTAssertEqual(prepared.mime, "image/jpeg")
        XCTAssertEqual(prepared.width, 120)
        XCTAssertEqual(prepared.height, 90)
        XCTAssertTrue(ImageThumbnailDecoder.isJPEG(data: prepared.bytes))
    }

    /// 不是 JPEG（带透明通道的 PNG）会先重压成 JPEG（垫白底），再上传。
    func testLocalPreparerCompressesPNGBeforeUpload() throws {
        let directory = cacheDirectory.appendingPathComponent("attachments-png", isDirectory: true)
        let storage = MediaStorage(directory: directory)
        let png = try makePNG(width: 64, height: 64)
        let url = directory.appendingPathComponent("chat-png.png")
        try png.write(to: url)
        let attachment = MessageAttachment(uri: url.absoluteString, mime: "image/png")

        let prepared = try XCTUnwrap(
            LocalChatMediaPreparer(storage: storage)
                .prepare(attachment: attachment, kind: .chatImage)
        )
        XCTAssertTrue(ImageThumbnailDecoder.isJPEG(data: prepared.bytes), "上传前必须统一成 JPEG")
        XCTAssertEqual(prepared.mime, "image/jpeg")
    }

    func testLocalPreparerReturnsNilForMissingFile() {
        let storage = MediaStorage(directory: cacheDirectory.appendingPathComponent("empty"))
        let missing = MessageAttachment(uri: "file:///tmp/does-not-exist.jpg")
        XCTAssertNil(LocalChatMediaPreparer(storage: storage).prepare(
            attachment: missing, kind: .chatImage
        ))
    }

    // MARK: - 夹具

    /// 生成一张真的 PNG（带 alpha，用来验证「非 JPEG 先重压」）。
    private func makePNG(width: Int, height: Int) throws -> Data {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let context = try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: colorSpace, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 0))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try XCTUnwrap(context.makeImage())
        let data = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(
            data as CFMutableData, "public.png" as CFString, 1, nil
        ))
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }

    /// 生成一张真的 JPEG（缓存/缩略图需要能被 ImageIO 解码）。
    private func makeJPEG(width: Int, height: Int) throws -> Data {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let context = try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: colorSpace, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.setFillColor(CGColor(red: 0.4, green: 0.5, blue: 0.6, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try XCTUnwrap(context.makeImage())
        let data = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(
            data as CFMutableData, "public.jpeg" as CFString, 1, nil
        ))
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }
}

/// `XCTAssertThrowsError` 的 async 版本（XCTest 没有内建）。
private func XCTAssertThrowsErrorAsync<T>(
    _ expression: () async throws -> T,
    file: StaticString = #filePath,
    line: UInt = #line,
    _ handler: (Error) -> Void = { _ in }
) async {
    do {
        _ = try await expression()
        XCTFail("应当抛错", file: file, line: line)
    } catch {
        handler(error)
    }
}
