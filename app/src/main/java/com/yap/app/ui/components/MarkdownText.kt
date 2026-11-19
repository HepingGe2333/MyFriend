package com.yap.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yap.app.R
import com.yap.app.domain.LinkDetector
import com.yap.app.domain.LinkKind
import com.yap.app.domain.LinkSpan
import com.yap.app.ui.theme.LocalAccentColor
import com.yap.app.ui.theme.isLight
import com.yap.app.util.Latex
import kotlinx.coroutines.delay

sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Bullets(val items: List<String>) : MarkdownBlock
    data class Ordered(val items: List<String>) : MarkdownBlock
    data object Divider : MarkdownBlock
    data class MathBlock(val latex: String) : MarkdownBlock
}

object MarkdownParser {
    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRegex = Regex("^\\s*[-*+]\\s+(.*)$")
    private val orderedRegex = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")

    fun parse(source: String): List<MarkdownBlock> {
        val lines = source.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> index++
                trimmed.startsWith("```") -> {
                    val language = trimmed.removePrefix("```").trim()
                    val code = mutableListOf<String>()
                    index++
                    while (index < lines.size && !lines[index].trim().startsWith("```")) {
                        code += lines[index]
                        index++
                    }
                    if (index < lines.size) index++
                    blocks += MarkdownBlock.CodeBlock(language, code.joinToString("\n"))
                }
                trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                    blocks += MarkdownBlock.Divider
                    index++
                }
                headingRegex.matches(line) -> {
                    val match = headingRegex.matchEntire(line.trim())!!
                    blocks += MarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2])
                    index++
                }
                trimmed.startsWith("$$") -> {
                    val math = StringBuilder()
                    var rest = trimmed.removePrefix("$$")
                    if (rest.contains("$$")) {
                        math.append(rest.substringBefore("$$"))
                        index++
                    } else {
                        if (rest.isNotBlank()) math.append(rest)
                        index++
                        while (index < lines.size) {
                            val current = lines[index]
                            if (current.trim().endsWith("$$")) {
                                math.append('\n').append(current.trim().removeSuffix("$$"))
                                index++
                                break
                            }
                            math.append('\n').append(current)
                            index++
                        }
                    }
                    blocks += MarkdownBlock.MathBlock(math.toString().trim())
                }
                trimmed.startsWith(">") -> {
                    val quote = mutableListOf<String>()
                    while (index < lines.size && lines[index].trim().startsWith(">")) {
                        quote += lines[index].trim().removePrefix(">").removePrefix(" ").trimStart()
                        index++
                    }
                    blocks += MarkdownBlock.Quote(quote.joinToString("\n"))
                }
                bulletRegex.matches(line) -> {
                    val items = mutableListOf<String>()
                    while (index < lines.size && bulletRegex.matches(lines[index])) {
                        items += bulletRegex.matchEntire(lines[index])!!.groupValues[1]
                        index++
                    }
                    blocks += MarkdownBlock.Bullets(items)
                }
                orderedRegex.matches(line) -> {
                    val items = mutableListOf<String>()
                    while (index < lines.size && orderedRegex.matches(lines[index])) {
                        items += orderedRegex.matchEntire(lines[index])!!.groupValues[2]
                        index++
                    }
                    blocks += MarkdownBlock.Ordered(items)
                }
                else -> {
                    val paragraph = mutableListOf<String>()
                    while (index < lines.size) {
                        val current = lines[index]
                        val currentTrimmed = current.trim()
                        if (currentTrimmed.isEmpty() ||
                            currentTrimmed.startsWith("```") ||
                            currentTrimmed.startsWith("$$") ||
                            currentTrimmed.startsWith(">") ||
                            headingRegex.matches(current) ||
                            bulletRegex.matches(current) ||
                            orderedRegex.matches(current) ||
                            currentTrimmed == "---" || currentTrimmed == "***" || currentTrimmed == "___"
                        ) break
                        paragraph += current
                        index++
                    }
                    if (paragraph.isNotEmpty()) blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
                }
            }
        }
        return blocks
    }
}

/**
 * 链接的蓝：像 Telegram 那样，一眼能看出「这里可以点开看一眼」。
 *
 * 出向气泡（accent 底色）上用更浅的蓝，保证在彩色背景上还能读。
 */
private val LinkBlueOnLight = Color(0xFF2C7DBE)
private val LinkBlueOnDark = Color(0xFF7FB8E8)
private val LinkBlueOnAccent = Color(0xFFDDEEFF)

@Composable
fun markdownLinkColor(isOutgoing: Boolean = false): Color {
    val dark = !MaterialTheme.colorScheme.background.isLight()
    return when {
        isOutgoing -> LinkBlueOnAccent
        dark -> LinkBlueOnDark
        else -> LinkBlueOnLight
    }
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    isOutgoing: Boolean = false,
    compact: Boolean = false,
    /** 点击链接的回调；为 null 时链接只上色、不响应（保持旧行为）。 */
    onOpenLink: ((String) -> Unit)? = null,
    /**
     * 要不要包一层 SelectionContainer（长按选字、拖动选一段）。
     *
     * 聊天气泡传 false：SelectionContainer 会在 Initial 阶段把按下事件吃掉，
     * 气泡上的「长按叫出消息菜单」就再也收不到按在文字上的那一下，
     * 而且长按会同时弹出选字工具条和他的消息菜单，两套菜单叠在一起。
     * 聊天里长按要的是菜单（复制整条、编辑重发、删除），
     * 局部选字留给代码块右上角的复制按钮。
     * **传 true 时 [onLongPress] 不生效**：长按归 SelectionContainer（选字）。
     */
    selectable: Boolean = true,
    /**
     * 长按正文（叫出消息菜单）。
     *
     * 带链接的 AnnotatedString 会让 Text 自己在内部接一层手势判定，它在 Initial 阶段
     * 就把按下事件吃掉了：外面的气泡 combinedClickable 收不到「按在文字上」的那一下，
     * 只有按在气泡的空白处才叫得出菜单。所以文本区域自己补一条长按。
     */
    onLongPress: (() -> Unit)? = null
) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val codeBackground = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    }
    val accent = LocalAccentColor.current
    val linkColor = markdownLinkColor(isOutgoing)
    val body: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Paragraph -> {
                        InlineText(
                            raw = block.text,
                            color = color,
                            codeBackground = codeBackground,
                            isOutgoing = isOutgoing,
                            linkColor = linkColor,
                            onOpenLink = onOpenLink,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    is MarkdownBlock.Heading -> {
                        val size = when (block.level) {
                            1 -> 22.sp
                            2 -> 20.sp
                            3 -> 18.sp
                            else -> 16.sp
                        }
                        InlineText(
                            raw = block.text,
                            color = color,
                            codeBackground = codeBackground,
                            isOutgoing = isOutgoing,
                            linkColor = linkColor,
                            onOpenLink = onOpenLink,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = size,
                                lineHeight = (size.value + 7).sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.2).sp
                            )
                        )
                    }
                    is MarkdownBlock.CodeBlock -> {
                        CodeBlockView(block, color, codeBackground)
                    }
                    is MarkdownBlock.Quote -> {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isOutgoing) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(accent.copy(alpha = 0.7f))
                            )
                            InlineText(
                                raw = block.text,
                                color = color.copy(alpha = 0.88f),
                                codeBackground = codeBackground,
                                isOutgoing = isOutgoing,
                                linkColor = linkColor,
                                onOpenLink = onOpenLink,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic
                            )
                        }
                    }
                    is MarkdownBlock.Bullets -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            block.items.forEach { item ->
                                Row {
                                    Text("•", color = color.copy(alpha = 0.7f), modifier = Modifier.padding(top = 1.dp, end = 8.dp))
                                    InlineText(
                                        raw = item,
                                        color = color,
                                        codeBackground = codeBackground,
                                        isOutgoing = isOutgoing,
                                        linkColor = linkColor,
                                        onOpenLink = onOpenLink,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }
                    }
                    is MarkdownBlock.Ordered -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            block.items.forEachIndexed { itemIndex, item ->
                                Row {
                                    Text(
                                        "${itemIndex + 1}.",
                                        color = color.copy(alpha = 0.65f),
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(top = 4.dp, end = 8.dp)
                                    )
                                    InlineText(
                                        raw = item,
                                        color = color,
                                        codeBackground = codeBackground,
                                        isOutgoing = isOutgoing,
                                        linkColor = linkColor,
                                        onOpenLink = onOpenLink,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }
                    }
                    MarkdownBlock.Divider -> {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .height(1.dp)
                                .background(color.copy(alpha = 0.16f))
                        )
                    }
                    is MarkdownBlock.MathBlock -> {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isOutgoing) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = Latex.toReadable(block.latex),
                                color = color,
                                fontFamily = FontFamily.Serif,
                                fontStyle = FontStyle.Italic,
                                fontSize = 17.sp,
                                lineHeight = 25.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
    // 两层长按只能留一层：
    // - 选字模式（SelectionContainer）自己就吃长按，叠一层会同时弹出选字工具条和消息菜单；
    // - 正文里没有链接时，Text 不接手势，气泡的 combinedClickable 会正常触发，这里再补一层就双响；
    // - 只有「带链接的正文」需要这里补：Text 内部手势消费了按下事件，气泡的 longClick 收不到。
    val hasLinks = remember(text, onOpenLink != null) {
        onOpenLink != null && LinkDetector.findAll(text).isNotEmpty()
    }
    val wrapped = if (selectable || !hasLinks) {
        modifier
    } else {
        modifier.then(longPressModifier(onLongPress))
    }
    if (selectable) {
        SelectionContainer(modifier = wrapped) { body() }
    } else {
        Box(wrapped) { body() }
    }
}

/**
 * 一段正文文字，链接照常上色，点击交给 [onOpenLink]。
 *
 * 为什么不用 Compose 自带的链接点击：只要 AnnotatedString 里带 LinkAnnotation，
 * Text 就会在内部接一层手势判定，它会在 Initial 阶段消费按下事件，并把「按住再松手」
 * 一律当成一次点击——长按叫出消息菜单时浏览器会跟着弹出来，外面的长按也收不到手势。
 * 所以这里只保留 LinkAnnotation 的样式与语义，点击自己判定：
 * 只有抬手真的落在链接范围里才回调并消费事件，长按、拖动、点空白一律放行。
 */
@Composable
fun InlineText(
    raw: String,
    color: Color,
    codeBackground: Color,
    isOutgoing: Boolean,
    linkColor: Color,
    onOpenLink: ((String) -> Unit)?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontStyle: FontStyle? = null,
    /** 长按这一行（叫出消息菜单）；为 null 时不接管。 */
    onLongPress: (() -> Unit)? = null
) {
    // 只按「有没有回调」做缓存键：外面每次重组都可能给出一个新的 lambda 实例，
    // 拿它当 key 会让这段文字每帧重建一遍 AnnotatedString（词法切分 + span 全跑）。
    val canOpenLink = onOpenLink != null
    val annotated = remember(raw, color, codeBackground, linkColor, canOpenLink) {
        buildInline(raw, color, codeBackground, linkColor, onOpenLink)
    }
    // 布局结果只在手势里读，不需要参与重组，所以用一个普通容器持有而不是 mutableStateOf。
    val layoutHolder = remember(annotated) { arrayOfNulls<TextLayoutResult>(1) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val openLink by rememberUpdatedState(onOpenLink)
    val longPress by rememberUpdatedState(onLongPress)
    val tapModifier = if (onOpenLink != null && annotated.hasLinkAnnotations(0, annotated.length)) {
        Modifier.pointerInput(annotated) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // 外面套着 SelectionContainer，它在 Initial 阶段就把按下事件吃掉了，
                // 所以这里不看消费状态，只看「同一根手指按下又抬起、中途没被拖走」。
                val release = awaitUpIgnoringConsumption(down, viewConfiguration.touchSlop)
                    ?: return@awaitEachGesture
                val up = release.change
                val isLongPress = release.heldMillis > viewConfiguration.longPressTimeoutMillis
                val target = if (isLongPress) {
                    null
                } else {
                    linkAt(layoutHolder[0], up.position, annotated, slopPx = LINK_TAP_SLOP_DP * density)
                }
                // 长按是「叫出消息菜单」。抬手照样吃掉，否则 Text 自带的链接点击
                // 会把这次长按也当成一次点击，菜单刚弹出来浏览器就盖上去了。
                if (target != null || isLongPress) up.consume()
                if (target == null) return@awaitEachGesture
                openLink?.invoke(target)
            }
        }
    } else {
        Modifier
    }
    Text(
        text = annotated,
        modifier = modifier.then(tapModifier).then(longPressModifier(longPress)),
        style = style,
        color = color,
        fontStyle = fontStyle,
        onTextLayout = { layoutHolder[0] = it }
    )
}

/**
 * 长按回调的 Modifier。
 *
 * 用 `awaitFirstDown(requireUnconsumed = false)`：带链接的文字会让 Text 在内部先消费按下事件，
 * 严格等「未消费的按下」的检测器在这里永远收不到手势。
 * 超过阈值就回调，不消费事件——外层的消息菜单、滚动照旧。
 */
@Composable
private fun longPressModifier(onLongPress: (() -> Unit)?): Modifier {
    val latest by rememberUpdatedState(onLongPress)
    return if (onLongPress == null) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (awaitLongPressOrCancellation(down.id) != null) latest?.invoke()
            }
        }
    }
}

/** 一次抬手：抬起时的指针状态，以及从按下到抬起的时长。 */
/** 点到链接时容许多大的边缘误差：手指本来就比字宽，8dp 是「还算是点它」的尺度。 */
private const val LINK_TAP_SLOP_DP = 8f

private class PointerRelease(val change: PointerInputChange, val heldMillis: Long)

/**
 * 等这根手指抬起。和 waitForUpOrCancellation 的区别是不理会消费状态
 * （外层的 SelectionContainer 会先把按下事件消费掉），中途变成拖动就放弃。
 */
private suspend fun AwaitPointerEventScope.awaitUpIgnoringConsumption(
    down: PointerInputChange,
    touchSlop: Float
): PointerRelease? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed) {
            return PointerRelease(change, change.uptimeMillis - down.uptimeMillis)
        }
        if ((change.position - down.position).getDistance() > touchSlop) return null
    }
}

/** 把点击位置换算成链接地址；没压到链接就返回 null。 */
private fun linkAt(
    layout: TextLayoutResult?,
    position: Offset,
    annotated: AnnotatedString,
    slopPx: Float = 8f
): String? {
    val result = layout ?: return null
    if (annotated.isEmpty()) return null
    val offset = result.getOffsetForPosition(position).coerceIn(0, annotated.length - 1)
    val annotation = annotated.getLinkAnnotations(offset, offset + 1).firstOrNull()?.item
    val tag = (annotation as? LinkAnnotation.Clickable)?.tag as? String ?: return null
    // getOffsetForPosition 会把任意一点吸附到最近的字符：点在行的留白、或两行之间的空隙里，
    // 也可能算出「落在链接上」。真正算点击之前，先确认手指确实压在那个字的框里（允许一点容差）。
    val box = runCatching { result.getBoundingBox(offset) }.getOrNull() ?: return tag
    val inside = position.x >= box.left - slopPx && position.x <= box.right + slopPx &&
        position.y >= box.top - slopPx && position.y <= box.bottom + slopPx
    return if (inside) tag else null
}

@Composable
private fun CodeBlockView(block: MarkdownBlock.CodeBlock, color: Color, background: Color) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = block.language.ifBlank { "code" }.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(block.code))
                    copied = true
                },
                modifier = Modifier.size(34.dp)
            ) {
                AnimatedContent(
                    targetState = copied,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "copyCode"
                ) { isCopied ->
                    Icon(
                        imageVector = if (isCopied) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(
                            if (isCopied) R.string.common_code_copied else R.string.common_code_copy
                        ),
                        tint = color.copy(alpha = 0.6f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 11.dp)
                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
        ) {
            Text(
                text = block.code,
                color = color.copy(alpha = 0.92f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
        }
    }
}

fun buildInline(
    raw: String,
    color: Color,
    codeBackground: Color,
    linkColor: Color = color,
    onOpenLink: ((String) -> Unit)? = null
): AnnotatedString = buildAnnotatedString {
    appendInline(raw, color, codeBackground, linkColor, onOpenLink)
}

private fun linkSpanStyle(linkColor: Color): SpanStyle = SpanStyle(
    color = linkColor,
    textDecoration = TextDecoration.Underline,
    fontWeight = FontWeight.Medium
)

private fun AnnotatedString.Builder.appendInline(
    raw: String,
    color: Color,
    codeBackground: Color,
    linkColor: Color,
    onOpenLink: ((String) -> Unit)?,
    inherited: SpanStyle = SpanStyle()
) {
    var index = 0
    // 先按原文坐标把链接找出来，逐字扫描时只查当前位置有没有链接，
    // 这样流式输出截断到一半的地址也不会把后面的文字吞进去。
    val inlineLinks = HashMap<Int, LinkSpan>()
    val markdownLinks = HashMap<Int, LinkSpan>()
    LinkDetector.findAll(raw).forEach { span ->
        if (span.kind == LinkKind.MARKDOWN) markdownLinks[span.start] = span
        else inlineLinks[span.start] = span
    }
    val linkStyle = linkSpanStyle(linkColor)

    fun appendStyled(text: String, style: SpanStyle) {
        withStyle(inherited.merge(style)) { append(text) }
    }

    fun appendLink(text: String, url: String) {
        val start = length
        withStyle(inherited.merge(linkStyle)) { append(text) }
        if (onOpenLink == null) return
        // 只登记 LinkAnnotation，不挂 linkInteractionListener：
        // 外观交给 SpanStyle，点击交给外层 InlineText 自己判定。
        // 官方那条自带点击在消息气泡里（外面套着 SelectionContainer）会在按下的瞬间
        // 就触发，连长按叫出消息菜单都会顺带打开一次浏览器，所以不用它。
        addLink(
            LinkAnnotation.Clickable(
                tag = url,
                styles = TextLinkStyles(style = linkStyle),
                linkInteractionListener = LinkInteractionListener { /* 触摸交给 InlineText 判定 */ }
            ),
            start,
            length
        )
    }

    while (index < raw.length) {
        val char = raw[index]
        if (char == '\\' && index + 1 < raw.length) {
            appendStyled(raw[index + 1].toString(), SpanStyle())
            index += 2
            continue
        }
        val inlineLink = inlineLinks[index]
        if (inlineLink != null) {
            appendLink(raw.substring(inlineLink.start, inlineLink.end), inlineLink.url)
            index = inlineLink.end
            continue
        }
        when {
            raw.startsWith("**", index) -> {
                val end = raw.indexOf("**", index + 2)
                if (end > index + 2) {
                    appendInline(raw.substring(index + 2, end), color, codeBackground, linkColor, onOpenLink, inherited.merge(SpanStyle(fontWeight = FontWeight.Bold)))
                    index = end + 2
                } else {
                    appendStyled("*", SpanStyle())
                    index++
                }
            }
            raw.startsWith("~~", index) -> {
                val end = raw.indexOf("~~", index + 2)
                if (end > index + 2) {
                    appendInline(raw.substring(index + 2, end), color, codeBackground, linkColor, onOpenLink, inherited.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)))
                    index = end + 2
                } else {
                    appendStyled("~", SpanStyle())
                    index++
                }
            }
            raw.startsWith("`", index) -> {
                val end = raw.indexOf('`', index + 1)
                if (end > index + 1) {
                    appendStyled(
                        raw.substring(index + 1, end),
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackground,
                            fontSize = 14.sp
                        )
                    )
                    index = end + 1
                } else {
                    appendStyled("`", SpanStyle())
                    index++
                }
            }
            raw.startsWith("$", index) -> {
                val end = raw.indexOf('$', index + 1)
                if (end > index + 1 && !raw.substring(index + 1, end).contains('\n')) {
                    appendStyled(
                        Latex.toReadable(raw.substring(index + 1, end)),
                        SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                    )
                    index = end + 1
                } else {
                    appendStyled("$", SpanStyle())
                    index++
                }
            }
            raw.startsWith("[", index) -> {
                val labelEnd = raw.indexOf("](", index + 1)
                val urlEnd = if (labelEnd > 0) raw.indexOf(')', labelEnd + 2) else -1
                if (labelEnd > index + 1 && urlEnd > labelEnd + 2) {
                    val label = raw.substring(index + 1, labelEnd)
                    val target = markdownLinks[index + 1]
                    if (target != null) {
                        appendLink(label, target.url)
                    } else {
                        // 目标地址不可用（比如 javascript:）：按普通文字渲染，不点亮
                        appendStyled(label, SpanStyle(textDecoration = TextDecoration.Underline, color = color))
                    }
                    index = urlEnd + 1
                } else {
                    appendStyled("[", SpanStyle())
                    index++
                }
            }
            char == '*' || char == '_' -> {
                val marker = char.toString()
                val end = raw.indexOf(marker, index + 1)
                if (end > index + 1) {
                    appendInline(raw.substring(index + 1, end), color, codeBackground, linkColor, onOpenLink, inherited.merge(SpanStyle(fontStyle = FontStyle.Italic)))
                    index = end + 1
                } else {
                    appendStyled(marker, SpanStyle())
                    index++
                }
            }
            else -> {
                appendStyled(char.toString(), SpanStyle())
                index++
            }
        }
    }
}
