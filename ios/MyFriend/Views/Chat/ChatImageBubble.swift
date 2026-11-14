import SwiftUI

/// 聊天气泡里的图片：缩略图 + 点击全屏。
///
/// 一条消息最多四张图（`AttachmentsCodec.maxImagesPerMessage`）。一张时按原比例铺开，
/// 多张时排成两列小方格 —— 与 Android 的图片气泡同一套观感，也不至于让一条消息占满一屏。
///
/// 缩略图一律按**显示尺寸**解码（`AttachmentThumbnail` → `MediaStorage.thumbnail`），
/// 文件被清掉时显示「图片已不在本机」，不崩、不空白。
struct ChatAttachmentsView: View {

    let attachments: [MessageAttachment]
    /// 点第几张（下标）。
    let onOpen: (Int) -> Void

    private var singleSize: CGSize {
        guard let first = attachments.first else { return CGSize(width: 200, height: 150) }
        return Self.thumbnailSize(for: first)
    }

    var body: some View {
        if attachments.count == 1 {
            image(at: 0, size: singleSize, corner: 12)
        } else {
            grid
        }
    }

    private var grid: some View {
        let columns = 2
        let side: CGFloat = 106
        let rows = Int(ceil(Double(attachments.count) / Double(columns)))
        return VStack(spacing: 4) {
            ForEach(0..<rows, id: \.self) { row in
                HStack(spacing: 4) {
                    ForEach(0..<columns, id: \.self) { column in
                        let index = row * columns + column
                        if index < attachments.count {
                            image(at: index, size: CGSize(width: side, height: side), corner: 10)
                        } else {
                            Color.clear.frame(width: side, height: side)
                        }
                    }
                }
            }
        }
    }

    private func image(at index: Int, size: CGSize, corner: CGFloat) -> some View {
        Button {
            onOpen(index)
        } label: {
            AttachmentThumbnail(
                uri: attachments[index].uri,
                pointSize: max(size.width, size.height),
                contentMode: attachments.count == 1 ? .fit : .fill
            ) { missing in
                AttachmentPlaceholder(missing: missing)
            }
            .frame(width: size.width, height: size.height)
            .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(L("图片，点开看大图")))
    }

    /// 按附件记录的真实像素宽高算缩略图占位（没有宽高时按 4:3）。
    static func thumbnailSize(for attachment: MessageAttachment) -> CGSize {
        let maxWidth: CGFloat = 232
        let maxHeight: CGFloat = 280
        guard attachment.width > 0, attachment.height > 0 else {
            return CGSize(width: maxWidth, height: maxWidth * 0.75)
        }
        let ratio = CGFloat(attachment.width) / CGFloat(attachment.height)
        let clamped = min(max(ratio, 0.5), 2.4)
        if clamped >= 1 {
            let width = maxWidth
            return CGSize(width: width, height: max(96, width / clamped))
        }
        let height = maxHeight
        return CGSize(width: max(96, height * clamped), height: height)
    }
}

// MARK: - 全屏查看

/// 全屏看图的入口参数：点的是哪条消息里的第几张。
struct ChatImagePreviewItem: Identifiable {
    let id: String
    let attachments: [MessageAttachment]
    let index: Int

    init(message: MessageEntity, attachments: [MessageAttachment], index: Int) {
        self.id = "\(message.id)#\(index)"
        self.attachments = attachments
        self.index = index
    }
}

/// 全屏看图：黑色底、左右翻页、双指缩放/拖动、双重点一下复位、右上角关闭。
///
/// 为什么自己做而不是 `QuickLook`：需要「关掉就回到聊天」这一下足够快，
/// 也不想为了看一张图去接一整套预览框架。
struct ChatImageViewer: View {

    let attachments: [MessageAttachment]
    @State private var index: Int

    @Environment(\.dismiss) private var dismiss

    init(attachments: [MessageAttachment], initialIndex: Int) {
        self.attachments = attachments
        _index = State(initialValue: min(max(initialIndex, 0), max(attachments.count - 1, 0)))
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()

            TabView(selection: $index) {
                ForEach(attachments.indices, id: \.self) { position in
                    ZoomableAttachmentImage(attachment: attachments[position])
                        .tag(position)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: attachments.count > 1 ? .automatic : .never))
            .ignoresSafeArea()

            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 38, height: 38)
                    .background(.black.opacity(0.45), in: Circle())
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .padding(.top, 8)
            .padding(.trailing, 14)
            .accessibilityLabel(Text(L("关闭")))
        }
        .statusBarHidden(true)
    }
}

/// 一张可以缩放/拖动的图。
private struct ZoomableAttachmentImage: View {

    let attachment: MessageAttachment

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    private let minScale: CGFloat = 1
    private let maxScale: CGFloat = 5

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                AttachmentThumbnail(
                    uri: attachment.uri,
                    pointSize: max(proxy.size.width, proxy.size.height)
                ) { missing in
                    if missing {
                        VStack(spacing: 8) {
                            Image(systemName: "photo.badge.exclamationmark")
                                .font(.system(size: 34, weight: .light))
                            Text(L("图片已不在本机"))
                                .font(.footnote)
                        }
                        .foregroundStyle(.white.opacity(0.75))
                    } else {
                        ProgressView().tint(.white)
                    }
                }
                .scaledToFit()
                .scaleEffect(scale)
                .offset(offset)
                .frame(width: proxy.size.width, height: proxy.size.height)
                .gesture(magnification)
                .simultaneousGesture(drag)
                .onTapGesture(count: 2) { toggleZoom() }
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
    }

    private var magnification: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                scale = min(max(lastScale * value, minScale), maxScale)
            }
            .onEnded { _ in
                lastScale = scale
                if scale <= minScale { reset() }
            }
    }

    private var drag: some Gesture {
        DragGesture()
            .onChanged { value in
                guard scale > minScale else { return }
                offset = CGSize(
                    width: lastOffset.width + value.translation.width,
                    height: lastOffset.height + value.translation.height
                )
            }
            .onEnded { _ in
                lastOffset = offset
            }
    }

    private func toggleZoom() {
        withAnimation(.easeInOut(duration: 0.2)) {
            if scale > minScale {
                reset()
            } else {
                scale = 2.4
                lastScale = scale
            }
        }
    }

    private func reset() {
        withAnimation(.easeInOut(duration: 0.2)) {
            scale = minScale
            lastScale = minScale
            offset = .zero
            lastOffset = .zero
        }
    }
}
