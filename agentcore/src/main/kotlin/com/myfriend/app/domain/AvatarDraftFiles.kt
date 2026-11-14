package com.myfriend.app.domain

/**
 * 「编辑器里选了头像、最后没保存」时该删哪些文件（审计 P2-2，iOS 0.10.1 已修、Android 未跟进）。
 *
 * 选图是「立刻落盘」的（`MediaStorage.persistAvatar` → `files/avatars/avatar-<uuid>.jpg`），
 * 之后只有三条路会处理这个文件：保存（进库）、换一张（旧的成孤儿）、取消（谁都不管）。
 * 于是每「选一次又退出去」就留下一个 ≤200KB 的孤儿，永不回收。
 *
 * 这里把「哪些文件该删」写成纯函数，ViewModel 只负责记住「本次编辑新建了哪些路径」：
 *
 * - [onPicked]：连选两张时，前一张如果是本次编辑新建的草稿 → 删掉；
 * - [onDiscard]：取消 / 返回 / ViewModel 销毁 → 删掉本次新建、且不是原有那张的全部草稿；
 * - [onSaved]：保存成功 → 被采纳的那张留下，其余草稿删掉。
 *
 * 注意「原有那张」不归这里管：它已经被数据库引用过，换头像时由
 * `PersonaRepository.save` 的既有逻辑删（`MediaStorage.deleteAvatar(existing.avatarUri)`）。
 * 双重删除没有害处，但语义要分清：这里只负责「这次编辑过程产生的中间文件」。
 */
object AvatarDraftFiles {

    /**
     * 又选了一张：返回应当删掉的上一张（可能为空）。
     *
     * 只有当上一张确实是「本次编辑新建的草稿」时才删——不然会把已经保存过的头像删掉。
     */
    fun onPicked(previousDraft: String?, pending: Set<String>): List<String> {
        val previous = previousDraft?.trim().orEmpty()
        if (previous.isEmpty()) return emptyList()
        if (previous !in pending) return emptyList()
        return listOf(previous)
    }

    /** 取消编辑：本次新建的草稿全删（原有那张不动）。 */
    fun onDiscard(initialAvatar: String?, pending: Set<String>): List<String> {
        val keep = initialAvatar?.trim().orEmpty()
        return pending.filter { it.isNotBlank() && it != keep }
    }

    /** 保存成功：被采纳的那张留下，其余草稿删掉。 */
    fun onSaved(savedAvatar: String?, pending: Set<String>): List<String> {
        val keep = savedAvatar?.trim().orEmpty()
        return pending.filter { it.isNotBlank() && it != keep }
    }
}
