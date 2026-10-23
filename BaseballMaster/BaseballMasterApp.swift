import SwiftUI

/// Fixture launch arguments are only honored by development builds.
private enum AppLaunchArguments {
    static var values: [String] {
        #if DEBUG
        ProcessInfo.processInfo.arguments
        #else
        []
        #endif
    }
}

@main
struct BaseballMasterApp: App {
    @StateObject private var store: GameStore
    @StateObject private var launchScreen: LaunchScreenController
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let isPreviewRun = AppLaunchArguments.values.contains { $0.hasSuffix("-preview") }
        let initialStore: GameStore
        #if DEBUG
        if AppLaunchArguments.values.contains("--live-production-persistent-test") {
            // An isolated on-disk synthetic match exercises normal Keychain/relaunch behavior.
            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let runID = AppLaunchArguments.values.first(where: { $0.hasPrefix("--live-integration-store=") })?.split(separator: "=").last.flatMap { UUID(uuidString: String($0)) }
            initialStore = GameStore(persistenceURL: documents.appendingPathComponent("LiveIntegration/\(runID?.uuidString ?? "game").json"))
            if initialStore.activeStoredGame == nil, let team = initialStore.currentTeam {
                let lineup = team.players.prefix(9).enumerated().map {
                    LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: $0.element.primaryPosition)
                }
                initialStore.startNewGame(opponent: initialStore.opponentTeams[0], isHome: false, rules: GameRules(), lineup: lineup)
            }
        } else {
            initialStore = isPreviewRun ? GameStore(persistenceURL: nil) : GameStore()
        }
        #else
        initialStore = GameStore()
        #endif
        #if DEBUG
        if AppLaunchArguments.values.contains("--v30-match-fixture") {
            if let team = initialStore.currentTeam {
                initialStore.startNewGame(opponent: initialStore.opponentTeams[0], isHome: false, innings: 6, lineup: Array(team.players.prefix(9)))
                initialStore.startGameClock(); initialStore.recordPitch(.ball)
                if AppLaunchArguments.values.contains("--v30-match-final") { initialStore.finishGame() }
            }
        }
        initialStore.runUpgradeReleaseAuditIfRequested()
        #endif
        _store = StateObject(wrappedValue: initialStore)
        _launchScreen = StateObject(wrappedValue: LaunchScreenController.forApp(arguments: AppLaunchArguments.values))
    }

    var body: some Scene {
        WindowGroup {
            AppLaunchContainer(controller: launchScreen, requiresDataRecovery: store.requiresDataRecovery) { LaunchRouterView() }
                .environmentObject(store)
                .onOpenURL { url in
                    launchScreen.dismiss()
                    if let token = DevicePairingLink.token(from: url.absoluteString) {
                        store.selectedTab = 1
                        store.teamSharing.incomingPairing = DevicePairingLink.url(token).absoluteString
                        return
                    }
                    guard let token = TeamInvitationLink.token(from: url.absoluteString) else { return }
                    store.selectedTab = 1
                    store.teamSharing.incomingInvitation = TeamInvitationLink.url(token: token).absoluteString
                }
                .task { store.liveBroadcasts.setForeground(true); store.sharedMatches.setForeground(true); store.teamSharing.setForeground(true) }
                .onChange(of: scenePhase) { phase in
                    if phase == .background { launchScreen.dismiss() }
                    store.liveBroadcasts.setForeground(phase == .active)
                    store.sharedMatches.setForeground(phase == .active)
                    if phase != .inactive { store.teamSharing.setForeground(phase == .active) }
                    if phase == .background { store.createAutomaticBackup() }
                }
        }
    }
}

struct LaunchRouterView: View {
    @EnvironmentObject private var store: GameStore
    @AppStorage("appAppearance") private var appAppearance = AppAppearance.system.rawValue

    var body: some View {
        #if DEBUG
        previewBody
        #else
        Group {
            if store.requiresDataRecovery { NavigationStack { BackupManagementView() } }
            else { RootTabView() }
        }
        .preferredColorScheme(AppAppearance(rawValue: appAppearance)?.colorScheme)
        #endif
    }

    #if DEBUG
    @ViewBuilder private var previewBody: some View {
        let arguments = AppLaunchArguments.values

        Group {
            if store.requiresDataRecovery {
                NavigationStack { BackupManagementView() }
            } else if arguments.contains("--v30-sharing-preview") {
                RootTabView().onAppear { store.selectedTab = 1 }
            } else if arguments.contains("--live-production-persistent-test") {
                NavigationStack { ScorekeepingView() }
            } else if arguments.contains("--v23-slow-preview") || arguments.contains("--v23-live-production-preview") {
                V23DevelopmentFixture(mode: .slowPitch)
            } else if arguments.contains("--v23-new-game-preview") {
                V23DevelopmentFixture(mode: nil)
            } else if arguments.contains("--correction-preview") {
                HistoryCorrectionFixture()
            } else if arguments.contains("--v11-coach-preview") {
                V11ScorekeepingFixture(mode: .coachPitch)
            } else if arguments.contains("--v11-cap-preview") {
                V11ScorekeepingFixture(mode: .standard)
            } else if arguments.contains("--v111-bases-preview") {
                V11ScorekeepingFixture(mode: .standard, fullBases: true, startsAfterRunners: true)
            } else if arguments.contains("--v111-limits-preview") {
                V11ScorekeepingFixture(mode: .standard, limitPreview: true)
            } else if arguments.contains("--v111-final-inning-preview") {
                V11ScorekeepingFixture(mode: .standard, limitPreview: true, finalInningPreview: true)
            } else if arguments.contains("--v111-long-pitcher-preview") {
                V11ScorekeepingFixture(mode: .standard, limitPreview: true, longPitcherName: true)
            } else if arguments.contains("--v11-bases-preview") {
                V11ScorekeepingFixture(mode: .standard, fullBases: true)
            } else if arguments.contains("--v11-navigation-preview") {
                V11ScorekeepingFixture(mode: .standard, fullBases: true, showsHome: true)
            } else if arguments.contains("--backup-preview") {
                NavigationStack { BackupManagementView() }
            } else if arguments.contains("--poster-preview") {
                GamePosterFixtureView()
            } else if arguments.contains("--situation-compound-preview") {
                SituationCompoundFixture()
            } else if arguments.contains("--batted-ball-preview") {
                BattedBallPreviewFixture()
            } else if arguments.contains("--scorekeeping-preview") {
                ScorekeepingPreviewFixtureView()
            } else if arguments.contains("--scorekeeping-timed-preview") {
                TimedScorekeepingFixtureView()
            } else if arguments.contains("--pending-review-preview") {
                PendingReviewFixtureView()
            } else if arguments.contains("--tiebreak-preview") {
                TiebreakPreviewFixtureView()
            } else if arguments.contains("--v22-opening-preview") {
                V11ScorekeepingFixture(mode: .standard, showsHome: true, openingPreview: true)
            } else if arguments.contains("--v22-coach-opening-preview") {
                V11ScorekeepingFixture(mode: .coachPitch, showsHome: true, openingPreview: true)
            } else if arguments.contains("--v22-statistics-preview") {
                StatisticsPreviewFixtureView(multipleGames: true)
            } else if arguments.contains("--statistics-preview") {
                StatisticsPreviewFixtureView()
            } else if arguments.contains("--statistics-empty-preview") {
                NavigationStack { StatsOverviewView() }
            } else if arguments.contains("--team-preview") {
                NavigationStack {
                    TeamRosterView()
                }
            } else if arguments.contains("--team-detail-preview") {
                NavigationStack {
                    TeamDetailView(teamID: store.currentTeam!.id)
                }
            } else if arguments.contains("--player-detail-preview") {
                NavigationStack {
                    PlayerDetailView(player: store.currentTeam!.players[0])
                }
            } else if arguments.contains("--profile-preview") {
                NavigationStack {
                    ProfileView()
                }
            } else if arguments.contains("--rules-preview") {
                NavigationStack {
                    BaseballRulesView()
                }
            } else if arguments.contains("--practice-preview") {
                NavigationStack {
                    PracticeInningView()
                }
            } else if arguments.contains("--practice-scorekeeping-preview") {
                NavigationStack {
                    PracticeScorekeepingView()
                }
            } else if arguments.contains("--pdf-preview") {
                NavigationStack {
                    BundledRulePDFView()
                }
            } else if arguments.contains("--pdf-search-preview") {
                NavigationStack {
                    BundledRulePDFView(initialQuery: "不合法投球")
                }
            } else if arguments.contains("--v30-date-preview") {
                BoxScorePreviewFixtureView(completedBackfill: true)
            } else if arguments.contains("--boxscore-preview") {
                BoxScorePreviewFixtureView()
            } else if arguments.contains("--home-preview") {
                NavigationStack {
                    GameHomeView()
                }
            } else if arguments.contains("--home-with-games-preview") {
                NavigationStack {
                    GameHomeFixtureView()
                }
            } else if arguments.contains("--setup-preview") {
                NavigationStack {
                    NewGameSetupView()
                }
            } else if arguments.contains("--schedule-preview") {
                NavigationStack {
                    NewGameSetupView(scheduleForLater: true)
                }
            } else if arguments.contains("--pregame-preview") {
                ScheduledPreparationFixtureView()
            } else if arguments.contains("--opponents-preview") {
                NavigationStack {
                    OpponentTeamsView()
                }
            } else if arguments.contains("--lineup-preview") {
                NavigationStack {
                    LineupSelectionView(
                        opponent: store.opponentTeams[0],
                        isHome: false,
                        innings: 6
                    )
                }
            } else if arguments.contains("--observed-lineup-preview") {
                NavigationStack {
                    ObservedGameLineupView(
                        awayTeam: store.opponentTeams[0],
                        homeTeam: store.opponentTeams[1],
                        rules: GameRules()
                    )
                }
            } else if arguments.contains("--outcome-cause-preview") {
                BattedBallCauseSheet(
                    arrival: .first,
                    hasRunners: true,
                    hasRunnerOnThird: false,
                    hasRunnersOnFirstAndSecond: false,
                    outs: 0,
                    onBack: {},
                    onSelect: { _ in }
                )
            } else if arguments.contains("--outcome-preview") {
                BattedBallObservationSheet(onSelect: { _ in }, onUnclear: {})
            } else if arguments.contains("--runner-preview") {
                RunnerResolutionSheet(
                    decisions: store.suggestedRunnerDecisions(for: .single),
                    availableDestinations: store.availableDestinations,
                    onConfirm: { _ in }
                )
            } else {
                RootTabView()
            }
        }
        .preferredColorScheme(AppAppearance(rawValue: appAppearance)?.colorScheme)
    }
    #endif
}

#if DEBUG
private struct GamePosterFixtureView: View {
    @EnvironmentObject private var store: GameStore
    @State private var gameID: UUID?
    var body: some View {
        NavigationStack {
            if let stored = store.games.first(where: { $0.id == gameID }) {
                GamePosterView(game: stored)
            } else { ProgressView() }
        }.onAppear {
            guard gameID == nil else { return }
            gameID = store.scheduleGame(ourTeam: store.currentTeam!, opponent: store.opponentTeams[0], isHome: false,
                                        scheduledAt: Date().addingTimeInterval(86400))
        }
    }
}

private struct StatisticsPreviewFixtureView: View {
    var multipleGames = false
    @EnvironmentObject private var store: GameStore
    @State private var configured = false

    var body: some View {
        NavigationStack { StatsOverviewView() }
            .onAppear {
                guard !configured, let opponent = store.opponentTeams.first else { return }
                configured = true
                let lineup = Array(store.currentTeam!.players.prefix(9))
                store.startNewGame(opponent: opponent, isHome: false, innings: 6, lineup: lineup)
                _ = store.applyPlay(.single)
                _ = store.applyPlay(.homeRun)
                for _ in 0..<3 { _ = store.applyPlay(.groundOut, defensivePlay: DefensivePlay.quickPlays[0]) }
                for _ in 0..<3 { store.recordPitch(.swingingStrike) }
                for _ in 0..<2 { _ = store.applyPlay(.groundOut, defensivePlay: DefensivePlay.quickPlays[0]) }
                store.finishGame()

                if multipleGames {
                    store.startNewGame(opponent: opponent, isHome: false, innings: 6, lineup: lineup)
                    _ = store.applyPlay(.double); store.finishGame()
                    store.startNewGame(opponent: opponent, isHome: true, innings: 6, lineup: lineup)
                    _ = store.applyPlay(.single); store.finishGame()
                    store.playerGameRecords.append(PlayerGameRecord(id: UUID(), playerID: lineup[0].id,
                        seasonID: store.seasons[0].id, date: Date(), opponent: "旧版独立记录", result: "未记录",
                        batting: BattingLine(plateAppearances: 100, atBats: 100, hits: 100)))
                }
                store.startNewGame(opponent: opponent, isHome: true, innings: 6, lineup: lineup)
                _ = store.applyPlay(.homeRun)
                for _ in 0..<3 { _ = store.applyPlay(.groundOut, defensivePlay: DefensivePlay.quickPlays[0]) }
                _ = store.applyPlay(.double)
                store.finishGame()
                if let saved = store.activeStoredGame, let previousYear = Calendar(identifier: .gregorian).date(byAdding: .year, value: -1, to: Date()) {
                    _ = store.updateCompletedGameDate(id: saved.id, playedAt: previousYear, timeZoneID: TimeZone.current.identifier, expectedRevision: saved.revision ?? 0)
                }
                store.addTeam(name: "新建球队", shortName: "新队", city: "")
            }
    }
}

private struct ScorekeepingPreviewFixtureView: View {
    @EnvironmentObject private var store: GameStore
    @State private var configured = false

    var body: some View {
        NavigationStack { ScorekeepingView() }
            .onAppear {
                guard !configured else { return }
                configured = true
                store.prepareScorekeepingPreview()
            }
    }
}

private struct TimedScorekeepingFixtureView: View {
    @EnvironmentObject private var store: GameStore
    @State private var configured = false

    var body: some View {
        NavigationStack { ScorekeepingView() }
            .onAppear {
                guard !configured, let opponent = store.opponentTeams.first else { return }
                configured = true
                let lineup = Array(store.currentTeam!.players.prefix(9)).enumerated().map { index, player in
                    LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
                }
                _ = store.startNewGame(
                    opponent: opponent,
                    isHome: false,
                    rules: GameRules(scheduledInnings: 6, timeLimitMinutes: 90, timeWarningMinutes: 10),
                    lineup: lineup
                )
            }
    }
}

private struct PendingReviewFixtureView: View {
    @EnvironmentObject private var store: GameStore
    @State private var configured = false

    var body: some View {
        NavigationStack { ScorekeepingView() }
            .onAppear {
                guard !configured, let opponent = store.opponentTeams.first else { return }
                configured = true
                let lineup = Array(store.currentTeam!.players.prefix(9)).enumerated().map { index, player in
                    LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
                }
                _ = store.startNewGame(
                    opponent: opponent,
                    isHome: false,
                    rules: GameRules(),
                    lineup: lineup
                )
                _ = store.applyPlay(.pending)
            }
    }
}

private struct BoxScorePreviewFixtureView: View {
    @EnvironmentObject private var store: GameStore
    var completedBackfill = false
    @State private var configured = false

    var body: some View {
        NavigationStack { BoxScoreView() }
            .onAppear {
                guard !configured, let opponent = store.opponentTeams.first else { return }
                configured = true
                let lineup = Array(store.currentTeam!.players.prefix(9)).enumerated().map { index, player in
                    LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
                }
                _ = store.startNewGame(
                    opponent: opponent,
                    isHome: false,
                    rules: GameRules(scheduledInnings: 7),
                    lineup: lineup
                )
                store.recordPitch(.ball)
                store.recordPitch(.foul)
                _ = store.applyPlay(.single)
                store.recordPitch(.calledStrike)
                let steal = store.suggestedRunnerEventDecisions(for: .stolenBase)
                _ = store.recordRunnerEvent(.stolenBase, decisions: steal)
                _ = store.applyPlay(.double)
                _ = store.applyPlay(.pending)
                if completedBackfill {
                    store.finishGame()
                    if let saved = store.activeStoredGame,
                       let date = ISO8601DateFormatter().date(from: "2024-02-29T06:00:00Z") {
                        _ = store.updateCompletedGameDate(id: saved.id, playedAt: date,
                            timeZoneID: "Asia/Shanghai", expectedRevision: saved.revision ?? 0)
                    }
                }
            }
    }
}

private struct TiebreakPreviewFixtureView: View {
    @EnvironmentObject private var store: GameStore
    @State private var configured = false

    var body: some View {
        NavigationStack { ScorekeepingView() }
            .onAppear {
                guard !configured, let opponent = store.opponentTeams.first else { return }
                configured = true
                let lineup = Array(store.currentTeam!.players.prefix(9)).enumerated().map { index, player in
                    LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
                }
                _ = store.startNewGame(
                    opponent: opponent,
                    isHome: false,
                    rules: GameRules(scheduledInnings: 7),
                    lineup: lineup
                )
                store.game.inning = 8
                store.game.homeRunsByInning.append(0)
                store.game.awayRunsByInning.append(0)
                store.confirmExtraInning(useTiebreak: true, runnerBases: [.first, .second])
            }
    }
}

private struct ScheduledPreparationFixtureView: View {
    @EnvironmentObject private var store: GameStore
    @State private var gameID: UUID?

    var body: some View {
        NavigationStack {
            Group {
                if let gameID {
                    ScheduledGamePreparationView(gameID: gameID)
                } else {
                    ProgressView()
                }
            }
        }
        .onAppear {
            guard gameID == nil, let opponent = store.opponentTeams.first else { return }
            gameID = store.scheduleGame(
                ourTeam: store.currentTeam!,
                opponent: opponent,
                isHome: false,
                scheduledAt: Date().addingTimeInterval(86_400)
            )
        }
    }
}

private struct GameHomeFixtureView: View {
    @EnvironmentObject private var store: GameStore
    @State private var isConfigured = false

    var body: some View {
        GameHomeView()
            .onAppear {
                guard !isConfigured, let opponent = store.opponentTeams.first else { return }
                isConfigured = true
                let lineup = Array(store.currentTeam!.players.prefix(9))
                let assignments = lineup.enumerated().map { index, player in
                    LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
                }
                _ = store.startNewGame(
                    opponent: opponent,
                    isHome: false,
                    rules: GameRules(scheduledInnings: 6, pitcherInningsLimit: 3),
                    lineup: assignments,
                    scheduledAt: Date().addingTimeInterval(86_400),
                    startImmediately: false
                )
                store.startNewGame(opponent: opponent, isHome: true, innings: 6, lineup: lineup)
                store.finishGame()
                if let observedHome = store.opponentTeams.dropFirst().first {
                    _ = store.createObservedGame(
                        awayTeam: opponent,
                        homeTeam: observedHome,
                        rules: GameRules(),
                        startImmediately: true
                    )
                    store.finishGame()
                }
                store.startNewGame(opponent: opponent, isHome: false, innings: 7, lineup: lineup)
                store.recordPitch(.ball)
            }
    }
}

private struct V11ScorekeepingFixture: View {
    @EnvironmentObject private var store: GameStore
    @State private var prepared = false
    let mode: GameMode
    var fullBases = false
    var showsHome = false
    var limitPreview = false
    var finalInningPreview = false
    var longPitcherName = false
    var startsAfterRunners = false
    var openingPreview = false
    var body: some View {
        Group {
            if showsHome { RootTabView() }
            else { NavigationStack { ScorekeepingView() } }
        }
            .onAppear {
                guard !prepared, let team = store.currentTeam else { return }
                prepared = true
                var rules = GameRules()
                rules.mode = mode; rules.coachPitchLimit = 6
                rules.halfInningRunLimit = mode == .standard ? 1 : nil
                if limitPreview { rules.pitchLimit = 6; rules.pitchWarningRemaining = 3; rules.pitcherInningsLimit = 2 }
                let lineup = team.players.prefix(9).enumerated().map {
                    LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: $0.element.primaryPosition)
                }
                store.startNewGame(opponent: store.opponentTeams[0], isHome: false, rules: rules, lineup: lineup)
                if limitPreview {
                    // Limits are based on mound appearances, not a fabricated IP
                    // total. Move through a real recorded first inning.
                    store.startGameClock()
                    store.endCurrentHalf(); store.endCurrentHalf()
                }
                if !openingPreview { store.game.baseRunners[.second] = team.players[1] }
                if startsAfterRunners { store.game.awayBatterIndex = 4 }
                if limitPreview {
                    store.game.outs = 2
                    store.game.pitching[store.currentPitcher.id] = PitchingLine(outsRecorded: 5, pitches: finalInningPreview ? 0 : 3)
                }
                if longPitcherName {
                    var draft = store.lineupDraft(forHomeTeam: store.game.isTop)
                    draft.updateProfile(playerID: store.currentPitcher.id, chineseName: "赵一鸣很长的投手姓名", englishName: "", numberTexts: ["77", "88"])
                    store.saveLineup(draft)
                }
                if fullBases {
                    store.game.baseRunners[.first] = team.players[2]
                    store.game.baseRunners[.third] = team.players[3]
                }
            }
    }
}

#endif

#if DEBUG
private struct V23DevelopmentFixture: View {
    @EnvironmentObject private var store: GameStore
    let mode: GameMode?
    @State private var ready = false
    var body: some View {
        Group {
            if ready {
                NavigationStack {
                    if mode == nil { NewGameSetupView() } else { ScorekeepingView(showsGameHomeButton: true) }
                }
            } else { ProgressView() }
        }
        .task {
            guard !ready else { return }
            func team(_ name: String) -> Team {
                Team(name: name, shortName: name, city: "", players: (0..<14).map { Player(name: name + "球员\($0 + 1)", number: $0 + 1, primaryPosition: FieldPosition.allCases[$0 % 10]) })
            }
            let own = team("飞鹰"), other = team("猎隼")
            store.teams = [own]; store.currentTeam = own; store.opponentTeams = [other]
            if let mode {
                var rules = GameRules(fieldersCount: 10, timeLimitMinutes: 90, timeWarningMinutes: 5)
                rules.mode = mode; rules.twoStrikeFoulPolicy = .oneExtraFoul; rules.competitionFormat = .timed
                func assignments(_ team: Team, _ count: Int) -> [LineupAssignment] {
                    team.players.prefix(count).enumerated().map { LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: $0.offset < 10 ? FieldPosition.allCases[$0.offset] : nil) }
                }
                store.startNewGame(opponent: other, isHome: false, rules: rules, lineup: assignments(own, 12), opponentLineup: assignments(other, 11))
            }
            ready = true
        }
    }
}
#endif
