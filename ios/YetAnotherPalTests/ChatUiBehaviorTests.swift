import Foundation
import XCTest
@testable import YetAnotherPal

/// 聊天页（`ChatViewModel` / 界面状态机）的**最小行为测试**。
///
/// 为什么需要这一组：0.11–0.12 改过两次聊天分页（0.10.2「重读最后 N 条」→ 0.11 区块模式 →
/// 首屏只读最新一块），而 View/ViewModel 这一层**没有任何自动化测试** —— 1263 条 Kit 测试
/// 全都停在 `MessageStore` / `MessageBlocks` 那一层，「界面首屏到底读了几块」「上滑之后窗口
/// 是不是真的接上了上一块」「发送被拒之后草稿还在不在」这些**用户能看见的**行为没人守。
///
/// 这六条覆盖的是最容易被后续重构悄悄改坏的路径（对应 `spec/messages-blocks/README.md` §3）：
///
/// 1. 首屏只读最新一块（不是整段、也不是旧常量 60 条）；
/// 2. 向上加载一块；
/// 3. 向下加载一块（v11 新增）与 `hasMoreNewer`；
/// 4. 从搜索结果定位：目标进窗口 + 高亮状态（不抓像素，只断言 ViewModel 状态）；
/// 5. 发送在**落库前**被拒时，草稿与已选图片保住（0.10.2 审计 P1-10）；
/// 6. 「删除全部本地数据」之后重新播种三位人格、会话还能打开。
///
/// ## 为什么是 App 承载的测试、且用真实的 `AppEnvironment`
///
/// `ChatViewModel` 只接受 `AppEnvironment`（`bind(env:sessionId:)`），而
/// `AppEnvironment.init()` 没有注入点（`stores` 是 `let`，路径固定为沙盒的
/// `Library/Application Support/yap.db`）。所以这一层**没法**像 Kit 测试那样
/// `DatabaseStores.open(path: ":memory:")` —— 想用临时库就必须改生产代码（本次约束不允许）。
/// 这里的做法是：用 App 容器里的真实装配 + **每次测试独有的 persona / session / 消息 id**，
/// 跑完在 `defer` 里把这几行删干净；断言全部只看自己那段会话，不依赖库里的其它数据。
///
/// 跑法（必须在模拟器上，`swift test` 覆盖不到 App target）：
/// ```bash
/// cd ios
/// TMPDIR=$PWD/.build/tmp CLANG_MODULE_CACHE_PATH=$PWD/.build/clang-cache \
/// SWIFT_MODULE_CACHE_PATH=$PWD/.build/swift-cache \
/// xcodebuild test -project YetAnotherPal.xcodeproj -scheme YetAnotherPal \
///   -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
///   -derivedDataPath .build/DerivedData \
///   -only-testing:YetAnotherPalTests/ChatUiBehaviorTests \
///   OTHER_SWIFT_FLAGS='$(inherited) -disable-sandbox'
/// ```
///
/// 如果所在环境没有 pty（沙箱里 `xcodebuild test` 会在启动阶段报
/// `Pseudo Terminal Setup Error ... Operation not permitted`，因为它要给测试宿主开伪终端），
/// 可以绕开 Xcode 的启动器，直接在模拟器里用 Xcode 自带的 `xctest` agent 跑**同一份产物**：
///
/// ```bash
/// cd ios
/// TMPDIR=$PWD/.build/tmp CLANG_MODULE_CACHE_PATH=$PWD/.build/clang-cache \
/// SWIFT_MODULE_CACHE_PATH=$PWD/.build/swift-cache \
/// xcodebuild build-for-testing -project YetAnotherPal.xcodeproj -scheme YetAnotherPal \
///   -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -derivedDataPath .build/DerivedData-chatui \
///   OTHER_SWIFT_FLAGS='$(inherited) -disable-sandbox'
/// xcrun simctl install booted .build/DerivedData-chatui/Build/Products/Debug-iphonesimulator/YetAnotherPal.app
/// APP=$(xcrun simctl get_app_container booted com.yap.app.ios app)
/// PLAT=/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer
/// SIMCTL_CHILD_DYLD_INSERT_LIBRARIES="$APP/YetAnotherPal.debug.dylib" \
/// SIMCTL_CHILD_DYLD_FRAMEWORK_PATH="$PLAT/Library/Frameworks:$PLAT/Library/PrivateFrameworks" \
/// SIMCTL_CHILD_DYLD_LIBRARY_PATH="$PLAT/usr/lib" \
/// xcrun simctl spawn booted "$PLAT/Library/Xcode/Agents/xctest" \
///   -XCTest ChatUiBehaviorTests "$APP/PlugIns/YetAnotherPalTests.xctest"
/// ```
final class ChatUiBehaviorTests: XCTestCase {

    /// 一块的条数（`spec/messages-blocks/README.md` §1：`BLOCK_SIZE = 200`）。
    private static let blockSize = 200
    /// 450 条 = 3 块（200 + 200 + 50）：首屏一块、上滑两次到底、下滑两次到底。
    private static let fixtureMessageCount = 450

    // MARK: - 1. 首屏只读一块

    /// 450 条三轮：首屏只该读**最新那一块**（50 条），从 SQLite 捞的行数不超过一块。
    ///
    /// 这条挡的是「又悄悄改回整段读 / 改回某个固定条数」：那种改动在 `MessageStore` 的
    /// 单测里看不出来（`blocksAround` 本身没变），只有在这里断言**窗口内容**才看得见。
    @MainActor
    func testInitialLoadOnlyReadsNewestBlock() throws {
        let harness = try makeHarness()
        defer { harness.cleanup() }

        let model = ChatViewModel()
        model.bind(env: harness.env, sessionId: harness.sessionId)

        // 数一数窗口化读取到底从 SQLite 物化了多少行（`MessageStore.queryObserver` 是单测钩子）。
        var observed: [(method: String, rows: Int)] = []
        harness.env.stores.messages.queryObserver = { method, rows in
            observed.append((method, rows))
        }
        defer { harness.env.stores.messages.queryObserver = nil }

        model.enter()
        defer { model.leave() }

        XCTAssertTrue(model.loaded, "进入页面之后应标记「首屏读完了」")
        XCTAssertEqual(model.session?.id, harness.sessionId)

        XCTAssertLessThanOrEqual(
            model.messages.count, Self.blockSize,
            "首屏最多一块（\(Self.blockSize) 条），实际 \(model.messages.count) 条"
        )
        XCTAssertEqual(
            model.messages.map(\.id), Array(harness.messageIds[400..<450]),
            "首屏应该正好是最新那一块，且按时间升序"
        )
        XCTAssertTrue(
            model.messages.contains { $0.id == harness.messageIds[449] },
            "最新一句必须在窗口里"
        )
        XCTAssertFalse(
            model.messages.contains { $0.id == harness.messageIds[0] },
            "最早一句不该被首屏读进来（那说明又整段读了）"
        )
        XCTAssertTrue(model.hasMoreHistory, "上面还有两块，应该显示「加载更早」")
        XCTAssertFalse(model.hasMoreNewer, "已经在最新一块，下面不该显示「有更新的消息」")

        XCTAssertFalse(observed.isEmpty, "首屏应该真的查了库（queryObserver 没收到回调）")
        XCTAssertTrue(
            observed.allSatisfy { $0.rows <= Self.blockSize },
            "首屏单次读取超过一块：\(observed)"
        )
        XCTAssertFalse(
            observed.contains { $0.method == "bySession" },
            "首屏不该整段读会话：\(observed)"
        )
    }

    // MARK: - 2. 向上加载

    /// 滚到顶加载更早：一次接上**上一块**，接两次到最早；到顶之后再喊不会重复加载。
    @MainActor
    func testLoadEarlierAppendsPreviousBlock() throws {
        let harness = try makeHarness()
        defer { harness.cleanup() }

        let model = ChatViewModel()
        model.bind(env: harness.env, sessionId: harness.sessionId)
        model.enter()
        defer { model.leave() }

        XCTAssertEqual(model.messages.map(\.id), Array(harness.messageIds[400..<450]))

        model.loadEarlierMessages()
        XCTAssertEqual(
            model.messages.map(\.id), Array(harness.messageIds[200..<450]),
            "向上加载一次应该把第 1 块接在最新块前面（250 条）"
        )
        XCTAssertTrue(
            model.messages.contains { $0.id == harness.messageIds[250] },
            "上一块的内容要真的进窗口"
        )
        XCTAssertTrue(model.hasMoreHistory, "最早那一块（第 0 块）还没加载")
        XCTAssertFalse(model.isLoadingEarlier, "同步加载结束后不该停在「正在加载」")

        model.loadEarlierMessages()
        XCTAssertEqual(model.messages.map(\.id), harness.messageIds, "再加载一次就到最早了（450 条）")
        XCTAssertFalse(model.hasMoreHistory, "到最早之后不该再显示「加载更早」")

        // 到顶之后 UI 的 onAppear 会继续喊：不该重复加载、也不该改变窗口。
        model.loadEarlierMessages()
        XCTAssertEqual(model.messages.map(\.id), harness.messageIds)
    }

    // MARK: - 3. 向下加载（v11）

    /// 窗口停在旧块时向下滚：接上**下一块**，`hasMoreNewer` 一直正确，到最后一块归 false。
    @MainActor
    func testLoadNewerAppendsNextBlockAndUpdatesHasMoreNewer() throws {
        let harness = try makeHarness()
        defer { harness.cleanup() }

        let model = ChatViewModel()
        model.bind(env: harness.env, sessionId: harness.sessionId)
        model.enter()
        defer { model.leave() }

        // 先把窗口停在最早那一块（等价于「从搜索结果跳到旧消息」之后的阅读位置）。
        model.focus(on: harness.messageIds[120])
        XCTAssertEqual(model.messages.map(\.id), Array(harness.messageIds[0..<200]))
        XCTAssertTrue(model.hasMoreNewer, "下面还有两块")
        XCTAssertFalse(model.hasMoreHistory, "已经在最早一块")

        model.loadNewerMessages()
        XCTAssertEqual(
            model.messages.map(\.id), Array(harness.messageIds[0..<400]),
            "向下加载一次应该接上第 1 块（400 条），且不挤掉正在看的第 0 块"
        )
        XCTAssertTrue(
            model.messages.contains { $0.id == harness.messageIds[250] },
            "下一块的内容要真的进窗口"
        )
        XCTAssertTrue(model.hasMoreNewer, "最新一块（第 2 块）还没加载")
        XCTAssertFalse(model.isLoadingNewer, "同步加载结束后不该停在「正在加载」")

        model.loadNewerMessages()
        XCTAssertEqual(model.messages.map(\.id), harness.messageIds, "再加载一次就到最新了")
        XCTAssertFalse(model.hasMoreNewer, "已经在最新一块，下面不该再亮「有更新的消息」")

        // 到最新之后继续滚到底：不该再触发任何加载。
        model.loadNewerMessages()
        XCTAssertEqual(model.messages.map(\.id), harness.messageIds)
    }

    // MARK: - 4. 跳转定位

    /// 从搜索结果进来：目标消息所在块（+ 上一块）进窗口，`highlightedMessageId` / `scrollTargetId`
    /// 指向它，列表条目（`rows`，界面真正画的东西）里确实有这一条。
    ///
    /// 刻意不抓像素、不驱动 SwiftUI 滚动：`ChatScreen` 只是把 `scrollTargetId` 交给
    /// `ScrollViewReader`，滚动本身不可断言；能稳定断言的是「窗口里有没有它」与「高亮状态」，
    /// 这两件正是回归时最先坏掉的部分。
    @MainActor
    func testFocusBringsOldMessageIntoWindowAndHighlightsIt() throws {
        let harness = try makeHarness()
        defer { harness.cleanup() }

        let model = ChatViewModel()
        model.bind(env: harness.env, sessionId: harness.sessionId)
        model.enter()
        defer { model.leave() }

        let target = harness.messageIds[120]  // 第 0 块，离首屏两块远
        XCTAssertFalse(model.messages.contains { $0.id == target }, "首屏（最新一块）里不该有这条")

        model.focus(on: target)

        XCTAssertEqual(
            model.messages.map(\.id), Array(harness.messageIds[0..<200]),
            "定位应该读目标块（第 0 块），带上它的上一块（没有，所以只有 200 条）"
        )
        XCTAssertTrue(model.messages.contains { $0.id == target }, "目标消息要进窗口")
        XCTAssertEqual(model.highlightedMessageId, target, "定位之后要高亮这一条")
        XCTAssertEqual(model.scrollTargetId, target, "界面靠 scrollTargetId 决定滚到哪里")
        XCTAssertTrue(
            model.rows.contains { $0.id == target },
            "消息列表条目里要有这一条（界面就是按 rows 画的）"
        )
        XCTAssertTrue(model.hasMoreNewer, "定位之后窗口不在最新位置，下面还有更新的消息")
        XCTAssertFalse(model.hasMoreHistory, "目标在第 0 块，上面没有更早的了")
    }

    // MARK: - 5. 发送失败保留草稿

    /// 发送在**落库前**被拒（无效配置 / 消息过长）时：草稿、已选图片原样留着，
    /// `sendFailure` 有值（界面据此显示「草稿还在，可以重试」），且库里没有半截消息。
    ///
    /// 0.10.2 审计 P1-10 的回归点：以前 `send()` 先清空输入状态，再拿到一条只会抛错的流，
    /// 用户刚打的字和刚选的图就没了。
    @MainActor
    func testSendRejectionKeepsDraftAndAttachments() throws {
        let harness = try makeHarness(messages: 0, withApiConfig: true)
        defer { harness.cleanup() }

        let model = ChatViewModel()
        model.bind(env: harness.env, sessionId: harness.sessionId)

        // 要有一个「能解析出来」的配置，`send()` 才会走到 `sendChecked`（否则它在更前面就弹引导了）。
        XCTAssertNotNil(harness.env.resolvedChatConfig(), "这条路径的前提是「有配置可选」")
        let configId = try XCTUnwrap(harness.configId)

        let draftText = "这句话不该消失"
        let image = MessageAttachment(uri: "file:///tmp/ui-test-image-\(UUID().uuidString).jpg")
        model.draft = draftText
        model.addAttachments([image])
        XCTAssertEqual(model.pendingAttachments.map(\.uri), [image.uri])

        // 场景 A：「无效配置」——会话绑的配置在库里已经不存在了（落库前就被拒，不发网络请求）。
        var session = try XCTUnwrap(harness.env.stores.sessions.byId(harness.sessionId))
        session.apiConfigId = "ui-test-config-missing-\(UUID().uuidString)"
        try harness.env.stores.sessions.upsert(session)

        model.send()
        XCTAssertTrue(
            (model.sendFailure ?? "").isEmpty == false,
            "被拒之后应该留一个可重试的原因，实际：\(String(describing: model.sendFailure))"
        )
        XCTAssertEqual(model.draft, draftText, "发送失败不能吞掉草稿")
        XCTAssertEqual(model.pendingAttachments.map(\.uri), [image.uri], "发送失败不能丢掉已选图片")
        XCTAssertFalse(model.isGenerating, "根本没开始生成")
        XCTAssertEqual(
            try harness.env.stores.messages.count(sessionId: harness.sessionId), 0,
            "被拒的话不该留下半截用户消息"
        )

        // 场景 B：「消息过长」——同一条保护的第二条触发路径（参数错误，仍在落库之前）。
        model.dismissSendFailure()
        XCTAssertNil(model.sendFailure)
        session.apiConfigId = configId
        try harness.env.stores.sessions.upsert(session)
        let longText = String(repeating: "字", count: ChatService.maxMessageLength + 1)
        model.draft = longText

        model.send()
        XCTAssertTrue(
            (model.sendFailure ?? "").isEmpty == false,
            "过长的消息也该给出可重试的原因，实际：\(String(describing: model.sendFailure))"
        )
        XCTAssertEqual(model.draft.count, longText.count, "草稿要一字不少地留着")
        XCTAssertEqual(model.pendingAttachments.map(\.uri), [image.uri], "图片也要留着")
        XCTAssertEqual(
            try harness.env.stores.messages.count(sessionId: harness.sessionId), 0,
            "被拒的话不该留下半截用户消息"
        )
    }

    // MARK: - 6. 清空后重播

    /// 「删除全部本地数据」之后：按当前语言重新播种 3 个人格、一人一段会话，旧会话被清掉，
    /// 而且新会话真的能被聊天页打开（`loaded`、空窗口、人格与场景都在）。
    ///
    /// 这条覆盖 0.11.0 审计 P2-13 的回归点：清空之后不能是一个空 App。
    @MainActor
    func testClearAllDataReseedsThreePersonasAndKeepsChatOpenable() throws {
        let harness = try makeHarness(messages: 5)
        defer { harness.cleanup() }
        let oldSessionId = harness.sessionId
        XCTAssertNotNil(try harness.env.stores.sessions.byId(oldSessionId), "清空之前旧会话应该在")

        let summary = harness.env.clearAllData()

        XCTAssertEqual(summary.seededPersonas, 3, "清空之后要重新播种三位示例人格")
        XCTAssertEqual(summary.seededSessions, 3, "一人一段，播种时同时建三段会话")
        XCTAssertTrue(summary.warnings.isEmpty, "清空时不该有表删失败：\(summary.warnings)")

        let personas = try harness.env.stores.personas.all(orderBy: "createdAt ASC")
        XCTAssertEqual(personas.count, 3, "清空之后不该是空 App")
        XCTAssertEqual(
            Set(personas.map(\.id)),
            [SeedPersonas.Identifiers.first, SeedPersonas.Identifiers.second, SeedPersonas.Identifiers.third],
            "重新播种用的是三个固定 id（备份与会话引用认的就是它）"
        )
        XCTAssertNil(try harness.env.stores.sessions.byId(oldSessionId), "旧会话应该被清掉")
        XCTAssertEqual(try harness.env.stores.sessions.count(), 3, "每个种子人格一段会话")

        // 会话可打开：这就是用户从首页点进聊天页的那条路径。
        let first = try XCTUnwrap(personas.first)
        let session = try XCTUnwrap(
            harness.env.session(forPersona: first.id),
            "每个种子人格都应该能取到自己的那一段会话"
        )
        let model = ChatViewModel()
        model.bind(env: harness.env, sessionId: session.id)
        model.enter()
        defer { model.leave() }

        XCTAssertTrue(model.loaded)
        XCTAssertEqual(model.session?.id, session.id)
        XCTAssertEqual(model.persona?.id, first.id)
        XCTAssertTrue(model.messages.isEmpty, "新会话没有历史")
        XCTAssertFalse(model.hasMoreHistory)
        XCTAssertFalse(model.hasMoreNewer)
    }

    // MARK: - 装配（真实 AppEnvironment + 每次独有的 fixture，跑完删干净）

    /// 一次测试用的会话夹具：一段独有的人格 + 会话 + N 条消息（必要时再给一条 API 配置）。
    @MainActor
    private final class Harness {
        let env: AppEnvironment
        let personaId: String
        let sessionId: String
        let configId: String?
        /// 按插入顺序（= 时间升序）的消息 id；下标即「第几条」。
        let messageIds: [String]

        init(env: AppEnvironment, personaId: String, sessionId: String, configId: String?, messageIds: [String]) {
            self.env = env
            self.personaId = personaId
            self.sessionId = sessionId
            self.configId = configId
            self.messageIds = messageIds
        }

        /// 把夹具写进库里的东西删干净（best-effort：清空全部数据之后这些行本来就不在了）。
        ///
        /// 删会话会连带删它的消息与块（`ChatSessionStore.delete` → `MessageBlockStore`），
        /// 所以这里不用再手删消息。
        func cleanup() {
            try? env.stores.sessions.delete(id: sessionId)
            try? env.stores.personas.delete(id: personaId)
            if let configId { try? env.stores.apiConfigs.delete(id: configId) }
        }
    }

    /// 造一份夹具。
    ///
    /// - Parameters:
    ///   - messages: 往会话里写多少条（450 = 三块：200 + 200 + 50）。
    ///   - withApiConfig: 是否建一条能解析出来的 API 配置（只有发送路径需要）。
    @MainActor
    private func makeHarness(
        messages count: Int = ChatUiBehaviorTests.fixtureMessageCount,
        withApiConfig: Bool = false
    ) throws -> Harness {
        let env = AppEnvironment()
        XCTAssertNil(env.bootError, "数据库打不开：\(env.bootError ?? "")")

        let suffix = String(UUID().uuidString.prefix(8))
        let now = DomainClock.nowMillis

        var configId: String?
        if withApiConfig {
            let id = "ui-test-config-\(suffix)"
            try env.stores.apiConfigs.upsert(ApiConfigEntity(
                id: id,
                name: "UI 测试配置",
                baseUrl: "http://127.0.0.1:9/v1",  // 永不发出请求：这些用例都停在落库之前
                model: "ui-test",
                createdAt: now,
                updatedAt: now
            ))
            configId = id
        }

        // 独有的人格：不碰种子人格，也不改「正在聊的人」设置。
        let personaId = "ui-test-persona-\(suffix)"
        try env.stores.personas.upsert(PersonaEntity(
            id: personaId,
            name: "UI 测试人格",
            prompt: "测试用",
            isDefault: false,
            createdAt: now,
            updatedAt: now,
            sleepEnabled: false
        ))

        guard var session = env.session(forPersona: personaId) else {
            throw NSError(domain: "ChatUiBehaviorTests", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "拿不到测试会话（session(forPersona:) 返回 nil）"
            ])
        }
        if let configId {
            session.apiConfigId = configId
            try env.stores.sessions.upsert(session)
        }

        var messageIds: [String] = []
        if count > 0 {
            let base = now - Int64(count) * 1_000
            var records: [MessageEntity] = []
            records.reserveCapacity(count)
            for index in 0..<count {
                let id = "ui-test-msg-\(suffix)-\(index)"
                messageIds.append(id)
                let stamp = base + Int64(index) * 1_000
                records.append(MessageEntity(
                    id: id,
                    sessionId: session.id,
                    personaId: personaId,
                    role: index % 2 == 0 ? .user : .assistant,
                    content: "第 \(index) 条：区块分页行为测试用。",
                    createdAt: stamp,
                    updatedAt: stamp,
                    status: .complete
                ))
            }
            // 走的是 App 里同一条写路径（`MessageStore.upsert` → 块表维护），不是直接塞 SQL。
            try env.stores.messages.upsert(records)
        }

        return Harness(
            env: env,
            personaId: personaId,
            sessionId: session.id,
            configId: configId,
            messageIds: messageIds
        )
    }
}
