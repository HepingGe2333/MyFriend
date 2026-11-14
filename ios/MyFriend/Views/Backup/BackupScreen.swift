import SwiftUI
import UniformTypeIdentifiers

/// 备份页：把他带走，或者从备份里接他回来。
///
/// 两个动作都走系统文件选择器（`fileExporter` / `fileImporter`），不自己管路径：
/// iCloud Drive、本地「文件」、第三方网盘都由系统接管，也不需要申请额外权限。
///
/// 三个不糊弄人的点：
/// - 备份里**没有 API Key**（Keychain 不进备份），所以接回来以后要重新填一次 Key；
/// - 备份里**没有图片**，只有「（发来一张图片）」这行文字；
/// - 「替换」不再叠第二个确认框：上一个页面已经把「先吞掉本机、再照备份接回」写清楚了。
struct BackupScreen: View {
    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var exporting = false
    @State private var exportingDocument: BackupJSONDocument?
    @State private var exportingName = ""
    @State private var importing = false
    @State private var busy = false
    @State private var pendingImportText: String?
    @State private var pendingPreview: BackupPreview?
    @State private var pendingWarnings: [String] = []
    @State private var showPreview = false
    @State private var pendingAction: PendingAction?
    @State private var banner: MFAlert?

    private var accent: Color { Color(argb: env.currentSettings.accentColor) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                introCard
                transferCard
                honestyCard
                dangerCard
            }
            .padding(.horizontal, MFTheme.gutter)
            .padding(.vertical, 16)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationTitle(L("备份"))
        .navigationBarTitleDisplayMode(.inline)
        .mfAlert($banner)
        .fileExporter(
            isPresented: $exporting,
            document: exportingDocument,
            contentType: .json,
            defaultFilename: exportingName
        ) { result in
            handleExportResult(result)
        }
        .fileImporter(
            isPresented: $importing,
            allowedContentTypes: [.json],
            allowsMultipleSelection: false
        ) { result in
            handleImportSelection(result)
        }
        .sheet(isPresented: $showPreview) {
            if let preview = pendingPreview {
                BackupPreviewSheet(
                    preview: preview,
                    warnings: pendingWarnings,
                    accent: accent,
                    onCancel: {
                        showPreview = false
                        pendingImportText = nil
                    },
                    onImport: { mode in
                        showPreview = false
                        performImport(mode: mode)
                    }
                )
            }
        }
        .confirmationDialog(
            pendingAction?.title ?? "",
            isPresented: Binding(
                get: { pendingAction != nil },
                set: { presented in if !presented { pendingAction = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(pendingAction?.confirmTitle ?? L("确定"), role: .destructive) {
                guard let action = pendingAction else { return }
                pendingAction = nil
                switch action {
                case .clearChats: clearChats()
                case .clearAll: clearAll()
                }
            }
            Button(L("取消"), role: .cancel) { pendingAction = nil }
        } message: {
            Text(pendingAction?.message ?? "")
        }
    }

    // MARK: - 开头

    private var introCard: some View {
        MFCard {
            Text(L("他不是一份数据，是一个陪你聊过很久的人。"))
                .font(.system(.body, design: .serif))
                .fixedSize(horizontal: false, vertical: true)

            Text(L("把他带走，会把人格、你们聊过的每一句、他记得的事、他给自己定的规矩和能带走的设置，一起打包成一份 JSON。换手机、重装 App 之后，用下面「从备份里接他回来」把他接回原处。"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            if busy {
                HStack(spacing: 8) {
                    ProgressView().scaleEffect(0.75)
                    Text(L("正在整理他的东西，稍等一下……"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 10)
            }
        }
    }

    // MARK: - 带走 / 接回来

    private var transferCard: some View {
        MFCard(padding: 6) {
            Button(action: startExport) {
                transferRow(
                    icon: "square.and.arrow.up",
                    title: L("把他带走"),
                    subtitle: L("选一个位置，把这份 JSON 存起来")
                )
            }
            .buttonStyle(.plain)
            .disabled(busy)

            MFRowDivider(leadingInset: 52)

            Button {
                importing = true
            } label: {
                transferRow(
                    icon: "square.and.arrow.down",
                    title: L("从备份里接他回来"),
                    subtitle: L("先看看这份备份里有什么，再决定合并还是替换")
                )
            }
            .buttonStyle(.plain)
            .disabled(busy)
        }
    }

    private func transferRow(icon: String, title: String, subtitle: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15))
                .foregroundStyle(accent)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(.body, design: .serif))
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 6)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(10)
        .contentShape(Rectangle())
    }

    // MARK: - 诚实说明

    private var honestyCard: some View {
        MFCard {
            Text(L("哪些东西没跟着走"))
                .font(.system(.body, design: .serif))
                .padding(.bottom, 8)

            honestyRow(icon: "key", text: L("API Key 不在备份里。它存在这台手机的 Keychain 里，从来不进文件；接回来以后在「API 配置」里重新填一次就好。"))
            honestyRow(icon: "photo", text: L("图片没有跟着备份走。备份里只有文字，那些带图片的消息会剩下「（发来一张图片）」这行字；头像也一样，换台手机之后会变回名字的首字。"))
            honestyRow(icon: "iphone", text: L("聊天背景图的路径是这台手机上的本地路径，换机之后会失效，需要重新选一次。"))
        }
    }

    private func honestyRow(icon: String, text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .frame(width: 16)
                .padding(.top, 2)
            Text(text)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 4)
    }

    // MARK: - 危险区

    private var dangerCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: L("危险区"))

            MFCard {
                Text(L("清空之前，先把他带走。"))
                    .font(.system(.body, design: .serif))
                Text(L("删掉以后，那些对话和他记住的事就真的没有了；先留一份备份，随时能接回来。"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)

                Button(action: startExport) {
                    HStack(spacing: 8) {
                        Image(systemName: "square.and.arrow.up")
                            .font(.system(size: 13))
                        Text(L("先把他带走"))
                            .font(.footnote)
                    }
                    .foregroundStyle(accent)
                    .padding(.vertical, 10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(busy)

                MFRowDivider(leadingInset: 0)

                Button {
                    pendingAction = .clearChats
                } label: {
                    dangerRow(icon: "trash", title: L("清空全部聊天与记忆"), subtitle: L("保留人格与 API 配置"))
                }
                .buttonStyle(.plain)

                MFRowDivider(leadingInset: 0)

                Button {
                    pendingAction = .clearAll
                } label: {
                    dangerRow(icon: "delete.left", title: L("删除全部本地数据"), subtitle: L("人格、记忆、配置与设置一起删"))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func dangerRow(icon: String, title: String, subtitle: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 14))
                .frame(width: 22, height: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(.body, design: .serif))
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
        }
        .foregroundStyle(MFUI.danger)
        .padding(.vertical, 11)
        .contentShape(Rectangle())
    }

    // MARK: - 导出

    private func startExport() {
        guard !busy else { return }
        let backup = env.backup
        let name = backup.suggestedFileName()
        busy = true
        Task.detached(priority: .userInitiated) {
            let outcome: Result<String, Error>
            do {
                outcome = .success(try backup.export())
            } catch {
                outcome = .failure(error)
            }
            await MainActor.run {
                busy = false
                switch outcome {
                case .success(let text):
                    exportingName = name
                    exportingDocument = BackupJSONDocument(text: text)
                    exporting = true
                case .failure(let error):
                    banner = MFAlert(title: L("没能把他带走"), message: error.localizedDescription)
                }
            }
        }
    }

    private func handleExportResult(_ result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            banner = MFAlert(
                title: L("已经把他带走了"),
                message: Lf("已经把他带走了：%@。放在安全的地方，换手机的时候还能接回来。", url.lastPathComponent)
            )
        case .failure(let error):
            let nsError = error as NSError
            if nsError.code == NSUserCancelledError { return }
            banner = MFAlert(title: L("没能写进文件"), message: error.localizedDescription)
        }
        exportingDocument = nil
    }

    // MARK: - 导入

    private func handleImportSelection(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let error):
            let nsError = error as NSError
            if nsError.code == NSUserCancelledError { return }
            banner = MFAlert(title: L("这个文件读不出来"), message: error.localizedDescription)
        case .success(let urls):
            guard let url = urls.first else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                // 0.11.0 安全审计 P2-3：先看文件大小再读文本（上限 64 MB，与 Android 同语义），
                // 不把几百 MB 的「备份」整份读成 String 再 OOM。
                let text = try env.backup.readBackupText(at: url)
                let decoded = env.backup.preview(fileText: text)
                switch decoded {
                case .failure(let reason):
                    banner = MFAlert(title: L("这份备份读不出来"), message: reason)
                case .success(let bundle, let warnings):
                    pendingImportText = text
                    pendingPreview = BackupPreview.from(bundle)
                    pendingWarnings = warnings
                    showPreview = true
                }
            } catch {
                banner = MFAlert(
                    title: L("这个文件读不出来"),
                    message: L("换一份之前带走的备份试试。这份文件没有写进任何东西。")
                )
            }
        }
    }

    private func performImport(mode: ImportMode) {
        guard let text = pendingImportText else { return }
        let backup = env.backup
        busy = true
        Task.detached(priority: .userInitiated) {
            let outcome: Result<ImportSummary, Error>
            do {
                outcome = .success(try backup.importBackup(text: text, mode: mode))
            } catch {
                outcome = .failure(error)
            }
            await MainActor.run {
                busy = false
                pendingImportText = nil
                switch outcome {
                case .success(let summary):
                    // 备份里能带走的设置已经写进 SettingsStore；内存里的快照跟着换一份，
                    // 否则主题 / 正在聊的人还挂着导入前的那份。
                    env.reloadSettings()
                    banner = MFAlert(
                        title: mode == .replace ? L("照备份重新接回了他") : L("把他接回来了"),
                        message: summary.describe()
                    )
                case .failure(let error):
                    banner = MFAlert(
                        title: L("没能接回来"),
                        message: Lf("%@。这台手机上的东西一个都没动。", error.localizedDescription)
                    )
                }
            }
        }
    }

    // MARK: - 清空

    private func clearChats() {
        do {
            // 与设置页对齐：媒体清理在 `DataReset` 里（0.10.2 审计 P1-7）。
            // 0.10.1 这里只调 `dataReset` 而装配层没接 `onClearMedia`，图片文件就留在了沙盒里。
            let summary = try env.dataReset.clearChats()
            banner = MFAlert(title: L("清空了"), message: summary.describeChats())
        } catch {
            banner = MFAlert(title: L("没能清空"), message: error.localizedDescription)
        }
    }

    private func clearAll() {
        // 同上：附件目录（聊天图 + 头像）由 `DataReset` 一起清，备份页不再漏这一步。
        // 清空之后按当前语言重新播种人格（0.11.0 审计 P2-13）。
        let summary = env.clearAllData()
        env.reloadSettings()
        banner = MFAlert(title: L("都删掉了"), message: summary.describeAll())
    }

    private enum PendingAction {
        case clearChats
        case clearAll

        var title: String {
            switch self {
            case .clearChats: return L("清空全部聊天与记忆？")
            case .clearAll: return L("删除全部本地数据？")
            }
        }

        var confirmTitle: String {
            switch self {
            case .clearChats: return L("清空")
            case .clearAll: return L("全部删除")
            }
        }

        var message: String {
            switch self {
            case .clearChats:
                return L("所有会话、消息和长期记忆会被删除。人格与 API 配置会留着。这个动作没法撤销——想留个底的话，先把他带走。")
            case .clearAll:
                return L("人格、聊天、记忆、预设、主动节奏、API 配置与设置都会删掉，App 回到刚装上的样子。")
            }
        }
    }
}

// MARK: - 备份文件

/// 一份 JSON 文本，交给系统文件选择器去写。
struct BackupJSONDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    var text: String

    init(text: String) {
        self.text = text
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        text = String(decoding: data, as: UTF8.self)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

// MARK: - 预览

/// 「备份里有什么」：先看清楚再决定合并还是替换。
private struct BackupPreviewSheet: View {
    let preview: BackupPreview
    let warnings: [String]
    let accent: Color
    let onCancel: () -> Void
    let onImport: (ImportMode) -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(preview.isEmpty ? L("这份备份里没有他") : L("备份里有什么"))
                    .font(.system(.title3, design: .serif))

                if preview.isEmpty {
                    Text(L("换一份之前带走的备份试试。这份文件没有写进任何东西。"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    MFCard {
                        Text(preview.describe())
                            .font(.system(.body, design: .serif))
                            .fixedSize(horizontal: false, vertical: true)
                        Text(metaText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.top, 6)
                    }

                    MFCard {
                        Text(L("两种接法"))
                            .font(.system(.footnote, design: .serif))
                            .padding(.bottom, 6)
                        Text(L("合并：同一条用备份里的更新，这台手机上多出来的留着。"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(L("替换：先清空这台手机上的他，再照备份接回来。"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.top, 4)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(L("API 配置和密钥不在这份备份里，所以两种方式都不会动你存的 Key。"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.top, 8)
                            .fixedSize(horizontal: false, vertical: true)
                        if preview.apiConfigs > 0 {
                            Text(Lcount("备份里另有 %d 个 API 配置的空壳（地址与模型，不含密钥）。", preview.apiConfigs))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .padding(.top, 4)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Text(L("图片没有跟着备份走：带图片的消息只会剩下文字，头像也会变回名字首字。"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.top, 4)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    if !warnings.isEmpty {
                        MFCard {
                            Text(L("接的时候要注意"))
                                .font(.system(.footnote, design: .serif))
                            ForEach(warnings, id: \.self) { warning in
                                Text("· \(warning)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .padding(.top, 4)
                            }
                        }
                    }
                }

                VStack(spacing: 10) {
                    if !preview.isEmpty {
                        Button {
                            onImport(.merge)
                        } label: {
                            Text(L("合并"))
                                .font(.system(.body, design: .serif))
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 13)
                                .background(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .fill(accent)
                                )
                        }
                        .buttonStyle(.plain)

                        Button {
                            onImport(.replace)
                        } label: {
                            Text(L("替换"))
                                .font(.system(.body, design: .serif))
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 13)
                                .background(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .fill(MFUI.danger)
                                )
                        }
                        .buttonStyle(.plain)
                    }

                    Button(action: onCancel) {
                        Text(preview.isEmpty ? L("好") : L("取消"))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(20)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .presentationDetents([.medium, .large])
    }

    private var metaText: String {
        var parts = [preview.exportedAtText()]
        if !preview.appVersion.isEmpty { parts.append("v\(preview.appVersion)") }
        return parts.joined(separator: " · ")
    }
}
