package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTraceTest {

    @Test
    fun encodeReturnsNullForEmptyList() {
        assertNull(ToolTraceCodec.encode(emptyList()))
    }

    @Test
    fun roundTripKeepsFields() {
        val items = listOf(
            ToolTraceItem(
                name = "web_search",
                title = "联网搜索",
                subtitle = "今天的新闻",
                detail = "查到 3 条",
                ok = true
            ),
            ToolTraceItem(name = "think", title = "想了想", detail = "要不要先问清楚", ok = false)
        )
        val raw = ToolTraceCodec.encode(items)
        val decoded = ToolTraceCodec.decode(raw)
        assertEquals(2, decoded.size)
        assertEquals("联网搜索", decoded[0].title)
        assertEquals("今天的新闻", decoded[0].subtitle)
        assertEquals(false, decoded[1].ok)
        assertNull(decoded[1].subtitle)
    }

    @Test
    fun decodeToleratesGarbage() {
        assertTrue(ToolTraceCodec.decode(null).isEmpty())
        assertTrue(ToolTraceCodec.decode("").isEmpty())
        assertTrue(ToolTraceCodec.decode("not json").isEmpty())
        // 宽松解析只会尽力而为，不抛异常就是合格；对象而非数组时返回空列表
        assertTrue(ToolTraceCodec.decode("[{\"name\":1}]").size <= 1)
        assertTrue(ToolTraceCodec.decode("{\"a\":1}").isEmpty())
    }

    @Test
    fun summarizeCountsRepeats() {
        val items = listOf(
            ToolTraceItem(name = "web_search", title = "联网搜索"),
            ToolTraceItem(name = "web_search", title = "联网搜索"),
            ToolTraceItem(name = "think", title = "想了想")
        )
        assertEquals("联网搜索 2 次 · 想了想", ToolTraceCodec.summarize(items))
        assertEquals("", ToolTraceCodec.summarize(emptyList()))
    }

    @Test
    fun summarizeFallsBackToName() {
        val items = listOf(ToolTraceItem(name = "remember", title = ""))
        assertEquals("remember", ToolTraceCodec.summarize(items))
    }
}
