package com.yap.app.domain

/**
 * 版本历史里那句「为什么会有这个版本」。
 *
 * 以前这里是直接拼好的中文（"创建人格" / "编辑前版本" / "恢复至 v3" / "自我调整：话少一点了"），
 * 存进 `persona_revisions.reason` 后由「成长」页原样显示——英文界面会看到中文。
 *
 * 现在存一个稳定的 ASCII 令牌（`@rev:<kind>[:<arg>]`）：
 * - 数据层只决定「是哪种原因」，不决定用哪种语言说；
 * - UI 侧 [com.yap.app.ui.components.RevisionReasonText] 负责映射 `R.string.reason_*`；
 * - 老库里已经有中文 reason，`decode` 解不出来时 UI 原样显示，不改写历史数据。
 */
object PersonaRevisionReason {
    const val PREFIX = "@rev:"
    private const val SEP = ":"

    /** 编辑器里新建人格时写入人设卡。 */
    val CARD_CREATED = PREFIX + "card_created"

    /** 编辑器里改了人设卡。 */
    val CARD_EDITED = PREFIX + "card_edited"

    /** 改卡之前的快照。 */
    val CARD_BEFORE_ADJUST = PREFIX + "card_before_adjust"

    /** 人格首次创建（表单保存）。 */
    val CREATED = PREFIX + "created"

    /** 编辑前的快照。 */
    val BEFORE_EDIT = PREFIX + "before_edit"

    /** 编辑后的人格。 */
    val EDITED = PREFIX + "edited"

    /** 复制出来的人格。 */
    val COPIED = PREFIX + "copied"

    /** 恢复历史版本之前的快照。 */
    val BEFORE_RESTORE = PREFIX + "before_restore"

    /** 进化提案应用之前的快照。 */
    val BEFORE_EVOLUTION = PREFIX + "before_evolution"

    /** 初始版本（种子人格 / 老库补齐）。 */
    val BASELINE = PREFIX + "baseline"

    /** 从文件导入的人格。 */
    val IMPORTED = PREFIX + "imported"

    /** 他自己改了人设卡：[summary] 是他当时给的理由/摘要。 */
    fun selfAdjusted(summary: String): String = PREFIX + "self_adjusted" + SEP + sanitize(summary)

    /** 恢复到了某一版。 */
    fun restored(version: Int): String = PREFIX + "restored" + SEP + version

    /** 人格进化：[summary] 是这次进化的方向。 */
    fun evolved(summary: String): String = PREFIX + "evolved" + SEP + sanitize(summary)

    /** 解出来的展示模型；不是令牌时返回 null（老数据原样显示）。 */
    fun decode(raw: String?): Label? {
        val text = raw?.trim().orEmpty()
        if (!text.startsWith(PREFIX)) return null
        val rest = text.removePrefix(PREFIX)
        val kind = rest.substringBefore(SEP)
        val arg = rest.substringAfter(SEP, "").takeIf { it.isNotBlank() }
        return when (kind) {
            "card_created" -> Label(Kind.CARD_CREATED)
            "card_edited" -> Label(Kind.CARD_EDITED)
            "card_before_adjust" -> Label(Kind.CARD_BEFORE_ADJUST)
            "created" -> Label(Kind.CREATED)
            "before_edit" -> Label(Kind.BEFORE_EDIT)
            "edited" -> Label(Kind.EDITED)
            "copied" -> Label(Kind.COPIED)
            "before_restore" -> Label(Kind.BEFORE_RESTORE)
            "before_evolution" -> Label(Kind.BEFORE_EVOLUTION)
            "baseline" -> Label(Kind.BASELINE)
            "imported" -> Label(Kind.IMPORTED)
            "self_adjusted" -> Label(Kind.SELF_ADJUSTED, detail = arg)
            "restored" -> Label(Kind.RESTORED, version = arg?.toIntOrNull())
            "evolved" -> Label(Kind.EVOLVED, detail = arg)
            else -> null
        }
    }

    fun isToken(raw: String?): Boolean = raw?.trim()?.startsWith(PREFIX) == true

    private fun sanitize(value: String): String =
        value.replace('\n', ' ').replace(SEP, " ").trim().take(80)

    enum class Kind {
        CARD_CREATED,
        CARD_EDITED,
        CARD_BEFORE_ADJUST,
        CREATED,
        BEFORE_EDIT,
        EDITED,
        COPIED,
        BEFORE_RESTORE,
        RESTORED,
        BEFORE_EVOLUTION,
        EVOLVED,
        BASELINE,
        IMPORTED,
        SELF_ADJUSTED
    }

    data class Label(
        val kind: Kind,
        val version: Int? = null,
        /** 模型给的摘要/理由：属于他的原话，不翻译。 */
        val detail: String? = null
    )
}
