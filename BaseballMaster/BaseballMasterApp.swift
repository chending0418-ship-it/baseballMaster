import SwiftUI

@main
struct BaseballMasterApp: App {
    @StateObject private var store: GameStore

    init() {
        let isPreviewRun = ProcessInfo.processInfo.arguments.contains { $0.hasSuffix("-preview") }
        _store = StateObject(wrappedValue: isPreviewRun ? GameStore(persistenceURL: nil) : GameStore())
    }

    var body: some Scene {
        WindowGroup {
            LaunchRouterView()
                .environmentObject(store)
        }
    }
}

struct LaunchRouterView: View {
    @EnvironmentObject private var store: GameStore
    @AppStorage("appAppearance") private var appAppearance = AppAppearance.system.rawValue

    var body: some View {
        let arguments = ProcessInfo.processInfo.arguments

        Group {
            if arguments.contains("--scorekeeping-preview") {
                ScorekeepingPreviewFixtureView()
            } else if arguments.contains("--scorekeeping-timed-preview") {
                TimedScorekeepingFixtureView()
            } else if arguments.contains("--pending-review-preview") {
                PendingReviewFixtureView()
            } else if arguments.contains("--tiebreak-preview") {
                TiebreakPreviewFixtureView()
            } else if arguments.contains("--team-preview") {
                NavigationStack {
                    TeamRosterView()
                }
            } else if arguments.contains("--team-detail-preview") {
                NavigationStack {
                    TeamDetailView(teamID: store.currentTeam.id)
                }
            } else if arguments.contains("--player-detail-preview") {
                NavigationStack {
                    PlayerDetailView(player: store.currentTeam.players[0])
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
                let lineup = Array(store.currentTeam.players.prefix(9)).enumerated().map { index, player in
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
                let lineup = Array(store.currentTeam.players.prefix(9)).enumerated().map { index, player in
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
                let lineup = Array(store.currentTeam.players.prefix(9)).enumerated().map { index, player in
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
                let lineup = Array(store.currentTeam.players.prefix(9)).enumerated().map { index, player in
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
                ourTeam: store.currentTeam,
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
                let lineup = Array(store.currentTeam.players.prefix(9))
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
