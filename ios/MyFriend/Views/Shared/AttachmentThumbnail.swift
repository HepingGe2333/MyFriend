import CoreGraphics
import SwiftUI

/// 按**显示尺寸**解码一张沙盒里的图片，异步加载 + 共享的缩略图缓存。
///
/// 为什么不 `UIImage(contentsOfFile:)`：那等于把整张原图（可能 1200 万像素）解进内存，
/// 聊天页滚一遍十几张就把内存顶上去。这里走 `MediaStorage.thumbnail(maxPixelSize:)`，
/// ImageIO 在解码阶段就出缩略图，解码结果进 `NSCache`（上限 = min(物理内存 × 10%, 48MB)）。
///
/// 离屏（`onDisappear`）时把 SwiftUI 这一层持有的 `CGImage` 放掉：缓存里还有一份，
/// 但那是按预算回收的；视图自己不该把整段历史的图都钉在内存里。
struct AttachmentThumbnail<Placeholder: View>: View {

    let uri: String
    /// 显示的边长（pt）。真正解码的像素数 = 它 × `displayScale`，不按原图解。
    let pointSize: CGFloat
    /// `.fill` 用于圆形头像/方格缩略图（会裁掉溢出的部分），`.fit` 用于看整张图。
    let contentMode: ContentMode
    /// 图还没解出来 / 文件已经不在时显示什么。参数 = 是不是「文件真的不见了」。
    let placeholder: (Bool) -> Placeholder

    @Environment(\.displayScale) private var displayScale
    @State private var image: CGImage?
    @State private var missing = false

    init(
        uri: String,
        pointSize: CGFloat,
        contentMode: ContentMode = .fit,
        @ViewBuilder placeholder: @escaping (Bool) -> Placeholder
    ) {
        self.uri = uri
        self.pointSize = pointSize
        self.contentMode = contentMode
        self.placeholder = placeholder
    }

    /// 解码目标：显示尺寸 × 屏幕倍率（3x 屏上 46pt 的头像解 138px 就够）。
    private var pixelSize: Int {
        max(1, Int((pointSize * displayScale).rounded()))
    }

    var body: some View {
        Group {
            if let image {
                if contentMode == .fill {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .interpolation(.medium)
                        .scaledToFill()
                } else {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .interpolation(.medium)
                        .scaledToFit()
                }
            } else {
                placeholder(missing)
            }
        }
        .task(id: "\(uri)#\(pixelSize)") { await load() }
        .onDisappear {
            // 离屏释放：滚动出屏幕的图不该继续被视图树强引用。
            image = nil
        }
    }

    private func load() async {
        guard image == nil, !missing else { return }
        let attachment = MessageAttachment(uri: uri)
        let storage = MediaStorage.shared
        let target = pixelSize
        let decoded = await Task.detached(priority: .userInitiated) { () -> CGImage? in
            storage.thumbnail(for: attachment, maxPixelSize: target)
        }.value
        guard !Task.isCancelled else { return }
        if let decoded {
            image = decoded
            missing = false
        } else {
            missing = true
        }
    }
}

/// 缩略图的占位块：加载中是一个浅浅的灰块，文件不在时明说「图片已不在本机」。
struct AttachmentPlaceholder: View {

    let missing: Bool
    var iconSize: CGFloat = 18
    var textSize: CGFloat = 11
    var compact: Bool = false

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(scheme == .dark ? Color.white.opacity(0.07) : Color.black.opacity(0.05))
            VStack(spacing: 4) {
                Image(systemName: missing ? "photo.badge.exclamationmark" : "photo")
                    .font(.system(size: iconSize, weight: .light))
                    .foregroundStyle(.secondary)
                if missing && !compact {
                    Text(L("图片已不在本机"))
                        .font(.system(size: textSize))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 6)
                }
            }
        }
    }
}
