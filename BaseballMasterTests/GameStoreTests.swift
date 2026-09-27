import XCTest
import CoreData
import PDFKit
import SQLite3
@testable import BaseballMaster

@MainActor
final class GameStoreTests: XCTestCase {
    func testLocalBackupRoundTripPreservesGamesEventsAndRestoresOnRestart() throws {
        let source = freshStore()
        source.recordPitch(.ball)
        source.applyPlay(.single)
        source.recordPitch(.calledStrike)
        let backup = try LocalBackup.read(source.exportBackup())
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("BackupRoundTrip-\(UUID())")
        let url = directory.appendingPathComponent("BaseballMaster.sqlite")
        let destination = GameStore(persistenceURL: url)
        try destination.restoreBackup(backup)
        XCTAssertEqual(destination.games, source.games)
        XCTAssertEqual(destination.teams, source.teams)
        XCTAssertEqual(destination.game.scoringEvents, source.game.scoringEvents)
        let reopened = GameStore(persistenceURL: url)
        XCTAssertFalse(reopened.requiresDataRecovery)
        XCTAssertEqual(reopened.games, source.games)
        XCTAssertEqual(reopened.game.currentBatter.id, source.game.currentBatter.id)
        XCTAssertFalse(destination.automaticBackupURLs.isEmpty)
    }

    func testCorruptDatabaseIsPreservedAndCanRecoverFromBackup() throws {
        let source = freshStore()
        source.recordPitch(.foul)
        let backup = try LocalBackup.read(source.exportBackup())
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("CorruptRecovery-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("BaseballMaster.sqlite")
        let broken = Data("This is not a SQLite database".utf8)
        try broken.write(to: url)
        let store = GameStore(persistenceURL: url)
        XCTAssertTrue(store.requiresDataRecovery)
        XCTAssertEqual(try Data(contentsOf: url), broken)
        XCTAssertThrowsError(try store.exportBackup())
        try store.restoreBackup(backup)
        XCTAssertFalse(store.requiresDataRecovery)
        XCTAssertEqual(GameStore(persistenceURL: url).games, source.games)
        let archive = try XCTUnwrap(try FileManager.default.contentsOfDirectory(at: directory.appendingPathComponent("Recovery"), includingPropertiesForKeys: nil).first)
        XCTAssertEqual(try Data(contentsOf: archive.appendingPathComponent(url.lastPathComponent)), broken)
    }

    func testInvalidAndFutureBackupsCannotReplaceExistingData() throws {
        let source = freshStore()
        let backup = try LocalBackup.read(source.exportBackup())
        let destination = freshStore()
        let original = destination.teams
        for key in ["version", "checksum"] {
            var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(backup)) as? [String: Any])
            json[key] = key == "version" ? 99 : "broken"
            let altered = try JSONDecoder().decode(LocalBackup.self, from: JSONSerialization.data(withJSONObject: json))
            XCTAssertThrowsError(try destination.restoreBackup(altered))
            XCTAssertEqual(destination.teams, original)
        }
        var game = source.games[0]
        game.state.awayBatterIndex = -1
        let snapshot = RosterSnapshot(teams: source.teams, currentTeamID: source.currentTeam!.id, seasons: source.seasons,
                                      playerGameRecords: [], games: [game])
        XCTAssertThrowsError(try LocalBackup(snapshot: snapshot))
    }

    func testAutomaticBackupsRotateAndEmptyOpponentRosterStaysEmpty() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("BackupRotation-\(UUID())")
        let url = directory.appendingPathComponent("BaseballMaster.sqlite")
        let store = GameStore(persistenceURL: url)
        for team in store.opponentTeams { _ = store.deleteOpponentTeam(id: team.id) }
        for _ in 0..<5 { store.createAutomaticBackup() }
        XCTAssertLessThanOrEqual(store.automaticBackupURLs.count, 3)
        XCTAssertTrue(GameStore(persistenceURL: url).opponentTeams.isEmpty)
        let backup = try LocalBackup.read(store.exportBackup())
        XCTAssertTrue(try backup.snapshot().opponentTeams.isEmpty)
    }

    func testFutureDatabaseVersionAndMalformedPayloadRequireRecovery() throws {
        for sql in ["UPDATE ZROSTERMETADATA SET ZSCHEMAVERSION=99", "UPDATE ZSTOREDGAME SET ZPAYLOADDATA=x'7b7d'", "UPDATE ZROSTERSEASON SET ZNAME=NULL"] {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("SchemaProtection-\(UUID())")
            let url = directory.appendingPathComponent("BaseballMaster.sqlite")
            let seed = freshStore()
            do {
                let db = try CoreDataRosterStore(storeURL: url)
                try db.replaceAll(with: LocalBackup.read(seed.exportBackup()).snapshot())
                try db.close()
            }
            var handle: OpaquePointer?
            XCTAssertEqual(sqlite3_open(url.path, &handle), SQLITE_OK)
            XCTAssertEqual(sqlite3_exec(handle, sql, nil, nil, nil), SQLITE_OK)
            sqlite3_close(handle)
            let original = try Data(contentsOf: url)
            let store = GameStore(persistenceURL: url)
            XCTAssertTrue(store.requiresDataRecovery)
            XCTAssertEqual(try Data(contentsOf: url), original)
        }
    }

    func testPlayByPlayPDFKeepsBallsRunnersTransitionsAndSingleAppearanceScope() throws {
        let store = freshStore()
        for _ in 0..<4 { store.recordPitch(.ball) }
        store.recordPitch(.calledStrike)
        store.recordRunnerEvent(.stolenBase, decisions: store.suggestedRunnerEventDecisions(for: .stolenBase))
        store.applyPlay(.double)
        for _ in 0..<3 { store.applyPlay(.groundOut) }
        store.recordPitch(.foul)
        let report = PlayByPlayPDFReport(game: store.game, appearances: store.plateAppearanceRecords(), playedAt: store.activeGameRecordedAt)
        XCTAssertEqual(report.entries.flatMap(\.events).map(\.id), store.game.scoringEvents?.map(\.id))
        XCTAssertEqual(report.entries.last?.after?.isTop, false)
        let text = try reportText(report.pdfData())
        for expected in ["PLAY BY PLAY", "逐打席", "坏球", "偷垒", "二垒安打", "攻守交换", "打席未完成"] {
            XCTAssertTrue(text.contains(expected), expected)
        }
        let single = try reportText(report.pdfData(appearanceID: report.entries[0].appearance.id))
        XCTAssertTrue(single.contains("保送"))
        XCTAssertFalse(single.contains("二垒安打"))
    }

    func testPlayByPlayMissingSnapshotsAndLongAppearancePaginateHonestly() throws {
        let store = freshStore()
        for _ in 0..<35 { store.recordPitch(.foul) }
        store.game.scoringEvents = store.game.scoringEvents?.map { event in
            var value = event; value.beforeSituation = nil; value.afterSituation = nil; return value
        }
        let report = PlayByPlayPDFReport(game: store.game, appearances: store.plateAppearanceRecords(), playedAt: nil)
        let data = report.pdfData(appearanceID: report.entries.first?.appearance.id)
        let text = try reportText(data)
        XCTAssertTrue(text.contains("此局面未记录"))
        XCTAssertTrue(text.contains("(续)"))
        XCTAssertTrue(text.contains("35 · 投球"))
        let empty = PlayByPlayPDFReport(game: GameStore(persistenceURL: nil).game, appearances: [], playedAt: nil)
        XCTAssertTrue(try reportText(empty.pdfData()).contains("暂无可导出"))
    }

    func testTextOnlyPlayByPlayPreservesDescriptionsAndSingleAppearanceScope() throws {
        let store = freshStore()
        for _ in 0..<4 { store.recordPitch(.ball) }
        store.recordPitch(.calledStrike)
        store.recordRunnerEvent(.stolenBase, decisions: store.suggestedRunnerEventDecisions(for: .stolenBase))
        store.applyPlay(.double)
        for _ in 0..<3 { store.applyPlay(.groundOut) }
        store.recordPitch(.foul)
        let report = PlayByPlayPDFReport(game: store.game, appearances: store.plateAppearanceRecords(), playedAt: store.activeGameRecordedAt)
        let data = report.pdfData(style: .textOnly)
        let document = try XCTUnwrap(PDFDocument(data: data))
        let text = try reportText(data, portrait: true)
        XCTAssertEqual(document.pageCount, 1, "Six short appearances should share one page")
        let bounds = try XCTUnwrap(document.page(at: 0)).bounds(for: .mediaBox)
        XCTAssertLessThan(bounds.width, bounds.height)
        for expected in ["第1局上", "第1局下", "保送", "偷垒", "二垒安打", "界外", "打席未完成"] {
            XCTAssertTrue(text.contains(expected), expected)
        }
        let first = try XCTUnwrap(text.range(of: "第 1 打席"))
        let last = try XCTUnwrap(text.range(of: "第 6 打席"))
        XCTAssertLessThan(first.lowerBound, last.lowerBound)
        for excluded in ["打席索引", "打席前", "B/S/O", "事件后垒况", "事件过程"] {
            XCTAssertFalse(text.contains(excluded), excluded)
        }
        let single = try reportText(report.pdfData(appearanceID: report.entries[0].appearance.id, style: .textOnly), portrait: true)
        XCTAssertTrue(single.contains("保送"))
        XCTAssertFalse(single.contains("二垒安打"))
        let file = try report.write(style: .textOnly)
        XCTAssertTrue(file.lastPathComponent.contains("文字简版"))
        XCTAssertNotNil(PDFDocument(url: file))
        try data.write(to: reportSampleDirectory().appendingPathComponent("play-by-play-text-report.pdf"))
    }

    func testTextOnlyPlayByPlayHandlesLongDescriptionsReviewsAndEmptyRecords() throws {
        let store = freshStore()
        for _ in 0..<150 { store.recordPitch(.foul) }
        store.applyPlay(.pending)
        let report = PlayByPlayPDFReport(game: store.game, appearances: store.plateAppearanceRecords(), playedAt: nil)
        let data = report.pdfData(style: .textOnly)
        let document = try XCTUnwrap(PDFDocument(data: data))
        XCTAssertGreaterThan(document.pageCount, 1)
        let text = try reportText(data, portrait: true)
        XCTAssertTrue(text.contains("(续)"))
        XCTAssertTrue(text.contains("待确认"))
        XCTAssertTrue(try XCTUnwrap(document.page(at: document.pageCount - 1)?.string).contains("待确认"))
        try data.write(to: reportSampleDirectory().appendingPathComponent("play-by-play-text-stress.pdf"))
        let empty = PlayByPlayPDFReport(game: store.game, appearances: [], playedAt: nil)
        XCTAssertTrue(try reportText(empty.pdfData(style: .textOnly), portrait: true).contains("暂无可导出的打席文字记录"))
    }

    func testGeneratePlayByPlayAndPosterPresentationSamples() throws {
        let store = freshStore()
        store.recordPitch(.ball); store.recordPitch(.foul); store.applyPlay(.single)
        store.recordPitch(.calledStrike)
        store.recordRunnerEvent(.stolenBase, decisions: store.suggestedRunnerEventDecisions(for: .stolenBase))
        store.applyPlay(.double)
        store.applyPlay(.pending)
        let report = PlayByPlayPDFReport(game: store.game, appearances: store.plateAppearanceRecords(), playedAt: store.activeGameRecordedAt)
        let directory = try reportSampleDirectory()
        try report.pdfData().write(to: directory.appendingPathComponent("play-by-play-report.pdf"))
        let game = try XCTUnwrap(store.activeStoredGame)
        let poster = GamePoster(game: game, venue: "青岛市体育中心 · 棒球场", note: "请队员提前 30 分钟到场热身\n欢迎家长和朋友到场观赛")
        let image = poster.image()
        XCTAssertEqual(image.size.width, 1080)
        XCTAssertEqual(image.size.height, 1440)
        try XCTUnwrap(image.pngData()).write(to: directory.appendingPathComponent("match-notice-poster.png"))
        XCTAssertEqual(try poster.write().pathExtension, "png")
    }

    func testLargeSeasonAggregationAndBackupPerformance() throws {
        let store = freshStore()
        for _ in 0..<50 { store.recordPitch(.foul) }
        store.finishGame()
        let state = store.game
        store.games = (0..<150).map { _ in
            StoredGame(seasonID: store.seasons[0].id, ourTeamID: store.currentTeam!.id, opponentTeamID: store.opponentTeams[0].id,
                       isHome: false, rules: GameRules(), lineup: [], status: .completed, state: state)
        }
        let options = XCTMeasureOptions(); options.iterationCount = 3
        measure(metrics: [XCTClockMetric(), XCTMemoryMetric()], options: options) {
            XCTAssertEqual(store.seasonStatistics(for: store.currentTeam!, seasonID: store.seasons[0].id).games.count, 150)
            do { _ = try LocalBackup.read(store.exportBackup()) }
            catch { XCTFail(error.localizedDescription) }
        }
    }

    private func freshStore() -> GameStore {
        let store = GameStore(persistenceURL: nil)
        store.startNewGame(
            opponent: store.opponentTeams[0],
            isHome: false,
            innings: 6,
            lineup: Array(store.currentTeam!.players.prefix(9))
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

        XCTAssertFalse(store.game.isFinal)
        XCTAssertEqual(store.game.pendingDecision, .regulationHalf)
        store.resolveGameDecision(finish: true)
        XCTAssertTrue(store.game.isFinal)
        XCTAssertTrue(store.game.isTop)
    }

    func testWalkOffRunPromptsBeforeEnding() {
        let store = freshStore()
        store.game.inning = 6
        store.game.isTop = false
        store.game.homeRunsByInning[0] = 1
        store.game.awayRunsByInning[0] = 1
        let runner = store.game.homeTeam.players[1]
        store.game.baseRunners[.third] = runner

        store.applyPlay(.single)

        XCTAssertFalse(store.game.isFinal)
        XCTAssertEqual(store.game.pendingDecision, .walkOff)
        store.resolveGameDecision(finish: true)
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
        XCTAssertEqual(reloadedStore.currentTeam!.id, teamID)
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
        XCTAssertEqual(migratedStore.currentTeam!.id, team.id)
        XCTAssertEqual(migratedStore.currentTeam!.players.first?.numbers, [9, 99])
        XCTAssertEqual(migratedStore.playerGameRecords.first?.batting.hits, 2)
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacyJSONURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: archivedJSONURL.path))

        let reloadedStore = GameStore(persistenceURL: databaseURL)
        XCTAssertEqual(reloadedStore.currentTeam!.id, team.id)
        XCTAssertEqual(reloadedStore.currentTeam!.name, "旧资料球队")
        XCTAssertEqual(reloadedStore.playerGameRecords.first?.opponent, "迁移对手")
    }

    func testPlayerStatisticsAggregateSelectedGames() throws {
        let store = GameStore(persistenceURL: nil)
        let player = store.currentTeam!.players[0]
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
        let assignments = Array(firstStore.currentTeam!.players.prefix(9)).enumerated().map { index, player in
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
        XCTAssertEqual(recovered.state.homeTeam.players.count, firstStore.currentTeam!.players.count)
    }

    func testFinishedGameMovesFromOngoingToRecentAndPersists() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BaseballMasterFinishedGameTests-\(UUID().uuidString)", isDirectory: true)
        let databaseURL = directory.appendingPathComponent("BaseballMaster.sqlite")
        defer { try? FileManager.default.removeItem(at: directory) }

        let store = GameStore(persistenceURL: databaseURL)
        let lineup = Array(store.currentTeam!.players.prefix(9))
        store.startNewGame(opponent: store.opponentTeams[0], isHome: false, innings: 6, lineup: lineup)
        store.finishGame()

        let reloaded = GameStore(persistenceURL: databaseURL)
        XCTAssertTrue(reloaded.ongoingGames.isEmpty)
        XCTAssertEqual(reloaded.recentGames.count, 1)
        XCTAssertTrue(reloaded.recentGames[0].state.isFinal)
    }

    func testPreviousLineupAndBenchRemainAvailable() throws {
        let store = GameStore(persistenceURL: nil)
        let starters = Array(store.currentTeam!.players.prefix(9))
        let assignments = starters.enumerated().map { index, player in
            LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
        }
        store.startNewGame(
            opponent: store.opponentTeams[0],
            isHome: false,
            rules: GameRules(),
            lineup: assignments
        )

        XCTAssertEqual(store.previousLineup(for: store.currentTeam!.id), assignments)
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
        let assignments = Array(store.currentTeam!.players.prefix(9)).enumerated().map { index, player in
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
        XCTAssertTrue(reloaded.startScheduledGame(id: try XCTUnwrap(gameID)))
        let appearanceID = try XCTUnwrap(reloaded.game.currentPlateAppearanceID)
        let restarted = GameStore(persistenceURL: databaseURL)
        XCTAssertEqual(restarted.game.currentPlateAppearanceID, appearanceID)
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
            ourTeam: store.currentTeam!,
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
            ourTeam: store.currentTeam!,
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
        let lineup = Array(store.currentTeam!.players.prefix(9)).enumerated().map { index, player in
            LineupAssignment(playerID: player.id, battingOrder: index + 1, position: FieldPosition.allCases[index])
        }
        let rules = GameRules(scheduledInnings: 7, pitchLimit: 75, pitcherInningsLimit: 2)

        XCTAssertTrue(store.startScheduledGame(id: gameID, rules: rules, lineup: lineup))
        let started = try XCTUnwrap(store.ongoingGames.first(where: { $0.id == gameID }))
        XCTAssertEqual(started.rules, rules)
        XCTAssertEqual(started.lineup, lineup)
        XCTAssertEqual(started.state.awayTeam.players.count, store.currentTeam!.players.count)
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
        let lineup = Array(store.currentTeam!.players.prefix(9))
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

    func testPinchHitterTakesLineupSpotAndRemovedPlayerCanReturn() throws {
        let store = freshStore()
        let removed = store.currentBatter
        let replacement = try XCTUnwrap(store.battingBenchPlayers.first)

        store.replaceCurrentBatter(with: replacement)

        XCTAssertEqual(store.currentBatter.id, replacement.id)
        XCTAssertTrue(store.exitedPlayerIDs(forHomeTeam: false).contains(removed.id))
        XCTAssertTrue(store.battingBenchPlayers.contains(where: { $0.id == removed.id }))
        store.replaceCurrentBatter(with: removed)
        XCTAssertEqual(store.currentBatter.id, removed.id)
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
        let lineup = Array(store.currentTeam!.players.prefix(9)).enumerated().map { index, player in
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
        let lineup = Array(first.currentTeam!.players.prefix(9)).enumerated().map { index, player in
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
        XCTAssertTrue(migrated.opponentTeams.isEmpty, "升级不得新增示例对手")
        XCTAssertTrue(migrated.games.isEmpty)
        for opponent in migrated.opponentTeams { XCTAssertTrue(migrated.deleteOpponentTeam(id: opponent.id)) }
        let reloaded = GameStore(persistenceURL: databaseURL)
        XCTAssertFalse(reloaded.requiresDataRecovery)
        XCTAssertTrue(reloaded.opponentTeams.isEmpty, "Migration defaults must only be seeded once")
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
        let lineup = Array(store.currentTeam!.players.prefix(9)).enumerated().map { index, player in
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
        XCTAssertEqual(appearances.count, 2)
        XCTAssertTrue(appearances.last!.events.isEmpty)
        XCTAssertFalse(appearances.last!.isComplete)
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
        XCTAssertEqual(appearances.count, 3)
        XCTAssertTrue(appearances.last!.events.isEmpty)
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
            XCTAssertTrue(document.page(at: pageIndex)?.string?.contains("BOX SCORE") == true)
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

    private func reportText(_ data: Data, portrait: Bool = false) throws -> String {
        let document = try XCTUnwrap(PDFDocument(data: data))
        XCTAssertGreaterThan(document.pageCount, 0)
        for index in 0..<document.pageCount {
            let page = try XCTUnwrap(document.page(at: index))
            XCTAssertEqual(page.bounds(for: .mediaBox).width, portrait ? 595 : 842, accuracy: 0.1)
            XCTAssertEqual(page.bounds(for: .mediaBox).height, portrait ? 842 : 595, accuracy: 0.1)
            XCTAssertTrue(page.string?.contains("第 \(index + 1) 页") == true)
        }
        return (0..<document.pageCount).compactMap { document.page(at: $0)?.string }.joined(separator: "\n")
    }

    func testTeamSeasonPDFContainsOnlySelectedTeamSeasonAndAllCategories() throws {
        let store = GameStore(persistenceURL: nil)
        let player = store.currentTeam!.players[0]
        var included = statisticsGame(store: store)
        included.state.batting[player.id] = BattingLine(plateAppearances: 4, atBats: 3, hits: 2, doubles: 1, walks: 1)
        included.state.pitching[player.id] = PitchingLine(outsRecorded: 4, strikeouts: 3)
        included.state.fielding[player.id] = FieldingLine(putouts: 1, assists: 2)
        var excluded = statisticsGame(store: store, seasonID: "excluded-season")
        excluded.state.awayTeam.name = "不应导出的其他赛季对手"
        store.games = [included, excluded]
        let report = TeamSeasonPDFReport(store: store, team: store.currentTeam!, seasonID: store.seasons[0].id)
        let text = try reportText(report.pdfData())
        XCTAssertTrue(text.contains("SEASON REPORT"))
        XCTAssertTrue(text.contains("BATTING"))
        XCTAssertTrue(text.contains("PITCHING"))
        XCTAssertTrue(text.contains("FIELDING"))
        XCTAssertTrue(text.contains("GAME LOG"))
        XCTAssertTrue(text.contains(player.name))
        XCTAssertTrue(text.contains(".667"))
        XCTAssertFalse(text.contains("不应导出"))
        XCTAssertTrue(text.contains("ERA 按 7 局"))
        XCTAssertEqual(report.summary.games.count, 1)
    }

    func testPlayerPDFHonorsSelectedGamesAndPreservesUnknownLegacyStatistics() throws {
        let store = GameStore(persistenceURL: nil)
        let player = store.currentTeam!.players[0]
        let seasonID = store.seasons[0].id
        var included = statisticsGame(store: store)
        included.state.batting[player.id] = BattingLine(plateAppearances: 4, atBats: 4, hits: 3)
        included.state.pitching[player.id] = PitchingLine(outsRecorded: 5, strikeouts: 3)
        var excluded = statisticsGame(store: store)
        excluded.state.awayTeam.name = "未选中的对手不应导出"
        store.games = [included, excluded]
        let legacy = PlayerGameRecord(playerID: player.id, seasonID: seasonID, date: Date(), opponent: "旧版对手", result: "胜", batting: BattingLine(atBats: 2, hits: 1))
        store.playerGameRecords = [legacy]
        let report = PlayerStatisticsPDFReport(store: store, player: player, seasonID: seasonID,
                                               teamID: store.currentTeam!.id, gameIDs: [included.id, legacy.id])
        let text = try reportText(report.pdfData())
        XCTAssertTrue(text.contains("已选 2 / 3"))
        XCTAssertTrue(text.contains("旧版个人打击记录"))
        XCTAssertTrue(text.contains("旧版对手"))
        XCTAssertFalse(text.contains("未选中的对手"))
        XCTAssertEqual(report.summary.batting.hits, 4)
        XCTAssertEqual(report.summary.pitching.outsRecorded, 5)
        let empty = PlayerStatisticsPDFReport(store: store, player: player, seasonID: seasonID, teamID: nil, gameIDs: [])
        let emptyText = try reportText(empty.pdfData())
        XCTAssertTrue(emptyText.contains("已选 0 / 3"))
        XCTAssertTrue(emptyText.contains("暂无符合当前范围的记录"))
        XCTAssertFalse(emptyText.localizedCaseInsensitiveContains("nan"))
    }

    func testReportPaginationRepeatsHeadersAndPreservesLongChineseNames() throws {
        let store = GameStore(persistenceURL: nil)
        for index in 0..<65 {
            _ = store.addPlayer(to: store.currentTeam!.id, chineseName: "长名单球员第\(index)位测试姓名末尾", englishName: "Long Name \(index)", numbers: [index + 100])
        }
        var game = statisticsGame(store: store)
        for player in store.currentTeam!.players {
            game.state.batting[player.id] = BattingLine(plateAppearances: 2, atBats: 2, hits: 1)
        }
        store.games = [game]
        let data = TeamSeasonPDFReport(store: store, team: store.currentTeam!, seasonID: store.seasons[0].id).pdfData()
        let document = try XCTUnwrap(PDFDocument(data: data))
        let text = try reportText(data)
        let compact = text.filter { !$0.isWhitespace }
        XCTAssertGreaterThan(document.pageCount, 4)
        XCTAssertTrue(compact.contains("长名单球员第64位测试姓名末尾"))
        XCTAssertTrue(text.contains("(续)"))
        XCTAssertGreaterThan(text.components(separatedBy: "OPS").count, 3)
        let totalPage = (0..<document.pageCount).compactMap { document.page(at: $0)?.string }.first { $0.contains("合计") }
        XCTAssertTrue(totalPage?.contains("宋知远") == true, "合计行应与最后一名球员保持在同一页")
        let directory = try reportSampleDirectory()
        try data.write(to: directory.appendingPathComponent("pagination-stress.pdf"))
    }

    func testBoxScorePDFSplitsLongExtraInningsWithoutShrinkingColumns() throws {
        let store = freshStore()
        store.game.inning = 30
        store.game.isTop = false
        store.game.awayRunsByInning = Array(repeating: 0, count: 30)
        store.game.homeRunsByInning = Array(repeating: 0, count: 30)
        store.game.awayRunsByInning[29] = 2
        store.finishGame()
        let data = GameBoxScorePDFReport(game: store.game, rules: store.activeRules, playedAt: store.activeGameRecordedAt).pdfData()
        let text = try reportText(data)
        XCTAssertTrue(text.contains("1-12"))
        XCTAssertTrue(text.contains("13-24"))
        XCTAssertTrue(text.contains("25-30"))
        XCTAssertTrue(text.contains("BOX SCORE"))
        try data.write(to: reportSampleDirectory().appendingPathComponent("extra-innings-stress.pdf"))
    }

    func testReportFilesSanitizeNamesAndNeverOverwritePreviousExports() throws {
        let first = try ReportExportFile.write(Data("first".utf8), name: "球队/测试:球员\n报告")
        let second = try ReportExportFile.write(Data("second".utf8), name: "球队/测试:球员\n报告")
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(first.lastPathComponent, second.lastPathComponent)
        XCTAssertFalse(first.lastPathComponent.contains(":"))
        XCTAssertFalse(first.lastPathComponent.contains("\n"))
        XCTAssertEqual(try Data(contentsOf: first), Data("first".utf8))
        XCTAssertEqual(try Data(contentsOf: second), Data("second".utf8))
        let long = try ReportExportFile.write(Data("long".utf8), name: String(repeating: "中文球队⚾️", count: 50))
        XCTAssertLessThanOrEqual(long.lastPathComponent.utf8.count, 184)
        XCTAssertEqual(try Data(contentsOf: long), Data("long".utf8))
    }

    private func reportSampleDirectory() throws -> URL {
        let documents = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first)
        let directory = documents.appendingPathComponent("PDFValidation", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    func testGeneratePresentationPDFSamplesFromRecordedGames() throws {
        let store = GameStore(persistenceURL: nil)
        store.updateTeam(id: store.currentTeam!.id, name: "青岛海浪（示例数据）", shortName: "海浪", city: "青岛")
        let lineup = Array(store.currentTeam!.players.prefix(9))
        store.startNewGame(opponent: store.opponentTeams[0], isHome: false, innings: 6, lineup: lineup)
        store.recordPitch(.ball)
        store.applyPlay(.single)
        store.applyPlay(.homeRun)
        for _ in 0..<3 { store.applyPlay(.groundOut, defensivePlay: DefensivePlay.quickPlays[0]) }
        store.applyPlay(.single)
        for _ in 0..<3 { store.recordPitch(.swingingStrike) }
        for _ in 0..<2 { store.applyPlay(.groundOut, defensivePlay: DefensivePlay.quickPlays[0]) }
        store.finishGame()
        let directory = try reportSampleDirectory()
        let teamData = TeamSeasonPDFReport(store: store, team: store.currentTeam!, seasonID: store.seasons[0].id).pdfData()
        let personalData = PlayerStatisticsPDFReport(store: store, player: lineup[0], seasonID: store.seasons[0].id,
                                                     teamID: store.currentTeam!.id, gameIDs: Set(store.games.map(\.id))).pdfData()
        let boxData = GameExportService(game: store.game, rules: store.activeRules, plateAppearances: store.plateAppearanceRecords(),
                                        gameEvents: store.nonPlateAppearanceGameEvents(), playedAt: store.activeGameRecordedAt).boxScorePDFData()
        for (name, data) in [("team-season-report.pdf", teamData), ("player-report.pdf", personalData), ("game-box-score.pdf", boxData)] {
            XCTAssertTrue(try reportText(data).contains("青岛海浪"))
            try data.write(to: directory.appendingPathComponent(name))
        }
    }

    private func statisticsGame(
        store: GameStore, team: Team? = nil, seasonID: String? = nil,
        isHome: Bool = true, status: StoredGameStatus = .completed,
        observation: Bool = false, runs: Int = 3, allowed: Int = 1
    ) -> StoredGame {
        let ours = team ?? store.currentTeam!
        let opponent = store.opponentTeams[0]
        var state = GameState(homeTeam: isHome ? ours : opponent, awayTeam: isHome ? opponent : ours)
        state.isFinal = status == .completed
        state.homeRunsByInning[0] = isHome ? runs : allowed
        state.awayRunsByInning[0] = isHome ? allowed : runs
        return StoredGame(
            seasonID: seasonID ?? store.seasons[0].id, ourTeamID: ours.id,
            opponentTeamID: opponent.id, isHome: isHome, isSpectator: observation,
            rules: GameRules(), lineup: [], status: status, state: state
        )
    }

    func testStatisticsExcludeOtherTeamsSeasonsObservationAndUnfinishedGames() {
        let store = GameStore(persistenceURL: nil)
        let other = Team(name: "其他球队", shortName: "其他", city: "", players: [])
        store.games = [
            statisticsGame(store: store, runs: 4, allowed: 1),
            statisticsGame(store: store, isHome: false, runs: 2, allowed: 3),
            statisticsGame(store: store, runs: 1, allowed: 1),
            statisticsGame(store: store, team: other),
            statisticsGame(store: store, seasonID: store.seasons[1].id),
            statisticsGame(store: store, status: .ongoing),
            statisticsGame(store: store, status: .scheduled),
            statisticsGame(store: store, observation: true)
        ]
        let summary = store.seasonStatistics(for: store.currentTeam!, seasonID: store.seasons[0].id)
        XCTAssertEqual(summary.games.count, 3)
        XCTAssertEqual(summary.wins, 1)
        XCTAssertEqual(summary.losses, 1)
        XCTAssertEqual(summary.ties, 1)
        XCTAssertEqual(summary.runs, 7)
        XCTAssertEqual(summary.runsAllowed, 5)
    }

    func testStatisticsAggregateRawTotalsBeforeCalculatingRatesAndInnings() throws {
        let store = GameStore(persistenceURL: nil)
        let player = store.currentTeam!.players[0]
        var first = statisticsGame(store: store)
        var second = statisticsGame(store: store, isHome: false)
        first.state.batting[player.id] = BattingLine(plateAppearances: 2, atBats: 1, hits: 1, walks: 1)
        second.state.batting[player.id] = BattingLine(plateAppearances: 3, atBats: 3, hits: 1, doubles: 1)
        first.state.pitching[player.id] = PitchingLine(outsRecorded: 2, hits: 1, earnedRuns: 1, walks: 1, pitches: 20)
        second.state.pitching[player.id] = PitchingLine(outsRecorded: 2, hits: 1, earnedRuns: 1, pitches: 12)
        first.state.fielding[player.id] = FieldingLine(putouts: 1, assists: 1, errors: 1)
        second.state.fielding[player.id] = FieldingLine(putouts: 3, assists: 3)
        // Opponent contributions must never leak into team totals.
        first.state.batting[store.opponentTeams[0].players[0].id] = BattingLine(atBats: 20, hits: 20)
        store.games = [first, second]
        let summary = store.seasonStatistics(for: store.currentTeam!, seasonID: store.seasons[0].id)
        XCTAssertEqual(summary.batting.average, 0.5, accuracy: 0.0001)
        XCTAssertEqual(summary.batting.onBasePercentage, 0.6, accuracy: 0.0001)
        XCTAssertEqual(summary.batting.slugging, 0.75, accuracy: 0.0001)
        XCTAssertEqual(summary.batting.ops, 1.35, accuracy: 0.0001)
        XCTAssertEqual(summary.pitching.inningsText, "1.1")
        XCTAssertEqual(summary.pitching.era, 10.5, accuracy: 0.0001)
        XCTAssertEqual(summary.pitching.whip, 2.25, accuracy: 0.0001)
        XCTAssertEqual(summary.pitching.pitches, 32)
        XCTAssertEqual(try XCTUnwrap(summary.fielding.percentage), 8.0 / 9.0, accuracy: 0.0001)
    }

    func testStatisticsKeepDeletedAndSubstitutedPlayersWithoutCountingUnusedBench() throws {
        let store = GameStore(persistenceURL: nil)
        let removed = store.currentTeam!.players[0]
        let benchID = try XCTUnwrap(store.addPlayer(to: store.currentTeam!.id, chineseName: "未上场替补", englishName: "", numbers: [99]))
        var stored = statisticsGame(store: store)
        stored.state.homeBattingOrderIDs.removeAll { $0 == benchID || $0 == removed.id }
        stored.state.homeExitedPlayerIDs = [removed.id]
        stored.state.batting[removed.id] = BattingLine(plateAppearances: 1, atBats: 1, hits: 1)
        store.games = [stored]
        store.deletePlayer(from: store.currentTeam!.id, playerID: removed.id)
        let summary = store.seasonStatistics(for: store.currentTeam!, seasonID: store.seasons[0].id)
        let historical = try XCTUnwrap(summary.players.first { $0.id == removed.id })
        XCTAssertFalse(historical.isCurrentRoster)
        XCTAssertEqual(historical.gamesPlayed, 1)
        XCTAssertEqual(historical.batting.hits, 1)
        XCTAssertEqual(summary.players.first { $0.id == benchID }?.gamesPlayed, 0)
        XCTAssertEqual(summary.batting.hits, 1)
    }

    func testStatisticsFiltersAndSortsUndefinedRatesLastInEitherDirection() {
        let ace = Player(chineseName: "投手甲", englishName: "Ace", numbers: [7, 17])
        let rookie = Player(chineseName: "投手乙", englishName: "Rookie", numbers: [8])
        let idle = Player(chineseName: "替补", englishName: "Bench", numbers: [9])
        let rows = [
            PlayerSeasonStatistics(player: rookie, pitching: PitchingLine(earnedRuns: 2, pitches: 10)),
            PlayerSeasonStatistics(player: ace, pitching: PitchingLine(outsRecorded: 3, strikeouts: 2, pitches: 12)),
            PlayerSeasonStatistics(player: idle)
        ]
        let summary = TeamSeasonStatistics(games: [], players: rows)
        for ascending in [true, false] {
            let ordered = summary.filteredPlayers(category: .pitching, metric: .era, ascending: ascending)
            XCTAssertEqual(ordered.map(\.id), [ace.id, rookie.id])
        }
        XCTAssertEqual(summary.filteredPlayers(category: .pitching, metric: .pitches, ascending: false).map(\.id), [ace.id, rookie.id])
        XCTAssertEqual(summary.filteredPlayers(category: .pitching, metric: .pitches, ascending: true).map(\.id), [rookie.id, ace.id])
        XCTAssertEqual(summary.filteredPlayers(category: .pitching, metric: .era, ascending: true, query: "ace").map(\.id), [ace.id])
        XCTAssertEqual(summary.filteredPlayers(category: .pitching, metric: .era, ascending: true, query: "17").map(\.id), [ace.id])
        XCTAssertEqual(summary.filteredPlayers(category: .batting, metric: .hits, ascending: false).count, 0)
        XCTAssertEqual(summary.filteredPlayers(category: .batting, metric: .hits, ascending: false, recordsOnly: false).count, 3)
        XCTAssertEqual(StatisticsMetric.era.formattedValue(for: rows[0]), "—")
        XCTAssertNil(rows[2].fielding.percentage)
    }

    func testStatisticsPlayerDetailsPreserveSelectedGamesAndLegacyBatting() {
        let store = GameStore(persistenceURL: nil)
        let player = store.currentTeam!.players[0]
        let seasonID = store.seasons[0].id
        var first = statisticsGame(store: store)
        var second = statisticsGame(store: store)
        first.state.batting[player.id] = BattingLine(atBats: 2, hits: 1)
        first.state.pitching[player.id] = PitchingLine(outsRecorded: 3, strikeouts: 2)
        first.state.fielding[player.id] = FieldingLine(putouts: 1)
        second.state.batting[player.id] = BattingLine(atBats: 3, hits: 2)
        second.state.pitching[player.id] = PitchingLine(outsRecorded: 6, strikeouts: 3)
        store.games = [first, second]
        let legacy = PlayerGameRecord(playerID: player.id, seasonID: seasonID, date: Date(), opponent: "旧对手", result: "胜", batting: BattingLine(atBats: 4, hits: 1))
        store.playerGameRecords = [legacy]
        let records = store.gameRecords(for: player, seasonID: seasonID)
        XCTAssertEqual(records.count, 3)
        let selected = store.playerStatistics(for: player, seasonID: seasonID, gameIDs: [first.id])
        XCTAssertEqual(selected.batting.hits, 1)
        XCTAssertEqual(selected.pitching.strikeouts, 2)
        XCTAssertEqual(selected.fielding.putouts, 1)
        XCTAssertEqual(store.playerStatistics(for: player, seasonID: seasonID, gameIDs: []).batting.hits, 0)
        XCTAssertEqual(store.seasonStatistics(for: store.currentTeam!, seasonID: seasonID).batting.hits, 3)
        // Opening an already-counted completed game must not add it again.
        store.openGame(id: first.id)
        XCTAssertEqual(store.seasonBattingLine(for: player).hits, 4)
    }

    func testStatisticsFinishingAndReloadingGameUpdatesOverviewAndPlayerDetails() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("statistics.sqlite")
        let store = GameStore(persistenceURL: url)
        let player = store.currentTeam!.players[0]
        let seasonID = store.seasons[0].id
        store.startNewGame(opponent: store.opponentTeams[0], isHome: false, innings: 6, lineup: Array(store.currentTeam!.players.prefix(9)))
        store.applyPlay(.homeRun)
        XCTAssertTrue(store.gameRecords(for: player, seasonID: seasonID).isEmpty)
        store.finishGame()
        let reloaded = GameStore(persistenceURL: url)
        let summary = reloaded.seasonStatistics(for: reloaded.currentTeam!, seasonID: seasonID)
        XCTAssertEqual(summary.games.count, 1)
        XCTAssertEqual(summary.wins, 1)
        XCTAssertEqual(summary.runs, 1)
        XCTAssertEqual(summary.batting.homeRuns, 1)
        XCTAssertEqual(reloaded.gameRecords(for: player, seasonID: seasonID).first?.batting.homeRuns, 1)
        XCTAssertEqual(reloaded.seasonBattingLine(for: player).homeRuns, 1)
        XCTAssertEqual(reloaded.recordedPlayerGameCount, 9)
        XCTAssertTrue(reloaded.deleteGame(id: summary.games[0].id))
        let afterDeletion = GameStore(persistenceURL: url)
        XCTAssertTrue(afterDeletion.gameRecords(for: player, seasonID: seasonID).isEmpty)
        XCTAssertEqual(afterDeletion.recordedPlayerGameCount, 0)
        XCTAssertEqual(afterDeletion.seasonStatistics(for: afterDeletion.currentTeam!, seasonID: seasonID).batting.homeRuns, 0)
    }

    func testStatisticsPostGameReviewPersistsAndRefreshesTotals() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("review.sqlite")
        let store = GameStore(persistenceURL: url)
        let player = store.currentTeam!.players[0]
        let seasonID = store.seasons[0].id
        store.startNewGame(opponent: store.opponentTeams[0], isHome: false, innings: 6, lineup: Array(store.currentTeam!.players.prefix(9)))
        store.applyPlay(.pending)
        store.finishGame()
        let gameID = try XCTUnwrap(store.games.first?.id)
        let reloaded = GameStore(persistenceURL: url)
        reloaded.openGame(id: gameID)
        let event = try XCTUnwrap(reloaded.game.scoringEvents?.first(where: \.needsReview))
        XCTAssertEqual(reloaded.seasonStatistics(for: reloaded.currentTeam!, seasonID: seasonID).pendingCount, 1)
        XCTAssertTrue(reloaded.reviewPendingEvent(
            id: event.id, title: "复核为一垒安打", category: event.category,
            notation: "1B", primaryPlayerID: event.primaryPlayerID, secondaryPlayerID: nil,
            ballStatus: event.ballStatus, resolvedOutcome: .single, note: "录像确认"
        ))
        let finalStore = GameStore(persistenceURL: url)
        let summary = finalStore.seasonStatistics(for: finalStore.currentTeam!, seasonID: seasonID)
        XCTAssertEqual(summary.pendingCount, 0)
        XCTAssertEqual(summary.batting.hits, 1)
        XCTAssertEqual(finalStore.gameRecords(for: player, seasonID: seasonID).first?.batting.hits, 1)
    }

    func testStatisticsExposeUnknownHistoricalSeasonsAndEmptyStates() {
        let store = GameStore(persistenceURL: nil)
        store.games = [statisticsGame(store: store, seasonID: "2024-local")]
        XCTAssertEqual(store.statisticsSeasons.last?.id, "2024-local")
        let summary = store.seasonStatistics(for: store.currentTeam!, seasonID: store.seasons[0].id)
        XCTAssertEqual(summary.games.count, 0)
        XCTAssertEqual(summary.wins, 0)
        XCTAssertEqual(summary.batting, BattingLine())
        XCTAssertEqual(summary.pitching, PitchingLine())
        XCTAssertEqual(summary.fielding, FieldingLine())
        let emptyTeam = Team(name: "空队", shortName: "空队", city: "", players: [])
        XCTAssertTrue(store.seasonStatistics(for: emptyTeam, seasonID: store.seasons[0].id).players.isEmpty)
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

extension GameStoreTests {
    private func v11Store(mode: GameMode = .standard, cap: Int? = nil, pitches: Int = 6, url: URL? = nil) -> GameStore {
        let store = GameStore(persistenceURL: url)
        var rules = GameRules()
        rules.mode = mode; rules.halfInningRunLimit = cap; rules.coachPitchLimit = pitches
        let lineup = store.currentTeam!.players.prefix(9).enumerated().map {
            LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: $0.element.primaryPosition)
        }
        store.startNewGame(opponent: store.opponentTeams[0], isHome: false, rules: rules, lineup: lineup)
        return store
    }

    func testV11EmptyRosterAnd00SurviveBackupAndRestart() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let url = root.appendingPathComponent("BaseballMaster.sqlite")
        let store = GameStore(persistenceURL: url)
        let team = try XCTUnwrap(store.currentTeam)
        let id = try XCTUnwrap(store.addPlayer(to: team.id, chineseName: "", englishName: "", numbers: [0, 0], numberTexts: ["00", "0"]))
        XCTAssertEqual(store.player(withID: id, in: team.id)?.name, "#00")
        let reopened = GameStore(persistenceURL: url)
        XCTAssertEqual(reopened.player(withID: id, in: team.id)?.numberTexts, ["00", "0"])
        XCTAssertTrue(reopened.deleteTeam(id: team.id))
        XCTAssertNil(reopened.currentTeam)
        let empty = GameStore(persistenceURL: url)
        XCTAssertFalse(empty.requiresDataRecovery)
        XCTAssertTrue(empty.teams.isEmpty)
        XCTAssertNil(try LocalBackup.read(empty.exportBackup()).snapshot().currentTeamID)
        XCTAssertTrue(empty.statisticsTeams.contains { $0.id == team.id })
    }

    func testV11BothUnfinishedTeamsAreProtectedAndCompletedHistorySurvivesDeletion() throws {
        let store = v11Store()
        let team = try XCTUnwrap(store.currentTeam)
        let opponent = store.opponentTeams[0]
        XCTAssertFalse(store.deleteTeam(id: team.id))
        XCTAssertFalse(store.deleteOpponentTeam(id: opponent.id))
        store.applyPlay(.single)
        store.finishGame()
        let games = store.games
        XCTAssertTrue(store.deleteTeam(id: team.id))
        XCTAssertTrue(store.deleteOpponentTeam(id: opponent.id))
        XCTAssertEqual(store.games, games)
        XCTAssertEqual(store.seasonStatistics(for: team, seasonID: store.seasons[0].id).batting.hits, 1)
    }

    func testV11StableAppearanceBeforePitchAcrossUndoRedoAndRestart() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let url = root.appendingPathComponent("BaseballMaster.sqlite")
        let store = v11Store(url: url)
        let first = try XCTUnwrap(store.game.currentPlateAppearanceID)
        XCTAssertEqual(store.plateAppearanceRecords().first?.id, first)
        store.recordPitch(.ball)
        XCTAssertEqual(store.game.scoringEvents?.last?.plateAppearanceID, first)
        store.applyPlay(.single)
        let next = try XCTUnwrap(store.game.currentPlateAppearanceID)
        XCTAssertNotEqual(first, next)
        let revision = try XCTUnwrap(store.activeStoredGame?.revision)
        store.undo()
        XCTAssertEqual(store.game.currentPlateAppearanceID, first)
        store.redo()
        XCTAssertEqual(store.game.currentPlateAppearanceID, next)
        XCTAssertGreaterThan(store.activeStoredGame?.revision ?? 0, revision)
        let reopened = GameStore(persistenceURL: url)
        XCTAssertEqual(reopened.game.currentPlateAppearanceID, next)
        XCTAssertEqual(reopened.game.plateAppearances, store.game.plateAppearances)
    }

    func testV11UnfinishedAppearanceHasNewLinkedSegmentAfterManualHalf() throws {
        let store = v11Store()
        store.recordPitch(.calledStrike)
        let batter = store.currentBatter.id
        let first = try XCTUnwrap(store.game.currentPlateAppearanceID)
        store.endCurrentHalf()
        XCTAssertFalse(store.game.isTop)
        XCTAssertEqual(store.battingLine(for: store.game.awayTeam.players[0]).plateAppearances, 0)
        store.endCurrentHalf()
        XCTAssertTrue(store.game.isTop)
        XCTAssertEqual(store.currentBatter.id, batter)
        XCTAssertEqual(store.game.strikes, 0)
        XCTAssertEqual(store.game.plateAppearances?.last?.previousSegmentID, first)
        XCTAssertFalse(store.game.plateAppearances?.first?.completed ?? true)
    }

    func testV11RunCapPreservesHomeRunAndCapsRunsRBIAndPitcherResponsibility() throws {
        let store = v11Store(cap: 6)
        store.game.awayRunsByInning[0] = 5
        let batter = store.currentBatter
        let lead = store.game.awayTeam.players[1]
        let other = store.game.awayTeam.players[2]
        store.game.baseRunners = [.third: lead, .first: other]
        store.applyPlay(.homeRun)
        XCTAssertEqual(store.game.awayScore, 6)
        XCTAssertEqual(store.battingLine(for: batter).homeRuns, 1)
        XCTAssertEqual(store.battingLine(for: batter).runsBattedIn, 1)
        XCTAssertEqual(store.battingLine(for: lead).runs, 1)
        XCTAssertEqual(store.battingLine(for: other).runs, 0)
        XCTAssertEqual(store.battingLine(for: batter).runs, 0)
        XCTAssertEqual(store.pitchingLine(for: store.currentPitcher).runs, 1)
        XCTAssertEqual(store.game.pendingDecision, .runLimit)
        store.resolveGameDecision(finish: false)
        store.applyPlay(.homeRun)
        XCTAssertEqual(store.game.awayScore, 6)
        XCTAssertNil(store.game.pendingDecision)
        store.endCurrentHalf()
        XCTAssertFalse(store.game.isTop)
        XCTAssertEqual(store.game.outs, 0)
    }

    func testV11CoachBoundaryFoulHBPAndFairBallNeverRecordPlayerPitching() {
        let store = v11Store(mode: .coachPitch, pitches: 6)
        let batter = store.currentBatter
        for _ in 0..<5 { store.recordPitch(.foul) }
        XCTAssertEqual(store.currentBatter.id, batter.id)
        store.recordHitByPitch()
        XCTAssertEqual(store.battingLine(for: batter).strikeouts, 0)
        XCTAssertEqual(store.battingLine(for: batter).plateAppearances, 1)
        XCTAssertEqual(store.game.outs, 1)
        XCTAssertEqual(store.game.scoringEvents?.last?.notation, "PITCH-LIMIT")
        for _ in 0..<5 { store.recordPitch(.ball) }
        let fairBatter = store.currentBatter
        store.applyPlay(.single)
        XCTAssertEqual(store.battingLine(for: fairBatter).hits, 1)
        XCTAssertEqual(store.battingLine(for: fairBatter).walks, 0)
        XCTAssertEqual(store.game.outs, 1)
        let next = store.currentBatter
        for _ in 0..<3 { store.recordPitch(.swingingStrike) }
        XCTAssertEqual(store.battingLine(for: next).strikeouts, 1)
        XCTAssertTrue(store.game.pitching.isEmpty)
        store.undo(); store.redo()
        XCTAssertTrue(store.game.pitching.isEmpty)
    }

    func testV11CoachForbiddenRunnerEventsAndModeStatistics() throws {
        let store = v11Store(mode: .coachPitch)
        store.applyPlay(.single)
        let state = store.game
        XCTAssertFalse(store.recordRunnerEvent(.stolenBase, decisions: store.suggestedRunnerEventDecisions(for: .stolenBase)))
        XCTAssertFalse(store.recordRunnerEvent(.wildPitch, decisions: store.suggestedRunnerEventDecisions(for: .wildPitch)))
        XCTAssertEqual(store.game, state)
        store.finishGame()
        let team = try XCTUnwrap(store.currentTeam)
        XCTAssertEqual(store.seasonStatistics(for: team, seasonID: store.seasons[0].id, mode: .coachPitch).games.count, 1)
        XCTAssertTrue(store.seasonStatistics(for: team, seasonID: store.seasons[0].id, mode: .standard).games.isEmpty)
        let text = try reportText(GameBoxScorePDFReport(game: store.game, rules: store.activeRules, playedAt: nil).pdfData())
        XCTAssertTrue(text.contains("不适用"))
    }

    func testV11ReopenKeepsIdentityClockAndCountsSeasonOnlyOnce() throws {
        let store = v11Store()
        store.applyPlay(.single)
        let gameID = store.activeStoredGame?.id
        let identities = store.game.plateAppearances
        store.finishGame()
        let team = try XCTUnwrap(store.currentTeam)
        let season = store.seasons[0].id
        XCTAssertEqual(store.seasonStatistics(for: team, seasonID: season).games.count, 1)
        XCTAssertTrue(store.reopenGame())
        XCTAssertFalse(store.isGameClockRunning)
        XCTAssertEqual(store.activeStoredGame?.id, gameID)
        XCTAssertEqual(store.game.plateAppearances, identities)
        XCTAssertEqual(store.seasonStatistics(for: team, seasonID: season).games.count, 0)
        store.finishGame()
        XCTAssertEqual(store.seasonStatistics(for: team, seasonID: season).games.count, 1)
    }
}

extension GameStoreTests {
    private func legacyV2Fixture(in root: URL) throws -> (URL, RosterSnapshot) {
        let seed = freshStore()
        seed.recordPitch(.ball); seed.applyPlay(.double); seed.finishGame()
        let snapshot = try LocalBackup.read(seed.exportBackup()).snapshot()
        let excluded: Set<String> = ["isDemo", "archivedTeams", "plateAppearances", "currentPlateAppearanceID", "plateAppearanceID", "effectiveScorerIDs", "plateAppearancePitchCount", "revision", "canResumePrecisely"]
        func oldJSON(_ value: Any) -> Any {
            if let dict = value as? [String: Any] { return dict.filter { !excluded.contains($0.key) || ($0.key == "plateAppearances" && $0.value is NSNumber) }.mapValues(oldJSON) }
            if let array = value as? [Any] { return array.map(oldJSON) }
            return value
        }
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(snapshot))
        let original = try JSONDecoder().decode(RosterSnapshot.self, from: JSONSerialization.data(withJSONObject: oldJSON(json)))
        let temporary = root.appendingPathComponent("fixture-source/BaseballMaster.sqlite")
        let source = try CoreDataRosterStore(storeURL: temporary, protectsUpgrade: false)
        try source.replaceAll(with: original); try source.close()
        let v3 = CoreDataRosterStore.makeManagedObjectModel()
        let v2 = CoreDataRosterStore.makeManagedObjectModel(version: 2)
        let url = root.appendingPathComponent("BaseballMaster.sqlite")
        let mapping = try NSMappingModel.inferredMappingModel(forSourceModel: v3, destinationModel: v2)
        try NSMigrationManager(sourceModel: v3, destinationModel: v2).migrateStore(from: temporary, sourceType: NSSQLiteStoreType,
            options: nil, with: mapping, toDestinationURL: url, destinationType: NSSQLiteStoreType, destinationOptions: nil)
        let coordinator = NSPersistentStoreCoordinator(managedObjectModel: v2)
        let store = try coordinator.addPersistentStore(ofType: NSSQLiteStoreType, configurationName: nil, at: url)
        let context = NSManagedObjectContext(concurrencyType: .mainQueueConcurrencyType)
        context.persistentStoreCoordinator = coordinator
        let metadata = try context.fetch(NSFetchRequest<NSManagedObject>(entityName: "RosterMetadata"))[0]
        metadata.setValue(2, forKey: "schemaVersion")
        for player in try context.fetch(NSFetchRequest<NSManagedObject>(entityName: "RosterPlayer")) {
            let data = try XCTUnwrap(player.value(forKey: "numbersData") as? Data)
            let decoded = try JSONDecoder().decode(Player.self, from: data)
            player.setValue(try JSONEncoder().encode(decoded.numbers), forKey: "numbersData")
        }
        try context.save(); try coordinator.remove(store)
        return (url, original)
    }

    func testV11LegacyV2MigrationPreservesEveryFieldAndKeepsIndependentArchive() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let (url, snapshot) = try legacyV2Fixture(in: root)
        let originalBytes = try Data(contentsOf: url)
        let migrated = try CoreDataRosterStore(storeURL: url)
        XCTAssertEqual(try migrated.loadSnapshot(), snapshot)
        XCTAssertEqual(try Data(contentsOf: url), originalBytes)
        XCTAssertNotEqual(migrated.resolvedURL, url)
        try migrated.close()
        let reopened = try CoreDataRosterStore(storeURL: url)
        XCTAssertEqual(try reopened.loadSnapshot(), snapshot)
        try reopened.close()
        let app = GameStore(persistenceURL: url)
        for _ in 0..<5 { app.createAutomaticBackup() }
        XCTAssertFalse(app.upgradeBackupURLs.isEmpty)
        XCTAssertEqual(try LocalBackup.read(app.upgradeBackupURLs[0]).snapshot(), snapshot)
        XCTAssertEqual(app.games.first?.state.homeScore, snapshot.games.first?.state.homeScore)
    }

    func testV11InterruptedUpgradeAtEveryStageLeavesSourceIntactAndCanRetry() throws {
        for stage in [CoreDataRosterStore.UpgradeStage.copied, .migrated, .validated, .beforeActivation] {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            let (url, expected) = try legacyV2Fixture(in: root)
            let bytes = try Data(contentsOf: url)
            CoreDataRosterStore.upgradeFault = { reached in
                if reached == stage { throw CocoaError(.fileWriteOutOfSpace) }
            }
            defer { CoreDataRosterStore.upgradeFault = nil }
            XCTAssertThrowsError(try CoreDataRosterStore(storeURL: url))
            XCTAssertEqual(try Data(contentsOf: url), bytes)
            XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("active-store-v3.json").path))
            CoreDataRosterStore.upgradeFault = nil
            let recovered = try CoreDataRosterStore(storeURL: url)
            XCTAssertEqual(try recovered.loadSnapshot(), expected)
            try recovered.close()
        }
    }

    func testV11FailedCompleteActionRollsBackScoreIdentityAndRevision() throws {
        let store = v11Store()
        let original = store.game
        let revision = store.activeStoredGame?.revision
        CoreDataRosterStore.writeFault = { throw CocoaError(.fileWriteOutOfSpace) }
        defer { CoreDataRosterStore.writeFault = nil }
        XCTAssertFalse(store.applyPlay(.homeRun))
        XCTAssertEqual(store.game, original)
        XCTAssertEqual(store.activeStoredGame?.revision, revision)
        XCTAssertNotNil(store.storageErrorMessage)
        CoreDataRosterStore.writeFault = nil
        XCTAssertTrue(store.applyPlay(.homeRun))
        XCTAssertEqual(store.game.awayScore, 1)
    }

    func testV11PinchHitterSharesAppearanceAndTerminalContinueReopensNextHalf() throws {
        let store = v11Store()
        store.recordPitch(.calledStrike)
        let id = try XCTUnwrap(store.game.currentPlateAppearanceID)
        let replacement = try XCTUnwrap(store.battingBenchPlayers.first)
        store.replaceCurrentBatter(with: replacement)
        store.recordPitch(.ball)
        XCTAssertEqual(store.game.currentPlateAppearanceID, id)
        XCTAssertEqual(store.game.plateAppearances?.first?.batterIDs.count, 2)
        store.game.inning = 6; store.game.isTop = false
        store.game.outs = 2
        store.applyPlay(.groundOut)
        XCTAssertEqual(store.game.pendingDecision, .regulationHalf)
        store.resolveGameDecision(finish: true)
        XCTAssertTrue(store.reopenGame())
        XCTAssertEqual(store.game.inning, 7)
        XCTAssertTrue(store.game.isTop)
        XCTAssertEqual(store.game.outs, 0)
    }
}

extension GameStoreTests {
    func testV11WalkAndHBPForcedRunCreditsOnlyEffectiveRBIAtCap() {
        for hbp in [false, true] {
            let store = v11Store(cap: 6)
            store.game.awayRunsByInning[0] = 5
            for (base, player) in zip(Base.allCases, store.game.awayTeam.players.dropFirst()) { store.game.baseRunners[base] = player }
            let batter = store.currentBatter
            if hbp { store.recordHitByPitch() } else { for _ in 0..<4 { store.recordPitch(.ball) } }
            XCTAssertEqual(store.game.awayScore, 6)
            XCTAssertEqual(store.battingLine(for: batter).runsBattedIn, 1)
            XCTAssertEqual(store.game.pendingDecision, .runLimit)
        }
    }

    func testV11LastFoulAndSimultaneousStrikeoutLimitCountOneOut() {
        let foul = v11Store(mode: .coachPitch, pitches: 1)
        let first = foul.currentBatter
        foul.recordPitch(.foul)
        XCTAssertEqual(foul.game.outs, 1)
        XCTAssertEqual(foul.battingLine(for: first).atBats, 1)
        XCTAssertEqual(foul.battingLine(for: first).strikeouts, 0)
        let strikeout = v11Store(mode: .coachPitch, pitches: 3)
        let batter = strikeout.currentBatter
        for _ in 0..<3 { strikeout.recordPitch(.calledStrike) }
        XCTAssertEqual(strikeout.game.outs, 1)
        XCTAssertEqual(strikeout.battingLine(for: batter).strikeouts, 1)
        XCTAssertEqual(strikeout.battingLine(for: batter).plateAppearances, 1)
        XCTAssertTrue(strikeout.game.pitching.isEmpty)
    }

    func testV11RosterNumberOrderingUsesPrimaryAndKeepsHistoricalSnapshot() throws {
        let store = v11Store()
        let team = try XCTUnwrap(store.currentTeam)
        let historicalName = store.game.awayTeam.players[0].name
        store.updatePlayer(in: team.id, playerID: team.players[0].id, chineseName: "", englishName: "New name", numbers: [0], numberTexts: ["00"])
        let second = team.players[1].id
        store.updatePlayer(in: team.id, playerID: second, chineseName: "", englishName: "", numbers: [0, 7], numberTexts: ["0", "7"])
        let roster = try XCTUnwrap(store.currentTeam).sortedPlayers
        XCTAssertEqual(roster.prefix(2).map(\.numberText), ["0", "00"])
        XCTAssertEqual(roster[1].name, "New name")
        XCTAssertEqual(store.game.awayTeam.players[0].name, historicalName)
        XCTAssertEqual(store.game.awayBattingOrderIDs.first, team.players[0].id)
    }
}

extension GameStoreTests {
    func testV11AppearanceMigrationWriteFailureStopsNormalUseAndRetries() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let url = root.appendingPathComponent("BaseballMaster.sqlite")
        let source = v11Store()
        source.recordPitch(.ball)
        let snapshot = try LocalBackup.read(source.exportBackup()).snapshot()
        var games = snapshot.games
        games[0].state.plateAppearances = nil
        games[0].state.currentPlateAppearanceID = nil
        for i in games[0].state.scoringEvents!.indices { games[0].state.scoringEvents![i].plateAppearanceID = nil }
        let legacy = RosterSnapshot(teams: snapshot.teams, opponentTeams: snapshot.opponentTeams,
            currentTeamID: snapshot.currentTeamID, seasons: snapshot.seasons, playerGameRecords: snapshot.playerGameRecords, games: games)
        let database = try CoreDataRosterStore(storeURL: url)
        try database.replaceAll(with: legacy)
        try database.close()
        CoreDataRosterStore.writeFault = { throw CocoaError(.fileWriteOutOfSpace) }
        defer { CoreDataRosterStore.writeFault = nil }
        let failed = GameStore(persistenceURL: url)
        XCTAssertTrue(failed.requiresDataRecovery)
        XCTAssertEqual(failed.games, legacy.games)
        XCTAssertTrue(failed.automaticBackupURLs.isEmpty)
        CoreDataRosterStore.writeFault = nil
        let retried = GameStore(persistenceURL: url)
        XCTAssertFalse(retried.requiresDataRecovery)
        XCTAssertNotNil(retried.game.currentPlateAppearanceID)
        let again = GameStore(persistenceURL: url)
        XCTAssertEqual(retried.game.plateAppearances, again.game.plateAppearances)
    }

    func testV11ScheduledObservationProtectsBothTeamsWithoutOwnTeam() throws {
        let store = GameStore(persistenceURL: nil)
        XCTAssertTrue(store.deleteTeam(id: try XCTUnwrap(store.currentTeam).id))
        let away = store.opponentTeams[0], home = store.opponentTeams[1]
        let id = try XCTUnwrap(store.createObservedGame(awayTeam: away, homeTeam: home, rules: GameRules(), startImmediately: false))
        XCTAssertFalse(store.deleteOpponentTeam(id: away.id))
        XCTAssertFalse(store.deleteOpponentTeam(id: home.id))
        store.deleteGame(id: id)
        XCTAssertTrue(store.deleteOpponentTeam(id: away.id))
        XCTAssertTrue(store.deleteOpponentTeam(id: home.id))
        XCTAssertTrue(store.teams.isEmpty)
    }
}

extension GameStoreTests {
    func testV11RestoredLegacyBackupGetsStableIDsBeforeNextAction() throws {
        let source = v11Store()
        source.recordPitch(.ball)
        let snapshot = try LocalBackup.read(source.exportBackup()).snapshot()
        var games = snapshot.games
        games[0].state.plateAppearances = nil
        games[0].state.currentPlateAppearanceID = nil
        for i in games[0].state.scoringEvents!.indices { games[0].state.scoringEvents![i].plateAppearanceID = nil }
        let legacy = RosterSnapshot(teams: snapshot.teams, opponentTeams: snapshot.opponentTeams,
            currentTeamID: snapshot.currentTeamID, seasons: snapshot.seasons, playerGameRecords: snapshot.playerGameRecords, games: games)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("BaseballMaster.sqlite")
        let restored = GameStore(persistenceURL: url)
        try restored.restoreBackup(LocalBackup(snapshot: legacy))
        let id = try XCTUnwrap(restored.game.currentPlateAppearanceID)
        XCTAssertEqual(restored.game.scoringEvents?.first(where: { $0.category == .pitch })?.plateAppearanceID, id)
        XCTAssertTrue((restored.game.scoringEvents ?? []).filter { $0.category == .clock }.allSatisfy { $0.plateAppearanceID == nil })
        XCTAssertEqual(restored.game.batting, source.game.batting)
        restored.recordPitch(.calledStrike)
        XCTAssertEqual(restored.game.scoringEvents?.last?.plateAppearanceID, id)
        XCTAssertEqual(GameStore(persistenceURL: url).game.currentPlateAppearanceID, id)
    }
}

extension GameStoreTests {
    func testPlayerPitchCountsPersistAcrossPitchingChangesUndoAndRestart() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let url = root.appendingPathComponent("BaseballMaster.sqlite")
        defer { try? FileManager.default.removeItem(at: root) }
        let store = v11Store(url: url)
        let firstPitcher = store.currentPitcher
        store.recordPitch(.ball)
        store.recordPitch(.calledStrike)
        store.recordPitch(.foul)
        XCTAssertTrue(store.applyPlay(.single))
        XCTAssertEqual(store.pitchingLine(for: firstPitcher).pitches, 4)
        XCTAssertEqual(store.pitchingLine(for: firstPitcher).strikes, 3)
        store.recordHitByPitch()
        XCTAssertEqual(store.pitchingLine(for: firstPitcher).pitches, 5)
        store.recordIntentionalWalk()
        XCTAssertEqual(store.pitchingLine(for: firstPitcher).pitches, 5)
        let nextPitcher = try XCTUnwrap(store.activeFielders.first { $0.id != firstPitcher.id })
        store.changePitcher(to: nextPitcher)
        XCTAssertEqual(store.currentPitcher.id, nextPitcher.id)
        XCTAssertEqual(store.pitchingLine(for: nextPitcher).pitches, 0)
        store.recordPitch(.ball)
        XCTAssertEqual(store.pitchingLine(for: nextPitcher).pitches, 1)
        store.undo()
        XCTAssertEqual(store.pitchingLine(for: nextPitcher).pitches, 0)
        store.redo()
        let restarted = GameStore(persistenceURL: url)
        XCTAssertEqual(restarted.currentPitcher.id, nextPitcher.id)
        XCTAssertEqual(restarted.pitchingLine(for: firstPitcher).pitches, 5)
        XCTAssertEqual(restarted.pitchingLine(for: firstPitcher).strikes, 3)
        XCTAssertEqual(restarted.pitchingLine(for: nextPitcher).pitches, 1)
    }
}

private final class LiveTestVault: LiveCredentialStorage {
    var bindings: [LiveBinding] = []
    var fails = false
    var reads = 0
    var writes = 0
    func read() throws -> [LiveBinding] { reads += 1; return bindings }
    func write(_ value: [LiveBinding]) throws {
        writes += 1
        if fails { throw LiveError.message("injected keychain failure") }
        bindings = value
    }
}
private final class LiveTestTransport: LiveTransport {
    struct Call { var method: String; var code: String?; var body: Data? }
    var calls: [Call] = []
    var failure: Error?
    var revision = 0
    var suspendCreation = false
    var failureAfterResume: Error?
    var continuation: CheckedContinuation<Void, Never>?
    func send(method: String, code: String?, token: String, body: Data?) async throws -> LiveResponse? {
        calls.append(Call(method: method, code: code, body: body))
        if let failure { self.failure = nil; throw failure }
        if suspendCreation && code == nil && method == "POST" {
            await withCheckedContinuation { continuation = $0 }
            if let error = failureAfterResume { failureAfterResume = nil; throw error }
        }
        if method == "DELETE" { return nil }
        if let body, let json = try JSONSerialization.jsonObject(with: body) as? [String: Any] {
            revision = (json["revision"] as? Int) ?? (json["snapshot"] as? [String: Any])?["revision"] as? Int ?? revision
        }
        let now = Date().timeIntervalSince1970 * 1000
        return LiveResponse(code: "1234567890ABCDEF12345678", revision: revision, serverTime: now, lastSeen: now, expiresAt: now + 3_600_000)
    }
}
extension GameStoreTests {
    func testLiveProjectionUsesStableAppearanceIDsAndUndoRemovesEvents() throws {
        let store = v11Store()
        let id = try XCTUnwrap(store.game.currentPlateAppearanceID)
        store.recordPitch(.ball)
        let first = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        let entry = try XCTUnwrap(first.entries.first { $0.id == id.uuidString })
        XCTAssertEqual(entry.status, "current")
        XCTAssertEqual(first.pitchCount, 1)
        XCTAssertTrue(store.applyPlay(.single))
        let hit = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(hit.entries.first { $0.id == id.uuidString }?.status, "completed")
        XCTAssertEqual(hit.away.hits, 1)
        store.undo()
        let undone = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(undone.currentAppearanceID, id.uuidString)
        XCTAssertEqual(undone.entries.first { $0.id == id.uuidString }?.details, entry.details)
        XCTAssertGreaterThan(undone.revision, hit.revision)
        XCTAssertEqual(undone.away.hits, 0)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: undone.data()) as? [String: Any])
        XCTAssertTrue(json["endedAt"] is NSNull)
        XCTAssertNil(json["teams"]); XCTAssertNil(json["token"])
        XCTAssertEqual(try undone.data(), try undone.data())
    }

    func testLiveProjectionCoachAndInterruptedAppearanceDoNotInventPitcherStatsOrOut() throws {
        let store = v11Store(mode: .coachPitch)
        store.recordPitch(.ball)
        let first = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertNil(first.pitchCount); XCTAssertEqual(first.appearancePitchCount, 1); XCTAssertEqual(first.pitchLimit, 6)
        let id = first.currentAppearanceID
        store.endCurrentHalf()
        let changed = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(changed.entries.first { $0.id == id }?.status, "interrupted")
        XCTAssertTrue(changed.entries.first { $0.id == id }?.summary.contains("未完成") == true)
        XCTAssertNil(changed.pitchCount)
    }

    func testLiveCredentialsMustPersistBeforeNetworkAndNeverEnterBackup() async throws {
        let store = v11Store(); let vault = LiveTestVault(); let network = LiveTestTransport()
        let manager = LiveBroadcastManager(store: store, vault: vault, transport: network, enabled: true)
        let id = try XCTUnwrap(store.activeStoredGame?.id)
        vault.fails = true; manager.start(id); await manager.waitForPendingSync()
        XCTAssertTrue(network.calls.isEmpty); XCTAssertTrue(manager.bindings.isEmpty)
        vault.fails = false; manager.start(id); await manager.waitForPendingSync()
        XCTAssertNotNil(manager.url(for: id)); XCTAssertEqual(manager.binding(for: id)?.ackRevision, store.activeStoredGame?.revision ?? 0)
        let secret = try XCTUnwrap(vault.bindings.first?.token)
        let exported = try Data(contentsOf: store.exportBackup())
        XCTAssertFalse(String(decoding: exported, as: UTF8.self).contains(secret))
    }

    func testLiveRestartRetriesSameCreationIdentityAndCoalescesLatestSavedState() async throws {
        let store = v11Store(); let vault = LiveTestVault(); let network = LiveTestTransport()
        network.failure = URLError(.networkConnectionLost)
        let manager = LiveBroadcastManager(store: store, vault: vault, transport: network, enabled: true)
        let id = try XCTUnwrap(store.activeStoredGame?.id)
        manager.start(id); await manager.waitForPendingSync()
        let requestID = try XCTUnwrap(vault.bindings.first?.requestID)
        store.recordPitch(.ball); store.recordPitch(.calledStrike); store.undo()
        let restarted = LiveBroadcastManager(store: store, vault: vault, transport: network, enabled: true)
        restarted.requestSync(); await restarted.waitForPendingSync()
        XCTAssertEqual(restarted.binding(for: id)?.requestID, requestID)
        XCTAssertEqual(restarted.binding(for: id)?.ackRevision, store.activeStoredGame?.revision)
        let puts = network.calls.filter { $0.method == "PUT" }
        let payload = try JSONDecoder().decode(LiveSnapshot.self, from: XCTUnwrap(puts.last?.body))
        XCTAssertEqual(payload.balls, 1); XCTAssertEqual(payload.strikes, 0)
        let createBodies = try network.calls.filter { $0.method == "POST" && $0.code == nil }.map { try JSONSerialization.jsonObject(with: XCTUnwrap($0.body)) as! [String: Any] }
        XCTAssertEqual(createBodies[0]["requestID"] as? String, createBodies[1]["requestID"] as? String)
    }

    func testLiveCloseDuringCreatePreservesIntentAndDeletesNewlyCreatedStream() async throws {
        let store = v11Store(); let vault = LiveTestVault(); let network = LiveTestTransport()
        network.suspendCreation = true
        let manager = LiveBroadcastManager(store: store, vault: vault, transport: network, enabled: true)
        let id = try XCTUnwrap(store.activeStoredGame?.id)
        manager.start(id)
        for _ in 0..<100 where network.continuation == nil { await Task.yield() }
        XCTAssertNotNil(network.continuation)
        manager.close(id); network.continuation?.resume(); await manager.waitForPendingSync()
        XCTAssertNil(manager.binding(for: id)); XCTAssertNil(manager.url(for: id))
        XCTAssertEqual(network.calls.last?.method, "DELETE")
        XCTAssertFalse(network.calls.contains { $0.method == "PUT" })
    }

    func testLiveConflictBlocksPublishingAndRestoreDetachesWithoutChangingLocalGame() async throws {
        let store = v11Store(); let vault = LiveTestVault(); let network = LiveTestTransport()
        let manager = LiveBroadcastManager(store: store, vault: vault, transport: network, enabled: true)
        let id = try XCTUnwrap(store.activeStoredGame?.id)
        manager.start(id); await manager.waitForPendingSync()
        store.recordPitch(.ball); network.failure = LiveError.server(409, "修订版本冲突")
        manager.requestSync(); await manager.waitForPendingSync()
        XCTAssertEqual(manager.binding(for: id)?.blocked, true)
        let saved = store.games
        manager.detachAfterRestore(); await manager.waitForPendingSync()
        XCTAssertNil(manager.binding(for: id)); XCTAssertEqual(store.games, saved)
    }

    func testLiveExpiredOrDeletedStreamCannotBeSilentlyRecreated() async throws {
        let store = v11Store(); let vault = LiveTestVault(); let network = LiveTestTransport()
        let id = try XCTUnwrap(store.activeStoredGame?.id)
        vault.bindings = [LiveBinding(gameID: id, token: String(repeating: "a", count: 64), code: "1234567890ABCDEF12345678", expiresAt: Date().addingTimeInterval(-1))]
        let manager = LiveBroadcastManager(store: store, vault: vault, transport: network, enabled: true)
        manager.requestSync(); await manager.waitForPendingSync()
        XCTAssertTrue(network.calls.isEmpty); XCTAssertNil(manager.binding(for: id))
        manager.requestSync(); await manager.waitForPendingSync()
        XCTAssertTrue(network.calls.isEmpty)
    }

    func testLiveHTTPProjectionAgainstLocalService() async throws {
        let base = URL(string: "http://127.0.0.1:18088/baseballmaster/live")!
        do { _ = try await URLSession.shared.data(from: base.appendingPathComponent("health")) }
        catch { throw XCTSkip("Run the local integration server on port 18088 for this test") }
        let store = v11Store(); let vault = LiveTestVault()
        let manager = LiveBroadcastManager(store: store, vault: vault, transport: LiveHTTPClient(baseURL: base), enabled: true)
        let id = try XCTUnwrap(store.activeStoredGame?.id)
        manager.start(id); await manager.waitForPendingSync()
        let code = try XCTUnwrap(manager.binding(for: id)?.code, manager.messages[id] ?? "")
        let readURL = base.appendingPathComponent("api/sessions/\(code)")
        store.recordPitch(.ball); XCTAssertTrue(store.applyPlay(.single)); manager.requestSync(); await manager.waitForPendingSync()
        let (firstData, firstResponse) = try await URLSession.shared.data(from: readURL)
        XCTAssertEqual((firstResponse as? HTTPURLResponse)?.statusCode, 200)
        let first = try XCTUnwrap(JSONSerialization.jsonObject(with: firstData) as? [String: Any])
        XCTAssertEqual((first["snapshot"] as? [String: Any])?["pitchCount"] as? Int, 2)
        store.undo(); manager.requestSync(); await manager.waitForPendingSync()
        let (undoData, _) = try await URLSession.shared.data(from: readURL)
        let undo = try XCTUnwrap(JSONSerialization.jsonObject(with: undoData) as? [String: Any])
        XCTAssertEqual(((undo["snapshot"] as? [String: Any])?["away"] as? [String: Any])?["hits"] as? Int, 0)
        store.finishGame(); manager.requestSync(); await manager.waitForPendingSync()
        let (finalData, _) = try await URLSession.shared.data(from: readURL)
        let final = try XCTUnwrap(JSONSerialization.jsonObject(with: finalData) as? [String: Any])
        XCTAssertEqual((final["snapshot"] as? [String: Any])?["isFinal"] as? Bool, true)
        XCTAssertEqual((final["expiresAt"] as? Double ?? 0) - (final["serverTime"] as? Double ?? 0), 3_600_000, accuracy: 5000)
        manager.close(id); await manager.waitForPendingSync()
        let (_, deleted) = try await URLSession.shared.data(from: readURL)
        XCTAssertEqual((deleted as? HTTPURLResponse)?.statusCode, 404)
        XCTAssertEqual(store.games.count, 1)
    }
}


extension GameStoreTests {
    func testLiveCloseSurvivesInFlightCreationFailure() async throws {
        let store = v11Store(); let vault = LiveTestVault(); let network = LiveTestTransport()
        network.suspendCreation = true; network.failureAfterResume = LiveError.server(400, "creation expired")
        let manager = LiveBroadcastManager(store: store, vault: vault, transport: network, enabled: true)
        let id = try XCTUnwrap(store.activeStoredGame?.id)
        manager.start(id)
        for _ in 0..<100 where network.continuation == nil { await Task.yield() }
        XCTAssertNotNil(network.continuation)
        manager.close(id); network.continuation?.resume(); await manager.waitForPendingSync()
        XCTAssertNil(manager.binding(for: id))
        XCTAssertEqual(network.calls.last?.method, "DELETE")
        XCTAssertNil(network.calls.last?.code)
    }
}


extension GameStoreTests {
    func testDeferredLiveDoesNotReadCredentialsOrSendEvenAfterRestoreAndForeground() async throws {
        let store = v11Store(); let vault = LiveTestVault(); let network = LiveTestTransport()
        let id = try XCTUnwrap(store.activeStoredGame?.id)
        let saved = LiveBinding(gameID: id, token: "synthetic-test-token", code: "1234567890ABCDEF12345678", expiresAt: Date().addingTimeInterval(3600))
        vault.bindings = [saved]
        let manager = LiveBroadcastManager(store: store, vault: vault, transport: network)
        XCTAssertFalse(manager.isEnabled)
        XCTAssertFalse(store.liveBroadcasts.isEnabled)
        manager.start(id); manager.setForeground(true); manager.requestSync(); manager.retry(id)
        manager.close(id); manager.detachAfterRestore(); manager.setForeground(false)
        await manager.synchronize(); await manager.waitForPendingSync()
        XCTAssertTrue(network.calls.isEmpty)
        XCTAssertEqual(vault.reads, 0); XCTAssertEqual(vault.writes, 0)
        XCTAssertEqual(vault.bindings, [saved])
        XCTAssertNil(manager.url(for: id))
        store.recordPitch(.ball)
        XCTAssertEqual(store.game.balls, 1)
        XCTAssertNotNil(store.activeStoredGame)
    }
}

// V1.1.1: identities, roster order and jersey labels are independent of participation.
extension GameStoreTests {
    private func v111Store(rules: GameRules) -> GameStore {
        let store = GameStore(persistenceURL: nil)
        let lineup = Array(store.currentTeam!.players.prefix(9)).enumerated().map {
            LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset])
        }
        store.opponentTeams[0].players.append(Player(name: "测试替补甲", number: 44, primaryPosition: .pitcher))
        store.opponentTeams[0].players.append(Player(name: "测试替补乙", number: 63, primaryPosition: .catcher))
        store.startNewGame(opponent: store.opponentTeams[0], isHome: false, rules: rules, lineup: lineup)
        return store
    }

    func testV111SelectedStarterWinsAcrossPathsSidesNumbersAndRosterOrders() throws {
        for path in 0..<4 {
            for isHome in [false, true] {
                for variant in 0..<3 {
                    let store = GameStore(persistenceURL: nil)
                    func fixture(_ name: String) -> (Team, [LineupAssignment], UUID, UUID) {
                        var bench = Player(name: "替补" + name, number: [10, 77, 0][variant], primaryPosition: .pitcher)
                        if variant == 2 { bench.jerseyNumbers = ["00"] }
                        let starters = (0..<9).map { Player(name: name + "先发\($0)", number: (variant * 21 + $0 + 1) % 100, primaryPosition: .pitcher) }
                        var roster = starters
                        roster.insert(bench, at: [0, 4, 9][variant])
                        if variant == 1 { roster.reverse() }
                        let lineup = starters.enumerated().map { LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset]) }
                        return (Team(name: name, shortName: name, city: "", players: roster), lineup, starters[0].id, bench.id)
                    }
                    let (ours, selected, expected, benchID) = fixture("本队")
                    let (opponent, otherSelected, otherExpected, otherBenchID) = fixture("对手")
                    if path < 2 {
                        store.teams = [ours]; store.currentTeam = ours; store.opponentTeams = [opponent]
                        let id = try XCTUnwrap(store.startNewGame(opponent: opponent, isHome: isHome, rules: GameRules(), lineup: selected, startImmediately: path == 0))
                        if path == 1 { XCTAssertTrue(store.startScheduledGame(id: id, rules: GameRules(), lineup: selected)) }
                    } else {
                        store.teams = []; store.currentTeam = nil; store.opponentTeams = [ours, opponent]
                        let away = isHome ? opponent : ours, home = isHome ? ours : opponent
                        let awayOrder = isHome ? otherSelected : selected, homeOrder = isHome ? selected : otherSelected
                        let id = try XCTUnwrap(store.createObservedGame(awayTeam: away, homeTeam: home, rules: GameRules(), awayLineup: awayOrder, homeLineup: homeOrder, startImmediately: path == 2))
                        if path == 3 { XCTAssertTrue(store.startScheduledObservedGame(id: id, rules: GameRules(), awayLineup: awayOrder, homeLineup: homeOrder)) }
                        XCTAssertEqual(isHome ? store.game.activeAwayPitcherID : store.game.activeHomePitcherID, otherExpected)
                        XCTAssertNotEqual(isHome ? store.game.activeAwayPitcherID : store.game.activeHomePitcherID, otherBenchID)
                    }
                    XCTAssertEqual(isHome ? store.game.activeHomePitcherID : store.game.activeAwayPitcherID, expected, "path \(path), side \(isHome), variant \(variant)")
                    XCTAssertNotEqual(isHome ? store.game.activeHomePitcherID : store.game.activeAwayPitcherID, benchID)
                    if !isHome { store.endCurrentHalf() }
                    XCTAssertEqual(store.currentPitcher.id, expected)
                    store.recordPitch(.ball)
                    XCTAssertEqual(store.game.pitching[expected]?.pitches, 1)
                    XCTAssertNil(store.game.pitching[benchID])
                    XCTAssertNil(store.actionErrorMessage)
                }
            }
        }
    }

    func testV111PitcherPositionSwapIgnoresBenchAndReentryAccumulates() throws {
        let store = v111Store(rules: GameRules())
        let original = store.currentPitcher
        let fielder = try XCTUnwrap(store.activeFielders.first { $0.primaryPosition == .shortstop })
        let relief = try XCTUnwrap(store.fieldingBenchPlayers.first)
        store.recordPitch(.ball)
        store.changeFieldingPosition(for: original, to: .shortstop)
        XCTAssertEqual(store.currentPitcher.id, fielder.id)
        XCTAssertEqual(store.activeFielders.first { $0.id == original.id }?.primaryPosition, .shortstop)
        store.recordPitch(.foul)
        store.changePitcher(to: relief)
        XCTAssertTrue(store.fieldingBenchPlayers.contains { $0.id == fielder.id })
        store.recordPitch(.ball)
        store.changePitcher(to: try XCTUnwrap(store.fieldingBenchPlayers.first { $0.id == fielder.id }))
        store.recordPitch(.foul)
        XCTAssertEqual(store.pitchingLine(for: fielder).pitches, 2)
        XCTAssertEqual(store.pitchingLine(for: original).pitches, 1)
        XCTAssertEqual(store.pitchingLine(for: relief).pitches, 1)
        XCTAssertEqual(store.activeFielders.filter { $0.primaryPosition == .pitcher }.map(\.id), [fielder.id])
        XCTAssertTrue(store.exitedPlayerIDs(forHomeTeam: true).contains(fielder.id))
    }

    func testV111BothTeamsReorderAnchorsCurrentAppearanceAndNextBatter() throws {
        let store = freshStore()
        store.recordPitch(.ball); store.recordPitch(.calledStrike)
        let batter = store.currentBatter, appearance = store.game.currentPlateAppearanceID
        let before = store.game, opponentNext = store.game.homeBattingOrderIDs[store.game.homeBatterIndex]
        var batting = store.lineupDraft(forHomeTeam: false)
        batting.moveBatter(from: 0, to: 3)
        let next = batting.battingOrderIDs[4]
        XCTAssertTrue(store.saveLineup(batting), store.actionErrorMessage ?? "")
        XCTAssertEqual(store.currentBatter.id, batter.id)
        XCTAssertEqual(store.currentBattingOrder, 4)
        XCTAssertEqual(store.game.currentPlateAppearanceID, appearance)
        XCTAssertEqual(store.game.plateAppearancePitchCount, before.plateAppearancePitchCount)
        XCTAssertEqual(store.game.balls, 1); XCTAssertEqual(store.game.strikes, 1)
        var defense = store.lineupDraft(forHomeTeam: true)
        defense.moveBatter(from: 0, to: 5)
        XCTAssertTrue(store.saveLineup(defense))
        XCTAssertEqual(store.game.homeBattingOrderIDs[store.game.homeBatterIndex], opponentNext)
        XCTAssertNil(store.validateScoringTimeline())
        XCTAssertTrue(store.applyPlay(.groundOut))
        XCTAssertEqual(store.currentBatter.id, next)
        XCTAssertEqual(store.game.plateAppearances?.first { $0.id == appearance }?.batterIDs, [batter.id])
        XCTAssertNil(store.validateScoringTimeline())
        store.endCurrentHalf()
        XCTAssertEqual(store.currentBatter.id, opponentNext)
    }

    func testV111ProfilesUpdateCurrentRunnerAndTeamRosterWithoutChangingIdentityOrOldText() throws {
        for isHome in [false, true] {
            let store = freshStore()
            if isHome { store.endCurrentHalf() }
            let runner = store.currentBatter
            XCTAssertTrue(store.applyPlay(.single))
            let beforeLog = store.game.playLog
            let beforeBatting = store.game.batting
            var draft = store.lineupDraft(forHomeTeam: isHome)
            XCTAssertTrue(draft.updateProfile(playerID: runner.id, chineseName: "更正姓名", englishName: "Corrected", numberTexts: ["00", "0", "87"]))
            XCTAssertTrue(store.saveLineup(draft), store.actionErrorMessage ?? "")
            XCTAssertEqual(store.game.baseRunners[.first]?.id, runner.id)
            XCTAssertEqual(store.game.baseRunners[.first]?.numberTexts, ["00", "0", "87"])
            XCTAssertEqual(store.game.batting, beforeBatting)
            XCTAssertEqual(Array(store.game.playLog.prefix(beforeLog.count)), beforeLog)
            let teamID = draft.team.id
            let roster = (store.teams + store.opponentTeams).first { $0.id == teamID }
            XCTAssertEqual(roster?.players.first { $0.id == runner.id }?.chineseName, "更正姓名")
            store.undo()
            XCTAssertEqual((store.teams + store.opponentTeams).first { $0.id == teamID }?.players.first { $0.id == runner.id }?.name, runner.name)
            store.redo()
            XCTAssertEqual((store.teams + store.opponentTeams).first { $0.id == teamID }?.players.first { $0.id == runner.id }?.chineseName, "更正姓名")
            XCTAssertNil(store.validateScoringTimeline())
        }
    }

    func testV111LineupCancelInvalidStaleAndFailedSaveLeaveGameAndRostersUnchanged() throws {
        let store = freshStore()
        let original = store.game, teams = store.teams, opponents = store.opponentTeams
        var draft = store.lineupDraft(forHomeTeam: false)
        XCTAssertFalse(draft.updateProfile(playerID: store.currentBatter.id, chineseName: "", englishName: "", numberTexts: ["007"]))
        XCTAssertTrue(draft.updateProfile(playerID: store.currentBatter.id, chineseName: "", englishName: "", numberTexts: ["00"]))
        draft.moveBatter(from: 0, to: 4)
        XCTAssertEqual(store.game, original) // Cancelling simply discards this draft.
        CoreDataRosterStore.writeFault = { throw CocoaError(.fileWriteOutOfSpace) }
        defer { CoreDataRosterStore.writeFault = nil }
        XCTAssertFalse(store.saveLineup(draft))
        XCTAssertEqual(store.game, original); XCTAssertEqual(store.teams, teams); XCTAssertEqual(store.opponentTeams, opponents)
        CoreDataRosterStore.writeFault = nil
        XCTAssertTrue(store.saveLineup(draft), store.actionErrorMessage ?? "")
        store.undo()
        XCTAssertEqual(store.game.currentBatter.id, original.currentBatter.id)
        XCTAssertEqual(store.teams, teams)
        var invalid = store.lineupDraft(forHomeTeam: false)
        invalid.battingOrderIDs[1] = invalid.battingOrderIDs[0]
        XCTAssertFalse(store.saveLineup(invalid))
        let stale = store.lineupDraft(forHomeTeam: true)
        store.recordPitch(.ball)
        let afterPitch = store.game
        XCTAssertFalse(store.saveLineup(stale))
        XCTAssertEqual(store.game, afterPitch)
    }

    func testV111LineupAndRosterSurviveRestartBackupAndFutureNewGame() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("BaseballMaster.sqlite")
        let seed = freshStore()
        let store = GameStore(persistenceURL: url)
        try store.restoreBackup(LocalBackup.read(seed.exportBackup()))
        let batter = store.currentBatter
        store.recordPitch(.calledStrike)
        var draft = store.lineupDraft(forHomeTeam: false)
        draft.moveBatter(from: 0, to: 2)
        XCTAssertTrue(draft.updateProfile(playerID: batter.id, chineseName: "球队新名字", englishName: "", numberTexts: ["99"]))
        XCTAssertTrue(store.saveLineup(draft))
        let expected = store.game
        let reopened = GameStore(persistenceURL: url)
        XCTAssertFalse(reopened.requiresDataRecovery)
        XCTAssertEqual(reopened.game, expected)
        XCTAssertEqual(reopened.currentTeam?.players.first { $0.id == batter.id }?.name, "球队新名字")
        let restored = GameStore(persistenceURL: nil)
        try restored.restoreBackup(LocalBackup.read(store.exportBackup()))
        XCTAssertEqual(restored.game, expected)
        XCTAssertEqual(restored.teams, store.teams)
        restored.finishGame()
        restored.startNewGame(opponent: restored.opponentTeams[0], isHome: false, innings: 6, lineup: Array(restored.currentTeam!.players.prefix(9)))
        XCTAssertEqual(restored.game.awayTeam.players.first { $0.id == batter.id }?.name, "球队新名字")
    }

    func testV111PinchRunnerAndFielderReentryKeepSlotsAndPitcherIdentity() throws {
        let store = v111Store(rules: GameRules())
        store.game.awayBatterIndex = try XCTUnwrap(store.game.awayBattingOrderIDs.firstIndex(of: try XCTUnwrap(store.game.activeAwayPitcherID)))
        let runner = store.currentBatter
        store.applyPlay(.single)
        store.game.automaticRunnerIDs = [runner.id]
        let replacement = try XCTUnwrap(store.battingBenchPlayers.first)
        store.replaceRunner(on: .first, with: replacement)
        store.replaceRunner(on: .first, with: try XCTUnwrap(store.battingBenchPlayers.first { $0.id == runner.id }))
        XCTAssertEqual(store.game.baseRunners[.first]?.id, runner.id)
        XCTAssertEqual(store.game.automaticRunnerIDs, [runner.id])
        XCTAssertEqual(store.game.activeAwayPitcherID, runner.id)
        let fielder = store.currentPitcher
        let bench = try XCTUnwrap(store.fieldingBenchPlayers.first)
        XCTAssertTrue(store.replaceFielder(fielder, with: bench))
        XCTAssertTrue(store.replaceFielder(try XCTUnwrap(store.activeFielders.first { $0.id == bench.id }), with: try XCTUnwrap(store.fieldingBenchPlayers.first { $0.id == fielder.id })))
        XCTAssertEqual(store.game.homeFieldingPlayerIDs?.filter { $0 == fielder.id }.count, 1)
        XCTAssertEqual(store.currentPitcher.id, fielder.id)
    }

    func testV111TwoWayPitcherCanKeepDHAndReturnToMoundUsingDraftOrQuickChange() throws {
        for useDraft in [false, true] {
            let store = GameStore(persistenceURL: nil)
            let lineup = Array(store.currentTeam!.players.prefix(9)).enumerated().map { LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset]) }
            store.startNewGame(opponent: store.opponentTeams[0], isHome: true, rules: GameRules(usesDesignatedHitter: true, allowsTwoWayPlayer: true), lineup: lineup)
            let original = store.currentPitcher, relief = try XCTUnwrap(store.fieldingBenchPlayers.first)
            store.recordPitch(.ball)
            if useDraft {
                var draft = store.lineupDraft(forHomeTeam: true)
                XCTAssertTrue(draft.replace(original.id, with: relief.id, pitchingOnly: true))
                XCTAssertTrue(store.saveLineup(draft), store.actionErrorMessage ?? "")
                draft = store.lineupDraft(forHomeTeam: true)
                XCTAssertTrue(draft.replace(relief.id, with: original.id))
                XCTAssertTrue(store.saveLineup(draft), store.actionErrorMessage ?? "")
            } else {
                store.changePitcher(to: relief)
                XCTAssertTrue(store.availablePitchers.contains { $0.id == original.id })
                store.changePitcher(to: original)
            }
            XCTAssertEqual(store.currentPitcher.id, original.id)
            XCTAssertEqual(store.game.homeDesignatedHitterID, original.id)
            XCTAssertEqual(store.game.homeBattingOrderIDs.filter { $0 == original.id }.count, 1)
            store.recordPitch(.ball)
            XCTAssertEqual(store.pitchingLine(for: original).pitches, 2)
        }
    }

    func testV111DraftSubstitutionPreservesTraditionalDHAndAutomaticRunner() throws {
        let store = GameStore(persistenceURL: nil)
        let lineup = Array(store.currentTeam!.players.prefix(9)).enumerated().map { LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset]) }
        store.startNewGame(opponent: store.opponentTeams[0], isHome: false, rules: GameRules(usesDesignatedHitter: true), lineup: lineup)
        let runner = store.currentBatter
        store.applyPlay(.single); store.game.automaticRunnerIDs = [runner.id]
        var draft = store.lineupDraft(forHomeTeam: false)
        let replacement = try XCTUnwrap(draft.bench.first)
        XCTAssertTrue(draft.replace(runner.id, with: replacement.id))
        XCTAssertTrue(store.saveLineup(draft), store.actionErrorMessage ?? "")
        XCTAssertEqual(store.game.baseRunners[.first]?.id, replacement.id)
        XCTAssertEqual(store.game.automaticRunnerIDs, [replacement.id])
        XCTAssertEqual(store.game.awayDesignatedHitterID, replacement.id)
        XCTAssertFalse(store.game.awayFieldingPlayerIDs?.contains(replacement.id) == true)
        XCTAssertFalse(store.game.awayBattingOrderIDs.contains(try XCTUnwrap(store.game.activeAwayPitcherID)))
    }

    func testV111FoulBuntIsOnePitchOneStrikeoutAndOrdinaryFoulIsNotAnOut() {
        for coach in [false, true] {
            let store = v11Store(mode: coach ? .coachPitch : .standard)
            let batter = store.currentBatter, pitcher = store.currentPitcher
            store.recordFoulBuntStrikeout()
            XCTAssertEqual(store.game.outs, 0)
            XCTAssertEqual(store.pitchingLine(for: pitcher).pitches, 0)
            store.recordPitch(.foul); store.recordPitch(.foul); store.recordPitch(.foul)
            XCTAssertEqual(store.game.strikes, 2); XCTAssertEqual(store.game.outs, 0)
            store.game.outs = 2
            store.game.baseRunners = [.second: store.game.battingOrderPlayers[1]]
            store.recordFoulBuntStrikeout()
            XCTAssertFalse(store.game.isTop); XCTAssertTrue(store.game.baseRunners.isEmpty)
            XCTAssertEqual(store.battingLine(for: batter).strikeouts, 1)
            XCTAssertEqual(store.pitchingLine(for: pitcher).pitches, coach ? 0 : 4)
            XCTAssertEqual(store.pitchingLine(for: pitcher).outsRecorded, coach ? 0 : 1)
            store.undo()
            XCTAssertEqual(store.currentBatter.id, batter.id); XCTAssertEqual(store.game.strikes, 2)
            store.redo()
            XCTAssertFalse(store.game.isTop)
        }
    }

    func testV111ThirdOutSkipsDestinationsButPreservesActualOutIdentities() throws {
        for (outcome, outs, runnerOutCount) in [(PlayOutcome.groundOut, 2, 0), (.flyOut, 2, 0), (.lineOut, 2, 0), (.foulFlyOut, 2, 0), (.pendingOut, 2, 0), (.doublePlay, 1, 1), (.triplePlay, 0, 2)] {
            let store = freshStore()
            store.game.outs = outs
            let runners = Array(store.game.battingOrderPlayers[1...3])
            store.game.baseRunners = [.first: runners[0], .second: runners[1], .third: runners[2]]
            let chosen = Set(runners.suffix(runnerOutCount).map(\.id))
            let decisions = try XCTUnwrap(store.halfEndingDecisions(for: outcome, runnerOutIDs: chosen))
            XCTAssertEqual(decisions.filter { $0.destination == .out }.count, 3 - outs)
            XCTAssertFalse(decisions.contains { $0.destination == .score })
            XCTAssertTrue(store.applyPlay(outcome, decisions: decisions))
            XCTAssertFalse(store.game.isTop); XCTAssertTrue(store.game.baseRunners.isEmpty)
            XCTAssertEqual(store.game.awayScore, 0)
            let event = try XCTUnwrap(store.game.scoringEvents?.last { $0.category == .battedBall })
            XCTAssertTrue(chosen.isSubset(of: Set(event.runnerMovements.filter { $0.destination == RunnerDestination.out.title }.map(\.playerID))))
        }
        let store = freshStore()
        store.game.outs = 2
        XCTAssertNil(store.halfEndingRunnerOutCount(for: .runnerTagOut)) // Still asks about run timing.
        XCTAssertNil(store.halfEndingDecisions(for: .groundOut, runnerOutIDs: [UUID()]))
    }

    func testV111BothPitcherLimitsRemainVisibleAcrossThirdOutUndoAndReentry() throws {
        let store = v111Store(rules: GameRules(pitchLimit: 3, pitchWarningRemaining: 1, pitcherInningsLimit: 1))
        let pitcher = store.currentPitcher, teamName = store.game.fieldingTeam.shortName
        store.applyPlay(.groundOut); store.applyPlay(.groundOut)
        XCTAssertTrue(store.ruleNotices().contains { $0.contains(teamName) && $0.contains("还剩 1 球") })
        store.applyPlay(.groundOut)
        XCTAssertFalse(store.game.isTop)
        XCTAssertTrue(store.ruleNotices().first?.contains(pitcher.name) == true, "Reached opponent limit must be visible before next pitcher's advisory")
        XCTAssertTrue(store.ruleNotices().contains { $0.contains(pitcher.name) && $0.contains("达到 3 球限制") })
        XCTAssertTrue(store.ruleNotices().contains { $0.contains(pitcher.name) && $0.contains("达到 1 局投球限制") })
        store.undo()
        XCTAssertFalse(store.ruleNotices().contains { $0.contains("已达到") })
        store.redo()
        XCTAssertTrue(store.ruleNotices().contains { $0.contains(pitcher.name) && $0.contains("已达到") })
        let other = store.currentPitcher
        for _ in 0..<3 { store.applyPlay(.groundOut) }
        XCTAssertTrue(store.ruleNotices().contains { $0.contains(other.name) && $0.contains("已达到") })
        XCTAssertTrue(store.ruleNotices().contains { $0.contains(pitcher.name) && $0.contains("已达到") })
    }
}

extension GameStoreTests {
    func testV111ThirdOutRunnerFlowOnlyNeedsOutAndPossibleScorers() throws {
        for counts in [false, true] {
            let store = freshStore()
            let current = store.currentBatter.id
            let runners = Array(store.game.battingOrderPlayers[1...2])
            store.game.outs = 2
            store.game.baseRunners = [.first: runners[0], .third: runners[1]]
            let decisions = try XCTUnwrap(store.halfEndingRunnerDecisions(for: .tagOut, outID: runners[0].id, scorerIDs: [runners[1].id]))
            XCTAssertTrue(store.runnerEventNeedsTimingDecision(.tagOut, decisions: decisions))
            XCTAssertFalse(store.recordRunnerEvent(.tagOut, decisions: decisions))
            XCTAssertTrue(store.recordRunnerEvent(.tagOut, decisions: decisions, timingRunCounts: counts))
            XCTAssertEqual(store.game.awayScore, counts ? 1 : 0)
            XCTAssertFalse(store.game.isTop); XCTAssertTrue(store.game.baseRunners.isEmpty)
            store.endCurrentHalf()
            XCTAssertEqual(store.currentBatter.id, current) // Interrupted appearance resumes, unchanged batting slot.
        }
        let store = freshStore()
        let runner = store.game.battingOrderPlayers[1]
        store.game.outs = 2; store.game.baseRunners = [.first: runner]
        XCTAssertNil(store.halfEndingRunnerDecisions(for: .forceOut, outID: runner.id, scorerIDs: [runner.id]))
        let decisions = try XCTUnwrap(store.halfEndingRunnerDecisions(for: .forceOut, outID: runner.id, scorerIDs: []))
        XCTAssertTrue(store.recordRunnerEvent(.forceOut, decisions: decisions))
        XCTAssertFalse(store.game.isTop)
    }

    func testV111DoubleSwitchCanReturnBothRemovedPlayers() throws {
        let store = v111Store(rules: GameRules())
        let pitcher = store.currentPitcher
        let catcher = try XCTUnwrap(store.activeFielders.first { $0.primaryPosition == .catcher })
        let reserves = store.fieldingBenchPlayers
        XCTAssertGreaterThanOrEqual(reserves.count, 2)
        XCTAssertTrue(store.performDoubleSwitch(firstOut: pitcher, firstIn: reserves[0], firstPosition: .pitcher,
                                              secondOut: catcher, secondIn: reserves[1], secondPosition: .catcher))
        let returningPitcher = try XCTUnwrap(store.fieldingBenchPlayers.first { $0.id == pitcher.id })
        let returningCatcher = try XCTUnwrap(store.fieldingBenchPlayers.first { $0.id == catcher.id })
        XCTAssertTrue(store.performDoubleSwitch(firstOut: reserves[0], firstIn: returningPitcher, firstPosition: .pitcher,
                                              secondOut: reserves[1], secondIn: returningCatcher, secondPosition: .catcher))
        XCTAssertEqual(store.currentPitcher.id, pitcher.id)
        XCTAssertEqual(Set(store.game.homeBattingOrderIDs).count, 9)
        XCTAssertEqual(Set(store.game.homeFieldingPlayerIDs ?? []).count, 9)
    }

    func testV111ExplicitLegacyPitcherAndStatisticsAreNotSilentlyMoved() throws {
        let store = v111Store(rules: GameRules())
        let originalStarter = store.currentPitcher
        let oldIncorrectPitcher = try XCTUnwrap(store.fieldingBenchPlayers.first)
        store.game.activeHomePitcherID = oldIncorrectPitcher.id
        store.game.pitching[oldIncorrectPitcher.id] = PitchingLine(pitches: 12)
        XCTAssertEqual(store.currentPitcher.id, oldIncorrectPitcher.id)
        let target = GameStore(persistenceURL: nil)
        try target.restoreBackup(LocalBackup.read(store.exportBackup()))
        XCTAssertEqual(target.currentPitcher.id, oldIncorrectPitcher.id)
        var draft = target.lineupDraft(forHomeTeam: true)
        XCTAssertNotNil(draft.validationMessage)
        draft.changePosition(playerID: originalStarter.id, to: .pitcher)
        XCTAssertTrue(target.saveLineup(draft))
        XCTAssertEqual(target.currentPitcher.id, originalStarter.id)
        XCTAssertEqual(target.pitchingLine(for: oldIncorrectPitcher).pitches, 12)
        XCTAssertEqual(target.pitchingLine(for: originalStarter).pitches, 0)
    }

    func testV111ProfileFailureAlsoLeavesDatabaseRosterUnchangedAndUndoCanRetry() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("BaseballMaster.sqlite")
        let store = GameStore(persistenceURL: url)
        try store.restoreBackup(LocalBackup.read(freshStore().exportBackup()))
        let oldGame = store.game, oldTeams = store.teams
        var draft = store.lineupDraft(forHomeTeam: false)
        XCTAssertTrue(draft.updateProfile(playerID: store.currentBatter.id, chineseName: "更正", englishName: "", numberTexts: ["00"]))
        CoreDataRosterStore.writeFault = { throw CocoaError(.fileWriteOutOfSpace) }
        defer { CoreDataRosterStore.writeFault = nil }
        XCTAssertFalse(store.saveLineup(draft))
        CoreDataRosterStore.writeFault = nil
        let reopened = GameStore(persistenceURL: url)
        XCTAssertEqual(reopened.game, oldGame); XCTAssertEqual(reopened.teams, oldTeams)
        XCTAssertTrue(store.saveLineup(draft))
        let corrected = store.game, correctedTeams = store.teams
        CoreDataRosterStore.writeFault = { throw CocoaError(.fileWriteOutOfSpace) }
        store.undo()
        XCTAssertEqual(store.game, corrected); XCTAssertEqual(store.teams, correctedTeams)
        CoreDataRosterStore.writeFault = nil
        store.undo()
        XCTAssertEqual(store.game.currentBatter.name, oldGame.currentBatter.name)
        XCTAssertEqual(store.teams, oldTeams)
        store.redo()
        XCTAssertEqual(store.teams, correctedTeams)
    }
}

extension GameStoreTests {
    func testV111LineupCorrectionsKeepStatsAndTextPDFExportsAttachedToSamePlayer() throws {
        let store = freshStore()
        let hitter = store.currentBatter
        store.recordPitch(.ball)
        XCTAssertTrue(store.applyPlay(.single))
        let originalEventText = try XCTUnwrap(store.game.scoringEvents?.first { $0.resolvedOutcome == .single }?.title)
        var draft = store.lineupDraft(forHomeTeam: false)
        draft.moveBatter(from: 1, to: 5)
        XCTAssertTrue(draft.updateProfile(playerID: hitter.id, chineseName: "更正打者", englishName: "Corrected", numberTexts: ["00", "0"]))
        XCTAssertTrue(store.saveLineup(draft))
        store.finishGame()
        let appearances = store.plateAppearanceRecords()
        let first = try XCTUnwrap(appearances.first { $0.batter.id == hitter.id })
        XCTAssertEqual(first.batter.name, "更正打者")
        XCTAssertEqual(store.battingLine(for: first.batter).hits, 1)
        let stats = store.seasonStatistics(for: store.currentTeam!, seasonID: store.seasons[0].id)
        XCTAssertEqual(stats.games.count, 1)
        let exporter = GameExportService(game: store.game, rules: store.activeRules, plateAppearances: appearances, gameEvents: store.nonPlateAppearanceGameEvents())
        let text = exporter.completeRecordText()
        XCTAssertTrue(text.contains("更正打者")); XCTAssertTrue(text.contains(originalEventText))
        XCTAssertTrue(try reportText(exporter.boxScorePDFData()).contains("更正打者"))
        let report = PlayByPlayPDFReport(game: store.game, appearances: appearances, playedAt: store.activeGameRecordedAt)
        XCTAssertTrue(try reportText(report.pdfData(style: .textOnly), portrait: true).contains("更正打者"))
        XCTAssertTrue(store.activeStoredGame?.statisticsParticipantIDs.contains(hitter.id) == true)
    }
}

extension GameStoreTests {
    func testV111BatterReturningWithinSameAppearanceKeepsLastBatterAndRecordedPitchOwners() throws {
        let store = freshStore()
        let first = store.currentBatter, substitute = try XCTUnwrap(store.battingBenchPlayers.first)
        let appearanceID = try XCTUnwrap(store.game.currentPlateAppearanceID)
        store.recordPitch(.ball)
        store.replaceCurrentBatter(with: substitute)
        store.recordPitch(.calledStrike)
        store.replaceCurrentBatter(with: first)
        XCTAssertEqual(store.game.balls, 1); XCTAssertEqual(store.game.strikes, 1)
        XCTAssertTrue(store.applyPlay(.single))
        let identity = try XCTUnwrap(store.game.plateAppearances?.first { $0.id == appearanceID })
        XCTAssertEqual(identity.batterIDs, [first.id, substitute.id, first.id])
        XCTAssertTrue(identity.completed)
        let appearance = try XCTUnwrap(store.plateAppearanceRecords().first { $0.id == appearanceID })
        XCTAssertEqual(appearance.batter.id, first.id)
        XCTAssertEqual(appearance.events.filter { $0.category == .pitch }.map(\.plateAppearanceBatterID), [first.id, substitute.id])
        XCTAssertEqual(store.battingLine(for: first).hits, 1)
        XCTAssertEqual(store.battingLine(for: substitute).plateAppearances, 0)
        XCTAssertNil(store.validateScoringTimeline())
        let target = GameStore(persistenceURL: nil)
        try target.restoreBackup(LocalBackup.read(store.exportBackup()))
        XCTAssertEqual(target.plateAppearanceRecords().first { $0.id == appearanceID }?.batter.id, first.id)
    }
}

extension GameStoreTests {
    func testV111PitcherBadgeSummarizesCurrentLimitsAndIdentifiesOtherPitchers() throws {
        let store = v111Store(rules: GameRules(pitchLimit: 6, pitchWarningRemaining: 3, pitcherInningsLimit: 2))
        let first = store.currentPitcher.id
        XCTAssertNil(store.ruleNoticeSummary())
        store.game.pitching[first] = PitchingLine(outsRecorded: 3)
        XCTAssertEqual(store.ruleNoticeSummary(), "最后一局")
        store.game.pitching[first]?.pitches = 3
        XCTAssertEqual(store.ruleNoticeSummary(), "还有 3 球")
        store.recordPitch(.ball)
        XCTAssertEqual(store.ruleNoticeSummary(), "还有 2 球")
        store.undo()
        XCTAssertEqual(store.ruleNoticeSummary(), "还有 3 球")
        store.game.pitching[first]?.pitches = 6
        XCTAssertEqual(store.ruleNoticeSummary(), "球数达限")
        store.game.pitching[first]?.pitches = 0
        store.game.pitching[first]?.outsRecorded = 6
        XCTAssertEqual(store.ruleNoticeSummary(), "局数达限")
        store.endCurrentHalf()
        XCTAssertNotEqual(store.currentPitcher.id, first)
        XCTAssertEqual(store.ruleNoticeSummary(), "其他达限")
        store.game.pitching[store.currentPitcher.id] = PitchingLine(pitches: 3)
        XCTAssertEqual(store.ruleNoticeSummary(), "还有 3 球")
        XCTAssertTrue(store.ruleNotices().contains { $0.contains("达到 2 局投球限制") })
    }
}
