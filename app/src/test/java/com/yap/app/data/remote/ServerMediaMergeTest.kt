package com.yap.app.data.remote

import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.MessageStatus
import com.yap.app.data.local.PersonaEntity
import com.yap.app.domain.AttachmentsCodec
import com.yap.app.domain.MessageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端消息附件「本地化之后怎么落库」的纯 JVM 单测（0.13）。
 *
 * 重点不是下载（那在 `ServerMediaStoreTest` 里），而是 `ServerMergePlan.message` 的合并口径：
 * 1. 有本地化结果 → `uri` 用 `file://`，同时保留 `serverMediaId`；
 * 2. 没有结果/失败 → 保留服务端引用，`serverMediaId` 照样有值；
 * 3. 本机已有的 kind/mime/durationMs 不能因为一次同步丢光（语音不能变成图片）；
 * 4. 计数（downloaded/skipped/failed）随 `ServerSyncApplyResult.plus` 一起累加。
 */
class ServerMediaMergeTest {

    @Test
    fun `有本地化结果时写本地路径并保留 serverMediaId 与语音元数据`() {
        val local = message(
            attachmentsJson = AttachmentsCodec.encode(
                listOf(
                    MessageAttachment(
                        uri = "/v1/media/voice1",
                        mime = "audio/m4a",
                        kind = MessageAttachment.KIND_AUDIO,
                        durationMs = 3_000
                    )
                )
            )
        )
        val server = ServerMessage(
            id = "m1",
            sessionId = "s1",
            role = "assistant",
            content = "给你听",
            createdAt = 20,
            attachments = listOf("/v1/media/voice1")
        )
        val media = mapOf(
            "/v1/media/voice1" to ServerMediaAttachment(
                serverMediaId = "voice1",
                uri = "file:///data/audio/voice-voice1.m4a",
                mime = "audio/m4a",
                kind = MessageAttachment.KIND_AUDIO,
                local = true
            )
        )

        val merged = ServerMergePlan.message(server, "p1", local, media)
        val attachment = AttachmentsCodec.decode(merged.attachmentsJson).single()

        assertEquals("file:///data/audio/voice-voice1.m4a", attachment.uri)
        assertEquals("voice1", attachment.serverMediaId)
        assertEquals("audio/m4a", attachment.mime)
        assertEquals(MessageAttachment.KIND_AUDIO, attachment.kind)
        assertEquals(3_000L, attachment.durationMs)
    }

    @Test
    fun `没有本地化结果时保留服务端引用但仍写 serverMediaId`() {
        val server = ServerMessage(
            id = "m1",
            sessionId = "s1",
            role = "assistant",
            content = "图",
            attachments = listOf("/v1/media/pic1")
        )

        val merged = ServerMergePlan.message(server, "p1", null)
        val attachment = AttachmentsCodec.decode(merged.attachmentsJson).single()

        assertEquals("/v1/media/pic1", attachment.uri)
        assertEquals("pic1", attachment.serverMediaId)
        assertEquals("image/jpeg", attachment.mime)
    }

    @Test
    fun `下载失败占位保留服务端引用不影响其它字段`() {
        val server = ServerMessage(id = "m1", sessionId = "s1", content = "图", attachments = listOf("/v1/media/broken"))
        val media = mapOf(
            "/v1/media/broken" to ServerMediaAttachment(
                serverMediaId = "broken",
                uri = "/v1/media/broken",
                local = false
            )
        )

        val merged = ServerMergePlan.message(server, "p1", null, media)
        val attachment = AttachmentsCodec.decode(merged.attachmentsJson).single()

        assertEquals("/v1/media/broken", attachment.uri)
        assertEquals("broken", attachment.serverMediaId)
        assertFalse(attachment.isAudioOrVideo)
    }

    @Test
    fun `裸 id 的服务端引用也能命中本地化结果`() {
        val server = ServerMessage(id = "m1", sessionId = "s1", content = "图", attachments = listOf("abc"))
        val media = mapOf(
            "/v1/media/abc" to ServerMediaAttachment(
                serverMediaId = "abc",
                uri = "file:///data/media/chat-abc.jpg",
                mime = "image/jpeg",
                local = true
            )
        )

        val attachment = AttachmentsCodec.decode(
            ServerMergePlan.message(server, "p1", null, media).attachmentsJson
        ).single()

        assertEquals("file:///data/media/chat-abc.jpg", attachment.uri)
        assertEquals("abc", attachment.serverMediaId)
    }

    @Test
    fun `表情本地化保留 STICKER kind 且不丢 serverMediaId`() {
        val local = message(
            attachmentsJson = AttachmentsCodec.encode(
                listOf(MessageAttachment(uri = "/v1/media/st1", mime = "image/gif", kind = MessageAttachment.KIND_STICKER))
            )
        )
        val server = ServerMessage(id = "m1", sessionId = "s1", content = "", attachments = listOf("/v1/media/st1"))
        val media = mapOf(
            "/v1/media/st1" to ServerMediaAttachment(
                serverMediaId = "st1",
                uri = "file:///data/stickers/sticker-st1.gif",
                mime = "image/gif",
                kind = MessageAttachment.KIND_STICKER,
                local = true
            )
        )

        val attachment = AttachmentsCodec.decode(
            ServerMergePlan.message(server, "p1", local, media).attachmentsJson
        ).single()

        assertEquals("file:///data/stickers/sticker-st1.gif", attachment.uri)
        assertEquals(MessageAttachment.KIND_STICKER, attachment.kind)
        assertEquals("st1", attachment.serverMediaId)
        assertTrue(attachment.isSticker)
    }

    @Test
    fun `服务端没有附件时保留本地附件 不清空`() {
        val local = message(
            attachmentsJson = AttachmentsCodec.encode(
                listOf(MessageAttachment(uri = "file:///data/media/chat-1.jpg"))
            )
        )
        val server = ServerMessage(id = "m1", sessionId = "s1", content = "重新同步", attachments = emptyList())

        val merged = ServerMergePlan.message(server, "p1", local)

        assertEquals(local.attachmentsJson, merged.attachmentsJson)
    }

    @Test
    fun `媒体计数随 plus 累加且旧字段保持兼容`() {
        val page = ServerSyncApplyResult(messages = 2, mediaDownloaded = 1, mediaFailed = 1, mediaSkipped = 2)
        val snapshot = ServerSyncApplyResult(personas = 1, sessions = 1, mediaDownloaded = 3)

        val total = page + snapshot

        assertEquals(2, total.messages)
        assertEquals(1, total.personas)
        assertEquals(4, total.mediaDownloaded)
        assertEquals(1, total.mediaFailed)
        assertEquals(2, total.mediaSkipped)
        assertEquals("默认值保持旧调用/旧测试兼容", 0, ServerSyncApplyResult().mediaDownloaded)
    }

    @Test
    fun `人格头像优先用刚落盘的本地文件`() {
        val server = ServerPersona(id = "p1", name = "他", avatarUrl = "/v1/media/av1", updatedAt = 20)

        val created = ServerMergePlan.persona(
            local = null,
            server = server,
            localAvatarUri = "file:///data/avatars/avatar-av1.jpg"
        )
        assertEquals("file:///data/avatars/avatar-av1.jpg", created.avatarUri)

        // 换设备后的旧数据：本地只存了服务端引用 → 换成刚落盘的文件。
        val stale = persona(avatarUri = "/v1/media/av1", updatedAt = 10)
        val upgraded = ServerMergePlan.persona(
            local = stale,
            server = server,
            localAvatarUri = "file:///data/avatars/avatar-av1.jpg"
        )
        assertEquals("file:///data/avatars/avatar-av1.jpg", upgraded.avatarUri)

        // 本地已有真文件 → 不换（离线也要能看）。
        val withFile = persona(avatarUri = "file:///data/avatars/avatar-old.jpg", updatedAt = 10)
        val kept = ServerMergePlan.persona(
            local = withFile,
            server = server,
            localAvatarUri = "file:///data/avatars/avatar-av1.jpg"
        )
        assertEquals("file:///data/avatars/avatar-old.jpg", kept.avatarUri)
    }

    @Test
    fun `没有本地头像文件时退回服务端引用`() {
        val server = ServerPersona(id = "p1", name = "他", avatarUrl = "/v1/media/av1", updatedAt = 20)
        val stale = persona(avatarUri = "/v1/media/av1", updatedAt = 10)

        val merged = ServerMergePlan.persona(local = stale, server = server, localAvatarUri = null)

        assertEquals("/v1/media/av1", merged.avatarUri)
        assertEquals(
            "连本地行都没有的新人格也退回服务端引用",
            "/v1/media/av1",
            ServerMergePlan.persona(local = null, server = server).avatarUri
        )
    }

    // ------------------------------------------------------------------ 构造器

    private fun message(attachmentsJson: String? = null): MessageEntity = MessageEntity(
        id = "m1",
        sessionId = "s1",
        personaId = "p1",
        role = MessageRole.ASSISTANT,
        content = "hi",
        createdAt = 10,
        updatedAt = 10,
        status = MessageStatus.COMPLETE,
        errorMessage = null,
        model = null,
        edited = false,
        attachmentsJson = attachmentsJson
    )

    private fun persona(avatarUri: String?, updatedAt: Long): PersonaEntity = PersonaEntity(
        id = "p1",
        name = "旧名字",
        description = "",
        avatarUri = avatarUri,
        avatarSeed = 0,
        prompt = "",
        category = "日常聊天",
        version = 1,
        defaultApiConfigId = null,
        modelOverride = null,
        isDefault = false,
        createdAt = 1,
        updatedAt = updatedAt
    )
}
