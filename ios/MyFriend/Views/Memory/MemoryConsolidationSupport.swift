import SwiftUI

// ---------------------------------------------------------------------------
// 「现在整理一次」与整理结果的中文文案。
//
// 执行器与文案放在一处：以后再有第二个入口（比如长按首页的联系人），
// 也不会出现「同一个动作在 A 页说成功、在 B 页说失败」。
//
// iOS 没有 WorkManager：「每天整理一次」在 App 层靠 BGProcessingTask（由系统择机），
// 这里只提供「用户主动点一下」的入口，不假装能定时。
// ---------------------------------------------------------------------------

@MainActor
final class ConsolidationRunner: ObservableObject {
    @Published private(set) var isRunning = false
    @Published private(set) var message: String?

    func run(env: AppEnvironment, personaId: String?) async {
        guard !isRunning else { return }
        // 1.9：记忆与整理都内置开启，没有开关要检查（§一）。
        guard let personaId, !personaId.isEmpty else {
            message = "还没有人格，没法整理记忆。"
            return
        }
        guard let resolved = env.resolvedChatConfig() else {
            message = "还没有可用的 API 配置，这次没整理记忆。"
            return
        }

        isRunning = true
        message = nil
        let config = CompletionConfig(entity: resolved.config, apiKey: resolved.apiKey)
        let result = await env.consolidator.consolidate(
            personaId: personaId,
            config: config,
            model: resolved.model
        )
        isRunning = false
        message = MemoryHealthText.manualResult(result)
    }
}

/// 「记忆的健康」与整理结果的中文句子。
///
/// 只拼句子，不碰数据库、也不读时钟（`now` 由调用方传）：
/// 「昨天 04:10」这种话最容易在日期边界上写错，集中一处好核对。
enum MemoryHealthText {
    private static let clockFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "M月d日"
        return formatter
    }()

    private static let yearFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyy年M月d日"
        return formatter
    }()

    /// 「上次整理：昨天 04:10 · 留下 12 条、并掉 2 条、移出 3 条、忘掉 1 条」。
    static func lastRunLine(_ entity: MemoryConsolidationEntity?, now: Int64 = DomainClock.nowMillis) -> String {
        guard let entity else { return "还没整理过" }
        let counts = countsText(
            kept: entity.kept,
            merged: entity.merged,
            archived: entity.archived,
            forgotten: entity.forgotten,
            separator: "、"
        )
        let head = "上次整理：\(dayAndClock(entity.at, now: now))"
        return counts.isEmpty ? head : "\(head) · \(counts)"
    }

    /// 手动整理结束后给用户看的一句话。
    static func manualResult(_ result: ConsolidationResult) -> String {
        switch result.status {
        case .done:
            let counts = countsText(
                kept: result.kept,
                merged: result.merged,
                archived: result.archived,
                forgotten: result.forgotten,
                separator: "，"
            )
            return counts.isEmpty
                ? "整理完了：这次没怎么动，让它自己慢慢变淡"
                : "整理完了：\(counts)"
        case .noMemory:
            return "整理完了：这次没有需要整理的东西"
        case .disabled:
            return "还没整理：记忆整理现在是关着的"
        case .noConfig:
            return "还没有可用的 API 配置，这次没整理记忆"
        case .noPersona:
            return "找不到这个人格，没法整理记忆"
        case .failed:
            return result.summary.isEmpty ? "这次没能整理，回头它自己会再试一次" : result.summary
        }
    }

    /// 四项计数拼成中文；全为 0 时返回空串（由调用方决定补哪句话）。
    static func countsText(kept: Int, merged: Int, archived: Int, forgotten: Int, separator: String) -> String {
        var parts: [String] = []
        if kept > 0 { parts.append("留下 \(kept) 条") }
        if merged > 0 { parts.append("并掉 \(merged) 条") }
        if archived > 0 { parts.append("移出 \(archived) 条") }
        if forgotten > 0 { parts.append("忘掉 \(forgotten) 条") }
        return parts.joined(separator: separator)
    }

    /// 今天 / 昨天 / 几月几日 / 跨年，再加精确到分钟的时刻。
    static func dayAndClock(_ at: Int64, now: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(at) / 1000)
        let nowDate = Date(timeIntervalSince1970: Double(now) / 1000)
        let calendar = Calendar.current
        let clock = clockFormatter.string(from: date)
        if calendar.isDateInToday(date) { return "今天 \(clock)" }
        if calendar.isDateInYesterday(date) { return "昨天 \(clock)" }
        if calendar.component(.year, from: date) == calendar.component(.year, from: nowDate) {
            return "\(dayFormatter.string(from: date)) \(clock)"
        }
        return "\(yearFormatter.string(from: date)) \(clock)"
    }
}

/// 抽屉的中文短名：列表里只够放两个字的宽度。
enum MFMemoryDrawerText {
    static func short(_ drawer: MemoryDrawer) -> String {
        switch drawer {
        case .selfDrawer: return "他自己"
        case .user: return "关于你"
        case .event: return "你们"
        }
    }

    /// 列表里的一枚小胶囊：原始抽屉名 + 白话，例如「USER · 关于你」。
    /// 原始名保留下来，是为了让用户能和备份/导出的字段对上号。
    static func badge(_ drawer: MemoryDrawer) -> String {
        "\(drawer.rawValue) · \(short(drawer))"
    }

    static func detail(_ drawer: MemoryDrawer) -> String {
        "\(drawer.rawValue) · \(drawer.label)"
    }
}

/// 记忆三层的中文说法与颜色语义（ACTIVE 用强调色，其余逐层降对比度）。
enum MFMemoryTierText {
    static func label(_ tier: MemoryTier) -> String {
        switch tier {
        case .active: return "还在用"
        case .dormant: return "沉下去了"
        case .archived: return "已归档"
        }
    }

    static func tag(_ tier: MemoryTier) -> String {
        switch tier {
        case .active: return "在用"
        case .dormant: return "沉睡"
        case .archived: return "归档"
        }
    }

    /// 归档不是删除：这句小字说明它去哪了。
    static func footnote(_ tier: MemoryTier) -> String? {
        switch tier {
        case .active: return nil
        case .dormant: return "关键词命中时还能被捞回来"
        case .archived: return "只有他认真回忆时才可能翻到"
        }
    }
}

/// 记忆分类的中文名（详情页与新增表单共用）。
enum MFMemoryText {
    static func categoryLabel(_ category: MemoryCategory) -> String {
        switch category {
        case .fact: return "事实"
        case .preference: return "偏好"
        case .event: return "经历"
        case .relationship: return "关系"
        case .other: return "其他"
        }
    }
}
