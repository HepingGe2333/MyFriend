package com.yap.app.domain

/**
 * 表情包的领域模型 + 模型工具 `sticker` 的**纯逻辑**（`spec/stickers/README.md`）。
 *
 * 这个文件不碰 Room / 文件 / Android 解码：所有副作用都在 [StickerWorkspace] 后面，
 * 生产实现是 `data/repo/StickerRepository`，单测塞假实现就能把 `send` / `make` / `save`
 * 三条路径（含假下载器、假图片）跑一遍，不需要真机。
 *
 * 回灌给模型的中文按 `docs/I18N.md` 的约定保持中文（提示词脚手架不翻译）；
 * 给用户看的标题/副标题走 `R.string.tool_trace_sticker*`，不在这里。
 */

/** 表情的来源（落库值，与 `spec/stickers/SCHEMA.md` 的 `source` 逐字一致）。 */
enum class StickerSourceKind {
    IMPORTED,
    MODEL_MADE,
    FROM_USER_MESSAGE,
    WEB
}

/** 谁做的（落库值，与 `createdBy` 逐字一致）。 */
enum class StickerCreator {
    USER,
    MODEL
}

/** 一张表情（`stickers` 表在领域层的投影）。 */
data class StickerRecord(
    val id: String,
    val file: String,
    val mime: String = MIME_GIF,
    val width: Int = 0,
    val height: Int = 0,
    val frames: Int = 1,
    val bytes: Int = 0,
    val source: String = StickerSourceKind.IMPORTED.name,
    val createdBy: String = StickerCreator.USER.name,
    val createdAt: Long = 0L,
    val personaId: String? = null,
    val ownerUserId: String? = null
) {
    val isAnimated: Boolean get() = frames > 1

    companion object {
        /** 静态图导入也是单帧 GIF：存储格式统一，渲染只有一条路。 */
        const val MIME_GIF = "image/gif"
    }
}

/** 叠字/叠图的位置。 */
enum class StickerPosition {
    TOP,
    CENTER,
    BOTTOM;

    companion object {
        /** 模型可能写成 top / 上 / 上方：认不出就居中（最安全的默认）。 */
        fun parse(raw: String?): StickerPosition = when (raw?.trim()?.lowercase()) {
            "top", "upper", "上", "上方", "顶部" -> TOP
            "bottom", "lower", "下", "下方", "底部" -> BOTTOM
            else -> CENTER
        }
    }
}

/** 工具 `sticker` 的三个动作（字符串是模型传进来的值）。 */
object StickerToolAction {
    const val SEND = "send"
    const val MAKE = "make"
    const val SAVE = "save"

    fun normalize(raw: String?): String = raw?.trim()?.lowercase().orEmpty()
}

/** `make` 的素材组合（都来自模型参数）。 */
data class StickerMakeRequest(
    val url: String? = null,
    val baseStickerId: String? = null,
    val text: String? = null,
    val overlayStickerId: String? = null,
    val position: StickerPosition = StickerPosition.CENTER
) {
    val hasAnyMaterial: Boolean
        get() = !url.isNullOrBlank() || !baseStickerId.isNullOrBlank() ||
            !text.isNullOrBlank() || !overlayStickerId.isNullOrBlank()
}

/** 失败原因（枚举而不是字符串：界面与回灌文本都从这里派生，改文案不会改判定）。 */
enum class StickerToolFailure {
    MISSING_ACTION,
    UNKNOWN_ACTION,
    STICKER_NOT_FOUND,
    MESSAGE_NOT_FOUND,
    NO_STICKER_IN_MESSAGE,
    EMPTY_MAKE,
    BAD_URL,
    DOWNLOAD_FAILED,
    IMAGE_UNREADABLE,
    CANNOT_COMPRESS,
    SAVE_FAILED
}

/** 一次工具调用的结果：只带数据，不带界面文案。 */
sealed interface StickerToolOutcome {
    data class Sent(val sticker: StickerRecord) : StickerToolOutcome
    data class Made(val sticker: StickerRecord, val fromWeb: Boolean) : StickerToolOutcome
    data class Saved(val sticker: StickerRecord) : StickerToolOutcome
    data class Failed(val reason: StickerToolFailure) : StickerToolOutcome
}

/**
 * 工具需要的最小能力面。
 *
 * 生产实现（`StickerRepository`）打 Room + 私有目录 + Android 解码器；
 * 单测实现只需要内存里的几条记录和几张假图。
 */
interface StickerWorkspace {
    suspend fun findSticker(stickerId: String): StickerRecord?

    /** 下载一张公开图片（**生产实现必须先过 `WebUrlPolicy`，且 ≤8 MiB**）。 */
    suspend fun downloadImage(url: String): ByteArray?

    /** 任意图片字节 → 画面序列（Android 侧走 ImageDecoder，支持 GIF/WebP/APNG 的帧）。 */
    suspend fun decodeImage(bytes: ByteArray): StickerSource?

    /** 已有表情 → 画面序列（读本地文件或服务端媒体）。 */
    suspend fun decodeSticker(sticker: StickerRecord): StickerSource?

    /** 内置空白底（纯色 + 文字用的 meme 底）。 */
    suspend fun blankSource(): StickerSource

    suspend fun renderText(source: StickerSource, text: String, position: StickerPosition): StickerSource

    suspend fun renderOverlay(source: StickerSource, overlay: StickerSource, position: StickerPosition): StickerSource

    /** 走同一条压缩管线落盘并落库；失败返回 null。 */
    suspend fun saveSticker(
        source: StickerSource,
        kind: StickerSourceKind,
        createdBy: StickerCreator,
        personaId: String?
    ): StickerRecord?

    /** 把某条消息里的表情加入「我的表情」（同文件、`createdBy` 不变）。 */
    suspend fun saveStickerFromMessage(messageId: String, personaId: String?): StickerRecord?
}

/**
 * 模型工具 `sticker` 的执行逻辑。
 *
 * 三条动作的边界写在 `ToolCatalog` 的描述里；这里只做「把参数变成一次真实的动作」，
 * 任何一步失败都返回 [StickerToolOutcome.Failed]，绝不让异常冒到聊天主流程
 * （工具失败不该让这一轮回复整个挂掉）。
 */
object StickerTool {

    suspend fun send(workspace: StickerWorkspace, stickerId: String?): StickerToolOutcome {
        val id = stickerId?.trim().orEmpty()
        if (id.isEmpty()) return StickerToolOutcome.Failed(StickerToolFailure.STICKER_NOT_FOUND)
        val sticker = workspace.findSticker(id)
            ?: return StickerToolOutcome.Failed(StickerToolFailure.STICKER_NOT_FOUND)
        return StickerToolOutcome.Sent(sticker)
    }

    suspend fun make(
        workspace: StickerWorkspace,
        request: StickerMakeRequest,
        personaId: String?
    ): StickerToolOutcome {
        if (!request.hasAnyMaterial) return StickerToolOutcome.Failed(StickerToolFailure.EMPTY_MAKE)

        // 1. 底图：已有表情 > 下载 > 叠图 > 内置空白底。
        var fromWeb = false
        var source: StickerSource? = null
        val baseId = request.baseStickerId?.trim().orEmpty()
        if (baseId.isNotEmpty()) {
            val base = workspace.findSticker(baseId)
                ?: return StickerToolOutcome.Failed(StickerToolFailure.STICKER_NOT_FOUND)
            source = workspace.decodeSticker(base)
                ?: return StickerToolOutcome.Failed(StickerToolFailure.IMAGE_UNREADABLE)
        }
        val url = request.url?.trim().orEmpty()
        if (source == null && url.isNotEmpty()) {
            val bytes = workspace.downloadImage(url)
                ?: return StickerToolOutcome.Failed(StickerToolFailure.DOWNLOAD_FAILED)
            source = workspace.decodeImage(bytes)
                ?: return StickerToolOutcome.Failed(StickerToolFailure.IMAGE_UNREADABLE)
            fromWeb = true
        }
        val overlayId = request.overlayStickerId?.trim().orEmpty()
        var overlay: StickerSource? = null
        if (overlayId.isNotEmpty()) {
            val record = workspace.findSticker(overlayId)
                ?: return StickerToolOutcome.Failed(StickerToolFailure.STICKER_NOT_FOUND)
            overlay = workspace.decodeSticker(record)
                ?: return StickerToolOutcome.Failed(StickerToolFailure.IMAGE_UNREADABLE)
            if (source == null) source = overlay
        }
        if (source == null) source = workspace.blankSource()

        // 2. 叠字 / 叠图（顺序固定：先图后字，字永远在最上面）。
        val text = request.text?.trim().orEmpty()
        val hasOverlay = overlay != null && overlay !== source
        if (hasOverlay) {
            source = workspace.renderOverlay(source!!, overlay!!, request.position)
        }
        if (text.isNotEmpty()) {
            source = workspace.renderText(source!!, text, request.position)
        }

        // 3. 同一条压缩管线落盘落库。
        val pureDownload = fromWeb && baseId.isEmpty() && text.isEmpty() && !hasOverlay
        val kind = if (pureDownload) StickerSourceKind.WEB else StickerSourceKind.MODEL_MADE
        val saved = workspace.saveSticker(source!!, kind, StickerCreator.MODEL, personaId)
            ?: return StickerToolOutcome.Failed(StickerToolFailure.CANNOT_COMPRESS)
        return StickerToolOutcome.Made(saved, fromWeb = pureDownload)
    }

    suspend fun save(
        workspace: StickerWorkspace,
        fromMessageId: String?,
        personaId: String?
    ): StickerToolOutcome {
        val messageId = fromMessageId?.trim().orEmpty()
        if (messageId.isEmpty()) return StickerToolOutcome.Failed(StickerToolFailure.MESSAGE_NOT_FOUND)
        val saved = workspace.saveStickerFromMessage(messageId, personaId)
            ?: return StickerToolOutcome.Failed(StickerToolFailure.NO_STICKER_IN_MESSAGE)
        return StickerToolOutcome.Saved(saved)
    }

    // ------------------------------------------------------------ 回灌给模型的文本

    /** 回灌摘要（一段话里的「摘要：」那一行）。 */
    fun summary(outcome: StickerToolOutcome): String = when (outcome) {
        is StickerToolOutcome.Sent -> "已发送表情"
        is StickerToolOutcome.Made -> "做好了表情（id=${outcome.sticker.id}）"
        is StickerToolOutcome.Saved -> "已收藏表情"
        is StickerToolOutcome.Failed -> failureText(outcome.reason)
    }

    /** 回灌正文：告诉他接下来该怎么说话（工具已经做完了，不要向对方解释工具细节）。 */
    fun contentForModel(outcome: StickerToolOutcome): String = when (outcome) {
        is StickerToolOutcome.Sent ->
            "表情已经作为一条消息发出去了（就一条，别再说「我发了」）。接着用你自己的语气把话说完。"

        is StickerToolOutcome.Made ->
            "做好了一张表情（id=${outcome.sticker.id}，${describe(outcome.sticker)}）。" +
                "想发就再调用一次 sticker 的 send；不想发就留着，不用向对方解释你做了什么。"

        is StickerToolOutcome.Saved ->
            "对方的这张表情已经收进「我的表情」里了。回一句自然的话就行，不用汇报收藏这件事。"

        is StickerToolOutcome.Failed ->
            "这次没做成：${failureText(outcome.reason)}。用你已经知道的东西继续，别向对方解释工具细节。"
    }

    /** 失败原因给模型看的一句话。 */
    fun failureText(reason: StickerToolFailure): String = when (reason) {
        StickerToolFailure.MISSING_ACTION -> "没有说要做什么（send / make / save）"
        StickerToolFailure.UNKNOWN_ACTION -> "不认识这个动作，只能用 send / make / save"
        StickerToolFailure.STICKER_NOT_FOUND -> "找不到这张表情"
        StickerToolFailure.MESSAGE_NOT_FOUND -> "找不到那条消息"
        StickerToolFailure.NO_STICKER_IN_MESSAGE -> "那条消息里没有表情"
        StickerToolFailure.EMPTY_MAKE -> "做表情至少要给 url、baseStickerId、overlayStickerId 或 text 里的一个"
        StickerToolFailure.BAD_URL -> "这个链接不能下载（只支持公开的 http/https 图片）"
        StickerToolFailure.DOWNLOAD_FAILED -> "图片没下载下来（可能是链接失效或文件太大）"
        StickerToolFailure.IMAGE_UNREADABLE -> "这张图读不出来"
        StickerToolFailure.CANNOT_COMPRESS -> "这张图压不进表情包的大小上限"
        StickerToolFailure.SAVE_FAILED -> "保存失败"
    }

    private fun describe(sticker: StickerRecord): String =
        if (sticker.isAnimated) "${sticker.width}×${sticker.height}，${sticker.frames} 帧" else "静态图"
}

/**
 * 系统提示词里的「我的表情」块（`spec/stickers/README.md` 的工具 `send` 需要 id）。
 *
 * 只给 **id + 动图/静态 + 谁做的**，不给文件路径：模型不需要本机路径（那既没用，
 * 又把沙盒结构写进了提示词），而「他做的 / 我收的」这个区别会影响它怎么开口
 * （「我刚做了个表情」比「这张表情不错」自然得多）。
 */
object StickerPromptBlock {

    /** 最多列这么多条：再多就只是在挤占上下文，模型挑表情也不会翻到第 21 张。 */
    const val LIMIT = 20

    fun build(records: List<StickerRecord>): String {
        val visible = records.take(LIMIT)
        if (visible.isEmpty()) return ""
        return buildString {
            appendLine("# 我的表情")
            appendLine("这些是你可以直接发（sticker 的 send）的表情，id 只能用这里出现过的，不要自己编。")
            visible.forEach { sticker ->
                append("- ${sticker.id}：")
                append(if (sticker.isAnimated) "动图（${sticker.frames} 帧）" else "静态图")
                append(if (sticker.createdBy == StickerCreator.MODEL.name) "·你做的" else "·收藏的")
                appendLine()
            }
            append("想现做一张就用 make（可以叠字或叠图）。")
        }.trimEnd()
    }
}
