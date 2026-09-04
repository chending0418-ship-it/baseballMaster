import XCTest
import CoreData
import PDFKit
@testable import BaseballMaster

@MainActor
final class GameStoreTests: XCTestCase {
    private func freshStore() -> GameStore {
        let store = GameStore(persistenceURL: nil)
        store.startNewGame(
            opponent: store.opponentTeams[0],
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

    func testTeamAndPlayerCRUDSupportsMultipleJerseyNumbers() throws {
        let store = GameStore(persistenceURL: nil)
        let teamID = store.addTeam(name: "杭州闪电", shortName: "闪电", city: "杭州")

        store.updateTeam(id: teamID, name: "杭州闪电棒球队", shortName: "闪电", city: "杭州")
        let playerID = try XCTUnwrap(
            store.addPlayer(
                to: teamID,
                chineseName: "李明",
                englishName: "Li Ming",
                numbers: [8, 28]
            )
        )

        var team = try XCTUnwrap(store.team(withID: teamID))
        XCTAssertEqual(team.name, "杭州闪电棒球队")
        XCTAssertEqual(team.players.count, 1)
        XCTAssertEqual(team.players[0].numbers, [8, 28])

        store.updatePlayer(
            in: teamID,
            playerID: playerID,
            chineseName: "李明远",
            englishName: "Mingyuan Li",
            numbers: [18, 88]
        )
        team = try XCTUnwrap(store.team(withID: teamID))
        XCTAssertEqual(team.players[0].chineseName, "李明远")
        XCTAssertEqual(team.players[0].englishName, "Mingyuan Li")
        XCTAssertEqual(team.players[0].numbers, [18, 88])

        store.deletePlayer(from: teamID, playerID: playerID)
        XCTAssertTrue(try XCTUnwrap(store.team(withID: teamID)).players.isEmpty)
        XCTAssertTrue(store.deleteTeam(id: teamID))
        XCTAssertNil(store.team(withID: teamID))
    }

    func testRosterChangesPersistToCoreDataSQLite() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BaseballMasterTests-\(UUID().uuidString)", isDirectory: true)
        let fileURL = directory.appendingPathComponent("BaseballMaster.sqlite")
        defer { try? FileManager.default.removeItem(at: directory) }

        let firstStore = GameStore(persistenceURL: fileURL)
        let teamID = firstStore.addTeam(name: "成都熊猫", shortName: "熊猫", city: "成都")
        let playerID = try XCTUnwrap(
            firstStore.addPlayer(
                to: teamID,
                chineseName: "张扬",
                englishName: "Zhang Yang",
                numbers: [6, 66]
            )
        )
        firstStore.updateTeam(id: teamID, name: "成都熊猫棒球队", shortName: "熊猫", city: "成都")
        firstStore.updatePlayer(
            in: teamID,
            playerID: playerID,
            chineseName: "张扬远",
            englishName: "Yangyuan Zhang",
            numbers: [16, 66]
        )
        firstStore.setCurrentTeam(id: teamID)

        let reloadedStore = GameStore(persistenceURL: fileURL)
        let reloadedTeam = try XCTUnwrap(reloadedStore.team(withID: teamID))
        let reloadedPlayer = try XCTUnwrap(reloadedTeam.players.first(where: { $0.id == playerID }))
        XCTAssertEqual(reloadedStore.currentTeam.id, teamID)
        XCTAssertEqual(reloadedTeam.name, "成都熊猫棒球队")
        XCTAssertEqual(reloadedPlayer.chineseName, "张扬远")
        XCTAssertEqual(reloadedPlayer.englishName, "Yangyuan Zhang")
        XCTAssertEqual(reloadedPlayer.numbers, [16, 66])

        reloadedStore.deletePlayer(from: teamID, playerID: playerID)
        XCTAssertNil(
            GameStore(persistenceURL: fileURL)
                .team(withID: teamID)?
                .players
                .first(where: { $0.id == playerID })
        )

        XCTAssertTrue(reloadedStore.deleteTeam(id: teamID))
        XCTAssertNil(GameStore(persistenceURL: fileURL).team(withID: teamID))
    }

    func testLegacyJSONImportsIntoCoreDataOnce() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BaseballMasterMigrationTests-\(UUID().uuidString)", isDirectory: true)
        let databaseURL = directory.appendingPathComponent("BaseballMaster.sqlite")
        let legacyJSONURL = directory.appendingPathComponent("roster-data.json")
        let archivedJSONURL = directory.appendingPathComponent("roster-data.migrated.json")
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        let player = Player(
            chineseName: "旧资料球员",
            englishName: "Legacy Player",
            numbers: [9, 99],
            primaryPosition: .catcher
        )
        let team = Team(name: "旧资料球队", shortName: "旧队", city: "北京", players: [player])
        var batting = BattingLine()
        batting.atBats = 4
        batting.hits = 2
        let snapshot = RosterSnapshot(
            teams: [team],
            currentTeamID: team.id,
            seasons: [Season(id: "legacy-season", name: "旧赛季")],
            playerGameRecords: [
                PlayerGameRecord(
                    playerID: player.id,
                    seasonID: "legacy-season",
                    date: Date(timeIntervalSince1970: 1_700_000_000),
                    opponent: "迁移对手",
                    result: "胜 3–2",
                    batting: batting
                )
            ]
        )
        try JSONEncoder().encode(snapshot).write(to: legacyJSONURL)

        let migratedStore = GameStore(
            persistenceURL: databaseURL,
            legacyJSONURL: legacyJSONURL
        )
        XCTAssertEqual(migratedStore.currentTeam.id, team.id)
        XCTAssertEqual(migratedStore.currentTeam.players.first?.numbers, [9, 99])
        XCTAssertEqual(migratedStore.playerGameRecords.first?.batting.hits, 2)
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacyJSONURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: archivedJSONURL.path))

        let reloadedStore = GameStore(persistenceURL: databaseURL)
        XCTAssertEqual(reloadedStore.currentTeam.id, team.id)
        XCTAssertEqual(reloadedStore.currentTeam.name, "旧资料球队")
        XCTAssertEqual(reloadedStore.playerGameRecords.first?.opponent, "迁移对手")
    }

    func testPlayerStatisticsAggregateSelectedGames() throws {
        let store = GameStore(persistenceURL: nil)
        let player = store.currentTeam.players[0]
        let seasonID = try XCTUnwrap(store.seasons.first?.id)
        var firstLine = BattingLine()
        firstLine.plateAppearances = 4
        firstLine.atBats = 3
        firstLine.hits = 2
        firstLine.walks = 1
        var secondLine = BattingLine()
        secondLine.plateAppearances = 3
        secondLine.atBats = 3
        secondLine.hits = 1
        store.playerGameRecords = [
            PlayerGameRecord(playerID: player.id, seasonID: seasonID, date: Date(), opponent: "甲队", result: "胜", batting: firstLine),
            PlayerGameRecord(playerID: player.id, seasonID: seasonID, date: Date().addingTimeInterval(-86_400), opponent: "乙队", result: "负", batting: secondLine)
        ]
        let records = store.gameRecords(for: player, seasonID: seasonID)

        XCTAssertEqual(records.count, 2)
        let recentThree = store.battingLine(for: Array(records.prefix(3)))
        XCTAssertEqual(recentThree.atBats, records.prefix(3).map(\.batting.atBats).reduce(0, +))
        XCTAssertEqual(recentThree.hits, records.prefix(3).map(\.batting.hits).reduce(0, +))
    }

    func testPracticeInningUsesIndependentResettableState() {
        let formalStore = GameStore(persistenceURL: nil)
        let formalGameBeforePractice = formalStore.game
        let practiceStore = GameStore(persistenceURL: nil)

        practiceStore.resetPracticeInning()
        XCTAssertEqual(practiceStore.game.scheduledInnings, 1)
        XCTAssertEqual(practiceStore.game.inning, 1)
        XCTAssertEqual(practiceStore.game.playLog.count, 1)

        practiceStore.recordPitch(.ball)
        XCTAssertEqual(practiceStore.game.balls, 1)
        XCTAssertEqual(formalStore.game, formalGameBeforePractice)

        practiceStore.resetPracticeInning()
        XCTAssertEqual(practiceStore.game.balls, 0)
        XCTAssertEqual(practiceStore.game.strikes, 0)
        XCTAssertEqual(practiceStore.game.outs, 0)
        XCTAssertEqual(practiceStore.game.playLog.count, 1)
    }

    func testOpponentTeamAndRosterPersistSeparately() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BaseballMasterOpponentTests-\(UUID().uuidString)", isDirectory: true)
        let databaseURL = directory.appendingPathComponent("BaseballMaster.sqlite")
        defer { try? FileManager.default.removeItem(at: directory) }

        let firstStore = GameStore(persistenceURL: databaseURL)
        let opponentID = firstStore.addOpponentTeam(name: "南京鲸鱼", shortName: "鲸鱼", city: "南京")
        let playerID = try XCTUnwrap(
            firstStore.addOpponentPlayer(
                to: opponentID,
                chineseName: "对手甲",
                englishName: "Opponent A",
                numbers: [7, 27]
            )
        )

        let reloaded = GameStore(persistenceURL: databaseURL)
        let opponent = try XCTUnwrap(reloaded.opponentTeam(withID: opponentID))
        XCTAssertNil(reloaded.team(withID: opponentID))
        XCTAssertEqual(opponent.players.first(where: { $0.id == playerID })?.numbers, [7, 27])
    }

    func testOngoingGameRulesLineupAndStateRecoverFromCoreData() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BaseballMasterGameTests-\(UUID().uuidString)", isDirectory: true)
        let databaseURL = directory.appendingPathComponent("BaseballMaster.sqlite")
        defer { try? FileManager.default.removeItem(at: directory) }

        let firstStore = GameStore(persistenceURL: databaseURL)
        let assignments = Array(firstStore.currentTeam.players.prefix(9)).enumerated().map { index, player in
            LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
        }
        let rules = GameRules(
            scheduledInnings: 7,
            fieldersCount: 9,
            timeLimitMinutes: 90,
            timeWarningMinutes: 10,
            pitchLimit: 80,
            pitchWarningRemaining: 10
        )
        let gameID = firstStore.startNewGame(
            opponent: firstStore.opponentTeams[0],
            isHome: true,
            rules: rules,
            lineup: assignments
        )
        firstStore.recordPitch(.ball)

        let reloaded = GameStore(persistenceURL: databaseURL)
        let recovered = try XCTUnwrap(reloaded.ongoingGames.first(where: { $0.id == gameID }))
        XCTAssertEqual(recovered.rules, rules)
        XCTAssertEqual(recovered.lineup, assignments)
        XCTAssertEqual(recovered.state.balls, 1)
        XCTAssertEqual(recovered.state.scoringEvents?.last?.category, .pitch)
        XCTAssertEqual(recovered.state.scoringEvents?.last?.afterSituation?.balls, 1)
        XCTAssertEqual(reloaded.game.balls, 1)
        XCTAssertEqual(recovered.state.homeBattingOrderIDs.count, 9)
        XCTAssertEqual(recovered.state.homeTeam.players.count, firstStore.currentTeam.players.count)
    }

    func testFinishedGameMovesFromOngoingToRecentAndPersists() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BaseballMasterFinishedGameTests-\(UUID().uuidString)", isDirectory: true)
        let databaseURL = directory.appendingPathComponent("BaseballMaster.sqlite")
        defer { try? FileManager.default.removeItem(at: directory) }

        let store = GameStore(persistenceURL: databaseURL)
        let lineup = Array(store.currentTeam.players.prefix(9))
        store.startNewGame(opponent: store.opponentTeams[0], isHome: false, innings: 6, lineup: lineup)
        store.finishGame()

        let reloaded = GameStore(persistenceURL: databaseURL)
        XCTAssertTrue(reloaded.ongoingGames.isEmpty)
        XCTAssertEqual(reloaded.recentGames.count, 1)
        XCTAssertTrue(reloaded.recentGames[0].state.isFinal)
    }

    func testPreviousLineupAndBenchRemainAvailable() throws {
        let store = GameStore(persistenceURL: nil)
        let starters = Array(store.currentTeam.players.prefix(9))
        let assignments = starters.enumerated().map { index, player in
            LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
        }
        store.startNewGame(
            opponent: store.opponentTeams[0],
            isHome: false,
            rules: GameRules(),
            lineup: assignments
        )

        XCTAssertEqual(store.previousLineup(for: store.currentTeam.id), assignments)
        XCTAssertEqual(store.game.awayBattingOrderIDs.count, 9)
        XCTAssertGreaterThan(store.game.awayTeam.players.count, store.game.awayBattingOrderIDs.count)

        let benchPlayer = try XCTUnwrap(
            store.game.awayTeam.players.first(where: { !store.game.awayBattingOrderIDs.contains($0.id) })
        )
        store.replaceCurrentBatter(with: benchPlayer)
        XCTAssertTrue(store.game.awayBattingOrderIDs.contains(benchPlayer.id))
        XCTAssertEqual(store.game.awayBattingOrderIDs.count, 9)
    }

    func testFutureGamePersistsWithoutReplacingCurrentGameAndCanStartLater() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BaseballMasterScheduledGameTests-\(UUID().uuidString)", isDirectory: true)
        let databaseURL = directory.appendingPathComponent("BaseballMaster.sqlite")
        defer { try? FileManager.default.removeItem(at: directory) }

        let store = GameStore(persistenceURL: databaseURL)
        let gameBeforeScheduling = store.game
        let assignments = Array(store.currentTeam.players.prefix(9)).enumerated().map { index, player in
            LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
        }
        let futureDate = Date().addingTimeInterval(86_400)
        let gameID = store.startNewGame(
            opponent: store.opponentTeams[0],
            isHome: false,
            rules: GameRules(),
            lineup: assignments,
            scheduledAt: futureDate,
            startImmediately: false
        )

        XCTAssertEqual(store.game, gameBeforeScheduling)
        XCTAssertTrue(store.ongoingGames.isEmpty)
        XCTAssertEqual(store.scheduledGames.first?.id, gameID)

        let reloaded = GameStore(persistenceURL: databaseURL)
        let reloadedDate = try XCTUnwrap(reloaded.scheduledGames.first?.effectiveScheduledAt)
        XCTAssertEqual(reloadedDate.timeIntervalSince1970, futureDate.timeIntervalSince1970, accuracy: 0.01)
        XCTAssertTrue(reloaded.startScheduledGame(id: gameID))
        XCTAssertEqual(reloaded.ongoingGames.first?.id, gameID)
        XCTAssertNil(reloaded.ongoingGames.first?.startedAt)
        reloaded.startGameClock()
        XCTAssertNotNil(reloaded.ongoingGames.first?.startedAt)
        XCTAssertTrue(GameStore(persistenceURL: databaseURL).scheduledGames.isEmpty)
    }

    func testMinimalFutureGameCanBeScheduledBeforeOpponentRosterExists() throws {
        let store = GameStore(persistenceURL: nil)
        let opponentID = store.addOpponentTeam(name: "待补名单队", shortName: "待补", city: "苏州")
        let opponent = try XCTUnwrap(store.opponentTeam(withID: opponentID))
        let gameBeforeScheduling = store.game

        let gameID = store.scheduleGame(
            ourTeam: store.currentTeam,
            opponent: opponent,
            isHome: true,
            scheduledAt: Date().addingTimeInterval(86_400)
        )

        let scheduled = try XCTUnwrap(store.scheduledGames.first(where: { $0.id == gameID }))
        XCTAssertTrue(scheduled.lineup.isEmpty)
        XCTAssertTrue(scheduled.state.homeBattingOrderIDs.isEmpty)
        XCTAssertTrue(scheduled.state.awayBattingOrderIDs.isEmpty)
        XCTAssertEqual(scheduled.opponentTeamID, opponentID)
        XCTAssertEqual(store.game, gameBeforeScheduling)
    }

    func testMinimalFutureGameUsesRostersAndRulesCompletedBeforeFirstPitch() throws {
        let store = GameStore(persistenceURL: nil)
        let opponentID = store.addOpponentTeam(name: "赛前补充队", shortName: "补充", city: "无锡")
        let opponent = try XCTUnwrap(store.opponentTeam(withID: opponentID))
        let gameID = store.scheduleGame(
            ourTeam: store.currentTeam,
            opponent: opponent,
            isHome: false,
            scheduledAt: Date().addingTimeInterval(86_400)
        )
        for number in 1...9 {
            _ = store.addOpponentPlayer(
                to: opponentID,
                chineseName: "赛前球员 \(number)",
                englishName: "Pregame \(number)",
                numbers: [number]
            )
        }
        let lineup = Array(store.currentTeam.players.prefix(9)).enumerated().map { index, player in
            LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
        }
        let rules = GameRules(scheduledInnings: 7, pitchLimit: 75, pitcherInningsLimit: 2)

        XCTAssertTrue(store.startScheduledGame(id: gameID, rules: rules, lineup: lineup))
        let started = try XCTUnwrap(store.ongoingGames.first(where: { $0.id == gameID }))
        XCTAssertEqual(started.rules, rules)
        XCTAssertEqual(started.lineup, lineup)
        XCTAssertEqual(started.state.awayTeam.players.count, store.currentTeam.players.count)
        XCTAssertEqual(started.state.homeTeam.players.count, 9)
    }

    func testMinimalFutureObservationCanAddBothRostersBeforeStarting() throws {
        let store = GameStore(persistenceURL: nil)
        let awayID = store.addOpponentTeam(name: "观赛客队", shortName: "观客", city: "常州")
        let homeID = store.addOpponentTeam(name: "观赛主队", shortName: "观主", city: "南通")
        let away = try XCTUnwrap(store.opponentTeam(withID: awayID))
        let home = try XCTUnwrap(store.opponentTeam(withID: homeID))
        let gameID = store.scheduleObservedGame(
            awayTeam: away,
            homeTeam: home,
            scheduledAt: Date().addingTimeInterval(86_400)
        )

        XCTAssertTrue(try XCTUnwrap(store.scheduledGames.first(where: { $0.id == gameID })).lineup.isEmpty)
        for number in 1...9 {
            _ = store.addOpponentPlayer(
                to: awayID,
                chineseName: "客队球员 \(number)",
                englishName: "Away \(number)",
                numbers: [number]
            )
            _ = store.addOpponentPlayer(
                to: homeID,
                chineseName: "主队球员 \(number)",
                englishName: "Home \(number)",
                numbers: [number + 20]
            )
        }
        let rules = GameRules(scheduledInnings: 5, fieldersCount: 9, pitcherInningsLimit: 2)
        let completedAway = try XCTUnwrap(store.opponentTeam(withID: awayID))
        let completedHome = try XCTUnwrap(store.opponentTeam(withID: homeID))
        var awayLineup = Array(completedAway.players.prefix(9)).enumerated().map { index, player in
            LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
        }
        awayLineup.swapAt(0, 1)
        for index in awayLineup.indices { awayLineup[index].battingOrder = index + 1 }
        let homeLineup = Array(completedHome.players.prefix(9)).enumerated().map { index, player in
            LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
        }

        XCTAssertTrue(
            store.startScheduledObservedGame(
                id: gameID,
                rules: rules,
                awayLineup: awayLineup,
                homeLineup: homeLineup
            )
        )
        let started = try XCTUnwrap(store.ongoingGames.first(where: { $0.id == gameID }))
        XCTAssertTrue(started.isObservation)
        XCTAssertEqual(started.lineup, awayLineup)
        XCTAssertEqual(started.secondaryLineup, homeLineup)
        XCTAssertEqual(started.rules, rules)
    }

    func testObservedGameUsesTwoOpponentTeamsAndDoesNotBecomeTeamRecord() throws {
        let store = GameStore(persistenceURL: nil)
        let away = try XCTUnwrap(store.opponentTeams.first)
        let home = try XCTUnwrap(store.opponentTeams.dropFirst().first)
        let gameID = store.createObservedGame(
            awayTeam: away,
            homeTeam: home,
            rules: GameRules(scheduledInnings: 7),
            startImmediately: true
        )

        let observed = try XCTUnwrap(store.ongoingGames.first(where: { $0.id == gameID }))
        XCTAssertTrue(observed.isObservation)
        XCTAssertNil(observed.ourTeamID)
        XCTAssertEqual(observed.state.awayTeam.id, away.id)
        XCTAssertEqual(observed.state.homeTeam.id, home.id)
        XCTAssertEqual(observed.lineup.count, 9)
        XCTAssertEqual(observed.secondaryLineup?.count, 9)
    }

    func testPitcherInningsLimitCreatesFinalInningAndLimitWarnings() {
        let store = GameStore(persistenceURL: nil)
        let lineup = Array(store.currentTeam.players.prefix(9))
        store.startNewGame(
            opponent: store.opponentTeams[0],
            isHome: false,
            rules: GameRules(scheduledInnings: 6, pitcherInningsLimit: 1),
            lineup: lineup.enumerated().map {
                LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset])
            }
        )
        let pitcher = store.currentPitcher
        store.game.pitching[pitcher.id] = PitchingLine(outsRecorded: 3)

        XCTAssertTrue(store.ruleNotices().contains(where: { $0.contains("达到 1 局投球限制") }))
    }

    func testStructuredScoringEventKeepsChineseLogAndSituationSnapshots() throws {
        let store = freshStore()
        let before = store.game.situationSnapshot

        store.recordPitch(.ball)

        let event = try XCTUnwrap(store.game.scoringEvents?.last)
        XCTAssertEqual(event.category, .pitch)
        XCTAssertTrue(event.title.contains("坏球"))
        XCTAssertEqual(event.notation, "B")
        XCTAssertEqual(event.beforeSituation, before)
        XCTAssertEqual(event.afterSituation?.balls, 1)
        XCTAssertEqual(store.game.playLog.last?.text, event.title)
    }

    func testPinchHitterTakesLineupSpotAndRemovedPlayerCannotReturn() throws {
        let store = freshStore()
        let removed = store.currentBatter
        let replacement = try XCTUnwrap(store.battingBenchPlayers.first)

        store.replaceCurrentBatter(with: replacement)

        XCTAssertEqual(store.currentBatter.id, replacement.id)
        XCTAssertTrue(store.exitedPlayerIDs(forHomeTeam: false).contains(removed.id))
        XCTAssertFalse(store.battingBenchPlayers.contains(where: { $0.id == removed.id }))
        XCTAssertEqual(store.game.awayBattingOrderIDs.count, 9)
        XCTAssertEqual(store.game.scoringEvents?.last?.category, .substitution)
    }

    func testForceThirdOutAutomaticallyCancelsRun() {
        let store = freshStore()
        let firstRunner = store.game.awayTeam.players[1]
        let thirdRunner = store.game.awayTeam.players[2]
        store.game.outs = 2
        store.game.baseRunners = [.first: firstRunner, .third: thirdRunner]
        let decisions = [
            RunnerDecision(player: firstRunner, origin: .base(.first), destination: .out),
            RunnerDecision(player: thirdRunner, origin: .base(.third), destination: .score)
        ]

        XCTAssertFalse(store.runnerEventNeedsTimingDecision(.forceOut, decisions: decisions))
        store.recordRunnerEvent(.forceOut, decisions: decisions)

        XCTAssertEqual(store.game.awayScore, 0)
        XCTAssertFalse(store.game.isTop)
    }

    func testTagThirdOutRequiresTimingChoiceAndCanCountEarlierRun() {
        let store = freshStore()
        let firstRunner = store.game.awayTeam.players[1]
        let thirdRunner = store.game.awayTeam.players[2]
        store.game.outs = 2
        store.game.baseRunners = [.first: firstRunner, .third: thirdRunner]
        let decisions = [
            RunnerDecision(player: firstRunner, origin: .base(.first), destination: .out),
            RunnerDecision(player: thirdRunner, origin: .base(.third), destination: .score)
        ]

        XCTAssertTrue(store.runnerEventNeedsTimingDecision(.tagOut, decisions: decisions))
        store.recordRunnerEvent(.tagOut, decisions: decisions, timingRunCounts: true)

        XCTAssertEqual(store.game.awayScore, 1)
        XCTAssertFalse(store.game.isTop)
    }

    func testInfieldFlyImmediatelyRetiresBatterButKeepsRunnerChoicesLive() {
        let store = freshStore()
        let firstRunner = store.game.awayTeam.players[1]
        let secondRunner = store.game.awayTeam.players[2]
        store.game.baseRunners = [.first: firstRunner, .second: secondRunner]

        store.applyPlay(.infieldFly, defensivePlay: .caught(by: .shortstop))

        XCTAssertEqual(store.game.outs, 1)
        XCTAssertEqual(store.game.baseRunners[.first]?.id, firstRunner.id)
        XCTAssertEqual(store.game.baseRunners[.second]?.id, secondRunner.id)
        XCTAssertEqual(store.game.playLog.last?.text.contains("内野高飞必死"), true)
    }

    func testExtraInningTiebreakIsEnabledLiveAndAutomaticRunnerRunIsUnearned() throws {
        let store = freshStore()
        store.game.inning = 7
        store.game.homeRunsByInning.append(0)
        store.game.awayRunsByInning.append(0)

        XCTAssertTrue(store.canConfirmExtraInning)
        store.confirmExtraInning(useTiebreak: true)
        XCTAssertTrue(store.requiresTiebreakRunnerPlacement)

        let runner = try XCTUnwrap(store.recommendedTiebreakRunner)
        let pitcher = store.currentPitcher
        store.placeTiebreakRunner(runner)
        XCTAssertEqual(store.game.baseRunners[.second]?.id, runner.id)

        store.applyPlay(.double)

        XCTAssertEqual(store.pitchingLine(for: pitcher).runs, 1)
        XCTAssertEqual(store.pitchingLine(for: pitcher).earnedRuns, 0)
        XCTAssertEqual(store.game.scoringEvents?.contains(where: { $0.category == .tiebreak }), true)
    }

    func testViolationTapAppliesCertainResultAndKeepsCauseInChineseLog() {
        let store = freshStore()
        let batter = store.currentBatter

        store.recordViolation(.catcherInterference)

        XCTAssertEqual(store.game.baseRunners[.first]?.id, batter.id)
        XCTAssertTrue(store.game.playLog.last?.text.contains("捕手干扰打击") == true)
        XCTAssertEqual(store.game.scoringEvents?.last?.category, .violation)
    }

    func testGameClockStartsPausesResumesAndSwitchesToCountdown() throws {
        let store = GameStore(persistenceURL: nil)
        let lineup = Array(store.currentTeam.players.prefix(9)).enumerated().map { index, player in
            LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
        }
        _ = store.startNewGame(
            opponent: store.opponentTeams[0],
            isHome: false,
            rules: GameRules(scheduledInnings: 6, timeLimitMinutes: 90, timeWarningMinutes: 10),
            lineup: lineup
        )
        let start = Date(timeIntervalSince1970: 1_000)
        store.startGameClock(at: start)

        XCTAssertTrue(store.isGameClockRunning)
        XCTAssertEqual(store.elapsedGameTime(at: start.addingTimeInterval(61)), 61, accuracy: 0.01)
        XCTAssertEqual(store.gameClockText(at: start.addingTimeInterval(61)), "01:01")
        XCTAssertNotNil(store.ongoingGames.first?.startedAt)

        store.toggleGameClockDisplayMode()
        XCTAssertEqual(store.gameClockDisplayMode, .remaining)
        XCTAssertEqual(store.gameClockText(at: start.addingTimeInterval(60)), "89:00")

        store.pauseGameClock(at: start.addingTimeInterval(61))
        XCTAssertFalse(store.isGameClockRunning)
        XCTAssertEqual(store.elapsedGameTime(at: start.addingTimeInterval(500)), 61, accuracy: 0.01)

        store.startGameClock(at: start.addingTimeInterval(100))
        XCTAssertEqual(store.elapsedGameTime(at: start.addingTimeInterval(119)), 80, accuracy: 0.01)
        XCTAssertTrue(store.game.playLog.contains(where: { $0.text.contains("Play Ball") }))
        XCTAssertTrue(store.game.playLog.contains(where: { $0.text.contains("暂停") }))
        XCTAssertTrue(store.game.playLog.contains(where: { $0.text.contains("继续") }))
        XCTAssertTrue(store.ruleNotices(at: start.addingTimeInterval(82 * 60)).contains(where: { $0.contains("剩余约 9 分钟") }))
        XCTAssertTrue(store.ruleNotices(at: start.addingTimeInterval(92 * 60)).contains(where: { $0.contains("时间限制") }))
    }

    func testFirstScoringActionAutomaticallyStartsFormalGameClock() {
        let store = freshStore()
        XCTAssertFalse(store.hasStartedGameClock)

        store.recordPitch(.ball)

        XCTAssertTrue(store.hasStartedGameClock)
        XCTAssertTrue(store.game.scoringEvents?.contains(where: { $0.category == .clock }) == true)
    }

    func testUndoAndRedoRestoreSameScoringState() {
        let store = freshStore()
        store.recordPitch(.ball)
        XCTAssertEqual(store.game.balls, 1)

        store.undo()
        XCTAssertEqual(store.game.balls, 0)
        XCTAssertTrue(store.canRedo)

        store.redo()
        XCTAssertEqual(store.game.balls, 1)
        XCTAssertFalse(store.canRedo)
    }

    func testAutomaticWalkRunnerAdvanceAndSubstitutionKeepEventTimelineContinuous() throws {
        let store = freshStore()
        for _ in 0..<4 { store.recordPitch(.ball) }
        let runner = try XCTUnwrap(store.game.baseRunners[.first])
        XCTAssertTrue(store.recordRunnerEvent(
            .stolenBase,
            decisions: [RunnerDecision(player: runner, origin: .base(.first), destination: .base(.second))]
        ))
        if let replacement = store.battingBenchPlayers.first {
            store.replaceCurrentBatter(with: replacement)
        }
        XCTAssertTrue(store.applyPlay(.single))

        XCTAssertNil(store.validateScoringTimeline())
        XCTAssertTrue(store.game.playLog.contains(where: { $0.text.contains("四坏球") }))
        XCTAssertTrue(store.game.playLog.contains(where: { $0.text.contains("偷垒") }))
    }

    func testRunnerConflictAndImpossibleFourthOutAreRejected() {
        let store = freshStore()
        let first = store.game.awayTeam.players[1]
        let second = store.game.awayTeam.players[2]
        store.game.baseRunners = [.first: first, .second: second]
        let conflict = [
            RunnerDecision(player: first, origin: .base(.first), destination: .base(.third)),
            RunnerDecision(player: second, origin: .base(.second), destination: .base(.third))
        ]

        XCTAssertFalse(store.recordRunnerEvent(.doubleSteal, decisions: conflict))
        XCTAssertNotNil(store.actionErrorMessage)
        XCTAssertEqual(store.game.baseRunners[.first]?.id, first.id)

        store.game.outs = 2
        let tooManyOuts = [
            RunnerDecision(player: first, origin: .base(.first), destination: .out),
            RunnerDecision(player: second, origin: .base(.second), destination: .out)
        ]
        XCTAssertFalse(store.recordRunnerEvent(.tagOut, decisions: tooManyOuts, timingRunCounts: false))
        XCTAssertEqual(store.game.outs, 2)
    }

    func testInfieldFlyAndTriplePlayRejectImpossibleSituations() {
        let store = freshStore()

        XCTAssertFalse(store.applyPlay(.infieldFly, defensivePlay: .caught(by: .shortstop)))
        XCTAssertTrue(store.actionErrorMessage?.contains("一、二垒") == true)
        XCTAssertFalse(store.applyPlay(.triplePlay, defensivePlay: DefensivePlay.quickPlays[7]))
    }

    func testPendingEventCanBeReviewedAndTimelineRemainsValid() throws {
        let store = freshStore()
        XCTAssertTrue(store.applyPlay(.pending))
        let pending = try XCTUnwrap(store.pendingReviewEvents.first)
        store.recordPitch(.ball)

        XCTAssertNil(store.validateScoringTimeline())
        XCTAssertTrue(store.reviewPendingEvent(
            id: pending.id,
            title: "复核：打者因守备失误上一垒",
            category: .battedBall,
            notation: "E6",
            primaryPlayerID: pending.primaryPlayerID,
            secondaryPlayerID: nil,
            ballStatus: .live,
            resolvedOutcome: .single,
            note: "赛后根据录像确认"
        ))
        XCTAssertFalse(store.game.scoringEvents?.first(where: { $0.id == pending.id })?.needsReview ?? true)
        XCTAssertTrue(store.game.playLog.contains(where: { $0.text.contains("待确认记录已复核") }))
        XCTAssertNil(store.validateScoringTimeline())
    }

    func testPendingSafeArrivalReviewUpdatesHitAndPitcherStatistics() throws {
        let store = freshStore()
        let batter = store.currentBatter
        let pitcher = store.currentPitcher
        XCTAssertTrue(store.applyPlay(.pending))
        let pending = try XCTUnwrap(store.pendingReviewEvents.first)

        XCTAssertEqual(store.battingLine(for: batter).hits, 0)
        XCTAssertEqual(store.pitchingLine(for: pitcher).hits, 0)
        XCTAssertTrue(store.reviewPendingEvent(
            id: pending.id,
            title: "复核：一垒安打",
            category: .battedBall,
            notation: "1B",
            primaryPlayerID: batter.id,
            secondaryPlayerID: pitcher.id,
            ballStatus: .live,
            resolvedOutcome: .single,
            note: "录像确认安打"
        ))

        XCTAssertEqual(store.battingLine(for: batter).hits, 1)
        XCTAssertEqual(store.pitchingLine(for: pitcher).hits, 1)
        XCTAssertEqual(store.game.awayHits, 1)
        XCTAssertEqual(store.game.scoringEvents?.first(where: { $0.id == pending.id })?.resolvedOutcome, .single)
    }

    func testPendingReviewRejectsOutcomeThatConflictsWithRecordedBatterResult() throws {
        let store = freshStore()
        XCTAssertTrue(store.applyPlay(.pending))
        let pending = try XCTUnwrap(store.pendingReviewEvents.first)
        let originalBatting = store.game.batting

        XCTAssertFalse(store.reviewPendingEvent(
            id: pending.id,
            title: "复核为出局",
            category: .out,
            notation: "GO",
            primaryPlayerID: pending.primaryPlayerID,
            secondaryPlayerID: pending.secondaryPlayerID,
            ballStatus: .live,
            resolvedOutcome: .groundOut,
            note: "尝试修改"
        ))
        XCTAssertTrue(store.actionErrorMessage?.contains("安全上垒") == true)
        XCTAssertEqual(store.game.batting, originalBatting)
        XCTAssertEqual(store.pendingReviewEvents.count, 1)
    }

    func testPendingErrorReviewRequiresAndCreditsDefensiveResponsibility() throws {
        let store = freshStore()
        XCTAssertTrue(store.applyPlay(.pending))
        let pending = try XCTUnwrap(store.pendingReviewEvents.first)
        let shortstop = try XCTUnwrap(store.game.homeTeam.players.first(where: { $0.primaryPosition == .shortstop }))

        XCTAssertTrue(store.reviewPendingEvent(
            id: pending.id,
            title: "复核：游击手失误，打者上一垒",
            category: .battedBall,
            notation: "E6",
            primaryPlayerID: pending.primaryPlayerID,
            secondaryPlayerID: shortstop.id,
            ballStatus: .live,
            resolvedOutcome: .error,
            note: "录像确认责任人"
        ))
        XCTAssertEqual(store.game.fielding[shortstop.id]?.errors, 1)
        XCTAssertEqual(store.game.homeErrors, 1)
    }

    func testNonBattedPendingViolationCanBeReviewedWithoutPlayOutcome() throws {
        let store = freshStore()
        XCTAssertTrue(store.recordViolation(.foreignSubstance))
        let pending = try XCTUnwrap(store.pendingReviewEvents.first)

        XCTAssertTrue(store.reviewPendingEvent(
            id: pending.id,
            title: "裁判确认投手球体处理违规，警告后继续比赛",
            category: .violation,
            notation: "WARN",
            primaryPlayerID: pending.primaryPlayerID,
            secondaryPlayerID: nil,
            ballStatus: .umpireDecision,
            resolvedOutcome: nil,
            note: "主审最终宣判"
        ))
        XCTAssertTrue(store.pendingReviewEvents.isEmpty)
    }

    func testHistoricalSituationEditRejectsConflictWithLaterEvent() throws {
        let store = freshStore()
        XCTAssertTrue(store.applyPlay(.pending))
        let pending = try XCTUnwrap(store.pendingReviewEvents.first)
        store.recordPitch(.ball)
        let original = try XCTUnwrap(pending.afterSituation)
        let conflicting = GameSituationSnapshot(
            inning: original.inning,
            isTop: original.isTop,
            balls: 3,
            strikes: original.strikes,
            outs: original.outs,
            homeRunsByInning: original.homeRunsByInning,
            awayRunsByInning: original.awayRunsByInning,
            homeBatterIndex: original.homeBatterIndex,
            awayBatterIndex: original.awayBatterIndex,
            baseRunners: original.baseRunners,
            activeHomePitcherID: original.activeHomePitcherID,
            activeAwayPitcherID: original.activeAwayPitcherID
        )

        XCTAssertFalse(store.replacePendingEventSituation(id: pending.id, with: conflicting))
        XCTAssertTrue(store.actionErrorMessage?.contains("不衔接") == true)
        XCTAssertEqual(store.game.balls, 1)
    }

    func testViolationRecordsDeadBallClassification() throws {
        let store = freshStore()
        XCTAssertTrue(store.recordViolation(.batterInterference))

        let event = try XCTUnwrap(store.game.scoringEvents?.last(where: { $0.category == .violation }))
        XCTAssertEqual(event.ballStatus, .dead)
    }

    func testCustomDefensiveRouteCreditsAssistAndPutout() throws {
        let store = freshStore()
        let shortstop = try XCTUnwrap(store.game.fieldingTeam.players.first(where: { $0.primaryPosition == .shortstop }))
        let firstBase = try XCTUnwrap(store.game.fieldingTeam.players.first(where: { $0.primaryPosition == .firstBase }))
        let route = DefensivePlay.custom(route: [.shortstop, .firstBase], outcome: .groundOut)

        XCTAssertTrue(store.applyPlay(.groundOut, defensivePlay: route))

        XCTAssertEqual(store.fieldingLine(for: shortstop).assists, 1)
        XCTAssertEqual(store.fieldingLine(for: firstBase).putouts, 1)
    }

    func testEndReasonFreezesClockAndSuspensionKeepsGameOngoing() {
        let store = freshStore()
        let start = Date(timeIntervalSince1970: 2_000)
        store.startGameClock(at: start)

        store.finishGame(reason: .suspended, at: start.addingTimeInterval(120))
        XCTAssertFalse(store.game.isFinal)
        XCTAssertFalse(store.isGameClockRunning)
        XCTAssertEqual(store.elapsedGameTime(at: start.addingTimeInterval(500)), 120, accuracy: 0.01)

        store.finishGame(reason: .timeLimit, at: start.addingTimeInterval(500))
        XCTAssertTrue(store.game.isFinal)
        XCTAssertEqual(store.game.endReason, .timeLimit)
        XCTAssertNotNil(store.game.endedAt)
    }

    func testClockAndPendingReviewPersistInCoreDataGamePayload() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BaseballMasterClockPersistence-\(UUID().uuidString)", isDirectory: true)
        let databaseURL = directory.appendingPathComponent("BaseballMaster.sqlite")
        defer { try? FileManager.default.removeItem(at: directory) }

        let first = GameStore(persistenceURL: databaseURL)
        let lineup = Array(first.currentTeam.players.prefix(9)).enumerated().map { index, player in
            LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
        }
        let gameID = first.startNewGame(
            opponent: first.opponentTeams[0],
            isHome: false,
            rules: GameRules(timeLimitMinutes: 90, timeWarningMinutes: 10),
            lineup: lineup
        )
        let now = Date()
        first.startGameClock(at: now.addingTimeInterval(-30))
        first.toggleGameClockDisplayMode()
        XCTAssertTrue(first.applyPlay(.pending))
        first.pauseGameClock(at: now)

        let reloaded = GameStore(persistenceURL: databaseURL)
        XCTAssertEqual(reloaded.ongoingGames.first?.id, gameID)
        XCTAssertEqual(reloaded.game.clockDisplayMode, .remaining)
        XCTAssertEqual(reloaded.elapsedGameTime(at: now), 30, accuracy: 1)
        XCTAssertFalse(reloaded.pendingReviewEvents.isEmpty)
        XCTAssertNil(reloaded.validateScoringTimeline())
    }

    func testRosterSchemaV1SQLiteMigratesToV2() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BaseballMasterV1MigrationTests-\(UUID().uuidString)", isDirectory: true)
        let databaseURL = directory.appendingPathComponent("BaseballMaster.sqlite")
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        let teamID = UUID()
        let model = makeV1TestModel()
        let coordinator = NSPersistentStoreCoordinator(managedObjectModel: model)
        let persistentStore = try coordinator.addPersistentStore(
            type: .sqlite,
            configuration: nil,
            at: databaseURL,
            options: nil
        )
        let context = NSManagedObjectContext(concurrencyType: .privateQueueConcurrencyType)
        context.persistentStoreCoordinator = coordinator
        try context.performAndWait {
            let team = NSEntityDescription.insertNewObject(forEntityName: "RosterTeam", into: context)
            team.setValue(teamID, forKey: "id")
            team.setValue("旧版球队", forKey: "name")
            team.setValue("旧队", forKey: "shortName")
            team.setValue("广州", forKey: "city")
            team.setValue(Int32(0), forKey: "sortOrder")
            try context.save()
        }
        try coordinator.remove(persistentStore)

        let migrated = GameStore(persistenceURL: databaseURL)
        XCTAssertEqual(migrated.team(withID: teamID)?.name, "旧版球队")
        XCTAssertFalse(migrated.opponentTeams.isEmpty)
        XCTAssertTrue(migrated.games.isEmpty)
    }

    func testArbitraryFielderReplacementUpdatesDefenseBattingSlotAndExitState() throws {
        let store = freshStore()
        store.game.isTop = false
        let previous = try XCTUnwrap(store.activeFielders.first)
        let replacement = try XCTUnwrap(store.fieldingBenchPlayers.first)

        XCTAssertTrue(store.replaceFielder(previous, with: replacement))

        XCTAssertFalse(store.activeFielders.contains(where: { $0.id == previous.id }))
        XCTAssertTrue(store.activeFielders.contains(where: { $0.id == replacement.id }))
        XCTAssertTrue(store.game.awayBattingOrderIDs.contains(replacement.id))
        XCTAssertTrue(store.exitedPlayerIDs(forHomeTeam: false).contains(previous.id))
        XCTAssertTrue(store.game.playLog.last?.text.contains("守备换人") == true)
    }

    func testFormalDoubleSwitchReplacesTwoPlayersAndPreservesTwoDistinctPositions() throws {
        let store = freshStore()
        store.game.isTop = false
        let fielders = store.activeFielders
        let bench = store.fieldingBenchPlayers
        let firstOut = try XCTUnwrap(fielders.first)
        let secondOut = try XCTUnwrap(fielders.dropFirst().first)
        let firstIn = try XCTUnwrap(bench.first)
        let secondIn = try XCTUnwrap(bench.dropFirst().first)

        XCTAssertTrue(store.performDoubleSwitch(
            firstOut: firstOut,
            firstIn: firstIn,
            firstPosition: firstOut.primaryPosition,
            secondOut: secondOut,
            secondIn: secondIn,
            secondPosition: secondOut.primaryPosition
        ))

        XCTAssertTrue(store.game.awayBattingOrderIDs.contains(firstIn.id))
        XCTAssertTrue(store.game.awayBattingOrderIDs.contains(secondIn.id))
        XCTAssertFalse(store.game.awayBattingOrderIDs.contains(firstOut.id))
        XCTAssertFalse(store.game.awayBattingOrderIDs.contains(secondOut.id))
        XCTAssertTrue(store.game.playLog.last?.text.contains("双重换人") == true)
    }

    func testDesignatedHitterAndTwoWayRulesChangeActualParticipation() throws {
        let store = GameStore(persistenceURL: nil)
        var opponent = store.opponentTeams[0]
        opponent.players.append(Player(name: "对手DH", number: 99, primaryPosition: .rightField))
        let lineup = Array(store.currentTeam.players.prefix(9)).enumerated().map { index, player in
            LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
        }
        _ = store.startNewGame(
            opponent: opponent,
            isHome: false,
            rules: GameRules(usesDesignatedHitter: true, allowsTwoWayPlayer: false),
            lineup: lineup
        )
        let pitcherID = try XCTUnwrap(store.game.awayFieldingPlayerIDs?.first)
        let dhID = try XCTUnwrap(store.game.awayDesignatedHitterID)
        XCTAssertNotEqual(pitcherID, dhID)
        XCTAssertFalse(store.game.awayBattingOrderIDs.contains(pitcherID))
        XCTAssertTrue(store.game.awayBattingOrderIDs.contains(dhID))
        XCTAssertTrue(store.game.awayFieldingPlayerIDs?.contains(pitcherID) == true)

        _ = store.startNewGame(
            opponent: opponent,
            isHome: false,
            rules: GameRules(usesDesignatedHitter: true, allowsTwoWayPlayer: true),
            lineup: lineup
        )
        let twoWayPitcherID = try XCTUnwrap(store.game.awayFieldingPlayerIDs?.first)
        XCTAssertEqual(store.game.awayDesignatedHitterID, twoWayPitcherID)
        XCTAssertTrue(store.game.awayBattingOrderIDs.contains(twoWayPitcherID))

        store.game.isTop = false
        let reliefPitcher = try XCTUnwrap(store.fieldingBenchPlayers.first)
        store.changePitcher(to: reliefPitcher)
        XCTAssertEqual(store.game.activeAwayPitcherID, reliefPitcher.id)
        XCTAssertFalse(store.game.awayFieldingPlayerIDs?.contains(twoWayPitcherID) == true)
        XCTAssertTrue(store.game.awayBattingOrderIDs.contains(twoWayPitcherID))
        XCTAssertEqual(store.game.awayDesignatedHitterID, twoWayPitcherID)
        XCTAssertFalse(store.exitedPlayerIDs(forHomeTeam: false).contains(twoWayPitcherID))
        XCTAssertTrue(store.game.playLog.last?.text.contains("继续以 DH 身份打击") == true)
    }

    func testCustomUmpireRulingHandlesBatterAndMultipleExistingRunners() throws {
        let store = freshStore()
        let batter = store.currentBatter
        let first = store.game.awayTeam.players[1]
        let second = store.game.awayTeam.players[2]
        store.game.baseRunners = [.first: first, .second: second]
        let ruling = ViolationAdjudication(
            ballStatus: .dead,
            previousPlayDisposition: .notApplicable,
            plateAppearanceDisposition: .batterOut,
            runnerDecisions: [
                RunnerDecision(player: first, origin: .base(.first), destination: .out),
                RunnerDecision(player: second, origin: .base(.second), destination: .base(.third)),
                RunnerDecision(player: batter, origin: .batter, destination: .out)
            ],
            countsAsAtBat: true,
            isFinalRuling: true
        )

        XCTAssertTrue(store.recordViolation(.runnerInterference, adjudication: ruling))
        XCTAssertEqual(store.game.outs, 2)
        XCTAssertEqual(store.game.baseRunners[.third]?.id, second.id)
        XCTAssertEqual(store.battingLine(for: batter).plateAppearances, 1)
        XCTAssertEqual(store.game.scoringEvents?.last(where: { $0.category == .violation })?.ballStatus, .dead)
    }

    func testCustomUmpireRulingCanCancelPreviousPlayResult() throws {
        let store = freshStore()
        let batter = store.currentBatter
        XCTAssertTrue(store.applyPlay(.single))
        XCTAssertEqual(store.game.baseRunners[.first]?.id, batter.id)
        let ruling = ViolationAdjudication(
            ballStatus: .dead,
            previousPlayDisposition: .cancel,
            plateAppearanceDisposition: .batterOut,
            runnerDecisions: [RunnerDecision(player: batter, origin: .batter, destination: .out)],
            countsAsAtBat: true,
            isFinalRuling: true
        )

        XCTAssertTrue(store.recordViolation(.batterInterference, adjudication: ruling))
        XCTAssertNil(store.game.baseRunners[.first])
        XCTAssertEqual(store.battingLine(for: batter).hits, 0)
        XCTAssertEqual(store.battingLine(for: batter).atBats, 1)
        XCTAssertTrue(store.game.playLog.last?.text.contains("取消上一比赛结果") == true)
    }

    func testCustomUmpireRulingOverridesHitRBIEarnedRunAndErrorResponsibility() throws {
        let store = freshStore()
        let batter = store.currentBatter
        let pitcher = store.currentPitcher
        let firstRunner = store.game.awayTeam.players[1]
        let thirdRunner = store.game.awayTeam.players[2]
        let fielder = try XCTUnwrap(store.activeFielders.first)
        store.game.baseRunners = [.first: firstRunner, .third: thirdRunner]

        let ruling = ViolationAdjudication(
            ballStatus: .dead,
            previousPlayDisposition: .notApplicable,
            plateAppearanceDisposition: .batterFirst,
            runnerDecisions: [
                RunnerDecision(player: firstRunner, origin: .base(.first), destination: .base(.second)),
                RunnerDecision(player: thirdRunner, origin: .base(.third), destination: .score),
                RunnerDecision(player: batter, origin: .batter, destination: .base(.first))
            ],
            countsAsAtBat: true,
            isFinalRuling: true,
            battingCredit: .reachedOnError,
            runsBattedIn: 1,
            earnedRuns: 0,
            fieldingErrorPlayerID: fielder.id
        )

        XCTAssertTrue(store.recordViolation(.detachedEquipment, adjudication: ruling))
        XCTAssertEqual(store.battingLine(for: batter).plateAppearances, 1)
        XCTAssertEqual(store.battingLine(for: batter).atBats, 1)
        XCTAssertEqual(store.battingLine(for: batter).hits, 0)
        XCTAssertEqual(store.battingLine(for: batter).runsBattedIn, 1)
        XCTAssertEqual(store.fieldingLine(for: fielder).errors, 1)
        XCTAssertEqual(store.pitchingLine(for: pitcher).runs, 1)
        XCTAssertEqual(store.pitchingLine(for: pitcher).earnedRuns, 0)
        XCTAssertEqual(store.game.scoringEvents?.last?.resolvedOutcome, .error)
    }

    func testCustomUmpireRulingCanCreditHitRBIAndEarnedRun() throws {
        let store = freshStore()
        let batter = store.currentBatter
        let pitcher = store.currentPitcher
        let ruling = ViolationAdjudication(
            ballStatus: .delayedDead,
            previousPlayDisposition: .notApplicable,
            plateAppearanceDisposition: .batterFirst,
            runnerDecisions: [
                RunnerDecision(player: batter, origin: .batter, destination: .score)
            ],
            countsAsAtBat: true,
            isFinalRuling: true,
            battingCredit: .homeRun,
            runsBattedIn: 1,
            earnedRuns: 1
        )

        XCTAssertTrue(store.recordViolation(.obstruction, adjudication: ruling))
        XCTAssertEqual(store.battingLine(for: batter).hits, 1)
        XCTAssertEqual(store.battingLine(for: batter).homeRuns, 1)
        XCTAssertEqual(store.battingLine(for: batter).runsBattedIn, 1)
        XCTAssertEqual(store.pitchingLine(for: pitcher).hits, 1)
        XCTAssertEqual(store.pitchingLine(for: pitcher).runs, 1)
        XCTAssertEqual(store.pitchingLine(for: pitcher).earnedRuns, 1)
        XCTAssertEqual(store.game.awayHits, 1)
        XCTAssertEqual(store.game.scoringEvents?.last?.resolvedOutcome, .homeRun)
    }

    func testTiebreakConfigurationAndPinchRunnerKeepAutomaticUnearnedStatusAcrossPitchingChange() throws {
        let store = freshStore()
        store.game.inning = 7
        store.game.homeRunsByInning.append(0)
        store.game.awayRunsByInning.append(0)
        store.confirmExtraInning(useTiebreak: true, runnerBases: [.first, .second])

        let firstAutomatic = try XCTUnwrap(store.recommendedTiebreakRunner)
        store.placeTiebreakRunner(firstAutomatic)
        let pinchRunner = try XCTUnwrap(store.battingBenchPlayers.first)
        store.replaceRunner(on: .first, with: pinchRunner)
        let secondAutomatic = try XCTUnwrap(store.eligibleTiebreakRunners.first)
        store.placeTiebreakRunner(secondAutomatic)

        XCTAssertFalse(store.requiresTiebreakRunnerPlacement)
        XCTAssertEqual(store.game.baseRunners.count, 2)
        XCTAssertTrue(store.game.automaticRunnerIDs?.contains(pinchRunner.id) == true)
        XCTAssertFalse(store.game.automaticRunnerIDs?.contains(firstAutomatic.id) == true)

        let newPitcher = store.game.fieldingTeam.players[1]
        store.changePitcher(to: newPitcher)
        XCTAssertTrue(store.applyPlay(.homeRun))
        XCTAssertEqual(store.pitchingLine(for: newPitcher).runs, 3)
        XCTAssertEqual(store.pitchingLine(for: newPitcher).earnedRuns, 1)
    }

    func testTiebreakRequestsFreshAutomaticRunnerInEachHalfInning() throws {
        let store = freshStore()
        store.game.inning = 7
        store.game.homeRunsByInning.append(0)
        store.game.awayRunsByInning.append(0)
        store.confirmExtraInning(useTiebreak: true)
        let topRunner = try XCTUnwrap(store.recommendedTiebreakRunner)
        store.placeTiebreakRunner(topRunner)

        XCTAssertTrue(store.applyPlay(.groundOut))
        XCTAssertTrue(store.applyPlay(.groundOut))
        XCTAssertTrue(store.applyPlay(.groundOut))

        XCTAssertFalse(store.game.isTop)
        XCTAssertTrue(store.game.baseRunners.isEmpty)
        XCTAssertTrue(store.game.automaticRunnerIDs?.isEmpty == true)
        XCTAssertTrue(store.requiresTiebreakRunnerPlacement)
        let bottomRunner = try XCTUnwrap(store.recommendedTiebreakRunner)
        store.placeTiebreakRunner(bottomRunner)
        XCTAssertEqual(store.game.baseRunners[.second]?.id, bottomRunner.id)
        XCTAssertFalse(store.requiresTiebreakRunnerPlacement)
    }

    func testPlateAppearanceChineseRecordAggregatesEveryEventIntoOneEntry() throws {
        let store = freshStore()
        let batter = store.currentBatter
        store.recordPitch(.ball)
        store.recordPitch(.foul)
        XCTAssertTrue(store.applyPlay(.single))

        let appearances = store.plateAppearanceRecords()
        let appearance = try XCTUnwrap(appearances.first)
        XCTAssertEqual(appearances.count, 1)
        XCTAssertEqual(appearance.batter.id, batter.id)
        XCTAssertEqual(appearance.events.count, 3)
        XCTAssertTrue(appearance.chineseRecord.contains("坏球"))
        XCTAssertTrue(appearance.chineseRecord.contains("界外球"))
        XCTAssertTrue(appearance.chineseRecord.contains("一垒安打"))
        XCTAssertTrue(appearance.isComplete)
    }

    func testRunnerActionDuringAtBatRemainsInCurrentBattersChineseRecord() throws {
        let store = freshStore()
        let leadoff = store.currentBatter
        XCTAssertTrue(store.applyPlay(.single))
        let currentBatter = store.currentBatter
        store.recordPitch(.ball)
        let steal = store.suggestedRunnerEventDecisions(for: .stolenBase)
        XCTAssertTrue(store.recordRunnerEvent(.stolenBase, decisions: steal))
        store.recordPitch(.calledStrike)
        XCTAssertTrue(store.applyPlay(.flyOut))

        let appearances = store.plateAppearanceRecords()
        XCTAssertEqual(appearances.count, 2)
        XCTAssertEqual(appearances[0].batter.id, leadoff.id)
        XCTAssertEqual(appearances[1].batter.id, currentBatter.id)
        XCTAssertEqual(appearances[1].events.count, 4)
        XCTAssertTrue(appearances[1].chineseRecord.contains("偷垒"))
        XCTAssertTrue(appearances[1].chineseRecord.contains("飞球出局"))
    }

    func testCompleteRecordAndProfessionalBoxScoreExports() throws {
        let store = freshStore()
        store.recordPitch(.calledStrike)
        XCTAssertTrue(store.applyPlay(.double))
        let exporter = GameExportService(
            game: store.game,
            rules: store.activeRules,
            plateAppearances: store.plateAppearanceRecords(),
            gameEvents: store.nonPlateAppearanceGameEvents()
        )

        let text = exporter.completeRecordText()
        XCTAssertTrue(text.contains("【逐局比分】"))
        XCTAssertTrue(text.contains("【按打席中文比赛记录】"))
        XCTAssertTrue(text.contains("看振"))
        XCTAssertTrue(text.contains("二垒安打"))
        XCTAssertTrue(text.contains("【结构化事件附录】"))

        let pdf = exporter.boxScorePDFData()
        XCTAssertGreaterThan(pdf.count, 1_000)
        XCTAssertEqual(String(data: pdf.prefix(4), encoding: .ascii), "%PDF")
        let document = try XCTUnwrap(PDFDocument(data: pdf))
        XCTAssertGreaterThanOrEqual(document.pageCount, 1)
        for pageIndex in 0..<document.pageCount {
            XCTAssertTrue(document.page(at: pageIndex)?.string?.contains("OFFICIAL BOX SCORE") == true)
        }
        let extractedPDFText = (0..<document.pageCount)
            .compactMap { document.page(at: $0)?.string }
            .joined(separator: "\n")
        XCTAssertTrue(extractedPDFText.contains("LINE SCORE"))
        XCTAssertTrue(extractedPDFText.contains("BATTING"))
        XCTAssertTrue(extractedPDFText.contains("PITCHING"))
        XCTAssertTrue(extractedPDFText.contains("FIELDING"))

        let textURL = try exporter.writeCompleteRecord()
        let pdfURL = try exporter.writeBoxScorePDF()
        XCTAssertTrue(FileManager.default.fileExists(atPath: textURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdfURL.path))
        XCTAssertEqual(textURL.pathExtension, "txt")
        XCTAssertEqual(pdfURL.pathExtension, "pdf")
    }

    private func makeV1TestModel() -> NSManagedObjectModel {
        func attribute(_ name: String, _ type: NSAttributeType) -> NSAttributeDescription {
            let value = NSAttributeDescription()
            value.name = name
            value.attributeType = type
            value.isOptional = false
            return value
        }
        func entity(_ name: String, _ attributes: [NSAttributeDescription]) -> NSEntityDescription {
            let value = NSEntityDescription()
            value.name = name
            value.managedObjectClassName = NSStringFromClass(NSManagedObject.self)
            value.properties = attributes
            value.uniquenessConstraints = [["id"]]
            return value
        }

        let metadata = entity("RosterMetadata", [
            attribute("id", .stringAttributeType), attribute("currentTeamID", .UUIDAttributeType),
            attribute("schemaVersion", .integer32AttributeType)
        ])
        let team = entity("RosterTeam", [
            attribute("id", .UUIDAttributeType), attribute("name", .stringAttributeType),
            attribute("shortName", .stringAttributeType), attribute("city", .stringAttributeType),
            attribute("sortOrder", .integer32AttributeType)
        ])
        let player = entity("RosterPlayer", [
            attribute("id", .UUIDAttributeType), attribute("rosterTeamID", .UUIDAttributeType),
            attribute("chineseName", .stringAttributeType), attribute("englishName", .stringAttributeType),
            attribute("numbersData", .binaryDataAttributeType), attribute("primaryPosition", .integer16AttributeType),
            attribute("sortOrder", .integer32AttributeType)
        ])
        let season = entity("RosterSeason", [
            attribute("id", .stringAttributeType), attribute("name", .stringAttributeType),
            attribute("sortOrder", .integer32AttributeType)
        ])
        let integerFields = [
            "plateAppearances", "atBats", "runs", "hits", "doubles", "triples", "homeRuns",
            "runsBattedIn", "walks", "hitByPitch", "strikeouts", "stolenBases", "caughtStealing", "sacrifices"
        ]
        var recordFields = [
            attribute("id", .UUIDAttributeType), attribute("playerID", .UUIDAttributeType),
            attribute("seasonID", .stringAttributeType), attribute("date", .dateAttributeType),
            attribute("opponent", .stringAttributeType), attribute("result", .stringAttributeType)
        ]
        recordFields.append(contentsOf: integerFields.map { attribute($0, .integer32AttributeType) })
        let record = entity("RosterPlayerGameRecord", recordFields)

        let model = NSManagedObjectModel()
        model.versionIdentifiers = ["RosterSchemaV1"]
        model.entities = [metadata, team, player, season, record]
        return model
    }
}
