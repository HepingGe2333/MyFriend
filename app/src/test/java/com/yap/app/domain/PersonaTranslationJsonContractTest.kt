package com.yap.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 翻译缓存的**跨端 JSON 形状契约**（Android ↔ iOS，`spec/persona-translation/README.md` §4）。
 *
 * 两端各自实现一份 `PersonaTranslation`（Kotlin / Swift），同一份缓存 JSON 会在备份恢复、
 * 服务端权威模式、跨端迁移里被另一边读到。所以这里不测哈希算法（两端本来可以不同），
 * 只钉住**同一段缓存 JSON 在两端读写出同一组键**：
 *
 * 1. 两端测试各自内嵌**完全相同的一段示例 JSON**（`SPEC_SAMPLE`）——规范 §4 的示例用了
 *    `[...]` 占位，不是合法 JSON，不能直接解析；所以约定好一段等价的可解析样本；
 * 2. 断言根对象 / `persona` / `world` / `speech` 的键集合完全一致；
 * 3. 断言没有 `schemaVersion`、没有 `phrases`（§3：`phrases` 不进缓存），
 *    没有话风/世界时仍然写 `"world": null` / `"speech": null`（不省键）；
 * 4. 额外读一遍规范 README §4，确认我们断言里的键名都真的写在规范示例里。
 *
 * iOS 侧的同名契约在 `ios/` 下的对应测试文件里，
 * 两边 `SPEC_SAMPLE` 逐字相同、期望键相同。任何一端多一个键，那一端就红。
 */
class PersonaTranslationJsonContractTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun findRepoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && specFile(dir).isFile) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录（从 $start 向上）：缺 settings.gradle.kts 或 spec/persona-translation/README.md")
    }

    private fun specFile(root: File): File =
        File(root, "spec/persona-translation/README.md")

    private fun obj(raw: String): JsonObject =
        json.parseToJsonElement(raw) as JsonObject

    @Test
    fun `同一段示例 JSON 在 Android 缓存里往返后键与 iOS 完全一致`() {
        val decoded = PersonaTranslationCodec.decode(SPEC_SAMPLE)
        assertNotNull("约定的跨端示例必须能被 Android 缓存解码", decoded)
        val encoded = PersonaTranslationCodec.encode(decoded!!)
        assertJsonShape(encoded)

        // 再解一次：形状相同、内容相同（宽容解码 + 固定编码是可往返的）。
        assertEquals("缓存 JSON 往返必须稳定", decoded, PersonaTranslationCodec.decode(encoded))
        assertFalse(encoded.contains("\"schemaVersion\""))
        assertFalse(encoded.contains("\"phrases\""))
    }

    @Test
    fun `示例里的键名都写在规范 README 第 4 节`() {
        val text = specFile(findRepoRoot()).readText(Charsets.UTF_8)
        val block = text.substringAfter("## 4. 输出与缓存")
            .substringAfter("```json")
            .substringBefore("```")
        // §4 的示例漏写了 §3 要求的 systemPrompt（实现补上）；其余键必须在示例里。
        val keys = (ROOT_KEYS + PERSONA_KEYS + WORLD_KEYS + SPEECH_KEYS) - "systemPrompt"
        for (key in keys) {
            assertTrue("规范 §4 的缓存示例里没有键「$key」", block.contains("\"$key\""))
        }
    }

    @Test
    fun `世界只有 worldName 时缓存 world 键集合仍然固定`() {
        val encoded = PersonaTranslationCodec.encode(
            PersonaTranslation(
                lang = "en",
                translatedAt = 1L,
                sourceHash = "sha256:abc",
                world = WorldPreset(worldName = "Lingang")
            )
        )
        val world = obj(encoded)["world"] as JsonObject
        // 空字符串字段也要写（跨端协议固定形状，不能靠「缺键」表达空值）。
        assertEquals(WORLD_KEYS, world.keys)
        assertEquals("", world["notes"]!!.jsonPrimitive.content)
    }

    @Test
    fun `没有世界与话风时 world 和 speech 写成 null 不省键`() {
        val encoded = PersonaTranslationCodec.encode(
            PersonaTranslation(lang = "en", translatedAt = 1L, sourceHash = "sha256:abc")
        )
        val root = obj(encoded)
        assertEquals(ROOT_KEYS, root.keys)
        assertTrue("world 缺省写 null", root["world"] is JsonNull)
        assertTrue("speech 缺省写 null", root["speech"] is JsonNull)
        assertFalse(encoded.contains("schemaVersion"))
    }

    private fun assertJsonShape(encoded: String) {
        val root = obj(encoded)
        assertEquals("根对象键 = §4 的 6 个", ROOT_KEYS, root.keys)
        assertEquals("persona 键集合（两端一致）", PERSONA_KEYS, (root["persona"] as JsonObject).keys)
        assertEquals("world 键集合（两端一致）", WORLD_KEYS, (root["world"] as JsonObject).keys)
        assertEquals(
            "speech 只含 tone/habits/avoid（phrases 永不进缓存）",
            SPEECH_KEYS,
            (root["speech"] as JsonObject).keys
        )
    }

    private companion object {
        /**
         * 跨端契约样本：与 iOS 测试里的 `specSampleJSON` **逐字相同**。
         *
         * 规范 §4 的示例用 `[...]` 表示数组占位，不能当 JSON 解析；这里给一份可解析的等价样本，
         * 键名/结构与规范示例一致（额外带上了 §3 要求翻译的 `systemPrompt`）。
         */
        const val SPEC_SAMPLE = """
{
  "lang": "en",
  "translatedAt": 1794500000000,
  "sourceHash": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
  "persona": {
    "name": "Lin Shen",
    "description": "An architect who runs a small team.",
    "traits": ["reserved", "soft-hearted"],
    "likes": ["night walks"],
    "behaviors": ["gives the facts first"],
    "backstory": "Grew up in the south.",
    "quirks": "Rarely uses exclamation marks.",
    "systemPrompt": "You are Lin Shen."
  },
  "world": {
    "worldName": "Lingang New City",
    "era": "2026, coastal China",
    "description": "A rainy city where the studio is always busy.",
    "schedule": [],
    "relationships": [],
    "obligations": [],
    "notes": "Nobody calls him by his full name."
  },
  "speech": {
    "tone": "short and direct",
    "habits": ["very short sentences"],
    "avoid": ["long speeches"]
  }
}
"""

        val ROOT_KEYS = setOf("lang", "translatedAt", "sourceHash", "persona", "world", "speech")
        val PERSONA_KEYS = setOf(
            "name", "description", "traits", "likes", "behaviors",
            "backstory", "quirks", "systemPrompt"
        )
        val WORLD_KEYS = setOf(
            "worldName", "era", "description", "schedule", "relationships", "obligations", "notes"
        )
        val SPEECH_KEYS = setOf("tone", "habits", "avoid")
    }
}
