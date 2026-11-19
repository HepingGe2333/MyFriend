package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * 0.14.1 备份对称（`spec/experience/README.md` §11）。
 *
 * 判据不是「服务端能读回来」，而是三件更硬的事：
 * 1. **客户端独有段原样往返**：`proactiveRules[]` / `memoryConsolidations[]`（以及客户端将来新增的
 *    未知顶层段）导入时存进 `client_extras.json`，导出时**逐字段**写回（不解析、不替换）；
 * 2. **两次往返幂等**：客户端 v6 fixture 导入 → 导出 → 再导入 → 再导出，两边的客户端段一模一样；
 * 3. **服务端专有数据在 `extensions`**：`mediaSkipped` / `mentalActivities` / `sessionMemory`
 *    不再占顶层键，客户端 `ignoreUnknownKeys` 忽略它们，服务端自己读得回来（旧的顶层写法仍兼容）。
 */
class BackupSymmetryTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val roots = mutableListOf<Path>()

    @Before
    fun setUp() {
        root = freshRoot("agentd-backup-symmetry")
        store = FileStore.open(root)
    }

    @After
    fun tearDown() {
        roots.forEach { TestSupport.deleteRecursively(it) }
    }

    private fun freshRoot(prefix: String): Path {
        val dir = TestSupport.tempDir(prefix)
        roots.add(dir)
        return dir
    }

    private fun fixture(name: String): String {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("fixtures/backup/$name")
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent
        }
        throw AssertionError("找不到 fixtures/backup/$name")
    }

    private fun export(target: FileStore = store): JsonValue.Obj {
        val out = StringBuilder()
        target.writeBackup(AgentdApi.VERSION, 1_794_500_000_000L, out)
        return TestSupport.parse(out.toString())
    }

    private fun importText(target: FileStore, text: String, mode: String = FileStore.MODE_REPLACE) {
        val result = target.importBackupRequest(StringReader(text), bareMode = mode)
        assertEquals(mode, result.mode)
    }

    /** 客户端独有段：内容逐个字段比较（不是只比段是否存在）。 */
    private fun assertClientSectionsEqual(expected: JsonValue.Obj, actual: JsonValue.Obj) {
        for (section in listOf("proactiveRules", "memoryConsolidations")) {
            assertEquals(
                "客户端段 $section 应原样往返",
                Json.stringify(expected.fields[section] as JsonValue),
                Json.stringify(actual.fields[section] as JsonValue)
            )
        }
    }

    // ------------------------------------------------------------------ 1. 客户端独有段原样往返

    @Test
    fun `client-only sections survive import then export field by field`() {
        val fixtureText = fixture("android-export-v6.json")
        val fixtureJson = TestSupport.parse(fixtureText)
        // fixture 本身就有非空的 proactiveRules / memoryConsolidations（不是空数组占位）。
        assertTrue((fixtureJson.fields["proactiveRules"] as JsonValue.Arr).items.isNotEmpty())
        assertTrue((fixtureJson.fields["memoryConsolidations"] as JsonValue.Arr).items.isNotEmpty())

        importText(store, fixtureText)
        val exported = export()

        assertClientSectionsEqual(fixtureJson, exported)
        // 落盘证据：导入时确实存进了 client_extras.json（不只是在内存里）。
        val extrasFile = root.resolve(FileStore.CLIENT_EXTRAS_FILE)
        assertTrue("导入应把客户端独有段存进 ${FileStore.CLIENT_EXTRAS_FILE}", Files.exists(extrasFile))
        val extras = TestSupport.parse(Files.readString(extrasFile))
        val sections = extras.obj("sections")
        assertEquals(
            Json.stringify(fixtureJson.fields["proactiveRules"] as JsonValue),
            Json.stringify(sections.fields["proactiveRules"] as JsonValue)
        )
    }

    @Test
    fun `ios fixture keeps its own rule and consolidation shapes`() {
        val fixtureText = fixture("ios-export-v6.json")
        val fixtureJson = TestSupport.parse(fixtureText)
        importText(store, fixtureText)
        assertClientSectionsEqual(fixtureJson, export())
    }

    // ------------------------------------------------------------------ 2. 两次往返幂等

    @Test
    fun `two round trips are idempotent for the client sections`() {
        val fixtureText = fixture("android-export-v6.json")

        importText(store, fixtureText)
        val firstExport = export()
        val firstText = Json.stringify(firstExport)

        val secondRoot = freshRoot("agentd-backup-symmetry-2")
        val second = FileStore.open(secondRoot)
        importText(second, firstText)
        val secondExport = export(second)

        assertClientSectionsEqual(firstExport, secondExport)
        // 再导一次（第三次落库）也不改变客户端段：幂等的真正含义。
        val thirdRoot = freshRoot("agentd-backup-symmetry-3")
        val third = FileStore.open(thirdRoot)
        importText(third, Json.stringify(secondExport))
        assertClientSectionsEqual(firstExport, export(third))
    }

    @Test
    fun `unknown client sections are preserved verbatim too`() {
        val fixtureText = fixture("android-export-v6.json")
        // 模拟「客户端将来新增了一段服务端还不认识的顶层数组」：JSON 值原样透传。
        val extended = fixtureText.trimEnd().removeSuffix("}") +
            ""","futureClientSection":{"note":"客户端将来新增的段","items":[1,2,3],"nested":{"flag":true}}}"""
        val expected = (TestSupport.parse(extended).fields["futureClientSection"] as JsonValue)
        importText(store, extended)
        val exported = export()
        assertEquals(
            "未知的客户端段必须原样带回",
            Json.stringify(expected),
            Json.stringify(exported.fields["futureClientSection"] as JsonValue)
        )
    }

    // ------------------------------------------------------------------ 3. 服务端专有数据在 extensions

    @Test
    fun `server-only data lives under extensions and not as top level extensions`() {
        val fixtureText = fixture("android-export-v6.json")
        importText(store, fixtureText)
        val exported = export()

        assertFalse("mediaSkipped 不该再占顶层键", exported.fields.containsKey("mediaSkipped"))
        assertFalse("其顶层不该有 mentalActivities（旧写法只接受导入）", exported.fields.containsKey("mentalActivities"))
        assertFalse("其顶层不该有 sessionMemory", exported.fields.containsKey("sessionMemory"))
        val extensions = exported.fields["extensions"] as? JsonValue.Obj
        assertNotNull("服务端专有数据必须放在 extensions", extensions)
        assertTrue(extensions!!.fields.containsKey("mediaSkipped"))
    }

    @Test
    fun `legacy top level mentalActivities and sessionMemory still import`() {
        // 0.14.0 的备份文件把这两段写在顶层；0.14.1 读它时不能丢。
        val fixtureText = fixture("android-export-v6.json")
        importText(store, fixtureText)
        val legacy = TestSupport.parse(Json.stringify(export()))
        legacy.fields.remove("extensions")
        val personaId = (legacy.fields["personas"] as JsonValue.Arr).items
            .map { it as JsonValue.Obj }
            .first().str("id")
        val sessionId = (legacy.fields["sessions"] as JsonValue.Arr).items
            .map { it as JsonValue.Obj }
            .first().str("id")
        legacy.fields["mentalActivities"] = Json.parse(
            """[{"id":"legacy-thought","personaId":"$personaId","sessionId":"$sessionId","at":1794500000000,
                "thought":"他今天有点不一样","moodLabel":"平静","moodValence":0,"moodArousal":2,
                "moodDominance":3,"moodIntensity":2}]"""
        )
        legacy.fields["sessionMemory"] = Json.parse(
            """[{"sessionId":"$sessionId","personaId":"$personaId","shortSummary":"最近聊了工作",
                "shortUpdatedAt":1794500000000,"shortUserMessages":5,"longSummary":"更早聊了搬家",
                "longUpdatedAt":1794400000000,"longUserMessages":30}]"""
        )

        val other = FileStore.open(freshRoot("agentd-backup-symmetry-legacy"))
        importText(other, Json.stringify(legacy))
        val reExported = export(other)
        val extensions = reExported.fields["extensions"] as? JsonValue.Obj
        assertNotNull(extensions)
        assertEquals(
            "旧顶层段读进来之后应落到 extensions.mentalActivities",
            1,
            (extensions!!.fields["mentalActivities"] as JsonValue.Arr).items.size
        )
        assertEquals(
            1,
            (extensions.fields["sessionMemory"] as JsonValue.Arr).items.size
        )
    }

    // ------------------------------------------------------------------ 4. merge / replace 语义

    @Test
    fun `replace clears client extras and merge keeps untouched sections`() {
        importText(store, fixture("android-export-v6.json"))
        assertNotNull(store.clientExtrasSection("proactiveRules"))

        // replace 一份不含客户端段的极简备份：旧透传必须清掉（备份就是新状态）。
        importText(
            store,
            """{"format":"myfriend.backup","formatVersion":6,"personas":[],
                "sessions":[],"messages":[],"memories":[],"presets":[],"media":[],
                "stickers":[],"diaryNotes":[],"settings":{}}""",
            mode = FileStore.MODE_REPLACE
        )
        assertEquals(null, store.clientExtrasSection("proactiveRules"))

        // merge 一份只带 proactiveRules 的备份：只覆盖它，不碰别的（这里别的已经被清空了）。
        importText(
            store,
            """{"format":"myfriend.backup","formatVersion":6,
                "proactiveRules":[{"id":"r-9","kind":"DAILY"}],
                "personas":[],"sessions":[],"messages":[],"memories":[],"presets":[],"media":[],
                "stickers":[],"diaryNotes":[],"settings":{}}""",
            mode = FileStore.MODE_MERGE
        )
        assertEquals(
            """[{"id":"r-9","kind":"DAILY"}]""",
            Json.stringify(store.clientExtrasSection("proactiveRules") as JsonValue)
        )
    }
}
