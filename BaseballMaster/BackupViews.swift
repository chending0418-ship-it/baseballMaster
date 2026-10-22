import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    static let baseballBackup = UTType(exportedAs: "com.jasonchen.baseballmaster.backup", conformingTo: .data)
}

struct BackupManagementView: View {
    @EnvironmentObject private var store: GameStore
    @State private var importing = false
    @State private var pendingBackup: LocalBackup?
    @State private var confirmRestore = false
    @State private var shareFile: ExportedReportFile?
    @State private var error: String?
    @State private var restored = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Label(store.requiresDataRecovery ? "原数据已保护" : "本地备份与恢复", systemImage: "externaldrive.badge.checkmark")
                    .font(.title2.bold())
                Text(store.requiresDataRecovery
                     ? "数据库无法完整读取。为避免覆盖原记录，已暂停记分和编辑；请选择完整备份恢复。恢复时会保留原数据库文件。"
                     : "备份包含球队、名单、年度比赛档案及逐球记录。你可以存入“文件”或通过系统分享转移到另一台设备。")
                    .foregroundStyle(BMTheme.secondaryText)
                if !store.requiresDataRecovery {
                    Button {
                        perform { shareFile = ExportedReportFile(url: try store.exportBackup()) }
                    } label: { Label("导出完整备份", systemImage: "square.and.arrow.up") }
                        .buttonStyle(PrimaryButtonStyle())
                        .accessibilityIdentifier("export-local-backup")
                }
                if store.requiresDataRecovery {
                    Button { perform { shareFile = ExportedReportFile(url: try store.exportOriginalData()) } }
                        label: { Label("导出原始资料供恢复", systemImage: "externaldrive") }
                        .buttonStyle(SecondaryButtonStyle())
                    Text("原始资料包含受保护的数据库文件；导出不会替换当前资料。可在排除存储问题后重新启动 App 重试。")
                        .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                }
                ForEach(store.upgradeBackupURLs, id: \.path) { url in
                    Button("查看升级保留备份") {
                        perform { pendingBackup = try LocalBackup.read(url); confirmRestore = true }
                    }.buttonStyle(SecondaryButtonStyle())
                    Button("导出这份升级保留备份") { shareFile = ExportedReportFile(url: url) }
                        .buttonStyle(SecondaryButtonStyle())
                }
                Button { importing = true } label: { Label("选择备份文件恢复", systemImage: "square.and.arrow.down") }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("import-local-backup")
                NavigationLink("球队完整档案") { TeamArchiveLibraryView() }.buttonStyle(SecondaryButtonStyle())
                if let url = store.automaticBackupURLs.first {
                    Button {
                        perform { pendingBackup = try LocalBackup.read(url); confirmRestore = true }
                    } label: { Label("使用最近自动备份恢复", systemImage: "clock.arrow.circlepath") }
                        .buttonStyle(SecondaryButtonStyle(color: BMTheme.green))
                        .accessibilityIdentifier("restore-automatic-backup")
                }
                BMCard {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("备份说明").font(.headline)
                        Text("启动和退到后台时自动保留最近 3 份完整备份；升级保留备份独立保存，不参与轮换。自动备份仍在 App 内，卸载 App 会一并删除。请定期导出到 App 之外保存。")
                        Text("恢复会替换本机全部数据，不合并名单或比赛。文件校验通过后才可确认恢复。备份未加密，请妥善保管球员与比赛资料。")
                    }.font(.subheadline).fixedSize(horizontal: false, vertical: true)
                }
                if let pendingBackup, let snapshot = try? pendingBackup.snapshot() {
                    Text("备份时间：\(ReportPDFCanvas.dateText(pendingBackup.createdAt, includesTime: true))\n\(snapshot.teams.count) 支本队 · \(snapshot.opponentTeams.count) 支对手 · \(snapshot.games.count) 场比赛")
                        .font(.subheadline)
                        .accessibilityIdentifier("backup-import-summary")
                }
            }.padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("备份与恢复")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.baseballBackup, .json, .data]) { result in
            perform {
                pendingBackup = try LocalBackup.read(result.get())
                confirmRestore = true
            }
        }
        .alert("替换本机全部数据？", isPresented: $confirmRestore) {
            Button("取消", role: .cancel) {}
            Button("确认恢复", role: .destructive) {
                perform {
                    guard let pendingBackup else { return }
                    try store.restoreBackup(pendingBackup)
                    self.pendingBackup = nil
                    restored = true
                }
            }
        } message: {
            if let pendingBackup, let snapshot = try? pendingBackup.snapshot() {
                Text("将恢复 \(ReportPDFCanvas.dateText(pendingBackup.createdAt, includesTime: true)) 的备份：\(snapshot.teams.count) 支本队、\(snapshot.games.count) 场比赛。当前数据库会另行保留，恢复后不能通过记分撤销返回。")
            }
        }
        .alert("操作未完成", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("知道了", role: .cancel) {}
        } message: { Text(error ?? "") }
        .alert("数据已恢复", isPresented: $restored) { Button("完成", role: .cancel) {} }
        .sheet(item: $shareFile) { file in ActivityShareSheet(items: [file.url]) }
    }

    private func perform(_ operation: () throws -> Void) {
        do { try operation() } catch { self.error = error.localizedDescription }
    }
}

struct TeamArchiveExportView: View {
    let teamID: UUID
    @ObservedObject var directory: TeamSharingStore
    @State private var busy = false
    @State private var share: ExportedReportFile?
    @State private var error: String?
    var body: some View {
        List {
            Text("导出全队名单、比赛、规则和球员对应关系，也保留回收站中的记录与删除标记。尚未同步到球队的记录请另外保存。")
            Text("文件不含设备授权、恢复码和观看链接。它可以在「我的 → 备份与恢复 → 球队完整档案」校验并导入为本机只读档案。档案未加密，请妥善保管。")
            Button(busy ? "正在导出并校验…" : "导出完整档案") { Task {
                busy = true; error = nil; defer { busy = false }
                do {
                    guard let client = directory.service as? TeamServiceClient else { throw LocalDataError.invalid("请连接球队服务后导出。") }
                    share = ExportedReportFile(url: try await client.exportTeamArchive(teamID: teamID))
                } catch { self.error = error.localizedDescription }
            } }.disabled(busy).accessibilityIdentifier("export-team-archive")
            if busy { ProgressView("逐场读取球队记录；资料有变化时会提示重新导出。") }
            if let error { Text(error).foregroundStyle(BMTheme.orange) }
        }.navigationTitle("球队完整档案").navigationBarTitleDisplayMode(.inline)
            .sheet(item: $share) { ActivityShareSheet(items: [$0.url]) }
    }
}

struct TeamArchiveLibraryView: View {
    private struct Item: Identifiable { let url: URL; let name: String; let count: Int; var id: URL { url } }
    @State private var items: [Item] = []
    @State private var importing = false
    @State private var pending: URL?
    @State private var summary: String?
    @State private var error: String?
    var body: some View {
        List {
            Text("保存共享球队导出的完整档案，离线查看比赛和名单。导入不会替换当前球队，也不会让你重新加入球队或恢复管理、观看权限。")
            Button("选择球队档案文件") { importing = true }.accessibilityIdentifier("import-team-archive")
            if let error { Text(error).foregroundStyle(BMTheme.orange) }
            ForEach(items) { item in NavigationLink { TeamArchiveReaderView(url: item.url) } label: {
                VStack(alignment: .leading) { Text(item.name); Text("\(item.count) 场比赛 · 本机只读档案").font(.caption).foregroundStyle(BMTheme.secondaryText) }
            } }
        }.navigationTitle("球队完整档案").navigationBarTitleDisplayMode(.inline).task { reload() }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.data, .json]) { result in
                do { let url = try result.get(); let archive = try PortableTeamArchive.validate(url); pending = url; summary = "\(archive.manifest.profile.name) · \(archive.records.filter { !$0.deleted }.count) 场有效比赛。导入后保留为本机只读档案。" }
                catch { self.error = error.localizedDescription }
            }
            .alert("档案校验通过", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } })) {
                Button("取消", role: .cancel) { pending = nil }
                Button("导入到本机") { do { if let pending { _ = try PortableTeamArchive.restore(pending) }; pending = nil; reload() } catch { self.error = error.localizedDescription } }
            } message: { Text(summary ?? "") }
    }
    private func reload() {
        do { items = try FileManager.default.contentsOfDirectory(at: PortableTeamArchive.library(), includingPropertiesForKeys: nil).filter { $0.pathExtension == "bmteam" }.map { url in
            let archive = try PortableTeamArchive.validate(url); return Item(url: url, name: archive.manifest.profile.name, count: archive.records.filter { !$0.deleted }.count)
        }; error = nil } catch { self.error = error.localizedDescription }
    }
}

struct TeamArchiveReaderView: View {
    let url: URL
    private struct Presentation: Identifiable { let id = UUID(); let store: GameStore }
    @State private var archive: VerifiedTeamArchive?
    @State private var presentation: Presentation?
    @State private var error: String?
    var body: some View {
        List {
            if let archive {
                Section(archive.manifest.profile.name) {
                    Text("这份档案保存在本机，包含导出时的比分和名单。共享球队后续的修改不会更新这份文件。")
                    ShareLink("分享这份档案", item: url)
                }
                Section("球队名单") { ForEach(archive.manifest.profile.players) { player in
                    Text("\(player.numberText) \(player.name)" + (player.isArchived ? " · 已停用" : ""))
                } }
                Section("比赛记录") { ForEach(archive.records) { record in
                    if let game = record.payload {
                        Button { do {
                            let copy = GameStore(persistenceURL: nil); try copy.installSharedCopy(game); copy.openGame(id: game.id); copy.readOnlyPresentation = true
                            presentation = Presentation(store: copy)
                        } catch { self.error = error.localizedDescription } } label: {
                            VStack(alignment: .leading) { Text("\(game.state.awayTeam.name) vs \(game.state.homeTeam.name)"); Text(record.deleted ? "导出时已在回收站" : game.effectivePlayedAt.formatted(date: .abbreviated, time: .shortened)).font(.caption) }
                        }
                    } else { Text("已清除的比赛 · 仅保留删除标记").foregroundStyle(BMTheme.secondaryText) }
                } }
            }
            if let error { Text(error).foregroundStyle(BMTheme.orange) }
        }.navigationTitle("本机球队档案").navigationBarTitleDisplayMode(.inline)
            .task { do { archive = try PortableTeamArchive.validate(url) } catch { self.error = error.localizedDescription } }
            .sheet(item: $presentation) { item in NavigationStack { BoxScoreView().environmentObject(item.store).toolbar { Button("完成") { presentation = nil } } } }
    }
}
