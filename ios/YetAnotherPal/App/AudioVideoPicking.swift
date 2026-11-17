import AVFoundation
import PhotosUI
import SwiftUI

// ---------------------------------------------------------------------------
// 语音录制与视频选择（0.13，`spec/audio-video/README.md`）。
//
// 与图片那条链路（`ImagePicking.swift`）并列：
//
//   按住麦克风 → AVAudioRecorder（AAC 单声道 16 kHz、.m4a）→ 松开 → 落盘 → 发送
//   点视频按钮 → PHPicker（.videos）→ 读时长/体积 → 不转码、原样拷进沙盒 → 发送
//
// 三条硬约束（都在 `AudioVideoLimits` 里，这里是它的 UI 侧执行者）：
// - 语音 ≤5 分钟（到点自动停，不等用户松手）、≤8 MB；
// - 视频 ≤2 分钟、≤64 MB，**不转码**（转码既慢又改变画质，规格明确要求原样）；
// - 超限就拒绝并说清原因，不做「悄悄压一下再发」。
// ---------------------------------------------------------------------------

/// 按住说话的录音机。
///
/// 为什么自己管一个 `AVAudioRecorder` 而不是用系统 `SFSpeechRecognizer`：
/// 我们要的是**声音本身**（模型真的听），不是转写文本。录音格式与上限全部对齐
/// `AudioVideoLimits`，两条链路（本地文件 / 服务端上传）拿到的是同一个 `.m4a`。
@MainActor
final class VoiceRecorder: NSObject, ObservableObject {

    /// 正在录（界面据此把输入栏换成「松开发送 / 上滑取消」）。
    @Published private(set) var isRecording = false
    /// 已经录了多久（毫秒），界面显示 `0:07`。
    @Published private(set) var elapsedMs = 0

    private var recorder: AVAudioRecorder?
    private var timer: Timer?
    private var fileURL: URL?
    /// 到 5 分钟自动停：由 `AudioVideoLimits.shouldAutoStop` 判定。
    var onAutoStop: (() -> Void)?

    /// 开始录：先要麦克风权限，再配好 iOS 的录音会话（播放与录音共用，否则录出来是一段静音）。
    ///
    /// 注意：`NSMicrophoneUsageDescription` 必须写在 Info.plist 里，否则系统会在第一次
    /// 录音时直接终止 App。仓库里的 `Config/Info.plist` 目前**没有**这一条（那不在本次
    /// 改动范围内），所以这里先把权限问清楚、失败时给一句人话，而不是让用户看到闪退。
    func start() async throws {
        guard !isRecording else { return }
        try await ensurePermission()
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: AudioVideoLimits.audioSampleRate,
            AVNumberOfChannelsKey: AudioVideoLimits.audioChannels,
            AVEncoderBitRateKey: AudioVideoLimits.audioBitRate,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue
        ]
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth]
        )
        try session.setActive(true)
        #endif
        // 临时文件：录完立刻搬进 `Application Support/audio`，这里只是录音器的出口。
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice-\(UUID().uuidString).m4a", isDirectory: false)
        let recorder = try AVAudioRecorder(url: url, settings: settings)
        recorder.isMeteringEnabled = true
        recorder.delegate = self
        guard recorder.record() else {
            throw AudioVideoStore.StorageError.writeFailed
        }
        self.recorder = recorder
        self.fileURL = url
        self.elapsedMs = 0
        self.isRecording = true
        startTimer()
    }

    /// 松手：停止并返回（临时文件 URL、时长毫秒）。
    ///
    /// 太短（<0.6 秒）当作误触：返回 nil 并删掉临时文件，免得发出一段「咔」的噪音。
    func stop() -> (url: URL, durationMs: Int)? {
        guard let recorder, isRecording else { return nil }
        let seconds = recorder.currentTime
        recorder.stop()
        stopTimer()
        isRecording = false
        self.recorder = nil
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
        guard let fileURL else { return nil }
        let durationMs = Int((seconds * 1000).rounded())
        guard durationMs >= Self.minimumDurationMs else {
            try? FileManager.default.removeItem(at: fileURL)
            self.fileURL = nil
            return nil
        }
        self.fileURL = nil
        return (fileURL, durationMs)
    }

    /// 取消（上滑/离开页面）：删掉临时文件，什么都不留。
    func cancel() {
        recorder?.stop()
        recorder = nil
        stopTimer()
        isRecording = false
        elapsedMs = 0
        if let fileURL { try? FileManager.default.removeItem(at: fileURL) }
        fileURL = nil
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }

    /// 误触阈值：小于它的录音没有意义（也是一次「点了一下」而不是「按住说话」）。
    static let minimumDurationMs = 600

    /// 没有麦克风权限（或用户拒绝过）。
    struct PermissionDenied: LocalizedError {
        var errorDescription: String? { L("没有麦克风权限，去系统设置里允许一下") }
    }

    private func ensurePermission() async throws {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted:
            return
        case .denied:
            throw PermissionDenied()
        case .undetermined:
            let granted = await withCheckedContinuation { continuation in
                session.requestRecordPermission { allowed in
                    continuation.resume(returning: allowed)
                }
            }
            guard granted else { throw PermissionDenied() }
        @unknown default:
            throw PermissionDenied()
        }
        #endif
    }

    private func startTimer() {
        stopTimer()
        let timer = Timer(timeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let recorder = self.recorder, self.isRecording else { return }
                self.elapsedMs = Int((recorder.currentTime * 1000).rounded())
                if AudioVideoLimits.shouldAutoStop(elapsedMs: self.elapsedMs) {
                    self.onAutoStop?()
                }
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }
}

extension VoiceRecorder: AVAudioRecorderDelegate {
    nonisolated func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        guard flag else { return }
    }
}

// MARK: - 落盘

/// 录音与视频的落盘：都走 `AudioVideoStore`（`Application Support/audio|video`），
/// 与 Kit 里别的附件同一条「原子写 + 归属守卫」。
enum AudioVideoImport {

    enum Outcome {
        case success(MessageAttachment)
        case failure(String)
    }

    /// 录音（ASR 不需要、也不做：交给模型听）。
    static func saveRecording(at url: URL, durationMs: Int) async -> Outcome {
        let store = AudioVideoStore.shared
        return await Task.detached(priority: .userInitiated) { () -> Outcome in
            guard let data = try? Data(contentsOf: url), !data.isEmpty else {
                return .failure(L("这段录音读不出来，再录一次"))
            }
            try? FileManager.default.removeItem(at: url)
            do {
                let attachment = try store.saveAudio(data: data, durationMs: durationMs)
                return .success(attachment)
            } catch {
                return .failure(message(for: error))
            }
        }.value
    }

    /// 视频：**不转码**，按源文件字节与时长直接判上限，然后原样拷贝。
    static func importVideo(_ item: PhotosPickerItem) async -> Outcome {
        guard let url = try? await item.loadTransferable(type: URL.self), url.isFileURL else {
            return .failure(L("读不出这个视频，换一个试试"))
        }
        let durationMs = await videoDurationMs(url)
        let mime = Self.mime(forFileURL: url)
        let store = AudioVideoStore.shared
        return await Task.detached(priority: .userInitiated) { () -> Outcome in
            do {
                let attachment = try store.importVideo(
                    at: url, durationMs: durationMs ?? 0, mime: mime
                )
                return .success(attachment)
            } catch {
                return .failure(message(for: error))
            }
        }.value
    }

    /// 视频时长（毫秒）；读不出来返回 nil（交给体积上限兜底）。
    private static func videoDurationMs(_ url: URL) async -> Int? {
        let asset = AVURLAsset(url: url)
        guard let duration = try? await asset.load(.duration) else { return nil }
        let seconds = CMTimeGetSeconds(duration)
        guard seconds.isFinite, seconds > 0 else { return nil }
        return Int((seconds * 1000).rounded())
    }

    /// 按扩展名给 mime（不转码：`.mov` 就是 `video/quicktime`）。
    static func mime(forFileURL url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "mov", "qt": return "video/quicktime"
        default: return AudioVideoLimits.videoMime
        }
    }

    private static func message(for error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return L("这个文件没能存到本机")
    }
}
