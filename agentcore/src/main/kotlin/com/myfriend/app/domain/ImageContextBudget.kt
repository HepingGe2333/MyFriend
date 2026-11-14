package com.myfriend.app.domain

/**
 * 「聊天上下文里一次带多少张图」的预算（审计 P2-9）。
 *
 * ## 为什么需要它
 *
 * 0.10.2 之前装配上下文时是「最近 3 条带图的消息 × 每条最多 4 张 × 单张最多 4MB」：
 * 12 张 4MB 的 PNG 原文件 → base64 约 1.33 倍 → 约 64MB 字符串，再被请求体
 * `payload.toString()` 复制一遍，峰值逼近 128MB。中端机（heap 192–256MB）这一轮直接 OOM，
 * 而触发条件只是「连着发了几张截图，再发一句」。
 *
 * ## 现在的两道限制
 *
 * 1. **数量**：只带最近 [MAX_IMAGE_MESSAGES_IN_CONTEXT] 条带图消息，每条最多
 *    [MAX_IMAGES_PER_MESSAGE] 张，合计上限 [MAX_IMAGES_IN_CONTEXT] 张；
 * 2. **尺寸**：单张在发送前按 [TARGET_EDGE_PX] 长边重编码成 JPEG，字节数压到
 *    [MAX_ENCODED_BYTES] 以内，再整体守住 [MAX_CONTEXT_ENCODED_CHARS] 的字符串预算。
 *
 * 这里只有纯计算（挑哪几条、还能不能再塞一张、单张的目标尺寸），真正的解码/缩放/压缩
 * 在 `MediaStorage` 里做——这样「预算」这件事可以在普通 JVM 单测里钉住，不需要 Bitmap。
 *
 * 为什么不干脆把图丢掉：图片是 1.1.0 起就有的对话内容，丢掉等于「他看不见你发的图」。
 * 这里做的是「看得见最近的，但别把手机撑爆」。
 */
object ImageContextBudget {

    /** 上下文里最多带几条「带图的消息」。 */
    const val MAX_IMAGE_MESSAGES_IN_CONTEXT = 2

    /** 单条消息最多带几张图（与发送侧的 4 张一致）。 */
    const val MAX_IMAGES_PER_MESSAGE = 4

    /** 一次上下文最多带几张图：两条消息 × 每条四张。 */
    const val MAX_IMAGES_IN_CONTEXT = MAX_IMAGE_MESSAGES_IN_CONTEXT * MAX_IMAGES_PER_MESSAGE

    /** 重编码时长边上限（像素）。视觉模型按 1024 已经足够看清截图里的字。 */
    const val TARGET_EDGE_PX = 1024

    /** 单张重编码后的字节上限：320KB。8 张合计约 2.6MB，base64 后约 3.5MB。 */
    const val MAX_ENCODED_BYTES = 320 * 1024

    /**
     * 一次上下文里所有 data URL 字符数的硬上限（4MB）。
     *
     * base64 之后的字符串会进请求体，还会被序列化复制一份；守在这里就不必在
     * 「拼 JSON」那一刻才发现内存不够。
     */
    const val MAX_CONTEXT_ENCODED_CHARS = 4 * 1024 * 1024

    /** 重编码的 JPEG 质量阶梯：先按高质量写，超预算就往下走。 */
    val JPEG_QUALITY_LADDER = listOf(80, 72, 64, 56, 48)

    /** 一条消息的「带不带图」视图：只挑用户消息，助手消息不会带图。 */
    data class Candidate(
        val messageId: String,
        val isUser: Boolean,
        val hasImages: Boolean
    )

    /**
     * 从「按时间正序」的消息列表里挑出要带图的那几条。
     *
     * 只认用户消息、只认真的有附件的，取**最近** [MAX_IMAGE_MESSAGES_IN_CONTEXT] 条。
     * 返回 messageId 集合，调用方据此决定给哪几条解码。
     */
    fun ownersWithImages(candidates: List<Candidate>): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        return candidates
            .filter { it.isUser && it.hasImages }
            .takeLast(MAX_IMAGE_MESSAGES_IN_CONTEXT)
            .map { it.messageId }
            .toSet()
    }

    /** 一条消息最多解码几张：传进来多少就夹到 [MAX_IMAGES_PER_MESSAGE]。 */
    fun imagesForMessage(count: Int): Int = count.coerceIn(0, MAX_IMAGES_PER_MESSAGE)

    /**
     * 解码后要把位图缩到的目标尺寸（纯函数，审计 P2-10 / 0.11.1）。
     *
     * API 28+ 的 `ImageDecoder` 能直接按目标尺寸解码；API 26–27 只能用
     * `BitmapFactory.inSampleSize` 粗采样（只能保证不超过 [TARGET_EDGE_PX] 的 2 倍），
     * 之后再调一次 `Bitmap.createScaledBitmap` 才真正落到「长边 ≤ 1024」。
     *
     * @return 目标宽高；已经是目标尺寸以内（或输入非法）时返回 null，表示不用缩。
     */
    fun scaledSize(width: Int, height: Int, targetEdge: Int = TARGET_EDGE_PX): Pair<Int, Int>? {
        if (width <= 0 || height <= 0 || targetEdge <= 0) return null
        val longest = maxOf(width, height)
        if (longest <= targetEdge) return null
        val scale = targetEdge.toFloat() / longest
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        return scaledWidth to scaledHeight
    }

    /**
     * 还能不能再塞一张已经编码好的 data URL。
     *
     * [usedChars] 是已经拼进去的字符数。空值（解不出来 / 文件没了）不占预算，返回 false。
     */
    fun acceptsEncoded(usedChars: Int, next: String?): Boolean {
        if (next == null) return false
        val used = usedChars.coerceAtLeast(0)
        return used + next.length <= MAX_CONTEXT_ENCODED_CHARS
    }

    /** 最坏情况下所有图加起来占的字符数（纯 base64 主体，不含前缀）：给单测一个明确上界。 */
    fun worstCaseEncodedChars(): Int {
        // base64 = ceil(n/3)*4，留一点前缀（"data:image/jpeg;base64,"）的余量。
        val base64 = ((MAX_ENCODED_BYTES + 2) / 3) * 4
        return base64 * MAX_IMAGES_IN_CONTEXT + MAX_IMAGES_IN_CONTEXT * 32
    }
}
