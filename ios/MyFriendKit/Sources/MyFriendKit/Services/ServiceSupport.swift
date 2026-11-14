import Foundation

// ---------------------------------------------------------------------------
// 服务层共用的小工具：设置来源、时间换算、作息判定、SQLite 的几件杂事。
//
// 这里刻意只放「多个服务都要用、又没有任何业务判断」的东西：
// 一旦某段代码有阈值、有权重、有取舍，它就该待在自己的文件里（或 Domain 里），
// 不能藏进一个叫 Support 的抽屉。
// ---------------------------------------------------------------------------

// MARK: - 设置来源

/// 服务层读设置的唯一入口。
///
/// 为什么不用 `SettingsStore` 具体类型：
/// - 单测要能在内存里换一份设置（不想为了改一个开关去写 UserDefaults）；
/// - 以后设置若从 UserDefaults 迁到别处，服务层一行都不用改。
public protocol SettingsProviding {
    func load() -> AppSettings
    func save(_ settings: AppSettings)
}

extension SettingsStore: SettingsProviding {}

/// 内存版设置：测试与「后台任务只读一次设置」的场景用。
public final class InMemorySettings: SettingsProviding {
    private let lock = NSLock()
    private var settings: AppSettings

    public init(_ settings: AppSettings = AppSettings()) {
        self.settings = settings
    }

    public func load() -> AppSettings {
        lock.lock()
        defer { lock.unlock() }
        return settings
    }

    public func save(_ settings: AppSettings) {
        lock.lock()
        self.settings = settings
        lock.unlock()
    }

    public func update(_ body: (inout AppSettings) -> Void) {
        lock.lock()
        var copy = settings
        lock.unlock()
        body(&copy)
        save(copy)
    }
}

// MARK: - 时间

/// 服务层的时间换算。所有时间都是毫秒时间戳，与库里的列一致。
public enum ServiceTime {

    /// 系统时区、公历：与 Android 的 `ZoneId.systemDefault()` 同义。
    public static func calendar(_ timeZone: TimeZone = .current) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar
    }

    /// 现在几点（0..23）。
    public static func hourOfDay(_ now: Int64, calendar: Calendar = ServiceTime.calendar()) -> Int {
        let date = Date(timeIntervalSince1970: Double(now) / 1000.0)
        return calendar.component(.hour, from: date)
    }

    /// 当天第几分钟（0..1439）。
    public static func minuteOfDay(_ now: Int64, calendar: Calendar = ServiceTime.calendar()) -> Int {
        let date = Date(timeIntervalSince1970: Double(now) / 1000.0)
        return calendar.component(.hour, from: date) * 60 + calendar.component(.minute, from: date)
    }

    /// 当天 00:00 的毫秒时间戳（「今天主动说了几句」这类计数从它开始）。
    public static func startOfDay(_ now: Int64, calendar: Calendar = ServiceTime.calendar()) -> Int64 {
        let date = Date(timeIntervalSince1970: Double(now) / 1000.0)
        let start = calendar.startOfDay(for: date)
        return Int64((start.timeIntervalSince1970 * 1000.0).rounded())
    }

    /// 两个时间点差多少分钟；不知道（nil/<=0）一律 nil，时钟回拨算 0。
    public static func minutesBetween(_ at: Int64?, _ now: Int64) -> Int? {
        guard let at, at > 0 else { return nil }
        let delta = now - at
        if delta <= 0 { return 0 }
        let minutes = delta / 60_000
        return minutes > Int64(Int.max) ? Int.max : Int(minutes)
    }

    /// 「刚刚 / 12 分钟前 / 3 小时前 / 2 天前」，给提示词里的人话时间。
    public static func timeAgo(_ at: Int64, now: Int64) -> String {
        let minutes = max((now - at) / 60_000, 0)
        switch minutes {
        case ..<2: return "刚刚"
        case ..<60: return "\(minutes) 分钟前"
        case ..<(60 * 24): return "\(minutes / 60) 小时前"
        default: return "\(minutes / (60 * 24)) 天前"
        }
    }

    /// 「2026-11-14 星期五 01:40」：整理提示词里的「现在」。
    public static func formatted(_ now: Int64, calendar: Calendar = ServiceTime.calendar()) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyy-MM-dd EEEE HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: Double(now) / 1000.0))
    }

    /// 「22:30」，作息与窗口的展示都用它。
    public static func clockText(_ minutes: Int) -> String {
        let safe = ((minutes % 1440) + 1440) % 1440
        return String(format: "%02d:%02d", safe / 60, safe % 60)
    }
}

// MARK: - 作息

/// 他给自己安排的作息：几点睡、几点醒。
///
/// 1.9 起真正的判定搬到了 `Domain/SleepSchedule`（要照顾 `sleepEnabled`、默写作息、
/// 唤醒阈值这些新字段）；这里保留同名入口，是因为心跳、自主循环、Presence 三处都在用它，
/// 换个名字只会让 diff 变大、行为一点没变。**唯一的事实来源仍然是 SleepSchedule。**
public enum PersonaSleep {

    /// 现在（当天第几分钟）是不是在睡。
    public static func isAsleep(_ persona: PersonaEntity, minuteOfDay: Int) -> Bool {
        SleepSchedule.isAsleep(persona, minuteOfDay: minuteOfDay)
    }

    /// 睡到几点：返回距离醒来的分钟数，至少 10 分钟。
    public static func minutesUntilWake(_ persona: PersonaEntity, minuteOfDay: Int) -> Int {
        SleepSchedule.minutesUntilWake(persona, minuteOfDay: minuteOfDay)
    }

    /// 给提示词的一句「你的作息：每天 23:30 睡觉，08:00 醒来」。
    public static func hint(_ persona: PersonaEntity) -> String? {
        SleepSchedule.hint(persona)
    }
}

// MARK: - SQLite 杂事

public extension SQLiteDatabase {

    /// 这张表在不在。
    ///
    /// 服务层用它处理「Schema 里还没加的表」（见 `MissingTables`）：
    /// 表不在时安静降级，而不是让后台任务每次启动都抛一个 no such table。
    /// 注意：这里**只读** `sqlite_master`，不建表——改 schema 是 Schema.swift 的事。
    func hasTable(_ name: String) -> Bool {
        let sql = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1"
        guard let statement = try? prepare(sql), (try? statement.bind([.text(name)])) != nil else { return false }
        return (try? statement.step()) ?? false
    }

    /// 上一条语句影响了多少行（SQLite 的 `changes()`，与连接绑定）。
    ///
    /// 清理旧向量、回收孤儿这些操作要把「删了几条」报给调用方/日志，
    /// 而 `SQLiteStatement` 不暴露 C 的 changes，就顺手问一次。
    func lastChangeCount() -> Int {
        Int((try? scalarInt64("SELECT changes()")) ?? 0)
    }
}

// MARK: - 服务层用到的两张表

/// 服务层按 Android 语义读写、但**不是记录型仓储**的两张表。
///
/// 历史：1.8 加这两条链路时 SQLite 的建表语句里还没它们，当时写成了「表不在就退回内存实现」，
/// 于是重启会丢留痕。0.9.1 把 DDL 补进了 `Schema.createStatements`（老库升级后也会自动补建，
/// 见 `Schema.supplementaryTables` 的说明），所以现在这两张表**总是存在**。
///
/// 这个类型保留下来只做一件事：给 `AgentTickStore` / `MemoryEchoStore` 一个统一的表名常量
/// 来源（它们仍然会先检查表在不在——那是防御，不是常态）。
public enum MissingTables {
    public static let agentTicks = Schema.agentTicksTable
    public static let memoryEchoes = Schema.memoryEchoesTable
}
