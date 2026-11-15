package com.myfriend.app.data.ai

/**
 * 从模型回复里捞出「第一个完整的 JSON 对象」。
 *
 * 模型经常：包一层 ```json 代码块、在 JSON 前后写一句「好的，这是结果：」、或者用全角引号。
 * 这里只做一件最基本、也最不容易出错的事——**按括号配对找第一个对象**，配对时跳过字符串
 * 内部的括号与转义；找不到或括号不闭合就返回 null，让调用方走兜底。
 *
 * 不做「修复 JSON」：把坏 JSON 硬补成能解析的样子，比重来一次更容易骗过自己。
 */
object GuidanceJson {

    fun firstObject(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> depth++
                char == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }
}
