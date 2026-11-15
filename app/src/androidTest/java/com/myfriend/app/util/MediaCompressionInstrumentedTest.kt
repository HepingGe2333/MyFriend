package com.myfriend.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfriend.app.domain.BackupBase64
import com.myfriend.app.domain.BackupMedia
import com.myfriend.app.domain.BackupMediaKind
import com.myfriend.app.domain.BackupMediaLimits
import com.myfriend.app.domain.BackupMediaRestorer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 媒体压缩的**真 Android** 测试（Bitmap / BitmapFactory 在 JVM 单测里是 stub）。
 *
 * 纯逻辑部分（上限常量、压缩器接线、内容寻址命名、去重语义）在 `BackupMediaTest`；
 * 这里证明真的解码出了像素：长边落到上限、字节落到上限、透明图垫成白底、
 * 以及选图/备份导入两条路都接上了内容哈希去重。
 */
@RunWith(AndroidJUnit4::class)
class MediaCompressionInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        tempFiles.forEach { file -> runCatching { if (file.isDirectory) file.deleteRecursively() else file.delete() } }
        tempFiles.clear()
    }

    @Test
    fun 大图导入压缩后长边与字节都不超过上限() {
        val png = noisyPng(width = 3000, height = 2000)

        val chat = MediaStorage.compressBackupImage(png, "image/png", BackupMediaKind.CHAT_IMAGE)
        assertNotNull("聊天图应该压得出来", chat)
        chat!!
        assertEquals("image/jpeg", chat.mime)
        assertEquals("jpg", chat.extension)
        val chatBitmap = decode(chat.bytes)
        assertTrue("聊天图长边 ≤1600，实际 ${chatBitmap.width}x${chatBitmap.height}",
            maxOf(chatBitmap.width, chatBitmap.height) <= BackupMediaLimits.CHAT_MAX_EDGE)
        assertTrue("聊天图输出 ≤800KB，实际 ${chat.bytes.size}", chat.bytes.size <= BackupMediaLimits.MAX_CHAT_OUTPUT_BYTES)
        chatBitmap.recycle()

        val avatar = MediaStorage.compressBackupImage(png, "image/png", BackupMediaKind.PERSONA_AVATAR)
        assertNotNull("头像应该压得出来", avatar)
        avatar!!
        val avatarBitmap = decode(avatar.bytes)
        assertTrue("头像长边 ≤512，实际 ${avatarBitmap.width}x${avatarBitmap.height}",
            maxOf(avatarBitmap.width, avatarBitmap.height) <= BackupMediaLimits.AVATAR_MAX_EDGE)
        assertTrue("头像输出 ≤400KB，实际 ${avatar.bytes.size}", avatar.bytes.size <= BackupMediaLimits.MAX_AVATAR_OUTPUT_BYTES)
        avatarBitmap.recycle()
    }

    @Test
    fun 透明图压缩成JPEG时垫白底() {
        val transparent = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        // 全透明；若压缩不垫白底，JPEG 解出来会是黑色。
        val png = pngBytes(transparent)
        transparent.recycle()

        val compressed = MediaStorage.compressBackupImage(png, "image/png", BackupMediaKind.PERSONA_AVATAR)
            ?: error("透明 PNG 应该压得出来")
        val decoded = decode(compressed.bytes)
        val pixel = decoded.getPixel(150, 150)
        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)
        assertTrue("透明区域应该变白，实际 rgb=($red,$green,$blue)", red >= 250 && green >= 250 && blue >= 250)
        decoded.recycle()
    }

    @Test
    fun 坏图与超小图返回null而不是抛异常() {
        assertEquals(null, MediaStorage.compressBackupImage(ByteArray(0), "image/png", BackupMediaKind.CHAT_IMAGE))
        assertEquals(
            null,
            MediaStorage.compressBackupImage(byteArrayOf(1, 2, 3, 4), "image/png", BackupMediaKind.CHAT_IMAGE)
        )
    }

    @Test
    fun 备份导入用真压缩器落盘并去重() {
        val root = File(context.cacheDir, "media-import-${System.nanoTime()}").apply { mkdirs() }
        tempFiles += root
        val avatarDir = File(root, "avatars").apply { mkdirs() }
        val chatDir = File(root, "media").apply { mkdirs() }
        val png = noisyPng(width = 1200, height = 800)
        val entries = listOf(
            BackupMedia(
                id = "1", kind = BackupMediaKind.CHAT_IMAGE, fileName = "chat-a.png",
                mime = "image/png", bytes = png.size, data = BackupBase64.encode(png)
            ),
            BackupMedia(
                id = "2", kind = BackupMediaKind.CHAT_IMAGE, fileName = "chat-b.png",
                mime = "image/png", bytes = png.size, data = BackupBase64.encode(png)
            )
        )
        val restored = BackupMediaRestorer.restore(
            entries = entries,
            avatarDir = avatarDir,
            chatDir = chatDir,
            compressor = { bytes, mime, kind -> MediaStorage.compressBackupImage(bytes, mime, kind) }
        )
        assertEquals("两张同图只落一份文件", 1, restored.writtenFiles.size)
        assertEquals(2, restored.chatImagesByFileName.size)
        val first = restored.chatImagesByFileName.getValue("chat-a.png")
        val second = restored.chatImagesByFileName.getValue("chat-b.png")
        assertEquals(first.file, second.file)
        assertTrue("聊天图文件名是内容寻址", Regex("""chat-[0-9a-f]{16}\.jpg""").matches(first.file.name))
        assertTrue("落盘字节不超上限", first.file.length() <= BackupMediaLimits.MAX_CHAT_OUTPUT_BYTES)
        assertTrue("附件 mime 要改成 jpeg", first.mime == "image/jpeg")
    }

    @Test
    fun 重复选同一张聊天图只保留一份文件() = runBlocking {
        val source = File(context.cacheDir, "pick-${System.nanoTime()}.png")
        tempFiles += source
        source.writeBytes(noisyPng(800, 600))
        val first = MediaStorage.persistChatImage(context, Uri.fromFile(source)).getOrThrow()
        val second = MediaStorage.persistChatImage(context, Uri.fromFile(source)).getOrThrow()
        assertEquals("同一张图重复选应该复用同一个文件", first.uri, second.uri)
        val file = MediaStorage.fileOf(first.uri)!!
        assertTrue("文件应该真的在", file.isFile)
        assertTrue("文件名是内容寻址", Regex("""chat-[0-9a-f]{16}\.(jpg|png)""").matches(file.name))
        // 清理测试写进真实媒体目录的文件。
        tempFiles += file
    }

    private fun decode(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("压缩输出解不出来")

    private fun pngBytes(bitmap: Bitmap): ByteArray {
        val buffer = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
        return buffer.toByteArray()
    }

    /** 造一张有噪声的大 PNG（纯色图 JPEG 太小，测不出字节上限路径）。 */
    private fun noisyPng(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint()
        var seed = 7
        for (i in 0 until 1200) {
            seed = seed * 1103515245 + 12345
            val x = (seed ushr 8) % width
            val y = (seed ushr 16) % height
            paint.color = Color.rgb(
                (seed ushr 0) and 0xFF,
                (seed ushr 8) and 0xFF,
                (seed ushr 16) and 0xFF
            )
            canvas.drawCircle(x.toFloat(), y.toFloat(), 18f, paint)
        }
        val bytes = pngBytes(bitmap)
        bitmap.recycle()
        return bytes
    }
}
