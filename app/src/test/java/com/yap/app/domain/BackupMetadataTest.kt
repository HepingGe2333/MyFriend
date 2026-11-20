package com.yap.app.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份版本管理（0.15）：v1–v7 读取、未来版本拒绝、checksum 损坏拒绝、元数据写入、往返幂等。
 *
 * 纯 JVM：只碰 JSON 与纯逻辑，不碰 Room（写库链路由 `BackupV7WiringContractTest` 盯）。
 *
 * 这些用例保护两件用户看不见、但升级时最要命的事：
 * 1. 旧备份永远读得回来（v1–v7 的真实样本逐个过一遍）；
 * 2. 来自更新版本 / 内容坏掉的文件一定被**拒绝**，而不是「半懂不懂」地写进库。
 */
class BackupMetadataTest {

    private val fixturesDir: File by lazy {
        listOf(File("../fixtures/backup"), File("fixtures/backup"))
            .firstOrNull { it.isDirectory }
            ?: error("找不到 fixtures/backup（工作目录=${File("").absolutePath}）")
    }

    private fun bundle(name: String = "阿深") = BackupBundle(
        formatVersion = BACKUP_FORMAT_VERSION,
        appVersion = "0.14.0",
        exportedAt = 1_700_000_000_000L,
        personas = listOf(BackupPersona(id = "persona-1", name = name, prompt = "你很安静。"))
    )

    private fun success(raw: String): BackupDecodeResult.Success =
        BackupCodec.decode(raw) as? BackupDecodeResult.Success
            ?: error("应该能读懂，实际：${BackupCodec.decode(raw)}")

    // ------------------------------------------------------------------ v1–v7

    @Test
    fun `v1 到 v7 的安卓与 iOS 样本都还读得回来`() {
        (1..7).forEach { version ->
            listOf("android", "ios").forEach { origin ->
                val file = File(fixturesDir, "$origin-export-v$version.json")
                assertTrue("缺少样本：${file.path}", file.isFile)
                val decoded = BackupCodec.decode(file.readText())
                assertTrue("$origin v$version 必须能读：$decoded", decoded is BackupDecodeResult.Success)
                val result = decoded as BackupDecodeResult.Success
                assertEquals("样本声明的格式版本", version, result.metadata.formatVersion)
                assertFalse("旧样本没有 checksum，不该被当成「已校验」", result.metadata.checksumVerified)
                assertNull("旧样本没有 checksum", result.metadata.checksum)
            }
        }
    }

    @Test
    fun `服务端 extensions 样本也照常能读`() {
        val file = File(fixturesDir, "server-export-v7-extensions.json")
        assertTrue(file.isFile)
        assertTrue(BackupCodec.decode(file.readText()) is BackupDecodeResult.Success)
    }

    // ------------------------------------------------------------------ 未来版本

    @Test
    fun `格式版本比本机高时明确拒绝 且优先于校验和判断`() {
        val raw = """
            {
              "format": "$BACKUP_FORMAT_NAME",
              "formatVersion": 8,
              "appVersion": "0.16.0",
              "checksum": "${"0".repeat(64)}"
            }
        """.trimIndent()
        val decoded = BackupCodec.decode(raw)
        assertTrue("必须整份拒绝：$decoded", decoded is BackupDecodeResult.Failure)
        assertEquals(
            "先说清楚「来自更新版本，先升级 App」",
            BackupFailure.NewerFormat(8, BACKUP_FORMAT_VERSION),
            (decoded as BackupDecodeResult.Failure).reason
        )
    }

    @Test
    fun `旧格式不会被拒绝 而是按既有迁移路径读进来并留一条提醒`() {
        val raw = """
            {
              "format": "$BACKUP_FORMAT_NAME",
              "formatVersion": 3,
              "appVersion": "0.9.0",
              "personas": [{"id":"p1","name":"阿深"}]
            }
        """.trimIndent()
        val decoded = success(raw)
        assertEquals(3, decoded.metadata.formatVersion)
        assertTrue(
            "旧格式要留一条 OlderFormat 提醒",
            decoded.warnings.any { it is BackupWarning.OlderFormat && it.version == 3 }
        )
    }

    // ------------------------------------------------------------------ 元数据写入

    @Test
    fun `导出写入 schemaVersion createdAt appVersion 与 checksum`() {
        val json = BackupCodec.encodeWithMetadata(bundle(), schemaVersion = 19, createdAt = 1_700_000_001_234L)
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(19, root["schemaVersion"]!!.jsonPrimitive.content.toInt())
        assertEquals(1_700_000_001_234L, root["createdAt"]!!.jsonPrimitive.content.toLong())
        assertEquals("0.14.0", root["appVersion"]!!.jsonPrimitive.content)
        assertEquals(BACKUP_FORMAT_VERSION, root["formatVersion"]!!.jsonPrimitive.content.toInt())
        assertEquals(BackupChecksum.ALGORITHM, root[BackupChecksum.ALGORITHM_KEY]!!.jsonPrimitive.content)
        val checksum = root["checksum"]!!.jsonPrimitive.content
        assertEquals("SHA-256 是 64 位小写十六进制", 64, checksum.length)
        assertTrue(checksum.all { it in "0123456789abcdef" })
        assertFalse("导出的文件里不允许出现密钥字段", BackupCodec.containsSecretField(json))
    }

    @Test
    fun `读回时元数据与校验和状态都在`() {
        val json = BackupCodec.encodeWithMetadata(bundle(), schemaVersion = 19)
        val decoded = success(json)
        assertEquals(19, decoded.metadata.schemaVersion)
        assertEquals("0.14.0", decoded.metadata.appVersion)
        assertEquals(1_700_000_000_000L, decoded.metadata.createdAt)
        assertTrue("checksum 对得上", decoded.metadata.checksumVerified)
        assertEquals(decoded.metadata.checksum, BackupChecksum.of(Json.parseToJsonElement(json).jsonObject))
        // 预览把版本信息带给界面（设置页/导入预览都要展示）。
        val preview = BackupPreview.from(decoded.bundle, decoded.metadata)
        assertEquals(19, preview.schemaVersion)
        assertEquals(BACKUP_FORMAT_VERSION, preview.formatVersion)
        assertEquals("0.14.0", preview.appVersion)
        assertTrue(preview.checksumVerified)
        assertTrue(preview.hasChecksum)
        assertEquals(1_700_000_000_000L, preview.createdAt)
    }

    @Test
    fun `没有 checksum 的文件的预览显示「没有校验和」`() {
        val decoded = success(BackupCodec.encode(bundle()))
        assertNull(decoded.metadata.checksum)
        assertFalse(decoded.metadata.checksumVerified)
        val preview = BackupPreview.from(decoded.bundle, decoded.metadata)
        assertFalse(preview.hasChecksum)
        assertFalse(preview.checksumVerified)
        assertEquals("没有写 schemaVersion 时是 0（界面显示未知）", 0, preview.schemaVersion)
    }

    @Test
    fun `旧备份没有 createdAt 时预览仍用 exportedAt`() {
        val encoded = BackupCodec.encode(bundle())
        val decoded = success(encoded)
        val preview = BackupPreview.from(decoded.bundle, decoded.metadata)
        assertEquals(1_700_000_000_000L, preview.exportedAt)
        assertEquals(0L, preview.createdAt)
    }

    // ------------------------------------------------------------------ checksum 拒绝

    @Test
    fun `内容被改过一个字就判损坏并拒绝`() {
        val json = BackupCodec.encodeWithMetadata(bundle(name = "阿深"), schemaVersion = 19)
        val tampered = json.replace("\"name\":\"阿深\"", "\"name\":\"阿浅\"")
        assertTrue("测试前提：JSON 里确实有可替换的字段", tampered != json)

        val decoded = BackupCodec.decode(tampered)
        assertTrue("必须拒绝，而不是照常导入：$decoded", decoded is BackupDecodeResult.Corrupted)
        val corruption = (decoded as BackupDecodeResult.Corrupted).reason
        assertTrue("原因是 checksum 对不上：$corruption", corruption is BackupCorruption.ChecksumMismatch)
        assertTrue(
            "声明值必须是真的声明值",
            (corruption as BackupCorruption.ChecksumMismatch).declared.length == 64
        )
    }

    @Test
    fun `checksum 本身不是合法 SHA-256 时也判损坏`() {
        val json = BackupCodec.encodeWithMetadata(bundle(), schemaVersion = 19)
        val root = Json.parseToJsonElement(json).jsonObject
        val broken = JsonObject(root + ("checksum" to kotlinx.serialization.json.JsonPrimitive("not-a-hash")))
        val decoded = BackupCodec.decode(broken.toString())
        assertTrue(decoded is BackupDecodeResult.Corrupted)
        assertTrue(
            (decoded as BackupDecodeResult.Corrupted).reason is BackupCorruption.ChecksumMalformed
        )
    }

    @Test
    fun `纯排版变化（重新缩进换行）不算损坏`() {
        val json = BackupCodec.encodeWithMetadata(bundle(), schemaVersion = 19)
        val pretty = Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(),
            Json.parseToJsonElement(json).jsonObject
        )
        assertTrue("前提：文本确实变了", pretty != json)
        val decoded = success(pretty)
        assertTrue("规范化后的哈希与排版无关", decoded.metadata.checksumVerified)
    }

    @Test
    fun `键顺序被重排也不算损坏`() {
        val json = BackupCodec.encodeWithMetadata(bundle(), schemaVersion = 19)
        val root = Json.parseToJsonElement(json).jsonObject
        // LinkedHashMap 按「倒序」重建：JSON 键顺序与导出时完全相反。
        val reversed = JsonObject(
            LinkedHashMap<String, kotlinx.serialization.json.JsonElement>().apply {
                root.keys.sortedDescending().forEach { key -> put(key, root.getValue(key)) }
            }
        )
        val decoded = success(reversed.toString())
        assertTrue(decoded.metadata.checksumVerified)
    }

    @Test
    fun `verifyChecksum 不解析业务段也能认出损坏 且旧备份放行`() {
        val json = BackupCodec.encodeWithMetadata(bundle(), schemaVersion = 19)
        assertNull("完整文件没有损坏", BackupCodec.verifyChecksum(json))
        assertTrue(
            "改坏的文件要被认出来（服务端导入前的预检也用它）",
            BackupCodec.verifyChecksum(json.replace("\"name\":\"阿深\"", "\"name\":\"阿浅\""))
                is BackupCorruption.ChecksumMismatch
        )
        assertNull("没有 checksum 的旧文件放行", BackupCodec.verifyChecksum(BackupCodec.encode(bundle())))
        assertNull("空串/垃圾不在这里判（交给 decode）", BackupCodec.verifyChecksum("not json"))
    }

    @Test
    fun `其他客户端写的 checksum 不复核 也不判损坏`() {
        val json = BackupCodec.encodeWithMetadata(bundle(), schemaVersion = 19)
        val root = Json.parseToJsonElement(json).jsonObject
        // 去掉算法标记 = 模拟只写 checksum 的其他客户端（iOS / 将来的版本）。
        val foreign = JsonObject(root - BackupChecksum.ALGORITHM_KEY).toString()
        val decoded = success(foreign)
        assertFalse("不是本机算法，不能声称已校验", decoded.metadata.checksumVerified)
        assertTrue("但要让界面说清「未在本机复核」", decoded.metadata.checksumForeign)
        assertEquals("文件本身照常可以导入", 1, decoded.bundle.personas.size)
        assertNull("服务端导入前的预检也放行", BackupCodec.verifyChecksum(foreign))
        val preview = BackupPreview.from(decoded.bundle, decoded.metadata)
        assertTrue(preview.hasChecksum)
        assertTrue(preview.checksumForeign)
        assertFalse(preview.checksumVerified)
    }

    // ------------------------------------------------------------------ 幂等

    @Test
    fun `同一份数据两次导出逐字节一致`() {
        val first = BackupCodec.encodeWithMetadata(bundle(), schemaVersion = 19, createdAt = 1_700_000_000_000L)
        val second = BackupCodec.encodeWithMetadata(bundle(), schemaVersion = 19, createdAt = 1_700_000_000_000L)
        assertEquals("导出必须确定性：否则 checksum 每次都不一样", first, second)
    }

    @Test
    fun `读回再导出的业务负载与原始完全一致`() {
        val original = bundle()
        val withMetadata = BackupCodec.encodeWithMetadata(original, schemaVersion = 19)
        val decoded = success(withMetadata)
        assertEquals(
            "封条与版本信息不参与「他」的往返",
            BackupCodec.encode(original),
            BackupCodec.encode(decoded.bundle)
        )
        assertTrue(BackupCodec.containsSecretField(withMetadata).not())
    }
}
