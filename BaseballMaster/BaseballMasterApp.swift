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
        initialStore.runUpgradeReleaseAuditIfRequested()
        #endif
        _store = StateObject(wrappedValue: initialStore)
    }

    var body: some Scene {
        WindowGroup {
            LaunchRouterView()
                .environmentObject(store)
                .task { store.liveBroadcasts.setForeground(true) }
                .onChange(of: scenePhase) { phase in
                    store.liveBroadcasts.setForeground(phase == .active)
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
            } else if arguments.contains("--live-production-persistent-test") {
                NavigationStack { ScorekeepingView() }
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
            } else if arguments.contains("--scorekeeping-preview") {
                ScorekeepingPreviewFixtureView()
            } else if arguments.contains("--scorekeeping-timed-preview") {
                TimedScorekeepingFixtureView()
            } else if arguments.contains("--pending-review-preview") {
                PendingReviewFixtureView()
            } else if arguments.contains("--tiebreak-preview") {
                TiebreakPreviewFixtureView()
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

                store.seasons.swapAt(0, 1)
                store.startNewGame(opponent: opponent, isHome: true, innings: 6, lineup: lineup)
                _ = store.applyPlay(.homeRun)
                for _ in 0..<3 { _ = store.applyPlay(.groundOut, defensivePlay: DefensivePlay.quickPlays[0]) }
                _ = store.applyPlay(.double)
                store.finishGame()
                store.seasons.swapAt(0, 1)
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
                store.game.baseRunners[.second] = team.players[1]
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
