import SwiftUI

/// 视觉底座：颜色、字号、间距、圆角。
///
/// 刻意只做「一套」而不是给用户一堆主题开关：Android 端能调的只有主题（浅/深/跟随）、
/// 主色、字号、气泡样式，剩下的审美决定由这里统一承担，两端看起来才像同一个东西。
enum MFTheme {

    // MARK: 颜色

    /// 强调色（默认那个偏暖的橙红）。设置里改 `accentColor` 时用 `Color(argb:)` 换算。
    static let defaultAccent = Color(hex: 0xC7, 0x6F, 0x4E)

    /// 链接蓝。与 Android 的 `#2C7DBE` 一致：能读出来是链接，又不抢强调色的戏。
    static let link = Color(hex: 0x2C, 0x7D, 0xBE)

    /// 聊天气泡：收到的一侧是带一点点暖的白，发出的一侧是强调色。
    static func incomingBubble(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(white: 0.13) : Color(white: 0.985)
    }

    /// 页面底色：比纯白暖一档，长时间看不刺眼。
    static func canvas(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(white: 0.07) : Color(hex: 0xF7, 0xF5, 0xF2)
    }

    static func subtleText(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(white: 0.62) : Color(white: 0.45)
    }

    // MARK: 间距

    static let gutter: CGFloat = 16
    static let bubbleRadius: CGFloat = 18
    static let cardRadius: CGFloat = 16
    static let rowSpacing: CGFloat = 10
}

extension Color {
    init(hex r: Int, _ g: Int, _ b: Int) {
        self.init(.sRGB, red: Double(r) / 255, green: Double(g) / 255, blue: Double(b) / 255, opacity: 1)
    }

    /// 从 Android 的 ARGB `Long` 换算（备份与设置里存的就是这个）。
    init(argb: Int64) {
        let value = UInt32(truncatingIfNeeded: argb)
        self.init(
            .sRGB,
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255,
            opacity: Double((value >> 24) & 0xFF) / 255
        )
    }
}

extension Int64 {
    /// 从 `Color` 取回 ARGB（写回设置/备份用）。
    static func argb(from color: Color, fallback: Int64 = 0xFFC76F4E) -> Int64 {
        #if canImport(UIKit)
        let ui = UIColor(color)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        guard ui.getRed(&r, green: &g, blue: &b, alpha: &a) else { return fallback }
        let ri = Int64((r * 255).rounded()), gi = Int64((g * 255).rounded())
        let bi = Int64((b * 255).rounded()), ai = Int64((a * 255).rounded())
        return (ai << 24) | (ri << 16) | (gi << 8) | bi
        #else
        return fallback
        #endif
    }
}

/// 时间文案：聊天分隔条、消息时间、相对时间。
///
/// 统一走这里，避免每个界面各写一份 `DateFormatter`（那是最常见的「同一个时间显示成两种格式」来源）。
enum MFDate {
    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "HH:mm"
        return f
    }()

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "M月d日"
        return f
    }()

    static func time(_ millis: Int64) -> String {
        timeFormatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    static func day(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return "今天" }
        if calendar.isDateInYesterday(date) { return "昨天" }
        return dayFormatter.string(from: date)
    }

    /// 「刚刚 / 12 分钟前 / 昨天 / 3 天前」——用在会话列表与状态行。
    static func relative(_ millis: Int64, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> String {
        let delta = max(0, now - millis)
        let minutes = delta / 60_000
        if minutes < 1 { return "刚刚" }
        if minutes < 60 { return "\(minutes) 分钟前" }
        let hours = minutes / 60
        if hours < 24 { return "\(hours) 小时前" }
        let days = hours / 24
        if days == 1 { return "昨天" }
        if days < 7 { return "\(days) 天前" }
        return day(millis)
    }
}
