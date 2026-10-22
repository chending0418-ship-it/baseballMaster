import SwiftUI
import UIKit

@MainActor
struct GamePoster {
    let game: StoredGame
    var headline = "一起到场，为球队加油"
    var venue = "场地待通知"
    var note = "欢迎队员、家长和朋友们到场观赛"

    func image() -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: CGSize(width: 1080, height: 1440), format: format).image { context in
            let ink = UIColor(red: 0.10, green: 0.11, blue: 0.10, alpha: 1)
            let vermilion = UIColor(red: 0.78, green: 0.16, blue: 0.12, alpha: 1)
            let paper = UIColor(red: 0.96, green: 0.95, blue: 0.91, alpha: 1)
            paper.setFill(); context.fill(CGRect(x: 0, y: 0, width: 1080, height: 1440))
            func block(_ rect: CGRect, _ color: UIColor) { color.setFill(); context.fill(rect) }
            func text(_ text: String, _ rect: CGRect, size: CGFloat, color: UIColor, weight: UIFont.Weight = .regular, center: Bool = false) {
                let paragraph = NSMutableParagraphStyle()
                paragraph.alignment = center ? .center : .left
                paragraph.lineBreakMode = .byCharWrapping
                var pointSize = size
                while pointSize > 12 {
                    let font = UIFont.systemFont(ofSize: pointSize, weight: weight)
                    let bounds = (text as NSString).boundingRect(with: CGSize(width: rect.width, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: font, .paragraphStyle: paragraph], context: nil)
                    if bounds.height <= rect.height { break }
                    pointSize -= 1
                }
                (text as NSString).draw(in: rect, withAttributes: [.font: UIFont.systemFont(ofSize: pointSize, weight: weight), .foregroundColor: color, .paragraphStyle: paragraph])
            }
            block(CGRect(x: 0, y: 0, width: 18, height: 1440), vermilion)
            text("BASEBALL MASTER  /  MATCH DAY", CGRect(x: 72, y: 54, width: 730, height: 40), size: 24, color: ink, weight: .bold)
            block(CGRect(x: 830, y: 48, width: 178, height: 62), vermilion)
            text("赛事预告", CGRect(x: 838, y: 61, width: 162, height: 44), size: 29, color: paper, weight: .bold, center: true)
            text(headline, CGRect(x: 72, y: 143, width: 936, height: 80), size: 35, color: ink, weight: .medium)
            block(CGRect(x: 72, y: 247, width: 936, height: 5), ink)

            text("AWAY  ／  客队", CGRect(x: 76, y: 278, width: 920, height: 42), size: 26, color: vermilion, weight: .bold)
            text(game.state.awayTeam.name, CGRect(x: 64, y: 322, width: 952, height: 198), size: 174, color: ink, weight: .black)
            block(CGRect(x: 72, y: 552, width: 936, height: 2), ink)
            block(CGRect(x: 460, y: 523, width: 160, height: 62), vermilion)
            text("VS", CGRect(x: 460, y: 531, width: 160, height: 54), size: 40, color: paper, weight: .black, center: true)
            text("HOME  ／  主队", CGRect(x: 76, y: 599, width: 920, height: 42), size: 26, color: vermilion, weight: .bold)
            text(game.state.homeTeam.name, CGRect(x: 64, y: 643, width: 952, height: 198), size: 174, color: ink, weight: .black)
            block(CGRect(x: 72, y: 869, width: 936, height: 5), ink)

            let date = game.scheduledAt ?? game.startedAt ?? game.createdAt
            let formatter = DateFormatter(); formatter.locale = Locale(identifier: "zh_CN")
            formatter.dateFormat = "yyyy / MM.dd"
            text(formatter.string(from: date), CGRect(x: 72, y: 907, width: 592, height: 85), size: 67, color: ink, weight: .black)
            formatter.dateFormat = "EEEE"
            text(formatter.string(from: date), CGRect(x: 74, y: 997, width: 520, height: 42), size: 28, color: ink, weight: .medium)
            block(CGRect(x: 687, y: 907, width: 2, height: 123), ink)
            formatter.dateFormat = "HH:mm"
            text(formatter.string(from: date), CGRect(x: 722, y: 907, width: 286, height: 85), size: 67, color: vermilion, weight: .black)
            text("开赛 · 当地时间", CGRect(x: 725, y: 997, width: 283, height: 42), size: 25, color: ink)
            block(CGRect(x: 72, y: 1059, width: 936, height: 2), ink)
            text("球场  /  VENUE", CGRect(x: 74, y: 1085, width: 926, height: 36), size: 22, color: vermilion, weight: .bold)
            text(venue.isEmpty ? "场地待通知" : venue, CGRect(x: 72, y: 1127, width: 936, height: 82), size: 39, color: ink, weight: .bold)
            text(note, CGRect(x: 74, y: 1231, width: 926, height: 98), size: 29, color: ink)
            block(CGRect(x: 72, y: 1350, width: 936, height: 2), ink)
            let rulesText = game.status == .scheduled && game.lineup.isEmpty
                ? "比赛规则待确认"
                : "\(game.rules.scheduledInnings) 局制  /  \(game.rules.fieldersCount) 人守备\(game.rules.timeLimitMinutes.map { "  /  限时 \($0) 分钟" } ?? "")"
            text(rulesText, CGRect(x: 74, y: 1373, width: 492, height: 40), size: 21, color: ink, weight: .medium)
            text("棒球大师 · 以组织方最新通知为准", CGRect(x: 576, y: 1373, width: 432, height: 40), size: 21, color: ink)

        }
    }

    func write() throws -> URL {
        let url = try ReportExportFile.url(name: "\(game.state.awayTeam.shortName)-vs-\(game.state.homeTeam.shortName)-比赛通知", extension: "png")
        guard let data = image().pngData() else { throw CocoaError(.fileWriteUnknown) }
        try data.write(to: url, options: .atomic)
        return url
    }
}

struct GamePosterView: View {
    let game: StoredGame
    @State private var headline = "一起到场，为球队加油"
    @State private var venue = ""
    @State private var note = "欢迎队员、家长和朋友们到场观赛"
    @State private var shareFile: ExportedReportFile?
    @FocusState private var editing: Bool
    @State private var error: String?
    private var poster: GamePoster { GamePoster(game: game, headline: headline, venue: venue, note: note) }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                posterPreview
                fields
                Button(action: share) { Label("分享比赛宣传图片", systemImage: "square.and.arrow.up") }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("share-game-poster")
            }.padding(BMTheme.horizontalPadding)
        }
        .scrollDismissesKeyboard(.interactively)
        .bmScreenBackground()
        .navigationTitle("比赛宣传海报")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: headline) { value in headline = String(value.prefix(40)) }
        .onChange(of: venue) { value in venue = String(value.prefix(80)) }
        .onChange(of: note) { value in note = String(value.prefix(120)) }
        .sheet(item: $shareFile) { file in ActivityShareSheet(items: [file.url]) }
        .alert("海报生成失败", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("知道了", role: .cancel) {}
        } message: { Text(error ?? "") }
    }

    private var posterPreview: some View {
        Image(uiImage: poster.image()).resizable().scaledToFit()
            .overlay(Rectangle().stroke(BMTheme.line, lineWidth: 1))
            .accessibilityLabel("比赛海报，\(game.state.awayTeam.name)对\(game.state.homeTeam.name)，\(venue)")
            .accessibilityIdentifier("game-poster-preview")
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("海报信息").font(.headline)
            TextField("宣传标题", text: $headline).accessibilityIdentifier("poster-headline")
            TextField("比赛场地（选填）", text: $venue).accessibilityIdentifier("poster-venue")
            TextField("通知备注", text: $note, axis: .vertical).accessibilityIdentifier("poster-note")
            Text("仅用于这张海报；比赛时间和双方取自已保存的比赛。")
                .font(.footnote).foregroundStyle(BMTheme.secondaryText)
        }.textFieldStyle(.roundedBorder)
            .focused($editing)
            .submitLabel(.done)
            .onSubmit { editing = false }
    }

    private func share() {
        editing = false
        do { shareFile = ExportedReportFile(url: try poster.write()) } catch { self.error = error.localizedDescription }
    }
}
