import Foundation

// ---------------------------------------------------------------------------
// 历史时间戳（0.15 profile 的 `history=timestamped`，`spec/context-profile/README.md`）。
//
// 为什么给历史加时间：模型原来看到的是**一串没有时间的对话**，于是「昨天」「刚才」
// 「那天」全是模糊的；聊到跨天的内容时它甚至会把三天前的事当成刚发生的。
// 每条前面挂一个本地时刻，模型就能自己算「这是昨晚说的」还是「这是上周说的」。
//
// 格式（逐字，两端一致）：
//
// ```
// [21:04] 用户：今天加班到十点
// [21:06] 他：嗯，累坏了吧
// [09:12] 用户：早
// ```
//
// - **同一天**只给 `[HH:mm]`：绝大多数历史都在今天，带日期是噪声；
// - **跨天**给 `[MM-dd HH:mm]`：日期只到月日，年份对「最近两百条消息」没有意义；
// - 「今天」的判据是**设备的本地时区**（`ServiceTime.calendar()`），
//   与状态段那行「现在是 …（UTC+08:00）」同一个时区，用户换时区之后两边一起变；
// - 时间取消息自己的 `createdAt`（毫秒），不取「发请求那一刻」；
// - 工具结果 / 系统消息**不加**时间戳：它们不是他说过的话（见 `PromptBuilder.history`）。
//
// 为什么单独一个类型而不是塞进 `PromptBuilder`：时间戳的格式、跨天判据、时区处理
// 都能被单测穷举（固定 `Calendar` + 固定毫秒），而 `PromptBuilder.history` 那一段
// 依赖消息实体、附件、媒体，测起来重得多。
// ---------------------------------------------------------------------------

public enum HistoryTimestamp {

    /// 中文人格的说话人前缀（`spec/context-profile/README.md`）。
    public static let chineseUserPrefix = "用户："
    public static let chineseAssistantPrefix = "他："

    /// 英文人格的前缀（与中文一一对应；人格说英文时不该读到中文标签）。
    public static let englishUserPrefix = "User: "
    public static let englishAssistantPrefix = "He: "

    /// 说话人标签。
    ///
    /// `role` 只处理 `user` / `assistant`：`system`（以及将来可能的工具角色）返回空串，
    /// 调用方据此**整条不加前缀**——工具结果是「他自己刚做完的事」，不是他说的话。
    public static func speakerPrefix(
        role: MessageRole,
        language: PromptLanguage = .zh
    ) -> String {
        let chinese = language == .zh
        switch role {
        case .user: return chinese ? chineseUserPrefix : englishUserPrefix
        case .assistant: return chinese ? chineseAssistantPrefix : englishAssistantPrefix
        case .system: return ""
        }
    }

    /// `[HH:mm]` / `[MM-dd HH:mm]`；`createdAt <= 0`（没有时间的老数据）返回空串。
    ///
    /// `now` 是「今天」的参照时刻；为 0 时退回用消息自己的时间当参照（此时永远是同一天，
    /// 只会给出 `[HH:mm]`）——老调用点/测试不必为了一个时间戳改签名。
    public static func label(
        createdAt: Int64,
        now: Int64 = 0,
        calendar: Calendar = ServiceTime.calendar()
    ) -> String {
        guard createdAt > 0 else { return "" }
        let reference = now > 0 ? now : createdAt
        let date = Date(timeIntervalSince1970: Double(createdAt) / 1000.0)
        let clock = components(date, calendar: calendar)
        let today = components(Date(timeIntervalSince1970: Double(reference) / 1000.0), calendar: calendar)
        let sameDay = clock.year == today.year && clock.month == today.month && clock.day == today.day
        if sameDay {
            return String(format: "[%02d:%02d] ", clock.hour, clock.minute)
        }
        return String(format: "[%02d-%02d %02d:%02d] ", clock.month, clock.day, clock.hour, clock.minute)
    }

    /// 一条历史消息的完整前缀：`[21:04] 用户：`。
    ///
    /// 没有时间（`createdAt <= 0`）时仍然给说话人标签：模型至少要分得清谁说的。
    /// `role == .system`（工具/系统）**整个前缀为空**。
    public static func prefix(
        createdAt: Int64,
        now: Int64 = 0,
        role: MessageRole,
        language: PromptLanguage = .zh,
        calendar: Calendar = ServiceTime.calendar()
    ) -> String {
        let speaker = speakerPrefix(role: role, language: language)
        guard !speaker.isEmpty else { return "" }
        return label(createdAt: createdAt, now: now, calendar: calendar) + speaker
    }

    // MARK: - 内部

    private struct Clock {
        var year: Int
        var month: Int
        var day: Int
        var hour: Int
        var minute: Int
    }

    private static func components(_ date: Date, calendar: Calendar) -> Clock {
        let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        return Clock(
            year: parts.year ?? 0, month: parts.month ?? 1, day: parts.day ?? 1,
            hour: parts.hour ?? 0, minute: parts.minute ?? 0
        )
    }
}
