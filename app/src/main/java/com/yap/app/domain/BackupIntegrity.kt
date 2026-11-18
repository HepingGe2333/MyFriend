package com.yap.app.domain

/**
 * 备份的「跨表引用完整性」闸（审计 P1-2 / 0.11.1）。
 *
 * ## 为什么要有这一层
 *
 * 0.11.0 的空包闸只数顶层条数（`personas` 有没有、`messages` 有没有），但导入真正能不能
 * 落地是**跨表**的：
 *
 * - 消息要挂在会话上（`messages.sessionId` → `chat_sessions.id`）；
 * - 会话要挂在人格上（`chat_sessions.personaId` → `personas.id`）；
 * - 记忆 / 主动规则 / 念头 / 整理记录都要挂在人格上。
 *
 * 一份 `messages` 非空、`sessions` 缺失的备份能通过旧闸。用户点「替换」之后，
 * `BackupRepository` 先清空本机，随后这些消息因为 `sessionIdMap` 里找不到键被 `filter` 掉
 * （只留一条 skipped warning）——**本机没了、备份也没接回来**。这正是 0.10.2 修掉
 * 「读不懂的备份 → 全清」之后漏掉的第二条路径：读得懂、但结构不完整。
 *
 * ## 口径
 *
 * 「能落库」按 [sessionsToRestore] 算，而不是按备份里出现的全部会话：产品口径是
 * 「一人一段」，同一人格的多段会话只保留最新那段（`importBundle` 的行为）。
 * 指向被丢掉那段的消 息同样落不下去，所以同样算缺口——否则「先清空再丢行」仍然存在。
 *
 * 任何一项不为 0 就整份拒绝（[BackupFailure.IncompleteReferences]），校验发生在
 * `database.withTransaction` **之前**，事务根本没开始，本机一个字节都不会变。
 */
data class BackupHoles(
    val sessions: Int = 0,
    val messages: Int = 0,
    val memories: Int = 0,
    val rules: Int = 0,
    val intents: Int = 0,
    val consolidations: Int = 0
) {
    /** 落不下去的行数合计（给用户看的就是这个数）。 */
    val total: Int get() = sessions + messages + memories + rules + intents + consolidations

    /** 结构完整：可以安全导入。 */
    val isClean: Boolean get() = total == 0
}

object BackupIntegrity {

    /**
     * 「一人一段」之后真正会被写进库的那批会话。
     *
     * 与 `BackupRepository.importBundle` 的唯一口径保持一致：同一人格按
     * `(lastMessageAt ?: updatedAt)` 取最新一段。
     */
    fun sessionsToRestore(bundle: BackupBundle): List<BackupSession> =
        bundle.sessions
            .groupBy { it.personaId }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.lastMessageAt ?: it.updatedAt } }

    /**
     * 这份备份能落成什么：逐类数「找不到所属人格/会话」的行。
     *
     * 纯函数，不碰数据库——单测可以直接构造 `BackupBundle` 断言缺口计数。
     *
     * v6 的 `stickers[]` / `diaryNotes[]` **刻意不计入缺口**（也不参与整份拒绝）：
     * 表情是用户自己的收藏（`personaId` 只是弱归属，孤儿引用降级成「不属于某个人格」），
     * 日记行则只接回「所属人格在这次备份里」的条目、其余静默跳过——两者都是「少了它也不影响
     * 他本人回来」的可选内容，不该像消息/记忆那样一票否决整份备份。
     */
    fun check(
        bundle: BackupBundle,
        keepSessions: List<BackupSession> = sessionsToRestore(bundle)
    ): BackupHoles {
        val personaIds = bundle.personas.mapNotNull { it.id.takeIf(String::isNotBlank) }.toSet()
        val sessionIds = keepSessions.mapNotNull { it.id.takeIf(String::isNotBlank) }.toSet()
        return BackupHoles(
            sessions = bundle.sessions.count { it.personaId !in personaIds },
            messages = bundle.messages.count { it.sessionId !in sessionIds },
            memories = bundle.memories.count { it.personaId !in personaIds },
            rules = bundle.proactiveRules.count { it.personaId !in personaIds },
            intents = bundle.agentIntents.count { it.personaId !in personaIds },
            consolidations = bundle.memoryConsolidations.count { it.personaId !in personaIds }
        )
    }

    /**
     * 导入前的统一闸门：返回拒绝原因；`null` 表示这份备份能安全写入。
     *
     * `BackupRepository.importBundle` 直接用它，所以「拒绝规则」只有一份、可单测；
     * 空包（替换模式）由调用方另外判（那是 `DomainErrorCode.BACKUP_EMPTY`，不是备份格式错误）。
     */
    fun rejectionOf(bundle: BackupBundle): BackupFailure? {
        val holes = check(bundle)
        return if (holes.isClean) null else BackupFailure.IncompleteReferences(holes.total)
    }

    /**
     * 「替换」模式下不能接的备份：四个主段全空。
     *
     * 与预览对话框的 `BackupPreview.isEmpty` 同口径（那边只显示「这份备份里什么也没有」，
     * 不给「替换」按钮）；这里防的是绕过界面的调用方把本机清成空。
     */
    fun isEffectivelyEmpty(bundle: BackupBundle): Boolean =
        bundle.personas.isEmpty() &&
            bundle.sessions.isEmpty() &&
            bundle.messages.isEmpty() &&
            bundle.memories.isEmpty()
}
