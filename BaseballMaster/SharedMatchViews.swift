import SwiftUI

struct SharedMatchesListView: View {
    @EnvironmentObject private var store: GameStore
    @ObservedObject var coordinator: SharedMatchCoordinator
    let teamID: UUID
    @State private var publish = false
    @State private var importHistory = false
    @State private var selectedYear = ""
    private var years: [String] { Set(coordinator.state.matches.filter { $0.teamID == teamID && !$0.deleted }.compactMap { $0.payload?.yearID }).sorted(by: >) }
    @State private var restoring: SharedMatch?
    var body: some View {
        List {
            if let message = coordinator.message { Text(message).foregroundStyle(BMTheme.orange) }
            Section {
                Text("这里的比赛可供球队成员查看。每场比赛同一时间由一台设备负责记分，更新后会同步给成员。")
                if store.teamSharing.teams.first(where: { $0.id == teamID })?.role != .member {
                    Button("共享本机比赛") { publish = true }.accessibilityIdentifier("publish-local-match")
                }
                if store.teamSharing.teams.first(where: { $0.id == teamID })?.role == .admin {
                    Button("导入历史比赛") { importHistory = true }.accessibilityIdentifier("import-history")
                }
            }
            Section {
                Picker("年度", selection: $selectedYear) {
                    Text("全部年度").tag("")
                    ForEach(years, id: \.self) { Text("\($0) 年").tag($0) }
                }.accessibilityIdentifier("shared-year-picker")
                Text("按比赛日期自动归入年度（1 月至 12 月），无需创建赛季。更正日期后自动调整。").font(.caption)
            }
            ForEach([StoredGameStatus.scheduled, .ongoing, .completed], id: \.rawValue) { status in
                Section(status == .scheduled ? "未来比赛" : status == .ongoing ? "进行中" : "历史比赛") {
                    ForEach(coordinator.state.matches.filter { $0.teamID == teamID && !$0.deleted && $0.payload?.status == status && (selectedYear.isEmpty || $0.payload?.yearID == selectedYear) }.sorted { ($0.payload?.displayDate ?? .distantPast) > ($1.payload?.displayDate ?? .distantPast) }) { match in
                        NavigationLink {
                            SharedMatchDetailView(coordinator: coordinator, matchID: match.id)
                        } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(match.matchTitle).font(.headline)
                                if let game = match.payload {
                                    Text("\(game.dateText) · \(game.state.awayScore) : \(game.state.homeScore)")
                                        .font(.subheadline).foregroundStyle(BMTheme.secondaryText)
                                    Text(coordinator.status(game.id)).font(.caption).foregroundStyle(BMTheme.green)
                                }
                            }
                        }.accessibilityIdentifier("shared-match-\(match.id)")
                    }
                }
            }
            if store.teamSharing.teams.first(where: { $0.id == teamID })?.role == .admin {
                Section("已删除比赛 · 30 天内可恢复") {
                    ForEach(coordinator.state.matches.filter { $0.teamID == teamID && $0.deleted }) { match in
                        Button("恢复删除于 " + Date(timeIntervalSince1970: match.updatedAt / 1000).formatted(date: .abbreviated, time: .shortened)) { restoring = match }
                    }
                }
            }
        }.navigationTitle("球队比赛").navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("刷新") { Task { await coordinator.synchronize(teamID: teamID) } }.disabled(coordinator.busy) }
        .task(id: store.teamSharing.foregroundGeneration) { if store.teamSharing.needsRefresh("matches-\(teamID)") { await coordinator.synchronize(teamID: teamID) } }
        .onChange(of: years) { if !selectedYear.isEmpty && !$0.contains(selectedYear) { selectedYear = "" } }
        .sheet(isPresented: $publish) { NavigationStack { PublishSharedMatchView(coordinator: coordinator, teamID: teamID) } }
        .sheet(isPresented: $importHistory) { NavigationStack { HistoricalImportView(coordinator: coordinator, teamID: teamID) } }
        .alert("恢复这场比赛？", isPresented: Binding(get: { restoring != nil }, set: { if !$0 { restoring = nil } }), presenting: restoring) { match in
            Button("恢复比赛") { Task { await coordinator.command(match, resource: "deletion", fields: ["deleted": .bool(false)]) } }
            Button("取消", role: .cancel) { }
        } message: { _ in Text("恢复后队内可查看，历史网页保持不开放，原观看授权不会恢复。") }
    }
}

struct PublishSharedMatchView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var coordinator: SharedMatchCoordinator
    let teamID: UUID
    @State private var selected: StoredGame?
    var body: some View {
        List {
            Text("选择要共享的本机比赛，球队成员就能查看阵容、逐球记录和统计。本机比赛仍会保留，两边的修改和删除互不影响。队外人员暂时不能通过历史回看网页观看，可另行设置。")
            if let message = coordinator.message { Text(message).foregroundStyle(BMTheme.orange) }
            ForEach(store.games.filter { !coordinator.isShared($0.id) }) { game in
                Button { selected = game } label: {
                    VStack(alignment: .leading) {
                        Text("\(game.state.awayTeam.name) vs \(game.state.homeTeam.name)")
                        Text(game.dateText).font(.caption)
                    }
                }.disabled(coordinator.busy).accessibilityIdentifier("select-match-to-share")
            }
        }.navigationTitle("共享比赛")
        .toolbar { Button("完成") { dismiss() }.disabled(coordinator.busy) }
        .alert("是否共享本次比赛到共享队伍，让成员可以查看？", isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } }), presenting: selected) { game in
            Button("确认共享") { Task { await coordinator.publish(game, teamID: teamID); if coordinator.publishedMatch(sourceID: game.id, teamID: teamID) != nil { dismiss() } } }
            Button("取消", role: .cancel) { }
        } message: { game in Text("\(game.state.awayTeam.name) vs \(game.state.homeTeam.name)\n\(game.dateText)\n本机比赛仍会保留，两边的修改和删除互不影响。") }
    }
}

private struct SharedResultPresentation: Identifiable { let id = UUID(); let store: GameStore }

struct SharedConflictDraft: Decodable, Identifiable {
    let id: UUID; let nickname: String; let deviceName: String; let reason: String
    let receivedRevision: Int; let createdAt: Double; let status: String
    let resolutionNote: String?; let resolvedRevision: Int?; let resolvedAt: Double?
    let payload: StoredGame?
    var statusText: String { status == "pending" ? "待核对" : status == "supplemented" ? "已补录核对" : "保留球队记录" }
}

struct ServiceRecoveryReviewView: View {
    @ObservedObject var directory: TeamSharingStore
    @ObservedObject var coordinator: SharedMatchCoordinator
    @State private var confirm = false
    @State private var complete = false
    var body: some View {
        List {
            Text("恢复备份后，需要重新确认你能访问的球队和比赛。恢复前未完成的邀请、删除和记分交接不会自动继续。")
            Text("这台设备上尚未同步的比赛记录会先保留，再获取球队已保存的记录。需要补录时，请交给管理员核对。旧备份不会自动覆盖球队的比赛。")
            Text("这台设备已保存 \(coordinator.state.matches.count) 场共享比赛，保留 \(coordinator.state.conflicts.count) 份待核对记录。")
            if let message = coordinator.message ?? directory.errorMessage { Text(message).foregroundStyle(BMTheme.orange) }
            if complete { Text("核对完成。请逐场查看保留记录，需要补录时提交给管理员。").foregroundStyle(BMTheme.green).accessibilityIdentifier("service-recovery-reviewed") }
            Button("核对可访问的球队和比赛") { confirm = true }.disabled(coordinator.busy || directory.isBusy)
                .accessibilityIdentifier("reconcile-service-recovery")
            NavigationLink("查看本机保留记录") { PreservedMatchDraftsView(coordinator: coordinator) }
        }.navigationTitle("恢复后核对").navigationBarTitleDisplayMode(.inline)
        .alert("开始核对恢复后的资料？", isPresented: $confirm) {
            Button("保留本机记录并核对") { Task {
                if await coordinator.reconcileAfterRecovery(), await directory.reconcileAfterRecovery() {
                    do { try coordinator.finishRecoveryReview(); complete = true } catch { coordinator.report(error) }
                }
            } }
            Button("取消", role: .cancel) { }
        } message: { Text("之前未完成的操作不会继续。比赛记录会保留供核对，已失效的球队访问权限、邀请和观看链接不会恢复。") }
    }
}
struct PreservedMatchDraftsView: View {
    @ObservedObject var coordinator: SharedMatchCoordinator
    @State private var presentation: SharedResultPresentation?
    @State private var shareFile: ExportedReportFile?
    @State private var error: String?
    var body: some View {
        List {
            Text("这些记录尚未同步到球队，也未计入球队统计。重新获得访问权限后，可在对应的共享比赛中提交给管理员核对。球队中已删除的比赛，可先导出保存，再请管理员确认是否需要重新导入。")
            if let error { Text(error).foregroundStyle(BMTheme.orange) }
            ForEach(coordinator.state.conflicts) { draft in
                Section(draft.game.state.awayTeam.name + " vs " + draft.game.state.homeTeam.name) {
                    Text(draft.game.dateText); Text(draft.reason).font(.caption)
                    Button("查看保留的比赛记录") {
                        do {
                            let copy = GameStore(persistenceURL: nil); try copy.installSharedCopy(draft.game)
                            copy.openGame(id: draft.game.id); copy.readOnlyPresentation = true
                            presentation = SharedResultPresentation(store: copy)
                        } catch { self.error = error.localizedDescription }
                    }
                    Button("导出保留的记录") {
                        do {
                            let url = try ReportExportFile.url(name: "BaseballMaster-待核对比赛", extension: "json")
                            try JSONEncoder().encode(draft.game).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                            shareFile = ExportedReportFile(url: url)
                        } catch { self.error = error.localizedDescription }
                    }
                }
            }
        }.navigationTitle("保留的记分资料").navigationBarTitleDisplayMode(.inline)
        .sheet(item: $presentation) { item in NavigationStack { BoxScoreView().environmentObject(item.store).toolbar { Button("完成") { presentation = nil } } } }
        .sheet(item: $shareFile) { ActivityShareSheet(items: [$0.url]) }
    }
}

struct SharedConflictReviewView: View {
    @ObservedObject var coordinator: SharedMatchCoordinator
    let matchID: UUID
    @State private var drafts: [SharedConflictDraft] = []
    @State private var error: String?
    var body: some View {
        List {
            Text("这里是各设备提交的未同步记录。请与球队已保存的记录对照；需要补录时，先接手记分，再到比赛页面更正并同步，最后在这里填写处理说明。")
            if let error { Text(error).foregroundStyle(BMTheme.orange) }
            if drafts.isEmpty { Text("暂无提交的待核对记录。") }
            ForEach(drafts) { draft in
                NavigationLink {
                    SharedConflictDetailView(coordinator: coordinator, matchID: matchID, draftID: draft.id)
                } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(draft.nickname + " · " + draft.deviceName).font(.headline)
                        Text(draft.statusText).foregroundStyle(draft.status == "pending" ? BMTheme.orange : BMTheme.green)
                        Text(Date(timeIntervalSince1970: draft.createdAt / 1000).formatted()).font(.caption)
                    }
                }.accessibilityIdentifier("team-conflict-\(draft.id)")
            }
        }.navigationTitle("比赛记录核对").navigationBarTitleDisplayMode(.inline)
        .task { await load() }.toolbar { Button("刷新") { Task { await load() } } }
    }
    private func load() async {
        guard let match = coordinator.match(id: matchID) else { return }
        do {
            struct Response: Decodable { let drafts: [SharedConflictDraft] }
            let result = try await coordinator.service.matchCommand(path: TeamServiceClient.matchPath(match.teamID, match.id) + "/drafts", method: "GET", body: .object([:]))
            drafts = try JSONDecoder().decode(Response.self, from: JSONEncoder().encode(result)).drafts
            error = nil
        } catch { drafts = []; self.error = error.localizedDescription }
    }
}

struct SharedConflictDetailView: View {
    @ObservedObject var coordinator: SharedMatchCoordinator
    let matchID: UUID
    let draftID: UUID
    @State private var draft: SharedConflictDraft?
    @State private var reviewedMatch: SharedMatch?
    @State private var note = ""
    @State private var decision: String?
    @State private var error: String?
    var body: some View {
        List {
            if let message = error ?? coordinator.message { Text(message).foregroundStyle(BMTheme.orange) }
            if let draft, let game = draft.payload, let official = reviewedMatch?.payload {
                Section("提交来源") {
                    Text(draft.nickname + " · " + draft.deviceName)
                    Text(draft.reason)
                    Text(draft.statusText).foregroundStyle(BMTheme.green).accessibilityIdentifier("conflict-resolution-status")
                }
                Section("比分与记录核对") {
                    Text("待核对记录：\(game.state.awayScore) : \(game.state.homeScore) · \(game.state.playLog.count) 条记录")
                    Text("球队已保存：\(official.state.awayScore) : \(official.state.homeScore) · \(official.state.playLog.count) 条记录")
                    NavigationLink("查看尚未同步的逐球记录") { List(game.state.playLog) { Text($0.text) }.navigationTitle("待核对记录") }
                    NavigationLink("查看球队已保存的逐球记录") { List(official.state.playLog) { Text($0.text) }.navigationTitle("球队记录") }
                }
                if draft.status == "pending" {
                    Section("处理方式") {
                        Text("需要补录时，请返回共享比赛并接手记分，对照这份记录更正比赛，再同步给球队。此处只保存核对结果，不会自动修改比赛。")
                        TextField("处理说明（必填）", text: $note, axis: .vertical).lineLimit(2...5)
                            .accessibilityIdentifier("conflict-resolution-note")
                        Button("已补录，完成核对") { decision = "supplemented" }
                            .disabled(note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || note.count > 500 || reviewedMatch?.canWrite != true || (reviewedMatch?.serverRevision ?? 0) <= draft.receivedRevision)
                        Button("保留球队记录，不补录") { decision = "discarded" }
                            .disabled(note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || note.count > 500)
                            .accessibilityIdentifier("discard-team-conflict")
                    }.disabled(coordinator.busy)
                } else {
                    Section("处理结果") {
                        Text(draft.resolutionNote ?? "")
                        if draft.resolvedRevision != nil { Text("处理结果已保存到球队。") }
                        Text("提交的记录仍会保留，供以后查看。")
                    }
                }
            }
        }.navigationTitle("核对比赛记录").navigationBarTitleDisplayMode(.inline)
        .task { await load() }.toolbar { Button("刷新球队记录") { Task { await load() } }.disabled(coordinator.busy) }
        .alert("确认处理这份待核对记录？", isPresented: Binding(get: { decision != nil }, set: { if !$0 { decision = nil } }), presenting: decision) { choice in
            Button("确认处理") { Task { if let reviewedMatch { await coordinator.command(reviewedMatch, resource: "drafts/" + draftID.uuidString.lowercased(), fields: ["decision": .string(choice), "note": .string(note)]); await load() } } }
            Button("取消", role: .cancel) { }
        } message: { choice in Text(choice == "supplemented" ? "保存你的补录说明。球队比赛以已同步的内容为准，提交的记录仍会保留。" : "球队比赛保持不变，并保存不补录的原因。提交的记录仍会保留。") }
    }
    private func load() async {
        guard let match = coordinator.match(id: matchID) else { return }
        do {
            await coordinator.refresh(match)
            guard coordinator.message == nil else { return }
            reviewedMatch = coordinator.match(id: matchID)
            let response = try await coordinator.service.matchCommand(path: TeamServiceClient.matchPath(match.teamID, match.id) + "/drafts/" + draftID.uuidString.lowercased(), method: "GET", body: .object([:]))
            draft = try JSONDecoder().decode(SharedConflictDraft.self, from: JSONEncoder().encode(response)); error = nil
        } catch { draft = nil; self.error = error.localizedDescription }
    }
}

struct SharedMatchDetailView: View {
    @EnvironmentObject private var store: GameStore
    @ObservedObject var coordinator: SharedMatchCoordinator
    let matchID: UUID
    @State private var devices: [MatchDevice] = []
    @State private var viewers: MatchViewers?
    @State private var proposedDevice: MatchDevice?
    @State private var force = false
    @State private var deleting = false
    @State private var mode: HistoricalAccessMode?
    @State private var showScore = false
    @State private var resultPresentation: SharedResultPresentation?
    @State private var error: String?
    @State private var conflict: MatchConflict?
    private var match: SharedMatch? { coordinator.match(id: matchID) }
    var body: some View {
        List {
            if let message = coordinator.message ?? error { Text(message).foregroundStyle(BMTheme.orange) }
            if let match, let game = match.payload {
                summaryContent(match, game)
                if game.status == .completed && !game.isObservation {
                    Section("球员对应") {
                        NavigationLink {
                            SharedPlayerLinksView(coordinator: coordinator, matchID: match.id)
                        } label: { Text("核对当场球员与球队名单") }
                        .accessibilityIdentifier("open-match-player-links")
                    }
                }
                handoffContent(match, game)
                conflictContent(match, game)
                if store.teamSharing.teams.first(where: { $0.id == match.teamID })?.role == .admin {
                    Section("比赛记录核对") {
                        NavigationLink("查看各设备提交的记录") {
                            SharedConflictReviewView(coordinator: coordinator, matchID: match.id)
                        }.accessibilityIdentifier("open-team-conflicts")
                    }
                }
                liveContent(match, game)
                historyContent(match, game)
                deleteContent(match, game)
            } else { Text("比赛暂不可用，请刷新后再试。") }
        }.disabled(coordinator.busy)
        .navigationTitle("共享比赛").navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("刷新") { Task { if let match { await coordinator.refresh(match) }; await reload() } } }
        .task { if let match { await coordinator.refresh(match) }; await reload() }
        .navigationDestination(isPresented: $showScore) { ScorekeepingView().environmentObject(store) }
        .sheet(item: $resultPresentation) { item in NavigationStack { BoxScoreView().environmentObject(item.store).toolbar { Button("完成") { resultPresentation = nil } } } }
        .alert("确认移交记分？", isPresented: Binding(get: { proposedDevice != nil }, set: { if !$0 { proposedDevice = nil } }), presenting: proposedDevice) { device in
            Button("同步并发起交接") { Task { if let match { await coordinator.command(match, resource: "handoff/propose", fields: ["targetDeviceID": .uuid(device.deviceID)]); await reload() } } }
            Button("取消", role: .cancel) { }
        } message: { device in Text("将暂停本机录入，等待 \(device.nickname) 在 \(device.label) 核对最新记录后确认。") }
        .alert("删除全队的这场比赛？", isPresented: $deleting) {
            Button("确认删除共享比赛", role: .destructive) { Task { if let match { await coordinator.command(match, resource: "deletion", fields: ["deleted": .bool(true)]) } } }
            Button("取消", role: .cancel) { }
        } message: { Text("删除后，球队成员将看不到这场比赛，球队统计也不再包含它，观看链接会关闭。本机比赛仍会保留。管理员可在 30 天内恢复，恢复后需重新设置网页观看。") }
        .alert("强制接管到本机？", isPresented: $force) {
            Button("已了解风险，接管", role: .destructive) { Task { if let match, let device = coordinator.state.deviceID { await coordinator.command(match, resource: "handoff/force", fields: ["targetDeviceID": .uuid(device), "confirmRisk": .bool(true)]); await reload() } } }
            Button("取消", role: .cancel) { }
        } message: { Text("这台设备将从球队最后保存的记录继续记分。旧设备可能还有未同步内容，联网后会保留供核对，不会覆盖你接手后的记录。") }
        .alert("修改历史观看方式？", isPresented: Binding(get: { mode != nil }, set: { if !$0 { mode = nil } }), presenting: mode) { option in
            Button("确认设为\(option.title)") { Task { if let match { await coordinator.command(match, resource: "access", fields: ["mode": .string(option.rawValue)]); await reload() } } }
            Button("取消", role: .cancel) { }
        } message: { option in Text(option == .public ? "本场双方队名、当场球员、比分、逐球记录和统计将对持链接的人开放。可先查看比赛与统计核对。" : option == .closed ? "关闭历史网页，并撤销现有指定观看授权。比赛档案和队内查看保留。" : "只有逐人授权的成员和经你核对的观看设备可访问；普通转发链接不会授予权限。") }
        .alert("改用球队已保存的记录？", isPresented: Binding(get: { conflict != nil }, set: { if !$0 { conflict = nil } }), presenting: conflict) { draft in
            Button("使用球队记录") { do { try coordinator.useOfficialVersion(draft) } catch { self.error = error.localizedDescription } }
            Button("取消", role: .cancel) { }
        } message: { _ in Text("确认后，这台设备会显示球队已保存的记录。尚未同步的内容会另外保留，供以后核对和补录，不会自动覆盖球队比赛。") }
    }
    @ViewBuilder private func summaryContent(_ match: SharedMatch, _ game: StoredGame) -> some View {
                Section {
                    Text(match.matchTitle).font(.headline)
                    Text("\(game.state.awayScore) : \(game.state.homeScore)").font(.largeTitle.bold())
                    Text(game.dateText)
                    Text(coordinator.status(game.id)).font(.footnote).foregroundStyle(BMTheme.green).accessibilityIdentifier("shared-match-status")
                    Button("查看比赛与统计") {
                        let copy = GameStore(persistenceURL: nil)
                        try? copy.installSharedCopy(game); copy.openGame(id: game.id); copy.readOnlyPresentation = true
                        resultPresentation = SharedResultPresentation(store: copy)
                    }.accessibilityIdentifier("view-shared-match-result")
                    if coordinator.canEdit(game.id), game.status != .scheduled {
                        Button(game.status == .completed ? "更正比赛记录" : "继续记分") {
                            store.openGame(id: game.id); showScore = true
                        }.accessibilityIdentifier("score-shared-match")
                    }
                }
    }
    @ViewBuilder private func handoffContent(_ match: SharedMatch, _ game: StoredGame) -> some View {
                if let h = match.handoff {
                    Section("记分交接") {
                        Text("双方核对完成后才切换设备。交接期间暂停记分，不因断线自动交给别人。")
                        if h.targetDeviceID == coordinator.state.deviceID {
                            Text("已收到球队最新记录，请核对上方比分、时间及比赛记录。")
                            Button("已核对，接手记分") { Task { await coordinator.command(match, resource: "handoff/accept"); await reload() } }
                                .accessibilityIdentifier("accept-match-handoff")
                        }
                        if h.sourceDeviceID == coordinator.state.deviceID {
                            Text("等待对方核对并确认。")
                            Button("取消交接", role: .destructive) { Task { await coordinator.command(match, resource: "handoff/cancel"); await reload() } }
                        }
                    }
                } else if coordinator.canEdit(game.id) {
                    Section("交接记录") {
                        if devices.filter({ $0.deviceID != coordinator.state.deviceID }).isEmpty { Text("还没有其他可接手的记录设备。") }
                        ForEach(devices.filter { $0.deviceID != coordinator.state.deviceID }) { device in
                            Button("交给 \(device.nickname) · \(device.label)") { proposedDevice = device }.accessibilityIdentifier("propose-match-handoff")
                        }
                    }
                }
                if store.teamSharing.teams.first(where: { $0.id == match.teamID })?.role == .admin, !coordinator.canEdit(game.id) {
                    Section { Button("管理员强制接管到本机", role: .destructive) { force = true }.accessibilityIdentifier("force-match-handoff") }
                }
    }
    @ViewBuilder private func conflictContent(_ match: SharedMatch, _ game: StoredGame) -> some View {
                ForEach(coordinator.state.conflicts.filter { $0.localID == game.id }) { draft in
                    Section("这台设备尚未同步的记录") {
                        Text(draft.reason)
                        Text("尚未同步：\(draft.game.state.awayScore) : \(draft.game.state.homeScore)，\(draft.game.state.playLog.count) 条记录\n球队已保存：\(game.state.awayScore) : \(game.state.homeScore)，\(game.state.playLog.count) 条记录")
                        NavigationLink("查看这台设备保留的记录") {
                            List(draft.game.state.playLog) { Text($0.text) }.navigationTitle("尚未同步的记录")
                        }
                        if coordinator.state.submittedConflictIDs?.contains(draft.id) == true {
                            Text("已提交给管理员核对，球队比赛未被覆盖。").foregroundStyle(BMTheme.green)
                        } else {
                            Button("交给管理员核对") { Task { await coordinator.submitConflict(draft, match: match) } }
                                .accessibilityIdentifier("submit-conflict-draft")
                        }
                        Button("使用球队记录，完成核对") { conflict = draft }
                    }
                }
                ForEach(coordinator.state.resolvedConflicts?.filter { $0.localID == game.id } ?? []) { draft in
                    Section("已处理的记录") {
                        NavigationLink("查看保留的记录") {
                            List(draft.game.state.playLog) { Text($0.text) }.navigationTitle("保留的记录")
                        }
                        if coordinator.state.submittedConflictIDs?.contains(draft.id) != true {
                            Button("交给管理员核对") { Task { await coordinator.submitConflict(draft, match: match) } }
                        }
                    }
                }
    }
    @ViewBuilder private func liveContent(_ match: SharedMatch, _ game: StoredGame) -> some View {
                if game.status != .completed || match.liveEnabled {
                    Section("实时直播") {
                        Text("任何人打开直播链接都能观看。比赛结束一小时后，或手动关闭直播后，链接会失效。赛后回看需要另行设置观看范围。")
                        if coordinator.canEdit(game.id) {
                            Button(match.liveEnabled ? "关闭直播" : "开启直播") {
                                Task { await coordinator.command(match, resource: "live", fields: ["enabled": .bool(!match.liveEnabled)]) }
                            }
                        }
                        if match.liveEnabled, let url = match.liveURL { ShareLink("分享实时直播", item: url) }
                    }
                }
    }
    @ViewBuilder private func historyContent(_ match: SharedMatch, _ game: StoredGame) -> some View {
                if game.status == .completed {
                    Section("历史网页观看") {
                        Text(match.accessMode.title).font(.headline).accessibilityIdentifier("historical-access-mode")
                        Text("这里设置谁能通过网页回看比赛，不影响仍有效的直播链接。关闭网页回看后，球队成员仍能在 App 中查看，比赛不会删除。")
                        if match.canManageAccess {
                            ForEach(HistoricalAccessMode.allCases, id: \.rawValue) { option in
                                Button(option.title) { mode = option }.disabled(option == match.accessMode)
                            }
                        }
                        if match.accessMode == .public { ShareLink("分享历史回看", item: match.viewURL) }
                        if match.accessMode == .restricted {
                            Button("在浏览器观看此场") {
                                Task {
                                    do {
                                        let token = try TeamDeviceKeychain.randomSecret()
                                        await coordinator.command(match, resource: "member-ticket", fields: ["token": .string(token)], token: token)
                                        if coordinator.message == nil, let url = coordinator.state.lastInvitationURL.flatMap(URL.init(string:)) { await UIApplication.shared.open(url) }
                                    } catch { self.error = error.localizedDescription }
                                }
                            }
                        }
                    }
                    if match.canManageAccess, match.accessMode == .restricted {
                        Section("指定队内成员") {
                            ForEach(viewers?.members ?? []) { member in
                                Button { Task { await coordinator.command(match, resource: "viewers", fields: ["memberID": .uuid(member.id), "allow": .bool(member.granted == 0)]); await reload() } } label: {
                                    HStack { Text(member.nickname); Spacer(); Text(member.granted == 1 ? "撤销观看" : "允许观看") }
                                }
                            }
                        }
                        Section("邀请队外观看者") {
                            Text("对方填写称呼后，你再核对并确认；未授权的人无法观看。")
                            Button("生成单次观看邀请") {
                                Task {
                                    do {
                                        let token = try TeamDeviceKeychain.randomSecret()
                                        await coordinator.command(match, resource: "viewer-invitations", fields: ["token": .string(token)], token: token)
                                    } catch { self.error = error.localizedDescription }
                                }
                            }.accessibilityIdentifier("invite-history-viewer")
                            if let link = coordinator.state.lastInvitationURL, link.hasPrefix(match.viewURL.absoluteString + "/"), let url = URL(string: link) {
                                ShareLink("分享观看邀请", item: url)
                                Text(link).font(.caption).textSelection(.enabled).accessibilityIdentifier("history-viewer-invite-url")
                            }
                            ForEach(viewers?.viewers.filter { $0.memberID == nil && $0.status != "revoked" } ?? []) { viewer in
                                VStack(alignment: .leading, spacing: 8) {
                                    Text(viewer.nickname + (viewer.status == "pending" ? " · 等待核对" : " · 已授权"))
                                    if viewer.status == "pending" {
                                        Button("确认是此人，允许观看") { Task { await coordinator.command(match, resource: "viewers", fields: ["viewerID": .uuid(viewer.id), "allow": .bool(true)]); await reload() } }
                                    }
                                    Button("撤销此人观看", role: .destructive) { Task { await coordinator.command(match, resource: "viewers", fields: ["viewerID": .uuid(viewer.id), "allow": .bool(false)]); await reload() } }
                                }
                            }
                        }
                    }
                }
    }
    @ViewBuilder private func deleteContent(_ match: SharedMatch, _ game: StoredGame) -> some View {
                if store.teamSharing.teams.first(where: { $0.id == match.teamID })?.role == .admin {
                    Section { Button("删除共享比赛", role: .destructive) { deleting = true } }
                }
    }
    private func reload() async {
        guard let match else { return }
        do { if match.canManageAccess { devices = try await coordinator.service.matchDevices(teamID: match.teamID, gameID: match.id) } } catch { devices = [] }
        do { if match.canManageAccess { viewers = try await coordinator.service.matchViewers(teamID: match.teamID, gameID: match.id) } } catch { viewers = nil }
    }
}

struct MatchSharingToolbar: View {
    @EnvironmentObject private var store: GameStore
    @ObservedObject var coordinator: SharedMatchCoordinator
    let localID: UUID
    var body: some View {
        if let match = coordinator.match(localID: localID) ?? store.teamSharing.teams.compactMap({ coordinator.publishedMatch(sourceID: localID, teamID: $0.id) }).first {
            NavigationLink { SharedMatchDetailView(coordinator: coordinator, matchID: match.id) } label: {
                Image(systemName: match.handoff != nil ? "arrow.left.arrow.right" : "person.2")
            }.accessibilityLabel("比赛同步与交接：" + coordinator.status(localID)).accessibilityIdentifier("match-sync-controls")
        }
    }
}

struct HistoricalImportView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var coordinator: SharedMatchCoordinator
    let teamID: UUID
    @State private var profile: SharedTeamProfile?
    @State private var sourceID = ""
    @State private var selected = Set<UUID>()
    @State private var mapping: [UUID: String] = [:]
    @State private var confirming = false
    @State private var preparing = false
    @State private var error: String?
    @State private var comparison: SharedResultPresentation?
    private var originals: [StoredGame] {
        store.games.filter { $0.status == .completed && $0.state.isFinal && $0.sharedGameID == nil && !coordinator.isShared($0.id) }
            .sorted { $0.displayDate > $1.displayDate }
    }
    private var sourceTeams: [Team] {
        var found: [UUID: Team] = [:]
        for game in originals {
            for team in game.isObservation ? [game.state.homeTeam, game.state.awayTeam] : [game.ourTeam] {
                if found[team.id] == nil { found[team.id] = team }
            }
        }
        return found.values.sorted { $0.name == $1.name ? $0.id.uuidString < $1.id.uuidString : $0.name < $1.name }
    }
    private var candidates: [StoredGame] {
        guard let source = UUID(uuidString: sourceID) else { return [] }
        return originals.filter { $0.isObservation ? [$0.state.homeTeam.id, $0.state.awayTeam.id].contains(source) : $0.ourTeam.id == source }
    }
    private var selectedGames: [StoredGame] { candidates.filter { selected.contains($0.id) } }
    private var sourcePlayers: [Player] {
        var found: [UUID: Player] = [:]
        let batch = coordinator.state.historyImport
        for game in batch?.rows.map(\.original) ?? selectedGames {
            let team = [game.state.homeTeam, game.state.awayTeam].first { $0.id.uuidString == (batch?.sourceTeamID.uuidString ?? sourceID) }
            for player in team?.players ?? [] where found[player.id] == nil { found[player.id] = player }
        }
        return found.values.sorted { $0.name == $1.name ? $0.id.uuidString < $1.id.uuidString : $0.name < $1.name }
    }
    private func target(_ player: Player) -> String {
        mapping[player.id] ?? ((profile?.players.contains { $0.sourceID == player.id } == true) ? player.id.uuidString : "")
    }
    var body: some View {
        List {
            if let message = error ?? coordinator.message { Text(message).foregroundStyle(BMTheme.orange).accessibilityIdentifier("import-message") }
            if let batch = coordinator.state.historyImport {
                if batch.teamID == teamID { batchContent(batch) }
                else { Text("另一支球队还有一批导入待处理，请先返回那支球队完成或结束导入。") }
            } else { selectionContent }
        }
        .id(coordinator.state.historyImport?.id)
        .navigationTitle("导入历史比赛").navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("关闭") { dismiss() } }
        .task {
            do { profile = try await coordinator.historicalImportProfile(teamID: teamID); if sourceID.isEmpty { sourceID = sourceTeams.first?.id.uuidString ?? "" } }
            catch { self.error = error.localizedDescription }
        }
        .onChange(of: sourceID) { _ in selected = []; mapping = [:] }
        .onChange(of: coordinator.state.historyImport?.id) { id in
            if id == nil { selected = []; mapping = [:] }
        }
        .alert("确认导入这些历史比赛？", isPresented: $confirming) {
            Button("备份并开始导入") { Task { await coordinator.resumeHistoricalImport() } }
            Button("返回核对", role: .cancel) { }
        } message: { Text("会先备份本机资料，再将所选比赛导入共享球队，供成员查看。本机比赛仍会保留，两边的修改和删除互不影响。队外人员暂时不能通过历史回看网页观看。断网后可继续导入。") }
        .sheet(item: $comparison) { item in NavigationStack { BoxScoreView().environmentObject(item.store).toolbar { Button("完成") { comparison = nil } } } }
    }
    @ViewBuilder private var selectionContent: some View {
        Section {
            Text("将本机已结束的比赛导入当前共享球队。先选择球队和比赛，再核对球员。每批最多 50 场。")
            if originals.isEmpty { Text("本机还没有可导入的已结束比赛。") }
            Picker("原本机球队", selection: $sourceID) {
                Text("请选择").tag("")
                ForEach(sourceTeams) { team in Text(team.name + (sourceTeams.filter { $0.name == team.name }.count > 1 ? " · 编号 " + String(team.id.uuidString.prefix(4)) : "")).tag(team.id.uuidString) }
            }.accessibilityIdentifier("import-source-team")
        }
        Section("选择比赛 · 已选 \(selected.count) 场") {
            ForEach(candidates) { game in
                Toggle(isOn: Binding(get: { selected.contains(game.id) }, set: { value in
                    if value && selected.count < 50 { selected.insert(game.id) } else { selected.remove(game.id) }
                })) { gameLabel(game) }.accessibilityIdentifier("import-select-\(game.id)")
            }
        }
        if !selected.isEmpty {
            Section("球员对应关系") {
                Text("仅同一球员编号自动对应。姓名或背号相同不会自动合并。无法确认的人员可保留未关联；比赛当时的姓名、背号、阵容与记录不会改写。")
                    .font(.caption).foregroundStyle(BMTheme.secondaryText)
                ForEach(sourcePlayers) { player in
                    Picker(selection: Binding(get: { target(player) }, set: { mapping[player.id] = $0 })) {
                        Text(profile?.players.contains { $0.sourceID == player.id } == true ? "沿用同一球员身份" : "保留未关联").tag("")
                        ForEach(profile?.players ?? []) { known in
                            Text("\(known.name) · \(known.numberText)\(known.isArchived ? " · 已停用" : "")" + ((profile?.players.filter { $0.name == known.name && $0.numbers == known.numbers }.count ?? 0) > 1 ? " · 编号 " + String(known.id.uuidString.prefix(4)) : "")).tag(known.sourceID.uuidString)
                        }
                    } label: { Text(player.compactName + (sourcePlayers.filter { $0.compactName == player.compactName }.count > 1 ? " · 编号 " + String(player.id.uuidString.prefix(4)) : "")) }
                    .accessibilityIdentifier("import-player-\(player.id)")
                }
                Text("比赛按实际日期自动归入年度，无需选择或创建赛季。观赛记录继续保留观赛身份，不计入本队年度统计。")
                    .font(.caption)
            }
            Section {
                Button(preparing ? "正在预览…" : "预览导入") {
                    guard let source = UUID(uuidString: sourceID) else { return }
                    let links = sourcePlayers.compactMap { player in UUID(uuidString: target(player)).map { MatchPlayerLink(sourceID: player.id, targetID: $0) } }
                    preparing = true; error = nil
                    Task { await coordinator.prepareHistoricalImport(selectedGames, teamID: teamID, sourceTeamID: source, links: links); preparing = false }
                }.disabled(preparing || coordinator.busy || profile == nil).accessibilityIdentifier("import-preview")
            }
        }
    }
    @ViewBuilder private func gameLabel(_ game: StoredGame) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text("\(game.state.awayTeam.name) vs \(game.state.homeTeam.name)").font(.headline)
            Text("\(game.dateText) · \(game.state.awayScore) : \(game.state.homeScore)").font(.subheadline)
            Text("\(game.yearID) 年 · \(game.rules.gameMode.title)\(game.isObservation ? " · 观赛记录，不计入本队统计" : "")").font(.caption).foregroundStyle(BMTheme.secondaryText)
        }
    }
    @ViewBuilder private func batchContent(_ batch: HistoricalImportBatch) -> some View {
        Section("导入核对") {
            Text("已导入 \(batch.rows.filter { $0.status == "imported" }.count) 场 · 已跳过 \(batch.rows.filter { $0.status == "skipped" }.count) 场 · 共 \(batch.rows.count) 场")
                .accessibilityIdentifier("import-progress")
            Text("将导入以下预览内容。本机比赛仍会保留；导入后，本机和共享球队中的比赛分别修改、分别删除，互不影响。")
            if let backup = batch.backupURL { ShareLink("保存导入前完整备份", item: backup) }
            if coordinator.importing { Button("暂停导入") { coordinator.pauseHistoricalImport() }.accessibilityIdentifier("import-pause") }
            else if !batch.finished {
                Button("刷新重复检查") { Task { do { profile = try await coordinator.historicalImportProfile(teamID: teamID); error = nil; await coordinator.previewHistoricalImport() } catch { self.error = error.localizedDescription } } }.disabled(coordinator.busy)
                    .accessibilityIdentifier("import-refresh-preview")
                Button(batch.backupURL == nil ? "确认并导入" : "继续导入") { confirming = true }
                    .disabled(!batch.ready || coordinator.busy).accessibilityIdentifier("import-confirm")
            }
        }
        Section("球员对应关系") {
            ForEach(sourcePlayers) { player in
                let targetID = batch.links.first { $0.sourceID == player.id }?.targetID ?? player.id
                let known = profile?.players.first { $0.sourceID == targetID }
                Text("\(player.compactName) → " + (known.map { "\($0.name) · \($0.numberText)" } ?? "保留未关联"))
                    .font(.subheadline)
            }
        }
        ForEach(batch.rows) { row in
            Section {
                gameLabel(row.original)
                if row.status == "imported" { Label("已导入，历史网页不开放", systemImage: "checkmark.circle") }
                else if row.status == "skipped" { Text("已跳过，原记录保留") }
                else if row.pending != nil { Text("上传结果待确认，继续时会核对，不会重复创建。") }
                else {
                    Text(row.preview?.explanation ?? "需要重新预览核对").foregroundStyle(row.decision == "review" ? BMTheme.orange : BMTheme.green)
                    ForEach(row.preview?.candidates ?? []) { existing in
                        Text("球队已有比赛：\(existing.title)\n\(existing.date) · \(existing.awayScore) : \(existing.homeScore)").font(.subheadline)
                        if !existing.deleted {
                            Button("查看球队中的这场比赛") { Task { await showComparison(existing.id) } }.disabled(coordinator.busy)
                        }
                    }
                    if row.preview?.status == "similar" {
                        Button(row.decision == "independent" ? "已确认：另一场比赛" : "确认是另一场，单独导入") { coordinator.chooseHistoricalImport(row.id, decision: "independent") }
                            .accessibilityIdentifier("import-independent-\(row.id)")
                    } else if row.preview?.status == "new" {
                        Button(row.decision == "include" ? "已选择导入" : "选择导入") { coordinator.chooseHistoricalImport(row.id, decision: "include") }
                    }
                    Button(row.decision == "skip" ? "已选择跳过" : "跳过此场，保留原记录") { coordinator.chooseHistoricalImport(row.id, decision: "skip") }
                        .accessibilityIdentifier("import-skip-\(row.id)")
                }
            }.disabled(coordinator.busy)
        }
        if !coordinator.importing {
            Section { Button(batch.finished ? "导入另一批比赛" : "结束本批，保留已导入比赛") { coordinator.endHistoricalImport() }.disabled(coordinator.busy).accessibilityIdentifier("import-end-batch") }
        }
    }
    private func showComparison(_ gameID: UUID) async {
        do {
            let remote = try await coordinator.service.match(teamID: teamID, gameID: gameID)
            guard let game = remote.payload else { throw TeamServiceError.message("这场比赛已不可用，请刷新核对。") }
            let copy = GameStore(persistenceURL: nil); try copy.installSharedCopy(game); copy.openGame(id: game.id); copy.readOnlyPresentation = true
            comparison = SharedResultPresentation(store: copy)
        } catch { self.error = error.localizedDescription }
    }
}

struct SharedStatisticsSyncNotice: View {
    @EnvironmentObject private var store: GameStore
    @ObservedObject var coordinator: SharedMatchCoordinator
    let teamID: UUID
    private var latest: Double? { coordinator.state.matches.filter { $0.teamID == teamID }.map(\.updatedAt).max() }
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text("按已同步的球队比赛汇总，未同步修改将在同步成功后计入。")
            if let latest { Text("最近收到的比赛更新：" + Date(timeIntervalSince1970: latest / 1000).formatted(date: .abbreviated, time: .shortened)) }
            if let message = coordinator.message { Text(message).foregroundStyle(BMTheme.orange) }
            Button(coordinator.busy ? "正在同步" : "刷新球队统计") { Task { await store.teamSharing.connect(); await coordinator.synchronize(teamID: teamID) } }.disabled(coordinator.busy)
        }.font(.footnote).foregroundStyle(BMTheme.secondaryText)
        .accessibilityIdentifier("shared-statistics-sync-notice")
    }
}

struct SharedPlayerLinksView: View {
    @EnvironmentObject private var store: GameStore
    @ObservedObject var coordinator: SharedMatchCoordinator
    let matchID: UUID
    @State private var reviewedMatch: SharedMatch?
    @State private var profile: SharedTeamProfile?
    @State private var role: SharedTeamRole?
    @State private var choices: [UUID: UUID] = [:]
    @State private var history: [PlayerLinkChange] = []
    @State private var loading = false
    @State private var confirming = false
    @State private var error: String?
    @State private var saved = false
    private var match: SharedMatch? { coordinator.match(id: matchID) }
    private var editable: Bool { role == .admin }
    private var players: [Player] { reviewedMatch?.payload?.ourTeam.players.sorted(by: Player.rosterOrder) ?? [] }
    private var links: [MatchPlayerLink] { choices.filter { $0.key != $0.value }.map { MatchPlayerLink(sourceID: $0.key, targetID: $0.value) }.sorted { $0.sourceID.uuidString < $1.sourceID.uuidString } }
    private var changed: Bool { Dictionary(uniqueKeysWithValues: (reviewedMatch?.playerLinks ?? []).map { ($0.sourceID,$0.targetID) }) != choices }
    private var ambiguous: Bool { Set(players.map { choices[$0.id] ?? $0.id }).count != players.count }
    private var unavailableTarget: Bool { choices.values.contains { id in !(profile?.players.contains { $0.sourceID == id } ?? false) } }
    private func targetName(_ id: UUID) -> String { profile?.players.first { $0.sourceID == id }.map { $0.localPlayer.compactName + ($0.isArchived ? "（已停用）" : "") } ?? "原对应球员" }
    private func summary(_ source: UUID, _ links: [MatchPlayerLink]) -> String {
        links.first { $0.sourceID == source }.map { targetName($0.targetID) } ?? "沿用当场身份"
    }
    var body: some View {
        List {
            Section {
                Text("核对本场球员是否为球队名单中的同一人。只调整本场的统计归属，保留当时姓名、背号、逐球记录和单场报告。")
                Text("同名或同背号不会自动合并；未关联的临时球员仍单独计入球队成绩。")
                if !editable { Text("管理员可修改对应关系。") }
                if !editable, let message = error ?? coordinator.message { Text(message).foregroundStyle(BMTheme.orange) }
            }
            if let profile {
                Section("本场球员 → 球队名单") {
                    ForEach(players) { player in
                        if editable {
                            Picker(selection: Binding(get: { choices[player.id]?.uuidString ?? "" }, set: { value in
                                choices[player.id] = UUID(uuidString: value); saved = false
                            })) {
                                Text(profile.players.contains { $0.sourceID == player.id } ? "保持当前球员对应" : "暂不关联").tag("")
                                ForEach(profile.players.filter { $0.sourceID != player.id }) { target in
                                    Text(targetName(target.sourceID) + (profile.players.filter { $0.name == target.name }.count > 1 ? " · " + String(target.sourceID.uuidString.prefix(4)) : "")).tag(target.sourceID.uuidString)
                                }
                            } label: { Text(player.compactName) }
                            .accessibilityIdentifier("player-link-\(player.id)")
                        } else {
                            VStack(alignment: .leading) { Text(player.compactName); Text(summary(player.id, reviewedMatch?.playerLinks ?? [])).foregroundStyle(BMTheme.secondaryText) }
                        }
                    }
                }
                if editable {
                    if ambiguous { Text("同一场两名球员不能对应到同一人，请重新核对。").foregroundStyle(BMTheme.orange) }
                    if unavailableTarget { Text("部分对应球员已不在当前名单，请重新选择。").foregroundStyle(BMTheme.orange) }
                    if changed {
                        Section("保存前核对 · 仅影响本场") {
                            ForEach(players.filter { player in choices[player.id] != reviewedMatch?.playerLinks?.first(where: { $0.sourceID == player.id })?.targetID }) { player in
                                Text("\(player.compactName)\n\(summary(player.id, reviewedMatch?.playerLinks ?? [])) → \(summary(player.id, links))")
                            }
                        }
                    }
                    Button("保存球员对应") { confirming = true }
                        .disabled(!changed || ambiguous || unavailableTarget || coordinator.busy || loading)
                        .accessibilityIdentifier("save-player-links")
                    if saved { Text("球员对应已保存，年度统计与报告已更新。").foregroundStyle(BMTheme.green).accessibilityIdentifier("player-links-saved") }
                    if let message = error ?? coordinator.message { Text(message).foregroundStyle(BMTheme.orange) }
                }
            } else if loading { ProgressView("读取名单") }
            Button("刷新比赛与名单，保留当前选择") { Task { await load(keepChoices: reviewedMatch != nil) } }
                .disabled(loading || coordinator.busy).accessibilityIdentifier("refresh-player-links")
            if !history.isEmpty {
                Section("最近 50 次对应修改") {
                    ForEach(history, id: \.revision) { change in
                        VStack(alignment: .leading, spacing: 5) {
                            Text("\(change.nickname) · \(change.deviceName)")
                            Text(Date(timeIntervalSince1970: change.occurredAt / 1000).formatted(date: .abbreviated, time: .shortened)).font(.caption)
                            ForEach(players.filter { summary($0.id, change.before) != summary($0.id, change.after) }) { player in
                                Text("\(player.compactName)：\(summary(player.id, change.before)) → \(summary(player.id, change.after))").font(.caption)
                            }
                        }
                    }
                }
            }
        }.navigationTitle("球员对应").navigationBarTitleDisplayMode(.inline)
        .disabled(loading || coordinator.busy)
        .task { if reviewedMatch == nil { await load(keepChoices: false) } }
        .alert("确认修改本场球员对应？", isPresented: $confirming) {
            Button("确认保存") { Task { await save() } }
            Button("返回核对", role: .cancel) { }
        } message: { Text("本场成绩将按刚才核对的身份归入年度统计，可在此更正或解除对应。其他比赛和当场记录保持原样。") }
    }
    private func load(keepChoices: Bool) async {
        guard let match else { return }
        loading = true; defer { loading = false }; error = nil
        await coordinator.refresh(match)
        guard coordinator.message == nil else { return }
        do {
            let detailJSON = try await coordinator.service.matchCommand(path: "teams/\(match.teamID.uuidString.lowercased())", method: "GET", body: .object([:]))
            let detail = try JSONDecoder().decode(SharedTeamDetail.self, from: JSONEncoder().encode(detailJSON))
            let historyJSON = try await coordinator.service.matchCommand(path: TeamServiceClient.matchPath(match.teamID, match.id) + "/player-links", method: "GET", body: .object([:]))
            history = try JSONDecoder().decode(PlayerLinkHistory.self, from: JSONEncoder().encode(historyJSON)).changes
            profile = detail.team; role = detail.role; reviewedMatch = coordinator.match(id: matchID)
            if !keepChoices { choices = Dictionary(uniqueKeysWithValues: (reviewedMatch?.playerLinks ?? []).map { ($0.sourceID,$0.targetID) }) }
        } catch { self.error = error.localizedDescription }
    }
    private func save() async {
        guard let reviewedMatch, let profile else { return }
        error = nil; saved = false
        do {
            let submitted = links
            await coordinator.command(reviewedMatch, resource: "player-links", fields: ["playerLinks": try .encode(submitted), "baseRosterRevision": .number(Double(profile.revision))])
            guard coordinator.message == nil, !coordinator.state.pending.contains(where: { $0.gameID == matchID }) else { return }
            guard Dictionary(uniqueKeysWithValues: (coordinator.match(id: matchID)?.playerLinks ?? []).map { ($0.sourceID,$0.targetID) })
                    == Dictionary(uniqueKeysWithValues: submitted.map { ($0.sourceID,$0.targetID) }) else {
                error = "尚未确认保存，你的选择已保留，请稍后重试。"; return
            }
            await load(keepChoices: false); saved = error == nil && coordinator.message == nil
        } catch { self.error = error.localizedDescription }
    }
}

/// Explicitly shares a snapshot; subsequent changes belong to the chosen side only.
struct LocalMatchShareView: View {
    @EnvironmentObject private var store: GameStore
    let game: StoredGame
    @State private var teamID: UUID?
    @State private var confirm = false
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ShareDestinationPicker(teamID: $teamID)
            if let teamID {
                if let match = store.sharedMatches.publishedMatch(sourceID: game.id, teamID: teamID) {
                    NavigationLink("查看已共享的比赛") { SharedMatchDetailView(coordinator: store.sharedMatches, matchID: match.id) }
                } else {
                    Button(game.status == .completed ? "导入这场比赛到共享球队" : "共享这场比赛到球队") { confirm = true }
                        .buttonStyle(SecondaryButtonStyle()).disabled(store.sharedMatches.busy).accessibilityIdentifier("share-current-local-game")
                }
            }
            if let message = store.sharedMatches.message { Text(message).font(.footnote).foregroundStyle(BMTheme.orange) }
        }
        .alert("是否共享本次比赛到共享队伍，让成员可以查看？", isPresented: $confirm) {
            Button("取消", role: .cancel) {}
            Button("确认共享") { Task { if let teamID { await store.sharedMatches.publish(game, teamID: teamID) } } }
        } message: { Text("共享队伍：" + (store.teamSharing.teams.first { $0.id == teamID }?.name ?? "") + "\n本机比赛仍会保留，两边的修改和删除互不影响。") }
    }
}

struct ShareDestinationPicker: View {
    @EnvironmentObject private var store: GameStore
    @Binding var teamID: UUID?
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("队内共享").font(.headline)
            Picker("共享到球队", selection: $teamID) {
                Text("仅保存在本机").tag(Optional<UUID>.none)
                ForEach(store.teamSharing.teams.filter { $0.role != .member }) { team in
                    Text(team.name + " · 共享球队").tag(Optional(team.id))
                }
            }.accessibilityIdentifier("share-destination-picker")
            if teamID != nil {
                Text("保存后，所选球队的成员就能查看这场比赛。本机比赛仍会保留，两边的修改和删除互不影响。要向成员更新记录，请从共享球队中打开这场比赛继续记分。")
                    .font(.footnote).foregroundStyle(BMTheme.secondaryText)
            }
        }
    }
}
