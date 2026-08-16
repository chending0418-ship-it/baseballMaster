import SwiftUI

@main
struct BaseballMasterApp: App {
    @StateObject private var store = MockGameStore()

    var body: some Scene {
        WindowGroup {
            LaunchRouterView()
                .environmentObject(store)
        }
    }
}

struct LaunchRouterView: View {
    @EnvironmentObject private var store: MockGameStore

    var body: some View {
        let arguments = ProcessInfo.processInfo.arguments

        if arguments.contains("--scorekeeping-preview") {
            NavigationStack {
                ScorekeepingView()
            }
        } else if arguments.contains("--boxscore-preview") {
            NavigationStack {
                BoxScoreView()
            }
        } else if arguments.contains("--lineup-preview") {
            NavigationStack {
                LineupSelectionView(
                    opponent: store.teams[1],
                    isHome: false,
                    innings: 6
                )
            }
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
}
