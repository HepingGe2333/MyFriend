import SwiftUI
import WebKit

/// 应用内浏览器：点他发来的链接，原地打开，不跳到 Safari。
///
/// 和 Android 端一致的三条：标题取网页自己的；主文档出错才提示，子资源（图片、字体）失败不打扰；
/// 右上角可以「在系统浏览器打开」或「复制链接」。
struct InAppBrowserView: View {
    let url: String

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    @State private var title: String = ""
    @State private var progress: Double = 0
    @State private var failure: String?
    @State private var reloadToken = 0
    @State private var copyToast = false

    var body: some View {
        VStack(spacing: 0) {
            header
            if progress < 1 && failure == nil {
                ProgressView(value: progress)
                    .progressViewStyle(.linear)
                    .tint(MFTheme.defaultAccent)
                    .frame(height: 2)
            }
            ZStack {
                WebViewContainer(
                    url: url,
                    reloadToken: reloadToken,
                    onTitle: { title = $0 },
                    onProgress: { progress = $0 },
                    onFailure: { failure = $0 }
                )
                if let failure {
                    failureView(failure)
                }
            }
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .overlay(alignment: .bottom) {
            if copyToast {
                Text(L("链接已复制"))
                    .font(.footnote)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(.thinMaterial, in: Capsule())
                    .padding(.bottom, 24)
                    .transition(.opacity)
            }
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 36, height: 36)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(title.isEmpty ? (URL(string: url)?.host ?? L("网页")) : title)
                    .font(.system(.headline, design: .serif))
                    .lineLimit(1)
                Text(L("他发给你的链接"))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            Button {
                if let link = URL(string: url) { UIApplication.shared.open(link) }
            } label: {
                Image(systemName: "safari")
                    .font(.system(size: 16))
                    .frame(width: 36, height: 36)
            }
            Button {
                UIPasteboard.general.string = url
                withAnimation { copyToast = true }
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
                    withAnimation { copyToast = false }
                }
            } label: {
                Image(systemName: "doc.on.doc")
                    .font(.system(size: 16))
                    .frame(width: 36, height: 36)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(MFTheme.canvas(scheme).opacity(0.96))
    }

    private func failureView(_ message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 30, weight: .light))
                .foregroundStyle(.secondary)
            Text(L("这个网页没打开"))
                .font(.system(.headline, design: .serif))
            Text(message)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Button(L("再试一次")) {
                failure = nil
                progress = 0
                reloadToken += 1
            }
            .buttonStyle(.borderedProminent)
            .tint(MFTheme.defaultAccent)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(MFTheme.canvas(scheme))
    }
}

/// `WKWebView` 的 SwiftUI 包装。
///
/// 单独抽出来是因为 WebView 的生命周期必须跟视图一致：`updateUIView` 里不能重建，
/// `dismantleUIView` 里要停加载，否则返回上一页后音频还在响、进度回调还在跑。
private struct WebViewContainer: UIViewRepresentable {
    let url: String
    let reloadToken: Int
    let onTitle: (String) -> Void
    let onProgress: (Double) -> Void
    let onFailure: (String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onTitle: onTitle, onProgress: onProgress, onFailure: onFailure)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.allowsInlineMediaPlayback = true
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.isOpaque = false
        webView.backgroundColor = .clear
        if let link = URL(string: url) {
            webView.load(URLRequest(url: link, timeoutInterval: 60))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        // 只有「重新加载」这一种情况需要动它：地址变了由 NavigationStack 换页面处理。
        if context.coordinator.loadedToken != reloadToken {
            context.coordinator.loadedToken = reloadToken
            if let link = URL(string: url) {
                webView.load(URLRequest(url: link, timeoutInterval: 60))
            }
        }
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.stopLoading()
        webView.navigationDelegate = nil
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        private let onTitle: (String) -> Void
        private let onProgress: (Double) -> Void
        private let onFailure: (String) -> Void
        var loadedToken = 0

        init(
            onTitle: @escaping (String) -> Void,
            onProgress: @escaping (Double) -> Void,
            onFailure: @escaping (String) -> Void
        ) {
            self.onTitle = onTitle
            self.onProgress = onProgress
            self.onFailure = onFailure
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            onProgress(0.1)
        }

        func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
            onProgress(0.6)
            if let title = webView.title, !title.isEmpty { onTitle(title) }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            onProgress(1)
            if let title = webView.title, !title.isEmpty { onTitle(title) }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            onFailure(error.localizedDescription)
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            onFailure(error.localizedDescription)
        }

        /// HTTP 4xx/5xx 不走 didFail：不补这一条，用户只会看到一个空白页。
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationResponse: WKNavigationResponse,
            decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void
        ) {
            if let response = navigationResponse.response as? HTTPURLResponse,
               response.statusCode >= 400 {
                onFailure(Lf("网页返回了 %d", response.statusCode))
            }
            decisionHandler(.allow)
        }
    }
}
