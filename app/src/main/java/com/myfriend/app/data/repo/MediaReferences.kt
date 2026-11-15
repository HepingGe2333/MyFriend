package com.myfriend.app.data.repo

import com.myfriend.app.data.local.MediaReferenceDao
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.util.MediaFileGc
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引用计数的**数据来源**。
 *
 * 抽成接口只为一件事：守卫逻辑（[MediaReferences]）要能在纯 JVM 单测里用内存假实现跑，
 * 不需要 Robolectric，也不需要真 Room。「两条消息共享一张图」「人格头像与用户头像同名」
 * 这类场景用假实现表达最直接。
 */
interface MediaReferenceSource {

    /**
     * 有多少条消息的附件里出现了 [fileName]（大小写/通配符由实现负责转义）。
     *
     * @param excludingPersonaId 非空时排除这个人的消息
     */
    suspend fun messageAttachmentCount(fileName: String, excludingPersonaId: String?): Int

    /** 人格头像里出现的文件名（已收成裸文件名）。 */
    suspend fun personaAvatarNames(excludingPersonaId: String?): Set<String>

    /** 人格预设头像里出现的文件名。 */
    suspend fun presetAvatarNames(): Set<String>

    /** 「我的头像」的文件名（DataStore 里的 `userAvatarUri`），没有为 null。 */
    suspend fun userAvatarName(): String?
}

/**
 * 「这个媒体文件还有没有人引用」的唯一入口（0.12 媒体去重的收尾）。
 *
 * ## 引用来源（与任务口径一一对应）
 *
 * 1. **聊天图**：`messages.attachmentsJson` 里包含该文件名（跨会话全局统计）。
 * 2. **头像**：`personas.avatarUri` / `persona_presets.avatarUri` / DataStore `userAvatarUri`。
 *
 * 刻意**不算**的三类：
 * - `persona_revisions.avatarUri`：版本历史只保存「当时长什么样」，文件被回收后
 *   `restoreVersion` 已经会用 `isMissingLocalFile` 退回无头像（历史不阻止回收，否则每个
 *   换过的头像都会永久留下）；
 * - `settings.chatBackgroundImageUri`：背景图是 `background-<uuid>.img`，与 `chat-*` /
 *   `avatar-*` 的命名空间不重叠，没有任何删除路径会碰它；
 * - 导出/备份里出现过的名字：备份是「某一刻的快照」，不是引用。
 *
 * ## 顺序
 *
 * 调用方必须**先删数据库行，再问这里**；「删一个人」这种批量场景如果确实要在删行之前问
 * （例如先把候选路径收集出来），必须用 [excludingPersonaId] 表达「排除他这次要删掉的那些行」。
 * 顺序反了的结果两个方向都错：算上自己 → 永远删不掉；算漏别人 → 误删。
 */
@Singleton
class MediaReferences @Inject constructor(
    private val source: MediaReferenceSource
) {

    /** 这些文件名里，哪些还有人引用。批量场景（删一个人/替换导入）只查一次头像表。 */
    suspend fun referencedNames(
        candidates: Collection<String>,
        excludingPersonaId: String? = null
    ): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val personaAvatars = source.personaAvatarNames(excludingPersonaId)
        val presetAvatars = source.presetAvatarNames()
        val userAvatar = source.userAvatarName()
        return candidates.filterTo(mutableSetOf()) { name ->
            name in personaAvatars || name in presetAvatars || name == userAvatar ||
                source.messageAttachmentCount(name, excludingPersonaId) > 0
        }
    }

    /** 单个文件：还有引用吗？ */
    suspend fun isReferenced(fileName: String, excludingPersonaId: String? = null): Boolean =
        fileName in referencedNames(listOf(fileName), excludingPersonaId)

    /** `file://` 路径版本（库里的原样值）；不是本机媒体文件时按「没有引用」处理。 */
    suspend fun isUriReferenced(uri: String?, excludingPersonaId: String? = null): Boolean {
        val name = MediaFileGc.fileNameOf(uri) ?: return false
        return isReferenced(name, excludingPersonaId)
    }
}

/**
 * [MediaReferenceSource] 的生产实现：Room + DataStore。
 *
 * 只有这一层知道「文件名怎么变成 SQL」：模式串统一由 [MediaFileGc.likePattern] 生成，
 * 与 [MediaReferenceDao] 里的 `ESCAPE '\'` 配套；头像路径统一用 [MediaFileGc.fileNameOf]
 * 收成文件名再比较（库里存的是绝对路径，直接比字符串会永远不相等）。
 */
@Singleton
class RoomMediaReferenceSource @Inject constructor(
    private val dao: MediaReferenceDao,
    private val settingsRepository: SettingsRepository
) : MediaReferenceSource {

    override suspend fun messageAttachmentCount(fileName: String, excludingPersonaId: String?): Int =
        dao.countMessagesWithAttachment(MediaFileGc.likePattern(fileName), excludingPersonaId)

    override suspend fun personaAvatarNames(excludingPersonaId: String?): Set<String> =
        dao.personaAvatarUris(excludingPersonaId)
            .mapNotNull(MediaFileGc::fileNameOf)
            .toSet()

    override suspend fun presetAvatarNames(): Set<String> =
        dao.presetAvatarUris()
            .mapNotNull(MediaFileGc::fileNameOf)
            .toSet()

    override suspend fun userAvatarName(): String? =
        runCatching { settingsRepository.settings.first().userAvatarUri }
            .getOrNull()
            .let(MediaFileGc::fileNameOf)
}
