import CoreGraphics
import Foundation
import ImageIO

/// 表情测试素材（服务层/工具测试共用）。
///
/// 现场生成，不入库：尺寸、帧数、延迟都由用例决定，改上限时能立刻看出哪条被打破。
enum StickerTestMedia {

    /// 一段普通 GIF（每个测试自己选尺寸/帧数/延迟）。
    static func animatedGIF(
        width: Int = 200,
        height: Int = 200,
        frameCount: Int = 6,
        delaySeconds: Double = 0.08
    ) -> Data {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            data as CFMutableData, "com.compuserve.gif" as CFString, frameCount, nil
        ) else { return Data() }
        CGImageDestinationSetProperties(destination, [
            kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFLoopCount: 0]
        ] as CFDictionary)
        for index in 0..<frameCount {
            guard let image = frame(width: width, height: height, index: index, frameCount: frameCount)
            else { continue }
            CGImageDestinationAddImage(destination, image, [
                kCGImagePropertyGIFDictionary: [
                    kCGImagePropertyGIFDelayTime: delaySeconds,
                    kCGImagePropertyGIFUnclampedDelayTime: delaySeconds
                ]
            ] as CFDictionary)
        }
        _ = CGImageDestinationFinalize(destination)
        return data as Data
    }

    /// 一张静态 PNG。
    static func staticPNG(width: Int = 240, height: Int = 180) -> Data {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            data as CFMutableData, "public.png" as CFString, 1, nil
        ), let image = frame(width: width, height: height, index: 0, frameCount: 1) else { return Data() }
        CGImageDestinationAddImage(destination, image, nil)
        _ = CGImageDestinationFinalize(destination)
        return data as Data
    }

    static func frame(width: Int, height: Int, index: Int, frameCount: Int) -> CGImage? {
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil,
            width: max(1, width),
            height: max(1, height),
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        let progress = frameCount > 1 ? CGFloat(index) / CGFloat(frameCount - 1) : 0
        context.setFillColor(CGColor(red: 0.15 + progress * 0.7, green: 0.35, blue: 0.85 - progress * 0.6, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.9))
        context.fill(CGRect(x: CGFloat(width) / 4, y: CGFloat(height) / 3, width: CGFloat(width) / 2, height: 12))
        return context.makeImage()
    }
}
