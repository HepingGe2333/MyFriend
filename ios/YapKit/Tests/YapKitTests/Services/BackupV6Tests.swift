import Foundation
import XCTest
@testable import YapKit

/// 备份 v6（0.13）：表情 / 语音 / 视频 / 日记便条也要跟着走。
///
/// 这一层测的是**服务层的编排**（收集哪些文件、引用怎么重写、导入怎么写回库），
/// 纯格式的键集合与跨端互通在 `BackupCodecTests` / `BackupInteropTests` 里。
///
/// 三个目录（attachments / stickers / audio / video）都是每个用例各自的临时目录：
/// 绝不往 `MediaStorage.shared` / `StickerStorage.shared` 里写测试数据。
final class BackupV6Tests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var service: BackupService!
    private var attachments: URL!
    private var stickers: URL!
    private var audio: URL!
    private var video: URL!
    private let fileManager = FileManager.default

    /// 1×1 的 GIF（42 字节）：表情必须是真 GIF。
    private static let gifBytes = Data(
        base64Encoded: "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
    )!
    /// 语音/视频样本字节：备份只校验 base64 → 原始字节数一致，不要求能播放。
    private static let audioBytes = Data("mock-audio-bytes".utf8)
    private static let videoBytes = Data("mock-video-bytes".utf8)

    override func setUpWithError() throws {
        try super.setUpWithError()
        db = try TempDatabase()
        settings = InMemorySettings(AppSettings(activePersonaId: "persona-1"))
        let base = fileManager.temporaryDirectory
            .appendingPathComponent("mf-backup-v6-\(UUID().uuidString)", isDirectory: true)
        attachments = base.appendingPathComponent("attachments", isDirectory: true)
        stickers = base.appendingPathComponent("stickers", isDirectory: true)
        audio = base.appendingPathComponent("audio", isDirectory: true)
        video = base.appendingPathComponent("video", isDirectory: true)
        for directory in [attachments, stickers, audio, video].compactMap({ $0 }) {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        }
        service = BackupService(
            stores: db.stores,
            settings: settings,
            appVersion: "0.13.0-ios",
            avatarFileExists: { [fileManager] path in fileManager.fileExists(atPath: path) },
            mediaDirectory: attachments,
            stickerDirectory: stickers,
            audioDirectory: audio,
            videoDirectory: video,
            now: { TestData.now }
        )
    }

    override func tearDownWithError() throws {
        service = nil
        settings = nil
        db.cleanUp()
        db = nil
        if let attachments { try? fileManager.removeItem(at: attachments.deletingLastPathComponent()) }
        attachments = nil
        stickers = nil
        audio = nil
        video = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    /// 造一份「有表情、有语音、有视频、有便条」的本机数据，并真的把文件落盘。
    @discardableResult
    private func seedV6() throws -> StickerEntity {
        try db.stores.personas.upsert([TestData.persona()])
        try db.stores.sessions.upsert([TestData.session()])

        // 表情：`stickers.file` 是绝对路径（与生产一致），文件名 `<id>.gif`。
        let stickerId = "sticker-1"
        let stickerURL = stickers.appendingPathComponent("\(stickerId).gif", isDirectory: false)
        try Self.gifBytes.write(to: stickerURL)
        let sticker = StickerEntity(
            id: stickerId, ownerUserId: nil, personaId: "persona-1",
            file: stickerURL.path, mime: "image/gif", width: 1, height: 1,
            frames: 1, bytes: Self.gifBytes.count, source: "AI", createdBy: "PERSONA",
            createdAt: TestData.now - 5_000
        )
        try db.stores.stickers.upsert([sticker])

        // 语音 / 视频：落在各自目录里，附件 `kind` + `durationMs` 齐全。
        let audioURL = audio.appendingPathComponent("audio-abc-1.m4a", isDirectory: false)
        try Self.audioBytes.write(to: audioURL)
        let videoURL = video.appendingPathComponent("video-abc-1.mp4", isDirectory: false)
        try Self.videoBytes.write(to: videoURL)
        let audioAttachment = MessageAttachment.audio(uri: audioURL.absoluteString, durationMs: 7_000)
        let videoAttachment = MessageAttachment.video(uri: videoURL.absoluteString, durationMs: 12_000)
        var message = TestData.message(
            id: "msg-audio", role: .assistant, content: "", createdAt: TestData.now - 2_000
        )
        message.attachmentsJson = AttachmentsCodec.encode([audioAttachment, videoAttachment])
        try db.stores.messages.upsert([message])

        // 日记便条：一条待提醒、一条已经提醒过。
        try db.stores.diaryNotes.upsert([
            DiaryNoteEntity(
                id: "note-1", personaId: "persona-1", content: "明天记得问他到家没",
                createdAt: TestData.now - 1_000, remindAt: TestData.now + 3_600_000
            ),
            DiaryNoteEntity(
                id: "note-2", personaId: "persona-1", content: "他说过不吃香菜",
                createdAt: TestData.now - 86_400_000,
                remindAt: TestData.now - 82_800_000, remindedAt: TestData.now - 82_700_000
            )
        ])
        return sticker
    }

    private func fileNames(in directory: URL) -> [String] {
        ((try? fileManager.contentsOfDirectory(atPath: directory.path)) ?? []).sorted()
    }

    // MARK: - 导出

    /// 导出：三类新媒体的二进制进 `media`，引用一律降成**裸文件名**。
    func testExportPacksStickersAudioAndVideoWithBareNames() throws {
        let sticker = try seedV6()
        let text = try service.export()
        let bundle = try XCTUnwrap(service.preview(fileText: text).bundle)

        let kinds = bundle.media.map(\.kind)
        XCTAssertEqual(kinds.filter { $0 == "STICKER" }.count, 1)
        XCTAssertEqual(kinds.filter { $0 == "AUDIO" }.count, 1)
        XCTAssertEqual(kinds.filter { $0 == "VIDEO" }.count, 1)

        // 表情行：`file` 是裸文件名（绝不把设备绝对路径带出去），并且和 media 对得上。
        let exportedSticker = try XCTUnwrap(bundle.stickers.first { $0.id == sticker.id })
        let stickerName = try XCTUnwrap(exportedSticker.file)
        XCTAssertFalse(stickerName.contains("/"), "表情的 file 必须是裸文件名：\(stickerName)")
        XCTAssertEqual(stickerName, "\(sticker.id).gif")
        XCTAssertTrue(bundle.media.contains { $0.kind == "STICKER" && $0.fileName == stickerName })

        // 消息附件：语音/视频的 uri 换成裸文件名，`kind` 与时长一个都不丢。
        let exportedMessage = try XCTUnwrap(bundle.messages.first { $0.id == "msg-audio" })
        let attachments = AttachmentsCodec.decode(exportedMessage.attachmentsJson)
        XCTAssertEqual(attachments.count, 2)
        let audioAttachment = try XCTUnwrap(attachments.first { $0.isAudio })
        XCTAssertEqual(audioAttachment.uri, "audio-abc-1.m4a")
        XCTAssertEqual(audioAttachment.durationMs, 7_000)
        XCTAssertEqual(audioAttachment.normalizedKind, MessageAttachment.audioKind)
        let videoAttachment = try XCTUnwrap(attachments.first { $0.isVideo })
        XCTAssertEqual(videoAttachment.uri, "video-abc-1.mp4")
        XCTAssertEqual(videoAttachment.durationMs, 12_000)

        // 日记便条整行带走（含提醒与「已经提醒过」的标记）。
        XCTAssertEqual(bundle.diaryNotes.count, 2)
        let note1 = try XCTUnwrap(bundle.diaryNotes.first { $0.id == "note-1" })
        XCTAssertEqual(note1.remindAt, TestData.now + 3_600_000)
        XCTAssertNil(note1.remindedAt)
        let note2 = try XCTUnwrap(bundle.diaryNotes.first { $0.id == "note-2" })
        XCTAssertEqual(note2.remindedAt, TestData.now - 82_700_000, "已经提醒过的标记也要带走")
    }

    /// 换成另一台「空的」手机：导入之后文件在各自目录里、库里引用指向新文件。
    func testImportRestoresFilesAndRowsOnACleanDevice() throws {
        _ = try seedV6()
        let text = try service.export()

        // 模拟换机：清库、清目录（与用户在新手机上的状态一致）。
        try db.stores.database.inTransaction {
            try db.stores.stickers.deleteAll()
            try db.stores.diaryNotes.deleteAll()
            try db.stores.messages.deleteAll()
            try db.stores.sessions.deleteAll()
            try db.stores.personas.deleteAll()
        }
        try fileManager.removeItem(at: attachments)
        try fileManager.removeItem(at: stickers)
        try fileManager.removeItem(at: audio)
        try fileManager.removeItem(at: video)

        let summary = try service.importBackup(text: text, mode: .replace)
        XCTAssertEqual(summary.stickers, 1)
        XCTAssertEqual(summary.diaryNotes, 2)

        // 表情：文件真的在表情目录里，且行指向它。
        let restoredSticker = try XCTUnwrap(try db.stores.stickers.allStickers().first)
        XCTAssertTrue(restoredSticker.file.hasPrefix(stickers.path), "表情文件应落在表情目录：\(restoredSticker.file)")
        XCTAssertTrue(fileManager.fileExists(atPath: restoredSticker.file))
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: restoredSticker.file)), Self.gifBytes)

        // 语音 / 视频：各自目录、字节一致。
        let message = try XCTUnwrap(try db.stores.messages.byId("msg-audio"))
        let attachments = AttachmentsCodec.decode(message.attachmentsJson)
        XCTAssertEqual(attachments.count, 2)
        let audioAttachment = try XCTUnwrap(attachments.first { $0.isAudio })
        XCTAssertTrue(audioAttachment.uri.contains("/audio/"), "语音应落在 audio 目录：\(audioAttachment.uri)")
        XCTAssertEqual(try Data(contentsOf: URL(string: audioAttachment.uri)!), Self.audioBytes)
        XCTAssertEqual(audioAttachment.durationMs, 7_000, "时长必须还在（气泡上要显示 0:07）")
        let videoAttachment = try XCTUnwrap(attachments.first { $0.isVideo })
        XCTAssertTrue(videoAttachment.uri.contains("/video/"), "视频应落在 video 目录：\(videoAttachment.uri)")
        XCTAssertEqual(try Data(contentsOf: URL(string: videoAttachment.uri)!), Self.videoBytes)

        // 日记便条：内容、提醒时间、已提醒标记都在。
        let notes = try db.stores.diaryNotes.fetch(where: nil, [], orderBy: "createdAt DESC")
        XCTAssertEqual(notes.count, 2)
        let note1 = try XCTUnwrap(notes.first { $0.id == "note-1" })
        XCTAssertEqual(note1.content, "明天记得问他到家没")
        XCTAssertEqual(note1.remindAt, TestData.now + 3_600_000)
        XCTAssertNil(note1.remindedAt)
        XCTAssertEqual(notes.first { $0.id == "note-2" }?.remindedAt, TestData.now - 82_700_000)
    }

    /// 导入两次同一份备份：媒体文件按内容哈希复用，不会每次翻倍占空间。
    func testImportingTwiceReusesTheSameMediaFiles() throws {
        _ = try seedV6()
        let text = try service.export()
        _ = try service.importBundle(try XCTUnwrap(service.preview(fileText: text).bundle), mode: .merge)
        let firstCounts = (
            fileNames(in: stickers).count,
            fileNames(in: audio).count,
            fileNames(in: video).count
        )
        _ = try service.importBundle(try XCTUnwrap(service.preview(fileText: text).bundle), mode: .merge)
        XCTAssertEqual(fileNames(in: stickers).count, firstCounts.0, "表情文件不该翻倍")
        XCTAssertEqual(fileNames(in: audio).count, firstCounts.1, "语音文件不该翻倍")
        XCTAssertEqual(fileNames(in: video).count, firstCounts.2, "视频文件不该翻倍")
        XCTAssertEqual(try db.stores.stickers.allStickers().count, 1, "同一 id 的表情仍然只有一条")
    }

    /// 超限的媒体跳过并计数（单张 / 总量），导入照样成功。
    func testOverLimitMediaIsSkippedAndCounted() throws {
        // 用一份很小的单张预算重建服务：语音 16 字节能过，视频也 16 字节能过；
        // 这里把预算压到 10 字节，两段都超限。
        service = BackupService(
            stores: db.stores,
            settings: settings,
            appVersion: "0.13.0-ios",
            avatarFileExists: { [fileManager] path in fileManager.fileExists(atPath: path) },
            mediaDirectory: attachments,
            mediaByteLimit: 1024,
            mediaFileByteLimit: 10,
            stickerDirectory: stickers,
            audioDirectory: audio,
            videoDirectory: video,
            now: { TestData.now }
        )
        _ = try seedV6()
        let result = try service.exportWithWarnings()
        let bundle = try XCTUnwrap(service.preview(fileText: result.text).bundle)
        XCTAssertTrue(bundle.media.isEmpty, "全部超出单张上限：\(bundle.media.map(\.fileName))")
        XCTAssertEqual(bundle.messages.first { $0.id == "msg-audio" }?.attachmentsJson, nil,
                       "文件没带走时不该留下指向不存在媒体的引用")
        XCTAssertTrue(
            result.warnings.contains { $0.contains("没有随备份带走") },
            "超限必须上屏：\(result.warnings)"
        )
    }

    // MARK: - 跨端：读 Android 导出的 v6

    /// Android 的 v6 黄金样本：iOS 要能**真的接回来**（不是只解出 JSON）。
    ///
    /// 两端并行实现，样本可能晚到：缺文件就跳过（不是静默通过）。
    func testImportsAndroidV6FixtureWhenPresent() throws {
        let directory = try XCTUnwrap(
            RepoLayout.backupFixtureDirectory(),
            "找不到 fixtures/backup（swift test 的工作目录应在仓库内）"
        )
        let url = directory.appendingPathComponent("android-export-v6.json")
        guard fileManager.fileExists(atPath: url.path) else {
            throw XCTSkip("android-export-v6.json 还不存在（Android 端尚未生成 v6 黄金样本）")
        }
        let text = try String(contentsOf: url, encoding: .utf8)
        let summary = try service.importBackup(text: text, mode: .replace)

        // 没有「整份读不懂」这类致命问题：警告最多是「版本差异」「有几样没接回来」。
        XCTAssertFalse(
            summary.warnings.contains { $0.contains("读不出来") },
            "v6 样本不该读失败：\(summary.warnings)"
        )
        // 表情与日记便条都接回来了（Android 的样本里各有 1 条）。
        XCTAssertEqual(summary.stickers, 1, "Android v6 样本里的表情要接回来：\(summary.warnings)")
        XCTAssertEqual(summary.diaryNotes, 1, "Android v6 样本里的日记要接回来：\(summary.warnings)")
        let sticker = try XCTUnwrap(try db.stores.stickers.allStickers().first)
        XCTAssertTrue(fileManager.fileExists(atPath: sticker.file),
                      "表情文件要真的落在本机：\(sticker.file)")
        XCTAssertFalse(sticker.isServerSticker)
        let notes = try db.stores.diaryNotes.fetch(where: nil, [], orderBy: "createdAt DESC")
        XCTAssertEqual(notes.count, 1)
        XCTAssertFalse(notes[0].content.isEmpty)

        // 语音/视频也在：它们必须落到各自目录（不是当成坏图片跳过）。
        let message = try db.stores.messages.all().first {
            !AttachmentsCodec.decode($0.attachmentsJson).isEmpty
        }
        if let message {
            let attachments = AttachmentsCodec.decode(message.attachmentsJson)
            for attachment in attachments {
                XCTAssertTrue(
                    attachment.uri.hasPrefix("file://"),
                    "接回来的附件必须指向本机文件：\(attachment.uri)"
                )
            }
        }
    }

    /// v5 备份（没有 stickers / diaryNotes / 新媒体）：读得进、导得进，且不会凭空造数据。
    func testV5BackupImportsWithoutInventingStickersOrNotes() throws {
        let v5 = """
        {"format":"myfriend.backup","formatVersion":5,"appVersion":"0.12.0-ios","exportedAt":1,
         "apiKeysOmitted":true,
         "personas":[{"id":"p-old","name":"旧人","prompt":"你叫旧人。"}],
         "memories":[],"sessions":[],"messages":[],"media":[],"presets":[],
         "proactiveRules":[],"agentIntents":[],"memoryConsolidations":[],"apiConfigs":[]}
        """
        let summary = try service.importBackup(text: v5, mode: .merge)
        XCTAssertEqual(summary.stickers, 0)
        XCTAssertEqual(summary.diaryNotes, 0)
        XCTAssertTrue(try db.stores.stickers.allStickers().isEmpty)
        XCTAssertEqual(
            try db.stores.diaryNotes.count(where: nil, []),
            0
        )
        XCTAssertTrue(summary.warnings.contains { $0.contains("v5") }, "版本差异要说明：\(summary.warnings)")
    }
}
