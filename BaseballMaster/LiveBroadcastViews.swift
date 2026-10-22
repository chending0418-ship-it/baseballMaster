import CoreImage.CIFilterBuiltins
import SwiftUI

struct LiveBroadcastSheet: View {
    @ObservedObject var store: GameStore
    let gameID: UUID
    @ObservedObject private var manager: LiveBroadcastManager
    @Environment(\.dismiss) private var dismiss
    @State private var confirmClose = false
    @State private var showShare = false

    init(store: GameStore, gameID: UUID) {
        self.store = store; self.gameID = gameID
        _manager = ObservedObject(wrappedValue: store.liveBroadcasts)
    }
    private var stored: StoredGame? { store.games.first { $0.id == gameID } }
    private var binding: LiveBinding? { manager.binding(for: gameID) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Label("让亲友跟上每一个打席", systemImage: "antenna.radiowaves.left.and.right")
                        .font(.title3.bold())
                    Text("网页每 10 秒自动刷新比分、垒况和打席过程，打开分享链接即可观看。")
                        .font(.subheadline).foregroundStyle(.secondary)
                    if let error = manager.credentialError {
                        Text(error).foregroundStyle(.orange)
                    }
                    if let binding {
                        Label(manager.messages[gameID] ?? (binding.closing ? "正在关闭直播…" : "正在检查同步状态…"), systemImage: binding.blocked ? "exclamationmark.triangle" : "dot.radiowaves.left.and.right")
                            .font(.subheadline).accessibilityIdentifier("live-sync-status")
                        if let date = binding.lastSync { Text("最近同步：\(date.formatted(date: .omitted, time: .standard))").font(.caption).foregroundStyle(.secondary) }
                        if let url = manager.url(for: gameID) {
                            VStack(spacing: 14) {
                                if let qr = qrImage(url.absoluteString) {
                                    Image(uiImage: qr).interpolation(.none).resizable().scaledToFit().frame(width: 180, height: 180)
                                        .padding(14).background(.white).clipShape(RoundedRectangle(cornerRadius: 14))
                                        .accessibilityLabel("观赛链接二维码")
                                }
                                Text(binding.code ?? "").font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
                                Text(url.absoluteString).font(.caption).textSelection(.enabled).multilineTextAlignment(.center)
                                Button { showShare = true } label: { Label("分享观赛链接", systemImage: "square.and.arrow.up") }
                                    .buttonStyle(PrimaryButtonStyle()).accessibilityIdentifier("share-live-link")
                            }.frame(maxWidth: .infinity)
                        }
                        if !binding.blocked && !binding.closing {
                            Button("立即重试同步") { manager.retry(gameID) }.buttonStyle(SecondaryButtonStyle())
                        }
                        Button(binding.closing ? "重试关闭直播" : "关闭直播并删除云端资料", role: .destructive) { confirmClose = true }
                            .font(.subheadline).frame(minHeight: 44).accessibilityIdentifier("close-live-broadcast")
                    } else {
                        if let message = manager.messages[gameID] { Text(message).font(.subheadline).foregroundStyle(.secondary) }
                        Button("开启文字直播") { manager.start(gameID) }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(stored?.status != .ongoing || manager.credentialError != nil)
                            .accessibilityIdentifier("start-live-broadcast")
                        if stored?.status != .ongoing { Text("进行中的正式比赛可以开启直播。").font(.caption).foregroundStyle(.secondary) }
                    }
                    Divider()
                    VStack(alignment: .leading, spacing: 10) {
                        Text("分享与保留说明").font(.headline)
                        Text("开启后公开本场球队名称、球员姓名及背号、比分和比赛过程。任何持有链接的人都能观看，请确认适合分享。")
                        Text("终场一小时后，链接和云端记录自动删除，不保留回放；连续一小时未同步也会关闭。本地比赛记录和备份不受影响。")
                        Text("记分时请保持 App 在前台并联网。断网不影响本地记分，恢复后自动补传。终场请等待“终场已同步”；停止直播后可重新开启，但需要分享新链接。")
                    }.font(.footnote).foregroundStyle(.secondary)
                }.padding(22)
            }
            .navigationTitle("文字直播").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("完成") { dismiss() } } }
            .confirmationDialog("关闭后旧链接立即失效，云端直播资料删除；本地比赛保留。", isPresented: $confirmClose, titleVisibility: .visible) {
                Button("关闭直播", role: .destructive) { manager.close(gameID) }
            }
            .sheet(isPresented: $showShare) {
                if let url = manager.url(for: gameID) { LiveActivityShareSheet(items: ["用 BaseballMaster 观看这场比赛", url]) { showShare = false } }
            }
            .onAppear { manager.requestSync() }
        }
    }
    private func qrImage(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(text.utf8)
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output.transformed(by: CGAffineTransform(scaleX: 8, y: 8)), from: output.extent.applying(CGAffineTransform(scaleX: 8, y: 8))) else { return nil }
        return UIImage(cgImage: image)
    }
}

private struct LiveActivityShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    let onComplete: () -> Void
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        controller.completionWithItemsHandler = { _, _, _, _ in
            DispatchQueue.main.async { onComplete() }
        }
        return controller
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
