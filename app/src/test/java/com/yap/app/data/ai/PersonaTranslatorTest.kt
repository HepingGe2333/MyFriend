package com.yap.app.data.ai

import com.yap.app.data.local.PersonaEntity
import com.yap.app.domain.PersonaProfile
import com.yap.app.domain.PersonaTranslationSource
import com.yap.app.domain.SpeechStyle
import com.yap.app.domain.SpeechStyleCodec
import com.yap.app.domain.UsageKind
import com.yap.app.domain.WorldPreset
import com.yap.app.domain.WorldPresetCodec
import com.yap.app.domain.WorldRelationship
import com.yap.app.domain.WorldScheduleEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人设翻译（`spec/persona-translation/README.md` §3 / §5 / §7）。
 *
 * 用假 [JsonLlmClient] 把每条回退路径跑一遍：跳过（源=目标）、正常译文、缺字段、
 * 坏 JSON、无配置（null）、超时。另外两条契约：
 * 1. **请求只带人设/世界**——没有聊天记录、记忆、API Key，也没有 `phrases`（§6）；
 * 2. 系统提示词逐字等于 §5 原文，只替换 `{目标语言}`。
 */
class PersonaTranslatorTest {

    private class FakeLlm(
        private val reply: String?,
        private val delayMs: Long = 0L
    ) : JsonLlmClient {
        var calls: Int = 0
        var lastSystem: String? = null
        var lastUser: String? = null
        var lastConfigId: String? = null
        var lastKind: String? = null
        var lastTemperature: Double? = null
        var lastMaxOutputTokens: Int? = null

        override suspend fun completeJson(
            system: String,
            user: String,
            configId: String?,
            kind: String,
            temperature: Double,
            maxOutputTokens: Int
        ): String? {
            calls++
            lastSystem = system
            lastUser = user
            lastConfigId = configId
            lastKind = kind
            lastTemperature = temperature
            lastMaxOutputTokens = maxOutputTokens
            if (delayMs > 0) delay(delayMs)
            return reply
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val world = WorldPreset(
        worldName = "临港新城",
        era = "当代，中国沿海新一线城市",
        description = "他带着六人小组赶一个高层住宅项目。",
        schedule = listOf(WorldScheduleEntry("工作日", "09:00", "18:30", "画图、审图")),
        relationships = listOf(WorldRelationship("母亲", "母亲", "每周三晚打电话", intimacy = 4)),
        obligations = listOf("按节点交图"),
        notes = "他怕冷，也怕吵。"
    )

    private fun persona(overrides: PersonaEntity.() -> PersonaEntity = { this }) = PersonaEntity(
        id = "p1",
        name = "林深",
        description = "建筑事务所的项目负责人。",
        avatarUri = null,
        avatarSeed = 1,
        prompt = "你是林深，一个慢热的人。",
        category = "朋友",
        version = 1,
        defaultApiConfigId = null,
        modelOverride = null,
        isDefault = true,
        createdAt = 0,
        updatedAt = 0,
        traits = PersonaProfile.encodeList(listOf("慢热", "嘴硬心软")),
        likes = PersonaProfile.encodeList(listOf("夜里散步")),
        behaviors = PersonaProfile.encodeList(listOf("先听完再给建议")),
        backstory = "在南方长大。",
        quirks = "不说「加油」。",
        worldJson = WorldPresetCodec.encode(world),
        speechStyleJson = SpeechStyleCodec.encode(
            SpeechStyle(
                // 常用语永不外发（§3），这里放一个哨兵值，请求里出现它就说明漏了。
                tone = "简短、口语",
                phrases = listOf(PHRASE_SENTINEL),
                habits = listOf("句子很短"),
                avoid = listOf("长篇大论")
            )
        )
    ).overrides()

    private val replyEn = """
        {
          "persona": {
            "name": "Lin Shen",
            "description": "He runs the project at an architecture firm.",
            "traits": ["Slow to warm", "Tough shell, soft heart"],
            "likes": ["Night walks"],
            "behaviors": ["Listens before giving advice"],
            "backstory": "Grew up in the south.",
            "quirks": "Never says \"cheer up\".",
            "systemPrompt": "You are Lin Shen, someone who warms up slowly."
          },
          "world": {
            "worldName": "Lingang New City",
            "era": "Present day, a coastal Chinese city",
            "description": "He leads a six-person team on a high-rise residential project.",
            "schedule": [
              {"day": "Weekday", "start": "09:00", "end": "18:30", "activity": "Drawing and reviewing plans"}
            ],
            "relationships": [
              {"name": "Mother", "relation": "Mother", "note": "Calls every Wednesday evening"}
            ],
            "obligations": ["Deliver drawings on deadline"],
            "notes": "He hates the cold, and noise."
          },
          "speech": {
            "tone": "short and direct",
            "habits": ["very short sentences"],
            "avoid": ["long speeches"]
          }
        }
    """.trimIndent()

    private fun translate(
        llm: JsonLlmClient,
        entity: PersonaEntity = persona(),
        targetLang: String = "en",
        configId: String? = null,
        timeoutMs: Long = PersonaTranslator.TIMEOUT_MS,
        now: Long = 1794500000000L
    ): PersonaTranslationResult = runBlocking {
        PersonaTranslator(llm).translate(entity, targetLang, configId, timeoutMs, now)
    }

    // ---- 跳过 -------------------------------------------------------------------------------

    @Test
    fun `源语言等于目标语言时跳过 零请求`() {
        val llm = FakeLlm(reply = replyEn)
        val entity = persona()
        val result = translate(llm, entity, targetLang = "zh", now = 42L)

        assertEquals(0, llm.calls)
        assertTrue(result.ok)
        assertTrue(result.skipped)
        assertFalse(result.failed)
        assertTrue(result.translation.persona.isEmpty())
        assertNull(result.translation.world)
        assertEquals("zh", result.translation.lang)
        assertEquals(42L, result.translation.translatedAt)
        assertEquals(PersonaTranslationSource.hashOf(entity), result.translation.sourceHash)
    }

    // ---- 正常译文 ---------------------------------------------------------------------------

    @Test
    fun `正常译文逐字段替换 世界也一起翻译`() {
        val result = translate(FakeLlm(reply = replyEn))
        assertTrue(result.ok)
        assertFalse(result.skipped)

        val translation = result.translation
        val translated = translation.persona
        assertEquals("Lin Shen", translated.name)
        assertEquals("He runs the project at an architecture firm.", translated.description)
        assertEquals(listOf("Slow to warm", "Tough shell, soft heart"), translated.traits)
        assertEquals(listOf("Night walks"), translated.likes)
        assertEquals(listOf("Listens before giving advice"), translated.behaviors)
        assertEquals("Grew up in the south.", translated.backstory)
        assertEquals("Never says \"cheer up\".", translated.quirks)
        assertEquals("You are Lin Shen, someone who warms up slowly.", translated.systemPrompt)

        val translatedWorld = translation.world
        assertNotNull(translatedWorld)
        assertEquals("Lingang New City", translatedWorld!!.worldName)
        assertEquals("Present day, a coastal Chinese city", translatedWorld.era)
        assertEquals("He leads a six-person team on a high-rise residential project.", translatedWorld.description)
        assertEquals("He hates the cold, and noise.", translatedWorld.notes)
        assertEquals(listOf("Deliver drawings on deadline"), translatedWorld.obligations)
        assertEquals("Mother", translatedWorld.relationships.single().name)
        assertEquals("Calls every Wednesday evening", translatedWorld.relationships.single().note)
        // 模型照 §3 把 day 也译了，但 day 只认中文白名单：回退原文，activity 保留译文
        assertEquals("工作日", translatedWorld.schedule.single().day)
        assertEquals("Drawing and reviewing plans", translatedWorld.schedule.single().activity)
        // 亲密度是 Android 端扩展，模型漏掉时按同下标补回
        assertEquals(4, translatedWorld.relationships.single().intimacy)

        // 话风：tone/habits/avoid 翻译，phrases 永远保留原文（§3）。
        val translatedSpeech = translation.speech
        assertNotNull(translatedSpeech)
        assertEquals("short and direct", translatedSpeech!!.tone)
        assertEquals(listOf("very short sentences"), translatedSpeech.habits)
        assertEquals(listOf("long speeches"), translatedSpeech.avoid)
        assertEquals(listOf(PHRASE_SENTINEL), translatedSpeech.phrases)
    }

    @Test
    fun `缺一个字段时只回退那一个字段 话风也逐字段回退`() {
        val reply = """{"persona":{"name":"Lin Shen"},"world":null,"speech":{"tone":"short"}}"""
        val result = translate(FakeLlm(reply = reply))
        assertTrue(result.ok)
        assertEquals("Lin Shen", result.translation.persona.name)
        // 其余字段逐个回退原文，不是整份丢弃
        assertEquals("建筑事务所的项目负责人。", result.translation.persona.description)
        assertEquals(listOf("慢热", "嘴硬心软"), result.translation.persona.traits)
        assertEquals("在南方长大。", result.translation.persona.backstory)
        assertEquals("你是林深，一个慢热的人。", result.translation.persona.systemPrompt)
        assertEquals("临港新城", result.translation.world?.worldName)
        // speech.habits/avoid 缺失 → 各自回退原文；phrases 始终原文。
        val speech = result.translation.speech
        assertNotNull(speech)
        assertEquals("short", speech!!.tone)
        assertEquals(listOf("句子很短"), speech.habits)
        assertEquals(listOf("长篇大论"), speech.avoid)
        assertEquals(listOf(PHRASE_SENTINEL), speech.phrases)
    }

    // ---- 失败回退 ---------------------------------------------------------------------------

    @Test
    fun `坏 JSON 全部回退原文并标记失败`() {
        val entity = persona()
        val result = translate(FakeLlm(reply = "抱歉，这次没能翻译。"))

        assertFalse(result.ok)
        assertTrue(result.failed)
        assertFalse(result.skipped)
        assertEquals("林深", result.translation.persona.name)
        assertEquals("建筑事务所的项目负责人。", result.translation.persona.description)
        assertEquals("临港新城", result.translation.world?.worldName)
        assertEquals("句子很短", result.translation.speech?.habits?.single())
        assertEquals("en", result.translation.lang)
        assertEquals(PersonaTranslationSource.hashOf(entity), result.translation.sourceHash)
        assertEquals(1794500000000L, result.translation.translatedAt)
    }

    @Test
    fun `没有配置时用原文 且按 TRANSLATION 记账`() {
        val llm = FakeLlm(reply = null)
        val result = translate(llm, configId = "cfg-1")

        assertEquals(1, llm.calls)
        assertEquals(UsageKind.TRANSLATION, llm.lastKind)
        assertEquals("cfg-1", llm.lastConfigId)
        assertEquals(0.2, llm.lastTemperature!!, 0.0001)
        assertEquals(PersonaTranslator.MAX_OUTPUT_TOKENS, llm.lastMaxOutputTokens)
        assertFalse(result.ok)
        assertTrue(result.failed)
        assertEquals("林深", result.translation.persona.name)
        assertEquals("临港新城", result.translation.world?.worldName)
    }

    @Test
    fun `超时按失败处理 不拖住调用方`() {
        val llm = FakeLlm(reply = replyEn, delayMs = 300)
        val started = System.currentTimeMillis()
        val result = translate(llm, timeoutMs = 50)
        val elapsed = System.currentTimeMillis() - started

        assertFalse(result.ok)
        assertEquals("林深", result.translation.persona.name)
        assertTrue("超时应在超时点附近返回，实际 $elapsed ms", elapsed < 300)
    }

    // ---- 隐私与提示词 -----------------------------------------------------------------------

    @Test
    fun `请求带人设 世界与话风 不含聊天 记忆 Key 和 phrases`() {
        val llm = FakeLlm(reply = null)
        translate(llm)
        val user = requireNotNull(llm.lastUser) { "应当发出一次请求" }

        assertFalse(user.contains(CHAT_SENTINEL))
        assertFalse(user.contains(MEMORY_SENTINEL))
        assertFalse(user.contains(API_KEY_SENTINEL))
        assertFalse(user.contains(PHRASE_SENTINEL))
        assertFalse(user.contains("phrases"))

        val root = json.parseToJsonElement(user).jsonObject
        assertEquals(setOf("persona", "world", "speech"), root.keys)
        val speechJson = root["speech"]!!.jsonObject
        assertEquals(setOf("tone", "habits", "avoid"), speechJson.keys)
        assertEquals("简短、口语", speechJson["tone"]!!.jsonPrimitive.content)
        assertEquals(listOf("句子很短"), speechJson["habits"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("长篇大论"), speechJson["avoid"]!!.jsonArray.map { it.jsonPrimitive.content })
        val personaJson = root["persona"]!!.jsonObject
        assertEquals(
            setOf(
                "name", "description", "traits", "likes", "behaviors",
                "backstory", "quirks", "systemPrompt"
            ),
            personaJson.keys
        )
        assertEquals("林深", personaJson["name"]!!.jsonPrimitive.content)
        val worldJson = root["world"]!!.jsonObject
        assertEquals("临港新城", worldJson["worldName"]!!.jsonPrimitive.content)
        assertEquals(
            "画图、审图",
            worldJson["schedule"]!!.jsonArray[0].jsonObject["activity"]!!.jsonPrimitive.content
        )

        assertEquals(EXPECTED_PROMPT_EN, llm.lastSystem)
        assertEquals(EXPECTED_PROMPT_ZH, PersonaTranslator.systemPrompt("zh-Hans"))
        assertFalse(PersonaTranslator.PROMPT_TEMPLATE.contains("English"))
        assertTrue(PersonaTranslator.PROMPT_TEMPLATE.contains("{目标语言}"))
    }

    @Test
    fun `now 与语言 哈希一起写进结果`() {
        val llm = FakeLlm(reply = replyEn)
        val entity = persona()
        val result = translate(llm, entity, targetLang = "en-US", now = 1794500000000L)

        assertEquals(1794500000000L, result.translation.translatedAt)
        // §7：缓存按完整 BCP-47 tag 区分，比较时再归一
        assertEquals("en-US", result.translation.lang)
        assertEquals(PersonaTranslationSource.hashOf(entity), result.translation.sourceHash)
        assertTrue(result.translation.matches(result.translation.sourceHash, "en"))
        assertFalse(result.translation.matches(result.translation.sourceHash, "zh"))
        assertTrue(llm.lastSystem!!.contains("翻译成English"))
    }

    private companion object {
        const val CHAT_SENTINEL = "CHAT-SENTINEL-今天加班到十点"
        const val MEMORY_SENTINEL = "MEMORY-SENTINEL-他妹妹要结婚"
        const val API_KEY_SENTINEL = "sk-sentinel-9f8e7d6c"
        const val PHRASE_SENTINEL = "这词儿别外传"

        /** §5 原文，只把 `{目标语言}` 换成 English（测试里逐字对，不引用实现常量）。 */
        val EXPECTED_PROMPT_EN = """
            你是翻译器。把给定的人设/世界设定翻译成English，保留语气、称呼与专有名词的意译一致性。
            只输出 JSON，键名原样保留，不要增删键，不要翻译 phrases 数组，不要解释：
            { "persona": {…}, "world": {…}, "speech": {…} }
            规则：
            - 专有名词首次出现可音译，之后保持一致；
            - 不要润色成书面语，保持口语性格；
            - 不确定的短语可保留原文。
        """.trimIndent()

        val EXPECTED_PROMPT_ZH = """
            你是翻译器。把给定的人设/世界设定翻译成中文，保留语气、称呼与专有名词的意译一致性。
            只输出 JSON，键名原样保留，不要增删键，不要翻译 phrases 数组，不要解释：
            { "persona": {…}, "world": {…}, "speech": {…} }
            规则：
            - 专有名词首次出现可音译，之后保持一致；
            - 不要润色成书面语，保持口语性格；
            - 不确定的短语可保留原文。
        """.trimIndent()
    }
}
