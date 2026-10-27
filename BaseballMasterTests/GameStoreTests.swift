import XCTest
import CoreData
import PDFKit
import SQLite3
import CryptoKit
import Security
@testable import BaseballMaster

private struct LiveReadEnvelope: Decodable { var snapshot: LiveSnapshot }

extension GameStoreTests {
    func testV21AppearanceNumbersAreSeparateForEachTeamAndContinueAcrossInnings() throws {
        let store = freshStore()
        for _ in 0..<18 { XCTAssertTrue(store.applyPlay(.groundOut)) }
        XCTAssertEqual(store.game.inning, 4); XCTAssertTrue(store.game.isTop)
        let saved = store.game
        let records = store.plateAppearanceRecords()
        XCTAssertEqual(records.filter(\.isTop).map(\.sequence), Array(1...10))
        XCTAssertEqual(records.filter { !$0.isTop }.map(\.sequence), Array(1...9))
        XCTAssertEqual(records[3].sequenceLabel, "主队第 1 打席")
        XCTAssertEqual(records[6].sequenceLabel, "客队第 4 打席")
        XCTAssertEqual(records.last?.sequenceLabel, "客队第 10 打席")
        XCTAssertEqual(store.currentBattingOrder, 1, "Team appearance numbers keep increasing when batting order wraps")
        XCTAssertEqual(Set(records.map(\.accessibilityKey)).count, records.count)
        XCTAssertEqual(records.map(\.id), store.game.plateAppearances?.map(\.id))
        XCTAssertEqual(store.game, saved, "Display numbering must not rewrite saved events or PA/AB statistics")
    }

    func testV21TeamAppearanceNumbersPreserveIdentityThroughReentryUndoAndBackup() throws {
        let store = v111Store(rules: GameRules())
        for _ in 0..<3 { XCTAssertTrue(store.applyPlay(.groundOut)) }
        let batter = store.currentBatter
        let substitute = try XCTUnwrap(store.battingBenchPlayers.first)
        let homeID = try XCTUnwrap(store.game.currentPlateAppearanceID)
        store.recordPitch(.ball)
        store.replaceCurrentBatter(with: substitute); store.replaceCurrentBatter(with: batter)
        XCTAssertEqual(store.plateAppearanceRecords().last?.id, homeID)
        XCTAssertEqual(store.plateAppearanceRecords().last?.sequenceLabel, "主队第 1 打席")
        XCTAssertTrue(store.applyPlay(.single))
        let afterHit = store.plateAppearanceRecords()
        XCTAssertEqual(afterHit.filter { !$0.isTop }.map(\.sequence), [1, 2])
        store.undo()
        XCTAssertEqual(store.plateAppearanceRecords().last?.id, homeID)
        XCTAssertEqual(store.plateAppearanceRecords().last?.sequence, 1)
        store.redo()
        XCTAssertEqual(store.plateAppearanceRecords(), afterHit)
        let restored = GameStore(persistenceURL: nil)
        try restored.restoreBackup(LocalBackup.read(store.exportBackup()))
        XCTAssertEqual(restored.plateAppearanceRecords(), afterHit)
        XCTAssertEqual(restored.game.batting, store.game.batting)
    }

    func testV21TeamAppearanceNumbersMatchTextAndPDFWithoutConfusingSingleExports() throws {
        let store = freshStore()
        for _ in 0..<3 { XCTAssertTrue(store.applyPlay(.groundOut)) }
        store.recordPitch(.ball)
        let appearances = store.plateAppearanceRecords()
        let away = try XCTUnwrap(appearances.first), home = try XCTUnwrap(appearances.last)
        XCTAssertEqual(away.sequence, 1); XCTAssertEqual(home.sequence, 1); XCTAssertNotEqual(away.id, home.id)
        let exporter = GameExportService(game: store.game, rules: store.activeRules, plateAppearances: appearances, gameEvents: store.nonPlateAppearanceGameEvents())
        let text = exporter.completeRecordText()
        XCTAssertTrue(text.contains("第1局上，客队第 1 打席"))
        XCTAssertTrue(text.contains("第1局下，主队第 1 打席"))
        let report = PlayByPlayPDFReport(game: store.game, appearances: appearances, playedAt: nil)
        for style in [PlayByPlayPDFStyle.detailed, .textOnly] {
            let full = try reportText(report.pdfData(style: style), portrait: style == .textOnly)
            XCTAssertTrue(full.contains("客队第 1 打席")); XCTAssertTrue(full.contains("主队第 1 打席"))
            XCTAssertFalse(full.contains("第 4 打席"))
            let homeOnly = try reportText(report.pdfData(appearanceID: home.id, style: style), portrait: style == .textOnly)
            XCTAssertTrue(homeOnly.contains("主队第 1 打席")); XCTAssertFalse(homeOnly.contains("客队第 1 打席"))
            let awayOnly = try reportText(report.pdfData(appearanceID: away.id, style: style), portrait: style == .textOnly)
            XCTAssertTrue(awayOnly.contains("客队第 1 打席")); XCTAssertFalse(awayOnly.contains("主队第 1 打席"))
        }
    }
}

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
        let first = try XCTUnwrap(text.range(of: "客队第 1 打席"))
        let last = try XCTUnwrap(text.range(of: "主队第 1 打席"))
        XCTAssertTrue(text.contains("客队第 5 打席"))
        XCTAssertFalse(text.contains("第 6 打席"))
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
        for _ in 0..<240 { store.recordPitch(.foul) }
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
        store.startGameClock()
        XCTAssertEqual(store.ruleNoticeSummary(), "最后登板局")
        XCTAssertEqual(store.pitchingLine(for: pitcher).inningsText, "0.0")
        store.endCurrentHalf()
        XCTAssertTrue(store.ruleNotices().contains(where: { $0.contains("达到 1 局登板额度") }))
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

        let originalPitcher = store.currentPitcher
        let newPitcher = store.game.fieldingTeam.players[1]
        store.changePitcher(to: newPitcher)
        XCTAssertTrue(store.applyPlay(.homeRun))
        XCTAssertEqual(store.pitchingLine(for: newPitcher).runs, 1)
        XCTAssertEqual(store.pitchingLine(for: originalPitcher).runs, 2)
        XCTAssertEqual(store.pitchingLine(for: originalPitcher).earnedRuns, 0)
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
        XCTAssertTrue(appearance.chineseRecord.contains("界外（"))
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
        XCTAssertTrue(text.contains("好球 - 看"))
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
        excluded.playedAt = Calendar(identifier: .gregorian).date(byAdding: .year, value: -1, to: Date())
        excluded.state.awayTeam.name = "不应导出的其他年度对手"
        store.games = [included, excluded]
        let report = TeamSeasonPDFReport(store: store, team: store.currentTeam!, seasonID: store.seasons[0].id)
        let text = try reportText(report.pdfData())
        XCTAssertTrue(text.contains("ANNUAL REPORT"))
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
        let year = Int((seasonID ?? MatchYear.current).prefix(4)) ?? Int(MatchYear.current)!
        let date = Calendar(identifier: .gregorian).date(from: DateComponents(year: year, month: 6, day: 1))!
        return StoredGame(
            playedAt: date, seasonID: seasonID ?? store.seasons[0].id, ourTeamID: ours.id,
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
            statisticsGame(store: store, seasonID: String(Int(MatchYear.current)! - 1)),
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
        XCTAssertEqual(store.statisticsSeasons.last?.id, "2024")
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
    func testV30NewPitchWordingFlowsToLiveAndExportsWithoutChangingOldRecords() throws {
        let store = v11Store()
        for action in [PitchAction.ball, .calledStrike, .swingingStrike, .foul] { store.recordPitch(action) }
        let modern = try XCTUnwrap(store.activeStoredGame)
        let labels = ["坏球", "好球 - 看", "好球 - 挥空", "界外"]
        let pitches = try XCTUnwrap(modern.state.scoringEvents).filter { $0.category == .pitch }
        XCTAssertEqual(pitches.count, 4)
        for (event, label) in zip(pitches, labels) { XCTAssertTrue(event.title.contains("：\(label)（")) }

        var legacy = modern
        let oldLabels = [("好球 - 看", "看振"), ("好球 - 挥空", "挥空"), ("界外", "界外球")]
        for (current, old) in oldLabels {
            for i in legacy.state.scoringEvents!.indices {
                legacy.state.scoringEvents![i].title = legacy.state.scoringEvents![i].title
                    .replacingOccurrences(of: "：\(current)（", with: "：\(old)（")
            }
            for i in legacy.state.playLog.indices {
                legacy.state.playLog[i].text = legacy.state.playLog[i].text
                    .replacingOccurrences(of: "：\(current)（", with: "：\(old)（")
            }
        }
        let preserved = legacy
        for (saved, expectedLabels) in [(modern, labels), (legacy, ["坏球", "看振", "挥空", "界外球"])] {
            store.game = saved.state
            let appearances = store.plateAppearanceRecords()
            let record = appearances.map(\.chineseRecord).joined()
            let exporter = GameExportService(game: saved.state, rules: saved.rules,
                                            plateAppearances: appearances, gameEvents: store.nonPlateAppearanceGameEvents())
            let projection = LiveSnapshot(saved)
            let live = projection.entries.map { $0.summary + $0.details.map(\.text).joined() }.joined()
            let report = PlayByPlayPDFReport(game: saved.state, appearances: appearances, playedAt: nil)
            let detailed = try reportText(report.pdfData())
            let plain = try reportText(report.pdfData(style: .textOnly), portrait: true)
            for output in [record, exporter.completeRecordText(), live, detailed, plain] {
                let compact = output.filter { !$0.isWhitespace }
                for label in expectedLabels { XCTAssertTrue(compact.contains(label.filter { !$0.isWhitespace }), label) }
                if saved == legacy { XCTAssertFalse(output.contains("好球 -")) }
                else { XCTAssertFalse(output.contains("看振")); XCTAssertFalse(output.contains("界外球（")) }
            }
            XCTAssertEqual(store.game, saved.state, "Rendering must not rewrite saved records or statistics")
        }
        XCTAssertEqual(legacy, preserved)
        XCTAssertEqual(try JSONDecoder().decode(StoredGame.self, from: JSONEncoder().encode(legacy)), legacy)
        XCTAssertEqual(try JSONDecoder().decode(PitchAction.self, from: Data("\"看振\"".utf8)), .calledStrike)
        XCTAssertEqual(try JSONDecoder().decode(PitchAction.self, from: Data("\"挥空\"".utf8)), .swingingStrike)

        legacy.state.scoringEvents = nil; legacy.state.plateAppearances = nil
        let oldLive = LiveSnapshot(legacy).entries.map(\.summary).joined()
        XCTAssertTrue(oldLive.contains("看振")); XCTAssertTrue(oldLive.contains("挥空"))
        XCTAssertFalse(oldLive.contains("好球 -"))
    }

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
        let base = URL(string: "http://127.0.0.1:18088/livestreaming/novideo")!
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

// 2.1: exercise actual saved App actions through HTTP, including automatic publish triggers.
extension GameStoreTests {
    private func startV21Live(_ store: GameStore) async throws {
        let base = URL(string: "http://127.0.0.1:18088/livestreaming/novideo")!
        do { _ = try await URLSession.shared.data(from: base.appendingPathComponent("health")) }
        catch { throw XCTSkip("Run the local integration server on port 18088 for live regression") }
        store.liveBroadcasts = LiveBroadcastManager(store: store, vault: LiveTestVault(), transport: LiveHTTPClient(baseURL: base), enabled: true, viewBaseURL: base)
        let id = try XCTUnwrap(store.activeStoredGame?.id)
        store.liveBroadcasts.start(id)
        await store.liveBroadcasts.waitForPendingSync()
        let code = try XCTUnwrap(store.liveBroadcasts.binding(for: id)?.code, store.liveBroadcasts.messages[id] ?? "")
        XCTAssertEqual(store.liveBroadcasts.url(for: id)?.path, "/livestreaming/novideo/\(code)")
    }

    @discardableResult
    private func readV21Live(_ store: GameStore, file: StaticString = #filePath, line: UInt = #line) async throws -> LiveSnapshot {
        await store.liveBroadcasts.waitForPendingSync()
        let saved = try XCTUnwrap(store.activeStoredGame, file: file, line: line)
        let binding = try XCTUnwrap(store.liveBroadcasts.binding(for: saved.id), file: file, line: line)
        XCTAssertEqual(binding.ackRevision, saved.revision, store.liveBroadcasts.messages[saved.id] ?? "", file: file, line: line)
        let code = try XCTUnwrap(binding.code, file: file, line: line)
        let url = URL(string: "http://127.0.0.1:18088/livestreaming/novideo/api/sessions/\(code)")!
        let (data, response) = try await URLSession.shared.data(from: url)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200, file: file, line: line)
        let published = try JSONDecoder().decode(LiveReadEnvelope.self, from: data).snapshot
        XCTAssertEqual(published, LiveSnapshot(saved), file: file, line: line)
        XCTAssertEqual(Set(published.entries.map(\.id)).count, published.entries.count, file: file, line: line)
        return published
    }

    private func closeV21Live(_ store: GameStore) async throws {
        store.liveBroadcasts.close(try XCTUnwrap(store.activeStoredGame?.id))
        await store.liveBroadcasts.waitForPendingSync()
        XCTAssertTrue(store.liveBroadcasts.bindings.isEmpty)
    }

    func testV21LiveBothLineupsReorderAndDefenseChangePublishCurrentOrder() async throws {
        for mode in [GameMode.standard, .coachPitch] {
            let store = v11Store(mode: mode)
            try await startV21Live(store)
            let first = try await readV21Live(store)
            XCTAssertEqual(first.batterOrder, 1)
            store.recordPitch(.ball); store.recordPitch(.calledStrike)
            let batter = store.currentBatter.id, appearance = store.game.currentPlateAppearanceID
            var attack = store.lineupDraft(forHomeTeam: false)
            attack.moveBatter(from: 0, to: 3)
            let next = attack.battingOrderIDs[4]
            XCTAssertTrue(store.saveLineup(attack), store.actionErrorMessage ?? "")
            let reordered = try await readV21Live(store)
            XCTAssertEqual(reordered.batterOrder, 4); XCTAssertEqual(reordered.batter?.id, batter.uuidString)
            XCTAssertEqual(reordered.currentAppearanceID, appearance?.uuidString)
            XCTAssertEqual(reordered.balls, mode == .coachPitch ? 0 : 1); XCTAssertEqual(reordered.strikes, 1)
            XCTAssertEqual(reordered.appearancePitchCount, 2)
            XCTAssertGreaterThan(reordered.revision, first.revision)
            let opposingBatter = store.game.homeBattingOrderIDs[store.game.homeBatterIndex]
            var defense = store.lineupDraft(forHomeTeam: true)
            defense.moveBatter(from: 0, to: 5)
            XCTAssertTrue(store.saveLineup(defense), store.actionErrorMessage ?? "")
            let fielder = try XCTUnwrap(store.activeFielders.first { $0.primaryPosition == .shortstop })
            store.changeFieldingPosition(for: store.currentPitcher, to: .shortstop)
            let changed = try await readV21Live(store)
            XCTAssertEqual(changed.pitcher?.id, fielder.id.uuidString)
            XCTAssertEqual(changed.pitchCount, mode == .coachPitch ? nil : 0)
            XCTAssertEqual(changed.batterOrder, 4)
            XCTAssertTrue(store.applyPlay(.groundOut))
            let advanced = try await readV21Live(store)
            XCTAssertEqual(advanced.batter?.id, next.uuidString); XCTAssertEqual(advanced.batterOrder, 5)
            store.endCurrentHalf()
            let switched = try await readV21Live(store)
            XCTAssertFalse(switched.isTop); XCTAssertEqual(switched.batter?.id, opposingBatter.uuidString)
            XCTAssertEqual(switched.batterOrder, 6)
            try await closeV21Live(store)
        }
    }

    func testV21LiveRepeatedBatterAndPitcherReentryKeepsIDsCountsAndUndo() async throws {
        let store = v111Store(rules: GameRules())
        try await startV21Live(store)
        let batter = store.currentBatter, pitcher = store.currentPitcher
        let substitute = try XCTUnwrap(store.battingBenchPlayers.first)
        let relief = try XCTUnwrap(store.fieldingBenchPlayers.first)
        let appearance = try XCTUnwrap(store.game.currentPlateAppearanceID).uuidString
        store.recordPitch(.ball)
        store.replaceCurrentBatter(with: substitute); store.changePitcher(to: relief)
        store.recordPitch(.calledStrike)
        let replaced = try await readV21Live(store)
        XCTAssertEqual(replaced.batter?.id, substitute.id.uuidString); XCTAssertEqual(replaced.batterOrder, 1)
        XCTAssertEqual(replaced.pitcher?.id, relief.id.uuidString); XCTAssertEqual(replaced.pitchCount, 1)
        XCTAssertEqual(replaced.entries.first { $0.id == appearance }?.player?.id, substitute.id.uuidString)
        store.replaceCurrentBatter(with: batter); store.changePitcher(to: pitcher)
        store.recordPitch(.foul)
        let returned = try await readV21Live(store)
        XCTAssertEqual(returned.batter?.id, batter.id.uuidString); XCTAssertEqual(returned.batterOrder, 1)
        XCTAssertEqual(returned.pitcher?.id, pitcher.id.uuidString); XCTAssertEqual(returned.pitchCount, 2)
        XCTAssertEqual(returned.balls, 1); XCTAssertEqual(returned.strikes, 2)
        XCTAssertEqual(returned.currentAppearanceID, appearance)
        XCTAssertEqual(returned.entries.first { $0.id == appearance }?.player?.id, batter.id.uuidString)
        XCTAssertTrue(store.applyPlay(.single))
        let hit = try await readV21Live(store)
        XCTAssertEqual(hit.batterOrder, 2); XCTAssertEqual(hit.away.hits, 1)
        XCTAssertEqual(hit.bases.first?.player.id, batter.id.uuidString)
        XCTAssertEqual(hit.entries.first { $0.id == appearance }?.status, "completed")
        store.undo()
        let undone = try await readV21Live(store)
        XCTAssertEqual(undone.batterOrder, 1); XCTAssertEqual(undone.currentAppearanceID, appearance)
        XCTAssertEqual(undone.away.hits, 0); XCTAssertTrue(undone.bases.isEmpty)
        XCTAssertEqual(undone.entries.first { $0.id == appearance }?.details, returned.entries.first { $0.id == appearance }?.details)
        XCTAssertGreaterThan(undone.revision, hit.revision)
        store.redo()
        let redone = try await readV21Live(store)
        XCTAssertEqual(redone.entries, hit.entries); XCTAssertEqual(redone.batterOrder, 2)
        try await closeV21Live(store)
    }

    func testV21LiveProfileCorrectionsUpdatePeopleButRetainRecordedHistory() async throws {
        for isHome in [false, true] {
            let store = freshStore()
            if isHome { store.endCurrentHalf() }
            let runner = store.currentBatter
            XCTAssertTrue(store.applyPlay(.single))
            try await startV21Live(store)
            let before = try await readV21Live(store)
            let historical = try XCTUnwrap(before.entries.first { $0.player?.id == runner.id.uuidString && $0.kind == "appearance" })
            let batter = store.currentBatter, pitcher = store.currentPitcher
            var offense = store.lineupDraft(forHomeTeam: isHome)
            XCTAssertTrue(offense.updateProfile(playerID: runner.id, chineseName: "更正跑者", englishName: "", numberTexts: ["00", "0", "87"]))
            XCTAssertTrue(offense.updateProfile(playerID: batter.id, chineseName: "更正打者", englishName: "", numberTexts: ["99"]))
            XCTAssertTrue(store.saveLineup(offense), store.actionErrorMessage ?? "")
            let corrected = try await readV21Live(store)
            XCTAssertEqual(corrected.batter?.name, "更正打者"); XCTAssertEqual(corrected.batter?.number, "99")
            XCTAssertEqual(corrected.bases.first?.player.name, "更正跑者")
            XCTAssertEqual(corrected.bases.first?.player.number, store.game.baseRunners[.first]?.numberText)
            let updatedHistory = try XCTUnwrap(corrected.entries.first { $0.id == historical.id })
            XCTAssertEqual(updatedHistory.player?.name, "更正跑者")
            XCTAssertEqual(updatedHistory.details, historical.details); XCTAssertEqual(updatedHistory.summary, historical.summary)
            for (afterSide, beforeSide) in [(corrected.away, before.away), (corrected.home, before.home)] {
                XCTAssertEqual(afterSide.runs, beforeSide.runs); XCTAssertEqual(afterSide.hits, beforeSide.hits)
                XCTAssertEqual(afterSide.errors, beforeSide.errors); XCTAssertEqual(afterSide.innings, beforeSide.innings)
                XCTAssertEqual(afterSide.playedInnings, beforeSide.playedInnings)
                XCTAssertEqual(afterSide.lineup?.map { $0.player.id }, beforeSide.lineup?.map { $0.player.id })
            }
            let correctedRoster = isHome ? corrected.home.lineup : corrected.away.lineup
            XCTAssertEqual(correctedRoster?.first { $0.player.id == runner.id.uuidString }?.player.name, "更正跑者")
            XCTAssertEqual(correctedRoster?.first { $0.player.id == batter.id.uuidString }?.player.name, "更正打者")
            XCTAssertEqual(corrected.batterOrder, before.batterOrder)
            store.undo()
            let undone = try await readV21Live(store)
            XCTAssertEqual(undone.batter, before.batter); XCTAssertEqual(undone.bases, before.bases)
            store.redo()
            let redone = try await readV21Live(store)
            XCTAssertEqual(redone.entries, corrected.entries); XCTAssertEqual(redone.bases, corrected.bases)
            var defense = store.lineupDraft(forHomeTeam: !isHome)
            XCTAssertTrue(defense.updateProfile(playerID: pitcher.id, chineseName: "更正投手", englishName: "", numberTexts: ["00"]))
            XCTAssertTrue(store.saveLineup(defense), store.actionErrorMessage ?? "")
            let pitcherCorrected = try await readV21Live(store)
            XCTAssertEqual(pitcherCorrected.pitcher?.id, pitcher.id.uuidString)
            XCTAssertEqual(pitcherCorrected.pitcher?.name, "更正投手"); XCTAssertEqual(pitcherCorrected.pitcher?.number, "00")
            try await closeV21Live(store)
        }
    }

    func testV21LiveThirdOutFoulBuntSwitchesBothModesAndFinishClearsOrder() async throws {
        for mode in [GameMode.standard, .coachPitch] {
            let store = v11Store(mode: mode)
            XCTAssertTrue(store.applyPlay(.groundOut)); XCTAssertTrue(store.applyPlay(.groundOut))
            try await startV21Live(store)
            let batter = store.currentBatter.id
            let appearance = try XCTUnwrap(store.game.currentPlateAppearanceID).uuidString
            store.recordPitch(.foul); store.recordPitch(.foul); store.recordPitch(.foul)
            let before = try await readV21Live(store)
            XCTAssertEqual(before.outs, 2); XCTAssertEqual(before.strikes, 2); XCTAssertEqual(before.batterOrder, 3)
            store.recordFoulBuntStrikeout()
            let switched = try await readV21Live(store)
            XCTAssertFalse(switched.isTop); XCTAssertEqual(switched.outs, 0)
            XCTAssertEqual(switched.balls, 0); XCTAssertEqual(switched.strikes, 0); XCTAssertEqual(switched.batterOrder, 1)
            XCTAssertNotEqual(switched.batter?.id, batter.uuidString)
            let completed = try XCTUnwrap(switched.entries.first { $0.id == appearance })
            XCTAssertEqual(completed.status, "completed"); XCTAssertTrue(completed.summary.contains("触击"))
            XCTAssertEqual(switched.pitchCount, mode == .coachPitch ? nil : 0)
            store.undo()
            let undone = try await readV21Live(store)
            XCTAssertTrue(undone.isTop); XCTAssertEqual(undone.outs, 2); XCTAssertEqual(undone.batterOrder, 3)
            XCTAssertEqual(undone.entries, before.entries); XCTAssertEqual(undone.pitchCount, before.pitchCount)
            store.redo()
            let redone = try await readV21Live(store)
            XCTAssertEqual(redone.entries, switched.entries); XCTAssertEqual(redone.batterOrder, 1)
            store.finishGame()
            let final = try await readV21Live(store)
            XCTAssertTrue(final.isFinal); XCTAssertNil(final.batterOrder)
            let object = try XCTUnwrap(JSONSerialization.jsonObject(with: final.data()) as? [String: Any])
            XCTAssertTrue(object["batterOrder"] is NSNull)
            var legacy = object; legacy.removeValue(forKey: "batterOrder")
            XCTAssertNil(try JSONDecoder().decode(LiveSnapshot.self, from: JSONSerialization.data(withJSONObject: legacy)).batterOrder)
            try await closeV21Live(store)
        }
    }

    func testV21LiveFailedSaveNeverPublishesAndRestartSendsLatestCorrection() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("V21Live-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let url = root.appendingPathComponent("BaseballMaster.sqlite")
        let store = v11Store(url: url), vault = LiveTestVault(), network = LiveTestTransport()
        store.liveBroadcasts = LiveBroadcastManager(store: store, vault: vault, transport: network, enabled: true)
        store.startGameClock()
        let id = try XCTUnwrap(store.activeStoredGame?.id)
        store.liveBroadcasts.start(id); await store.liveBroadcasts.waitForPendingSync()
        let calls = network.calls.count, revision = store.activeStoredGame?.revision
        var draft = store.lineupDraft(forHomeTeam: false)
        draft.moveBatter(from: 0, to: 4)
        XCTAssertTrue(draft.updateProfile(playerID: store.currentBatter.id, chineseName: "重启后更正", englishName: "", numberTexts: ["00"]))
        CoreDataRosterStore.writeFault = { throw CocoaError(.fileWriteOutOfSpace) }
        defer { CoreDataRosterStore.writeFault = nil }
        XCTAssertFalse(store.saveLineup(draft))
        await store.liveBroadcasts.waitForPendingSync()
        XCTAssertEqual(network.calls.count, calls); XCTAssertEqual(store.activeStoredGame?.revision, revision)
        CoreDataRosterStore.writeFault = nil
        network.failure = URLError(.networkConnectionLost)
        XCTAssertTrue(store.saveLineup(draft), store.actionErrorMessage ?? "")
        await store.liveBroadcasts.waitForPendingSync()
        XCTAssertEqual(vault.bindings.first?.ackRevision, revision)
        let reopened = GameStore(persistenceURL: url)
        reopened.liveBroadcasts = LiveBroadcastManager(store: reopened, vault: vault, transport: network, enabled: true)
        reopened.liveBroadcasts.requestSync(); await reopened.liveBroadcasts.waitForPendingSync()
        let payload = try JSONDecoder().decode(LiveSnapshot.self, from: XCTUnwrap(network.calls.last { $0.method == "PUT" }?.body))
        XCTAssertEqual(payload.batterOrder, 5); XCTAssertEqual(payload.batter?.name, "重启后更正")
        XCTAssertEqual(payload.batter?.number, "00")
        XCTAssertEqual(payload, LiveSnapshot(try XCTUnwrap(reopened.activeStoredGame)))
        XCTAssertEqual(reopened.liveBroadcasts.binding(for: id)?.ackRevision, reopened.activeStoredGame?.revision)
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
    func testDisabledLiveDoesNotReadCredentialsOrSendEvenAfterRestoreAndForeground() async throws {
        let store = v11Store(); let vault = LiveTestVault(); let network = LiveTestTransport()
        let id = try XCTUnwrap(store.activeStoredGame?.id)
        let saved = LiveBinding(gameID: id, token: "synthetic-test-token", code: "1234567890ABCDEF12345678", expiresAt: Date().addingTimeInterval(3600))
        vault.bindings = [saved]
        let manager = LiveBroadcastManager(store: store, vault: vault, transport: network, enabled: false)
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
        XCTAssertTrue(store.ruleNotices().contains { $0.contains(pitcher.name) && $0.contains("达到 1 局登板额度") })
        store.undo()
        XCTAssertFalse(store.ruleNotices().contains { $0.contains("达到 3 球限制") })
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: pitcher.id), [1])
        store.redo()
        XCTAssertTrue(store.ruleNotices().contains { $0.contains(pitcher.name) && $0.contains("已达到") })
        let other = store.currentPitcher
        for _ in 0..<3 { store.applyPlay(.groundOut) }
        XCTAssertTrue(store.ruleNotices().contains { $0.contains(other.name) && $0.contains("已达到") })
        XCTAssertTrue(store.ruleNotices().contains { $0.contains(pitcher.name) && $0.contains("超过 1 局限制") })
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
        store.startGameClock()
        store.endCurrentHalf(); store.endCurrentHalf()
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: first), [1, 2])
        XCTAssertEqual(store.ruleNoticeSummary(), "最后登板局")
        store.game.pitching[first] = PitchingLine(pitches: 3)
        XCTAssertEqual(store.ruleNoticeSummary(), "还有 3 球")
        store.recordPitch(.ball)
        XCTAssertEqual(store.ruleNoticeSummary(), "还有 2 球")
        store.undo()
        XCTAssertEqual(store.ruleNoticeSummary(), "还有 3 球")
        store.game.pitching[first]?.pitches = 6
        XCTAssertEqual(store.ruleNoticeSummary(), "球数达限")
        store.game.pitching[first]?.pitches = 0
        store.game.pitching[first]?.outsRecorded = 6
        XCTAssertEqual(store.ruleNoticeSummary(), "最后登板局", "Statistical outs do not determine the appearance limit")
        store.endCurrentHalf()
        XCTAssertNotEqual(store.currentPitcher.id, first)
        XCTAssertEqual(store.ruleNoticeSummary(), "其他达限")
        store.game.pitching[store.currentPitcher.id] = PitchingLine(pitches: 3)
        XCTAssertEqual(store.ruleNoticeSummary(), "还有 3 球")
        XCTAssertTrue(store.ruleNotices().contains { $0.contains("达到 2 局登板额度") })
    }
}

extension GameStoreTests {
    func testHistoryNoEditReplayPreservesScoresStatsAndIDs() throws {
        let store = freshStore()
        store.recordPitch(.ball); store.recordPitch(.calledStrike)
        XCTAssertTrue(store.applyPlay(.single))
        XCTAssertTrue(store.applyPlay(.double))
        for _ in 0..<3 { XCTAssertTrue(store.applyPlay(.groundOut, defensivePlay: DefensivePlay.quickPlays.first)) }
        let draft = try store.makeCorrectionDraft()
        let preview = store.previewCorrection(draft)
        XCTAssertNil(preview.conflict)
        XCTAssertTrue(preview.game.historyScoreEquivalent(to: store.game))
        XCTAssertEqual(preview.game.scoringEvents?.map(\.id), store.game.scoringEvents?.map(\.id))
        XCTAssertEqual(preview.game.plateAppearances?.map(\.id), store.game.plateAppearances?.map(\.id))
        XCTAssertEqual(preview.game.currentPlateAppearanceID, store.game.currentPlateAppearanceID)
        XCTAssertEqual(store.previewCorrection(draft).game, preview.game, "Preview must be deterministic")
    }

    func testHistoryMissedPitcherPreservesInheritedRunnerAndSaveRevert() throws {
        let store = freshStore()
        let firstPitcher = store.currentPitcher
        XCTAssertTrue(store.applyPlay(.single))
        let boundary = store.game.historyJournal!.operations.count
        let before = store.game
        let newPitcher = try XCTUnwrap(store.availablePitchers.first { $0.id != firstPitcher.id })
        store.recordPitch(.ball)
        XCTAssertTrue(store.applyPlay(.homeRun))
        let original = store.game
        var draft = try store.makeCorrectionDraft()
        draft.journal.operations.insert(HistoryOperation(command: .pitcher(newPitcher.id), before: before, inserted: true), at: boundary)
        let preview = store.previewCorrection(draft)
        XCTAssertNil(preview.conflict)
        XCTAssertEqual(store.game, original, "Preview cannot mutate official scoring")
        XCTAssertEqual(preview.game.pitching[firstPitcher.id]?.pitches, 1)
        XCTAssertEqual(preview.game.pitching[newPitcher.id]?.pitches, 2)
        XCTAssertEqual(preview.game.pitching[firstPitcher.id]?.runs, 1)
        XCTAssertEqual(preview.game.pitching[newPitcher.id]?.runs, 1)
        XCTAssertEqual(preview.game.awayScore, 2)
        XCTAssertTrue(store.saveCorrection(draft), store.actionErrorMessage ?? "")
        store.recordPitch(.calledStrike)
        let revision = try XCTUnwrap(store.game.correctionRevisions?.last)
        let revert = try store.draftRevertingCorrection(revision)
        XCTAssertTrue(store.saveCorrection(revert), store.actionErrorMessage ?? "")
        XCTAssertEqual(store.game.strikes, 1, "Reverting correction preserves subsequent pitches")
        XCTAssertEqual(store.game.pitching[firstPitcher.id]?.pitches, 4)
        XCTAssertEqual(store.game.correctionRevisions?.count, 2)
    }

    func testHistoryPositionsReassignOnlyLaterDefenseWithoutBattingChanges() throws {
        let store = freshStore()
        let shortstop = try XCTUnwrap(store.activeFielders.first { $0.primaryPosition == .shortstop })
        let second = try XCTUnwrap(store.activeFielders.first { $0.primaryPosition == .secondBase })
        XCTAssertTrue(store.applyPlay(.error, defensivePlay: .error(at: .shortstop)))
        let before = store.game, boundary = store.game.historyJournal!.operations.count
        XCTAssertTrue(store.applyPlay(.error, defensivePlay: .error(at: .shortstop)))
        var draft = try store.makeCorrectionDraft()
        draft.journal.operations.insert(HistoryOperation(command: .position(shortstop.id, .secondBase), before: before, inserted: true), at: boundary)
        let preview = store.previewCorrection(draft)
        XCTAssertNil(preview.conflict)
        XCTAssertEqual(preview.game.fielding[shortstop.id]?.errors, 1)
        XCTAssertEqual(preview.game.fielding[second.id]?.errors, 1)
        XCTAssertEqual(preview.game.homeBattingOrderIDs, store.game.homeBattingOrderIDs)
        XCTAssertEqual(preview.game.homeExitedPlayerIDs, store.game.homeExitedPlayerIDs)
    }

    func testHistoryHitToErrorAndAdditionalErrorsCanBeRevertedExactly() throws {
        let store = freshStore()
        XCTAssertTrue(store.applyPlay(.single))
        let batter = store.game.baseRunners[.first]!
        let original = store.game
        var draft = try store.makeCorrectionDraft()
        let i = try XCTUnwrap(draft.journal.operations.firstIndex { if case .play = $0.command { return true }; return false })
        guard case .play(_, _, let moves, _) = draft.journal.operations[i].command else { return XCTFail() }
        draft.journal.operations[i].command = .play(.error, .error(at: .shortstop), moves, nil)
        draft.journal.operations[i].edited = true
        let preview = store.previewCorrection(draft)
        XCTAssertNil(preview.conflict)
        XCTAssertEqual(preview.game.awayHits, 0); XCTAssertEqual(preview.game.homeErrors, 1)
        XCTAssertEqual(preview.game.batting[batter.id]?.plateAppearances, 1)
        XCTAssertEqual(preview.game.pitching[store.currentPitcher.id]?.pitches, 1)
        XCTAssertTrue(store.saveCorrection(draft), store.actionErrorMessage ?? "")
        XCTAssertTrue(store.saveCorrection(try store.draftRevertingCorrection(store.game.correctionRevisions!.last!)))
        XCTAssertTrue(store.game.historyScoreEquivalent(to: original))
        var extra = try store.makeCorrectionDraft()
        extra.journal.operations[i].errors = [HistoryErrorCredit(playerID: store.activeFielders[1].id), HistoryErrorCredit(playerID: store.activeFielders[2].id, count: 2)]
        extra.journal.operations[i].edited = true
        XCTAssertTrue(store.saveCorrection(extra), store.actionErrorMessage ?? "")
        XCTAssertEqual(store.game.awayHits, 1); XCTAssertEqual(store.game.homeErrors, 3)
        XCTAssertEqual(store.game.batting[batter.id]?.plateAppearances, 1)
    }

    func testHistoryExtraBallConflictIsLocalizedAndCanBeRepairedInDraft() throws {
        let store = freshStore()
        for _ in 0..<3 { store.recordPitch(.ball) }
        store.recordPitch(.calledStrike)
        var draft = try store.makeCorrectionDraft()
        let first = try XCTUnwrap(store.previewCorrection(draft).statesBefore[draft.journal.operations[0].id])
        draft.journal.operations.insert(HistoryOperation(command: .pitch(.ball), before: first, inserted: true), at: 0)
        let conflict = store.previewCorrection(draft)
        XCTAssertNotNil(conflict.conflictID)
        XCTAssertFalse(store.saveCorrection(draft))
        let i = try XCTUnwrap(draft.journal.operations.firstIndex { $0.id == conflict.conflictID })
        draft.journal.operations[i].acceptsNewContext = true
        XCTAssertNil(store.previewCorrection(draft).conflict)
        XCTAssertTrue(store.saveCorrection(draft), store.actionErrorMessage ?? "")
        XCTAssertEqual(store.game.balls, 0); XCTAssertEqual(store.game.strikes, 1)
        XCTAssertEqual(store.game.baseRunners.count, 1)
    }

    func testHistoryFinishedGameStaysFinishedBackupAndStaleDraft() throws {
        let store = freshStore()
        XCTAssertTrue(store.applyPlay(.single)); store.finishGame()
        let endedAt = store.game.endedAt
        var draft = try store.makeCorrectionDraft()
        let i = try XCTUnwrap(draft.journal.operations.firstIndex { if case .play = $0.command { return true }; return false })
        draft.journal.operations[i].errors = [HistoryErrorCredit(playerID: store.activeFielders[1].id)]
        draft.journal.operations[i].edited = true
        XCTAssertTrue(store.saveCorrection(draft), store.actionErrorMessage ?? "")
        XCTAssertTrue(store.game.isFinal); XCTAssertEqual(store.game.endedAt, endedAt); XCTAssertNil(store.game.clockRunningSince)
        XCTAssertFalse(store.saveCorrection(draft), "Old revision cannot overwrite new scoring")
        let restored = GameStore(persistenceURL: nil)
        try restored.restoreBackup(LocalBackup.read(store.exportBackup()))
        restored.openGame(id: store.activeStoredGame!.id)
        XCTAssertEqual(restored.game, store.game)
        XCTAssertNil(restored.previewCorrection(try restored.makeCorrectionDraft()).conflict)
    }

    func testHistoryTwoStrikePinchHitterAndTwoBallPitcherCountResponsibility() throws {
        let store = freshStore()
        let firstBatter = store.currentBatter
        store.recordPitch(.calledStrike); store.recordPitch(.calledStrike)
        let pinch = try XCTUnwrap(store.battingBenchPlayers.first)
        store.replaceCurrentBatter(with: pinch)
        store.recordPitch(.swingingStrike)
        XCTAssertEqual(store.game.batting[firstBatter.id]?.strikeouts, 1)
        XCTAssertEqual(store.game.batting[pinch.id, default: BattingLine()].plateAppearances, 0)
        let pitcher = store.currentPitcher
        store.recordPitch(.ball); store.recordPitch(.ball)
        let replacement = try XCTUnwrap(store.availablePitchers.first { $0.id != pitcher.id })
        store.changePitcher(to: replacement)
        store.recordPitch(.ball); store.recordPitch(.ball)
        XCTAssertEqual(store.game.pitching[pitcher.id]?.walks, 1)
        XCTAssertEqual(store.game.pitching[replacement.id, default: PitchingLine()].walks, 0)
        XCTAssertEqual(store.game.runnerPitcherIDs?[store.game.baseRunners[.first]!.id], pitcher.id)
    }
}

extension GameStoreTests {
    func testHistoryFailedAtomicSaveRetriesAndSurvivesDiskRestart() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("scores.sqlite")
        let store = GameStore(persistenceURL: url)
        try store.restoreBackup(LocalBackup.read(freshStore().exportBackup()))
        store.recordPitch(.ball)
        var draft = try store.makeCorrectionDraft()
        draft.journal.operations[0].command = .pitch(.calledStrike); draft.journal.operations[0].edited = true
        let original = store.game, revision = store.activeStoredGame!.revision
        CoreDataRosterStore.writeFault = { throw CocoaError(.fileWriteOutOfSpace) }
        XCTAssertFalse(store.saveCorrection(draft))
        XCTAssertEqual(store.game, original); XCTAssertEqual(store.activeStoredGame!.revision, revision)
        CoreDataRosterStore.writeFault = nil
        XCTAssertTrue(store.saveCorrection(draft), store.actionErrorMessage ?? "")
        let reopened = GameStore(persistenceURL: url)
        XCTAssertEqual(reopened.game, store.game)
        XCTAssertEqual(reopened.game.correctionRevisions?.count, 1)
        reopened.undo()
        XCTAssertEqual(reopened.game.correctionRevisions?.count, 1, "Persistent audit cannot disappear through the live undo button")
    }

    func testHistoryOldStructuredReplayOrHonestLocalFallback() throws {
        let store = freshStore()
        store.recordPitch(.ball); store.recordPitch(.calledStrike); XCTAssertTrue(store.applyPlay(.single))
        let original = store.game
        store.game.historyJournal = nil
        let draft = try store.makeCorrectionDraft()
        XCTAssertNil(draft.compatibilityNote)
        XCTAssertFalse(draft.journal.operations.isEmpty)
        let preview = store.previewCorrection(draft)
        XCTAssertNil(preview.conflict)
        XCTAssertTrue(preview.game.historyScoreEquivalent(to: original))
        store.recordSubstitution("旧版仅文字换人")
        store.game.historyJournal = nil
        let limited = try store.makeCorrectionDraft()
        XCTAssertNotNil(limited.compatibilityNote)
        XCTAssertTrue(limited.journal.legacyPrefix)
        XCTAssertTrue(limited.journal.operations.isEmpty)
        XCTAssertTrue(store.previewCorrection(limited).game.historyScoreEquivalent(to: store.game))
    }

    func testHistoryPinchRunnerReentryAndMissedScoreKeepOnePlateAppearance() throws {
        let store = v111Store(rules: GameRules())
        let a = store.currentBatter
        XCTAssertTrue(store.applyPlay(.single))
        let b = try XCTUnwrap(store.battingBenchPlayers.first)
        let firstBoundary = store.game.historyJournal!.operations.count
        let before = store.game
        let moves = [RunnerDecision(player: a, origin: .base(.first), destination: .base(.third)), RunnerDecision(player: store.currentBatter, origin: .batter, destination: .base(.first))]
        XCTAssertTrue(store.applyPlay(.single, decisions: moves))
        let boundary2 = store.game.historyJournal!.operations.count, before2 = store.game
        XCTAssertTrue(store.applyPlay(.homeRun))
        var draft = try store.makeCorrectionDraft()
        draft.journal.operations.insert(HistoryOperation(command: .pinchRunner(.third, a.id), before: before2, inserted: true), at: boundary2)
        draft.journal.operations.insert(HistoryOperation(command: .pinchRunner(.first, b.id), before: before, inserted: true), at: firstBoundary)
        let preview = store.previewCorrection(draft)
        XCTAssertNil(preview.conflict)
        XCTAssertEqual(preview.game.awayScore, 3)
        XCTAssertEqual(preview.game.batting[a.id]?.runs, 1)
        XCTAssertEqual(preview.game.batting[b.id, default: BattingLine()].plateAppearances, 0)
        XCTAssertEqual(preview.game.awayBattingOrderIDs[0], a.id)
        XCTAssertTrue(store.saveCorrection(draft), store.actionErrorMessage ?? "")
    }

    func testHistoryErrorManualResponsibilityAndIncompleteSituationExports() throws {
        let store = freshStore()
        XCTAssertTrue(store.applyPlay(.triple))
        XCTAssertTrue(store.applyPlay(.single))
        var draft = try store.makeCorrectionDraft()
        let i = draft.journal.operations.count - 1
        draft.journal.operations[i].errors = [HistoryErrorCredit(playerID: store.activeFielders[1].id)]
        draft.journal.operations[i].edited = true
        let uncertain = store.previewCorrection(draft)
        XCTAssertTrue(uncertain.game.statisticsIncomplete == true)
        draft.journal.operations[i].statisticsRuling = HistoryStatisticsRuling(runsBattedIn: 1, earnedRuns: [store.currentPitcher.id: 1], unearnedRunnerIDs: [])
        let reviewed = store.previewCorrection(draft)
        XCTAssertNil(reviewed.conflict); XCTAssertNotEqual(reviewed.game.statisticsIncomplete, true)
        XCTAssertTrue(store.saveCorrection(draft))
        var incomplete = try store.makeCorrectionDraft()
        var state = HistorySituation(store.game); state.outs = 1; state.awayScore = 3
        incomplete.journal.operations.append(HistoryOperation(command: .situation(state), before: store.game, inserted: true))
        XCTAssertTrue(store.saveCorrection(incomplete), store.actionErrorMessage ?? "")
        XCTAssertTrue(store.game.statisticsIncomplete == true)
        XCTAssertEqual(store.game.awayHits, 2, "Unknown process must not fabricate additional hits")
        let exporter = GameExportService(game: store.game, rules: store.activeRules, plateAppearances: store.plateAppearanceRecords(), gameEvents: store.nonPlateAppearanceGameEvents())
        XCTAssertTrue(exporter.completeRecordText().contains("统计可能不完整"))
        XCTAssertTrue(LiveSnapshot(store.activeStoredGame!).notice.contains("统计可能不完整"))
        let data = PlayByPlayPDFReport(game: store.game, appearances: store.plateAppearanceRecords(), playedAt: nil).pdfData(style: .textOnly)
        XCTAssertTrue(try reportText(data, portrait: true).contains("统计可能不完整"))
    }

    func testHistoryNoEditCannotCreateSpuriousRevisionAndThirdOutDoesNotMoveHistory() throws {
        let store = freshStore()
        for _ in 0..<2 { XCTAssertTrue(store.applyPlay(.groundOut)) }
        store.recordPitch(.ball)
        var draft = try store.makeCorrectionDraft()
        XCTAssertFalse(store.saveCorrection(draft))
        XCTAssertEqual(store.game.correctionRevisions?.count ?? 0, 0)
        let last = draft.journal.operations.last!
        let before = store.previewCorrection(draft).statesBefore[last.id]!
        let worker = store.historyWorker(store.activeStoredGame!, state: before)
        draft.journal.operations.insert(HistoryOperation(command: .play(.groundOut, nil, worker.suggestedRunnerDecisions(for: .groundOut), nil), before: before, inserted: true), at: draft.journal.operations.count - 1)
        let result = store.previewCorrection(draft)
        XCTAssertEqual(result.conflictID, last.id)
        XCTAssertFalse(store.saveCorrection(draft))
        XCTAssertTrue(store.game.isTop); XCTAssertEqual(store.game.outs, 2)
    }

    func testHistorySharedReplayCoversCoachDHLineupAndTiebreak() throws {
        for mode in [GameMode.standard, .coachPitch] {
            var rules = GameRules(); rules.mode = mode; rules.coachPitchLimit = 3
            let store = v111Store(rules: rules)
            store.recordPitch(.ball); store.recordPitch(.calledStrike); store.recordPitch(.foul)
            if mode == .standard {
                var lineup = store.lineupDraft(forHomeTeam: true)
                let first = lineup.fieldingIDs[3], second = lineup.fieldingIDs[5]
                let ai = lineup.players.firstIndex { $0.id == first }!, bi = lineup.players.firstIndex { $0.id == second }!
                let pos = lineup.players[ai].primaryPosition
                lineup.players[ai].primaryPosition = lineup.players[bi].primaryPosition; lineup.players[bi].primaryPosition = pos
                lineup.changes = ["两人交换"]
                XCTAssertTrue(store.saveLineup(lineup))
                store.enableTiebreakFromCurrentInning(runnerBases: [.second])
                store.placeTiebreakRunner(try XCTUnwrap(store.recommendedTiebreakRunner), on: .second)
            }
            let preview = store.previewCorrection(try store.makeCorrectionDraft())
            XCTAssertNil(preview.conflict)
            XCTAssertTrue(preview.game.historyScoreEquivalent(to: store.game))
        }
    }
}

extension GameStoreTests {
    func testHistoryCancelledActionAndPendingReviewReplayRemainConsistent() throws {
        let store = freshStore()
        let batter = store.currentBatter
        XCTAssertTrue(store.applyPlay(.single))
        let ruling = ViolationAdjudication(ballStatus: .dead, previousPlayDisposition: .cancel, plateAppearanceDisposition: .batterOut,
            runnerDecisions: [RunnerDecision(player: batter, origin: .batter, destination: .out)], countsAsAtBat: true, isFinalRuling: true)
        XCTAssertTrue(store.recordViolation(.batterInterference, adjudication: ruling))
        let preview = store.previewCorrection(try store.makeCorrectionDraft())
        XCTAssertNil(preview.conflict)
        XCTAssertTrue(preview.game.historyScoreEquivalent(to: store.game))
        XCTAssertEqual(preview.game.scoringEvents?.map(\.id), store.game.scoringEvents?.map(\.id))
        XCTAssertTrue(store.applyPlay(.pending))
        let event = try XCTUnwrap(store.game.scoringEvents?.last)
        XCTAssertTrue(store.reviewPendingEvent(id: event.id, title: "一垒安打已确认", category: .battedBall, notation: "1B", primaryPlayerID: event.primaryPlayerID,
            secondaryPlayerID: event.secondaryPlayerID, ballStatus: .live, resolvedOutcome: .single, note: "复核"))
        let afterReview = store.previewCorrection(try store.makeCorrectionDraft())
        XCTAssertNil(afterReview.conflict)
        XCTAssertTrue(afterReview.game.historyScoreEquivalent(to: store.game))
    }

    func testHistoryDHAndDoubleSwitchReplayPreservesParticipation() throws {
        let store = GameStore(persistenceURL: nil)
        let lineup = store.currentTeam!.players.prefix(9).enumerated().map { LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset]) }
        store.startNewGame(opponent: store.opponentTeams[0], isHome: true, rules: GameRules(usesDesignatedHitter: true, allowsTwoWayPlayer: true), lineup: lineup)
        let original = store.currentPitcher, relief = try XCTUnwrap(store.fieldingBenchPlayers.first)
        store.recordPitch(.ball)
        var roster = store.lineupDraft(forHomeTeam: true)
        XCTAssertTrue(roster.replace(original.id, with: relief.id, pitchingOnly: true)); XCTAssertTrue(store.saveLineup(roster))
        store.recordPitch(.calledStrike)
        roster = store.lineupDraft(forHomeTeam: true)
        XCTAssertTrue(roster.replace(relief.id, with: original.id)); XCTAssertTrue(store.saveLineup(roster))
        let preview = store.previewCorrection(try store.makeCorrectionDraft())
        XCTAssertNil(preview.conflict)
        XCTAssertTrue(preview.game.historyScoreEquivalent(to: store.game))
        XCTAssertEqual(preview.game.homeDesignatedHitterID, store.game.homeDesignatedHitterID)
        let other = v111Store(rules: GameRules())
        let old = other.currentPitcher, catcher = other.activeFielders.first { $0.primaryPosition == .catcher }!, bench = other.fieldingBenchPlayers
        XCTAssertTrue(other.performDoubleSwitch(firstOut: old, firstIn: bench[0], firstPosition: .pitcher, secondOut: catcher, secondIn: bench[1], secondPosition: .catcher))
        other.recordPitch(.ball)
        let replay = other.previewCorrection(try other.makeCorrectionDraft())
        XCTAssertNil(replay.conflict); XCTAssertTrue(replay.game.historyScoreEquivalent(to: other.game))
    }
}

extension GameStoreTests {
    func testHistoryFielderChoicePreservesReplacedInheritedRunnerResponsibility() throws {
        let store = v111Store(rules: GameRules())
        let oldPitcher = store.currentPitcher, runner = store.currentBatter
        XCTAssertTrue(store.applyPlay(.single))
        let newPitcher = try XCTUnwrap(store.fieldingBenchPlayers.first)
        store.changePitcher(to: newPitcher)
        let batter = store.currentBatter
        XCTAssertTrue(store.applyPlay(.fieldersChoice, decisions: [RunnerDecision(player: runner, origin: .base(.first), destination: .out), RunnerDecision(player: batter, origin: .batter, destination: .base(.first))]))
        XCTAssertEqual(store.game.runnerPitcherIDs?[batter.id], oldPitcher.id)
        XCTAssertTrue(store.applyPlay(.homeRun))
        XCTAssertEqual(store.pitchingLine(for: oldPitcher).runs, 1)
        XCTAssertEqual(store.pitchingLine(for: newPitcher).runs, 1)
        XCTAssertTrue(store.previewCorrection(try store.makeCorrectionDraft()).game.historyScoreEquivalent(to: store.game))
    }
}

extension GameStoreTests {
    func testV22TextExportUsesHistoricalPitchersAndCoachPitchNotApplicable() throws {
        let store = v111Store(rules: GameRules())
        let first = store.currentPitcher
        let relief = try XCTUnwrap(store.availablePitchers.first { $0.id != first.id })
        store.recordPitch(.calledStrike)
        store.changePitcher(to: relief)
        store.recordPitch(.ball)
        XCTAssertTrue(store.applyPlay(.single))
        let report = PlayByPlayPDFReport(game: store.game, appearances: store.plateAppearanceRecords(), playedAt: nil)
        let text = try reportText(report.pdfData(style: .textOnly), portrait: true)
        XCTAssertTrue(text.contains("投手：")); XCTAssertTrue(text.contains(first.name)); XCTAssertTrue(text.contains(relief.name))
        XCTAssertTrue(text.contains("→"), text)
        let coach = PlayByPlayPDFReport(game: store.game, appearances: store.plateAppearanceRecords(), playedAt: nil, gameMode: .coachPitch)
        XCTAssertTrue(try reportText(coach.pdfData(style: .textOnly), portrait: true).contains("投手不适用"))
        var missing = store.game
        missing.scoringEvents = missing.scoringEvents?.map { event in
            var event = event; event.beforeSituation = nil; event.afterSituation = nil; return event
        }
        let legacy = PlayByPlayPDFReport(game: missing, appearances: { store.game = missing; return store.plateAppearanceRecords() }(), playedAt: nil)
        let legacyText = try reportText(legacy.pdfData(style: .textOnly), portrait: true)
        XCTAssertTrue(legacyText.contains("未记录"), legacyText)
        try report.pdfData(style: .textOnly).write(to: reportSampleDirectory().appendingPathComponent("v22-historical-pitchers.pdf"))
    }

    func testV22DragAcrossMultipleSlotsAndConsecutiveLineupSavesKeepBatterAndCount() throws {
        let store = freshStore()
        store.recordPitch(.calledStrike); store.recordPitch(.ball)
        let batter = store.currentBatter.id, pitcher = store.currentPitcher.id
        let original = store.game.awayBattingOrderIDs
        var draft = store.lineupDraft(forHomeTeam: false)
        draft.moveBatters(from: IndexSet(integer: 0), to: 5)
        XCTAssertEqual(draft.battingOrderIDs[4], original[0])
        XCTAssertTrue(store.saveLineup(draft))
        var next = store.lineupDraft(forHomeTeam: false)
        next.moveBatters(from: IndexSet([1, 3]), to: 0)
        XCTAssertTrue(store.saveLineup(next), store.actionErrorMessage ?? "")
        XCTAssertEqual(store.currentBatter.id, batter); XCTAssertEqual(store.currentPitcher.id, pitcher)
        XCTAssertEqual(store.game.balls, 1); XCTAssertEqual(store.game.strikes, 1)
        XCTAssertEqual(Set(store.game.awayBattingOrderIDs), Set(original))
        XCTAssertEqual(store.game.historyJournal?.operations.filter { if case .lineup = $0.command { return true }; return false }.count, 2)
        store.undo(); XCTAssertEqual(store.game.awayBattingOrderIDs, draft.battingOrderIDs)
    }

    func testV22OpeningSideSwapsPreserveParticipationClockHistoryAndOurTeam() throws {
        let store = v111Store(rules: GameRules(usesDesignatedHitter: true))
        var draft = store.lineupDraft(forHomeTeam: false)
        draft.moveBatters(from: IndexSet(integer: 0), to: 3)
        XCTAssertTrue(store.saveLineup(draft))
        store.startGameClock()
        let before = store.game, ours = store.activeStoredGame!.ourTeamID
        for _ in 0..<3 { XCTAssertTrue(store.swapOpeningSides(), store.actionErrorMessage ?? "") }
        XCTAssertEqual(store.game.homeTeam, before.awayTeam)
        XCTAssertEqual(store.game.homeBattingOrderIDs, before.awayBattingOrderIDs)
        XCTAssertEqual(store.game.homeFieldingPlayerIDs, before.awayFieldingPlayerIDs)
        XCTAssertEqual(store.game.homeDesignatedHitterID, before.awayDesignatedHitterID)
        XCTAssertEqual(store.game.activeHomePitcherID, before.activeAwayPitcherID)
        XCTAssertEqual(store.game.clockRunningSince, before.clockRunningSince)
        XCTAssertEqual(store.activeStoredGame?.ourTeamID, ours); XCTAssertEqual(store.activeStoredGame?.isHome, true)
        let preview = store.previewCorrection(try store.makeCorrectionDraft())
        XCTAssertNil(preview.conflict, preview.conflict ?? "")
        XCTAssertTrue(preview.game.historyScoreEquivalent(to: store.game))
        store.undo(); XCTAssertEqual(store.activeStoredGame?.isHome, false)
        store.redo(); XCTAssertEqual(store.activeStoredGame?.isHome, true)
        store.recordPitch(.ball)
        XCTAssertFalse(store.canSwapOpeningSides)
        store.undo(); XCTAssertEqual(store.game.balls, 0)
        XCTAssertFalse(store.canSwapOpeningSides); XCTAssertFalse(store.swapOpeningSides())
        let target = GameStore(persistenceURL: nil)
        try target.restoreBackup(LocalBackup.read(store.exportBackup()))
        XCTAssertFalse(target.canSwapOpeningSides)
        XCTAssertEqual(target.activeStoredGame?.isHome, true)
    }

    func testV22ObservedSwapsPersistBothLineupsAndFailedWritesRollBack() throws {
        let store = freshStore()
        let teams = [store.game.awayTeam, store.game.homeTeam]
        let id = try XCTUnwrap(store.createObservedGame(awayTeam: teams[0], homeTeam: teams[1], rules: GameRules()))
        let original = try XCTUnwrap(store.activeStoredGame)
        CoreDataRosterStore.writeFault = { throw CocoaError(.fileWriteOutOfSpace) }
        defer { CoreDataRosterStore.writeFault = nil }
        XCTAssertFalse(store.swapOpeningSides())
        XCTAssertEqual(store.activeStoredGame, original)
        CoreDataRosterStore.writeFault = nil
        XCTAssertTrue(store.swapOpeningSides())
        XCTAssertEqual(store.activeStoredGame?.lineup, original.secondaryLineup)
        XCTAssertEqual(store.activeStoredGame?.secondaryLineup, original.lineup)
        XCTAssertEqual(store.activeStoredGame?.id, id)
        let target = GameStore(persistenceURL: nil)
        try target.restoreBackup(LocalBackup.read(store.exportBackup()))
        XCTAssertEqual(target.game.homeTeam.id, teams[0].id)
        XCTAssertTrue(target.canSwapOpeningSides)
        target.recordPitch(.foul); target.undo()
        XCTAssertFalse(target.canSwapOpeningSides)
    }

    func testV22FirstPitchLockSurvivesRestartAndLegacyPayloadStaysReadable() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("v22.sqlite")
        let store = GameStore(persistenceURL: url)
        try store.restoreBackup(LocalBackup.read(freshStore().exportBackup()))
        XCTAssertTrue(store.swapOpeningSides())
        store.recordPitch(.calledStrike); store.undo()
        let reopened = GameStore(persistenceURL: url)
        XCTAssertFalse(reopened.canSwapOpeningSides)
        let stored = try XCTUnwrap(reopened.activeStoredGame)
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(stored)) as? [String: Any])
        json.removeValue(forKey: "openingPitchRecorded")
        let old = try JSONDecoder().decode(StoredGame.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertNil(old.openingPitchRecorded); XCTAssertEqual(old.state, stored.state)
        reopened.games = [old]
        XCTAssertFalse(reopened.canSwapOpeningSides, "Old records without first-pitch evidence must not silently unlock")
    }

    func testV22CoachPitchAndInPlayOutcomesLockOpeningSwap() {
        for coach in [false, true] {
            var rules = GameRules(); rules.mode = coach ? .coachPitch : .standard
            let store = v111Store(rules: rules)
            XCTAssertTrue(store.canSwapOpeningSides)
            XCTAssertTrue(store.swapOpeningSides())
            store.recordPitch(.ball); store.undo()
            XCTAssertFalse(store.canSwapOpeningSides)
        }
        let store = freshStore()
        XCTAssertTrue(store.applyPlay(.single)); store.undo()
        XCTAssertFalse(store.canSwapOpeningSides)
    }

    func testV22SelectedGamesMatchTeamPlayerAndPDFWithEmptyAndModeScopes() throws {
        let store = GameStore(persistenceURL: nil), team = GameStore(persistenceURL: nil).currentTeam!
        // Use the store's stable player identities across all synthetic games.
        let ours = store.currentTeam!, player = ours.players[0], season = store.seasons[0].id
        var g1 = statisticsGame(store: store, runs: 4, allowed: 1)
        var g2 = statisticsGame(store: store, runs: 1, allowed: 3)
        var g3 = statisticsGame(store: store, runs: 2, allowed: 2)
        g1.state.batting[player.id] = BattingLine(plateAppearances: 3, atBats: 3, hits: 1)
        g2.state.batting[player.id] = BattingLine(plateAppearances: 4, atBats: 4, hits: 4)
        g3.state.batting[player.id] = BattingLine(plateAppearances: 2, atBats: 1, hits: 1, walks: 1)
        g3.rules.mode = .coachPitch
        g1.state.pitching[player.id] = PitchingLine(outsRecorded: 3, pitches: 5)
        g3.state.pitching[player.id] = PitchingLine(outsRecorded: 99, pitches: 99)
        g1.state.fielding[player.id] = FieldingLine(putouts: 1)
        g3.state.fielding[player.id] = FieldingLine(assists: 2)
        var excluded = statisticsGame(store: store, observation: true, runs: 99)
        excluded.state.batting[player.id] = BattingLine(plateAppearances: 99, atBats: 99, hits: 99)
        store.games = [g1, g2, g3, excluded, statisticsGame(store: store, status: .ongoing)]
        XCTAssertEqual(store.seasonStatistics(for: ours, seasonID: season).games.count, 3)
        store.playerGameRecords = [PlayerGameRecord(id: UUID(), playerID: player.id, seasonID: season,
            date: Date(), opponent: "旧版独立记录", result: "未记录", batting: BattingLine(plateAppearances: 100, atBats: 100, hits: 100))]
        let eligible = Set(store.completedStatisticsGames(teamID: ours.id, seasonID: season).map(\.id))
        XCTAssertEqual(store.playerStatistics(for: player, seasonID: season, teamID: ours.id, gameIDs: eligible).batting.hits, 6)
        let ids: Set<UUID> = [g1.id, g3.id, excluded.id, UUID()]
        let chosen = store.seasonStatistics(for: ours, seasonID: season, gameIDs: ids)
        XCTAssertEqual(Set(chosen.games.map(\.id)), [g1.id, g3.id])
        XCTAssertEqual(chosen.runs, 6); XCTAssertEqual(chosen.wins, 1); XCTAssertEqual(chosen.ties, 1)
        XCTAssertEqual(chosen.batting.hits, 2); XCTAssertEqual(chosen.pitching.pitches, 5)
        let row = store.playerStatistics(for: player, seasonID: season, teamID: ours.id, gameIDs: ids)
        XCTAssertEqual(row.batting.hits, 2); XCTAssertEqual(row.fielding.assists, 2)
        let playerReport = PlayerStatisticsPDFReport(store: store, player: player, seasonID: season,
            teamID: ours.id, gameIDs: [g1.id, g2.id, g3.id], scopeGameIDs: [g1.id, g3.id])
        XCTAssertEqual(playerReport.seasonGameCount, 2); XCTAssertEqual(playerReport.records.count, 2)
        XCTAssertEqual(playerReport.summary.batting.hits, 2)
        XCTAssertTrue(try reportText(playerReport.pdfData()).contains("2 / 2 场比赛"))
        let empty = store.seasonStatistics(for: ours, seasonID: season, gameIDs: [])
        XCTAssertEqual(empty.games.count, 0); XCTAssertEqual(empty.batting.hits, 0)
        XCTAssertEqual(store.seasonStatistics(for: ours, seasonID: season, mode: .standard, gameIDs: ids).games.count, 1)
        let report = TeamSeasonPDFReport(store: store, team: ours, seasonID: season, gameIDs: ids)
        XCTAssertEqual(Set(report.summary.games.map(\.id)), Set(chosen.games.map(\.id)))
        XCTAssertEqual(report.summary.batting, chosen.batting)
        XCTAssertTrue(try reportText(report.pdfData()).contains("所选比赛统计"))
        XCTAssertEqual(store.seasonStatistics(for: team, seasonID: season, gameIDs: ids).games.count, 0)
        try report.pdfData().write(to: reportSampleDirectory().appendingPathComponent("v22-selected-games.pdf"))
    }
}

extension GameStoreTests {
    func testV22AllPitchEntrancesLockWhileNonPitchAwardsAndLineupDoNot() throws {
        for action in PitchAction.allCases {
            let store = freshStore(); store.recordPitch(action); store.undo()
            XCTAssertFalse(store.canSwapOpeningSides, action.rawValue)
        }
        for coach in [false, true] {
            var rules = GameRules(); rules.mode = coach ? .coachPitch : .standard
            let store = v111Store(rules: rules)
            store.recordHitByPitch(); store.undo(); XCTAssertFalse(store.canSwapOpeningSides)
        }
        let store = freshStore()
        store.recordIntentionalWalk()
        XCTAssertEqual(store.activeStoredGame?.openingPitchRecorded, false)
        XCTAssertFalse(store.canSwapOpeningSides, "Cannot move a batter already awarded first base to the other side")
        store.undo(); XCTAssertTrue(store.canSwapOpeningSides)
        XCTAssertTrue(store.recordViolation(.foreignSubstance))
        XCTAssertEqual(store.activeStoredGame?.openingPitchRecorded, false)
        store.undo(); XCTAssertTrue(store.canSwapOpeningSides, "A non-pitch equipment penalty must not become a physical first pitch")
    }

    func testV22ScheduledLegacyOpeningSupportsFreshStartAndMultipleSwaps() throws {
        for observation in [false, true] {
            let store = GameStore(persistenceURL: nil)
            let teams = observation ? Array(store.opponentTeams.prefix(2)) : [store.currentTeam!, store.opponentTeams[0]]
            let assignments = teams[0].players.prefix(9).enumerated().map {
                LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset])
            }
            let id: UUID
            if observation {
                id = try XCTUnwrap(store.createObservedGame(awayTeam: teams[0], homeTeam: teams[1], rules: GameRules(), startImmediately: false))
            } else {
                id = try XCTUnwrap(store.startNewGame(opponent: teams[1], isHome: false, rules: GameRules(), lineup: assignments, startImmediately: false))
            }
            let index = try XCTUnwrap(store.games.firstIndex { $0.id == id })
            store.games[index].openingPitchRecorded = nil
            let started = observation ? store.startScheduledObservedGame(id: id, rules: GameRules()) : store.startScheduledGame(id: id)
            XCTAssertTrue(started); XCTAssertTrue(store.canSwapOpeningSides)
            XCTAssertTrue(store.swapOpeningSides()); XCTAssertTrue(store.swapOpeningSides())
            store.recordPitch(.calledStrike); store.undo(); XCTAssertFalse(store.canSwapOpeningSides)
        }
    }
}

// 2.2 live viewer: project saved game state, including upgrade of existing 2.1 links.
extension GameStoreTests {
    func testV22LiveRulesClockAndUnplayedHalvesUseSavedState() throws {
        let store = v11Store(cap: 6)
        let fresh = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(fresh.hasStarted, false)
        XCTAssertTrue(try XCTUnwrap(fresh.away.playedInnings).allSatisfy { !$0 })
        XCTAssertTrue(try XCTUnwrap(fresh.home.playedInnings).allSatisfy { !$0 })
        XCTAssertEqual(fresh.rules?.halfInningRunLimit, 6)
        let start = Date().addingTimeInterval(-120)
        store.startGameClock(at: start)
        let running = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(running.clock?.startedAt, start.timeIntervalSince1970 * 1000)
        XCTAssertEqual(running.clock?.runningSince, start.timeIntervalSince1970 * 1000)
        XCTAssertEqual(running.away.playedInnings?.first, true)
        XCTAssertEqual(running.home.playedInnings?.first, false)
        XCTAssertEqual(try running.data(), try running.data(), "Viewer clock must not change the publication hash")
        store.pauseGameClock(at: start.addingTimeInterval(61))
        let paused = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertNil(paused.clock?.runningSince); XCTAssertEqual(paused.clock?.elapsedSeconds, 61)
    }

    func testV22LiveLineupExcludesBenchAndNextBattersWrapAndWalkIsNotAtBat() throws {
        let store = v11Store()
        let initial = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(initial.away.lineup?.map { $0.player.id }, store.game.awayBattingOrderIDs.map(\.uuidString))
        XCTAssertEqual(initial.away.lineup?.count, 9)
        XCTAssertGreaterThan(store.game.awayTeam.players.count, 9)
        for _ in 0..<8 { for _ in 0..<4 { store.recordPitch(.ball) } }
        let ninth = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(ninth.batterOrder, 9); XCTAssertEqual(ninth.nextBatters?.map(\.order), [1, 2])
        for _ in 0..<4 { store.recordPitch(.ball) }
        let first = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(first.batterOrder, 1); XCTAssertEqual(first.batterStats?.atBats, 0)
        XCTAssertEqual(first.batterStats?.hits, 0)
        XCTAssertTrue(store.applyPlay(.single))
        for _ in 0..<8 { for _ in 0..<4 { store.recordPitch(.ball) } }
        let returned = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(returned.batterOrder, 1)
        XCTAssertEqual(returned.batterStats?.atBats, 1); XCTAssertEqual(returned.batterStats?.hits, 1)
        var draft = store.lineupDraft(forHomeTeam: false)
        draft.moveBatter(from: 0, to: 4)
        XCTAssertTrue(store.saveLineup(draft))
        let reordered = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(reordered.away.lineup?.map { $0.player.id }, store.game.awayBattingOrderIDs.map(\.uuidString))
        XCTAssertEqual(reordered.batterOrder, 5)
        XCTAssertEqual(reordered.nextBatters?.map(\.order), [6, 7])
    }

    func testV22LiveHistoricalBasesAreOwnedByEachEntryAndPitchAndSurviveUndo() throws {
        let store = v11Store()
        let firstID = try XCTUnwrap(store.game.currentPlateAppearanceID).uuidString
        store.recordPitch(.ball)
        XCTAssertTrue(store.applyPlay(.single))
        let first = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(first.entries.first { $0.id == firstID }?.situation?.bases, [1])
        XCTAssertEqual(first.entries.first { $0.id == firstID }?.details.first?.situation?.bases, [])
        for _ in 0..<4 { store.recordPitch(.ball) }
        let advanced = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(advanced.bases.map(\.base), [1, 2])
        XCTAssertEqual(advanced.entries.first { $0.id == firstID }?.situation?.bases, [1])
        XCTAssertEqual(advanced.entries.first { $0.id == advanced.currentAppearanceID }?.situation?.bases, [1, 2])
        store.undo()
        let undone = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(undone.bases.map(\.base), [1])
        XCTAssertEqual(undone.entries.first { $0.id == firstID }?.situation?.bases, [1])
        store.redo()
        XCTAssertEqual(LiveSnapshot(try XCTUnwrap(store.activeStoredGame)).entries, advanced.entries)
    }

    func testV22LiveThirdOutDoesNotInventStartedHomeHalfOrCarryBasesAcrossHalf() throws {
        let store = v11Store()
        XCTAssertTrue(store.applyPlay(.single))
        for _ in 0..<2 { XCTAssertTrue(store.applyPlay(.groundOut)) }
        let thirdID = try XCTUnwrap(store.game.currentPlateAppearanceID).uuidString
        XCTAssertTrue(store.applyPlay(.groundOut))
        let switched = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        let third = try XCTUnwrap(switched.entries.first { $0.id == thirdID })
        XCTAssertEqual(third.situation?.halfEnded, true); XCTAssertEqual(third.situation?.bases, [])
        XCTAssertEqual(switched.home.playedInnings?.first, true)
        store.finishGame()
        let finished = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(finished.away.playedInnings?.first, true)
        XCTAssertEqual(finished.home.playedInnings?.first, false, "Immediate final after third out leaves the unused bottom half '-' ")
        XCTAssertEqual(finished.nextBatters, []); XCTAssertNil(finished.batterStats)
    }

    func testV22LiveDHIncludesNonBattingPitcherAndCoachDoesNotInventPitcherCount() throws {
        let store = v111Store(rules: GameRules(usesDesignatedHitter: true))
        let snap = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        let roster = try XCTUnwrap(snap.away.lineup)
        XCTAssertEqual(roster.count, 10)
        XCTAssertEqual(roster.filter { $0.position == "DH" }.count, 1)
        XCTAssertEqual(roster.filter { $0.order == nil }.count, 1)
        XCTAssertEqual(roster.first { $0.order == nil }?.player.id, snap.away.pitcherID)
        let coach = v11Store(mode: .coachPitch, pitches: 5)
        coach.recordPitch(.ball)
        let c = LiveSnapshot(try XCTUnwrap(coach.activeStoredGame))
        XCTAssertNil(c.pitchCount); XCTAssertEqual(c.appearancePitchCount, 1); XCTAssertEqual(c.pitchLimit, 5)
    }

    func testV22LiveLegacyDecodeDoesNotInventLineupsStatsOrHistoricalSituations() throws {
        let store = v11Store(); store.recordPitch(.ball)
        let old = try JSONDecoder().decode(LiveSnapshot.self, from: LiveSnapshot(try XCTUnwrap(store.activeStoredGame)).legacyData())
        XCTAssertNil(old.rules); XCTAssertNil(old.clock); XCTAssertNil(old.batterStats)
        XCTAssertNil(old.nextBatters); XCTAssertNil(old.home.lineup); XCTAssertNil(old.away.playedInnings)
        XCTAssertNil(old.entries.first?.situation); XCTAssertNil(old.entries.first?.details.first?.situation)
    }

    func testV22LiveProjectionUpgradePersistsBeforePublishAndFailedSaveKeepsOldLink() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("V22LiveUpgrade-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let url = root.appendingPathComponent("BaseballMaster.sqlite")
        let store = v11Store(url: url); store.recordPitch(.ball)
        let saved = try XCTUnwrap(store.activeStoredGame), old = try LiveSnapshot(saved).legacyData()
        let vault = LiveTestVault(), network = LiveTestTransport()
        let oldDigest = SHA256.hash(data: old).map { String(format: "%02x", $0) }.joined()
        vault.bindings = [LiveBinding(gameID: saved.id, token: "synthetic-test-token", code: "1234567890ABCDEF12345678",
            ackRevision: saved.revision, ackDigest: oldDigest, expiresAt: Date().addingTimeInterval(3600))]
        let manager = LiveBroadcastManager(store: store, vault: vault, transport: network, enabled: true)
        CoreDataRosterStore.writeFault = { throw CocoaError(.fileWriteOutOfSpace) }
        await manager.synchronize()
        CoreDataRosterStore.writeFault = nil
        XCTAssertTrue(network.calls.isEmpty); XCTAssertEqual(store.activeStoredGame?.revision, saved.revision)
        XCTAssertEqual(vault.bindings.first?.ackDigest, oldDigest)
        manager.retry(saved.id); await manager.waitForPendingSync()
        XCTAssertEqual(network.calls.filter { $0.method == "PUT" }.count, 1)
        XCTAssertEqual(vault.bindings.first?.code, "1234567890ABCDEF12345678")
        XCTAssertEqual(store.activeStoredGame?.revision, (saved.revision ?? 0) + 1)
        XCTAssertEqual(GameStore(persistenceURL: url).activeStoredGame?.revision, store.activeStoredGame?.revision)
        XCTAssertEqual(store.game, saved.state)
        let payload = try JSONDecoder().decode(LiveSnapshot.self, from: XCTUnwrap(network.calls.last?.body))
        XCTAssertEqual(payload, LiveSnapshot(try XCTUnwrap(store.activeStoredGame)))
        await manager.synchronize()
        XCTAssertEqual(network.calls.filter { $0.method == "PUT" }.count, 1, "Upgrade must happen only once")
    }

    func testV22LiveHTTPUpgradeUsesSameReleasedLinkAndPublishesNewFields() async throws {
        let base = URL(string: "http://127.0.0.1:18088/livestreaming/novideo")!
        do { _ = try await URLSession.shared.data(from: base.appendingPathComponent("health")) }
        catch { throw XCTSkip("Run the local integration server for upgrade regression") }
        let store = v11Store(); store.recordPitch(.ball)
        let saved = try XCTUnwrap(store.activeStoredGame), old = try LiveSnapshot(saved).legacyData()
        var binding = LiveBinding(gameID: saved.id, token: (UUID().uuidString + UUID().uuidString).replacingOccurrences(of: "-", with: "").lowercased())
        let http = LiveHTTPClient(baseURL: base)
        let body = try JSONSerialization.data(withJSONObject: ["requestID": binding.requestID.uuidString,
            "createdAt": binding.createdAt.timeIntervalSince1970 * 1000, "snapshot": JSONSerialization.jsonObject(with: old)])
        let response = try await http.send(method: "POST", code: nil, token: binding.token, body: body)
        let created = try XCTUnwrap(response)
        binding.code = created.code; binding.ackRevision = saved.revision
        binding.ackDigest = SHA256.hash(data: old).map { String(format: "%02x", $0) }.joined()
        binding.expiresAt = Date(timeIntervalSince1970: created.expiresAt / 1000)
        let vault = LiveTestVault(); vault.bindings = [binding]
        store.liveBroadcasts = LiveBroadcastManager(store: store, vault: vault, transport: http, enabled: true, viewBaseURL: base)
        store.liveBroadcasts.requestSync()
        let published = try await readV21Live(store)
        XCTAssertEqual(store.liveBroadcasts.binding(for: saved.id)?.code, created.code)
        XCTAssertEqual(published.revision, (saved.revision ?? 0) + 1)
        XCTAssertEqual(published.rules?.scheduledInnings, store.game.scheduledInnings)
        XCTAssertEqual(published.away.lineup?.count, 9)
        XCTAssertNotNil(published.entries.first?.situation)
        try await closeV21Live(store)
    }
}


extension GameStoreTests {
    func testV22LiveLegacyCountOrBasesMeansPlayHasStarted() throws {
        let store = v11Store()
        var saved = try XCTUnwrap(store.activeStoredGame)
        saved.state.scoringEvents = []; saved.openingPitchRecorded = nil
        saved.state.balls = 1
        XCTAssertEqual(LiveSnapshot(saved).hasStarted, true)
        saved.state.balls = 0
        saved.state.baseRunners[.first] = store.currentBatter
        XCTAssertEqual(LiveSnapshot(saved).hasStarted, true)
        XCTAssertEqual(LiveSnapshot(saved).away.playedInnings?.first, true)
        XCTAssertEqual(LiveSnapshot(saved).home.playedInnings?.first, false)
    }
}

extension GameStoreTests {
    func testV22LiveManualHalfEndClosesInterruptedSituationWithoutChangingEarlierHit() throws {
        let store = v11Store()
        let hitID = try XCTUnwrap(store.game.currentPlateAppearanceID).uuidString
        XCTAssertTrue(store.applyPlay(.single))
        let interruptedID = try XCTUnwrap(store.game.currentPlateAppearanceID).uuidString
        store.recordPitch(.ball)
        store.endCurrentHalf()
        let ended = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(ended.entries.first { $0.id == hitID }?.situation?.bases, [1])
        let interrupted = try XCTUnwrap(ended.entries.first { $0.id == interruptedID })
        XCTAssertEqual(interrupted.status, "interrupted")
        XCTAssertEqual(interrupted.situation?.halfEnded, true); XCTAssertEqual(interrupted.situation?.bases, [])
        XCTAssertEqual(interrupted.details.first?.situation?.bases, [1], "The earlier pitch retains its own situation")
        store.undo()
        let restored = LiveSnapshot(try XCTUnwrap(store.activeStoredGame))
        XCTAssertEqual(restored.entries.first { $0.id == interruptedID }?.situation?.bases, [1])
        store.redo()
        XCTAssertEqual(LiveSnapshot(try XCTUnwrap(store.activeStoredGame)).entries, ended.entries)
    }
}

extension GameStoreTests {
    private func lastInningFixture(isTop: Bool) throws -> GameStore {
        let store = v111Store(rules: GameRules(scheduledInnings: 6))
        for _ in 0..<29 { XCTAssertTrue(store.applyPlay(.groundOut)) }
        if isTop { XCTAssertTrue(store.applyPlay(.homeRun)) }
        XCTAssertTrue(store.applyPlay(.groundOut))
        if !isTop {
            XCTAssertTrue(store.applyPlay(.homeRun))
            for _ in 0..<3 { XCTAssertTrue(store.applyPlay(.groundOut)) }
        }
        XCTAssertEqual(store.game.inning, 6)
        XCTAssertEqual(store.game.isTop, isTop)
        let relief = try XCTUnwrap(store.availablePitchers.first { store.pitchingLine(for: $0).outsRecorded == 0 })
        store.changePitcher(to: relief)
        XCTAssertEqual(store.currentPitcher.id, relief.id)
        return store
    }

    private func assertFinalPitchingLine(_ store: GameStore, pitcher: Player, expectedOuts: Int, label: String) throws {
        XCTAssertEqual(store.game.pendingDecision, .regulationHalf)
        let before = store.pitchingLine(for: pitcher)
        store.resolveGameDecision(finish: true)
        XCTAssertTrue(store.game.isFinal)
        let saved = try XCTUnwrap(store.activeStoredGame)
        let actual = try XCTUnwrap(saved.state.pitching[pitcher.id])
        XCTAssertEqual(actual, before, "Finish confirmation must preserve pitching totals")
        XCTAssertEqual(actual.outsRecorded, expectedOuts, label)
        XCTAssertEqual(actual.inningsText, "\(expectedOuts / 3).\(expectedOuts % 3)", label)
    }

    func testV23FinalNormalOutsCountOneInning() throws {
        for top in [true, false] {
            let store = try lastInningFixture(isTop: top)
            let pitcher = store.currentPitcher
            for _ in 0..<3 { XCTAssertTrue(store.applyPlay(.groundOut)) }
            try assertFinalPitchingLine(store, pitcher: pitcher, expectedOuts: 3, label: "normal-top=\(top)")
        }
    }

    func testV23FinalStrikeoutsCountOneInning() throws {
        for top in [true, false] {
            let store = try lastInningFixture(isTop: top)
            let pitcher = store.currentPitcher
            for _ in 0..<9 { store.recordPitch(.calledStrike) }
            try assertFinalPitchingLine(store, pitcher: pitcher, expectedOuts: 3, label: "strikeouts-top=\(top)")
        }
    }

    func testV23FinalRunnerOutMustCompletePitcherInning() throws {
        for top in [true, false] {
            for kind in [RunnerEventKind.caughtStealing, .pickoff, .tagOut, .forceOut] {
                let store = try lastInningFixture(isTop: top)
                let pitcher = store.currentPitcher
                XCTAssertTrue(store.applyPlay(.single))
                for _ in 0..<6 { store.recordPitch(.calledStrike) }
                let runner = try XCTUnwrap(store.game.baseRunners[.first])
                XCTAssertTrue(store.recordRunnerEvent(kind, decisions: [RunnerDecision(player: runner, origin: .base(.first), destination: .out)]))
                XCTAssertEqual(store.game.outs, 3)
                try assertFinalPitchingLine(store, pitcher: pitcher, expectedOuts: 3, label: "\(kind)-top=\(top)")
            }
        }
    }

    func testV23FinalDroppedStrikeOutMustCompletePitcherInning() throws {
        for top in [true, false] {
            let store = try lastInningFixture(isTop: top)
            let pitcher = store.currentPitcher
            for _ in 0..<2 { XCTAssertTrue(store.applyPlay(.groundOut)) }
            store.recordPitch(.calledStrike); store.recordPitch(.calledStrike)
            XCTAssertTrue(store.recordDroppedThirdStrike(decisions: [RunnerDecision(player: store.currentBatter, origin: .batter, destination: .out)]))
            XCTAssertEqual(store.game.outs, 3)
            try assertFinalPitchingLine(store, pitcher: pitcher, expectedOuts: 3, label: "dropped-strike-out-top=\(top)")
        }
    }

    func testV23DroppedStrikeSafeDoesNotInventOut() throws {
        let store = try lastInningFixture(isTop: true)
        let pitcher = store.currentPitcher
        for _ in 0..<2 { XCTAssertTrue(store.applyPlay(.groundOut)) }
        store.recordPitch(.calledStrike); store.recordPitch(.calledStrike)
        XCTAssertTrue(store.recordDroppedThirdStrike(decisions: [RunnerDecision(player: store.currentBatter, origin: .batter, destination: .base(.first))]))
        XCTAssertEqual(store.game.outs, 2)
        XCTAssertEqual(store.pitchingLine(for: pitcher).outsRecorded, 2)
        XCTAssertEqual(store.pitchingLine(for: pitcher).inningsText, "0.2")
        XCTAssertEqual(store.pitchingLine(for: pitcher).strikeouts, 1)
    }

    func testV23ManualHalfEndDoesNotInventThirdOut() throws {
        let store = try lastInningFixture(isTop: true)
        let pitcher = store.currentPitcher
        for _ in 0..<2 { XCTAssertTrue(store.applyPlay(.groundOut)) }
        store.endCurrentHalf()
        try assertFinalPitchingLine(store, pitcher: pitcher, expectedOuts: 2, label: "manual-half-end-with-two-outs")
    }
}

extension GameStoreTests {
    func testV23OwnGamesSelectBothStartingPitchersWithoutChangingIdentity() throws {
        for isHome in [false, true] {
            let store = GameStore(persistenceURL: nil)
            let own = try XCTUnwrap(store.currentTeam), opponent = store.opponentTeams[0]
            let ownLineup = Array(own.players.prefix(9)).enumerated().map { LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset]) }
            let otherLineup = Array(opponent.players.prefix(9).reversed()).enumerated().map { LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset]) }
            let id = try XCTUnwrap(store.startNewGame(opponent: opponent, isHome: isHome, rules: GameRules(), lineup: ownLineup, opponentLineup: otherLineup))
            let stored = try XCTUnwrap(store.games.first { $0.id == id })
            XCTAssertFalse(stored.isObservation)
            XCTAssertEqual(stored.ourTeamID, own.id)
            XCTAssertEqual(stored.secondaryLineup, otherLineup)
            XCTAssertEqual(store.game.activeHomePitcherID, isHome ? ownLineup[0].playerID : otherLineup[0].playerID)
            XCTAssertEqual(store.game.activeAwayPitcherID, isHome ? otherLineup[0].playerID : ownLineup[0].playerID)
            XCTAssertEqual(store.game.homeBattingOrderIDs, (isHome ? ownLineup : otherLineup).map(\.playerID))
            XCTAssertTrue(store.game.pitching.isEmpty)
            XCTAssertFalse((store.game.scoringEvents ?? []).contains { $0.category == .substitution })
        }
    }

    func testV23BothScheduledLineupsSaveRestoreEditAndStartWithoutDefaultReset() throws {
        for isHome in [false, true] {
            let store = GameStore(persistenceURL: nil)
            let own = try XCTUnwrap(store.currentTeam), opponent = store.opponentTeams[0]
            let ours = Array(own.players.prefix(9)).enumerated().map { LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset]) }
            var theirs = Array(opponent.players.prefix(9).reversed()).enumerated().map { LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset]) }
            let id = try XCTUnwrap(store.startNewGame(opponent: opponent, isHome: isHome, rules: GameRules(), lineup: ours, opponentLineup: theirs, startImmediately: false))
            theirs.swapAt(0, 1)
            for index in theirs.indices { theirs[index].battingOrder = index + 1 }
            XCTAssertTrue(store.saveScheduledLineups(id: id, rules: GameRules(), awayLineup: isHome ? theirs : ours, homeLineup: isHome ? ours : theirs))
            XCTAssertEqual(store.games.first { $0.id == id }?.status, .scheduled)
            XCTAssertNil(store.activeGameID)
            let restored = GameStore(persistenceURL: nil)
            try restored.restoreBackup(LocalBackup.read(store.exportBackup()))
            let saved = try XCTUnwrap(restored.games.first { $0.id == id })
            XCTAssertEqual(saved.secondaryLineup, theirs)
            XCTAssertTrue(restored.startScheduledGame(id: id, rules: saved.rules, lineup: saved.lineup))
            XCTAssertEqual(isHome ? restored.game.awayBattingOrderIDs : restored.game.homeBattingOrderIDs, theirs.map(\.playerID))
            XCTAssertEqual(isHome ? restored.game.activeAwayPitcherID : restored.game.activeHomePitcherID, theirs.first { $0.position == .pitcher }?.playerID)
            XCTAssertEqual(restored.activeStoredGame?.ourTeamID, own.id)
            XCTAssertFalse(try XCTUnwrap(restored.activeStoredGame).isObservation)
            XCTAssertEqual(restored.activeStoredGame?.secondaryLineup, theirs)
            XCTAssertEqual(restored.previousLineup(for: opponent.id, mode: .standard), theirs)
            let reopened = GameStore(persistenceURL: nil)
            try reopened.restoreBackup(LocalBackup.read(restored.exportBackup()))
            XCTAssertEqual(reopened.activeStoredGame?.secondaryLineup, theirs)
        }
    }
}

extension GameStoreTests {
    private func slowStore(policy: TwoStrikeFoulPolicy = .oneExtraFoul, balls: Int = 1, strikes: Int = 1) throws -> GameStore {
        let store = GameStore(persistenceURL: nil)
        func team(_ name: String) -> Team {
            Team(name: name, shortName: name, city: "", players: (0..<14).map { Player(name: name + "球员\($0+1)", number: $0+1, primaryPosition: FieldPosition.allCases[$0 % 10]) })
        }
        let own = team("客队"), opponent = team("主队")
        store.teams = [own]; store.currentTeam = own; store.opponentTeams = [opponent]
        var rules = GameRules(scheduledInnings: 6, fieldersCount: 10, timeLimitMinutes: 90, timeWarningMinutes: 5)
        rules.mode = .slowPitch; rules.competitionFormat = .timed
        rules.initialBalls = balls; rules.initialStrikes = strikes; rules.twoStrikeFoulPolicy = policy; rules.rulesVersion = 2
        func lineup(_ team: Team, count: Int) -> [LineupAssignment] {
            team.players.prefix(count).enumerated().map { LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset+1, position: $0.offset < 10 ? FieldPosition.allCases[$0.offset] : nil) }
        }
        XCTAssertNotNil(store.startNewGame(opponent: opponent, isHome: false, rules: rules, lineup: lineup(own, count: 12), opponentLineup: lineup(opponent, count: 11)))
        return store
    }

    func testV23SlowPitchInitialCountsDoNotGeneratePitchesAndEachAppearanceResets() throws {
        let store = try slowStore()
        XCTAssertEqual(store.game.balls, 1); XCTAssertEqual(store.game.strikes, 1)
        XCTAssertEqual(store.pitchingLine(for: store.currentPitcher).pitches, 0)
        XCTAssertTrue(store.isPregame); XCTAssertTrue(store.canSwapOpeningSides)
        XCTAssertFalse(LiveSnapshot(try XCTUnwrap(store.activeStoredGame)).hasStarted ?? true)
        let pitcher = store.currentPitcher
        for _ in 0..<3 { store.recordPitch(.ball) }
        XCTAssertEqual(store.pitchingLine(for: pitcher).pitches, 3)
        XCTAssertEqual(store.pitchingLine(for: pitcher).walks, 1)
        XCTAssertEqual(store.game.balls, 1); XCTAssertEqual(store.game.strikes, 1)
        XCTAssertFalse(store.canSwapOpeningSides)
        let invalid = GameRules()
        XCTAssertEqual(invalid.startingBalls, 0); XCTAssertEqual(invalid.startingStrikes, 0)
    }

    func testV23SlowPitchFoulGraceSurvivesBallUndoBackupAndResetsOnNextAppearance() throws {
        let store = try slowStore()
        let pitcher = store.currentPitcher
        store.recordPitch(.foul) // reaches two strikes, does not use the extra chance
        XCTAssertFalse(store.game.extraFoulUsed ?? true)
        store.recordPitch(.foul)
        XCTAssertTrue(store.game.extraFoulUsed == true)
        store.undo(); XCTAssertFalse(store.game.extraFoulUsed ?? true)
        store.redo(); XCTAssertTrue(store.game.extraFoulUsed == true)
        let restored = GameStore(persistenceURL: nil)
        try restored.restoreBackup(LocalBackup.read(store.exportBackup()))
        XCTAssertTrue(restored.game.extraFoulUsed == true)
        restored.recordPitch(.ball)
        restored.recordPitch(.foul)
        XCTAssertEqual(restored.pitchingLine(for: pitcher).strikeouts, 1)
        XCTAssertEqual(restored.pitchingLine(for: pitcher).outsRecorded, 1)
        XCTAssertEqual(restored.pitchingLine(for: pitcher).pitches, 4)
        XCTAssertEqual(restored.game.strikes, 1); XCTAssertEqual(restored.game.balls, 1)
        XCTAssertFalse(restored.game.extraFoulUsed ?? true)
        XCTAssertTrue(restored.game.scoringEvents?.contains { $0.notation == "K-F" } == true)
    }

    func testV23SlowPitchImmediateFoulAndInitialTwoStrikesEachCountActualPitchesOnce() throws {
        let immediate = try slowStore(policy: .outImmediately)
        let pitcher = immediate.currentPitcher
        immediate.recordPitch(.foul); immediate.recordPitch(.foul)
        XCTAssertEqual(immediate.pitchingLine(for: pitcher).outsRecorded, 1)
        XCTAssertEqual(immediate.pitchingLine(for: pitcher).pitches, 2)
        let initialTwo = try slowStore(strikes: 2)
        initialTwo.recordPitch(.foul)
        XCTAssertEqual(initialTwo.game.outs, 0)
        XCTAssertTrue(initialTwo.game.extraFoulUsed == true)
        initialTwo.recordPitch(.foul)
        XCTAssertEqual(initialTwo.game.outs, 1)
    }

    func testV23SlowPitchDifferentOrdersFreeAndTimeGameContinuePastSevenInnings() throws {
        let store = try slowStore()
        XCTAssertEqual(store.game.awayBattingOrderIDs.count, 12)
        XCTAssertEqual(store.game.homeBattingOrderIDs.count, 11)
        XCTAssertEqual(store.game.awayFieldingPlayerIDs?.count, 10)
        XCTAssertEqual(store.game.homeFieldingPlayerIDs?.count, 10)
        XCTAssertEqual(FieldPosition.free.rawValue, 10)
        XCTAssertEqual(FieldPosition.free.shortName, "Free")
        for _ in 0..<48 { XCTAssertTrue(store.applyPlay(.groundOut)) }
        XCTAssertEqual(store.game.inning, 9)
        XCTAssertNil(store.game.pendingDecision); XCTAssertFalse(store.game.isFinal)
        XCTAssertEqual(store.game.awayBatterIndex, 0) // 24 appearances / 12
        XCTAssertEqual(store.game.homeBatterIndex, 2) // 24 appearances / 11
        XCTAssertEqual(store.game.balls, 1); XCTAssertEqual(store.game.strikes, 1)
        XCTAssertFalse(store.ruleNotices().contains { $0.contains("末局") || $0.contains("延长") })
    }

    func testV23IllegalPenaltySwingFairBallAndNoPitchHaveOnePhysicalPitchAtMost() throws {
        let store = try slowStore()
        let pitcher = store.currentPitcher
        XCTAssertTrue(store.recordIllegalPitch(.ball, wasThrown: false))
        XCTAssertEqual(store.game.balls, 2); XCTAssertEqual(store.pitchingLine(for: pitcher).pitches, 0)
        XCTAssertTrue(store.recordIllegalPitch(.swingingStrike, wasThrown: true))
        XCTAssertEqual(store.game.balls, 2); XCTAssertEqual(store.game.strikes, 2)
        XCTAssertEqual(store.pitchingLine(for: pitcher).pitches, 1)
        XCTAssertTrue(store.recordIllegalPitch(nil, wasThrown: true, outcome: .single))
        XCTAssertEqual(store.pitchingLine(for: pitcher).pitches, 2)
        XCTAssertEqual(store.pitchingLine(for: pitcher).hits, 1)
        let before = store.game.baseRunners
        XCTAssertTrue(store.recordIllegalPitch(nil, wasThrown: false))
        XCTAssertEqual(store.game.baseRunners, before)
        XCTAssertEqual(store.pitchingLine(for: pitcher).pitches, 2)
        XCTAssertEqual(store.game.scoringEvents?.filter { $0.illegalPitch != nil }.count, 4)
        let restored = GameStore(persistenceURL: nil)
        let backup = try LocalBackup.read(store.exportBackup())
        XCTAssertEqual(backup.version, 7)
        try restored.restoreBackup(backup)
        XCTAssertEqual(restored.game, store.game)
    }

    func testV23ExtraHitterRotatesIntoDefenseWithoutChangingOrderOrCount() throws {
        let store = try slowStore()
        store.recordPitch(.ball)
        let order = store.game.homeBattingOrderIDs, before = store.game
        var draft = store.lineupDraft(forHomeTeam: true)
        let incoming = order[10], outgoing = try XCTUnwrap(draft.pitcherID)
        draft.rotateIntoFielding(playerID: incoming, replacing: outgoing)
        XCTAssertNil(draft.validationMessage)
        XCTAssertTrue(store.saveLineup(draft))
        XCTAssertEqual(store.game.homeBattingOrderIDs, order)
        XCTAssertEqual(store.game.homeFieldingPlayerIDs?.count, 10)
        XCTAssertEqual(store.game.activeHomePitcherID, incoming)
        XCTAssertEqual(store.game.balls, before.balls); XCTAssertEqual(store.game.strikes, before.strikes)
        XCTAssertFalse(store.game.homeExitedPlayerIDs?.contains(outgoing) ?? false)
        XCTAssertFalse(store.canReachOnDroppedThirdStrike)
        XCTAssertFalse(store.applyPlay(.sacrificeBunt))
    }

    func testV23PregameRosterSetsOpeningBatterButLiveReorderKeepsCurrentBatter() throws {
        let store = freshStore()
        let first = store.currentBatter.id, next = store.game.awayBattingOrderIDs[1]
        var pregame = store.lineupDraft(forHomeTeam: false)
        pregame.moveBatter(from: 0, to: 1)
        XCTAssertTrue(store.saveLineup(pregame))
        XCTAssertEqual(store.currentBatter.id, next)
        XCTAssertFalse((store.game.scoringEvents ?? []).contains { $0.category == .substitution })
        let openingLineup = try XCTUnwrap(store.activeStoredGame?.lineup)
        store.undo()
        XCTAssertEqual(store.currentBatter.id, first)
        XCTAssertEqual(store.activeStoredGame?.lineup.map(\.playerID), store.game.awayBattingOrderIDs)
        store.redo()
        XCTAssertEqual(store.currentBatter.id, next)
        XCTAssertEqual(store.activeStoredGame?.lineup, openingLineup)
        store.startGameClock()
        var live = store.lineupDraft(forHomeTeam: false)
        live.moveBatter(from: 0, to: 1)
        XCTAssertTrue(store.saveLineup(live))
        XCTAssertEqual(store.currentBatter.id, next)
        XCTAssertNotEqual(store.currentBatter.id, first)
        XCTAssertTrue((store.game.scoringEvents ?? []).contains { $0.category == .substitution })
        XCTAssertEqual(store.activeStoredGame?.lineup, openingLineup, "Live edits must preserve the initial lineup")
    }

    func testV23PregameLineupChangesStayOutOfAllGameRecordExports() throws {
        for store in [freshStore(), try slowStore()] {
            for isHome in [false, true, false] {
                var draft = store.lineupDraft(forHomeTeam: isHome)
                draft.moveBatter(from: 0, to: 1)
                XCTAssertTrue(store.saveLineup(draft))
            }
            let setupIDs = Set((store.game.scoringEvents ?? []).filter(\.isPregameLineupAdjustment).map(\.id))
            XCTAssertEqual(setupIDs.count, 3)
            let openingBatter = store.currentBatter
            store.startGameClock()
            var live = store.lineupDraft(forHomeTeam: false)
            live.moveBatter(from: 0, to: 1)
            XCTAssertTrue(store.saveLineup(live))
            let liveEvent = try XCTUnwrap(store.game.scoringEvents?.last { $0.notation == "LINEUP" })
            store.recordPitch(.ball)
            XCTAssertTrue(store.applyPlay(.single))
            let saved = store.game
            let appearances = store.plateAppearanceRecords()
            let opening = try XCTUnwrap(appearances.first)
            XCTAssertEqual(opening.batter.id, openingBatter.id)
            let report = PlayByPlayPDFReport(game: saved, appearances: appearances, playedAt: nil,
                                             gameMode: store.activeRules?.gameMode ?? .standard)
            XCTAssertTrue(report.entries.first?.events.contains { $0.id == liveEvent.id } == true)
            XCTAssertTrue(report.entries.allSatisfy { entry in entry.events.allSatisfy { !setupIDs.contains($0.id) } })
            let text = GameExportService(game: saved, rules: store.activeRules, plateAppearances: appearances,
                                         gameEvents: store.nonPlateAppearanceGameEvents()).completeRecordText()
            XCTAssertFalse(text.contains("赛前阵容已确认"))
            XCTAssertFalse(text.contains("PREGAME-LINEUP"))
            XCTAssertTrue(text.contains(liveEvent.title))
            for style in [PlayByPlayPDFStyle.detailed, .textOnly] {
                for appearanceID in [nil, Optional(opening.id)] {
                    let data = report.pdfData(appearanceID: appearanceID, style: style)
                    let pdfText = try reportText(data, portrait: style == .textOnly)
                        .components(separatedBy: .whitespacesAndNewlines).joined()
                    XCTAssertFalse(pdfText.contains("赛前阵容已确认"))
                    XCTAssertTrue(pdfText.contains("阵容调整"))
                    XCTAssertTrue(pdfText.contains("一垒安打"))
                    if store.activeRules?.gameMode == .standard {
                        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "com.adobe.pdf")
                        attachment.name = "pregame-filter-\(style == .detailed ? "detailed" : "text")-\(appearanceID == nil ? "full" : "single").pdf"
                        attachment.lifetime = .keepAlways
                        add(attachment)
                    }
                }
            }
            XCTAssertEqual(store.game, saved, "Export must preserve saved setup history, initial lineup and statistics")
        }
    }

    func testV23SavedPregameLineupEventWithAppearanceOwnerIsExcludedAfterRestore() throws {
        let source = freshStore()
        var draft = source.lineupDraft(forHomeTeam: false)
        draft.moveBatter(from: 0, to: 1)
        XCTAssertTrue(source.saveLineup(draft))
        source.startGameClock()
        source.recordPitch(.ball)
        let appearanceID = try XCTUnwrap(source.game.currentPlateAppearanceID)
        let setupIndex = try XCTUnwrap(source.game.scoringEvents?.firstIndex { $0.isPregameLineupAdjustment })
        // Simulate a saved setup event incorrectly attached to the first PA.
        source.game.scoringEvents?[setupIndex].plateAppearanceID = appearanceID
        source.game.scoringEvents?[setupIndex].needsReview = true
        let restored = GameStore(persistenceURL: nil)
        try restored.restoreBackup(LocalBackup.read(source.exportBackup()))
        let saved = restored.game
        XCTAssertTrue(saved.scoringEvents?.contains { $0.isPregameLineupAdjustment } == true)
        let appearances = restored.plateAppearanceRecords()
        let opening = try XCTUnwrap(appearances.first)
        XCTAssertFalse(opening.needsReview)
        XCTAssertFalse(opening.chineseRecord.contains("赛前阵容"))
        XCTAssertEqual(opening.events.map(\.category), [.pitch])
        let report = PlayByPlayPDFReport(game: saved, appearances: appearances, playedAt: nil)
        XCTAssertFalse(report.entries.flatMap(\.events).contains { $0.isPregameLineupAdjustment })
        let text = GameExportService(game: saved, rules: restored.activeRules, plateAppearances: appearances,
                                     gameEvents: restored.nonPlateAppearanceGameEvents()).completeRecordText()
        XCTAssertFalse(text.contains("赛前阵容"))
        XCTAssertTrue(text.contains("坏球"))
        XCTAssertEqual(restored.game, saved)
    }

    func testV23LegacyLogFallbackOmitsLinkedPregameLineupChanges() throws {
        let store = freshStore()
        var draft = store.lineupDraft(forHomeTeam: true)
        draft.moveBatter(from: 0, to: 1)
        XCTAssertTrue(store.saveLineup(draft))
        let saved = store.game
        let report = PlayByPlayPDFReport(game: saved, appearances: [], playedAt: nil)
        let text = try reportText(report.pdfData()).components(separatedBy: .whitespacesAndNewlines).joined()
        XCTAssertTrue(text.contains("原始比赛记录"))
        XCTAssertFalse(text.contains("赛前阵容已确认"))
        XCTAssertEqual(store.game, saved)
    }
}

extension GameStoreTests {
    func testV23UpgradeRepairsCompleteLegacyPitcherOutHistoryButPreservesPartialHistory() throws {
        for complete in [true, false] {
            let original = freshStore(), pitcher = original.currentPitcher
            XCTAssertTrue(original.applyPlay(.single))
            for _ in 0..<6 { original.recordPitch(.calledStrike) }
            let runner = try XCTUnwrap(original.game.baseRunners[.first])
            XCTAssertTrue(original.recordRunnerEvent(.pickoff, decisions: [RunnerDecision(player: runner, origin: .base(.first), destination: .out)]))
            let valid = original.game
            original.game.pitching[pitcher.id]?.outsRecorded = 2 // the 2.2 omission
            original.game.pitchingOutsAccountingVersion = nil
            original.game.rulesSnapshot = nil
            if complete, var journal = original.game.historyJournal {
                var checkpoint = try JSONDecoder().decode(GameState.self, from: journal.checkpoint)
                checkpoint.pitchingOutsAccountingVersion = nil; checkpoint.rulesSnapshot = nil
                journal.checkpoint = try checkpoint.historyData()
                original.game.historyJournal = journal
            } else { original.game.historyJournal = nil }
            let id = try XCTUnwrap(original.activeGameID)
            original.games[try XCTUnwrap(original.games.firstIndex { $0.id == id })].state = original.game
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            let database = directory.appendingPathComponent("BaseballMaster.sqlite")
            let destination = GameStore(persistenceURL: database)
            try destination.restoreBackup(LocalBackup.read(original.exportBackup()))
            let reopened = GameStore(persistenceURL: database)
            XCTAssertFalse(reopened.requiresDataRecovery)
            XCTAssertEqual(reopened.pitchingLine(for: pitcher).outsRecorded, complete ? 3 : 2)
            XCTAssertEqual(reopened.game.pitchingOutsReviewRequired, !complete)
            XCTAssertEqual(reopened.game.batting, valid.batting)
            XCTAssertEqual(reopened.game.homeRunsByInning, valid.homeRunsByInning)
            XCTAssertEqual(reopened.pitchingLine(for: pitcher).pitches, valid.pitching[pitcher.id]?.pitches)
            if complete {
                let audit = reopened.game.pitchingOutsBeforeRepair
                XCTAssertEqual(audit?[pitcher.id], 2)
                var draft = try reopened.makeCorrectionDraft()
                let pitchIndex = try XCTUnwrap(draft.journal.operations.firstIndex { if case .pitch = $0.command { return true }; return false })
                draft.journal.operations[pitchIndex].command = .pitch(.swingingStrike)
                draft.journal.operations[pitchIndex].edited = true
                draft.reason = "核对第一颗好球为挥空"
                XCTAssertTrue(reopened.saveCorrection(draft), reopened.actionErrorMessage ?? "")
                XCTAssertEqual(reopened.game.pitchingOutsAccountingVersion, 2)
                XCTAssertEqual(reopened.game.pitchingOutsReviewRequired, false)
                XCTAssertEqual(reopened.game.pitchingOutsBeforeRepair, audit)
                let revision = reopened.activeStoredGame?.revision
                let afterCorrection = GameStore(persistenceURL: database)
                XCTAssertFalse(afterCorrection.requiresDataRecovery)
                XCTAssertEqual(afterCorrection.game.pitchingOutsBeforeRepair, audit)
                XCTAssertEqual(afterCorrection.activeStoredGame?.revision, revision, "Reopening must not repeat a completed repair")
            }
        }
    }

    func testV23SlowHistoryReplayPreservesFoulChanceAndCanChangeIllegalToOrdinaryPitch() throws {
        let store = try slowStore()
        store.recordPitch(.foul)
        XCTAssertTrue(store.recordIllegalPitch(.foul, wasThrown: true))
        XCTAssertTrue(store.game.extraFoulUsed == true)
        store.recordPitch(.ball)
        var draft = try store.makeCorrectionDraft()
        let index = try XCTUnwrap(draft.journal.operations.firstIndex { if case .illegalPitch = $0.command { return true }; return false })
        draft.journal.operations[index].command = .pitch(.foul)
        draft.journal.operations[index].edited = true
        let preview = store.previewCorrection(draft)
        XCTAssertTrue(preview.canSave, preview.conflict ?? "")
        XCTAssertTrue(preview.game.extraFoulUsed == true)
        XCTAssertEqual(preview.game.balls, store.game.balls)
        XCTAssertEqual(preview.game.strikes, 2)
        XCTAssertTrue(store.saveCorrection(draft))
        XCTAssertFalse(store.game.scoringEvents?.contains { $0.illegalPitch != nil } ?? true)
        store.recordPitch(.foul)
        XCTAssertEqual(store.game.outs, 1)
        XCTAssertFalse(store.game.extraFoulUsed ?? true)
    }

    func testV23FreeTwoDigitNotationFullOrderAndTimedReportsMatchSavedGame() throws {
        let store = try slowStore(), pitcher = store.currentPitcher
        let free = try XCTUnwrap(store.activeFielders.first { $0.primaryPosition == .free })
        let error = DefensivePlay(id: "E10", title: "自由人失误", notation: "E10", assistPositions: [], putoutPosition: nil, errorPosition: .free)
        XCTAssertTrue(store.applyPlay(.error, defensivePlay: error))
        let route = DefensivePlay(id: "10-6-3", title: "自由人传游击再传一垒", notation: "10-6-3", assistPositions: [.free, .shortstop], putoutPosition: .firstBase, errorPosition: nil)
        XCTAssertTrue(store.applyPlay(.groundOut, defensivePlay: route))
        XCTAssertEqual(store.fieldingLine(for: free).errors, 1)
        XCTAssertEqual(store.fieldingLine(for: free).assists, 1)
        XCTAssertEqual(store.pitchingLine(for: pitcher).outsRecorded, 1)
        let exporter = GameExportService(game: store.game, rules: store.activeRules, plateAppearances: store.plateAppearanceRecords(), gameEvents: store.nonPlateAppearanceGameEvents())
        let text = exporter.completeRecordText()
        XCTAssertTrue(text.contains("E10")); XCTAssertTrue(text.contains("10-6-3"))
        XCTAssertTrue(text.contains("12棒")); XCTAssertTrue(text.contains("暂不守备")); XCTAssertTrue(text.contains("Free"))
        XCTAssertFalse(text.replacingOccurrences(of: " ", with: "").contains("规定6局"))
        let box = exporter.boxScorePDFData()
        let boxText = try XCTUnwrap(PDFDocument(data: box)?.string)
        let normalized = boxText.components(separatedBy: .whitespacesAndNewlines).joined()
        XCTAssertTrue(normalized.contains("Free"), "Free role missing")
        XCTAssertTrue(normalized.contains("12棒"), "Complete batting order missing")
        XCTAssertTrue(normalized.contains("时间赛"), "Timed rules missing")
        let playByPlay = PlayByPlayPDFReport(game: store.game, appearances: store.plateAppearanceRecords(), playedAt: nil, gameMode: .slowPitch)
        for style in [PlayByPlayPDFStyle.detailed, .textOnly] {
            let data = playByPlay.pdfData(style: style)
            let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "com.adobe.pdf")
            attachment.name = style == .detailed ? "slow-detailed.pdf" : "slow-text.pdf"
            attachment.lifetime = .keepAlways; add(attachment)
            let pdfText = try XCTUnwrap(PDFDocument(data: data)?.string)
            XCTAssertTrue(pdfText.contains("10-6-3")); XCTAssertTrue(pdfText.contains("E10"))
            XCTAssertTrue(pdfText.components(separatedBy: .whitespacesAndNewlines).joined().contains("时间赛"))
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("V23-slow-\(style == .detailed ? "detailed" : "text").pdf")
            try data.write(to: url); print("V23_REPORT_PATH=\(url.path)")
        }
        let boxAttachment = XCTAttachment(data: box, uniformTypeIdentifier: "com.adobe.pdf")
        boxAttachment.name = "slow-boxscore.pdf"; boxAttachment.lifetime = .keepAlways; add(boxAttachment)
        let boxURL = FileManager.default.temporaryDirectory.appendingPathComponent("V23-slow-boxscore.pdf")
        try box.write(to: boxURL); print("V23_REPORT_PATH=\(boxURL.path)")
        let target = GameStore(persistenceURL: nil)
        try target.restoreBackup(LocalBackup.read(store.exportBackup()))
        XCTAssertEqual(target.game.homeFieldingPlayerIDs?.count, 10)
        XCTAssertEqual(target.game.awayBattingOrderIDs.count, 12)
    }

    func testV23IllegalBallForcesOnlyOnWalkAndNeverAppliesBalkAdvancement() throws {
        let store = try slowStore(), pitcher = store.currentPitcher
        XCTAssertTrue(store.applyPlay(.single))
        let runner = try XCTUnwrap(store.game.baseRunners[.first])
        XCTAssertTrue(store.recordIllegalPitch(.ball, wasThrown: true))
        XCTAssertEqual(store.game.baseRunners[.first]?.id, runner.id)
        XCTAssertNil(store.game.baseRunners[.second])
        XCTAssertTrue(store.recordIllegalPitch(.ball, wasThrown: true, penaltyApplied: false))
        XCTAssertFalse(try XCTUnwrap(store.game.scoringEvents?.last { $0.illegalPitch != nil }).illegalPitch?.penaltyApplied ?? true)
        XCTAssertTrue(store.recordIllegalPitch(.ball, wasThrown: false))
        XCTAssertEqual(store.game.baseRunners[.second]?.id, runner.id)
        XCTAssertEqual(store.pitchingLine(for: pitcher).walks, 1)
        XCTAssertEqual(store.pitchingLine(for: pitcher).pitches, 3)
        XCTAssertEqual(store.game.balls, 1)
    }

    func testV23SlowRestartAndOptionalInningsLimitKeepIndependentRuleContexts() throws {
        let store = try slowStore()
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let database = root.appendingPathComponent("BaseballMaster.sqlite")
        let persisted = GameStore(persistenceURL: database)
        try persisted.restoreBackup(LocalBackup.read(store.exportBackup()))
        persisted.recordPitch(.foul); persisted.recordPitch(.foul)
        let reopened = GameStore(persistenceURL: database)
        XCTAssertTrue(reopened.game.extraFoulUsed == true)
        XCTAssertEqual(reopened.game.strikes, 2)
        XCTAssertEqual(reopened.activeRules?.gameMode, .slowPitch)
        let saved = try XCTUnwrap(store.activeStoredGame)
        var inningsRules = saved.rules
        inningsRules.competitionFormat = .innings; inningsRules.scheduledInnings = 1
        XCTAssertNotNil(store.startNewGame(opponent: store.opponentTeams[0], isHome: false, rules: inningsRules, lineup: saved.lineup, opponentLineup: saved.secondaryLineup))
        for _ in 0..<6 { XCTAssertTrue(store.applyPlay(.groundOut)) }
        XCTAssertEqual(store.game.pendingDecision, .regulationHalf)
        store.resolveGameDecision(finish: false)
        XCTAssertTrue(store.canConfirmExtraInning)
        XCTAssertEqual(store.game.balls, 1); XCTAssertEqual(store.game.strikes, 1)
    }

    func testV23SlowLiveHTTPUsesActualCountsAllSlotsAndSavedFoulState() async throws {
        let store = try slowStore()
        try await startV21Live(store)
        defer { store.liveBroadcasts.close(store.activeGameID!) }
        let before = try await readV21Live(store)
        XCTAssertEqual(before.mode, "slowPitch"); XCTAssertFalse(before.hasStarted ?? true)
        XCTAssertEqual(before.away.lineup?.filter { $0.order != nil }.count, 12)
        XCTAssertEqual(before.home.lineup?.filter { $0.order != nil }.count, 11)
        XCTAssertEqual(before.rules?.competitionFormat, "timed")
        XCTAssertEqual(before.pitchCount, 0)
        store.recordPitch(.foul); store.recordPitch(.foul)
        let after = try await readV21Live(store)
        XCTAssertEqual(after.pitchCount, 2); XCTAssertEqual(after.rules?.extraFoulUsed, true)
        XCTAssertEqual(after.strikes, 2)
        XCTAssertTrue(after.home.lineup?.contains { $0.position == "自由人" } ?? false)
    }
}

extension GameStoreTests {
    func testV23PregameSlowOrderCanGrowAndShrinkButLiveOrderLengthIsLocked() throws {
        let store = try slowStore()
        var draft = store.lineupDraft(forHomeTeam: false)
        let extra = try XCTUnwrap(draft.bench.first)
        draft.addExtraHitter(extra.id)
        XCTAssertTrue(store.saveLineup(draft))
        XCTAssertEqual(store.game.awayBattingOrderIDs.count, 13)
        XCTAssertEqual(store.activeStoredGame?.lineup.count, 13)
        XCTAssertEqual(store.game.awayFieldingPlayerIDs?.count, 10)
        var remove = store.lineupDraft(forHomeTeam: false)
        remove.removeExtraHitter(extra.id)
        XCTAssertTrue(store.saveLineup(remove)); XCTAssertEqual(store.game.awayBattingOrderIDs.count, 12)
        store.startGameClock()
        var live = store.lineupDraft(forHomeTeam: false)
        live.addExtraHitter(extra.id)
        XCTAssertFalse(live.hasChanges)
        XCTAssertEqual(live.battingOrderIDs.count, 12)
    }

    func testV23TimedExpirationPromptsWithoutCreatingOutOrEndingAppearanceAutomatically() throws {
        let store = try slowStore()
        store.startGameClock(at: Date().addingTimeInterval(-6000))
        XCTAssertTrue((store.remainingGameTime() ?? 1) <= 0)
        XCTAssertTrue(store.canRecordAction); XCTAssertFalse(store.game.isFinal)
        store.recordPitch(.ball)
        XCTAssertEqual(store.game.outs, 0)
        XCTAssertEqual(store.game.batting[store.currentBatter.id]?.plateAppearances ?? 0, 0)
        store.finishGame(reason: .timeLimit)
        XCTAssertTrue(store.game.isFinal); XCTAssertEqual(store.game.endReason, .timeLimit)
        XCTAssertEqual(store.game.outs, 0)
    }

    func testV23TimeGameStartsWithRemainingTimeAndResumePreservesUserChoice() throws {
        let store = try slowStore()
        let start = Date()
        XCTAssertEqual(store.gameClockDisplayMode, .remaining)
        store.startGameClock(at: start)
        XCTAssertEqual(store.gameClockDisplayMode, .remaining)
        store.pauseGameClock(at: start.addingTimeInterval(20))
        store.toggleGameClockDisplayMode()
        XCTAssertEqual(store.gameClockDisplayMode, .elapsed)
        store.startGameClock(at: start.addingTimeInterval(30))
        XCTAssertEqual(store.gameClockDisplayMode, .elapsed)
        XCTAssertEqual(store.elapsedGameTime(at: start.addingTimeInterval(35)), 25, accuracy: 0.01)
        let standard = freshStore()
        standard.startGameClock(at: start)
        XCTAssertEqual(standard.gameClockDisplayMode, .elapsed)
    }

    func testV23ObservedSlowImmediateAndScheduledDraftPreserveBothOrdersAndPitchers() throws {
        for scheduled in [false, true] {
            let source = GameStore(persistenceURL: nil)
            func team(_ name: String) -> Team {
                Team(name: name, shortName: name, city: "", players: (0..<14).map {
                    Player(name: name + "\($0+1)", number: $0+1, primaryPosition: FieldPosition.allCases[$0 % 10])
                })
            }
            let away = team("慢垒客队"), home = team("慢垒主队")
            source.opponentTeams = [away, home]
            func assignments(_ team: Team, _ count: Int) -> [LineupAssignment] {
                team.players.prefix(count).enumerated().map {
                    LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset+1,
                                     position: $0.offset < 10 ? FieldPosition.allCases[$0.offset] : nil)
                }
            }
            var awayOrder = assignments(away, 12), homeOrder = assignments(home, 11)
            (awayOrder[0].position, awayOrder[3].position) = (awayOrder[3].position, awayOrder[0].position)
            (homeOrder[0].position, homeOrder[5].position) = (homeOrder[5].position, homeOrder[0].position)
            var rules = GameRules(fieldersCount: 10, timeLimitMinutes: 90)
            rules.mode = .slowPitch; rules.competitionFormat = .timed; rules.twoStrikeFoulPolicy = .oneExtraFoul
            var draft = rules
            if scheduled { draft.timeLimitMinutes = nil; draft.twoStrikeFoulPolicy = nil }
            let id = try XCTUnwrap(source.createObservedGame(awayTeam: away, homeTeam: home, rules: draft,
                awayLineup: awayOrder, homeLineup: homeOrder, startImmediately: !scheduled))
            var store = source
            if scheduled {
                XCTAssertNil(source.activeGameID)
                store = GameStore(persistenceURL: nil)
                try store.restoreBackup(LocalBackup.read(source.exportBackup()))
                let saved = try XCTUnwrap(store.games.first { $0.id == id })
                XCTAssertEqual(saved.lineup, awayOrder); XCTAssertEqual(saved.secondaryLineup, homeOrder)
                XCTAssertFalse(store.startScheduledObservedGame(id: id, rules: saved.rules,
                    awayLineup: saved.lineup, homeLineup: saved.secondaryLineup))
                XCTAssertTrue(store.startScheduledObservedGame(id: id, rules: rules,
                    awayLineup: saved.lineup, homeLineup: saved.secondaryLineup))
            }
            XCTAssertTrue(try XCTUnwrap(store.activeStoredGame).isObservation)
            XCTAssertEqual(store.game.awayBattingOrderIDs, awayOrder.map(\.playerID))
            XCTAssertEqual(store.game.homeBattingOrderIDs, homeOrder.map(\.playerID))
            XCTAssertEqual(store.game.activeAwayPitcherID, awayOrder[3].playerID)
            XCTAssertEqual(store.game.activeHomePitcherID, homeOrder[5].playerID)
            XCTAssertEqual(store.game.awayFieldingPlayerIDs?.count, 10)
            XCTAssertEqual(store.game.homeFieldingPlayerIDs?.count, 10)
            XCTAssertEqual(store.game.balls, 1); XCTAssertEqual(store.game.strikes, 1)
            XCTAssertTrue(store.isPregame)
            XCTAssertEqual(store.pitchingLine(for: store.currentPitcher).pitches, 0)
            store.startGameClock()
            XCTAssertEqual(store.gameClockDisplayMode, .remaining)
        }
    }

    func testV23TwelfthHitterReplacementAndPitchingChangeRetainUsedFoulChance() throws {
        let store = try slowStore()
        for _ in 0..<11 { XCTAssertTrue(store.applyPlay(.single)) }
        XCTAssertEqual(store.currentBattingOrder, 12)
        store.recordPitch(.foul); store.recordPitch(.foul)
        let appearance = store.game.currentPlateAppearanceID
        let batter = try XCTUnwrap(store.battingBenchPlayers.first)
        store.replaceCurrentBatter(with: batter)
        let pitcher = try XCTUnwrap(store.fieldingBenchPlayers.first)
        store.changePitcher(to: pitcher)
        XCTAssertEqual(store.currentBattingOrder, 12)
        XCTAssertEqual(store.game.currentPlateAppearanceID, appearance)
        XCTAssertEqual(store.game.balls, 1); XCTAssertEqual(store.game.strikes, 2)
        XCTAssertEqual(store.game.extraFoulUsed, true)
        let restored = GameStore(persistenceURL: nil)
        try restored.restoreBackup(LocalBackup.read(store.exportBackup()))
        XCTAssertEqual(restored.game.extraFoulUsed, true)
        XCTAssertEqual(restored.game.currentBatter.id, batter.id)
        XCTAssertEqual(restored.game.currentPitcher.id, pitcher.id)
        restored.recordPitch(.foul)
        XCTAssertEqual(restored.pitchingLine(for: pitcher).pitches, 1)
        XCTAssertEqual(restored.pitchingLine(for: pitcher).outsRecorded, 1)
        XCTAssertEqual(restored.game.outs, 1)
        XCTAssertEqual(restored.currentBattingOrder, 1)
        XCTAssertEqual(restored.game.balls, 1); XCTAssertEqual(restored.game.strikes, 1)
        XCTAssertEqual(restored.game.extraFoulUsed, false)
    }
}

// 3.0: the date of the match is independent from entry timestamps and the clock.
extension GameStoreTests {
    private func v30Backfill(_ mode: GameMode, observed: Bool = false, persistenceURL: URL? = nil) throws -> GameStore {
        let store = GameStore(persistenceURL: persistenceURL)
        var rules = GameRules(fieldersCount: mode == .slowPitch ? 10 : 9, timeLimitMinutes: 90)
        rules.mode = mode
        if mode == .slowPitch { rules.competitionFormat = .timed; rules.twoStrikeFoulPolicy = .oneExtraFoul }
        func team(_ name: String) -> Team {
            Team(name: name, shortName: name, city: "", players: (0..<12).map {
                Player(name: "\(name)\($0)", number: $0, primaryPosition: FieldPosition.allCases[$0 % 10])
            })
        }
        let away = team("补录客队"), home = team("补录主队")
        let assignments = away.players.prefix(rules.fieldersCount).enumerated().map {
            LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset])
        }
        let past = try XCTUnwrap(ISO8601DateFormatter().date(from: "2024-02-29T06:00:00Z"))
        if observed {
            store.opponentTeams = [away, home]
            XCTAssertNotNil(store.createObservedGame(awayTeam: away, homeTeam: home, rules: rules, playedAt: past))
        } else {
            store.teams = [away]; store.currentTeam = away; store.opponentTeams = [home]
            XCTAssertNotNil(store.startNewGame(opponent: home, isHome: false, rules: rules, lineup: assignments, playedAt: past))
        }
        return store
    }

    func testV30BackfillSurvivesPlayBallAndClockForAllModesAndObservation() throws {
        for mode: GameMode in [.standard, .coachPitch, .slowPitch] {
            for observed in [false, true] {
                let store = try v30Backfill(mode, observed: observed)
                let original = try XCTUnwrap(store.activeStoredGame)
                let now = Date()
                store.startGameClock(at: now)
                XCTAssertEqual(store.activeGameRecordedAt, original.playedAt)
                XCTAssertEqual(store.activeStoredGame?.startedAt, now)
                XCTAssertEqual(store.elapsedGameTime(at: now.addingTimeInterval(30)), 30, accuracy: 0.01)
                XCTAssertEqual(try XCTUnwrap(store.remainingGameTime(at: now)), 5400, accuracy: 0.01)
                store.pauseGameClock(at: now.addingTimeInterval(60))
                XCTAssertEqual(store.activeGameRecordedAt, original.playedAt)
                let restored = GameStore(persistenceURL: nil)
                try restored.restoreBackup(LocalBackup.read(store.exportBackup()))
                XCTAssertEqual(restored.games.first?.playedAt, original.playedAt)
                XCTAssertEqual(restored.games.first?.playedTimeZoneID, original.playedTimeZoneID)
                XCTAssertEqual(restored.game.clockElapsedSeconds, 60)
            }
        }
    }

    func testV30DateCorrectionPreservesScoreClockJournalAndCreatesRevision() throws {
        let store = try v30Backfill(.standard)
        store.applyPlay(.single)
        store.finishGame()
        let original = try XCTUnwrap(store.activeStoredGame)
        let earlier = original.effectivePlayedAt.addingTimeInterval(-86400)
        XCTAssertTrue(store.updateCompletedGameDate(id: original.id, playedAt: earlier, timeZoneID: "Asia/Shanghai", expectedRevision: original.revision ?? 0))
        let corrected = try XCTUnwrap(store.activeStoredGame)
        XCTAssertEqual(corrected.state, original.state)
        XCTAssertEqual(store.game, original.state)
        XCTAssertEqual(corrected.createdAt, original.createdAt)
        XCTAssertEqual(corrected.startedAt, original.startedAt)
        XCTAssertEqual(corrected.seasonID, original.seasonID)
        XCTAssertEqual(corrected.revision, (original.revision ?? 0) + 1)
        XCTAssertEqual(corrected.dateRevisions?.first?.previousDate, original.effectivePlayedAt)
        XCTAssertEqual(corrected.effectivePlayedAt, earlier)
        XCTAssertEqual(corrected.dateText, "2024-02-28 14:00")
        let player = store.game.awayTeam.players[0]
        XCTAssertEqual(store.gameRecords(for: player, seasonID: corrected.seasonID).first?.date, earlier)
        let exporter = GameExportService(game: store.game, rules: store.activeRules, plateAppearances: store.plateAppearanceRecords(),
            gameEvents: store.nonPlateAppearanceGameEvents(), playedAt: corrected.effectivePlayedAt, timeZone: corrected.playedTimeZone)
        XCTAssertTrue(exporter.completeRecordText().contains("2024-02-28 14:00"))
        let pdf = try XCTUnwrap(PDFDocument(data: exporter.boxScorePDFData()))
        XCTAssertTrue(try XCTUnwrap(pdf.string).contains("2024-02-28 14:00"))
    }

    func testV30DateCorrectionRejectsFutureStaleAndOngoingAndRollsBackOnDiskFailure() throws {
        let store = freshStore()
        let id = try XCTUnwrap(store.activeGameID)
        XCTAssertFalse(store.updateCompletedGameDate(id: id, playedAt: Date().addingTimeInterval(-86400), timeZoneID: "UTC", expectedRevision: 0))
        store.finishGame()
        let before = try XCTUnwrap(store.activeStoredGame)
        let revision = before.revision ?? 0
        XCTAssertFalse(store.updateCompletedGameDate(id: id, playedAt: Date().addingTimeInterval(3600), timeZoneID: "UTC", expectedRevision: revision))
        XCTAssertFalse(store.updateCompletedGameDate(id: id, playedAt: Date(), timeZoneID: "bad-zone", expectedRevision: revision))
        XCTAssertFalse(store.updateCompletedGameDate(id: id, playedAt: Date(), timeZoneID: "UTC", expectedRevision: revision - 1))
        CoreDataRosterStore.writeFault = { throw LocalDataError.invalid("Synthetic disk failure") }
        defer { CoreDataRosterStore.writeFault = nil }
        XCTAssertFalse(store.updateCompletedGameDate(id: id, playedAt: Date().addingTimeInterval(-86400), timeZoneID: "UTC", expectedRevision: revision))
        XCTAssertEqual(store.activeStoredGame, before)
        XCTAssertEqual(store.game, before.state)
    }

    func testV30DateSortingAndBackupKeepAnnualTotalsWithoutDuplicates() throws {
        let store = try v30Backfill(.standard)
        store.finishGame()
        let first = try XCTUnwrap(store.activeStoredGame)
        XCTAssertNotNil(store.startNewGame(opponent: store.opponentTeams[0], isHome: false, rules: first.rules, lineup: first.lineup))
        store.finishGame()
        let second = try XCTUnwrap(store.activeStoredGame)
        XCTAssertEqual(store.recentGames.first?.id, second.id)
        XCTAssertTrue(store.updateCompletedGameDate(id: second.id, playedAt: first.effectivePlayedAt.addingTimeInterval(-86400), timeZoneID: "UTC", expectedRevision: second.revision ?? 0))
        XCTAssertEqual(store.recentGames.map(\.id), [first.id, second.id])
        XCTAssertEqual(store.completedStatisticsGames(seasonID: first.yearID).map(\.id), [first.id, second.id])
        let restored = GameStore(persistenceURL: nil)
        try restored.restoreBackup(LocalBackup.read(store.exportBackup()))
        XCTAssertEqual(restored.recentGames, store.recentGames)
        XCTAssertEqual(restored.seasonStatistics(for: try XCTUnwrap(restored.currentTeam), seasonID: first.yearID).games.count, 2)
    }

    func testV30OldGameDecodingKeepsFallbackAndInvalidTimeZoneBackupIsRejected() throws {
        let original = try XCTUnwrap(freshStore().activeStoredGame)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(original)) as? [String: Any])
        for key in ["playedAt", "playedTimeZoneID", "dateRevisions"] { object.removeValue(forKey: key) }
        let old = try JSONDecoder().decode(StoredGame.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertNil(old.playedAt); XCTAssertNil(old.dateRevisions)
        XCTAssertEqual(old.effectivePlayedAt, old.startedAt ?? old.scheduledAt ?? old.createdAt)
        let store = freshStore()
        store.games[0].playedTimeZoneID = "not-a-zone"
        XCTAssertThrowsError(try store.exportBackup())
    }

    func testV30LineupCandidateLabelsPreserveNumbersAndNoNameFallback() {
        var player = Player(name: "同名球员", number: 0, primaryPosition: .pitcher)
        player.jerseyNumbers = ["00", "12"]
        XCTAssertEqual(player.lineupSelectionText, "同名球员 · #00 / #12")
        player.jerseyNumbers = ["0"]
        XCTAssertEqual(player.lineupSelectionText, "同名球员 · #0")
        player.chineseName = ""
        XCTAssertEqual(player.lineupSelectionText, "#0")
        player.jerseyNumbers = []; player.numbers = []
        XCTAssertEqual(player.lineupSelectionText, "暂无背号")
        player.englishName = "Alex"
        XCTAssertEqual(player.lineupSelectionText, "Alex · 暂无背号")
    }
}

private struct V30MemoryTeamCredential: TeamCredentialStorage {
    let value: String
    func loadOrCreate() throws -> String { value }
}

private final class V30DirectoryMemoryStorage: TeamDirectoryStorage {
    var state = TeamDirectoryState()
    var saves = 0
    var failSaveAt: Int?
    var failLoad = false
    func load() throws -> TeamDirectoryState {
        if failLoad { throw CocoaError(.fileReadNoPermission) }
        return state
    }
    func save(_ state: TeamDirectoryState) throws {
        saves += 1
        if saves == failSaveAt { throw CocoaError(.fileWriteOutOfSpace) }
        self.state = state
    }
}

private actor V30DirectoryService: TeamDirectoryService {
    var epoch = UUID()
    var teamsValue: [SharedTeamSummary] = []
    var results: [UUID: SharedTeamMembership] = [:]
    var createIDs: [UUID] = []
    var enrollCount = 0
    var loseCreateResponse = false
    func loseNextResponse() { loseCreateResponse = true }
    func recoverServer() { epoch = UUID() }
    func enroll(deviceName: String) async throws -> UUID { enrollCount += 1; return UUID() }
    func teams() async throws -> SharedTeamList { SharedTeamList(teams: teamsValue, recoveryEpoch: epoch) }
    func createTeam(name: String, nickname: String, operationID: UUID, profile: SharedRosterSeed?, recoveryToken: String?) async throws -> SharedTeamMembership {
        createIDs.append(operationID)
        if let saved = results[operationID] { return saved }
        let result = SharedTeamMembership(teamID: UUID(), memberID: UUID(), role: .admin)
        results[operationID] = result
        teamsValue.append(SharedTeamSummary(id: result.teamID, name: name, revision: 1, role: .admin, memberID: result.memberID))
        if loseCreateResponse { loseCreateResponse = false; throw TeamServiceError.message("模拟响应丢失") }
        return result
    }
    func detail(teamID: UUID) async throws -> SharedTeamDetail {
        guard let team = teamsValue.first(where: { $0.id == teamID }) else { throw TeamServiceError.response(404, "无权访问") }
        return SharedTeamDetail(team: SharedTeamProfile(id: teamID, name: team.name, revision: team.revision, shortName: team.name, city: "", players: []), role: team.role, memberID: team.memberID)
    }
    func preview(token: String) async throws -> SharedInvitationPreview { throw TeamServiceError.response(410, "邀请失效") }
    func invite(teamID: UUID, role: SharedTeamRole, token: String, operationID: UUID) async throws -> SharedTeamInvitation { throw TeamServiceError.response(403, "无权邀请") }
    func join(token: String, nickname: String, operationID: UUID) async throws -> SharedTeamMembership { throw TeamServiceError.response(410, "邀请失效") }
    func invitations(teamID: UUID) async throws -> [SharedTeamInvitation] { [] }
    func members(teamID: UUID) async throws -> [SharedTeamMember] { [] }
    func revoke(teamID: UUID, invitationID: UUID, operationID: UUID) async throws { }
    func changeMember(teamID: UUID, member: SharedTeamMember, role: SharedTeamRole, remove: Bool, operationID: UUID) async throws { }
    func leave(teamID: UUID, operationID: UUID) async throws { }
}

extension GameStoreTests {
    func testV30SharedDirectoryRetryAfterRelaunchUsesSameOperationAndBinding() async throws {
        let api = V30DirectoryService(), storage = V30DirectoryMemoryStorage()
        let localID = UUID()
        let directory = TeamSharingStore(service: api, storage: storage)
        await directory.connect()
        await api.loseNextResponse()
        let sent = await directory.submit(.create(name: "待确认球队", nickname: "管理员", localTeamID: localID, profile: nil))
        XCTAssertFalse(sent)
        let pending = try XCTUnwrap(storage.state.pending)
        let reopened = TeamSharingStore(service: api, storage: storage)
        await reopened.connect()
        XCTAssertFalse(reopened.canMutate)
        let duplicate = await reopened.submit(.create(name: "误重复", nickname: "管理员", localTeamID: localID, profile: nil))
        XCTAssertFalse(duplicate)
        let retried = await reopened.retryPending()
        XCTAssertTrue(retried)
        let ids = await api.createIDs
        XCTAssertEqual(ids, [pending.id, pending.id])
        XCTAssertEqual(reopened.teams.count, 1)
        XCTAssertEqual(reopened.state.bindings.count, 1)
        XCTAssertEqual(reopened.state.bindings.first?.localTeamID, localID)
        XCTAssertNil(storage.state.pending)
    }

    func testV30SharedDirectoryRequiresDurableInputBeforeSending() async {
        let api = V30DirectoryService(), storage = V30DirectoryMemoryStorage()
        let directory = TeamSharingStore(service: api, storage: storage)
        await directory.connect()
        storage.failSaveAt = storage.saves + 1
        let sent = await directory.submit(.create(name: "未发送", nickname: "管理员", localTeamID: nil, profile: nil))
        XCTAssertFalse(sent)
        let ids = await api.createIDs
        XCTAssertTrue(ids.isEmpty)
        XCTAssertNil(storage.state.pending)
        let unreadable = V30DirectoryMemoryStorage(); unreadable.failLoad = true
        let offlineAPI = V30DirectoryService(), blocked = TeamSharingStore(service: offlineAPI, storage: unreadable)
        await blocked.connect()
        let count = await offlineAPI.enrollCount
        XCTAssertEqual(count, 0); XCTAssertFalse(blocked.canMutate)
    }

    func testV30SharedDirectoryAcknowledgmentDiskFailureRetainsRetryWithoutDuplicate() async throws {
        let api = V30DirectoryService(), storage = V30DirectoryMemoryStorage()
        let directory = TeamSharingStore(service: api, storage: storage)
        await directory.connect()
        storage.failSaveAt = storage.saves + 3 // pending, preflight refresh, acknowledged result
        let sent = await directory.submit(.create(name: "已提交待保存", nickname: "管理员", localTeamID: nil, profile: nil))
        XCTAssertFalse(sent); XCTAssertNotNil(storage.state.pending)
        storage.failSaveAt = nil
        let retried = await directory.retryPending()
        XCTAssertTrue(retried)
        let ids = await api.createIDs
        XCTAssertEqual(Set(ids).count, 1); XCTAssertEqual(directory.teams.count, 1)
    }

    func testV30SharedDirectoryRecoveryEpochBlocksOldPendingSubmission() async throws {
        let api = V30DirectoryService(), storage = V30DirectoryMemoryStorage()
        let directory = TeamSharingStore(service: api, storage: storage)
        await directory.connect(); await api.loseNextResponse()
        _ = await directory.submit(.create(name: "恢复待核对", nickname: "管理员", localTeamID: nil, profile: nil))
        await api.recoverServer()
        let retried = await directory.retryPending()
        XCTAssertFalse(retried); XCTAssertTrue(directory.state.requiresRecoveryReview)
        XCTAssertNotNil(directory.state.pending); XCTAssertTrue(directory.teams.isEmpty)
        let ids = await api.createIDs
        XCTAssertEqual(ids.count, 1)
        let reconciled = await directory.reconcileAfterRecovery()
        XCTAssertTrue(reconciled); XCTAssertFalse(directory.state.requiresRecoveryReview)
        XCTAssertNil(directory.state.pending); XCTAssertEqual(directory.teams.count, 1)
        let after = await api.createIDs; XCTAssertEqual(after.count, 1, "Recovery review must not replay the old create request")
    }

    func testV30SharedDirectoryEncryptsPendingSecretsAndRejectsForeignDevice() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("private/directory.sealed")
        let vault = V30MemoryTeamCredential(value: try TeamDeviceKeychain.randomSecret())
        let file = TeamDirectoryFile(url: url, vault: vault)
        let invitation = try TeamDeviceKeychain.randomSecret()
        var state = TeamDirectoryState()
        state.pending = PendingTeamCommand(id: UUID(), command: .join(token: invitation, nickname: "保密称呼"))
        try file.save(state)
        let raw = try Data(contentsOf: url)
        XCTAssertNil(raw.range(of: Data(invitation.utf8)))
        XCTAssertNil(raw.range(of: Data(vault.value.utf8)))
        XCTAssertEqual(try file.load().pending?.id, state.pending?.id)
        XCTAssertEqual(try url.deletingLastPathComponent().resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        let other = TeamDirectoryFile(url: url, vault: V30MemoryTeamCredential(value: try TeamDeviceKeychain.randomSecret()))
        XCTAssertThrowsError(try other.load())
        var tampered = raw; tampered[tampered.startIndex] ^= 1; try tampered.write(to: url)
        XCTAssertThrowsError(try file.load())
    }

    func testV30InvitationParsingAcceptsOnlyExplicitTrustedRoutesAndCanonicalSecrets() throws {
        let secret = try TeamDeviceKeychain.randomSecret()
        XCTAssertEqual(TeamInvitationLink.token(from: TeamInvitationLink.url(token: secret).absoluteString), secret)
        XCTAssertEqual(TeamInvitationLink.token(from: "baseballmaster://join#\(secret)"), secret)
        for prefix in ["https://evil.example/join#", "http://baseballmaster.cc/join#", "https://baseballmaster.cc/other#",
                       "https://baseballmaster.cc:444/join#", "https://user@baseballmaster.cc/join#", "https://baseballmaster.cc/join?token="] {
            XCTAssertNil(TeamInvitationLink.token(from: prefix + secret))
        }
        XCTAssertNil(TeamInvitationLink.token(from: String(repeating: "A", count: 42) + "B"))
    }

    func testV30DirectoryClientRosterMemberManagementAndLeaveWithLocalServer() async throws {
        let url = URL(string: "http://127.0.0.1:18788/api/v3")!
        let admin = try TeamServiceClient(baseURL: url, vault: V30MemoryTeamCredential(value: TeamDeviceKeychain.randomSecret()))
        let viewer = try TeamServiceClient(baseURL: url, vault: V30MemoryTeamCredential(value: TeamDeviceKeychain.randomSecret()))
        _ = try await admin.enroll(deviceName: "名单管理设备"); _ = try await viewer.enroll(deviceName: "受邀设备")
        var player = Player(name: "背号队员", number: 0, primaryPosition: .pitcher); player.jerseyNumbers = ["00", "0"]
        let local = Team(name: "共享名单验收", shortName: "名单", city: "上海", players: [player])
        let membership = try await admin.createTeam(name: local.name, nickname: "管理员", operationID: UUID(), profile: SharedRosterSeed(team: local))
        let token = try TeamDeviceKeychain.randomSecret()
        let invitation = try await admin.invite(teamID: membership.teamID, role: .member, token: token, operationID: UUID())
        let preview = try await viewer.preview(token: token)
        XCTAssertEqual(preview.teamName, local.name); XCTAssertEqual(preview.role, .member)
        let guest = try await viewer.join(token: token, nickname: "球员家长", operationID: UUID())
        let detail = try await viewer.detail(teamID: guest.teamID)
        XCTAssertEqual(detail.team.players.first?.numbers, ["00", "0"])
        XCTAssertNotEqual(detail.team.players.first?.id, player.id)
        let members = try await admin.members(teamID: membership.teamID)
        let member = try XCTUnwrap(members.first { $0.id == guest.memberID })
        try await admin.changeMember(teamID: membership.teamID, member: member, role: .scorer, remove: false, operationID: UUID())
        let promoted = try await viewer.detail(teamID: guest.teamID)
        XCTAssertEqual(promoted.role, .scorer)
        do { _ = try await viewer.members(teamID: guest.teamID); XCTFail("Scorer cannot manage members") } catch { XCTAssertTrue(error.localizedDescription.contains("当前角色")) }
        let activeInvites = try await admin.invitations(teamID: membership.teamID)
        XCTAssertFalse(activeInvites.contains { $0.id == invitation.id })
        let leave = UUID(); try await viewer.leave(teamID: guest.teamID, operationID: leave)
        try await viewer.leave(teamID: guest.teamID, operationID: leave)
        let remaining = try await viewer.teams(); XCTAssertTrue(remaining.teams.isEmpty)
        do { try await admin.leave(teamID: membership.teamID, operationID: UUID()); XCTFail("Last admin cannot leave") }
        catch { XCTAssertTrue(error.localizedDescription.contains("另一名管理员")) }
    }
}

extension GameStoreTests {
    func testV30TeamClientCreateInviteJoinAndReadAcrossTwoDevices() async throws {
        let url = URL(string: "http://127.0.0.1:18788/api/v3")!
        var probe = URLRequest(url: url.appendingPathComponent("teams"))
        probe.timeoutInterval = 2
        do {
            let (_, response) = try await URLSession.shared.data(for: probe)
            XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 426)
        } catch let error as URLError where [.cannotConnectToHost, .timedOut].contains(error.code) {
            throw XCTSkip("Run the local collaboration integration server on port 18788 for this test")
        }
        let admin = try TeamServiceClient(baseURL: url, vault: V30MemoryTeamCredential(value: TeamDeviceKeychain.randomSecret()))
        let viewer = try TeamServiceClient(baseURL: url, vault: V30MemoryTeamCredential(value: TeamDeviceKeychain.randomSecret()))
        let deviceID = try await admin.enroll(deviceName: "管理员测试手机")
        let retryID = try await admin.enroll(deviceName: "管理员测试手机")
        XCTAssertEqual(deviceID, retryID)
        _ = try await viewer.enroll(deviceName: "成员测试手机")
        let operationID = UUID()
        let team = try await admin.createTeam(name: "端到端合成球队", nickname: "管理员", operationID: operationID)
        let repeatTeam = try await admin.createTeam(name: "端到端合成球队", nickname: "管理员", operationID: operationID)
        XCTAssertEqual(team.teamID, repeatTeam.teamID)
        let invite = try TeamDeviceKeychain.randomSecret()
        let invitation = try await admin.invite(teamID: team.teamID, role: .member, token: invite, operationID: UUID())
        XCTAssertEqual(invitation.role, .member)
        let joinOperation = UUID()
        let member = try await viewer.join(token: invite, nickname: "队员", operationID: joinOperation)
        XCTAssertEqual(member.teamID, team.teamID); XCTAssertEqual(member.role, .member)
        let replay = try await viewer.join(token: invite, nickname: "队员", operationID: joinOperation)
        XCTAssertEqual(replay.memberID, member.memberID)
        let list = try await viewer.teams()
        XCTAssertEqual(list.teams.count, 1); XCTAssertEqual(list.teams.first?.name, "端到端合成球队")
        do {
            _ = try await viewer.invite(teamID: team.teamID, role: .scorer, token: TeamDeviceKeychain.randomSecret(), operationID: UUID())
            XCTFail("Member cannot invite")
        } catch { XCTAssertEqual(error.localizedDescription, "当前角色不能执行此操作。") }
    }

    func testV30TeamCredentialPersistsLocallyAndDoesNotSynchronize() throws {
        let account = "test-" + UUID().uuidString
        let vault = TeamDeviceKeychain(account: account)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.jasonchen.baseballmaster.collaboration", kSecAttrAccount as String: account]
        defer { SecItemDelete(query as CFDictionary) }
        let value = try vault.loadOrCreate()
        XCTAssertEqual(value.count, 43)
        XCTAssertEqual(try vault.loadOrCreate(), value)
        var lookup = query; lookup[kSecReturnAttributes as String] = true
        var attributes: CFTypeRef?
        XCTAssertEqual(SecItemCopyMatching(lookup as CFDictionary, &attributes), errSecSuccess)
        let result = try XCTUnwrap(attributes as? [String: Any])
        XCTAssertEqual(result[kSecAttrAccessible as String] as? String, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
        XCTAssertEqual((result[kSecAttrSynchronizable as String] as? NSNumber)?.boolValue ?? false, false)
    }

    func testV30BackfillAndDateCorrectionSurviveDatabaseReopen() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("roster.sqlite")
        let store = try v30Backfill(.coachPitch, persistenceURL: url)
        store.startGameClock(); store.pauseGameClock(); store.finishGame()
        let game = try XCTUnwrap(store.activeStoredGame)
        XCTAssertTrue(store.updateCompletedGameDate(id: game.id, playedAt: game.effectivePlayedAt.addingTimeInterval(-86400),
            timeZoneID: "Asia/Shanghai", expectedRevision: game.revision ?? 0))
        let restored = GameStore(persistenceURL: url)
        XCTAssertFalse(restored.requiresDataRecovery)
        XCTAssertEqual(restored.recentGames, store.recentGames)
        let db = try CoreDataRosterStore(storeURL: url)
        _ = try db.loadSnapshot()
        XCTAssertEqual(db.loadedSchemaVersion, 8)
        try db.close()
    }
}

extension GameStoreTests {
    func testV30PendingPitcherCannotRecordOrWriteAnAccidentalSubstitution() throws {
        let store = v111Store(rules: GameRules())
        store.recordPitch(.ball)
        let incoming = try XCTUnwrap(store.availablePitchers.first)
        let before = store.game, saved = store.activeStoredGame
        XCTAssertFalse(store.requestLineupChange(.pitcher(incoming)))
        XCTAssertNotNil(store.pendingPitcherChange)
        XCTAssertFalse(store.canRecordAction)
        store.recordPitch(.calledStrike)
        XCTAssertFalse(store.applyPlay(.groundOut))
        store.endCurrentHalf()
        store.undo()
        store.finishGame()
        XCTAssertEqual(store.game, before)
        XCTAssertEqual(store.activeStoredGame, saved)
        store.cancelPitcherChange()
        XCTAssertTrue(store.canRecordAction)
        XCTAssertEqual(store.game, before)
        XCTAssertEqual(store.pitchingLine(for: incoming), PitchingLine())
        store.undo()
        XCTAssertEqual(store.pitchingLine(for: before.currentPitcher).pitches, 0,
                       "Cancelled selection must not add an undo operation")
    }

    func testV30ConfirmedPitcherAccumulatesOnlyActualOutsAndCanUndo() throws {
        let store = v111Store(rules: GameRules(pitcherInningsLimit: 1))
        store.recordPitch(.ball)
        let previous = store.currentPitcher
        let incoming = try XCTUnwrap(store.availablePitchers.first)
        let before = store.game
        XCTAssertFalse(store.requestLineupChange(.pitcher(incoming)))
        let confirmation = try XCTUnwrap(store.pendingPitcherChange)
        XCTAssertTrue(store.confirmPitcherChange(id: confirmation.id))
        XCTAssertNil(store.pendingPitcherChange)
        XCTAssertTrue(store.canRecordAction)
        XCTAssertEqual(store.currentPitcher.id, incoming.id)
        XCTAssertEqual(store.pitchingLine(for: incoming).inningsText, "0.0")
        XCTAssertEqual(store.pitchingLine(for: previous).pitches, 1)
        let changed = store.game
        store.undo(); XCTAssertEqual(store.game, before)
        store.redo(); XCTAssertEqual(store.game, changed)
        XCTAssertFalse(store.confirmPitcherChange(id: confirmation.id), "Double confirmation cannot duplicate the change")
        store.recordPitch(.ball)
        XCTAssertEqual(store.pitchingLine(for: incoming).inningsText, "0.0")
        for outs in 1...3 {
            XCTAssertTrue(store.applyPlay(.groundOut))
            XCTAssertEqual(store.pitchingLine(for: incoming).outsRecorded, outs)
            XCTAssertEqual(store.pitchingLine(for: incoming).inningsText, outs == 3 ? "1.0" : "0.\(outs)")
        }
        XCTAssertNil(store.validateScoringTimeline())
        XCTAssertEqual(store.activeStoredGame?.state.pitching[incoming.id]?.outsRecorded, 3)
    }

    func testV30PitcherConfirmationPreservesInheritedRunnersAndWalkResponsibility() throws {
        let store = v111Store(rules: GameRules())
        XCTAssertTrue(store.applyPlay(.single))
        for _ in 0..<3 { store.recordPitch(.ball) }
        let previous = store.currentPitcher, runners = store.game.baseRunners
        let incoming = try XCTUnwrap(store.availablePitchers.first)
        XCTAssertFalse(store.requestLineupChange(.pitcher(incoming)))
        XCTAssertTrue(store.confirmPitcherChange(id: try XCTUnwrap(store.pendingPitcherChange).id))
        XCTAssertEqual(store.game.baseRunners, runners)
        XCTAssertEqual(store.game.walkResponsiblePitcherID, previous.id)
        XCTAssertTrue(runners.values.allSatisfy { store.game.runnerPitcherIDs?[$0.id] == previous.id })
        store.recordPitch(.ball)
        XCTAssertEqual(store.pitchingLine(for: previous).walks, 1)
        XCTAssertEqual(store.pitchingLine(for: incoming).walks, 0)
        XCTAssertEqual(store.pitchingLine(for: incoming).outsRecorded, 0)
    }

    func testV30EveryLiveLineupPathConfirmsPitcherAndCancelKeepsDraftUncommitted() throws {
        for path in 0..<6 {
            let store = v111Store(rules: GameRules(pitcherInningsLimit: 2))
            store.recordPitch(.ball)
            let original = store.currentPitcher
            let bench = store.fieldingBenchPlayers
            let catcher = try XCTUnwrap(store.activeFielders.first { $0.primaryPosition == .catcher })
            let change: GameStore.LineupChange
            switch path {
            case 0: change = .pitcher(try XCTUnwrap(bench.first))
            case 1: change = .fielder(original, try XCTUnwrap(bench.first))
            case 2: change = .position(catcher, .pitcher)
            case 3: change = .position(original, .catcher)
            case 4:
                XCTAssertGreaterThanOrEqual(bench.count, 2)
                change = .doubleSwitch(original, bench[0], .pitcher, catcher, bench[1], .catcher)
            default:
                var draft = store.lineupDraft(forHomeTeam: !store.game.isTop)
                let incoming = try XCTUnwrap(draft.players.first { draft.fieldingIDs.contains($0.id) && $0.primaryPosition == .catcher })
                draft.changePosition(playerID: incoming.id, to: .pitcher)
                change = .lineup(draft)
            }
            let before = store.game
            XCTAssertFalse(store.requestLineupChange(change), "path \(path)")
            XCTAssertNotNil(store.pendingPitcherChange, "path \(path)")
            XCTAssertEqual(store.game, before)
            store.cancelPitcherChange()
            XCTAssertEqual(store.game, before)
            XCTAssertFalse(store.requestLineupChange(change))
            let failedProposal = try XCTUnwrap(store.pendingPitcherChange)
            let saved = store.activeStoredGame
            CoreDataRosterStore.writeFault = { throw CocoaError(.fileWriteOutOfSpace) }
            let failed = store.confirmPitcherChange(id: failedProposal.id)
            CoreDataRosterStore.writeFault = nil
            XCTAssertFalse(failed, "path \(path) must report the persistence failure")
            XCTAssertEqual(store.game, before)
            XCTAssertEqual(store.activeStoredGame, saved)
            XCTAssertFalse(store.requestLineupChange(change))
            let pending = try XCTUnwrap(store.pendingPitcherChange)
            XCTAssertTrue(store.confirmPitcherChange(id: pending.id), "path \(path): \(store.actionErrorMessage ?? "")")
            XCTAssertEqual(store.pitchingLine(for: pending.candidate).outsRecorded, 0)
            let id = path == 5 ? store.game.activeAwayPitcherID : store.game.activeHomePitcherID
            XCTAssertEqual(id, pending.candidate.id)
            if path == 5 {
                // This draft edits the batting team's future pitcher, who has not taken the mound yet.
                XCTAssertTrue(store.game.pitcherInningUsage.innings(for: pending.candidate.id).isEmpty)
                store.endCurrentHalf()
            }
            XCTAssertEqual(store.game.pitcherInningUsage.innings(for: pending.candidate.id), [1], "path \(path)")
            XCTAssertEqual(store.pitchingLine(for: pending.candidate).inningsText, "0.0", "path \(path)")
            XCTAssertFalse(store.game.pitcherInningUsage.hasIncompleteHistory, "path \(path)")
        }
    }

    func testV30StalePitcherConfirmationAndWrongTokenDoNotApply() throws {
        let store = v111Store(rules: GameRules())
        store.recordPitch(.ball)
        let incoming = try XCTUnwrap(store.availablePitchers.first)
        XCTAssertFalse(store.requestLineupChange(.pitcher(incoming)))
        let pending = try XCTUnwrap(store.pendingPitcherChange)
        XCTAssertFalse(store.confirmPitcherChange(id: UUID()))
        XCTAssertNotNil(store.pendingPitcherChange)
        store.game.balls = 2 // Simulate a separately refreshed match snapshot.
        let refreshed = store.game
        XCTAssertFalse(store.confirmPitcherChange(id: pending.id))
        XCTAssertEqual(store.game, refreshed)
        XCTAssertNil(store.pendingPitcherChange)
        XCTAssertTrue(store.actionErrorMessage?.contains("局面已变化") == true)
    }

    func testV30PitcherConfirmationSaveFailureRollsBackAndCanRetry() throws {
        let store = v111Store(rules: GameRules())
        store.recordPitch(.ball)
        let incoming = try XCTUnwrap(store.availablePitchers.first)
        let before = store.game, saved = store.activeStoredGame
        XCTAssertFalse(store.requestLineupChange(.pitcher(incoming)))
        CoreDataRosterStore.writeFault = { throw CocoaError(.fileWriteOutOfSpace) }
        defer { CoreDataRosterStore.writeFault = nil }
        XCTAssertFalse(store.confirmPitcherChange(id: try XCTUnwrap(store.pendingPitcherChange).id))
        XCTAssertEqual(store.game, before)
        XCTAssertEqual(store.activeStoredGame, saved)
        CoreDataRosterStore.writeFault = nil
        XCTAssertFalse(store.requestLineupChange(.pitcher(incoming)))
        XCTAssertTrue(store.confirmPitcherChange(id: try XCTUnwrap(store.pendingPitcherChange).id))
        XCTAssertEqual(store.currentPitcher.id, incoming.id)
    }

    func testV30PregameAndCoachFieldingKeepExistingConfirmationFlow() throws {
        for coach in [false, true] {
            var rules = GameRules()
            rules.mode = coach ? .coachPitch : .standard
            let store = v111Store(rules: rules)
            if coach { store.startGameClock() }
            let incoming = try XCTUnwrap(store.availablePitchers.first)
            XCTAssertTrue(store.requestLineupChange(.pitcher(incoming)))
            XCTAssertNil(store.pendingPitcherChange)
            XCTAssertEqual(store.currentPitcher.id, incoming.id)
            XCTAssertEqual(store.pitchingLine(for: incoming).outsRecorded, 0)
        }
    }
}

extension GameStoreTests {
    func testV30AppearanceLimitCountsConfirmedZeroPitchEntryOncePerInning() throws {
        let store = v111Store(rules: GameRules(pitcherInningsLimit: 3))
        let starter = store.currentPitcher
        let incoming = try XCTUnwrap(store.availablePitchers.first)
        XCTAssertTrue(store.game.pitcherInningUsage.inningsByPitcher.isEmpty)
        store.startGameClock()
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: starter.id), [1])
        XCTAssertFalse(store.game.pitcherInningUsage.hasIncompleteHistory)
        XCTAssertFalse(store.requestLineupChange(.pitcher(incoming)))
        XCTAssertTrue(store.game.pitcherInningUsage.innings(for: incoming.id).isEmpty)
        store.cancelPitcherChange()
        XCTAssertTrue(store.game.pitcherInningUsage.innings(for: incoming.id).isEmpty)
        XCTAssertFalse(store.requestLineupChange(.pitcher(incoming)))
        XCTAssertTrue(store.confirmPitcherChange(id: try XCTUnwrap(store.pendingPitcherChange).id))
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: incoming.id), [1])
        XCTAssertEqual(store.pitchingLine(for: incoming), PitchingLine())
        store.undo()
        XCTAssertTrue(store.game.pitcherInningUsage.innings(for: incoming.id).isEmpty)
        store.redo()
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: incoming.id), [1])
        for player in [starter, incoming, starter] {
            XCTAssertFalse(store.requestLineupChange(.pitcher(player)))
            XCTAssertTrue(store.confirmPitcherChange(id: try XCTUnwrap(store.pendingPitcherChange).id))
        }
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: starter.id), [1])
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: incoming.id), [1])
        XCTAssertTrue(store.game.pitching.isEmpty)
    }

    func testV30AppearanceLimitAdvancesWithDefensiveInningsWithoutAnyPitches() {
        let store = v111Store(rules: GameRules(pitcherInningsLimit: 2))
        let homePitcher = store.currentPitcher
        let awayPitcherID = store.game.activeAwayPitcherID!
        store.startGameClock()
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: homePitcher.id), [1])
        XCTAssertTrue(store.game.pitcherInningUsage.innings(for: awayPitcherID).isEmpty)
        store.endCurrentHalf()
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: awayPitcherID), [1])
        store.endCurrentHalf()
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: homePitcher.id), [1, 2])
        XCTAssertEqual(store.ruleNoticeSummary(), "最后登板局")
        XCTAssertEqual(store.pitchingLine(for: homePitcher).inningsText, "0.0")
        store.pauseGameClock(); store.startGameClock()
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: homePitcher.id).count, 2)
        store.endCurrentHalf(); store.endCurrentHalf()
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: homePitcher.id), [1, 2, 3])
        XCTAssertEqual(store.ruleNoticeSummary(), "登板超限")
        XCTAssertEqual(store.pitchingLine(for: homePitcher).pitches, 0)
        XCTAssertEqual(store.pitchingLine(for: homePitcher).inningsText, "0.0")
    }

    func testV30TwoOutRelieverUsesFullLimitInningButOnlyActualStatisticalOut() throws {
        let store = v111Store(rules: GameRules(pitcherInningsLimit: 1))
        let starter = store.currentPitcher
        XCTAssertTrue(store.applyPlay(.groundOut)); XCTAssertTrue(store.applyPlay(.groundOut))
        let incoming = try XCTUnwrap(store.availablePitchers.first)
        XCTAssertFalse(store.requestLineupChange(.pitcher(incoming)))
        XCTAssertTrue(store.confirmPitcherChange(id: try XCTUnwrap(store.pendingPitcherChange).id))
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: incoming.id).count, 1)
        XCTAssertEqual(store.pitchingLine(for: incoming).inningsText, "0.0")
        XCTAssertTrue(store.applyPlay(.groundOut))
        XCTAssertEqual(store.pitchingLine(for: incoming).inningsText, "0.1")
        XCTAssertEqual(store.pitchingLine(for: starter).inningsText, "0.2")
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: incoming.id).count, 1)
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: starter.id).count, 1)
        XCTAssertFalse(store.game.pitcherInningUsage.hasIncompleteHistory)
    }

    func testV30PregameAndBattingTeamPitcherSelectionDoNotInventMoundEntry() throws {
        let store = v111Store(rules: GameRules(pitcherInningsLimit: 3))
        let pregamePitcher = try XCTUnwrap(store.availablePitchers.first)
        XCTAssertTrue(store.requestLineupChange(.pitcher(pregamePitcher)))
        XCTAssertTrue(store.game.pitcherInningUsage.inningsByPitcher.isEmpty)
        store.startGameClock()
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: pregamePitcher.id), [1])
        var awayDraft = store.lineupDraft(forHomeTeam: false)
        let oldAway = try XCTUnwrap(awayDraft.pitcherID)
        let newAway = try XCTUnwrap(awayDraft.players.first { awayDraft.fieldingIDs.contains($0.id) && $0.primaryPosition == .catcher })
        awayDraft.changePosition(playerID: newAway.id, to: .pitcher)
        XCTAssertFalse(store.requestLineupChange(.lineup(awayDraft)))
        XCTAssertTrue(store.confirmPitcherChange(id: try XCTUnwrap(store.pendingPitcherChange).id))
        XCTAssertTrue(store.game.pitcherInningUsage.innings(for: oldAway).isEmpty)
        XCTAssertTrue(store.game.pitcherInningUsage.innings(for: newAway.id).isEmpty)
        store.endCurrentHalf()
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: newAway.id), [1])
        XCTAssertTrue(store.game.pitcherInningUsage.innings(for: oldAway).isEmpty)
    }

    func testV30AppearanceLimitSurvivesEncodingAndHistoryReplayAndCorrection() throws {
        let store = v111Store(rules: GameRules(pitcherInningsLimit: 2))
        store.startGameClock()
        let incoming = try XCTUnwrap(store.availablePitchers.first)
        XCTAssertFalse(store.requestLineupChange(.pitcher(incoming)))
        XCTAssertTrue(store.confirmPitcherChange(id: try XCTUnwrap(store.pendingPitcherChange).id))
        let saved = try XCTUnwrap(store.activeStoredGame)
        let decoded = try JSONDecoder().decode(StoredGame.self, from: JSONEncoder().encode(saved))
        XCTAssertEqual(decoded.state.pitcherInningUsage.inningsByPitcher, store.game.pitcherInningUsage.inningsByPitcher)
        var draft = try store.makeCorrectionDraft()
        let replayed = store.previewCorrection(draft)
        XCTAssertNil(replayed.conflict)
        XCTAssertEqual(replayed.game.pitcherInningUsage.inningsByPitcher, store.game.pitcherInningUsage.inningsByPitcher)
        draft.journal.operations.removeAll { if case .pitcher(let id) = $0.command { return id == incoming.id }; return false }
        let corrected = store.previewCorrection(draft)
        XCTAssertNil(corrected.conflict)
        XCTAssertTrue(corrected.game.pitcherInningUsage.innings(for: incoming.id).isEmpty)
        XCTAssertEqual(corrected.game.pitching, store.game.pitching)
    }

    func testV30IncompleteOldMoundHistoryNeverUsesStatisticalIPForLimits() {
        let store = v111Store(rules: GameRules(pitcherInningsLimit: 2))
        let pitcher = store.currentPitcher
        store.game.pitching[pitcher.id] = PitchingLine(outsRecorded: 27, pitches: 90)
        store.game.scoringEvents = nil
        XCTAssertEqual(store.game.pitcherInningUsage.innings(for: pitcher.id), [1])
        XCTAssertTrue(store.game.pitcherInningUsage.hasIncompleteHistory)
        XCTAssertEqual(store.ruleNoticeSummary(), "登板待核对")
        XCTAssertFalse(store.ruleNotices().contains { $0.contains("超过 2 局") })
        XCTAssertEqual(store.pitchingLine(for: pitcher).inningsText, "9.0")
    }

    func testV30FinalHalfDecisionDoesNotCountAnUnplayedDefensiveHalf() {
        let store = v111Store(rules: GameRules(scheduledInnings: 1, pitcherInningsLimit: 2))
        let awayPitcher = store.game.activeAwayPitcherID!
        store.startGameClock()
        store.game.homeRunsByInning[0] = 1
        store.endCurrentHalf()
        XCTAssertEqual(store.game.pendingDecision, .regulationHalf)
        XCTAssertTrue(store.game.pitcherInningUsage.innings(for: awayPitcher).isEmpty)
        store.resolveGameDecision(finish: true)
        XCTAssertTrue(store.game.pitcherInningUsage.innings(for: awayPitcher).isEmpty)
    }

    func testV30CoachPitchHasNoPitcherAppearanceQuotaAndSlowPitchCanUseOne() throws {
        for mode in [GameMode.coachPitch, .slowPitch] {
            let store = try v30Backfill(mode)
            store.startGameClock()
            XCTAssertEqual(store.game.pitcherInningUsage.innings(for: store.currentPitcher.id).count, mode == .coachPitch ? 0 : 1)
        }
    }
}

private final class V30MatchMemoryStorage: SharedMatchStorage {
    var state = SharedMatchLocalState()
    var failSave = false
    func load() throws -> SharedMatchLocalState { state }
    func save(_ state: SharedMatchLocalState) throws {
        if failSave { throw CocoaError(.fileWriteOutOfSpace) }
        self.state = state
    }
}
private actor V30UnreliableMatchService: MatchService {
    let client: TeamServiceClient
    var offline = false
    var loseNextResponse = false
    var operations: [String] = []
    var expireCursor = false
    func expireNextCursor() { expireCursor = true }
    var recoveryEpochOverride: UUID?
    func simulateServerRecovery() { recoveryEpochOverride = UUID() }
    var importCommitHook: (@MainActor @Sendable () -> Void)?
    func afterImportCommit(_ hook: @escaping @MainActor @Sendable () -> Void) { importCommitHook = hook }
    init(_ client: TeamServiceClient) { self.client = client }
    func network(offline: Bool = false, loseNextResponse: Bool = false) { self.offline = offline; self.loseNextResponse = loseNextResponse }
    func enroll(deviceName: String) async throws -> UUID { if offline { throw TeamServiceError.message("测试断网") }; return try await client.enroll(deviceName: deviceName) }
    func teams() async throws -> SharedTeamList {
        let value = try await client.teams()
        return SharedTeamList(teams: value.teams, recoveryEpoch: recoveryEpochOverride ?? value.recoveryEpoch)
    }
    func matchPage(teamID: UUID, cursor: Int) async throws -> SharedMatchPage {
        if expireCursor && cursor > 0 { expireCursor = false; throw TeamServiceError.response(410, "同步记录已整理") }
        let value = try await client.matchPage(teamID: teamID, cursor: cursor)
        return SharedMatchPage(games: value.games, cursor: value.cursor, hasMore: value.hasMore, recoveryEpoch: recoveryEpochOverride ?? value.recoveryEpoch)
    }
    func match(teamID: UUID, gameID: UUID) async throws -> SharedMatch { try await client.match(teamID: teamID, gameID: gameID) }
    func matchCommand(path: String, method: String, body: MatchJSON) async throws -> MatchJSON {
        operations.append(String(data: try JSONEncoder().encode(body), encoding: .utf8)!)
        let result = try await client.matchCommand(path: path, method: method, body: body)
        if path.hasSuffix("/history-imports/commit"), let hook = importCommitHook { importCommitHook = nil; await hook() }
        if loseNextResponse { loseNextResponse = false; throw TeamServiceError.message("测试响应丢失") }
        return result
    }
    func matchDevices(teamID: UUID, gameID: UUID) async throws -> [MatchDevice] { try await client.matchDevices(teamID: teamID, gameID: gameID) }
    func matchViewers(teamID: UUID, gameID: UUID) async throws -> MatchViewers { try await client.matchViewers(teamID: teamID, gameID: gameID) }
}

extension GameStoreTests {
    private func matchClient() throws -> TeamServiceClient {
        try TeamServiceClient(baseURL: URL(string: "http://127.0.0.1:18788/api/v3")!, vault: V30MemoryTeamCredential(value: TeamDeviceKeychain.randomSecret()))
    }
    private func matchPartner(_ admin: TeamServiceClient, team: UUID, role: SharedTeamRole = .scorer) async throws -> TeamServiceClient {
        let client = try matchClient(); _ = try await client.enroll(deviceName: "接手 iPhone 17 Pro")
        let token = try TeamDeviceKeychain.randomSecret()
        _ = try await admin.invite(teamID: team, role: role, token: token, operationID: UUID())
        _ = try await client.join(token: token, nickname: role.title, operationID: UUID())
        return client
    }
    func testV30FullMatchSyncHandoffAndReadOnlyScoringUseActualAppPayload() async throws {
        let admin = try matchClient(); _ = try await admin.enroll(deviceName: "原记录员")
        let team = try await admin.createTeam(name: "比赛交接集成", nickname: "教练", operationID: UUID())
        let partner = try await matchPartner(admin, team: team.teamID), spectator = try await matchPartner(admin, team: team.teamID, role: .member)
        let a = freshStore(), b = GameStore(persistenceURL: nil), c = GameStore(persistenceURL: nil)
        let ca = SharedMatchCoordinator(store: a, service: admin, storage: V30MatchMemoryStorage())
        let cb = SharedMatchCoordinator(store: b, service: partner, storage: V30MatchMemoryStorage())
        let cc = SharedMatchCoordinator(store: c, service: spectator, storage: V30MatchMemoryStorage())
        a.sharedMatches = ca; b.sharedMatches = cb; c.sharedMatches = cc
        a.startGameClock(); a.recordPitch(.ball)
        let source = try XCTUnwrap(a.activeStoredGame)
        await ca.publish(source, teamID: team.teamID)
        let sharedCopyID = try XCTUnwrap(ca.state.matches.last?.payload?.id)
        a.openGame(id: sharedCopyID)
        XCTAssertNil(ca.message)
        let match = try XCTUnwrap(ca.match(localID: sharedCopyID))
        XCTAssertTrue(ca.canEdit(sharedCopyID))
        await cb.synchronize(teamID: team.teamID); await cc.synchronize(teamID: team.teamID)
        XCTAssertNil(cb.message); XCTAssertNil(cc.message)
        XCTAssertEqual(b.games.first(where: { $0.id == sharedCopyID })?.state, source.state)
        c.openGame(id: sharedCopyID); let readOnly = c.game
        c.recordPitch(.calledStrike); c.finishGame(); XCTAssertEqual(c.game, readOnly)
        XCTAssertFalse(cc.canEdit(sharedCopyID))
        let nextDevice = try await partner.enroll(deviceName: "接手设备")
        await ca.command(match, resource: "handoff/propose", fields: ["targetDeviceID": .uuid(nextDevice)])
        XCTAssertNil(ca.message); XCTAssertFalse(ca.canEdit(sharedCopyID))
        let frozen = a.game; a.recordPitch(.calledStrike); XCTAssertEqual(a.game, frozen)
        await cb.synchronize(teamID: team.teamID)
        let offered = try XCTUnwrap(cb.match(id: match.id)); XCTAssertNotNil(offered.handoff)
        await cb.command(offered, resource: "handoff/accept")
        XCTAssertNil(cb.message); XCTAssertTrue(cb.canEdit(sharedCopyID))
        b.openGame(id: sharedCopyID); b.recordPitch(.calledStrike)
        await cb.waitForSync()
        await ca.synchronize(teamID: team.teamID)
        XCTAssertEqual(a.games.first(where: { $0.id == sharedCopyID })?.state.strikes, 1)
        XCTAssertFalse(ca.canEdit(sharedCopyID)); XCTAssertEqual(cb.match(id: match.id)?.writerEpoch, 2)
        XCTAssertTrue(ca.state.conflicts.isEmpty); XCTAssertTrue(cc.state.conflicts.isEmpty)
    }
    func testV30MatchLostResponseRelaunchAndDurableQueuePreventDuplicateCreation() async throws {
        let client = try matchClient(); _ = try await client.enroll(deviceName: "测试")
        let team = try await client.createTeam(name: "续传测试", nickname: "教练", operationID: UUID())
        let unreliable = V30UnreliableMatchService(client), disk = V30MatchMemoryStorage(), store = freshStore()
        let coordinator = SharedMatchCoordinator(store: store, service: unreliable, storage: disk); store.sharedMatches = coordinator
        await unreliable.network(loseNextResponse: true)
        await coordinator.publish(try XCTUnwrap(store.activeStoredGame), teamID: team.teamID)
        XCTAssertEqual(disk.state.pending.count, 1)
        let operationID = try XCTUnwrap(disk.state.pending.first?.id)
        let relaunched = SharedMatchCoordinator(store: store, service: unreliable, storage: disk); store.sharedMatches = relaunched
        await relaunched.synchronize(teamID: team.teamID)
        XCTAssertNil(relaunched.message); XCTAssertTrue(disk.state.pending.isEmpty)
        let page = try await client.matchPage(teamID: team.teamID, cursor: 0); XCTAssertEqual(page.games.count, 1)
        let calls = await unreliable.operations
        XCTAssertEqual(calls.count, 2)
        for call in calls { XCTAssertTrue(call.lowercased().contains(operationID.uuidString.lowercased())) }
        let second = freshStore(), failing = V30MatchMemoryStorage(); failing.failSave = true
        let protected = SharedMatchCoordinator(store: second, service: unreliable, storage: failing)
        await protected.publish(try XCTUnwrap(second.activeStoredGame), teamID: team.teamID)
        let after = await unreliable.operations; XCTAssertEqual(after.count, 2)
    }
    func testV30OfflineScoringThenForceTakeoverPreservesConflictAndRejectsOldWriter() async throws {
        let client = try matchClient(); _ = try await client.enroll(deviceName: "原设备")
        let team = try await client.createTeam(name: "离线接管测试", nickname: "教练", operationID: UUID())
        let admin2 = try await matchPartner(client, team: team.teamID)
        let members = try await client.members(teamID: team.teamID), secondMember = try XCTUnwrap(members.first { $0.id != team.memberID })
        try await client.changeMember(teamID: team.teamID, member: secondMember, role: .admin, remove: false, operationID: UUID())
        let a = freshStore(), disk = V30MatchMemoryStorage(), unreliable = V30UnreliableMatchService(client)
        let ca = SharedMatchCoordinator(store: a, service: unreliable, storage: disk); a.sharedMatches = ca
        await ca.publish(try XCTUnwrap(a.activeStoredGame), teamID: team.teamID)
        a.openGame(id: try XCTUnwrap(ca.state.matches.first?.payload?.id))
        let shared = try XCTUnwrap(ca.state.matches.first), localID = try XCTUnwrap(shared.payload?.id)
        await unreliable.network(offline: true); a.recordPitch(.ball); await ca.waitForSync()
        XCTAssertEqual(a.game.balls, 1); XCTAssertFalse(ca.state.pending.isEmpty)
        let b = GameStore(persistenceURL: nil), cb = SharedMatchCoordinator(store: b, service: admin2, storage: V30MatchMemoryStorage()); b.sharedMatches = cb
        await cb.synchronize(teamID: team.teamID)
        let remote = try XCTUnwrap(cb.match(id: shared.id)), device = try XCTUnwrap(cb.state.deviceID)
        await cb.command(remote, resource: "handoff/force", fields: ["targetDeviceID": .uuid(device), "confirmRisk": .bool(true)])
        XCTAssertNil(cb.message)
        await unreliable.network(); await ca.synchronize(teamID: team.teamID)
        XCTAssertEqual(ca.state.conflicts.first?.game.state.balls, 1)
        XCTAssertFalse(ca.canEdit(localID)); XCTAssertTrue(ca.state.pending.isEmpty)
        await ca.synchronize(teamID: team.teamID)
        XCTAssertEqual(ca.match(id: shared.id)?.payload?.state.balls, 0)
        XCTAssertEqual(ca.state.conflicts.first?.game.state.balls, 1)
    }
    func testV30SharedMatchFinalDateCorrectionAndHistoricalPermissionsStayIndependent() async throws {
        let client = try matchClient(); _ = try await client.enroll(deviceName: "记录手机")
        let team = try await client.createTeam(name: "历史授权测试", nickname: "教练", operationID: UUID())
        let store = freshStore(), coordinator = SharedMatchCoordinator(store: store, service: client, storage: V30MatchMemoryStorage()); store.sharedMatches = coordinator
        store.recordPitch(.ball); store.finishGame()
        let local = try XCTUnwrap(store.activeStoredGame)
        await coordinator.publish(local, teamID: team.teamID)
        let sharedCopyID = try XCTUnwrap(coordinator.state.matches.last?.payload?.id)
        store.openGame(id: sharedCopyID)
        XCTAssertNil(coordinator.message)
        let match = try XCTUnwrap(coordinator.match(localID: sharedCopyID))
        XCTAssertEqual(match.accessMode, .closed)
        await coordinator.command(match, resource: "access", fields: ["mode": .string("public")]); XCTAssertNil(coordinator.message)
        XCTAssertTrue(store.updateCompletedGameDate(id: sharedCopyID, playedAt: Date().addingTimeInterval(-86400), timeZoneID: "Asia/Shanghai", expectedRevision: store.activeStoredGame!.revision ?? 0))
        await coordinator.waitForSync()
        let remote = try await client.match(teamID: team.teamID, gameID: match.id)
        XCTAssertEqual(remote.accessMode, .public); XCTAssertEqual(remote.payload?.playedAt, store.activeStoredGame?.playedAt)
        XCTAssertEqual(remote.payload?.state, local.state)
        await coordinator.command(remote, resource: "access", fields: ["mode": .string("closed")])
        let closed = try await client.match(teamID: team.teamID, gameID: match.id)
        XCTAssertNotNil(closed.payload); XCTAssertEqual(closed.accessMode, .closed)
    }
}

extension GameStoreTests {
    func testV30ThreeModeCompleteGamesAndCorrectionReplaySurviveSharing() async throws {
        let client = try matchClient(); _ = try await client.enroll(deviceName: "三模式测试")
        let team = try await client.createTeam(name: "三模式共享", nickname: "教练", operationID: UUID())
        for mode: GameMode in [.standard, .coachPitch, .slowPitch] {
            let store = try v30Backfill(mode)
            store.startGameClock(); store.recordPitch(.ball); _ = store.applyPlay(.single); store.finishGame()
            let coordinator = SharedMatchCoordinator(store: store, service: client, storage: V30MatchMemoryStorage()); store.sharedMatches = coordinator
            let local = try XCTUnwrap(store.activeStoredGame)
            await coordinator.publish(local, teamID: team.teamID); XCTAssertNil(coordinator.message, mode.rawValue)
        let sharedCopyID = try XCTUnwrap(coordinator.state.matches.last?.payload?.id)
        store.openGame(id: sharedCopyID)
            let match = try XCTUnwrap(coordinator.match(localID: sharedCopyID)), remote = try await client.match(teamID: team.teamID, gameID: match.id)
            XCTAssertEqual(remote.payload?.state, local.state); XCTAssertEqual(remote.payload?.rules, local.rules)
            let draft = try store.makeCorrectionDraft(), preview = store.previewCorrection(draft)
            XCTAssertTrue(preview.canSave, preview.conflict ?? "")
            XCTAssertEqual(preview.game.homeScore, local.state.homeScore); XCTAssertEqual(preview.game.awayScore, local.state.awayScore)
            XCTAssertTrue(store.reopenGame()); store.undo(); await coordinator.waitForSync()
            let revised = try await client.match(teamID: team.teamID, gameID: match.id)
            XCTAssertGreaterThan(revised.serverRevision, remote.serverRevision)
            XCTAssertEqual(revised.payload?.state, store.game)
        }
    }
    func testV30SharedBackupRestoreDoesNotPublishAnOldVersionAndQueueIsDeviceBound() async throws {
        let client = try matchClient(); _ = try await client.enroll(deviceName: "备份测试")
        let team = try await client.createTeam(name: "共享备份保护", nickname: "教练", operationID: UUID())
        let store = freshStore(), coordinator = SharedMatchCoordinator(store: store, service: client, storage: V30MatchMemoryStorage()); store.sharedMatches = coordinator
        await coordinator.publish(try XCTUnwrap(store.activeStoredGame), teamID: team.teamID)
        store.openGame(id: try XCTUnwrap(coordinator.state.matches.first?.payload?.id))
        let oldBackup = try LocalBackup.read(store.exportBackup()), localID = try XCTUnwrap(store.activeGameID)
        store.recordPitch(.ball); await coordinator.waitForSync()
        let match = try XCTUnwrap(coordinator.match(localID: localID)), revision = match.serverRevision
        try store.restoreBackup(oldBackup)
        XCTAssertFalse(coordinator.canEdit(localID)); XCTAssertEqual(coordinator.state.conflicts.count, 1)
        await coordinator.synchronize(teamID: team.teamID)
        let remote = try await client.match(teamID: team.teamID, gameID: match.id)
        XCTAssertEqual(remote.serverRevision, revision); XCTAssertEqual(remote.payload?.state.balls, 1)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = SharedMatchFile(url: directory.appendingPathComponent("matches.sealed"), vault: V30MemoryTeamCredential(value: try TeamDeviceKeychain.randomSecret()))
        try file.save(coordinator.state)
        XCTAssertFalse(String(data: try Data(contentsOf: file.url), encoding: .utf8)?.contains("payload") == true)
        let another = SharedMatchFile(url: file.url, vault: V30MemoryTeamCredential(value: try TeamDeviceKeychain.randomSecret()))
        XCTAssertThrowsError(try another.load())
        XCTAssertEqual(try file.load().matches.count, 1)
    }
}


extension GameStoreTests {
    func testV30RemovedMemberClearsSharedCopyAndPublisherKeepsOnlyOriginalLocalGame() async throws {
        let admin = try matchClient(); _ = try await admin.enroll(deviceName: "教练")
        let team = try await admin.createTeam(name: "退出清理", nickname: "教练", operationID: UUID())
        let publisher = try await matchPartner(admin, team: team.teamID)
        let reader = try await matchPartner(admin, team: team.teamID, role: .member)
        let a = freshStore(), ca = SharedMatchCoordinator(store: a, service: publisher, storage: V30MatchMemoryStorage()); a.sharedMatches = ca
        let original = try XCTUnwrap(a.activeStoredGame)
        await ca.publish(original, teamID: team.teamID)
        let sharedCopyID = try XCTUnwrap(ca.state.matches.first?.payload?.id); a.openGame(id: sharedCopyID)
        a.recordPitch(.ball); await ca.waitForSync()
        let b = GameStore(persistenceURL: nil), cb = SharedMatchCoordinator(store: b, service: reader, storage: V30MatchMemoryStorage()); b.sharedMatches = cb
        await cb.synchronize(teamID: team.teamID)
        XCTAssertTrue(b.games.contains { $0.id == sharedCopyID })
        let sharedID = try XCTUnwrap(cb.state.matches.first?.id)
        b.openGame(id: sharedCopyID)
        try await reader.leave(teamID: team.teamID, operationID: UUID())
        await cb.synchronize(teamID: team.teamID)
        XCTAssertNil(cb.message); XCTAssertFalse(b.games.contains { $0.id == sharedCopyID }); XCTAssertNil(b.activeGameID)
        XCTAssertEqual(b.game.balls, 0)
        try await publisher.leave(teamID: team.teamID, operationID: UUID())
        await ca.synchronize(teamID: team.teamID)
        XCTAssertNil(ca.message); XCTAssertTrue(ca.state.matches.isEmpty)
        XCTAssertEqual(a.games.first { $0.id == original.id }?.state, original.state)
        XCTAssertNil(a.games.first { $0.id == original.id }?.sharedGameID)
        let retained = try await admin.match(teamID: team.teamID, gameID: sharedID)
        XCTAssertNotNil(retained.payload)
    }
}

private actor V30SecurityClient: TeamDirectoryService, TeamSecurityService {
    let client: TeamServiceClient
    var lose = false
    var delayedRecoveryReply: MatchJSON?
    var securityCalls = 0
    init(_ client: TeamServiceClient) { self.client = client }
    func loseNextResponse() { lose = true }
    func deliverDelayedRecoveryRead(_ value: MatchJSON) { delayedRecoveryReply = value }
    func enroll(deviceName: String) async throws -> UUID { try await client.enroll(deviceName: deviceName) }
    func teams() async throws -> SharedTeamList { try await client.teams() }
    func createTeam(name: String, nickname: String, operationID: UUID, profile: SharedRosterSeed?, recoveryToken: String?) async throws -> SharedTeamMembership { try await client.createTeam(name: name, nickname: nickname, operationID: operationID, profile: profile, recoveryToken: recoveryToken) }
    func detail(teamID: UUID) async throws -> SharedTeamDetail { try await client.detail(teamID: teamID) }
    func preview(token: String) async throws -> SharedInvitationPreview { try await client.preview(token: token) }
    func invite(teamID: UUID, role: SharedTeamRole, token: String, operationID: UUID) async throws -> SharedTeamInvitation { try await client.invite(teamID: teamID, role: role, token: token, operationID: operationID) }
    func join(token: String, nickname: String, operationID: UUID) async throws -> SharedTeamMembership { try await client.join(token: token, nickname: nickname, operationID: operationID) }
    func invitations(teamID: UUID) async throws -> [SharedTeamInvitation] { try await client.invitations(teamID: teamID) }
    func revoke(teamID: UUID, invitationID: UUID, operationID: UUID) async throws { try await client.revoke(teamID: teamID, invitationID: invitationID, operationID: operationID) }
    func members(teamID: UUID) async throws -> [SharedTeamMember] { try await client.members(teamID: teamID) }
    func changeMember(teamID: UUID, member: SharedTeamMember, role: SharedTeamRole, remove: Bool, operationID: UUID) async throws { try await client.changeMember(teamID: teamID, member: member, role: role, remove: remove, operationID: operationID) }
    func leave(teamID: UUID, operationID: UUID) async throws { try await client.leave(teamID: teamID, operationID: operationID) }
    func securityRequest(path: String, method: String, body: MatchJSON?) async throws -> MatchJSON {
        securityCalls += 1
        if method == "GET", path.hasSuffix("/recovery"), let delayedRecoveryReply {
            self.delayedRecoveryReply = nil; return delayedRecoveryReply
        }
        let result = try await client.securityRequest(path: path, method: method, body: body)
        if method != "GET", lose { lose = false; throw TeamServiceError.message("测试响应丢失") }
        return result
    }
}
extension GameStoreTests {
    func testV30DelayedRecoveryReadCannotEraseNewlyRotatedCard() async throws {
        let client = try matchClient(), proxy = V30SecurityClient(client), disk = V30DirectoryMemoryStorage()
        let directory = TeamSharingStore(service: proxy, storage: disk); await directory.connect()
        let created = await directory.submit(.create(name: "恢复卡读写竞争", nickname: "教练", localTeamID: nil, profile: nil)); XCTAssertTrue(created)
        let team = try XCTUnwrap(directory.teams.first), status = try await directory.recoveryStatus(team.id)
        let oldReply = try await client.securityRequest(path: "teams/\(team.id.uuidString.lowercased())/recovery", method: "GET", body: nil)
        let rotated = await directory.rotateRecovery(status); XCTAssertTrue(rotated)
        let newCard = try XCTUnwrap(directory.state.recoveryCards?.first)
        await proxy.deliverDelayedRecoveryRead(oldReply)
        do { let _ = try await directory.recoveryStatus(team.id); XCTFail("Stale recovery read must require refresh") } catch { }
        XCTAssertEqual(directory.state.recoveryCards?.first?.token, newCard.token)
        XCTAssertEqual(disk.state.recoveryCards?.first?.version, 2)
        let fresh = try await directory.recoveryStatus(team.id); XCTAssertEqual(fresh.version, 2)
        let valid = try await directory.previewRecovery(newCard.code); XCTAssertEqual(valid.version, 2)
    }
    func testV30CreateTeamGeneratesRecoverableCardAndPairingRetainsSameMember() async throws {
        let a = try matchClient(), b = try matchClient()
        let da = TeamSharingStore(service: a, storage: V30DirectoryMemoryStorage())
        let db = TeamSharingStore(service: b, storage: V30DirectoryMemoryStorage())
        await da.connect(); await db.connect()
        let created = await da.submit(.create(name: "管理恢复卡测试", nickname: "教练", localTeamID: nil, profile: nil)); XCTAssertTrue(created)
        let team = try XCTUnwrap(da.teams.first), card = try XCTUnwrap(da.state.recoveryCards?.first)
        XCTAssertFalse(card.confirmedSaved); XCTAssertEqual(card.version, 1)
        let status = try await db.previewRecovery(card.code); XCTAssertEqual(status.teamID, team.id)
        await db.createPairing(); XCTAssertNil(db.errorMessage)
        let request = try XCTUnwrap(db.state.pairing), preview = try await da.previewPairing(teamID: team.id, input: DevicePairingLink.url(request.token).absoluteString)
        XCTAssertEqual(preview.phrase, request.request.phrase)
        let approved = await da.approvePairing(preview, token: request.token); XCTAssertTrue(approved); XCTAssertTrue(db.teams.isEmpty)
        await db.refreshPairing(); let accepted = await db.acceptPairing(); XCTAssertTrue(accepted)
        XCTAssertEqual(db.teams.first?.memberID, team.memberID)
        XCTAssertEqual(db.teams.first?.role, .admin)
        let devices = try await db.teamDevices(team.id); XCTAssertEqual(devices.count, 2)
        let old = try XCTUnwrap(devices.first { $0.isCurrent == 0 }), removed = await db.removeDevice(old, teamID: team.id); XCTAssertTrue(removed)
        await da.connect(); XCTAssertTrue(da.teams.isEmpty); XCTAssertTrue(da.state.recoveryCards?.isEmpty == true)
        let finalDevices = try await db.teamDevices(team.id); XCTAssertEqual(finalDevices.count, 1)
        da.confirmCardSaved(team.id); XCTAssertTrue(da.state.recoveryCards?.isEmpty == true)
    }
    func testV30ManagementRecoveryLostReplyRelaunchRetainsNewCodeAndIsolatesOldScoringDraft() async throws {
        let client = try matchClient(), proxy = V30SecurityClient(client), disk = V30DirectoryMemoryStorage()
        let directory = TeamSharingStore(service: proxy, storage: disk); await directory.connect()
        let created = await directory.submit(.create(name: "恢复续接测试", nickname: "教练", localTeamID: nil, profile: nil)); XCTAssertTrue(created)
        let card = try XCTUnwrap(directory.state.recoveryCards?.first), oldMember = try XCTUnwrap(directory.teams.first?.memberID)
        let game = freshStore(), matchDisk = V30MatchMemoryStorage(), network = V30UnreliableMatchService(client)
        let matches = SharedMatchCoordinator(store: game, service: network, storage: matchDisk); game.sharedMatches = matches
        await matches.publish(try XCTUnwrap(game.activeStoredGame), teamID: card.teamID)
        game.openGame(id: try XCTUnwrap(matches.state.matches.first?.payload?.id))
        let shared = try XCTUnwrap(matches.state.matches.first)
        await network.network(offline: true); game.recordPitch(.ball); await matches.waitForSync()
        let preview = try await directory.previewRecovery(card.code)
        await proxy.loseNextResponse()
        let recovered = await directory.recoverManagement(input: card.code, preview: preview, nickname: "恢复后的教练"); XCTAssertFalse(recovered)
        let pending = try XCTUnwrap(disk.state.pending), operation = try XCTUnwrap({ () -> TeamSecurityCommand? in if case .security(let c) = pending.command { return c }; return nil }())
        let expectedToken = try XCTUnwrap(operation.newToken)
        let reopened = TeamSharingStore(service: proxy, storage: disk); await reopened.connect()
        XCTAssertNotNil(reopened.state.pending)
        let retried = await reopened.retryPending(); XCTAssertTrue(retried, reopened.errorMessage ?? "")
        XCTAssertNil(reopened.state.pending); XCTAssertEqual(reopened.state.recoveryCards?.first?.token, expectedToken)
        XCTAssertNotEqual(reopened.teams.first?.memberID, oldMember)
        XCTAssertEqual(reopened.state.recoveryCards?.first?.version, 2)
        await network.network(); await matches.synchronize(teamID: card.teamID)
        XCTAssertNil(matches.message); XCTAssertTrue(matches.state.pending.isEmpty)
        XCTAssertEqual(matches.state.conflicts.first?.game.state.balls, 1)
        let official = try await client.match(teamID: card.teamID, gameID: shared.id)
        XCTAssertEqual(official.payload?.state.balls, 0); XCTAssertFalse(official.canWrite); XCTAssertFalse(official.writerActive)
        XCTAssertEqual(official.accessMode, .closed)
        let validNew = try await reopened.previewRecovery(try XCTUnwrap(reopened.state.recoveryCards?.first?.code)); XCTAssertEqual(validNew.version, 2)
        do { let _: TeamRecoveryStatus = try await reopened.previewRecovery(card.code); XCTFail("Old recovery card must be invalid") } catch { }
    }
    func testV30SecurityQueueMustSaveBeforeSendingAndKeepsRotationAfterAcknowledgmentDiskFailure() async throws {
        let client = try matchClient(), proxy = V30SecurityClient(client), disk = V30DirectoryMemoryStorage()
        let directory = TeamSharingStore(service: proxy, storage: disk); await directory.connect()
        disk.failSaveAt = disk.saves + 1
        await directory.createPairing(); let before = await proxy.securityCalls; XCTAssertEqual(before, 0); XCTAssertNil(directory.state.pending)
        disk.failSaveAt = nil
        let created = await directory.submit(.create(name: "恢复保存失败", nickname: "教练", localTeamID: nil, profile: nil)); XCTAssertTrue(created)
        let team = try XCTUnwrap(directory.teams.first), status = try await directory.recoveryStatus(team.id)
        // Durable input, preflight refresh, then acknowledgment save fails after server commit.
        disk.failSaveAt = disk.saves + 3
        let rotated = await directory.rotateRecovery(status); XCTAssertFalse(rotated); XCTAssertNotNil(directory.state.pending)
        disk.failSaveAt = nil
        let retried = await directory.retryPending(); XCTAssertTrue(retried)
        let card = try XCTUnwrap(directory.state.recoveryCards?.first), result = try await directory.previewRecovery(card.code)
        XCTAssertEqual(result.version, 2); XCTAssertEqual(card.version, 2)
    }
    func testV30RecoveryCardsAndPairingSecretsAreDeviceEncryptedAndOldDirectoryRemainsReadable() throws {
        let secret = try TeamDeviceKeychain.randomSecret(), team = UUID()
        let card = SavedRecoveryCard(teamID: team, teamName: "隐私测试", token: secret, version: 2)
        XCTAssertEqual(SavedRecoveryCard.parse(card.text)?.teamID, team)
        XCTAssertEqual(SavedRecoveryCard.parse(card.code)?.token, secret)
        XCTAssertNil(SavedRecoveryCard.parse(card.code + "\n" + card.code))
        XCTAssertNil(DevicePairingLink.token(from: "https://baseballmaster.cc.evil/pair#" + secret))
        XCTAssertNil(DevicePairingLink.token(from: "https://baseballmaster.cc/pair?token=" + secret))
        XCTAssertNil(TeamInvitationLink.token(from: DevicePairingLink.url(secret).absoluteString))
        XCTAssertEqual(DevicePairingLink.token(from: DevicePairingLink.url(secret).absoluteString), secret)
        var state = TeamDirectoryState(); state.recoveryCards = [card]
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString); defer { try? FileManager.default.removeItem(at: dir) }
        let vault = V30MemoryTeamCredential(value: try TeamDeviceKeychain.randomSecret()), file = TeamDirectoryFile(url: dir.appendingPathComponent("directory.sealed"), vault: vault)
        try file.save(state)
        XCTAssertEqual(try file.load().recoveryCards?.first?.token, secret)
        XCTAssertFalse(String(data: try Data(contentsOf: file.url), encoding: .utf8)?.contains(secret) == true)
        XCTAssertThrowsError(try TeamDirectoryFile(url: file.url, vault: V30MemoryTeamCredential(value: try TeamDeviceKeychain.randomSecret())).load())
        let old = try JSONDecoder().decode(TeamDirectoryState.self, from: Data("{\"teams\":[],\"bindings\":[],\"requiresRecoveryReview\":false}".utf8))
        XCTAssertNil(old.recoveryCards); XCTAssertNil(old.pairing)
    }
}

extension V30SecurityClient: TeamRosterService {
    func updateRoster(_ change: SharedRosterChange, operationID: UUID) async throws -> SharedTeamDetail {
        let result = try await client.updateRoster(change, operationID: operationID)
        if lose { lose = false; throw TeamServiceError.message("测试名单响应丢失") }
        return result
    }
}

extension GameStoreTests {
    func testV30AnnualGroupingUsesMatchDateZoneInsteadOfLegacySeasonAndMovesAfterCorrection() throws {
        let store = freshStore(); store.finishGame()
        let original = try XCTUnwrap(store.activeStoredGame), team = try XCTUnwrap(store.currentTeam)
        let boundary = ISO8601DateFormatter().date(from: "2024-12-31T16:30:00Z")!
        XCTAssertTrue(store.updateCompletedGameDate(id: original.id, playedAt: boundary, timeZoneID: "Asia/Shanghai", expectedRevision: original.revision ?? 0))
        let china = try XCTUnwrap(store.activeStoredGame)
        XCTAssertEqual(china.yearID, "2025")
        XCTAssertEqual(china.seasonID, original.seasonID) // legacy source metadata is preserved, never used for grouping
        XCTAssertEqual(store.seasonStatistics(for: team, seasonID: "2025").games.count, 1)
        XCTAssertEqual(store.seasonStatistics(for: team, seasonID: "2024").games.count, 0)
        XCTAssertTrue(store.updateCompletedGameDate(id: original.id, playedAt: boundary, timeZoneID: "America/Los_Angeles", expectedRevision: china.revision ?? 0))
        XCTAssertEqual(store.activeStoredGame?.yearID, "2024")
        XCTAssertEqual(store.seasonStatistics(for: team, seasonID: "2025").games.count, 0)
        XCTAssertEqual(store.seasonStatistics(for: team, seasonID: "2024").games.count, 1)
        XCTAssertEqual(store.activeStoredGame?.state, original.state)
        XCTAssertEqual(store.games.count, 1)
        XCTAssertTrue(store.statisticsSeasons.contains { $0.id == "2024" && $0.name == "2024 年" })
        XCTAssertFalse(store.statisticsSeasons.contains { $0.id.contains("summer") || $0.id.contains("autumn") })
        let restored = GameStore(persistenceURL: nil)
        try restored.restoreBackup(LocalBackup.read(store.exportBackup()))
        XCTAssertEqual(restored.games.first?.yearID, "2024")
    }

    func testV30SharedRosterRefreshUsesSourceIdentityAndArchiveKeepsHistoricalStatisticsAcrossDevices() async throws {
        let a = freshStore(), b = GameStore(persistenceURL: nil), admin = try matchClient()
        _ = try await admin.enroll(deviceName: "名单管理员")
        let originalTeam = try XCTUnwrap(a.currentTeam), player = originalTeam.players[0]
        let created = try await admin.createTeam(name: "年度同步队", nickname: "教练", operationID: UUID(), profile: SharedRosterSeed(team: originalTeam))
        let reader = try await matchPartner(admin, team: created.teamID, role: .member)
        let da = TeamSharingStore(service: admin, storage: V30DirectoryMemoryStorage()), db = TeamSharingStore(service: reader, storage: V30DirectoryMemoryStorage())
        a.teamSharing = da; b.teamSharing = db; await da.connect(); await db.connect()
        let profile = try XCTUnwrap(da.state.profiles?.first), seeded = try XCTUnwrap(profile.players.first { $0.sourceID == player.id })
        XCTAssertNotEqual(seeded.id, seeded.sourceID)
        XCTAssertEqual(db.sharedRosterTeams.first?.players.first?.id, originalTeam.players.first?.id)
        XCTAssertFalse(b.recordableTeams.contains { $0.id == created.teamID })
        a.startGameClock(); a.recordPitch(.ball); a.finishGame()
        let original = try XCTUnwrap(a.activeStoredGame)
        let ca = SharedMatchCoordinator(store: a, service: admin, storage: V30MatchMemoryStorage()), cb = SharedMatchCoordinator(store: b, service: reader, storage: V30MatchMemoryStorage())
        a.sharedMatches = ca; b.sharedMatches = cb
        await ca.publish(original, teamID: created.teamID); await cb.synchronize(teamID: created.teamID)
        let sharedCopyID = try XCTUnwrap(ca.state.matches.last?.payload?.id)
        a.openGame(id: sharedCopyID)
        XCTAssertNil(ca.message); XCTAssertNil(cb.message)
        let statsTeam = try XCTUnwrap(db.sharedRosterTeams.first)
        XCTAssertEqual(b.seasonStatistics(for: statsTeam, seasonID: original.yearID).games.count, 1)
        var archived = seeded; archived.archived = true
        let submitted = await da.submit(.roster(SharedRosterChange(teamID: created.teamID, baseRevision: profile.revision, kind: "player", player: archived)))
        XCTAssertTrue(submitted); await db.connect()
        let after = try XCTUnwrap(db.sharedRosterTeams.first)
        XCTAssertFalse(after.players.contains { $0.id == player.id })
        XCTAssertEqual(b.games.first?.state, original.state)
        let historical = b.seasonStatistics(for: after, seasonID: original.yearID)
        XCTAssertEqual(historical.games.count, 1)
        let report = PlayerStatisticsPDFReport(store: b, player: player, seasonID: original.yearID, teamID: after.id, gameIDs: [sharedCopyID])
        XCTAssertEqual(report.teamName, "年度同步队")
        XCTAssertTrue(historical.players.contains { $0.id == player.id && !$0.isCurrentRoster })
        let stored = try XCTUnwrap(a.activeStoredGame), movedDate = ISO8601DateFormatter().date(from: "2023-12-31T23:30:00Z")!
        XCTAssertTrue(a.updateCompletedGameDate(id: stored.id, playedAt: movedDate, timeZoneID: "UTC", expectedRevision: stored.revision ?? 0))
        await ca.synchronize(); await cb.synchronize(teamID: created.teamID)
        XCTAssertEqual(b.games.first?.yearID, "2023")
        XCTAssertEqual(b.seasonStatistics(for: after, seasonID: original.yearID).games.count, 0)
        XCTAssertEqual(b.seasonStatistics(for: after, seasonID: "2023").games.count, 1)
        let left = await db.submit(.leave(teamID: created.teamID)); XCTAssertTrue(left)
        XCTAssertTrue(db.sharedRosterTeams.isEmpty)
        XCTAssertTrue(b.completedStatisticsGames(teamID: created.teamID, seasonID: "2023").isEmpty)
        a.setCurrentTeam(id: created.teamID)
        let roster = try XCTUnwrap(a.currentTeam)
        XCTAssertEqual(roster.id, created.teamID); XCTAssertFalse(roster.players.contains { $0.id == player.id })
        let lineup = Array(roster.players.prefix(9)).enumerated().map { LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset]) }
        XCTAssertNotNil(a.startNewGame(opponent: a.opponentTeams[0], isHome: false, rules: GameRules(), lineup: lineup))
        XCTAssertEqual(a.activeStoredGame?.ourTeamID, created.teamID)
        XCTAssertEqual(Set(a.activeStoredGame!.ourTeam.players.map(\.id)), Set(roster.players.map(\.id)))
        XCTAssertNoThrow(try a.exportBackup())
    }

    func testV30RosterConflictKeepsDraftAcrossRelaunchAndExplicitRebasePreservesOtherPlayers() async throws {
        let client = try matchClient(), disk = V30DirectoryMemoryStorage(), directory = TeamSharingStore(service: client, storage: disk)
        await directory.connect()
        let created = await directory.submit(.create(name: "并发名单队", nickname: "教练", localTeamID: nil, profile: nil)); XCTAssertTrue(created)
        await directory.connect()
        let team = try XCTUnwrap(directory.teams.first)
        let first = SharedRosterPlayer(id: UUID(), sourceID: UUID(), chineseName: "同名", englishName: "", numbers: ["00"], archived: false)
        let second = SharedRosterPlayer(id: UUID(), sourceID: UUID(), chineseName: "同名", englishName: "", numbers: ["00"], archived: false)
        _ = try await client.updateRoster(SharedRosterChange(teamID: team.id, baseRevision: team.revision, kind: "player", player: first), operationID: UUID())
        let stale = SharedRosterChange(teamID: team.id, baseRevision: team.revision, kind: "player", player: second)
        let result = await directory.submit(.roster(stale)); XCTAssertFalse(result)
        XCTAssertNil(directory.state.pending); XCTAssertEqual(directory.state.rosterDraft?.player?.id, second.id)
        let reopened = TeamSharingStore(service: client, storage: disk); await reopened.connect()
        let unrelated = SharedRosterChange(teamID: team.id, baseRevision: 2, kind: "profile", name: "不能丢弃名单草稿", shortName: "测试", city: "")
        let blocked = await reopened.submit(.roster(unrelated)); XCTAssertFalse(blocked)
        XCTAssertEqual(reopened.state.rosterDraft?.player?.id, second.id)
        var draft = try XCTUnwrap(reopened.state.rosterDraft)
        draft.baseRevision = try XCTUnwrap(reopened.state.profiles?.first?.revision)
        let retried = await reopened.submit(.roster(draft)); XCTAssertTrue(retried)
        XCTAssertNil(reopened.state.rosterDraft)
        XCTAssertEqual(Set(reopened.sharedRosterTeams[0].players.map(\.id)), Set([first.sourceID, second.sourceID]))
    }
    func testV30ScorerRosterConflictKeepsDraftAcrossRelaunchAndExplicitRebase() async throws {
        let admin = try matchClient(); _ = try await admin.enroll(deviceName: "名单管理设备")
        let created = try await admin.createTeam(name: "记录员名单", nickname: "管理员", operationID: UUID())
        let client = try await matchPartner(admin, team: created.teamID, role: .scorer)
        let disk = V30DirectoryMemoryStorage(), directory = TeamSharingStore(service: client, storage: disk)
        await directory.connect()
        let team = try XCTUnwrap(directory.teams.first)
        XCTAssertTrue(team.role.canEditPlayers)
        XCTAssertFalse(SharedTeamRole.member.canEditPlayers)
        let first = SharedRosterPlayer(id: UUID(), sourceID: UUID(), chineseName: "同名", englishName: "", numbers: ["00"], archived: false)
        let second = SharedRosterPlayer(id: UUID(), sourceID: UUID(), chineseName: "同名", englishName: "", numbers: ["00"], archived: false)
        _ = try await admin.updateRoster(SharedRosterChange(teamID: team.id, baseRevision: team.revision, kind: "player", player: first), operationID: UUID())
        let stale = SharedRosterChange(teamID: team.id, baseRevision: team.revision, kind: "player", player: second)
        let result = await directory.submit(.roster(stale)); XCTAssertFalse(result)
        XCTAssertNil(directory.state.pending); XCTAssertEqual(directory.state.rosterDraft?.player?.id, second.id)
        let reopened = TeamSharingStore(service: client, storage: disk); await reopened.connect()
        let unrelated = SharedRosterChange(teamID: team.id, baseRevision: 2, kind: "profile", name: "不能丢弃名单草稿", shortName: "测试", city: "")
        let blocked = await reopened.submit(.roster(unrelated)); XCTAssertFalse(blocked)
        XCTAssertEqual(reopened.state.rosterDraft?.player?.id, second.id)
        var draft = try XCTUnwrap(reopened.state.rosterDraft)
        draft.baseRevision = try XCTUnwrap(reopened.state.profiles?.first?.revision)
        let retried = await reopened.submit(.roster(draft)); XCTAssertTrue(retried)
        XCTAssertNil(reopened.state.rosterDraft)
        XCTAssertEqual(Set(reopened.sharedRosterTeams[0].players.map(\.id)), Set([first.sourceID, second.sourceID]))
    }

    func testV30RosterLostResponseAndDiskFailureRetrySameOperationWithoutDuplicate() async throws {
        let client = try matchClient(), proxy = V30SecurityClient(client), disk = V30DirectoryMemoryStorage()
        let directory = TeamSharingStore(service: proxy, storage: disk); await directory.connect()
        let created = await directory.submit(.create(name: "名单重试", nickname: "教练", localTeamID: nil, profile: nil)); XCTAssertTrue(created)
        await directory.connect(); let team = try XCTUnwrap(directory.teams.first)
        let player = SharedRosterPlayer(id: UUID(), sourceID: UUID(), chineseName: "测试", englishName: "", numbers: ["0", "00"], archived: false)
        let change = SharedRosterChange(teamID: team.id, baseRevision: team.revision, kind: "player", player: player)
        disk.failSaveAt = disk.saves + 1
        let notSent = await directory.submit(.roster(change)); XCTAssertFalse(notSent)
        let untouched = try await client.detail(teamID: team.id); XCTAssertTrue(untouched.team.players.isEmpty)
        disk.failSaveAt = nil; await proxy.loseNextResponse()
        let lost = await directory.submit(.roster(change)); XCTAssertFalse(lost)
        let pendingID = try XCTUnwrap(disk.state.pending?.id)
        let reopened = TeamSharingStore(service: client, storage: disk); await reopened.connect()
        XCTAssertEqual(reopened.state.pending?.id, pendingID)
        let retry = await reopened.retryPending(); XCTAssertTrue(retry)
        let synced = try await client.detail(teamID: team.id)
        XCTAssertEqual(synced.team.players.count, 1); XCTAssertEqual(synced.team.revision, 2)
        XCTAssertEqual(synced.team.players.first?.numbers, ["0", "00"])
        let cached = TeamSharingStore(service: client, storage: disk); cached.loadCachedDirectory()
        XCTAssertEqual(cached.sharedRosterTeams.first?.players.first?.id, player.sourceID)
    }
}

extension GameStoreTests {
    func testV30HistoricalImportThreeModesPreserveLocalGamesDatesAndOnlyLinkExplicitPlayers() async throws {
        for mode in [GameMode.standard, .coachPitch, .slowPitch] {
            var rules = GameRules(); rules.mode = mode
            let store = mode == .slowPitch ? try slowStore() : v111Store(rules: rules)
            let sourceTeam = try XCTUnwrap(store.currentTeam), batter = store.currentBatter
            XCTAssertTrue(store.applyPlay(.single)); store.finishGame()
            let old = try XCTUnwrap(store.activeStoredGame)
            let played = ISO8601DateFormatter().date(from: "2024-12-31T16:30:00Z")!
            XCTAssertTrue(store.updateCompletedGameDate(id: old.id, playedAt: played, timeZoneID: "Asia/Shanghai", expectedRevision: old.revision ?? 0))
            let original = try XCTUnwrap(store.activeStoredGame)
            let official = Player(chineseName: "陈昊", englishName: "", numbers: [0])
            let cloudTeam = Team(name: "海浪", shortName: "海浪", city: "", players: [official])
            let client = try matchClient(); _ = try await client.enroll(deviceName: "iPhone 17 Pro")
            let created = try await client.createTeam(name: "海浪", nickname: "教练", operationID: UUID(), profile: SharedRosterSeed(team: cloudTeam))
            let directory = TeamSharingStore(service: client, storage: V30DirectoryMemoryStorage()); store.teamSharing = directory; await directory.connect()
            let disk = V30MatchMemoryStorage(), coordinator = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = coordinator
            await coordinator.prepareHistoricalImport([original], teamID: created.teamID, sourceTeamID: sourceTeam.id, links: [MatchPlayerLink(sourceID: batter.id, targetID: official.id)])
            XCTAssertNil(coordinator.message); XCTAssertEqual(coordinator.state.historyImport?.rows.first?.preview?.status, "new")
            XCTAssertNil(coordinator.state.historyImport?.backupURL)
            await coordinator.resumeHistoricalImport()
            XCTAssertTrue(coordinator.state.historyImport?.finished == true, coordinator.message ?? "")
            let backup = try XCTUnwrap(coordinator.state.historyImport?.backupURL)
            XCTAssertTrue(try LocalBackup.read(backup).snapshot().games.contains { $0 == original })
            XCTAssertEqual(store.games.first { $0.id == original.id }, original)
            let remote = try XCTUnwrap(coordinator.state.matches.first), copied = try XCTUnwrap(store.games.first { $0.sharedGameID == remote.id })
            XCTAssertNotEqual(copied.id, original.id); XCTAssertEqual(copied.historySourceID, original.id)
            XCTAssertEqual(copied.state, original.state); XCTAssertEqual(copied.rules, original.rules)
            XCTAssertEqual(copied.playedAt, original.playedAt); XCTAssertEqual(copied.playedTimeZoneID, "Asia/Shanghai"); XCTAssertEqual(copied.yearID, "2025")
            XCTAssertEqual(remote.accessMode, .closed); XCTAssertFalse(remote.liveEnabled)
            let team = try XCTUnwrap(directory.sharedRosterTeams.first)
            let stats = store.seasonStatistics(for: team, seasonID: "2025")
            XCTAssertEqual(stats.games.count, 1); XCTAssertEqual(stats.players.first { $0.id == official.id }?.batting.hits, 1)
            XCTAssertFalse(stats.players.contains { $0.id == batter.id }); XCTAssertTrue(stats.players.contains { $0.isUnlinked })
            XCTAssertEqual(store.gameRecords(for: official, seasonID: "2025", teamID: team.id).first?.batting.hits, 1)
            let report = PlayerStatisticsPDFReport(store: store, player: official, seasonID: "2025", teamID: team.id, gameIDs: [copied.id])
            XCTAssertEqual(report.summary.batting.hits, 1); XCTAssertTrue(try reportText(report.pdfData()).contains("2025"))
            let reader = try await matchPartner(client, team: created.teamID, role: .member), other = GameStore(persistenceURL: nil)
            let readerDirectory = TeamSharingStore(service: reader, storage: V30DirectoryMemoryStorage()); other.teamSharing = readerDirectory; await readerDirectory.connect()
            let sync = SharedMatchCoordinator(store: other, service: reader, storage: V30MatchMemoryStorage()); other.sharedMatches = sync
            await sync.synchronize(teamID: created.teamID)
            XCTAssertEqual(other.games.first?.sharedPlayerLinks, copied.sharedPlayerLinks)
            XCTAssertEqual(other.seasonStatistics(for: try XCTUnwrap(readerDirectory.sharedRosterTeams.first), seasonID: "2025").players.first { $0.id == official.id }?.batting.hits, 1)
            store.openGame(id: copied.id)
            let correctedDate = ISO8601DateFormatter().date(from: "2023-12-31T10:00:00Z")!
            XCTAssertTrue(store.updateCompletedGameDate(id: copied.id, playedAt: correctedDate, timeZoneID: "UTC", expectedRevision: copied.revision ?? 0))
            await coordinator.waitForSync(); await coordinator.synchronize(teamID: created.teamID); await sync.synchronize(teamID: created.teamID)
            XCTAssertEqual(other.games.first?.yearID, "2023"); XCTAssertEqual(other.games.first?.sharedPlayerLinks, copied.sharedPlayerLinks)
            XCTAssertEqual(store.games.first { $0.id == original.id }, original)
        }
    }

    func testV30HistoricalImportLostResponseRelaunchBackupAndDuplicateConflictReview() async throws {
        let store = freshStore(); store.recordPitch(.ball); store.finishGame()
        let original = try XCTUnwrap(store.activeStoredGame), client = try matchClient()
        _ = try await client.enroll(deviceName: "导入 iPhone")
        let team = try await client.createTeam(name: "海浪", nickname: "教练", operationID: UUID())
        let disk = V30MatchMemoryStorage(), proxy = V30UnreliableMatchService(client)
        let importing = SharedMatchCoordinator(store: store, service: proxy, storage: disk); store.sharedMatches = importing
        await importing.prepareHistoricalImport([original], teamID: team.teamID, sourceTeamID: original.ourTeam.id, links: [])
        await proxy.network(loseNextResponse: true); await importing.resumeHistoricalImport()
        XCTAssertNotNil(importing.state.historyImport?.rows.first?.pending); XCTAssertEqual(store.games.count, 1)
        importing.endHistoricalImport(); XCTAssertNotNil(importing.state.historyImport, "Unknown upload outcomes must be resolved, not discarded")
        let relaunched = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = relaunched
        await relaunched.resumeHistoricalImport()
        XCTAssertTrue(relaunched.state.historyImport?.finished == true, relaunched.message ?? "")
        let importedPage = try await client.matchPage(teamID: team.teamID, cursor: 0)
        XCTAssertEqual(importedPage.games.count, 1)
        XCTAssertEqual(store.games.first { $0.id == original.id }, original)
        relaunched.endHistoricalImport()
        await relaunched.prepareHistoricalImport([original], teamID: team.teamID, sourceTeamID: original.ourTeam.id, links: [])
        XCTAssertEqual(relaunched.state.historyImport?.rows.first?.preview?.status, "duplicate")
        await relaunched.resumeHistoricalImport(); XCTAssertEqual(relaunched.state.historyImport?.rows.first?.status, "skipped")
        relaunched.endHistoricalImport()
        store.openGame(id: original.id)
        XCTAssertTrue(store.updateCompletedGameDate(id: original.id, playedAt: original.effectivePlayedAt.addingTimeInterval(-60), timeZoneID: "UTC", expectedRevision: original.revision ?? 0))
        let changed = try XCTUnwrap(store.activeStoredGame)
        await relaunched.prepareHistoricalImport([changed], teamID: team.teamID, sourceTeamID: original.ourTeam.id, links: [])
        XCTAssertEqual(relaunched.state.historyImport?.rows.first?.preview?.status, "conflict"); XCTAssertFalse(relaunched.state.historyImport!.ready)
        relaunched.chooseHistoricalImport(original.id, decision: "skip"); await relaunched.resumeHistoricalImport()
        XCTAssertEqual(store.games.first { $0.id == original.id }, changed)
        let remote = try XCTUnwrap(relaunched.state.matches.first)
        await relaunched.command(remote, resource: "deletion", fields: ["deleted": .bool(true)])
        relaunched.endHistoricalImport()
        await relaunched.prepareHistoricalImport([changed], teamID: team.teamID, sourceTeamID: original.ourTeam.id, links: [])
        XCTAssertEqual(relaunched.state.historyImport?.rows.first?.preview?.status, "deleted")
        await relaunched.resumeHistoricalImport(); XCTAssertEqual(relaunched.state.historyImport?.rows.first?.status, "skipped")
        XCTAssertEqual(store.games.first { $0.id == original.id }, changed)
    }

    func testV30HistoricalImportCancelFailureAndBackupRestoreNeverUploadUnconfirmedGames() async throws {
        let store = freshStore(); store.finishGame(); let original = try XCTUnwrap(store.activeStoredGame), client = try matchClient()
        _ = try await client.enroll(deviceName: "iPhone"); let team = try await client.createTeam(name: "海浪", nickname: "教练", operationID: UUID())
        let disk = V30MatchMemoryStorage(), coordinator = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = coordinator
        await coordinator.prepareHistoricalImport([original], teamID: team.teamID, sourceTeamID: original.ourTeam.id, links: [])
        coordinator.endHistoricalImport(); XCTAssertNil(coordinator.state.historyImport)
        do { let page = try await client.matchPage(teamID: team.teamID, cursor: 0); XCTAssertTrue(page.games.isEmpty) }
        await coordinator.prepareHistoricalImport([original], teamID: team.teamID, sourceTeamID: original.ourTeam.id, links: [])
        disk.failSave = true; await coordinator.resumeHistoricalImport(); XCTAssertNotNil(coordinator.message)
        do { let page = try await client.matchPage(teamID: team.teamID, cursor: 0); XCTAssertTrue(page.games.isEmpty) }
        disk.failSave = false
        let backup = try LocalBackup.read(store.exportBackup()); try store.restoreBackup(backup)
        XCTAssertNil(coordinator.state.historyImport); XCTAssertEqual(store.games.first, original)
        let member = try await matchPartner(client, team: team.teamID, role: .member)
        let denied = SharedMatchCoordinator(store: store, service: member, storage: V30MatchMemoryStorage())
        await denied.prepareHistoricalImport([original], teamID: team.teamID, sourceTeamID: original.ourTeam.id, links: [])
        XCTAssertNil(denied.state.historyImport); XCTAssertNotNil(denied.message)
    }
}

extension GameStoreTests {
    func testV30HistoricalImportPauseResumesOnlyRemainingGamesAndObservationsStayExcluded() async throws {
        let store = freshStore(); store.finishGame()
        let before = try XCTUnwrap(store.activeStoredGame)
        XCTAssertTrue(store.updateCompletedGameDate(id: before.id, playedAt: before.effectivePlayedAt.addingTimeInterval(-86400), timeZoneID: "UTC", expectedRevision: before.revision ?? 0))
        let first = try XCTUnwrap(store.activeStoredGame), originalTeam = try XCTUnwrap(store.currentTeam)
        store.startNewGame(opponent: store.opponentTeams[0], isHome: false, innings: 6, lineup: Array(originalTeam.players.prefix(9)))
        store.recordPitch(.ball); store.finishGame(); let second = try XCTUnwrap(store.activeStoredGame)
        let client = try matchClient(); _ = try await client.enroll(deviceName: "iPhone")
        let team = try await client.createTeam(name: "海浪", nickname: "教练", operationID: UUID(), profile: SharedRosterSeed(team: originalTeam))
        let directory = TeamSharingStore(service: client, storage: V30DirectoryMemoryStorage()); store.teamSharing = directory; await directory.connect()
        let proxy = V30UnreliableMatchService(client), disk = V30MatchMemoryStorage()
        let coordinator = SharedMatchCoordinator(store: store, service: proxy, storage: disk); store.sharedMatches = coordinator
        await coordinator.prepareHistoricalImport([first,second], teamID: team.teamID, sourceTeamID: originalTeam.id, links: [])
        await proxy.afterImportCommit { coordinator.pauseHistoricalImport() }
        await coordinator.resumeHistoricalImport()
        XCTAssertEqual(coordinator.state.historyImport?.rows.map(\.status), ["imported", "waiting"])
        XCTAssertEqual(store.games.first { $0.id == first.id }, first); XCTAssertEqual(store.games.first { $0.id == second.id }, second)
        let resumed = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = resumed
        await resumed.resumeHistoricalImport(); XCTAssertTrue(resumed.state.historyImport?.finished == true, resumed.message ?? "")
        let page = try await client.matchPage(teamID: team.teamID, cursor: 0); XCTAssertEqual(page.games.count, 2)
        resumed.endHistoricalImport()
        let observedID = try XCTUnwrap(store.createObservedGame(awayTeam: store.opponentTeams[0], homeTeam: store.opponentTeams[1], rules: GameRules(), startImmediately: true))
        store.openGame(id: observedID); store.finishGame(); let observation = try XCTUnwrap(store.activeStoredGame)
        await resumed.prepareHistoricalImport([observation], teamID: team.teamID, sourceTeamID: observation.state.awayTeam.id, links: [])
        if resumed.state.historyImport?.rows.first?.preview?.status == "similar" { resumed.chooseHistoricalImport(observation.id, decision: "independent") }
        await resumed.resumeHistoricalImport(); XCTAssertTrue(resumed.state.historyImport?.finished == true, resumed.message ?? "")
        let copy = try XCTUnwrap(store.games.first { $0.historySourceID == observation.id }); XCTAssertTrue(copy.isObservation); XCTAssertNil(copy.ourTeamID)
        let completed = store.completedStatisticsGames(teamID: team.teamID, seasonID: observation.yearID)
        XCTAssertFalse(completed.contains { $0.id == copy.id }); XCTAssertEqual(store.games.first { $0.id == observation.id }, observation)
    }
}

extension GameStoreTests {
    func testV30PlayerLinksAcrossModesDevicesStatisticsAndReportsKeepOriginalMatch() async throws {
        for mode in [GameMode.standard, .coachPitch, .slowPitch] {
            var rules = GameRules(); rules.mode = mode
            let store = mode == .slowPitch ? try slowStore() : v111Store(rules: rules)
            let temporary = store.currentBatter
            XCTAssertTrue(store.applyPlay(.single)); store.finishGame()
            let original = try XCTUnwrap(store.activeStoredGame)
            let official = Player(chineseName: "正式陈昊", englishName: "", numbers: [0])
            let team = Team(name: "海浪", shortName: "海浪", city: "", players: [official])
            let client = try matchClient(); _ = try await client.enroll(deviceName: "iPhone 17 Pro")
            let created = try await client.createTeam(name: team.name, nickname: "陈教练", operationID: UUID(), profile: SharedRosterSeed(team: team))
            store.teamSharing = TeamSharingStore(service: client, storage: V30DirectoryMemoryStorage()); await store.teamSharing.connect()
            let sync = SharedMatchCoordinator(store: store, service: client, storage: V30MatchMemoryStorage()); store.sharedMatches = sync
            await sync.publish(original, teamID: created.teamID); XCTAssertNil(sync.message)
        let sharedCopyID = try XCTUnwrap(sync.state.matches.last?.payload?.id)
        store.openGame(id: sharedCopyID)
            let before = try XCTUnwrap(sync.state.matches.first)
            let links = [MatchPlayerLink(sourceID: temporary.id, targetID: official.id)]
            await sync.command(before, resource: "player-links", fields: ["playerLinks": try .encode(links), "baseRosterRevision": .number(1)])
            XCTAssertNil(sync.message)
            let linked = try XCTUnwrap(sync.match(id: before.id)), cloud = try XCTUnwrap(store.teamSharing.sharedRosterTeams.first)
            XCTAssertEqual(linked.serverRevision, before.serverRevision + 1); XCTAssertEqual(linked.payload?.state, original.state)
            XCTAssertEqual(linked.playerLinks, links); XCTAssertEqual(linked.payload?.ourTeam, original.ourTeam)
            let stats = store.seasonStatistics(for: cloud, seasonID: original.yearID)
            XCTAssertEqual(stats.games.count, 1); XCTAssertEqual(stats.players.first { $0.id == official.id }?.batting.hits, 1)
            XCTAssertFalse(stats.players.contains { $0.id == temporary.id }); XCTAssertEqual(stats.batting.hits, 1)
            let report = PlayerStatisticsPDFReport(store: store, player: official, seasonID: original.yearID, teamID: cloud.id, gameIDs: [sharedCopyID])
            XCTAssertEqual(report.summary.batting.hits, 1); XCTAssertEqual(report.records.count, 1)
            XCTAssertTrue(try reportText(report.pdfData()).contains("正式陈昊"))
            XCTAssertTrue(try reportText(TeamSeasonPDFReport(store: store, team: cloud, seasonID: original.yearID).pdfData()).contains("球队记录"))
            let reader = try await matchPartner(client, team: cloud.id, role: .member), other = GameStore(persistenceURL: nil)
            other.teamSharing = TeamSharingStore(service: reader, storage: V30DirectoryMemoryStorage()); await other.teamSharing.connect()
            other.sharedMatches = SharedMatchCoordinator(store: other, service: reader, storage: V30MatchMemoryStorage())
            await other.sharedMatches.synchronize() // A new device discovers all its teams, even with no cached matches.
            XCTAssertNil(other.sharedMatches.message); XCTAssertEqual(other.sharedMatches.state.matches.count, 1)
            XCTAssertEqual(other.seasonStatistics(for: try XCTUnwrap(other.teamSharing.sharedRosterTeams.first), seasonID: original.yearID).batting, stats.batting)
            XCTAssertEqual(other.gameRecords(for: official, seasonID: original.yearID, teamID: cloud.id).first?.batting.hits, 1)
            await sync.command(linked, resource: "player-links", fields: ["playerLinks": .array([]), "baseRosterRevision": .number(1)])
            XCTAssertNil(sync.message); await other.sharedMatches.synchronize()
            XCTAssertEqual(other.seasonStatistics(for: cloud, seasonID: original.yearID).players.first { $0.id == official.id }?.batting.hits, 0)
            XCTAssertEqual(other.seasonStatistics(for: cloud, seasonID: original.yearID).players.first { $0.id == temporary.id }?.batting.hits, 1)
            XCTAssertEqual(other.seasonStatistics(for: cloud, seasonID: original.yearID).batting.hits, 1)
        }
    }

    func testV30SharedStatisticsExcludeUnacknowledgedDraftsAndDeletedGames() async throws {
        let store = freshStore(); XCTAssertTrue(store.applyPlay(.single)); store.finishGame()
        let original = try XCTUnwrap(store.activeStoredGame), player = store.currentTeam!.players.first!
        let client = try matchClient(); _ = try await client.enroll(deviceName: "iPhone")
        let created = try await client.createTeam(name: "飞鹰", nickname: "教练", operationID: UUID(), profile: SharedRosterSeed(team: original.ourTeam))
        store.teamSharing = TeamSharingStore(service: client, storage: V30DirectoryMemoryStorage()); await store.teamSharing.connect()
        let disk = V30MatchMemoryStorage(), sync = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = sync
        await sync.publish(original, teamID: created.teamID)
        let sharedCopyID = try XCTUnwrap(sync.state.matches.last?.payload?.id)
        store.openGame(id: sharedCopyID)
        let cloud = try XCTUnwrap(store.teamSharing.sharedRosterTeams.first), remote = try XCTUnwrap(sync.state.matches.first)
        let expected = store.seasonStatistics(for: cloud, seasonID: original.yearID)
        var dirty = try XCTUnwrap(store.games.first { $0.id == sharedCopyID })
        dirty.state.batting[player.id, default: BattingLine()].hits += 20
        dirty.state.batting[player.id, default: BattingLine()].atBats += 20
        dirty.playedAt = ISO8601DateFormatter().date(from: "2021-01-01T12:00:00Z")!
        dirty.revision = (dirty.revision ?? 0) + 1
        try store.installSharedCopy(dirty)
        XCTAssertEqual(store.seasonStatistics(for: cloud, seasonID: original.yearID).batting, expected.batting)
        XCTAssertEqual(store.completedStatisticsGames(teamID: cloud.id, seasonID: "2021").count, 0)
        XCTAssertEqual(TeamSeasonPDFReport(store: store, team: cloud, seasonID: original.yearID).summary.batting, expected.batting)
        let relaunched = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = relaunched
        XCTAssertEqual(store.seasonStatistics(for: cloud, seasonID: original.yearID).batting, expected.batting)
        await relaunched.refresh(remote)
        XCTAssertEqual(store.games.first { $0.id == sharedCopyID }?.state.batting[player.id]?.hits, dirty.state.batting[player.id]?.hits, "Refreshing an unchanged official version must not erase a local draft")
        XCTAssertTrue(relaunched.state.conflicts.isEmpty)
        _ = try await client.matchCommand(path: TeamServiceClient.matchPath(cloud.id, remote.id) + "/deletion", method: "POST", body: .object([
            "operationID": .uuid(UUID()), "baseRevision": .number(Double(remote.serverRevision)), "writerEpoch": .number(Double(remote.writerEpoch)), "deleted": .bool(true)
        ]))
        await relaunched.refresh(remote)
        XCTAssertNil(relaunched.message); XCTAssertTrue(store.completedStatisticsGames(teamID: cloud.id, seasonID: original.yearID).isEmpty)
        XCTAssertTrue(relaunched.state.conflicts.contains { $0.game.state.batting[player.id]?.hits == dirty.state.batting[player.id]?.hits })
    }

    func testV30PlayerLinksLostResponseRetryPersistenceAndStaleReviewDoNotLoseDraft() async throws {
        let store = freshStore(); store.finishGame()
        let original = try XCTUnwrap(store.activeStoredGame), source = original.ourTeam.players[0]
        let official = Player(chineseName: "关联目标", englishName: "", numbers: [12])
        let client = try matchClient(); _ = try await client.enroll(deviceName: "iPhone")
        let created = try await client.createTeam(name: "海浪", nickname: "教练", operationID: UUID(), profile: SharedRosterSeed(team: Team(name: "海浪", shortName: "海浪", city: "", players: [official])))
        store.teamSharing = TeamSharingStore(service: client, storage: V30DirectoryMemoryStorage()); await store.teamSharing.connect()
        let disk = V30MatchMemoryStorage(), proxy = V30UnreliableMatchService(client)
        let sync = SharedMatchCoordinator(store: store, service: proxy, storage: disk); store.sharedMatches = sync
        await sync.publish(original, teamID: created.teamID)
        let before = try XCTUnwrap(sync.state.matches.first), fields: [String: MatchJSON] = ["playerLinks": try .encode([MatchPlayerLink(sourceID: source.id, targetID: official.id)]), "baseRosterRevision": .number(1)]
        disk.failSave = true; await sync.command(before, resource: "player-links", fields: fields); disk.failSave = false
        let unchanged = try await client.match(teamID: created.teamID, gameID: before.id); XCTAssertEqual(unchanged.serverRevision, before.serverRevision)
        await proxy.network(loseNextResponse: true)
        await sync.command(before, resource: "player-links", fields: fields)
        XCTAssertNotNil(sync.message); XCTAssertEqual(sync.state.pending.count, 1); XCTAssertEqual(sync.match(id: before.id)?.playerLinks, [])
        let pendingID = sync.state.pending.first?.id
        let resumed = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = resumed
        XCTAssertEqual(resumed.state.pending.first?.id, pendingID); await resumed.synchronize()
        XCTAssertNil(resumed.message); XCTAssertTrue(resumed.state.pending.isEmpty)
        XCTAssertEqual(resumed.match(id: before.id)?.serverRevision, before.serverRevision + 1)
        await resumed.command(before, resource: "player-links", fields: ["playerLinks": .array([]), "baseRosterRevision": .number(1)])
        XCTAssertNotNil(resumed.message); XCTAssertEqual(resumed.match(id: before.id)?.playerLinks?.first?.targetID, official.id)
        await resumed.refresh(try XCTUnwrap(resumed.match(id: before.id))); XCTAssertNil(resumed.message)
        let json = try await client.matchCommand(path: TeamServiceClient.matchPath(created.teamID,before.id) + "/player-links", method: "GET", body: .object([:]))
        let history = try JSONDecoder().decode(PlayerLinkHistory.self, from: JSONEncoder().encode(json))
        XCTAssertEqual(history.changes.count, 1); XCTAssertEqual(history.changes.first?.before, [])
    }
}

extension GameStoreTests {
    func testV30NewOfficialVersionWhileSameWriterHasDraftPreservesConflictAndCleanRefreshInstalls() async throws {
        let store = freshStore(); store.finishGame(); let original = try XCTUnwrap(store.activeStoredGame)
        let client = try matchClient(); _ = try await client.enroll(deviceName: "iPhone")
        let team = try await client.createTeam(name: "海浪", nickname: "教练", operationID: UUID())
        let sync = SharedMatchCoordinator(store: store, service: client, storage: V30MatchMemoryStorage()); store.sharedMatches = sync
        await sync.publish(original, teamID: team.teamID)
        let sharedCopyID = try XCTUnwrap(sync.state.matches.last?.payload?.id)
        store.openGame(id: sharedCopyID)
        let remote = try XCTUnwrap(sync.state.matches.first)
        var updated = try XCTUnwrap(remote.payload); updated.revision = (updated.revision ?? 0) + 1
        updated.playedAt = Date(timeIntervalSince1970: 1700000000)
        func upload(_ match: SharedMatch, _ game: StoredGame) async throws {
            var game = game; game.id = match.wireLocalID ?? game.id
            _ = try await client.matchCommand(path: TeamServiceClient.matchPath(team.teamID, match.id), method: "PUT", body: .object([
                "operationID": .uuid(UUID()), "baseRevision": .number(Double(match.serverRevision)), "writerEpoch": .number(Double(match.writerEpoch)),
                "formatVersion": .number(2), "payload": try .encode(game), "projection": try JSONDecoder().decode(MatchJSON.self, from: LiveSnapshot(game).data())
            ]))
        }
        try await upload(remote, updated); await sync.refresh(remote)
        XCTAssertEqual(store.games.first { $0.id == sharedCopyID }?.playedAt, updated.playedAt)
        XCTAssertTrue(sync.state.conflicts.isEmpty)
        let current = try XCTUnwrap(sync.state.matches.first)
        var dirty = try XCTUnwrap(store.games.first { $0.id == sharedCopyID }); dirty.revision = (dirty.revision ?? 0) + 1
        dirty.playedAt = Date(timeIntervalSince1970: 1600000000); try store.installSharedCopy(dirty)
        updated.revision = (updated.revision ?? 0) + 1; updated.playedAt = Date(timeIntervalSince1970: 1500000000)
        try await upload(current, updated); await sync.refresh(current)
        XCTAssertNil(sync.message); XCTAssertEqual(sync.state.conflicts.count, 1)
        XCTAssertEqual(sync.state.conflicts.first?.game.playedAt, dirty.playedAt)
        XCTAssertEqual(store.games.first { $0.id == sharedCopyID }?.playedAt, updated.playedAt)
        XCTAssertFalse(sync.canEdit(sharedCopyID))
        try sync.useOfficialVersion(try XCTUnwrap(sync.state.conflicts.first)); XCTAssertTrue(sync.canEdit(sharedCopyID))
        XCTAssertEqual(sync.state.resolvedConflicts?.first?.game.playedAt, dirty.playedAt)
    }
}

extension GameStoreTests {
    func testV30ConflictSubmissionLostReplyAdminReviewAndDecisionKeepOriginalDraft() async throws {
        let client = try matchClient(); _ = try await client.enroll(deviceName: "原设备")
        let team = try await client.createTeam(name: "海浪", nickname: "教练", operationID: UUID())
        let store = freshStore(), disk = V30MatchMemoryStorage(), proxy = V30UnreliableMatchService(client)
        let sync = SharedMatchCoordinator(store: store, service: proxy, storage: disk); store.sharedMatches = sync
        store.startGameClock(); let source = try XCTUnwrap(store.activeStoredGame)
        await sync.publish(source, teamID: team.teamID)
        let sharedCopyID = try XCTUnwrap(sync.state.matches.last?.payload?.id)
        store.openGame(id: sharedCopyID)
        let original = try XCTUnwrap(sync.match(localID: sharedCopyID))
        // Preserve an offline alternative, while the official record remains unchanged.
        var draftGame = source; draftGame.id = sharedCopyID; draftGame.playedAt = Date(timeIntervalSince1970: 1600000000)
        let draft = MatchConflict(id: UUID(), localID: sharedCopyID, savedAt: Date(), game: draftGame, reason: "旧设备断网期间记录")
        disk.state.conflicts = [draft]
        let coordinator = SharedMatchCoordinator(store: store, service: proxy, storage: disk); store.sharedMatches = coordinator
        await proxy.network(loseNextResponse: true)
        await coordinator.submitConflict(draft, match: original)
        XCTAssertNotNil(coordinator.message); XCTAssertEqual(coordinator.state.pending.count, 1)
        XCTAssertFalse(coordinator.state.submittedConflictIDs?.contains(draft.id) == true)
        let resumed = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = resumed
        await resumed.synchronize()
        XCTAssertNil(resumed.message); XCTAssertTrue(resumed.state.submittedConflictIDs?.contains(draft.id) == true)
        XCTAssertEqual(resumed.match(id: original.id)?.serverRevision, original.serverRevision)
        XCTAssertEqual(resumed.state.conflicts.first?.game.playedAt, draftGame.playedAt)
        struct DraftList: Decodable { let drafts: [SharedConflictDraft] }
        let path = TeamServiceClient.matchPath(team.teamID, original.id) + "/drafts"
        let data = try await client.matchCommand(path: path, method: "GET", body: .object([:]))
        let list = try JSONDecoder().decode(DraftList.self, from: JSONEncoder().encode(data))
        XCTAssertEqual(list.drafts.count, 1)
        let pending = try XCTUnwrap(list.drafts.first)
        try resumed.useOfficialVersion(draft)
        await resumed.command(try XCTUnwrap(resumed.match(id: original.id)), resource: "drafts/" + pending.id.uuidString.lowercased(), fields: ["decision": .string("discarded"), "note": .string("核对后采用球队记录")])
        XCTAssertNil(resumed.message); XCTAssertEqual(resumed.match(id: original.id)?.serverRevision, original.serverRevision + 1)
        XCTAssertEqual(resumed.state.resolvedConflicts?.first?.game.playedAt, draftGame.playedAt)
        let read = try await client.matchCommand(path: path + "/" + pending.id.uuidString.lowercased(), method: "GET", body: .object([:]))
        let finished = try JSONDecoder().decode(SharedConflictDraft.self, from: JSONEncoder().encode(read))
        XCTAssertEqual(finished.status, "discarded"); XCTAssertEqual(finished.payload?.playedAt, draftGame.playedAt)
        let member = try await matchPartner(client, team: team.teamID, role: .member)
        do { _ = try await member.matchCommand(path: path, method: "GET", body: .object([:])); XCTFail("Members must not read admin drafts") }
        catch { XCTAssertTrue(error.localizedDescription.contains("角色")) }
    }

    func testV30ExpiredCursorRebuildKeepsDraftAndAllOfficialGames() async throws {
        let client = try matchClient(); _ = try await client.enroll(deviceName: "iPhone")
        let team = try await client.createTeam(name: "飞鹰", nickname: "教练", operationID: UUID())
        let store = freshStore(), disk = V30MatchMemoryStorage(), proxy = V30UnreliableMatchService(client)
        let sync = SharedMatchCoordinator(store: store, service: proxy, storage: disk); store.sharedMatches = sync
        let game = try XCTUnwrap(store.activeStoredGame); await sync.publish(game, teamID: team.teamID)
        let sharedCopyID = try XCTUnwrap(sync.state.matches.last?.payload?.id)
        store.openGame(id: sharedCopyID)
        let remote = try XCTUnwrap(sync.state.matches.first)
        disk.state.conflicts = [MatchConflict(id: UUID(), localID: sharedCopyID, savedAt: Date(), game: try XCTUnwrap(store.games.first { $0.id == sharedCopyID }), reason: "待核对资料")]
        let resumed = SharedMatchCoordinator(store: store, service: proxy, storage: disk); store.sharedMatches = resumed
        await proxy.expireNextCursor(); await resumed.synchronize()
        XCTAssertNil(resumed.message); XCTAssertEqual(resumed.state.matches.count, 1)
        XCTAssertEqual(resumed.match(id: remote.id)?.serverRevision, remote.serverRevision)
        XCTAssertEqual(resumed.state.conflicts.count, 1); XCTAssertFalse(resumed.canEdit(sharedCopyID))
        XCTAssertGreaterThan(resumed.state.cursors[team.teamID.uuidString] ?? 0, 0)
    }

    func testV30DissolutionRestorationDirectoryClearsAccessAndExposesAudit() async throws {
        let client = try matchClient(); _ = try await client.enroll(deviceName: "iPhone 17 Pro")
        let directory = TeamSharingStore(service: client, storage: V30DirectoryMemoryStorage())
        await directory.connect()
        let didCreate = await directory.submit(.create(name: "海浪", nickname: "教练", localTeamID: nil, profile: nil)); XCTAssertTrue(didCreate)
        let team = try XCTUnwrap(directory.teams.first)
        let page: TeamAuditPage = try await directory.securityRead("teams/\(team.id.uuidString.lowercased())/audit")
        XCTAssertEqual(page.events.first?.nickname, "教练")
        let dissolve = TeamSecurityCommand(path: "teams/\(team.id.uuidString.lowercased())/dissolve", method: "POST", fields: ["confirm": .bool(true), "name": .string(team.name), "baseRevision": .number(Double(team.revision))], kind: "team-dissolve", teamID: team.id)
        let didDissolve = await directory.submit(.security(dissolve)); XCTAssertTrue(didDissolve)
        XCTAssertTrue(directory.teams.isEmpty); XCTAssertNil(directory.state.recoveryCards?.first { $0.teamID == team.id })
        struct Deleted: Decodable { let teams: [DissolvedTeam] }
        let deleted: Deleted = try await directory.securityRead("dissolved-teams"); XCTAssertEqual(deleted.teams.first?.id, team.id)
        let restore = TeamSecurityCommand(path: "teams/\(team.id.uuidString.lowercased())/restore", method: "POST", fields: ["confirm": .bool(true), "name": .string(team.name)], kind: "team-restore", teamID: team.id)
        let didRestore = await directory.submit(.security(restore)); XCTAssertTrue(didRestore)
        XCTAssertEqual(directory.teams.first?.id, team.id); XCTAssertEqual(directory.teams.first?.role, .admin)
        let after: TeamAuditPage = try await directory.securityRead("teams/\(team.id.uuidString.lowercased())/audit")
        XCTAssertTrue(after.events.contains { $0.action == "teamRestored" })
    }
}

extension GameStoreTests {
    func testV30RecoveryReviewPreservesAcknowledgedRecordsAndNeverReplaysOldMutation() async throws {
        let client = try matchClient(); _ = try await client.enroll(deviceName: "原记录设备")
        let team = try await client.createTeam(name: "海浪", nickname: "教练", operationID: UUID())
        let store = freshStore(), proxy = V30UnreliableMatchService(client), disk = V30MatchMemoryStorage()
        let sync = SharedMatchCoordinator(store: store, service: proxy, storage: disk); store.sharedMatches = sync
        let source = try XCTUnwrap(store.activeStoredGame); await sync.publish(source, teamID: team.teamID)
        let sharedCopyID = try XCTUnwrap(sync.state.matches.last?.payload?.id)
        store.openGame(id: sharedCopyID)
        let before = await proxy.operations.count
        await proxy.simulateServerRecovery(); await sync.synchronize()
        XCTAssertTrue(sync.state.requiresRecoveryReview); XCTAssertFalse(sync.canEdit(sharedCopyID))
        let reconciled = await sync.reconcileAfterRecovery(); XCTAssertTrue(reconciled)
        XCTAssertTrue(sync.state.requiresRecoveryReview, "Editing waits for both the directory and match review")
        XCTAssertEqual(sync.state.conflicts.first?.game.state, source.state)
        XCTAssertTrue(sync.state.pending.isEmpty)
        try sync.finishRecoveryReview()
        XCTAssertFalse(sync.state.requiresRecoveryReview); XCTAssertFalse(sync.canEdit(sharedCopyID), "Preserved original still needs a match-level decision")
        let after = await proxy.operations.count; XCTAssertEqual(after, before)
        let resumed = SharedMatchCoordinator(store: store, service: proxy, storage: disk)
        XCTAssertEqual(resumed.state.conflicts.count, 1); XCTAssertFalse(resumed.state.requiresRecoveryReview)
    }
    func testV30RestoringOlderLocalBackupRequiresReviewBeforeAnySharedUpload() async throws {
        let client = try matchClient(); _ = try await client.enroll(deviceName: "原记录设备")
        let team = try await client.createTeam(name: "飞鹰", nickname: "教练", operationID: UUID())
        let store = freshStore(), proxy = V30UnreliableMatchService(client), disk = V30MatchMemoryStorage()
        let sync = SharedMatchCoordinator(store: store, service: proxy, storage: disk); store.sharedMatches = sync
        let source = try XCTUnwrap(store.activeStoredGame); await sync.publish(source, teamID: team.teamID)
        let sharedCopyID = try XCTUnwrap(sync.state.matches.last?.payload?.id)
        store.openGame(id: sharedCopyID)
        let backup = try LocalBackup(snapshot: store.makeRosterSnapshot())
        store.openGame(id: sharedCopyID); store.startGameClock(); store.recordPitch(.ball); await sync.waitForSync(); await sync.synchronize()
        let current = try XCTUnwrap(sync.match(localID: sharedCopyID)), count = await proxy.operations.count
        try store.restoreBackup(backup)
        XCTAssertTrue(sync.state.requiresRecoveryReview)
        await sync.synchronize()
        let stoppedCount = await proxy.operations.count; XCTAssertEqual(stoppedCount, count)
        let remote = try await client.match(teamID: team.teamID, gameID: current.id)
        XCTAssertEqual(remote.serverRevision, current.serverRevision); XCTAssertEqual(remote.payload?.state.balls, 1)
        let reconciled = await sync.reconcileAfterRecovery(); XCTAssertTrue(reconciled)
        try sync.finishRecoveryReview()
        XCTAssertEqual(store.games.first { $0.id == sharedCopyID }?.state.balls, 1)
        XCTAssertEqual(sync.state.conflicts.first?.game.state.balls, try backup.snapshot().games.first?.state.balls)
        let finalCount = await proxy.operations.count; XCTAssertEqual(finalCount, count)
    }

    func testV30PortableTeamArchiveRoundTripAndCorruptionNeverRestoresCloudAuthority() async throws {
        let client = try matchClient(); _ = try await client.enroll(deviceName: "档案导出 iPhone")
        let store = freshStore(), source = try XCTUnwrap(store.activeStoredGame)
        let team = try await client.createTeam(name: "海浪档案", nickname: "教练", operationID: UUID(), profile: SharedRosterSeed(team: source.ourTeam))
        let sync = SharedMatchCoordinator(store: store, service: client, storage: V30MatchMemoryStorage()); store.sharedMatches = sync
        await sync.publish(source, teamID: team.teamID)
        let url = try await client.exportTeamArchive(teamID: team.teamID)
        defer { try? FileManager.default.removeItem(at: url) }
        let archive = try PortableTeamArchive.validate(url)
        XCTAssertEqual(archive.manifest.profile.name, "海浪档案"); XCTAssertEqual(archive.records.count, 1)
        XCTAssertEqual(archive.records.first?.payload?.state, source.state)
        XCTAssertEqual(archive.records.first?.payload?.rules, source.rules)
        XCTAssertNil(archive.records.first?.payload?.sharedGameID)
        let restored = try PortableTeamArchive.restore(url); defer { try? FileManager.default.removeItem(at: restored) }
        XCTAssertEqual(try PortableTeamArchive.validate(restored).records.first?.id, archive.records.first?.id)
        XCTAssertEqual(store.games.count, 2, "Importing an archive does not replace the current database")
        let data = try Data(contentsOf: url), damaged = url.deletingLastPathComponent().appendingPathComponent(UUID().uuidString + ".bmteam")
        defer { try? FileManager.default.removeItem(at: damaged) }
        try data.dropLast(32).write(to: damaged); XCTAssertThrowsError(try PortableTeamArchive.validate(damaged))
        var appended = data; appended.append(data); try appended.write(to: damaged)
        XCTAssertThrowsError(try PortableTeamArchive.validate(damaged))
        let member = try await matchPartner(client, team: team.teamID, role: .member)
        do { _ = try await member.exportTeamArchive(teamID: team.teamID); XCTFail("Only administrators export a full archive") }
        catch { XCTAssertTrue(error.localizedDescription.contains("角色")) }
    }

    func testV30CredentialRotationKeepsEncryptedCachesReadableAndRecoversLostCommitResponse() async throws {
        let account = "rotation-test-" + UUID().uuidString, vault = TeamDeviceKeychain(account: account)
        defer { for suffix in ["", ".storage-key", ".rotation-pending", ".rotation-date"] {
            SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.jasonchen.baseballmaster.collaboration", kSecAttrAccount as String: account + suffix] as CFDictionary)
        } }
        let client = try TeamServiceClient(baseURL: URL(string: "http://127.0.0.1:18788/api/v3")!, vault: vault)
        let device = try await client.enroll(deviceName: "iPhone"), old = try vault.loadOrCreate()
        let team = try await client.createTeam(name: "海浪", nickname: "教练", operationID: UUID())
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let file = SharedMatchFile(url: folder.appendingPathComponent("cache.sealed"), vault: vault)
        var state = SharedMatchLocalState(); state.deviceID = device; try file.save(state)
        let pending = try vault.beginRotation()
        let _: MatchJSON = try await client.request("POST", path: "devices/credential-prepare", body: MatchJSON.object(["operationID": .uuid(pending.id), "newToken": .string(pending.token)]))
        let _: MatchJSON = try await client.request("POST", path: "devices/credential-commit", body: MatchJSON.object(["rotationID": .uuid(pending.id)]), credential: pending.token)
        XCTAssertEqual(try vault.loadOrCreate(), old, "Simulate server commit before the local receipt was saved")
        let enrolled = try await client.enroll(deviceName: "iPhone"); XCTAssertEqual(enrolled, device)
        XCTAssertEqual(try vault.loadOrCreate(), pending.token); XCTAssertNil(try vault.pendingRotation())
        XCTAssertEqual(try file.load().deviceID, device)
        let teams = try await client.teams(); XCTAssertEqual(teams.teams.first?.id, team.teamID)
        try vault.replaceLostCredential(); XCTAssertEqual(try file.load().deviceID, device)
        XCTAssertNotEqual(try vault.loadOrCreate(), pending.token)
    }
    func testV30CompleteSixInningMatchesAcrossThreeModesRoundTripWithPortableArchive() async throws {
        for mode in [GameMode.standard, .coachPitch, .slowPitch] {
            let store = try v30Backfill(mode)
            store.startGameClock()
            for _ in 0..<12 {
                if store.game.isFinal { break }
                store.recordPitch(.ball); store.recordPitch(.calledStrike); store.applyPlay(.single)
                store.recordPitch(.foul); store.applyPlay(.double)
                for _ in 0..<3 { store.recordPitch(.swingingStrike); store.applyPlay(.groundOut, defensivePlay: DefensivePlay.quickPlays[0]) }
            }
            if !store.game.isFinal { store.finishGame() }
            let game = try XCTUnwrap(store.activeStoredGame)
            XCTAssertTrue(game.state.isFinal); XCTAssertGreaterThanOrEqual(game.state.inning, 6)
            XCTAssertGreaterThan(game.state.scoringEvents?.count ?? 0, 100)
            _ = try LocalBackup(snapshot: store.makeRosterSnapshot()).snapshot()
            let client = try matchClient(); _ = try await client.enroll(deviceName: "完整比赛验证 iPhone")
            let team = try await client.createTeam(name: "海浪", nickname: "陈教练", operationID: UUID(), profile: SharedRosterSeed(team: game.ourTeam))
            let sync = SharedMatchCoordinator(store: store, service: client, storage: V30MatchMemoryStorage()); store.sharedMatches = sync
            await sync.publish(game, teamID: team.teamID)
        let sharedCopyID = try XCTUnwrap(sync.state.matches.last?.payload?.id)
        store.openGame(id: sharedCopyID)
            let saved = try XCTUnwrap(sync.match(localID: sharedCopyID)); XCTAssertEqual(saved.payload?.state, game.state)
            let url = try await client.exportTeamArchive(teamID: team.teamID)
            XCTAssertEqual(try PortableTeamArchive.validate(url).records.first?.payload?.state, game.state)
            try? FileManager.default.removeItem(at: url)
            let body = MatchJSON.object(["formatVersion": .number(1), "payload": try .encode(game), "projection": try JSONDecoder().decode(MatchJSON.self, from: LiveSnapshot(game).data())])
            let capture = XCTAttachment(data: try JSONEncoder().encode(body), uniformTypeIdentifier: "public.json")
            capture.name = "3.0-complete-match-\(mode.rawValue).json"; capture.lifetime = .keepAlways; add(capture)
        }
    }
    func testV30ProductionHTTPSAppClientSyncArchiveAndClosedHistory() async throws {
        guard ProcessInfo.processInfo.environment["BM_PRODUCTION_ACCEPTANCE"] == "1" else { throw XCTSkip("Explicit production validation only") }
        let client = try TeamServiceClient(vault: V30MemoryTeamCredential(value: TeamDeviceKeychain.randomSecret()))
        _ = try await client.enroll(deviceName: "3.0 发布验证 iPhone")
        let store = freshStore(), source = try XCTUnwrap(store.activeStoredGame)
        let team = try await client.createTeam(name: "App HTTPS 临时验证", nickname: "验证记录员", operationID: UUID(), profile: SharedRosterSeed(team: source.ourTeam))
        do {
            let sync = SharedMatchCoordinator(store: store, service: client, storage: V30MatchMemoryStorage()); store.sharedMatches = sync
            await sync.publish(source, teamID: team.teamID)
        let sharedCopyID = try XCTUnwrap(sync.state.matches.last?.payload?.id)
        store.openGame(id: sharedCopyID)
            let remote = try XCTUnwrap(sync.match(localID: sharedCopyID), sync.message ?? "Shared match missing")
            XCTAssertEqual(remote.payload?.state, source.state); XCTAssertEqual(remote.accessMode, .closed)
            store.openGame(id: sharedCopyID); store.startGameClock(); store.recordPitch(.ball); await sync.waitForSync(); await sync.synchronize()
            let updated = try await client.match(teamID: team.teamID, gameID: remote.id); XCTAssertEqual(updated.payload?.state.balls, 1)
            let archive = try await client.exportTeamArchive(teamID: team.teamID)
            XCTAssertEqual(try PortableTeamArchive.validate(archive).records.first?.payload?.state.balls, 1); try? FileManager.default.removeItem(at: archive)
            let detail = try await client.detail(teamID: team.teamID)
            _ = try await client.securityRequest(path: "teams/\(team.teamID.uuidString.lowercased())/dissolve", method: "POST", body: .object(["operationID": .uuid(UUID()), "confirm": .bool(true), "name": .string(detail.team.name), "baseRevision": .number(Double(detail.team.revision))]))
        } catch {
            if let detail = try? await client.detail(teamID: team.teamID) {
                _ = try? await client.securityRequest(path: "teams/\(team.teamID.uuidString.lowercased())/dissolve", method: "POST", body: .object(["operationID": .uuid(UUID()), "confirm": .bool(true), "name": .string(detail.team.name), "baseRevision": .number(Double(detail.team.revision))]))
            }
            throw error
        }
    }
}

extension GameStoreTests {
    func testV301SevenLocalGamesImportFiveAndRemainEditableAcrossReload() async throws {
        let store = freshStore(), client = try matchClient()
        _ = try await client.enroll(deviceName: "3.0.1 QA")
        let localTeam = try XCTUnwrap(store.currentTeam)
        let team = try await client.createTeam(name: localTeam.name, nickname: "管理员", operationID: UUID(), profile: SharedRosterSeed(team: localTeam))
        let directory = TeamSharingStore(service: client, storage: V30DirectoryMemoryStorage())
        store.teamSharing = directory; await directory.connect()
        for index in 0..<7 {
            if index > 0 { store.startNewGame(opponent: store.opponentTeams[0], isHome: false, innings: 6, lineup: Array(localTeam.players.prefix(9))) }
            store.finishGame()
            let game = try XCTUnwrap(store.activeStoredGame)
            XCTAssertTrue(store.updateCompletedGameDate(id: game.id, playedAt: Date().addingTimeInterval(Double(-index-1)*86400), timeZoneID: "Asia/Shanghai", expectedRevision: game.revision ?? 0))
        }
        let originals = store.recentGames
        XCTAssertEqual(originals.count, 7)
        let disk = V30MatchMemoryStorage(), sync = SharedMatchCoordinator(store: store, service: client, storage: disk)
        store.sharedMatches = sync
        await sync.prepareHistoricalImport(Array(originals.prefix(5)), teamID: team.teamID, sourceTeamID: localTeam.id, links: [])
        await sync.resumeHistoricalImport()
        XCTAssertTrue(sync.state.historyImport?.finished == true, sync.message ?? "")
        XCTAssertEqual(sync.state.matches.count, 5)
        XCTAssertEqual(store.recentGames.count, 7)
        XCTAssertEqual(store.teams.first { $0.id == localTeam.id }, localTeam)
        for game in originals { XCTAssertEqual(store.games.first { $0.id == game.id }, game); XCTAssertTrue(sync.canEdit(game.id)) }
        let reopened = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = reopened
        await reopened.synchronize()
        XCTAssertEqual(store.recentGames.count, 7); XCTAssertEqual(reopened.state.matches.count, 5)
        await reopened.prepareHistoricalImport(Array(originals.prefix(5)), teamID: team.teamID, sourceTeamID: localTeam.id, links: [])
        XCTAssertTrue(reopened.state.historyImport?.rows.allSatisfy { $0.preview?.status == "duplicate" } == true)
        await reopened.resumeHistoricalImport()
        XCTAssertEqual(store.recentGames.count, 7); XCTAssertEqual(reopened.state.matches.count, 5)
    }

    func testV301PublishedCopyEditsDatesDeletionAndOtherMemberNeverTouchOriginal() async throws {
        let store = freshStore(), client = try matchClient()
        _ = try await client.enroll(deviceName: "3.0.1 QA")
        let team = try await client.createTeam(name: "独立副本", nickname: "管理员", operationID: UUID())
        let disk = V30MatchMemoryStorage(), sync = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = sync
        let original = try XCTUnwrap(store.activeStoredGame)
        await sync.publish(original, teamID: team.teamID)
        XCTAssertNil(sync.message)
        let shared = try XCTUnwrap(sync.publishedMatch(sourceID: original.id, teamID: team.teamID))
        XCTAssertNotEqual(shared.payload?.id, original.id); XCTAssertEqual(store.games.first { $0.id == original.id }, original)
        store.recordPitch(.ball); store.finishGame()
        let localFinished = try XCTUnwrap(store.activeStoredGame)
        XCTAssertTrue(store.updateCompletedGameDate(id: original.id, playedAt: Date().addingTimeInterval(-172800), timeZoneID: "Asia/Shanghai", expectedRevision: localFinished.revision ?? 0))
        let changedOriginal = try XCTUnwrap(store.activeStoredGame)
        await sync.synchronize()
        XCTAssertEqual(sync.match(id: shared.id)?.payload?.state.balls, original.state.balls)
        XCTAssertEqual(sync.match(id: shared.id)?.payload?.playedAt, original.playedAt)
        store.openGame(id: shared.id); store.recordPitch(.swingingStrike)
        await sync.waitForSync(); await sync.synchronize()
        XCTAssertNil(sync.message)
        XCTAssertEqual(store.games.first { $0.id == original.id }, changedOriginal)
        XCTAssertEqual(sync.match(id: shared.id)?.payload?.state.strikes, 1)
        store.finishGame(); await sync.waitForSync(); await sync.synchronize()
        let finished = try XCTUnwrap(store.activeStoredGame)
        XCTAssertTrue(store.updateCompletedGameDate(id: finished.id, playedAt: Date().addingTimeInterval(-86400), timeZoneID: "Asia/Shanghai", expectedRevision: finished.revision ?? 0))
        await sync.waitForSync(); await sync.synchronize()
        XCTAssertEqual(store.games.first { $0.id == original.id }, changedOriginal)
        let reader = try await matchPartner(client, team: team.teamID, role: .member), other = GameStore(persistenceURL: nil)
        let reading = SharedMatchCoordinator(store: other, service: reader, storage: V30MatchMemoryStorage()); other.sharedMatches = reading
        await reading.synchronize(); XCTAssertEqual(other.games.first { $0.id == shared.id }?.state.strikes, 1)
        XCTAssertTrue(store.deleteGame(id: original.id)); await sync.synchronize(); await reading.synchronize()
        XCTAssertNotNil(other.games.first { $0.id == shared.id })
        await sync.command(try XCTUnwrap(sync.match(id: shared.id)), resource: "deletion", fields: ["deleted": .bool(true)])
        XCTAssertNil(sync.message); XCTAssertNil(store.games.first { $0.id == shared.id })
        let reload = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = reload
        await reload.synchronize(); XCTAssertFalse(store.games.contains { $0.id == original.id })
        await reading.synchronize(); XCTAssertFalse(other.games.contains { $0.id == shared.id })
    }

    func testV301DeletingSharedCopyPreservesSourceAndRepeatPublishIsNoOp() async throws {
        let store = freshStore(), client = try matchClient(); _ = try await client.enroll(deviceName: "QA")
        let team = try await client.createTeam(name: "删除隔离", nickname: "管理员", operationID: UUID())
        let sync = SharedMatchCoordinator(store: store, service: client, storage: V30MatchMemoryStorage()); store.sharedMatches = sync
        try await startV21Live(store)
        let source = try XCTUnwrap(store.activeStoredGame)
        let sourceLiveCode = store.liveBroadcasts.binding(for: source.id)?.code
        await sync.publish(source, teamID: team.teamID); await sync.publish(source, teamID: team.teamID)
        XCTAssertEqual(sync.state.matches.count, 1)
        let copy = try XCTUnwrap(sync.state.matches.first)
        await sync.command(copy, resource: "deletion", fields: ["deleted": .bool(true)])
        XCTAssertEqual(store.games.first { $0.id == source.id }, source)
        XCTAssertTrue(sync.canEdit(source.id)); store.openGame(id: source.id); store.recordPitch(.ball)
        XCTAssertEqual(store.game.balls, 1)
        await sync.publish(try XCTUnwrap(store.activeStoredGame), teamID: team.teamID)
        XCTAssertEqual(sync.state.matches.count, 1); XCTAssertTrue(sync.state.matches.first?.deleted == true)
        XCTAssertEqual(store.liveBroadcasts.binding(for: source.id)?.code, sourceLiveCode)
        store.liveBroadcasts.close(source.id); await store.liveBroadcasts.waitForPendingSync()
    }

    func testV301LegacySharingMigrationRestoresOriginalAndKeepsSharedProgress() async throws {
        let store = freshStore(), client = try matchClient(); _ = try await client.enroll(deviceName: "QA")
        let team = try await client.createTeam(name: "旧版迁移", nickname: "管理员", operationID: UUID())
        let source = try XCTUnwrap(store.activeStoredGame)
        let disk = V30MatchMemoryStorage(), sync = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = sync
        await sync.publish(source, teamID: team.teamID)
        let copy = try XCTUnwrap(sync.state.matches.first)
        var legacy = try await client.match(teamID: team.teamID, gameID: copy.id)
        legacy.payload?.sharedGameID = copy.id; legacy.payload?.sharedTeamID = team.teamID
        var oldGame = try XCTUnwrap(legacy.payload); oldGame.state.balls = 2
        store.games = [oldGame]
        disk.state.matches = [legacy]; disk.state.originalLocalGames = [source]; disk.state.isolatedCopiesVersion = nil
        let migrated = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = migrated
        XCTAssertNil(migrated.message)
        XCTAssertEqual(store.games.first { $0.id == source.id }, source)
        XCTAssertEqual(store.games.first { $0.id == copy.id }?.state.balls, 2)
        XCTAssertTrue(migrated.canEdit(source.id)); XCTAssertFalse(migrated.isShared(source.id))
        let again = SharedMatchCoordinator(store: store, service: client, storage: disk)
        XCTAssertNil(again.message); XCTAssertEqual(store.games.count, 2)
        // A member's active downloaded game moves to its new ID without becoming a local original.
        store.games = [oldGame]; disk.state.matches = [legacy]; disk.state.originalLocalGames = []; disk.state.isolatedCopiesVersion = nil
        let memberMigration = SharedMatchCoordinator(store: store, service: client, storage: disk)
        XCTAssertNil(memberMigration.message); XCTAssertEqual(store.activeGameID, copy.id); XCTAssertEqual(store.games.count, 1)
        var collision = source; collision.id = copy.id
        store.games = [oldGame, collision]; disk.state.matches = [legacy]; disk.state.isolatedCopiesVersion = nil
        let blocked = SharedMatchCoordinator(store: store, service: client, storage: disk)
        XCTAssertNotNil(blocked.message); XCTAssertFalse(blocked.canEdit(source.id))
        XCTAssertEqual(store.games, [oldGame, collision])
    }

    func testV301RefreshGateAndFixedInvitationPersistAcrossReopen() async throws {
        let client = try matchClient(), disk = V30DirectoryMemoryStorage(), directory = TeamSharingStore(service: client, storage: disk)
        directory.setForeground(true)
        XCTAssertTrue(directory.needsRefresh("directory")); XCTAssertFalse(directory.needsRefresh("directory"))
        directory.setForeground(true); XCTAssertFalse(directory.needsRefresh("directory"))
        directory.setForeground(false); directory.setForeground(true); XCTAssertTrue(directory.needsRefresh("directory"))
        await directory.connect()
        let created = await directory.submit(.create(name: "固定邀请", nickname: "教练", localTeamID: nil, profile: nil)); XCTAssertTrue(created)
        let team = try XCTUnwrap(directory.teams.first)
        let fetched = await directory.submit(.fixedInvite(teamID: team.id, rotate: false)); XCTAssertTrue(fetched, directory.errorMessage ?? "")
        let first = try XCTUnwrap(directory.state.fixedInvitations?.first)
        let reopened = TeamSharingStore(service: client, storage: disk); await reopened.connect()
        let fetchedAgain = await reopened.submit(.fixedInvite(teamID: team.id, rotate: false)); XCTAssertTrue(fetchedAgain)
        XCTAssertEqual(reopened.state.fixedInvitations?.first?.token, first.token)
        let rotated = await reopened.submit(.fixedInvite(teamID: team.id, rotate: true)); XCTAssertTrue(rotated)
        XCTAssertNotEqual(reopened.state.fixedInvitations?.first?.token, first.token)
    }
}

extension GameStoreTests {
    func testV301ScorerSharesScheduledAndCompletedSnapshotsAndMemberCannot() async throws {
        let admin = try matchClient(); _ = try await admin.enroll(deviceName: "管理员")
        let team = try await admin.createTeam(name: "赛程共享", nickname: "教练", operationID: UUID())
        let scorer = try await matchPartner(admin, team: team.teamID), reader = try await matchPartner(admin, team: team.teamID, role: .member)
        let store = freshStore(), sync = SharedMatchCoordinator(store: store, service: scorer, storage: V30MatchMemoryStorage()); store.sharedMatches = sync
        store.finishGame(); let finished = try XCTUnwrap(store.activeStoredGame)
        await sync.publish(finished, teamID: team.teamID); XCTAssertNil(sync.message)
        let scheduledID = store.scheduleGame(ourTeam: try XCTUnwrap(store.currentTeam), opponent: store.opponentTeams[0], isHome: false, scheduledAt: Date().addingTimeInterval(86400))
        let scheduled = try XCTUnwrap(store.games.first { $0.id == scheduledID })
        await sync.publish(scheduled, teamID: team.teamID); XCTAssertNil(sync.message)
        XCTAssertEqual(sync.state.matches.count, 2); XCTAssertEqual(store.games.first { $0.id == scheduled.id }, scheduled)
        let other = GameStore(persistenceURL: nil), read = SharedMatchCoordinator(store: other, service: reader, storage: V30MatchMemoryStorage()); other.sharedMatches = read
        await read.synchronize(); XCTAssertEqual(Set(read.state.matches.compactMap { $0.payload?.status }), [.scheduled, .completed])
        XCTAssertEqual(store.recentGames.count, 1); XCTAssertEqual(store.scheduledGames.count, 1)
        let forbidden = freshStore(), deny = SharedMatchCoordinator(store: forbidden, service: reader, storage: V30MatchMemoryStorage()); forbidden.sharedMatches = deny
        await deny.publish(try XCTUnwrap(forbidden.activeStoredGame), teamID: team.teamID)
        XCTAssertNotNil(deny.message)
        let finalPage = try await reader.matchPage(teamID: team.teamID, cursor: 0)
        XCTAssertEqual(finalPage.games.count, 2)
    }

    func testV301DatabaseReopenAndMigrationFailurePreserveBothCopies() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("records.sqlite")
        let sourceStore = freshStore(), source = try XCTUnwrap(sourceStore.activeStoredGame)
        let store = GameStore(persistenceURL: url); try store.restoreBackup(LocalBackup(snapshot: sourceStore.makeRosterSnapshot()))
        let client = try matchClient(); _ = try await client.enroll(deviceName: "QA")
        let team = try await client.createTeam(name: "落盘隔离", nickname: "教练", operationID: UUID())
        let disk = V30MatchMemoryStorage(), sync = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = sync
        await sync.publish(source, teamID: team.teamID)
        let shared = try XCTUnwrap(sync.state.matches.first)
        let reopened = GameStore(persistenceURL: url)
        XCTAssertEqual(reopened.games.first { $0.id == source.id }, source)
        XCTAssertNotNil(reopened.games.first { $0.id == shared.id })
        disk.state.isolatedCopiesVersion = nil; disk.failSave = true
        let blocked = SharedMatchCoordinator(store: reopened, service: client, storage: disk)
        XCTAssertNotNil(blocked.message); XCTAssertTrue(blocked.canEdit(source.id))
        XCTAssertFalse(blocked.canEdit(shared.id))
        XCTAssertEqual(reopened.games.first { $0.id == source.id }, source)
        disk.failSave = false; await blocked.synchronize()
        XCTAssertNil(blocked.message); XCTAssertTrue(blocked.canEdit(source.id))
        XCTAssertEqual(GameStore(persistenceURL: url).games.count, 2)
    }
}

extension GameStoreTests {
    func testV301DirectoryRefreshCoalescesAndRequiresNewForegroundOrManualAction() async throws {
        let api = V30DirectoryService(), directory = TeamSharingStore(service: api, storage: V30DirectoryMemoryStorage())
        directory.setForeground(true)
        async let first: Void = directory.connectOnce()
        async let repeated: Void = directory.connectOnce()
        _ = await (first, repeated)
        await directory.connectOnce()
        let initial = await api.enrollCount; XCTAssertEqual(initial, 1)
        await directory.connect()
        let manual = await api.enrollCount; XCTAssertEqual(manual, 2)
        directory.setForeground(false); directory.setForeground(true)
        await directory.connectOnce(); await directory.connectOnce()
        let foreground = await api.enrollCount; XCTAssertEqual(foreground, 3)
    }
}

extension GameStoreTests {
    func testV301RestoringV30BackupSeparatesLegacyIDsWithoutOverwritingOriginalOrUploading() async throws {
        let store = freshStore(), client = try matchClient(); _ = try await client.enroll(deviceName: "QA")
        let team = try await client.createTeam(name: "旧备份隔离", nickname: "教练", operationID: UUID())
        let source = try XCTUnwrap(store.activeStoredGame), disk = V30MatchMemoryStorage()
        let sync = SharedMatchCoordinator(store: store, service: client, storage: disk); store.sharedMatches = sync
        await sync.publish(source, teamID: team.teamID)
        let shared = try XCTUnwrap(sync.state.matches.first)
        var legacy = source; legacy.sharedGameID = shared.id; legacy.sharedTeamID = team.teamID
        legacy.state.balls = 2; legacy.revision = (legacy.revision ?? 0) + 1
        let snapshot = store.makeRosterSnapshot()
        let backup = try LocalBackup(snapshot: RosterSnapshot(teams: snapshot.teams, opponentTeams: snapshot.opponentTeams,
            currentTeamID: snapshot.currentTeamID, seasons: snapshot.seasons, playerGameRecords: snapshot.playerGameRecords,
            games: [legacy], archivedTeams: snapshot.archivedTeams))
        try store.restoreBackup(backup)
        XCTAssertEqual(store.games.first { $0.id == source.id }, source)
        XCTAssertEqual(store.games.first { $0.id == shared.id }?.state.balls, 2)
        XCTAssertTrue(sync.state.requiresRecoveryReview)
        XCTAssertEqual(sync.state.conflicts.first?.localID, shared.id)
        XCTAssertTrue(sync.canEdit(source.id)); XCTAssertFalse(sync.canEdit(shared.id))
        await sync.publish(source, teamID: team.teamID)
        XCTAssertNotNil(sync.message); XCTAssertTrue(sync.state.pending.isEmpty)
        XCTAssertEqual(store.games.first { $0.id == source.id }, source)
        let reconciled = await sync.reconcileAfterRecovery(); XCTAssertTrue(reconciled)
        XCTAssertEqual(store.games.first { $0.id == source.id }, source)
        let remote = try await client.match(teamID: team.teamID, gameID: shared.id)
        XCTAssertEqual(remote.serverRevision, shared.serverRevision); XCTAssertEqual(remote.payload?.state.balls, 0)
        XCTAssertEqual(sync.state.conflicts.first?.game.state.balls, 2)
    }
}

// 3.1.0: observations are part of the same committed play, never independent scores.
extension GameStoreTests {
    func testV310ObservationCombinationsRoundTripWithoutGuessingOrChangingScores() throws {
        for type in BattedBallType.allCases {
            for precision in [BattedBallObservation.Precision.unknown, .region, .point] {
                var observation = BattedBallObservation(type: type)
                if precision == .region { observation.selectRegion(.foulLeft) }
                if precision == .point { observation.selectPoint(CGPoint(x: 0.23, y: 0.17)) }
                let store = freshStore(), control = freshStore()
                XCTAssertTrue(store.applyPlay(.single, observation: observation))
                XCTAssertTrue(control.applyPlay(.single))
                XCTAssertEqual(store.game.awayScore, control.game.awayScore)
                XCTAssertEqual(store.game.outs, control.game.outs)
                XCTAssertEqual(store.game.awayHits, 1)
                XCTAssertEqual(store.game.scoringEvents?.last?.battedBall, observation)
                XCTAssertEqual(store.game.historyJournal?.operations.last?.battedBall, observation)
                let decoded = try JSONDecoder().decode(GameState.self, from: JSONEncoder().encode(store.game))
                XCTAssertEqual(decoded, store.game)
            }
        }
        var region = BattedBallObservation(type: .line)
        region.selectPoint(CGPoint(x: 0.2, y: 0.3)); region.selectRegion(.center)
        XCTAssertNil(region.x); XCTAssertNil(region.y); XCTAssertEqual(region.precision, .region)
        region.selectRegion(nil); XCTAssertFalse(region.hasLocation)
    }

    func testV310InvalidObservationAndSlowPitchBuntAreAtomicAndLeaveNoUndo() throws {
        let store = freshStore(), before = store.game
        let invalid = BattedBallObservation(type: .ground, precision: .point, x: .nan, y: 0.4)
        XCTAssertFalse(store.applyPlay(.single, observation: invalid)); XCTAssertEqual(store.game, before)
        let slow = try slowStore(), slowBefore = slow.game
        XCTAssertFalse(slow.applyPlay(.single, observation: BattedBallObservation(type: .bunt)))
        XCTAssertEqual(slow.game, slowBefore)
    }

    func testV310ObservationSurvivesUndoRedoDiskRestartBackupAndHistoryCorrection() throws {
        let store = freshStore()
        var observation = BattedBallObservation(type: .hardGround); observation.selectRegion(.left)
        XCTAssertTrue(store.applyPlay(.single, observation: observation))
        let after = store.game
        store.undo(); XCTAssertTrue(store.game.scoringEvents?.allSatisfy { $0.battedBall == nil } ?? true)
        store.redo(); XCTAssertEqual(store.game, after)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("BM310-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("game.sqlite")
        let restored = GameStore(persistenceURL: path)
        try restored.restoreBackup(LocalBackup.read(store.exportBackup()))
        let reopened = GameStore(persistenceURL: path)
        XCTAssertFalse(reopened.requiresDataRecovery); XCTAssertEqual(reopened.game, after)
        var draft = try reopened.makeCorrectionDraft()
        let index = try XCTUnwrap(draft.journal.operations.firstIndex { $0.battedBall != nil })
        observation.type = .line; observation.selectRegion(.center)
        draft.journal.operations[index].battedBall = observation; draft.journal.operations[index].edited = true
        let preview = reopened.previewCorrection(draft)
        XCTAssertTrue(preview.canSave, preview.conflict ?? "")
        XCTAssertEqual(preview.game.scoringEvents?.first(where: { $0.category == .battedBall })?.battedBall, observation)
        XCTAssertTrue(reopened.saveCorrection(draft), reopened.actionErrorMessage ?? "")
        XCTAssertEqual(reopened.game.awayHits, 1)
        XCTAssertEqual(reopened.game.batting, after.batting)
        XCTAssertTrue(reopened.game.playLog.contains { $0.text.contains("平飞") })
    }

    func testV310OldPayloadKeepsTraditionalMethodAndUnknownContact() throws {
        let store = freshStore(); XCTAssertTrue(store.applyPlay(.single))
        let decoded = try JSONDecoder().decode(GameState.self, from: JSONEncoder().encode(store.game))
        XCTAssertEqual(decoded.rulesSnapshot?.effectiveRecordingMethod ?? .traditional, .traditional)
        XCTAssertNil(decoded.scoringEvents?.last?.battedBall)
        let player = store.game.awayTeam.players.first!
        let data = store.batterPerformance(playerID: player.id, annual: false)
        XCTAssertEqual(data.line.hits, 1); XCTAssertEqual(data.missingLocations, 1); XCTAssertTrue(data.contacts.isEmpty)
    }

    func testV310CurrentAndAnnualPerformanceExcludeIncompleteAndKeepStableIdentity() {
        let store = freshStore(), player = store.currentBatter
        store.recordPitch(.ball)
        XCTAssertEqual(store.batterPerformance(playerID: player.id, annual: false).line.plateAppearances, 0)
        var observation = BattedBallObservation(type: .fly); observation.selectRegion(.right)
        XCTAssertTrue(store.applyPlay(.homeRun, observation: observation))
        let gameData = store.batterPerformance(playerID: player.id, annual: false)
        let annual = store.batterPerformance(playerID: player.id, annual: true)
        XCTAssertEqual(gameData.line.hits, 1); XCTAssertEqual(gameData.contacts.count, 1)
        XCTAssertEqual(annual.line.hits, 1); XCTAssertEqual(annual.contacts.count, 1)
        let unknown = Player(name: player.name, number: player.number, primaryPosition: .leftField)
        XCTAssertEqual(store.batterPerformance(playerID: unknown.id, annual: true).line.plateAppearances, 0)
        store.undo()
        XCTAssertEqual(store.batterPerformance(playerID: player.id, annual: true).contacts.count, 0)
        store.redo()
        XCTAssertEqual(store.batterPerformance(playerID: player.id, annual: true).contacts.count, 1)
    }

    func testV310DefensiveRouteSnapshotsNamesAndRemainsHistoricalAfterSubstitution() throws {
        let store = freshStore()
        let fielders = store.activeFielders
        let route = DefensivePlay.quickPlays[6]
        let expected = "防守路线 6-4-3，" + [FieldPosition.shortstop, .secondBase, .firstBase].map { position in
            position.fullName + fielders.first { $0.primaryPosition == position }!.name
        }.joined(separator: "-")
        XCTAssertTrue(store.applyPlay(.single))
        XCTAssertTrue(store.applyPlay(.doublePlay, defensivePlay: route))
        let event = try XCTUnwrap(store.game.scoringEvents?.first { $0.resolvedOutcome == .doublePlay })
        XCTAssertEqual(event.defensiveRoute, expected); XCTAssertTrue(event.title.contains(expected))
        let stored = try XCTUnwrap(store.activeStoredGame)
        let projection = LiveSnapshot(stored)
        XCTAssertTrue(projection.entries.flatMap(\.details).contains { $0.text.contains(expected) })
        let oldName = store.game.homeTeam.players.first { $0.primaryPosition == .shortstop }!.name
        let index = store.game.homeTeam.players.firstIndex { $0.primaryPosition == .shortstop }!
        store.game.homeTeam.players[index].chineseName = "后来的新游击手"
        let changedProjection = LiveSnapshot({ var value = stored; value.state = store.game; return value }())
        XCTAssertTrue(changedProjection.entries.flatMap(\.details).contains { $0.text.contains("游击手" + oldName) })
    }

    func testV310PracticeMethodsResetAndDoNotCreateFormalGames() {
        let store = GameStore(persistenceURL: nil), original = store.games
        for method in RecordingMethod.allCases {
            store.resetPracticeInning(method: method)
            XCTAssertEqual(store.game.rulesSnapshot?.effectiveRecordingMethod, method)
            XCTAssertTrue(store.applyPlay(.single, observation: method == .situation ? BattedBallObservation(type: .line) : nil))
            XCTAssertEqual(store.games, original); XCTAssertNil(store.activeGameID)
        }
    }

    func testV310IllegalPitchKeepsObservationInTheSameReplayOperation() throws {
        let store = try slowStore()
        var observation = BattedBallObservation(type: .ground); observation.selectRegion(.infieldLeft)
        XCTAssertTrue(store.recordIllegalPitch(nil, wasThrown: true, outcome: .single, observation: observation))
        let event = try XCTUnwrap(store.game.scoringEvents?.first { $0.battedBall != nil })
        XCTAssertNotNil(event.illegalPitch)
        let preview = store.previewCorrection(try store.makeCorrectionDraft())
        XCTAssertTrue(preview.canSave, preview.conflict ?? "")
        XCTAssertEqual(preview.game.scoringEvents?.first { $0.battedBall != nil }?.battedBall, observation)
        XCTAssertTrue(preview.game.historyScoreEquivalent(to: store.game))
    }
}

extension GameStoreTests {
    func testV310AnnualSharedCopiesDeduplicateAndRevokedTeamsDisappear() async throws {
        let a = freshStore(), player = a.currentBatter
        var observation = BattedBallObservation(type: .line); observation.selectRegion(.center)
        XCTAssertTrue(a.applyPlay(.homeRun, observation: observation)); a.finishGame()
        let local = try XCTUnwrap(a.activeStoredGame), admin = try matchClient()
        _ = try await admin.enroll(deviceName: "击球记录员")
        let team = try await admin.createTeam(name: "击球图权限队", nickname: "教练", operationID: UUID(), profile: SharedRosterSeed(team: a.currentTeam!))
        let reader = try await matchPartner(admin, team: team.teamID, role: .member)
        let da = TeamSharingStore(service: admin, storage: V30DirectoryMemoryStorage()); a.teamSharing = da; await da.connect()
        let ca = SharedMatchCoordinator(store: a, service: admin, storage: V30MatchMemoryStorage()); a.sharedMatches = ca
        await ca.publish(local, teamID: team.teamID); XCTAssertNil(ca.message)
        let copyID = try XCTUnwrap(ca.state.matches.first?.payload?.id)
        XCTAssertEqual(a.batterPerformance(playerID: player.id, annual: true).contacts.count, 1)
        XCTAssertEqual(a.batterPerformance(playerID: player.id, annual: true).line.plateAppearances, 1)
        let b = GameStore(persistenceURL: nil)
        let db = TeamSharingStore(service: reader, storage: V30DirectoryMemoryStorage()); b.teamSharing = db; await db.connect()
        let cb = SharedMatchCoordinator(store: b, service: reader, storage: V30MatchMemoryStorage()); b.sharedMatches = cb
        await cb.synchronize(teamID: team.teamID); b.openGame(id: copyID)
        XCTAssertFalse(b.canEditCurrentMatch)
        let data = b.batterPerformance(playerID: player.id, annual: true)
        XCTAssertEqual(data.contacts.count, 1); XCTAssertEqual(data.line.hits, 1)
        let before = b.game; XCTAssertFalse(b.applyPlay(.single, observation: observation)); XCTAssertEqual(b.game, before)
        let left = await db.submit(.leave(teamID: team.teamID)); XCTAssertTrue(left)
        XCTAssertEqual(b.batterPerformance(playerID: player.id, annual: true).contacts.count, 0)
    }

    func testV310ModeYearAndOpponentIdentityScopeAndObservationOnlyCorrectionStayConsistent() throws {
        let store = freshStore(), player = store.currentBatter
        var observation = BattedBallObservation(type: .ground); observation.selectPoint(CGPoint(x: 0.4, y: 0.5))
        XCTAssertTrue(store.applyPlay(.single, observation: observation)); store.finishGame()
        let stored = try XCTUnwrap(store.activeStoredGame)
        var otherMode = stored; otherMode.id = UUID(); otherMode.rules.mode = .coachPitch
        var otherYear = stored; otherYear.id = UUID(); otherYear.playedAt = Date(timeIntervalSince1970: 1600000000)
        store.games += [otherMode, otherYear]
        XCTAssertEqual(store.batterPerformance(playerID: player.id, annual: true).contacts.count, 1)
        var draft = try store.makeCorrectionDraft()
        let index = try XCTUnwrap(draft.journal.operations.firstIndex { $0.battedBall != nil })
        draft.journal.operations[index].battedBall?.selectRegion(nil)
        XCTAssertTrue(store.saveCorrection(draft), store.actionErrorMessage ?? "")
        let corrected = store.batterPerformance(playerID: player.id, annual: false)
        XCTAssertEqual(corrected.line.hits, 1); XCTAssertEqual(corrected.contacts.count, 0); XCTAssertEqual(corrected.missingLocations, 1)
        let originalOpponent = store.game.homeTeam.players[0]
        let stranger = Player(name: player.name, number: player.number, primaryPosition: .pitcher)
        store.game.homeTeam.players[0] = stranger
        XCTAssertEqual(store.batterPerformance(playerID: originalOpponent.id, annual: false).line.hits, 0)
        XCTAssertEqual(store.batterPerformance(playerID: stranger.id, annual: false).line.hits, 0)
    }

    func testV310LegacyLiveRoutesUseHistoricalParticipantsNotCurrentAssignments() throws {
        let store = freshStore(), original = store.game.homeTeam.players.first { $0.primaryPosition == .shortstop }!
        XCTAssertTrue(store.applyPlay(.groundOut, defensivePlay: DefensivePlay.quickPlays[0]))
        let index = try XCTUnwrap(store.game.scoringEvents?.firstIndex { $0.category == .battedBall })
        store.game.scoringEvents?[index].defensiveRoute = nil
        store.game.scoringEvents?[index].title = "旧记录：滚地出局，游击传一垒（6-3）"
        // A later assignment change must not select the new shortstop for an older event.
        let oldIndex = store.game.homeTeam.players.firstIndex { $0.id == original.id }!
        store.game.homeTeam.players[oldIndex].primaryPosition = .leftField
        let newIndex = store.game.homeTeam.players.firstIndex { $0.primaryPosition == .secondBase }!
        store.game.homeTeam.players[newIndex].primaryPosition = .shortstop
        let event = store.game.scoringEvents![index]
        let text = store.game.liveText(for: event)
        XCTAssertTrue(text.contains("游击手" + original.name))
        XCTAssertFalse(text.contains("游击手" + store.game.homeTeam.players[newIndex].name))
    }
}

extension GameStoreTests {
    private func v4Observation(_ type: BattedBallType = .line, _ route: [FieldPosition] = [.centerField, .secondBase], status: BattedBallFielding.Status = .recorded, error: FieldPosition? = nil) -> BattedBallObservation {
        var value = BattedBallObservation(type: type)
        value.selectPoint(CGPoint(x: 0.3, y: 0.25))
        value.fielding = BattedBallFielding(status: status, route: route, errorPosition: error)
        return value
    }

    func testV310V4HitsRequireExplicitFieldingAndNeverInferItFromLocation() throws {
        let store = freshStore(), before = store.game
        var observation = v4Observation()
        observation.fielding?.status = nil; observation.fielding?.route = []
        XCTAssertFalse(store.applyPlay(.single, observation: observation)); XCTAssertEqual(store.game, before)
        observation.fielding?.status = .recorded
        XCTAssertFalse(store.applyPlay(.single, observation: observation)); XCTAssertEqual(store.game, before)
        observation = v4Observation(.line, [.centerField, .secondBase, .centerField])
        var fielding = observation.fielding!; fielding.touch(.centerField)
        XCTAssertEqual(fielding.route, [.centerField, .secondBase, .centerField], "Repeated same-player taps do not add throws")
        fielding.touch(.catcher); XCTAssertEqual(fielding.route.count, 4)
        observation.fielding = fielding
        let moves = store.suggestedRunnerDecisions(for: .single)
        let defense = situationDefensivePlay(outcome: .single, observation: observation, decisions: moves)
        XCTAssertEqual(defense?.putoutPositions, []); XCTAssertEqual(defense?.assistPositions, [])
        XCTAssertTrue(store.applyPlay(.single, defensivePlay: defense, decisions: moves, observation: observation))
        XCTAssertEqual(store.game.scoringEvents?.last?.battedBall?.fielding?.route, fielding.route)
        XCTAssertTrue(store.game.scoringEvents!.last!.title.contains("防守路线 8-4-8-2"))
        XCTAssertEqual(store.game.pitching.values.reduce(0) { $0 + $1.pitches }, 1)
    }

    func testV310V4CompoundHitAndTagOutPreservesHitRunOnePitchAndSeparateRoute() throws {
        let store = freshStore(); XCTAssertTrue(store.applyPlay(.double))
        let runner = try XCTUnwrap(store.game.baseRunners[.second]), batter = store.currentBatter, pitcher = store.currentPitcher
        var observation = v4Observation()
        let moves = [RunnerDecision(player: batter, origin: .batter, destination: .out), RunnerDecision(player: runner, origin: .base(.second), destination: .score)]
        observation.runnerDetails = [BattedBallRunnerDetail(playerID: batter.id, cause: .tag, fielding: BattedBallFielding(status: .recorded, route: [.centerField, .secondBase])), BattedBallRunnerDetail(playerID: runner.id)]
        let defense = situationDefensivePlay(outcome: .single, observation: observation, decisions: moves)
        XCTAssertTrue(store.applyPlay(.single, defensivePlay: defense, decisions: moves, observation: observation))
        XCTAssertEqual(store.game.batting[batter.id]?.hits, 1); XCTAssertEqual(store.game.batting[batter.id]?.runsBattedIn, 1)
        XCTAssertEqual(store.game.awayScore, 1); XCTAssertEqual(store.game.outs, 1); XCTAssertEqual(store.game.pitching[pitcher.id]?.pitches, 2)
        let second = store.game.homeTeam.players.first { $0.primaryPosition == .secondBase }!
        XCTAssertEqual(store.game.fielding[second.id]?.putouts, 1)
        let after = store.game; store.undo(); XCTAssertEqual(store.game.outs, 0); store.redo(); XCTAssertEqual(store.game, after)
        XCTAssertTrue(store.previewCorrection(try store.makeCorrectionDraft()).game.historyScoreEquivalent(to: after))
    }

    func testV310V4ThirdOutTimingForCatchAndAdditionalRunnerOutCanCountOrCancelRun() throws {
        for runCounts in [false, true] {
            let store = freshStore()
            XCTAssertTrue(store.applyPlay(.single)); XCTAssertTrue(store.applyPlay(.single))
            store.game.baseRunners[.third] = store.game.baseRunners.removeValue(forKey: .second)
            XCTAssertTrue(store.applyPlay(.groundOut))
            let first = try XCTUnwrap(store.game.baseRunners[.first]), third = try XCTUnwrap(store.game.baseRunners[.third]), batter = store.currentBatter
            var observation = v4Observation(.fly, [.centerField, .firstBase])
            let moves = [RunnerDecision(player: batter, origin: .batter, destination: .out), RunnerDecision(player: first, origin: .base(.first), destination: .out), RunnerDecision(player: third, origin: .base(.third), destination: .score)]
            observation.runnerDetails = [BattedBallRunnerDetail(playerID: batter.id), BattedBallRunnerDetail(playerID: first.id, cause: .tag, fielding: BattedBallFielding(status: .recorded, route: [.centerField, .firstBase])), BattedBallRunnerDetail(playerID: third.id)]
            XCTAssertTrue(store.situationNeedsTimingDecision(.flyOut, decisions: moves))
            let before = store.game
            XCTAssertFalse(store.applyPlay(.flyOut, decisions: moves, observation: observation)); XCTAssertEqual(store.game, before)
            let defense = situationDefensivePlay(outcome: .flyOut, observation: observation, decisions: moves)
            XCTAssertTrue(store.applyPlay(.flyOut, defensivePlay: defense, decisions: moves, timingRunCounts: runCounts, observation: observation))
            XCTAssertEqual(store.game.awayScore, runCounts ? 1 : 0)
            XCTAssertEqual(store.game.pitching[before.currentPitcher.id]?.outsRecorded, 3)
        }
    }

    func testV310V4IndependentErrorHomeRunNoFieldingPendingAndAmendmentAreExplicit() throws {
        let store = freshStore(), batter = store.currentBatter
        var error = v4Observation(.ground, [.shortstop, .firstBase])
        XCTAssertFalse(store.applyPlay(.error, observation: error))
        error.fielding?.errorPosition = .firstBase
        let moves = store.suggestedRunnerDecisions(for: .error)
        XCTAssertTrue(store.applyPlay(.error, defensivePlay: situationDefensivePlay(outcome: .error, observation: error, decisions: moves), decisions: moves, observation: error))
        let first = store.game.homeTeam.players.first { $0.primaryPosition == .firstBase }!, short = store.game.homeTeam.players.first { $0.primaryPosition == .shortstop }!
        XCTAssertEqual(store.game.fielding[first.id]?.errors, 1); XCTAssertNil(store.game.fielding[short.id]?.errors)
        var draft = try store.makeCorrectionDraft()
        let index = try XCTUnwrap(draft.journal.operations.firstIndex { $0.battedBall != nil })
        draft.journal.operations[index].battedBall?.fielding?.route = [.thirdBase, .firstBase]
        draft.journal.operations[index].edited = true
        XCTAssertTrue(store.saveCorrection(draft), store.actionErrorMessage ?? "")
        XCTAssertEqual(store.game.batting[batter.id]?.plateAppearances, 1)
        XCTAssertEqual(store.game.pitching.values.reduce(0) { $0 + $1.pitches }, 1)
        let hrStore = freshStore(), hr = v4Observation(.fly, [], status: .noFielding)
        XCTAssertFalse(hrStore.applyPlay(.single, observation: hr))
        XCTAssertTrue(hrStore.applyPlay(.homeRun, observation: hr)); XCTAssertEqual(hrStore.game.awayScore, 1)
        let pendingStore = freshStore(), pending = v4Observation(.line, [.leftField], status: .pending)
        XCTAssertTrue(pendingStore.applyPlay(.single, observation: pending))
        XCTAssertTrue(pendingStore.game.scoringEvents!.last!.needsReview)
        XCTAssertTrue(pendingStore.game.scoringEvents!.last!.title.contains("后续待补"))
    }
}

extension GameStoreTests {
    func testV310V4PracticeWholePlayAmendmentIsMemoryOnlyAndRejectsStaleDraft() throws {
        let store = GameStore(persistenceURL: nil); store.resetPracticeInning(method: .situation)
        let batter = store.currentBatter, observation = v4Observation(), moves = store.suggestedRunnerDecisions(for: .single)
        XCTAssertTrue(store.applyPlay(.single, defensivePlay: situationDefensivePlay(outcome: .single, observation: observation, decisions: moves), decisions: moves, observation: observation))
        var draft = try store.makeCorrectionDraft()
        let index = try XCTUnwrap(draft.journal.operations.firstIndex { $0.battedBall != nil })
        draft.journal.operations[index].battedBall?.fielding?.touch(.catcher)
        draft.journal.operations[index].edited = true; draft.reason = "练习补充这球"
        XCTAssertTrue(store.saveCorrection(draft), store.actionErrorMessage ?? "")
        XCTAssertEqual(store.game.batting[batter.id]?.plateAppearances, 1)
        XCTAssertEqual(store.game.pitching.values.reduce(0) { $0 + $1.pitches }, 1)
        XCTAssertEqual(store.game.scoringEvents?.first { $0.category == .battedBall }?.battedBall?.fielding?.route, [.centerField, .secondBase, .catcher])
        XCTAssertTrue(store.games.isEmpty); XCTAssertNil(store.activeGameID)
        let stale = try store.makeCorrectionDraft(); store.recordPitch(.ball)
        XCTAssertFalse(store.saveCorrection(stale)); XCTAssertEqual(store.game.balls, 1)
    }
}

extension GameStoreTests {
    func testV310V4RawFieldingAndRunnerCausesSurviveSharingHandoffAndPortableArchive() async throws {
        let a = freshStore(); XCTAssertTrue(a.applyPlay(.double))
        let batter = a.currentBatter, runner = try XCTUnwrap(a.game.baseRunners[.second])
        var observation = v4Observation()
        let moves = [RunnerDecision(player: batter, origin: .batter, destination: .out), RunnerDecision(player: runner, origin: .base(.second), destination: .score)]
        observation.runnerDetails = [BattedBallRunnerDetail(playerID: batter.id, cause: .tag, fielding: BattedBallFielding(status: .recorded, route: [.centerField, .secondBase])), BattedBallRunnerDetail(playerID: runner.id)]
        XCTAssertTrue(a.applyPlay(.single, decisions: moves, observation: observation))
        let original = try XCTUnwrap(a.activeStoredGame), expected = try XCTUnwrap(a.game.scoringEvents?.last?.battedBall)
        let client = try matchClient(); _ = try await client.enroll(deviceName: "V4 记录设备")
        let team = try await client.createTeam(name: "V4 全球档案测试", nickname: "教练", operationID: UUID())
        let partner = try await matchPartner(client, team: team.teamID), b = GameStore(persistenceURL: nil)
        let ca = SharedMatchCoordinator(store: a, service: client, storage: V30MatchMemoryStorage()), cb = SharedMatchCoordinator(store: b, service: partner, storage: V30MatchMemoryStorage())
        a.sharedMatches = ca; b.sharedMatches = cb
        await ca.publish(original, teamID: team.teamID); XCTAssertNil(ca.message)
        let match = try XCTUnwrap(ca.state.matches.first), copyID = try XCTUnwrap(match.payload?.id)
        a.openGame(id: copyID)
        await cb.synchronize(teamID: team.teamID); b.openGame(id: copyID)
        XCTAssertEqual(b.game.scoringEvents?.last?.battedBall, expected)
        let device = try await partner.enroll(deviceName: "V4 接手设备")
        await ca.command(match, resource: "handoff/propose", fields: ["targetDeviceID": .uuid(device)])
        await cb.synchronize(teamID: team.teamID)
        await cb.command(try XCTUnwrap(cb.match(id: match.id)), resource: "handoff/accept")
        XCTAssertNil(cb.message); XCTAssertTrue(cb.canEdit(copyID))
        b.recordPitch(.ball); await cb.waitForSync(); await ca.synchronize(teamID: team.teamID)
        XCTAssertEqual(a.games.first { $0.id == copyID }?.state.scoringEvents?.first { $0.resolvedOutcome == .single }?.battedBall, expected)
        let archive = try await client.exportTeamArchive(teamID: team.teamID)
        defer { try? FileManager.default.removeItem(at: archive) }
        let verified = try PortableTeamArchive.validate(archive)
        XCTAssertEqual(verified.manifest.version, 2)
        XCTAssertEqual(verified.records.first?.payload?.state.scoringEvents?.first { $0.resolvedOutcome == .single }?.battedBall, expected)
    }
}

extension GameStoreTests {
    func testV310V4BallTypeNeverChangesOfficialStatisticsAndPendingErrorCountsTeamError() throws {
        var reference: GameState?
        for type in BattedBallType.allCases {
            let store = freshStore(); XCTAssertTrue(store.applyPlay(.single))
            let observation = v4Observation(type, [.shortstop, .secondBase, .firstBase])
            XCTAssertTrue(store.applyPlay(.doublePlay, observation: observation))
            if let reference {
                XCTAssertEqual(store.game.awayHits, reference.awayHits); XCTAssertEqual(store.game.outs, reference.outs)
                XCTAssertEqual(store.game.fielding.values.reduce(0) { $0 + $1.putouts }, reference.fielding.values.reduce(0) { $0 + $1.putouts })
                XCTAssertEqual(store.game.fielding.values.reduce(0) { $0 + $1.assists }, reference.fielding.values.reduce(0) { $0 + $1.assists })
            } else { reference = store.game }
        }
        let store = freshStore(), observation = v4Observation(.ground, [], status: .pending)
        XCTAssertTrue(store.applyPlay(.error, observation: observation)); XCTAssertEqual(store.game.homeErrors, 1)
        XCTAssertTrue(store.game.scoringEvents!.last!.needsReview)
    }
}

// Android migration reference generation. Only synthetic teams; no server access.
extension GameStoreTests {
    func testAndroidParitySeedExport() throws {
        for mode in [GameMode.standard, .coachPitch, .slowPitch] {
            let store = GameStore(persistenceURL: nil)
            let count = mode == .slowPitch ? 10 : 9
            func team(_ name: String, prefix: String) -> Team {
                Team(id: UUID(uuidString: prefix + "0000-0000-0000-000000000000")!,
                     name: name, shortName: name, city: "合成测试",
                     players: (1...count).map { n in
                    Player(id: UUID(uuidString: prefix + String(format: "0000-0000-0000-%012d", n))!,
                           chineseName: "球员\(n)", englishName: "", numbers: [n],
                           primaryPosition: FieldPosition.allCases[n - 1])
                })
            }
            let away = team("客队", prefix: "A1000000-"), home = team("主队", prefix: "B1000000-")
            store.teams = [away]; store.currentTeam = away; store.opponentTeams = [home]
            var rules = GameRules(scheduledInnings: 6, fieldersCount: count, timeLimitMinutes: mode == .slowPitch ? 90 : nil)
            rules.mode = mode; rules.coachPitchLimit = 6
            if mode == .slowPitch {
                rules.competitionFormat = .timed; rules.initialBalls = 1; rules.initialStrikes = 1
                rules.twoStrikeFoulPolicy = .oneExtraFoul; rules.rulesVersion = 2
            }
            func lineup(_ t: Team) -> [LineupAssignment] {
                t.players.enumerated().map { LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1,
                                                               position: FieldPosition.allCases[$0.offset]) }
            }
            XCTAssertNotNil(store.startNewGame(opponent: home, isHome: false, rules: rules,
                                               lineup: lineup(away), opponentLineup: lineup(home)))
            let actions: [PitchAction]
            switch mode {
            case .standard: actions = [.ball, .calledStrike, .swingingStrike, .foul, .foul, .ball, .ball, .ball,
                                       .calledStrike, .calledStrike, .calledStrike]
            case .coachPitch: actions = Array(repeating: .ball, count: 6)
            case .slowPitch: actions = [.foul, .foul, .ball, .foul, .ball, .ball, .ball]
            }
            func canonical() -> [String: Any] {
                let g = store.game
                let order = g.isTop ? g.awayBattingOrderIDs : g.homeBattingOrderIDs
                return ["balls": g.balls, "strikes": g.strikes, "outs": g.outs, "inning": g.inning,
                        "isTop": g.isTop, "batterIndex": g.isTop ? g.awayBatterIndex : g.homeBatterIndex,
                        "appearancePitches": g.plateAppearancePitchCount ?? 0,
                        "extraFoulUsed": g.extraFoulUsed ?? false,
                        "bases": (1...3).map { base in
                            g.baseRunners[Base(rawValue: base)!].flatMap { order.firstIndex(of: $0.id) } ?? -1
                        }]
            }
            let initial = canonical()
            let payload = try XCTUnwrap(store.activeStoredGame)
            var steps: [[String: Any]] = []
            for action in actions {
                store.recordPitch(action)
                XCTAssertNil(store.actionErrorMessage)
                let key: String
                switch action {
                case .ball: key = "ball"
                case .calledStrike: key = "calledStrike"
                case .swingingStrike: key = "swingingStrike"
                case .foul: key = "foul"
                }
                steps.append(["action": key, "expected": canonical()])
            }
            let fixture: [String: Any] = ["schema": 1, "source": "iOS GameStore @ 40df3d2",
                "mode": mode.rawValue, "rules": try JSONSerialization.jsonObject(with: JSONEncoder().encode(rules)),
                "initial": initial, "steps": steps,
                "iosInitialPayload": try JSONSerialization.jsonObject(with: JSONEncoder().encode(payload))]
            let data = try JSONSerialization.data(withJSONObject: fixture, options: [.prettyPrinted, .sortedKeys])
            let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
            attachment.name = "android-parity-\(mode.rawValue).json"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
    }
}
