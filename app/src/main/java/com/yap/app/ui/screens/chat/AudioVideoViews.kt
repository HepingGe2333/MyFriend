package com.yap.app.ui.screens.chat

import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.yap.app.R
import com.yap.app.domain.AudioVideoPolicy
import com.yap.app.domain.MessageAttachment
import com.yap.app.domain.Playback
import com.yap.app.domain.PlaybackStatus
import com.yap.app.util.VideoFramesExtractor
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 语音气泡：时长 + 播放/暂停（`MediaPlayer`）。
 *
 * 状态机在 [Playback]（纯逻辑、有单测）；这里只负责「拿到本地文件 → 播放 → 把位置画出来」。
 * 服务端媒体先由 `AudioVideoRepository.localFile` 下载到缓存，播放器永远只碰本地文件
 * （`MediaPlayer` 不能带 `Authorization`，令牌也不该进 URL）。
 */
@Composable
fun VoiceBubble(
    attachment: MessageAttachment,
    tint: Color,
    accent: Color,
    resolveFile: suspend (MessageAttachment) -> File?,
    modifier: Modifier = Modifier
) {
    var status by remember(attachment.uri) { mutableStateOf(PlaybackStatus.IDLE) }
    var positionMs by remember(attachment.uri) { mutableLongStateOf(0L) }
    val playerRef = remember(attachment.uri) { mutableStateOf<MediaPlayer?>(null) }
    val scope = rememberCoroutineScope()
    val durationMs = attachment.durationMs ?: 0L
    // 出向气泡的底色是强调色（contentColor 通常是白色）：图标要反过来取黑，否则白底白字看不见。
    val iconTint = if (accent.luminance() > 0.6f) Color.Black else Color.White
    val label = Playback.clockLabel(
        positionMs = positionMs,
        durationMs = if (durationMs > 0L) durationMs else positionMs,
        status = status
    )

    DisposableEffect(attachment.uri) {
        onDispose {
            runCatching { playerRef.value?.release() }
            playerRef.value = null
        }
    }

    // 播放中每 200ms 更新一次进度：只重组这一条气泡，不触发列表级刷新。
    LaunchedEffect(status) {
        while (status == PlaybackStatus.PLAYING) {
            positionMs = runCatching { playerRef.value?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
            delay(200)
        }
    }

    fun toggle() {
        val player = playerRef.value
        if (player != null) {
            when (Playback.toggle(status)) {
                com.yap.app.domain.PlaybackEvent.PAUSE -> {
                    runCatching { player.pause() }
                    status = PlaybackStatus.PAUSED
                }
                com.yap.app.domain.PlaybackEvent.RESUME -> {
                    runCatching {
                        if (status == PlaybackStatus.COMPLETED) player.seekTo(0)
                        player.start()
                    }
                    positionMs = 0L
                    status = PlaybackStatus.PLAYING
                }
                else -> Unit
            }
            return
        }
        if (status == PlaybackStatus.PREPARING) return
        status = PlaybackStatus.PREPARING
        scope.launch {
            val file = resolveFile(attachment)
            if (file == null) {
                status = PlaybackStatus.FAILED
                return@launch
            }
            val prepared = runCatching {
                MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnPreparedListener { media ->
                        media.start()
                        status = PlaybackStatus.PLAYING
                    }
                    setOnCompletionListener {
                        status = PlaybackStatus.COMPLETED
                        positionMs = 0L
                    }
                    setOnErrorListener { _, _, _ ->
                        status = PlaybackStatus.FAILED
                        true
                    }
                    prepareAsync()
                }
            }.getOrNull()
            if (prepared == null) {
                status = PlaybackStatus.FAILED
            } else {
                playerRef.value = prepared
            }
        }
    }

    Surface(
        modifier = modifier.widthIn(max = 248.dp),
        shape = RoundedCornerShape(16.dp),
        color = tint.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .clickableNoRipple {
                        if (status == PlaybackStatus.FAILED) {
                            status = PlaybackStatus.IDLE
                            playerRef.value?.release()
                            playerRef.value = null
                        } else {
                            toggle()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (Playback.showsPause(status)) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(
                        if (Playback.showsPause(status)) R.string.chat_voice_pause else R.string.chat_voice_play
                    ),
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(Modifier.padding(start = 10.dp, end = 4.dp)) {
                // 一条简化波形：只是「这是声音」的视觉提示，不表示真实振幅。
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val bars = remember { listOf(6, 12, 9, 16, 8, 13, 5, 11, 7) }
                    bars.forEach { height ->
                        Box(
                            Modifier
                                .width(2.dp)
                                .height(height.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.55f))
                        )
                    }
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * 视频气泡：首帧缩略图 + 播放按钮，点开全屏播放（`VideoView`）。
 *
 * 首帧在 IO 上解一次（`VideoFramesExtractor.poster`），缩到长边 512px：
 * 列表里几十个视频气泡各自解一张 4K 原图会把滚动拖垮。
 */
@Composable
fun VideoBubble(
    attachment: MessageAttachment,
    tint: Color,
    resolveFile: suspend (MessageAttachment) -> File?,
    modifier: Modifier = Modifier
) {
    var opening by remember(attachment.uri) { mutableStateOf(false) }
    var playingFile by remember(attachment.uri) { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    // 首帧在 IO 上解一次；换了一条媒体（uri 变了）才重解。
    var poster by remember(attachment.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(attachment.uri) {
        poster = withContext(Dispatchers.IO) {
            resolveFile(attachment)?.let { file ->
                runCatching { VideoFramesExtractor.poster(file)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    val duration = attachment.durationMs ?: 0L

    Box(
        modifier = modifier
            .widthIn(max = 248.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.16f))
            .clickableNoRipple {
                if (opening) return@clickableNoRipple
                opening = true
                scope.launch {
                    playingFile = resolveFile(attachment)
                    opening = false
                }
            }
    ) {
        val bitmap = poster
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.chat_video_message),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.chat_video_message),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.chat_video_play),
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        if (duration > 0L) {
            Text(
                text = AudioVideoPolicy.clockDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }

    playingFile?.let { file ->
        VideoPlayerDialog(file = file, onDismiss = { playingFile = null })
    }
}

/** 全屏视频播放：点外面/右上角关闭，播放器随之释放。 */
@Composable
private fun VideoPlayerDialog(file: File, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setVideoPath(file.absolutePath)
                        setOnCompletionListener { onDismiss() }
                        start()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickableNoRipple { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.chat_video_close),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * 草稿区里的一段语音/视频（还没发出去）：小图标 + 时长 + 右上角删除。
 *
 * 与图片缩略图同尺寸（62dp），让「一张图 + 一段语音」的草稿看起来是一排东西，
 * 而不是两种风格拼在一起。
 */
@Composable
fun PendingMediaTile(
    icon: ImageVector,
    label: String,
    accent: Color,
    contentDescription: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(62.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickableNoRipple(onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.chat_remove_image),
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/** 不带涟漪的点击：气泡里的播放按钮不需要 Material 的水波纹（会把画面顶花）。 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.clickable(
            interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
            indication = null,
            onClick = onClick
        )
    )
