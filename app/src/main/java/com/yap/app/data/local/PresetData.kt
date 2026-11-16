package com.yap.app.data.local

import android.content.Context
import com.yap.app.R
import java.util.UUID

/**
 * 首次安装时放进去的三个人格。
 *
 * 名字、简介、提示词、分类都从资源读（`res/values/seed.xml` 与 `res/values-en/seed.xml`）：
 * 中文系统得到林深 / 阿岚 / 墨，英文系统得到英文原生的 Elias / Finn / Ansel。
 *
 * id 仍然是首次启动时生成的 UUID，seed 判定逻辑没有变——老用户升级上来不会被重新 seed，
 * 已经存在的人格更不会被翻译改写。语言只影响「第一次见面时长什么样」。
 */
object SeedPersonas {

    /** 三个种子人格的顺序是稳定的：index 决定读哪组资源，avatarSeed 决定默认头像。 */
    private val SEEDS = listOf(
        SeedSpec(index = 1, avatarSeed = 2, isDefault = true),
        SeedSpec(index = 2, avatarSeed = 5, isDefault = false),
        SeedSpec(index = 3, avatarSeed = 8, isDefault = false)
    )

    fun build(context: Context, now: Long): List<PersonaEntity> =
        build(now) { resId -> context.getString(resId) }

    /**
     * 资源解耦的那一层：`stringOf` 只负责把资源 id 变成一句话。
     *
     * 单测（纯 JVM，没有 `Context`）用一张假的名字表跑同一段构造逻辑，
     * 这样「种子人格默认不睡 / 唤醒阈值 3」这类默认值是**真的被测到**，而不是靠读源码。
     */
    internal fun build(now: Long, stringOf: (Int) -> String): List<PersonaEntity> = SEEDS.map { spec ->
        PersonaEntity(
            id = UUID.randomUUID().toString(),
            name = stringOf(nameRes(spec.index)),
            description = stringOf(descriptionRes(spec.index)),
            avatarUri = null,
            avatarSeed = spec.avatarSeed,
            prompt = stringOf(promptRes(spec.index)),
            category = stringOf(categoryRes(spec.index)),
            version = 1,
            defaultApiConfigId = null,
            modelOverride = null,
            isDefault = spec.isDefault,
            createdAt = now,
            updatedAt = now,
            // 0.12 续 7：新装的人不睡。睡眠是可选功能——打开它（并在人格编辑里设时段/阈值）
            // 才算「他会睡」；种子人格默认打开等于「半夜发什么都没反应」，那是最糟的第一次见面。
            sleepEnabled = false
        )
    }

    private fun nameRes(index: Int): Int = when (index) {
        1 -> R.string.seed_persona_1_name
        2 -> R.string.seed_persona_2_name
        else -> R.string.seed_persona_3_name
    }

    private fun descriptionRes(index: Int): Int = when (index) {
        1 -> R.string.seed_persona_1_description
        2 -> R.string.seed_persona_2_description
        else -> R.string.seed_persona_3_description
    }

    private fun promptRes(index: Int): Int = when (index) {
        1 -> R.string.seed_persona_1_prompt
        2 -> R.string.seed_persona_2_prompt
        else -> R.string.seed_persona_3_prompt
    }

    private fun categoryRes(index: Int): Int = when (index) {
        1 -> R.string.seed_persona_1_category
        2 -> R.string.seed_persona_2_category
        else -> R.string.seed_persona_3_category
    }

    private data class SeedSpec(val index: Int, val avatarSeed: Int, val isDefault: Boolean)
}
