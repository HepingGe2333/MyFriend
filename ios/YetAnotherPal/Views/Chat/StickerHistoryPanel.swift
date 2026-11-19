import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// 输入栏表情按钮打开的历史面板（`spec/stickers/README.md` §发送与历史）。
///
/// 两档：**最近 30 个**（默认）与**全部**；4 列网格，点选即发送（不进入文字草稿）。
/// 右下角两个导入入口：
/// - 相册（`PhotosPicker`，系统进程读图，不要相册权限）；
/// - 文件（`.fileImporter`，用来选 GIF / WebP / APNG —— 相册里有些动图会被系统转成静态图）。
///
/// 导入一律走 `StickerService.importSticker` → 同一条压缩管线（≤320 px / ≤60 帧 / ≤6 s / ≤512 KB）。
struct StickerHistoryPanel: View {

    /// 点选一个表情：调用方负责发送并关掉面板。
    let onPick: (StickerEntity) -> Void

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var stickers: [StickerEntity] = []
    @State private var showAll = false
    @State private var pickedItems: [PhotosPickerItem] = []
    @State private var importing = false
    @State private var showFileImporter = false
    /// 导入/删除失败时弹的那一句。删除失败时表情**保留**在面板里，不做乐观移除。
    @State private var errorTitle: String = L("表情没能导入")
    @State private var errorText: String?

    private var visibleStickers: [StickerEntity] {
        showAll ? stickers : Array(stickers.prefix(StickerLimits.recentLimit))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $showAll) {
                    Text(L("最近")).tag(false)
                    Text(L("全部")).tag(true)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 6)
                .accessibilityLabel(Text(L("表情范围")))

                if visibleStickers.isEmpty {
                    emptyState
                } else {
                    grid
                }

                importBar
            }
            .background(MFTheme.canvasLight)
            .navigationTitle(Text(L("我的表情")))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("完成")) { dismiss() }
                }
            }
        }
        .task { await reload() }
        .onChange(of: pickedItems) { items in
            importPicked(items)
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: [.image],
            allowsMultipleSelection: false
        ) { result in
            importFile(result)
        }
        .alert(
            errorTitle,
            isPresented: Binding(
                get: { errorText != nil },
                set: { if !$0 { errorText = nil } }
            )
        ) {
            Button(L("好"), role: .cancel) { errorText = nil }
        } message: {
            Text(errorText ?? "")
        }
    }

    // MARK: - 网格

    private var grid: some View {
        ScrollView {
            LazyVGrid(
                columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 4),
                spacing: 10
            ) {
                ForEach(visibleStickers, id: \.id) { sticker in
                    Button {
                        onPick(sticker)
                        dismiss()
                    } label: {
                        StickerBubbleView(
                            attachment: env.stickers.attachment(for: sticker),
                            maxPointSize: 72,
                            animated: false
                        )
                        .frame(maxWidth: .infinity)
                        .padding(6)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(Color.primary.opacity(0.04))
                        )
                        .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button(role: .destructive) {
                            delete(sticker)
                        } label: {
                            Label(L("删掉这个表情"), systemImage: "trash")
                        }
                    }
                    .accessibilityLabel(Text(L("发送这个表情")))
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .overlay(alignment: .center) {
            if importing {
                ProgressView(L("正在导入…"))
                    .padding(14)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Spacer(minLength: 0)
            Image(systemName: "face.smiling")
                .font(.system(size: 38, weight: .light))
                .foregroundStyle(.secondary)
            Text(L("还没有表情。点下面的按钮导入一张图片或 GIF 吧。"))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 30)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - 导入

    private var importBar: some View {
        HStack(spacing: 12) {
            PhotosPicker(
                selection: $pickedItems,
                maxSelectionCount: 1,
                matching: .images,
                photoLibrary: .shared()
            ) {
                Label(L("从相册导入"), systemImage: "photo.on.rectangle")
                    .font(.subheadline.weight(.medium))
            }
            .disabled(importing)

            Button {
                showFileImporter = true
            } label: {
                Label(L("从文件导入 GIF"), systemImage: "doc.badge.plus")
                    .font(.subheadline.weight(.medium))
            }
            .disabled(importing)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.bar)
    }

    private func importPicked(_ items: [PhotosPickerItem]) {
        guard let item = items.first else { return }
        pickedItems = []
        startImporting()
        Task { @MainActor in
            do {
                guard let data = try await item.loadTransferable(type: Data.self) else {
                    throw StickerError.unreadableImage
                }
                _ = try await env.stickers.importSticker(data: data)
                importing = false
                await reload()
            } catch {
                importing = false
                errorText = message(for: error)
            }
        }
    }

    private func importFile(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let error):
            errorText = message(for: error)
        case .success(let urls):
            guard let url = urls.first else { return }
            startImporting()
            Task { @MainActor in
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    _ = try await env.stickers.importSticker(at: url)
                    importing = false
                    await reload()
                } catch {
                    importing = false
                    errorText = message(for: error)
                }
            }
        }
    }

    private func startImporting() {
        importing = true
        errorTitle = L("表情没能导入")
        errorText = nil
    }

    private func reload() async {
        stickers = (try? env.stickers.all()) ?? []
    }

    /// 删除一个表情（长按 → 上下文菜单）。
    ///
    /// 规范 `spec/stickers/README.md` §发送与历史 + 0.14.1：
    /// - 服务端模式先 `DELETE /v1/stickers/{id}`（失败不能假装删掉了）；
    /// - 本地模式删行 + 文件（文件仍被聊天消息引用时只删行）；
    /// - 成功后**立刻**重新读面板，表情从「最近 / 全部」与发送列表消失；
    /// - 失败时弹一句明确的原因并**保留**这个表情。
    private func delete(_ sticker: StickerEntity) {
        Task { @MainActor in
            do {
                _ = try await env.stickers.delete(id: sticker.id)
                await reload()
            } catch {
                errorTitle = L("表情没能删除")
                errorText = message(for: error)
            }
        }
    }

    private func message(for error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        let text = (error as NSError).localizedDescription
        return text.isEmpty ? errorTitle : text
    }
}

// MARK: - 主题色（面板自己用，不污染 MFTheme）

private extension MFTheme {
    /// 面板底：比聊天气泡更浅一点，深色模式下也是卡片感（`canvas` 是聊天页底色）。
    static var canvasLight: Color {
        Color(uiColor: .systemGroupedBackground)
    }
}
