package com.yap.agentd.media

import com.yap.agentd.store.MediaMime
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit

/**
 * 用系统 `ffmpeg` 抽关键帧（可 `--ffmpeg` / `YAP_FFMPEG` 指定路径；不存在就明确「没有解码器」）。
 *
 * 为什么不是「自带解码器」：JVM 侧要解 H.264 只能引第三方（JCodec/JavaCV），而 `:agentd` 是
 * **零第三方依赖**的硬约束；系统 `ffmpeg` 是发行时就要求装好的外部程序，找不到时由
 * [MediaUnderstanding.watch] 明确失败（「服务器没有视频解码器，看不了视频」），绝不返回假帧。
 *
 * 调用策略：
 * - 路径解析一次（`@Volatile` 缓存）：显式参数 → `YAP_FFMPEG` → `PATH` 里的 `ffmpeg`；
 * - 每次抽帧新建 `yap-watch-*` 临时目录，输入写成 `input.mp4` / `input.mov`，输出 `frame%02d.jpg`；
 * - 30 秒超时（超时 `destroyForcibly`），stderr 最多读 4 KiB，只打**一行** `[agentd]` 日志
 *   （不含视频内容），`finally` 里删临时目录；
 * - 任何失败（进程起不来、非 0 退出、超时、没有可用的 JPEG）都返回空列表，不往外抛。
 *
 * 帧滤镜 = `thumbnail=100`（每 100 帧挑一张最有代表性的）+ scale 到长边 ≤ [maxEdge]
 * （`force_original_aspect_ratio=decrease`，不裁切、不变形）。不用 `ffprobe`：这里只需要画面。
 */
class FfmpegFrameExtractor(private val ffmpegPath: String? = null) : FrameExtractor {

    /** 已解析出的可执行文件路径；`null` = 没找到。volatile 缓存，避免每次 watch 都扫一遍 PATH。 */
    @Volatile
    private var cachedBinary: String? = null

    /** 是否已经探测过（和 [cachedBinary] 配对：`false` 时缓存值没有意义）。 */
    @Volatile
    private var probed = false

    /** 找到的可执行文件路径；找不到 null。 */
    fun resolvedBinary(): String? {
        if (probed) return cachedBinary
        synchronized(this) {
            if (!probed) {
                cachedBinary = probeBinary()
                probed = true
            }
            return cachedBinary
        }
    }

    override fun available(): Boolean = resolvedBinary() != null

    /**
     * 抽帧。失败返回空列表（**不抛异常**）；`available() == false` 时直接返回，不跑进程。
     */
    override fun extract(video: ByteArray, mime: String, maxFrames: Int, maxEdge: Int): List<ByteArray> {
        if (maxFrames <= 0 || video.isEmpty()) return emptyList()
        val binary = resolvedBinary() ?: return emptyList()
        var workDir: Path? = null
        try {
            workDir = Files.createTempDirectory(TEMP_PREFIX)
            val inputName = "input." + (MediaMime.extension(mime) ?: "mp4")
            Files.write(workDir.resolve(inputName), video)

            val process = ProcessBuilder(
                binary,
                "-y",
                "-hide_banner",
                "-loglevel", "error",
                "-i", inputName,
                "-vf", videoFilter(maxEdge),
                "-frames:v", maxFrames.toString(),
                "-q:v", "4",
                "frame%02d.jpg"
            )
                // 用绝对工作目录 + 相对文件名：临时目录路径即使带空格也不会把参数拆开。
                .directory(workDir.toFile())
                .start()

            // stderr 必须在等待时就被读走：管道（64 KiB）写满会把 ffmpeg 卡在写入上，waitFor 白等到超时。
            val stderr = StringBuilder()
            val pump = Thread({ drainStderr(process.errorStream, stderr) }, "yap-ffmpeg-stderr")
            pump.isDaemon = true
            pump.start()

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                pump.join(1_000)
                System.err.println("[agentd] ffmpeg 抽帧失败：超过 ${TIMEOUT_SECONDS} 秒没结束")
                return emptyList()
            }
            pump.join(1_000)

            val frames = readFrames(workDir, maxFrames)
            if (frames.isEmpty()) {
                val detail = stderr.toString().trim().replace(Regex("\\s+"), " ").take(STDERR_LIMIT)
                val reason = detail.ifEmpty { "没有产出可用的画面（exit=${process.exitValue()}）" }
                System.err.println("[agentd] ffmpeg 抽帧失败：$reason")
            }
            return frames
        } catch (failure: Exception) {
            // 路径不对、没有权限、磁盘满……都只是「这次抽帧失败」，由上层翻成一句人话。
            val detail = failure.message?.replace(Regex("\\s+"), " ")?.take(STDERR_LIMIT)
            System.err.println("[agentd] ffmpeg 抽帧失败：${failure.javaClass.simpleName}${detail?.let { "（$it）" } ?: ""}")
            return emptyList()
        } finally {
            workDir?.let { deleteQuietly(it) }
        }
    }

    // ------------------------------------------------------------------ 路径解析

    /**
     * 解析顺序：显式 [ffmpegPath] → 环境变量 `YAP_FFMPEG` → `ffmpeg`。
     *
     * 只解析**第一个非空候选**，不再往下兜底：显式配置写错了却悄悄用另一个 ffmpeg，
     * 比明确「没有解码器」更难排查。含 `/` / `\` 的当文件判断，否在 `PATH` 里逐个目录找。
     */
    private fun probeBinary(): String? {
        val candidate = ffmpegPath?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv(ENV_FFMPEG)?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_BINARY
        return findExecutable(candidate)
    }

    private fun findExecutable(candidate: String): String? = runCatching {
        if (candidate.contains('/') || candidate.contains('\\')) {
            val file = Path.of(candidate)
            if (Files.isRegularFile(file) && Files.isExecutable(file)) file.toAbsolutePath().toString() else null
        } else {
            System.getenv("PATH").orEmpty()
                .split(File.pathSeparatorChar)
                // 空段在 POSIX 里表示当前目录；这里跳过，免得在数据目录里误跑一个同名文件。
                .filter { it.isNotBlank() }
                .asSequence()
                .map { Path.of(it).resolve(candidate) }
                .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
                ?.toAbsolutePath()
                ?.toString()
        }
    }.getOrNull()

    // ------------------------------------------------------------------ 进程与临时文件

    /**
     * 帧滤镜：每 [THUMBNAIL_BATCH] 帧挑一张最有代表性的，再等比缩到长边 ≤ [maxEdge]。
     *
     * 注意 `min(1024,iw)` 里的逗号在 ffmpeg 的 filtergraph 里是**分隔符**，必须写成 `\,`；
     * 否则 ffmpeg 会把 `min(1024` 当成一个滤镜名直接报错（这正是不能用 shell 引号的原因：
     * 参数走 `ProcessBuilder` 时没有 shell 帮忙剥引号）。
     */
    private fun videoFilter(maxEdge: Int): String =
        "thumbnail=$THUMBNAIL_BATCH," +
            "scale=min($maxEdge\\,iw):min($maxEdge\\,ih):force_original_aspect_ratio=decrease"

    /** 读 `frame*.jpg`（按文件名排序），逐个校验 JPEG magic，最多 [maxFrames] 个。 */
    private fun readFrames(workDir: Path, maxFrames: Int): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        Files.newDirectoryStream(workDir, "frame*.jpg").use { entries ->
            for (file in entries.sortedBy { it.fileName.toString() }) {
                if (frames.size >= maxFrames) break
                val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: continue
                if (isJpeg(bytes)) frames += bytes
            }
        }
        return frames
    }

    /**
     * 读 stderr：只保留前 [STDERR_LIMIT] 字节，其余**继续读并丢掉**。
     *
     * 丢不掉就会写满管道把子进程卡死（见 [extract] 的注释）。
     */
    private fun drainStderr(stream: InputStream, sink: StringBuilder) {
        try {
            val buffer = ByteArray(1024)
            var kept = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (kept < STDERR_LIMIT) {
                    val take = minOf(read, STDERR_LIMIT - kept)
                    sink.append(String(buffer, 0, take, StandardCharsets.UTF_8))
                    kept += take
                }
            }
        } catch (_: Exception) {
            // 进程被强杀时管道会断：stderr 只是诊断信息，读不到就算了。
        }
    }

    /** 递归删临时目录；删不掉只留一个空目录，不影响本次结果。 */
    private fun deleteQuietly(dir: Path) {
        try {
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { candidate ->
                    runCatching { Files.deleteIfExists(candidate) }
                }
            }
        } catch (_: Exception) {
            // 系统临时目录最终会被回收，清理失败不升级成业务失败。
        }
    }

    private companion object {
        const val ENV_FFMPEG = "YAP_FFMPEG"
        const val DEFAULT_BINARY = "ffmpeg"
        const val TEMP_PREFIX = "yap-watch"
        const val THUMBNAIL_BATCH = 100
        const val TIMEOUT_SECONDS = 30L
        const val STDERR_LIMIT = 4 * 1024

        /** JPEG 文件头（`FF D8`）。 */
        fun isJpeg(bytes: ByteArray): Boolean =
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    }
}
