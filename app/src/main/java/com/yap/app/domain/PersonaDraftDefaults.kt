package com.yap.app.domain

/**
 * 「名字 / 提示词留空也能保存」的兜底规则（0.13）。
 *
 * 真机反馈：新建人格或预设时，名字或提示词只要有一项没填就保存不了，甚至表现为
 * 「保存了却无法启动」。产品上唯一必须成立的约束是**最终能生成一段提示词**，
 * 其余字段（世界、话风、作息、活动……）全部可空。于是把「空 → 默认」的规则收在这里：
 *
 * - 纯函数、不依赖 Android：编辑页和仓储层（预设生成人格、导入、复制等不经过编辑页的入口）
 *   共用同一套规则，JVM 单测可以直接钉死每一种组合；
 * - 文案不在这里：默认名 / 默认人设 / 中英文的分隔符都由调用方从资源里取字符串传进来，
 *   domain 不持有界面文案（否则英文界面会冒出中文）。
 */
object PersonaDraftDefaults {

    /**
     * 空白名字落成本地化默认名；用户写了什么就只做 trim 后原样返回。
     *
     * 不在这里截断长度：长度上限是仓储层的既有校验（人格 80 / 预设 60），
     * 在这里悄悄截短会让「名字太长」的报错永远触发不了，用户也看不出被改名了。
     */
    fun name(raw: String, fallback: String): String {
        val trimmed = raw.trim()
        return if (trimmed.isEmpty()) fallback.trim() else trimmed
    }

    /**
     * 提示词为空时按「名字 + 简介」生成一句：
     * 两者都有 → `名字 + separator + 简介`；只有一个 → 用那一个；都没有 → 系统默认人设。
     *
     * 空白判断发生在 trim 之后：只敲了几个空格的输入和完全不填等价。
     * [name] 传用户原始输入（不传落库用的默认名）：名字空着、简介有时，
     * 生成的应该是那句简介，而不是「未命名：简介」。
     */
    fun prompt(
        rawPrompt: String,
        name: String,
        description: String,
        separator: String,
        fallback: String
    ): String {
        val explicit = rawPrompt.trim()
        if (explicit.isNotEmpty()) return explicit
        val cleanName = name.trim()
        val cleanDescription = description.trim()
        return when {
            cleanName.isNotEmpty() && cleanDescription.isNotEmpty() -> cleanName + separator + cleanDescription
            cleanName.isNotEmpty() -> cleanName
            cleanDescription.isNotEmpty() -> cleanDescription
            else -> fallback.trim()
        }
    }
}
