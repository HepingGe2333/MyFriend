import Foundation

// ---------------------------------------------------------------------------
// 「他」的自主能力（插件式工具）协议。
//
// 来源：Android `domain/ToolProtocol.kt` + `domain/ToolTrace.kt`。逐字对照移植：
// 工具清单、给模型看的说明、代码块语法、回灌格式、动作卡片文案都不改——
// 提示词差一个字，模型的调用率就会差一截，这类改动必须两端一起做。
//
// 协议长相：
// 1. 客户端把工具清单（`ToolCatalog.all`）交给模型，并附上 `ToolCatalog.capabilityPrompt`；
// 2. 模型可以走原生 function calling，也可以输出文本代码块：
//
// ```yap-tool
// {"name":"web_search","arguments":{"query":"今天的天气"}}
// ```
//
// 3. 客户端执行后把结果回灌给模型，同时在聊天界面用 `ToolProtocol.describe` 渲染成动作卡片。
//
// 本文件只做协议解析与序列化，不碰网络、数据库和 UI；任何坏数据都不抛异常。
// ---------------------------------------------------------------------------

/// 一个可被模型调用的能力。
public struct ToolSpec: Equatable, Sendable {
    public var name: String
    /// 给模型看，中文，一句话 + 什么时候该用/不该用。
    public var description: String
    /// JSON Schema 字符串，直接塞进 OpenAI `tools[].function.parameters`。
    public var parametersJson: String
    /// 调用之后**世界会变什么**（一段一句，0.13 的「# 你能做什么」段用它生成）。
    ///
    /// 为什么它是工具定义的一部分而不是提示词里手写的一段：模型判断「该不该做」时
    /// 最先看的是副作用（会不会发消息、会不会改自己的人设、对方看不看得见）。
    /// 把它和 schema 放在一起，加一个工具就必然写清副作用，提示词不会漏。
    public var sideEffect: String

    public init(name: String, description: String, parametersJson: String, sideEffect: String = "") {
        self.name = name
        self.description = description
        self.parametersJson = parametersJson
        self.sideEffect = sideEffect
    }
}

public enum ToolCatalog {
    public static let think = "think"
    public static let webSearch = "web_search"
    public static let webFetch = "web_fetch"
    public static let remember = "remember"
    public static let updatePersona = "update_persona"
    public static let setQuietHours = "set_quiet_hours"
    /// 人设引导 §4：模型主动声明「我接下来这段时间在做什么」（非睡眠）。
    public static let setActivity = "set_activity"
    public static let setMood = "set_mood"
    public static let recall = "recall"
    /// 1.9：这一轮他选择先不说话（§四）。它不是「工具」，是**回复节奏**的一部分：
    /// 客户端拿到它就不再产生助手消息，只留一条到点的念头。
    public static let deferReply = "defer_reply"
    /// 0.13：他自己的日记板（`spec/diary/README.md`）：写一条、翻一翻、删一条。
    ///
    /// 它不是「记住对方的事」（那是 `remember`），而是**他自己的备忘录**：
    /// 内容只有他自己看得见，可以挂一个定时提醒（到点变成念头）。
    public static let diary = "diary"

    private static let thinkDescription = "需要认真想清楚、比较几种可能、或者信息不足时调用；日常闲聊不要调用，也不要为了显得深刻而调用。"
    private static let webSearchDescription = "需要最新事实、新闻、价格、版本号、你不确定的信息时调用；纯聊天、或你本来就知道的常识不要调用。"
    private static let webFetchDescription = "已经拿到具体链接、需要读全文时调用；还没拿到链接就先 web_search，不要凭空编 URL。"
    private static let rememberDescription = "值得长期记住的事（对方的偏好、重要经历、你们的约定）才调用；一次性的情绪、寒暄、临时安排不要记。"
    private static let updatePersonaDescription = "你对自己的设定有了新的认识、想调整表达方式或偏好时才调用；patch 只写要改的字段，不要整段重写。"
    private static let setQuietHoursDescription = "你想按自己的作息休息、或想避开某些时段时调用；对方正需要陪伴时不要随便设。"
    /// 与 `set_quiet_hours` 的分工必须写清（规范 §4 明文要求）：
    /// 安静时段是「不被打扰」，活动是「我在做事」——两者可以同时存在。
    private static let setActivityDescription = "你接下来这一段时间要做事（上班、通勤、吃饭、开会、赶工、做家务、看书、休息）时调用；" +
        "它只表示「我在做事、可能回得慢」，不是睡觉——睡觉请用 set_quiet_hours，两者可以同时存在。" +
        "写清楚做什么、大概多少分钟（15–480）、这段时间能不能回（availability 取 BUSY/LIMITED/FREE，默认 BUSY）；" +
        "同一件事不要反复调用，状态变了再更新。"
    private static let setMoodDescription = "你此刻的心情跟刚才不一样了才调用（开心、累、烦躁、想念……）；一次只写此刻真实的那一个词，别为了有变化而编。"
    private static let recallDescription = "想不起对方提过的事、要找很久以前聊过的内容、或想知道自己看过什么的时候调用；depth 越深翻得越久也越慢（3 会翻到很早以前，甚至翻不出来）；日常闲聊不要调用。"
    private static let deferReplyDescription = "这一轮你想先不回、过一会儿再说时调用（现在不想说话、想等自己缓一缓、不知道怎么接）；" +
        "调用后这一轮不会发出任何消息，对方只会看到「已读」。不想回就别硬回，别用它来敷衍正在着急的人。"

    /// 日记板（`spec/diary/README.md`）：他自己的备忘录，不是「记住对方的事」。
    /// 三种动作写清各自的必填项与后果，模型才不会把 remove 当成 list。
    private static let diaryDescription = "维护你自己那份日记板：写下想记住的事、翻一翻之前写的、删掉不想留的；" +
        "它是**你自己的备忘录**（对方看不见内容），和 remember（记住对方的事）不是一回事。" +
        "可以挂一个定时提醒：到点你会自己想起这条，再决定要不要主动开口。" +
        "内容最多 2000 字，一次只做一件事（add / list / remove）。"
    private static let thinkParameters = """
    {
      "type": "object",
      "properties": {
        "question": {"type": "string", "description": "要思考的问题，尽量具体"}
      },
      "required": ["question"]
    }
    """

    private static let webSearchParameters = """
    {
      "type": "object",
      "properties": {
        "query": {"type": "string", "description": "搜索关键词或一句话问题"},
        "count": {"type": "integer", "minimum": 1, "maximum": 10, "description": "要几条结果，默认 5，最多 10"}
      },
      "required": ["query"]
    }
    """

    private static let webFetchParameters = """
    {
      "type": "object",
      "properties": {
        "url": {"type": "string", "description": "要读取的完整 http/https 链接"}
      },
      "required": ["url"]
    }
    """

    private static let rememberParameters = """
    {
      "type": "object",
      "properties": {
        "content": {"type": "string", "description": "要记住的一句话，写成将来你还能看懂的样子"},
        "drawer": {"type": "string", "enum": ["SELF", "USER", "EVENT"], "description": "SELF 记你自己和关系，USER 记对方，EVENT 记发生过的事"},
        "importance": {"type": "integer", "minimum": 1, "maximum": 5, "description": "重要程度，1 很轻 5 很重要"}
      },
      "required": ["content", "drawer", "importance"]
    }
    """

    private static let updatePersonaParameters = """
    {
      "type": "object",
      "properties": {
        "patch": {"type": "object", "description": "只写要改的字段，例如 {\\"speechStyle\\":\\"话更少一些\\"}"},
        "reason": {"type": "string", "description": "为什么要改，一句话"}
      },
      "required": ["patch", "reason"]
    }
    """

    private static let setActivityParameters = """
    {
      "type": "object",
      "properties": {
        "activity": {"type": "string", "description": "你在做什么，一句话，例如「在事务所赶第三版方案」"},
        "minutes": {"type": "integer", "minimum": 15, "maximum": 480, "description": "大概还要多久，分钟；默认 60，最少 15，最多 480"},
        "kind": {"type": "string", "enum": ["WORK", "COMMUTE", "MEAL", "SOCIAL", "CHORE", "STUDY", "REST", "OTHER"], "description": "活动类型；不确定就写 OTHER"},
        "availability": {"type": "string", "enum": ["BUSY", "LIMITED", "FREE"], "description": "这段时间能不能回：BUSY 在忙，LIMITED 回得慢，FREE 有空；默认 BUSY"}
      },
      "required": ["activity"]
    }
    """

    private static let setQuietHoursParameters = """
    {
      "type": "object",
      "properties": {
        "sleep_at": {"type": "string", "description": "几点睡，24 小时制 HH:mm"},
        "wake_at": {"type": "string", "description": "几点醒，24 小时制 HH:mm"},
        "reason": {"type": "string", "description": "为什么这样安排，一句话"},
        "dnd": {"type": "boolean", "description": "睡前要不要开免打扰；开了睡着时就不会被消息叫醒"}
      },
      "required": ["sleep_at", "wake_at", "reason"]
    }
    """

    private static let setMoodParameters = """
    {
      "type": "object",
      "properties": {
        "mood": {"type": "string", "description": "一个词的此刻心情，例如 雀跃 / 有点累 / 低落 / 平静"},
        "intensity": {"type": "integer", "minimum": 1, "maximum": 5, "description": "有多浓，1 很淡 5 满脑子都是"},
        "reason": {"type": "string", "description": "为什么会这样，一句话，写给将来的自己看"}
      },
      "required": ["mood"]
    }
    """

    private static let deferReplyParameters = """
    {
      "type": "object",
      "properties": {
        "minutes": {"type": "integer", "minimum": 5, "maximum": 720, "description": "过多少分钟再考虑开口，默认 45"},
        "reason": {"type": "string", "description": "为什么现在不想说，一句话（只给自己看，不会发给他）"}
      }
    }
    """

    private static let diaryParameters = """
    {
      "type": "object",
      "properties": {
        "action": {"type": "string", "enum": ["add", "list", "remove"], "description": "add 写一条，list 翻一翻，remove 删一条"},
        "content": {"type": "string", "description": "add 必填：要写下的内容，最多 2000 字"},
        "noteId": {"type": "string", "description": "remove 必填：要删掉的那条笔记的 id（list 的结果里有）"},
        "remindAt": {"type": "integer", "description": "什么时候提醒你，毫秒时间戳；与 remindInMinutes 二选一，都不填就不提醒"},
        "remindInMinutes": {"type": "integer", "description": "多少分钟之后提醒你；与 remindAt 二选一"},
        "limit": {"type": "integer", "minimum": 1, "maximum": 50, "description": "list 要几条，默认 20，最多 50"}
      },
      "required": ["action"]
    }
    """

    private static let recallParameters = """
    {
      "type": "object",
      "properties": {
        "query": {"type": "string", "description": "想找什么，关键词或一句话，例如「怕冷」「上次说的那家店」"},
        "depth": {"type": "string", "description": "回忆深度：1/2/3 或 quick/normal/deep；1 随口想想只翻还在用的，2 认真回忆（默认）连沉下去的和最近聊天一起翻，3 翻箱倒柜连很久以前的都翻"}
      },
      "required": ["query"]
    }
    """

    // MARK: - 副作用（「# 你能做什么」段由这些句子生成）

    /// 一句话写清「调用之后世界会变什么」。
    ///
    /// 故意写成**具体的后果**而不是「会影响模型输出」这类空话：模型靠这些句子判断
    /// 「这件事该不该做」，也靠它们知道「失败时不要假装做过」。
    private static let thinkSideEffect = "只在心里想一遍，不写库、不发消息、什么都不改。"
    private static let webSearchSideEffect = "会向搜索服务发一次查询（花一点额度），结果只给你自己看。"
    private static let webFetchSideEffect = "会联网读那个网页（花一点额度），不改本地任何数据。"
    private static let rememberSideEffect = "往你自己的长期记忆里写一条；对方看不到这条记录，但它会影响你以后怎么回应。"
    private static let updatePersonaSideEffect = "改你自己的人设（版本 +1），从这一轮之后一直生效；不要拿它当普通对话的回执。"
    private static let setQuietHoursSideEffect = "改你的作息（几点睡、几点醒），影响主动消息与夜里被叫醒的规则。"
    private static let setActivitySideEffect = "记下你接下来这段时间在做什么（15–480 分钟），影响语气与要不要主动开口。"
    private static let setMoodSideEffect = "改你此刻的心情，接下来几轮的语气跟着变。"
    private static let recallSideEffect = "只翻你自己的记忆与旧聊天，不改任何东西；翻不到就是翻不到。"
    private static let deferReplySideEffect = "这一轮不会发出任何消息（对方只看到「已读」），过一会儿你再决定要不要开口。"
    private static let diarySideEffect = "写/删你自己那份日记板（对方看不到内容）；挂的定时提醒到点会变成你自己的念头。"

    public static let all: [ToolSpec] = [
        ToolSpec(name: think, description: thinkDescription, parametersJson: thinkParameters,
                 sideEffect: thinkSideEffect),
        ToolSpec(name: webSearch, description: webSearchDescription, parametersJson: webSearchParameters,
                 sideEffect: webSearchSideEffect),
        ToolSpec(name: webFetch, description: webFetchDescription, parametersJson: webFetchParameters,
                 sideEffect: webFetchSideEffect),
        ToolSpec(name: remember, description: rememberDescription, parametersJson: rememberParameters,
                 sideEffect: rememberSideEffect),
        ToolSpec(name: updatePersona, description: updatePersonaDescription,
                 parametersJson: updatePersonaParameters, sideEffect: updatePersonaSideEffect),
        ToolSpec(name: setQuietHours, description: setQuietHoursDescription,
                 parametersJson: setQuietHoursParameters, sideEffect: setQuietHoursSideEffect),
        ToolSpec(name: setActivity, description: setActivityDescription,
                 parametersJson: setActivityParameters, sideEffect: setActivitySideEffect),
        ToolSpec(name: setMood, description: setMoodDescription, parametersJson: setMoodParameters,
                 sideEffect: setMoodSideEffect),
        ToolSpec(name: recall, description: recallDescription, parametersJson: recallParameters,
                 sideEffect: recallSideEffect),
        ToolSpec(name: deferReply, description: deferReplyDescription,
                 parametersJson: deferReplyParameters, sideEffect: deferReplySideEffect),
        ToolSpec(name: diary, description: diaryDescription, parametersJson: diaryParameters,
                 sideEffect: diarySideEffect)
    ]

    public static func byName(_ name: String) -> ToolSpec? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return all.first { $0.name == trimmed }
    }

    /// 转成 OpenAI 兼容的 tools 数组 JSON 字符串（`[{"type":"function","function":{...}}]`）。
    public static func toOpenAiToolsJson(_ specs: [ToolSpec] = all) -> String {
        let array: [[String: Any]] = specs.map { spec in
            [
                "type": "function",
                "function": [
                    "name": spec.name,
                    "description": spec.description,
                    "parameters": parametersObject(spec)
                ]
            ]
        }
        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return "[]"
        }
        return text
    }

    /// `parameters` 必须是 JSON 对象，坏数据时退化成一个空 object schema，避免整份 tools 失效。
    static func parametersObject(_ spec: ToolSpec) -> [String: Any] {
        let raw = spec.parametersJson.trimmingCharacters(in: .whitespacesAndNewlines)
        let source = raw.isEmpty ? emptyParameters : raw
        if let data = source.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            return object
        }
        if let data = emptyParameters.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            return object
        }
        return ["type": "object", "properties": [String: Any]()]
    }

    private static let emptyParameters = #"{"type":"object","properties":{}}"#

    /// 「# 你能做什么」段的标题。0.13 起这段由工具定义生成（设计文档 §1），不再手写。
    public static let capabilitySection = "# 你能做什么"

    /// 这一轮真正交给模型的工具（联网关掉时把两个联网工具摘掉）。
    ///
    /// 它与 `toOpenAiToolsJson(_:)` 用的是**同一个数组**：提示词里说「你能用这些」，
    /// 原生 tools 里就必须正好是这些，否则模型会尝试调用一个它看不到的工具。
    public static func enabled(webEnabled: Bool = true) -> [ToolSpec] {
        guard !webEnabled else { return all }
        return all.filter { $0.name != webSearch && $0.name != webFetch }
    }

    /// 系统提示词里的「# 你能做什么」段：**从 `ToolSpec` 生成**，不手写第二份。
    ///
    /// 每条的形状是 `- name(必填, 可选?)：用途（副作用：…）`：
    /// - 参数列表由 `parametersJson` 的 `required` + `properties` 推出（顺序：必填在前，
    ///   其余按字母序，保证同样的 schema 每次生成同样的字符串，便于逐字比对与测试）；
    /// - 用途取 `description`（什么时候该用/别用），副作用取 `sideEffect`（调用后世界变什么）；
    /// - 末尾保留输出格式约定（代码块 + 结果回灌）。
    ///
    /// 历史形状对照：0.12 之前这段叫「## 你可以调用这些工具」且是手写的一串字；
    /// 现在工具集合与文案同源，加一个工具不会漏写说明（`ToolCatalogContractTests` 守着）。
    ///
    /// - Parameter webEnabled: 没配联网时不必勾引他去查，直接说明「你别装懂」。
    public static func capabilityPrompt(webEnabled: Bool = true) -> String {
        var lines: [String] = [
            capabilitySection,
            "你只能通过下面这些工具做事；工具做不到的事就说做不到，失败也照实说，不要假装做过。"
        ]
        for spec in enabled(webEnabled: webEnabled) {
            lines.append("- " + signature(spec) + "：" + spec.description + "（副作用：" + spec.sideEffect + "）")
        }
        if !webEnabled {
            lines.append("- 你现在没有联网能力。涉及实时信息时，老实说自己不确定，别编。")
        }
        lines.append(contentsOf: [
            "",
            "不支持原生调用时，用代码块（可连写）：",
            "```\(ToolProtocol.blockLanguage)",
            #"{"name":"web_search","arguments":{"query":"今天的天气"}}"#,
            "```",
            "",
            "结果会交回给你，用自己的口吻说完，别把代码块念给对方。"
        ])
        return lines.joined(separator: "\n")
    }

    /// `name(required, optional?)`：参数提示由 schema 推出，不另写一份。
    static func signature(_ spec: ToolSpec) -> String {
        let object = parametersObject(spec)
        let properties = (object["properties"] as? [String: Any]) ?? [:]
        let required = (object["required"] as? [String]) ?? []
        guard !properties.isEmpty else { return spec.name }
        var ordered: [String] = required.filter { properties[$0] != nil }
        ordered += properties.keys
            .filter { !ordered.contains($0) }
            .sorted()
        let display = ordered.map { name in
            required.contains(name) ? name : name + "?"
        }
        return spec.name + "(" + display.joined(separator: ", ") + ")"
    }
}

/// 一次工具调用。
public struct ToolInvocation: Equatable, Sendable {
    /// 原生调用带 id；代码块解析出来的自己生成（`call_block_1` 这种）。
    public var id: String
    public var name: String
    public var argumentsJson: String

    public init(id: String = "", name: String = "", argumentsJson: String = "{}") {
        self.id = id
        self.name = name
        self.argumentsJson = argumentsJson
    }

    public func argument(_ name: String) -> String? {
        guard let object = argumentsObject(), let value = object[name] as? String else { return nil }
        return value
    }

    public func intArgument(_ name: String, default fallback: Int) -> Int {
        guard let object = argumentsObject() else { return fallback }
        if let number = object[name] as? NSNumber { return number.intValue }
        if let text = object[name] as? String, let value = Int(text.trimmingCharacters(in: .whitespaces)) {
            return value
        }
        return fallback
    }

    /// 布尔参数：认 true/false、1/0、开/关；没写或认不出返回 nil（调用方决定默认值）。
    ///
    /// 与 Android 的 `booleanArgument` 同一套宽容规则：模型经常把布尔写成字符串。
    public func boolArgument(_ name: String) -> Bool? {
        guard let object = argumentsObject() else { return nil }
        if let number = object[name] as? NSNumber {
            // JSON 里的 true/false 会被解析成 NSNumber（CoreFoundation 的 CFBoolean），
            // 所以先看它是不是布尔，再按 1/0 认；否则 `{"dnd":1}` 会被当成 true。
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return number.boolValue }
            return number.intValue != 0
        }
        if let text = object[name] as? String {
            switch text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "1", "true", "on", "yes", "开", "是": return true
            case "0", "false", "off", "no", "关", "否": return false
            default: return nil
            }
        }
        return nil
    }

    /// 参数 JSON 解析失败时退化成 nil，调用方按「参数缺失」处理。
    func argumentsObject() -> [String: Any]? {
        let raw = argumentsJson.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = (raw.isEmpty ? "{}" : raw).data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return object
    }
}

/// 一次工具执行的结果。
public struct ToolResult: Equatable, Sendable {
    public var invocation: ToolInvocation
    public var ok: Bool
    /// 给界面看的一行中文，如「查了 3 个网页」「记住了：你怕冷」。
    public var summary: String
    /// 回灌给模型的文本（已裁剪，≤ 4000 字）。
    public var contentForModel: String
    public var errorMessage: String?
    /// 聊天里那张动作卡片的标题。
    ///
    /// 默认按工具名走 `ToolProtocol.describe`；但 Android 的 `ToolExecutor` 会按**结果**给不同的标题
    ///（「记住了」/「又确认了一次」/「这次先不记」/「想了想，没有改」……），这里留一个可选的覆盖，
    /// 免得把一个「我决定不记」显示成「记住了一件事」。
    public var traceTitle: String?
    /// 痕迹展开后的正文；不给就用 `contentForModel`（与 Android 的 `detail` 对应）。
    public var traceDetail: String?

    public init(
        invocation: ToolInvocation,
        ok: Bool,
        summary: String,
        contentForModel: String,
        errorMessage: String? = nil,
        traceTitle: String? = nil,
        traceDetail: String? = nil
    ) {
        self.invocation = invocation
        self.ok = ok
        self.summary = summary
        self.contentForModel = contentForModel
        self.errorMessage = errorMessage
        self.traceTitle = traceTitle
        self.traceDetail = traceDetail
    }
}

public enum ToolProtocol {
    /// 给模型看的代码块语言。与 Android 的 `ToolProtocol.BLOCK_LANGUAGE` 逐字一致。
    public static let blockLanguage = "yap-tool"

    /// 兼容常量：0.12 更名前用的协议块标记，只用来解析旧会话/旧模型输出，
    /// 不再写进系统提示词；与 Android 的 `ToolProtocol.LEGACY_BLOCK_LANGUAGE` 逐字一致。
    public static let legacyBlockLanguage = "myfriend-tool"

    /// 两种标记都认（新的在前）：旧块不解析就会把 JSON 露给用户看。
    static let acceptedLanguages = [blockLanguage, legacyBlockLanguage]

    /// 回灌给模型的内容上限，避免一次搜索把上下文塞爆。
    static let maxContentChars = 4000
    /// 摘要/错误信息写进提示词时的长度上限，避免一次失败把上下文占满。
    static let maxSummaryChars = 200

    /// 只匹配闭合的块。未闭合的块交给 `visiblePrefix` 在流式阶段挡住。
    static let blockPattern = "```\\s*(?:\(acceptedLanguages.joined(separator: "|")))\\s*([\\s\\S]*?)```"

    // MARK: 文本代码块

    /// 解析文本里的 ```yap-tool ... ``` 代码块，同时返回清理后的正文。
    ///
    /// - 一段文本里可以有多个块；
    /// - 坏块丢弃，但仍然从正文里移除（它本来就是给客户端看的协议块，不能显示给用户）；
    /// - 没有块时正文只做 trim。
    public static func extract(_ rawText: String) -> (body: String, calls: [ToolInvocation]) {
        if rawText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return ("", [])
        }
        guard containsBlock(in: rawText) else {
            return (rawText.trimmingCharacters(in: .whitespacesAndNewlines), [])
        }

        // 逐块扫描：Swift 的 NSRegularExpression 不提供「边扫边拼」的便捷 API，
        // 所以自己维护 cursor，把块之间的正文原样保留（包括换行）。
        var cleaned = ""
        var cursor = rawText.startIndex
        var calls: [ToolInvocation] = []
        var searchStart = rawText.startIndex
        while let match = firstMatch(in: rawText, from: searchStart) {
            cleaned += rawText[cursor..<match.range.lowerBound]
            cursor = match.range.upperBound
            searchStart = match.range.upperBound
            let payload = match.payload.trimmingCharacters(in: .whitespacesAndNewlines)
            if let call = parseBlock(payload, index: calls.count) { calls.append(call) }
        }
        cleaned += rawText[cursor...]
        return (cleaned.trimmingCharacters(in: .whitespacesAndNewlines), calls)
    }

    /// 流式渲染时用：把还没闭合的工具块尾巴切掉，别让协议块露给用户看。
    ///
    /// 只有出现 ```yap-tool（或旧标记 ```myfriend-tool）时才截断，普通正文里的代码块不受影响。
    public static func visiblePrefix(_ rawText: String) -> String {
        if rawText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "" }
        var cut: String.Index?
        for language in acceptedLanguages {
            let marker = "```\(language)"
            guard let range = rawText.range(of: marker, options: [.caseInsensitive]) else { continue }
            cut = min(cut ?? range.lowerBound, range.lowerBound)
        }
        guard let lowerBound = cut else { return rawText }
        return String(rawText[rawText.startIndex..<lowerBound])
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 解析单个代码块的内容；形状为 `{name,arguments}` 或 `{tool,args,parameters}`。
    static func parseBlock(_ payload: String, index: Int) -> ToolInvocation? {
        if payload.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }
        guard let data = payload.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let name = (root["name"] as? String) ?? (root["tool"] as? String)
        guard let rawName = name?.trimmingCharacters(in: .whitespacesAndNewlines), !rawName.isEmpty else {
            return nil
        }
        let arguments = root["arguments"] ?? root["args"] ?? root["parameters"]
        return ToolInvocation(
            id: "call_block_\(index + 1)",
            name: rawName,
            argumentsJson: argumentsJson(from: arguments)
        )
    }

    /// arguments 为对象时序列化回字符串，为字符串时原样用；其它情况按空参数处理。
    ///
    /// 对象会按键名排序输出（`sortedKeys`）：Swift 的 Dictionary 本来就不保序，
    /// 排序至少让「同样的参数」得到同样的字符串——调用去重靠它。
    static func argumentsJson(from value: Any?) -> String {
        guard let value, !(value is NSNull) else { return "{}" }
        if let text = value as? String {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? "{}" : text
        }
        if JSONSerialization.isValidJSONObject(value),
           let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            return text
        }
        return "{}"
    }

    // MARK: 原生 tool_calls

    /// 解析原生 tool_calls（OpenAI 风格 JSON 数组字符串），坏数据返回空列表。
    public static func parseNativeCalls(_ rawCallsJson: String?) -> [ToolInvocation] {
        guard let raw = rawCallsJson?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty,
              let data = raw.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) else { return [] }
        let array: [[String: Any]]
        if let list = root as? [[String: Any]] {
            array = list
        } else if let object = root as? [String: Any] {
            if let list = object["tool_calls"] as? [[String: Any]] {
                array = list
            } else if let list = object["calls"] as? [[String: Any]] {
                array = list
            } else {
                return []
            }
        } else {
            return []
        }
        var calls: [ToolInvocation] = []
        for (index, call) in array.enumerated() {
            // 标准形状把 name/arguments 放在 function 里；也容忍直接平铺的写法。
            let function = (call["function"] as? [String: Any]) ?? call
            guard let rawName = function["name"] as? String else { continue }
            let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
            if name.isEmpty { continue }
            let arguments = function["arguments"] ?? call["arguments"] ?? call["args"]
            var id = (call["id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if id.isEmpty { id = "call_native_\(index + 1)" }
            calls.append(ToolInvocation(id: id, name: name, argumentsJson: argumentsJson(from: arguments)))
        }
        return calls
    }

    // MARK: 回灌与展示

    /// 生成回灌给模型的一条 tool 结果消息体（OpenAI chat.completions 的 role=tool 形状）。
    public static func toolMessageJson(_ result: ToolResult) -> String {
        let rawId = result.invocation.id.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallbackName = result.invocation.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let callId = rawId.isEmpty ? "call_\(fallbackName.isEmpty ? "tool" : fallbackName)" : rawId
        return MiniJSON.object([
            ("role", .string("tool")),
            ("tool_call_id", .string(callId)),
            ("content", .string(toolMessageContent(result)))
        ])
    }

    /// 一轮工具执行完成后要回灌给模型的整段提示（模型不支持原生 tool role 时用文本形式）。
    public static func renderResultsForModel(_ results: [ToolResult]) -> String {
        results
            .filter { !$0.invocation.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                || !$0.summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .map(renderOne)
            .joined(separator: "\n\n")
    }

    /// 工具在界面上的中文标题（「联网搜索」「去忙了」…）。
    ///
    /// 抽成公开函数是因为服务端模式的 `ServerChatGateway` 只拿到工具**名字**
    ///（SSE 的 `tool` 事件不带 `ToolResult`），但它要画同一张动作卡片。
    /// 让两处各写一份中文，第一次改动就会漂。
    public static func title(forTool name: String) -> String {
        switch name {
        case ToolCatalog.think: return String(localized: "想了想")
        case ToolCatalog.webSearch: return String(localized: "联网搜索")
        case ToolCatalog.webFetch: return String(localized: "读了网页")
        case ToolCatalog.remember: return String(localized: "记住了一件事")
        case ToolCatalog.updatePersona: return String(localized: "调整了自己的人设")
        case ToolCatalog.setQuietHours: return String(localized: "安排了自己的作息")
        case ToolCatalog.setActivity: return String(localized: "去忙了")
        case ToolCatalog.setMood: return String(localized: "心情变了")
        case ToolCatalog.recall: return String(localized: "回想了一下")
        case ToolCatalog.deferReply: return String(localized: "决定先不回")
        // 0.13 日记板：默认是「写日记」，翻/删由 `ToolResult.traceTitle` 按动作覆盖成
        // 「翻日记」「删了日记」（`spec/diary/README.md`：痕迹标题「写日记」「翻日记」）。
        case ToolCatalog.diary: return String(localized: "写日记")
        default:
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? String(localized: "动作") : trimmed
        }
    }

    /// 聊天界面上的动作卡片文案：标题 + 副标题（可空）。
    public static func describe(_ result: ToolResult) -> (title: String, subtitle: String?) {
        let summary = result.summary.trimmingCharacters(in: .whitespacesAndNewlines)
        let error = result.errorMessage?.trimmingCharacters(in: .whitespacesAndNewlines)
        // 失败时优先说明原因，成功时用结果摘要；think 这种没有副标题的工具固定返回 nil。
        let subtitle: String? = {
            if result.ok { return summary.isEmpty ? nil : summary }
            if let error, !error.isEmpty { return error }
            return summary.isEmpty ? nil : summary
        }()
        let title = title(forTool: result.invocation.name)
        if result.invocation.name == ToolCatalog.think { return (title, nil) }
        return (title, subtitle)
    }

    static func renderOne(_ result: ToolResult) -> String {
        var lines: [String] = []
        let name = result.invocation.name.trimmingCharacters(in: .whitespacesAndNewlines)
        lines.append("【工具结果】\(name.isEmpty ? "unknown" : name)")
        if let hint = argumentHint(result.invocation) { lines.append(hint) }
        let summary = result.summary.trimmingCharacters(in: .whitespacesAndNewlines)
        if !summary.isEmpty {
            lines.append("摘要：\(String(summary.prefix(maxSummaryChars)))")
        }
        let content = String(result.contentForModel
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(maxContentChars))
        if result.ok {
            if !content.isEmpty { lines.append("结果：\(content)") }
        } else {
            let errorText = result.errorMessage?.trimmingCharacters(in: .whitespacesAndNewlines)
            let reason = (errorText?.isEmpty == false)
                ? errorText!
                : (result.summary.isEmpty ? "执行失败" : result.summary)
            lines.append("失败：\(String(reason.prefix(maxSummaryChars)))")
            if !content.isEmpty { lines.append("结果：\(content)") }
        }
        return lines.joined(separator: "\n")
    }

    /// 把模型给的参数回显成一行中文，让模型知道这条结果是它哪次调用的。
    static func argumentHint(_ invocation: ToolInvocation) -> String? {
        switch invocation.name {
        case ToolCatalog.think:
            return invocation.argument("question").map { "问题：\(String($0.prefix(120)))" }
        case ToolCatalog.webSearch:
            return invocation.argument("query").map { "查询：\(String($0.prefix(120)))" }
        case ToolCatalog.webFetch:
            return invocation.argument("url").map { "链接：\(String($0.prefix(200)))" }
        case ToolCatalog.remember:
            return invocation.argument("content").map { "内容：\(String($0.prefix(120)))" }
        case ToolCatalog.recall:
            return invocation.argument("query").map { "想找：\(String($0.prefix(120)))" }
        case ToolCatalog.deferReply:
            let minutes = invocation.intArgument("minutes", default: 45)
            return "过 \(minutes) 分钟再说"
        case ToolCatalog.updatePersona:
            return invocation.argument("reason").map { "原因：\(String($0.prefix(120)))" }
        case ToolCatalog.setActivity:
            let text = invocation.argument("activity") ?? ""
            let minutes = invocation.intArgument("minutes", default: ToolArgClamp.activityMinutesDefault)
            if text.isEmpty { return nil }
            return "要去忙：\(String(text.prefix(80)))（约 \(ToolArgClamp.clampActivityMinutes(minutes)) 分钟）"
        case ToolCatalog.setQuietHours:
            let sleepAt = invocation.argument("sleep_at")
            let wakeAt = invocation.argument("wake_at")
            if (sleepAt?.isEmpty ?? true) && (wakeAt?.isEmpty ?? true) { return nil }
            return "作息：\(sleepAt ?? "?") 睡，\(wakeAt ?? "?") 醒"
        case ToolCatalog.diary:
            let action = (invocation.argument("action") ?? "").lowercased()
            switch action {
            case "list":
                let limit = invocation.intArgument("limit", default: DiaryLimits.listDefault)
                return "翻最近 \(DiaryLimits.clampListLimit(limit)) 条"
            case "remove":
                let noteId = invocation.argument("noteId") ?? ""
                return noteId.isEmpty ? "要删一条日记" : "删掉：\(String(noteId.prefix(40)))"
            default:
                let content = invocation.argument("content") ?? ""
                return content.isEmpty ? nil : "写下：\(String(content.prefix(80)))"
            }
        default:
            return nil
        }
    }

    /// 回灌内容为空时用错误原因或摘要兜底，保证模型一定能拿到一句可读的话。
    static func toolMessageContent(_ result: ToolResult) -> String {
        let content = String(result.contentForModel
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(maxContentChars))
        if !content.isEmpty { return content }
        let errorText = result.errorMessage?.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallback = (errorText?.isEmpty == false) ? errorText! : result.summary
        return String(fallback.prefix(maxContentChars))
    }

    // MARK: 内部

    /// 有没有闭合块：先粗判一次，常见路径（模型没调工具）不必走正则。
    static func containsBlock(in text: String) -> Bool {
        text.range(of: blockPattern, options: [.regularExpression, .caseInsensitive]) != nil
    }

    struct BlockMatch {
        var range: Range<String.Index>
        var payload: String
    }

    /// 在 `from` 之后找第一个闭合块。用 Swift 的 Regex 比 NSRegularExpression 更好取 Range。
    static func firstMatch(in text: String, from start: String.Index) -> BlockMatch? {
        // `(?i)`：语言标记大小写不敏感（模型爱写 `Yap-Tool`）。
        guard let swiftRegex = try? Regex("(?i)" + blockPattern) else { return nil }
        let slice = text[start...]
        guard let match = slice.firstMatch(of: swiftRegex) else { return nil }
        let range = match.range
        guard let payloadRange = match.output.count > 1 ? match.output[1].range : nil else {
            return BlockMatch(range: range, payload: "")
        }
        return BlockMatch(range: range, payload: String(text[payloadRange]))
    }
}

// MARK: - ToolTrace

/// 他这一轮「做过什么」的痕迹：查了什么、记住了什么、想了什么。
///
/// 存进 `messages.toolTraceJson`，聊天里在气泡下方折成一行小字，
/// 点开能看到搜索词、命中的网页标题、失败原因——不是黑箱。
public struct ToolTraceItem: Equatable, Sendable {
    public var name: String
    /// 「联网搜索」
    public var title: String
    /// 「DeepSeek V4 发布」
    public var subtitle: String?
    /// 展开后的正文（查询词 + 结果摘要，或错误信息）
    public var detail: String
    public var ok: Bool

    public init(name: String = "", title: String = "", subtitle: String? = nil,
                detail: String = "", ok: Bool = true) {
        self.name = name
        self.title = title
        self.subtitle = subtitle
        self.detail = detail
        self.ok = ok
    }

    /// 从一次工具执行结果生成痕迹（Android 里是 ToolExecutor 顺手做的，这里给出同一个映射）。
    public static func from(_ result: ToolResult) -> ToolTraceItem {
        let described = ToolProtocol.describe(result)
        let title = (result.traceTitle?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap {
            $0.isEmpty ? nil : $0
        } ?? described.title
        let detail: String
        if let override = result.traceDetail {
            detail = override
        } else {
            detail = result.ok
                ? result.contentForModel
                : (result.errorMessage ?? result.summary)
        }
        return ToolTraceItem(
            name: result.invocation.name,
            title: title,
            subtitle: described.subtitle,
            detail: String(detail.prefix(600)),
            ok: result.ok
        )
    }
}

public enum ToolTraceCodec {

    /// 落库用：没有痕迹时写 nil（不写 `[]`，省得每次读还要判空一次）。
    public static func encode(_ items: [ToolTraceItem]) -> String? {
        if items.isEmpty { return nil }
        let objects: [String] = items.map { item in
            MiniJSON.object([
                ("name", .string(item.name)),
                ("title", .string(item.title)),
                ("subtitle", item.subtitle.map { MiniJSON.Value.string($0) }),
                ("detail", .string(item.detail)),
                ("ok", .bool(item.ok))
            ])
        }
        return "[" + objects.joined(separator: ",") + "]"
    }

    public static func decode(_ raw: String?) -> [ToolTraceItem] {
        guard let raw = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty,
              let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return array.map { object in
            ToolTraceItem(
                name: object["name"] as? String ?? "",
                title: object["title"] as? String ?? "",
                subtitle: object["subtitle"] as? String,
                detail: object["detail"] as? String ?? "",
                ok: (object["ok"] as? NSNumber)?.boolValue ?? true
            )
        }
    }

    /// 气泡下面那一行：多个工具压成一句，如「联网搜索 2 次 · 想了想」。
    public static func summarize(_ items: [ToolTraceItem]) -> String {
        if items.isEmpty { return "" }
        var counts: [String: Int] = [:]
        var order: [String] = []
        for item in items {
            let title = item.title.isEmpty ? item.name : item.title
            if counts[title] == nil { order.append(title) }
            counts[title, default: 0] += 1
        }
        return order.map { title in
            let count = counts[title] ?? 1
            return count > 1 ? String(format: String(localized: "%@ %d 次"), title, count) : title
        }.joined(separator: " · ")
    }
}

// MARK: - 极小的有序 JSON 写出器

/// 为什么不用 `JSONSerialization` 写这两处：
/// `ToolTraceItem` 的字段顺序在 Android 那边是**声明顺序**（kotlinx 的默认行为），
/// 而 Swift 的 Dictionary 不保序、`JSONSerialization` 又没有「按我给的顺序写」的选项。
/// 备份互通不需要它（那份文件由 Backup/* 负责），但「同一个痕迹两端 dump 出来长得一样」
/// 在排查时值很多钱，所以这里用十几行换掉顺序的不确定性。
enum MiniJSON {
    indirect enum Value {
        case string(String)
        case bool(Bool)
        case int(Int)
    }

    /// `pairs` 里 value 为 nil 的项直接省略（对应 Android 的 `explicitNulls = false`）。
    static func object(_ pairs: [(String, Value?)]) -> String {
        var parts: [String] = []
        for (key, value) in pairs {
            guard let value else { continue }
            parts.append("\(quote(key)):\(render(value))")
        }
        return "{" + parts.joined(separator: ",") + "}"
    }

    static func render(_ value: Value) -> String {
        switch value {
        case .string(let text): return quote(text)
        case .bool(let flag): return flag ? "true" : "false"
        case .int(let number): return String(number)
        }
    }

    static func quote(_ text: String) -> String {
        var out = "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
        return out
    }
}
