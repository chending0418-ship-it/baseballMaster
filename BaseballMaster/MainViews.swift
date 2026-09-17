import SwiftUI

struct RootTabView: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        TabView(selection: $store.selectedTab) {
            NavigationStack { GameHomeView() }
                .id(store.gameNavigationID)
                .tabItem { Label("比赛", systemImage: "baseball.diamond.bases") }
                .tag(0)

            NavigationStack { TeamRosterView() }
                .tabItem { Label("球队", systemImage: "person.3.fill") }
                .tag(1)

            NavigationStack { StatsOverviewView() }
                .tabItem { Label("统计", systemImage: "chart.bar.fill") }
                .tag(2)

            NavigationStack { ProfileView() }
                .tabItem { Label("我的", systemImage: "person.crop.circle") }
                .tag(3)
        }
        .tint(BMTheme.green)
    }
}

struct GameHomeView: View {
    @EnvironmentObject private var store: GameStore
    @State private var gamePendingDeletion: StoredGame?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                teamHeader

                NavigationLink(destination: NewGameSetupView()) {
                    Label(store.ongoingGames.isEmpty ? "开始新比赛" : "再开始一场比赛", systemImage: "plus.circle.fill")
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("start-new-game")

                if !store.scheduledGames.isEmpty {
                    SectionHeader(title: "即将进行", subtitle: "\(store.scheduledGames.count) 场")
                    LazyVStack(spacing: 12) {
                        ForEach(store.scheduledGames) { stored in
                            HStack(spacing: 0) {
                                NavigationLink(destination: ScheduledGameDetailView(gameID: stored.id)) {
                                    scheduledGameContent(stored)
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("scheduled-game-\(stored.id.uuidString)")
                                Menu {
                                    Button("删除安排", systemImage: "trash", role: .destructive) {
                                        gamePendingDeletion = stored
                                    }
                                } label: {
                                    Image(systemName: "ellipsis.circle")
                                        .font(.system(size: 20))
                                        .foregroundStyle(BMTheme.secondaryText)
                                        .frame(width: 48, height: 68)
                                }
                            }
                            .background(BMTheme.surface)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: 16, style: .continuous)
                                    .stroke(BMTheme.line.opacity(0.7), lineWidth: 1)
                            }
                        }
                    }
                }

                SectionHeader(title: "正在进行", subtitle: store.ongoingGames.isEmpty ? nil : "\(store.ongoingGames.count) 场")
                if store.ongoingGames.isEmpty {
                    emptyGameCard(
                        icon: "play.slash",
                        title: "没有进行中的比赛",
                        detail: "新比赛创建后会自动保存在这里，可在 App 重启后继续。"
                    )
                } else {
                    LazyVStack(spacing: 12) {
                        ForEach(store.ongoingGames) { stored in
                            HStack(spacing: 0) {
                                NavigationLink(destination: StoredGameRouteView(gameID: stored.id, showsBoxScore: false)) {
                                    gameCardContent(stored)
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("continue-game-\(stored.id.uuidString)")

                                Menu {
                                    Button("删除比赛", systemImage: "trash", role: .destructive) {
                                        gamePendingDeletion = stored
                                    }
                                } label: {
                                    Image(systemName: "ellipsis.circle")
                                        .font(.system(size: 20))
                                        .foregroundStyle(BMTheme.secondaryText)
                                        .frame(width: 48, height: 70)
                                }
                            }
                            .background(BMTheme.surface)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: 16, style: .continuous)
                                    .stroke(BMTheme.line.opacity(0.7), lineWidth: 1)
                            }
                        }
                    }
                }

                SectionHeader(title: "最近比赛", subtitle: store.seasons.first?.name)
                if store.recentGames.isEmpty {
                    emptyGameCard(
                        icon: "clock",
                        title: "还没有已完成的比赛",
                        detail: "比赛结束并保存后，会按时间显示在这里。"
                    )
                } else {
                    LazyVStack(spacing: 10) {
                        ForEach(store.recentGames.prefix(10)) { stored in
                            HStack(spacing: 0) {
                                NavigationLink(destination: StoredGameRouteView(gameID: stored.id, showsBoxScore: true)) {
                                    recentGameContent(stored)
                                }
                                .buttonStyle(.plain)

                                Menu {
                                    Button("删除记录", systemImage: "trash", role: .destructive) {
                                        gamePendingDeletion = stored
                                    }
                                } label: {
                                    Image(systemName: "ellipsis.circle")
                                        .font(.system(size: 18))
                                        .foregroundStyle(BMTheme.secondaryText)
                                        .frame(width: 44, height: 60)
                                }
                            }
                            .background(BMTheme.surface)
                            .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                        }
                    }
                }
            }
            .padding(.horizontal, BMTheme.horizontalPadding)
            .padding(.vertical, 18)
        }
        .bmScreenBackground()
        .navigationTitle("比赛")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(destination: BaseballRulesView()) {
                    Image(systemName: "questionmark.circle")
                }
                .accessibilityLabel("规则帮助")
            }
        }
        .confirmationDialog(
            "删除比赛记录？",
            isPresented: Binding(
                get: { gamePendingDeletion != nil },
                set: { if !$0 { gamePendingDeletion = nil } }
            ),
            presenting: gamePendingDeletion
        ) { stored in
            Button("删除这场比赛", role: .destructive) {
                _ = store.deleteGame(id: stored.id)
                gamePendingDeletion = nil
            }
            Button("取消", role: .cancel) { gamePendingDeletion = nil }
        } message: { stored in
            Text(stored.status == .ongoing
                 ? "现场记录和当前比分会永久删除，此操作无法撤销。"
                 : (stored.status == .scheduled
                    ? "这项未来比赛安排会永久删除，此操作无法撤销。"
                    : "这场历史比赛会永久删除，此操作无法撤销。"))
        }
        .alert("本地数据异常", isPresented: Binding(
            get: { store.storageErrorMessage != nil },
            set: { if !$0 { store.storageErrorMessage = nil } }
        )) {
            Button("知道了", role: .cancel) { store.storageErrorMessage = nil }
        } message: {
            Text(store.storageErrorMessage ?? "")
        }
    }

    private var teamHeader: some View {
        HStack(spacing: 13) {
            TeamMark(team: store.currentTeam, size: 56)
            VStack(alignment: .leading, spacing: 4) {
                Text(store.currentTeam.name)
                    .font(.system(size: 24, weight: .black))
                    .foregroundStyle(BMTheme.navy)
                Text("\(store.seasons.first?.name ?? "暂无赛季") · \(store.currentTeam.players.count) 名球员")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(BMTheme.secondaryText)
            }
            Spacer()
            Text(recordText)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(BMTheme.green)
                .padding(.horizontal, 11)
                .padding(.vertical, 7)
                .background(BMTheme.greenSoft)
                .clipShape(Capsule())
        }
    }

    private var recordText: String {
        let completed = store.recentGames.filter {
            $0.ourTeamID == store.currentTeam.id && $0.seasonID == store.seasons.first?.id
        }
        guard !completed.isEmpty else { return "暂无战绩" }
        let wins = completed.filter { $0.ourScore > $0.opponentScore }.count
        let losses = completed.filter { $0.ourScore < $0.opponentScore }.count
        let ties = completed.count - wins - losses
        return ties == 0 ? "\(wins)胜 \(losses)负" : "\(wins)胜 \(losses)负 \(ties)平"
    }

    private func gameCardContent(_ stored: StoredGame) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                Text("\(stored.state.awayTeam.shortName)  vs  \(stored.state.homeTeam.shortName)")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(BMTheme.navy)
                HStack(spacing: 5) {
                    Text("\(stored.state.inning)局\(stored.state.isTop ? "上" : "下")")
                    if !stored.state.baseRunners.isEmpty {
                        Text("· \(stored.state.baseRunners.count) 名跑者在垒")
                    }
                }
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(BMTheme.secondaryText)
                Label(stored.isObservation ? "继续观赛记录" : "继续比赛", systemImage: stored.isObservation ? "eye.fill" : "play.fill")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(BMTheme.green)
            }
            Spacer()
            Text("\(stored.state.awayScore) : \(stored.state.homeScore)")
                .font(.system(size: 24, weight: .black, design: .rounded))
                .foregroundStyle(BMTheme.navy)
        }
        .padding(.leading, 15)
        .padding(.vertical, 14)
    }

    private func recentGameContent(_ stored: StoredGame) -> some View {
        let won = stored.ourScore > stored.opponentScore
        let tied = stored.ourScore == stored.opponentScore
        return HStack(spacing: 12) {
            Text(stored.isObservation ? "观" : (tied ? "平" : (won ? "胜" : "负")))
                .font(.system(size: 14, weight: .black))
                .foregroundStyle(.white)
                .frame(width: 36, height: 36)
                .background(stored.isObservation ? BMTheme.brandNavy : (tied ? BMTheme.orange : (won ? BMTheme.green : BMTheme.red)))
                .clipShape(Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text("\(stored.state.awayTeam.shortName) 对 \(stored.state.homeTeam.shortName)")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(BMTheme.navy)
                Text(stored.updatedAt.formatted(date: .abbreviated, time: .omitted))
                    .font(.system(size: 12))
                    .foregroundStyle(BMTheme.secondaryText)
            }
            Spacer()
            Text("\(stored.state.awayScore) - \(stored.state.homeScore)")
                .font(.system(size: 18, weight: .black, design: .rounded))
                .foregroundStyle(BMTheme.navy)
            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(BMTheme.secondaryText)
        }
        .padding(.leading, 14)
        .padding(.vertical, 12)
    }

    private func scheduledGameContent(_ stored: StoredGame) -> some View {
        HStack(spacing: 12) {
            Image(systemName: stored.isObservation ? "eye.fill" : "calendar.badge.clock")
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(stored.isObservation ? BMTheme.brandNavy : BMTheme.green)
                .frame(width: 38, height: 38)
                .background(stored.isObservation ? BMTheme.brandNavy.opacity(0.12) : BMTheme.greenSoft)
                .clipShape(Circle())
            VStack(alignment: .leading, spacing: 4) {
                Text("\(stored.state.awayTeam.shortName) 对 \(stored.state.homeTeam.shortName)")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(BMTheme.navy)
                Text(stored.effectiveScheduledAt.formatted(date: .abbreviated, time: .shortened))
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(BMTheme.secondaryText)
            }
            Spacer()
            Text(stored.isObservation ? "观赛" : "本队")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(BMTheme.green)
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .background(BMTheme.greenSoft)
                .clipShape(Capsule())
            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(BMTheme.secondaryText)
        }
        .padding(.leading, 14)
        .padding(.vertical, 12)
    }

    private func emptyGameCard(icon: String, title: String, detail: String) -> some View {
        BMCard {
            HStack(spacing: 13) {
                Image(systemName: icon)
                    .font(.system(size: 23))
                    .foregroundStyle(BMTheme.secondaryText)
                    .frame(width: 38)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(BMTheme.navy)
                    Text(detail)
                        .font(.system(size: 12))
                        .foregroundStyle(BMTheme.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

private struct StoredGameRouteView: View {
    @EnvironmentObject private var store: GameStore
    let gameID: UUID
    let showsBoxScore: Bool

    var body: some View {
        Group {
            if showsBoxScore { BoxScoreView() } else { ScorekeepingView() }
        }
        .onAppear { store.openGame(id: gameID) }
    }
}

private struct ScheduledGameDetailView: View {
    @EnvironmentObject private var store: GameStore
    let gameID: UUID

    private var stored: StoredGame? {
        store.games.first(where: { $0.id == gameID && $0.status == .scheduled })
    }

    var body: some View {
        Group {
            if let stored {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        BMCard {
                            VStack(spacing: 15) {
                                HStack {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(stored.isObservation ? "观赛记录" : "本队比赛")
                                            .font(.system(size: 12, weight: .bold))
                                            .foregroundStyle(BMTheme.green)
                                        Text("\(stored.state.awayTeam.name)\n对\n\(stored.state.homeTeam.name)")
                                            .font(.system(size: 20, weight: .black))
                                            .foregroundStyle(BMTheme.navy)
                                    }
                                    Spacer()
                                    Image(systemName: "calendar.badge.clock")
                                        .font(.system(size: 32))
                                        .foregroundStyle(BMTheme.green)
                                }
                                Divider()
                                Label(
                                    stored.effectiveScheduledAt.formatted(date: .complete, time: .shortened),
                                    systemImage: "clock.fill"
                                )
                                .font(.system(size: 15, weight: .bold))
                                .foregroundStyle(BMTheme.navy)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }

                        SectionHeader(title: "赛前准备")
                        BMCard {
                            VStack(alignment: .leading, spacing: 10) {
                                Label(
                                    stored.lineup.isEmpty ? "名单、规则与阵容尚未确认" : "已提前设置，可在开赛前继续修改",
                                    systemImage: stored.lineup.isEmpty ? "clock.badge.exclamationmark" : "checkmark.circle.fill"
                                )
                                Text("进入后可补充双方球员名单，并设置局数、时间提醒、投手限制、棒次与本场守位。")
                                    .font(.system(size: 13))
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(BMTheme.secondaryText)
                        }

                        Text("可以在计划时间到达前提前开始；App 不会自动启动比赛。")
                            .font(.system(size: 12))
                            .foregroundStyle(BMTheme.secondaryText)

                        NavigationLink(destination: ScheduledGamePreparationView(gameID: gameID)) {
                            HStack {
                                Text(stored.lineup.isEmpty ? "完成赛前设置" : "检查设置并开始")
                                Spacer()
                                Image(systemName: "arrow.right")
                            }
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .accessibilityIdentifier("prepare-scheduled-game")
                        NavigationLink(destination: GamePosterView(game: stored)) {
                            Label("制作比赛宣传海报", systemImage: "photo.badge.plus")
                        }
                        .buttonStyle(SecondaryButtonStyle())
                        .accessibilityIdentifier("open-scheduled-poster")
                    }
                    .padding(BMTheme.horizontalPadding)
                }
            } else {
                VStack(spacing: 10) {
                    Image(systemName: "calendar.badge.exclamationmark")
                        .font(.system(size: 32))
                        .foregroundStyle(BMTheme.secondaryText)
                    Text("比赛安排不存在")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(BMTheme.navy)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .bmScreenBackground()
        .navigationTitle("比赛安排")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview("首页") {
    NavigationStack { GameHomeView() }
        .environmentObject(GameStore())
}
