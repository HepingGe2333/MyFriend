import Foundation
import XCTest
@testable import YapKit

/// 备份导出/导入的加固（0.11.1 审计 P1-4 / P2-4 + 安全审计 P2-4 / P2-12）。
///
/// 三条：
/// 1. 导出与导入的尺寸口径一致（导不回来的备份不允许写出来）；
/// 2. 导入的头像路径只认 attachments/ 下的真实文件（`../../yap.db` 不能落库）；
/// 3. 「读文件 + JSON 预解析」在后台线程（`BackupImportLoader`）+ 有界读取。
final class BackupHardeningTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var root: URL!
    private var attachments: URL!
    private var fileManager = FileManager.default

    override func setUpWithError() throws {
        try super.setUpWithError()
        db = try TempDatabase()
        settings = InMemorySettings(AppSettings(activePersonaId: "persona-1"))
        root = fileManager.temporaryDirectory
            .appendingPathComponent("mf-backup-hardening-\(UUID().uuidString)", isDirectory: true)
        attachments = root.appendingPathComponent("attachments", isDirectory: true)
        try fileManager.createDirectory(at: attachments, withIntermediateDirectories: true)
        try seed()
    }

    override func tearDownWithError() throws {
        if let root { try? fileManager.removeItem(at: root) }
        db.cleanUp()
        db = nil
        try super.tearDownWithError()
    }

    private func seed() throws {
        try db.stores.personas.upsert([TestData.persona(id: "persona-1")])
        try db.stores.sessions.upsert(TestData.session(id: "session-1", personaId: "persona-1"))
        try db.stores.messages.upsert([
            TestData.message(id: "msg-1", role: .user, content: "在吗", createdAt: TestData.now - 1000),
            TestData.message(id: "msg-2", role: .assistant, content: "在", createdAt: TestData.now)
        ])
    }

    private func makeService(
        exportLimitBytes: Int = BackupService.maxExportBytes,
        mediaByteLimit: Int = BackupService.maxMediaBytes,
        mediaFileByteLimit: Int = BackupService.maxMediaFileBytes
    ) -> BackupService {
        BackupService(
            stores: db.stores,
            settings: settings,
            appVersion: "0.11.1-ios",
            avatarFileExists: { [fileManager] path in fileManager.fileExists(atPath: path) },
            mediaDirectory: attachments,
            exportLimitBytes: exportLimitBytes,
            mediaByteLimit: mediaByteLimit,
            mediaFileByteLimit: mediaFileByteLimit,
            now: { TestData.now }
        )
    }

    // MARK: - 1. 导出上限（P1-4）

    func testExportLimitDefaultsToImportLimit() {
        XCTAssertEqual(BackupService.maxExportBytes, BackupService.maxImportBytes)
        // v3 起图片二进制也进备份（v4 只是加了 provider 字段，媒体闸不变）：128 MiB 原始字节 base64 之后约 171 MiB，闸抬到 256 MiB。
        XCTAssertEqual(BackupService.maxExportBytes, 256 * 1024 * 1024)
        XCTAssertEqual(BackupService.maxMediaBytes, 128 * 1024 * 1024)
        XCTAssertEqual(BackupService.maxMediaFileBytes, 8 * 1024 * 1024)
    }

    /// 超限时明确报错、不返回半份 JSON（调用点因此不会写出文件）。
    func testExportRejectsBackupLargerThanLimit() throws {
        let service = makeService(exportLimitBytes: 256)
        let full = try makeService().export()
        XCTAssertGreaterThan(full.utf8.count, 256, "夹具要真的比上限大，否则用例测不到东西")
        do {
            _ = try service.export()
            XCTFail("超过导出上限必须抛错")
        } catch let error as BackupImportError {
            XCTAssertTrue(error.message.contains("没有写出文件"), "错误要说清楚没写文件：\(error.message)")
            XCTAssertTrue(error.message.contains("256") || error.message.contains("bytes"), error.message)
        }
    }

    func testExportUnderLimitStillWorks() throws {
        let text = try makeService(exportLimitBytes: 1 << 20).export()
        XCTAssertTrue(text.contains("\"formatVersion\":\(BackupCodec.formatVersion)"))
        XCTAssertTrue(text.contains("在吗"))
    }

    /// 导出不再把设备绝对路径写进备份（安全审计 P2-12）：附件里的 `file:///var/mobile/...` 会被置空。
    func testExportDoesNotCarryAttachmentAbsolutePaths() throws {
        try db.stores.messages.upsert([
            TestData.message(id: "msg-img", role: .user, content: "看图",
                             createdAt: TestData.now - 500)
        ])
        let withPath = MessageEntity(
            id: "msg-img",
            sessionId: "session-1",
            personaId: "persona-1",
            role: .user,
            content: "看图",
            createdAt: TestData.now - 500,
            updatedAt: TestData.now - 500,
            status: .complete,
            attachmentsJson: "[{\"uri\":\"file:///var/mobile/Containers/book.jpg\",\"mime\":\"image/jpeg\"}]"
        )
        try db.stores.messages.upsert(withPath)

        let text = try makeService().export()
        XCTAssertFalse(text.contains("file:///var/mobile"), "设备沙盒路径不该进备份")
        XCTAssertFalse(text.contains("book.jpg"))
    }

    // MARK: - 2. 导入的头像路径（安全审计 P2-4）

    private func bundleWithAvatar(_ avatarUri: String) throws -> BackupBundle {
        let decoded = try XCTUnwrap(BackupCodec.decode(try makeService().export()).bundle)
        var bundle = decoded
        bundle.personas = bundle.personas.map { persona in
            var copy = persona
            copy.avatarUri = avatarUri
            return copy
        }
        return bundle
    }

    func testImportRejectsAvatarTraversalAndClearsIt() throws {
        // 目录外真实存在的文件：旧实现会让它落库。
        try Data([0xFF, 0xD8]).write(to: root.appendingPathComponent("yap.db"))
        let bundle = try bundleWithAvatar("../../yap.db")
        let summary = try makeService().importBundle(bundle, mode: .merge)
        let stored = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        XCTAssertNil(stored.avatarUri, "穿越路径不能写成头像引用")
        XCTAssertTrue(summary.warnings.contains(where: { $0.contains("头像") }))
    }

    func testImportNormalizesOwnAvatarToCurrentContainer() throws {
        try Data([0xFF, 0xD8]).write(to: attachments.appendingPathComponent("avatar-1.jpg"))
        let oldPath = "file:///var/mobile/Containers/Data/Application/OLD/attachments/avatar-1.jpg"
        let bundle = try bundleWithAvatar(oldPath)
        _ = try makeService().importBundle(bundle, mode: .merge)
        let stored = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        let expected = attachments.appendingPathComponent("avatar-1.jpg").standardizedFileURL.absoluteString
        XCTAssertEqual(stored.avatarUri, expected, "容器 UUID 变了，但同名头像文件还在 → 归一成当前路径")
    }

    func testImportClearsAvatarWhenFileMissing() throws {
        let bundle = try bundleWithAvatar("avatar-missing.jpg")
        _ = try makeService().importBundle(bundle, mode: .merge)
        let stored = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        XCTAssertNil(stored.avatarUri)
    }

    // MARK: - 3. 读 + 预解析（P2-4）

    func testBoundedReadRejectsFileOverLimit() throws {
        let url = root.appendingPathComponent("big-backup.json")
        try Data(repeating: UInt8(ascii: "a"), count: 4096).write(to: url)
        do {
            _ = try BackupService.readBoundedText(at: url, maxBytes: 1024)
            XCTFail("超过上限必须拒绝")
        } catch let error as BackupImportError {
            XCTAssertEqual(error.message, BackupService.tooLargeMessage)
        }
    }

    func testImportLoaderReadsAndPreviewsOffMainThread() async throws {
        let url = root.appendingPathComponent("backup.json")
        let text = try makeService().export()
        try Data(text.utf8).write(to: url)
        let result = await BackupImportLoader.load(at: url)
        switch result {
        case .failure(let error):
            XCTFail("应当读得出来：\(error)")
        case .success(let preparation):
            XCTAssertEqual(preparation.text, text)
            XCTAssertTrue(preparation.decoded.isSuccess)
            XCTAssertEqual(preparation.decoded.bundle?.messages.count, 2)
        }
    }

    func testImportLoaderSurfacesUnreadableFile() async throws {
        let url = root.appendingPathComponent("missing.json")
        let result = await BackupImportLoader.load(at: url)
        guard case .failure(let error) = result else {
            return XCTFail("不存在的文件应当失败")
        }
        XCTAssertFalse(error.localizedDescription.isEmpty)
    }

    // MARK: - 4. v3 媒体：坏数据只跳过、绝不写坏路径（导入侧外部输入）

    private func smallService(
        mediaByteLimit: Int = BackupService.maxMediaBytes,
        mediaFileByteLimit: Int = BackupService.maxMediaFileBytes
    ) -> BackupService {
        makeService(mediaByteLimit: mediaByteLimit, mediaFileByteLimit: mediaFileByteLimit)
    }

    /// 坏 base64 / 不支持的 mime / 文件名不安全 / 字节数与声明不符：全部跳过并计数，
    /// 导入照样成功（一份备份里有一张坏图，不该让整次「接他回来」失败）。
    func testImportSkipsBrokenMediaAndStillSucceeds() throws {
        let bundle = BackupBundle(
            personas: [
                BackupPersona(id: "persona-1", name: "林岸", avatarUri: "avatar-bad.jpg"),
                BackupPersona(id: "persona-2", name: "陈屿", avatarUri: "avatar-mime.jpg")
            ],
            sessions: [BackupSession(id: "session-1", personaId: "persona-1", model: "deepseek-chat",
                                     title: "看图", createdAt: TestData.now, updatedAt: TestData.now)],
            messages: [BackupMessage(
                id: "msg-1", sessionId: "session-1", personaId: "persona-1", role: "USER",
                content: "看图", createdAt: TestData.now,
                attachmentsJson: #"[{"uri":"chatImage-bad.png","mime":"image/png"}]"#
            )],
            media: [
                // 解不出来的 base64。
                BackupMedia(id: "m1", kind: "PERSONA_AVATAR", personaId: "persona-1",
                            fileName: "avatar-bad.jpg", mime: "image/jpeg", bytes: 4, data: "!!!!"),
                // 不支持的 mime。
                BackupMedia(id: "m2", kind: "PERSONA_AVATAR", personaId: "persona-2",
                            fileName: "avatar-mime.jpg", mime: "application/pdf", bytes: 4,
                            data: Data([0x25, 0x50, 0x44, 0x46]).base64EncodedString()),
                // 声明的字节数与解出来的不一致（坏数据，按 Android 的口径算 invalid）。
                BackupMedia(id: "m3", kind: "CHAT_IMAGE", personaId: nil,
                            fileName: "chatImage-bad.png", mime: "image/png", bytes: 99,
                            data: Data([0x89, 0x50]).base64EncodedString())
            ]
        )
        // 让 chatImage-bad.png 变成「被引用」的条目（否则它会在落盘前就被过滤掉）。
        try db.stores.messages.upsert([
            TestData.message(id: "msg-1", role: .user, content: "看图", createdAt: TestData.now)
        ])

        let summary = try smallService().importBundle(bundle, mode: .replace)

        XCTAssertTrue(
            summary.warnings.contains { $0.contains("没接回来（文件损坏或格式不支持）") },
            "坏图要如实告诉用户：\(summary.warnings)"
        )
        let persona = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        XCTAssertNil(persona.avatarUri, "接不回来的头像要清空，别留一个永远加载不出来的地址")
        let message = try XCTUnwrap(try db.stores.messages.byId("msg-1"))
        XCTAssertNil(message.attachmentsJson, "没接回来的聊天图片不留坏引用")
        XCTAssertEqual(
            Set(try db.stores.personas.all().map(\.id)), ["persona-1", "persona-2"],
            "坏媒体不影响别的内容"
        )
        // 一张都没写下去（坏数据不该在磁盘上留下痕迹）。
        XCTAssertEqual(
            (try? fileManager.contentsOfDirectory(atPath: attachments.path))?.count ?? 0, 0
        )
    }

    /// 备份里没写 `avatarUri`、但 `media` 带着 `personaId`：按 personaId 兜底接回头像
    /// （与 Android 的 `avatarsByPersonaId` 同一条规则）。
    func testMediaAvatarIsRestoredByPersonaIdWhenAvatarUriIsMissing() throws {
        // 导入会重新压成 JPEG（长边 ≤512、0.85、白底），所以这里放一张**真图**；
        // 断言的是「接回来了、落在我们的目录里、还是一张能解开的图」，不是原字节相同。
        let payload = Data(base64Encoded: BackupSamples.sampleJPEGBase64)!
        let summary = try smallService().importBundle(
            BackupBundle(
                personas: [BackupPersona(id: "persona-1", name: "林岸", avatarUri: nil)],
                media: [BackupMedia(
                    id: "m1", kind: "PERSONA_AVATAR", personaId: "persona-1",
                    fileName: "avatar-by-id.jpg", mime: "image/jpeg", bytes: payload.count,
                    data: payload.base64EncodedString()
                )]
            ),
            mode: .replace
        )
        let stored = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        let uri = try XCTUnwrap(stored.avatarUri, "按 personaId 兜底也要把头像接回来")
        let url = try XCTUnwrap(URL(string: uri))
        XCTAssertTrue(AttachmentLocation.isWithin(attachments, url))
        let pixelSize = try XCTUnwrap(ImageThumbnailDecoder.pixelSize(url: url))
        XCTAssertEqual(pixelSize.width, 2)
        XCTAssertEqual(pixelSize.height, 2)
        XCTAssertFalse(
            summary.warnings.contains { $0.contains("头像文件找不到") },
            "接回来了就不该再说「找不到」：\(summary.warnings)"
        )
    }

    /// 不认识的 kind / 重复的同名条目：都只跳过并计数，不写文件、不崩。
    func testUnknownKindAndDuplicateMediaAreSkippedAndCounted() throws {
        let payload = Data(base64Encoded: BackupSamples.sampleJPEGBase64)!
        let encoded = payload.base64EncodedString()
        let summary = try smallService().importBundle(
            BackupBundle(media: [
                BackupMedia(id: "m1", kind: "SOMETHING_NEW", fileName: "avatar-new.jpg",
                            mime: "image/jpeg", bytes: payload.count, data: encoded),
                // 同 (kind, fileName) 出现两次：只认第一条，第二条算坏数据。
                BackupMedia(id: "m2", kind: "USER_AVATAR", fileName: "avatar-dup.jpg",
                            mime: "image/jpeg", bytes: payload.count, data: encoded),
                BackupMedia(id: "m3", kind: "USER_AVATAR", fileName: "avatar-dup.jpg",
                            mime: "image/jpeg", bytes: payload.count, data: encoded)
            ]),
            mode: .merge
        )
        XCTAssertTrue(
            summary.warnings.contains { $0.contains("没接回来（文件损坏或格式不支持）") },
            "\(summary.warnings)"
        )
        let names = (try? fileManager.contentsOfDirectory(atPath: attachments.path)) ?? []
        XCTAssertEqual(names.count, 1, "只该落下第一条 USER_AVATAR：\(names)")
        XCTAssertTrue(names[0].hasPrefix("avatar-"))
        XCTAssertTrue(names[0].contains("-"), "v12 起文件名带 SHA-256 前缀：\(names[0])")
        XCTAssertNotNil(ImageThumbnailDecoder.pixelSize(url: attachments.appendingPathComponent(names[0])))
        XCTAssertEqual(settings.load().myAvatarUri, attachments.appendingPathComponent(names[0]).absoluteString)
    }

    /// 文件名带 `../` 或绝对路径：**不能**用它拼路径，也**不能**写到目录外。
    func testRestorerRejectsTraversalFileNamesAndNeverWritesOutside() throws {
        let outside = root.appendingPathComponent("yap.db")
        try Data([0xFF, 0xD8]).write(to: outside)
        let payload = Data([0xFF, 0xD8, 0xFF]).base64EncodedString()

        let result = BackupMediaRestorer.restore(
            entries: [
                BackupMedia(id: "m1", kind: "PERSONA_AVATAR", fileName: "../../yap.db",
                            mime: "image/jpeg", bytes: 3, data: payload),
                BackupMedia(id: "m2", kind: "USER_AVATAR", fileName: "/etc/passwd",
                            mime: "image/jpeg", bytes: 3, data: payload),
                BackupMedia(id: "m3", kind: "CHAT_IMAGE", fileName: "a/b.png",
                            mime: "image/png", bytes: 3, data: payload)
            ],
            directory: attachments
        )
        XCTAssertEqual(result.invalidCount, 3, "三种不安全名字都要被拒绝")
        XCTAssertTrue(result.writtenFiles.isEmpty)
        XCTAssertTrue(result.avatarsByFileName.isEmpty)
        XCTAssertTrue(result.chatImagesByFileName.isEmpty)
        // 目录外那个文件原样还在，内容没被覆盖。
        XCTAssertEqual(try Data(contentsOf: outside), Data([0xFF, 0xD8]))
        XCTAssertEqual((try? fileManager.contentsOfDirectory(atPath: attachments.path))?.count ?? 0, 0)
    }

    /// 目录归属守卫：文件名里带 `../` / 绝对路径时，一个字节都不会落到目录外。
    func testMediaWriteGuardRefusesPathsOutsideTheDirectory() throws {
        let outside = root.appendingPathComponent("escaped.jpg")
        let payload = Data([0xFF, 0xD8, 0xFF])

        XCTAssertNil(
            BackupMediaTransfer.write(payload, as: "../escaped.jpg", in: attachments),
            "带 ../ 的文件名必须被拒绝"
        )
        XCTAssertNil(
            BackupMediaTransfer.write(payload, as: "..%2Fescaped.jpg", in: attachments),
            "百分号转义的取值也是一样的坏名字"
        )
        XCTAssertNil(
            BackupMediaTransfer.write(payload, as: "sub/escaped.jpg", in: attachments),
            "带目录的文件名必须被拒绝"
        )
        XCTAssertFalse(fileManager.fileExists(atPath: outside.path), "目录外不能出现文件")
        XCTAssertEqual((try? fileManager.contentsOfDirectory(atPath: attachments.path))?.count ?? 0, 0)

        // 正常名字照写（守卫不能把合法写入也挡掉）。
        let written = try XCTUnwrap(
            BackupMediaTransfer.write(
                payload, as: "avatar-00000000-0000-0000-0000-0000000000AA.jpg", in: attachments
            )
        )
        XCTAssertTrue(AttachmentLocation.isWithin(attachments, written))
        XCTAssertEqual(try Data(contentsOf: written), payload)
    }

    /// 单张超限：导出跳过并给一句「超出上限」的提醒，导入同样跳过并计数。
    func testMediaPerFileLimitSkipsAndWarns() throws {
        let big = attachments.appendingPathComponent("avatar-big.jpg")
        try Data(repeating: 0x41, count: 2_000).write(to: big)
        var persona = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        persona.avatarUri = big.absoluteString
        try db.stores.personas.upsert(persona)

        let service = smallService(mediaFileByteLimit: 1_000)
        let result = try service.exportWithWarnings()
        XCTAssertTrue(result.text.contains(#""media":[]"#), "超限的图不该写进 media")
        XCTAssertEqual(result.warnings.count, 1)
        XCTAssertTrue(result.warnings[0].contains("没有随备份带走（超出上限）"), result.warnings[0])

        // 导入侧：声明 2000 字节 > 单张上限 1000 → 跳过并计「超出上限」。
        let imported = try smallService(mediaFileByteLimit: 1_000).importBundle(
            BackupBundle(media: [BackupMedia(
                id: "m1", kind: "USER_AVATAR", fileName: "avatar-big.jpg", mime: "image/jpeg",
                bytes: 2_000, data: Data(repeating: 0x41, count: 2_000).base64EncodedString()
            )]),
            mode: .merge
        )
        XCTAssertTrue(
            imported.warnings.contains { $0.contains("没有接回来（超出上限）") },
            "\(imported.warnings)"
        )
    }

    /// 总量超限：导出停止收集、把剩下的都算进提醒；导入按累计预算跳过。
    func testMediaTotalLimitStopsCollecting() throws {
        try db.stores.personas.upsert([TestData.persona(id: "persona-2", name: "陈屿", isDefault: false)])
        try Data([0xFF, 0xD8, 0x01]).write(to: attachments.appendingPathComponent("avatar-a.jpg"))
        try Data([0xFF, 0xD8, 0x02]).write(to: attachments.appendingPathComponent("avatar-b.jpg"))
        var first = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        first.avatarUri = attachments.appendingPathComponent("avatar-a.jpg").absoluteString
        try db.stores.personas.upsert(first)
        var second = try XCTUnwrap(try db.stores.personas.byId("persona-2"))
        second.avatarUri = attachments.appendingPathComponent("avatar-b.jpg").absoluteString
        try db.stores.personas.upsert(second)

        // 预算只够一张（3 字节）：第二张开始「停止收集」并被计数。
        let result = try smallService(mediaByteLimit: 4).exportWithWarnings()
        let bundle = try XCTUnwrap(BackupCodec.decode(result.text).bundle)
        XCTAssertEqual(bundle.media.count, 1)
        XCTAssertEqual(result.warnings.count, 1)
        XCTAssertTrue(result.warnings[0].contains("超出上限"), result.warnings[0])
        let withMedia = bundle.personas.filter { $0.avatarUri != nil }
        XCTAssertEqual(withMedia.count, 1, "只有真的带走了字节的头像才写文件名")
    }
}
