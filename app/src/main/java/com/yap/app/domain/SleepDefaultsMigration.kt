package com.yap.app.domain

import com.yap.app.data.local.PersonaEntity

/**
 * 一次性迁移：把「从没配置过睡眠」的旧数据里的默认睡眠关掉（0.12 续 7）。
 *
 * 背景：1.9 给 `personas` 加 `sleepEnabled` 时，列默认是 1（会睡），而绝大多数人从没进过
 * 人格编辑里那一栏。于是种子人格 + 老库里的「默认睡眠」在夜里表现为：
 * 用户发 1–5 条都只标已读、一条也不回（默认窗口 23:30–08:00，唤醒阈值原来是 6）。
 * 「新装看起来像发什么都没反应」就是这么来的。
 *
 * 迁移只动**从没编辑过睡眠**的人，条件缺一不可：
 * - `sleepEnabled = true`：本来就关着的人没什么可改；
 * - `quietSleepMinutes` / `quietWakeMinutes` 都是 null：用户自己填过时段就说明他是**想要**他睡的，
 *   那是明确的选择，不能被一次迁移抹掉；
 * - `version = 1`：改过提示词就算「人格进入新版本」，这种人是主动编辑过的，同样不动。
 *
 * 迁移只跑一次（DataStore / UserDefaults 上的 `sleepDefaultsMigratedV1` 标记），
 * 且不碰 `updatedAt` —— 它不是用户改人格，不该打乱首页排序。
 *
 * 这里刻意做成纯函数（不读库、不写标记、不打日志）：条件与「只跑一次」的编排都能在 JVM 上
 * 一条条钉住，仓储层只负责把结果写成一条 `UPDATE`。
 */
object SleepDefaultsMigration {

    /** 一次性标记的键名（两端同名，便于排查时一眼认出）。 */
    const val MARKER_KEY = "sleep_defaults_migrated_v1"

    /** 这个人格是不是「默认睡眠、从没配置过」？ */
    fun shouldDisableSleep(persona: PersonaEntity): Boolean =
        persona.sleepEnabled &&
            persona.quietSleepMinutes == null &&
            persona.quietWakeMinutes == null &&
            persona.version == 1

    /** 符合条件的人格 id（顺序与入参一致，便于日志与测试对照）。 */
    fun candidateIds(personas: List<PersonaEntity>): List<String> =
        personas.filter(::shouldDisableSleep).map { it.id }

    /**
     * 迁移编排：已迁移过返回 `null`（调用方据此跳过整件事，连 `UPDATE` 都不发），
     * 否则返回要改的 id 列表（可能为空 —— 空表、或每个人格都自己设过睡眠）。
     */
    fun plan(alreadyMigrated: Boolean, personas: List<PersonaEntity>): List<String>? =
        if (alreadyMigrated) null else candidateIds(personas)
}
