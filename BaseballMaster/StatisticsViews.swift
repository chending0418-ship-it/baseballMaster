import SwiftUI

struct StatsOverviewView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @FocusState private var searchFocused: Bool
    @State private var selectedTeamID: UUID?
    @State private var selectedSeasonID = ""
    @State private var category = StatisticsCategory.batting
    @State private var metric = StatisticsMetric.average
    @State private var ascending = false
    @State private var query = ""
    @State private var recordsOnly = true
    @ScaledMetric(relativeTo: .body) private var metricWidth = 92

    private var team: Team? {
        store.teams.first { $0.id == (selectedTeamID ?? store.currentTeam.id) } ?? store.teams.first
    }
    private var seasonID: String {
        store.statisticsSeasons.contains(where: { $0.id == selectedSeasonID })
            ? selectedSeasonID : (store.statisticsSeasons.first?.id ?? "")
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let team {
                    let summary = store.seasonStatistics(for: team, seasonID: seasonID)
                    scopeSelection(team: team)
                    teamSummary(summary)
                    Text("仅统计已结束的本队比赛；观赛、练习和进行中比赛不计入。")
                        .font(.footnote)
                        .foregroundStyle(BMTheme.secondaryText)
                    if summary.pendingCount > 0 {
                        Label("有 \(summary.pendingCount) 条待确认记录，统计将随赛后复核更新。", systemImage: "exclamationmark.circle")
                            .font(.footnote)
                            .foregroundStyle(BMTheme.orange)
                            .accessibilityIdentifier("stats-pending-notice")
                    }
                    Picker("统计分类", selection: $category) {
                        ForEach(StatisticsCategory.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("stats-category")

                    StatisticsMetricsGrid(category: category,
                        batting: summary.batting, pitching: summary.pitching, fielding: summary.fielding
                    )
                    if summary.games.isEmpty {
                        emptyState("这个赛季还没有已结束的比赛", detail: "完成并保存比赛后，这里会自动汇总球队和球员数据。", id: "stats-no-games")
                    }
                    playerControls
                    let rows = summary.filteredPlayers(
                        category: category, metric: metric, ascending: ascending,
                        query: query, recordsOnly: recordsOnly
                    )
                    Text("\(rows.count) 名球员 · 点击查看逐场数据")
                        .font(.footnote)
                        .foregroundStyle(BMTheme.secondaryText)
                    if rows.isEmpty {
                        emptyState(
                            team.players.isEmpty && summary.players.isEmpty ? "球队还没有球员" : "没有符合条件的球员",
                            detail: "可清空搜索或关闭“仅显示有记录球员”；也可前往球队页面添加球员。",
                            id: "stats-no-players"
                        )
                    } else {
                        LazyVStack(spacing: 10) {
                            ForEach(rows) { row in
                                NavigationLink(destination: PlayerDetailView(
                                    player: row.player, initialSeasonID: seasonID,
                                    statisticsTeamID: team.id, initialCategory: category
                                )) {
                                    playerRow(row)
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("stats-player-\(row.player.name)")
                            }
                        }
                    }
                    if store.playerGameRecords.contains(where: { record in
                        record.seasonID == seasonID && team.players.contains(where: { $0.id == record.playerID })
                    }) {
                        Text("旧版个人打击记录保留在球员详情中，因缺少完整比赛信息，不计入本页球队统计。")
                            .font(.footnote)
                            .foregroundStyle(BMTheme.secondaryText)
                    }
                } else {
                    emptyState("还没有球队", detail: "先在球队页面创建球队并添加球员。", id: "stats-no-teams")
                }
            }
            .padding(BMTheme.horizontalPadding)
        }
        .scrollDismissesKeyboard(.immediately)
        .bmScreenBackground()
        .navigationTitle("统计")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let team {
                    ReportExportButton(title: "导出 PDF", identifier: "export-team-season-pdf", previewTitle: "球队赛季报告") {
                        try TeamSeasonPDFReport(store: store, team: team, seasonID: seasonID).write()
                    }
                }
            }
        }
        .onChange(of: category) { value in
            metric = value.metrics[0]
            ascending = metric.ascendingByDefault
        }
        .onChange(of: metric) { ascending = $0.ascendingByDefault }
    }

    private func scopeSelection(team: Team) -> some View {
        BMCard {
            VStack(alignment: .leading, spacing: 12) {
                Picker("球队", selection: Binding(get: { team.id }, set: { selectedTeamID = $0 })) {
                    ForEach(store.teams) { Text($0.name).tag($0.id) }
                }
                .accessibilityIdentifier("stats-team-picker")
                Divider()
                Picker("赛季", selection: Binding(get: { seasonID }, set: { selectedSeasonID = $0 })) {
                    ForEach(store.statisticsSeasons) { Text($0.name).tag($0.id) }
                }
                .accessibilityIdentifier("stats-season-picker")
            }
            .pickerStyle(.menu)
            .tint(BMTheme.green)
        }
    }

    private func teamSummary(_ summary: TeamSeasonStatistics) -> some View {
        BMCard {
            VStack(alignment: .leading, spacing: 14) {
                Text("\(summary.games.count) 场比赛 · \(summary.wins)胜\(summary.losses)负\(summary.ties)平")
                    .font(.title3.bold())
                    .foregroundStyle(BMTheme.navy)
                    .accessibilityIdentifier("stats-team-record")
                LazyVGrid(columns: [GridItem(.adaptive(minimum: metricWidth), alignment: .leading)], spacing: 14) {
                    StatisticsValue(label: "累计得分", value: "\(summary.runs)")
                    StatisticsValue(label: "累计失分", value: "\(summary.runsAllowed)")
                    StatisticsValue(label: "净胜分", value: "\(summary.runs - summary.runsAllowed)")
                }
            }
        }
    }

    private var playerControls: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("球员表现").font(.title3.bold()).foregroundStyle(BMTheme.navy)
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(BMTheme.secondaryText)
                TextField("搜索姓名、英文名或背号", text: $query)
                    .autocorrectionDisabled()
                    .focused($searchFocused)
                    .submitLabel(.search)
                    .onSubmit { searchFocused = false }
                    .accessibilityIdentifier("stats-search")
                if !query.isEmpty {
                    Button {
                        query = ""
                        searchFocused = false
                    } label: { Image(systemName: "xmark.circle.fill") }
                        .accessibilityLabel("清空搜索")
                }
            }
            .padding(12)
            .background(BMTheme.surface, in: RoundedRectangle(cornerRadius: 12))
            Toggle("仅显示有记录球员", isOn: $recordsOnly)
                .font(.subheadline)
                .tint(BMTheme.green)
                .accessibilityIdentifier("stats-records-only")
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 12) {
                        sortPicker
                        sortDirection
                    }
                } else {
                    HStack {
                        sortPicker
                        Spacer()
                        sortDirection
                    }
                }
            }
            .tint(BMTheme.green)
        }
    }

    private var sortPicker: some View {
        Menu {
            Picker("排序指标", selection: $metric) {
                ForEach(category.metrics) { Text($0.rawValue).tag($0) }
            }
        } label: {
            Label(metric.rawValue, systemImage: "arrow.up.arrow.down")
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
                .frame(minHeight: 44, alignment: .leading)
        }
        .accessibilityLabel("排序指标，\(metric.rawValue)")
        .accessibilityIdentifier("stats-sort-picker")
    }

    private var sortDirection: some View {
        Button { ascending.toggle() } label: {
            Label(ascending ? "升序" : "降序", systemImage: ascending ? "arrow.up" : "arrow.down")
                .font(.subheadline.bold())
                .fixedSize(horizontal: false, vertical: true)
                .frame(minHeight: 44)
        }
        .accessibilityIdentifier("stats-sort-direction")
    }

    private func playerRow(_ row: PlayerSeasonStatistics) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 5) {
                Text(row.player.name).font(.headline)
                Text("\(row.player.numbersText) · \(row.gamesPlayed) 场\(row.isCurrentRoster ? "" : " · 历史球员")")
                    .font(.caption).foregroundStyle(BMTheme.secondaryText)
                Text(rowDescription(row)).font(.caption).foregroundStyle(BMTheme.secondaryText)
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 5) {
                Text(metric.formattedValue(for: row)).font(.title3.bold().monospacedDigit())
                Text(metric.rawValue).font(.caption2)
            }
            .foregroundStyle(BMTheme.green)
            Image(systemName: "chevron.right").font(.caption).padding(.top, 5)
        }
        .foregroundStyle(BMTheme.navy)
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(BMTheme.surface, in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine)
    }

    private func rowDescription(_ row: PlayerSeasonStatistics) -> String {
        switch category {
        case .batting: "\(row.batting.hits) 安打 · \(row.batting.atBats) 打数 · \(row.batting.runsBattedIn) 打点"
        case .pitching: "\(row.pitching.inningsText) 局 · \(row.pitching.strikeouts) 三振 · \(row.pitching.earnedRuns) 自责分"
        case .fielding: "\(row.fielding.putouts) 刺杀 · \(row.fielding.assists) 助杀 · \(row.fielding.errors) 失误"
        }
    }

    private func emptyState(_ title: String, detail: String, id: String) -> some View {
        BMCard {
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(.headline).foregroundStyle(BMTheme.navy)
                Text(detail).font(.subheadline).foregroundStyle(BMTheme.secondaryText)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityIdentifier(id)
    }
}

struct StatisticsValue: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(value).font(.title3.bold().monospacedDigit()).foregroundStyle(BMTheme.navy)
            Text(label).font(.caption).foregroundStyle(BMTheme.secondaryText)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label)：\(value)")
    }
}

struct StatisticsMetricsGrid: View {
    let category: StatisticsCategory
    let batting: BattingLine
    let pitching: PitchingLine
    let fielding: FieldingLine
    @ScaledMetric(relativeTo: .body) private var metricWidth = 92

    init(category: StatisticsCategory, batting: BattingLine, pitching: PitchingLine, fielding: FieldingLine) {
        self.category = category
        self.batting = batting
        self.pitching = pitching
        self.fielding = fielding
    }

    init(category: StatisticsCategory, row: PlayerSeasonStatistics) {
        self.init(category: category, batting: row.batting, pitching: row.pitching, fielding: row.fielding)
    }

    private var values: [(String, String)] {
        let b = batting
        let p = pitching
        let f = fielding
        switch category {
        case .batting:
            return [("打率 AVG", b.atBats > 0 ? statText(b.average) : "—"),
                    ("上垒率 OBP", b.atBats + b.walks + b.hitByPitch + b.sacrifices > 0 ? statText(b.onBasePercentage) : "—"),
                    ("长打率 SLG", b.atBats > 0 ? statText(b.slugging) : "—"),
                    ("攻击指数 OPS", b.atBats > 0 ? statText(b.ops) : "—"),
                    ("打席 PA", "\(b.plateAppearances)"), ("打数 AB", "\(b.atBats)"),
                    ("安打 H", "\(b.hits)"), ("本垒打 HR", "\(b.homeRuns)"),
                    ("打点 RBI", "\(b.runsBattedIn)"), ("保送 BB", "\(b.walks)"),
                    ("三振 SO", "\(b.strikeouts)"), ("盗垒 SB", "\(b.stolenBases)")]
        case .pitching:
            return [("投球局数 IP", p.inningsText), ("防御率 ERA", p.outsRecorded > 0 ? String(format: "%.2f", p.era) : "—"),
                    ("每局被上垒率 WHIP", p.outsRecorded > 0 ? String(format: "%.2f", p.whip) : "—"), ("用球数 P", "\(p.pitches)"),
                    ("好球 S", "\(p.strikes)"), ("面对打者 BF", "\(p.battersFaced)"),
                    ("被安打 H", "\(p.hits)"), ("失分 R", "\(p.runs)"), ("自责分 ER", "\(p.earnedRuns)"),
                    ("保送 BB", "\(p.walks)"), ("三振 SO", "\(p.strikeouts)"), ("暴投 WP", "\(p.wildPitches)")]
        case .fielding:
            return [("守备率 FPCT", f.percentage.map(statText) ?? "—"),
                    ("守备机会 TC", "\(f.chances)"), ("刺杀 PO", "\(f.putouts)"),
                    ("助杀 A", "\(f.assists)"), ("失误 E", "\(f.errors)"), ("参与双杀 DP", "\(f.doublePlays)")]
        }
    }

    var body: some View {
        BMCard {
            VStack(alignment: .leading, spacing: 14) {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: metricWidth), alignment: .leading)], alignment: .leading, spacing: 16) {
                    ForEach(values, id: \.0) { label, value in
                        StatisticsValue(label: label, value: value)
                    }
                }
                if category == .pitching {
                    Text("ERA 按 7 局计算；局数小数位表示出局数，0.1 为 1 人出局。")
                        .font(.caption).foregroundStyle(BMTheme.secondaryText)
                } else if category == .fielding {
                    Text("守备率 =（刺杀 + 助杀）÷ 守备机会；DP 为球员参与次数。")
                        .font(.caption).foregroundStyle(BMTheme.secondaryText)
                }
                Text("“—”表示没有计算该比率所需的记录。")
                    .font(.caption).foregroundStyle(BMTheme.secondaryText)
            }
        }
        .accessibilityIdentifier("stats-metrics-\(category.rawValue)")
    }
}
