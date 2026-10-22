import SwiftUI
import CoreImage.CIFilterBuiltins

struct TeamAuditEvent: Decodable, Identifiable {
    let id: Int; let action: String; let objectID: String; let occurredAt: Double
    let nickname: String; let deviceName: String
    static func title(_ action: String) -> String {
        ["teamCreated": "创建球队", "teamProfileUpdated": "修改球队资料", "playerUpdated": "修改球员资料", "playerArchived": "停用球员",
         "invitationCreated": "邀请成员", "invitationRedeemed": "加入球队", "invitationRevoked": "撤销邀请",
         "memberLeft": "退出球队", "memberRemoved": "移除成员", "memberRoleChanged": "调整成员角色",
         "devicePairingApproved": "确认设备配对", "devicePaired": "完成设备配对", "devicePairingCancelled": "取消设备配对", "teamDeviceRemoved": "移除设备",
         "recoveryCodeInvalidated": "停用旧恢复卡", "recoveryCodeRotated": "更换恢复卡", "managementRecovered": "恢复球队管理权",
         "gameCreated": "共享比赛", "gameUpdated": "更新球队比赛", "gameDeleted": "删除比赛", "gameRestored": "恢复比赛",
         "historyImported": "导入历史比赛", "playerLinksUpdated": "修改球员对应", "handoffProposed": "发起记分交接", "handoffAccepted": "接手记分",
         "handoffCancelled": "取消记分交接", "handoffForced": "管理员接管记分", "accessChanged": "调整历史观看方式",
         "handoff:propose": "发起记分交接", "handoff:accept": "接手记分", "handoff:cancel": "取消记分交接", "handoff:force": "管理员接管记分",
         "historicalAccessChanged": "调整历史观看方式", "writerAuthorizationRevoked": "停用原设备记分权", "handoffTargetRemoved": "撤销接手设备",
         "viewerAuthorizationChanged": "调整指定观看者", "viewerInvited": "邀请观看者", "liveOpened": "开启实时直播", "liveClosed": "关闭实时直播",
         "draftSubmitted": "提交未同步记录", "draftResolved": "保存记录核对说明", "draftSupplemented": "完成补录核对", "draftDiscarded": "核对后保留球队记录",
         "teamDissolved": "解散球队", "teamRestored": "恢复球队档案", "gameClosedByDissolution": "随球队解散关闭比赛", "gameRestoredWithTeam": "随球队恢复比赛", "deviceCredentialRotated": "更新设备授权", "archiveExported": "导出球队档案", "archiveRestored": "恢复球队档案"][action] ?? "球队操作记录"
    }
}
struct TeamAuditPage: Decodable { let events: [TeamAuditEvent]; let before: Int; let hasMore: Bool; let actions: [String] }
struct DissolvedTeam: Decodable, Identifiable { let id: UUID; let name: String; let deletedAt: Double; let expiresAt: Double }
struct DissolvedTeamsView: View {
    @EnvironmentObject private var store: GameStore
    @ObservedObject var directory: TeamSharingStore
    @State private var teams: [DissolvedTeam] = []
    @State private var selected: DissolvedTeam?
    @State private var error: String?
    var body: some View {
        List {
            Text("解散前管理员的原设备，可在 30 天内恢复球队档案。恢复后只有执行恢复的这台设备保留管理权限，需要重新邀请成员、保存新的恢复卡和设置观看方式。")
            TeamSecurityStatusView(directory: directory)
            if let error { Text(error).foregroundStyle(BMTheme.orange) }
            if teams.isEmpty { Text("这台设备没有可恢复的已解散球队。") }
            ForEach(teams) { team in
                VStack(alignment: .leading, spacing: 8) {
                    Text(team.name).font(.headline)
                    Text("恢复期限：" + Date(timeIntervalSince1970: team.expiresAt / 1000).formatted()).font(.caption)
                    Button("恢复球队档案") { selected = team }.disabled(!directory.canMutate)
                        .accessibilityIdentifier("restore-dissolved-team")
                }
            }
        }.navigationTitle("已解散的球队").navigationBarTitleDisplayMode(.inline)
        .task { await load() }.toolbar { Button("刷新") { Task { await load() } }.disabled(directory.isBusy) }
        .alert("恢复球队档案？", isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } }), presenting: selected) { team in
            Button("确认恢复") { Task {
                let command = TeamSecurityCommand(path: "teams/\(team.id.uuidString.lowercased())/restore", method: "POST", fields: ["confirm": .bool(true), "name": .string(team.name)], kind: "team-restore", teamID: team.id)
                if await directory.submit(.security(command)) { await store.sharedMatches.synchronize(); await load() }
            } }
            Button("取消", role: .cancel) { }
        } message: { team in Text("恢复「\(team.name)」的名单和比赛。原成员、邀请、观看链接和恢复卡不会重新生效。请恢复后保存新的管理恢复卡。") }
    }
    private func load() async {
        do {
            struct Response: Decodable { let teams: [DissolvedTeam] }
            let value: Response = try await directory.securityRead("dissolved-teams"); teams = value.teams; error = nil
        } catch { teams = []; self.error = error.localizedDescription }
    }
}
struct DissolveTeamView: View {
    @EnvironmentObject private var store: GameStore
    @ObservedObject var directory: TeamSharingStore
    let team: SharedTeamProfile
    @State private var name = ""
    @State private var confirm = false
    private var completed: Bool { directory.state.lastSecurityReceipt?.kind == "team-dissolve" && directory.state.lastSecurityReceipt?.teamID == team.id && !directory.teams.contains { $0.id == team.id } }
    var body: some View {
        Form {
            TeamSecurityStatusView(directory: directory)
            if completed {
                Text("球队已解散。成员访问、记分、邀请和观看链接已关闭。可从「球队共享 → 已解散的球队」在 30 天内恢复档案。")
                    .accessibilityIdentifier("team-dissolved")
            } else {
                Section("解散「\(team.name)」") {
                    Text("这是对全队生效的操作。所有成员立即停止访问、记分和分享；你的原本机资料保留。请先导出需要留存的球队档案。")
                    Text("30 天内可使用解散前管理员的原设备恢复档案，成员与观看权限需要重新授权。超过期限将清除档案，备份随后按保留期限到期。")
                    TextField("输入完整球队名称", text: $name).accessibilityIdentifier("dissolve-team-name")
                    Button("解散球队", role: .destructive) { confirm = true }
                        .disabled(!directory.canMutate || name != team.name).accessibilityIdentifier("confirm-team-dissolution")
                }
            }
        }.navigationTitle("解散球队").navigationBarTitleDisplayMode(.inline)
        .alert("确认解散这支球队？", isPresented: $confirm) {
            Button("确认解散全队", role: .destructive) { Task {
                let command = TeamSecurityCommand(path: "teams/\(team.id.uuidString.lowercased())/dissolve", method: "POST", fields: ["confirm": .bool(true), "name": .string(name), "baseRevision": .number(Double(team.revision))], kind: "team-dissolve", teamID: team.id)
                if await directory.submit(.security(command)) { await store.sharedMatches.synchronize() }
            } }
            Button("取消", role: .cancel) { }
        } message: { Text("全队成员将无法继续使用共享资料，所有直播和历史观看链接将关闭。") }
    }
}
struct TeamAuditView: View {
    @ObservedObject var directory: TeamSharingStore
    let teamID: UUID
    @State private var events: [TeamAuditEvent] = []
    @State private var actions: [String] = []
    @State private var action = ""
    @State private var days = 30
    @State private var before: Int?
    @State private var more = false
    @State private var loading = false
    @State private var error: String?
    var body: some View {
        List {
            Section {
                Text("称呼和设备名称用于队内核对，不代表实名认证。仅管理员可查看。")
                Picker("时间范围", selection: $days) { Text("最近 7 天").tag(7); Text("最近 30 天").tag(30); Text("全部记录").tag(0) }
                Picker("操作类型", selection: $action) {
                    Text("全部操作").tag("")
                    ForEach(actions, id: \.self) { Text(TeamAuditEvent.title($0)).tag($0) }
                }
            }.disabled(loading)
            if let error { Text(error).foregroundStyle(BMTheme.orange) }
            ForEach(events) { event in
                VStack(alignment: .leading, spacing: 6) {
                    Text(TeamAuditEvent.title(event.action)).font(.headline)
                    Text(event.nickname + " · " + event.deviceName)
                    Text(Date(timeIntervalSince1970: event.occurredAt / 1000).formatted()).font(.caption).foregroundStyle(BMTheme.secondaryText)
                    Text("记录编号 \(event.id)").font(.caption)
                }
            }
            if events.isEmpty && !loading { Text("此范围内暂无操作记录。") }
            if more { Button("更早的记录") { Task { await load(reset: false) } }.disabled(loading) }
            if loading { ProgressView("正在读取…") }
        }.navigationTitle("球队操作记录").navigationBarTitleDisplayMode(.inline)
        .task { await load(reset: true) }
        .onChange(of: days) { _ in Task { await load(reset: true) } }
        .onChange(of: action) { _ in Task { await load(reset: true) } }
        .toolbar { Button("刷新") { Task { await load(reset: true) } }.disabled(loading) }
    }
    private func load(reset: Bool) async {
        guard !loading else { return }; loading = true; defer { loading = false }
        let from = days == 0 ? 0 : Int(Date().addingTimeInterval(-Double(days) * 86400).timeIntervalSince1970 * 1000)
        var query = "?from=\(from)&action=\(action)"
        if !reset, let before { query += "&before=\(before)" }
        do {
            let page: TeamAuditPage = try await directory.securityRead("teams/\(teamID.uuidString.lowercased())/audit" + query)
            events = reset ? page.events : events + page.events; actions = page.actions
            before = page.before; more = page.hasMore; error = nil
        } catch { events = []; more = false; self.error = error.localizedDescription }
    }
}

struct TeamSecurityStatusView: View {
    @ObservedObject var directory: TeamSharingStore
    var body: some View {
        if directory.isBusy { ProgressView("正在核对…") }
        if let error = directory.errorMessage { Text(error).foregroundStyle(BMTheme.red).accessibilityIdentifier("security-error") }
        if let title = directory.pendingTitle {
            Text("\(title)的结果尚待确认，原操作已保存。")
            Button("重试同一次操作") { Task { await directory.retryPending() } }.disabled(directory.isBusy)
                .accessibilityIdentifier("retry-security-operation")
        }
    }
}
struct NewDevicePairingView: View {
    @ObservedObject var directory: TeamSharingStore
    @State private var deviceLabel = "我的新 iPhone"
    private var pairing: DevicePairing? { directory.state.pairing?.request }
    var body: some View {
        Form {
            Section {
                Text("这台手机作为新设备。请用原设备打开配对请求，选择一支球队并核对短语；只关联该队，不转移比赛记分权。")
                TeamSecurityStatusView(directory: directory)
            }
            if let saved = directory.state.pairing {
                Section("两台设备核对同一短语") {
                    Text(saved.request.phrase).font(.title3.bold()).textSelection(.enabled).accessibilityIdentifier("pairing-phrase")
                    if let image = pairingQR(DevicePairingLink.url(saved.token).absoluteString), saved.request.status == "pending", saved.request.isPending {
                        Image(uiImage: image).interpolation(.none).resizable().scaledToFit().frame(maxWidth: 210)
                            .padding(12).background(.white).accessibilityLabel("新设备配对二维码")
                        Text(DevicePairingLink.url(saved.token).absoluteString).font(.caption).textSelection(.enabled).accessibilityIdentifier("pairing-request-url")
                        ShareLink("分享给原设备", item: DevicePairingLink.url(saved.token))
                        Text("10 分钟内有效，仅可使用一次。")
                    }
                    if saved.request.status == "pending" && saved.request.isPending { Text("等待原设备选择球队并确认。") }
                    if saved.request.status == "offered" && saved.request.isPending {
                        Text("球队：\(saved.request.teamName ?? "")")
                        Text("成员：\(saved.request.nickname ?? "") · \(saved.request.role?.title ?? "")")
                        Button("球队与身份正确，完成配对") { Task { _ = await directory.acceptPairing() } }
                            .disabled(!directory.canMutate).accessibilityIdentifier("accept-device-pairing")
                    }
                    if saved.request.status == "accepted" {
                        Text("配对完成，已关联\(saved.request.teamName ?? "球队")。原设备继续有效，可在设备列表移除。")
                            .accessibilityIdentifier("pairing-completed")
                    } else if !saved.request.isPending {
                        Text("配对已结束或失效，请生成新请求。")
                    }
                    if saved.request.isPending {
                        Button("取消本次配对", role: .destructive) { Task { _ = await directory.cancelPairing(saved.request.id) } }.disabled(!directory.canMutate)
                    }
                }
            }
            if pairing?.isPending != true {
                Section {
                    TextField("新设备名称", text: $deviceLabel).accessibilityIdentifier("pairing-device-label")
                    Button("生成新设备配对请求") { Task { await directory.createPairing(label: deviceLabel) } }
                        .disabled(!directory.canMutate || deviceLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty).accessibilityIdentifier("create-device-pairing")
                }
            }
        }.navigationTitle("接入这台新设备").navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("刷新") { Task { await directory.refreshPairing() } }.disabled(directory.isBusy) }
        .task {
            await directory.connect()
            while !Task.isCancelled { await directory.refreshPairing(); try? await Task.sleep(nanoseconds: 3_000_000_000) }
        }
    }
    private func pairingQR(_ value: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(value.utf8)
        guard let output = filter.outputImage?.transformed(by: .init(scaleX: 8, y: 8)), let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
struct ApproveDevicePairingView: View {
    @ObservedObject var directory: TeamSharingStore
    var teamID: UUID? = nil
    var initialLink = ""
    @State private var selectedTeam: UUID?
    @State private var input = ""
    @State private var preview: DevicePairing?
    @State private var approved = false
    @State private var checked = false
    var body: some View {
        Form {
            Section {
                Text("仅授权你在所选球队的成员身份。请当面或通过可信方式核对新手机上的同一短语，不给其他人使用的手机配对。")
                TeamSecurityStatusView(directory: directory)
                if teamID == nil {
                    Picker("选择要关联的球队", selection: $selectedTeam) {
                        Text("请选择球队").tag(Optional<UUID>.none)
                        ForEach(directory.teams) { Text($0.name).tag(Optional($0.id)) }
                    }.disabled(preview != nil)
                }
                TextField("粘贴新设备配对请求", text: $input).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .disabled(preview != nil).accessibilityIdentifier("pairing-request-input")
                if preview == nil {
                    Button("核对新设备") {
                        Task {
                            guard let team = teamID ?? selectedTeam else { return }
                            do { preview = try await directory.previewPairing(teamID: team, input: input); checked = false }
                            catch { directory.errorMessage = error.localizedDescription }
                        }
                    }.disabled(!directory.canMutate || (teamID ?? selectedTeam) == nil).accessibilityIdentifier("preview-device-pairing")
                }
            }
            if let preview {
                Section("核对后再授权") {
                    Text(preview.phrase).font(.title3.bold()).accessibilityIdentifier("pairing-preview-phrase")
                    Text("新设备：\(preview.targetLabel ?? "iPhone")")
                    Text("球队：\(preview.teamName ?? "")")
                    Text("你的身份：\(preview.nickname ?? "") · \(preview.role?.title ?? "")")
                    if approved {
                        Text("已授权，等待新设备确认完成。记分权保持原状。")
                            .accessibilityIdentifier("pairing-approved")
                        Button("撤销这次待确认配对", role: .destructive) {
                            Task { if await directory.cancelPairing(preview.id, teamID: preview.teamID) { self.preview = nil; approved = false } }
                        }.disabled(!directory.canMutate)
                    } else {
                        Toggle("我确认两台手机的短语一致", isOn: $checked).accessibilityIdentifier("pairing-phrase-checked")
                        Button("授权这台新设备") {
                            Task { if let token = DevicePairingLink.token(from: input) { approved = await directory.approvePairing(preview, token: token) } }
                        }.disabled(!checked || !directory.canMutate).accessibilityIdentifier("approve-device-pairing")
                        Button("取消核对，重新输入") { self.preview = nil; checked = false }
                    }
                }
            }
        }.navigationTitle("授权我的新设备").navigationBarTitleDisplayMode(.inline)
        .task { input = initialLink; await directory.connect(); if selectedTeam == nil { selectedTeam = teamID ?? directory.teams.first?.id } }
        .onChange(of: directory.state.lastSecurityReceipt?.id) { _ in
            if let receipt = directory.state.lastSecurityReceipt, receipt.kind == "pair-approve", receipt.pairingID == preview?.id { approved = true }
        }
    }
}
struct TeamDevicesView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var directory: TeamSharingStore
    let teamID: UUID
    @State private var devices: [PairedTeamDevice] = []
    @State private var removing: PairedTeamDevice?
    var body: some View {
        List {
            Section {
                Text("管理员可管理本队设备，其他成员可管理自己的设备。移除只影响本队；最后一台有效管理设备不能直接移除。")
                TeamSecurityStatusView(directory: directory)
                NavigationLink("授权我的新设备") { ApproveDevicePairingView(directory: directory, teamID: teamID) }
                    .accessibilityIdentifier("open-pairing-approval")
            }
            if let pair = directory.state.outgoingPairing, pair.teamID == teamID, pair.isPending {
                Section("待新设备确认") {
                    Text(pair.targetLabel ?? "新设备")
                    Text(pair.phrase)
                    Button("撤销待确认配对", role: .destructive) { Task { _ = await directory.cancelPairing(pair.id, teamID: teamID) } }.disabled(!directory.canMutate)
                }
            }
            Section("已授权设备") {
                ForEach(devices) { device in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(device.label + (device.isCurrent == 1 ? " · 本机" : "")).font(.headline)
                        Text("\(device.nickname) · \(device.role.title)").foregroundStyle(BMTheme.secondaryText)
                        Button("移除此设备", role: .destructive) { removing = device }
                            .disabled(!directory.canMutate).accessibilityIdentifier("remove-device-\(device.label)")
                    }
                }
            }
        }.navigationTitle("球队设备").navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("刷新") { Task { await reload() } }.disabled(directory.isBusy) }
        .task { await reload() }
        .alert("移除这台设备？", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), presenting: removing) { device in
            Button("确认移除", role: .destructive) {
                Task {
                    if await directory.removeDevice(device, teamID: teamID) {
                        await store.sharedMatches.synchronize(teamID: teamID)
                        if device.isCurrent == 1 { dismiss() } else { await reload() }
                    }
                }
            }.accessibilityIdentifier("confirm-remove-device")
            Button("取消", role: .cancel) { }
        } message: { device in Text("\(device.label) 将无法继续访问或记分。球队档案、其他设备和其他球队授权保留。涉及管理员设备时旧恢复卡同时失效，请重新生成；此前导出或离线资料无法远程收回。") }
    }
    private func reload() async {
        devices = []
        do { devices = try await directory.teamDevices(teamID); await directory.refreshOutgoingPairing() }
        catch { directory.errorMessage = error.localizedDescription }
    }
}
struct TeamRecoveryCardView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var directory: TeamSharingStore
    let teamID: UUID
    var onFinished: (() -> Void)? = nil
    @State private var status: TeamRecoveryStatus?
    @State private var confirmRotate = false
    @State private var showCode = false
    @State private var reloadID = UUID()
    private var card: SavedRecoveryCard? { directory.state.recoveryCards?.first { $0.teamID == teamID && $0.version == status?.version } }
    var body: some View {
        Form {
            Section {
                Text("恢复卡用于所有管理设备都不可用时接回球队。请离线保存，勿发到球队群；无需注册或 Apple 账号。")
                TeamSecurityStatusView(directory: directory)
            }
            if let status {
                Section(status.teamName) {
                    if let card {
                        Text(card.confirmedSaved ? "已标记保存" : "请保存管理恢复卡").font(.headline).accessibilityIdentifier("recovery-card-status")
                        Text("持卡者可恢复管理权。恢复会撤销旧设备、邀请和观看授权，比赛档案保留。")
                        if showCode {
                            Text(card.code).font(.system(.caption, design: .monospaced)).textSelection(.enabled).privacySensitive()
                                .accessibilityIdentifier("recovery-card-code")
                            ShareLink("保存／分享恢复卡", item: card.text).accessibilityIdentifier("share-recovery-card")
                            Button("复制恢复码") { UIPasteboard.general.setItems([["public.utf8-plain-text": card.code]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(300)]) }
                        } else { Button("显示恢复卡") { showCode = true }.accessibilityIdentifier("show-recovery-card") }
                        Button("我已保存恢复卡") { directory.confirmCardSaved(teamID); if directory.errorMessage == nil { onFinished?() } }
                            .disabled(!showCode || !directory.canMutate).accessibilityIdentifier("confirm-recovery-saved")
                    } else {
                        Text(status.hasCode == false ? "旧卡无效或尚未建立恢复码，请生成并保存新卡。" : "本机没有当前恢复卡。服务器不保存完整恢复码；可在原保存位置查找，或更换并保存新卡。")
                    }
                    Button(status.version == 0 ? "生成管理恢复卡" : "更换管理恢复码", role: status.version == 0 ? nil : .destructive) { confirmRotate = true }
                        .disabled(!directory.canMutate).accessibilityIdentifier("rotate-recovery-code")
                }
            }
            if onFinished != nil {
                Section { Button("稍后保存，返回球队") { onFinished?() }.disabled(directory.isBusy).accessibilityIdentifier("defer-recovery-card") }
            }
        }.navigationTitle("管理恢复卡").navigationBarTitleDisplayMode(.inline)
        .task { await reload() }
        .onChange(of: scenePhase) { phase in if phase != .active { showCode = false } }
        .toolbar { Button("刷新") { Task { await reload() } }.disabled(directory.isBusy) }
        .alert("生成新的管理恢复码？", isPresented: $confirmRotate) {
            Button("确认更换并保存新卡") { Task { if let status, await directory.rotateRecovery(status) { await reload(); showCode = true } } }
            Button("取消", role: .cancel) { }
        } message: { Text("旧恢复卡立即失效。现有成员和设备仍可使用球队；只有使用恢复卡接回管理权时才撤销旧授权。") }
        .onChange(of: directory.state.pending?.id) { _ in if directory.state.pending == nil { Task { await reload() } } }
    }
    private func reload() async {
        let requestID = UUID(); reloadID = requestID
        status = nil
        do {
            let value = try await directory.recoveryStatus(teamID)
            guard reloadID == requestID else { return }
            status = value
        } catch {
            guard reloadID == requestID else { return }
            showCode = false; directory.errorMessage = error.localizedDescription
        }
    }
}
struct RecoverTeamManagementView: View {
    @EnvironmentObject private var store: GameStore
    @ObservedObject var directory: TeamSharingStore
    @State private var input = ""
    @State private var nickname = ""
    @State private var preview: TeamRecoveryStatus?
    @State private var confirm = false
    @State private var recoveredTeam: UUID?
    var body: some View {
        Form {
            Section {
                Text("原手机仍可用时优先配对；还有其他管理员时请联系对方重新邀请。仅在管理设备都不可用时使用恢复卡。没有恢复卡，不能凭队名或昵称找回管理权。")
                TeamSecurityStatusView(directory: directory)
                SecureField("粘贴 BMREC1 开头的恢复码", text: $input).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .disabled(preview != nil).accessibilityIdentifier("management-recovery-input")
                TextField("你的队内称呼", text: $nickname).disabled(preview != nil).accessibilityIdentifier("recovery-nickname")
                if preview == nil {
                    Button("核对恢复的球队") {
                        Task { do { preview = try await directory.previewRecovery(input) } catch { directory.errorMessage = error.localizedDescription } }
                    }.disabled(!directory.canMutate || nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("preview-management-recovery")
                }
            }
            if let preview {
                Section("将恢复 \(preview.teamName)") {
                    Text("你将成为新管理员。该队所有旧设备、未用邀请、配对和观看授权立即失效；原直播和历史链接关闭。比赛档案保留，需要重新邀请成员并重新设置观看方式。新设备核对比赛后再接管记分。")
                    Button("恢复管理权", role: .destructive) { confirm = true }.disabled(!directory.canMutate).accessibilityIdentifier("recover-management")
                    Button("取消核对") { self.preview = nil }
                }
            }
        }.navigationTitle("恢复球队管理权").navigationBarTitleDisplayMode(.inline)
        .task { await directory.connect() }
        .onChange(of: directory.state.lastSecurityReceipt?.id) { _ in
            if let receipt = directory.state.lastSecurityReceipt, receipt.kind == "recovery-redeem" {
                recoveredTeam = receipt.teamID; input = ""; preview = nil
                Task { await store.sharedMatches.synchronize(teamID: receipt.teamID) }
            }
        }
        .alert("撤销旧授权并恢复管理权？", isPresented: $confirm) {
            Button("确认恢复并生成新卡", role: .destructive) {
                Task {
                    if let preview, await directory.recoverManagement(input: input, preview: preview, nickname: nickname) {
                        recoveredTeam = preview.teamID; input = ""; self.preview = nil
                        await store.sharedMatches.synchronize(teamID: preview.teamID)
                    }
                }
            }.accessibilityIdentifier("confirm-management-recovery")
            Button("取消", role: .cancel) { }
        } message: { Text("此操作会撤销这支球队的现有设备与分享授权。请确认已核对球队，并保存接下来生成的新恢复卡。") }
        .navigationDestination(isPresented: Binding(get: { recoveredTeam != nil }, set: { if !$0 { recoveredTeam = nil } })) {
            if let team = recoveredTeam { TeamRecoveryCardView(directory: directory, teamID: team) }
        }
    }
}
