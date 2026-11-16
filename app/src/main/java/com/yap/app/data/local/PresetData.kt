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

    fun build(context: Context, now: Long): List<PersonaEntity> = SEEDS.map { spec ->
        PersonaEntity(
            id = UUID.randomUUID().toString(),
            name = context.getString(nameRes(spec.index)),
            description = context.getString(descriptionRes(spec.index)),
            avatarUri = null,
            avatarSeed = spec.avatarSeed,
            prompt = context.getString(promptRes(spec.index)),
            category = context.getString(categoryRes(spec.index)),
            version = 1,
            defaultApiConfigId = null,
            modelOverride = null,
            isDefault = spec.isDefault,
            createdAt = now,
            updatedAt = now
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
