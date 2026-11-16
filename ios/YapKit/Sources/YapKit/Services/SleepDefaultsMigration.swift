import Foundation

// ---------------------------------------------------------------------------
// 一次性迁移：把「从没配置过睡眠」的旧数据里的默认睡眠关掉（0.12 续 7）。
//
// 背景：1.9 给 `personas` 加 `sleepEnabled` 时，默认值是「会睡」，而绝大多数人从没进过
// 人格编辑里那一栏。于是老库里的默认人格 + 默认窗口（23:30–08:00）+ 唤醒阈值 6
// 在夜里表现为：发 1–5 条都只标已读、一条也不回 —— 用户看起来像「发什么都没反应」。
//
// 迁移只动**从没编辑过睡眠**的人，四个条件缺一不可：
// - `sleepEnabled = true`（`NULL` 按 true 处理：老库新增这一列时旧行没有值，
//   解码侧 `row.bool("sleepEnabled", true)` 也当它会睡，两边口径必须一致）；
// - `quietSleepMinutes` / `quietWakeMinutes` 都是 nil：用户自己填过时段，说明他**想要**他睡，
//   那是明确的选择，不能被一次迁移抹掉；
// - `version == 1`：改过提示词就算人格进入新版本，这种人是主动编辑过的，同样不动。
//
// 只跑一次（UserDefaults 上的 `sleep_defaults_migrated_v1` 标记），且不碰 `updatedAt`：
// 它不是用户改人格，不该打乱首页排序。
//
// 条件与「只跑一次」的编排都做成纯函数（`shouldDisableSleep` / `plan`），App 层只负责调用；
// 这样 `swift test` 能在真库上把「谁被改、谁不动、跑第二次什么都不做」逐条钉住。
// ---------------------------------------------------------------------------

public enum SleepDefaultsMigration {

    /// 一次性标记的键名（与 Android DataStore 上的键同名，便于排查时一眼认出）。
    public static let markerKey = "sleep_defaults_migrated_v1"

    /// 这个人格是不是「默认睡眠、从没配置过」。
    public static func shouldDisableSleep(_ persona: PersonaEntity) -> Bool {
        persona.sleepEnabled
            && persona.quietSleepMinutes == nil
            && persona.quietWakeMinutes == nil
            && persona.version == 1
    }

    /// 符合条件的人格 id（顺序与入参一致，便于日志与测试对照）。
    public static func candidateIds(_ personas: [PersonaEntity]) -> [String] {
        personas.filter(shouldDisableSleep).map(\.id)
    }

    /// 迁移编排：已迁移过返回 `nil`（调用方据此连一条 `UPDATE` 都不发），
    /// 否则返回要改的 id 列表（可能为空：库是空的，或每个人都自己设过作息）。
    public static func plan(alreadyMigrated: Bool, personas: [PersonaEntity]) -> [String]? {
        alreadyMigrated ? nil : candidateIds(personas)
    }

    /// 跑一次迁移：读库 → 计划 → 写库 → 写标记 → 一条 debug 日志（只说人数，不含用户内容）。
    ///
    /// 失败不抛也不挡启动：日志记一笔就返回 0，下次启动重跑（对已改过的人不再成立，天然幂等）。
    /// 标记在写库**之后**：中途被杀掉时下次会重做，而不是留下「一半改了、一半没改」还标成完成。
    ///
    /// - Returns: 实际被关掉睡眠的人数。
    @discardableResult
    public static func runIfNeeded(
        stores: DatabaseStores,
        isMigrated: () -> Bool,
        markMigrated: () -> Void,
        log: (String) -> Void = { _ in }
    ) -> Int {
        let personas: [PersonaEntity]
        do {
            personas = try stores.personas.all()
        } catch {
            log("sleep defaults migration skipped: could not read personas (\(String(describing: type(of: error))))")
            return 0
        }
        guard let plan = plan(alreadyMigrated: isMigrated(), personas: personas) else { return 0 }
        var changed = 0
        if !plan.isEmpty {
            do {
                changed = try stores.personas.disableSleepFor(ids: plan)
            } catch {
                log("sleep defaults migration failed: \(String(describing: type(of: error)))")
                return 0
            }
        }
        markMigrated()
        log("sleepDefaultsMigratedV1: candidates=\(plan.count) changed=\(changed) "
            + "(default sleep turned off; custom schedules and edited personas untouched)")
        return changed
    }
}
