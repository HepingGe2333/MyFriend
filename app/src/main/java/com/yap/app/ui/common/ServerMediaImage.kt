package com.yap.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import com.yap.app.data.remote.ServerMediaResolver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 让 Compose 拿到 [ServerMediaResolver] 的 Hilt 入口。
 *
 * 为什么不用 ViewModel 往外传：头像会出现在首页/聊天/记忆/设置等十几个页面，
 * 每个页面都为了「解析一个图片地址」多一个依赖不划算；这个入口只暴露解析器，
 * 不暴露连接配置或令牌（令牌只在 [ServerMediaResolver] 内部进 ImageRequest 头）。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServerMediaResolverEntryPoint {
    fun serverMediaResolver(): ServerMediaResolver
}

/** 全 App 共用的媒体引用解析器；服务端配置变化会触发重组（打印/切换后图片能立刻重新加载）。 */
@Composable
fun rememberServerMediaResolver(): ServerMediaResolver {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ServerMediaResolverEntryPoint::class.java
        ).serverMediaResolver()
    }
}

/**
 * 把「本地路径 / 服务端媒体引用 / 普通 URL」变成一张按目标尺寸解码的 [ImageRequest]。
 *
 * 服务端媒体会带 `Authorization: Bearer …` 头（只对同源 `/v1/media/` 生效）；
 * Coil 的磁盘缓存会把下载后的文件留在 `cacheDir` 下的图像缓存目录，所以同一张图第二次打开
 * 不再走网络——这就是「缓存到本地文件，复用磁盘缓存」的那层。
 */
@Composable
fun rememberMediaImageRequest(
    raw: String?,
    widthPx: Int,
    heightPx: Int,
    crossfade: Boolean = true
): ImageRequest? {
    val resolver = rememberServerMediaResolver()
    val context = LocalContext.current
    // 服务端地址/令牌变化时要重新建请求（否则已经显示的图会继续用旧地址）。
    val state by resolver.state.collectAsState()
    val key = raw?.trim().orEmpty()
    return remember(key, widthPx, heightPx, crossfade, state.config) {
        val model = resolver.model(key) ?: return@remember null
        ImageRequest.Builder(context)
            .data(model)
            .size(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
            .apply {
                resolver.authorizationHeaderFor(model)?.let { addHeader("Authorization", it) }
                if (crossfade) crossfade(true)
            }
            .build()
    }
}
