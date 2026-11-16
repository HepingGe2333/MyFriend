package com.yap.app.domain

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

    /**
     * 文件 / 字符串超过上限（安全审计 P2-3）。
     *
     * 超过 [limitBytes] 的「备份」只可能是拿错了文件：正常备份是几 MB 量级。
     * 在读之前 / 读的过程中就拒绝，而不是先读进内存再判断（那正是 OOM 的地方）。
     */
    data class TooLarge(val limitBytes: Long) : BackupFailure

    /**
     * 结构不完整：有行引用了备份里不存在（或这次不会写进去）的人格/会话（审计 P1-2 / 0.11.1）。
     *
     * 这类备份能解析、甚至「看起来很满」，但真的写库时这些行会落不下去。替换模式会先清空本机、
     * 再把这些行丢掉——用户得到的是「本机没了，备份也没接回来」。所以整份拒绝，
     * 绝不先清后弃。[holes] 是落不下去的行数。
     */
    data class IncompleteReferences(val holes: Int) : BackupFailure
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

    /**
     * 备份想把 API 配置的服务地址改成另一个主机，被拒绝、保留了本机地址。
     *
     * 0.10.2 安全修复：地址与明文开关不是可迁移内容（否则一份可控备份就能把
     * 本机 Key 与聊天内容引到攻击者主机）。
     */
    data class ApiConfigAddressKept(val count: Int) : BackupWarning

    /** 备份要求打开「允许不安全 HTTP」，没有被采纳（明文只能由用户在本机手动开启）。 */
    data class ApiConfigInsecureIgnored(val count: Int) : BackupWarning

    /** 备份里的某条配置没通过与本机保存相同的校验（例如 `http://` 明文地址），已跳过。 */
    data class ApiConfigRejected(val configName: String) : BackupWarning

    /**
     * 预览阶段就发现了跨表引用缺口（审计 P1-2）：导入会被整份拒绝，先在这里提醒一句。
     *
     * [holes] 是落不下去的行数（找不到所属人格/会话的消息、记忆、规则、念头等）。
     */
    data class IncompleteReferences(val holes: Int) : BackupWarning
}
