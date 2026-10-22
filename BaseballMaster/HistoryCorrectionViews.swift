import SwiftUI

enum HistoryCorrectionKind: String, CaseIterable, Identifiable {
    case substitution = "漏记换人", position = "漏记换守位", situation = "漏记局面", error = "漏记失误"
    var id: String { rawValue }
    var symbol: String {
        switch self { case .substitution: "person.2.badge.gearshape"; case .position: "arrow.triangle.swap"; case .situation: "baseball.diamond.bases"; case .error: "exclamationmark.triangle" }
    }
    var detail: String {
        switch self { case .substitution: "换投 · 代打 · 代跑 · 守备替补"; case .position: "恢复当时守位，核对后续守备责任"; case .situation: "补漏球、跑垒或打席，修正原有结果"; case .error: "安打改失误，或保留安打补记失误" }
    }
}

private struct HistoryEditTarget: Identifiable {
    let id = UUID()
    var index: Int
    var replacing: Bool
    var state: GameState
    var original: HistoryOperation?
}

struct HistoryCorrectionView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss
    @State private var draft: HistoryCorrectionDraft?
    @State private var preview: HistoryCorrectionPreview?
    @State private var kind: HistoryCorrectionKind?
    @State private var target: HistoryEditTarget?
    @State private var issue: String?
    @State private var showPreview = false
    @State private var showDiscard = false
    @State private var dirty = false
    @State private var showRevisions = false
    @State private var selectedHalf = "全部局次"

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    intro
                    if let conflict = preview?.conflict {
                        Label(conflict, systemImage: "exclamationmark.triangle.fill")
                            .font(.subheadline).foregroundStyle(BMTheme.orange).padding(14)
                            .background(BMTheme.orangeSoft).clipShape(RoundedRectangle(cornerRadius: 12))
                            .accessibilityIdentifier("history-conflict")
                    }
                    if let id = preview?.conflictID, let op = draft?.journal.operations.first(where: { $0.id == id }) {
                        Button("定位到冲突局次") { selectedHalf = op.location; if kind == nil { kind = .situation } }
                    }
                    if let kind {
                        HStack {
                            Label(kind.rawValue, systemImage: kind.symbol).font(.title3.bold())
                            Spacer()
                            Button("更换类型") { self.kind = nil }
                        }
                        timeline
                    } else {
                        ForEach(HistoryCorrectionKind.allCases) { item in
                            Button { kind = item } label: {
                                HStack(spacing: 14) {
                                    Image(systemName: item.symbol).font(.title2).frame(width: 35)
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(item.rawValue).font(.headline)
                                        Text(item.detail).font(.caption).foregroundStyle(BMTheme.secondaryText)
                                    }
                                    Spacer(minLength: 0)
                                    Image(systemName: "chevron.right").font(.caption.bold())
                                }.padding(18).frame(maxWidth: .infinity, alignment: .leading)
                                    .background(BMTheme.surface).clipShape(RoundedRectangle(cornerRadius: 16))
                            }.buttonStyle(.plain).disabled(draft == nil)
                                .accessibilityIdentifier("history-kind-\(item.id)")
                        }
                    }
                    if let issue { Text(issue).foregroundStyle(BMTheme.orange).font(.subheadline) }
                    if !(store.game.correctionRevisions ?? []).isEmpty {
                        Button { showRevisions = true } label: {
                            Label("更正历史 · \(store.game.correctionRevisions?.count ?? 0) 次", systemImage: "clock.arrow.circlepath")
                        }.padding(.vertical, 8)
                    }
                }.padding(18)
            }.bmScreenBackground()
                .navigationTitle("纠正记录").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("关闭") { if dirty { showDiscard = true } else { dismiss() } } }
                }
                .safeAreaInset(edge: .bottom) {
                    Button { showPreview = true } label: { Label("预览后续影响", systemImage: "list.bullet.clipboard") }
                        .buttonStyle(PrimaryButtonStyle()).disabled(!dirty || draft == nil).opacity(dirty ? 1 : 0.45)
                        .accessibilityIdentifier("history-preview").padding(16).background(BMTheme.surface)
                }
                .onAppear { if draft == nil { load() } }
                .interactiveDismissDisabled(dirty)
                .confirmationDialog("放弃这份未保存的更正？", isPresented: $showDiscard, titleVisibility: .visible) {
                    Button("放弃草稿", role: .destructive) { dismiss() }
                    Button("继续编辑", role: .cancel) {}
                }
                .sheet(item: $target) { selected in
                    if let stored = store.activeStoredGame, let kind {
                        HistoryOperationEditor(state: selected.state, stored: stored, kind: kind, original: selected.original) { command, errors, addedPlayer, ruling in
                            guard var value = draft else { return }
                            if let addedPlayer { value.addedPlayers.append(addedPlayer); value.addedPlayersHome[addedPlayer.id] = selected.state.isTop == (kind != .substitution || command.isDefensiveSubstitution) }
                            var op = selected.original ?? HistoryOperation(command: command, before: selected.state, timestamp: selected.original?.timestamp ?? anchorDate(selected.index), inserted: true)
                            op.command = command; op.title = command.title; op.errors = errors; op.statisticsRuling = ruling
                            op.context = HistoryContext(selected.state); op.acceptsNewContext = true; op.correctedAt = Date()
                            if selected.replacing { op.edited = true; value.journal.operations[selected.index] = op }
                            else { value.journal.operations.insert(op, at: selected.index) }
                            value.reason = kind.rawValue
                            draft = value; dirty = true; recalculate()
                        }
                    }
                }
                .sheet(isPresented: $showPreview) { previewSheet }
                .sheet(isPresented: $showRevisions) { revisionSheet }
        }
    }

    private var intro: some View {
        BMCard {
            VStack(alignment: .leading, spacing: 8) {
                Text("把遗漏补回发生的位置").font(.title3.bold())
                Text("先选择类型，再定位具体事件。更正保留在草稿中，核对后续局面与统计后一起保存。")
                    .font(.subheadline).foregroundStyle(BMTheme.secondaryText)
                Label(store.game.isFinal ? "赛后更正 · 保持比赛结束状态" : "历史草稿 · 现场记分保持原样", systemImage: "lock.shield")
                    .font(.caption.bold()).foregroundStyle(BMTheme.green)
                if let note = draft?.compatibilityNote { Text(note).font(.caption).foregroundStyle(BMTheme.orange) }
                if draft?.journal.legacyPrefix == true {
                    Text("此比赛含旧版记录：仅可从已保存的可靠检查点起重算；更早过程不能猜测。")
                        .font(.caption).foregroundStyle(BMTheme.orange)
                }
            }
        }
    }

    @ViewBuilder private var timeline: some View {
        if let draft {
            Text("选择事件边界。修改已记的结果请用“修改此条”，避免重复记一次打席。")
                .font(.caption).foregroundStyle(BMTheme.secondaryText)
            if draft.journal.operations.isEmpty, let state = try? JSONDecoder().decode(GameState.self, from: draft.journal.checkpoint) {
                historyContext(state)
                Button("从此局面补录") { target = HistoryEditTarget(index: 0, replacing: false, state: state) }
                    .buttonStyle(PrimaryButtonStyle())
            }
            Picker("定位局次", selection: $selectedHalf) {
                Text("全部局次").tag("全部局次")
                ForEach(draft.journal.operations.map(\.location).reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }, id: \.self) { Text($0).tag($0) }
            }.pickerStyle(.menu)
            LazyVStack(spacing: 12) {
                ForEach(Array(draft.journal.operations.enumerated()).filter { selectedHalf == "全部局次" || $0.element.location == selectedHalf }, id: \.element.id) { index, op in
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text("\(op.location) · \(appearanceLabel(op)) · 第 \(op.context.batterIndex + 1) 棒").font(.caption.bold()).foregroundStyle(BMTheme.green)
                            Spacer()
                            if op.inserted || op.edited { Text(op.inserted ? "已补录" : "已修改").font(.caption.bold()).foregroundStyle(BMTheme.orange) }
                            if preview?.conflictID == op.id { Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(BMTheme.orange) }
                        }
                        Text(op.title).font(.subheadline.bold())
                        if let state = preview?.statesBefore[op.id] {
                            historyContext(state)
                            HStack {
                                Button("此前补录") { target = HistoryEditTarget(index: index, replacing: false, state: state) }
                                Spacer()
                                Button("修改此条") { target = HistoryEditTarget(index: index, replacing: true, state: state, original: op) }
                                Spacer()
                                Menu {
                                    Button("此后补录") { if let after = afterState(index) { target = HistoryEditTarget(index: index + 1, replacing: false, state: after) } }
                                        .disabled(afterState(index) == nil)
                                    Button("确认属于预览中的局面") { mutate(index) { $0.acceptsNewContext = true; $0.edited = true } }
                                    if index > 0 { Button("向前移动一条") { move(index, to: index - 1) } }
                                    if index + 1 < draft.journal.operations.count { Button("向后移动一条") { move(index, to: index + 1) } }
                                    Button("删除误录／重复记录", role: .destructive) {
                                        self.draft?.journal.operations.remove(at: index); dirty = true; recalculate()
                                    }
                                } label: { Image(systemName: "ellipsis.circle").frame(minWidth: 32, minHeight: 32) }
                            }.font(.subheadline).buttonStyle(.borderless)
                        } else {
                            Text("先处理前面的冲突，再核对此条局面").font(.caption).foregroundStyle(BMTheme.secondaryText)
                        }
                    }.padding(14).background(BMTheme.surface).clipShape(RoundedRectangle(cornerRadius: 14))
                        .accessibilityIdentifier("history-operation-\(index)")
                }
            }
        }
    }

    private func appearanceLabel(_ op: HistoryOperation) -> String {
        let side = op.context.isTop ? "客队" : "主队"
        guard let id = preview?.statesBefore[op.id]?.currentPlateAppearanceID,
              let index = preview?.game.plateAppearances?.filter({ $0.isTop == op.context.isTop }).firstIndex(where: { $0.id == id }) else { return side }
        return "\(side)第 \(index + 1) 打席"
    }

    private func historyContext(_ state: GameState) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(state.awayTeam.shortName) \(state.awayScore) : \(state.homeScore) \(state.homeTeam.shortName)  ·  B \(state.balls) / S \(state.strikes) / O \(state.outs)")
                .monospacedDigit()
            Text("\(state.isTop ? "客队" : "主队") · 打者 \(state.currentBatter.compactName)   投手 \(state.currentPitcher.compactName)")
            Text(Base.allCases.map { "\($0.title)：\(state.baseRunners[$0]?.compactName ?? "空")" }.joined(separator: "  "))
            DisclosureGroup("当时守备阵容") {
                ForEach(state.fieldingTeam.players.filter { state.fieldingPlayerIDs.contains($0.id) }.sorted { $0.primaryPosition.rawValue < $1.primaryPosition.rawValue }) { p in
                    Text("\(p.primaryPosition.fullName) · \(p.compactName)").frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }.font(.caption).foregroundStyle(BMTheme.secondaryText)
    }

    private var previewSheet: some View {
        NavigationStack {
            List {
                Section("保存前核对") {
                    if let value = preview {
                        if let conflict = value.conflict { Label(conflict, systemImage: "exclamationmark.triangle.fill").foregroundStyle(BMTheme.orange) }
                        ForEach(Array(value.changes.enumerated()), id: \.offset) { _, change in Text(change) }
                        if value.game.statisticsIncomplete == true { Text("含过程或责任待确认：结果页及导出会保留此标记。").foregroundStyle(BMTheme.orange) }
                    }
                }
                Section("更正原因") { TextField("填写原因", text: Binding(get: { draft?.reason ?? "" }, set: { draft?.reason = $0 })) }
                if let issue { Section { Text(issue).foregroundStyle(BMTheme.orange) } }
                Section {
                    Button("保存更正") {
                        guard let draft else { return }
                        if store.saveCorrection(draft) { dismiss() }
                        else { issue = store.actionErrorMessage ?? "保存失败，草稿仍保留，请重试。" }
                    }.disabled(preview?.canSave != true).accessibilityIdentifier("history-save")
                    Text("事件、局面与统计一起保存；保存成功后才会更新直播。已结束比赛保持结束。").font(.caption)
                }
            }.navigationTitle("预览后续影响").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("返回草稿") { showPreview = false } } }
        }
    }

    private var revisionSheet: some View {
        NavigationStack {
            List {
                ForEach((store.game.correctionRevisions ?? []).reversed()) { revision in
                    Section {
                        Text(revision.reason).font(.headline)
                        Text(revision.date.formatted()).font(.caption)
                        ForEach(Array(revision.summary.enumerated()), id: \.offset) { _, text in Text(text).font(.subheadline) }
                        if revision.id == store.game.correctionRevisions?.last?.id {
                            Button("撤回这次更正，先预览") {
                                do { draft = try store.draftRevertingCorrection(revision); dirty = true; kind = .situation; recalculate(); showRevisions = false; showPreview = true }
                                catch { issue = error.localizedDescription }
                            }
                        }
                    }
                }
            }.navigationTitle("更正历史").toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { showRevisions = false } } }
        }
    }

    private func load() { do { draft = try store.makeCorrectionDraft(); recalculate() } catch { issue = error.localizedDescription } }
    private func recalculate() { if let draft { preview = store.previewCorrection(draft) } }
    private func anchorDate(_ index: Int) -> Date {
        guard let ops = draft?.journal.operations, !ops.isEmpty else { return store.game.playLog.last?.timestamp ?? Date() }
        return ops[min(index, ops.count - 1)].timestamp
    }
    private func afterState(_ index: Int) -> GameState? {
        guard let draft, let preview else { return nil }
        if index + 1 < draft.journal.operations.count { return preview.statesBefore[draft.journal.operations[index + 1].id] }
        return preview.canSave ? preview.game : nil
    }
    private func mutate(_ index: Int, body: (inout HistoryOperation) -> Void) { body(&draft!.journal.operations[index]); dirty = true; recalculate() }
    private func move(_ index: Int, to target: Int) { let op = draft!.journal.operations.remove(at: index); draft!.journal.operations.insert(op, at: target); dirty = true; recalculate() }
}

private extension HistoryCommand {
    var isDefensiveSubstitution: Bool { switch self { case .pitcher, .fielder, .position, .lineup: true; default: false } }
}

private struct HistoryOperationEditor: View {
    @Environment(\.dismiss) private var dismiss
    let state: GameState
    let stored: StoredGame
    let kind: HistoryCorrectionKind
    let original: HistoryOperation?
    let onSave: (HistoryCommand, [HistoryErrorCredit], Player?, HistoryStatisticsRuling?) -> Void
    @State private var mode = "换投"
    @State private var incoming: UUID?
    @State private var outgoing: UUID?
    @State private var base = Base.first
    @State private var positions: [UUID: FieldPosition] = [:]
    @State private var outcome = PlayOutcome.single
    @State private var pitch = PitchAction.ball
    @State private var runnerKind = RunnerEventKind.stolenBase
    @State private var moves: [RunnerDecision] = []
    @State private var defense: DefensivePlay?
    @State private var errorPosition = FieldPosition.shortstop
    @State private var errors: [HistoryErrorCredit] = []
    @State private var timing: Bool?
    @State private var supplementalNote = ""
    @State private var situation: HistorySituation?
    @State private var reviewedStats = false
    @State private var rbi = 0
    @State private var earned: [UUID: Int] = [:]
    @State private var unearned: Set<UUID> = []
    @State private var newName = ""
    @State private var newNumber = ""
    @State private var newPlayer: Player?
    @State private var issue: String?
    private var worker: GameStore { let value = GameStore(persistenceURL: nil); return value.historyWorker(stored, state: state) }
    private var fielders: [Player] { state.fieldingTeam.players.filter { state.fieldingPlayerIDs.contains($0.id) } }
    private var incomingPlayers: [Player] {
        let players = mode == "换投" ? worker.availablePitchers : (mode == "守备替补" ? worker.fieldingBenchPlayers : worker.battingBenchPlayers)
        return players + (newPlayer.map { [$0] } ?? [])
    }
    var body: some View {
        NavigationStack {
            Form {
                Section("正在更正历史 · 第 \(state.inning) 局\(state.isTop ? "上" : "下")") {
                    Text("打者 \(state.currentBatter.compactName) · 投手 \(state.currentPitcher.compactName)")
                    Text("比分 \(state.awayScore):\(state.homeScore)   B \(state.balls) / S \(state.strikes) / O \(state.outs)").font(.subheadline).monospacedDigit()
                    if let original { Text("原记录：\(original.title)").font(.caption) }
                }
                if kind == .substitution { substitutionForm }
                else if kind == .position { positionForm }
                else { playForm }
                if let issue { Section { Text(issue).foregroundStyle(BMTheme.orange) } }
            }.navigationTitle(kind.rawValue).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("加入草稿") { submit() }.accessibilityIdentifier("history-add-draft") }
                }.onAppear { initialize() }
        }
    }

    private var substitutionForm: some View {
        Group {
            Section("换人方式") {
                Picker("类型", selection: $mode) { ForEach(["换投", "代打", "代跑", "守备替补"], id: \.self) { Text($0) } }
                    .onChange(of: mode) { _ in incoming = nil }
                if mode == "代跑" { Picker("替换垒上跑者", selection: $base) { ForEach(Base.allCases, id: \.self) { Text("\($0.title) · \(state.baseRunners[$0]?.compactName ?? "空")").tag($0) } } }
                if mode == "守备替补" { playerPicker("换下球员", selection: $outgoing, players: fielders) }
                if mode == "换投" { Text("换下：\(state.currentPitcher.compactName)") }
                if mode == "代打" { Text("换下：\(state.currentBatter.compactName)；继承当时球数与棒次") }
                playerPicker("换上球员", selection: $incoming, players: incomingPlayers)
            }
            Section("找不到球员？补全本场身份") {
                TextField("姓名", text: $newName)
                TextField("背号", text: $newNumber).keyboardType(.numberPad)
                Button("加入本场可选名单") {
                    let name = newName.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !name.isEmpty, let number = Int(newNumber), (0...99).contains(number) else { issue = "填写姓名与 0–99 的背号。"; return }
                    let p = Player(name: name, number: number, primaryPosition: .leftField)
                    newPlayer = p; incoming = p.id; issue = nil
                }
            }
        }
    }

    private var positionForm: some View {
        Section("当时场上守位 · 一起校验保存") {
            ForEach(fielders) { p in
                Picker(p.compactName, selection: Binding(get: { positions[p.id] ?? p.primaryPosition }, set: { positions[p.id] = $0 })) {
                    ForEach(FieldPosition.allCases) { Text($0.fullName).tag($0) }
                }
            }
            Text("可同时调整多人；棒次保持不变。投手、DH 与重复守位在预览中校验。").font(.caption)
        }
    }

    private var playForm: some View {
        Group {
            Section(kind == .error ? "失误如何影响原结果" : "补录内容") {
                Picker("记录类型", selection: $mode) {
                    ForEach(kind == .error ? ["击球结果", "附加失误"] : ["一球", "击球结果", "跑者事件", "仅修正局面", "补充说明"], id: \.self) { Text($0) }
                }.onChange(of: mode) { _ in resetMoves() }
                if mode == "补充说明" { TextField("补充已知信息，不改比分与统计", text: $supplementalNote, axis: .vertical) }
                if mode == "一球" { Picker("球", selection: $pitch) { ForEach(PitchAction.allCases) { Text($0.rawValue).tag($0) } }.accessibilityIdentifier("history-pitch") }
                if mode == "击球结果" || (mode == "附加失误" && !extraOnly) {
                    Picker("击球结果", selection: $outcome) { ForEach(PlayOutcome.allCases) { Text($0.rawValue).tag($0) } }
                        .onChange(of: outcome) { _ in resetMoves() }
                    if outcome == .error {
                        Picker("失误守位", selection: $errorPosition) { ForEach(fielders) { Text("\($0.primaryPosition.fullName) · \($0.compactName)").tag($0.primaryPosition) } }
                    } else {
                        Picker("守备路线", selection: $defense) {
                            Text("无／待补充").tag(nil as DefensivePlay?)
                            ForEach(DefensivePlay.quickPlays) { Text($0.title).tag(Optional($0)) }
                        }
                    }
                    Text("修改已有击球时保留一次打席与一球；附加失误保留有效安打。请逐一核对实际进垒。").font(.caption)
                }
                if mode == "跑者事件" { Picker("原因", selection: $runnerKind) { ForEach(RunnerEventKind.allCases) { Text($0.rawValue).tag($0) } } }
            }
            if ["击球结果", "附加失误", "跑者事件"].contains(mode) {
                Section("实际跑者去向") {
                    Button("按预览局面重新关联跑者") { resetMoves() }
                    ForEach($moves) { $move in
                        Picker("\(move.origin.title) · \(move.player.compactName)", selection: $move.destination) {
                            ForEach([RunnerDestination.hold, .base(.first), .base(.second), .base(.third), .score, .out], id: \.self) { Text($0.title).tag($0) }
                        }
                    }
                    if needsTiming {
                        Picker("得分与第三出局先后", selection: $timing) {
                            Text("请选择实际先后").tag(nil as Bool?)
                            Text("先得分，后触杀").tag(Optional(true))
                            Text("先触杀，不计分").tag(Optional(false))
                        }
                    }
                }
            }
            if kind == .error {
                Section("附加失误 · 多人／多次") {
                    ForEach($errors) { $credit in
                        VStack(alignment: .leading) {
                            Picker("责任球员", selection: $credit.playerID) { ForEach(fielders) { Text($0.compactName).tag($0.id) } }
                            Stepper("\(credit.count) 次", value: $credit.count, in: 1...9)
                            TextField("实际影响，例如传球失误多进一垒", text: $credit.note)
                            Button("移除此失误", role: .destructive) { errors.removeAll { $0.id == credit.id } }
                        }
                    }
                    Button("增加附加失误") { if let p = fielders.first { errors.append(HistoryErrorCredit(playerID: p.id)) } }
                    Text("失误上垒本身已记一次 E；这里只补额外失误。无法确定的自责分或打点需保留待确认标记。").font(.caption)
                }
            }
            if ["击球结果", "附加失误", "跑者事件"].contains(mode) {
                Section("打点与自责分复核") {
                    Toggle("已核对本事件的统计责任", isOn: $reviewedStats)
                    if reviewedStats {
                        Stepper("本事件打点 \(rbi)", value: $rbi, in: 0...4)
                        ForEach(responsiblePitchers) { player in
                            Stepper("\(player.compactName) 自责分 \(earned[player.id, default: 0])", value: Binding(get: { earned[player.id, default: 0] }, set: { earned[player.id] = $0 }), in: 0...4)
                        }
                        ForEach(moves.filter { if case .base = $0.destination { return true }; return $0.destination == .hold }) { move in
                            Toggle("\(move.player.compactName) 后续得分为非自责", isOn: Binding(get: { unearned.contains(move.player.id) }, set: { if $0 { unearned.insert(move.player.id) } else { unearned.remove(move.player.id) } }))
                        }
                        Text("逐投手填写本事件新增自责分；不能超过该投手新增失分。复杂失误链须在后续得分事件继续复核。").font(.caption)
                    } else if kind == .error { Text("更正失误后尚未复核的责任会标为待确认。").font(.caption).foregroundStyle(BMTheme.orange) }
                }
            }
            if mode == "仅修正局面", situation != nil { situationForm }
        }
    }

    private var situationForm: some View {
        Section("只补已知局面 · 过程与统计待确认") {
            Stepper("第 \(situation!.inning) 局", value: Binding(get: { situation!.inning }, set: { situation!.inning = $0 }), in: 1...100)
            Toggle("上半局", isOn: Binding(get: { situation!.isTop }, set: { situation!.isTop = $0; situation!.runners = [:]; situation!.pitcherID = $0 ? state.activeHomePitcherID : state.activeAwayPitcherID }))
            countStepper("坏球", \.balls, 0...3); countStepper("好球", \.strikes, 0...2); countStepper("出局", \.outs, 0...2)
            countStepper("客队得分", \.awayScore, 0...999); countStepper("主队得分", \.homeScore, 0...999)
            Picker("下一打者棒次", selection: Binding(get: { situation!.batterIndex }, set: { situation!.batterIndex = $0 })) {
                let ids = situation!.isTop ? state.awayBattingOrderIDs : state.homeBattingOrderIDs
                ForEach(ids.indices, id: \.self) { Text("第 \($0 + 1) 棒").tag($0) }
            }
            ForEach(Base.allCases, id: \.self) { base in
                playerPicker("\(base.title)跑者", selection: Binding(get: { situation!.runners[base]?.id }, set: { id in situation!.runners[base] = (state.homeTeam.players + state.awayTeam.players).first { $0.id == id } }), players: situation!.isTop ? state.awayTeam.players : state.homeTeam.players)
            }
            Text("不推测缺失安打、失误、打点或投手贡献。之后可用完整事件替换本条，重新校验。").font(.caption).foregroundStyle(BMTheme.orange)
        }
    }
    private func countStepper(_ title: String, _ path: WritableKeyPath<HistorySituation, Int>, _ range: ClosedRange<Int>) -> some View {
        Stepper("\(title) \(situation![keyPath: path])", value: Binding(get: { situation![keyPath: path] }, set: { situation![keyPath: path] = $0 }), in: range)
    }
    private func playerPicker(_ title: String, selection: Binding<UUID?>, players: [Player]) -> some View {
        Picker(title, selection: selection) { Text("请选择／空").tag(nil as UUID?); ForEach(players) { Text($0.compactName).tag(Optional($0.id)) } }
    }
    private func initialize() {
        mode = kind == .substitution ? "换投" : kind == .position ? "守位" : kind == .error ? "击球结果" : "一球"
        situation = HistorySituation(state); positions = Dictionary(uniqueKeysWithValues: fielders.map { ($0.id, $0.primaryPosition) })
        if kind == .error { outcome = .error }
        if let original {
            errors = original.errors
            switch original.command {
            case .pitch(let p): mode = "一球"; pitch = p
            case .play(let o, let d, let m, let t): mode = "击球结果"; outcome = o; defense = d; moves = m; timing = t
            case .runner(let k, let m, let t): mode = "跑者事件"; runnerKind = k; moves = m; timing = t
            case .pitcher(let p): mode = "换投"; incoming = p
            case .batter(let p): mode = "代打"; incoming = p
            case .pinchRunner(let b, let p): mode = "代跑"; base = b; incoming = p
            case .fielder(let o, let p): mode = "守备替补"; outgoing = o; incoming = p
            case .situation(let s): mode = "仅修正局面"; situation = s
            case .additionalErrors(let credits): mode = "附加失误"; errors = credits
            case .note(let text): mode = "补充说明"; supplementalNote = text
            default: break
            }
        }
        if let ruling = original?.statisticsRuling { reviewedStats = true; rbi = ruling.runsBattedIn; earned = ruling.earnedRuns; unearned = Set(ruling.unearnedRunnerIDs) }
        if moves.isEmpty { resetMoves() }
    }
    private var responsiblePitchers: [Player] {
        let ids = Set(state.baseRunners.values.compactMap { state.runnerPitcherIDs?[$0.id] }).union([state.currentPitcher.id])
        return state.fieldingTeam.players.filter { ids.contains($0.id) }
    }
    private var extraOnly: Bool {
        guard mode == "附加失误" else { return false }
        if let original, case .play = original.command { return false }
        return true
    }
    private func resetMoves() {
        if mode == "跑者事件" || extraOnly { moves = Base.allCases.compactMap { b in state.baseRunners[b].map { RunnerDecision(player: $0, origin: .base(b), destination: .hold) } } }
        else { moves = worker.suggestedRunnerDecisions(for: outcome) }
    }
    private var needsTiming: Bool {
        if mode == "跑者事件" { return worker.runnerEventNeedsTimingDecision(runnerKind, decisions: moves) }
        if mode == "击球结果" || (mode == "附加失误" && !extraOnly) { return worker.playNeedsTimingDecision(outcome, decisions: moves) }
        return false
    }
    private func submit() {
        guard !needsTiming || timing != nil else { issue = "请确认得分与第三出局的实际先后。"; return }
        let effectiveTiming = needsTiming ? timing : nil
        let command: HistoryCommand
        if kind == .substitution {
            guard let incoming else { issue = "请选择换上球员。"; return }
            switch mode {
            case "代打": command = .batter(incoming)
            case "代跑": command = .pinchRunner(base, incoming)
            case "守备替补": guard let outgoing else { issue = "请选择换下球员。"; return }; command = .fielder(outgoing, incoming)
            default: command = .pitcher(incoming)
            }
        } else if kind == .position {
            var lineup = worker.lineupDraft(forHomeTeam: state.isTop)
            for i in lineup.players.indices { if let pos = positions[lineup.players[i].id] { lineup.players[i].primaryPosition = pos } }
            lineup.pitcherID = fielders.first { positions[$0.id] == .pitcher }?.id
            lineup.changes = ["补记历史守位调整"]
            command = .lineup(HistoryLineup(lineup))
        } else {
            switch mode {
            case "一球": command = .pitch(pitch)
            case "跑者事件": command = .runner(runnerKind, moves, effectiveTiming)
            case "仅修正局面": command = .situation(situation!)
            case "补充说明":
                guard !supplementalNote.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { issue = "请填写补充说明。"; return }
                command = .note(supplementalNote)
            case "附加失误" where extraOnly:
                if moves.isEmpty {
                    guard !errors.isEmpty else { issue = "请选择至少一名失误责任人。"; return }
                    let ruling = reviewedStats && ["击球结果", "附加失误", "跑者事件"].contains(mode) ? HistoryStatisticsRuling(runsBattedIn: rbi, earnedRuns: Dictionary(uniqueKeysWithValues: responsiblePitchers.map { ($0.id, earned[$0.id, default: 0]) }), unearnedRunnerIDs: Array(unearned)) : nil
                    onSave(.additionalErrors(errors), [], newPlayer, ruling); dismiss(); return
                }
                command = .runner(.defensiveErrorAdvance, moves, effectiveTiming)
            default: command = .play(outcome, outcome == .error ? .error(at: errorPosition) : defense, moves, effectiveTiming)
            }
        }
        let ruling = reviewedStats && ["击球结果", "附加失误", "跑者事件"].contains(mode) ? HistoryStatisticsRuling(runsBattedIn: rbi, earnedRuns: Dictionary(uniqueKeysWithValues: responsiblePitchers.map { ($0.id, earned[$0.id, default: 0]) }), unearnedRunnerIDs: Array(unearned)) : nil
        onSave(command, errors, newPlayer, ruling); dismiss()
    }
}

#if DEBUG
struct HistoryCorrectionFixture: View {
    @EnvironmentObject private var store: GameStore
    @State private var ready = false
    var body: some View {
        Group {
            if ready { HistoryCorrectionView() }
            else { ProgressView() }
        }.onAppear {
            guard !ready, let team = store.currentTeam else { return }
            store.opponentTeams[0].players.append(Player(name: "周亦辰", number: 44, primaryPosition: .pitcher))
            let lineup = team.players.prefix(9).enumerated().map { LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset]) }
            store.startNewGame(opponent: store.opponentTeams[0], isHome: false, rules: GameRules(), lineup: lineup)
            store.recordPitch(.ball)
            store.recordPitch(.calledStrike)
            _ = store.applyPlay(.single)
            store.recordPitch(.ball)
            _ = store.applyPlay(.double)
            ready = true
        }
    }
}
#endif
