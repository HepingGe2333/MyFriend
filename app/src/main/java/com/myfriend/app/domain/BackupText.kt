package com.myfriend.app.domain

/**
 * 备份读不懂 / 有东西带不回来时的**结构化**说明。
 *
 * 0.10.0 及以前这些是直接拼好的中文句子（"人格 里有 2 条读不出来，已跳过"），
 * 会原样出现在设置页的导入对话框里。现在数据层只给「哪一段、多少条、什么原因」，
 * 句子由 UI 按当前语言拼（`BackupText`），老数据不受影响。
 */
enum class BackupSection {
    PERSONA,
    MEMORY,
    SESSION,
    MESSAGE,
    PRESET,
    PROACTIVE_RULE,
    AGENT_INTENT,
    CONSOLIDATION,
    API_CONFIG
}

/** 整份文件读不懂的原因。 */
sealed interface BackupFailure {
    data object EmptyFile : BackupFailure
    data object InvalidJson : BackupFailure
    data class WrongFormat(val format: String) : BackupFailure
    data class NewerFormat(val version: Int, val supported: Int) : BackupFailure
}

/** 读懂了、但有一部分没能带回来的说明。 */
sealed interface BackupWarning {
    data class OlderFormat(val version: Int, val supported: Int) : BackupWarning
    data class SectionNotList(val section: BackupSection) : BackupWarning
    data class DroppedRows(val section: BackupSection, val count: Int) : BackupWarning
    data class BlankIds(val section: BackupSection, val count: Int) : BackupWarning
    data object SettingsUnreadable : BackupWarning
    data object NoContent : BackupWarning

    // 以下只在「写进这台手机」时产生（BackupRepository）
    data class ExtraSessions(val count: Int) : BackupWarning
    data class AttachmentsDropped(val count: Int) : BackupWarning
    data class ConfigsMissingKey(val count: Int) : BackupWarning
    data object NoSettings : BackupWarning
    data object SettingsPartiallyRestored : BackupWarning
    data object ActivePersonaMissing : BackupWarning

    /** 备份里的头像路径已经失效，这个字段被清空了。 */
    data class AvatarMissing(val personaName: String) : BackupWarning
}
