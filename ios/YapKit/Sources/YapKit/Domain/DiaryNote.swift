import Foundation

// ---------------------------------------------------------------------------
// 日记板（`spec/diary/README.md`，0.13）。
//
// 它是**他自己的备忘录**，不是「记住对方的事」：
// - `remember` 记的是关于对方/关系的长期记忆，会进提示词的记忆块；
// - `diary_notes` 写的是他给自己留的便条（今天想说的话、明天要做的事、对方提过的一个日子），
//   只有他自己看得见，可以挂一个定时提醒 —— 到点由心跳把它变成**他自己的念头**。
//
// 这张表为什么单独存在而不是复用 `memories`：两者的生命周期与可见性都不同。
// 记忆会被检索、会被淡忘、会进提示词；便条是「写下来就不会自己消失，除非我删掉」，
// 而且删除是**硬删除**（用户明确要求：删了就是删了，不留墓碑）。
//
// 时间一律毫秒时间戳（与库里其它表一致）；`remindAt` 可空 = 不提醒；
// `remindedAt` 非空 = 这条提醒已经处理过（防重复，`spec/diary` §提醒）。
// ---------------------------------------------------------------------------

/// 日记板的各种上限。集中在一处，是因为它们在三个地方同时用到：
/// 工具执行（夹取）、心跳（过期判定）、界面/测试（条数）。
public enum DiaryLimits {
    /// 单条内容上限（字）。超过就明确拒绝，不静默截断（截断会让模型以为自己写全了）。
    public static let contentMax = 2000
    /// `list` 默认返回几条。
    public static let listDefault = 20
    /// `list` 最多返回几条（再多模型也读不完，只会把上下文烧在旧便条上）。
    public static let listMax = 50
    /// 错过多久之后不再补提醒：超过 24 小时的提醒只清标记，不生成念头。
    ///
    /// 为什么是 24 小时：一个「明天记得问他到家没」的提醒，隔了三天再冒出来只会莫名其妙；
    /// 而当天错过的（手机没电、App 没开）值得补一次。
    public static let reminderStaleMillis: Int64 = 24 * 60 * 60 * 1000
    /// `remindInMinutes` 的上限：**30 天**（与 Android `Diary.REMIND_IN_MINUTES_MAX` 一致）。
    /// 再长就不是「提醒」而是日程了；也给模型一个明确的边界，免得它把提醒排到几百年后。
    public static let remindInMinutesMax = 30 * 24 * 60

    public static func clampListLimit(_ value: Int) -> Int {
        min(max(value, 1), listMax)
    }

    /// 内容清洗：去首尾空白；空 → nil。**不截断**（截断由调用方决定怎么拒绝）。
    public static func clean(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return nil }
        return trimmed
    }

    public static func isWithinLimit(_ content: String) -> Bool {
        content.count <= contentMax
    }
}

/// 一条日记便条（表 `diary_notes`）。
public struct DiaryNoteEntity: Equatable, Sendable {
    public var id: String
    /// 每条笔记只属于一个人格（跨人格隔离：A 的便条 B 看不到）。
    public var personaId: String
    public var content: String
    public var createdAt: Int64
    /// 什么时候提醒他（毫秒）；nil = 不提醒。
    public var remindAt: Int64?
    /// 这条提醒已经处理过的时间；nil = 还没处理（`spec/diary`：只触发一次）。
    public var remindedAt: Int64?
    public var updatedAt: Int64

    public init(
        id: String,
        personaId: String,
        content: String,
        createdAt: Int64,
        remindAt: Int64? = nil,
        remindedAt: Int64? = nil,
        updatedAt: Int64 = 0
    ) {
        self.id = id
        self.personaId = personaId
        self.content = String(content.prefix(DiaryLimits.contentMax))
        self.createdAt = createdAt
        self.remindAt = remindAt
        self.remindedAt = remindedAt
        self.updatedAt = updatedAt == 0 ? createdAt : updatedAt
    }

    /// 到点、且还没提醒过。
    public func isDue(now: Int64) -> Bool {
        guard let remindAt, remindAt > 0, remindedAt == nil else { return false }
        return remindAt <= now
    }

    /// 错过太久（> 24h）：只清标记，不补发念头。
    public func isStale(now: Int64) -> Bool {
        guard let remindAt, remindAt > 0 else { return false }
        return now - remindAt > DiaryLimits.reminderStaleMillis
    }

    public var hasReminder: Bool {
        (remindAt ?? 0) > 0
    }

    /// 给痕迹/回灌用的一行时间：「11-17 02:42」。
    ///
    /// 只到分钟：日记板上的时间不需要秒，而且「11-17 02:42」在动作卡片里放得下。
    /// 用设备当前时区（用户看到的是自己的时间）。
    public static func displayClock(_ millis: Int64, calendar: Calendar = ServiceTime.calendar()) -> String {
        guard millis > 0 else { return "--" }
        let date = Date(timeIntervalSince1970: Double(millis) / 1000.0)
        let parts = calendar.dateComponents([.month, .day, .hour, .minute], from: date)
        return String(format: "%02d-%02d %02d:%02d",
                      parts.month ?? 0, parts.day ?? 0, parts.hour ?? 0, parts.minute ?? 0)
    }
}

extension DiaryNoteEntity: SQLiteRecord {
    public static let tableName = "diary_notes"

    public static let columns = [
        "id", "personaId", "content", "createdAt", "remindAt", "remindedAt", "updatedAt"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id),
            .text(personaId),
            .text(content),
            .int(createdAt),
            remindAt.map { SQLiteValue.int($0) } ?? .null,
            remindedAt.map { SQLiteValue.int($0) } ?? .null,
            .int(updatedAt)
        ]
    }

    public init(row: SQLiteRow) throws {
        id = try row.text("id")
        personaId = try row.text("personaId")
        content = try row.text("content")
        createdAt = try row.int64("createdAt")
        remindAt = try row.optionalInt64("remindAt")
        remindedAt = try row.optionalInt64("remindedAt")
        updatedAt = try row.int64("updatedAt")
    }
}

/// 日记板的读写。
///
/// 只有三类查询，每一类都带 `personaId`（跨人格隔离不是调用方要记得的事，是这里的事）：
/// 按时间倒序翻、查到点/过期的提醒、按 id 删。
public final class DiaryNoteStore: SQLiteStore<DiaryNoteEntity> {

    /// 翻最近 `limit` 条（按 `createdAt` 倒序，与 `spec/diary` 一致）。
    public func byPersona(_ personaId: String, limit: Int = DiaryLimits.listDefault) throws -> [DiaryNoteEntity] {
        try fetch(
            where: "personaId = ?", [.text(personaId)],
            orderBy: "createdAt DESC", limit: DiaryLimits.clampListLimit(limit)
        )
    }

    public func count(personaId: String) throws -> Int {
        try count(where: "personaId = ?", [.text(personaId)])
    }

    /// 这个人格此刻到点的提醒（`remindAt <= now` 且 `remindedAt IS NULL`）。
    public func due(personaId: String, now: Int64) throws -> [DiaryNoteEntity] {
        try fetch(
            where: "personaId = ? AND remindedAt IS NULL AND remindAt IS NOT NULL AND remindAt > 0 AND remindAt <= ?",
            [.text(personaId), .int(now)],
            orderBy: "remindAt ASC"
        )
    }

    /// 全库到点的提醒（心跳在「还没选人格」时也能扫一眼；正常路径用 `due(personaId:now:)`）。
    public func due(now: Int64) throws -> [DiaryNoteEntity] {
        try fetch(
            where: "remindedAt IS NULL AND remindAt IS NOT NULL AND remindAt > 0 AND remindAt <= ?",
            [.int(now)],
            orderBy: "remindAt ASC"
        )
    }

    /// 标记「这条提醒处理过了」：**只置 `remindedAt`**，不改内容与 `updatedAt`。
    ///
    /// 条件里带 `remindedAt IS NULL`：并发两拍心跳时只有一条 UPDATE 会命中（SQLite 单写者），
    /// 所以「只触发一次」不依赖调用方的互斥。
    @discardableResult
    public func markReminded(id: String, at: Int64) throws -> Bool {
        try database.withConnectionLock {
            let statement = try database.prepare(
                "UPDATE diary_notes SET remindedAt = ? WHERE id = ? AND remindedAt IS NULL"
            )
            try statement.bind([.int(at), .text(id)])
            _ = try statement.step()
            return database.lastChangeCount() > 0
        }
    }

    /// 硬删除（`spec/diary`：删除是硬删除）。带 `personaId` 防止模型删到别人的便条。
    @discardableResult
    public func remove(id: String, personaId: String) throws -> Bool {
        try database.withConnectionLock {
            try delete(where: "id = ? AND personaId = ?", [.text(id), .text(personaId)])
            return database.lastChangeCount() > 0
        }
    }

    public func deleteForPersona(_ personaId: String) throws {
        try delete(where: "personaId = ?", [.text(personaId)])
    }
}
