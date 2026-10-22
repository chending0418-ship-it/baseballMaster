import SwiftUI

struct BoxScoreView: View {
    @EnvironmentObject private var store: MockGameStore
    @State private var selectedSection = 0
    private let sections = ["打击", "投手", "守备", "记录"]

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                ScoreboardCard(game: store.game)
                LineScoreTable(game: store.game)

                Picker("数据分类", selection: $selectedSection) {
                    ForEach(sections.indices, id: \.self) { index in
                        Text(sections[index]).tag(index)
                    }
                }
                .pickerStyle(.segmented)

                Group {
                    switch selectedSection {
                    case 0: battingTable
                    case 1: pitchingTable
                    case 2: fieldingTable
                    default: playLog
                    }
                }

                if !store.game.isFinal {
                    Button {
                        store.finishGame()
                    } label: {
                        Label("结束并保存比赛结果", systemImage: "checkmark.seal.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle(color: BMTheme.brandNavy))
                }
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("比赛结果")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var battingTable: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "本队打击", subtitle: "点击球员查看详情")
            ScrollView(.horizontal, showsIndicators: false) {
                VStack(spacing: 0) {
                    tableHeader(["球员", "PA", "AB", "R", "H", "RBI", "BB", "SO", "AVG"])
                    ForEach(store.currentTeam.players.prefix(9)) { player in
                        NavigationLink(destination: PlayerDetailView(player: player)) {
                            let line = store.battingLine(for: player)
                            tableRow([
                                "#\(player.number) \(player.name)",
                                "\(line.plateAppearances)", "\(line.atBats)", "\(line.runs)", "\(line.hits)",
                                "\(line.runsBattedIn)", "\(line.walks)", "\(line.strikeouts)", statText(line.average)
                            ], emphasized: 0)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .background(BMTheme.surface)
                .clipShape(RoundedRectangle(cornerRadius: 14))
            }
        }
    }

    private var pitchingTable: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "投手表现", subtitle: "演示数据")
            ScrollView(.horizontal, showsIndicators: false) {
                VStack(spacing: 0) {
                    tableHeader(["投手", "IP", "BF", "H", "R", "ER", "BB", "SO", "P-S", "ERA"])
                    ForEach(pitchersToDisplay) { player in
                        let line = store.pitchingLine(for: player)
                        tableRow([
                            "#\(player.number) \(player.name)", line.inningsText, "\(line.battersFaced)", "\(line.hits)",
                            "\(line.runs)", "\(line.earnedRuns)", "\(line.walks)", "\(line.strikeouts)",
                            "\(line.pitches)-\(line.strikes)", String(format: "%.2f", line.era)
                        ], emphasized: 0)
                    }
                }
                .background(BMTheme.surface)
                .clipShape(RoundedRectangle(cornerRadius: 14))
            }
        }
    }

    private var fieldingTable: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "守备记录", subtitle: "主要责任人")
            ScrollView(.horizontal, showsIndicators: false) {
                VStack(spacing: 0) {
                    tableHeader(["球员", "守位", "PO", "A", "E", "DP"])
                    ForEach(store.currentTeam.players.prefix(9)) { player in
                        let line = store.fieldingLine(for: player)
                        tableRow([
                            "#\(player.number) \(player.name)", player.primaryPosition.shortName,
                            "\(line.putouts)", "\(line.assists)", "\(line.errors)", "\(line.doublePlays)"
                        ], emphasized: 0)
                    }
                }
                .background(BMTheme.surface)
                .clipShape(RoundedRectangle(cornerRadius: 14))
            }
        }
    }

    private var playLog: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "中文比赛记录", subtitle: "\(store.game.playLog.count) 条")
            if store.game.playLog.isEmpty {
                BMCard {
                    Text("还没有比赛记录")
                        .foregroundStyle(BMTheme.secondaryText)
                        .frame(maxWidth: .infinity, minHeight: 80)
                }
            } else {
                ForEach(store.game.playLog.reversed()) { entry in
                    HStack(alignment: .top, spacing: 12) {
                        Text(entry.inningLabel)
                            .font(.system(size: 12, weight: .black))
                            .foregroundStyle(entry.isIncomplete ? BMTheme.orange : BMTheme.green)
                            .frame(width: 36, height: 30)
                            .background(entry.isIncomplete ? BMTheme.orangeSoft : BMTheme.greenSoft)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(entry.text)
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(BMTheme.navy)
                                .fixedSize(horizontal: false, vertical: true)
                            if entry.isIncomplete {
                                Label("待补充守备细节", systemImage: "exclamationmark.circle.fill")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundStyle(BMTheme.orange)
                            }
                        }
                        Spacer()
                    }
                    .padding(13)
                    .background(BMTheme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 13))
                }
            }
        }
    }

    private var pitchersToDisplay: [Player] {
        let all = store.game.homeTeam.players + store.game.awayTeam.players
        let recorded = all.filter { store.pitchingLine(for: $0).pitches > 0 || store.pitchingLine(for: $0).battersFaced > 0 }
        return recorded.isEmpty ? all.filter { $0.primaryPosition == .pitcher } : recorded
    }

    private func tableHeader(_ values: [String]) -> some View {
        HStack(spacing: 0) {
            ForEach(values.indices, id: \.self) { index in
                Text(values[index])
                    .font(.system(size: 11, weight: .black))
                    .foregroundStyle(BMTheme.secondaryText)
                    .frame(width: index == 0 ? 112 : 54, alignment: index == 0 ? .leading : .center)
                    .padding(.horizontal, index == 0 ? 10 : 0)
            }
        }
        .frame(height: 38)
        .background(BMTheme.background)
    }

    private func tableRow(_ values: [String], emphasized: Int) -> some View {
        HStack(spacing: 0) {
            ForEach(values.indices, id: \.self) { index in
                Text(values[index])
                    .font(.system(size: index == emphasized ? 13 : 12, weight: index == emphasized ? .bold : .medium))
                    .foregroundStyle(index == emphasized ? BMTheme.navy : BMTheme.secondaryText)
                    .frame(width: index == 0 ? 112 : 54, alignment: index == 0 ? .leading : .center)
                    .padding(.horizontal, index == 0 ? 10 : 0)
                    .lineLimit(1)
            }
        }
        .frame(height: 46)
        .overlay(alignment: .bottom) {
            Rectangle().fill(BMTheme.line.opacity(0.65)).frame(height: 1)
        }
    }
}

struct LineScoreTable: View {
    let game: DemoGameState

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            VStack(spacing: 0) {
                lineScoreRow(label: "", innings: Array(1...game.homeRunsByInning.count).map(String.init), totals: ["R", "H", "E"], header: true)
                lineScoreRow(label: game.awayTeam.shortName, innings: game.awayRunsByInning.map(String.init), totals: ["\(game.awayScore)", "\(game.awayHits)", "\(game.awayErrors)"])
                lineScoreRow(label: game.homeTeam.shortName, innings: game.homeRunsByInning.map(String.init), totals: ["\(game.homeScore)", "\(game.homeHits)", "\(game.homeErrors)"])
            }
            .background(BMTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("逐局比分，\(game.awayTeam.shortName) \(game.awayScore)分，\(game.homeTeam.shortName) \(game.homeScore)分")
    }

    private func lineScoreRow(label: String, innings: [String], totals: [String], header: Bool = false) -> some View {
        HStack(spacing: 0) {
            Text(label)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(BMTheme.navy)
                .frame(width: 62, alignment: .leading)
                .padding(.leading, 12)
            ForEach(innings.indices, id: \.self) { index in
                Text(innings[index])
                    .font(.system(size: 12, weight: header ? .bold : .medium, design: .rounded))
                    .foregroundStyle(header ? BMTheme.secondaryText : BMTheme.navy)
                    .frame(width: 32)
            }
            Rectangle().fill(BMTheme.line).frame(width: 1, height: 24).padding(.horizontal, 5)
            ForEach(totals.indices, id: \.self) { index in
                Text(totals[index])
                    .font(.system(size: 12, weight: .black, design: .rounded))
                    .foregroundStyle(index == 0 && !header ? BMTheme.green : BMTheme.navy)
                    .frame(width: 32)
            }
        }
        .frame(height: header ? 34 : 42)
        .background(header ? BMTheme.background : BMTheme.surface)
        .overlay(alignment: .bottom) {
            if !header { Rectangle().fill(BMTheme.line.opacity(0.5)).frame(height: 1) }
        }
    }
}

struct StatsOverviewView: View {
    @EnvironmentObject private var store: MockGameStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                BMCard {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("2026 夏季")
                                    .font(.system(size: 22, weight: .black))
                                    .foregroundStyle(BMTheme.navy)
                                Text("4 场比赛 · 3胜1负")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(BMTheme.secondaryText)
                            }
                            Spacer()
                            Image(systemName: "chart.line.uptrend.xyaxis")
                                .font(.system(size: 28, weight: .bold))
                                .foregroundStyle(BMTheme.green)
                        }
                        HStack(spacing: 8) {
                            StatPill(label: "球队打率", value: ".286", color: BMTheme.green)
                            StatPill(label: "累计得分", value: "24")
                            StatPill(label: "团队防率", value: "3.12")
                        }
                    }
                }

                SectionHeader(title: "球员表现", subtitle: "点击查看累计数据")
                LazyVStack(spacing: 10) {
                    ForEach(store.currentTeam.players) { player in
                        let line = store.seasonBattingLine(for: player)
                        NavigationLink(destination: PlayerDetailView(player: player)) {
                            HStack(spacing: 12) {
                                Text("\(player.number)")
                                    .font(.system(size: 16, weight: .black, design: .rounded))
                                    .foregroundStyle(BMTheme.navy)
                                    .frame(width: 40, height: 40)
                                    .background(BMTheme.greenSoft)
                                    .clipShape(Circle())
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(player.name)
                                        .font(.system(size: 16, weight: .bold))
                                        .foregroundStyle(BMTheme.navy)
                                    Text("\(line.hits)安打 · \(line.runsBattedIn)打点 · \(line.runs)得分")
                                        .font(.system(size: 12))
                                        .foregroundStyle(BMTheme.secondaryText)
                                }
                                Spacer()
                                Text(statText(line.average))
                                    .font(.system(size: 18, weight: .black, design: .rounded))
                                    .foregroundStyle(BMTheme.green)
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundStyle(BMTheme.secondaryText)
                            }
                            .padding(14)
                            .background(BMTheme.surface)
                            .clipShape(RoundedRectangle(cornerRadius: 15))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("统计")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct PlayerDetailView: View {
    @EnvironmentObject private var store: MockGameStore
    let player: Player

    private var line: BattingLine { store.seasonBattingLine(for: player) }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                VStack(spacing: 10) {
                    Text("\(player.number)")
                        .font(.system(size: 34, weight: .black, design: .rounded))
                        .foregroundStyle(.white)
                        .frame(width: 78, height: 78)
                        .background(BMTheme.brandNavy)
                        .clipShape(Circle())
                    Text(player.name)
                        .font(.system(size: 25, weight: .black))
                        .foregroundStyle(BMTheme.navy)
                    Text("#\(player.number) · \(player.primaryPosition.fullName) · 2026 夏季")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(BMTheme.secondaryText)
                }
                .frame(maxWidth: .infinity)

                HStack(spacing: 8) {
                    StatPill(label: "AVG", value: statText(line.average), color: BMTheme.green)
                    StatPill(label: "OBP", value: statText(line.onBasePercentage))
                    StatPill(label: "SLG", value: statText(line.slugging))
                    StatPill(label: "OPS", value: statText(line.ops), color: BMTheme.orange)
                }

                BMCard {
                    VStack(alignment: .leading, spacing: 15) {
                        SectionHeader(title: "赛季打击")
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 16) {
                            detailStat("PA", line.plateAppearances)
                            detailStat("AB", line.atBats)
                            detailStat("H", line.hits)
                            detailStat("R", line.runs)
                            detailStat("2B", line.doubles)
                            detailStat("3B", line.triples)
                            detailStat("HR", line.homeRuns)
                            detailStat("RBI", line.runsBattedIn)
                            detailStat("BB", line.walks)
                            detailStat("SO", line.strikeouts)
                            detailStat("SB", line.stolenBases)
                            detailStat("TB", line.totalBases)
                        }
                    }
                }

                VStack(alignment: .leading, spacing: 10) {
                    SectionHeader(title: "最近比赛")
                    recentLine("对 天津火箭", "2-3 · 1打点", ".667")
                    recentLine("对 济南小熊", "1-3 · 1得分", ".333")
                    recentLine("对 烟台巨人", "2-4 · 2打点", ".500")
                }
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("球员数据")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func detailStat(_ label: String, _ value: Int) -> some View {
        VStack(spacing: 4) {
            Text("\(value)")
                .font(.system(size: 19, weight: .black, design: .rounded))
                .foregroundStyle(BMTheme.navy)
            Text(label)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(BMTheme.secondaryText)
        }
    }

    private func recentLine(_ opponent: String, _ result: String, _ average: String) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(opponent)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(BMTheme.navy)
                Text(result)
                    .font(.system(size: 12))
                    .foregroundStyle(BMTheme.secondaryText)
            }
            Spacer()
            Text(average)
                .font(.system(size: 17, weight: .black, design: .rounded))
                .foregroundStyle(BMTheme.green)
        }
        .padding(14)
        .background(BMTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

#Preview("比赛结果") {
    NavigationStack { BoxScoreView() }
        .environmentObject(MockGameStore())
}

#Preview("球队统计") {
    NavigationStack { StatsOverviewView() }
        .environmentObject(MockGameStore())
}

#Preview("球员详情") {
    let store = MockGameStore()
    NavigationStack { PlayerDetailView(player: store.currentTeam.players[0]) }
        .environmentObject(store)
}
