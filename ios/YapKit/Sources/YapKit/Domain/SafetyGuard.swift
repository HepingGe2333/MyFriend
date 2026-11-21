import Foundation

// ---------------------------------------------------------------------------
// 0.15 危机安全兜底（纯逻辑）。
//
// 真实短板（体验审计）：用户说「我不想活了」，模型有时会顺着人设回一句
// 「嗯……那你早点休息」这种完全不管用的话；更糟的是他自己也在情绪里，
// 回一句「你想怎样就怎样」。这类轮次不能靠提示词碰运气。
//
// 兜底规则（只做加法，不改写模型的话）：
// 1. 用户这一轮的话命中危机模式（不想活 / 自杀 / 安眠药 / 割腕 / 消失比较好 ……）；
// 2. 模型的回复里**没有**任何安全锚点（我在 / 别一个人 / 身边有人 / 120 / 110 /
//    医院 / 急诊 / 求助 / 家人 / 朋友）；
// 3. 在回复末尾追加 1–2 句简短的安全提示，语气保持平（不喊口号、不说教、
//    不把人设改成客服），只做「别一个人 + 可以求助」这两件事。
//
// 为什么放 Domain 而不是 ChatService 里：这是纯函数（不碰库、不碰网络、不读时钟），
// 「哪些话算危机」「什么算已经有锚点」「追加哪两句」都必须能被直接钉死。
//
// 0.15 起它只是**第一段**：`isCrisis` 的词表结论为 true 时，`CrisisClassifier` 还会用当前
// API 配置问模型一次「这是不是真实的危机」；模型明确说 false 时调用方会传 `crisis: false`
// 进来（`needsSafetyNet` / `appendingSafetyNetIfNeeded` 的 `crisis` 参数），
// 于是这里补话的那一步也跟着不执行 —— 两段式判定的结论必须贯穿到底。
//
// 规则集合与 Android `domain/SafetyGuard.kt` 同一套语义（0.15 两端一致）：
// 判断刻意保守——宁可多补一句，也别漏掉真正的求助；但「想死你了」「笑死」这类
// 想念 / 夸张说法不算危机（见 `crisisPatterns` 里的负向先行断言）。
// ---------------------------------------------------------------------------

/// 危机安全兜底：检测危机表达，给缺少安全锚点的回复补 1–2 句。
public enum SafetyGuard {

    /// 危机语句（正则）。中文为主，附几条明确的英文说法（英文人格同样会遇到）。
    ///
    /// 每条都尽量带上下文，避免把日常夸张当成危机：
    /// - `想死(?!你|我)`：排除「想死你了」「想死我了」；
    /// - `(不如|干脆|还是)消失(掉)?(?!在)`：排除「消失在人群里」这类普通描述；
    /// - `要死` 不认：「累得要死」「要死机了」太常见，会大面积误判；
    /// - 英文只认明确的第一人称表达，不认 `kill` / `die` 这种会误伤玩笑的词。
    public static let crisisPatterns: [String] = [
        "不想活",
        "活不下去",
        #"想死(?!你|我)"#,
        "自杀",
        "轻生",
        "自残|自伤",
        #"结束(自己的)?生命|了结自己"#,
        "割腕|跳楼|上吊|烧炭",
        "安眠药",
        "一了百了",
        #"(不如|干脆|还是)消失(掉)?(?!在)"#,
        #"消失(比较|更)好"#,
        #"没有我(会|你们会)?更好"#,
        "想不开",
        "遗书",
        #"(?i)\bkill(ing)? myself\b"#,
        #"(?i)\b(want|wanna|wanting) to die\b"#,
        #"(?i)\bsuicid(e|al)\b"#,
        #"(?i)\bend my life\b|\bend it all\b"#,
        #"(?i)\b(hurt|cut|harm)(ting)? myself\b"#,
        #"(?i)\bself[- ]?harm\b"#
    ]

    /// 安全锚点：出现任何一条就认为模型已经把人往「有人陪着 / 能求助」的方向带，不再补话。
    ///
    /// `我在` 用先行断言限定成「我在这儿 / 我在，/ 我陪你」这类真正的陪伴，
    /// 避免「我在忙」把安全提示挡掉；`朋友` 与 `家人` 按规范列为锚点（哪怕是在劝他找他们）。
    public static let anchorPatterns: [String] = [
        #"我在(?=[，。！？!?,、\s]|这|你|旁|呢|啊|呀|陪|$)"#,
        "别一个人|不要一个人|一个人待着|身边有人|有人陪|陪着你|我陪你|我在你身边",
        #"\b(120|110|119|911|112)\b"#,
        "医院|急诊|医生|心理(咨询|热线|医生)|热线|求助|报警",
        "家人|家里人|朋友",
        #"(?i)\bi'?m here\b|\bi am here\b"#,
        #"(?i)\bnot alone\b|\bsomeone (is )?with you\b"#,
        #"(?i)\bhospital\b|\bemergency\b|\bhotline\b|\btherapist\b|\bdoctor\b"#,
        #"(?i)\bfamily\b|\bfriends?\b"#
    ]

    private static let crisisRegexes: [Regex<AnyRegexOutput>] = crisisPatterns.compactMap {
        try? Regex($0)
    }

    private static let anchorRegexes: [Regex<AnyRegexOutput>] = anchorPatterns.compactMap {
        try? Regex($0)
    }

    /// 用户这一轮的话里有没有危机表达。
    public static func isCrisis(_ message: String) -> Bool {
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return false }
        return crisisRegexes.contains { text.firstMatch(of: $0) != nil }
    }

    /// 回复里有没有安全锚点（中英文都算）。
    public static func hasSafetyAnchor(_ reply: String) -> Bool {
        let text = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return false }
        return anchorRegexes.contains { text.firstMatch(of: $0) != nil }
    }

    /// 要不要补安全提示：用户命中危机，而回复里一个锚点都没有。
    ///
    /// - Parameter crisis: **已经判定过的**危机结论（0.15 的两段式判定：词表 + 模型复核）。
    ///   传 `nil` = 现场跑第一段词表（老调用点与纯逻辑测试的行为，一个字都不变）。
    ///   为什么要这个参数：`CrisisClassifier` 可能把词表命中的夸张说法判成 `false`，
    ///   那时「不进入兜底」必须贯穿到追加这一步，否则下面会在回复末尾又补一句，
    ///   等于两段式判定的第二段白做。
    public static func needsSafetyNet(
        userMessage: String,
        reply: String,
        crisis: Bool? = nil
    ) -> Bool {
        (crisis ?? isCrisis(userMessage)) && !hasSafetyAnchor(reply)
    }

    /// 需要就补在末尾（同一条消息里另起一行），不需要就原样返回。
    ///
    /// 只追加、不改写：模型说的那句可能已经安慰到了，我们只是确保「别一个人 / 可以求助」
    /// 这两件事至少出现一次。空回复直接给安全提示（不然这一轮什么安全信息都没有）。
    ///
    /// - Parameter crisis: 同 `needsSafetyNet`：`nil` = 现场跑词表（老行为）。
    public static func appendingSafetyNetIfNeeded(
        reply: String,
        userMessage: String,
        language: PromptLanguage = .zh,
        crisis: Bool? = nil
    ) -> String {
        guard needsSafetyNet(userMessage: userMessage, reply: reply, crisis: crisis) else { return reply }
        let net = safetyNetText(language: language)
        let trimmed = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return net }
        return trimmed + "\n" + net
    }

    /// 追加的那 1–2 句：中文与 Android `safety_crisis_note` 同一句话；英文同样两短句。
    ///
    /// 用括号写成「旁注」而不是一段大道理：接在任何人设后面都不出戏，也不改写他的语气。
    /// 其它语言先用英文垫着——安全信息比语言纯正更重要。
    public static func safetyNetText(language: PromptLanguage) -> String {
        switch language {
        case .zh:
            return "（先别一个人待着，身边有人吗？可以打 120。）"
        default:
            return "(Please don't stay on your own — is anyone with you? "
                + "You can call 120 or your local emergency number.)"
        }
    }
}
