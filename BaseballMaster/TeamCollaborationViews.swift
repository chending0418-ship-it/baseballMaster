import SwiftUI
import CoreImage.CIFilterBuiltins

struct SharedTeamsEntryView: View {
    @ObservedObject var directory: TeamSharingStore
    var body: some View {
        NavigationLink {
            SharedTeamsView(directory: directory)
        } label: {
            BMCard {
                HStack(spacing: 12) {
                    Image(systemName: "person.2.badge.gearshape.fill").foregroundStyle(BMTheme.green)
                    VStack(alignment: .leading, spacing: 5) {
                        Text("球队共享").font(.headline).foregroundStyle(BMTheme.navy)
                        Text("创建共享球队，或通过邀请加入").font(.subheadline).foregroundStyle(BMTheme.secondaryText)
                    }
                    Spacer(); Image(systemName: "chevron.right").foregroundStyle(BMTheme.secondaryText)
                }
            }
        }.buttonStyle(.plain).accessibilityIdentifier("open-team-sharing")
        .sheet(isPresented: Binding(get: { directory.incomingPairing != nil }, set: { if !$0 { directory.incomingPairing = nil } })) {
            NavigationStack { ApproveDevicePairingView(directory: directory, initialLink: directory.incomingPairing ?? "") }
        }
        .sheet(isPresented: Binding(get: { directory.incomingInvitation != nil }, set: { if !$0 { directory.incomingInvitation = nil } })) {
            NavigationStack { JoinSharedTeamView(directory: directory, initialLink: directory.incomingInvitation ?? "") }
        }
    }
}

struct SharedTeamsView: View {
    @EnvironmentObject private var store: GameStore
    @ObservedObject var directory: TeamSharingStore
    @State private var showCreate = false
    @State private var showJoin = false
    @State private var resetCredential = false
    @State private var preservedBulkEditor: RosterEditorContext?
    private var preservedBulkDrafts: [PreservedBulkDraftItem] { bulkDraftItems(directory) }
    private func preservedBatchButton(_ item: PreservedBulkDraftItem) -> some View {
        Button {
            let draft = item.draft
            let profile = directory.state.profiles?.first { $0.id == draft.teamID }
                ?? SharedTeamProfile(id: draft.teamID, name: "原共享球队", revision: draft.baseRevision,
                                     shortName: "", city: "", players: [])
            preservedBulkEditor = RosterEditorContext(profile: profile, kind: "players", draft: draft)
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text("核对保留的批量名单")
                Text(item.summary(teamName: directory.state.profiles?.first { $0.id == item.draft.teamID }?.name))
                    .font(.caption).foregroundStyle(BMTheme.secondaryText)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }.buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("open-preserved-bulk-roster")
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("仅受邀成员可访问。无需注册账号；本机球队可继续独立使用。")
                    .foregroundStyle(BMTheme.secondaryText)
                SharedTeamStatusView(directory: directory)
                if !preservedBulkDrafts.isEmpty {
                    SectionHeader(title: "保留的批量名单", subtitle: "\(preservedBulkDrafts.count) 个批次")
                    ForEach(preservedBulkDrafts) { item in preservedBatchButton(item) }
                    Text("每个批次的完整输入分别保存在本机；无球队权限时可查看，恢复权限并核对后才能新增。等待确认结果的批次仍须原样重试。")
                        .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                }
                if directory.credentialExpired {
                    Button("重新接入本机") { resetCredential = true }.buttonStyle(SecondaryButtonStyle())
                    Text("设备授权已失效。重新接入后，需要原设备配对、管理员邀请或管理恢复卡来重新获得球队权限。")
                        .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                }
                if directory.state.requiresRecoveryReview || store.sharedMatches.state.requiresRecoveryReview {
                    NavigationLink("核对恢复后的球队资料") { ServiceRecoveryReviewView(directory: directory, coordinator: store.sharedMatches) }
                        .buttonStyle(PrimaryButtonStyle()).accessibilityIdentifier("open-service-recovery-review")
                }
                if !store.sharedMatches.state.conflicts.isEmpty {
                    NavigationLink("本机保留的待核对记录") { PreservedMatchDraftsView(coordinator: store.sharedMatches) }
                        .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("open-preserved-match-drafts")
                }
                Button("新建共享球队") { showCreate = true }
                    .buttonStyle(PrimaryButtonStyle()).disabled(!directory.canMutate).accessibilityIdentifier("create-shared-team")
                Button("通过邀请加入") { showJoin = true }
                    .buttonStyle(SecondaryButtonStyle()).disabled(directory.isBusy || directory.pendingTitle != nil).accessibilityIdentifier("join-shared-team")
                NavigationLink("接入这台新设备") { NewDevicePairingView(directory: directory) }
                    .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("open-new-device-pairing")
                NavigationLink("使用恢复卡找回管理权") { RecoverTeamManagementView(directory: directory) }
                    .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("open-management-recovery")
                NavigationLink("已解散的球队") { DissolvedTeamsView(directory: directory) }
                    .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("open-dissolved-teams")
                SectionHeader(title: "已加入的球队", subtitle: "\(directory.teams.count) 支")
                if directory.teams.isEmpty && !directory.isBusy {
                    Text("尚无可显示的共享球队。创建或加入后，会出现在这里。")
                        .foregroundStyle(BMTheme.secondaryText)
                }
                ForEach(directory.teams) { team in
                    NavigationLink {
                        SharedTeamDetailView(directory: directory, teamID: team.id)
                    } label: {
                        BMCard {
                            HStack {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(team.name).font(.headline).foregroundStyle(BMTheme.navy)
                                    Text("队内共享 · \(team.role.title)").foregroundStyle(BMTheme.green).font(.subheadline)
                                }
                                Spacer(); Image(systemName: "chevron.right").foregroundStyle(BMTheme.secondaryText)
                            }
                        }
                    }.buttonStyle(.plain).accessibilityIdentifier("shared-team-\(team.name)")
                }
                if let date = directory.state.lastRefreshed {
                    Text("球队列表更新于 \(date.formatted(date: .abbreviated, time: .shortened))")
                        .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                }
            }.padding(BMTheme.horizontalPadding)
        }.bmScreenBackground().navigationTitle("球队共享").navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("刷新") { Task { await directory.connect() } }.disabled(directory.isBusy) }
        .task(id: directory.foregroundGeneration) { await directory.connectOnce() }
        .sheet(item: $preservedBulkEditor) { context in SharedBulkRosterEditor(directory: directory, context: context) }
        .sheet(isPresented: $showCreate) { NavigationStack { CreateSharedTeamView(directory: directory) } }
        .sheet(isPresented: $showJoin) { NavigationStack { JoinSharedTeamView(directory: directory) } }
        .alert("重新接入这台设备？", isPresented: $resetCredential) {
            Button("取消", role: .cancel) {}
            Button("重新接入") { Task {
                if await directory.reconnectExpiredDevice() {
                    if await store.sharedMatches.reconcileAfterRecovery(), await directory.reconcileAfterRecovery() { try? store.sharedMatches.finishRecoveryReview() }
                }
            } }
        } message: { Text("会建立新的本机授权，并保留本机记录供核对。不会自动恢复原球队权限；请随后通过配对、邀请或管理恢复卡接回球队。") }
    }
}

private struct SharedTeamStatusView: View {
    @ObservedObject var directory: TeamSharingStore
    var body: some View {
        if directory.isBusy { ProgressView("正在连接球队服务…").frame(maxWidth: .infinity) }
        if let error = directory.errorMessage {
            Text(error).font(.subheadline).foregroundStyle(BMTheme.red).accessibilityIdentifier("team-sharing-error")
        }
        if let title = directory.pendingTitle {
            BMCard {
                VStack(alignment: .leading, spacing: 10) {
                    Text("\(title)：等待确认结果").font(.headline)
                    Text("操作已保存在本机。重试会继续同一次操作，避免重复创建或加入。")
                        .font(.subheadline).foregroundStyle(BMTheme.secondaryText)
                    Button("重试待处理操作") { Task { await directory.retryPending() } }
                        .disabled(directory.isBusy || directory.state.requiresRecoveryReview).accessibilityIdentifier("retry-team-operation")
                }
            }
        }
    }
}

struct CreateSharedTeamView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var directory: TeamSharingStore
    @State private var name = ""
    @State private var nickname = ""
    @State private var sourceID: UUID?
    @State private var confirm = false
    @State private var createdTeam: UUID?
    @FocusState private var focusedField: String?
    private var source: Team? { store.teams.first { $0.id == sourceID } }
    private var valid: Bool { !(source?.name ?? name).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var body: some View {
        Form {
            Section("球队来源") {
                Picker("选择球队", selection: $sourceID) {
                    Text("新建空白球队").tag(Optional<UUID>.none)
                    ForEach(store.teams.filter { team in !directory.state.bindings.contains { $0.localTeamID == team.id } }) { team in
                        Text(team.name).tag(Optional(team.id))
                    }
                }.accessibilityIdentifier("shared-source-team")
                if source == nil { TextField("球队名称", text: $name).focused($focusedField, equals: "name").accessibilityIdentifier("shared-team-name") }
            }
            Section("你在队内的称呼") {
                TextField("例如：陈教练", text: $nickname).focused($focusedField, equals: "nickname").accessibilityIdentifier("shared-nickname")
                Text("创建后你是管理员。称呼用于队内辨认，无需手机号、邮箱或密码。")
                    .font(.footnote).foregroundStyle(BMTheme.secondaryText)
            }
            Section("共享范围") {
                Label(source?.name ?? (name.isEmpty ? "球队名称" : name), systemImage: "person.3.fill")
                Text("\(source?.players.count ?? 0) 名球员的姓名与背号")
                Text("受邀成员可以查看本次共享的名单。历史比赛保留在本机，本次不上传；本机名单的后续修改不会自动覆盖这份共享名单。")
                    .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                if let source {
                    DisclosureGroup("查看本次共享的球员名单") {
                        ForEach(source.sortedPlayers) { player in Text(player.lineupSelectionText).font(.subheadline) }
                    }
                }
            }
            Section {
                SharedTeamStatusView(directory: directory)
                Button("确认共享范围") { confirm = true }
                    .disabled(!valid || !directory.canMutate).accessibilityIdentifier("confirm-sharing-scope")
            }
        }.scrollDismissesKeyboard(.interactively).navigationTitle("新建共享球队").navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(directory.isBusy) }
            ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("收起键盘") { focusedField = nil } }
        }
        .interactiveDismissDisabled(directory.isBusy)
        .alert("创建这支共享球队？", isPresented: $confirm) {
            Button("创建并共享") {
                let team = source
                Task {
                    if await directory.submit(.create(name: (team?.name ?? name).trimmingCharacters(in: .whitespacesAndNewlines),
                        nickname: nickname.trimmingCharacters(in: .whitespacesAndNewlines), localTeamID: team?.id,
                        profile: team.map(SharedRosterSeed.init))) { createdTeam = directory.state.lastCreatedTeamID }
                }
            }.accessibilityIdentifier("submit-shared-team")
            Button("再核对一下", role: .cancel) { }
        } message: { Text("将共享“\(source?.name ?? name)”和 \(source?.players.count ?? 0) 名球员的名单。只有受邀成员可以访问。") }
        .onChange(of: directory.state.lastCreatedTeamID) { id in if let id { createdTeam = id } }
        .navigationDestination(isPresented: Binding(get: { createdTeam != nil }, set: { if !$0 { createdTeam = nil } })) {
            if let id = createdTeam { TeamRecoveryCardView(directory: directory, teamID: id, onFinished: { dismiss() }) }
        }
    }
}

struct JoinSharedTeamView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var directory: TeamSharingStore
    var initialLink = ""
    @State private var link = ""
    @State private var nickname = ""
    @State private var preview: SharedInvitationPreview?
    @State private var checkedToken: String?
    @State private var checking = false
    var body: some View {
        Form {
            Section("管理员发来的邀请") {
                TextField("粘贴邀请链接", text: $link).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityIdentifier("team-invite-link")
                    .onChange(of: link) { _ in preview = nil; checkedToken = nil }
                Button("核对邀请") {
                    let input = link
                    checking = true
                    Task {
                        let result = await directory.previewInvitation(input)
                        if input == link { preview = result; checkedToken = result == nil ? nil : TeamInvitationLink.token(from: input) }
                        checking = false
                    }
                }.disabled(link.isEmpty || checking || directory.isBusy).accessibilityIdentifier("preview-team-invite")
            }
            if let preview {
                Section("邀请核对") {
                    Text(preview.teamName).font(.headline).accessibilityIdentifier("invitation-team-name")
                    Text("加入后的角色：\(preview.role.title)")
                    Text(preview.role.explanation).font(.subheadline)
                    Text(preview.expiresAt > 8e15 ? "球队固定链接 · 管理员更换前有效" : "有效期至 \(Date(timeIntervalSince1970: preview.expiresAt / 1000).formatted(date: .abbreviated, time: .shortened))")
                        .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                }
                Section("你在队内的称呼") { TextField("例如：球员家长", text: $nickname).accessibilityIdentifier("join-nickname") }
                Section {
                    Button("确认加入球队") {
                        guard let token = checkedToken else { return }
                        Task {
                            if await directory.submit(.join(token: token, nickname: nickname.trimmingCharacters(in: .whitespacesAndNewlines))) { dismiss() }
                        }
                    }.disabled(!directory.canMutate || nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("confirm-join-team")
                }
            }
            Section { SharedTeamStatusView(directory: directory) }
        }.navigationTitle("加入球队").navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(directory.isBusy) } }
        .interactiveDismissDisabled(directory.isBusy)
        .onAppear { if link.isEmpty { link = initialLink } }
    }
}

private struct MemberChange: Identifiable {
    let id = UUID(); let member: SharedTeamMember; let role: SharedTeamRole; let remove: Bool
}
struct SharedTeamDetailView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var directory: TeamSharingStore
    let teamID: UUID
    @State private var detail: SharedTeamDetail?
    @State private var members: [SharedTeamMember] = []
    @State private var invitations: [SharedTeamInvitation] = []
    @State private var showInvite = false
    @State private var openedInvitationID: UUID?
    @State private var rosterEditor: RosterEditorContext?
    @State private var archivePlayer: SharedRosterPlayer?
    @State private var showNewGame = false
    @State private var change: MemberChange?
    @State private var leave = false
    @State private var loading = false
    var body: some View { membershipAlerts }

    private var page: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                SharedTeamStatusView(directory: directory)
                if loading { ProgressView("正在刷新球队资料…") }
                if let detail, directory.teams.contains(where: { $0.id == teamID }) {
                    teamContent(detail)
                } else if !loading {
                    unavailableContent
                }
            }.padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground().navigationTitle("共享球队").navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("刷新", action: refreshTeam).disabled(directory.isBusy || loading) }
        .task(id: directory.foregroundGeneration) { await refreshIfNeeded() }
        .navigationDestination(isPresented: $showNewGame) { NewGameSetupView() }
    }

    private var rosterPresentation: some View {
        page
            .sheet(item: $rosterEditor, onDismiss: rosterEditorDidDismiss) { context in
                rosterEditorView(context)
            }
            .alert(archiveTitle, isPresented: archivePresented, presenting: archivePlayer) { player in
                Button("确认") { toggleArchive(player) }
                Button("取消", role: .cancel) { }
            } message: { player in Text(archiveExplanation(player)) }
            .sheet(isPresented: $showInvite, onDismiss: invitationDidDismiss) {
                NavigationStack { InviteSharedTeamView(directory: directory, teamID: teamID) }
            }
    }

    private var membershipAlerts: some View {
        rosterPresentation
            .alert(memberChangeTitle, isPresented: memberChangePresented, presenting: change) { value in
                Button("确认调整", role: value.remove ? .destructive : nil) { applyMemberChange(value) }
                    .accessibilityIdentifier("confirm-member-change")
                Button("取消", role: .cancel) { }
            } message: { value in Text(memberChangeExplanation(value)) }
            .alert("退出这支球队？", isPresented: $leave) {
                Button("确认退出", role: .destructive, action: leaveTeam)
                Button("取消", role: .cancel) { }
            } message: {
                Text("退出后，你将无法查看这支共享球队的资料。球队资料仍会保留，本机球队和比赛也不会删除。如果只有你一名管理员，请先设置另一名管理员。")
            }
    }

    @ViewBuilder
    private func teamContent(_ detail: SharedTeamDetail) -> some View {
        teamIntroduction(detail)
        sharedRosterSection(detail)
        if detail.role == .admin { administrationSection(detail) }
        teamNavigation(detail)
    }

    @ViewBuilder
    private func teamIntroduction(_ detail: SharedTeamDetail) -> some View {
        BMCard {
            VStack(alignment: .leading, spacing: 8) {
                Text(detail.team.name).font(.title2.bold())
                Text("队内共享 · \(detail.role.title)").foregroundStyle(BMTheme.green)
                Text(detail.role.explanation).font(.subheadline).foregroundStyle(BMTheme.secondaryText)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
        NavigationLink { SharedMatchesListView(coordinator: store.sharedMatches, teamID: teamID) } label: {
            Label("球队比赛与历史", systemImage: "baseball")
        }.buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("open-shared-matches")
        if detail.role != .member {
            NavigationLink {
                TeamBroadcastSourcesView(coordinator: store.teamBroadcasts, teamID: teamID, canRotate: detail.role == .admin)
            } label: {
                Label("转播源", systemImage: "rectangle.on.rectangle")
            }.buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("open-team-broadcast-sources")
        }
        NavigationLink { StatsOverviewView(initialTeamID: teamID) } label: {
            Label("球队年度统计", systemImage: "chart.bar")
        }.buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("shared-annual-statistics")
        Text("创建、设置和记分请从首页的比赛入口进入。比赛始终保存在本机，结束后确认上传，球队成员才能查看完整档案。")
            .font(.caption).foregroundStyle(BMTheme.secondaryText)
    }

    @ViewBuilder
    private func administrationSection(_ detail: SharedTeamDetail) -> some View {
        Button("邀请成员", action: openInvitation).buttonStyle(PrimaryButtonStyle())
            .disabled(!directory.canMutate).accessibilityIdentifier("invite-team-member")
        SectionHeader(title: "成员管理", subtitle: "\(members.count) 人")
        Text("管理员 \(administratorCount) / 3 · 每支球队最多三名管理员")
            .font(.footnote).foregroundStyle(BMTheme.secondaryText).accessibilityIdentifier("team-admin-limit")
        ForEach(members) { member in memberCard(member, detail: detail) }
        if !invitations.isEmpty {
            SectionHeader(title: "邀请链接")
            ForEach(invitations) { invitation in invitationCard(invitation) }
        }
    }

    private func memberCard(_ member: SharedTeamMember, detail: SharedTeamDetail) -> some View {
        BMCard {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(memberDisplayName(member, detail: detail)).font(.headline)
                    Text(member.role.title).font(.subheadline).foregroundStyle(BMTheme.secondaryText)
                }
                Spacer()
                memberMenu(member, detail: detail)
            }
        }
    }

    private func memberMenu(_ member: SharedTeamMember, detail: SharedTeamDetail) -> some View {
        Menu {
            ForEach(SharedTeamRole.allCases.filter { $0 != member.role }, id: \.rawValue) { role in
                Button("设为\(role.title)") { change = MemberChange(member: member, role: role, remove: false) }
                    .disabled(role == .admin && administratorCount >= 3)
            }
            if member.id != detail.memberID {
                Button("移除成员", role: .destructive) { change = MemberChange(member: member, role: member.role, remove: true) }
            }
        } label: { Image(systemName: "ellipsis.circle").frame(width: 44, height: 44) }
            .disabled(!directory.canMutate).accessibilityIdentifier("manage-member-" + member.nickname)
    }

    private func invitationCard(_ invitation: SharedTeamInvitation) -> some View {
        BMCard {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("邀请\(invitation.role.title)")
                    Text(invitationExpiry(invitation)).font(.footnote).foregroundStyle(BMTheme.secondaryText)
                }
                Spacer()
                Button("撤销", role: .destructive) { revokeInvitation(invitation) }
                    .disabled(!directory.canMutate).accessibilityIdentifier("revoke-team-invitation")
            }
        }
    }

    @ViewBuilder
    private func teamNavigation(_ detail: SharedTeamDetail) -> some View {
        NavigationLink("球队设备与配对") { TeamDevicesView(directory: directory, teamID: teamID) }
            .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("open-team-devices")
        if detail.role == .admin { administratorNavigation }
        Button("退出这支球队", role: .destructive) { leave = true }
            .frame(minHeight: 44).disabled(!directory.canMutate).accessibilityIdentifier("leave-shared-team")
        if detail.role == .admin {
            NavigationLink("解散球队") { DissolveTeamView(directory: directory, team: detail.team) }
                .foregroundStyle(BMTheme.red).frame(minHeight: 44).accessibilityIdentifier("open-dissolve-team")
        }
    }

    @ViewBuilder
    private var administratorNavigation: some View {
        NavigationLink("导出球队完整档案") { TeamArchiveExportView(teamID: teamID, directory: directory) }
            .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("open-team-archive")
        NavigationLink("球队操作记录") { TeamAuditView(directory: directory, teamID: teamID) }
            .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("open-team-audit")
        NavigationLink { TeamRecoveryCardView(directory: directory, teamID: teamID) } label: {
            Label(recoveryCardTitle, systemImage: "key.fill")
        }.buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("open-team-recovery-card")
    }

    @ViewBuilder
    private var unavailableContent: some View {
        Text("请联网刷新后查看球队资料。").foregroundStyle(BMTheme.secondaryText)
        preservedBatchButtons
    }

    @ViewBuilder
    private var preservedBatchButtons: some View {
        let items = bulkDraftItems(directory, teamID: teamID)
        if !items.isEmpty {
            SectionHeader(title: "保留的批量输入", subtitle: "\(items.count) 个批次")
            ForEach(items) { item in
                Button { openPreservedBulkDraft(item.draft) } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("查看并核对这一批名单")
                        Text(item.summary()).font(.caption).foregroundStyle(BMTheme.secondaryText)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.accessibilityIdentifier("resume-bulk-roster-draft")
            }
        }
    }

    @ViewBuilder
    private func rosterEditorView(_ context: RosterEditorContext) -> some View {
        if context.kind == "players" { SharedBulkRosterEditor(directory: directory, context: context) }
        else { NavigationStack { SharedRosterEditor(directory: directory, context: context) } }
    }

    private var administratorCount: Int { members.filter { $0.role == .admin }.count }
    private var recoveryCardTitle: String {
        directory.state.recoveryCards?.first(where: { $0.teamID == teamID })?.confirmedSaved == true
            ? "管理恢复卡" : "管理恢复卡 · 请确认已保存"
    }
    private var archiveTitle: String { archivePlayer?.isArchived == true ? "恢复这位球员？" : "停用这位球员？" }
    private var archivePresented: Binding<Bool> {
        Binding(get: { archivePlayer != nil }, set: { if !$0 { archivePlayer = nil } })
    }
    private var memberChangeTitle: String { change?.remove == true ? "移除这位成员？" : "调整成员角色？" }
    private var memberChangePresented: Binding<Bool> {
        Binding(get: { change != nil }, set: { if !$0 { change = nil } })
    }
    private func memberDisplayName(_ member: SharedTeamMember, detail: SharedTeamDetail) -> String {
        member.nickname + (member.id == detail.memberID ? "（你）" : "")
    }
    private func invitationExpiry(_ invitation: SharedTeamInvitation) -> String {
        invitation.expiresAt > 8e15 ? "固定链接 · 可供多人加入"
            : Date(timeIntervalSince1970: invitation.expiresAt / 1000).formatted(date: .abbreviated, time: .shortened)
    }
    private func archiveExplanation(_ player: SharedRosterPlayer) -> String {
        player.isArchived ? "恢复后，新比赛可再次选择这位球员。" : "从新比赛可选名单移除；已开始和已结束比赛的阵容、成绩都保留。"
    }
    private func memberChangeExplanation(_ value: MemberChange) -> String {
        value.remove ? "\(value.member.nickname) 将无法继续访问球队资料。球队记录保留。"
            : "将“\(value.member.nickname)”设为\(value.role.title)。\(value.role.explanation)"
    }
    private func openPreservedBulkDraft(_ draft: SharedRosterChange) {
        let profile = detail?.team ?? directory.state.profiles?.first { $0.id == draft.teamID }
            ?? SharedTeamProfile(id: teamID, name: "原共享球队", revision: draft.baseRevision, shortName: "", city: "", players: [])
        rosterEditor = RosterEditorContext(profile: profile, kind: "players", draft: draft)
    }
    private func openInvitation() {
        openedInvitationID = directory.state.fixedInvitations?.first { $0.teamID == teamID }?.invitation.id
        showInvite = true
    }
    private func refreshTeam() { Task { await directory.connect(); await reload() } }
    private func revokeInvitation(_ invitation: SharedTeamInvitation) {
        Task { _ = await directory.submit(.revoke(teamID: teamID, invitationID: invitation.id)); await reload() }
    }
    private func applyMemberChange(_ value: MemberChange) {
        Task { _ = await directory.submit(.member(teamID: teamID, member: value.member, role: value.role, remove: value.remove)); await reload() }
    }
    private func leaveTeam() { Task { if await directory.submit(.leave(teamID: teamID)) { dismiss() } } }
    private func toggleArchive(_ player: SharedRosterPlayer) {
        Task {
            guard let detail else { return }
            var changed = player; changed.archived = !player.isArchived
            let request = SharedRosterChange(teamID: teamID, baseRevision: detail.team.revision, kind: "player", player: changed)
            _ = await directory.submit(.roster(request))
            await reload()
        }
    }
    private func rosterEditorDidDismiss() {
        if directory.cachedDetail(teamID: teamID)?.team.revision != detail?.team.revision { Task { await reload() } }
    }
    private func invitationDidDismiss() {
        if directory.state.fixedInvitations?.first(where: { $0.teamID == teamID })?.invitation.id != openedInvitationID { Task { await reload() } }
    }
    @MainActor private func refreshIfNeeded() async {
        if let cached = directory.detailCache[teamID] { detail = cached.0; members = cached.1; invitations = cached.2 }
        if directory.needsRefresh("detail-\(teamID)") { await reload() }
    }

    @ViewBuilder
    private func sharedRosterSection(_ detail: SharedTeamDetail) -> some View {
        SectionHeader(title: "共享名单", subtitle: "\(detail.team.players.filter { !$0.isArchived }.count) 名现役球员")
        if detail.role.canEditPlayers {
            HStack {
                if detail.role == .admin {
                Button("编辑球队") { rosterEditor = RosterEditorContext(profile: detail.team, kind: "profile") }
                    .accessibilityIdentifier("edit-shared-profile")
                }
                Spacer()
                Button("批量输入") { rosterEditor = RosterEditorContext(profile: detail.team, kind: "players") }
                    .accessibilityIdentifier("open-shared-bulk-roster")
                Button("添加球员") { rosterEditor = RosterEditorContext(profile: detail.team, kind: "player") }
                    .accessibilityIdentifier("add-shared-player")
            }.disabled(!directory.canMutate)
            if let draft = directory.state.rosterDraft, draft.teamID == teamID, draft.kind != "players" && (draft.kind == "player" || detail.role == .admin) {
                Button("继续核对未保存的名单") { rosterEditor = RosterEditorContext(profile: detail.team, kind: draft.kind, player: draft.player, draft: draft) }
                    .accessibilityIdentifier("resume-roster-draft")
            }
        }
        preservedBatchButtons
        if detail.team.players.isEmpty { Text("这支球队还没有共享球员名单。").foregroundStyle(BMTheme.secondaryText) }
        ForEach([false, true], id: \.self) { archived in
            if archived && detail.team.players.contains(where: \.isArchived) { SectionHeader(title: "已停用球员", subtitle: "历史成绩保留，可恢复到现役名单") }
            ForEach(detail.team.players.filter { $0.isArchived == archived }) { player in
                sharedPlayerCard(player, detail: detail)
            }
        }
    }

    private func sharedPlayerCard(_ player: SharedRosterPlayer, detail: SharedTeamDetail) -> some View {
        BMCard {
            HStack {
                VStack(alignment: .leading) { Text(player.name).font(.headline); Text(player.numberText).foregroundStyle(BMTheme.green) }
                Spacer()
                if detail.role.canEditPlayers {
                    Menu {
                        Button("编辑资料") { rosterEditor = RosterEditorContext(profile: detail.team, kind: "player", player: player) }
                        if detail.role == .admin {
                        Button(player.isArchived ? "恢复现役" : "停用球员") { archivePlayer = player }
                        }
                    } label: { Image(systemName: "ellipsis.circle").frame(width: 44, height: 44) }
                        .disabled(!directory.canMutate).accessibilityIdentifier(playerActionIdentifier(player))
                }
            }
        }
    }

    private func playerActionIdentifier(_ player: SharedRosterPlayer) -> String {
        "edit-shared-player-" + player.name
    }

    @MainActor private func reload() async {
        guard !loading else { return }
        loading = true; detail = directory.cachedDetail(teamID: teamID); members = []; invitations = []
        if let result = await directory.details(teamID: teamID) { detail = result.0; members = result.1; invitations = result.2 }
        loading = false
    }
}

private struct InviteSharedTeamView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var directory: TeamSharingStore
    let teamID: UUID
    @State private var rotate = false
    private var invitation: SavedTeamInvitation? { directory.state.fixedInvitations?.first { $0.teamID == teamID } }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                SharedTeamStatusView(directory: directory)
                Text("同一球队使用固定链接，可邀请多人，以成员身份加入。加入后管理员可调整为记录员。")
                if let saved = invitation {
                    let url = TeamInvitationLink.url(token: saved.token)
                    if let image = qrImage(url.absoluteString) {
                        Image(uiImage: image).interpolation(.none).resizable().scaledToFit().frame(maxWidth: 210)
                            .padding(15).background(.white).frame(maxWidth: .infinity).accessibilityLabel("球队邀请二维码")
                    }
                    Text(url.absoluteString).font(.caption).textSelection(.enabled).accessibilityIdentifier("team-invitation-url")
                    ShareLink(item: url) { Label("分享邀请", systemImage: "square.and.arrow.up") }.buttonStyle(PrimaryButtonStyle())
                    Button("复制邀请链接") { UIPasteboard.general.setItems([["public.utf8-plain-text": url.absoluteString]], options: [.localOnly: true]) }
                        .accessibilityIdentifier("copy-team-invitation")
                    Button("更换邀请链接", role: .destructive) { rotate = true }.disabled(!directory.canMutate)
                        .accessibilityIdentifier("rotate-team-invitation")
                    Text("链接一直有效，手动更换后旧链接失效，已加入成员不受影响。未安装 App 的成员可从页面下载。")
                        .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                } else {
                    Button("获取球队邀请链接") { Task { _ = await directory.submit(.fixedInvite(teamID: teamID, rotate: false)) } }
                        .disabled(!directory.canMutate).accessibilityIdentifier("generate-team-invitation")
                }
            }.padding(BMTheme.horizontalPadding)
        }.bmScreenBackground().navigationTitle("邀请成员").navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("完成") { dismiss() }.disabled(directory.isBusy) }
        .interactiveDismissDisabled(directory.isBusy)
        .task { _ = await directory.submit(.fixedInvite(teamID: teamID, rotate: false)) }
        .alert("更换球队邀请链接？", isPresented: $rotate) {
            Button("取消", role: .cancel) {}
            Button("更换链接", role: .destructive) { Task { _ = await directory.submit(.fixedInvite(teamID: teamID, rotate: true)) } }
        } message: { Text("旧链接将立即失效。已加入成员保持原身份和权限，请向新成员分享新链接。") }
    }
    private func qrImage(_ value: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(value.utf8)
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output.transformed(by: .init(scaleX: 8, y: 8)), from: output.extent.applying(.init(scaleX: 8, y: 8))) else { return nil }
        return UIImage(cgImage: image)
    }
}

private struct PreservedBulkDraftItem: Identifiable {
    let draft: SharedRosterChange
    var id: UUID { draft.players?.first?.id ?? draft.teamID }
    func summary(teamName: String? = nil) -> String {
        let count = draft.players?.count ?? 0
        let firstName = draft.players?.first?.name ?? "待核对名单"
        let batch = "\(count) 名 · \(firstName)" + (count > 1 ? " 等" : "")
        return teamName.map { $0 + " · " + batch } ?? batch
    }
}

@MainActor
private func bulkDraftItems(_ directory: TeamSharingStore, teamID: UUID? = nil) -> [PreservedBulkDraftItem] {
    var drafts = directory.preservedBulkRosterDrafts
    if case .roster(let pending) = directory.state.pending?.command, pending.kind == "players",
       !drafts.contains(where: { $0.players?.first?.id == pending.players?.first?.id }) {
        drafts.append(pending)
    }
    return drafts.filter { teamID == nil || $0.teamID == teamID }.map { PreservedBulkDraftItem(draft: $0) }
}

private struct RosterEditorContext: Identifiable {
    let id = UUID()
    let sourceID = UUID()
    let profile: SharedTeamProfile
    let kind: String
    var player: SharedRosterPlayer? = nil
    var draft: SharedRosterChange? = nil
}
private struct SharedBulkRosterEditor: View {
    @ObservedObject var directory: TeamSharingStore
    let context: RosterEditorContext
    @State private var reviewedProfile: SharedTeamProfile
    @State private var reviewMessage = "整批新增会同步给全队；发生冲突时请刷新名单、重新预览并确认，原名单和历史比赛不变。"

    init(directory: TeamSharingStore, context: RosterEditorContext) {
        self.directory = directory; self.context = context
        _reviewedProfile = State(initialValue: context.profile)
    }

    var body: some View {
        BulkRosterImportSheet(teamName: reviewedProfile.name,
                              existingPlayers: reviewedProfile.players.filter { !$0.isArchived }.map(\.localPlayer),
                              initialInput: context.draft?.bulkInput ?? "",
                              reviewMessage: reviewMessage,
                              refreshReview: {
            if let result = await directory.details(teamID: context.profile.id) {
                reviewedProfile = result.0.team
                reviewMessage = "已刷新至名单第 \(reviewedProfile.revision) 版。请重新预览整批输入后确认新增。"
            } else {
                reviewMessage = directory.errorMessage ?? "暂未能刷新名单；输入已保留，请恢复访问后重新核对。"
            }
        }, importPlayers: { input in
            let outcome = await directory.importRoster(teamID: context.profile.id, input: input,
                                                       reviewedRevision: reviewedProfile.revision,
                                                       preservedDraftID: context.draft?.players?.first?.id)
            if case .failure(let message) = outcome { reviewMessage = message }
            return outcome
        })
        .interactiveDismissDisabled(directory.isBusy)
    }
}

private struct SharedRosterEditor: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var directory: TeamSharingStore
    let context: RosterEditorContext
    @FocusState private var fieldFocused: Bool
    @State private var showBulk = false
    @State private var name: String
    @State private var english: String
    @State private var numbers: String
    @State private var revision: Int
    @State private var latest: SharedTeamProfile
    private let playerID: UUID
    private let sourceID: UUID
    init(directory: TeamSharingStore, context: RosterEditorContext) {
        self.directory = directory; self.context = context
        let draft = context.draft
        _name = State(initialValue: context.kind == "profile" ? (draft?.name ?? context.profile.name) : (context.player?.chineseName ?? ""))
        _english = State(initialValue: context.kind == "profile" ? (draft?.shortName ?? context.profile.shortName) : (context.player?.englishName ?? ""))
        _numbers = State(initialValue: context.kind == "profile" ? (draft?.city ?? context.profile.city) : (context.player?.numbers.joined(separator: " ") ?? ""))
        _revision = State(initialValue: context.profile.revision); _latest = State(initialValue: context.profile)
        playerID = context.player?.id ?? context.id; sourceID = context.player?.sourceID ?? context.sourceID
    }
    private var parsedNumbers: [String] { numbers.split(whereSeparator: { $0.isWhitespace || $0 == "," || $0 == "，" || $0 == "/" }).map(String.init) }
    private var valid: Bool {
        if context.kind == "profile" { return !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !english.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && [name, english, numbers].allSatisfy { $0.count <= 80 } }
        return name.count <= 80 && english.count <= 80 && (!name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !english.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !parsedNumbers.isEmpty)
            && parsedNumbers.count <= 10 && Set(parsedNumbers).count == parsedNumbers.count
            && parsedNumbers.allSatisfy { $0.range(of: "^(00|0|[1-9][0-9]?)$", options: .regularExpression) != nil }
    }
    var body: some View {
        Form {
            Section {
                SharedTeamStatusView(directory: directory)
                Text("保存后同步给全队。历史比赛保留当场阵容，不改写旧姓名、背号或统计。")
            }
            if let draft = directory.state.rosterDraft, draft.teamID == context.profile.id {
                Section("你的修改已保留，请核对最新资料") {
                    if context.kind == "profile" { Text("球队当前资料：\(latest.name) · \(latest.shortName) · \(latest.city)") }
                    else if let player = latest.players.first(where: { $0.id == playerID }) { Text("球队当前资料：\(player.name) · \(player.numberText) · \(player.isArchived ? "已停用" : "现役")") }
                    else { Text("共享球队的名单中还没有这位球员。") }
                    Button("刷新最新资料，保留我的填写") {
                        Task { if let result = await directory.details(teamID: context.profile.id) { latest = result.0.team; revision = latest.revision } }
                    }.accessibilityIdentifier("refresh-roster-conflict")
                    Text("核对后点保存，仅提交当前这项修改。")
                }
            }
            if context.kind == "player" && context.player == nil {
                Section {
                    Button("批量输入多位球员") { showBulk = true }
                        .disabled(!directory.canMutate).accessibilityIdentifier("open-bulk-roster")
                }
            }
            Section(context.kind == "profile" ? "球队资料" : "球员资料") {
                TextField(context.kind == "profile" ? "球队名称" : "中文姓名", text: $name).focused($fieldFocused).accessibilityIdentifier("roster-name")
                TextField(context.kind == "profile" ? "球队简称" : "英文姓名（可选）", text: $english).focused($fieldFocused).accessibilityIdentifier("roster-english")
                TextField(context.kind == "profile" ? "城市（可选）" : "背号，如 00 12（可选）", text: $numbers).focused($fieldFocused).accessibilityIdentifier("roster-numbers")
                if context.kind == "player" { Text("多个背号用空格分隔，支持 0–99 和 00。同名或同背号球员分别保留身份。").font(.footnote) }
            }.disabled(directory.isBusy || directory.state.pending != nil)
            Section {
                Button("保存并同步") {
                    Task {
                        let change: SharedRosterChange
                        if context.kind == "profile" { change = SharedRosterChange(teamID: context.profile.id, baseRevision: revision, kind: "profile", name: name, shortName: english, city: numbers) }
                        else { change = SharedRosterChange(teamID: context.profile.id, baseRevision: revision, kind: "player", player: SharedRosterPlayer(id: playerID, sourceID: sourceID, chineseName: name, englishName: english, numbers: parsedNumbers, archived: context.player?.isArchived ?? false)) }
                        if await directory.submit(.roster(change)) { dismiss() }
                    }
                }.disabled(!valid || !directory.canMutate).accessibilityIdentifier("save-shared-roster")
            }
        }.scrollDismissesKeyboard(.interactively).navigationTitle(context.kind == "profile" ? "编辑共享球队" : "球员资料")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() }.disabled(directory.isBusy) }
            ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("收起键盘") { fieldFocused = false } }
        }
        .interactiveDismissDisabled(directory.isBusy)
        .sheet(isPresented: $showBulk) {
            SharedBulkRosterEditor(directory: directory, context: RosterEditorContext(profile: latest, kind: "players"))
        }
    }
}

struct TeamBroadcastSourcesView: View {
    let coordinator: TeamBroadcastCoordinator
    let teamID: UUID
    var canRotate = false
    var matchID: UUID?
    @State private var sources: TeamBroadcastStatus?
    @State private var message: String?
    @State private var busy = false
    @State private var confirmSwitch = false
    @State private var confirmRotate = false

    var body: some View {
        List {
            Section("当前转播") {
                Text(sources?.activeMatch ?? (sources?.activeGameID == nil ? "暂无比赛转播" : "等待本场同步"))
                    .accessibilityIdentifier("team-broadcast-current-match")
                if let matchID, let sources, sources.activeGameID != coordinator.broadcastID(for: matchID) {
                    Button("切换到本场比赛") { confirmSwitch = true }
                }
                if let message { Text(message).foregroundStyle(BMTheme.orange) }
            }
            if let code = sources?.code {
                Section {
                    source("比分＋投手", "640 × 116", code: code, view: "score")
                    source("当前打者", "600 × 108", code: code, view: "batter")
                    source("客队阵容", "440 × 540", code: code, view: "lineup", side: "away")
                    source("主队阵容", "440 × 540", code: code, view: "lineup", side: "home")
                } header: {
                    Text("固定链接")
                } footer: {
                    Text("把链接分享给导播。球队开赛后会自动更新这套链接；文字直播在比赛中单独分享。")
                }
                if canRotate {
                    Button("重置四条链接", role: .destructive) { confirmRotate = true }
                }
            }
        }
        .navigationTitle("转播源")
        .disabled(busy)
        .task { await reload() }
        .refreshable { await reload() }
        .alert("切换当前转播？", isPresented: $confirmSwitch) {
            Button("切换") { Task { await select() } }
            Button("取消", role: .cancel) {}
        } message: {
            Text("四条固定链接会一起切换到本场比赛。")
        }
        .alert("重置转播链接？", isPresented: $confirmRotate) {
            Button("重置", role: .destructive) { Task { await rotate() } }
            Button("取消", role: .cancel) {}
        } message: {
            Text("旧链接会失效，请把新链接发给导播。")
        }
    }

    private func source(_ title: String, _ size: String, code: String, view: String, side: String? = nil) -> some View {
        ShareLink(item: coordinator.url(code: code, view: view, side: side)) {
            HStack {
                Text(title)
                Spacer()
                Text(size).font(.caption).foregroundStyle(BMTheme.secondaryText)
                Image(systemName: "square.and.arrow.up")
            }
        }.accessibilityIdentifier("team-broadcast-source-" + (side ?? view))
    }

    private func reload() async {
        do { sources = try await coordinator.sources(teamID); message = nil }
        catch { sources = nil; message = error.localizedDescription }
    }

    private func select() async {
        guard let matchID, let sources else { return }
        busy = true
        defer { busy = false }
        do {
            let result = try await coordinator.select(matchID, team: teamID, expectedGameID: sources.activeGameID)
            await reload()
            if result?.selected != true { message = "请先开始比赛，或刷新后重新确认切换。" }
        } catch { message = error.localizedDescription }
    }

    private func rotate() async {
        guard let code = sources?.code else { return }
        busy = true
        defer { busy = false }
        do { sources = try await coordinator.rotate(teamID, code: code); message = nil }
        catch { await reload(); message = error.localizedDescription }
    }
}
