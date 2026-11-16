import Foundation

// ---------------------------------------------------------------------------
// 无状态模式的 system 提示词拼装。
//
// 来源：Android `data/repo/ChatRepository.kt` 的 `buildContext`（STATELESS 分支）
// 加上它引用的几段渲染：
//   - 人格文本：`PersonaCardRepository.systemText`（这里由调用方给进来，原因见下）
//   - 记忆块：`MemoryRepository.formatForPrompt`（Services/MemoryRepository.swift 已有）
//   - 会话滚动摘要：`HistoryCompression.memoryBlock`（Domain 已有）
//   - 心情：`MoodRepository`（Services）→ 这里只接收渲染好的两段字符串
//   - 此刻状态：`PresenceRepository.current().detail`（由 App 层注入 `presenceDetail` 闭包）
//   - 主动唤醒能力：`ProactiveProtocol.CAPABILITY_PROMPT`（iOS 还没移植，常量先放这里）
//   - 工具能力：`ToolCatalog.capabilityPrompt`（Chat/ToolProtocol.swift）
//
// **分段顺序是有意义的**，不是审美：人格在最前（模型的「自我」优先），记忆与摘要紧随
// （让「他记得」比「他会用工具」更早进入注意力），工具与输出格式放最后（属于规则，
// 放在尾巴上模型更容易当成指令而不是设定）。改顺序等于改提示词，必须两端一起改。
//
// 为什么要一个「拼装器」而不是在聊天服务里 `append`：这个字符串是**产品**——
// 段落、标题、空行都有人对着两端逐字比对；把它做成纯函数，测试才能钉住
// 「段落顺序」和「关键标题字符串」这两件最容易在重构里悄悄漂掉的事。
// ---------------------------------------------------------------------------

/// 一次 system 拼装用到的全部输入。
///
/// 每个字段都是「已经渲染好的文本片段」——这个类型不查库、不读时钟，
/// 所以它能被单测直接构造，也能被后台任务（主动消息）复用。
public struct SystemPromptInput {
    /// 人格文本（Android: `personaCardRepository.systemText(persona)`）。
    /// 有结构化人设卡时应当是人设卡渲染结果；iOS 的人设卡渲染还没移植，
    /// 调用方现在传 `persona.prompt`（Android 里那份 prompt 本身就是人设卡的镜像）。
    public var personaText: String
    /// 人格名字：只用于「你不是助手，你是 X 本人」这一句与反例里的第三人称提醒。空 = 不点名。
    public var personaName: String
    /// 世界预设那一段（`WorldPreset.renderForPrompt()`，人设引导 §1/§2）。
    public var worldBlock: String
    /// 「# 你此刻的状态」整段（时间 + 当前活动 + 他在做什么）。
    /// 空串 = 不加（老调用点 / 测试不必关心时间）。
    public var stateBlock: String
    /// 话风那一段（`SpeechStyle.renderForPrompt()`，人设引导 §3）。
    public var speechStyleBlock: String
    /// 「# 你们的关系」那一段（关系、亲密度、称呼）。
    public var relationshipBlock: String
    /// 「# 边界」那一段：可以覆盖，默认是 `PromptBuilder.boundarySection`。
    public var boundaryBlock: String
    /// 记忆块（Android: `memoryRepository.formatForPrompt(memories)`），空串表示这次没挑到记忆。
    public var memoriesBlock: String
    /// 会话滚动摘要块（`HistoryCompression.memoryBlock(session.historySummary, …)`）。
    public var earlierMemoryBlock: String
    /// 心情那一段（`MoodPrompt.line(state)`）。
    public var moodBlock: String
    /// 心情驱动的语气分寸（`MoodBehavior.promptLine(mood)`）。
    public var moodPaceBlock: String
    /// 「他此刻在做什么」那一段；为空表示不加（状态不该成为聊天里的障碍）。
    ///
    /// 0.12 起由 `stateBlock` 承担（它把时间、活动、在场合成一段）；
    /// 这个字段保留是为了老调用点与测试不用改签名 —— `stateBlock` 为空时仍然会用它。
    public var presenceBlock: String
    /// 周期性心情引导（`MoodPrompt.reflectionNudge`）；不该问的时候是空串。
    public var moodNudgeBlock: String
    /// 主动唤醒能力说明。
    public var proactiveCapability: String
    /// 工具能力说明（`ToolCatalog.capabilityPrompt(webEnabled:)`）。
    public var toolsCapability: String
    /// 「## 语言」小节的完整文本（模板由 App 层按界面语言从资源里取，见 `AppLanguage.languageDirective`）。
    /// 空串 = 不加这一节（老调用点与测试不用改签名）。
    public var languageDirective: String

    public init(
        personaText: String = "",
        personaName: String = "",
        worldBlock: String = "",
        stateBlock: String = "",
        speechStyleBlock: String = "",
        relationshipBlock: String = "",
        boundaryBlock: String = PromptBuilder.defaultBoundary,
        memoriesBlock: String = "",
        earlierMemoryBlock: String = "",
        moodBlock: String = "",
        moodPaceBlock: String = "",
        presenceBlock: String = "",
        moodNudgeBlock: String = "",
        proactiveCapability: String = ProactiveCapabilityPrompt.text,
        toolsCapability: String = ToolCatalog.capabilityPrompt(),
        languageDirective: String = ""
    ) {
        self.personaText = personaText
        self.personaName = personaName
        self.worldBlock = worldBlock
        self.stateBlock = stateBlock
        self.speechStyleBlock = speechStyleBlock
        self.relationshipBlock = relationshipBlock
        self.boundaryBlock = boundaryBlock
        self.memoriesBlock = memoriesBlock
        self.earlierMemoryBlock = earlierMemoryBlock
        self.moodBlock = moodBlock
        self.moodPaceBlock = moodPaceBlock
        self.presenceBlock = presenceBlock
        self.moodNudgeBlock = moodNudgeBlock
        self.proactiveCapability = proactiveCapability
        self.toolsCapability = toolsCapability
        self.languageDirective = languageDirective
    }
}

/// 「你可以主动联系对方」这一段。
///
/// 从 0.9.1 起真正的文案在 `Services/ProactiveRuleService.swift` 的 `ProactiveProtocol.capabilityPrompt`
/// （与 Android 的 `ProactiveProtocol.CAPABILITY_PROMPT` 逐字一致，代码块语言是 `yap-wake`）。
/// 这里保留一个同名入口，是为了不让 `PromptBuilder` 的调用点因为「文案搬家」而改一遍签名：
/// 文案只有一个来源，**协议与落库**属于 Proactive 模块。
public enum ProactiveCapabilityPrompt {

    public static let blockLanguage = ProactiveProtocol.blockLanguage

    public static let text: String = ProactiveProtocol.capabilityPrompt
}

public enum PromptBuilder {

    /// 一次回复最多带多少字的对话正文。与 Android 的 `CONTEXT_CHAR_BUDGET` 一致。
    public static let contextCharBudget = 28_000

    /// 装配上下文时最多从库里读多少条消息（Android: `CONTEXT_RECENT_LIMIT`）。
    public static let contextRecentLimit = 200

    /// 分段之间的固定分隔：一个显眼的横线，让「设定」与「规则」在长上下文里也分得开。
    static let sectionSeparator = "\n\n---\n"

    // MARK: - system

    /// 把各段拼起来。
    ///
    /// 顺序按 `spec/persona-guidance/README.md` §1（**规范：越靠前越强，顺序不可乱**）：
    ///
    /// ```
    /// # 你是谁       —— 身份、第一人称、不是助手
    /// # 你所在的世界  —— 世界预设（World Preset）
    /// # 你此刻的状态  —— 时间、当前活动/忙碌、心情、在场
    /// # 你怎么说话    —— 话风（tone/habits/avoid + 常用语）
    /// # 你们的关系    —— 与用户的关系、亲密度、称呼
    /// 记忆 / 滚动摘要  —— 「他记得什么」（不属于人设引导，紧跟关系之后）
    /// ---
    /// 主动唤醒能力 / 工具能力
    /// # 边界         —— 不暴露 AI、不旁白、不总结人设、不替用户决定
    /// 心情引导 / 语言
    /// ```
    ///
    /// 「边界」放在能力说明之后、最后的输出规则之前：它是**规则**，不是设定；
    /// 压在尾巴上模型更容易当成硬约束而不是「人物简介的一部分」。
    public static func systemPrompt(_ input: SystemPromptInput) -> String {
        var text = identityBlock(input.personaText, name: input.personaName)
        text += input.worldBlock
        text += stateSection(input.stateBlock, legacyPresence: input.presenceBlock)
        text += input.moodBlock
        text += input.moodPaceBlock
        text += input.speechStyleBlock
        text += input.relationshipBlock
        text += input.memoriesBlock
        text += input.earlierMemoryBlock
        text += sectionSeparator
        text += input.proactiveCapability
        text += "\n\n"
        text += input.toolsCapability
        text += boundaryBlock(input.boundaryBlock)
        text += input.moodNudgeBlock
        text += languageSection(input.languageDirective)
        return text
    }

    /// 「用对方的语言回答」这一节。
    ///
    /// 为什么放在最后一节：前面的段落都是「他是谁 / 他记得什么 / 他能做什么」，
    /// 「怎么说」属于输出规则，压在尾巴上模型更容易当成指令而不是设定。
    ///
    /// **指令本身随界面语言本地化**（与 Android `LanguageDirective.section` 对齐）：
    /// 中文界面发 `## 语言` + 中文说明，英文界面发 `## Language` + 英文说明。
    /// Kit 不读资源、也不读 `Locale`：模板由 App 层取好、拼好语言名再传进来，
    /// 这样「模板内容」归资源守卫管、「拼在 system 末尾」归这里管，两边都能单测。
    public static func languageSection(_ directive: String) -> String {
        let trimmed = directive.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        return "\n\n" + trimmed
    }

    // MARK: - 人设引导（0.12，`spec/persona-guidance/README.md`）

    /// 「# 边界」那一段（规范 §1.1 的七条强制规则里，属于「不许做什么」的那些）。
    ///
    /// 为什么写死在这里而不是让调用方传：这七条是**所有端一致**的硬约束，
    /// 让它可覆盖只会让某一端悄悄少一条；`SystemPromptInput.boundaryBlock` 只用于
    /// 特殊场景（比如主动消息要更短的一段），默认永远是这一段。
    ///
    /// 文案里刻意写出**反例**（§1.2）：模型对「不要写成 …」的服从度明显高于抽象要求。
    public static let defaultBoundary = PersonaPromptScaffold.boundariesText()

    /// 第一人称规则（§1.1 第 1 条）。`name` 空时不点名，避免出现「你是 本人」这种句子。
    ///
    /// 文案逐字来自 `spec/persona-guidance/scaffold.json` 的 `identity_body` /
    /// `identity_body_with_meta`：`meta`（年龄、职业这类补充）由调用方给，空时不带括号。
    public static func firstPersonRule(name: String, meta: String = "") -> String {
        PersonaPromptScaffold.identityLine(name: name, meta: meta)
    }

    /// 把「人格正文」包成 `# 你是谁` 一节；正文自己已经带了第一人称规则时不重复写。
    ///
    /// 为什么需要判断「已经带了」：`personaSection` 会带上规则，人设卡渲染的正文不会；
    /// 两者都可能从 App 层传进来，重复两遍规则会白烧 token 且显得啰嗦。
    public static func identityBlock(_ personaText: String, name: String) -> String {
        let body = personaText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return "" }
        if body.contains("第一人称") { return body }
        return "# 你是谁\n" + firstPersonRule(name: name) + "\n\n" + body
    }

    /// 世界预设那一段。
    ///
    /// 没有预设时**仍然出现**这一节、只写 `world_empty` 那一句（脚手架 `world_empty`）：
    /// 模型需要知道「这里没有设定」，而不是自己猜一个世界出来。
    public static func worldSection(_ world: WorldPreset?) -> String {
        let rendered = world?.renderForPrompt() ?? ""
        if !rendered.isEmpty { return "\n\n" + rendered }
        return "\n\n" + PersonaPromptScaffold.worldSection
            + "\n" + PersonaPromptScaffold.worldEmpty
    }

    /// 话风那一段（空风格返回空串）。
    public static func speechStyleSection(_ style: SpeechStyle?) -> String {
        guard let style else { return "" }
        let rendered = style.renderForPrompt()
        return rendered.isEmpty ? "" : "\n\n" + rendered
    }

    /// 「# 你们的关系」：称呼、关系、亲密度。
    ///
    /// 数据来源是结构化人设卡的 `identity.relation` / `voice.address` / `bond`，
    /// 再退回人格的一句话描述。全都为空就整节不出现（不写「你们关系很好」这种空话）。
    public static func relationshipSection(persona: PersonaEntity) -> String {
        var parts: [String] = []
        let card = PersonaCardCodec.decode(persona.personaCardJson)
        let relation = card.identity.relation.trimmingCharacters(in: .whitespacesAndNewlines)
        if !relation.isEmpty { parts.append("你和他：\(relation)。") }
        let address = card.voice.address.trimmingCharacters(in: .whitespacesAndNewlines)
        if !address.isEmpty { parts.append("你叫他「\(address)」。") }
        let bond = card.bond.trimmingCharacters(in: .whitespacesAndNewlines)
        if !bond.isEmpty { parts.append(bond) }
        let description = persona.description.trimmingCharacters(in: .whitespacesAndNewlines)
        if parts.isEmpty, !description.isEmpty { parts.append(description) }
        // 一句结构化关系都没有时用脚手架里的兜底（不能留一个只有标题的空段落）。
        if parts.isEmpty { parts.append(PersonaPromptScaffold.relationshipDefault) }
        return "\n\n" + PersonaPromptScaffold.relationshipSection + "\n"
            + parts.joined(separator: "\n")
    }

    /// 「# 你此刻的状态」整段：现在是几点、他在做什么、忙不忙。
    ///
    /// 规范 §6.3 的形状：
    /// `现在是{weekday HH:mm}。{activity 或「没在忙什么」}{；心情：…}{；已经睡了…}`
    /// 这里只负责「时间 + 活动 + 在场」；心情由 `MoodPrompt` 那两段跟在后面。
    public static func stateSection(
        now: Int64,
        activity: PersonaActivity?,
        presenceDetail: String? = nil,
        calendar: Calendar = ServiceTime.calendar()
    ) -> String {
        guard now > 0 else { return "" }
        // 「现在是周三 19:40。」逐字来自脚手架 `state_line`；`state_idle` 同理。
        var line = PersonaPromptScaffold.fill(PersonaPromptScaffold.stateLine, [
            "weekday": PromptClock.weekdayText(now, calendar: calendar),
            "time": PromptClock.clockText(now, calendar: calendar)
        ])
        if let activity, activity.isActive(now: now) {
            line += activity.renderStateLine(now: now, calendar: calendar)
        } else {
            line += PersonaPromptScaffold.stateIdle
        }
        var lines = [PersonaPromptScaffold.stateSection, line]
        let presence = (presenceDetail ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !presence.isEmpty { lines.append("（你自己的状态：\(presence)）") }
        return "\n\n" + lines.joined(separator: "\n")
    }

    /// system 里那一段：优先用渲染好的 `stateBlock`；老调用点只给了 `presenceBlock` 时保持原样。
    static func stateSection(_ rendered: String, legacyPresence: String) -> String {
        if !rendered.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return rendered }
        return legacyPresence
    }

    /// 把「边界」那一段拼进 system；传空串 = 整节不出现（只有测试会这么用）。
    public static func boundaryBlock(_ custom: String) -> String {
        let trimmed = custom.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "" : "\n\n" + trimmed
    }

    // MARK: - 人格小节（1.9）

    /// 把人格的自由文本提示词 + 五个结构化字段拼成「# 你是谁」下的一段。
    ///
    /// 为什么要有这一层：1.9 把「他是谁」拆成了可单独填的字段（性格 / 喜欢 / 习惯 / 过去 / 设定），
    /// 但用户仍然可以直接写一整段提示词。两者都在时，原则是「自由文本是主体，结构化字段是补充」：
    /// 主体先出现（模型的「自我」优先），小节跟在后面，空字段整节不出现——不占位、不留空标题。
    ///
    /// `proactiveLevel` 与 `memoryRetention` **刻意不进这里**：它们是行为参数（每天主动几次、
    /// 记忆淡得快不快），不是「他是谁」的描述。让他读到「我是 3 级主动」只会让语气变形。
    public static func personaSection(_ persona: PersonaEntity) -> String {
        let body = personaProfileBody(persona)
        guard !body.isEmpty else { return "" }
        return "# 你是谁\n" + firstPersonRule(name: persona.name) + "\n\n" + body
    }

    /// 只有「他是谁」的正文，不带 `## 你是谁` 这个标题 —— 给已经有自己标题的拼装器用
    /// （比如 `AgentPromptBuilder` 的「你是你自己」那段，它自己会写标题）。
    public static func personaProfileBody(_ persona: PersonaEntity) -> String {
        var lines: [String] = []
        let prompt = persona.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = persona.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let description = persona.description.trimmingCharacters(in: .whitespacesAndNewlines)

        if !prompt.isEmpty {
            lines.append(prompt)
        } else if !name.isEmpty {
            var lead = "你叫\(name)。"
            if !description.isEmpty { lead += description }
            lines.append(lead)
        }

        func appendSection(_ title: String, _ rawContent: String) {
            let content = rawContent.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !content.isEmpty else { return }
            lines.append("### \(title)")
            lines.append(content)
        }

        if !persona.traits.isEmpty {
            appendSection("性格", persona.traits.map { "- \($0)" }.joined(separator: "\n"))
        }
        if !persona.likes.isEmpty {
            appendSection("喜欢", persona.likes.map { "- \($0)" }.joined(separator: "\n"))
        }
        if !persona.behaviors.isEmpty {
            appendSection("习惯", persona.behaviors.map { "- \($0)" }.joined(separator: "\n"))
        }
        appendSection("过去", persona.backstory ?? "")
        appendSection("设定", persona.quirks ?? "")

        return lines.joined(separator: "\n\n")
    }

    /// 「他此刻在做什么」那一段。空 detail 返回空串（不加这一段）。
    public static func presenceBlock(detail: String?) -> String {
        let trimmed = (detail ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "" }
        return "\n\n## 你此刻的状态\n\(trimmed)"
    }

    // MARK: - 对话历史

    /// 把库里的消息变成要发出去的消息序列。
    ///
    /// - 已经被滚动摘要盖住的那部分不再逐字塞进来（它已经在 system 里那段「更早记得的」里了，
    ///   同一个意思说两遍只会让他混乱）；
    /// - 只带 USER / ASSISTANT，空的、失败态的都不要（失败气泡不是他真说过的话）。
    ///
    /// 图片消息：正文为空时补一句「（发来一张图片）」，保证模型至少知道发生过这件事；
    /// 真正的图片以 data URL 塞进 `WireMessage.images`（多模态报文已支持），
    /// 但**只带最近 `AttachmentsCodec.maxImageMessagesInContext` 条带图的消息**——
    /// base64 会让请求体膨胀 4/3，把整段历史里的图全带上等于把上下文预算烧在旧图上。
    /// 文件已经被清掉（换机、从备份接回）时 `imageResolver` 返回 nil，这条就只剩那句「发来一张图片」。
    public static func history(
        messages: [MessageEntity],
        summarizedUpTo: Int64?,
        imageResolver: ((MessageAttachment) -> String?)? = nil
    ) -> [WireMessage] {
        let cutoff = summarizedUpTo.flatMap { $0 > 0 ? $0 : nil }
        // 先筛出「真的要发给模型的那几条」，再定哪几条的图值得带上（与 Android 的
        // `buildContext` 同一顺序：先过滤、再决定给谁解码，别为已经被裁掉的消息解一遍图）。
        var visible: [MessageEntity] = []
        for message in messages {
            if let cutoff, message.createdAt <= cutoff { continue }
            guard message.role == .user || message.role == .assistant else { continue }
            guard message.status != .error else { continue }
            let hasAttachments = !(message.attachmentsJson ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            let content = message.content.trimmingCharacters(in: .whitespacesAndNewlines)
            if content.isEmpty && !hasAttachments { continue }
            visible.append(message)
        }

        let imageOwnerIds: Set<String> = {
            guard imageResolver != nil else { return [] }
            let owners = visible.filter { message in
                message.role == .user
                    && !(message.attachmentsJson ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
            return Set(owners.suffix(AttachmentsCodec.maxImageMessagesInContext).map(\.id))
        }()

        var result: [WireMessage] = []
        for message in visible {
            let content = message.content.trimmingCharacters(in: .whitespacesAndNewlines)

            var images: [String] = []
            if let imageResolver, imageOwnerIds.contains(message.id) {
                images = AttachmentsCodec.decode(message.attachmentsJson)
                    .filter { $0.isImage }
                    .prefix(AttachmentsCodec.maxImagesPerMessage)
                    .compactMap { imageResolver($0) }
            }

            result.append(WireMessage(
                role: message.role == .user ? "user" : "assistant",
                content: content.isEmpty ? "（发来一张图片）" : message.content,
                images: images
            ))
        }
        return result
    }

    /// 按字数预算从**最新**往前留（Android `trimHistory`）。
    ///
    /// 「至少留一条」是刻意的：最新那句就算超预算也必须发出去，否则这次请求没有意义。
    public static func trimHistory(_ history: [WireMessage], budget: Int = PromptBuilder.contextCharBudget) -> [WireMessage] {
        if history.isEmpty { return history }
        var kept: [WireMessage] = []
        var chars = 0
        for message in history.reversed() {
            if !kept.isEmpty && chars + message.content.count > budget { break }
            kept.insert(message, at: 0)
            chars += message.content.count
        }
        return kept
    }

    /// 一次请求要发的完整消息列表：一条 system + 裁剪过的历史。
    public static func wireMessages(system: String, history: [WireMessage],
                                    budget: Int = PromptBuilder.contextCharBudget) -> [WireMessage] {
        [WireMessage(role: "system", content: system)] + trimHistory(history, budget: budget)
    }
}
