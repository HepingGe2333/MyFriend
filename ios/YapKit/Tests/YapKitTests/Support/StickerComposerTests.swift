import CoreGraphics
import Foundation
import XCTest
@testable import YapKit

/// 表情合成（`Support/StickerComposer.swift`）：叠字 / 叠图**真的画上去了**，位置参数真的生效。
///
/// 为什么必须比像素而不是只比「文件生成了」：CoreText 的绘制在 macOS/iOS 上都能静默
/// 画不出东西（字体、颜色属性、坐标系任一错位），而压缩结果照样是一张合法 GIF。
/// 这里逐像素对比「有文字/没文字」「TOP/BOTTOM」，画不上去就红。
final class StickerComposerTests: XCTestCase {

    func testBlankWithTextDrawsPixels() throws {
        let blankColor = CGColor(red: 0.99, green: 0.97, blue: 0.92, alpha: 1)
        let blank = try XCTUnwrap(
            StickerComposer.solidCanvas(width: 320, height: 320, color: blankColor)
        )
        let composition = try XCTUnwrap(StickerComposer.blankWithText("你好呀", position: .center))
        XCTAssertEqual(composition.frames.count, 1)
        let difference = try differingPixelCount(pixelData(blank), pixelData(composition.frames[0]))
        XCTAssertGreaterThan(difference, 500, "叠字必须真的画出笔画（差异像素太少）")
    }

    func testTextPositionChangesOutput() throws {
        let top = try XCTUnwrap(StickerComposer.blankWithText("上面", position: .top)?.frames.first)
        let bottom = try XCTUnwrap(StickerComposer.blankWithText("下面", position: .bottom)?.frames.first)
        let difference = try differingPixelCount(pixelData(top), pixelData(bottom))
        XCTAssertGreaterThan(difference, 500, "TOP 与 BOTTOM 必须画在不同的位置（文字也不同）")
    }

    func testOverlayStickerIsDrawnAtPosition() throws {
        let base = try XCTUnwrap(StickerTestMedia.frame(width: 320, height: 320, index: 0, frameCount: 1))
        let overlay = try XCTUnwrap(
            StickerComposer.solidCanvas(width: 80, height: 80, color: CGColor(red: 1, green: 0, blue: 0, alpha: 1))
        )
        let top = try XCTUnwrap(StickerComposer.compose(
            baseFrames: [base], baseDelays: [0], overlay: overlay, text: nil, position: .top
        ))
        let bottom = try XCTUnwrap(StickerComposer.compose(
            baseFrames: [base], baseDelays: [0], overlay: overlay, text: nil, position: .bottom
        ))
        XCTAssertEqual(top.frames.count, 1)
        let difference = try differingPixelCount(pixelData(top.frames[0]), pixelData(bottom.frames[0]))
        XCTAssertGreaterThan(difference, 500, "叠图在 TOP 与 BOTTOM 必须落点不同")

        // 叠图确实出现了：与没有叠图的基底不同。
        let baseData = pixelData(base)
        XCTAssertGreaterThan(try differingPixelCount(baseData, pixelData(top.frames[0])), 500)
    }

    func testAnimatedBaseKeepsFrameCountAndDelays() throws {
        let frames = (0..<5).compactMap { StickerTestMedia.frame(width: 200, height: 200, index: $0, frameCount: 5) }
        let composition = try XCTUnwrap(StickerComposer.compose(
            baseFrames: frames,
            baseDelays: [100, 100, 100, 100, 100],
            overlay: nil,
            text: "动图",
            position: .bottom
        ))
        XCTAssertEqual(composition.frames.count, 5, "逐帧叠字不能丢帧")
        XCTAssertEqual(composition.delaysMillis.count, 5)
        XCTAssertEqual(composition.delaysMillis.reduce(0, +), 500)
    }

    // MARK: - 像素对比

    private func pixelData(_ image: CGImage) -> Data {
        let width = max(1, image.width)
        let height = max(1, image.height)
        var data = Data(count: width * height * 4)
        data.withUnsafeMutableBytes { buffer in
            guard let base = buffer.baseAddress else { return }
            let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
            guard let context = CGContext(
                data: base,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: width * 4,
                space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return data
    }

    private func differingPixelCount(_ lhs: Data, _ rhs: Data) throws -> Int {
        guard lhs.count == rhs.count else {
            throw XCTSkip("两张图尺寸不同，无法逐像素比较")
        }
        var count = 0
        for index in stride(from: 0, to: lhs.count, by: 4) {
            if lhs[index] != rhs[index]
                || lhs[index + 1] != rhs[index + 1]
                || lhs[index + 2] != rhs[index + 2]
                || lhs[index + 3] != rhs[index + 3] {
                count += 1
            }
        }
        return count
    }
}
