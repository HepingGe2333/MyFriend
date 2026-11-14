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

    /** 「次 / times」的措辞在界面层（`R.plurals.chat_trace_count`），这里只测分组与拼接。 */
    private val chineseCount: (Int) -> String = { count -> "$count 次" }

    @Test
    fun summarizeCountsRepeats() {
        val items = listOf(
            ToolTraceItem(name = "web_search", title = "联网搜索"),
            ToolTraceItem(name = "web_search", title = "联网搜索"),
            ToolTraceItem(name = "think", title = "想了想")
        )
        assertEquals("联网搜索 2 次 · 想了想", ToolTraceCodec.summarize(items, chineseCount))
        assertEquals("", ToolTraceCodec.summarize(emptyList(), chineseCount))
    }

    /**
     * 0 / 1 / 2 三种次数：1 不写数量，0 条痕迹没有摘要，2 才出现计数词。
     *
     * 英文的 "N times"/"once" 由 `R.plurals.chat_trace_count` 决定（见 `ToolTraceTextTest`），
     * 这里验证的是计数词确实只在 count > 1 时被调用。
     */
    @Test
    fun summarizeOnlyCountsWhenRepeated() {
        val once = listOf(ToolTraceItem(name = "web_search", title = "联网搜索"))
        assertEquals("联网搜索", ToolTraceCodec.summarize(once, chineseCount))

        val twice = once + once
        assertEquals("联网搜索 2 次", ToolTraceCodec.summarize(twice, chineseCount))

        var calls = 0
        val counted: (Int) -> String = { count -> calls++; "$count times" }
        assertEquals("联网搜索 3 times", ToolTraceCodec.summarize(twice + once, counted))
        assertEquals(1, calls)
    }

    @Test
    fun summarizeFallsBackToName() {
        val items = listOf(ToolTraceItem(name = "remember", title = ""))
        assertEquals("remember", ToolTraceCodec.summarize(items, chineseCount))
    }
}
