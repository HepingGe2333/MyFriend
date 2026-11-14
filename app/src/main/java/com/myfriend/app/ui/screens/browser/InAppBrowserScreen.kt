package com.myfriend.app.ui.screens.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.myfriend.app.domain.LinkDetector
import com.myfriend.app.ui.components.AppHeader
import com.myfriend.app.ui.components.BackNavigation
import com.myfriend.app.ui.components.EmptyState
import com.myfriend.app.ui.components.SecondaryButton
import com.myfriend.app.ui.theme.LocalAccentColor
import kotlinx.coroutines.launch

/**
 * 在应用里看他发来的网页。
 *
 * 这不是「跳去浏览器」：地址原地加载，返回优先在页面里往回退，退到头才离开。
 * 顶部标题取自网页自己的标题，拿不到就退回域名，文案只说「他发给你的链接」。
 */
@Composable
fun InAppBrowserScreen(
    url: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val accent = LocalAccentColor.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 地址统一过一遍 LinkDetector：只有 http/https 才会真的加载。
    // 路由参数偶尔可能还是编码过的形态（%3A%2F%2F），这种情况再解一次。
    val initialUrl = remember(url) {
        LinkDetector.normalize(url)
            ?: runCatching { LinkDetector.normalize(Uri.decode(url)) }.getOrNull()
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl ?: url) }
    var progress by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var failureReason by remember { mutableStateOf("这个页面没能打开") }

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun openInSystem(target: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
        runCatching { context.startActivity(intent) }
            .onFailure { notify("没能打开，可以先复制链接") }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    val title = pageTitle?.takeIf { it.isNotBlank() }
        ?: hostOf(currentUrl)
        ?: "打开链接"

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AppHeader(
            title = title,
            subtitle = "他发给你的链接",
            navigation = { BackNavigation(onBack) },
            actions = {
                IconButton(onClick = { openInSystem(currentUrl) }) {
                    Icon(
                        Icons.Outlined.OpenInBrowser,
                        contentDescription = "在系统浏览器打开",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(currentUrl))
                    notify("链接已复制")
                }) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制链接",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (initialUrl != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // 只给最基础的能力：能看网页、能记住登录态，但碰不到本机文件
                            applyRestrictedSettings()
                            // 长按只保留系统默认的选字：不挂下载图片、自定义菜单、新窗口这些多余的入口
                            isLongClickable = true
                            webViewClient = InAppWebViewClient(
                                onStarted = { startedUrl ->
                                    failed = false
                                    progress = 0
                                    startedUrl?.let { currentUrl = it }
                                },
                                onFinished = { finishedUrl ->
                                    finishedUrl?.let { currentUrl = it }
                                    progress = 100
                                },
                                onMainFrameError = { reason ->
                                    failed = true
                                    failureReason = reason
                                    progress = 100
                                },
                                onExternalUri = { uri -> openInSystem(uri.toString()) }
                            )
                            webChromeClient = InAppChromeClient(
                                onProgress = { progress = it },
                                onTitle = { newTitle ->
                                    pageTitle = newTitle?.trim()?.takeIf { it.isNotBlank() }
                                }
                            )
                            loadUrl(initialUrl)
                            webView = this
                        }
                    }
                )
                // 2dp 进度条：只在这一页最上沿，扫过去就收起来
                if (!failed && progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter),
                        color = accent,
                        trackColor = accent.copy(alpha = 0.12f)
                    )
                }
            }

            if (initialUrl == null || failed) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(
                        icon = Icons.Outlined.Language,
                        title = "这个页面打不开",
                        description = if (initialUrl == null) {
                            "这个地址不太对，没能在应用里打开。"
                        } else {
                            failureReason
                        },
                        action = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                SecondaryButton(
                                    text = "用系统浏览器打开",
                                    onClick = { openInSystem(currentUrl) }
                                )
                                if (initialUrl != null) {
                                    Spacer(Modifier.height(6.dp))
                                    TextButton(onClick = {
                                        failed = false
                                        webView?.reload()
                                    }) {
                                        Text("再试一次", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    )
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }

    // 页面进后台时让网页也安静下来，回来再继续
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webView) {
        val view = webView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                view.stopLoading()
                view.webChromeClient = null
                view.removeAllViews()
                view.destroy()
            }
        }
    }
}

/**
 * 网页里点的 http/https 原地加载，其它 scheme（打电话、邮件、应用唤起）交给系统。
 */
private class InAppWebViewClient(
    private val onStarted: (String?) -> Unit,
    private val onFinished: (String?) -> Unit,
    private val onMainFrameError: (String) -> Unit,
    private val onExternalUri: (Uri) -> Unit
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return handleNavigation(request.url)
    }

    @Deprecated("已由 WebResourceRequest 版本覆盖，只为老系统保留")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return handleNavigation(Uri.parse(url))
    }

    private fun handleNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        return when (scheme) {
            "http", "https", "about" -> false
            null -> true
            else -> {
                onExternalUri(uri)
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onFinished(url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) onMainFrameError(describeError(error))
    }

    /**
     * HTTP 错误码（404 / 500 / CDN 拦截）不是网络错误，WebView 不会走 onReceivedError，
     * 只会渲染一个空壳页：进度条走完、页面一片空白，用户既没有报错也没有重试入口。
     * 所以这里补一条——只认主文档、只认 4xx/5xx。
     */
    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (!request.isForMainFrame) return
        val status = errorResponse.statusCode
        if (status < 400) return
        val reason = errorResponse.reasonPhrase?.takeIf { it.isNotBlank() }
        onMainFrameError(
            if (reason == null) "网页返回了 $status" else "网页返回了 $status（$reason）"
        )
    }
}

private class InAppChromeClient(
    private val onProgress: (Int) -> Unit,
    private val onTitle: (String?) -> Unit
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        onTitle(title)
    }

    // 页面想开新窗口也不弹：留在这一页里看，别把用户甩出去
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message
    ): Boolean = false
}

/**
 * 安全设置：脚本和本地存储给到（否则大部分页面是空白），
 * 但读本机文件、读 content://、从 file:// 越权访问一律关闭。
 */
@Suppress("DEPRECATION")
private fun WebView.applyRestrictedSettings() {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.setGeolocationEnabled(false)
    settings.mediaPlaybackRequiresUserGesture = true
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
}

private fun describeError(error: WebResourceError): String = when (error.errorCode) {
    WebViewClient.ERROR_HOST_LOOKUP -> "没找到这个地址，可能是域名写错了。"
    WebViewClient.ERROR_CONNECT -> "连不上这个页面，网络可能不太稳。"
    WebViewClient.ERROR_TIMEOUT -> "页面响应太慢，没能加载出来。"
    WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "这个地址应用里打不开。"
    WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "这个页面的安全连接没建立起来。"
    else -> "这个页面没能打开，网络或者地址可能有问题。"
}

private fun hostOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val host = runCatching { Uri.parse(url).host }.getOrNull()
    return host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
}
