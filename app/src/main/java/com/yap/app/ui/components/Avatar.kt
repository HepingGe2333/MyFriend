package com.yap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yap.app.R
import com.yap.app.ui.common.rememberMediaImageRequest
import com.yap.app.ui.theme.avatarPalette

/**
 * 他和你的头像。
 *
 * ## 内存上的两条硬规矩
 *
 * 1. **每个请求都带 `size(...)`**：按显示尺寸（dp → px）算目标边长，绝不让 Coil
 *    把原图整张解出来再交给 `ContentScale.Crop`。头像在屏幕上就是 28–92dp，
 *    一张 4000px 的相册图按原尺寸解码是 48MB，按显示尺寸只有几十 KB。
 * 2. **同一个 [AsyncImage] 只认一个 ImageLoader**：它来自
 *    `YapApplication` 实现的 `ImageLoaderFactory`，也就是 [com.yap.app.di.AppModule]
 *    里那个 `@Singleton`（内存缓存 min(可用内存×12%, 48MB)、磁盘 128MB）。
 *
 * 人格头像与「我的头像」共用下面这套渲染/请求逻辑，不要再各自写一份 ImageRequest。
 */
@Composable
fun PersonaAvatar(
    avatarUri: String?,
    seed: Int,
    name: String,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    ringColor: Color? = null
) {
    val palette = avatarPalette(seed)
    AvatarSurface(
        avatarUri = avatarUri,
        size = size,
        modifier = modifier,
        ringColor = ringColor,
        contentDescription = stringResource(R.string.common_avatar_content_description, name),
        background = Brush.linearGradient(listOf(palette.first, palette.second)),
        badgePadding = 2.dp
    ) {
        Text(
            text = name.trim().take(1).ifBlank { stringResource(R.string.common_avatar_initial) },
            color = Color.White.copy(alpha = 0.96f),
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.38f).sp
        )
    }
}

/**
 * 「我的头像」：聊天里用户消息旁边那个小圆。
 *
 * 没选图时不画首字，而是一枚安静的 `Person` 图标——用户没有名字可显示，
 * 用首字只会让人以为那是某个人的头像。
 */
@Composable
fun UserAvatar(
    avatarUri: String?,
    size: Dp = 28.dp,
    modifier: Modifier = Modifier,
    /**
     * 离线兜底：服务端模式下 [avatarUri] 是 `/v1/media/<id>`，服务端不可达时取不到图，
     * 这里传本机缓存副本（`settings.userAvatarLocalUri`）。本地模式下留空即可。
     */
    fallbackUri: String? = null
) {
    AvatarSurface(
        avatarUri = avatarUri,
        fallbackUri = fallbackUri,
        size = size,
        modifier = modifier,
        ringColor = null,
        contentDescription = stringResource(R.string.common_user_avatar_content_description),
        background = Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
            )
        ),
        badgePadding = 0.dp
    ) {
        Icon(
            Icons.Outlined.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            modifier = Modifier.size(size * 0.58f)
        )
    }
}

/**
 * 头像的公共壳：渐变底 + 圆形裁切 + 可选图片 + 兜底内容。
 *
 * [avatarUri] 为空时渲染 [fallback]（人格是色块+首字，用户是 Person 图标）。
 */
@Composable
private fun AvatarSurface(
    avatarUri: String?,
    fallbackUri: String? = null,
    size: Dp,
    modifier: Modifier,
    ringColor: Color?,
    contentDescription: String,
    background: Brush,
    badgePadding: Dp,
    fallback: @Composable () -> Unit
) {
    val density = LocalDensity.current
    // 头像本身的显示像素：带描边时内圈小一点，但目标尺寸差异可以忽略，统一按 size 算。
    val targetPx = remember(size, density) {
        with(density) { size.roundToPx() }.coerceAtLeast(1)
    }
    // 目标尺寸参与 key：同一个 uri 在 28dp 和 92dp 下需要不同尺寸的解码结果，
    // 否则小头像会复用大头像的位图（或反过来把大图缩了又缩）。
    //
    // 0.12 M5：`avatarUri` 可能是服务端媒体引用（`/v1/media/<id>`）而不是本机文件；
    // 统一交给 `rememberMediaImageRequest` 解析并补上 Authorization，头像不再分两套渲染。
    val request = rememberMediaImageRequest(
        raw = avatarUri,
        widthPx = targetPx,
        heightPx = targetPx
    )
    // 0.12.1 离线兜底：服务端引用加载失败时用本机缓存副本再试一次。
    val fallbackRequest = rememberMediaImageRequest(
        raw = fallbackUri,
        widthPx = targetPx,
        heightPx = targetPx
    )
    // 文件被清掉 / 备份从另一台手机接回来时，avatarUri 可能指向一个不存在的路径。
    // 这时退回默认（色块+首字 / Person 图标），而不是留一个空圆圈。
    var primaryFailed by remember(request) { mutableStateOf(false) }
    var fallbackFailed by remember(fallbackRequest) { mutableStateOf(false) }
    val activeRequest = when {
        request != null && !primaryFailed -> request
        fallbackRequest != null && !fallbackFailed -> fallbackRequest
        else -> null
    }
    val shape = CircleShape
    val borderModifier = if (ringColor != null) {
        Modifier.border(2.dp, ringColor, shape).padding(badgePadding)
    } else Modifier
    Box(
        modifier = modifier
            .size(size)
            .then(borderModifier)
            .clip(shape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        if (activeRequest != null) {
            AsyncImage(
                model = activeRequest,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                onError = {
                    if (activeRequest === request) primaryFailed = true else fallbackFailed = true
                }
            )
        } else {
            fallback()
        }
    }
}

@Composable
fun SmallOnlineDot(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = modifier
            .size(11.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background)
            .padding(2.dp)
            .clip(CircleShape)
            .background(color)
    )
}
