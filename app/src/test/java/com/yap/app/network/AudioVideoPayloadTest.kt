package com.yap.app.network

import com.yap.app.domain.AudioVideoPolicy
import com.yap.app.domain.MediaPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音/视频的请求体形状（`spec/audio-video/README.md`「模型侧」）。
 *
 * 断言真正发出去的 JSON：
 * - 语音 → `{"type":"input_audio","input_audio":{"data":<base64>,"format":"m4a"}}`；
 * - 视频帧 → 多张 `image_url`，一轮最多 8 张；
 * - 没有媒体的历史消息仍然是纯字符串 content（不产生空 parts 数组）。
 */
class AudioVideoPayloadTest {

    private fun payload(messages: List<WireMessage>): JsonObject = buildChatCompletionPayload(
        model = "test-model",
        messages = messages,
        params = InferenceParams(),
        stream = false
    )

    private fun contentParts(body: JsonObject, index: Int): JsonArray =
        body["messages"]!!.jsonArray[index].jsonObject["content"]!!.jsonArray

    @Test
    fun audioIsEncodedAsInputAudioPart() {
        val part = MediaPayload.audioPart(byteArrayOf(1, 2, 3, 4), "audio/m4a", 12_000)!!
        val body = payload(
            listOf(
                WireMessage("system", "你是阿深。"),
                WireMessage(
                    role = "user",
                    content = "你听听这个",
                    audios = listOf(WireAudio(base64 = part.base64, format = part.format))
                )
            )
        )
        val parts = contentParts(body, index = 1)
        assertEquals(2, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        val audio = parts[1].jsonObject
        assertEquals("input_audio", audio["type"]!!.jsonPrimitive.content)
        val inputAudio = audio["input_audio"]!!.jsonObject
        assertEquals(part.base64, inputAudio["data"]!!.jsonPrimitive.content)
        assertEquals("m4a", inputAudio["format"]!!.jsonPrimitive.content)
        assertFalse("不能带 data: 前缀", inputAudio["data"]!!.jsonPrimitive.content.startsWith("data:"))
    }

    @Test
    fun audioOnlyMessageHasNoEmptyTextPart() {
        val body = payload(
            listOf(
                WireMessage("user", "", audios = listOf(WireAudio("QUJD", "m4a")))
            )
        )
        val parts = contentParts(body, index = 0)
        assertEquals(1, parts.size)
        assertEquals("input_audio", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun videoFramesAreImageUrlPartsAndCappedAtEight() {
        val frames = MediaPayload.frameDataUrls(List(12) { ByteArray(8) { index -> index.toByte() } })
        assertEquals(AudioVideoPolicy.MAX_VIDEO_FRAMES, frames.size)
        val body = payload(
            listOf(
                WireMessage(
                    role = "user",
                    content = "视频：8 帧，时长 37 秒",
                    images = frames
                )
            )
        )
        val parts = contentParts(body, index = 0)
        assertEquals("1 段文字 + 8 张图", 9, parts.size)
        val imageParts = parts.drop(1)
        assertEquals(8, imageParts.size)
        imageParts.forEach { part ->
            assertEquals("image_url", part.jsonObject["type"]!!.jsonPrimitive.content)
            assertTrue(
                part.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                    .startsWith("data:image/jpeg;base64,")
            )
        }
    }

    @Test
    fun textImagesAndAudioCoexistInOrder() {
        val body = payload(
            listOf(
                WireMessage(
                    role = "user",
                    content = "看也听",
                    images = listOf("data:image/jpeg;base64,AAA"),
                    audios = listOf(WireAudio("QUJD", "m4a"))
                )
            )
        )
        val types = contentParts(body, index = 0).map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("text", "image_url", "input_audio"), types)
    }

    @Test
    fun historyWithoutMediaStaysPlainString() {
        val body = payload(
            listOf(
                WireMessage("user", "（对方发来一段 12 秒的语音）"),
                WireMessage("assistant", "听到了，像是海边")
            )
        )
        val messages = body["messages"]!!.jsonArray
        messages.forEach { message ->
            val content = message.jsonObject["content"]!!
            assertTrue("没有多模态内容时 content 仍是字符串", content is kotlinx.serialization.json.JsonPrimitive)
        }
    }

    @Test
    fun systemMessageIsNeverMultimodal() {
        val body = payload(listOf(WireMessage("system", "你是阿深。")))
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!
        assertTrue(content is kotlinx.serialization.json.JsonPrimitive)
    }
}
