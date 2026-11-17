import Foundation
import XCTest
@testable import YapKit

/// 语音/视频落盘（`Application Support/audio|video`）：原子写、目录归属守卫、时长/体积拒绝、
/// 以及「不转码」的拷贝语义。
final class AudioVideoStoreTests: XCTestCase {

    private var root: URL!
    private var store: AudioVideoStore!

    override func setUpWithError() throws {
        try super.setUpWithError()
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("yap-av-store-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        store = AudioVideoStore(
            audioDirectory: root.appendingPathComponent("audio", isDirectory: true),
            videoDirectory: root.appendingPathComponent("video", isDirectory: true)
        )
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
        root = nil
        store = nil
        try super.tearDownWithError()
    }

    private func audioBytes(_ count: Int = 2_048) -> Data {
        Data((0..<count).map { UInt8($0 % 251) })
    }

    // MARK: - 语音

    func testSaveAudioWritesUnderAudioDirectoryAndResolvesBack() throws {
        let data = audioBytes()
        let attachment = try store.saveAudio(data: data, durationMs: 12_000)

        XCTAssertTrue(attachment.isAudio)
        XCTAssertEqual(attachment.kind, MessageAttachment.audioKind)
        XCTAssertEqual(attachment.mime, "audio/m4a")
        XCTAssertEqual(attachment.durationMs, 12_000)
        let url = try XCTUnwrap(store.fileURL(for: attachment))
        XCTAssertTrue(url.path.hasPrefix(root.appendingPathComponent("audio").path))
        XCTAssertEqual(url.pathExtension, "m4a")
        XCTAssertEqual(try Data(contentsOf: url), data, "音频不转码：字节必须原样落盘")
        XCTAssertEqual(store.data(for: attachment), data)
        XCTAssertEqual(store.byteCount(for: attachment), data.count)

        // 归属守卫：它确实在 audio 目录里；删得掉。
        store.deleteIfOwned(attachment)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    func testSaveAudioRejectsTooLongTooLargeAndUnsupportedMime() {
        XCTAssertThrowsError(try store.saveAudio(data: audioBytes(), durationMs: 5 * 60_000 + 1)) { error in
            XCTAssertEqual(
                (error as? AudioVideoStore.StorageError)?.errorDescription,
                "语音最长 5 分钟"
            )
        }
        let oversized = Data(count: AudioVideoLimits.audioMaxBytes + 1)
        XCTAssertThrowsError(try store.saveAudio(data: oversized, durationMs: 1_000)) { error in
            XCTAssertEqual(
                (error as? AudioVideoStore.StorageError)?.errorDescription,
                "语音超过 8 MB"
            )
        }
        XCTAssertThrowsError(
            try store.saveAudio(data: audioBytes(), durationMs: 1_000, mime: "audio/ogg")
        ) { error in
            guard case .some(.unsupportedMime("audio/ogg")) = error as? AudioVideoStore.StorageError else {
                return XCTFail("应当报「不支持这种格式」：\(error)")
            }
        }
        XCTAssertThrowsError(try store.saveAudio(data: Data(), durationMs: 1_000))
    }

    // MARK: - 视频（原样拷贝，不转码）

    func testImportVideoCopiesBytesWithoutTranscoding() throws {
        let source = root.appendingPathComponent("picker-tmp.mov")
        let bytes = audioBytes(4_096)
        try bytes.write(to: source)
        let attachment = try store.importVideo(
            at: source, durationMs: 30_000, width: 1920, height: 1080, mime: "video/quicktime"
        )
        XCTAssertTrue(attachment.isVideo)
        XCTAssertEqual(attachment.durationMs, 30_000)
        XCTAssertEqual(attachment.width, 1920)
        let url = try XCTUnwrap(store.fileURL(for: attachment))
        XCTAssertTrue(url.path.hasPrefix(root.appendingPathComponent("video").path))
        XCTAssertEqual(url.pathExtension, "mov")
        XCTAssertEqual(try Data(contentsOf: url), bytes)
    }

    func testImportVideoRejectsOverlongAndOversized() throws {
        let source = root.appendingPathComponent("long.mp4")
        try audioBytes(64).write(to: source)
        XCTAssertThrowsError(try store.importVideo(at: source, durationMs: 2 * 60_000 + 1)) { error in
            XCTAssertEqual(
                (error as? AudioVideoStore.StorageError)?.errorDescription,
                "视频最长 2 分钟"
            )
        }

        // 稀疏文件：占位 64 MB + 1，不真的分配内存/磁盘。
        let big = root.appendingPathComponent("big.mp4")
        FileManager.default.createFile(atPath: big.path, contents: nil)
        let handle = try FileHandle(forWritingTo: big)
        try handle.truncate(atOffset: UInt64(AudioVideoLimits.videoMaxBytes + 1))
        try handle.close()
        XCTAssertThrowsError(try store.importVideo(at: big, durationMs: 1_000)) { error in
            XCTAssertEqual(
                (error as? AudioVideoStore.StorageError)?.errorDescription,
                "视频超过 64 MB"
            )
            XCTAssertTrue(store.totalBytesOnDisk() < AudioVideoLimits.videoMaxBytes, "超限文件不该落盘")
        }
    }

    // MARK: - 归属守卫

    func testResolveRefusesPathsOutsideManagedDirectories() {
        // 绝对路径在别的目录：不认。
        XCTAssertNil(store.fileURL(for: "file:///etc/passwd", kind: .audio))
        // 路径穿越：不认。
        XCTAssertNil(store.fileURL(for: "../../yap.db", kind: .audio))
        XCTAssertNil(store.fileURL(for: "file:///..%2F..%2Fyap.db", kind: .audio))
        // 音频附件不能从视频目录里解出来（目录归属按 kind 分开）。
        let attachment = MessageAttachment.audio(uri: "file:///tmp/audio-1.m4a", durationMs: 1_000)
        XCTAssertNil(store.fileURL(for: attachment))
    }

    func testValidationAcceptsAllowedMimesAndRejectsOthers() {
        let audio = MessageAttachment.audio(uri: "file:///x.m4a", durationMs: 3_000)
        XCTAssertNil(MediaAttachmentValidation.rejection(attachment: audio, byteCount: 1_000))
        XCTAssertEqual(
            MediaAttachmentValidation.rejection(attachment: audio, byteCount: AudioVideoLimits.audioMaxBytes + 1),
            "语音超过 8 MB"
        )
        let badMime = MessageAttachment(uri: "file:///x.ogg", mime: "audio/ogg", kind: "AUDIO", durationMs: 1_000)
        XCTAssertTrue(
            (MediaAttachmentValidation.rejection(attachment: badMime, byteCount: 100) ?? "").contains("格式不支持")
        )
        let image = MessageAttachment(uri: "file:///x.heic", mime: "image/heic")
        XCTAssertTrue(
            (MediaAttachmentValidation.rejection(attachment: image, byteCount: 100) ?? "").contains("不支持")
        )
    }
}
