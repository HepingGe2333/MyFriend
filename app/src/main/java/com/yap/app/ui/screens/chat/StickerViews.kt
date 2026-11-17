package com.yap.app.ui.screens.chat

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yap.app.R
import com.yap.app.data.local.StickerEntity
import com.yap.app.domain.StickerSource
import com.yap.app.ui.theme.LocalAccentColor
import com.yap.app.ui.theme.LocalAnimationsEnabled
import kotlinx.coroutines.delay

/**
 * 聊天里的表情：**自己播帧**的动画 GIF。
 *
 * 为什么不用 Coil 的 `AsyncImage`：Coil 的动图支持在 `coil-gif` 这个额外依赖里，
 * 本轮不允许新增依赖；而我们已经能把 GIF/APNG 解成帧序列（`StickerGif` / `Apng`），
 * 所以这里直接按帧的 `delayMs` 播放。好处是「服务端下载来的表情」「本机文件」走同一条路，
 * 坏处是解码出来的帧要占内存（见报告里的内存风险）。
 *
 * 关掉动画（设置里的「动画效果」）时只画第一帧：低配机器/省电模式下不该有东西一直重绘。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerImage(
    source: StickerSource?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxSize: Dp = STICKER_BUBBLE_SIZE,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val animationsEnabled = LocalAnimationsEnabled.current
    val aspect = remember(source) {
        if (source == null || source.width <= 0 || source.height <= 0) 1f
        else source.width.toFloat() / source.height.toFloat()
    }
    val clickModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .heightIn(max = maxSize)
            .aspectRatio(aspect, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(12.dp))
            .then(clickModifier),
        contentAlignment = Alignment.Center
    ) {
        val frame = rememberAnimatedFrame(source, animationsEnabled)
        val bitmap = remember(frame, source) { frame?.let { source?.let { s -> it.toImageBitmap(s.width, s.height) } } }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            // 还没解出来 / 解不出来：一块很轻的占位，不显示错误文案（气泡里留白更难受，但也不该吓人）。
            Box(
                Modifier
                    .size(maxSize.coerceAtMost(120.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            )
        }
    }
}

/**
 * 取当前该显示的那一帧。
 *
 * 用 `remember` + `LaunchedEffect` 自己排时间：GIF 的帧时长本来就是毫秒，
 * `delay(frame.delayMs)` 比「按固定 16ms 重绘再算时间」更省电，也不会在后台白跑。
 */
@Composable
private fun rememberAnimatedFrame(source: StickerSource?, animationsEnabled: Boolean): com.yap.app.domain.StickerFrame? {
    if (source == null || source.frames.isEmpty()) return null
    if (!animationsEnabled || source.frames.size == 1) return source.frames.first()
    var index by remember(source) { mutableIntStateOf(0) }
    LaunchedEffect(source) {
        var cursor = 0
        while (true) {
            val frames = source.frames
            delay(frames[cursor % frames.size].delayMs.toLong().coerceAtLeast(20L))
            cursor = (cursor + 1) % frames.size
            index = cursor
        }
    }
    return source.frames[index % source.frames.size]
}

private fun com.yap.app.domain.StickerFrame.toImageBitmap(width: Int, height: Int): ImageBitmap? =
    runCatching { Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap() }.getOrNull()

/** 历史面板里的一格缩略图：只画第一帧（列表里不需要动，动了反而更耗电）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerThumbnail(
    thumbnail: ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 0.7.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(14.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp)
            )
        }
    }
}

/**
 * 输入框上面的「我的表情」面板：最近 30 个 + 全部，点选即发送。
 *
 * 面板本身不持有仓库：缩略图由调用方（`ChatScreen`）预先解好交进来，
 * 这样「打开面板解 30 张首帧」这件事只在面板可见时发生一次。
 */
@Composable
fun StickerPanel(
    stickers: List<StickerEntity>,
    showAll: Boolean,
    thumbnails: Map<String, ImageBitmap?>,
    onToggleShowAll: (Boolean) -> Unit,
    onPick: (StickerEntity) -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalAccentColor.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 292.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.chat_sticker_panel_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(10.dp))
            TextButton(onClick = { onToggleShowAll(false) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    stringResource(R.string.chat_sticker_panel_recent),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (!showAll) accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onToggleShowAll(true) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    stringResource(R.string.chat_sticker_panel_all),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (showAll) accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onImport, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(
                    Icons.Outlined.AddPhotoAlternate,
                    contentDescription = stringResource(R.string.chat_sticker_panel_import),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    stringResource(R.string.chat_cancel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (stickers.isEmpty()) {
            Text(
                stringResource(R.string.chat_sticker_panel_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 18.dp)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 6.dp)
            ) {
                items(stickers, key = { it.id }) { sticker ->
                    StickerThumbnail(
                        thumbnail = thumbnails[sticker.id],
                        onClick = { onPick(sticker) }
                    )
                }
            }
        }
    }
}

/** 气泡里表情的显示尺寸（规范：表情消息在聊天里以 240 px 展示）。 */
val STICKER_BUBBLE_SIZE: Dp = 240.dp

/**
 * 面板缩略图的状态容器：uri → 第一帧。
 *
 * 放在这里而不是 ViewModel：它是纯粹的「画」的状态（解码失败就是 null），
 * 不该让 ViewModel 关心某张图有没有解出来。
 */
@Composable
fun rememberStickerThumbnails(
    stickers: List<StickerEntity>,
    visible: Boolean,
    load: suspend (String) -> StickerSource?
): Map<String, ImageBitmap?> {
    var thumbnails by remember { mutableStateOf<Map<String, ImageBitmap?>>(emptyMap()) }
    LaunchedEffect(stickers, visible) {
        if (!visible) return@LaunchedEffect
        val result = mutableMapOf<String, ImageBitmap?>()
        stickers.forEach { sticker ->
            val existing = thumbnails[sticker.id]
            if (existing != null) {
                result[sticker.id] = existing
                return@forEach
            }
            val source = load(sticker.file) ?: return@forEach
            val frame = source.frames.firstOrNull() ?: return@forEach
            result[sticker.id] = runCatching {
                Bitmap.createBitmap(frame.argb, source.width, source.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
        }
        thumbnails = result
    }
    return thumbnails
}
