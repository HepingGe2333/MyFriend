import CoreGraphics
import CoreText
import Foundation

// ---------------------------------------------------------------------------
// 表情制作（`sticker.make`）：基底 + 文字 / 基底 + 另一张图 / 纯色底 + 文字。
//
// 这里只做**画面合成**：把输入帧逐帧画成新的 CGImage，再交给 `StickerGIF.compress`
// 走同一条压缩管线（因此做出来的表情和导入的表情遵守完全一样的上限）。
//
// 为什么文字用 CoreText 而不是 UIKit/AppKit：Kit 层要在 macOS 上 `swift test`，
// CoreText 两端都有；字体用系统字（中文落到苹方，英文落到 San Francisco），
// 描边用 `kCTStrokeWidthAttributeName` 负值 = 填充 + 描边，白字黑边在任何底图上都读得清。
// ---------------------------------------------------------------------------

public enum StickerComposer {

    /// 文字/叠图放在哪个位置。
    public enum Position: String, CaseIterable, Sendable {
        case top = "TOP"
        case center = "CENTER"
        case bottom = "BOTTOM"

        /// 模型给的大小写/中文都认；认不出按 BOTTOM（表情文字最常见的位置）。
        public static func parse(_ raw: String?) -> Position {
            let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
            switch text {
            case "TOP", "上", "上面", "顶部": return .top
            case "CENTER", "MIDDLE", "中", "中间", "居中": return .center
            case "BOTTOM", "下", "下面", "底部": return .bottom
            default: return .bottom
            }
        }
    }

    /// 空白底（纯色 + 文字）的边长：与压缩上限一致，不再二次缩放。
    public static let blankCanvasSide = StickerLimits.maxLongEdge
    /// 文字四周留白（像素）。
    static let textMargin: CGFloat = 18
    /// 叠图最大占画布的比例。
    static let overlayMaxFraction: CGFloat = 0.5

    /// 合成结果（帧 + 延迟），可直接交给 `StickerGIF.compress(frames:delaysMillis:)`。
    public struct Composition: Sendable {
        public var frames: [CGImage]
        public var delaysMillis: [Int]

        public init(frames: [CGImage], delaysMillis: [Int]) {
            self.frames = frames
            self.delaysMillis = delaysMillis
        }
    }

    /// 纯色底 + 文字（meme 风格）：`baseStickerId` 为空时走这条。
    public static func blankWithText(_ text: String, position: Position) -> Composition? {
        guard let blank = solidCanvas(
            width: blankCanvasSide, height: blankCanvasSide,
            color: CGColor(red: 0.99, green: 0.97, blue: 0.92, alpha: 1)
        ) else { return nil }
        guard let composed = draw(frame: blank, text: text, position: position, overlay: nil, textPosition: position) else {
            return nil
        }
        return Composition(frames: [composed], delaysMillis: [StickerGIF.stillFrameDelayMillis])
    }

    /// 基底 + 文字（可选）+ 叠图（可选）。基底是动图时逐帧合成。
    ///
    /// - Parameters:
    ///   - baseFrames: 基底帧（空 = 纯色底 320×320）。
    ///   - baseDelays: 基底每帧延迟（空 = 静态）。
    ///   - overlay: 叠图（只取它的一帧；要叠动图的话需要对齐两段动画，这里不做）。
    ///   - text: 叠字（nil/空 = 不叠字）。
    ///   - position: 文字/叠图的位置。两者同时给时，文字放到**对面的那一侧**，
    ///     否则两样东西会叠在一起看不清。
    public static func compose(
        baseFrames: [CGImage],
        baseDelays: [Int],
        overlay: CGImage?,
        text: String?,
        position: Position
    ) -> Composition? {
        let trimmedText = text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let hasText = !trimmedText.isEmpty
        let hasOverlay = overlay != nil
        let base: [CGImage]
        if baseFrames.isEmpty {
            guard let blank = solidCanvas(
                width: blankCanvasSide, height: blankCanvasSide,
                color: CGColor(red: 0.99, green: 0.97, blue: 0.92, alpha: 1)
            ) else { return nil }
            base = [blank]
        } else {
            base = baseFrames
        }
        guard hasText || hasOverlay else { return nil }

        let textPosition = textPosition(for: position, both: hasText && hasOverlay)
        var frames: [CGImage] = []
        frames.reserveCapacity(base.count)
        for frame in base {
            guard let composed = draw(
                frame: frame,
                text: hasText ? trimmedText : nil,
                position: position,
                overlay: overlay,
                textPosition: textPosition
            ) else { continue }
            frames.append(composed)
        }
        guard !frames.isEmpty else { return nil }
        let delays = base.count == frames.count
            ? normalized(baseDelays, count: frames.count)
            : Array(repeating: StickerGIF.defaultFrameDelayMillis, count: frames.count)
        return Composition(frames: frames, delaysMillis: delays)
    }

    /// 两者同时给时文字放哪一侧。
    static func textPosition(for position: Position, both: Bool) -> Position {
        guard both else { return position }
        switch position {
        case .top: return .bottom
        case .bottom: return .top
        case .center: return .bottom
        }
    }

    // MARK: - 绘制

    /// 在一帧上画叠图与文字，返回新的 CGImage。
    static func draw(
        frame: CGImage,
        text: String?,
        position: Position,
        overlay: CGImage?,
        textPosition: Position
    ) -> CGImage? {
        let width = max(1, frame.width)
        let height = max(1, frame.height)
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        // 透明底：贴纸本身可以带透明通道（GIF 支持 1-bit 透明）；纯色底那条已经是不透明的。
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        context.clear(CGRect(x: 0, y: 0, width: width, height: height))
        context.interpolationQuality = .high
        let canvas = CGRect(x: 0, y: 0, width: width, height: height)
        context.draw(frame, in: canvas)

        if let overlay {
            drawOverlay(overlay, in: context, canvas: canvas, position: position)
        }
        if let text, !text.isEmpty {
            drawText(text, in: context, canvas: canvas, position: textPosition)
        }
        return context.makeImage()
    }

    /// 叠图：等比缩到画布的 50% 以内，按位置放（TOP/BOTTOM 留 `textMargin`）。
    static func drawOverlay(
        _ overlay: CGImage,
        in context: CGContext,
        canvas: CGRect,
        position: Position
    ) {
        let maxSide = min(canvas.width, canvas.height) * overlayMaxFraction
        let fitted = ImageEncodingPolicy.fittedSize(
            width: overlay.width, height: overlay.height, longEdge: Int(maxSide)
        )
        let size = CGSize(width: CGFloat(fitted.width), height: CGFloat(fitted.height))
        let origin: CGPoint
        switch position {
        case .top:
            origin = CGPoint(x: (canvas.width - size.width) / 2, y: canvas.height - textMargin - size.height)
        case .center:
            origin = CGPoint(x: (canvas.width - size.width) / 2, y: (canvas.height - size.height) / 2)
        case .bottom:
            origin = CGPoint(x: (canvas.width - size.width) / 2, y: textMargin)
        }
        context.draw(overlay, in: CGRect(origin: origin, size: size))
    }

    /// 叠字：白字黑边、自动换行、自动缩字号，最多占画布 3/4 高度。
    static func drawText(
        _ text: String,
        in context: CGContext,
        canvas: CGRect,
        position: Position
    ) {
        let availableWidth = max(40, canvas.width - textMargin * 2)
        let availableHeight = max(40, canvas.height * 0.75)
        let clean = String(text.prefix(StickerLimits.maxOverlayTextLength))
        // 字号从大往小试：能放下就用，最小 12（再小在手机上糊成一片）。
        var chosen: (framesetter: CTFramesetter, size: CGSize, font: CGFloat)?
        var size = min(96, max(20, canvas.width / 3))
        while size >= 12 {
            let attributed = attributedText(clean, fontSize: size)
            let framesetter = CTFramesetterCreateWithAttributedString(attributed)
            let suggested = CTFramesetterSuggestFrameSizeWithConstraints(
                framesetter,
                CFRange(location: 0, length: 0),
                nil,
                CGSize(width: availableWidth, height: .greatestFiniteMagnitude),
                nil
            )
            if suggested.width <= availableWidth + 0.5, suggested.height <= availableHeight {
                chosen = (framesetter, suggested, size)
                break
            }
            size -= 2
        }
        guard let chosen else {
            // 极小画布 / 极长文字：用最小字号硬画一次，总比什么都不画好。
            let attributed = attributedText(clean, fontSize: 12)
            let framesetter = CTFramesetterCreateWithAttributedString(attributed)
            let suggested = CTFramesetterSuggestFrameSizeWithConstraints(
                framesetter, CFRange(location: 0, length: 0), nil,
                CGSize(width: availableWidth, height: .greatestFiniteMagnitude), nil
            )
            draw(framesetter: framesetter, height: min(suggested.height, availableHeight),
                 in: context, canvas: canvas, position: position)
            return
        }
        draw(
            framesetter: chosen.framesetter,
            height: min(chosen.size.height, availableHeight),
            in: context, canvas: canvas, position: position
        )
    }

    private static func draw(
        framesetter: CTFramesetter,
        height: CGFloat,
        in context: CGContext,
        canvas: CGRect,
        position: Position
    ) {
        let rect: CGRect
        switch position {
        case .top:
            rect = CGRect(
                x: textMargin,
                y: canvas.height - textMargin - height,
                width: canvas.width - textMargin * 2,
                height: height
            )
        case .center:
            rect = CGRect(
                x: textMargin,
                y: (canvas.height - height) / 2,
                width: canvas.width - textMargin * 2,
                height: height
            )
        case .bottom:
            rect = CGRect(
                x: textMargin,
                y: textMargin,
                width: canvas.width - textMargin * 2,
                height: height
            )
        }
        let path = CGPath(rect: rect, transform: nil)
        let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: 0, length: 0), path, nil)
        context.saveGState()
        // CoreText 在「文本坐标系」里排字；CGContext 的 y 轴向上，直接 CTFrameDraw 即可。
        CTFrameDraw(frame, context)
        context.restoreGState()
    }

    /// 白字黑边（描边宽度为负 = 同时填充与描边）。
    static func attributedText(_ text: String, fontSize: CGFloat) -> NSAttributedString {
        let font = systemFont(size: fontSize)
        let attributes: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String):
                CGColor(red: 1, green: 1, blue: 1, alpha: 1),
            NSAttributedString.Key(kCTStrokeColorAttributeName as String):
                CGColor(red: 0, green: 0, blue: 0, alpha: 0.92),
            NSAttributedString.Key(kCTStrokeWidthAttributeName as String):
                NSNumber(value: -4.0)
        ]
        return NSAttributedString(string: text, attributes: attributes)
    }

    static func systemFont(size: CGFloat) -> CTFont {
        if let font = CTFontCreateUIFontForLanguage(.emphasizedSystem, size, nil) {
            return font
        }
        return CTFontCreateWithName("Helvetica-Bold" as CFString, size, nil)
    }

    // MARK: - 小工具

    static func solidCanvas(width: Int, height: Int, color: CGColor) -> CGImage? {
        let w = max(1, width)
        let h = max(1, height)
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil,
            width: w,
            height: h,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        context.setFillColor(color)
        context.fill(CGRect(x: 0, y: 0, width: w, height: h))
        return context.makeImage()
    }

    static func normalized(_ delays: [Int], count: Int) -> [Int] {
        guard count > 1 else { return [StickerGIF.stillFrameDelayMillis] }
        return (0..<count).map { index in
            let value = index < delays.count ? delays[index] : StickerGIF.defaultFrameDelayMillis
            return max(StickerGIF.delayTickMillis, value)
        }
    }
}
