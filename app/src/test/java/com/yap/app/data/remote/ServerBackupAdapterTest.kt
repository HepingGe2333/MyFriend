package com.yap.app.data.remote

import com.yap.app.domain.AttachmentsCodec
import com.yap.app.domain.BackupCodec
import com.yap.app.domain.BackupDecodeResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端备份 → 本地 v5 的适配（M5 导入后的缓存刷新）。
 *
 * 原样导入服务端 JSON 会出现两类静默错误：人格没有 `avatarUri`（接回来没脸）、
 * 记忆重要度 0–100 被当成 1–5（界面显示 80 星）。这里逐条钉住转换结果。
 */
class ServerBackupAdapterTest {

    private val serverBackup = """
        {
          "format":"myfriend.backup",
          "formatVersion":5,
          "appVersion":"0.12.0",
          "exportedAt":100,
          "apiKeysOmitted":true,
          "personas":[
            {"id":"p1","name":"林深","description":"","avatarUrl":"/v1/media/av1",
             "isDefault":true,"createdAt":1,"updatedAt":2,"prompt":"你叫林深"}
          ],
          "memories":[
            {"id":"m1","personaId":"p1","content":"喜欢夜里散步","importance":80,
             "tags":["散步"],"createdAt":3,"updatedAt":4}
          ],
          "sessions":[
            {"id":"s1","personaId":"p1","title":"和深","lastMessageAt":5,
             "unread":2,"lastBlockIndex":0,"updatedAt":6,"createdAt":5}
          ],
          "messages":[
            {"id":"msg1","sessionId":"s1","blockIndex":0,"role":"USER","content":"看",
             "createdAt":5,"attachments":["/v1/media/av1"],"source":"CHAT"}
          ],
          "presets":[{"id":"pre1","name":"模板","prompt":"提示词"}],
          "settings":{"themeMode":"DARK","activePersonaId":"p1"}
        }
    """.trimIndent()

    @Test
    fun `人格 avatarUrl 转成 avatarUri 且其它字段原样保留`() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(serverBackup)
        val local = ServerBackupAdapter.toLocalBackup(root as JsonObject)
        val persona = local.getValue("personas").let { it as JsonArray }.single()

        assertEquals(
            "/v1/media/av1",
            persona.jsonObject.getValue("avatarUri").jsonPrimitive.content
        )
        assertEquals("p1", persona.jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("你叫林深", persona.jsonObject.getValue("prompt").jsonPrimitive.content)
    }

    @Test
    fun `记忆重要度从 0-100 落到 1-5`() {
        val local = ServerBackupAdapter.toLocalBackupJson(serverBackup)!!
        val decoded = BackupCodec.decode(local)

        assertTrue(decoded is BackupDecodeResult.Success)
        val bundle = (decoded as BackupDecodeResult.Success).bundle
        assertEquals(4, bundle.memories.single().importance) // 80 → 4
        assertEquals("喜欢夜里散步", bundle.memories.single().content)
    }

    @Test
    fun `会话 unread 转成 unreadCount`() {
        val local = ServerBackupAdapter.toLocalBackupJson(serverBackup)!!
        val bundle = (BackupCodec.decode(local) as BackupDecodeResult.Success).bundle

        assertEquals(2, bundle.sessions.single().unreadCount)
    }

    @Test
    fun `消息附件数组转成客户端 attachmentsJson 并能被附件解析器读回`() {
        val local = ServerBackupAdapter.toLocalBackupJson(serverBackup)!!
        val bundle = (BackupCodec.decode(local) as BackupDecodeResult.Success).bundle
        val message = bundle.messages.single()

        val attachments = AttachmentsCodec.decode(message.attachmentsJson)
        assertEquals(1, attachments.size)
        assertEquals("/v1/media/av1", attachments.single().uri)
    }

    @Test
    fun `非 JSON 对象返回 null 而不是抛异常`() {
        assertEquals(null, ServerBackupAdapter.toLocalBackupJson("not json"))
        assertEquals(null, ServerBackupAdapter.toLocalBackupJson("[]"))
    }

    @Test
    fun `已经是 1-5 的本地备份不会被再次缩小`() {
        val raw = """{"format":"myfriend.backup","formatVersion":5,
            "memories":[{"id":"m1","personaId":"p1","content":"x","importance":4}]}"""

        val bundle = (BackupCodec.decode(ServerBackupAdapter.toLocalBackupJson(raw)!!) as BackupDecodeResult.Success).bundle

        assertEquals(4, bundle.memories.single().importance)
    }

    @Test
    fun `适配后的备份仍能被预览解码 且各段数量不变`() = runBlocking {
        val local = ServerBackupAdapter.toLocalBackupJson(serverBackup)!!
        val decoded = BackupCodec.decode(local)

        assertNotNull(decoded)
        val bundle = (decoded as BackupDecodeResult.Success).bundle
        assertEquals(1, bundle.personas.size)
        assertEquals(1, bundle.memories.size)
        assertEquals(1, bundle.sessions.size)
        assertEquals(1, bundle.messages.size)
        assertEquals(1, bundle.presets.size)
        assertEquals("DARK", bundle.settings?.themeMode)
    }
}
