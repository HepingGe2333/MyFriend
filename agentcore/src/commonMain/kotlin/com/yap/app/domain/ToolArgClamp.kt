package com.yap.app.domain

/**
 * 工具参数的夹取（模型给什么，都不该越界）。
 *
 * 模型时常给「差不多」的数字：`importance: 9`、`count: 50`、`minutes: 3000`。
 * 这些值如果直接落库/发请求，会出现「一条记忆重要到 9/5」「一次搜索要 50 条结果」
 * 「他说三天后再回」这类用户无法理解的状态。所以每一处入口都夹一次。
 *
 * 为什么单独一个文件、而且是纯 Kotlin：
 * - 夹取规则（1–5 / 1–12 / 5–720）是**两端必须一致**的事实，值得单独被契约用例钉住；
 * - 规则住在 `:agentcore`，Android 的 `ToolExecutor` 与 JVM 服务 `:agentd` 编译的是**同一份
 *   class**，不必为了一个 `coerceIn` 各自抄一遍、日后改一处漏一处。
 *
 * 注意：这里只做「数值夹取」，不做字符串清洗、不碰 JSON 解析、不碰数据库。
 */
object ToolArgClamp {

    // ---------------------------------------------------------------- remember

    /** 记忆重要度：1 很轻 … 5 很重要。 */
    const val IMPORTANCE_MIN = 1
    const val IMPORTANCE_MAX = 5

    /** 缺省重要度：模型没写时按「普通」记。 */
    const val IMPORTANCE_DEFAULT = 3

    // ---------------------------------------------------------------- web_search

    /** 一次搜索结果条数：至少 1，最多 12（再多只会把上下文塞满）。 */
    const val COUNT_MIN = 1
    const val COUNT_MAX = 12

    /** 缺省结果条数。 */
    const val COUNT_DEFAULT = 6

    // ---------------------------------------------------------------- defer_reply

    /** 推迟回复的分钟数：5 分钟到 12 小时（规格 §四）。 */
    const val MINUTES_MIN = 5
    const val MINUTES_MAX = 720

    /** 缺省推迟 45 分钟（规格 §四）。 */
    const val MINUTES_DEFAULT = 45

    // ---------------------------------------------------------------- set_activity

    /** 「占用时间」的分钟数：15 分钟到 8 小时（`spec/persona-guidance/README.md` §4）。 */
    const val ACTIVITY_MINUTES_MIN = 15
    const val ACTIVITY_MINUTES_MAX = 480

    /** 缺省 60 分钟：模型没写时按「一个小时」算。 */
    const val ACTIVITY_MINUTES_DEFAULT = 60

    /** `set_activity` 的 kind 枚举；认不出的值一律 OTHER。 */
    val ACTIVITY_KINDS = listOf("WORK", "COMMUTE", "MEAL", "SOCIAL", "CHORE", "STUDY", "REST", "OTHER")

    /** `set_activity` 的 availability 枚举；认不出的值按最常见、最保守的 BUSY。 */
    val ACTIVITY_AVAILABILITIES = listOf("BUSY", "LIMITED", "FREE")

    fun clampImportance(value: Int): Int = value.coerceIn(IMPORTANCE_MIN, IMPORTANCE_MAX)

    fun clampCount(value: Int): Int = value.coerceIn(COUNT_MIN, COUNT_MAX)

    fun clampDeferMinutes(value: Int): Int = value.coerceIn(MINUTES_MIN, MINUTES_MAX)

    /** 活动时长：越界夹到边界。 */
    fun clampActivityMinutes(value: Int): Int =
        value.coerceIn(ACTIVITY_MINUTES_MIN, ACTIVITY_MINUTES_MAX)

    /**
     * 活动时长的宽松入口（`tool-args.json` 的 `minutes: "abc"` 用例）。
     *
     * 非数字 / 空 / 认不出 → [ACTIVITY_MINUTES_DEFAULT]，再夹一次边界。
     * 数字字符串按数字处理（模型经常把数字写成字符串）。
     */
    fun clampActivityMinutes(raw: String?): Int {
        val parsed = raw?.trim()?.toIntOrNull() ?: return ACTIVITY_MINUTES_DEFAULT
        return clampActivityMinutes(parsed)
    }

    /** kind 归一化：大小写不敏感；空/认不出 → OTHER。 */
    fun normalizeActivityKind(raw: String?): String {
        val cleaned = raw?.trim()?.uppercase() ?: ""
        return if (cleaned in ACTIVITY_KINDS) cleaned else "OTHER"
    }

    /** availability 归一化：大小写不敏感；空/认不出 → BUSY。 */
    fun normalizeActivityAvailability(raw: String?): String {
        val cleaned = raw?.trim()?.uppercase() ?: ""
        return if (cleaned in ACTIVITY_AVAILABILITIES) cleaned else "BUSY"
    }
}
