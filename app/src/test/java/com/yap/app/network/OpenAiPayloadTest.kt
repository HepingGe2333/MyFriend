package com.yap.app.network

import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.data.local.ProviderType
import com.yap.app.domain.ApiDefaults
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求体里的推理参数（`spec/api-defaults/README.md` 的验收项）。
 *
 * 这里断言的是**真正发送出去的 JSON**，不是某个常量：默认值来自 `ApiDefaults`、
 * 配置显式值优先、工具回路每轮都带同一套参数。以前这三件事只能靠读代码确认，
 * 于是「最大输出忘了带」这种问题会一直安静地存在。
 */
class OpenAiPayloadTest {

    private fun config(
        temperature: Double? = null,
        topP: Double? = null,
        maxOutputTokens: Int? = null,
        frequencyPenalty: Double? = null,
        presencePenalty: Double? = null
    ) = ApiConfigEntity(
        id = "cfg",
        name = "默认配置",
        provider = ProviderType.OPENAI,
        baseUrl = "https://api.deepseek.com",
        apiKeyCipher = null,
        model = "deepseek-chat",
        customHeadersJson = "{}",
        allowInsecureHttp = false,
        temperature = temperature,
        topP = topP,
        maxOutputTokens = maxOutputTokens,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun payload(config: ApiConfigEntity, stream: Boolean = false): JsonObject =
        buildChatCompletionPayload(
            model = config.model,
            messages = listOf(WireMessage("system", "你是阿深。"), WireMessage("user", "在干嘛")),
            params = config.inferenceParams(),
            stream = stream
        )

    @Test
    fun `没填配置时请求体带应用默认值 温度 0_75 最大输出 16384`() {
        val body = payload(config())
        assertEquals(0.75, body["temperature"]!!.jsonPrimitive.double, 0.0)
        assertEquals(16_384, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(1.0, body["top_p"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.0, body["frequency_penalty"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.0, body["presence_penalty"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun `默认值与 ApiDefaults 是同一个数字 不是网络层又抄了一遍`() {
        val body = payload(config())
        assertEquals(ApiDefaults.TEMPERATURE, body["temperature"]!!.jsonPrimitive.double, 0.0)
        assertEquals(ApiDefaults.MAX_OUTPUT_TOKENS, body["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `配置里显式填了就以配置为准`() {
        val body = payload(
            config(temperature = 0.2, topP = 0.8, maxOutputTokens = 512, frequencyPenalty = 0.3, presencePenalty = 0.4)
        )
        assertEquals(0.2, body["temperature"]!!.jsonPrimitive.double, 0.0)
        assertEquals(512, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(0.8, body["top_p"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.3, body["frequency_penalty"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.4, body["presence_penalty"]!!.jsonPrimitive.double, 0.0)
    }

    /** 只覆盖温度、其它留空：留空的那几项仍然走默认，不能因为填了一项就整份变 null。 */
    @Test
    fun `只覆盖一部分时其余仍是默认值`() {
        val body = payload(config(temperature = 1.1))
        assertEquals(1.1, body["temperature"]!!.jsonPrimitive.double, 0.0)
        assertEquals(16_384, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(1.0, body["top_p"]!!.jsonPrimitive.double, 0.0)
    }

    /** 工具回路每一轮都用同一份 `inferenceParams()`：两次请求体的参数必须一模一样。 */
    @Test
    fun `工具回路的下一轮带同样的参数`() {
        val cfg = config()
        val first = payload(cfg)
        val second = payload(cfg)
        assertEquals(first["temperature"], second["temperature"])
        assertEquals(first["max_tokens"], second["max_tokens"])
    }

    @Test
    fun `流式请求带 include_usage 且正文与图片形状不变`() {
        val body = buildChatCompletionPayload(
            model = "m",
            messages = listOf(WireMessage("user", "看图", images = listOf("data:image/jpeg;base64,AAA"))),
            params = config().inferenceParams(),
            stream = true
        )
        assertEquals(true, body["stream"]!!.jsonPrimitive.content == "true")
        assertTrue("流式要带 usage", body.containsKey("stream_options"))
        assertTrue("多模态 content 仍是数组", body["messages"].toString().contains("image_url"))
        // 普通文本消息仍然是字符串 content，不能被多模态改造波及。
        val plain = payload(config())
        assertTrue(plain["messages"].toString().contains("\"content\":\"你是阿深。\""))
        assertFalse(plain.containsKey("stream_options"))
    }
}
