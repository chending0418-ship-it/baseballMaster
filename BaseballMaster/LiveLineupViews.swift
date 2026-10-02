import SwiftUI

/// Edits stay local until the entire lineup is validated and saved as one action.
struct LiveLineupEditor: View {
    @EnvironmentObject private var store: GameStore
    @State var draft: LiveLineupDraft
    let onCancel: () -> Void
    let onSaved: () -> Void
    @State private var editingPlayer: Player?
    @State private var replacement: LineupReplacement?
    @State private var saved = false
    @State private var confirmExit = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("姓名与号码更正同时更新球队名单。排序保留当前／下一位打者及球数；换人接替原棒次和守位，退场球员可再次上场。")
                        .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                }
                if saved && !draft.hasChanges {
                    Section { Label("已保存，可继续调整或点完成返回", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(BMTheme.green).accessibilityIdentifier("lineup-saved") }
                }
                Section("打序 · 长按右侧把手拖动") {
                    ForEach(Array(draft.battingOrderIDs.enumerated()), id: \.element) { index, id in
                        if let player = draft.player(id) {
                            HStack(spacing: 6) {
                                Text("\(index + 1)").font(.headline.monospacedDigit())
                                    .frame(width: 26)
                                playerMenu(player)
                                Spacer(minLength: 0)
                                Button { draft.moveBatter(from: index, to: index - 1) } label: {
                                    Image(systemName: "arrow.up").frame(width: 40, height: 44)
                                }
                                .buttonStyle(.borderless).disabled(index == 0)
                                .accessibilityLabel("\(player.name) 棒次前移")
                                .accessibilityIdentifier("lineup-up-\(index + 1)")
                                Button { draft.moveBatter(from: index, to: index + 1) } label: {
                                    Image(systemName: "arrow.down").frame(width: 40, height: 44)
                                }
                                .buttonStyle(.borderless).disabled(index == draft.battingOrderIDs.count - 1)
                                .accessibilityLabel("\(player.name) 棒次后移")
                                .accessibilityIdentifier("lineup-down-\(index + 1)")
                            }
                        }
                    }
                    .onMove { offsets, destination in draft.moveBatters(from: offsets, to: destination) }
                }
                let defenseOnly = draft.fieldingIDs.filter { !draft.battingOrderIDs.contains($0) }
                if !defenseOnly.isEmpty {
                    Section("只参与守备") {
                        ForEach(defenseOnly, id: \.self) { id in
                            if let player = draft.player(id) { playerMenu(player) }
                        }
                    }
                }
                Section("替补 · 包括已换下球员") {
                    if draft.bench.isEmpty { Text("暂无可替换球员").foregroundStyle(.secondary) }
                    ForEach(draft.bench) { player in
                        Button { editingPlayer = player } label: { playerLabel(player, active: false) }
                            .accessibilityIdentifier("lineup-bench-\(player.id)")
                    }
                }
                if draft.hasChanges {
                    Section("待保存的调整") {
                        ForEach(Array(draft.changes.enumerated()), id: \.offset) { _, change in
                            Text(change).font(.footnote)
                        }
                    }
                }
                if let issue = draft.validationMessage {
                    Section { Text(issue).foregroundStyle(BMTheme.orange) }
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("\(draft.team.shortName) · 阵容")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(draft.hasChanges ? "返回" : "完成") {
                        if draft.hasChanges { confirmExit = true } else { onCancel() }
                    }.accessibilityIdentifier("lineup-finish")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存", action: save)
                        .disabled(!draft.hasChanges || draft.validationMessage != nil)
                        .accessibilityIdentifier("lineup-save")
                }
            }
            .sheet(item: $editingPlayer) { player in
                MatchPlayerProfileEditor(player: player) { chinese, english, numbers in
                    draft.updateProfile(playerID: player.id, chineseName: chinese, englishName: english, numberTexts: numbers)
                }
            }
            .sheet(item: $replacement) { selection in
                NavigationStack {
                    List {
                        Section {
                            Text(selection.pitchingOnly ? "新投手接管投球，原投手继续担任 DH。" : "新球员接管原球员的棒次、守位和垒位。")
                                .font(.footnote)
                        }
                        ForEach(draft.replacementCandidates(for: selection.player.id)) { player in
                            Button {
                                if draft.replace(selection.player.id, with: player.id, pitchingOnly: selection.pitchingOnly) {
                                    replacement = nil
                                }
                            } label: { playerLabel(player, active: false) }
                                .accessibilityIdentifier("lineup-replace-with-\(player.id)")
                        }
                        if draft.replacementCandidates(for: selection.player.id).isEmpty { Text("暂无可替换球员") }
                    }
                    .navigationTitle("替换 \(selection.player.name)")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { replacement = nil } } }
                }
            }
            .interactiveDismissDisabled(draft.hasChanges)
            .confirmationDialog("还有未保存的阵容调整", isPresented: $confirmExit, titleVisibility: .visible) {
                Button("保存并返回") { if saveDraft() { onCancel() } }
                    .disabled(draft.validationMessage != nil)
                Button("放弃调整并返回", role: .destructive, action: onCancel)
                Button("继续调整", role: .cancel) {}
            }
            .gameActionErrorAlert(store)
        }
    }

    private func save() { _ = saveDraft() }

    @discardableResult
    private func saveDraft() -> Bool {
        guard store.saveLineup(draft) else { return false }
        draft = store.lineupDraft(forHomeTeam: draft.isHomeTeam)
        saved = true
        onSaved()
        return true
    }

    private func playerLabel(_ player: Player, active: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(player.numbersText) \(player.name)").font(.subheadline.bold())
                .foregroundStyle(BMTheme.navy)
            if active {
                Text(role(for: player)).font(.caption).foregroundStyle(BMTheme.green)
            }
        }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
    }

    private func role(for player: Player) -> String {
        var roles: [String] = []
        if draft.fieldingIDs.contains(player.id) { roles.append(player.primaryPosition.fullName) }
        if draft.designatedHitterID == player.id { roles.append("DH") }
        if draft.batterAnchorID == player.id {
            roles.append(draft.source.isTop != draft.isHomeTeam ? "当前打者" : "下一位打者")
        }
        if let base = Base.allCases.first(where: { draft.runners[$0]?.id == player.id }) { roles.append(base.title + "跑者") }
        return roles.joined(separator: " · ")
    }

    private func playerMenu(_ player: Player) -> some View {
        Menu {
            Button("更正姓名／号码") { editingPlayer = player }
            Button("换人（接替棒次与守位）") { replacement = LineupReplacement(player: player, pitchingOnly: false) }
            if draft.allowsTwoWayPlayer, player.id == draft.pitcherID, player.id == draft.designatedHitterID {
                Button("只换投手（原投手继续 DH）") { replacement = LineupReplacement(player: player, pitchingOnly: true) }
            }
            if draft.fieldingIDs.contains(player.id) {
                Menu("调整守位") {
                    ForEach(FieldPosition.allCases) { position in
                        Button(position.fullName) { draft.changePosition(playerID: player.id, to: position) }
                    }
                }
            }
        } label: { playerLabel(player, active: true) }
            .accessibilityIdentifier("lineup-player-\(player.id)")
    }
}

private struct LineupReplacement: Identifiable {
    let id = UUID()
    let player: Player
    let pitchingOnly: Bool
}

private struct MatchPlayerProfileEditor: View {
    @Environment(\.dismiss) private var dismiss
    let player: Player
    let onSave: (String, String, [String]) -> Bool
    @State private var chineseName: String
    @State private var englishName: String
    @State private var numbers: String

    init(player: Player, onSave: @escaping (String, String, [String]) -> Bool) {
        self.player = player
        self.onSave = onSave
        _chineseName = State(initialValue: player.chineseName)
        _englishName = State(initialValue: player.englishName)
        _numbers = State(initialValue: player.numberTexts.joined(separator: ", "))
    }
    private var numberTexts: [String] {
        numbers.replacingOccurrences(of: "，", with: ",").components(separatedBy: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
    }
    var body: some View {
        NavigationStack {
            Form {
                Section("球员资料 · 同步球队名单") {
                    TextField("中文名", text: $chineseName).accessibilityIdentifier("lineup-chinese-name")
                    TextField("英文名", text: $englishName).accessibilityIdentifier("lineup-english-name")
                    TextField("背号", text: $numbers).accessibilityIdentifier("lineup-numbers")
                    Text("支持 0、00、1–99；多个背号用逗号分隔。姓名可留空，沿用号码显示。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section {
                    Text("保留同一球员的全部本场统计。已有逐球文字保留原文，保存阵容时同时更新球队名单，以后新建比赛使用更正后的资料。")
                        .font(.footnote)
                }
            }
            .navigationTitle("更正球员资料")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { if onSave(chineseName, englishName, numberTexts) { dismiss() } }
                        .disabled(!Player.validNumberTexts(numberTexts))
                        .accessibilityIdentifier("lineup-profile-done")
                }
            }
        }
    }
}

/// Only the runners who are out need to be identified when a double/triple play ends the half.
struct HalfEndingOutsSheet: View {
    @Environment(\.dismiss) private var dismiss
    let runners: [RunnerDecision]
    let requiredOuts: Int
    let onConfirm: (Set<UUID>) -> Void
    @State private var selected: Set<UUID> = []

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("打者已出局，再选择 \(requiredOuts) 名实际出局的跑者。确认后交换攻守，无需安排其余跑者的垒位。")
                }
                ForEach(runners) { runner in
                    Button {
                        if !selected.insert(runner.player.id).inserted { selected.remove(runner.player.id) }
                    } label: {
                        HStack {
                            Text("\(runner.origin.title) · \(runner.player.compactName)")
                            Spacer()
                            Image(systemName: selected.contains(runner.player.id) ? "checkmark.circle.fill" : "circle")
                        }.frame(minHeight: 44)
                    }
                    .accessibilityIdentifier("terminal-out-\(runner.player.id)")
                }
            }
            .navigationTitle("确认出局者")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("交换攻守") { onConfirm(selected) }
                        .disabled(selected.count != requiredOuts)
                        .accessibilityIdentifier("confirm-terminal-outs")
                }
            }
        }
    }
}

struct HalfEndingRunnerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let runners: [RunnerDecision]
    let allowsScoring: Bool
    let onConfirm: (UUID, Set<UUID>) -> Void
    @State private var outID: UUID?
    @State private var scorerIDs: Set<UUID> = []

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(allowsScoring
                         ? "选择第三出局的跑者。如同球有人回本垒，勾选后确认得分先后；其余跑者无需安排垒位。"
                         : "选择被封杀的跑者，形成第三出局后直接交换攻守。")
                        .font(.footnote)
                }
                Section("谁出局了？") {
                    ForEach(runners) { runner in
                        Button {
                            outID = runner.player.id
                            scorerIDs.remove(runner.player.id)
                        } label: {
                            HStack {
                                Text("\(runner.origin.title) · \(runner.player.compactName)")
                                Spacer()
                                Image(systemName: outID == runner.player.id ? "checkmark.circle.fill" : "circle")
                            }.frame(minHeight: 44)
                        }
                        .accessibilityIdentifier("third-out-runner-\(runner.player.id)")
                    }
                }
                if allowsScoring, outID != nil, runners.count > 1 {
                    Section("同球回本垒的跑者（如有）") {
                        ForEach(runners.filter { $0.player.id != outID }) { runner in
                            Toggle(runner.player.compactName, isOn: Binding(
                                get: { scorerIDs.contains(runner.player.id) },
                                set: { if $0 { scorerIDs.insert(runner.player.id) } else { scorerIDs.remove(runner.player.id) } }
                            ))
                        }
                    }
                }
            }
            .navigationTitle("第三出局")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(scorerIDs.isEmpty ? "交换攻守" : "确认得分先后") {
                        if let outID { onConfirm(outID, scorerIDs) }
                    }
                    .disabled(outID == nil)
                    .accessibilityIdentifier("confirm-third-out-runner")
                }
            }
        }
    }
}
