package com.yap.app.domain

import java.util.Locale

/**
 * 系统提示词里「用对方的语言回答」那一节。
 *
 * 提示词脚手架本身继续用中文（那是给模型看的，不是给用户看的），只有这一节跟着 App 语言走：
 * 中文系统给它一段中文说明，英文系统给一段英文说明——模型两种都读得懂，
 * 让它照着对方此刻说的语言回答，专有名词可以保留原文。
 *
 * 只做纯拼装，不读 Android 资源：模板由调用方从 `R.string.prompt_language_directive` 取，
 * 这样「语言名」和「拼在 system 末尾」这两件事都能单测。
 */
object LanguageDirective {

    /** 和上一节之间空一行，粘在 system 的最后。 */
    private const val SECTION_SEPARATOR = "\n\n"

    fun section(template: String, languageName: String): String =
        SECTION_SEPARATOR + String.format(Locale.ROOT, template, languageName)

    /**
     * 对方现在说什么语言，用语言自己的说法：中文 / English / …
     *
     * 只取语言、不取地区：`zh-CN` 说「中文」，`en-US` 说「English」，
     * 而不是「中文 (中国)」这种在句子里读起来别扭的全名。
     */
    fun languageName(locale: Locale = Locale.getDefault()): String {
        val tag = locale.language.ifBlank { DEFAULT_LANGUAGE }
        val languageOnly = Locale.forLanguageTag(tag)
        val name = languageOnly.getDisplayName(languageOnly).trim()
        return name.ifBlank { tag }
    }

    /** 认不出语言时按中文算：默认语言就是中文。 */
    private const val DEFAULT_LANGUAGE = "zh"
}
