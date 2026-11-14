import Foundation

// ---------------------------------------------------------------------------
// 聊天页窗口与库的一致性判定（0.11.1 审计 P1-3）。
//
// 0.11.0 把聊天页的重读改成了「只追加」（P2-5 的修法）：窗口以已加载区间的最早一条为锚，
// 20 秒轮询只 `pageAfter(窗口最后一条)`。但**删除**与**整体替换**从来没有让窗口重置：
//
// - 用户在聊天页里进设置 →「清空全部聊天与记忆」/ 备份「替换导入」→ 返回；
// - `sessions.byId` 已经 nil，`pagesAfter` 以「已删消息」的游标只会返回空，`messages`
//   原样留着；`session` 也只在读到时赋值，于是页面继续显示已经不存在的消息，
//   发送只会得到「会话不存在」。20 秒轮询的比较条件永远成立 → 永远无效重试。
//
// 这一层把「这一拍该怎么刷新」写成纯函数：视图只负责拿库里的三个轻量数字
// （会话还在不在、全表条数、窗口条数），按结果 `keep / append / reset / sessionGone`。
// 抽出来是为了能在 `swift test` 里直接跑「外部删除后轮询会清空列表与会话」这条行为。
// ---------------------------------------------------------------------------

/// 聊天页窗口在这一拍该怎么刷新。
public enum ChatWindowRefresh: Equatable, Sendable {
    /// 什么都没变：不要动列表（避免无意义重绘）。
    case keep
    /// 有新消息：只往后追加（保住已加载的历史与滚动位置）。
    case append
    /// 窗口里已经有消息在库里不存在了（删消息 / 清空上下文 / 替换导入）：以最近一屏重置。
    case reset
    /// 整个会话都没了（清空全部聊天 / 删人格 / 替换导入删掉了它）：清空会话与消息、停轮询。
    case sessionGone
}

public enum ChatWindowPolicy {

    /// 判定这一拍要怎么刷新。
    ///
    /// - Parameters:
    ///   - sessionExists: `sessions.byId` 是否还读得到（**不是**内存里的 `session` 是否非空）。
    ///   - storedTotal: 库里这段会话一共多少条（`messages.count(sessionId:)`；查失败用 nil）。
    ///   - loadedCount: 当前窗口里多少条。
    ///   - loadedTotalAtLastCheck: 上一次核对时库里的总数（会话打开/上次刷新时记下的）。
    ///   - latestIdMatched: 库里的最新一条 id 与窗口最后一条是否相同。
    public static func plan(
        sessionExists: Bool,
        storedTotal: Int?,
        loadedCount: Int,
        loadedTotalAtLastCheck: Int,
        latestIdMatched: Bool
    ) -> ChatWindowRefresh {
        guard sessionExists else { return .sessionGone }
        guard let storedTotal else {
            // 查库失败：不动窗口（下一拍再试），但也不假装「没有变化」去 append。
            return .keep
        }
        if storedTotal == 0 {
            // 会话还在、消息被清空了（清空上下文 / 替换导入后的空会话）。
            return loadedCount == 0 ? .keep : .reset
        }
        // 外部删除：窗口里比库里还多，或者库里的总数比上次看到的少 —— 说明有消息被删掉了，
        // 只追加永远追不回来，必须以最近一屏重置。
        if loadedCount > storedTotal { return .reset }
        if storedTotal < loadedTotalAtLastCheck { return .reset }
        if latestIdMatched && storedTotal == loadedTotalAtLastCheck { return .keep }
        return .append
    }
}
