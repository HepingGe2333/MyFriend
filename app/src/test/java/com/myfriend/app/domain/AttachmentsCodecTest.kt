package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsCodecTest {

    @Test
    fun roundTripsSingleImage() {
        val items = listOf(
            MessageAttachment(
                uri = "file:///data/user/0/com.myfriend.app.debug/files/media/a.jpg",
                mime = "image/jpeg",
                width = 1200,
                height = 900
            )
        )

        val encoded = AttachmentsCodec.encode(items)
        // 默认值（image/jpeg）不写进 JSON，因此这里只校验关键字段。
        assertTrue(encoded!!.contains("a.jpg"))
        assertTrue(encoded.contains("1200"))
        assertEquals(items, AttachmentsCodec.decode(encoded))
    }

    @Test
    fun encodesEmptyListAsNull() {
        assertNull(AttachmentsCodec.encode(emptyList()))
        assertTrue(AttachmentsCodec.decode(null).isEmpty())
        assertTrue(AttachmentsCodec.decode("").isEmpty())
    }

    @Test
    fun dropsBrokenPayloadsInsteadOfCrashing() {
        assertTrue(AttachmentsCodec.decode("不是 JSON").isEmpty())
        assertTrue(AttachmentsCodec.decode("[{\"mime\":\"image/png\"}]").isEmpty())
        assertTrue(AttachmentsCodec.decode("[{\"uri\":\"  \"}]").isEmpty())
    }

    @Test
    fun ignoresUnknownFieldsAndDefaultsMime() {
        val decoded = AttachmentsCodec.decode(
            """[{"uri":"file:///tmp/b.png","extra":"ignored"}]"""
        )

        assertEquals(1, decoded.size)
        assertEquals("image/jpeg", decoded.first().mime)
        assertTrue(decoded.first().isImage)
    }
}
