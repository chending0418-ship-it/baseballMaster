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
                     : "备份包含球队、名单、赛季、所有比赛及逐球记录。你可以存入“文件”或通过系统分享转移到另一台设备。")
                    .foregroundStyle(BMTheme.secondaryText)
                if !store.requiresDataRecovery {
                    Button {
                        perform { shareFile = ExportedReportFile(url: try store.exportBackup()) }
                    } label: { Label("导出完整备份", systemImage: "square.and.arrow.up") }
                        .buttonStyle(PrimaryButtonStyle())
                        .accessibilityIdentifier("export-local-backup")
                }
                Button { importing = true } label: { Label("选择备份文件恢复", systemImage: "square.and.arrow.down") }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("import-local-backup")
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
                        Text("启动和退到后台时自动保留最近 3 份完整备份。自动备份仍在 App 内，卸载 App 会一并删除。请定期导出到 App 之外保存。")
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
