import CoreGraphics
import Foundation
import ImageIO

// ---------------------------------------------------------------------------
// 表情包压缩管线（`spec/stickers/README.md` §导入与压缩）。
//
// 输入：GIF / WebP 动图 / APNG / 静态图（jpeg、png、heic…）的原始字节。
// 输出：**GIF89a**（`com.compuserve.gif`，循环次数 0 = 无限），并保证：
//
// | 约束 | 值 | 这一层怎么保证 |
// | --- | --- | --- |
// | 长边 | ≤ 320 px | 每帧解码后按 `ImageEncodingPolicy.fittedSize` 等比缩 |
// | 帧率 | ≤ 20 fps | 相邻帧的显示时间合并到 ≥ 50 ms（不是丢帧，见 `framePlan`） |
// | 帧数 | ≤ 60 | 超过就按等间隔抽样，被抽掉的帧时间累加到保留帧上 |
// | 总时长 | ≤ 6 s | 从第一帧开始累加到 6000 ms 就截断（保持原速，不加速） |
// | 调色板 | ≤ 256 色 | GIF 是 8-bit 索引色，ImageIO 编码器每帧都量化到 ≤256 |
// | 体积 | ≤ 512 KB | 编码后超限 → 降长边 / 减帧 / 降帧率，按 `qualityLadder` 逐档重试 |
//
// 为什么「合并帧」而不是「隔帧丢」：微信/QQ 拆出来的 GIF 常见 50 fps、每帧 20 ms，
// 直接丢一半会得到 25 fps（仍然超 20 fps），而合并相邻两帧的显示时间既压到 20 fps
// 又不改变播放总时长与观感。
//
// 为什么用 ImageIO 而不是自己写 GIF 编码器：解码（GIF/WebP/APNG 的帧与延迟）、
// 缩放、量化、LZW 压缩系统都有，自己写只会多一份「颜色表错位」的 bug。这里做的是
// **策略**（取哪些帧、缩到多大、超限怎么退），那才是产品要定的事。
// ---------------------------------------------------------------------------

/// 压缩结果：写库要用的元数据 + 可以直接落盘的字节。
public struct StickerGIFResult: Equatable, Sendable {
    public var data: Data
    public var width: Int
    public var height: Int
    public var frames: Int
    public var durationMillis: Int

    public init(data: Data, width: Int, height: Int, frames: Int, durationMillis: Int) {
        self.data = data
        self.width = width
        self.height = height
        self.frames = frames
        self.durationMillis = durationMillis
    }

    public var bytes: Int { data.count }

    /// 给界面/测试看的一行。
    public var displaySummary: String {
        "\(width)×\(height) · \(frames) 帧 · \(durationMillis) ms · \(data.count) B"
    }
}

/// 一帧的原始画面 + 它应该显示多久（毫秒）。
public struct StickerGIFFrame: Sendable {
    public var image: CGImage
    public var delayMillis: Int

    public init(image: CGImage, delayMillis: Int) {
        self.image = image
        self.delayMillis = max(0, delayMillis)
    }
}

public enum StickerGIF {

    // MARK: - 常量

    public static let gifType = "com.compuserve.gif"
    /// GIF 的延迟单位是 10 ms（1/100 s）；20 fps = 5 个 tick。
    static let delayTickMillis = 10
    /// 单帧静止 GIF 的显示时间（只是给播放器一个值，不影响画面）。
    static let stillFrameDelayMillis = 100
    /// 没有延迟信息时的兜底（10 fps）：WebP 的逐帧延迟 ImageIO 不总是给。
    static let defaultFrameDelayMillis = 100
    /// 超过这个尺寸就不再逐档缩了：320 → … → 80 已经够把 512 KB 压下来。
    public static let minimumLongEdge = 80

    /// 压缩字节上限的逐档退让表（先缩尺寸，再减帧、降帧率）。
    ///
    /// 顺序刻意是「尺寸优先」：表情看起来糊一点还能认，帧数砍一半会变成幻灯片。
    /// 每一项是（长边, 最多帧数, 最短帧间隔 ms）；后一档同时覆盖前一档。
    public static let qualityLadder: [(longEdge: Int, maxFrames: Int, minDelayMillis: Int)] = [
        (320, 60, 50),
        (320, 40, 50),
        (288, 40, 50),
        (256, 32, 50),
        (224, 24, 50),
        (200, 20, 50),
        (176, 16, 66),
        (152, 14, 100),
        (128, 12, 100),
        (112, 10, 150),
        (96, 8, 150),
        (80, 6, 200)
    ]

    // MARK: - 解码

    /// 读一个图片源的帧与延迟（GIF / WebP / APNG / 静态图）。
    ///
    /// **只解码最终会用到的帧**：先读全部帧的延迟（只是元数据，很便宜），用 `framePlan`
    /// 算出「取哪些帧、每帧多久」，再只解码那些帧。一个 300 帧的 GIF 不会为了做一张
    /// ≤60 帧的表情被整段解进内存（320 px × 300 帧 ≈ 120 MB）。
    ///
    /// - Parameters:
    ///   - data: 原始字节。
    ///   - maxPixelSize: 每帧解码后允许的最大长边。
    ///   - maxFrames: 最多解码几帧（默认 60，即表情上限；界面动画也用这个值）。
    /// - Returns: nil = 不是能读的图片 / 一帧都解不出来。
    public static func decodeFrames(
        data: Data,
        maxPixelSize: Int = StickerLimits.maxLongEdge,
        maxFrames: Int = StickerLimits.maxFrames
    ) -> (frames: [CGImage], delaysMillis: [Int])? {
        guard !data.isEmpty,
              let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let count = CGImageSourceGetCount(source)
        guard count > 0 else { return nil }
        let allDelays = (0..<count).map { frameDelayMillis(source: source, index: $0) }
        let plan = framePlan(delaysMillis: allDelays, maxFrames: maxFrames)
        guard !plan.indices.isEmpty else { return nil }
        let edge = max(1, maxPixelSize)
        var frames: [CGImage] = []
        var delays: [Int] = []
        frames.reserveCapacity(plan.indices.count)
        delays.reserveCapacity(plan.indices.count)
        for (position, index) in plan.indices.enumerated() {
            guard let image = frame(at: index, source: source, maxPixelSize: edge) else { continue }
            frames.append(image)
            delays.append(position < plan.delaysMillis.count
                ? plan.delaysMillis[position]
                : defaultFrameDelayMillis)
        }
        guard !frames.isEmpty else { return nil }
        return (frames, delays)
    }

    /// 单帧按目标尺寸解码（`CGImageSourceCreateThumbnailAtIndex` 会摆正 EXIF 方向）。
    static func frame(at index: Int, source: CGImageSource, maxPixelSize: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, maxPixelSize)
        ]
        if let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, index, options as CFDictionary) {
            return thumbnail
        }
        // 某些格式（个别 APNG）不提供缩略图，退回到整帧解码再缩。
        guard let image = CGImageSourceCreateImageAtIndex(source, index, nil) else { return nil }
        return ImageCompressor.resized(image, maxPixelSize: maxPixelSize) ?? image
    }

    /// 读第 index 帧的显示时间（毫秒）。
    ///
    /// GIF 用 `UnclampedDelayTime`（不被播放器夹到 100 ms 的原始值）优先，其次 `DelayTime`；
    /// APNG 用自己那套键；WebP 的逐帧延迟 ImageIO 暴露得不全，读不到就按 100 ms 兜底
    ///（后面 `framePlan` 会把总时长截到 6 s，所以兜底不会撑爆时长）。
    static func frameDelayMillis(source: CGImageSource, index: Int) -> Int {
        guard let raw = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any] else {
            return defaultFrameDelayMillis
        }
        func seconds(_ dictionaryKey: CFString, _ delayKeys: [CFString]) -> Double? {
            guard let dictionary = raw[dictionaryKey] as? [CFString: Any] else { return nil }
            for key in delayKeys {
                if let value = (dictionary[key] as? NSNumber)?.doubleValue, value.isFinite, value > 0 {
                    return value
                }
            }
            return nil
        }
        let secondsValue =
            seconds(kCGImagePropertyGIFDictionary,
                    [kCGImagePropertyGIFUnclampedDelayTime, kCGImagePropertyGIFDelayTime])
            ?? seconds(kCGImagePropertyPNGDictionary,
                       [kCGImagePropertyAPNGUnclampedDelayTime, kCGImagePropertyAPNGDelayTime])
        guard let secondsValue, secondsValue > 0 else { return defaultFrameDelayMillis }
        // 0.001 s 这种「几乎为 0」的延迟在真实文件里表示「越快越好」，按 10 ms 处理。
        return max(delayTickMillis, Int((secondsValue * 1000).rounded()))
    }

    // MARK: - 帧计划

    /// 一帧要保留谁、显示多久。
    public struct FramePlan: Equatable, Sendable {
        /// 保留的是原始帧下标（升序）。
        public var indices: [Int]
        /// 与 `indices` 一一对应的显示时间（毫秒）。
        public var delaysMillis: [Int]

        public var count: Int { indices.count }
        public var durationMillis: Int { delaysMillis.reduce(0, +) }
    }

    /// 从源延迟算出「取哪些帧、每帧显示多久」。
    ///
    /// 三步，顺序不能换：
    /// 1. **合并到最短间隔**（≤20 fps）：把连续几帧的显示时间叠在最后一帧上；
    /// 2. **截到 6 s**：从第一帧开始累加，超过上限就停在上一帧（保持原速）；
    /// 3. **帧数上限 60**：等间隔抽样，被抽掉帧的时间累加到保留帧上（总时长不变）。
    ///
    /// 静态图（`delays` 只有一项或全是 0）直接返回单帧。
    public static func framePlan(
        delaysMillis: [Int],
        maxFrames: Int = StickerLimits.maxFrames,
        minDelayMillis: Int = StickerLimits.minFrameDelayMillis,
        maxDurationMillis: Int = StickerLimits.maxDurationMillis
    ) -> FramePlan {
        guard !delaysMillis.isEmpty else { return FramePlan(indices: [], delaysMillis: []) }
        let minDelay = max(1, minDelayMillis)
        let frameCap = max(1, maxFrames)
        let durationCap = max(delayTickMillis, maxDurationMillis)

        // 1) 合并：每凑够 minDelay 就保留「这一段的最后一帧」，时间记在这一帧上。
        var mergedIndices: [Int] = []
        var mergedDelays: [Int] = []
        var accumulated = 0
        for (index, rawDelay) in delaysMillis.enumerated() {
            accumulated += max(delayTickMillis, rawDelay)
            if accumulated >= minDelay {
                mergedIndices.append(index)
                mergedDelays.append(accumulated)
                accumulated = 0
            }
        }
        // 尾巴不足一个间隔：并进最后一帧（否则会丢掉最后一点画面）。
        if accumulated > 0 {
            if mergedIndices.isEmpty {
                mergedIndices.append(delaysMillis.count - 1)
                mergedDelays.append(accumulated)
            } else {
                mergedDelays[mergedDelays.count - 1] += accumulated
            }
        }

        // 2) 截到总时长上限。
        var keptIndices: [Int] = []
        var keptDelays: [Int] = []
        var total = 0
        for (index, delay) in zip(mergedIndices, mergedDelays) {
            if total + delay > durationCap {
                // 一帧都还没有时也要给一帧，否则表情会变成空文件。
                if keptIndices.isEmpty {
                    keptIndices.append(index)
                    keptDelays.append(min(delay, durationCap))
                }
                break
            }
            keptIndices.append(index)
            keptDelays.append(delay)
            total += delay
        }

        // 3) 帧数上限：等间隔抽样，时间累加到保留帧上。
        if keptIndices.count > frameCap {
            let stride = Int(ceil(Double(keptIndices.count) / Double(frameCap)))
            var sampledIndices: [Int] = []
            var sampledDelays: [Int] = []
            for position in Swift.stride(from: 0, to: keptIndices.count, by: stride) {
                var delay = 0
                var lastIndex = position
                for offset in 0..<stride where position + offset < keptIndices.count {
                    delay += keptDelays[position + offset]
                    lastIndex = keptIndices[position + offset]
                }
                sampledIndices.append(lastIndex)
                sampledDelays.append(delay)
            }
            keptIndices = sampledIndices
            keptDelays = sampledDelays
        }

        return FramePlan(indices: keptIndices, delaysMillis: keptDelays)
    }

    // MARK: - 编码

    /// 把帧编码成 GIF89a。失败返回 nil（调用方按「这张图做不成表情」处理）。
    ///
    /// - Parameters:
    ///   - frames: 已经按目标尺寸缩好的帧。
    ///   - delaysMillis: 每帧显示时间；静态图传一个 100 ms 即可。
    ///   - loopCount: 0 = 无限循环（规格要求）。
    public static func encode(
        frames: [CGImage],
        delaysMillis: [Int],
        loopCount: Int = 0
    ) -> Data? {
        guard !frames.isEmpty else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            data as CFMutableData, gifType as CFString, frames.count, nil
        ) else { return nil }

        // 全局循环次数：0 = 无限。写在文件级属性里，播放器读一次就够。
        let fileProperties: [CFString: Any] = [
            kCGImagePropertyGIFDictionary: [
                kCGImagePropertyGIFLoopCount: max(0, loopCount)
            ]
        ]
        CGImageDestinationSetProperties(destination, fileProperties as CFDictionary)

        for (index, image) in frames.enumerated() {
            let delay = index < delaysMillis.count
                ? delaysMillis[index]
                : (frames.count > 1 ? StickerLimits.minFrameDelayMillis : stillFrameDelayMillis)
            // GIF 的延迟是百分之一秒，且 0 / 1 tick 会被浏览器当成「100 ms」；
            // 我们的下限是 50 ms（5 tick），所以这里只需规整到 tick。
            let ticks = max(1, Int((Double(delay) / Double(delayTickMillis)).rounded()))
            let frameProperties: [CFString: Any] = [
                kCGImagePropertyGIFDictionary: [
                    kCGImagePropertyGIFDelayTime: Double(ticks) * Double(delayTickMillis) / 1000.0,
                    kCGImagePropertyGIFUnclampedDelayTime: Double(ticks) * Double(delayTickMillis) / 1000.0
                ]
            ]
            CGImageDestinationAddImage(destination, image, frameProperties as CFDictionary)
        }
        guard CGImageDestinationFinalize(destination) else { return nil }
        return normalizedGIFSignature(data as Data)
    }

    /// 把 ImageIO 写的 `GIF87a` 头改成 `GIF89a`。
    ///
    /// 为什么需要：ImageIO 的 GIF 编码器在写了 Graphic Control Extension（每帧延迟）
    /// 和 NETSCAPE2.0 循环扩展（无限循环）之后，仍然把文件头写成 `GIF87a` ——
    /// 那是 87a 的语法里根本没有的东西（技术上是个「错误的版本标记」）。
    /// 规格要求输出 GIF89a，播放器也更愿意按 89a 解析动画，所以这里只改这 1 个字节：
    /// `GIF87a` → `GIF89a`。文件里没有任何 87a 独有、89a 不兼容的结构，改动是安全的。
    static func normalizedGIFSignature(_ data: Data) -> Data {
        var output = data
        guard output.count >= 6,
              String(data: output.prefix(6), encoding: .ascii) == "GIF87a" else { return output }
        output[output.startIndex + 4] = Character("9").asciiValue ?? 0x39
        return output
    }

    // MARK: - 压缩入口

    /// 压缩一段图片字节（GIF/WebP/APNG/静态图）。全部约束都在这里兜住。
    ///
    /// - Parameters:
    ///   - data: 原始字节。
    ///   - maxBytes: 体积上限（默认 512 KB；测试可以调小来验证退让表）。
    ///   - forcedLongEdge: 只给测试用：跳过自动缩放（验证字节上限退让）。
    public static func compress(
        data: Data,
        maxBytes: Int = StickerLimits.maxBytes
    ) -> StickerGIFResult? {
        guard let decoded = decodeFrames(data: data) else { return nil }
        return compress(frames: decoded.frames, delaysMillis: decoded.delaysMillis, maxBytes: maxBytes)
    }

    /// 压缩已经解码好的帧（制作表情时走这条：基底 + 文字/叠图之后仍是同一套管线上限）。
    public static func compress(
        frames: [CGImage],
        delaysMillis: [Int],
        maxBytes: Int = StickerLimits.maxBytes
    ) -> StickerGIFResult? {
        guard !frames.isEmpty else { return nil }
        let byteLimit = max(1024, maxBytes)
        let isStill = frames.count == 1
        let delays = normalizedDelays(delaysMillis, frameCount: frames.count)
        if isStill {
            return encodeStill(frame: frames[0], byteLimit: byteLimit)
        }
        for config in qualityLadder {
            let plan = framePlan(
                delaysMillis: delays,
                maxFrames: config.maxFrames,
                minDelayMillis: config.minDelayMillis
            )
            guard !plan.indices.isEmpty else { continue }
            let selected = plan.indices.compactMap { index in
                index < frames.count ? frames[index] : nil
            }
            let scaled = selected.compactMap {
                ImageCompressor.resized($0, maxPixelSize: config.longEdge)
            }
            guard scaled.count == selected.count, !scaled.isEmpty else { continue }
            let scaledDelays = Array(plan.delaysMillis.prefix(scaled.count))
            guard let encoded = encode(frames: scaled, delaysMillis: scaledDelays) else { continue }
            if encoded.count <= byteLimit {
                let first = scaled[0]
                return StickerGIFResult(
                    data: encoded,
                    width: first.width,
                    height: first.height,
                    frames: scaled.count,
                    durationMillis: scaledDelays.reduce(0, +)
                )
            }
        }
        // 最后一档（80px / 6 帧）都超限：说明输入是极端噪点图，明确失败而不是写一个超限文件。
        return nil
    }

    /// 静态图：单帧 GIF（存储仍是 GIF，展示时就是一张图）。
    static func encodeStill(frame: CGImage, byteLimit: Int) -> StickerGIFResult? {
        var edge = StickerLimits.maxLongEdge
        for _ in 0..<8 {
            guard let scaled = ImageCompressor.resized(frame, maxPixelSize: edge) else { return nil }
            if let encoded = encode(frames: [scaled], delaysMillis: [stillFrameDelayMillis]),
               encoded.count <= byteLimit {
                return StickerGIFResult(
                    data: encoded,
                    width: scaled.width,
                    height: scaled.height,
                    frames: 1,
                    durationMillis: 0
                )
            }
            let next = max(minimumLongEdge, edge * 3 / 4)
            guard next < edge else { break }
            edge = next
        }
        return nil
    }

    /// 延迟规整：数量对齐帧数；静态图忽略延迟；动图每帧至少 10 ms。
    static func normalizedDelays(_ raw: [Int], frameCount: Int) -> [Int] {
        guard frameCount > 1 else { return [stillFrameDelayMillis] }
        var result: [Int] = []
        result.reserveCapacity(frameCount)
        for index in 0..<frameCount {
            let value = index < raw.count ? raw[index] : defaultFrameDelayMillis
            result.append(max(delayTickMillis, value))
        }
        return result
    }

    // MARK: - 读回（测试与界面用）

    /// 读一个 GIF 的元数据（尺寸 / 帧数 / 总时长），不解码所有帧的像素。
    public static func inspect(data: Data) -> (width: Int, height: Int, frames: Int, durationMillis: Int)? {
        guard !data.isEmpty,
              let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let count = CGImageSourceGetCount(source)
        guard count > 0 else { return nil }
        guard let properties = CGImageSourceCopyProperties(source, nil) as? [CFString: Any] else {
            return nil
        }
        // ImageIO 对 GIF 把画布尺寸放在 GIF 字典里（`CanvasPixelWidth/Height`），
        // 顶层 `PixelWidth` 反而可能是空的 —— 两种都认。
        let gifDictionary = properties[kCGImagePropertyGIFDictionary] as? [CFString: Any]
        let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue
            ?? (gifDictionary?[kCGImagePropertyGIFCanvasPixelWidth] as? NSNumber)?.intValue
        let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue
            ?? (gifDictionary?[kCGImagePropertyGIFCanvasPixelHeight] as? NSNumber)?.intValue
        guard let width, let height, width > 0, height > 0 else { return nil }
        var duration = 0
        for index in 0..<count {
            duration += frameDelayMillis(source: source, index: index)
        }
        return (width, height, count, duration)
    }

    /// 文件头是不是 GIF89a（规格显式要求；ImageIO 在有循环扩展时会写 89a）。
    public static func isGIF89a(data: Data) -> Bool {
        data.count >= 6 && String(data: data.prefix(6), encoding: .ascii) == "GIF89a"
    }
}
