import Foundation
import SwiftUI

/// 调试用的启动助手（只在 Debug 构建里编译）。
///
/// 为什么需要它：模拟器上没有「点一下、再打字」的自动化手段，而聊天的完整链路
/// （配置 → SSE → 落库 → 提示词）又必须在提交前真跑一遍。所以留三个环境变量：
///
/// - `MYFRIEND_DEBUG_API_BASE`：自动建一条指向本地参考服务的 API 配置
///   （例如 `http://127.0.0.1:8787/v1`），模型默认 `myfriend-reference`；
/// - `MYFRIEND_DEBUG_SEND`：启动后在这段会话里发一句话，把流式回复打到控制台；
/// - `MYFRIEND_DEBUG_OPEN`：`chat` 表示启动后直接进会话页（方便截图）；
/// - `MYFRIEND_DEBUG_AGENT_CORE_PING=1`：启动后把 agent 决策层的四个端点各走一次并写日志
///   （配合 `MYFRIEND_AGENT_CORE=remote-strict` 验证 App 真的连上了 agentd）。
///
/// 用 `xcrun simctl launch` 的 `SIMCTL_CHILD_` 前缀传进去，例如：
/// ```
/// SIMCTL_CHILD_MYFRIEND_DEBUG_API_BASE=http://127.0.0.1:8787/v1 \
/// SIMCTL_CHILD_MYFRIEND_DEBUG_SEND=你好 \
/// SIMCTL_CHILD_MYFRIEND_DEBUG_OPEN=chat \
/// xcrun simctl launch --terminate-running-process booted com.myfriend.app.ios
/// ```
/// 这些代码不会进 Release：整个文件包在 `#if DEBUG` 里。
#if DEBUG
enum DebugSeeding {

    /// 调试日志写进 App 沙盒的 Documents/debug.log。
    /// 为什么不用 print：`xcrun simctl launch --console` 在受限环境里起不了 FIFO，
    /// 日志落文件才能被 `xcrun simctl get_app_container` 读出来。
    ///
    /// 0.11.0 审计：心跳 / 后台补算 / 工具执行会在不同线程上同时写这一行，
    /// 而 `seekToEndOfFile + write` 不是原子操作 —— 没有这把锁，两行日志会互相覆盖。
    private static let logLock = NSLock()

    static func log(_ message: String) {
        let line = "\(Int(Date().timeIntervalSince1970)) \(message)\n"
        guard let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let url = dir.appendingPathComponent("debug.log")
        logLock.lock()
        defer { logLock.unlock() }
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            handle.write(Data(line.utf8))
            try? handle.close()
        } else {
            try? line.write(to: url, atomically: true, encoding: .utf8)
        }
    }

    /// 根视图起来时调一次：装配调试用的数据、可选打开会话页、可选自动发一句话。
    @MainActor
    static func bootstrap(env: AppEnvironment, path: Binding<NavigationPath>) {
        // 内存快照与内存警告留痕：只为了在提交前能给出「改动前后」的真实数字。
        ImageThumbnailCache.shared.onMemoryWarning = {
            log("[memory] 收到内存警告：缩略图缓存已清空")
        }
        DebugMemoryLog.scheduleBootSnapshot()
        seedApiConfigIfNeeded(env)
        seedLongConversationIfNeeded(env)
        applyPersonaDebugAction(env)
        applyProactiveDebugAction(env)
        applyScenarioIfNeeded(env)
        importDebugMediaIfNeeded(env)
        pingAgentCoreIfNeeded(env)
        if let route = launchRoute {
            path.wrappedValue.append(route)
        }
        if ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_OPEN"] == "persona-editor",
           let persona = ((try? env.stores.personas.allOrderedByUpdatedAt()) ?? []).first {
            path.wrappedValue.append(Route.personaEditor(personaId: persona.id))
        }
        if openChatOnLaunch {
            // 一人一段：直接打开最近说过话的那一段（没有就打开第一段）。
            if let latest = ((try? env.stores.sessions.allOrderedByRecency()) ?? []).first {
                path.wrappedValue.append(Route.chat(sessionId: latest.id))
            }
        }
        sendDebugMessageIfNeeded(env)
    }

    /// 启动后直接打开某一页：`chat` / `settings` / `memory` / `backup` / `personas`。
    /// 只为了在模拟器里截图与验收，Release 包里没有这段。
    static var launchRoute: Route? {
        switch ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_OPEN"] {
        case "chat":
            return nil // 会话页需要具体 id，单独处理
        case "settings", "settings-avatar", "settings-proactive": return .settings
        case "memory": return .memories
        case "proactive": return .proactive
        case "backup": return .backups
        case "personas": return .personas
        case "search": return .search
        case "browser": return .browser(url: ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_URL"] ?? "https://example.com")
        default: return nil
        }
    }

    static var openChatOnLaunch: Bool {
        ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_OPEN"] == "chat"
    }

    // MARK: - 搜索页的验收钩子（0.10.2）

    /// `MYFRIEND_DEBUG_SEARCH_QUERY`：进搜索页就把这句话填进去并查一次。
    static var searchQuery: String? {
        let text = ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SEARCH_QUERY"] ?? ""
        return text.isEmpty ? nil : text
    }

    /// `MYFRIEND_DEBUG_SEARCH_TIME=7d|30d`：让时间筛选停在「最近 7 天 / 最近 30 天」上（截图用）。
    static var searchTimeFilter: SearchViewModel.TimeFilter? {
        switch ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SEARCH_TIME"] ?? "" {
        case "7d": return .week
        case "30d": return .month
        default: return nil
        }
    }

    /// `MYFRIEND_DEBUG_SEARCH_PERSONA=<人格名>`：让按人格筛选停在某个人身上（截图用）。
    static var searchPersonaName: String? {
        let name = ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SEARCH_PERSONA"] ?? ""
        return name.isEmpty ? nil : name
    }

    /// `MYFRIEND_DEBUG_SEARCH_SCROLL=1`：搜索页自己把结果列表滚到底（触发「加载更多」）。
    /// `simctl` 不能上滑，长列表的分页只有这个办法能在截图里看见。
    static var searchScrollToBottom: Bool {
        ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SEARCH_SCROLL"] == "1"
    }

    /// `MYFRIEND_DEBUG_SEARCH_OPEN=1`：结果出来之后自动点开第一条（验收「跳转 + 定位高亮」）。
    static var searchOpenFirst: Bool {
        ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SEARCH_OPEN"] == "1"
    }

    /// `MYFRIEND_DEBUG_SEARCH_ROUNDTRIP=1`：自动点开第一条 → 4 秒后自动返回搜索页。
    /// 用来验收「返回时查询与列表位置还在」（模拟器里没人能点返回键）。
    static var searchRoundTrip: Bool {
        ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SEARCH_ROUNDTRIP"] == "1"
    }

    /// `MYFRIEND_DEBUG_SEARCH_NO_KEYBOARD=1`：进搜索页不弹键盘（键盘会盖住半屏结果，截图用）。
    static var searchNoKeyboard: Bool {
        ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SEARCH_NO_KEYBOARD"] == "1"
    }

    /// `MYFRIEND_DEBUG_SEARCH_SLOW_PAGING=1`：让「正在加载更多…」多停 8 秒，好让 `simctl` 截图。
    /// 与聊天页的 `MYFRIEND_DEBUG_SLOW_PAGING` 是同一个用途（分页状态一帧就过去了）。
    static var searchSlowPaging: Bool {
        ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SEARCH_SLOW_PAGING"] == "1"
    }

    /// 模拟器里没法「点一下图片」：用它让聊天页自己把第一张图的全屏查看打开，方便截图验收。
    static var openImageOnLaunch: Bool {
        ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_OPEN_IMAGE"] == "1"
    }

    /// `MYFRIEND_DEBUG_OPEN=settings-avatar`：设置页打开后自己滚到「我的头像」那一行。
    static var openSettingsAvatar: Bool {
        ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_OPEN"] == "settings-avatar"
    }

    /// Debug：打开设置页并滚到「主动唤醒」入口（截图验收用）。
    static var openSettingsProactive: Bool {
        ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_OPEN"] == "settings-proactive"
    }

    static var pendingMessage: String? {
        let text = ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SEND"] ?? ""
        return text.isEmpty ? nil : text
    }

    /// 验收用：同一个进程里再发一句（`MYFRIEND_DEBUG_SEND_2`）。
    ///
    /// 为什么需要：有些链路**必须同一进程连说两次**才看得出结果——例如 `remember` 的
    /// 「第一次只留回声、第二次才落长期记忆」（回声表在本机还没有 DDL，退回内存实现，
    /// 重新启动就没了）。模拟器里没有人能点键盘，只能靠这个钩子。
    static var pendingFollowUpMessage: String? {
        let text = ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SEND_2"] ?? ""
        return text.isEmpty ? nil : text
    }

    // MARK: - agent 决策层联调

    /// `MYFRIEND_DEBUG_AGENT_CORE_PING=1`：启动后把 agent 决策层的四个端点各走一次，
    /// 结果写进 `debug.log`。
    ///
    /// 为什么需要它：睡眠判定 / defer 计划 / 工具参数夹取都要等模型或心跳才能触发，
    /// 而「app 到底有没有走远程 agentd」这件事必须在联调时一眼看见。
    /// 配合 `MYFRIEND_AGENT_CORE=remote-strict`（不回退）使用：任何一条失败都会写成
    /// `[agentcore-ping] 失败：…`，不会静默回退到本机。
    static var agentCorePingOnLaunch: Bool {
        ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_AGENT_CORE_PING"] == "1"
    }

    @MainActor
    static func pingAgentCoreIfNeeded(_ env: AppEnvironment) {
        guard agentCorePingOnLaunch else { return }
        let mode = ProcessInfo.processInfo.environment["MYFRIEND_AGENT_CORE"] ?? "native（默认）"
        let baseURL = ProcessInfo.processInfo.environment["MYFRIEND_AGENT_CORE_URL"] ?? "http://127.0.0.1:8788（默认）"
        let core = env.agentCore
        log("[agentcore-ping] 开始：mode=\(mode) baseURL=\(baseURL)")
        Task.detached(priority: .utility) {
            let input = SleepInput(
                sleepEnabled: true, dndOnSleep: false,
                sleepMinutes: nil, wakeMinutes: nil,
                nowMinutes: 1410, userMessagesInWindow: 1, wakeThreshold: 3
            )
            do {
                let sleep = try await core.sleepDecision(input)
                log("[agentcore-ping] sleep-decision wake=\(sleep.wake) marksRead=\(sleep.marksRead) reason=\(sleep.reason)")
                let plan = try await core.deferPlan(minutes: 90, reason: "联调", now: 1_700_000_000_000)
                log("[agentcore-ping] defer-plan minutes=\(plan.minutes) earliestAt=\(plan.earliestAt) latestAt=\(plan.latestAt)")
                let cap = try await core.proactiveCap(level: 5, moodIntensity: 2)
                log("[agentcore-ping] proactive-cap dailyCap=\(cap)")
                let args: [String: JSONValue] = ["content": .string("联调"), "importance": .int(9)]
                let clamped = try await core.clampToolArgs(tool: AgentCoreTool.remember, args: args)
                log("[agentcore-ping] tool-args importance=\(String(describing: clamped["importance"] ?? .null))")
                log("[agentcore-ping] 四个端点全部成功")
            } catch {
                log("[agentcore-ping] 失败：\(error)")
            }
        }
    }

    /// 建一条本地参考服务的配置（幂等：已存在同名配置就只更新地址）。
    @MainActor
    static func seedApiConfigIfNeeded(_ env: AppEnvironment) {
        let base = ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_API_BASE"] ?? ""
        guard !base.isEmpty else { return }
        let model = ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_MODEL"] ?? "myfriend-reference"
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // 名字跟随界面语言（仅 Debug 用）；查找同时认地址，切换语言后不会又建一条。
        let configName = String(localized: "本地参考服务")
        let existing = (try? env.stores.apiConfigs.all()) ?? []
        if let found = existing.first(where: { $0.name == configName || $0.baseUrl == base }) {
            var updated = found
            updated.baseUrl = base
            updated.model = model
            updated.allowInsecureHttp = true
            // 本地参考服务同时提供 DeepSeek 的 /v1/tools/search 与 /v1/tools/fetch，
            // 所以调试配置顺手把联网工具也指向它——这样 `web_search` / `web_fetch` 能在模拟器上真跑通。
            updated.webToolsEnabled = true
            updated.webToolsBaseUrl = base
            updated.updatedAt = now
            try? env.stores.apiConfigs.upsert(updated)
            try? env.apiKeys.setAPIKey("sk-test", for: found.id)
        } else {
            let config = ApiConfigEntity(
                id: UUID().uuidString,
                name: configName,
                provider: .openai,
                baseUrl: base,
                model: model,
                allowInsecureHttp: true,
                createdAt: now,
                updatedAt: now,
                webToolsEnabled: true,
                webToolsBaseUrl: base
            )
            try? env.stores.apiConfigs.upsert(config)
            try? env.apiKeys.setAPIKey("sk-test", for: config.id)
        }

        // 把已经有、但还没绑定配置的会话补上（正常路径由 AppEnvironment 做，这里兜一次）。
        if let configId = env.defaultApiConfigId() {
            let sessions = (try? env.stores.sessions.all()) ?? []
            for session in sessions where (session.apiConfigId ?? "").isEmpty {
                var fixed = session
                fixed.apiConfigId = configId
                try? env.stores.sessions.upsert(fixed)
            }
        }
    }

    /// 只为了在模拟器里复现「他这一轮没回」的几个场景（`MYFRIEND_DEBUG_SCENARIO`）：
    ///
    /// - `asleep`：把当前人格设成「正在睡、没开免打扰、阈值 6」——发一条消息会得到「已读」；
    /// - `asleep-dnd`：正在睡 + 免打扰——发一条消息连「已读」都没有；
    /// - `awake`：把「不睡觉」复原，让消息能正常发出去；
    /// - `defer`：让模拟服务端回一个 `defer_reply` 工具块（消息已读、没有助手气泡）。
    ///
    /// 为什么要有它：这三种沉默在界面上都「什么都没发生」，没有这个开关就没法截图验收。
    /// 只改当前人格的睡眠字段，不碰别的数据；Release 包里不存在这段代码。
    @MainActor
    static func applyScenarioIfNeeded(_ env: AppEnvironment) {
        let scenario = ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SCENARIO"] ?? ""
        guard !scenario.isEmpty else { return }
        let personas = (try? env.stores.personas.allOrderedByUpdatedAt()) ?? []
        guard var persona = personas.first else { return }
        let now = Calendar.current.dateComponents([.hour, .minute], from: Date())
        let minute = (now.hour ?? 22) * 60 + (now.minute ?? 0)
        switch scenario {
        case "asleep", "asleep-dnd":
            persona.sleepEnabled = true
            persona.quietSleepMinutes = (minute - 60 + 1440) % 1440
            persona.quietWakeMinutes = (minute + 180) % 1440
            persona.dndOnSleep = scenario == "asleep-dnd"
            // 阈值给大一点：截图/验收时「连着发几条也不该把他叫醒」，方便看「已读」而不触发唤醒。
            // （唤醒本身由 SleepScheduleTests / ChatRhythmTests 覆盖，那里可以精确控制消息数。）
            persona.wakeThreshold = 20
            try? env.stores.personas.upsert(persona)
            log("[debug] scenario \(scenario)：作息 \(persona.quietSleepMinutes!)–\(persona.quietWakeMinutes!)，免打扰=\(persona.dndOnSleep)")
        case "awake":
            // 「他不睡觉，任何时间都会回」：用来把上一个 asleep 场景复原。
            // 这是三个人格之后的行为：只唤醒第一个不够——`MYFRIEND_DEBUG_SEND` 发的是「最近那段会话」，
            // 可能属于另一个人。全部复原，截图/验收才可预期。
            for var person in personas {
                person.sleepEnabled = false
                try? env.stores.personas.upsert(person)
            }
            log("[debug] scenario awake：\(personas.count) 个人都不睡，随时会回")
        case "defer":
            log("[debug] scenario defer：等模拟服务端回 defer_reply")
        default:
            break
        }
    }

    /// 长会话验收：`MYFRIEND_DEBUG_SEED_MESSAGES=130` 往最近那段会话里补 N 条消息。
    ///
    /// 为什么要有它：聊天页的窗口化分页（首屏 60、上滑再 60）只有长会话才看得出效果，
    /// 而模拟器里没人能连续打 130 句话。这里直接写库，走的是和真实消息一样的 `MessageStore`。
    @MainActor
    static func seedLongConversationIfNeeded(_ env: AppEnvironment) {
        let raw = ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SEED_MESSAGES"] ?? ""
        guard let count = Int(raw), count > 0 else { return }
        let sessions = (try? env.stores.sessions.allOrderedByRecency()) ?? []
        guard let session = sessions.first else {
            log("[debug] seed messages：没有会话")
            return
        }
        let existing = (try? env.stores.messages.count(sessionId: session.id)) ?? 0
        guard existing < count else {
            log("[debug] seed messages：已有 \(existing) 条，不再补")
            return
        }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let start = now - Int64(count - existing) * 60_000
        let messages = (existing..<count).map { index -> MessageEntity in
            let stamp = start + Int64(index - existing) * 60_000
            return MessageEntity(
                id: "debug-msg-\(index)",
                sessionId: session.id,
                personaId: session.personaId,
                role: index % 2 == 0 ? .user : .assistant,
                content: index % 4 == 0
                    ? "第 \(index) 句：今天还是有点忙，晚点再细说。"
                    : "第 \(index) 句：我在，慢慢说。",
                createdAt: stamp,
                updatedAt: stamp,
                status: .complete
            )
        }
        try? env.stores.messages.upsert(messages)
        if var fresh = (try? env.stores.sessions.byId(session.id)) ?? nil {
            fresh.lastMessagePreview = messages.last?.content
            fresh.lastMessageAt = messages.last?.createdAt
            fresh.updatedAt = now
            try? env.stores.sessions.upsert(fresh)
        }
        log("[debug] seed messages \(messages.count) 条 → 会话 \(session.id)，全表 \(count) 条")
    }

    /// 人格删除验收：`MYFRIEND_DEBUG_PERSONA_ACTION=delete-avatar` 删掉第一个带头像的人格，
    /// 并在日志里写出「删除前文件存在 / 删除后文件不在」——截图之外再留一份可核对的事实。
    @MainActor
    static func applyPersonaDebugAction(_ env: AppEnvironment) {
        guard ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_PERSONA_ACTION"] == "delete-avatar" else {
            return
        }
        let personas = (try? env.stores.personas.allOrderedByUpdatedAt()) ?? []
        guard let target = personas.first(where: { ($0.avatarUri ?? "").isEmpty == false }) else {
            log("[debug] persona delete：没有带头像的人格")
            return
        }
        let uri = target.avatarUri ?? ""
        let before = MediaStorage.shared.exists(uriString: uri)
        log("[debug] persona delete 前：\(uri) exists=\(before)")
        do {
            try env.deletePersona(target)
            let after = MediaStorage.shared.exists(uriString: uri)
            log("[debug] persona delete 后：\(target.name) 已删，头像 exists=\(after)")
        } catch {
            log("[debug] persona delete 失败：\(error.localizedDescription)")
        }
    }

    /// 「主动唤醒」页的调试动作（`MYFRIEND_DEBUG_PROACTIVE_ACTION=seed|disable|delete`）。
    ///
    /// 模拟器里 `simctl` 不能点按，所以列表页那三条用户操作（新建 / 关掉 / 删除）
    /// 只能由这里替用户点一下——它调用的**就是界面按钮调用的那几个方法**
    /// （`ProactiveRuleService.createManual` / `setEnabled` / `delete`），不是另一条捷径。
    static var proactiveDebugAction: String? {
        ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_PROACTIVE_ACTION"]
    }

    @MainActor
    static func applyProactiveDebugAction(_ env: AppEnvironment) {
        guard let action = proactiveDebugAction, !action.isEmpty else { return }
        guard let persona = env.currentPersona() else {
            log("[debug] proactive：还没有人格")
            return
        }
        let sessionId = env.session(forPersona: persona.id)?.id
        switch action {
        case "seed":
            let daily = env.proactiveRules.createManual(
                personaId: persona.id, sessionId: sessionId, kind: .daily,
                timeOfDayMinutes: 22 * 60 + 30, intervalMinutes: nil,
                hint: "像往常一样轻声问他今天过得怎么样，不要提规则本身",
                note: "睡前问一句今天过得怎么样"
            )
            let interval = env.proactiveRules.createManual(
                personaId: persona.id, sessionId: sessionId, kind: .interval,
                timeOfDayMinutes: nil, intervalMinutes: 3 * 60,
                hint: "随口问一句他手上那件事的进展，别像查岗",
                note: "下午随口问一句在忙什么"
            )
            log("[debug] proactive seed：daily=\(String(describing: try? daily.get())) interval=\(String(describing: try? interval.get()))")
        case "disable":
            let rules = ((try? env.stores.proactiveRules.all(orderBy: "nextTriggerAt ASC")) ?? [])
                .filter(\.enabled)
            guard let first = rules.first else {
                log("[debug] proactive disable：没有开着的规则")
                return
            }
            log("[debug] proactive disable：\(env.proactiveRules.setEnabled(id: first.id, enabled: false))")
        case "delete":
            let rules = (try? env.stores.proactiveRules.all(orderBy: "nextTriggerAt ASC")) ?? []
            guard let last = rules.last else {
                log("[debug] proactive delete：没有规则可删")
                return
            }
            log("[debug] proactive delete \(last.id)：\(env.proactiveRules.delete(id: last.id))")
        default:
            log("[debug] proactive：不认识的动作 \(action)")
        }
    }

    /// 调试用图片路径（`MYFRIEND_DEBUG_IMAGE=/tmp/photo.jpg`）：把这台电脑上的一张图
    /// 当成相册选的结果导入，并顺手给当前人格与「我的头像」设上同一张。
    ///
    /// 为什么需要它：模拟器里没有人能替我们点 `PhotosPicker`，而「选图 → 压缩落盘 →
    /// `attachmentsJson` 落库 → 气泡渲染 → 重启后仍在」这条链路必须真跑一遍。
    /// 这段走的正是 App 里那条完全相同的 `MediaStorage.saveImage`，只是跳过了相册那一层。
    static var debugImagePath: String? {
        let path = ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_IMAGE"] ?? ""
        return path.isEmpty ? nil : path
    }

    @MainActor
    static func importDebugMediaIfNeeded(_ env: AppEnvironment) {
        guard let path = debugImagePath else { return }
        let url = URL(fileURLWithPath: path)
        Task { @MainActor in
            // 1) 聊天图：长边 ≤1600 / ≤800KB
            guard let chatImage = try? MediaStorage.shared.saveImage(at: url, kind: .chatImage) else {
                log("[debug] 导入聊天图失败：\(path)")
                return
            }
            log("[debug] 聊天图已落盘：\(chatImage.uri) \(chatImage.width)x\(chatImage.height)")

            // 2) 头像：长边 ≤512 / ≤400KB（给当前人格与「我的头像」各一份引用）
            if let avatar = try? MediaStorage.shared.saveImage(at: url, kind: .avatar) {
                if var persona = ((try? env.stores.personas.allOrderedByUpdatedAt()) ?? []).first {
                    MediaStorage.shared.deleteFileIfOwned(persona.avatarUri)
                    persona.avatarUri = avatar.uri
                    try? env.stores.personas.upsert(persona)
                }
                env.updateSettings { $0.myAvatarUri = avatar.uri }
                log("[debug] 头像已设置：\(avatar.uri) \(avatar.width)x\(avatar.height)")
            }

            // 3) 把这张图当一条用户消息发出去（可选配文用 `MYFRIEND_DEBUG_SEND`）。
            let sessions = (try? env.stores.sessions.allOrderedByRecency()) ?? []
            guard let session = sessions.first else {
                log("[debug] 没有会话可发图")
                return
            }
            let caption = ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SEND"] ?? ""
            log("[debug] 发送带图消息到会话 \(session.id)：\(caption)")
            do {
                for try await event in env.chat.send(
                    sessionId: session.id, text: caption, attachments: [chatImage]
                ) {
                    if case .done(let completion) = event {
                        log("[debug] 带图消息已回复：\(completion.message.content.prefix(80))")
                    }
                    if case .noReply(let reason) = event {
                        log("[debug] 带图消息这一轮没回：\(reason)")
                    }
                }
            } catch {
                log("[debug] 带图消息失败：\(error.localizedDescription)")
            }
        }
    }

    /// 启动后自动发一条消息，把流式回复打出来（只用于人工核验链路）。
    @MainActor
    static func sendDebugMessageIfNeeded(_ env: AppEnvironment) {
        // 带图场景由 `importDebugMediaIfNeeded` 一起发（图 + 可选的配文），别发两遍。
        guard debugImagePath == nil else { return }
        guard let text = pendingMessage else { return }
        Task { @MainActor in
            let sessions = (try? env.stores.sessions.allOrderedByRecency()) ?? []
            guard let session = sessions.first else {
                log("[debug] 没有会话可发")
                return
            }
            log("[debug] 发送到会话 \(session.id)：\(text)")
            do {
                for try await event in env.chat.send(sessionId: session.id, text: text) {
                    switch event {
                    case .delta(let full):
                        log("[debug] delta: \(full.suffix(40))")
                    case .toolStarted(let trace):
                        log("[debug] tool started: \(trace.name)")
                    case .toolFinished(let trace):
                        log("[debug] tool finished: \(trace.name) ok=\(trace.ok)")
                    case .done(let completion):
                        log("[debug] done: \(completion.message.content.prefix(120))")
                    case .noReply(let reason):
                        // 1.9：这一轮他选择不说话（睡着 / 免打扰 / defer_reply）。
                        log("[debug] noReply: \(reason)")
                    }
                }
            } catch {
                log("[debug] 失败：\(error.localizedDescription)")
            }

            // 验收用：同一进程再发一句（见 `pendingFollowUpMessage` 的说明）。
            guard let followUp = pendingFollowUpMessage else { return }
            log("[debug] 补发到会话 \(session.id)：\(followUp)")
            do {
                for try await event in env.chat.send(sessionId: session.id, text: followUp) {
                    if case .done(let completion) = event {
                        log("[debug] 补发已回复：\(completion.message.content.prefix(120))")
                    }
                    if case .toolFinished(let trace) = event {
                        log("[debug] 补发 tool finished: \(trace.name) \(trace.title)")
                    }
                }
            } catch {
                log("[debug] 补发失败：\(error.localizedDescription)")
            }
        }
    }
}
#else
enum DebugSeeding {
    /// Release：什么都不做。
    @MainActor
    static func bootstrap(env: AppEnvironment, path: Binding<NavigationPath>) {}

    /// 与 Debug 版同名：装配层会用它记一条并发闸超时日志，Release 里这条链路不存在。
    static func log(_ message: String) {}

    /// Release：不自动打开全屏看图。
    static var openImageOnLaunch: Bool { false }

    /// Release：设置页不自动滚动。
    static var openSettingsAvatar: Bool { false }

    /// Release：没有这个调试钩子。
    static var openSettingsProactive: Bool { false }

    /// Release：没有这个调试钩子。
    static var pendingFollowUpMessage: String? { nil }

    /// Release：不执行主动唤醒页的调试动作。
    @MainActor
    static func applyProactiveDebugAction(_ env: AppEnvironment) {}

    /// Release：搜索页没有预填查询。
    static var searchQuery: String? { nil }

    /// Release：搜索页不预设时间筛选。
    static var searchTimeFilter: SearchViewModel.TimeFilter? { nil }

    /// Release：搜索页不预设人格筛选。
    static var searchPersonaName: String? { nil }

    /// Release：搜索页不自动滚动。
    static var searchScrollToBottom: Bool { false }

    /// Release：搜索页不自动点开第一条。
    static var searchOpenFirst: Bool { false }

    /// Release：搜索页没有「慢速分页」这个调试模式。
    static var searchSlowPaging: Bool { false }

    /// Release：搜索页照常自动聚焦（弹键盘）。
    static var searchNoKeyboard: Bool { false }

    /// Release：搜索页不会自动往返。
    static var searchRoundTrip: Bool { false }
}
#endif
