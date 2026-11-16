package com.myfriend.app.domain

import com.myfriend.app.data.local.PersonaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 翻译缓存的数据层（`spec/persona-translation/README.md` §3 / §4 / §7）。
 *
 * 这里只测纯 JVM 的三件事：**缓存形状**（encode/decode 往返、坏数据不炸）、
 * **语言判定**（CJK 占比、BCP-47 归一）、**缓存指纹**（任一源字段变了就必须失效）。
 * 网络调用在 [com.myfriend.app.data.ai.PersonaTranslatorTest] 里测。
 */
class PersonaTranslationTest {

    private val world = WorldPreset(
        worldName = "临港新城",
        era = "当代，中国沿海新一线城市",
        description = "他带着六人小组赶一个高层住宅项目。",
        schedule = listOf(WorldScheduleEntry("工作日", "09:00", "18:30", "画图、审图")),
        relationships = listOf(WorldRelationship("母亲", "母亲", "每周三晚打电话", intimacy = 4)),
        obligations = listOf("按节点交图"),
        notes = "他怕冷，也怕吵。"
    )

    private fun translation() = PersonaTranslation(
        lang = "en",
        translatedAt = 1794500000000L,
        sourceHash = "sha256:abc",
        persona = TranslatedPersona(
            name = "Lin Shen",
            description = "He runs a six-person team at an architecture firm.",
            traits = listOf("slow to warm", "tough shell, soft heart"),
            likes = listOf("night walks"),
            behaviors = listOf("listens before giving advice"),
            backstory = "Grew up in the south.",
            quirks = "Never says \"cheer up\".",
            systemPrompt = "You are Lin Shen."
        ),
        world = world,
        speech = SpeechStyle(
            tone = "short and direct",
            habits = listOf("very short sentences"),
            avoid = listOf("long speeches")
        )
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
                tone = "简短、口语",
                phrases = listOf("嗯，我在"),
                habits = listOf("句子很短"),
                avoid = listOf("长篇大论")
            )
        )
    ).overrides()

    // ---- 缓存形状 ---------------------------------------------------------------------------

    @Test
    fun `缓存编码解码往返不丢字段`() {
        val original = translation()
        val raw = PersonaTranslationCodec.encode(original)
        assertTrue(raw.contains("\"lang\":\"en\""))
        assertTrue(raw.contains("\"translatedAt\":1794500000000"))
        assertTrue(raw.contains("\"world\":{"))
        assertTrue(raw.contains("\"speech\":{"))
        assertTrue("缓存里不该出现 phrases", !raw.contains("\"phrases\""))
        val decoded = PersonaTranslationCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `没有世界时编码成 JSON null`() {
        val raw = PersonaTranslationCodec.encode(
            PersonaTranslation(lang = "en", translatedAt = 1L, sourceHash = "sha256:abc")
        )
        assertTrue(raw.contains("\"world\":null"))
        assertTrue("没有话风也要写 speech:null，两端 JSON 形状固定", raw.contains("\"speech\":null"))
        val decoded = PersonaTranslationCodec.decode(raw)
        assertNotNull(decoded)
        assertNull(decoded!!.world)
        assertNull(decoded.speech)
    }

    @Test
    fun `坏 JSON 解码为 null`() {
        listOf(
            null, "", "   ", "不是 JSON", "{", "[]", "null", "\"字符串\""
        ).forEach { raw -> assertNull("raw=$raw", PersonaTranslationCodec.decode(raw)) }
    }

    @Test
    fun `缺字段按默认值 未知键忽略`() {
        val decoded = PersonaTranslationCodec.decode(
            """{"lang":"en","futureKey":{"a":1},"persona":{"name":"Lin Shen","extra":"x"}}"""
        )
        assertNotNull(decoded)
        assertEquals("en", decoded!!.lang)
        assertEquals(0L, decoded.translatedAt)
        assertEquals("", decoded.sourceHash)
        assertEquals("Lin Shen", decoded.persona.name)
        assertEquals("", decoded.persona.description)
        assertTrue(decoded.persona.traits.isEmpty())
        assertNull(decoded.world)
        assertNull(decoded.speech)
        assertFalse(decoded.isEmpty())

        val empty = PersonaTranslationCodec.decode("{}")
        assertNotNull(empty)
        assertTrue(empty!!.isEmpty())
    }

    @Test
    fun `缺少 persona 对象时人设正文为空`() {
        val decoded = PersonaTranslationCodec.decode("""{"lang":"en","sourceHash":"sha256:abc"}""")
        assertNotNull(decoded)
        assertTrue(decoded!!.persona.isEmpty())
    }

    // ---- 语言判定 ---------------------------------------------------------------------------

    @Test
    fun `BCP-47 归一成主语言子标签`() {
        assertEquals("en", PersonaLanguage.normalize("en-US"))
        assertEquals("en", PersonaLanguage.normalize("EN-us"))
        assertEquals("zh", PersonaLanguage.normalize("zh-Hans"))
        assertEquals("zh", PersonaLanguage.normalize("zh_CN"))
        assertEquals("ja", PersonaLanguage.normalize("ja-JP"))
        assertEquals("", PersonaLanguage.normalize("   "))
    }

    @Test
    fun `CJK 占比超过 0_2 才算中文`() {
        assertTrue(PersonaLanguage.isChinese("你好世界"))
        // 空白不计入分母
        assertTrue(PersonaLanguage.isChinese("你   好"))
        // 2/10 = 0.2，不算「超过」
        assertFalse(PersonaLanguage.isChinese("你好abcdefgh"))
        // 3/10 = 0.3
        assertTrue(PersonaLanguage.isChinese("你好啊abcdefg"))
        assertFalse(PersonaLanguage.isChinese("Lin Shen is an architect"))
        assertFalse(PersonaLanguage.isChinese(""))
        // 全角标点/假名不算汉字
        assertFalse(PersonaLanguage.isChinese("こんにちは"))
    }

    @Test
    fun `语言检测只分 zh 和 en`() {
        assertEquals("zh", PersonaLanguage.detect("林深在建筑事务所赶项目。"))
        assertEquals("en", PersonaLanguage.detect("Lin Shen is an architect."))
        assertEquals("en", PersonaLanguage.detect(""))
    }

    @Test
    fun `语言相等按主标签比较 空值永不相等`() {
        assertTrue(PersonaLanguage.isSame("en-US", "en"))
        assertTrue(PersonaLanguage.isSame("zh-Hans", "ZH"))
        assertFalse(PersonaLanguage.isSame("en", "zh"))
        assertFalse(PersonaLanguage.isSame("", ""))
    }

    @Test
    fun `提示词里的目标语言名`() {
        assertEquals("中文", PersonaLanguage.displayName("zh"))
        assertEquals("中文", PersonaLanguage.displayName("zh-Hans"))
        assertEquals("English", PersonaLanguage.displayName("en-US"))
        // 非 zh/en 按 §7 原样交给模型（完整 tag）
        assertEquals("ja-JP", PersonaLanguage.displayName("ja-JP"))
        assertEquals("中文", PersonaLanguage.displayName(""))
    }

    // ---- 缓存指纹与命中 ---------------------------------------------------------------------

    @Test
    fun `哈希稳定且带 sha256 前缀`() {
        val first = PersonaTranslationSource.hashOf(persona())
        assertEquals(first, PersonaTranslationSource.hashOf(persona()))
        assertTrue(first.matches(Regex("^sha256:[0-9a-f]{64}$")))
    }

    @Test
    fun `任一源字段变化都会让哈希变化`() {
        val base = persona()
        val hash = PersonaTranslationSource.hashOf(base)
        val changed = listOf(
            "name" to base.copy(name = "林深深"),
            "description" to base.copy(description = "换了个简介。"),
            "traits" to base.copy(traits = PersonaProfile.encodeList(listOf("慢热"))),
            "likes" to base.copy(likes = PersonaProfile.encodeList(listOf("白天的公园"))),
            "behaviors" to base.copy(behaviors = PersonaProfile.encodeList(listOf("先给建议再听"))),
            "backstory" to base.copy(backstory = "在北方长大。"),
            "quirks" to base.copy(quirks = "爱说「加油」。"),
            "prompt" to base.copy(prompt = "你是另一个人。"),
            "world notes" to base.copy(
                worldJson = WorldPresetCodec.encode(world.copy(notes = "他怕冷，也怕吵，还怕黑。"))
            ),
            "world schedule" to base.copy(
                worldJson = WorldPresetCodec.encode(
                    world.copy(schedule = listOf(WorldScheduleEntry("周末", "10:00", "12:00", "睡到自然醒")))
                )
            ),
            "speechStyle tone" to base.copy(
                speechStyleJson = SpeechStyleCodec.encode(SpeechStyle(tone = "话很多"))
            ),
            "speechStyle habits" to base.copy(
                speechStyleJson = SpeechStyleCodec.encode(
                    SpeechStyle(tone = "简短、口语", phrases = listOf("嗯，我在"), habits = listOf("句子很长"))
                )
            )
        )
        changed.forEach { (label, entity) ->
            assertNotEquals(label, hash, PersonaTranslationSource.hashOf(entity))
        }
    }

    @Test
    fun `phrases 变化不影响 sourceHash`() {
        val base = persona()
        val hash = PersonaTranslationSource.hashOf(base)
        val changedPhrases = base.copy(
            speechStyleJson = SpeechStyleCodec.encode(
                SpeechStyle(
                    tone = "简短、口语",
                    phrases = listOf("换了一句常用语"),
                    habits = listOf("句子很短"),
                    avoid = listOf("长篇大论")
                )
            )
        )
        assertEquals(
            "phrases 不参与翻译、不进 sourceHash：只改常用语不该让整份译文失效",
            hash,
            PersonaTranslationSource.hashOf(changedPhrases)
        )
        assertFalse(PersonaTranslationSource.sourceText(changedPhrases).contains("换了一句常用语"))
        assertTrue(PersonaTranslationSource.sourceText(base).contains("简短、口语"))
    }

    @Test
    fun `命中要求哈希一致且语言等价`() {
        val cached = translation()
        assertTrue(cached.matches("sha256:abc", "en"))
        assertTrue(cached.matches("sha256:abc", "en-GB"))
        assertFalse(cached.matches("sha256:abc", "zh"))
        assertFalse(cached.matches("sha256:other", "en"))
        assertFalse(PersonaTranslation(lang = "", sourceHash = "sha256:abc").matches("sha256:abc", "en"))
    }

    @Test
    fun `源语言等于目标语言时不需要翻译`() {
        assertFalse(PersonaTranslationSource.needsTranslation(persona(), "zh"))
        assertFalse(PersonaTranslationSource.needsTranslation(persona(), "zh-Hans"))
        assertTrue(PersonaTranslationSource.needsTranslation(persona(), "en"))
        assertTrue(PersonaTranslationSource.needsTranslation(persona(), "ja"))

        val english = persona {
            copy(
                name = "Lin Shen",
                description = "An architect.",
                prompt = "You are Lin Shen.",
                backstory = "Grew up in the south.",
                quirks = "Never says cheer up.",
                traits = PersonaProfile.encodeList(listOf("slow to warm")),
                likes = PersonaProfile.encodeList(listOf("night walks")),
                behaviors = PersonaProfile.encodeList(listOf("listens first")),
                worldJson = null,
                speechStyleJson = null
            )
        }
        assertFalse(PersonaTranslationSource.needsTranslation(english, "en"))
        assertTrue(PersonaTranslationSource.needsTranslation(english, "zh"))
    }

    @Test
    fun `缓存命中就不需要翻译 哈希或语言对不上就要`() {
        val base = persona()
        val hit = base.copy(
            translationJson = PersonaTranslationCodec.encode(
                PersonaTranslation(
                    lang = "en",
                    translatedAt = 1L,
                    sourceHash = PersonaTranslationSource.hashOf(base),
                    persona = TranslatedPersona(name = "Lin Shen")
                )
            ),
            translationLang = "en"
        )
        assertFalse(PersonaTranslationSource.needsTranslation(hit, "en-US"))

        // 目标语言换成一个和源、和缓存都不一样语言 → 要重译
        assertTrue(PersonaTranslationSource.needsTranslation(hit, "ja"))

        // 源字段改了 → 哈希对不上 → 要重译
        val stale = hit.copy(name = "林深深")
        assertTrue(PersonaTranslationSource.needsTranslation(stale, "en"))

        // 缓存语言和当前语言不一致 → 要重译
        val wrongLang = hit.copy(
            translationJson = PersonaTranslationCodec.encode(
                PersonaTranslation(
                    lang = "ja",
                    sourceHash = PersonaTranslationSource.hashOf(base),
                    persona = TranslatedPersona(name = "Lin Shen")
                )
            )
        )
        assertTrue(PersonaTranslationSource.needsTranslation(wrongLang, "en"))
    }

    @Test
    fun `用于语言检测的源文本包含所有被翻译字段`() {
        val text = PersonaTranslationSource.sourceText(persona())
        listOf("林深", "建筑事务所", "慢热", "夜里散步", "先听完再给建议", "在南方长大", "不说「加油」", "你是林深").forEach {
            assertTrue("缺少 $it", text.contains(it))
        }
        assertEquals("zh", PersonaLanguage.detect(text))
    }

    // ---- 0.12 规范更新：sourceHash = 规范化 JSON 的 SHA-256 --------------------------------

    /**
     * 规范 §4 的固定输入 → 固定哈希。
     *
     * 期望值不是调生产代码算出来的，而是把规范要求的**逐字规范化 JSON**（键字典序、紧凑）
     * 用 SHA-256 算出的常量；这样「键排序 / 无空白 / UTF-8 / 小写 hex」任何一项写错都会红。
     */
    @Test
    fun `sourceHash 与规范的规范化 JSON 逐字一致`() {
        val minimal = PersonaEntity(
            id = "p-min",
            name = "林深",
            description = "建筑师",
            avatarUri = null,
            avatarSeed = 0,
            prompt = "你是林深。",
            category = "日常聊天",
            version = 1,
            defaultApiConfigId = null,
            modelOverride = null,
            isDefault = false,
            createdAt = 0L,
            updatedAt = 0L
        )
        val canonical =
            """{"persona":{"backstory":"","behaviors":[],"description":"建筑师","likes":[],"name":"林深","quirks":"","systemPrompt":"你是林深。","traits":[]},"speech":null,"world":null}"""
        assertEquals("规范化 JSON 形状必须与规范一致", canonical, PersonaTranslationSource.canonicalSourceJson(minimal))
        assertEquals(
            "sha256:50837cd41a80e09703452b1b9a3e258f6cf0c9df6de1402faa0bbab805915367",
            PersonaTranslationSource.hashOf(minimal)
        )
    }

    /**
     * 与 `spec/persona-guidance/cases/` 里的固定输入对齐：世界与话风的 `expect` 原样喂进来，
     * 期望哈希是**手工按规范规范化 JSON 算出**的常量（不是调用生产代码再比较）。
     *
     * 这样跨端 review 时只需要确认一件事：两端对同一份 spec 样例算出同一个 `a781a4…`。
     */
    @Test
    fun `sourceHash 与 spec 固定样例一致`() {
        val worldFixture = specFixture("persona-guidance/cases/world-preset-linshen.json")
        val speechFixture = specFixture("persona-guidance/cases/speech-style-linshen.json")
        val world = WorldPresetCodec.decode(worldFixture.jsonObject.getValue("expect").toString())
            ?: error("spec 世界样例读不出来")
        val speech = SpeechStyleCodec.decode(speechFixture.jsonObject.getValue("expect").toString())
            ?: error("spec 话风样例读不出来")

        val persona = PersonaEntity(
            id = "p-spec",
            name = "林深",
            description = "建筑事务所项目负责人，最近在赶一个住宅项目；和母亲同城。",
            avatarUri = null,
            avatarSeed = 0,
            prompt = "你是林深，一个慢热的人。",
            category = "日常聊天",
            version = 1,
            defaultApiConfigId = null,
            modelOverride = null,
            isDefault = true,
            createdAt = 0L,
            updatedAt = 0L,
            traits = PersonaProfile.encodeList(listOf("慢热", "嘴硬心软")),
            likes = PersonaProfile.encodeList(listOf("夜里散步")),
            behaviors = PersonaProfile.encodeList(listOf("先听完再给建议")),
            backstory = "在南方长大。",
            quirks = "不说「加油」。",
            worldJson = WorldPresetCodec.encode(world),
            speechStyleJson = SpeechStyleCodec.encode(
                speech.copy(phrases = listOf("嗯，我在"))
            )
        )

        assertEquals(
            "sha256:a781a42a1afe1ce3ad651dddc36cc736d56351f75c1fd8833056b905e16cb0d3",
            PersonaTranslationSource.hashOf(persona)
        )
    }

    private fun specFixture(relative: String) = Json.parseToJsonElement(
        listOf(File("spec/$relative"), File("../spec/$relative"))
            .firstOrNull { it.exists() }
            ?.readText()
            ?: error("找不到 spec 样例 $relative（工作目录=${File("").absolutePath}）")
    ).jsonObject

    @Test
    fun `sourceHash 覆盖世界与话风 且 phrases 不参与`() {
        val base = persona {
            copy(
                name = "林深",
                description = "项目负责人。",
                prompt = "你是林深，一个慢热的人。",
                backstory = "在南方长大。",
                quirks = "不说加油。",
                traits = PersonaProfile.encodeList(listOf("慢热", "嘴硬心软")),
                likes = PersonaProfile.encodeList(listOf("夜里散步")),
                behaviors = PersonaProfile.encodeList(listOf("先听完再给建议")),
                worldJson = WorldPresetCodec.encode(
                    WorldPreset(
                        worldName = "临港新城",
                        era = "当代",
                        description = "他住在海边。",
                        schedule = listOf(WorldScheduleEntry("工作日", "09:00", "18:00", "画图")),
                        relationships = listOf(WorldRelationship("母亲", "母亲", "每周打电话")),
                        obligations = listOf("交图"),
                        notes = "他怕冷。"
                    )
                ),
                speechStyleJson = SpeechStyleCodec.encode(
                    SpeechStyle(
                        tone = "简短",
                        phrases = listOf("嗯，我在"),
                        habits = listOf("句子短"),
                        avoid = listOf("长篇大论")
                    )
                )
            )
        }
        val canonical =
            """{"persona":{"backstory":"在南方长大。","behaviors":["先听完再给建议"],"description":"项目负责人。","likes":["夜里散步"],"name":"林深","quirks":"不说加油。","systemPrompt":"你是林深，一个慢热的人。","traits":["慢热","嘴硬心软"]},"speech":{"avoid":["长篇大论"],"habits":["句子短"],"tone":"简短"},"world":{"description":"他住在海边。","era":"当代","notes":"他怕冷。","obligations":["交图"],"relationships":[{"name":"母亲","note":"每周打电话","relation":"母亲"}],"schedule":[{"activity":"画图","day":"工作日","end":"18:00","start":"09:00"}],"worldName":"临港新城"}}"""
        assertEquals(canonical, PersonaTranslationSource.canonicalSourceJson(base))
        assertEquals(
            "sha256:34da842611e6210a383bd1cadb03ddb70ae626bc5a2fe8f8615eedd9a62a4b51",
            PersonaTranslationSource.hashOf(base)
        )

        // 只改 phrases（不参与翻译、不进哈希）→ 哈希不变。
        val phrasesChanged = base.copy(
            speechStyleJson = SpeechStyleCodec.encode(
                SpeechStyle(
                    tone = "简短",
                    phrases = listOf("换了一句口头禅"),
                    habits = listOf("句子短"),
                    avoid = listOf("长篇大论")
                )
            )
        )
        assertEquals(PersonaTranslationSource.hashOf(base), PersonaTranslationSource.hashOf(phrasesChanged))

        // 任一源字段变化 → 哈希变化。
        assertNotEquals(
            PersonaTranslationSource.hashOf(base),
            PersonaTranslationSource.hashOf(base.copy(worldJson = null))
        )
        assertNotEquals(
            PersonaTranslationSource.hashOf(base),
            PersonaTranslationSource.hashOf(base.copy(speechStyleJson = null))
        )
    }
}
