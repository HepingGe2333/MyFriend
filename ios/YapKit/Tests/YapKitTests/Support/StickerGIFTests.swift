import CoreGraphics
import Foundation
import ImageIO
import XCTest
@testable import YapKit

/// 表情压缩管线（`Support/StickerGIF.swift`）的验收：
/// 尺寸 / 帧数 / 时长 / 字节上限 / 往返帧数 / GIF89a / 无限循环。
///
/// 测试素材在测试里现场生成（`makeGIF` / `makePNG`），不往仓库里塞二进制：
/// 这样「源图多大、多少帧、多快」都是用例自己说了算，改上限时能立刻看出哪一条被打破。
final class StickerGIFTests: XCTestCase {

    // MARK: - 动图：全部上限

    func testAnimatedGIFIsClampedToEveryLimit() throws {
        // 源图：640×480、30 帧、每帧 40 ms（25 fps）、总长 1.2 s —— 四个维度全超。
        let source = try makeGIF(width: 640, height: 480, frameCount: 30, delaySeconds: 0.04)
        let result = try XCTUnwrap(StickerGIF.compress(data: source))

        XCTAssertLessThanOrEqual(max(result.width, result.height), StickerLimits.maxLongEdge)
        XCTAssertLessThanOrEqual(result.frames, StickerLimits.maxFrames)
        XCTAssertLessThanOrEqual(result.durationMillis, StickerLimits.maxDurationMillis)
        XCTAssertLessThanOrEqual(result.bytes, StickerLimits.maxBytes)
        XCTAssertTrue(StickerGIF.isGIF89a(data: result.data), "输出必须是 GIF89a")
        // 循环次数 0（无限）：GIF89a 的 NETSCAPE2.0 扩展里第三个字节是循环次数。
        XCTAssertTrue(containsInfiniteLoopExtension(result.data), "必须写「无限循环」扩展")

        // 帧率：每帧显示时间 ≥ 50 ms（= 20 fps）。
        let inspected = try XCTUnwrap(StickerGIF.inspect(data: result.data))
        XCTAssertEqual(inspected.frames, result.frames, "压缩结果里的帧数必须与文件里读回来的一致")
        XCTAssertEqual(inspected.width, result.width)
        XCTAssertEqual(inspected.height, result.height)
        XCTAssertLessThanOrEqual(inspected.durationMillis, StickerLimits.maxDurationMillis)

        // 逐帧延迟：从文件里读回来验证（而不是相信 plan 的返回）。
        let decoded = try XCTUnwrap(StickerGIF.decodeFrames(data: result.data))
        XCTAssertEqual(decoded.frames.count, result.frames, "往返帧数一致")
        for delay in decoded.delaysMillis {
            XCTAssertGreaterThanOrEqual(delay, StickerLimits.minFrameDelayMillis, "帧率必须 ≤ 20 fps")
        }
    }

    /// 8 秒的 GIF：只截前 6 秒，帧数也压到 ≤60（不加速、不改变播放速度）。
    func testLongAnimationIsTruncatedToSixSeconds() throws {
        let source = try makeGIF(width: 320, height: 320, frameCount: 80, delaySeconds: 0.1)
        let result = try XCTUnwrap(StickerGIF.compress(data: source))
        XCTAssertLessThanOrEqual(result.durationMillis, StickerLimits.maxDurationMillis)
        XCTAssertLessThanOrEqual(result.frames, StickerLimits.maxFrames)
        XCTAssertLessThanOrEqual(result.bytes, StickerLimits.maxBytes)
    }

    /// 静态 PNG：输出单帧 GIF（存储仍是 GIF），尺寸/字节都合格。
    func testStaticImageBecomesSingleFrameGIF() throws {
        let source = try makePNG(width: 1000, height: 700)
        let result = try XCTUnwrap(StickerGIF.compress(data: source))
        XCTAssertEqual(result.frames, 1)
        XCTAssertLessThanOrEqual(max(result.width, result.height), StickerLimits.maxLongEdge)
        XCTAssertLessThanOrEqual(result.bytes, StickerLimits.maxBytes)
        XCTAssertTrue(StickerGIF.isGIF89a(data: result.data) || result.data.starts(with: Data("GIF8".utf8)))
        XCTAssertEqual(StickerGIF.inspect(data: result.data)?.frames, 1)
    }

    /// 字节上限：噪声多的动图在 512 KB 下放不进去时，按退让表降尺寸/减帧，最终一定 ≤ 上限。
    func testNoisyAnimationStaysUnderByteLimit() throws {
        let source = try makeGIF(
            width: 320, height: 320, frameCount: 16, delaySeconds: 0.06, noisy: true
        )
        // 故意把上限压到 40 KB：一档放不下，必须真的走退让表。
        let result = try XCTUnwrap(StickerGIF.compress(data: source, maxBytes: 40 * 1024))
        XCTAssertLessThanOrEqual(result.bytes, 40 * 1024)
        XCTAssertLessThanOrEqual(max(result.width, result.height), StickerLimits.maxLongEdge)
        XCTAssertLessThanOrEqual(result.frames, StickerLimits.maxFrames)
    }

    /// 坏数据不崩：不是图片就返回 nil，由调用方给一句「读不出这张图片」。
    func testBrokenDataReturnsNil() {
        XCTAssertNil(StickerGIF.compress(data: Data()))
        XCTAssertNil(StickerGIF.compress(data: Data("not an image".utf8)))
        XCTAssertNil(StickerGIF.inspect(data: Data()))
    }

    // MARK: - 帧计划

    func testFramePlanMergesFastFramesToTwentyFPS() {
        // 100 帧 × 20 ms = 2 s（50 fps）→ 每凑够 50 ms 才保留一帧：
        // 3 帧 × 20 ms = 60 ms（2 帧只有 40 ms，不够），所以 33 帧、总时长不变。
        let plan = StickerGIF.framePlan(delaysMillis: Array(repeating: 20, count: 100))
        XCTAssertEqual(plan.count, 33)
        XCTAssertEqual(plan.durationMillis, 2_000)
        XCTAssertTrue(plan.delaysMillis.allSatisfy { $0 >= StickerLimits.minFrameDelayMillis })
        // 20 fps 是**上限**：帧间隔 ≥50 ms 就一定成立。
        XCTAssertTrue(plan.delaysMillis.allSatisfy { 1000 / $0 <= StickerLimits.maxFPS })
    }

    func testFramePlanTruncatesAtSixSecondsAndKeepsOrder() {
        let plan = StickerGIF.framePlan(delaysMillis: Array(repeating: 100, count: 100))
        XCTAssertLessThanOrEqual(plan.durationMillis, StickerLimits.maxDurationMillis)
        XCTAssertEqual(plan.count, 60)
        XCTAssertEqual(plan.indices, Array(0..<60), "截断要保留最前面的连续帧")
    }

    func testFramePlanSamplesWhenFramesExceedCap() {
        // 200 帧 × 50 ms = 10 s：先截到 6 s（120 帧），再抽样到 60 帧（步长 2）。
        let plan = StickerGIF.framePlan(delaysMillis: Array(repeating: 50, count: 200))
        XCTAssertEqual(plan.count, 60)
        XCTAssertLessThanOrEqual(plan.durationMillis, StickerLimits.maxDurationMillis)
        XCTAssertEqual(plan.delaysMillis.reduce(0, +), plan.durationMillis)
    }

    func testFramePlanForStaticFrame() {
        let plan = StickerGIF.framePlan(delaysMillis: [0])
        XCTAssertEqual(plan.indices, [0])
        XCTAssertEqual(plan.count, 1)
    }

    // MARK: - 素材

    /// 现场生成一段 GIF（可选噪声帧）。
    private func makeGIF(
        width: Int,
        height: Int,
        frameCount: Int,
        delaySeconds: Double,
        noisy: Bool = false
    ) throws -> Data {
        let data = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(
            data as CFMutableData, StickerGIF.gifType as CFString, frameCount, nil
        ))
        CGImageDestinationSetProperties(destination, [
            kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFLoopCount: 0]
        ] as CFDictionary)
        var generator = SystemRandomNumberGenerator()
        for index in 0..<frameCount {
            let image = try XCTUnwrap(drawFrame(
                width: width,
                height: height,
                index: index,
                frameCount: frameCount,
                noisy: noisy,
                generator: &generator
            ))
            CGImageDestinationAddImage(destination, image, [
                kCGImagePropertyGIFDictionary: [
                    kCGImagePropertyGIFDelayTime: delaySeconds,
                    kCGImagePropertyGIFUnclampedDelayTime: delaySeconds
                ]
            ] as CFDictionary)
        }
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }

    private func makePNG(width: Int, height: Int) throws -> Data {
        let data = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(
            data as CFMutableData, "public.png" as CFString, 1, nil
        ))
        var generator = SystemRandomNumberGenerator()
        let image = try XCTUnwrap(drawFrame(
            width: width, height: height, index: 0, frameCount: 1,
            noisy: true, generator: &generator
        ))
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }

    private func drawFrame(
        width: Int,
        height: Int,
        index: Int,
        frameCount: Int,
        noisy: Bool,
        generator: inout SystemRandomNumberGenerator
    ) -> CGImage? {
        _ = generator
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        if noisy {
            // 直接填像素缓冲：320×320 逐像素 `context.fill` 要跑十万次/帧，太慢。
            var pixels = [UInt8](repeating: 0, count: width * height * 4)
            var seed = UInt64(index &+ 1) &* 2_654_435_761 &+ 12_345
            for offset in stride(from: 0, to: pixels.count, by: 4) {
                seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
                pixels[offset] = UInt8((seed >> 16) & 0xFF)
                pixels[offset + 1] = UInt8((seed >> 24) & 0xFF)
                pixels[offset + 2] = UInt8((seed >> 32) & 0xFF)
                pixels[offset + 3] = 255
            }
            guard let provider = CGDataProvider(data: Data(pixels) as CFData) else { return nil }
            return CGImage(
                width: width,
                height: height,
                bitsPerComponent: 8,
                bitsPerPixel: 32,
                bytesPerRow: width * 4,
                space: colorSpace,
                bitmapInfo: CGBitmapInfo(
                    rawValue: CGImageAlphaInfo.premultipliedLast.rawValue
                        | CGBitmapInfo.byteOrder32Big.rawValue
                ),
                provider: provider,
                decode: nil,
                shouldInterpolate: false,
                intent: .defaultIntent
            )
        }
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        let progress = frameCount > 1 ? CGFloat(index) / CGFloat(frameCount - 1) : 0
        context.setFillColor(CGColor(red: progress, green: 0.35, blue: 1 - progress, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.9))
        let barWidth = max(8, width / 8)
        context.fill(CGRect(
            x: CGFloat(index % max(1, width - barWidth)),
            y: CGFloat(height) / 2 - 10,
            width: CGFloat(barWidth),
            height: 20
        ))
        return context.makeImage()
    }

    /// GIF89a 的 NETSCAPE2.0 应用扩展：`21 FF 0B NETSCAPE2.0 03 01 <loopLo> <loopHi> 00`。
    private func containsInfiniteLoopExtension(_ data: Data) -> Bool {
        guard let marker = "NETSCAPE2.0".data(using: .ascii) else { return false }
        guard let range = data.range(of: marker) else { return false }
        let loopOffset = range.upperBound + 2 // 03 01 之后就是循环次数（小端）
        guard loopOffset + 1 < data.count else { return false }
        return data[loopOffset] == 0 && data[loopOffset + 1] == 0
    }
}
