import Foundation

// 来源：`spec/experience/README.md` §2「拉黑（AI 主动）」。
//
// 拉黑到期**自动解除**（`BlockRepository.state` 读一次就把两列清掉），所以界面需要的
// 不是「解除按钮」，而是一句会自己走字的倒计时：
//
//     你已被拉黑，还剩 1 小时 23 分
//
// 这一段刻意做成纯函数（两个毫秒时间戳进、一句文案出）：倒计时每秒重算一次，
// 散在 View 里最容易出现「四舍五入不统一」「到期了还显示 0 分」这类只在小数点上
// 出现、又特别难复现的问题。规则写死在这里、逐条单测钉住。
//
// 文案按产品规范是中文（`spec/experience/README.md` §2 的原文），所以不走
// `String(localized:)`：英语环境里也照原样显示这句中文。

/// 拉黑倒计时（纯逻辑）。
public enum BlockCountdown {

    /// 还剩多久：`hours` 为整小时、`minutes` 为**向上取整**的剩余分钟（0...59）。
    ///
    /// 返回 nil 表示「现在不在拉黑期」：`until` 为空/0/负数，或者已经到期。
    /// 向上取整的理由：只剩 1 秒时显示「还剩 1 分」比显示「还剩 0 分」诚实——
    /// 用户看到 0 分却还没解除，会以为界面坏了。
    public static func remaining(until: Int64, now: Int64) -> (hours: Int, minutes: Int)? {
        guard until > 0, until > now else { return nil }
        let delta = until - now
        // 先向上取整到分钟，再拆成小时 + 分钟：这样「1 小时零 1 毫秒」是 1 小时 1 分，
        // 而「1 小时差 1 毫秒」是 1 小时整（60 分），与界面上的分钟粒度一致。
        let totalMinutes = (delta + 59_999) / 60_000
        return (hours: Int(totalMinutes / 60), minutes: Int(totalMinutes % 60))
    }

    /// 输入框上方那句倒计时；不在拉黑期返回 nil。
    ///
    /// 格式规则（与产品规范一致）：
    /// - 0 小时时省略「X 小时」（例如「还剩 1 分」）；
    /// - 0 分时省略「Y 分」（例如「还剩 2 小时」）；
    /// - 两段都非 0 时用空格连接（「还剩 1 小时 23 分」）；
    /// - 不夹取 24 小时：服务端下发的时长更长时就照实说「还剩 25 小时」。
    ///
    /// 注意：`remaining` 保证至少有一段非 0（`until > now` ⇒ 总分钟数 ≥ 1），
    /// 所以这里不需要「至少显示一样」的兜底分支。
    public static func text(until: Int64, now: Int64) -> String? {
        guard let remaining = remaining(until: until, now: now) else { return nil }
        var segments: [String] = []
        if remaining.hours > 0 {
            segments.append("\(remaining.hours) 小时")
        }
        if remaining.minutes > 0 {
            segments.append("\(remaining.minutes) 分")
        }
        guard !segments.isEmpty else { return nil }
        return "你已被拉黑，还剩 " + segments.joined(separator: " ")
    }
}
