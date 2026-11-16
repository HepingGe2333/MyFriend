package com.yap.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端媒体引用与渲染鉴权（M5）的纯 JVM 测试。
 *
 * 聊天图/头像在本地模式是 `file://`，服务端模式是裸 id 或 `/v1/media/<id>`；
 * 两条路径必须在同一个解析器里归一，且 Authorization **只**发给同源 `/v1/media/`。
 */
class ServerMediaRefTest {

    private val https = ServerConfig(baseUrl = "https://chat.example.com", token = "t1", enabled = true)

    @Test
    fun `裸 id 与路径与绝对 URL 都归一到同一个 id`() {
        assertEquals("media-1", ServerMediaRef.idOf("media-1"))
        assertEquals("media-1", ServerMediaRef.idOf("/v1/media/media-1"))
        assertEquals("media-1", ServerMediaRef.idOf("https://chat.example.com/v1/media/media-1"))
        assertEquals("media-1", ServerMediaRef.idOf("/v1/media/media-1?x=1"))
        assertEquals("/v1/media/media-1", ServerMediaRef.canonical("media-1"))
        assertNull(ServerMediaRef.idOf("chat-a.jpg"))
        assertNull(ServerMediaRef.idOf(""))
        assertNull(ServerMediaRef.idOf("/v1/media/../etc/passwd"))
    }

    @Test
    fun `带反代前缀的地址拼出正确绝对 URL`() {
        val config = ServerConfig(baseUrl = "https://host.example/mf/v1", token = "t1", enabled = true)

        assertEquals(
            "https://host.example/mf/v1/media/media-1",
            ServerMediaRef.resolve("/v1/media/media-1", config)
        )
        assertEquals(
            "https://host.example/mf/v1/media/media-1",
            ServerMediaRef.resolve("media-1", config)
        )
    }

    @Test
    fun `服务端关闭或地址不合法时解析为 null 而不是拼坏 URL`() {
        assertNull(ServerMediaRef.resolve("/v1/media/media-1", ServerConfig.DISABLED))
        assertNull(
            ServerMediaRef.resolve(
                "/v1/media/media-1",
                ServerConfig(baseUrl = "http://evil.example.com", enabled = true)
            )
        )
    }

    @Test
    fun `鉴权头只发给同源 v1 media 路径`() {
        assertEquals(
            "Bearer t1",
            ServerMediaRef.authorizationHeader("https://chat.example.com/v1/media/media-1", https)
        )
        // 别的域名：即使路径长得像，也不能把令牌送出去。
        assertNull(ServerMediaRef.authorizationHeader("https://evil.example.com/v1/media/media-1", https))
        // 同源但不是媒体路径：不附加（避免把令牌带给任意页面/图片）。
        assertNull(ServerMediaRef.authorizationHeader("https://chat.example.com/other.png", https))
        // 端口不同视为不同源。
        assertNull(ServerMediaRef.authorizationHeader("https://chat.example.com:8443/v1/media/x", https))
        // 没配令牌时没有头。
        assertNull(
            ServerMediaRef.authorizationHeader(
                "https://chat.example.com/v1/media/x",
                ServerConfig(baseUrl = "https://chat.example.com", enabled = true)
            )
        )
    }

    @Test
    fun `反代前缀下鉴权头仍按前缀匹配`() {
        val config = ServerConfig(baseUrl = "https://host.example/mf", token = "t2", enabled = true)

        assertEquals(
            "Bearer t2",
            ServerMediaRef.authorizationHeader("https://host.example/mf/v1/media/x", config)
        )
        assertNull(
            ServerMediaRef.authorizationHeader("https://host.example/other/v1/media/x", config)
        )
    }

    @Test
    fun `任何形态的媒体引用都能识别 普通文件名不算`() {
        assertTrue(ServerMediaRef.isMediaRef("media-1"))
        assertTrue(ServerMediaRef.isMediaRef("/v1/media/media-1"))
        assertTrue(ServerMediaRef.isMediaRef("https://chat.example.com/v1/media/media-1"))
        assertFalse(ServerMediaRef.isMediaRef("file:///data/media/chat-a.jpg"))
        assertFalse(ServerMediaRef.isMediaRef("content://media/external/images/1"))
        assertFalse(ServerMediaRef.isMediaRef("/v1/media/"))
    }
}
