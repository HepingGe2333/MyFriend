package com.myfriend.app.domain

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

    fun clampImportance(value: Int): Int = value.coerceIn(IMPORTANCE_MIN, IMPORTANCE_MAX)

    fun clampCount(value: Int): Int = value.coerceIn(COUNT_MIN, COUNT_MAX)

    fun clampDeferMinutes(value: Int): Int = value.coerceIn(MINUTES_MIN, MINUTES_MAX)
}
