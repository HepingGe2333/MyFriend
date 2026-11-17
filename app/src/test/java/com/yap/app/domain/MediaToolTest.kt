package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `listen` / `watch` 工具结果的形状（`spec/audio-video/README.md`「模型侧」）。
 *
 * 「模型真的收到多模态内容」在数据层就体现在这两条：
 * 1. [ToolResult.media] 里真的有 base64 语音 / 帧 data URL；
 * 2. 摘要与正文是「我听了一遍…」「我看了 N 帧…」，而不是一句「已处理」。
 */
class MediaToolTest {

    private fun invocation(name: String, arguments: String = "{}") =
        ToolInvocation(id = "call_1", name = name, argumentsJson = arguments)

    @Test
    fun listeningCarriesAudioBase64AndSummary() {
        val clip = AudioClipPart(base64 = "AAAA", format = "m4a", durationMs = 12_000)
        val result = MediaTool.listening(invocation(ToolCatalog.LISTEN), clip, 12_000)

        assertTrue(result.ok)
        assertTrue("摘要要像「我听了一遍」", result.summary.startsWith("我听了一遍"))
        assertTrue(result.summary.contains("12 秒"))
        val media = result.media
        assertNotNull(media)
        assertEquals(1, media!!.audios.size)
        assertEquals("AAAA", media.audios.first().base64)
        assertEquals("m4a", media.audios.first().format)
        assertTrue(media.images.isEmpty())
        assertTrue(media.caption.contains("听了一遍"))
    }

    @Test
    fun watchingCarriesFramesAndFrameCount() {
        val frames = listOf("data:image/jpeg;base64,AAA", "data:image/jpeg;base64,BBB")
        val result = MediaTool.watching(invocation(ToolCatalog.WATCH), frames, 37_000)

        assertTrue(result.ok)
        assertTrue(result.summary.startsWith("我看了 2 帧"))
        assertTrue(result.summary.contains("37 秒"))
        val media = result.media!!
        assertEquals(frames, media.images)
        assertTrue(media.audios.isEmpty())
        assertTrue(media.caption.contains("视频：2 帧"))
    }

    @Test
    fun missingMediaSaysSoWithoutPretending() {
        val audio = MediaTool.missing(invocation(ToolCatalog.LISTEN), AudioVideoKind.AUDIO)
        assertFalse(audio.ok)
        assertTrue(audio.summary.contains("没有语音"))
        assertTrue(audio.contentForModel.contains("没有语音"))
        assertNull("没有媒体就不该带任何多模态内容", audio.media)
        // 不许伪造转写
        assertFalse(audio.contentForModel.contains("转写"))

        val video = MediaTool.missing(invocation(ToolCatalog.WATCH), AudioVideoKind.VIDEO)
        assertFalse(video.ok)
        assertTrue(video.summary.contains("没有视频"))
        assertNull(video.media)
    }

    @Test
    fun unreadableMediaIsDifferentFromMissing() {
        val result = MediaTool.unreadable(invocation(ToolCatalog.LISTEN), AudioVideoKind.AUDIO)
        assertFalse(result.ok)
        assertTrue(result.summary.contains("打不开"))
        assertFalse(result.summary.contains("没有语音"))
        assertNull(result.media)
    }

    @Test
    fun requestedIdReadsBothParameterNames() {
        assertEquals(
            "av:m1:0",
            MediaTool.requestedId(invocation(ToolCatalog.LISTEN, """{"audioId":"av:m1:0"}"""))
        )
        assertEquals(
            "av:m2:1",
            MediaTool.requestedId(invocation(ToolCatalog.WATCH, """{"videoId":"av:m2:1"}"""))
        )
        assertEquals(
            "av:m3:0",
            MediaTool.requestedId(invocation(ToolCatalog.WATCH, """{"mediaId":"av:m3:0"}"""))
        )
        assertNull(MediaTool.requestedId(invocation(ToolCatalog.LISTEN, "{}")))
        assertNull(MediaTool.requestedId(invocation(ToolCatalog.WATCH, """{"videoId":"   "}""")))
    }

    @Test
    fun toolSpecsAreRegisteredWithOptionalIds() {
        val listen = ToolCatalog.byName(ToolCatalog.LISTEN)
        assertNotNull(listen)
        assertTrue(listen!!.parametersJson.contains("\"audioId\""))
        // audioId 选填：不填就听最近一条
        assertFalse(listen.parametersJson.contains("\"required\""))

        val watch = ToolCatalog.byName(ToolCatalog.WATCH)
        assertNotNull(watch)
        assertTrue(watch!!.parametersJson.contains("\"videoId\""))
        assertFalse(watch.parametersJson.contains("\"required\""))

        // 都出现在「你能做什么」里，否则模型根本不会调用
        val prompt = ToolCatalog.capabilityPrompt()
        assertTrue(prompt.contains("- " + ToolCatalog.signature(ToolCatalog.byName(ToolCatalog.LISTEN)!!) + "："))
        assertTrue(prompt.contains("- " + ToolCatalog.signature(ToolCatalog.byName(ToolCatalog.WATCH)!!) + "："))
    }

    @Test
    fun toolResultJsonKeepsMediaOutOfTheTextChannel() {
        // 媒体只走 media 字段，不塞进 contentForModel（否则 base64 会被当成正文写进上下文）
        val clip = AudioClipPart(base64 = "QUJD", format = "m4a")
        val result = MediaTool.listening(invocation(ToolCatalog.LISTEN), clip, 3_000)
        assertFalse(result.contentForModel.contains("QUJD"))
        val json = ToolProtocol.toolMessageJson(result)
        assertFalse("原生 tool 消息里不该出现 base64", json.contains("QUJD"))
    }
}
