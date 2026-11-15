import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import MyFriendKit

// ---------------------------------------------------------------------------
// iOS 备份基准：500 张聊天图片（v3 备份把图片用 base64 带进 JSON）的
// 导出 / 导入耗时、JSON 体积、峰值内存增量。
//
// 为什么必须量峰值内存：v3 的图片是 base64 字符串，导出路径上同时存在
//   原始字节（逐个文件读入）→ base64 字符串（约 4/3 原始大小）→ 整份 JSON 的 Data
//   → 整份 JSON 的 String。
// 四份东西叠在一起，峰值 ≈ 3 倍原始字节；导入路径上还要再叠一份 JSONValue 语法树。
// 500 张图看起来才几十 MB，峰值却可能摸到几百 MB —— 这正是 iOS 上「备份时被 Jetsam 杀掉」
// 的典型原因，也是这条基准要给出的数字。
//
// 口径与 Android 对齐：500 张、单张 ≤800KB、图片挂在消息附件上、导出走 `exportWithWarnings()`、
// 导入走 `importBackup(text:mode:.replace)`（都是生产入口）。图片用真实 JPEG（CoreGraphics +
// ImageIO 生成），不是随便的字节 —— 尺寸/压缩率对 base64 体积与内存都有影响。
// ---------------------------------------------------------------------------

final class BackupMediaBenchmarkTests: XCTestCase {

    // MARK: - 基准参数

    /// 默认 500 张；`BENCH_IMAGE_COUNT=20` 之类的覆盖只用于冒烟。
    private static let imageCount = benchmarkInt("BENCH_IMAGE_COUNT", default: 500)
    /// 噪点图：接近真实照片，JPEG 压不动（800×600 q0.5 ≈ 300KB/张）。
    private static let noiseImageCount = imageCount / 2
    /// 纯色图：接近截图/表情包，极小（1024×768 q0.8 ≈ 13KB/张）。
    private static let solidImageCount = imageCount - imageCount / 2
    private static let noiseWidth = 800
    private static let noiseHeight = 600
    private static let solidWidth = 1024
    private static let solidHeight = 768
    private static let exportRepeats = benchmarkInt("BENCH_EXPORT_REPEATS", default: 3)
    private static let importRepeats = benchmarkInt("BENCH_IMPORT_REPEATS", default: 2)

    /// 环境变量覆盖（只给调试/冒烟用；正式报告用默认值）。
    private static func benchmarkInt(_ name: String, default fallback: Int) -> Int {
        guard let raw = ProcessInfo.processInfo.environment[name], let value = Int(raw), value > 0 else {
            return fallback
        }
        return value
    }

    private static let personaId = "persona-bench"
    private static let sessionId = "session-bench"
    private static let configId = "config-bench"
    private static let baseTimestamp: Int64 = 1_794_500_000_000

    private var root: URL!
    private var mediaDirectory: URL!
    private var databasePath: String!
    private var stores: DatabaseStores?
    private var exportedText: String?

    override func setUpWithError() throws {
        try super.setUpWithError()
        try BenchmarkGate.require()
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-bench-backup-\(UUID().uuidString)", isDirectory: true)
        mediaDirectory = root.appendingPathComponent("attachments", isDirectory: true)
        try FileManager.default.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
        databasePath = root.appendingPathComponent("bench.sqlite").path
    }

    override func tearDownWithError() throws {
        if let stores { stores.database.close() }
        stores = nil
        exportedText = nil
        if let root { try? FileManager.default.removeItem(at: root) }
        try super.tearDownWithError()
    }

    // MARK: - 主用例

    func testBackupWith500ImagesExportImportMemoryAndSize() throws {
        // 1) 造 500 张真实 JPEG + 引用它们的消息
        let generated = try generateImages()
        let images = generated.images
        let imageBytesTotal = images.reduce(0) { $0 + $1.bytes }
        XCTAssertLessThanOrEqual(
            imageBytesTotal, BackupService.maxMediaBytes,
            "原始图片总量必须落在备份的媒体预算内（否则导出会跳过一部分，测的就不是 500 张了）"
        )
        let seeded = try seedDatabase(images: images)
        stores = seeded.stores
        let imageSizes = images.map(\.bytes).sorted()
        print(BenchmarkFormat.table([
            ("图片", "\(images.count) 张（噪点 \(Self.noiseImageCount) · 纯色 \(Self.solidImageCount)）"),
            ("原始字节", BenchmarkFormat.bytes(Int64(imageBytesTotal))
                + "（min \(BenchmarkFormat.bytes(Int64(imageSizes.first ?? 0)))"
                + " / max \(BenchmarkFormat.bytes(Int64(imageSizes.last ?? 0)))）"),
            ("图片生成+落盘耗时", BenchmarkFormat.ms(generated.milliseconds)),
            ("建库/挂附件耗时", BenchmarkFormat.ms(seeded.seedMilliseconds))
        ]))

        // 2) 导出 ×3（第 1 次的结果留给导入）
        let service = makeService(stores: seeded.stores, mediaDirectory: mediaDirectory)
        var exportRuns: [OperationRun] = []
        var exportWarnings: [String] = []
        for run in 0..<Self.exportRepeats {
            let (result, milliseconds, memory) = try measured { try service.exportWithWarnings() }
            if run == 0 {
                exportedText = result.text
                exportWarnings = result.warnings
            }
            exportRuns.append(OperationRun(milliseconds: milliseconds, memory: memory, extra: [
                "textBytes": result.text.utf8.count,
                "warnings": result.warnings
            ]))
            print("IOS-BENCH  导出第 \(run + 1) 次：\(BenchmarkFormat.ms(milliseconds))"
                + " · JSON \(BenchmarkFormat.bytes(Int64(result.text.utf8.count)))"
                + " · 峰值增量 footprint "
                + BenchmarkFormat.mb(ProcessMemory.Snapshot.megabytes(memory.peakFootprintDeltaBytes)))
        }
        let text = try XCTUnwrap(exportedText, "第一次导出应当留下文本")
        XCTAssertTrue(exportWarnings.isEmpty, "500 张图都在预算内，不该有「没带走」的警告：\(exportWarnings)")
        XCTAssertLessThanOrEqual(text.utf8.count, BackupService.maxExportBytes, "导出体积必须在自己能导回来的上限内")
        let exportTiming = BenchmarkStats(exportRuns.map(\.milliseconds))
        let exportPeakFootprint = exportRuns.map(\.memory.peakFootprintDeltaBytes).max() ?? 0
        let exportPeakResident = exportRuns.map(\.memory.peakResidentDeltaBytes).max() ?? 0
        // 峰值绝对值也记一份：增量会被「基线里已经有一份上一次导出的文本」影响。
        let exportPeakFootprintAbsolute = exportRuns.map(\.memory.peak.footprintBytes).max() ?? 0

        // 备份里到底带了几张图（解码一次核对，不参与计时）
        let decoded = BackupCodec.decode(text)
        let bundle = try XCTUnwrap(decoded.bundle, "导出的备份必须能读回来：\(decoded.failureReason ?? "?")")
        let chatImageCount = bundle.media.filter { $0.kind == BackupMediaKind.chatImage.rawValue }.count
        XCTAssertEqual(chatImageCount, Self.imageCount, "500 张聊天图片都要在 media 里")
        XCTAssertEqual(bundle.messages.count, Self.imageCount)
        XCTAssertTrue(decoded.warnings.isEmpty, "不该有警告：\(decoded.warnings)")

        print(BenchmarkFormat.table([
            ("导出 median", BenchmarkFormat.ms(exportTiming.medianMilliseconds)),
            ("导出 min/max", "\(BenchmarkFormat.ms(exportTiming.minMilliseconds)) / \(BenchmarkFormat.ms(exportTiming.maxMilliseconds))"),
            ("JSON 体积", BenchmarkFormat.bytes(Int64(text.utf8.count))
                + "（原始 \(BenchmarkFormat.bytes(Int64(imageBytesTotal))) 的 "
                + String(format: "%.2f×", Double(text.utf8.count) / Double(max(imageBytesTotal, 1))) + "）"),
            ("导出峰值增量 footprint", BenchmarkFormat.mb(ProcessMemory.Snapshot.megabytes(exportPeakFootprint))),
            ("导出峰值增量 resident", BenchmarkFormat.mb(ProcessMemory.Snapshot.megabytes(exportPeakResident))),
            ("导出峰值绝对 footprint", BenchmarkFormat.mb(ProcessMemory.Snapshot.megabytes(exportPeakFootprintAbsolute))),
            ("media 条目", "\(bundle.media.count)（CHAT_IMAGE \(chatImageCount)）")
        ]))

        // 3) 导入 ×2（每次一个全新的库 + 空附件目录，验证 500 张图真的落盘了）
        var importRuns: [OperationRun] = []
        var verification: [String: Any] = [:]
        for run in 0..<Self.importRepeats {
            let importRoot = root.appendingPathComponent("import-\(run)", isDirectory: true)
            let importMedia = importRoot.appendingPathComponent("attachments", isDirectory: true)
            try FileManager.default.createDirectory(at: importMedia, withIntermediateDirectories: true)
            let fresh = try DatabaseStores.open(path: importRoot.appendingPathComponent("import.sqlite").path)
            let importService = makeService(stores: fresh, mediaDirectory: importMedia)
            let heldTextBytes = text.utf8.count
            let (summary, milliseconds, memory) = try measured {
                try importService.importBackup(text: text, mode: .replace)
            }
            let restoredFiles = try FileManager.default.contentsOfDirectory(at: importMedia, includingPropertiesForKeys: nil)
            let restoredBytes = try restoredFiles.reduce(Int64(0)) { partial, url in
                let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
                let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
                return partial + size
            }
            let importedMessages = try fresh.messages.count()
            // 导入侧 0.12 起会把图片**重新压缩**（JPEG 0.85）并按内容 hash 去重，
            // 所以「落盘字节 == 导出前原始字节」不再成立；这里核对的是「张数、可解码、引用有效」。
            let restoredJpegCount = restoredFiles.reduce(into: 0) { count, url in
                if CGImageSourceCreateWithURL(url as CFURL, nil) != nil { count += 1 }
            }
            // 导入侧 0.12 起按「压缩后内容的 SHA-256 前 64 bit」去重：内容相同的图只落一个文件。
            // 500 张里那些颜色只差 1 个色阶的纯色图会被压成同样的字节，所以**落盘文件数可能少于
            // 图片数**；真正要保证的是「每条消息的附件都指到一个真实存在的文件」。
            var messagesWithValidAttachment = 0
            var sampleUri = ""
            for offset in 0..<Self.imageCount {
                let message = try fresh.messages.byId("bench-msg-\(offset)")
                guard let raw = message?.attachmentsJson,
                      let attachment = AttachmentsCodec.decode(raw).first,
                      let fileURL = URL(string: attachment.uri),
                      FileManager.default.fileExists(atPath: fileURL.path) else { continue }
                messagesWithValidAttachment += 1
                if offset == 0 { sampleUri = attachment.uri }
            }
            let imageWarnings = summary.warnings.filter { $0.contains("图片") || $0.contains("句话") }
            if ProcessInfo.processInfo.environment["BENCH_DEBUG"] == "1" {
                print("IOS-BENCH-DEBUG summary=\(summary.describe())")
                print("IOS-BENCH-DEBUG restoredBytes=\(restoredBytes) rawBytes=\(imageBytesTotal)"
                    + " jpeg=\(restoredJpegCount)/\(restoredFiles.count)")
                print("IOS-BENCH-DEBUG validRefs=\(messagesWithValidAttachment)/\(Self.imageCount)")
            }
            let expectedSummary = Self.imageCount
            XCTAssertEqual(summary.messages, expectedSummary, "每条带图消息都要接回来")
            XCTAssertEqual(importedMessages, expectedSummary)
            XCTAssertGreaterThan(restoredFiles.count, expectedSummary / 2, "落盘文件数应当接近图片数（去重只影响内容相同的图）")
            XCTAssertLessThanOrEqual(restoredFiles.count, expectedSummary)
            XCTAssertEqual(restoredJpegCount, restoredFiles.count, "落盘的每个文件都应当能当 JPEG 解码")
            XCTAssertEqual(messagesWithValidAttachment, expectedSummary, "每条消息的附件都要指向真实存在的文件")
            XCTAssertTrue(imageWarnings.isEmpty, "不该有图片丢失的警告：\(summary.warnings)")
            XCTAssertTrue(sampleUri.hasPrefix("file:"), "导入后附件应当重写成当前容器的 file:// 绝对路径：\(sampleUri)")
            importRuns.append(OperationRun(milliseconds: milliseconds, memory: memory, extra: [
                "heldExportTextBytes": heldTextBytes,
                "restoredFiles": restoredFiles.count,
                "restoredBytes": Int(restoredBytes),
                "restoredJpegCount": restoredJpegCount,
                "messagesWithValidAttachment": messagesWithValidAttachment,
                "importedMessages": importedMessages,
                "warnings": summary.warnings
            ]))
            verification = [
                "messages": importedMessages,
                "restoredFiles": restoredFiles.count,
                "deduplicatedFiles": Self.imageCount - restoredFiles.count,
                "restoredBytes": Int(restoredBytes),
                "restoredOverOriginalRatio": BenchmarkStats.round(
                    Double(restoredBytes) / Double(max(imageBytesTotal, 1))
                ),
                "restoredJpegCount": restoredJpegCount,
                "messagesWithValidAttachment": messagesWithValidAttachment,
                "sampleUri": sampleUri,
                "warnings": summary.warnings,
                "note": "0.12 起导入会重新压缩（JPEG 0.85、长边 ≤1600）并按内容 hash 去重：落盘体积与原始体积不再相等，内容相同的图共用一个文件"
            ]
            print("IOS-BENCH  导入第 \(run + 1) 次：\(BenchmarkFormat.ms(milliseconds))"
                + " · 落盘 \(restoredFiles.count) 张 / \(BenchmarkFormat.bytes(restoredBytes))"
                + " · 峰值增量 footprint "
                + BenchmarkFormat.mb(ProcessMemory.Snapshot.megabytes(memory.peakFootprintDeltaBytes))
                + "（内存里还压着 \(BenchmarkFormat.bytes(Int64(heldTextBytes))) 的备份文本）")
            fresh.database.close()
            try? FileManager.default.removeItem(at: importRoot)
        }
        let importTiming = BenchmarkStats(importRuns.map(\.milliseconds))
        let importPeakFootprint = importRuns.map(\.memory.peakFootprintDeltaBytes).max() ?? 0
        let importPeakResident = importRuns.map(\.memory.peakResidentDeltaBytes).max() ?? 0
        let importPeakFootprintAbsolute = importRuns.map(\.memory.peak.footprintBytes).max() ?? 0
        print(BenchmarkFormat.table([
            ("导入 median", BenchmarkFormat.ms(importTiming.medianMilliseconds)),
            ("导入 min/max", "\(BenchmarkFormat.ms(importTiming.minMilliseconds)) / \(BenchmarkFormat.ms(importTiming.maxMilliseconds))"),
            ("导入峰值增量 footprint", BenchmarkFormat.mb(ProcessMemory.Snapshot.megabytes(importPeakFootprint))),
            ("导入峰值增量 resident", BenchmarkFormat.mb(ProcessMemory.Snapshot.megabytes(importPeakResident))),
            ("导入峰值绝对 footprint", BenchmarkFormat.mb(ProcessMemory.Snapshot.megabytes(importPeakFootprintAbsolute)))
        ]))

        // 4) 落盘 JSON + 打印结论数字
        let payload: [String: Any] = [
            "kind": "ios-backup-media",
            "generatedAt": iso8601Now(),
            "howToRun": "cd ios/MyFriendKit && RUN_BENCHMARKS=1 swift test --disable-sandbox --filter BackupMediaBenchmarkTests",
            "environment": BenchmarkEnvironment.capture(extra: [
                "backupFormatVersion": backupFormatVersion,
                "backupServiceMaxExportBytes": BackupService.maxExportBytes,
                "backupServiceMaxMediaBytes": BackupService.maxMediaBytes,
                "backupServiceMaxMediaFileBytes": BackupService.maxMediaFileBytes
            ]),
            "dataset": [
                "images": images.count,
                "noiseImages": Self.noiseImageCount,
                "solidImages": Self.solidImageCount,
                "noiseDimensions": "\(Self.noiseWidth)x\(Self.noiseHeight)",
                "solidDimensions": "\(Self.solidWidth)x\(Self.solidHeight)",
                "jpegQuality": "噪点 0.5 / 纯色 0.8",
                "rawBytesTotal": imageBytesTotal,
                "rawBytesMin": imageSizes.first ?? 0,
                "rawBytesMax": imageSizes.last ?? 0,
                "rawBytesMean": Int(Double(imageBytesTotal) / Double(max(images.count, 1))),
                "messages": Self.imageCount,
                "attachmentsPerMessage": 1,
                "generateMilliseconds": BenchmarkStats.round(generated.milliseconds),
                "seedMilliseconds": BenchmarkStats.round(seeded.seedMilliseconds)
            ],
            "export": [
                "repeats": Self.exportRepeats,
                "jsonBytes": text.utf8.count,
                "jsonOverRawBytes": BenchmarkStats.round(Double(text.utf8.count) / Double(max(imageBytesTotal, 1))),
                "mediaEntries": bundle.media.count,
                "chatImageEntries": chatImageCount,
                "mediaDeclaredBytes": bundle.media.reduce(0) { $0 + $1.bytes },
                "mediaDeclaredOverRawBytes": BenchmarkStats.round(
                    Double(bundle.media.reduce(0) { $0 + $1.bytes }) / Double(max(imageBytesTotal, 1))
                ),
                "warnings": exportWarnings,
                "timing": exportTiming.dictionary,
                "runs": exportRuns.map(\.dictionary),
                "peakFootprintDeltaMBMax": ProcessMemory.Snapshot.round(
                    ProcessMemory.Snapshot.megabytes(exportPeakFootprint)
                ),
                "peakResidentDeltaMBMax": ProcessMemory.Snapshot.round(
                    ProcessMemory.Snapshot.megabytes(exportPeakResident)
                ),
                "peakFootprintMBMax": ProcessMemory.Snapshot.round(
                    ProcessMemory.Snapshot.megabytes(exportPeakFootprintAbsolute)
                )
            ],
            "import": [
                "repeats": Self.importRepeats,
                "mode": "replace（清空本机再照备份接回来，生产入口 importBackup）",
                "heldExportTextBytes": text.utf8.count,
                "timing": importTiming.dictionary,
                "runs": importRuns.map(\.dictionary),
                "peakFootprintDeltaMBMax": ProcessMemory.Snapshot.round(
                    ProcessMemory.Snapshot.megabytes(importPeakFootprint)
                ),
                "peakResidentDeltaMBMax": ProcessMemory.Snapshot.round(
                    ProcessMemory.Snapshot.megabytes(importPeakResident)
                ),
                "peakFootprintMBMax": ProcessMemory.Snapshot.round(
                    ProcessMemory.Snapshot.megabytes(importPeakFootprintAbsolute)
                ),
                "verification": verification
            ],
            "methodology": [
                "clock": "DispatchTime.uptimeNanoseconds（单调时钟），只包住服务调用本身",
                "memory": "task_info(MACH_TASK_BASIC_INFO).resident_size + task_info(TASK_VM_INFO).phys_footprint；基线 = malloc_zone_pressure_relief 后取 3 次最小值；峰值 = 5ms 采样线程",
                "exportPath": "BackupService.exportWithWarnings()（含 mediaCandidates → BackupMediaPacker.pack → BackupCodec.encode → containsSecretField → 尺寸闸）",
                "importPath": "BackupService.importBackup(text:mode:.replace)（含 BackupCodec.decode 的自定义 JSON 解析 → BackupMediaRestorer 落盘 → 单事务写库）",
                "importMemoryNote": "导入测量时进程里同时压着上一次导出的整份备份文本（约 \(text.utf8.count / 1_048_576) MB）——真实导入也是「先读文件到内存」",
                "imageNote": "JPEG 由 CoreGraphics + ImageIO 真实编码；噪点图用固定线性同余序列（可重复），纯色图每张颜色不同",
                "notes": [
                    "导出第 1 次的基线里没有备份文本，第 2/3 次基线里压着上一份（约 \(text.utf8.count / 1_048_576) MB），所以看「峰值增量」时注意基线差异；峰值绝对值一并给出",
                    "导入每次都新建库与空附件目录，避免「同名文件已存在」影响落盘",
                    "macOS 上 `FileManager` / `Data` 的分配器与 iOS 不同，内存数字只能看量级与相对关系"
                ]
            ]
        ]
        BenchmarkOutput.write(payload, name: "backup-media")
    }

    // MARK: - 图片生成（真实 JPEG）

    private struct GeneratedImage {
        var fileName: String
        var url: URL
        var bytes: Int
        var isNoise: Bool
    }

    private func generateImages() throws -> (images: [GeneratedImage], milliseconds: Double) {
        var images: [GeneratedImage] = []
        images.reserveCapacity(Self.imageCount)
        let start = BenchmarkClock.nowNanos()
        for index in 0..<Self.imageCount {
            let isNoise = index < Self.noiseImageCount
            let fileName = String(format: "chatImage-bench-%03d.jpg", index)
            let url = mediaDirectory.appendingPathComponent(fileName)
            let data: Data
            if isNoise {
                data = try Self.makeJPEG(
                    width: Self.noiseWidth, height: Self.noiseHeight,
                    fill: .noise,
                    quality: 0.5
                )
            } else {
                let hue = Double(index - Self.noiseImageCount) / Double(max(Self.solidImageCount, 1))
                data = try Self.makeJPEG(
                    width: Self.solidWidth, height: Self.solidHeight,
                    fill: .solid(red: hue, green: 0.35, blue: 1.0 - hue),
                    quality: 0.8
                )
            }
            try data.write(to: url)
            images.append(GeneratedImage(fileName: fileName, url: url, bytes: data.count, isNoise: isNoise))
        }
        return (images, BenchmarkClock.milliseconds(since: start))
    }

    private enum Fill {
        case solid(red: Double, green: Double, blue: Double)
        case noise
    }

    private static func makeJPEG(
        width: Int,
        height: Int,
        fill: Fill,
        quality: Double
    ) throws -> Data {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else {
            throw BenchmarkImageError.contextCreationFailed(width: width, height: height)
        }
        switch fill {
        case .solid(let red, let green, let blue):
            context.setFillColor(CGColor(red: red, green: green, blue: blue, alpha: 1))
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        case .noise:
            guard let raw = context.data else {
                throw BenchmarkImageError.contextCreationFailed(width: width, height: height)
            }
            // 用 `arc4random_buf` 直接灌满像素缓冲（C 实现，比在 Swift 里逐字节算快两个数量级）。
            // 噪点图不要求逐字节可重复：JPEG 体积对随机噪点非常稳定（±1%），够做基准。
            arc4random_buf(raw, width * height * 4)
        }
        guard let image = context.makeImage() else {
            throw BenchmarkImageError.imageCreationFailed
        }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            data, UTType.jpeg.identifier as CFString, 1, nil
        ) else {
            throw BenchmarkImageError.destinationCreationFailed
        }
        CGImageDestinationAddImage(
            destination, image,
            [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary
        )
        guard CGImageDestinationFinalize(destination) else {
            throw BenchmarkImageError.finalizeFailed
        }
        return data as Data
    }

    // MARK: - 数据构造

    private struct Seeded {
        var stores: DatabaseStores
        var seedMilliseconds: Double
    }

    private func seedDatabase(images: [GeneratedImage]) throws -> Seeded {
        let stores = try DatabaseStores.open(path: databasePath)
        let seedStart = BenchmarkClock.nowNanos()

        try stores.apiConfigs.upsert(ApiConfigEntity(
            id: Self.configId,
            name: "基准配置",
            baseUrl: "https://api.example.com",
            model: "deepseek-chat"
        ))
        var persona = TestData.persona(id: Self.personaId)
        persona.defaultApiConfigId = Self.configId
        try stores.personas.upsert(persona)
        var session = TestData.session(id: Self.sessionId, personaId: Self.personaId)
        session.apiConfigId = Self.configId
        try stores.sessions.upsert(session)

        var messages: [MessageEntity] = []
        messages.reserveCapacity(images.count)
        for (index, image) in images.enumerated() {
            let attachment = MessageAttachment(
                uri: image.url.absoluteString,
                mime: "image/jpeg",
                width: image.isNoise ? Self.noiseWidth : Self.solidWidth,
                height: image.isNoise ? Self.noiseHeight : Self.solidHeight
            )
            messages.append(MessageEntity(
                id: "bench-msg-\(index)",
                sessionId: Self.sessionId,
                personaId: Self.personaId,
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index) 句，带一张图。",
                createdAt: Self.baseTimestamp + Int64(index) * 1_000,
                updatedAt: Self.baseTimestamp + Int64(index) * 1_000,
                status: .complete,
                attachmentsJson: AttachmentsCodec.encode([attachment])
            ))
        }
        try stores.messages.upsert(messages)
        let seedMilliseconds = BenchmarkClock.milliseconds(since: seedStart)
        XCTAssertEqual(try stores.messages.count(), images.count)
        return Seeded(stores: stores, seedMilliseconds: seedMilliseconds)
    }

    // MARK: - 测量

    private struct OperationRun {
        var milliseconds: Double
        var memory: ProcessMemory.Delta
        var extra: [String: Any]

        var dictionary: [String: Any] {
            var out: [String: Any] = [
                "milliseconds": BenchmarkStats.round(milliseconds),
                "memory": memory.dictionary
            ]
            for (key, value) in extra { out[key] = value }
            return out
        }
    }

    /// 基线 → 5ms 采样 → 计时执行 → 峰值 → 稳定后的「之后」。
    ///
    /// 时间只包住 `body` 本身，不含基线等待与采样线程启停。
    private func measured<T>(_ body: () throws -> T) rethrows -> (
        result: T, milliseconds: Double, memory: ProcessMemory.Delta
    ) {
        let baseline = ProcessMemory.settled()
        let sampler = PeakMemorySampler(baseline: baseline)
        sampler.start()
        let start = BenchmarkClock.nowNanos()
        do {
            let result = try body()
            let milliseconds = BenchmarkClock.milliseconds(since: start)
            let peak = sampler.stop().peak
            let after = ProcessMemory.settled(samples: 2, pauseMicroseconds: 15_000)
            let memory = ProcessMemory.Delta(baseline: baseline, peak: peak, after: after)
            return (result, milliseconds, memory)
        } catch {
            _ = sampler.stop()
            throw error
        }
    }

    private func makeService(stores: DatabaseStores, mediaDirectory: URL) -> BackupService {
        let fileManager = FileManager.default
        return BackupService(
            stores: stores,
            settings: InMemorySettings(AppSettings()),
            appVersion: "0.12.0-ios-bench",
            avatarFileExists: { path in fileManager.fileExists(atPath: path) },
            mediaDirectory: mediaDirectory,
            now: { TestData.now }
        )
    }

    private func iso8601Now() -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: Date())
    }
}

enum BenchmarkImageError: Error, CustomStringConvertible {
    case contextCreationFailed(width: Int, height: Int)
    case imageCreationFailed
    case destinationCreationFailed
    case finalizeFailed

    var description: String {
        switch self {
        case .contextCreationFailed(let width, let height):
            return "创建 \(width)x\(height) 的 CGContext 失败"
        case .imageCreationFailed:
            return "CGContext 转 CGImage 失败"
        case .destinationCreationFailed:
            return "创建 JPEG 编码目标失败"
        case .finalizeFailed:
            return "JPEG 编码收尾失败"
        }
    }
}
