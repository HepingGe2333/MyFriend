package com.yap.agentd.sticker

import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/** 一张输入图：原始字节 + mime（image/jpeg|image/png|image/gif）。 */
data class StickerInput(val bytes: ByteArray, val mime: String)

/** 合成成功的结果（GIF89a 字节 + 元数据）。 */
data class ComposedSticker(
    val gif: ByteArray,
    val width: Int,
    val height: Int,
    val frames: Int,
    val durationMs: Long
)

/** 可预期的合成失败；code 是稳定字符串，message 是中文一句（会原样进工具结果）。 */
class StickerComposeException(val code: String, message: String) : Exception(message)

/**
 * `sticker.make` 的服务端图片合成器：把「基底图 + 贴纸 + 文字」拼成一张符合
 * `spec/stickers/README.md` 上限的 GIF89a（长边 ≤320、帧数 ≤60、总时长 ≤6 s、文件 ≤512 KB）。
 *
 * 为什么要有它：模型工具 `sticker` 的 `make` 走服务端时手里只有几个 URL 的字节，
 * 而 Android 的 `Bitmap.compress` 没有 GIF 编码器、服务端又是「零第三方依赖」，
 * 所以这里用 JDK 自带的 Java2D 做栅格合成，再用同包的 [StickerGif] 编码
 * （与 App 端同一份 GIF89a 算法，导出的表情两端能互相读懂）。
 *
 * 设计取舍：
 * - **只读字节、不落盘、不联网**：下载与存储由调用方负责，这里是纯函数式的合成；
 * - **object + 无可变共享状态**：所有中间结果（帧序列、合成画布、排版）都只活在一次 [compose]
 *   里，同一个服务进程并发调用是安全的；
 * - **「压到 512 KB」是一条降级阶梯**（见 [LADDER]），不是一次压缩：先降色、再缩画布、
 *   再抽帧、最后兜底降到 16 色；每换一档画布都重新合成，字号与贴纸位置跟着画布重算；
 * - 字体只用 JDK 逻辑字体 `SansSerif`：`java.awt` 的逻辑字体在 headless 环境里会映射到
 *   JDK 自带字体，不需要 `javax.swing`、也不碰 `Frame`/`Window`。
 */
object StickerComposer {

    /** 输出长边的硬上限（`spec/stickers`）。 */
    const val MAX_EDGE = 320

    /** 输出帧数上限。 */
    const val MAX_FRAMES = 60

    /** 输出字节上限：512 KB。 */
    const val MAX_BYTES = 512 * 1024

    /** 输出总时长上限：6 秒。 */
    const val MAX_DURATION_MS = 6_000L

    /** 单张输入图字节上限：8 MiB（与 `WebUrlPolicy` 的下载上限一致）。 */
    const val MAX_INPUT_BYTES = 8 * 1024 * 1024

    /** 内置空白底的边长（`base == null` 时用）。 */
    const val BLANK_SIZE = 320

    /**
     * 编码前预量化的触发阈值：唯一颜色超过它才量化（≤4096 正好装进编码器的 16384 槽缓存）。
     */
    private const val MAX_UNIQUE_COLORS_BEFORE_QUANTIZE = 4096

    /**
     * 静态图（含内置空白底）单帧的停留时长：GIF 没有「无限停留」，100 ms 是各家渲染器的默认档。
     */
    private const val STATIC_FRAME_DELAY_MS = 100

    /**
     * 一帧的最短停留：GIF 的延迟以 1/100 s 记，<20 ms 会被很多渲染器当成 100 ms，
     * 所以任何一帧都不低于 20 ms（也是 [StickerGif] 解码时用的同一档下限）。
     */
    private const val MIN_FRAME_DELAY_MS = 20

    /** 贴纸长边占画布长边的比例。 */
    private const val OVERLAY_RATIO = 0.55

    /** 贴纸/文字离画布边缘的留白（px）。 */
    private const val MARGIN = 8

    /** 文字最多折几行：再多也没法在 320 px 里看清。 */
    private const val MAX_TEXT_LINES = 3

    /** 字号上限占画布高的比例。 */
    private const val TEXT_HEIGHT_RATIO = 0.28

    /** 字号下限：再小就等于没写。 */
    private const val MIN_FONT_SIZE = 12

    /** 文字截断的字符数（按 code point 数，emoji 算一个字）。 */
    private const val MAX_TEXT_CHARS = 60

    /** 画布任一边小于它就不叠字了：细条图上叠字只会糊成一团。 */
    private const val MIN_TEXT_CANVAS = 24

    /** 只依赖 JDK 逻辑字体，不要求系统装了某一个具体字体族。 */
    private const val FONT_FAMILY = "SansSerif"

    /** 描边宽度 = 字号 / 它（320 px 画布、90 px 字 → 5 px 描边）。 */
    private const val STROKE_DIVISOR = 16

    /** 内置空白底的渐变两端：浅奶油到浅米色，白字 + 黑描边在上面足够清楚。 */
    private val BLANK_TOP = Color(0xFFFCF4)
    private val BLANK_BOTTOM = Color(0xFFEFE2)

    /**
     * 降级阶梯：严格按 `spec/stickers` 的顺序「降色（128→64→32）→ 缩画布（240→200→160）→
     * 抽帧（≤30→≤15→≤8）→ 兜底 16 色」。
     *
     * 为什么要一档一档真编码：GIF 的字节数取决于调色板和 LZW 的实际命中率，
     * 用像素数估算会差好几倍（噪声图同尺寸能差 50 倍），只有编出来才知道过没过 512 KB。
     * 11 档覆盖了从「本来就不大」到「真·噪声 60 帧」的输入，最坏情况总耗时约 2 s。
     */
    private val LADDER = listOf(
        Attempt(MAX_EDGE, 256, MAX_FRAMES),
        Attempt(MAX_EDGE, 128, MAX_FRAMES),
        Attempt(MAX_EDGE, 64, MAX_FRAMES),
        Attempt(MAX_EDGE, 32, MAX_FRAMES),
        Attempt(240, 32, MAX_FRAMES),
        Attempt(200, 32, MAX_FRAMES),
        Attempt(160, 32, MAX_FRAMES),
        Attempt(160, 32, 30),
        Attempt(160, 32, 15),
        Attempt(160, 32, 8),
        Attempt(160, 16, 8)
    )

    /**
     * @param base 基底图；null = 内置空白底（320x320 浅色底，meme 风格）
     * @param overlay 要叠上去的图（贴纸/表情）；null = 不叠图
     * @param text 要叠的字；null/空白 = 不叠字
     * @param position "TOP" | "CENTER" | "BOTTOM"（大小写不敏感，默认/认不出 → "CENTER"）
     */
    fun compose(
        base: StickerInput?,
        overlay: StickerInput?,
        text: String?,
        position: String = "CENTER"
    ): ComposedSticker {
        val caption = truncateText(text)
        val zone = parseZone(position)
        // 什么素材都没给：这不是「合成一张空白」，而是调用方把参数拼错了，直接报错更早暴露问题。
        if (base == null && overlay == null && caption.isEmpty()) {
            throw StickerComposeException("empty_request", "没有可用的素材")
        }
        val baseClip = base?.let { decode(it) } ?: blankClip()
        val overlayClip = overlay?.let { decode(it) }

        // 帧序列只跟「帧预算」有关（抽帧与时长裁剪都不碰像素），阶梯里同一档预算会重复出现，
        // 缓存一次即可；真正贵的「合成」不缓存，见下面循环里的说明。
        val plans = HashMap<Int, List<StickerFrame>>()
        // 素材本来就比目标画布小时，几个缩画布档位会给出完全相同的画布：
        // 同一份像素 + 同一个调色板再编一次，结果必然逐字节相同，跳过。
        val tried = HashSet<String>()

        for (attempt in LADDER) {
            val frames = plans.getOrPut(attempt.maxFrames) { planFrames(baseClip, attempt.maxFrames) }
            // 画布尺寸只由「基底 + 目标长边」决定，先算出来做去重键（避免白合成一轮）。
            val canvas = fitSize(baseClip.width, baseClip.height, attempt.edge)
            val attemptKey = "${canvas.width}x${canvas.height}:${frames.size}:${attempt.colors}"
            if (!tried.add(attemptKey)) continue
            // 每一档都**重新合成**：画布尺寸变了，字号自适应、贴纸缩放与边距都必须按新画布重算，
            // 只改宽高字段会得到「文字溢出画布、贴纸飘到画布外」的图。
            val scene = renderScene(baseClip, frames, overlayClip, caption, zone, attempt.edge)
            // 照片类素材（几十万种颜色）会让 StickerGif 的调色板映射退化：每个未命中像素都要
            // 全表探测，一帧可能接近 1 秒。这里先做一步「只在颜色太多时才生效」的预量化
            // （每通道保留高 4 bit → ≤4096 色，正好落在编码器 16384 槽的映射缓存里），
            // 平涂/低色表情（正常表情包形态）像素完全不变，逐字节往返不受影响。
            val gif = StickerGif.encode(reduceColorsIfNeeded(scene), attempt.colors)
            if (gif.size <= MAX_BYTES) {
                val duration = scene.frames.sumOf { it.delayMs.toLong() }
                return ComposedSticker(gif, scene.width, scene.height, scene.frames.size, duration)
            }
        }
        // 走到这里说明连 160 px / 8 帧 / 16 色都还超 512 KB：宁可明确失败，也不返回一张超限的表情。
        throw StickerComposeException("too_large", "这张表情压不到 512 KB 以内")
    }

    // ------------------------------------------------------------ 解码

    /** 解码后的一份素材：与 [StickerSource] 同构，只是中间态（帧的 argb 还没上画布）。 */
    private class Clip(val width: Int, val height: Int, val frames: List<StickerFrame>)

    private fun invalidImage(): StickerComposeException =
        StickerComposeException("invalid_image", "这张图打不开")

    /**
     * 字节 → 帧序列。
     *
     * 为什么 GIF 走自己的解码器：`ImageIO` 的 GIF 读取器只给首帧，动图会直接丢帧；
     * PNG/JPEG 则交给 `ImageIO`（JDK 自带，零依赖）。
     */
    private fun decode(input: StickerInput): Clip {
        if (input.bytes.isEmpty()) throw invalidImage()
        if (input.bytes.size > MAX_INPUT_BYTES) {
            throw StickerComposeException("image_too_large", "图片太大（上限 8 MiB）")
        }
        // mime 可能带 `; charset=...` 之类参数，按分号前的主类型判断。
        val mime = input.mime.substringBefore(';').trim().lowercase()
        return when (mime) {
            "image/gif" -> {
                val source = StickerGif.decode(input.bytes) ?: throw invalidImage()
                Clip(source.width, source.height, source.frames)
            }

            // `image/jpg` 是常见的误写，按 JPEG 收下：拒绝它对用户没有任何好处。
            "image/png", "image/jpeg", "image/jpg" -> decodeRaster(input.bytes)

            else -> throw StickerComposeException("unsupported_image", "只支持 JPEG/PNG/GIF 图片")
        }
    }

    /** 静态图（PNG/JPEG）→ 单帧。ImageIO 只在真解不开时返回 null / 抛异常，这里统一折成 `invalid_image`。 */
    private fun decodeRaster(bytes: ByteArray): Clip {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: throw invalidImage()
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) throw invalidImage()
        val argb = IntArray(width * height)
        image.getRGB(0, 0, width, height, argb, 0, width)
        return Clip(width, height, listOf(StickerFrame(argb, STATIC_FRAME_DELAY_MS)))
    }

    /** 内置空白底：meme 风格的浅色渐变，白字 + 黑描边压在它上面仍然清楚。 */
    private fun blankClip(): Clip {
        val image = BufferedImage(BLANK_SIZE, BLANK_SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.paint = GradientPaint(0f, 0f, BLANK_TOP, 0f, BLANK_SIZE.toFloat(), BLANK_BOTTOM)
            g.fillRect(0, 0, BLANK_SIZE, BLANK_SIZE)
        } finally {
            g.dispose()
        }
        val argb = IntArray(BLANK_SIZE * BLANK_SIZE)
        image.getRGB(0, 0, BLANK_SIZE, BLANK_SIZE, argb, 0, BLANK_SIZE)
        return Clip(BLANK_SIZE, BLANK_SIZE, listOf(StickerFrame(argb, STATIC_FRAME_DELAY_MS)))
    }

    /**
     * 编码前的「颜色太多才预量化」：唯一不透明颜色超过 [MAX_UNIQUE_COLORS_BEFORE_QUANTIZE] 时，
     * 把每个通道的低 4 bit 抹掉（RGB 8bit → 高半字节，≤4096 色）。
     *
     * 为什么需要：`StickerGif.ColorMapper` 的缓存只有 16384 槽，照片感素材（>10 万色）会让
     * 每帧的映射退化到接近全表探测（实测 ~1 秒/帧，60 帧就是一分钟）。预量化只在「颜色确实
     * 过多」时发生，所以普通表情包（平涂、卡通、低色动图）的像素与逐字节输出**完全不变**。
     */
    private fun reduceColorsIfNeeded(source: StickerSource): StickerSource {
        val seen = HashSet<Int>()
        for (frame in source.frames) {
            for (argb in frame.argb) {
                // GIF 只有「全透明/不透明」两档：透明像素统一按 0 计，避免半透明把计数撑大。
                val key = if ((argb ushr 24) < 0x80) 0 else argb
                if (seen.add(key) && seen.size > MAX_UNIQUE_COLORS_BEFORE_QUANTIZE) {
                    return source.copy(
                        frames = source.frames.map { frame ->
                            frame.copy(argb = IntArray(frame.argb.size) { index -> quantize(frame.argb[index]) })
                        }
                    )
                }
            }
        }
        return source
    }

    /** 每通道保留高 4 bit（透明像素仍然全透明）。 */
    private fun quantize(argb: Int): Int {
        val alpha = argb ushr 24
        if (alpha < 0x80) return 0
        val red = (argb shr 16) and 0xFF and 0xF0
        val green = (argb shr 8) and 0xFF and 0xF0
        val blue = argb and 0xFF and 0xF0
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    // ------------------------------------------------------------ 帧预算

    /** 按帧预算抽帧，再把总时长截到 [MAX_DURATION_MS]。 */
    private fun planFrames(clip: Clip, maxFrames: Int): List<StickerFrame> {
        val frames = clip.frames
        val thinned = if (frames.size <= maxFrames) frames else thin(frames, maxFrames)
        return capDuration(thinned)
    }

    /**
     * 均匀分组抽帧：把源帧切成 [maxFrames] 段，每段保留第一帧，并把这帧之后被跳过帧的
     * delay **累加**到保留帧上。
     *
     * 为什么不是「每 N 帧取一张、delay 不变」：那样总时长会缩水，动图的动作会莫名变快；
     * 累加延迟能让「丢掉的那些帧」以「多停一会儿」的形式保留下来。
     */
    private fun thin(frames: List<StickerFrame>, maxFrames: Int): List<StickerFrame> {
        val groups = maxFrames.coerceAtLeast(1)
        // 调用方保证 frames.size > groups，因此每段至少一帧、`start` 不会越界。
        val out = ArrayList<StickerFrame>(groups)
        for (group in 0 until groups) {
            val start = group * frames.size / groups
            val end = (group + 1) * frames.size / groups
            var delay = 0
            for (index in start until end) delay += frames[index].delayMs
            out += StickerFrame(frames[start].argb, delay.coerceAtLeast(MIN_FRAME_DELAY_MS))
        }
        return out
    }

    /** 总时长超过 6 s 就从尾部砍帧，砍掉的时长也不允许靠「拉长最后一帧」补回来。 */
    private fun capDuration(frames: List<StickerFrame>): List<StickerFrame> {
        val budget = MAX_DURATION_MS.toInt()
        val out = ArrayList<StickerFrame>(frames.size)
        var elapsed = 0
        for (frame in frames) {
            if (elapsed >= budget) break
            val remaining = budget - elapsed
            // 剩下这点时间连一帧最短停留都放不下：宁可少一帧，也不让总时长超 6 s。
            if (remaining < MIN_FRAME_DELAY_MS && out.isNotEmpty()) break
            val delay = frame.delayMs.coerceIn(
                MIN_FRAME_DELAY_MS,
                remaining.coerceAtLeast(MIN_FRAME_DELAY_MS)
            )
            out += StickerFrame(frame.argb, delay)
            elapsed += delay
        }
        // Clip 至少有 1 帧，所以这里也至少有 1 帧（单帧不会进入上面的 break）。
        return out
    }

    // ------------------------------------------------------------ 逐帧合成

    private class Size(val width: Int, val height: Int)

    private class Placement(val x: Int, val y: Int, val width: Int, val height: Int)

    /** 等比缩到长边 ≤ [maxEdge]；已经够小就原样返回（不放大，避免把 16 px 小图拉成一片模糊）。 */
    private fun fitSize(width: Int, height: Int, maxEdge: Int): Size {
        val longEdge = max(width, height)
        if (longEdge <= maxEdge) return Size(width, height)
        val scale = maxEdge.toDouble() / longEdge
        return Size(
            max(1, (width * scale).roundToInt()),
            max(1, (height * scale).roundToInt())
        )
    }

    /**
     * 合成一档画布尺寸的全部帧：基底逐帧缩放到画布、贴纸按 [zone] 就位、文字每帧都重画。
     *
     * 每换一档画布都会整段重跑（字号按新画布宽高重新自适应、贴纸尺寸与边距重新算），
     * 而不是只把宽高字段改小——后者会让文字溢出、贴纸飘到画布外。
     * 「GIF 必须真的逐帧合成再重新编码」也落在这里：动起来的基底每一帧都有自己的画面。
     */
    private fun renderScene(
        base: Clip,
        baseFrames: List<StickerFrame>,
        overlay: Clip?,
        caption: String,
        zone: Zone,
        edge: Int
    ): StickerSource {
        val size = fitSize(base.width, base.height, edge)
        val canvasWidth = size.width
        val canvasHeight = size.height
        // 排版只跟画布尺寸有关，所有帧共用一份（画字比算字贵得多）。
        val textPlan = if (caption.isEmpty()) null else planText(caption, canvasWidth, canvasHeight)
        val placement = overlay?.let { placeOverlay(it, canvasWidth, canvasHeight, zone) }
        val frameCount = baseFrames.size

        val frames = ArrayList<StickerFrame>(frameCount)
        for (index in baseFrames.indices) {
            val canvas = BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB)
            val g = canvas.createGraphics()
            try {
                g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
                )
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                )
                g.drawImage(
                    toImage(baseFrames[index], base.width, base.height),
                    0, 0, canvasWidth, canvasHeight,
                    null
                )
                if (overlay != null && placement != null) {
                    // 动画贴纸只在「帧数跟基底一致」时逐帧对贴，否则永远贴第 1 帧：
                    // 用取模循环会让贴纸和基底的动作错位，看着像两张不相干的动图叠在一起。
                    val overlayIndex = if (overlay.frames.size == frameCount) index else 0
                    g.drawImage(
                        toImage(overlay.frames[overlayIndex], overlay.width, overlay.height),
                        placement.x,
                        placement.y,
                        placement.width,
                        placement.height,
                        null
                    )
                }
                if (textPlan != null) drawText(g, textPlan, zone, canvasWidth, canvasHeight)
            } finally {
                g.dispose()
            }
            val argb = IntArray(canvasWidth * canvasHeight)
            canvas.getRGB(0, 0, canvasWidth, canvasHeight, argb, 0, canvasWidth)
            frames += StickerFrame(argb, baseFrames[index].delayMs)
        }
        return StickerSource(canvasWidth, canvasHeight, frames)
    }

    /** 一帧 ARGB 像素 → `BufferedImage`（`setRGB` 会做一次拷贝，之后就不再改它）。 */
    private fun toImage(frame: StickerFrame, width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, frame.argb, 0, width)
        return image
    }

    /** 贴纸落点：横向永远居中，纵向按 [zone]，边距约 [MARGIN] px。 */
    private fun placeOverlay(
        overlay: Clip,
        canvasWidth: Int,
        canvasHeight: Int,
        zone: Zone
    ): Placement {
        val cap = max(1, (max(canvasWidth, canvasHeight) * OVERLAY_RATIO).toInt())
        val scaled = fitSize(overlay.width, overlay.height, cap)
        val x = (canvasWidth - scaled.width) / 2
        val y = when (zone) {
            Zone.TOP -> MARGIN
            Zone.CENTER -> (canvasHeight - scaled.height) / 2
            Zone.BOTTOM -> canvasHeight - scaled.height - MARGIN
        }.coerceIn(0, max(0, canvasHeight - scaled.height))
        return Placement(x, y, scaled.width, scaled.height)
    }

    // ------------------------------------------------------------ 文字

    private enum class Zone { TOP, CENTER, BOTTOM }

    /** 认不出就居中：未知取值更可能来自客户端版本差，而不是真的想做一张贴边的表情。 */
    private fun parseZone(position: String?): Zone = when (position?.trim()?.uppercase()) {
        "TOP" -> Zone.TOP
        "BOTTOM" -> Zone.BOTTOM
        else -> Zone.CENTER
    }

    private class TextPlan(
        val font: Font,
        val lines: List<String>,
        val lineWidths: IntArray,
        val lineHeight: Int,
        val ascent: Int,
        val blockHeight: Int,
        val strokeWidth: Int
    )

    /**
     * 排版：字号从「画布高的 28%」往下试，直到「行数 ≤ [MAX_TEXT_LINES] 且整块塞得下」，
     * 最小 [MIN_FONT_SIZE] px。
     *
     * 为什么要折行而不是硬截断：60 个汉字在 320 px 画布上按 12 px 单行也要 720 px，
     * 硬画会横向溢出被裁掉；折行 + 缩字号至少保证字都读得到。
     * 每行的宽度必须在 **graphics 还活着** 时量出来，所以 [TextPlan] 里带的是宽度而不是重新量的字符串。
     */
    private fun planText(caption: String, canvasWidth: Int, canvasHeight: Int): TextPlan? {
        if (canvasWidth < MIN_TEXT_CANVAS || canvasHeight < MIN_TEXT_CANVAS) return null
        val maxWidth = canvasWidth - 2 * MARGIN
        val maxByHeight = (canvasHeight * TEXT_HEIGHT_RATIO).toInt().coerceAtLeast(MIN_FONT_SIZE)
        val scratch = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g = scratch.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            var size = maxByHeight
            while (true) {
                val font = Font(FONT_FAMILY, Font.BOLD, size)
                g.font = font
                val metrics = g.fontMetrics
                val wrapped = wrapText(caption, metrics, maxWidth)
                val lineHeight = metrics.ascent + metrics.descent
                val blockHeight = lineHeight * wrapped.size
                val fits = wrapped.size <= MAX_TEXT_LINES && blockHeight <= canvasHeight - 2 * MARGIN
                if (fits || size <= MIN_FONT_SIZE) {
                    val lines = if (wrapped.size > MAX_TEXT_LINES) ellipsize(wrapped) else wrapped
                    val widths = IntArray(lines.size) { metrics.stringWidth(lines[it]) }
                    return TextPlan(
                        font = font,
                        lines = lines,
                        lineWidths = widths,
                        lineHeight = lineHeight,
                        ascent = metrics.ascent,
                        blockHeight = lineHeight * lines.size,
                        strokeWidth = max(1, size / STROKE_DIVISOR)
                    )
                }
                size = (size - 2).coerceAtLeast(MIN_FONT_SIZE)
            }
        } finally {
            g.dispose()
        }
    }

    /**
     * 贪心折行：按 code point 累加宽度，超了就另起一行。
     *
     * 用 code point 而不是 `Char`：直接按下标切会把 emoji 的代理对切成半个字，渲染成方块。
     */
    private fun wrapText(text: String, metrics: FontMetrics, maxWidth: Int): List<String> {
        if (metrics.stringWidth(text) <= maxWidth) return listOf(text)
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            val before = current.length
            current.appendCodePoint(codePoint)
            if (before > 0 && metrics.stringWidth(current.toString()) > maxWidth) {
                // 这个字放不下：把它退回去，作为下一行的开头。
                current.setLength(before)
                lines += current.toString()
                current.setLength(0)
                current.appendCodePoint(codePoint)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /** 行数超了就保留前几行，末行以「…」收尾。 */
    private fun ellipsize(lines: List<String>): List<String> {
        val kept = lines.take(MAX_TEXT_LINES - 1).toMutableList()
        kept += lines[MAX_TEXT_LINES - 1].dropLast(1) + "…"
        return kept
    }

    /**
     * 白字 + 黑描边：先按描边宽度往 4 个方向各画一次黑字，再盖一次白字。
     *
     * 为什么不用 `Stroke`：`drawString` 不吃描边，只能靠偏移叠画；4 次（上下左右）够覆盖
     * 「深色底糊字」和「浅色底糊字」两种情况，再多只是徒增绘制时间。
     */
    private fun drawText(g: Graphics2D, plan: TextPlan, zone: Zone, canvasWidth: Int, canvasHeight: Int) {
        val top = when (zone) {
            Zone.TOP -> MARGIN
            Zone.CENTER -> (canvasHeight - plan.blockHeight) / 2
            Zone.BOTTOM -> canvasHeight - MARGIN - plan.blockHeight
        }.coerceAtLeast(0)
        g.font = plan.font
        plan.lines.forEachIndexed { lineIndex, line ->
            val x = (canvasWidth - plan.lineWidths[lineIndex]) / 2
            val baseline = top + lineIndex * plan.lineHeight + plan.ascent
            val stroke = plan.strokeWidth
            g.color = Color.BLACK
            g.drawString(line, x - stroke, baseline)
            g.drawString(line, x + stroke, baseline)
            g.drawString(line, x, baseline - stroke)
            g.drawString(line, x, baseline + stroke)
            g.color = Color.WHITE
            g.drawString(line, x, baseline)
        }
    }

    /** `text.trim()` 为空 → 空串；否则按 code point 截到 [MAX_TEXT_CHARS] 个字。 */
    private fun truncateText(text: String?): String {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        var count = 0
        var end = 0
        while (end < trimmed.length && count < MAX_TEXT_CHARS) {
            end += Character.charCount(trimmed.codePointAt(end))
            count++
        }
        return trimmed.substring(0, end)
    }

    /** 一档降级尝试：目标长边 + 调色板上限 + 帧数上限。 */
    private class Attempt(val edge: Int, val colors: Int, val maxFrames: Int)
}
