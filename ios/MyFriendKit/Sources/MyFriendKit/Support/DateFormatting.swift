import Foundation

/// 备份里到处是「毫秒时间戳」，界面与摘要里要的却是「2026年11月14日 01:40」。
///
/// 单独放在 Support 里，是因为这里有三个坑：
/// 1. 时区必须用设备当前时区（用户看到的是自己的时间），与 Android 的 `SimpleDateFormat` 一致；
/// 2. 数字形状（`yyyyMMdd-HHmm`）必须用 `en_US_POSIX`：`Locale.CHINA` 在某些设置下会走
///    非公历日历或本地数字，文件名会变形；
/// 3. `DateFormatter` 不是线程安全的，所以每次现建、不共享实例（备份导出是低频操作，不值得加锁）。
public enum BackupDateFormatting {

    /// 「2026年11月14日 01:40」；`at <= 0` 表示时间不详。
    public static func moment(_ at: Int64) -> String {
        guard at > 0 else { return String(localized: "时间不详") }
        // 跟着界面语言走：中文是「2026年11月14日 01:40」，英文是 "Nov 14, 2026 at 1:40 AM"。
        let formatter = DateFormatter()
        formatter.locale = Locale.current
        formatter.timeZone = TimeZone.current
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date(millis: at))
    }

    /// 备份文件名里的时间戳：`20261114-0140`。
    public static func fileStamp(_ at: Int64) -> String {
        guard at > 0 else { return "00000000-0000" }
        return formatter("yyyyMMdd-HHmm", locale: "en_US_POSIX").string(from: date(millis: at))
    }

    private static func formatter(_ format: String, locale: String) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: locale)
        formatter.timeZone = TimeZone.current
        formatter.dateFormat = format
        return formatter
    }

    private static func date(millis: Int64) -> Date {
        Date(timeIntervalSince1970: Double(millis) / 1000.0)
    }
}

/// 当前毫秒时间戳（与 Android 的 `System.currentTimeMillis()` 同义）。
public func currentTimeMillis() -> Int64 {
    Int64((Date().timeIntervalSince1970 * 1000).rounded())
}

/// 备份文件建议名字：`myfriend-backup-20261114-0140.json`（与 Android 的 `backupFileName` 同形）。
public func backupFileName(now: Int64 = currentTimeMillis()) -> String {
    "myfriend-backup-\(BackupDateFormatting.fileStamp(now)).json"
}

/// 「2026年11月14日 01:40」，给界面和摘要共用（与 Android 的 `formatBackupMoment` 同文案）。
public func formatBackupMoment(_ at: Int64) -> String {
    BackupDateFormatting.moment(at)
}
