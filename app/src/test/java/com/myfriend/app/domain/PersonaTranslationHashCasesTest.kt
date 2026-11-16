package com.myfriend.app.domain

import com.myfriend.app.data.local.PersonaEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `sourceHash` 的**跨端共享用例**（`spec/persona-translation/README.md` §4.1）。
 *
 * 用例住在 `spec/persona-translation/cases/hash-cases.json`，iOS 的
 * `PersonaTranslationHashCasesTests` 读的是**同一份文件**。`expectHash` 由
 * `scripts/hash-cases.py` 独立算出（不调用两端实现）——Android 与 iOS 只要有一端
 * 在「键集合 / 是否 trim / speech 全空判定 / 字典序」上偷懒，这里就会有一端红。
 *
 * 喂进被测代码的是**和真实库列一样的东西**：人设字段是裸字符串，世界/话风是 JSON 文本
 * （用例里写成对象，测试再序列化成字符串），所以这条也顺带覆盖了「宽容解析 → 规范化」这段。
 */
class PersonaTranslationHashCasesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `共享用例逐条与独立脚本算出的权威哈希一致`() {
        val cases = loadCases()
        assertTrue("共享用例不能少于 3 条（现有 ${cases.size} 条）", cases.size >= 3)
        cases.forEach { element ->
            val case = element.jsonObject
            val name = case.str("name")
            val input = case["input"]?.jsonObject ?: error("[$name] 缺 input")
            val personaJson = input["persona"]?.jsonObject ?: error("[$name] 缺 input.persona")

            val persona = PersonaEntity(
                id = "hash-case",
                name = personaJson.str("name"),
                description = personaJson.str("description"),
                avatarUri = null,
                avatarSeed = 0,
                prompt = personaJson.str("systemPrompt"),
                category = "",
                version = 1,
                defaultApiConfigId = null,
                modelOverride = null,
                isDefault = false,
                createdAt = 0L,
                updatedAt = 0L,
                traits = PersonaProfile.encodeList(personaJson.strings("traits")),
                likes = PersonaProfile.encodeList(personaJson.strings("likes")),
                behaviors = PersonaProfile.encodeList(personaJson.strings("behaviors")),
                backstory = personaJson.str("backstory"),
                quirks = personaJson.str("quirks"),
                // 世界/话风按库里存的形状传：JSON 文本；用例里没有就是 null。
                worldJson = (input["world"] as? JsonObject)?.toString(),
                speechStyleJson = (input["speech"] as? JsonObject)?.toString()
            )

            assertEquals(
                "[$name] 规范化 JSON 与 spec 用例不一致（形状/键序/是否 trim）",
                case.str("canonical"),
                PersonaTranslationSource.canonicalSourceJson(persona)
            )
            assertEquals(
                "[$name] sourceHash 与 scripts/hash-cases.py 独立算出的权威值不一致",
                case.str("expectHash"),
                PersonaTranslationSource.hashOf(persona)
            )
        }
    }

    /**
     * 「只有 phrases 的话风」= 没有话风（§4.1 第 4 条）：改常用语不能经由 null ↔ 空对象翻转
     * 改变哈希。这条独立于用例文件，因为它钉的是为什么 `speech` 判空不看 `phrases`。
     */
    @Test
    fun `只有 phrases 的话风与没有话风同哈希`() {
        val base = persona(speechStyleJson = null)
        val phrasesOnly = persona(
            speechStyleJson = SpeechStyleCodec.encode(
                SpeechStyle(tone = "", phrases = listOf("嗯，我在"), habits = emptyList(), avoid = emptyList())
            )
        )
        assertEquals(
            PersonaTranslationSource.hashOf(base),
            PersonaTranslationSource.hashOf(phrasesOnly)
        )
        assertTrue(
            "phrases-only 的话风也必须写 speech:null",
            PersonaTranslationSource.canonicalSourceJson(phrasesOnly).endsWith("\"speech\":null,\"world\":null}")
        )
    }

    /** 人设字段是库里的原值：规范化阶段不 trim，首尾空白要进哈希（§4.1 第 6 条）。 */
    @Test
    fun `人设字段首尾空白不 trim`() {
        val padded = persona(name = " 林深 ")
        assertTrue(
            PersonaTranslationSource.canonicalSourceJson(padded).contains("\"name\":\" 林深 \""),
        )
        assertTrue(
            PersonaTranslationSource.hashOf(padded) != PersonaTranslationSource.hashOf(persona(name = "林深")),
        )
    }

    private fun persona(
        name: String = "林深",
        speechStyleJson: String? = null
    ) = PersonaEntity(
        id = "p-hash",
        name = name,
        description = "嘴硬心软的建筑师。",
        avatarUri = null,
        avatarSeed = 0,
        prompt = "你叫林深。",
        category = "",
        version = 1,
        defaultApiConfigId = null,
        modelOverride = null,
        isDefault = false,
        createdAt = 0L,
        updatedAt = 0L,
        traits = PersonaProfile.encodeList(listOf("嘴硬心软")),
        likes = PersonaProfile.encodeList(listOf("夜里散步")),
        behaviors = PersonaProfile.encodeList(listOf("先给事实")),
        backstory = "父亲早年去世。",
        quirks = "不太用感叹号。",
        worldJson = null,
        speechStyleJson = speechStyleJson
    )

    // ---- 读 spec 用例 ----------------------------------------------------------------------

    private fun loadCases(): JsonArray {
        val root = findRepoRoot()
        val file = File(root, "spec/persona-translation/cases/hash-cases.json")
        val document = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        return document["cases"] as? JsonArray
            ?: error("$file 的顶层 cases 必须是数组")
    }

    private fun findRepoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val cases = File(dir, "spec/persona-translation/cases/hash-cases.json")
            if (File(dir, "settings.gradle.kts").isFile && cases.isFile) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录（从 $start 向上）：缺 settings.gradle.kts 或 spec/persona-translation/cases/hash-cases.json")
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content ?: ""

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
}
