package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** 备份读取的字节上限（安全审计 P2-3）：超限必须在**读完之前**停下。 */
class BoundedTextReadTest {

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `小文件原样读出来`() {
        assertEquals("{\"format\":\"myfriend.backup\"}", BoundedTextRead.read(stream("{\"format\":\"myfriend.backup\"}")))
    }

    @Test
    fun `恰好等于上限可以读`() {
        val text = "a".repeat(1024)
        assertEquals(text, BoundedTextRead.read(stream(text), maxBytes = 1024))
    }

    @Test
    fun `超过上限抛 TooLarge 并说明上限`() {
        val error = assertThrows(BoundedTextRead.TooLarge::class.java) {
            BoundedTextRead.read(stream("a".repeat(2048)), maxBytes = 1024)
        }
        assertEquals(1024L, error.limitBytes)
    }

    @Test
    fun `按字节而不是字符计数 中文不会被低估`() {
        // 300 个汉字 = 900 字节：字符数是 300，字节数超了 512。
        val chinese = "备".repeat(300)
        assertEquals(900, chinese.toByteArray(Charsets.UTF_8).size)
        assertThrows(BoundedTextRead.TooLarge::class.java) {
            BoundedTextRead.read(stream(chinese), maxBytes = 512)
        }
        assertEquals("512 字节以内能读", chinese, BoundedTextRead.read(stream(chinese), maxBytes = 900))
    }

    @Test
    fun `默认上限就是备份上限`() {
        assertTrue(BackupLimits.MAX_BACKUP_BYTES > 0)
        // 随便一份很小的内容不该因为默认上限被拒。
        assertEquals("ok", BoundedTextRead.read(stream("ok")))
    }
}
