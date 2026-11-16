import Foundation
import XCTest
@testable import YapKit

/// 首次全量同步（bootstrap，0.12.1）：四象限 / 幂等 / 分批 / 消息时间与已读 / 重试 / 增量回归。
///
/// 全部用真 SQLite（临时文件）+ 假 HTTP（`ServerRouteStub`）：
/// 「人格 id 折算」「消息按 (createdAt,id) 升序落块」「游标与数据同一事务」这几件事
/// 只有在真库上跑才看得出来。
final class ServerBootstrapTests: XCTestCase {

    private var temp: TempDatabase!
    private var server: BootstrapFakeServer!
    private var settings: SettingsStore!
    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerRouteStub.reset()
        temp = try TempDatabase()
        server = BootstrapFakeServer()
        server.registerRoutes()
        suiteName = "server-bootstrap-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        settings = SettingsStore(defaults: defaults)
    }

    override func tearDownWithError() throws {
        ServerRouteStub.reset()
        temp?.cleanUp()
        temp = nil
        if !suiteName.isEmpty { defaults?.removePersistentDomain(forName: suiteName) }
        defaults = nil
        settings = nil
        server = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private var baseURL: URL { URL(string: "https://server.example.com")! }

    private func client() -> ServerAPIClient {
        ServerAPIClient(
            config: ServerConfig(baseURL: baseURL, token: "secret-token"),
            session: ServerRouteStub.makeSession()
        )
    }

    private func makeService(
        retry: ServerSyncService.RetryPolicy = .init(maxAttempts: 1, baseDelaySeconds: 0),
        avatarUploader: ServerUserAvatarUploader? = nil,
        progress: (@Sendable (ServerBootstrapProgress) -> Void)? = nil,
        memories: MemoryRepository? = nil
    ) -> ServerBootstrapService {
        ServerBootstrapService(
            client: client(),
            stores: temp.stores,
            state: nil,
            settings: settings,
            memories: memories,
            avatarUploader: avatarUploader,
            retry: retry,
            now: { 1_800_000_000_000 },
            log: { _ in },
            sleeper: { _ in },
            progress: progress
        )
    }

    /// 一批本地数据：一个带会话的人格 + `messageCount` 条消息。
    private func seedLocal(
        personaId: String,
        personaName: String = "林深",
        personaDescription: String = "慢热",
        sessionId: String,
        messageCount: Int = 0,
        startAt: Int64 = 1_000
    ) throws {
        try temp.stores.personas.upsert(PersonaEntity(
            id: personaId,
            name: personaName,
            description: personaDescription,
            createdAt: 1,
            updatedAt: 2
        ))
        try temp.stores.sessions.upsert(ChatSessionEntity(
            id: sessionId,
            personaId: personaId,
            title: "夜里散步",
            createdAt: 3,
            updatedAt: 4
        ))
        for index in 0..<messageCount {
            try temp.stores.messages.upsert(MessageEntity(
                id: "local-\(index)",
                sessionId: sessionId,
                personaId: personaId,
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index + 1) 句",
                createdAt: startAt + Int64(index),
                attachmentsJson: index == 0
                    ? AttachmentsCodec.encode([MessageAttachment(uri: "/v1/media/media-a")])
                    : nil,
                readAt: index < 10 ? startAt + Int64(index) + 5 : nil
            ))
        }
    }

    private func localMessages(sessionId: String) throws -> [MessageEntity] {
        try temp.stores.messages.bySession(sessionId)
    }

    private func serverMessages(sessionId: String) -> [BootstrapFakeServer.Message] {
        server.messages[sessionId] ?? []
    }

    // MARK: - 象限 1：本机有、服务端空 → 全量推送

    func testLocalOnlyPushesPersonasSessionsAndAllMessagesInBatches() async throws {
        let personaId = UUID().uuidString
        let sessionId = UUID().uuidString
        try seedLocal(personaId: personaId, sessionId: sessionId, messageCount: 450)
        // 「正在聊的人」是本机 id：推设置时必须换算成服务端 id（否则服务端整条 400）。
        settings.save(AppSettings(activePersonaId: personaId))

        let box = ProgressBox()
        let outcome = await makeService(progress: { box.append($0) }).bootstrap()

        guard case .completed(let report) = outcome else {
            return XCTFail("期望 completed，实际 \(outcome)")
        }
        XCTAssertEqual(report.mode, .push)
        XCTAssertEqual(report.personasPushed, 1)
        XCTAssertEqual(report.sessionsPushed, 1)
        XCTAssertEqual(report.messagesPushed, 450)
        XCTAssertEqual(report.batches, 3, "450 条按 200/200/50 分三批")
        XCTAssertTrue(report.settingsPushed)

        // 分批是契约的一半：每批 ≤200，且保持原顺序。
        XCTAssertEqual(server.importRequests.map(\.count), [200, 200, 50])
        XCTAssertEqual(server.importRequests[0].first?["id"] as? String, "local-0")
        XCTAssertEqual(server.importRequests[2].last?["id"] as? String, "local-449")

        // 服务端真的有了同一份东西：内容 / 消息 id / 时间 / 角色 / 已读 / 附件原样保留。
        //
        // 人格 id 例外：agentd 的 `POST /v1/personas` 语义是「带 id = 更新」，带一个它不认识的
        // id 会 404；客户端这时回落成「不带 id 新建」，服务端自己发 id，并把映射记下来
        //（否则重试会在服务端建出第二个「林深」）。
        XCTAssertEqual(server.personas.count, 1)
        XCTAssertEqual(server.personas.first?.name, "林深")
        let mappedPersonaId = ServerSyncStateStore(database: temp.stores.database)
            .string(ServerSyncStateStore.Key.personaIdMap(personaId))
        XCTAssertEqual(mappedPersonaId, server.personas.first?.id, "本机人格 id 与服务端 id 的映射要落库")
        // 设置里的「正在聊的人」也换算过：服务端只认识它自己的 id。
        XCTAssertEqual(
            server.settings["activePersonaId"] as? String,
            server.personas.first?.id
        )
        XCTAssertEqual(server.sessions.count, 1)
        let pushed = serverMessages(sessionId: server.sessions[0].id)
        XCTAssertEqual(pushed.count, 450)
        XCTAssertEqual(pushed.first?.id, "local-0")
        XCTAssertEqual(pushed.first?.createdAt, 1_000)
        XCTAssertEqual(pushed.first?.role, "USER")
        XCTAssertEqual(pushed.first?.readAt, 1_005)
        XCTAssertEqual(pushed.first?.attachments, ["/v1/media/media-a"])
        XCTAssertEqual(pushed[9].readAt, 1_014, "前 10 条是已读")
        XCTAssertNil(pushed[10].readAt, "第 11 条之后未读（服务端缺键 = 未读）")
        XCTAssertEqual(pushed.last?.content, "第 450 句")

        // 进度：最后一定是 done，且中途有 pushing 阶段。
        let stages = box.all.map(\.stage)
        XCTAssertEqual(stages.first, .detecting)
        XCTAssertTrue(stages.contains(.pushing))
        XCTAssertEqual(stages.last, .done)

        // 本机那一段会话记下了服务端 id（之后发消息要用）。
        let localSession = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertEqual(localSession.serverSessionId, server.sessions[0].id)
    }

    func testBootstrapIsIdempotentAndRemembersPerBaseURL() async throws {
        let sessionId = UUID().uuidString
        try seedLocal(personaId: UUID().uuidString, sessionId: sessionId, messageCount: 3)
        let service = makeService()

        guard case .completed = await service.bootstrap() else {
            return XCTFail("第一次应当完成")
        }
        let done = await service.doneRecord()
        XCTAssertNotNil(done)
        XCTAssertEqual(done?.mode, "PUSH")

        // 第二次：同一个 URL 不再跑（不会重复 import）。
        let second = await service.bootstrap()
        guard case .skipped = second else { return XCTFail("第二次应当 skipped，实际 \(second)") }

        // 强制重来：幂等 —— 服务端一条不多、一条不少，返回的 skipped 等于全部。
        server.importedTotal = 0
        server.skippedTotal = 0
        let forced = await service.bootstrap(force: true)
        guard case .completed(let report) = forced else {
            return XCTFail("强制重来应当完成")
        }
        // 服务端已经有一份了：这次按 MERGE 走，全量拉回来、一条都不重复推上去。
        XCTAssertEqual(report.mode, .merge)
        XCTAssertEqual(report.messagesPushed, 0)
        XCTAssertEqual(report.messagesSkipped, 0)
        XCTAssertEqual(report.messagesPulled, 3)
        XCTAssertEqual(serverMessages(sessionId: server.sessions[0].id).count, 3)
        XCTAssertEqual(server.personas.count, 1, "重来不应当再建一个人格")
        XCTAssertEqual(
            try temp.stores.sessions.count(), 1,
            "也不应当多出一段会话（人格 id 折算的问题会让本机会话被复制一份）"
        )
    }

    // MARK: - 象限 2：服务端有、本机空 → 全量拉取（新客户端自动拿到历史）

    func testServerOnlyPullsEveryBlockInAscendingOrderWithReadState() async throws {
        server.seedPersona(id: "p1", name: "林深", description: "慢热", isDefault: true)
        server.seedSession(id: "s1", personaId: "p1", title: "夜里散步", unread: 3)
        server.seedMessages(sessionId: "s1", count: 450, startAt: 1_000, readCount: 10)

        let box = ProgressBox()
        let outcome = await makeService(progress: { box.append($0) }).bootstrap()

        guard case .completed(let report) = outcome else {
            return XCTFail("期望 completed，实际 \(outcome)")
        }
        XCTAssertEqual(report.mode, .pull)
        XCTAssertEqual(report.personasPulled, 1)
        XCTAssertEqual(report.sessionsPulled, 1)
        XCTAssertEqual(report.messagesPulled, 450, "三块都要翻完，不是只拿最新一块")
        XCTAssertTrue(report.settingsPulled)

        // 本机有了「能看到的历史」：人格、会话（未读数按服务端）、全部消息。
        XCTAssertEqual(try temp.stores.personas.count(), 1)
        let session = try XCTUnwrap(try temp.stores.sessions.byId("s1"))
        XCTAssertEqual(session.personaId, "p1")
        XCTAssertEqual(session.unreadCount, 3)
        XCTAssertEqual(session.serverSessionId, "s1")
        XCTAssertEqual(session.lastMessagePreview, "第 450 句")
        let messages = try localMessages(sessionId: "s1")
        XCTAssertEqual(messages.count, 450)
        XCTAssertEqual(messages.first?.id, "srv-s1-0")
        XCTAssertEqual(messages.last?.id, "srv-s1-449")
        XCTAssertEqual(messages.map(\.createdAt), messages.map(\.createdAt).sorted(),
                       "升序落库（倒序翻页只影响请求，不影响写库顺序）")
        XCTAssertEqual(messages.first?.readAt, 1_005)
        XCTAssertNil(messages[10].readAt)
        XCTAssertEqual(messages.first?.role, .user)
        XCTAssertEqual(messages[1].role, .assistant)

        // 块是本地自己切的（服务端块号不落库）：450 条 = 3 块，且块表自洽。
        XCTAssertEqual(try temp.stores.messageBlocks.blockCount(sessionId: "s1"), 3)
        XCTAssertTrue(try temp.stores.messageBlocks.inconsistencies().isEmpty)

        // 游标推进 → 之后走增量，不再从头拉。
        let state = ServerSyncStateStore(database: temp.stores.database)
        XCTAssertEqual(state.cursor(), server.cursor)

        let stages = box.all.map(\.stage)
        XCTAssertEqual(stages.first, .detecting)
        XCTAssertTrue(stages.contains(.pulling))
        XCTAssertEqual(stages.last, .done)
    }

    /// 拉取完成之后，既有增量同步要能接着跑（回归：不要因为 bootstrap 写了游标就失灵）。
    func testIncrementalSyncContinuesAfterBootstrapPull() async throws {
        server.seedPersona(id: "p1", name: "林深")
        server.seedSession(id: "s1", personaId: "p1", title: "夜里散步")
        server.seedMessages(sessionId: "s1", count: 5, startAt: 1_000, readCount: 2)

        guard case .completed = await makeService().bootstrap() else {
            return XCTFail("拉取应当完成")
        }
        XCTAssertEqual(try localMessages(sessionId: "s1").count, 5)

        // 另一边刚说了一句：增量同步应当只补这一条。
        server.appendMessage(sessionId: "s1", content: "刚回来", createdAt: 2_000)
        let sync = ServerSyncService(
            client: client(),
            stores: temp.stores,
            retry: .init(maxAttempts: 1, baseDelaySeconds: 0),
            now: { 1_800_000_000_000 },
            log: { _ in },
            sleeper: { _ in }
        )
        let outcome = await sync.syncNow(fillLatestBlocks: false)
        guard case .synced(let report) = outcome else {
            return XCTFail("增量同步应当成功，实际 \(outcome)")
        }
        XCTAssertEqual(report.messages, 1)
        let messages = try localMessages(sessionId: "s1")
        XCTAssertEqual(messages.count, 6)
        XCTAssertEqual(messages.last?.content, "刚回来")
        XCTAssertEqual(messages.last?.personaId, "p1")
    }

    // MARK: - 象限 3：两边都有 → 合并

    func testMergeDeduplicatesSameNamedPersonaAndKeepsBothHistories() async throws {
        // 本机：本地模式的种子人格（id 是本机 UUID），已经聊过两句。
        let localPersonaId = UUID().uuidString
        let localSessionId = UUID().uuidString
        try seedLocal(
            personaId: localPersonaId,
            personaDescription: "本机的林深",
            sessionId: localSessionId,
            messageCount: 2,
            startAt: 2_000
        )
        try temp.stores.presets.upsert(PersonaPresetEntity(id: "local-preset", name: "夜班记者"))
        try temp.stores.memories.upsert(MemoryEntity(
            id: "local-mem", personaId: localPersonaId, content: "本机记住的事", importance: 3
        ))
        settings.save(AppSettings(
            themeMode: .system,
            accentColor: 999,
            myAvatarUri: nil
        ))

        // 服务端：同名不同 id 的种子人格（还有一段服务端历史）+ 另一个只存在服务端的人格。
        server.seedPersona(id: "p1", name: "林深", description: "服务端的林深", isDefault: true)
        server.seedSession(id: "s1", personaId: "p1", title: "服务端的会话")
        server.seedMessages(sessionId: "s1", count: 3, startAt: 1_000)
        server.seedPersona(id: "p2", name: "阿岚")
        server.seedSession(id: "s2", personaId: "p2", title: "阿岚的会话")
        server.seedMessages(sessionId: "s2", count: 1, startAt: 3_000)
        server.memories["p1"] = [[
            "id": "srv-mem", "personaId": "p1", "content": "服务端记住的事",
            "importance": 60, "tags": [], "createdAt": 1, "updatedAt": 1
        ]]
        server.presets = [["id": "srv-preset", "name": "旧预设", "createdAt": 1, "updatedAt": 1]]
        server.setSettings([
            "themeMode": "DARK",
            "accentColor": 111,
            "activePersonaId": "p1"
        ])

        let outcome = await makeService().bootstrap()
        guard case .completed(let report) = outcome else {
            return XCTFail("期望 completed，实际 \(outcome)")
        }
        XCTAssertEqual(report.mode, .merge)

        // 人格：不出现第二个「林深」（同名的本机人格被认成服务端 p1），服务端独占的 p2 拉回来。
        let personas = try temp.stores.personas.all(orderBy: "createdAt ASC")
        XCTAssertEqual(personas.count, 2, "人格集合：\(personas.map { "\($0.id):\($0.name)" })")
        XCTAssertEqual(Set(personas.map(\.name)), ["林深", "阿岚"])
        XCTAssertEqual(server.personas.count, 2, "不应当往服务端再建一个林深")
        let merged = try XCTUnwrap(personas.first { $0.name == "林深" })
        XCTAssertEqual(merged.id, localPersonaId, "本机 id 保持权威（会话/消息都挂在它下面）")
        XCTAssertEqual(merged.description, "服务端的林深", "冲突时服务端优先")

        // 会话：本机那一段被认领成 s1，服务端两段历史都落到本机。
        let sessions = try temp.stores.sessions.all(orderBy: "createdAt ASC")
        XCTAssertEqual(sessions.count, 2, "会话集合：\(sessions.map(\.id))")
        let claimed = try XCTUnwrap(sessions.first { $0.id == localSessionId })
        XCTAssertEqual(claimed.serverSessionId, "s1")
        let messages = try localMessages(sessionId: localSessionId)
        XCTAssertEqual(messages.count, 5, "3 条服务端历史 + 2 条本机历史")
        XCTAssertEqual(Set(messages.prefix(3).map(\.id)), ["srv-s1-0", "srv-s1-1", "srv-s1-2"])
        XCTAssertEqual(Set(messages.suffix(2).map(\.id)), ["local-0", "local-1"])
        XCTAssertTrue(messages.allSatisfy { $0.personaId == localPersonaId })
        XCTAssertEqual(serverMessages(sessionId: "s1").count, 5, "本机独有的两条推到了 s1")
        XCTAssertTrue(serverMessages(sessionId: "s1").contains { $0.id == "local-0" })

        // 记忆：服务端那一份拉回来了，本机那一份推上去了。
        let memories = try temp.stores.memories.byPersona(localPersonaId)
        XCTAssertEqual(Set(memories.map(\.content)), ["本机记住的事", "服务端记住的事"])
        XCTAssertEqual(server.memories["p1"]?.count, 2)

        // 预设：两边各有一条，合并后都在。
        XCTAssertEqual(try temp.stores.presets.count(), 2)
        XCTAssertEqual(server.presets.count, 2)

        // 设置：MERGE 不推本机设置（推了会把服务端盖掉），拉服务端的。
        XCTAssertEqual(settings.load().themeMode, .dark)
        XCTAssertEqual(settings.load().accentColor, 111)
        XCTAssertEqual(
            settings.load().activePersonaId, localPersonaId,
            "服务端的人格 id 要折算回本机 id（否则首页指向一个不存在的 id）"
        )
        XCTAssertEqual(server.settings["themeMode"] as? String, "DARK")
        XCTAssertEqual(BootstrapFakeServer.int64(server.settings["accentColor"]), 111)
    }

    // MARK: - 象限 4：两边都空

    func testBothEmptyOnlyMarksDoneAndPushesSettings() async throws {
        let service = makeService()
        let outcome = await service.bootstrap()
        guard case .completed(let report) = outcome else {
            return XCTFail("期望 completed，实际 \(outcome)")
        }
        XCTAssertEqual(report.mode, .empty)
        XCTAssertEqual(report.messagesPushed, 0)
        XCTAssertTrue(report.settingsPushed)
        XCTAssertEqual(try temp.stores.personas.count(), 0, "不应当凭空造人格")
        XCTAssertEqual(server.sessions.count, 0)
        let done = await service.doneRecord()
        XCTAssertEqual(done?.mode, "EMPTY")
    }

    /// 本地 `file://` 图搬不过去（服务端没有那个文件）：引用被去掉，但要在报告里数清楚。
    func testLocalFileAttachmentIsDroppedAndCounted() async throws {
        let personaId = UUID().uuidString
        let sessionId = UUID().uuidString
        try seedLocal(personaId: personaId, sessionId: sessionId, messageCount: 0)
        try temp.stores.messages.upsert(MessageEntity(
            id: "local-file-att",
            sessionId: sessionId,
            personaId: personaId,
            role: .user,
            content: "看这张",
            createdAt: 5_000,
            attachmentsJson: AttachmentsCodec.encode([
                MessageAttachment(uri: "file:///tmp/me.jpg")
            ])
        ))

        let outcome = await makeService().bootstrap()
        guard case .completed(let report) = outcome else {
            return XCTFail("期望 completed，实际 \(outcome)")
        }
        XCTAssertEqual(report.attachmentsDropped, 1)
        let pushed = serverMessages(sessionId: server.sessions[0].id)
        XCTAssertEqual(pushed.count, 1)
        XCTAssertTrue(pushed[0].attachments.isEmpty, "本地图不在服务端，不能硬推一个坏引用")
    }

    // MARK: - 重试 / 断点续跑

    func testInterruptedImportResumesWithoutDuplicates() async throws {
        let sessionId = UUID().uuidString
        try seedLocal(personaId: UUID().uuidString, sessionId: sessionId, messageCount: 450)
        server.failImportTimes = 1

        let service = makeService(retry: .init(maxAttempts: 2, baseDelaySeconds: 0))
        let outcome = await service.bootstrap()
        guard case .completed(let report) = outcome else {
            return XCTFail("重试之后应当完成，实际 \(outcome)")
        }
        XCTAssertEqual(report.messagesPushed, 450)
        XCTAssertEqual(serverMessages(sessionId: server.sessions[0].id).count, 450,
                       "重试不应当造出重复消息")
        XCTAssertEqual(server.importRequests.count, 4, "1 次失败 + 3 次成功")
    }

    /// 服务端说某张附件不在（本地 `file://` 图搬不过去）：去掉附件把文字留下，别整段放弃。
    func testUnknownAttachmentIsDroppedInsteadOfFailingWholeHistory() async throws {
        let sessionId = UUID().uuidString
        try seedLocal(personaId: UUID().uuidString, sessionId: sessionId, messageCount: 2)
        server.rejectUnknownMedia = true

        let outcome = await makeService().bootstrap()
        guard case .completed(let report) = outcome else {
            return XCTFail("期望 completed，实际 \(outcome)")
        }
        XCTAssertEqual(report.attachmentsDropped, 1)
        XCTAssertEqual(report.messagesPushed, 2)
        let pushed = serverMessages(sessionId: server.sessions[0].id)
        XCTAssertEqual(pushed.count, 2)
        XCTAssertTrue(pushed.allSatisfy { $0.attachments.isEmpty })
    }

    // MARK: - 头像（bootstrap 顺手搬）

    func testPushModeUploadsLocalAvatarAndWritesServerKey() async throws {
        let sessionId = UUID().uuidString
        try seedLocal(personaId: UUID().uuidString, sessionId: sessionId, messageCount: 1)
        settings.save(AppSettings(myAvatarUri: "avatar-local.jpg"))

        let uploader = ServerUserAvatarUploader(
            uploader: client(),
            preparer: FakeAvatarPreparer()
        )
        let outcome = await makeService(avatarUploader: uploader).bootstrap()
        guard case .completed(let report) = outcome else {
            return XCTFail("期望 completed，实际 \(outcome)")
        }
        XCTAssertTrue(report.avatarUploaded)
        XCTAssertEqual(server.uploadedMedia.count, 1)
        XCTAssertEqual(server.uploadedMedia.first?.kind, "USER_AVATAR")
        XCTAssertTrue(server.uploadedMedia.first?.bytes ?? 0 > 0)
        let written = try XCTUnwrap(server.settings[ServerSettingsMapping.avatarKey] as? String)
        let mediaId = try XCTUnwrap(server.uploadedMedia.first?.id)
        XCTAssertEqual(written, "/v1/media/\(mediaId)")
        XCTAssertTrue(ServerMediaReference.isServerMediaURI(written))
        // 本机也换成服务端路径（否则下次推送又传一遍）。
        XCTAssertEqual(settings.load().myAvatarUri, written)
    }

    /// 头像上传失败不该挡住「把聊天搬过去」，也不能把设置推失败。
    func testAvatarUploadFailureDoesNotBlockBootstrap() async throws {
        let sessionId = UUID().uuidString
        try seedLocal(personaId: UUID().uuidString, sessionId: sessionId, messageCount: 1)
        settings.save(AppSettings(myAvatarUri: "avatar-local.jpg"))
        server.failMediaUpload = true

        let uploader = ServerUserAvatarUploader(
            uploader: client(),
            preparer: FakeAvatarPreparer()
        )
        let outcome = await makeService(avatarUploader: uploader).bootstrap()
        guard case .completed(let report) = outcome else {
            return XCTFail("头像失败不应让整次同步失败，实际 \(outcome)")
        }
        XCTAssertFalse(report.avatarUploaded)
        XCTAssertTrue(report.settingsPushed)
        XCTAssertNil(server.settings[ServerSettingsMapping.avatarKey])
        XCTAssertEqual(settings.load().myAvatarUri, "avatar-local.jpg", "传不上去就保留本地那张")
    }

    /// 合并时服务端没有头像、本机有一张本地图：顺手传上去（服务端优先：它已有就不覆盖）。
    func testMergeUploadsLocalAvatarWhenServerHasNone() async throws {
        let localPersonaId = UUID().uuidString
        let localSessionId = UUID().uuidString
        try seedLocal(personaId: localPersonaId, sessionId: localSessionId, messageCount: 1)
        settings.save(AppSettings(myAvatarUri: "avatar-local.jpg"))
        server.seedPersona(id: "p1", name: "林深")
        server.seedSession(id: "s1", personaId: "p1")

        let uploader = ServerUserAvatarUploader(
            uploader: client(),
            preparer: FakeAvatarPreparer()
        )
        let outcome = await makeService(avatarUploader: uploader).bootstrap()
        guard case .completed(let report) = outcome else {
            return XCTFail("期望 completed，实际 \(outcome)")
        }
        XCTAssertEqual(report.mode, .merge)
        XCTAssertTrue(report.avatarUploaded)
        XCTAssertEqual(server.uploadedMedia.first?.kind, "USER_AVATAR")
        let written = try XCTUnwrap(server.settings[ServerSettingsMapping.avatarKey] as? String)
        XCTAssertTrue(ServerMediaReference.isServerMediaURI(written))
        XCTAssertEqual(settings.load().myAvatarUri, written)
    }

    /// 拉设置时还原用户头像；服务端只有旧键名（`myAvatarUri`）也要认。
    func testPullRestoresUserAvatarFromLegacyKey() async throws {
        server.seedPersona(id: "p1", name: "林深")
        server.seedSession(id: "s1", personaId: "p1")
        server.setSettings([ServerSettingsMapping.legacyAvatarKey: "/v1/media/avatar-1"])

        guard case .completed = await makeService().bootstrap() else {
            return XCTFail("拉取应当完成")
        }
        XCTAssertEqual(settings.load().myAvatarUri, "/v1/media/avatar-1")
    }
}

// MARK: - 小工具

/// 进度回调是 `@Sendable` 的，测试这一侧用加锁盒子接。
final class ProgressBox: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [ServerBootstrapProgress] = []

    func append(_ progress: ServerBootstrapProgress) {
        lock.lock()
        items.append(progress)
        lock.unlock()
    }

    var all: [ServerBootstrapProgress] {
        lock.lock()
        defer { lock.unlock() }
        return items
    }
}
