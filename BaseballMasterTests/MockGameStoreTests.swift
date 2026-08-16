import XCTest
@testable import BaseballMaster

@MainActor
final class MockGameStoreTests: XCTestCase {
    private func freshStore() -> MockGameStore {
        let store = MockGameStore()
        store.startNewGame(
            opponent: store.teams[1],
            isHome: false,
            innings: 6,
            lineup: Array(store.currentTeam.players.prefix(9))
        )
        return store
    }

    func testFourBallsMovesBatterToFirstAndAdvancesOrder() {
        let store = freshStore()
        let batter = store.currentBatter

        for _ in 0..<4 { store.recordPitch(.ball) }

        XCTAssertEqual(store.game.baseRunners[.first]?.id, batter.id)
        XCTAssertEqual(store.game.balls, 0)
        XCTAssertEqual(store.game.strikes, 0)
        XCTAssertNotEqual(store.currentBatter.id, batter.id)
        XCTAssertEqual(store.battingLine(for: batter).walks, 1)
    }

    func testThreeStrikesRecordsStrikeoutAndOut() {
        let store = freshStore()
        let batter = store.currentBatter

        for _ in 0..<3 { store.recordPitch(.swingingStrike) }

        XCTAssertEqual(store.game.outs, 1)
        XCTAssertEqual(store.battingLine(for: batter).strikeouts, 1)
        XCTAssertEqual(store.game.strikes, 0)
    }

    func testThreeGroundOutsSwitchHalfInning() {
        let store = freshStore()

        for _ in 0..<3 {
            store.applyPlay(.groundOut, defensivePlay: DefensivePlay.quickPlays[0])
        }

        XCTAssertFalse(store.game.isTop)
        XCTAssertEqual(store.game.inning, 1)
        XCTAssertEqual(store.game.outs, 0)
        XCTAssertTrue(store.game.baseRunners.isEmpty)
    }

    func testSinglePlacesBatterOnFirstAndUpdatesHit() {
        let store = freshStore()
        let batter = store.currentBatter

        store.applyPlay(.single)

        XCTAssertEqual(store.game.baseRunners[.first]?.id, batter.id)
        XCTAssertEqual(store.game.awayHits, 1)
        XCTAssertEqual(store.battingLine(for: batter).hits, 1)
    }

    func testUndoRestoresPreviousCount() {
        let store = freshStore()
        store.recordPitch(.ball)
        XCTAssertEqual(store.game.balls, 1)

        store.undo()

        XCTAssertEqual(store.game.balls, 0)
    }

    func testHitByPitchForcesLoadedBasesAndScoresRun() {
        let store = freshStore()
        let players = store.game.awayTeam.players
        let batter = store.currentBatter
        store.game.baseRunners = [.first: players[1], .second: players[2], .third: players[3]]

        store.recordHitByPitch()

        XCTAssertEqual(store.battingLine(for: batter).hitByPitch, 1)
        XCTAssertEqual(store.game.baseRunners[.first]?.id, batter.id)
        XCTAssertEqual(store.game.baseRunners[.second]?.id, players[1].id)
        XCTAssertEqual(store.game.baseRunners[.third]?.id, players[2].id)
        XCTAssertEqual(store.game.awayScore, 1)
    }

    func testDroppedThirdStrikeCanPlaceBatterOnFirst() {
        let store = freshStore()
        let batter = store.currentBatter

        store.recordDroppedThirdStrike(decisions: store.droppedThirdStrikeDecisions())

        XCTAssertEqual(store.game.baseRunners[.first]?.id, batter.id)
        XCTAssertEqual(store.battingLine(for: batter).strikeouts, 1)
        XCTAssertEqual(store.game.outs, 0)
    }

    func testStolenBaseMovesRunnerAndUpdatesStatistic() {
        let store = freshStore()
        let runner = store.game.awayTeam.players[1]
        store.game.baseRunners[.first] = runner

        let decisions = store.suggestedRunnerEventDecisions(for: .stolenBase)
        store.recordRunnerEvent(.stolenBase, decisions: decisions)

        XCTAssertNil(store.game.baseRunners[.first])
        XCTAssertEqual(store.game.baseRunners[.second]?.id, runner.id)
        XCTAssertEqual(store.battingLine(for: runner).stolenBases, 1)
    }

    func testThirdOutOnBatterCancelsRun() {
        let store = freshStore()
        let runner = store.game.awayTeam.players[1]
        store.game.outs = 2
        store.game.baseRunners[.third] = runner
        var decisions = store.suggestedRunnerDecisions(for: .groundOut)
        if let index = decisions.firstIndex(where: { $0.player.id == runner.id }) {
            decisions[index].destination = .score
        }

        store.applyPlay(.groundOut, defensivePlay: DefensivePlay.quickPlays[0], decisions: decisions)

        XCTAssertEqual(store.game.awayScore, 0)
        XCTAssertFalse(store.game.isTop)
    }

    func testCorrectionChangesCountAndBaseState() {
        let store = freshStore()
        let runner = store.game.awayTeam.players[2]
        store.correctGameState(
            inning: 3,
            isTop: true,
            balls: 2,
            strikes: 1,
            outs: 2,
            awayScore: 4,
            homeScore: 2,
            batterIndex: 4,
            pitcherID: store.currentPitcher.id,
            baseRunners: [.second: runner]
        )

        XCTAssertEqual(store.game.inning, 3)
        XCTAssertEqual(store.game.balls, 2)
        XCTAssertEqual(store.game.strikes, 1)
        XCTAssertEqual(store.game.outs, 2)
        XCTAssertEqual(store.game.awayScore, 4)
        XCTAssertEqual(store.game.homeScore, 2)
        XCTAssertEqual(store.game.baseRunners[.second]?.id, runner.id)
        XCTAssertEqual(store.currentBatter.id, store.game.awayTeam.players[4].id)
    }

    func testPitcherChangeUpdatesCurrentPitcher() {
        let store = freshStore()
        let replacement = store.game.fieldingTeam.players[1]

        store.changePitcher(to: replacement)

        XCTAssertEqual(store.currentPitcher.id, replacement.id)
    }

    func testHomeLeadAfterTopOfFinalInningEndsGame() {
        let store = freshStore()
        store.game.inning = 6
        store.game.isTop = true
        store.game.homeRunsByInning[0] = 2
        store.game.awayRunsByInning[0] = 1
        store.game.outs = 2

        store.applyPlay(.groundOut, defensivePlay: DefensivePlay.quickPlays[0])

        XCTAssertTrue(store.game.isFinal)
        XCTAssertTrue(store.game.isTop)
    }

    func testWalkOffRunEndsGameImmediately() {
        let store = freshStore()
        store.game.inning = 6
        store.game.isTop = false
        store.game.homeRunsByInning[0] = 1
        store.game.awayRunsByInning[0] = 1
        let runner = store.game.homeTeam.players[1]
        store.game.baseRunners[.third] = runner

        store.applyPlay(.single)

        XCTAssertTrue(store.game.isFinal)
        XCTAssertEqual(store.game.homeScore, 2)
    }
}
