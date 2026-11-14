// 来源：app/src/main/java/com/myfriend/app/domain/ProactiveLevel.kt
//
// 主动程度：设置页里那一格「每天最多主动几次」被拿掉之后，这件事交回给人格。
//
// 1–5 各有一个人话解释，同时换算成两个真东西：
// - 每日主动上限：1–5 次（心情还会在 `MoodBehavior` 里挪一格）；
// - 心跳权重：乘在「想不想找你」的概率上。1 的人半年不主动也正常，5 的人会明显地黏。
//
// 纯函数，边界收在这里，免得 UI、心跳、AgentLoop 各写一份 1..5 的夹取逻辑。
//
// 这个文件是 0.10.0 为了「两端共用契约」补上的：在此之前 iOS 只有
// `PersonaEntity.proactiveDailyCap`（档位=次数），没有心跳权重，也没有和 Android
// 一字不差的人话解释。契约用例 `spec/agent-core/cases/proactive-cap.json`
// 同时钉住 dailyCap / heartbeatWeight / description 三件事。

import Foundation

/// 主动程度 1–5 的换算。
public enum ProactiveLevel {

    public static let MIN = 1
    public static let MAX = 5
    public static let DEFAULT = 3

    public static func normalize(_ level: Int) -> Int {
        min(max(level, MIN), MAX)
    }

    /// 每天最多主动开口几次。
    public static func dailyCap(_ level: Int) -> Int {
        normalize(level)
    }

    /// 心跳概率权重：0.7 / 0.85 / 1.0 / 1.15 / 1.3。
    ///
    /// 不做到 0 也不做到 3 倍：再冷淡的人也会有忽然想起你的时候，
    /// 再黏的人也不该变成推送机器。`Heartbeat` 自己会再夹一次概率。
    public static func heartbeatWeight(_ level: Int) -> Double {
        switch normalize(level) {
        case 1: return 0.70
        case 2: return 0.85
        case 3: return 1.0
        case 4: return 1.15
        default: return 1.30
        }
    }

    /// 编辑页滑杆下面那句人话（跟随界面语言）。
    ///
    /// 中文原文就是资源 key：`spec/agent-core` 的 `proactive-cap` 用例钉的是这段中文，
    /// 而 `swift test` 没有资源包时会回落成 key 本身，所以契约两端都还看得见同一句中文；
    /// App 里则按系统语言取对应译文。
    public static func description(_ level: Int) -> String {
        switch normalize(level) {
        case 1: return String(localized: "很少主动。你不找他，他基本不会来打扰。")
        case 2: return String(localized: "偶尔想起你。忙起来就顾不上了。")
        case 3: return String(localized: "会主动，但有分寸。大多数日子会来找你说一句话。")
        case 4: return String(localized: "比较黏人。隔一阵子就想知道你在做什么。")
        default: return String(localized: "很黏你。一天里会好几次想找你说话。")
        }
    }
}
