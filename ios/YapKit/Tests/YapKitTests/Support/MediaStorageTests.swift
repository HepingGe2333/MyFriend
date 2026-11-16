import CoreGraphics
import Foundation
import ImageIO
import XCTest
@testable import YapKit

/// 图片压缩参数、沙盒落盘、缩略图解码与内存预算。
///
/// 这一层是「真的会吃内存」的地方，所以两类断言都要有：
/// 纯函数（尺寸/质量/上限怎么算）与真解码（ImageIO 出来的图到底多大）。
final class MediaStorageTests: XCTestCase {

    private var directory: URL!
    private var storage: MediaStorage!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-media-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        storage = MediaStorage(directory: directory.appendingPathComponent("attachments", isDirectory: true))
    }

    override func tearDownWithError() throws {
        if let directory {
            try? FileManager.default.removeItem(at: directory)
        }
        storage = nil
        try super.tearDownWithError()
    }

    // MARK: - 纯函数：尺寸

    func testFittedSizeKeepsAspectAndCapsLongEdge() {
        let landscape = ImageEncodingPolicy.fittedSize(width: 4000, height: 3000, longEdge: 1600)
        XCTAssertEqual(landscape.width, 1600)
        XCTAssertEqual(landscape.height, 1200)

        let portrait = ImageEncodingPolicy.fittedSize(width: 3000, height: 4000, longEdge: 1600)
        XCTAssertEqual(portrait.width, 1200)
        XCTAssertEqual(portrait.height, 1600)

        // 本来就小：不放大（放大只会让文件变大、画质变糊）。
        let small = ImageEncodingPolicy.fittedSize(width: 800, height: 600, longEdge: 1600)
        XCTAssertEqual(small.width, 800)
        XCTAssertEqual(small.height, 600)

        // 极端细长：短边至少留 1px。
        let strip = ImageEncodingPolicy.fittedSize(width: 1, height: 4000, longEdge: 1600)
        XCTAssertEqual(strip.width, 1)
        XCTAssertEqual(strip.height, 1600)

        // 非法输入原样退回（由调用方当坏图处理）。
        XCTAssertEqual(ImageEncodingPolicy.fittedSize(width: 0, height: 10, longEdge: 1600).width, 0)
    }

    func testKindParametersMatchSpec() {
        XCTAssertEqual(ImageEncodingPolicy.longEdge(for: .chatImage), 1600)
        XCTAssertEqual(ImageEncodingPolicy.jpegQuality(for: .chatImage), 0.85, accuracy: 0.0001)
        XCTAssertEqual(ImageEncodingPolicy.byteLimit(for: .chatImage), 800 * 1024)

        XCTAssertEqual(ImageEncodingPolicy.longEdge(for: .avatar), 512)
        XCTAssertEqual(ImageEncodingPolicy.jpegQuality(for: .avatar), 0.82, accuracy: 0.0001)
        XCTAssertEqual(ImageEncodingPolicy.byteLimit(for: .avatar), 400 * 1024)

        XCTAssertFalse(ImageEncodingPolicy.isTooLargeInput(800 * 1024))
        XCTAssertTrue(ImageEncodingPolicy.isTooLargeInput(21 * 1024 * 1024))
    }

    /// 压缩重试阶梯：从 0.85 一路降到 0.55，再低就不降了（宁可略超体积）。
    func testQualityLadderStopsAtMinimum() {
        XCTAssertEqual(
            ImageEncodingPolicy.qualityLadder(from: 0.85),
            [0.85, 0.75, 0.65, 0.55]
        )
        XCTAssertEqual(ImageEncodingPolicy.qualityLadder(from: 0.55), [0.55])
        // 传进来的质量本来就低于下限：保留它本身，不凭空变好。
        XCTAssertEqual(ImageEncodingPolicy.qualityLadder(from: 0.4), [0.4])
    }

    // MARK: - 纯函数：内存预算

    func testThumbnailCacheLimitIsTenPercentCappedAt48MB() {
        // 大内存设备：封顶 48MB。
        XCTAssertEqual(
            MemoryBudget.thumbnailCacheLimitBytes(physicalMemory: 8 * 1024 * 1024 * 1024),
            48 * 1024 * 1024
        )
        XCTAssertEqual(
            MemoryBudget.thumbnailCacheLimitBytes(physicalMemory: 1 * 1024 * 1024 * 1024),
            48 * 1024 * 1024
        )
        // 小内存设备：按 10% 走，别把聊天本身挤掉。
        XCTAssertEqual(
            MemoryBudget.thumbnailCacheLimitBytes(physicalMemory: 256 * 1024 * 1024),
            Int(256.0 * 1024 * 1024 * 0.10)
        )
        // 极端值不返回 0（NSCache 的 0 会变成「不限」，反而更危险）。
        XCTAssertEqual(MemoryBudget.thumbnailCacheLimitBytes(physicalMemory: 0), 1)
        XCTAssertEqual(MemoryBudget.imageCostBytes(width: 100, height: 50), 100 * 50 * 4)
    }

    // MARK: - 路径重定位（容器 UUID 变了）

    /// iOS 的数据容器路径里带 UUID，重装/迁移之后会变。
    /// 绝对路径打不开时，用同名文件在当前附件目录里找回来。
    func testAttachmentLocationRebasesWhenContainerPathChanged() {
        let directory = URL(fileURLWithPath: "/new/container/attachments", isDirectory: true)
        let existing = "/new/container/attachments/chat-a.jpg"
        let result = AttachmentLocation.resolve(
            "file:///old/container/attachments/chat-a.jpg",
            directory: directory,
            fileExists: { $0 == existing }
        )
        XCTAssertEqual(result?.path, existing)

        // 相对路径（防御手工数据）也按当前目录解。
        XCTAssertEqual(
            AttachmentLocation.resolve("chat-a.jpg", directory: directory, fileExists: { $0 == existing })?.path,
            existing
        )

        // 两边都找不到：返回 nil，由界面显示「图片已不在本机」。
        XCTAssertNil(AttachmentLocation.resolve(
            "file:///old/container/attachments/gone.jpg", directory: directory, fileExists: { _ in false }
        ))
        // 外部地址不是本机文件。
        XCTAssertNil(AttachmentLocation.resolve("https://example.com/a.jpg", directory: directory))
        XCTAssertNil(AttachmentLocation.resolve("content://media/external/1", directory: directory))
        XCTAssertNil(AttachmentLocation.resolve("   ", directory: directory))
    }

    /// 真落盘之后把 `uri` 改写成「旧容器路径」，仍然读得回来（模拟更新后容器变名）。
    func testSavedImageIsRecoveredAfterContainerRename() throws {
        let attachment = try storage.saveImage(
            data: makeJPEG(width: 300, height: 200), kind: .chatImage
        )
        let name = (attachment.uri as NSString).lastPathComponent
        let stale = MessageAttachment(uri: "file:///old/container/attachments/\(name)")
        XCTAssertTrue(storage.fileExists(stale))
        XCTAssertNotNil(storage.dataURL(for: stale))
        XCTAssertNotNil(storage.thumbnail(for: stale, maxPixelSize: 80))
    }

    // MARK: - 真解码 / 落盘

    func testSaveChatImageDownscalesAndWritesFile() throws {
        let jpeg = try makeJPEG(width: 3000, height: 2000)
        let attachment = try storage.saveImage(data: jpeg, kind: .chatImage)

        XCTAssertEqual(attachment.width, 1600)
        // 3000×2000 → 1600×1067（四舍五入）。
        XCTAssertEqual(attachment.height, 1067)
        XCTAssertEqual(attachment.mime, "image/jpeg")
        XCTAssertTrue(attachment.uri.hasPrefix("file://"))
        XCTAssertTrue(attachment.uri.hasSuffix(".jpg"))

        let url = try XCTUnwrap(storage.fileURL(for: attachment.uri))
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        let size = (try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.intValue ?? 0
        XCTAssertGreaterThan(size, 0)
        XCTAssertLessThanOrEqual(size, ImageEncodingPolicy.chatMaxBytes)

        // 落盘的图真的就是 1600×1067，不是「元数据写着 1600、文件里还是原图」。
        let pixelSize = try XCTUnwrap(ImageThumbnailDecoder.pixelSize(url: url))
        XCTAssertEqual(pixelSize.width, 1600)
        XCTAssertEqual(pixelSize.height, 1067)

        // data URL 形状与 Android 的 `MediaStorage.toDataUrl` 一致。
        let dataURL = try XCTUnwrap(storage.dataURL(for: attachment))
        XCTAssertTrue(dataURL.hasPrefix("data:image/jpeg;base64,"))
        XCTAssertGreaterThan(dataURL.count, 100)
    }

    func testSaveAvatarDownscalesTo512() throws {
        let jpeg = try makeJPEG(width: 2000, height: 1000)
        let attachment = try storage.saveImage(data: jpeg, kind: .avatar)
        XCTAssertEqual(attachment.width, 512)
        XCTAssertEqual(attachment.height, 256)
    }

    /// 小图不放大：头像的原图只有 100×80 时，存下来还是 100×80。
    func testSmallImageIsNotUpscaled() throws {
        let jpeg = try makeJPEG(width: 100, height: 80)
        let attachment = try storage.saveImage(data: jpeg, kind: .avatar)
        XCTAssertEqual(attachment.width, 100)
        XCTAssertEqual(attachment.height, 80)
    }

    func testRejectsEmptyTooLargeAndUnreadableInput() {
        XCTAssertThrowsError(try storage.saveImage(data: Data(), kind: .chatImage)) { error in
            XCTAssertEqual(error as? MediaStorage.StorageError, .emptyImage)
        }
        XCTAssertThrowsError(
            try storage.saveImage(data: Data(count: 21 * 1024 * 1024), kind: .chatImage)
        ) { error in
            XCTAssertEqual(error as? MediaStorage.StorageError, .tooLarge)
        }
        XCTAssertThrowsError(
            try storage.saveImage(data: Data("不是图片".utf8), kind: .chatImage)
        ) { error in
            XCTAssertEqual(error as? MediaStorage.StorageError, .unreadable)
        }
        // 错误文案是给用户看的，必须是中文。
        XCTAssertEqual(MediaStorage.StorageError.tooLarge.errorDescription, "图片超过 20 MB，请换一张小一点的")
    }

    /// 文件被系统清掉（或者从 Android 备份接回来）时：不崩，返回 nil，界面显示「图片已不在本机」。
    func testMissingFileDegradesGracefully() {
        let missing = MessageAttachment(uri: "file:///var/mobile/not-there.jpg", width: 100, height: 100)
        XCTAssertFalse(storage.fileExists(missing))
        XCTAssertNil(storage.dataURL(for: missing))
        XCTAssertNil(storage.thumbnail(for: missing, maxPixelSize: 100))
        // 删不存在的文件也不该抛。
        storage.delete(missing)
        // 非 file:// 的地址（外部相册）一律当文件不见了。
        XCTAssertNil(storage.fileURL(for: "https://example.com/a.jpg"))
        XCTAssertNil(storage.fileURL(for: "content://media/external/images/1"))
        XCTAssertNil(storage.fileURL(for: ""))
    }

    func testThumbnailDecodesAtRequestedSize() throws {
        let jpeg = try makeJPEG(width: 1200, height: 800)
        let attachment = try storage.saveImage(data: jpeg, kind: .chatImage)
        // 存的是 1200×800；聊天页按 ~260pt 显示，解出来的长边不该超过请求值。
        let thumbnail = try XCTUnwrap(storage.thumbnail(for: attachment, maxPixelSize: 260))
        XCTAssertLessThanOrEqual(max(thumbnail.width, thumbnail.height), 260)
        XCTAssertGreaterThan(thumbnail.width, 0)

        // 第二次走缓存：内容一样（缓存命中时拿到的是同一张 CGImage）。
        let again = try XCTUnwrap(storage.thumbnail(for: attachment, maxPixelSize: 260))
        XCTAssertEqual(again.width, thumbnail.width)
        XCTAssertEqual(again.height, thumbnail.height)
    }

    func testDeleteRemovesFileButKeepsOthers() throws {
        // 两张**不同**的图（尺寸不同 → 压缩后字节不同）：删一张不能连带删另一张。
        let first = try storage.saveImage(data: makeJPEG(width: 200, height: 200), kind: .chatImage)
        let second = try storage.saveImage(data: makeJPEG(width: 240, height: 240), kind: .chatImage)

        XCTAssertTrue(storage.fileExists(first))
        XCTAssertTrue(storage.fileExists(second))
        XCTAssertNotEqual(first.uri, second.uri)
        storage.delete(first)
        XCTAssertFalse(storage.fileExists(first))
        XCTAssertTrue(storage.fileExists(second))

        storage.deleteAttachments(in: AttachmentsCodec.encode([second]))
        XCTAssertFalse(storage.fileExists(second))
    }

    /// 重复选同一张图：压缩后的 SHA-256 相同 → 复用同一个文件（不新增表）。
    func testSaveImageDeduplicatesSameContentByHash() throws {
        let jpeg = try makeJPEG(width: 600, height: 400)
        let first = try storage.saveImage(data: jpeg, kind: .chatImage)
        let second = try storage.saveImage(data: jpeg, kind: .chatImage)

        XCTAssertEqual(first.uri, second.uri, "同一张图两次落盘应当复用同一个文件")
        XCTAssertEqual(
            try FileManager.default.contentsOfDirectory(atPath: storage.directory.path).count, 1,
            "目录里只该有一个文件"
        )
        let name = (first.uri as NSString).lastPathComponent
        let hash = ImageContentHash.shortPrefix(try Data(contentsOf: XCTUnwrap(storage.fileURL(for: first.uri))))
        XCTAssertTrue(name.hasPrefix("chatImage-\(hash)-"), "文件名要带 SHA-256 前缀：\(name)")
    }

    /// 不同图仍然各存一份（去重不能把内容不一样的图合并）。
    func testDifferentImagesAreNotDeduplicated() throws {
        let first = try storage.saveImage(data: makeJPEG(width: 300, height: 200), kind: .chatImage)
        let second = try storage.saveImage(data: makeJPEG(width: 301, height: 200), kind: .chatImage)
        XCTAssertNotEqual(first.uri, second.uri)
        XCTAssertEqual(
            try FileManager.default.contentsOfDirectory(atPath: storage.directory.path).count, 2
        )
    }

    func testTotalBytesOnDiskTracksStoredFiles() throws {
        XCTAssertEqual(storage.totalBytesOnDisk(), 0)
        _ = try storage.saveImage(data: makeJPEG(width: 400, height: 300), kind: .chatImage)
        XCTAssertGreaterThan(storage.totalBytesOnDisk(), 0)
        storage.deleteAll()
        XCTAssertEqual(storage.totalBytesOnDisk(), 0)
    }

    // MARK: - 造图

    /// 画一张纯色图并编码成 JPEG：测试里要的是「尺寸/体积」，不是画面内容。
    private func makeJPEG(width: Int, height: Int, quality: Double = 0.9) throws -> Data {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue
        let context = try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: colorSpace, bitmapInfo: bitmapInfo
        ))
        context.setFillColor(CGColor(red: 0.62, green: 0.42, blue: 0.31, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        // 加一点噪点，避免纯色图被 JPEG 压成几百字节（体积断言就没意义了）。
        context.setFillColor(CGColor(red: 0.2, green: 0.7, blue: 0.5, alpha: 1))
        for index in stride(from: 0, to: width, by: 7) {
            context.fill(CGRect(x: index, y: (index / 7) % max(height, 1), width: 4, height: 4))
        }
        let image = try XCTUnwrap(context.makeImage())

        let data = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(
            data as CFMutableData, "public.jpeg" as CFString, 1, nil
        ))
        CGImageDestinationAddImage(
            destination, image,
            [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary
        )
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }

    // MARK: - 相册选图走文件路径（0.11.0 审计 P2-14）

    /// 从文件 URL 落盘：按目标尺寸缩略图解码，不是先读整张图的 Data。
    func testSaveImageFromFileURLDownscalesWithoutLoadingFullImage() throws {
        let source = try makeJPEGFile(width: 4000, height: 3000)
        defer { try? FileManager.default.removeItem(at: source) }

        let attachment = try storage.saveImage(at: source, kind: .chatImage)
        XCTAssertLessThanOrEqual(max(attachment.width, attachment.height), ImageEncodingPolicy.chatMaxLongEdge)
        XCTAssertEqual(attachment.width, 1600)
        XCTAssertEqual(attachment.height, 1200)
        let stored = try XCTUnwrap(storage.fileURL(for: attachment.uri))
        XCTAssertTrue(FileManager.default.fileExists(atPath: stored.path))
        // 原图没有被搬进附件目录（只留压好的那一份）。
        XCTAssertNotEqual(stored.lastPathComponent, source.lastPathComponent)
    }

    /// 超过 20 MB 的文件**在解码之前**就被拒：先看文件大小，再决定要不要碰像素。
    ///
    /// 这里放的是「不是图片」的 21 MB 稀疏文件：如果实现先解码，会得到 `.unreadable`；
    /// 得到 `.tooLarge` 才说明大小判断真的在解码前面。
    func testSaveImageRejectsOversizedFilePathBeforeDecoding() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-oversized-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let huge = directory.appendingPathComponent("huge.jpg")
        FileManager.default.createFile(atPath: huge.path, contents: Data())
        let handle = try FileHandle(forWritingTo: huge)
        try handle.truncate(atOffset: UInt64(ImageEncodingPolicy.maxInputBytes + 1))
        try handle.close()

        XCTAssertThrowsError(try storage.saveImage(at: huge, kind: .chatImage)) { error in
            XCTAssertEqual(error as? MediaStorage.StorageError, .tooLarge)
        }
        // 上限的纯逻辑：20 MB 整可以，多 1 字节就不行。
        XCTAssertFalse(ImageEncodingPolicy.isTooLargeInput(ImageEncodingPolicy.maxInputBytes))
        XCTAssertTrue(ImageEncodingPolicy.isTooLargeInput(ImageEncodingPolicy.maxInputBytes + 1))
    }

    /// 空文件 / 不存在的文件：给出与人话一致的错误，不崩。
    func testSaveImageFromMissingOrEmptyFileFailsGracefully() throws {
        let missing = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-missing-\(UUID().uuidString).jpg")
        XCTAssertThrowsError(try storage.saveImage(at: missing, kind: .avatar)) { error in
            XCTAssertEqual(error as? MediaStorage.StorageError, .emptyImage)
        }
    }

    private func makeJPEGFile(width: Int, height: Int) throws -> URL {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let context = try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: colorSpace, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.setFillColor(CGColor(red: 0.4, green: 0.5, blue: 0.6, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try XCTUnwrap(context.makeImage())
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-source-\(UUID().uuidString).jpg")
        let destination = try XCTUnwrap(CGImageDestinationCreateWithURL(
            url as CFURL, "public.jpeg" as CFString, 1, nil
        ))
        CGImageDestinationAddImage(
            destination, image,
            [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary
        )
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return url
    }
}
