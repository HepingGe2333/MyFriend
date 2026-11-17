import CoreGraphics
import Foundation

#if canImport(AVFoundation)
import AVFoundation
#endif

// ---------------------------------------------------------------------------
// 视频关键帧（0.13）：模型「看视频」靠的是抽出来的几帧静止画面。
//
// 策略（`spec/audio-video/README.md`）：
// - 最多 8 帧；
// - 每帧长边 ≤1024 px 的 JPEG；
// - 全部帧加起来 ≤8 MB；
// - 时长未知时按「约 2 秒一帧」估。
//
// 为什么不做场景检测：`AVAssetImageGenerator` 的同步接口只能按时间点取帧，
// 真做场景变化要解码整条时间轴（几十秒的 4K 视频会把内存与时间都吃掉）。
// 「均匀时间采样」在聊天这个场景里已经够用（对方发的是日常片段，不是电影），
// 而且行为可预测、可单测（`frameTimes` 是纯函数）。
// ---------------------------------------------------------------------------

/// 抽帧策略：全部是纯函数/常量，单测直接断言（不必真的解一段视频）。
public enum VideoFramePolicy {

    /// 一次最多抽几帧。
    public static let maxFrames = 8
    /// 每帧长边上限（px）。
    public static let maxPixel = 1024
    /// 所有帧的原始字节预算（8 MiB）。
    public static let maxTotalBytes = 8 * 1024 * 1024
    /// 每帧大约隔几秒取一张（时长未知时也按它估帧数）。
    public static let secondsPerFrame: Double = 2
    /// JPEG 质量阶梯（体积超预算时逐档降）。
    public static let jpegQualities: [Double] = [0.72, 0.62, 0.52, 0.42]

    /// 这段时长抽几帧：约 2 秒一帧，至少 1 帧、最多 8 帧。
    public static func frameCount(durationSeconds: Double?) -> Int {
        guard let durationSeconds, durationSeconds > 0 else { return maxFrames }
        let estimated = Int((durationSeconds / secondsPerFrame).rounded(.up))
        return min(maxFrames, max(1, estimated))
    }

    /// 均匀采样点（秒）：每一段的**中点**，避开首尾黑帧。
    public static func frameTimes(durationSeconds: Double, count: Int) -> [Double] {
        guard count > 0 else { return [] }
        guard durationSeconds > 0 else {
            // 时长未知：按「2 秒一帧」给一串时间点，让播放器/生成器自己取最近的一帧。
            return (0..<count).map { Double($0) * secondsPerFrame + secondsPerFrame / 2 }
        }
        let last = max(0, durationSeconds - 0.05)
        return (0..<count).map { index in
            let position = (Double(index) + 0.5) / Double(count)
            return min(last, max(0, durationSeconds * position))
        }
    }

    /// 时长未知时的估计值（按帧数倒推），只用于那句「时长 X 秒」。
    public static func estimatedDuration(_ frameCount: Int) -> Double {
        Double(max(frameCount, 1)) * secondsPerFrame
    }

    /// 给模型看的那一行：`视频：8 帧，时长 12.0 秒`。
    public static func note(frameCount: Int, durationSeconds: Double) -> String {
        String(
            format: String(localized: "视频：%d 帧，时长 %.1f 秒"),
            max(frameCount, 0), max(durationSeconds, 0)
        )
    }

    /// 体积预算：超了就从**最后**一帧开始丢（开头那几帧最能代表「这条视频是什么」）。
    public static func budgeted(_ frames: [Data], budget: Int = maxTotalBytes) -> [Data] {
        guard budget > 0 else { return [] }
        var kept: [Data] = []
        var total = 0
        for frame in frames {
            guard total + frame.count <= budget else { break }
            kept.append(frame)
            total += frame.count
        }
        return kept
    }

    /// 单帧 JPEG 的字节预算（按帧数均分 8 MB，至少给 64 KB）。
    public static func perFrameBudget(frameCount: Int) -> Int {
        guard frameCount > 0 else { return maxTotalBytes }
        return max(64 * 1024, maxTotalBytes / frameCount)
    }

    /// 把一帧编码成 JPEG：从高到低试质量阶梯，取第一张落进单帧预算的。
    public static func encodeFrame(_ image: CGImage, maxBytes: Int) -> Data? {
        for quality in jpegQualities {
            guard let data = ImageCompressor.encodeJPEG(image, quality: quality) else { continue }
            if data.count <= maxBytes { return data }
        }
        return ImageCompressor.encodeJPEG(image, quality: jpegQualities.last ?? 0.42)
    }

    /// 抽帧并转成 data URL（`MediaPartsBuilding` 用的就是它）。
    public static func jpegDataURLs(
        for fileURL: URL,
        durationSeconds: Double?,
        extractor: VideoFrameExtracting
    ) async -> [String] {
        let count = frameCount(durationSeconds: durationSeconds)
        let frames = await extractor.frames(of: fileURL, count: count, maxPixel: maxPixel)
        let kept = budgeted(frames)
        return kept.map { "data:image/jpeg;base64,\($0.base64EncodedString())" }
    }
}

/// 「从视频里抽几帧 JPEG」的抽象（生产用 `AVVideoFrameExtractor`；测试注入假实现）。
public protocol VideoFrameExtracting: AnyObject {
    /// - Parameters:
    ///   - url: 本机视频文件。
    ///   - count: 最多几帧（已经按 `VideoFramePolicy` 算好）。
    ///   - maxPixel: 每帧长边上限。
    /// - Returns: JPEG 字节（压缩失败的那一帧直接跳过，不抛）。
    func frames(of url: URL, count: Int, maxPixel: Int) async -> [Data]

    /// 视频首帧（视频气泡的封面）。
    func firstFrameJPEG(of url: URL, maxPixel: Int) async -> Data?
}

#if canImport(AVFoundation)
/// 真实现：`AVAssetImageGenerator` 按时间点取帧 → JPEG。
///
/// 只依赖 AVFoundation（macOS / iOS 都有），所以 `swift build` 能编过；
/// 单测不碰它（不要求仓库里带视频样片），策略层已经由 `VideoFramePolicy` 钉住。
public final class AVVideoFrameExtractor: VideoFrameExtracting {

    public init() {}

    public func frames(of url: URL, count: Int, maxPixel: Int) async -> [Data] {
        guard count > 0 else { return [] }
        let asset = AVURLAsset(url: url)
        let duration = await Self.durationSeconds(of: asset)
        let times = VideoFramePolicy.frameTimes(durationSeconds: duration ?? 0, count: count)
        let generator = Self.makeGenerator(asset: asset, maxPixel: maxPixel)
        let budget = VideoFramePolicy.perFrameBudget(frameCount: times.count)
        var result: [Data] = []
        for seconds in times {
            guard let image = try? generator.copyCGImage(
                at: CMTime(seconds: seconds, preferredTimescale: 600), actualTime: nil
            ) else { continue }
            guard let data = VideoFramePolicy.encodeFrame(image, maxBytes: budget) else { continue }
            result.append(data)
        }
        return result
    }

    public func firstFrameJPEG(of url: URL, maxPixel: Int) async -> Data? {
        let asset = AVURLAsset(url: url)
        let generator = Self.makeGenerator(asset: asset, maxPixel: maxPixel)
        let duration = await Self.durationSeconds(of: asset) ?? 0
        let seconds = min(0.2, max(0, duration / 2))
        guard let image = try? generator.copyCGImage(
            at: CMTime(seconds: seconds, preferredTimescale: 600), actualTime: nil
        ) else { return nil }
        return VideoFramePolicy.encodeFrame(
            image, maxBytes: VideoFramePolicy.maxTotalBytes
        )
    }

    private static func makeGenerator(asset: AVAsset, maxPixel: Int) -> AVAssetImageGenerator {
        let generator = AVAssetImageGenerator(asset: asset)
        // 手机拍的视频带旋转矩阵：不摆正的话，竖屏视频的帧会躺着。
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxPixel, height: maxPixel)
        generator.requestedTimeToleranceBefore = CMTime(seconds: 0.2, preferredTimescale: 600)
        generator.requestedTimeToleranceAfter = CMTime(seconds: 0.2, preferredTimescale: 600)
        return generator
    }

    private static func durationSeconds(of asset: AVAsset) async -> Double? {
        guard let duration = try? await asset.load(.duration) else { return nil }
        let seconds = CMTimeGetSeconds(duration)
        guard seconds.isFinite, seconds > 0 else { return nil }
        return seconds
    }
}
#endif
