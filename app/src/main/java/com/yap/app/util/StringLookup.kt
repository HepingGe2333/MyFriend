package com.yap.app.util

import android.content.Context
import androidx.annotation.StringRes

/**
 * 按资源 id 取一句 UI 文案。
 *
 * 存在的唯一理由：有些纯拼句子的工具（例如记忆整理结果）要在 JVM 单测里跑，
 * 又不想把资源 id 散落在业务代码里。生产侧由 [androidStringLookup] 接 [Context]，
 * 单测侧传一个假实现，句子本身仍然只写在 res 下的 strings.xml 里。
 */
fun interface StringLookup {
    fun get(@StringRes id: Int, vararg args: Any): String
}

/** 生产实现：走 App 当前的资源配置（语言随系统）。 */
fun androidStringLookup(context: Context): StringLookup = StringLookup { id, args ->
    context.getString(id, *args)
}
