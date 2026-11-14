import Foundation

// ---------------------------------------------------------------------------
// 聊天页的窗口化分页（纯逻辑，便于单测）。
//
// 语义与 Android 侧的窗口化一致：
// - 首屏只取最近 60 条；
// - 上滑到顶再取 60 条（`createdAt` 游标，`id` 做同一毫秒的稳定排序键），直到最早；
// - 加载中不重复触发（由界面状态位负责，这里只负责「合并」）。
//
// 为什么合并要单独一个函数：同一毫秒里可能落两条消息（主动开口与用户发送撞在一起），
// 游标按 `(createdAt, id)` 取值时不会重复，但界面重读与上滑可能同时把同一页拿两次；
// 合并时按 id 去重是最后一道保险，避免 `ForEach` 出现重复 id。
// ---------------------------------------------------------------------------

public enum MessagePaging {
    /// 首屏与每一页的条数。
    public static let pageSize = 60

    /// 把「更早的一页」接在窗口前面：按 id 去重、保持时间升序。
    public static func prepend(_ older: [MessageEntity], to current: [MessageEntity]) -> [MessageEntity] {
        if older.isEmpty { return current }
        if current.isEmpty { return older }
        let existing = Set(current.map(\.id))
        let fresh = older.filter { !existing.contains($0.id) }
        return fresh.isEmpty ? current : fresh + current
    }
}
