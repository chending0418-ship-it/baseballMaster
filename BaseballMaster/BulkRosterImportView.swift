import SwiftUI
import UIKit

enum BulkRosterImportOutcome {
    case success(Int)
    case failure(String)
}

/// A shared preview/confirmation surface. The destination owns the atomic save.
struct BulkRosterImportSheet: View {
    @Environment(\.dismiss) private var dismiss
    let teamName: String
    var existingPlayers: [Player] = []
    var initialInput = ""
    var reviewMessage: String? = nil
    var refreshReview: (@MainActor () async -> Void)? = nil
    let importPlayers: @MainActor (String) async -> BulkRosterImportOutcome
    @State private var input = ""
    @State private var preview: BulkRosterParseResult?
    @State private var reviewedInput: String?
    @State private var saving = false
    @State private var attemptedImport = false
    @State private var failure: String?
    @State private var importedCount: Int?
    @FocusState private var inputFocused: Bool

    private let example = "姓名,英文名,背号\n陈昊,Hao Chen,12\n周子墨,,00"
    private var overlaps: Int {
        (preview?.rows ?? []).filter { row in
            existingPlayers.contains { player in
                let sameName = (!row.chineseName.isEmpty && player.chineseName == row.chineseName)
                    || (!row.englishName.isEmpty && player.englishName.caseInsensitiveCompare(row.englishName) == .orderedSame)
                return sameName || (!row.numberText.isEmpty && player.numberTexts.contains(row.numberText))
            }
        }.count
    }

    var body: some View {
        NavigationStack {
            Form {
                if let count = importedCount {
                    Section {
                        Label("已添加 \(count) 名队员", systemImage: "checkmark.circle.fill")
                            .font(.headline).foregroundStyle(BMTheme.green)
                            .accessibilityIdentifier("bulk-roster-success")
                        Text("已加入\(teamName)的名单。原有队员与历史比赛保持原样。")
                    }
                } else {
                    if let reviewMessage {
                        Section("共享名单") {
                            Text(reviewMessage)
                            if let refreshReview {
                                Button("刷新名单并重新核对") {
                                    inputFocused = false
                                    saving = true
                                    Task { @MainActor in
                                        await refreshReview()
                                        preview = nil; reviewedInput = nil; failure = nil
                                        saving = false
                                    }
                                }
                                .disabled(saving)
                                .accessibilityIdentifier("refresh-bulk-roster-review")
                            }
                        }
                    }
                    Section("输入格式") {
                        Text("每行一名队员：姓名,英文名,背号")
                        DisclosureGroup("查看格式示例") {
                            Text(example).font(.system(.callout, design: .monospaced)).textSelection(.enabled)
                            Button("复制格式示例") { UIPasteboard.general.string = example }
                        }
                        Text("英文名和背号可留空，逗号仍需保留。支持中文或英文逗号；背号为 0–99 或 00，每批最多 200 人。")
                            .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                    }
                    Section("粘贴名单 · \(teamName)") {
                        TextEditor(text: $input)
                            .font(.system(.body, design: .monospaced))
                            .frame(minHeight: 180)
                            .autocorrectionDisabled().textInputAutocapitalization(.never)
                            .focused($inputFocused)
                            .accessibilityLabel("批量队员名单")
                            .accessibilityIdentifier("bulk-roster-input")
                            .disabled(saving)
                    }
                    if let preview {
                        if !preview.issues.isEmpty {
                            Section("请先修改，再预览") {
                                ForEach(preview.issues) { issue in
                                    Text(issue.lineNumber > 0 ? "第 \(issue.lineNumber) 行：\(issue.message)" : issue.message)
                                        .foregroundStyle(BMTheme.red)
                                        .accessibilityIdentifier("bulk-roster-error")
                                }
                            }
                        }
                        if !preview.warnings.isEmpty || overlaps > 0 {
                            Section("请核对") {
                                ForEach(preview.warnings) { issue in Text(issue.message) }
                                if overlaps > 0 {
                                    Text("其中 \(overlaps) 行与现有名单同名或同背号。确认后仍会新增独立队员，请核对是否重复粘贴。")
                                }
                            }.foregroundStyle(BMTheme.orange)
                        }
                        Section("预览 · \(preview.rows.count) 名队员") {
                            ForEach(preview.rows) { row in
                                HStack {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(row.chineseName.isEmpty ? row.englishName : row.chineseName)
                                        if !row.chineseName.isEmpty && !row.englishName.isEmpty {
                                            Text(row.englishName).font(.caption).foregroundStyle(BMTheme.secondaryText)
                                        }
                                    }
                                    Spacer()
                                    Text(row.numberText.isEmpty ? "无背号" : "#" + row.numberText)
                                        .monospacedDigit().foregroundStyle(BMTheme.green)
                                }
                            }
                        }
                        Section {
                            Text("只新增名单，不覆盖现有队员或改写历史比赛。确认前，预览或取消不会新增队员。")
                                .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                        }
                    }
                    if let failure {
                        Section { Text(failure).foregroundStyle(BMTheme.red) }
                            .accessibilityIdentifier("bulk-roster-save-error")
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if importedCount == nil {
                    if let preview {
                        Button(saving ? "正在添加…" : "确认添加 \(preview.rows.count) 名队员") { confirm() }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(saving || !preview.canImport || reviewedInput != input)
                            .accessibilityIdentifier("confirm-bulk-roster")
                            .padding(12).background(BMTheme.surface)
                    } else {
                        Button("预览名单") { previewInput() }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(saving || input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("preview-bulk-roster")
                        .padding(12).background(BMTheme.surface)
                    }
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("批量输入名单").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if importedCount == nil {
                        Button(attemptedImport ? "关闭" : "取消") { dismiss() }.disabled(saving)
                            .accessibilityIdentifier("cancel-bulk-roster")
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if importedCount != nil {
                        Button("完成") { dismiss() }.accessibilityIdentifier("close-bulk-roster")
                    }
                }
                ToolbarItemGroup(placement: .keyboard) {
                    if preview == nil && importedCount == nil {
                        Button("预览名单") { previewInput() }
                            .disabled(saving || input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            .accessibilityIdentifier("keyboard-preview-bulk-roster")
                    }
                    Spacer()
                    Button("收起键盘") { inputFocused = false }
                }
            }
            .interactiveDismissDisabled(saving)
            .onAppear { if input.isEmpty && preview == nil { input = initialInput } }
            .onChange(of: input) { _ in preview = nil; reviewedInput = nil; failure = nil }
        }
    }

    private func previewInput() {
        inputFocused = false
        preview = BulkRosterParser.parse(input)
        reviewedInput = input
        failure = nil
    }

    private func confirm() {
        guard !saving, preview?.canImport == true, reviewedInput == input else { return }
        inputFocused = false
        saving = true
        attemptedImport = true
        let snapshot = input
        Task { @MainActor in
            await Task.yield()
            switch await importPlayers(snapshot) {
            case .success(let count): importedCount = count
            case .failure(let message): failure = message
            }
            saving = false
        }
    }
}

struct LocalBulkRosterImportSheet: View {
    @EnvironmentObject private var store: GameStore
    let teamID: UUID
    var isOpponent = false
    private var team: Team? { isOpponent ? store.opponentTeam(withID: teamID) : store.team(withID: teamID) }

    var body: some View {
        BulkRosterImportSheet(teamName: team?.name ?? "球队", existingPlayers: team?.players ?? []) { input in
            if let ids = store.bulkAddPlayers(to: teamID, input: input, isOpponent: isOpponent) {
                return .success(ids.count)
            }
            return .failure(store.actionErrorMessage ?? store.storageErrorMessage ?? "名单未保存，输入已保留，请重试。")
        }
    }
}
