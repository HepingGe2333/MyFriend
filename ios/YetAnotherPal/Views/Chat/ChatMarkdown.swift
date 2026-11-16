import SwiftUI
import UIKit

/// 气泡里的 Markdown 排版。
///
/// 分工：切块交给 `MarkdownParser`、行内样式交给 `InlineMarkdown`（这两条规则与 Android 端同源，
/// 所以同一段回复在两端长得一样）；这个文件只回答「每一块怎么画」。
///
/// 三条刻意的取舍：
/// - **不做选字**：长按要给消息菜单（复制 / 重新生成 / 删除），系统选字工具条会跟它抢长按；
///   Android 端为了菜单干净也关掉了选字，两端对齐。局部复制留给代码块右上角的按钮。
/// - **链接走 `openURL` 拦截**：SwiftUI 没法给 `Text` 里的某一段加 `NavigationLink`，
///   所以链接用 `AttributedString.link`，页面在 `openURL` 里把它换成内置浏览器的 push。
/// - **数学块用可读文本**：`Latex.toReadable` 把 `$…$` / `$$…$$` 转成 Unicode，
///   这里只负责「衬线斜体、居中偏左」的观感，不做 TeX 排版。
struct ChatMarkdownView: View {

    let text: String
    let isOutgoing: Bool
    let onOpenLink: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(MarkdownParser.parse(text).enumerated()), id: \.offset) { _, block in
                blockView(block)
            }
        }
        // 气泡宽度由内容决定：短句就一小块，长段落/代码/引用自然撑开（与 Android 的 widthIn 一致）。
        .frame(alignment: .leading)
    }

    // MARK: - 块

    @ViewBuilder
    private func blockView(_ block: MarkdownBlock) -> some View {
        switch block {
        case .paragraph(let raw):
            ChatInlineText(raw: raw, isOutgoing: isOutgoing, font: .body, onOpenLink: onOpenLink)

        case .heading(let level, let raw):
            ChatInlineText(
                raw: raw,
                isOutgoing: isOutgoing,
                font: .system(headingStyle(level), weight: .semibold),
                onOpenLink: onOpenLink
            )
            .padding(.top, 2)

        case .codeBlock(let language, let code):
            ChatCodeBlockView(language: language, code: code, isOutgoing: isOutgoing)

        case .quote(let raw):
            HStack(alignment: .top, spacing: 0) {
                Rectangle()
                    .fill(MFTheme.defaultAccent.opacity(isOutgoing ? 0.9 : 0.7))
                    .frame(width: 3)
                ChatInlineText(
                    raw: raw,
                    isOutgoing: isOutgoing,
                    font: .callout,
                    italic: true,
                    onOpenLink: onOpenLink
                )
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
            }
            .fixedSize(horizontal: false, vertical: true)
            .background(panelBackground)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

        case .bullets(let items):
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("•")
                            .font(.body)
                            .foregroundStyle(textColor.opacity(0.7))
                        ChatInlineText(raw: item, isOutgoing: isOutgoing, font: .body, onOpenLink: onOpenLink)
                    }
                }
            }

        case .ordered(let items):
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("\(index + 1).")
                            .font(.callout)
                            .foregroundStyle(textColor.opacity(0.65))
                        ChatInlineText(raw: item, isOutgoing: isOutgoing, font: .body, onOpenLink: onOpenLink)
                    }
                }
            }

        case .divider:
            Rectangle()
                .fill(textColor.opacity(0.16))
                .frame(height: 1)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 2)

        case .mathBlock(let latex):
            Text(Latex.toReadable(latex))
                .font(.system(.body, design: .serif).italic())
                .foregroundStyle(textColor)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(panelBackground)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }

    // MARK: - 颜色

    private var textColor: Color {
        isOutgoing ? .white : .primary
    }

    /// 引用块 / 公式块 / 代码块的底：出向气泡上用白色薄纱，收到的气泡上用一层灰。
    private var panelBackground: Color {
        isOutgoing ? Color.white.opacity(0.10) : Color.primary.opacity(0.055)
    }

    private func headingStyle(_ level: Int) -> Font.TextStyle {
        switch level {
        case 1: return .title2
        case 2: return .title3
        case 3: return .headline
        default: return .subheadline
        }
    }
}

// MARK: - 行内

/// 一段行内文本：把 `InlineMarkdown` 切出来的 span 拼成 `AttributedString` 再交给一个 `Text`。
///
/// 为什么拼成一整个 `Text` 而不是一行 `Text` 拼接：整段才是一段，换行、对齐、行距才正常；
/// 链接的点击由 `Text` 自己处理（`AttributedString.link` → `openURL`）。
struct ChatInlineText: View {

    let raw: String
    let isOutgoing: Bool
    var font: Font = .body
    var italic: Bool = false
    let onOpenLink: (String) -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Text(attributed)
            .font(italic ? font.italic() : font)
            .foregroundStyle(isOutgoing ? Color.white : Color.primary)
            .lineSpacing(3)
            .fixedSize(horizontal: false, vertical: true)
            // 点链接：在应用里打开（内置浏览器），不跳系统浏览器。
            .environment(\.openURL, OpenURLAction { url in
                onOpenLink(url.absoluteString)
                return .handled
            })
    }

    private var attributed: AttributedString {
        var result = AttributedString()
        // 链接蓝在三种底色上要分别够亮：强调色气泡上用近白蓝，深色气泡上用亮一档的蓝，
        // 浅色气泡上才是主题里那支 `#2C7DBE`。
        let linkColor: Color
        if isOutgoing {
            linkColor = Color(hex: 0xE6, 0xF2, 0xFF)
        } else if scheme == .dark {
            linkColor = Color(hex: 0x6F, 0xB2, 0xE8)
        } else {
            linkColor = MFTheme.link
        }
        let codeBackground: Color = isOutgoing
            ? Color.white.opacity(0.18)
            : Color.primary.opacity(scheme == .dark ? 0.16 : 0.10)

        for span in InlineMarkdown.parse(raw) {
            guard !span.text.isEmpty else { continue }
            var piece = AttributedString(span.text)

            if span.isBold || span.isItalic || span.isCode {
                var intent = InlinePresentationIntent()
                if span.isBold { intent.insert(.stronglyEmphasized) }
                if span.isItalic { intent.insert(.emphasized) }
                if span.isCode { intent.insert(.code) }
                piece.inlinePresentationIntent = intent
            }
            if span.isStrikethrough { piece.swiftUI.strikethroughStyle = .single }
            if span.isUnderlined { piece.swiftUI.underlineStyle = .single }
            if span.isCode { piece.swiftUI.backgroundColor = codeBackground }
            if span.isMath { piece.swiftUI.font = .system(.body, design: .serif).italic() }

            if let raw = span.url, let url = URL(string: raw), LinkDetector.normalize(raw) != nil {
                // `.link` 属于 Foundation 的属性作用域（SwiftUI 作用域没有它），
                // 颜色与下划线仍然用 SwiftUI 属性，点按由 `openURL` 接管。
                piece.link = url
                piece.swiftUI.foregroundColor = linkColor
                piece.swiftUI.underlineStyle = .single
            }
            result.append(piece)
        }
        return result
    }
}

// MARK: - 代码块

/// 代码块：等宽字体 + 横向滚动 + 右上角复制。
///
/// 横向滚动而不是折行：代码折行之后就不像代码了；"嵌套三级"的缩进一折就全乱。
struct ChatCodeBlockView: View {

    let language: String
    let code: String
    let isOutgoing: Bool

    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                Text(language.isEmpty ? "code" : language.lowercased())
                    .font(.caption2)
                    .foregroundStyle(tint.opacity(0.6))
                    .lineLimit(1)
                Spacer(minLength: 8)
                Button(action: copy) {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(tint.opacity(copied ? 0.9 : 0.6))
                        .frame(width: 30, height: 26)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(copied ? L("已复制") : L("复制代码")))
            }
            ScrollView(.horizontal, showsIndicators: false) {
                Text(code)
                    .font(.system(.footnote, design: .monospaced))
                    .foregroundStyle(tint.opacity(0.95))
                    .fixedSize(horizontal: true, vertical: false)
                    .padding(.bottom, 8)
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, 6)
        .padding(.top, 2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(isOutgoing ? Color.white.opacity(0.10) : Color.primary.opacity(0.055))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var tint: Color {
        isOutgoing ? .white : .primary
    }

    private func copy() {
        UIPasteboard.general.string = code
        copied = true
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            copied = false
        }
    }
}
