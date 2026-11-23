import SwiftUI

private struct RemoteLineupEdit: Identifiable {
    let id = UUID()
    let draft: LiveLineupDraft
    let version: Int
    let lastPlay: UUID?
    let epoch: Int
    let sharedRosterID: UUID?
}

struct ScheduleScoringDesk: View {
    @EnvironmentObject private var store: GameStore
    let key: ScheduleMatchKey
    @State private var status: ScheduleScoringStatus?
    @State private var members: [SharedTeamMember] = []
    @State private var editingAssignment = false
    @State private var scorer: UUID?
    @State private var lineup: UUID?
    @State private var assignmentEpoch = 0
    @State private var discardDraft = false
    @State private var edit: RemoteLineupEdit?
    @State private var message: String?
    @State private var busy = false

    private var admin: Bool { store.teamSharing.teams.first(where: { $0.id == key.team })?.role == .admin }
    private var local: StoredGame? { store.games.first { $0.id == key.game && $0.schedulePublication?.teamID == key.team } }
    private var draft: ScheduleDraftEnvelope? { store.teamSharing.state.scheduleDrafts?.first { $0.game == key.game && $0.team == key.team } }

    var body: some View {
        Form {
            if let status {
                Section("本场分工") {
                    Text(status.access.description)
                    if admin && !status.access.closed {
                        Button("安排记录员") { perform {
                            members = try await store.teamSharing.service.members(teamID:key.team).filter { $0.role != .member }
                            scorer = status.access.scorer; lineup = status.access.lineup
                            assignmentEpoch = status.access.epoch; editingAssignment = true
                        } }
                    }
                }
                if status.access.closed { Text("比赛已结束") }
                else {
                    if local?.status == .scheduled && status.access.canPrepare == true {
                        Button("提供双方开赛阵容") { perform { try await store.prepareScoring(key,status:status) } }
                    }
                    if let transfer = status.access.transfer {
                        Text("等待交接给" + transfer.targetName)
                        if status.access.holdsDevice && status.access.role == "scorer" {
                            Button("交接已保存的完整比赛记录") {
                                perform { try await store.releaseScheduledScore(key,status:status) }
                            }
                        }
                    } else if status.access.role == "scorer" {
                        Button(status.access.active ? "继续记录局面" : "核对阵容并进入记分") {
                            perform {
                                try await store.enterScheduledScore(key)
                                store.scheduleScoreRoute = key.game
                            }
                        }
                        if let f = status.frame {
                            Text("客队首棒：" + (f.state.awayTeam.players.first { $0.id == f.state.awayBattingOrderIDs.first }?.compactName ?? "待确认"))
                            Text("主队先发投手：" + (f.state.homeTeam.players.first { $0.id == f.state.activeHomePitcherID }?.compactName ?? "待确认"))
                        }
                    }
                    if status.access.role == "lineup" { lineupControls(status) }
                }
            } else if message == nil { ProgressView() }
            if let message { Text(message).foregroundStyle(.secondary) }
            Button("刷新岗位") { perform {} }
        }
        .navigationTitle("本场记录岗位")
        .disabled(busy)
        .task(id:key) {
            repeat {
                if !busy && edit == nil && !editingAssignment { await refresh() }
                do { try await Task.sleep(nanoseconds:4_000_000_000) } catch { return }
            } while !Task.isCancelled
        }
        .sheet(isPresented:$editingAssignment) {
            NavigationStack {
                Form {
                    memberPicker("整场或局面记录员", selection:$scorer, excluding:nil)
                    if local?.status == .scheduled && status?.version == 0 { Text("保存指派时，将提供本场双方名单与比赛规则。").font(.footnote) }
                    if scorer != nil { memberPicker("阵容记录员（可选）", selection:$lineup, excluding:scorer) }
                    if let message { Text(message) }
                }
                .onChange(of:scorer) { _ in if scorer == nil || scorer == lineup { lineup = nil } }
                .navigationTitle("安排记录员")
                .toolbar {
                    ToolbarItem(placement:.cancellationAction) { Button("取消") { editingAssignment = false } }
                    ToolbarItem(placement:.confirmationAction) { Button("保存") { perform {
                        try await store.scoringWrite(key,"assign",ScheduleWrite(epoch:assignmentEpoch,scorer:scorer,lineup:lineup))
                        editingAssignment = false
                        if local?.status == .scheduled, let status, status.version == 0 {
                            do { try await store.prepareScoring(key,status:status) }
                            catch { message = "指派已保存。开赛阵容尚待提供：" + error.localizedDescription }
                        }
                    } }.disabled(busy) }
                }
            }
        }
        .confirmationDialog("放弃本机未提交的阵容草稿？",isPresented:$discardDraft,titleVisibility:.visible) {
            Button("放弃草稿",role:.destructive) { perform {
                var next = store.teamSharing.state
                next.scheduleDrafts?.removeAll { $0.team == key.team && $0.game == key.game }
                try store.teamSharing.persist(next)
            } }
        }
        .sheet(item:$edit) { selection in
            LiveLineupEditor(draft:selection.draft,onCancel:{ edit = nil },onSaved:{},sharedRosterID:selection.sharedRosterID,submitRemote:{ updated in
                guard let status else { return false }
                do {
                    let p = ScheduleProposal(version:selection.version,baseline:HistoryLineup(selection.draft),
                        lastPlay:selection.lastPlay,lineup:HistoryLineup(updated))
                    try store.stageScheduleDraft(key,epoch:selection.epoch,proposal:p)
                    edit = nil
                    perform { try await store.submitScheduleDraft(key,status:status) }
                    return true
                } catch { store.actionErrorMessage = error.localizedDescription; return false }
            })
        }
    }

    private func memberPicker(_ title: String, selection: Binding<UUID?>, excluding: UUID?) -> some View {
        Picker(title,selection:selection) {
            Text("未指派").tag(nil as UUID?)
            ForEach(members.filter { $0.id != excluding }) { Text($0.nickname).tag(Optional($0.id)) }
        }
    }

    @ViewBuilder private func lineupControls(_ s: ScheduleScoringStatus) -> some View {
        Section("阵容记录") {
            if let pending = s.proposal {
                Text("待局面端处理：" + pending.lineup.changes.joined(separator:"、"))
            } else if let receipt = s.receipt {
                Text(receipt.applied ? "上次阵容调整已生效" : "上次调整已退回，请按最新局面重新选择")
            }
            if draft != nil {
                Text(draft?.proposal.lineup.changes.joined(separator:"、") ?? "本机有待提交的阵容草稿")
                Button("放弃本机草稿",role:.destructive) { discardDraft = true }
                Button("重试提交") { perform { try await store.submitScheduleDraft(key,status:s) } }
            }
            if let f = s.frame {
                if s.access.holdsDevice {
                    if s.access.openingConfirmed != true && s.proposal == nil {
                        if s.access.openingReady == true { Text("双方先发已提交，等待局面员核对") }
                        else { Button("提交双方先发") { perform {
                            try await store.scoringWrite(key,"confirmLineup",ScheduleWrite(epoch:s.access.epoch,version:s.version))
                        } } }
                    }
                    if s.proposal == nil && draft == nil {
                        ForEach([false,true],id:\.self) { home in
                            Button((home ? f.state.homeTeam.name : f.state.awayTeam.name) + " · 管理阵容") {
                                edit = RemoteLineupEdit(draft:LiveLineupDraft(game:f.state,isHomeTeam:home,
                                    allowsTwoWayPlayer:f.rules.twoWayPlayerEnabled,isPregame:f.pregame),
                                    version:s.version,lastPlay:f.lastPlay,epoch:s.access.epoch,sharedRosterID:home == f.isHome ? key.team : nil)
                            }
                        }
                    }
                } else {
                    Button("在此设备管理阵容") { perform { try await store.scoringWrite(key,"joinLineup",ScheduleWrite(epoch:s.access.epoch)) } }
                }
            } else { Text("等待比赛发布人提供开赛阵容") }
        }
    }

    private func refresh() async {
        do { status = try await store.scoringStatus(key) }
        catch { if case TeamServiceError.response = error { status = nil }; message = error.localizedDescription }
    }

    private func perform(_ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }; busy = true; message = nil
        Task { @MainActor in
            defer { busy = false }
            do { try await action() } catch { message = error.localizedDescription }
            await refresh()
        }
    }
}

// The device that owns the score applies a proposal in the same local transaction as its receipt ID.
struct ScheduleScoreSync: View {
    @EnvironmentObject private var store: GameStore
    let key: ScheduleMatchKey
    @State private var conflict: ScheduleProposal?
    @State private var message: String?
    @State private var busy = false
    @State private var reviewing = false
    @State private var opening: ScheduleScoringStatus?
    @State private var confirmingOpening = false

    var body: some View {
        VStack(alignment:.leading,spacing:4) {
            if let opening {
                if opening.access.openingReady == true {
                    Button("核对双方先发并开始") { confirmingOpening = true }
                } else { Text("等待阵容员提交双方先发").font(.footnote) }
            }
            if let conflict {
                Text(conflict.lineup.changes.joined(separator:"、")).font(.footnote)
                Button("核对换人生效位置") { reviewing = true }
            }
            if let message { Text(message).font(.footnote) }
        }
        .frame(maxWidth:.infinity,alignment:.leading)
        .confirmationDialog("局面已更新，请核对这次调整",isPresented:$reviewing,titleVisibility:.visible) {
            Button("从当前局面起生效") { Task { await sync(accept:conflict?.id) } }
            Button("退回阵容员重新选择") { Task { await sync(reject:conflict?.id) } }
        } message: { Text("如果换人早于已经记录的一球，请先按逐球记录核对并纠错。") }
        .alert("确认双方先发？",isPresented:$confirmingOpening,presenting:opening) { value in
            Button("确认并开始") { Task {
                do {
                    try await store.scoringWrite(key,"confirmOpening",ScheduleWrite(epoch:value.access.epoch,version:value.version))
                    await sync()
                } catch { message = error.localizedDescription }
            } }
            Button("返回核对",role:.cancel) {}
        } message: { _ in
            Text("首棒：" + store.game.currentBatter.compactName + "　先发投手：" + store.game.currentPitcher.compactName)
        }
        .task(id:key) {
            repeat {
                await sync()
                do { try await Task.sleep(nanoseconds:2_000_000_000) } catch { return }
            } while !Task.isCancelled
        }
    }

    private func sync(accept: UUID? = nil,reject: UUID? = nil) async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do {
            let result = try await store.synchronizeScheduledScore(key,accept:accept,reject:reject)
            opening = result.opening
            conflict = result.conflict
            message = result.message
        } catch { message = error.localizedDescription }
    }
}
