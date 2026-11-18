import ImageIO
import SwiftUI
import UIKit

/// 聊天气泡/表情面板里的一个表情：支持动图（GIF 动画），按显示尺寸解码。
///
/// 为什么不用 `AttachmentThumbnail`：那个是给静态图片（JPEG/PNG）用的，只解**第一帧**；
/// 表情的卖点就是动。这里用 `StickerGIF.decodeFrames` 读出所有帧，再交给
/// `UIImage.animatedImage` 播 —— 帧数已经由压缩管线限到 ≤60、每帧 ≤320 px，
/// 内存与 CPU 都是可控的。
///
/// 数据来源两种：
/// - 本机：`StickerStorage`（`Application Support/stickers/<id>.gif`）；
/// - 服务端模式：`ServerMediaCache`（`/v1/media/<id>`，带 Authorization）。
struct StickerBubbleView: View {

    let attachment: MessageAttachment
    /// 显示边长上限（聊天 240 pt；面板缩略图 72 pt）。
    var maxPointSize: CGFloat = StickerLimits.bubblePointSize
    /// 是否播放动画（面板里几十个表情同时动会卡，所以缩略图只显示首帧）。
    var animated: Bool = true

    @Environment(\.displayScale) private var displayScale
    @State private var frames: [CGImage] = []
    @State private var durations: [Double] = []
    @State private var failed = false

    var body: some View {
        Group {
            if let first = frames.first {
                if animated, frames.count > 1 {
                    AnimatedStickerImageView(frames: frames, durations: durations)
                } else {
                    Image(decorative: first, scale: 1)
                        .resizable()
                        .interpolation(.medium)
                        .scaledToFit()
                }
            } else if failed {
                stickerMissing
            } else {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color.primary.opacity(0.05))
                    .overlay(ProgressView().controlSize(.small))
            }
        }
        .frame(width: displaySize.width, height: displaySize.height)
        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        .accessibilityLabel(Text(L("表情")))
        .task(id: "\(attachment.uri)#\(maxPointSize)") { await load() }
        .onDisappear { frames = [] }
    }

    /// 按首帧比例算显示尺寸（长边 ≤ maxPointSize；还没解出来时先占一个方框）。
    private var displaySize: CGSize {
        guard let first = frames.first, first.width > 0, first.height > 0 else {
            let side = min(maxPointSize, 160)
            return CGSize(width: side, height: side)
        }
        let ratio = CGFloat(first.width) / CGFloat(first.height)
        let side = maxPointSize
        if ratio >= 1 {
            return CGSize(width: side, height: max(48, side / ratio))
        }
        return CGSize(width: max(48, side * ratio), height: side)
    }

    private var stickerMissing: some View {
        VStack(spacing: 4) {
            Image(systemName: "face.smiling")
                .font(.system(size: 20, weight: .light))
            Text(L("表情已不在本机"))
                .font(.caption2)
                .multilineTextAlignment(.center)
        }
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color.primary.opacity(0.05))
        )
    }

    private func load() async {
        guard frames.isEmpty else { return }
        let uri = attachment.uri
        let target = min(
            StickerLimits.maxLongEdge,
            max(1, Int((maxPointSize * displayScale).rounded()))
        )
        // 缩略图（历史面板）只解第一帧：一屏几十个动图各解 60 帧会把内存顶起来。
        let frameLimit = animated ? StickerLimits.maxFrames : 1
        let data: Data?
        if ServerMediaReference.mediaId(from: uri) != nil {
            data = try? await ServerMediaCache.shared.data(for: uri)
        } else if let url = StickerStorage.shared.fileURL(for: uri) {
            data = try? Data(contentsOf: url)
        } else {
            data = nil
        }
        guard let data, !data.isEmpty else {
            failed = true
            return
        }
        let decoded = await Task.detached(priority: .userInitiated) { () -> ([CGImage], [Double])? in
            guard let result = StickerGIF.decodeFrames(
                data: data, maxPixelSize: target, maxFrames: frameLimit
            ) else { return nil }
            let durations = result.delaysMillis.map { Double(max(10, $0)) / 1000.0 }
            return (result.frames, durations)
        }.value
        guard !Task.isCancelled, let decoded else {
            failed = true
            return
        }
        frames = decoded.0
        durations = decoded.1
    }
}

/// `UIImageView` + 逐帧动画。
///
/// 为什么不用 SwiftUI 的 `TimelineView` 手动切帧：`UIImage.animatedImage` 由 UIKit 自己
/// 按帧调度，在列表里更省；我们只需要把已经解码好的 CGImage 包成 UIImage。
private struct AnimatedStickerImageView: UIViewRepresentable {

    let frames: [CGImage]
    let durations: [Double]

    func makeUIView(context: Context) -> UIImageView {
        let view = UIImageView()
        view.contentMode = .scaleAspectFit
        view.clipsToBounds = true
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        apply(to: view)
        return view
    }

    func updateUIView(_ uiView: UIImageView, context: Context) {
        apply(to: uiView)
    }

    private func apply(to view: UIImageView) {
        let images = frames.map { UIImage(cgImage: $0) }
        guard images.count > 1 else {
            view.image = images.first
            view.stopAnimating()
            return
        }
        // `UIImage.animatedImage` 只接受**统一**帧间隔；压缩管线已经把延迟规整到
        // 50–200 ms 一档，这里用总时长/帧数近似即可（GIF 原速的观感差别肉眼看不出来）。
        let total = max(0.1, durations.reduce(0, +))
        view.animationImages = images
        view.animationDuration = total
        view.animationRepeatCount = 0
        view.startAnimating()
    }
}
