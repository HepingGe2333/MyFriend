package com.yap.app.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.MessageStatus
import com.yap.app.data.local.YapDatabase
import com.yap.app.data.local.PersonaEntity
import com.yap.app.domain.AttachmentsCodec
import com.yap.app.domain.BACKUP_FORMAT_VERSION
import com.yap.app.domain.BackupAvatarPath
import com.yap.app.domain.BackupBundle
import com.yap.app.domain.BackupCodec
import com.yap.app.domain.BackupDecodeResult
import com.yap.app.domain.BackupMediaCandidate
import com.yap.app.domain.BackupMediaFormats
import com.yap.app.domain.BackupMediaKind
import com.yap.app.domain.BackupMediaPacker
import com.yap.app.domain.BackupMediaRestorer
import com.yap.app.domain.MessageAttachment
import com.yap.app.domain.toBackupMessage
import com.yap.app.domain.toBackupPersona
import com.yap.app.domain.toBackupSession
import com.yap.app.util.MediaStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.Random

/**
 * **备份 v3 的媒体路径基准**：500 张图 + 500 条消息，从导出到导入的内存 / 体积 / 耗时。
 *
 * ## 它回答什么
 *
 * v3 的备份把图片用 base64 塞进**一个 JSON 字符串**里。这带来两个必须量出来的问题：
 *
 * 1. 一份带 500 张图的备份，在手机上生成时**峰值内存**是多少？会不会在低堆机器上直接 OOM？
 * 2. 导入时要把整份文件读成一个 `String` 再解析，这个「先整体读进内存」的代价有多大？
 *
 * 结论直接决定「要不要改成流式备份（边读边写 / 流式 JSON）」，以及「该不该给媒体加更硬的上限」。
 *
 * ## 它测的是哪条路径
 *
 * **真的生产代码**：`MediaStorage.ownedFile`（目录归属守卫）→ `BackupMediaPacker.pack`（读文件 +
 * base64）→ `MessageEntity.toBackupMessage`（附件引用换裸文件名）→ `BackupCodec.encode`
 * （真 JSON 编码）→ 写文件；导入侧 `BackupCodec.decode` + `BackupMediaRestorer.restore`（真落盘）。
 *
 * 只有一处不同：**没有构造整个 `BackupRepository`**。它的构造函数要 20 多个依赖
 * （DataStore、ApiConfigRepository、ChatRepository…），在 instrumented test 里拼装它们
 * 既脆又跟被测的东西无关；`export()` 里属于「读库」的那几步（每个人的消息、去重后的会话）
 * 都在本测试里按同样的顺序用真实 DAO 做了一遍。差异是常量级开销，报告里标注了。
 *
 * ## 为什么不只测「500 × 800KB」
 *
 * 因为 128MiB 的总预算 + 192MB 的堆，会让「500 × 800KB」在编码阶段直接 OOM——而 OOM 本身
 * 就是最有价值的结论之一。所以这里跑一条**从 0 张到 500 × 800KB 的梯度**：
 * 报告里给出「能过 / 过不去」的分界线，比只报一个失败更有用。
 *
 * 默认跳过（见 [benchEnabled]），跑法写在 `docs/performance.md`。
 */
@RunWith(AndroidJUnit4::class)
class BackupMediaBenchmarkTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val recorder = BenchRecorder("backup-media")
    private var room: YapDatabase? = null

    @Before
    fun setUp() {
        assumeTrue("benchmark 默认跳过：需要 -Pandroid.testInstrumentationRunnerArguments.bench=true", benchEnabled())
    }

    @After
    fun tearDown() {
        runCatching { room?.close() }
        room = null
        runCatching { context.deleteDatabase(DB_NAME) }
        cleanupMedia()
        cleanupImportDirs()
    }

    @Test
    fun 五百张图的备份导出与导入基准() {
        recorder.putEnvironment(context)
        recorder.put("fixture.messagesPerProfile", MESSAGES_PER_PROFILE)
        recorder.put("fixture.mediaBudgetBytes", com.yap.app.domain.BackupMediaLimits.MAX_MEDIA_BYTES)
        recorder.put("fixture.perFileLimitBytes", com.yap.app.domain.BackupMediaLimits.MAX_MEDIA_FILE_BYTES)

        val database = openRoom()

        // 梯度：控制组（0 张图）→ 32KB → 64KB → 128KB → 250KB → 800KB（题目要求的 500 × ≤800KB 在最后一档）。
        val profiles = listOf(
            Profile("control-no-media", 500, 0),
            Profile("500x32KB", 500, 32 * 1024),
            Profile("500x64KB", 500, 64 * 1024),
            Profile("500x128KB", 500, 128 * 1024),
            Profile("500x250KB", 500, 250 * 1024),
            Profile("500x800KB", 500, 800 * 1024)
        )

        runBlocking {
            seedPersonaAndSession(database)
            profiles.forEach { profile ->
                runProfile(database, profile)
                // 每档跑完就落一次盘：低堆设备上后面几档可能把进程撑爆，
                // 已经量到的数字不能跟着陪葬。
                recorder.writeJsonFile(context)
            }
        }
        recorder.writeJsonFile(context)
    }

    // ------------------------------------------------------------------
    // 一档：造图 → 灌消息 → 导出 → 导入
    // ------------------------------------------------------------------

    private suspend fun runProfile(database: YapDatabase, profile: Profile) {
        val key = "profile.${profile.name}"
        recorder.put("$key.targetImageBytes", profile.targetImageBytes)
        try {
            val imageStart = System.nanoTime()
            val images = createImages(profile)
            val imageBytes = images.sumOf { it.length() }
            recorder.put("$key.imageCount", images.size)
            recorder.put("$key.imageBytesTotal", imageBytes)
            recorder.put("$key.imageBytesAvg", if (images.isEmpty()) 0 else imageBytes / images.size)
            recorder.put("$key.imageCreateMs", round2(elapsedMsSince(imageStart)))

            seedMessages(database, images)

            exportAndMeasure(database, profile, key)
            importAndMeasure(profile, key)
        } catch (failure: Throwable) {
            // OOM 是这类基准的**正常结果**之一（它就是「现状在低堆机器上撑不住」的证据），
            // 记录后继续下一档：不能因为一档爆炸就丢掉整份报告。
            recorder.put("$key.failed", true)
            recorder.put("$key.failure", failure::class.java.name + ": " + (failure.message ?: ""))
            Log.w(BenchLog.TAG, "profile ${profile.name} failed", failure)
        } finally {
            cleanupMedia()
            cleanupImportDirs()
            forceGc()
            recorder.put("$key.afterGcJavaUsedMb", readMemory(context).javaUsedMb)
        }
    }

    private data class ExportOutcome(
        val file: File,
        val latencyMs: Double,
        val writeMs: Double,
        val chars: Int,
        val utf8Bytes: Long,
        val stringHeapBytes: Long,
        val mediaPacked: Int,
        val mediaSkipped: Int,
        val mediaBytes: Long,
        val peak: HeapPeakSampler.Result,
        val afterGc: MemoryReading
    )

    /**
     * 导出。返回时 `encoded` 这个上百 MB 的字符串已经出了作用域——导入测量要的是
     * 「只有一份 JSON 在内存里」的状态，不能拿导出的残留当基线。
     */
    private suspend fun exportAndMeasure(database: YapDatabase, profile: Profile, key: String) {
        forceGc()
        val baseline = readMemory(context)
        recorder.put("$key.export.baselineJavaUsedMb", baseline.javaUsedMb)
        recorder.put("$key.export.baselineTotalPssMb", baseline.totalPssMb)

        val sampler = HeapPeakSampler().start()
        val cpuStart = processCpuMs()
        val started = System.nanoTime()
        var stage = "start"
        try {
            stage = "load-messages"
            val messages = database.messageDao().getBySession(SESSION_ID)

            stage = "collect-media"
            val mediaDir = MediaStorage.mediaDirectory(context)
            val candidates = ArrayList<BackupMediaCandidate>()
            messages.forEach { message ->
                AttachmentsCodec.decode(message.attachmentsJson).forEach { attachment ->
                    // 与 BackupRepository.export 的 addCandidate 同一套守卫：只认自己目录里、真实存在的图。
                    val file = MediaStorage.ownedFile(attachment.uri, mediaDir) ?: return@forEach
                    if (!BackupAvatarPath.isSafeName(file.name)) return@forEach
                    val mime = BackupMediaFormats.mimeOfFile(file.name) ?: return@forEach
                    candidates += BackupMediaCandidate(
                        kind = BackupMediaKind.CHAT_IMAGE,
                        personaId = message.personaId,
                        fileName = file.name,
                        file = file,
                        mime = mime
                    )
                }
            }

            stage = "pack-media"
            val packed = BackupMediaPacker.pack(candidates)
            val mediaBytes = packed.media.sumOf { it.bytes.toLong() }
            // 先落一次：后面 encode 阶段很可能 OOM，但「打包进了多少张图」这个结论必须留下。
            recorder.put("$key.export.mediaPacked", packed.media.size)
            recorder.put("$key.export.mediaSkippedByLimit", packed.skippedByLimit)
            recorder.put("$key.export.mediaBytesIncluded", mediaBytes)

            stage = "map-messages"
            val chatImageNames = packed.media
                .filter { it.kind == BackupMediaKind.CHAT_IMAGE }
                .map { it.fileName }
                .toSet()
            val backupMessages = messages.map { it.toBackupMessage(chatImageNames) }

            stage = "bundle"
            val bundle = BackupBundle(
                formatVersion = BACKUP_FORMAT_VERSION,
                appVersion = "bench",
                exportedAt = System.currentTimeMillis(),
                personas = database.personaDao().getAll().map { it.toBackupPersona() },
                sessions = database.chatSessionDao().getAll().map { it.toBackupSession() },
                messages = backupMessages,
                media = packed.media
            )

            stage = "encode"
            val encoded = BackupCodec.encode(bundle)
            val latencyMs = elapsedMsSince(started)
            val cpuMs = processCpuMs() - cpuStart

            stage = "measure-json"
            val size = jsonSizeOf(encoded)
            val secretCheck = BackupCodec.containsSecretField(encoded)

            stage = "write-file"
            val outDir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(outDir, "bench-media-${profile.name}.json")
            val writeStart = System.nanoTime()
            file.outputStream().writer(Charsets.UTF_8).use { it.write(encoded) }
            val writeMs = elapsedMsSince(writeStart)

            val peak = sampler.stop()
            forceGc()
            val afterGc = readMemory(context)

            recorder.put("$key.export.latencyMs", round2(latencyMs))
            recorder.put("$key.export.cpuMs", cpuMs)
            recorder.put("$key.export.writeMs", round2(writeMs))
            recorder.put("$key.export.jsonChars", size.chars)
            recorder.put("$key.export.jsonUtf8Bytes", size.utf8Bytes)
            recorder.put("$key.export.jsonStringHeapBytes", size.stringHeapBytes)
            recorder.put("$key.export.jsonFileBytes", file.length())
            recorder.put("$key.export.mediaPacked", packed.media.size)
            recorder.put("$key.export.mediaSkippedByLimit", packed.skippedByLimit)
            recorder.put("$key.export.mediaBytesIncluded", mediaBytes)
            recorder.put("$key.export.containsSecretField", secretCheck)
            recorder.put("$key.export.peakJavaUsedMb", peak.peakMb)
            recorder.put("$key.export.peakDeltaMb", peak.deltaMb)
            recorder.put("$key.export.afterGcJavaUsedMb", afterGc.javaUsedMb)
            recorder.put("$key.export.afterTotalPssMb", afterGc.totalPssMb)
            recorder.put("$key.export.afterJavaHeapPssMb", afterGc.javaHeapPssMb)
            recorder.put("$key.export.mediaDominated", profile.targetImageBytes > 0)
        } catch (failure: Throwable) {
            val peak = sampler.stop()
            recorder.put("$key.export.failedStage", stage)
            recorder.put("$key.export.peakJavaUsedMb", peak.peakMb)
            recorder.put("$key.export.peakDeltaMb", peak.deltaMb)
            recorder.put("$key.export.error", failure::class.java.name + ": " + (failure.message ?: ""))
            throw failure
        }
    }

    private suspend fun importAndMeasure(profile: Profile, key: String) {
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(outDir, "bench-media-${profile.name}.json")
        if (!file.isFile) {
            recorder.put("$key.import", "skipped:导出没有产出文件")
            return
        }

        forceGc()
        val baseline = readMemory(context)
        recorder.put("$key.import.baselineJavaUsedMb", baseline.javaUsedMb)

        val sampler = HeapPeakSampler().start()
        val cpuStart = processCpuMs()
        val started = System.nanoTime()
        var stage = "start"
        try {
            stage = "read-file"
            val raw = file.readText()

            stage = "decode"
            val decoded = BackupCodec.decode(raw)

            stage = "restore-media"
            val avatarDir = File(context.filesDir, "bench-import/avatars")
            val chatDir = File(context.filesDir, "bench-import/chat")
            var restoredImages = 0
            var mediaWarnings = 0
            var decodeWarnings = 0
            when (decoded) {
                is BackupDecodeResult.Success -> {
                    decodeWarnings = decoded.warnings.size + decoded.mediaWarnings.size
                    val restored = BackupMediaRestorer.restore(decoded.bundle.media, avatarDir, chatDir)
                    restoredImages = restored.chatImagesByFileName.size
                    mediaWarnings = restored.warnings.size
                }

                is BackupDecodeResult.Failure -> {
                    recorder.put("$key.import.decodeFailure", decoded.reason.toString())
                }

                is BackupDecodeResult.Corrupted -> {
                    recorder.put("$key.import.decodeFailure", decoded.reason.toString())
                }
            }
            val latencyMs = elapsedMsSince(started)
            val cpuMs = processCpuMs() - cpuStart
            val peak = sampler.stop()
            forceGc()
            val afterGc = readMemory(context)

            recorder.put("$key.import.latencyMs", round2(latencyMs))
            recorder.put("$key.import.cpuMs", cpuMs)
            recorder.put("$key.import.restoredImages", restoredImages)
            recorder.put("$key.import.decodeWarnings", decodeWarnings)
            recorder.put("$key.import.mediaWarnings", mediaWarnings)
            recorder.put("$key.import.peakDeltaMb", peak.deltaMb)
            recorder.put("$key.import.peakJavaUsedMb", peak.peakMb)
            recorder.put("$key.import.afterGcJavaUsedMb", afterGc.javaUsedMb)
        } catch (failure: Throwable) {
            val peak = sampler.stop()
            recorder.put("$key.import.failedStage", stage)
            recorder.put("$key.import.peakJavaUsedMb", peak.peakMb)
            recorder.put("$key.import.peakDeltaMb", peak.deltaMb)
            recorder.put("$key.import.error", failure::class.java.name + ": " + (failure.message ?: ""))
            throw failure
        } finally {
            cleanupImportDirs()
            forceGc()
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun openRoom(): YapDatabase {
        context.deleteDatabase(DB_NAME)
        val built = Room.databaseBuilder(context, YapDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        room = built
        built.openHelper.writableDatabase
        recorder.put("schema.version", built.openHelper.writableDatabase.version)
        return built
    }

    private suspend fun seedPersonaAndSession(database: YapDatabase) {
        val now = System.currentTimeMillis()
        database.personaDao().insert(
            PersonaEntity(
                id = PERSONA_ID,
                name = "小满",
                description = "备份基准",
                avatarUri = null,
                avatarSeed = 7,
                prompt = "你是小满",
                category = "COMPANION",
                version = 1,
                defaultApiConfigId = null,
                modelOverride = null,
                isDefault = true,
                createdAt = now,
                updatedAt = now
            )
        )
        database.chatSessionDao().insert(
            ChatSessionEntity(
                id = SESSION_ID,
                personaId = PERSONA_ID,
                apiConfigId = null,
                model = "bench-model",
                title = "五百张图",
                lastMessagePreview = "晚安",
                lastMessageAt = now,
                unreadCount = 0,
                pinned = false,
                createdAt = now,
                updatedAt = now,
                lastPersonaVersion = 1,
                lastMemoryExtractedCount = 0
            )
        )
    }

    /** 500 条消息；有图的时候一条带一张（与真实使用最接近的形状）。 */
    private suspend fun seedMessages(database: YapDatabase, images: List<File>) {
        database.openHelper.writableDatabase.execSQL("DELETE FROM `messages`")
        val started = System.nanoTime()
        val messages = ArrayList<MessageEntity>(MESSAGES_PER_PROFILE)
        for (index in 0 until MESSAGES_PER_PROFILE) {
            val image = images.getOrNull(index)
            val attachments = image?.let { file ->
                val uri = "file://" + file.absolutePath
                AttachmentsCodec.encode(listOf(MessageAttachment(uri = uri, mime = "image/jpeg")))
            }
            val createdAt = System.currentTimeMillis() + index
            messages += MessageEntity(
                id = String.format(Locale.US, "b%05d", index),
                sessionId = SESSION_ID,
                personaId = PERSONA_ID,
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = ImportedChatCorpus.contentAt(index),
                createdAt = createdAt,
                updatedAt = createdAt,
                status = MessageStatus.COMPLETE,
                errorMessage = null,
                model = "bench-model",
                edited = false,
                attachmentsJson = attachments
            )
        }
        database.messageDao().insertAll(messages)
        recorder.put("fixture.seedMessagesMs", round2(elapsedMsSince(started)))
    }

    /**
     * 造图：先用噪点 JPEG 逼近目标字节数，再在这个分辨率上压三种质量当「不同照片」。
     *
     * 为什么是噪点：真实照片的压缩率差很多，噪点图的可压缩性最差、最接近「压不小的图」这个上界；
     * 而基准要测的是 base64 与内存，不是 JPEG 编码器的效率。
     */
    private fun createImages(profile: Profile): List<File> {
        if (profile.imageCount <= 0 || profile.targetImageBytes <= 0) return emptyList()
        val dir = MediaStorage.mediaDirectory(context)
        val variants = buildJpegVariants(profile.targetImageBytes)
        val files = ArrayList<File>(profile.imageCount)
        for (index in 0 until profile.imageCount) {
            val bytes = variants[index % variants.size]
            val file = File(dir, String.format(Locale.US, "chat-bench-%s-%05d.jpg", profile.name, index))
            file.writeBytes(bytes)
            files += file
        }
        recorder.put("profile.${profile.name}.variantBytes", variants.joinToString(",") { it.size.toString() })
        return files
    }

    private fun buildJpegVariants(targetBytes: Int): List<ByteArray> {
        var side = Math.sqrt(targetBytes.toDouble()).toInt().coerceIn(32, 4096)
        var bytes = renderNoiseJpeg(side, JPEG_QUALITY)
        var attempts = 0
        while (attempts < 5 && (bytes.size > targetBytes * 1.4 || bytes.size < targetBytes * 0.6)) {
            val ratio = Math.sqrt(targetBytes.toDouble() / bytes.size).coerceIn(0.4, 2.5)
            side = (side * ratio).toInt().coerceIn(32, 4096)
            bytes = renderNoiseJpeg(side, JPEG_QUALITY)
            attempts++
        }
        return listOf(JPEG_QUALITY - 12, JPEG_QUALITY, JPEG_QUALITY + 8)
            .map { quality -> renderNoiseJpeg(side, quality.coerceIn(40, 100)) }
    }

    private fun renderNoiseJpeg(side: Int, quality: Int): ByteArray {
        val random = Random(side.toLong() * 31 + quality)
        val pixels = IntArray(side * side)
        for (index in pixels.indices) {
            // 每个像素都不同 → JPEG 压不动，正好拿来当「体积上限」的素材。
            pixels[index] = 0xFF000000.toInt() or (random.nextInt() and 0x00FFFFFF)
        }
        val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------
    // 清理与小工具
    // ------------------------------------------------------------------

    private fun cleanupMedia() {
        val dir = File(context.filesDir, "media")
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith("chat-bench-")) file.delete()
            }
        }
    }

    private fun cleanupImportDirs() {
        runCatching { File(context.filesDir, "bench-import").deleteRecursively() }
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        runCatching {
            outDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("bench-media-")) file.delete()
            }
        }
    }

    private data class JsonSize(val chars: Int, val utf8Bytes: Long, val stringHeapBytes: Long)

    /**
     * 一份 JSON 字符串的「体积」的三种口径。
     *
     * `stringHeapBytes` 是最容易被忽略的那个：Java 9+ 的紧凑字符串在**出现任何非 Latin-1 字符**
     * 时会退回 UTF-16（2 字节/字符）。我们的 JSON 里有中文正文，于是 base64（本来 1 字节/字符）
     * 也跟着变成 2 字节/字符——一个 128MiB 的媒体预算，在堆上要占 342MB。
     * 这不是实现细节，这正是「要不要流式」的关键数字。
     */
    private fun jsonSizeOf(text: String): JsonSize {
        var utf8 = 0L
        var hasNonLatin1 = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val code = char.code
            if (code > 0xFF) hasNonLatin1 = true
            utf8 += when {
                code < 0x80 -> 1
                code < 0x800 -> 2
                Character.isHighSurrogate(char) &&
                    index + 1 < text.length &&
                    Character.isLowSurrogate(text[index + 1]) -> {
                    index++
                    4
                }

                else -> 3
            }
            index++
        }
        val heapBytes = if (hasNonLatin1) text.length * 2L else text.length.toLong()
        return JsonSize(text.length, utf8, heapBytes)
    }

    private fun benchEnabled(): Boolean =
        InstrumentationRegistry.getArguments().getString("bench") == "true"

    private data class Profile(
        val name: String,
        val imageCount: Int,
        val targetImageBytes: Int
    )

    private companion object {
        const val DB_NAME = "bench-backup.db"
        const val PERSONA_ID = "persona-bench"
        const val SESSION_ID = "session-bench"
        const val MESSAGES_PER_PROFILE = 500
        const val JPEG_QUALITY = 88
    }
}

/**
 * 备份基准里的 500 条消息正文：短句、中英混排，贴近真实聊天（不是 lorem ipsum）。
 *
 * 长度刻意控制在一两句：备份体积的主体应该是图片，正文只占零头——
 * 这样报告里「JSON 体积 ≈ base64 体积 × 4/3」这句话才成立。
 */
internal object ImportedChatCorpus {

    private val pool = listOf(
        "今天也在加班，回家的路上买了杯冰美式。",
        "你看这张照片，楼下的橘猫又来蹭饭了。",
        "刚把窗台的花浇完，叶子还是有点蔫。",
        "周末去看海吧？天气预报说周六是晴天。",
        "这张是上次说的那家小馆子，记得存好。",
        "我把书桌收拾了一遍，翻出一张旧电影票。",
        "地铁上听到一首老歌，突然有点想哭。",
        "凌晨三点还没睡着，就拍了张窗外的路灯。",
        "刚洗完澡，头发还在滴水，屋里很安静。",
        "今天的夕阳特别好看，可惜你没在。"
    )

    private val english = listOf(
        "Shot this on the way home — the light was unreal.",
        "Saved you a seat by the window.",
        "This one is for the album we keep talking about."
    )

    fun contentAt(index: Int): String {
        val random = Random(index * 104_729L + 7L)
        val builder = StringBuilder(80)
        builder.append(pool[random.nextInt(pool.size)])
        if (index % 4 == 0) {
            builder.append(' ').append(english[random.nextInt(english.size)])
        }
        return builder.toString()
    }
}
