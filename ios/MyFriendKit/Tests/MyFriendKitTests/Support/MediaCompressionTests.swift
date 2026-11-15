import CoreGraphics
import Foundation
import ImageIO
import XCTest
@testable import MyFriendKit

/// 0.12：媒体在**备份导入**时的压缩与去重。
///
/// 规范（`spec/web-search/README.md` 同批交付）：头像长边 ≤512、聊天图长边 ≤1600、
/// JPEG 0.85、透明垫白底；体积上限沿用选图路径（头像 400KB / 聊天图 800KB）；
/// 重复导入同一张图按 SHA-256 去重（文件名带哈希前缀，不新增表）。
final class MediaCompressionTests: XCTestCase {

    private var directory: URL!
    private let fileManager = FileManager.default

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = fileManager.temporaryDirectory
            .appendingPathComponent("mf-media-compression-\(UUID().uuidString)", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let directory { try? fileManager.removeItem(at: directory) }
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func entry(
        kind: BackupMediaKind,
        fileName: String,
        data: Data,
        mime: String = "image/jpeg"
    ) -> BackupMedia {
        BackupMedia(
            id: UUID().uuidString, kind: kind.rawValue, personaId: nil,
            fileName: fileName, mime: mime,
            bytes: data.count, data: data.base64EncodedString()
        )
    }

    private func fileNames() -> [String] {
        ((try? fileManager.contentsOfDirectory(atPath: directory.path)) ?? []).sorted()
    }

    // MARK: - 尺寸与体积

    /// 聊天图：长边 1600、JPEG、≤800KB。
    func testImportedChatImageIsDownscaledAndCompressed() throws {
        let source = try makeNoisyJPEG(width: 2400, height: 1600)
        XCTAssertGreaterThan(source.count, ImageEncodingPolicy.chatMaxBytes,
                             "夹具要真的超过 800KB，压缩断言才有意义")

        let result = BackupMediaRestorer.restore(
            entries: [entry(kind: .chatImage, fileName: "chat-big.jpg", data: source)],
            directory: directory
        )
        XCTAssertEqual(result.invalidCount, 0)
        let restored = try XCTUnwrap(result.chatImagesByFileName["chat-big.jpg"])
        XCTAssertEqual(restored.mime, "image/jpeg")
        XCTAssertEqual(restored.fileName, "chat-big.jpg", "关联键仍是备份里的裸文件名")
        XCTAssertTrue(restored.fileName.contains("/") == false)

        let url = try XCTUnwrap(URL(string: restored.uri))
        XCTAssertTrue(AttachmentLocation.isWithin(directory, url))
        let data = try Data(contentsOf: url)
        XCTAssertEqual(data.prefix(2), Data([0xFF, 0xD8]), "落盘必须是 JPEG")
        XCTAssertLessThanOrEqual(data.count, ImageEncodingPolicy.chatMaxBytes)
        let size = try XCTUnwrap(ImageThumbnailDecoder.pixelSize(url: url))
        // 规范是「长边 ≤1600 且 ≤800KB」；噪点图在 1600 宽时压不进 800KB，
        // 实现会再缩一档尺寸（1200×800），两条上限都守住。
        XCTAssertLessThanOrEqual(max(size.width, size.height), ImageEncodingPolicy.chatMaxLongEdge)
        XCTAssertLessThanOrEqual(size.width, 1600)
        XCTAssertLessThanOrEqual(size.height, 1067)
        XCTAssertEqual(Double(size.width) / Double(size.height), 1.5, accuracy: 0.01, "保持 3:2 比例")
    }

    /// 头像：长边 512、≤400KB。
    func testImportedAvatarIsDownscaledTo512() throws {
        let source = try makeNoisyJPEG(width: 2000, height: 3000)
        let result = BackupMediaRestorer.restore(
            entries: [entry(kind: .personaAvatar, fileName: "avatar-big.jpg", data: source)],
            directory: directory
        )
        let restored = try XCTUnwrap(result.avatarsByFileName["avatar-big.jpg"])
        let url = try XCTUnwrap(URL(string: restored.uri))
        let size = try XCTUnwrap(ImageThumbnailDecoder.pixelSize(url: url))
        XCTAssertEqual(max(size.width, size.height), ImageEncodingPolicy.avatarMaxLongEdge)
        XCTAssertEqual(size.width, 341)
        XCTAssertEqual(size.height, 512)
        XCTAssertLessThanOrEqual(
            try Data(contentsOf: url).count, ImageEncodingPolicy.avatarMaxBytes
        )
    }

    /// 小图不放大：2×2 的图导进来还是 2×2（只压体积，不改尺寸）。
    func testSmallImportedImageIsNotUpscaled() throws {
        let source = Data(base64Encoded: BackupSamples.sampleJPEGBase64)!
        let result = BackupMediaRestorer.restore(
            entries: [entry(kind: .userAvatar, fileName: "avatar-small.jpg", data: source)],
            directory: directory
        )
        let url = try XCTUnwrap(URL(string: try XCTUnwrap(result.userAvatar).uri))
        let size = try XCTUnwrap(ImageThumbnailDecoder.pixelSize(url: url))
        XCTAssertEqual(size.width, 2)
        XCTAssertEqual(size.height, 2)
    }

    // MARK: - 透明垫白底

    /// 全透明的 PNG 头像：JPEG 没有 alpha，透明区域必须垫成**白**，不能是黑块。
    func testTransparentPNGIsFlattenedOnWhite() throws {
        let transparentPNG = try makePNG(width: 200, height: 200, color: CGColor(
            red: 0, green: 0, blue: 0, alpha: 0
        ))
        let result = BackupMediaRestorer.restore(
            entries: [entry(
                kind: .personaAvatar, fileName: "avatar-alpha.png",
                data: transparentPNG, mime: "image/png"
            )],
            directory: directory
        )
        let url = try XCTUnwrap(URL(string: try XCTUnwrap(result.avatarsByFileName["avatar-alpha.png"]).uri))
        let pixel = try averageCornerPixel(url: url)
        XCTAssertGreaterThanOrEqual(pixel.red, 240, "透明处应当垫成白色")
        XCTAssertGreaterThanOrEqual(pixel.green, 240)
        XCTAssertGreaterThanOrEqual(pixel.blue, 240)
    }

    // MARK: - 去重

    /// 同一份备份里两条 CHAT_IMAGE 内容相同：只写一个文件，两个源文件名都映射到它。
    func testRepeatedEntriesWithSameBytesShareOneFile() throws {
        let source = try makeNoisyJPEG(width: 800, height: 600)
        let result = BackupMediaRestorer.restore(
            entries: [
                entry(kind: .chatImage, fileName: "chat-a.jpg", data: source),
                entry(kind: .chatImage, fileName: "chat-b.jpg", data: source)
            ],
            directory: directory
        )
        XCTAssertEqual(result.writtenFiles.count, 1, "同一内容只该落盘一次")
        let first = try XCTUnwrap(result.chatImagesByFileName["chat-a.jpg"])
        let second = try XCTUnwrap(result.chatImagesByFileName["chat-b.jpg"])
        XCTAssertEqual(first.uri, second.uri)
        XCTAssertEqual(fileNames().count, 1)
        XCTAssertTrue(fileNames()[0].hasPrefix("chatImage-"))
        XCTAssertTrue(fileNames()[0].contains("-"), "文件名带 SHA-256 前缀")
    }

    /// 同一份 v3 备份接两次（同一个目录）：第二次不再写文件，复用上次那张。
    func testImportingTheSameBackupTwiceReusesTheSameFile() throws {
        let source = try makeNoisyJPEG(width: 640, height: 480)
        let imports = [
            entry(kind: .chatImage, fileName: "chat-again.jpg", data: source),
            entry(kind: .chatImage, fileName: "chat-again.jpg", data: source)
        ]
        let first = BackupMediaRestorer.restore(entries: [imports[0]], directory: directory)
        let second = BackupMediaRestorer.restore(entries: [imports[1]], directory: directory)
        XCTAssertEqual(first.writtenFiles.count, 1)
        XCTAssertTrue(second.writtenFiles.isEmpty, "第二次应当认出来已有这张图，而不是再写一份")
        XCTAssertEqual(
            first.chatImagesByFileName.values.first?.uri,
            second.chatImagesByFileName.values.first?.uri
        )
        XCTAssertEqual(fileNames().count, 1)
    }

    /// 内容不同（哪怕只差一个像素）不能误合并。
    func testDifferentContentIsNotDeduplicated() throws {
        let first = try makeNoisyJPEG(width: 400, height: 300, seed: 1)
        let second = try makeNoisyJPEG(width: 400, height: 300, seed: 2)
        let result = BackupMediaRestorer.restore(
            entries: [
                entry(kind: .chatImage, fileName: "chat-1.jpg", data: first),
                entry(kind: .chatImage, fileName: "chat-2.jpg", data: second)
            ],
            directory: directory
        )
        XCTAssertEqual(result.writtenFiles.count, 2)
        XCTAssertEqual(fileNames().count, 2)
    }

    // MARK: - 坏数据（压缩不能把「坏图」变成「整份导入失败」）

    func testUndecodableImageIsSkippedNotFatal() throws {
        let bad = Data([0xFF, 0xD8, 0xFF, 0x00, 0x01])
        let result = BackupMediaRestorer.restore(
            entries: [entry(kind: .chatImage, fileName: "chat-bad.jpg", data: bad)],
            directory: directory
        )
        XCTAssertEqual(result.invalidCount, 1)
        XCTAssertTrue(result.writtenFiles.isEmpty)
        XCTAssertTrue(result.chatImagesByFileName.isEmpty)
        XCTAssertEqual(fileNames().count, 0)
    }

    // MARK: - 造图 / 取像素

    /// 随机噪点图：纯色图会被 JPEG 压得太小，测不出体积上限。
    private func makeNoisyJPEG(width: Int, height: Int, seed: UInt64 = 7) throws -> Data {
        var generator = SplitMix64(seed: seed)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        for index in stride(from: 0, to: bytes.count, by: 4) {
            bytes[index] = UInt8(generator.next() & 0xFF)
            bytes[index + 1] = UInt8(generator.next() & 0xFF)
            bytes[index + 2] = UInt8(generator.next() & 0xFF)
            bytes[index + 3] = 255
        }
        let provider = try XCTUnwrap(CGDataProvider(data: Data(bytes) as CFData))
        let image = try XCTUnwrap(CGImage(
            width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: width * 4, space: colorSpace,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false,
            intent: .defaultIntent
        ))
        let data = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(
            data as CFMutableData, "public.jpeg" as CFString, 1, nil
        ))
        CGImageDestinationAddImage(
            destination, image,
            [kCGImageDestinationLossyCompressionQuality: 0.95] as CFDictionary
        )
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }

    private func makePNG(width: Int, height: Int, color: CGColor) throws -> Data {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let context = try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: colorSpace, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.setFillColor(color)
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

    /// 把图缩到 1×1 读平均色（JPEG 有噪点，取整块平均比单像素稳）。
    private func averageCornerPixel(url: URL) throws -> (red: Int, green: Int, blue: Int) {
        let source = try XCTUnwrap(CGImageSourceCreateWithURL(url as CFURL, nil))
        let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        var pixel = [UInt8](repeating: 0, count: 4)
        let context = try pixel.withUnsafeMutableBytes { buffer -> CGContext in
            try XCTUnwrap(CGContext(
                data: buffer.baseAddress, width: 1, height: 1, bitsPerComponent: 8,
                bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ))
        }
        context.draw(image, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        return (Int(pixel[0]), Int(pixel[1]), Int(pixel[2]))
    }
}

/// 测试里要可复现的噪点，不用 `arc4random`（每次跑结果不同，体积断言会抖）。
private struct SplitMix64 {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
