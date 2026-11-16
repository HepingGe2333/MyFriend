package com.yap.app.data.repo

import com.yap.app.data.ai.PersonaTranslationResult
import com.yap.app.data.local.PersonaEntity
import com.yap.app.domain.PersonaTranslation
import com.yap.app.domain.PersonaTranslationCodec
import com.yap.app.domain.PersonaTranslationSource
import com.yap.app.domain.SpeechStyle
import com.yap.app.domain.TranslatedPersona
import com.yap.app.domain.WorldPreset
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「开始人格之前按需翻译」的调度规则（`spec/persona-translation/README.md` §2 / §4 / §7）。
 *
 * 这里测的**不是译文质量**，而是四件会直接影响用户体验与账单的事：
 * 1. 缓存命中零请求；源=目标零请求；关掉开关零请求；
 * 2. 失败不写缓存（否则下次会被当成「命中」，永远不会重试）；
 * 3. 同一 `personaId + lang + sourceHash` 的并发调用只发一次请求；
 * 4. 手动「重新翻译」无视开关、先清旧缓存。
 */
class PersonaTranslationCoordinatorTest {

    private fun persona(
        name: String = "林深",
        description: String = "建筑事务所项目负责人，最近在赶图。",
        translationJson: String? = null
    ) = PersonaEntity(
        id = "persona-1",
        name = name,
        description = description,
        avatarUri = null,
        avatarSeed = 0,
        prompt = "你是林深，说话简短。",
        category = "日常聊天",
        version = 1,
        defaultApiConfigId = null,
        modelOverride = null,
        isDefault = true,
        createdAt = 1L,
        updatedAt = 1L,
        translationJson = translationJson,
        translationLang = translationJson?.let { PersonaTranslationCodec.decode(it)?.lang }
    )

    /** 假网关：内存里存一份人格与缓存，并记下「真的发了几次请求」。 */
    private class FakeGateway(
        var current: PersonaEntity?,
        private val enabled: Boolean = true,
        private val replyOk: Boolean = true,
        private val delayMs: Long = 0L,
        private val translatedName: String = "Lin Shen"
    ) : PersonaTranslationGateway {
        var translateCalls = 0
        var writes = 0
        var lastWritten: String? = null

        override suspend fun persona(id: String): PersonaEntity? = current

        override suspend fun writeCache(personaId: String, translationJson: String?, lang: String?) {
            writes++
            lastWritten = translationJson
            current = current?.copy(
                translationJson = translationJson,
                translationLang = lang
            )
        }

        override suspend fun autoTranslateEnabled(): Boolean = enabled

        override suspend fun translate(
            persona: PersonaEntity,
            targetLang: String
        ): PersonaTranslationResult {
            translateCalls++
            if (delayMs > 0) delay(delayMs)
            val hash = PersonaTranslationSource.hashOf(persona)
            val translated = PersonaTranslation(
                lang = targetLang,
                translatedAt = 42L,
                sourceHash = hash,
                persona = TranslatedPersona(
                    name = translatedName,
                    description = "Architect, busy drawing.",
                    traits = listOf("reserved"),
                    systemPrompt = "You are Lin."
                ),
                world = WorldPreset(worldName = "Lingang", era = "2026"),
                speech = SpeechStyle(
                    tone = "short and direct",
                    habits = listOf("very short sentences"),
                    avoid = listOf("long speeches")
                )
            )
            return PersonaTranslationResult(
                translation = if (replyOk) translated else translated.copy(
                    persona = TranslatedPersona(),
                    world = null
                ),
                ok = replyOk
            )
        }

        override fun appLanguage(): String = "en"
    }

    private fun coordinator(gateway: FakeGateway) = PersonaTranslationCoordinator(gateway)

    @Test
    fun `缓存命中时零请求`() = runBlocking {
        val base = persona()
        val hash = PersonaTranslationSource.hashOf(base)
        val cache = PersonaTranslationCodec.encode(
            PersonaTranslation(
                lang = "en",
                translatedAt = 1L,
                sourceHash = hash,
                persona = TranslatedPersona(name = "Lin Shen"),
                world = null
            )
        )
        val gateway = FakeGateway(base.copy(translationJson = cache, translationLang = "en"))
        val outcome = coordinator(gateway).ensureTranslated("persona-1")
        assertEquals(PersonaTranslationOutcome.CACHED, outcome)
        assertEquals("缓存命中不该发请求", 0, gateway.translateCalls)
    }

    @Test
    fun `关掉开关时零请求`() = runBlocking {
        val gateway = FakeGateway(persona(), enabled = false)
        assertEquals(
            PersonaTranslationOutcome.DISABLED,
            coordinator(gateway).ensureTranslated("persona-1")
        )
        assertEquals(0, gateway.translateCalls)
    }

    @Test
    fun `源语言等于目标语言时跳过`() = runBlocking {
        val gateway = FakeGateway(persona())
        val coordinator = PersonaTranslationCoordinator(
            object : PersonaTranslationGateway by gateway {
                override fun appLanguage(): String = "zh"
            }
        )
        assertEquals(
            PersonaTranslationOutcome.SAME_LANGUAGE,
            coordinator.ensureTranslated("persona-1")
        )
        assertEquals("中文人设配中文界面不该翻译", 0, gateway.translateCalls)
    }

    @Test
    fun `成功时写缓存`() = runBlocking {
        val gateway = FakeGateway(persona())
        val outcome = coordinator(gateway).ensureTranslated("persona-1")
        assertEquals(PersonaTranslationOutcome.TRANSLATED, outcome)
        assertEquals(1, gateway.translateCalls)
        assertEquals(1, gateway.writes)
        val written = PersonaTranslationCodec.decode(gateway.lastWritten)
        assertNotNull("缓存必须能被解析", written)
        assertEquals("en", written!!.lang)
        assertEquals("Lin Shen", written.persona.name)
        assertEquals("Lingang", written.world?.worldName)
        assertEquals("short and direct", written.speech?.tone)
        assertEquals(listOf("very short sentences"), written.speech?.habits)
    }

    @Test
    fun `失败时用原文且不写缓存`() = runBlocking {
        val gateway = FakeGateway(persona(), replyOk = false)
        val outcome = coordinator(gateway).ensureTranslated("persona-1")
        assertEquals(PersonaTranslationOutcome.FAILED, outcome)
        assertEquals(1, gateway.translateCalls)
        assertEquals("失败不能留下假缓存", 0, gateway.writes)
        assertNull(gateway.current?.translationJson)
    }

    @Test
    fun `并发请求同一个人的同一种语言只发一次`() = runBlocking {
        val gateway = FakeGateway(persona(), delayMs = 120)
        val coordinator = coordinator(gateway)
        val results = listOf(
            async { coordinator.ensureTranslated("persona-1") },
            async { coordinator.ensureTranslated("persona-1") },
            async { coordinator.ensureTranslated("persona-1") }
        ).awaitAll()
        assertEquals("三次并发只该发一次请求", 1, gateway.translateCalls)
        assertTrue("每个等待者都要拿到同一份结果", results.all { it == PersonaTranslationOutcome.TRANSLATED })
    }

    @Test
    fun `手动重译无视开关并清掉旧缓存`() = runBlocking {
        val base = persona()
        val stale = PersonaTranslationCodec.encode(
            PersonaTranslation(
                lang = "en",
                translatedAt = 1L,
                sourceHash = "sha256:stale",
                persona = TranslatedPersona(name = "Old"),
                world = null
            )
        )
        val gateway = FakeGateway(base.copy(translationJson = stale, translationLang = "en"), enabled = false)
        val outcome = coordinator(gateway).ensureTranslated("persona-1", force = true)
        assertEquals("手动重译不受开关影响", PersonaTranslationOutcome.TRANSLATED, outcome)
        assertEquals(1, gateway.translateCalls)
        assertTrue("旧缓存必须先被清掉", gateway.writes >= 2)
    }

    @Test
    fun `人格不存在时安静返回`() = runBlocking {
        val gateway = FakeGateway(null)
        assertEquals(
            PersonaTranslationOutcome.MISSING,
            coordinator(gateway).ensureTranslated("gone")
        )
        assertEquals(0, gateway.translateCalls)
    }

    @Test
    fun `prefetch 永远不抛异常`() = runBlocking {
        val gateway = FakeGateway(null)
        coordinator(gateway).prefetch("gone")
        coordinator(FakeGateway(persona())).prefetch("persona-1")
    }
}
