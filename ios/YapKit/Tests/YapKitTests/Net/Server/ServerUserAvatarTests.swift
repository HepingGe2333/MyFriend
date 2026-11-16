import Foundation
import XCTest
@testable import YapKit

/// 用户头像上传（0.12.1，`USER_AVATAR`）：成功 / 失败 / 已经是服务端媒体 / 本地文件没了。
///
/// 传输层用 `ServerRouteStub`（一个真实的 `POST /v1/media`），准备与上传各自可注入：
/// 「先压缩再上传」「kind 是 USER_AVATAR」「上传失败要能看见」这三条是这层唯一的职责。
final class ServerUserAvatarTests: XCTestCase {

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerRouteStub.reset()
    }

    override func tearDownWithError() throws {
        ServerRouteStub.reset()
        try super.tearDownWithError()
    }

    private func client() -> ServerAPIClient {
        ServerAPIClient(
            config: ServerConfig(
                baseURL: URL(string: "https://server.example.com")!,
                token: "secret-token"
            ),
            session: ServerRouteStub.makeSession()
        )
    }

    // MARK: - 准备器 / 上传器的注入

    func testUploadPreparesThenUploadsAsUserAvatar() async throws {
        let uploader = RecordingMediaUploader()
        let preparer = FakeAvatarPreparer(bytes: Data(repeating: 0xAB, count: 4096))
        let avatar = ServerUserAvatarUploader(uploader: uploader, preparer: preparer)

        let result = try await avatar.upload(localURI: "avatar.jpg")

        XCTAssertEqual(result.mediaId, "media-9")
        XCTAssertEqual(result.uri, "/v1/media/media-9")
        XCTAssertEqual(result.bytes, 4096)
        XCTAssertEqual(result.mime, "image/jpeg")
        XCTAssertEqual(uploader.calls.count, 1)
        XCTAssertEqual(uploader.calls.first?.kind, "USER_AVATAR")
        XCTAssertNil(uploader.calls.first?.personaId, "用户头像不挂在任何人格上")
        XCTAssertEqual(uploader.calls.first?.mime, "image/jpeg")
        XCTAssertEqual(uploader.calls.first?.bytes, 4096)
    }

    func testAlreadyServerMediaIsNotUploadedAgain() async throws {
        let uploader = RecordingMediaUploader()
        let avatar = ServerUserAvatarUploader(uploader: uploader, preparer: FakeAvatarPreparer())

        let result = try await avatar.upload(localURI: "/v1/media/abc123")

        XCTAssertEqual(result.mediaId, "abc123")
        XCTAssertEqual(result.uri, "/v1/media/abc123")
        XCTAssertEqual(result.bytes, 0)
        XCTAssertTrue(uploader.calls.isEmpty, "已经有服务端引用就不该再传一次")
    }

    func testMissingLocalFileThrowsFriendlyError() async throws {
        let avatar = ServerUserAvatarUploader(
            uploader: RecordingMediaUploader(),
            preparer: FakeAvatarPreparer(missing: true)
        )
        do {
            _ = try await avatar.upload(localURI: "gone.jpg")
            XCTFail("应当抛错")
        } catch let error as ServerUserAvatarError {
            XCTAssertEqual(error, .localFileMissing)
            XCTAssertFalse(error.localizedDescription.isEmpty)
        }
    }

    func testEmptyPreparedBytesThrows() async throws {
        let avatar = ServerUserAvatarUploader(
            uploader: RecordingMediaUploader(),
            preparer: FakeAvatarPreparer(bytes: Data())
        )
        do {
            _ = try await avatar.upload(localURI: "avatar.jpg")
            XCTFail("应当抛错")
        } catch let error as ServerUserAvatarError {
            XCTAssertEqual(error, .emptyPayload)
        }
    }

    func testUploadFailurePropagatesServerError() async throws {
        let uploader = RecordingMediaUploader()
        uploader.error = ServerAPIError(
            code: "storage_failed", status: 500, message: "服务端存不下这张图"
        )
        let avatar = ServerUserAvatarUploader(uploader: uploader, preparer: FakeAvatarPreparer())
        do {
            _ = try await avatar.upload(localURI: "avatar.jpg")
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, "storage_failed")
            XCTAssertEqual(error.status, 500)
        }
    }

    // MARK: - 真传输层（契约：kind=USER_AVATAR + 原始字节）

    func testUploadsRawBytesWithUserAvatarKindOverHTTP() async throws {
        let server = BootstrapFakeServer()
        server.registerRoutes()
        let avatar = ServerUserAvatarUploader(uploader: client(), preparer: FakeAvatarPreparer())

        let result = try await avatar.upload(localURI: "avatar.jpg")

        XCTAssertEqual(server.uploadedMedia.count, 1)
        XCTAssertEqual(server.uploadedMedia.first?.kind, "USER_AVATAR")
        XCTAssertEqual(server.uploadedMedia.first?.bytes, 32)
        XCTAssertTrue(result.uri.hasPrefix("/v1/media/"))

        let request = try XCTUnwrap(ServerRouteStub.recorded.first { $0.path == "/v1/media" })
        XCTAssertEqual(request.method, "POST")
        XCTAssertEqual(request.query["kind"], "USER_AVATAR")
        XCTAssertEqual(request.body.count, 32, "body 是压缩后的原始字节，不是 base64")
    }

    func testHTTPUploadFailureSurfacesAsServerAPIError() async throws {
        let server = BootstrapFakeServer()
        server.registerRoutes()
        server.failMediaUpload = true
        let avatar = ServerUserAvatarUploader(uploader: client(), preparer: FakeAvatarPreparer())
        do {
            _ = try await avatar.upload(localURI: "avatar.jpg")
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.status, 500)
        }
    }

    /// 头像的压缩预算（≤512 / ≤400KB）是选图路径已有的策略，这里钉一下别被改坏。
    func testAvatarEncodingBudgetIsUnchanged() {
        XCTAssertEqual(ImageEncodingPolicy.longEdge(for: .avatar), 512)
        XCTAssertEqual(ImageEncodingPolicy.byteLimit(for: .avatar), 400 * 1024)
        XCTAssertLessThan(
            ImageEncodingPolicy.byteLimit(for: .avatar),
            ServerAPIClient.maxMediaUploadBytes,
            "压缩产物必须落在服务端单文件上限以内"
        )
    }
}

// MARK: - 测试替身

/// 假的「压缩产物」：直接给字节，测的是上传器怎么用它，不是怎么压缩。
struct FakeAvatarPreparer: ServerChatMediaPreparing {
    var bytes: Data = Data(repeating: 0x11, count: 32)
    var mime: String = "image/jpeg"
    var missing = false

    func prepare(attachment: MessageAttachment, kind: MediaKind) -> ServerMediaPreparation? {
        guard !missing else { return nil }
        return ServerMediaPreparation(bytes: bytes, mime: mime)
    }
}

/// 记录「用什么 kind/字节上传」的假上传器。
final class RecordingMediaUploader: ServerMediaUploading {
    var result = ServerMedia(
        id: "media-9", kind: "USER_AVATAR", mime: "image/jpeg", bytes: 32,
        sha256: "", url: nil
    )
    var error: Error?
    private(set) var calls: [(bytes: Int, mime: String, kind: String, personaId: String?)] = []

    func uploadMedia(
        bytes: Data,
        mime: String,
        kind: String,
        personaId: String?
    ) async throws -> ServerMedia {
        calls.append((bytes: bytes.count, mime: mime, kind: kind, personaId: personaId))
        if let error { throw error }
        return result
    }
}
