package com.myfriend.app.data.local

import androidx.room.Dao
import androidx.room.Query

/**
 * 媒体文件的**引用计数**查询（0.12 媒体去重的收尾）。
 *
 * 背景：聊天图片按内容寻址命名（`chat-<sha16>.<ext>`），同一张图只落一份；删除一条消息 /
 * 一段会话 / 一个人格时，文件只有在**再也没有任何行引用它**时才能删。这个 DAO 就是那句
 * 「还有引用吗」的唯一 SQL 出处（不新增表、不升 schema，纯查询）。
 *
 * ## 口径
 *
 * - **聊天图**：`messages.attachmentsJson` 里出现该文件名。跨会话、跨人格全局统计
 *   （去重本来就是全局的），`attachmentsJson` 为空的纯文本消息直接跳过。
 *   `messages` 是**硬删除**（删了就真没这行了，没有 `deleted` 软标记），所以「未删除消息」
 *   就是表里剩下的行——这也是「先删行、后查引用」顺序的由来。
 * - **头像**：`personas.avatarUri` / `persona_presets.avatarUri` 的**文件名**等于它，
 *   或者 DataStore 里的 `userAvatarUri`（见 [com.myfriend.app.data.repo.MediaReferences]）。
 *   库里存的是 `file://` 绝对路径，所以取文件名比较；这里只把候选值查出来，比较在代码层做
 *   （人格/预设的数量是几十级，不需要为它拼 LIKE）。
 *
 * ## LIKE 转义
 *
 * 文件名是 `chat-<sha16>` / `avatar-<sha16>-<uuid>`，正常不含 `%`/`_`，但库是
 * 「数据即代码」的输入面。模式串由 [com.myfriend.app.util.MediaFileGc.likePattern] 生成
 * （`%`、`_`、`\` 全部转义），这里的 `ESCAPE '\'` 与它配套；引用计数只会**偏高**
 * （宁可不删），但绝不能因为一个通配符把无关文件算成「有人引用」或反过来误删。
 */
@Dao
interface MediaReferenceDao {

    /**
     * 有多少条消息的附件里出现了 [pattern]。
     *
     * @param pattern `%<escaped fileName>%`（由 `MediaFileGc.likePattern` 生成）
     * @param excludingPersonaId 非空时排除这个人的消息——「删一个人」时用来算
     *   「这个文件除了他要删的行之外还有人引用吗」
     */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE attachmentsJson IS NOT NULL
          AND attachmentsJson LIKE :pattern ESCAPE '\'
          AND (:excludingPersonaId IS NULL OR personaId != :excludingPersonaId)
        """
    )
    suspend fun countMessagesWithAttachment(pattern: String, excludingPersonaId: String?): Int

    /** 所有人格的头像路径（`file://...`）；[excludingPersonaId] 非空时排除那一个人。 */
    @Query(
        """
        SELECT avatarUri FROM personas
        WHERE avatarUri IS NOT NULL
          AND (:excludingPersonaId IS NULL OR id != :excludingPersonaId)
        """
    )
    suspend fun personaAvatarUris(excludingPersonaId: String?): List<String>

    /** 所有人格预设的头像路径（`file://...`）。 */
    @Query("SELECT avatarUri FROM persona_presets WHERE avatarUri IS NOT NULL")
    suspend fun presetAvatarUris(): List<String>
}
