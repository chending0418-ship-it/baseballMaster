import XCTest
import UIKit

final class BaseballMasterUITests: XCTestCase {
    func testV311ConfigurableLaunchKeepsBrandIconsAndSkipsToHome() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--launch-screen-custom-preview"]
        app.launch()
        XCTAssertTrue(app.staticTexts["launch-message"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["launch-message"].label.contains("固定品牌 图文保持清晰"))
        XCTAssertTrue(app.images["launch-fixed-bm"].exists)
        XCTAssertTrue(app.images["launch-fixed-app-icon"].exists)
        let iconFrame = app.images["launch-fixed-app-icon"].frame
        let capture = XCTAttachment(screenshot: app.screenshot()); capture.name = "3.1.1 可配置开屏"; capture.lifetime = .keepAlways; add(capture)
        app.buttons["skip-launch-screen"].tap()
        XCTAssertTrue(app.navigationBars["比赛"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["start-new-game"].exists)
        app.terminate(); app.launchArguments = ["--launch-screen-preview"]; app.launch()
        XCTAssertTrue(app.staticTexts["launch-message"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.images["launch-fixed-app-icon"].frame.minY, iconFrame.minY, accuracy: 1, "Caption length must not move the fixed App icon")
        app.buttons["skip-launch-screen"].tap()
    }

    func testV311BundledLaunchRetainsProductGreenAndDefaultMessage() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--launch-screen-preview"]
        app.launch()
        XCTAssertTrue(app.staticTexts["launch-message"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["launch-message"].label, "每一球 都有迹可循")
        XCTAssertTrue(app.images["launch-fixed-bm"].exists)
        XCTAssertTrue(app.images["launch-fixed-app-icon"].exists)
        let capture = XCTAttachment(screenshot: app.screenshot()); capture.name = "3.1.1 默认缓存兜底开屏"; capture.lifetime = .keepAlways; add(capture)
        app.buttons["skip-launch-screen"].tap()
        XCTAssertTrue(app.navigationBars["比赛"].waitForExistence(timeout: 5))
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testWholeGameAndSingleAppearancePDFCanBePreviewed() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--boxscore-preview"]
        app.launch()
        app.buttons["记录"].tap()
        let single = app.buttons["export-appearance-away-3"]
        revealStatisticsControl(single, in: app)
        single.tap()
        XCTAssertTrue(app.navigationBars["打席速报"].waitForExistence(timeout: 5))
        app.buttons["close-report-pdf"].tap()
        let full = app.buttons["share-play-by-play"]
        revealStatisticsControl(full, in: app)
        full.tap()
        XCTAssertTrue(app.navigationBars["逐打席速报"].waitForExistence(timeout: 5))
        let capture = XCTAttachment(screenshot: app.screenshot()); capture.name = "逐打席速报预览"; capture.lifetime = .keepAlways; add(capture)
        app.buttons["share-report-pdf"].tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 5), app.debugDescription)
    }

    func testTextOnlyWholeGameAndSingleAppearancePDFCanBePreviewedAndShared() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--boxscore-preview"]
        app.launch()
        app.buttons["记录"].tap()
        let single = app.buttons["export-appearance-text-away-3"]
        revealStatisticsControl(single, in: app)
        single.tap()
        XCTAssertTrue(app.navigationBars["单打席文字简版"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["report-pdf-page-count"].label.contains("A4 竖版"))
        app.buttons["close-report-pdf"].tap()
        let full = app.buttons["share-play-by-play-text"]
        revealStatisticsControl(full, in: app)
        full.tap()
        XCTAssertTrue(app.navigationBars["逐打席文字简版"].waitForExistence(timeout: 5))
        let capture = XCTAttachment(screenshot: app.screenshot())
        capture.name = "逐打席文字简版预览"; capture.lifetime = .keepAlways; add(capture)
        app.buttons["share-report-pdf"].tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 5))
    }

    func testBackupExportAndImportPickerAreAvailable() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--backup-preview"]
        app.launch()
        XCTAssertTrue(app.buttons["export-local-backup"].waitForExistence(timeout: 5))
        app.buttons["export-local-backup"].tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 5), app.debugDescription)
        app.terminate(); app.launch()
        app.buttons["import-local-backup"].tap()
        let picker = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.navigationBars["Browse"].exists || app.buttons["Browse"].exists || app.buttons["浏览"].exists
                || app.buttons["Cancel"].exists || app.buttons["取消"].exists
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [picker], timeout: 20), .completed, app.debugDescription)
        let capture = XCTAttachment(screenshot: app.screenshot()); capture.name = "本机备份系统文件选择器"; capture.lifetime = .keepAlways; add(capture)
    }

    func testGamePosterCanEditVenueAndShareImage() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--poster-preview"]
        app.launch()
        XCTAssertTrue(app.images["game-poster-preview"].waitForExistence(timeout: 5))
        let venue = app.textFields["poster-venue"]
        revealStatisticsControl(venue, in: app)
        venue.tap(); venue.typeText("Baseball Park\n")
        let share = app.buttons["share-game-poster"]
        revealStatisticsControl(share, in: app)
        share.tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 5), app.debugDescription)
    }

    func testTeamSeasonPDFCanBePreviewedAndShared() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--statistics-preview"]
        app.launch()
        let export = app.buttons["export-team-season-pdf"]
        XCTAssertTrue(export.waitForExistence(timeout: 5))
        export.tap()
        XCTAssertTrue(app.navigationBars["球队年度报告"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["report-pdf-page-count"].label.contains("A4 横版"))
        app.buttons["share-report-pdf"].tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 5), app.debugDescription)
    }

    func testPlayerSelectedGamePDFCanBePreviewedAndShared() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--statistics-preview"]
        app.launch()
        let player = app.buttons["stats-player-陈昊"]
        revealStatisticsControl(player, in: app)
        player.tap()
        XCTAssertTrue(app.buttons["export-player-pdf"].waitForExistence(timeout: 3))
        app.buttons["open-game-filter"].tap()
        app.buttons["清空"].tap()
        app.buttons["应用"].tap()
        app.buttons["export-player-pdf"].tap()
        XCTAssertTrue(app.navigationBars["球员个人报告"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["report-pdf-page-count"].exists)
        app.buttons["share-report-pdf"].tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 5), app.debugDescription)
    }

    func testProfessionalBoxScorePDFPreviewAndShare() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--boxscore-preview"]
        app.launch()
        let export = app.buttons["share-box-score"]
        revealStatisticsControl(export, in: app)
        XCTAssertTrue(export.isHittable)
        captureV111("2.2-导出BoxScore入口", app: app)
        export.tap()
        XCTAssertTrue(app.navigationBars["比赛战报"].waitForExistence(timeout: 5))
        app.buttons["share-report-pdf"].tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 5), app.debugDescription)
    }

    func testStatisticsLargeTextAndDarkModeKeepFiltersReachable() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--statistics-preview", "-appAppearance", "dark",
                               "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        XCTAssertTrue(app.buttons["stats-season-picker"].waitForExistence(timeout: 5))
        let pitching = app.buttons["投手"]
        for _ in 0..<12 where !pitching.isHittable { app.swipeUp() }
        XCTAssertTrue(pitching.isHittable)
        pitching.tap()
        let sort = app.buttons["stats-sort-picker"]
        for _ in 0..<20 where !sort.isHittable { app.swipeUp() }
        XCTAssertTrue(sort.isHittable)
        XCTAssertLessThanOrEqual(sort.frame.maxX, app.frame.maxX)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "统计页-深色-最大字体"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        sort.tap()
        XCTAssertTrue(app.buttons["三振 SO"].waitForExistence(timeout: 3))
        app.buttons["三振 SO"].tap()
        XCTAssertTrue(app.buttons["stats-sort-direction"].isHittable)
    }

    func testStatisticsSwitchesSeasonAndTeamUsingRealCompletedGames() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--statistics-preview"]
        app.launch()
        let record = app.staticTexts["stats-team-record"]
        XCTAssertTrue(record.waitForExistence(timeout: 5))
        XCTAssertEqual(record.label, "1 场比赛 · 1胜0负0平")
        app.buttons["stats-season-picker"].tap()
        app.buttons["2025 年"].tap()
        XCTAssertEqual(record.label, "1 场比赛 · 0胜1负0平")
        captureHistory(app, name: "3.0-annual-statistics-2025")
        app.buttons["stats-team-picker"].tap()
        app.buttons["新建球队 · 本机"].tap()
        XCTAssertEqual(record.label, "0 场比赛 · 0胜0负0平")
        let empty = app.staticTexts["这个年度还没有已结束的比赛"]
        for _ in 0..<5 where !empty.isHittable { app.swipeUp() }
        XCTAssertTrue(empty.exists)
    }

    func testStatisticsCategoriesOpenPlayerWithSeasonAndGameFilter() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--statistics-preview"]
        app.launch()
        XCTAssertTrue(app.buttons["stats-season-picker"].waitForExistence(timeout: 5))
        app.buttons["stats-season-picker"].tap()
        app.buttons["2025 年"].tap()
        app.buttons["投手"].tap()
        let player = app.buttons["stats-player-陈昊"]
        for _ in 0..<6 where !player.isHittable { app.swipeUp() }
        XCTAssertTrue(player.isHittable)
        player.tap()
        XCTAssertTrue(app.navigationBars["球员数据"].waitForExistence(timeout: 3))
        let season = app.buttons["player-season-picker"]
        XCTAssertTrue((season.label + (season.value as? String ?? "")).contains("2025 年"))
        XCTAssertTrue(app.buttons["投手"].isSelected)
        XCTAssertTrue(app.staticTexts["已选 1 / 1 场比赛"].exists)
        app.buttons["open-game-filter"].tap()
        app.buttons["清空"].tap()
        app.buttons["应用"].tap()
        XCTAssertTrue(app.staticTexts["已选 0 / 1 场比赛"].waitForExistence(timeout: 3))
        app.buttons["守备"].tap()
        XCTAssertTrue(app.otherElements["stats-metrics-守备"].exists)
    }

    func testStatisticsSearchRecordFilterAndSortControls() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--statistics-preview"]
        app.launch()
        let search = app.textFields["stats-search"]
        revealStatisticsControl(search, in: app)
        XCTAssertTrue(search.isHittable)
        search.tap()
        search.typeText("NoSuchPlayer\n")
        app.swipeUp()
        XCTAssertTrue(app.staticTexts["没有符合条件的球员"].exists)
        app.buttons["清空搜索"].tap()
        search.tap()
        search.typeText("Chen Hao\n")
        app.swipeUp()
        XCTAssertTrue(app.buttons["stats-player-陈昊"].waitForExistence(timeout: 3))
        app.buttons["清空搜索"].tap()
        let sort = app.buttons["stats-sort-picker"]
        revealStatisticsControl(sort, in: app)
        if !sort.isHittable {
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = "统计页-搜索后排序定位"
            screenshot.lifetime = .keepAlways
            add(screenshot)
        }
        XCTAssertTrue(sort.isHittable)
        sort.tap()
        app.buttons["本垒打 HR"].tap()
        XCTAssertTrue(app.buttons["stats-sort-direction"].label.contains("降序"))
        app.buttons["stats-sort-direction"].tap()
        XCTAssertTrue(app.buttons["stats-sort-direction"].label.contains("升序"))
        let recordsOnly = app.switches["stats-records-only"]
        revealStatisticsControl(recordsOnly, in: app)
        recordsOnly.tap()
        XCTAssertTrue(app.staticTexts["12 名球员 · 点击查看逐场数据"].exists)
    }

    func testStatisticsEmptySeasonHasNoFabricatedTotals() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--statistics-empty-preview"]
        app.launch()
        XCTAssertTrue(app.staticTexts["stats-team-record"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["stats-team-record"].label, "0 场比赛 · 0胜0负0平")
        XCTAssertFalse(app.staticTexts["4 场比赛 · 3胜1负"].exists)
        let empty = app.staticTexts["这个年度还没有已结束的比赛"]
        for _ in 0..<5 where !empty.isHittable { app.swipeUp() }
        XCTAssertTrue(empty.exists)
        XCTAssertTrue(app.staticTexts["没有符合条件的球员"].exists)
    }

    private func keepFinishedGameLocal(in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let keep = app.buttons["keep-final-local"]
        XCTAssertTrue(keep.waitForExistence(timeout: 5), "Saved formal matches must ask whether to upload", file: file, line: line)
        keep.tap()
        XCTAssertTrue(keep.waitForNonExistence(timeout: 5), file: file, line: line)
    }

    private func revealStatisticsControl(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<15 {
            if element.exists && element.isHittable && element.frame.minY > 100
                && element.frame.maxY < app.frame.maxY - 70 { return }
            let above = element.exists && element.frame.midY < app.frame.midY
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: above ? 0.35 : 0.7))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: above ? 0.65 : 0.4))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
    }

    func testScorekeepingCoreControlsAndOutcomeSheet() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--scorekeeping-preview"]
        app.launch()

        XCTAssertTrue(app.buttons["pitch-坏球"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["pitch-看振"].exists)
        XCTAssertTrue(app.buttons["pitch-挥空"].exists)
        XCTAssertTrue(app.buttons["pitch-界外"].exists)
        XCTAssertTrue(app.buttons["open-runner-events"].isHittable)
        XCTAssertTrue(app.buttons["open-special-events"].isHittable)
        XCTAssertTrue(app.buttons["open-state-correction"].isHittable)
        XCTAssertTrue(app.buttons["open-substitutions"].isHittable)
        let startClock = app.buttons["start-game-clock"]
        XCTAssertTrue(startClock.exists)
        startClock.tap()
        XCTAssertTrue(app.alerts["确认双方阵容并开赛？"].waitForExistence(timeout: 3))
        app.alerts.buttons["确认开赛"].tap()
        XCTAssertTrue(app.buttons["game-clock-display"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["redo-last-play"].exists)

        app.buttons["ball-in-play"].tap()
        XCTAssertTrue(app.buttons["arrival-到一垒"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["arrival-打者出局"].exists)
        app.buttons["arrival-到一垒"].tap()
        XCTAssertTrue(app.buttons["cause-正常打上垒"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["cause-守备没处理好"].exists)
    }

    func testGameClockStartsPausesAndSwitchesToRemainingTime() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--scorekeeping-timed-preview"]
        app.launch()

        let start = app.buttons["start-game-clock"]
        XCTAssertTrue(start.waitForExistence(timeout: 3))
        start.tap()
        XCTAssertTrue(app.alerts["确认双方阵容并开赛？"].waitForExistence(timeout: 3))
        app.alerts.buttons["确认开赛"].tap()

        let display = app.buttons["game-clock-display"]
        XCTAssertTrue(display.waitForExistence(timeout: 2))
        XCTAssertTrue(display.label.contains("已进行"))
        display.tap()
        XCTAssertTrue(display.label.contains("剩余"))

        let pause = app.buttons["toggle-game-clock-running"]
        XCTAssertTrue(pause.exists)
        XCTAssertTrue(pause.label.contains("暂停"))
        pause.tap()
        XCTAssertTrue(pause.label.contains("继续"))
    }

    func testPendingEventCanBeOpenedAndReviewedFromCorrection() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--pending-review-preview"]
        app.launch()

        let correction = app.buttons["open-state-correction"]
        XCTAssertTrue(correction.waitForExistence(timeout: 3))
        correction.tap()

        let pending = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH 'pending-event-'")
        ).firstMatch
        XCTAssertTrue(pending.waitForExistence(timeout: 2))
        pending.tap()

        XCTAssertTrue(app.buttons["review-event-outcome"].waitForExistence(timeout: 2))
        let note = app.textFields["review-event-note"]
        XCTAssertTrue(note.waitForExistence(timeout: 2))
        note.tap()
        note.typeText("录像复核确认")
        app.buttons["confirm-pending-event-review"].tap()
        XCTAssertTrue(app.staticTexts["当前没有待确认事件"].waitForExistence(timeout: 2))
    }

    func testCustomDefensiveRouteUsesOrderedFielderTaps() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--scorekeeping-preview"]
        app.launch()

        app.buttons["ball-in-play"].tap()
        app.buttons["arrival-打者出局"].tap()
        app.buttons["cause-打者未到一垒前出局"].tap()
        let other = app.buttons["defense-OTHER"]
        XCTAssertTrue(other.waitForExistence(timeout: 2))
        other.tap()
        XCTAssertTrue(app.navigationBars["自定义守备路线"].waitForExistence(timeout: 2))
        app.buttons["custom-route-6"].tap()
        app.buttons["custom-route-3"].tap()
        XCTAssertTrue(app.buttons["confirm-custom-defense-route"].isEnabled)
    }

    func testManualGameEndRequiresReason() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--scorekeeping-preview"]
        app.launch()

        app.buttons["open-box-score"].tap()
        let finish = app.buttons["open-finish-game"]
        for _ in 0..<5 where !finish.isHittable { app.swipeUp() }
        XCTAssertTrue(finish.isHittable)
        finish.tap()
        XCTAssertTrue(app.navigationBars["结束或中断比赛"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["finish-reason-时间限制结束"].exists)
        XCTAssertTrue(app.buttons["finish-reason-比赛中断"].exists)
    }

    func testBoxScoreCanBeOpenedFromScoring() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--scorekeeping-preview"]
        app.launch()

        let resultButton = app.buttons["open-box-score"]
        XCTAssertTrue(resultButton.waitForExistence(timeout: 3))
        resultButton.tap()
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 2))
    }

    func testResultGroupsChineseRecordsByPlateAppearanceAndOpensSystemShare() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--boxscore-preview"]
        app.launch()

        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 3))
        let records = app.buttons["记录"]
        XCTAssertTrue(records.waitForExistence(timeout: 2))
        records.tap()
        let firstAppearance = app.descendants(matching: .any).matching(identifier: "plate-appearance-away-1").firstMatch
        let secondAppearance = app.descendants(matching: .any).matching(identifier: "plate-appearance-away-2").firstMatch
        let thirdAppearance = app.descendants(matching: .any).matching(identifier: "plate-appearance-away-3").firstMatch
        XCTAssertTrue(firstAppearance.waitForExistence(timeout: 2))
        XCTAssertTrue(secondAppearance.exists)
        XCTAssertTrue(thirdAppearance.exists)

        let share = app.buttons["share-all-game-files"]
        for _ in 0..<8 where !share.isHittable { app.swipeUp() }
        XCTAssertTrue(share.isHittable)
        share.tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 5), app.debugDescription)
    }

    func testPendingRecordCanBeReviewedDirectlyFromGameResult() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--boxscore-preview"]
        app.launch()

        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 3))
        app.buttons["记录"].tap()
        let pending = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH 'result-review-'")
        ).firstMatch
        XCTAssertTrue(pending.waitForExistence(timeout: 2))
        pending.tap()
        XCTAssertTrue(app.navigationBars["复核待确认记录"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["confirm-pending-event-review"].exists)
    }

    func testFormalSubstitutionAndComplexUmpireRulingEntrypointsExist() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--scorekeeping-preview"]
        app.launch()

        app.buttons["open-substitutions"].tap()
        XCTAssertTrue(app.buttons["substitution-fielder"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["substitution-double-switch"].exists)
        app.buttons["返回比赛"].tap()

        app.buttons["open-special-events"].tap()
        app.buttons["special-violations"].tap()
        app.buttons["violation-category-捕手"].tap()
        let customRuling = app.buttons["custom-violation-捕手干扰打击"]
        XCTAssertTrue(customRuling.waitForExistence(timeout: 2))
        customRuling.tap()
        XCTAssertTrue(app.navigationBars["捕手干扰打击"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.staticTexts["正式统计责任"].exists)
        XCTAssertTrue(app.buttons["confirm-custom-violation"].exists)
    }

    func testMultiRunnerTiebreakPlacementCompletesBeforeScoringContinues() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--tiebreak-preview"]
        app.launch()

        XCTAssertTrue(app.navigationBars["TB 垒上放人"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["当前放置：一垒（共 2 人）"].exists)
        let recommended = app.buttons["tiebreak-recommended-runner"]
        XCTAssertTrue(recommended.exists)
        recommended.tap()
        XCTAssertTrue(app.staticTexts["当前放置：二垒（共 2 人）"].waitForExistence(timeout: 2))
        app.buttons["tiebreak-recommended-runner"].tap()
        XCTAssertTrue(app.buttons["pitch-坏球"].waitForExistence(timeout: 3))
    }

    func testSingleWithRunnerUsesSuggestedRunnerConfirmation() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--scorekeeping-preview"]
        app.launch()

        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 3))
        app.buttons["ball-in-play"].tap()
        XCTAssertTrue(app.buttons["arrival-到一垒"].waitForExistence(timeout: 2))
        app.buttons["arrival-到一垒"].tap()
        XCTAssertTrue(app.buttons["cause-正常打上垒"].waitForExistence(timeout: 2))
        app.buttons["cause-正常打上垒"].tap()

        let confirm = app.buttons["confirm-runners"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 3))
        confirm.tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 3))
    }

    func testSpecialEventsAndCorrectionAreVisible() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--scorekeeping-preview"]
        app.launch()

        XCTAssertTrue(app.buttons["open-special-events"].waitForExistence(timeout: 3))
        app.buttons["open-special-events"].tap()
        XCTAssertTrue(app.buttons["special-hbp"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["special-dropped-third"].exists)
        let close = app.buttons["close-special-events"]
        XCTAssertTrue(close.waitForExistence(timeout: 2))
        close.tap()

        XCTAssertTrue(app.buttons["open-state-correction"].waitForExistence(timeout: 2))
        app.buttons["open-state-correction"].tap()
        XCTAssertTrue(app.buttons["apply-state-correction"].waitForExistence(timeout: 2))
    }

    func testViolationRecordingUsesCategorizedTapFlow() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--scorekeeping-preview"]
        app.launch()

        app.buttons["open-special-events"].tap()
        let violations = app.buttons["special-violations"]
        XCTAssertTrue(violations.waitForExistence(timeout: 2))
        if !violations.isHittable { app.swipeUp() }
        violations.tap()

        let pitcherCategory = app.buttons["violation-category-投手"]
        XCTAssertTrue(pitcherCategory.waitForExistence(timeout: 2))
        pitcherCategory.tap()
        XCTAssertTrue(app.buttons["violation-投手犯规"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["violation-快速投球"].exists)
    }

    func testTeamListOpensRosterAndPlayerStatistics() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--team-preview"]
        app.launch()

        XCTAssertTrue(app.navigationBars["球队"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["add-team"].exists)
        let team = app.buttons["team-card-海浪"]
        XCTAssertTrue(team.exists)
        team.tap()

        XCTAssertTrue(app.navigationBars["海浪"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["add-player"].exists)
        let player = app.buttons["player-card-陈昊"]
        XCTAssertTrue(player.exists)
        player.tap()

        XCTAssertTrue(app.navigationBars["球员数据"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["open-game-filter"].exists)
        XCTAssertTrue(app.staticTexts["近 3 场"].exists)
        XCTAssertTrue(app.staticTexts["近 10 场"].exists)
        XCTAssertTrue(app.staticTexts["本年度"].exists)
    }

    func testPlayerGameFilterCanBeOpened() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--player-detail-preview"]
        app.launch()

        let filter = app.buttons["open-game-filter"]
        XCTAssertTrue(filter.waitForExistence(timeout: 3))
        filter.tap()
        XCTAssertTrue(app.navigationBars["筛选比赛"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["全选"].exists)
        XCTAssertTrue(app.buttons["清空"].exists)
        XCTAssertTrue(app.buttons["应用"].exists)
    }

    func testProfileShowsRulesPracticeDataSettingsAndAbout() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--profile-preview"]
        app.launch()

        XCTAssertTrue(app.navigationBars["我的"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["open-baseball-rules"].exists)
        XCTAssertTrue(app.buttons["open-practice-inning"].exists)
        XCTAssertTrue(app.buttons["open-local-data"].exists)
        XCTAssertTrue(app.buttons["open-app-settings"].exists)
        XCTAssertTrue(app.buttons["open-app-about"].exists)
    }

    func testRulesCategoriesAndBundledPDFCanBeOpened() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--rules-preview"]
        app.launch()

        XCTAssertTrue(app.navigationBars["棒球规则查询"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["rule-category-基础"].exists)
        XCTAssertTrue(app.buttons["rule-category-进攻"].exists)
        XCTAssertTrue(app.buttons["rule-category-守备"].exists)
        XCTAssertTrue(app.buttons["rule-category-投手"].exists)
        XCTAssertTrue(app.buttons["rule-category-违规"].exists)

        let openPDF = app.buttons["open-rule-pdf"]
        XCTAssertTrue(openPDF.exists)
        openPDF.tap()
        XCTAssertTrue(app.navigationBars["棒球规则 2022"].waitForExistence(timeout: 4))
        XCTAssertTrue(app.staticTexts["rule-pdf-page-count"].exists)

        let searchField = app.textFields["rule-pdf-search-field"]
        XCTAssertTrue(searchField.waitForExistence(timeout: 2))
        searchField.tap()
        searchField.typeText("投手")
        app.buttons["rule-pdf-search-button"].tap()
        XCTAssertTrue(app.staticTexts["rule-pdf-search-status"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["rule-pdf-next-match"].isEnabled)
    }

    func testPracticeModeStartsAndResets() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--practice-preview", "-showPracticeGuidance", "YES"]
        app.launch()

        let start = app.buttons["start-practice-inning"]
        XCTAssertTrue(start.waitForExistence(timeout: 3))
        start.tap()

        XCTAssertTrue(app.staticTexts["练习模式 · 不会保存到正式数据"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["pitch-坏球"].exists)
        app.buttons["pitch-坏球"].tap()
        if app.buttons["确认开赛"].waitForExistence(timeout: 2) { app.buttons["确认开赛"].tap() }
        let pitcher = app.descendants(matching: .any)["current-pitcher"].firstMatch
        XCTAssertTrue(pitcher.label.contains("本场已投 1 球"))

        let reset = app.buttons["reset-practice-inning"]
        XCTAssertTrue(reset.exists)
        reset.tap()
        XCTAssertTrue(app.buttons["重新开始"].waitForExistence(timeout: 2))
        app.buttons["重新开始"].tap()
        XCTAssertTrue(app.buttons["pitch-坏球"].waitForExistence(timeout: 2))
        XCTAssertTrue(pitcher.label.contains("本场已投 0 球"))
        app.buttons["结束比赛"].tap()
        let reason = app.buttons["finish-reason-时间限制结束"]
        XCTAssertTrue(reason.waitForExistence(timeout: 5)); reason.tap()
        app.buttons["确认保存"].tap()
        let back = app.buttons["return-final-game-home"]
        XCTAssertTrue(back.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["keep-final-local"].exists, "Practice is never a saved formal match")
        XCTAssertTrue(back.label.contains("返回练习")); back.tap()
        XCTAssertTrue(start.waitForExistence(timeout: 5))
    }

    func testFormalGameHomeUsesLocalEmptyStates() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--home-preview"]
        app.launch()

        XCTAssertTrue(app.navigationBars["比赛"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["start-new-game"].exists)
        XCTAssertTrue(app.staticTexts["没有进行中的比赛"].exists)
        XCTAssertTrue(app.staticTexts["还没有已完成的比赛"].exists)
        XCTAssertTrue(app.staticTexts["暂无战绩"].exists)
    }

    func testGameSetupOpensPersistentOpponentManagement() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--setup-preview"]
        app.launch()

        XCTAssertTrue(app.navigationBars["新比赛"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["continue-to-lineup"].exists)
        let manage = app.buttons["manage-opponents"]
        XCTAssertTrue(manage.exists)
        manage.tap()

        XCTAssertTrue(app.navigationBars["对手管理"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["add-opponent-team"].exists)
        XCTAssertTrue(app.buttons["opponent-card-飞鹰"].exists)
    }

    func testLineupSupportsPositionsBenchAndDragReordering() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--lineup-preview"]
        app.launch()

        XCTAssertTrue(app.navigationBars["选择先发"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["reuse-previous-lineup"].exists)
        XCTAssertTrue(app.staticTexts["替补球员"].exists)
        XCTAssertTrue(app.buttons["confirm-lineup"].isEnabled)

        let first = app.images["lineup-drag-陈昊"]
        let third = app.images["lineup-drag-林宇轩"]
        XCTAssertTrue(first.exists)
        XCTAssertTrue(third.exists)
        first.press(forDuration: 1.0, thenDragTo: third)
        XCTAssertGreaterThan(first.frame.minY, third.frame.minY)
    }

    func testHomeShowsScheduledOngoingAndObservedHistory() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--home-with-games-preview"]
        app.launch()

        XCTAssertTrue(app.navigationBars["比赛"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["即将进行"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.staticTexts["正在进行"].exists)
        XCTAssertTrue(app.staticTexts["最近比赛"].exists)
        XCTAssertTrue(app.staticTexts["观"].exists)
    }

    func testSetupSupportsFutureObservedGameFlow() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--root-preview"]
        app.launch()

        let startNewGame = app.buttons["start-new-game"]
        XCTAssertTrue(startNewGame.waitForExistence(timeout: 3))
        startNewGame.tap()
        XCTAssertTrue(app.navigationBars["新比赛"].waitForExistence(timeout: 2))

        let observedMode = app.buttons["观赛记录"]
        XCTAssertTrue(observedMode.waitForExistence(timeout: 3))
        observedMode.tap()
        XCTAssertTrue(app.staticTexts["客队 · 先攻"].exists)
        XCTAssertTrue(app.staticTexts["主队 · 后攻"].exists)

        let futureToggle = app.switches["安排未来比赛"]
        for _ in 0..<4 where !futureToggle.isHittable { app.swipeUp() }
        XCTAssertTrue(futureToggle.isHittable)
        futureToggle.tap()
        XCTAssertTrue(app.staticTexts["开赛时间"].exists)
        XCTAssertTrue(app.switches["现在完成赛前设置"].exists)
        XCTAssertFalse(app.switches["单投手局数限制"].exists)

        let next = app.buttons["continue-to-lineup"]
        for _ in 0..<5 where !next.isHittable { app.swipeUp() }
        XCTAssertTrue(next.isHittable)
        XCTAssertTrue(next.isEnabled)
        next.tap()

        let creationAlert = app.alerts["保存比赛安排？"]
        XCTAssertTrue(creationAlert.waitForExistence(timeout: 2))
        creationAlert.buttons["保存安排"].tap()

        let createdAlert = app.alerts["比赛安排已保存"]
        XCTAssertTrue(createdAlert.waitForExistence(timeout: 2))
        createdAlert.buttons["制作宣传海报"].tap()
        XCTAssertTrue(app.images["game-poster-preview"].waitForExistence(timeout: 3))
        app.buttons["close-created-game-poster"].tap()
        XCTAssertTrue(app.navigationBars["比赛"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["即将进行"].exists)

        let scheduledGame = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH 'scheduled-game-'")
        ).firstMatch
        XCTAssertTrue(scheduledGame.waitForExistence(timeout: 2))
        scheduledGame.tap()
        XCTAssertTrue(app.navigationBars["比赛安排"].waitForExistence(timeout: 2))
        app.buttons["prepare-scheduled-game"].tap()
        XCTAssertTrue(app.navigationBars["赛前设置"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["scheduled-roster-客队名单"].exists)
        XCTAssertTrue(app.buttons["scheduled-roster-主队名单"].exists)
    }

    func testFutureGameCanBeConfiguredBeforeSaving() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--root-preview"]
        app.launch()

        let startNewGame = app.buttons["start-new-game"]
        XCTAssertTrue(startNewGame.waitForExistence(timeout: 3))
        startNewGame.tap()
        XCTAssertTrue(app.navigationBars["新比赛"].waitForExistence(timeout: 2))

        let futureToggle = app.switches["安排未来比赛"]
        for _ in 0..<4 where !futureToggle.isHittable { app.swipeUp() }
        XCTAssertTrue(futureToggle.isHittable)
        futureToggle.tap()

        let configureNow = app.switches["现在完成赛前设置"]
        XCTAssertTrue(configureNow.waitForExistence(timeout: 2))
        configureNow.tap()

        let pitcherInningsToggle = app.switches["单投手局数限制"]
        for _ in 0..<6 where !pitcherInningsToggle.isHittable { app.swipeUp() }
        XCTAssertTrue(pitcherInningsToggle.isHittable)
        pitcherInningsToggle.tap()
        XCTAssertTrue(app.staticTexts["投球局数上限"].exists)

        let next = app.buttons["continue-to-lineup"]
        for _ in 0..<5 where !next.isHittable { app.swipeUp() }
        XCTAssertTrue(next.isHittable)
        next.tap()
        XCTAssertTrue(app.navigationBars["双方阵容"].waitForExistence(timeout: 3))
        let confirm = app.buttons["confirm-lineup"]
        XCTAssertTrue(confirm.exists)
        XCTAssertTrue(confirm.isEnabled)
        confirm.tap()

        let confirmation = app.alerts["确认创建未来比赛？"]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 2))
        confirmation.buttons["创建比赛"].tap()
        XCTAssertTrue(app.alerts["比赛已创建"].waitForExistence(timeout: 2))
    }

    func testObservedGameCanConfirmBothLineupsBeforeStarting() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--setup-preview"]
        app.launch()

        let observedMode = app.buttons["观赛记录"]
        XCTAssertTrue(observedMode.waitForExistence(timeout: 3))
        observedMode.tap()

        let next = app.buttons["continue-to-lineup"]
        for _ in 0..<5 where !next.isHittable { app.swipeUp() }
        XCTAssertTrue(next.isHittable)
        next.tap()

        XCTAssertTrue(app.navigationBars["观赛阵容"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["客队 · 先攻"].exists)
        XCTAssertTrue(app.staticTexts["主队 · 后攻"].exists)
        let rows = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label MATCHES %@", "observed-lineup-row-", "^[0-9]+$"))
        for start in [0, 9] {
            let id = rows.element(boundBy: start).identifier
            let target = rows.element(boundBy: start + 2)
            revealStatisticsControl(target, in: app)
            let dragged = rows.matching(identifier: id).firstMatch
            let originalLabel = dragged.label
            dragged.press(forDuration: 1, thenDragTo: target)
            XCTAssertNotEqual(dragged.label, originalLabel, "Dragging each team's first player must change its recorded batting-order label")
        }
        captureV111("2.2-观赛双方赛前拖动阵容", app: app)
        XCTAssertTrue(app.buttons["confirm-observed-lineup"].isEnabled)
    }
}

extension BaseballMasterUITests {
    private func sharingTestSecret() -> String {
        Data((0..<32).map { _ in UInt8.random(in: .min ... .max) }).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    private func sharingAPI(_ credential: String, _ path: String, method: String = "POST", body: [String: Any]? = nil) async throws -> [String: Any] {
        var request = URLRequest(url: URL(string: "http://127.0.0.1:18788/api/v3" + path)!)
        request.httpMethod = method
        request.setValue("Bearer \(credential)", forHTTPHeaderField: "Authorization")
        request.setValue("1", forHTTPHeaderField: "X-BM-Protocol")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try body.map { try JSONSerialization.data(withJSONObject: $0) }
        let (data, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    @MainActor func testV30SharingCreateInviteSecondDeviceAndManageRole() async throws {
        let app = XCUIApplication()
        app.launchArguments = ["--v30-sharing-preview", "--team-test-id=\(UUID().uuidString)", "-appAppearance", "dark"]
        app.launch()
        XCTAssertTrue(app.buttons["open-team-sharing"].waitForExistence(timeout: 5)); app.buttons["open-team-sharing"].tap()
        let create = app.buttons["create-shared-team"]
        XCTAssertTrue(create.waitForExistence(timeout: 5))
        XCTAssertTrue(create.isEnabled); create.tap()
        let name = "协作验收队"
        app.textFields["shared-team-name"].tap(); app.textFields["shared-team-name"].typeText(name)
        app.textFields["shared-nickname"].tap(); app.textFields["shared-nickname"].typeText("陈教练")
        let confirm = app.buttons["confirm-sharing-scope"]
        revealStatisticsControl(confirm, in: app); confirm.tap()
        XCTAssertTrue(app.alerts["创建这支共享球队？"].waitForExistence(timeout: 3))
        captureHistory(app, name: "3.0-sharing-scope-confirmation")
        app.buttons["submit-shared-team"].firstMatch.tap()
        XCTAssertTrue(app.buttons["defer-recovery-card"].waitForExistence(timeout: 8)); app.buttons["defer-recovery-card"].tap()
        let team = app.buttons["shared-team-\(name)"]
        XCTAssertTrue(team.waitForExistence(timeout: 8)); team.tap()
        let invite = app.buttons["invite-team-member"]
        XCTAssertTrue(invite.waitForExistence(timeout: 5)); invite.tap()
        let link = app.staticTexts["team-invitation-url"]
        XCTAssertTrue(link.waitForExistence(timeout: 8))
        let token = try XCTUnwrap(URL(string: link.label)?.fragment)
        captureHistory(app, name: "3.0-team-invitation-qr")
        let guest = sharingTestSecret()
        _ = try await sharingAPI(guest, "/devices", body: ["label": "另一台设备"])
        let joined = try await sharingAPI(guest, "/invitations/redeem", body: ["operationID": UUID().uuidString, "token": token, "nickname": "球员家长"])
        let teamID = try XCTUnwrap(joined["teamID"] as? String)
        app.buttons["完成"].firstMatch.tap()
        let manage = app.buttons["manage-member-球员家长"]
        XCTAssertTrue(manage.waitForExistence(timeout: 8)); manage.tap()
        app.buttons["设为记录员"].tap()
        app.buttons["confirm-member-change"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["记录员"].waitForExistence(timeout: 8))
        let changed = try await sharingAPI(guest, "/teams/\(teamID)", method: "GET")
        XCTAssertEqual(changed["role"] as? String, "scorer")
        captureHistory(app, name: "3.0-team-member-management")
        let leave = app.buttons["leave-shared-team"]
        revealStatisticsControl(leave, in: app); leave.tap(); app.buttons["确认退出"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["team-sharing-error"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["team-sharing-error"].label.contains("另一名管理员"))
    }

    @MainActor func testV30SharingJoinPreviewRosterReadOnlyAndExit() async throws {
        let admin = sharingTestSecret(), invitation = sharingTestSecret()
        _ = try await sharingAPI(admin, "/devices", body: ["label": "邀请人设备"])
        let created = try await sharingAPI(admin, "/teams", body: ["operationID": UUID().uuidString, "name": "受邀少年队", "nickname": "球队管理员",
            "profile": ["shortName": "少年", "city": "上海", "players": [["sourceID": UUID().uuidString, "chineseName": "背号小将", "englishName": "", "numbers": ["00", "12"]]]]])
        let teamID = try XCTUnwrap(created["teamID"] as? String)
        _ = try await sharingAPI(admin, "/teams/\(teamID)/invitations", body: ["operationID": UUID().uuidString, "token": invitation, "role": "member"])
        let app = XCUIApplication()
        app.launchArguments = ["--v30-sharing-preview", "--team-test-id=\(UUID().uuidString)"]
        app.launch()
        app.buttons["open-team-sharing"].tap()
        XCTAssertTrue(app.buttons["join-shared-team"].waitForExistence(timeout: 5)); app.buttons["join-shared-team"].tap()
        app.textFields["team-invite-link"].tap(); app.textFields["team-invite-link"].typeText("https://baseballmaster.cc/join#\(invitation)")
        app.buttons["preview-team-invite"].tap()
        XCTAssertTrue(app.staticTexts["invitation-team-name"].waitForExistence(timeout: 8))
        XCTAssertEqual(app.staticTexts["invitation-team-name"].label, "受邀少年队")
        XCTAssertTrue(app.staticTexts["加入后的角色：成员"].exists)
        captureHistory(app, name: "3.0-join-team-preview")
        app.textFields["join-nickname"].tap(); app.textFields["join-nickname"].typeText("球员家长")
        let confirm = app.buttons["confirm-join-team"]; revealStatisticsControl(confirm, in: app); confirm.tap()
        let team = app.buttons["shared-team-受邀少年队"]
        XCTAssertTrue(team.waitForExistence(timeout: 8)); team.tap()
        XCTAssertTrue(app.staticTexts["背号小将"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["#00 / #12"].exists)
        XCTAssertFalse(app.buttons["invite-team-member"].exists)
        XCTAssertFalse(app.staticTexts["成员管理"].exists)
        captureHistory(app, name: "3.0-shared-roster-member")
        app.buttons["leave-shared-team"].tap(); app.buttons["确认退出"].firstMatch.tap()
        XCTAssertTrue(app.buttons["join-shared-team"].waitForExistence(timeout: 8))
        XCTAssertFalse(team.exists)
    }

    @MainActor func testV30SharingLocalRosterPreviewCancelAndBindingKeepOriginalTeam() async throws {
        let app = XCUIApplication()
        app.launchArguments = ["--v30-sharing-preview", "--team-test-id=\(UUID().uuidString)"]
        app.launch()
        app.buttons["open-team-sharing"].tap()
        let create = app.buttons["create-shared-team"]
        XCTAssertTrue(create.waitForExistence(timeout: 5)); create.tap()
        app.buttons["shared-source-team"].tap(); app.buttons["青岛海浪"].tap()
        XCTAssertFalse(app.textFields["shared-team-name"].exists)
        app.textFields["shared-nickname"].tap(); app.textFields["shared-nickname"].typeText("海浪教练")
        app.buttons["收起键盘"].tap()
        let confirm = app.buttons["confirm-sharing-scope"]
        revealStatisticsControl(confirm, in: app); confirm.tap()
        XCTAssertTrue(app.alerts["创建这支共享球队？"].waitForExistence(timeout: 3))
        captureHistory(app, name: "3.0-local-roster-sharing-preview")
        app.buttons["再核对一下"].firstMatch.tap()
        XCTAssertFalse(app.buttons["shared-team-青岛海浪"].exists)
        revealStatisticsControl(confirm, in: app); confirm.tap(); app.buttons["submit-shared-team"].firstMatch.tap()
        XCTAssertTrue(app.buttons["defer-recovery-card"].waitForExistence(timeout: 8)); app.buttons["defer-recovery-card"].tap()
        let team = app.buttons["shared-team-青岛海浪"]
        XCTAssertTrue(team.waitForExistence(timeout: 8)); team.tap()
        XCTAssertTrue(app.staticTexts["陈昊"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["#12 / #88"].exists)
        captureHistory(app, name: "3.0-shared-team-roster")
        app.navigationBars["共享球队"].buttons.element(boundBy: 0).tap()
        create.tap(); app.buttons["shared-source-team"].tap()
        XCTAssertFalse(app.buttons["青岛海浪"].exists, "Already bound local team cannot accidentally create a duplicate")
        app.buttons["新建空白球队"].tap(); app.buttons["取消"].tap()
        app.navigationBars["球队共享"].buttons.element(boundBy: 0).tap()
        let local = app.buttons["team-card-海浪"]
        XCTAssertTrue(local.waitForExistence(timeout: 5)); local.tap()
        XCTAssertTrue(app.buttons["add-player"].exists, "Original local roster remains independently editable")
    }
}

extension BaseballMasterUITests {
    func testLivePitchCountTracksCurrentPitcherAndUndo() {
        let app = XCUIApplication()
        app.launchArguments = ["--v11-navigation-preview"]
        app.launch()
        let game = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        XCTAssertTrue(game.waitForExistence(timeout: 5))
        game.tap()
        let pitcher = app.descendants(matching: .any)["current-pitcher"].firstMatch
        XCTAssertTrue(pitcher.waitForExistence(timeout: 5))
        XCTAssertTrue(pitcher.label.contains("本场已投 0 球"))
        app.buttons["pitch-坏球"].tap()
        app.buttons["pitch-坏球"].tap()
        app.buttons["pitch-看振"].tap()
        XCTAssertTrue(pitcher.label.contains("本场已投 3 球"))
        app.buttons["undo-last-play"].tap()
        XCTAssertTrue(pitcher.label.contains("本场已投 2 球"))
        app.buttons["redo-last-play"].tap()
        XCTAssertTrue(pitcher.label.contains("本场已投 3 球"))
        app.buttons["open-substitutions"].tap()
        app.buttons["substitution-pitcher"].tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "高博文")).firstMatch.tap()
        XCTAssertTrue(app.buttons["confirm-current-pitcher"].firstMatch.waitForExistence(timeout: 3))
        app.buttons["confirm-current-pitcher"].firstMatch.tap()
        XCTAssertTrue(app.buttons["返回比赛"].waitForExistence(timeout: 3))
        captureV111("2.2-换投后继续调整", app: app)
        app.buttons["返回比赛"].tap()
        XCTAssertTrue(pitcher.label.contains("高博文"))
        XCTAssertTrue(pitcher.label.contains("本场已投 0 球"))
        app.buttons["pitch-坏球"].tap()
        XCTAssertTrue(pitcher.label.contains("本场已投 1 球"))
        app.buttons["undo-last-play"].tap()
        app.buttons["undo-last-play"].tap()
        XCTAssertTrue(pitcher.label.contains("赵一鸣"))
        XCTAssertTrue(pitcher.label.contains("本场已投 3 球"))
        XCTAssertTrue(app.buttons["ball-in-play"].isHittable)
        XCTAssertTrue(app.buttons["结束比赛"].isHittable)
        XCTAssertFalse(app.tabBars.firstMatch.isHittable)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "V1.1-现场投手累计用球"; attachment.lifetime = .keepAlways; add(attachment)

        app.launchArguments = ["--v11-coach-preview"]
        app.launch()
        XCTAssertTrue(app.staticTexts["coach-pitch-count"].waitForExistence(timeout: 5))
        XCTAssertFalse(pitcher.label.contains("已投"))
    }

    func testV11ScoringHidesDockAndKeepsBackNavigation() {
        let app = XCUIApplication()
        app.launchArguments = ["--v11-navigation-preview"]
        app.launch()
        let game = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        XCTAssertTrue(game.waitForExistence(timeout: 5))
        game.tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.tabBars.firstMatch.isHittable)
        for id in ["pitch-坏球", "pitch-界外", "open-runner-events", "open-substitutions"] {
            XCTAssertTrue(app.buttons[id].isHittable)
        }
        XCTAssertTrue(app.buttons["结束半局"].isHittable)
        XCTAssertTrue(app.buttons["结束比赛"].isHittable)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "V1.1-记分全屏与返回"; attachment.lifetime = .keepAlways; add(attachment)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 3))
        XCTAssertTrue(game.exists)
    }

    func testManualGameEndFromHomeKeepsNavigationResponsive() {
        let app = XCUIApplication()
        app.launchArguments = ["--v11-navigation-preview"]
        app.launch()
        let game = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        XCTAssertTrue(game.waitForExistence(timeout: 5))
        game.tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        app.buttons["结束比赛"].tap()
        let reason = app.buttons["finish-reason-时间限制结束"]
        XCTAssertTrue(reason.waitForExistence(timeout: 5))
        reason.tap()
        app.buttons["确认保存"].tap()
        keepFinishedGameLocal(in: app)
        let capture = XCTAttachment(screenshot: app.screenshot())
        capture.name = "手动结束比赛后的导航"; capture.lifetime = .keepAlways; add(capture)
        let home = app.buttons["return-final-game-home"]
        XCTAssertTrue(home.waitForExistence(timeout: 5), app.debugDescription)
        let results = app.buttons["open-box-score"]
        results.tap()
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5), app.debugDescription)
        app.navigationBars["比赛结果"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(home.waitForExistence(timeout: 5))
        home.tap()
        XCTAssertTrue(app.buttons["start-new-game"].waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(game.exists)
        app.tabBars.buttons["我的"].tap()
        XCTAssertTrue(app.navigationBars["我的"].waitForExistence(timeout: 5))
        app.tabBars.buttons["比赛"].tap()
        XCTAssertTrue(app.buttons["start-new-game"].waitForExistence(timeout: 5))
    }

    func testManualEndOfNewGameCanReturnHomeAndOpenSavedResult() {
        let app = XCUIApplication()
        app.launchArguments = ["--root-preview"]
        app.launch()
        startNewGameForNavigationTest(in: app)
        app.buttons["pitch-坏球"].tap()
        app.buttons["结束比赛"].tap()
        let reason = app.buttons["finish-reason-时间限制结束"]
        XCTAssertTrue(reason.waitForExistence(timeout: 5)); reason.tap()
        app.buttons["确认保存"].tap()
        keepFinishedGameLocal(in: app)
        XCTAssertTrue(app.buttons["return-final-game-home"].waitForExistence(timeout: 5))
        let capture = XCTAttachment(screenshot: app.screenshot())
        capture.name = "新建比赛手动结束后的返回入口"; capture.lifetime = .keepAlways; add(capture)
        let home = app.buttons["return-final-game-home"]
        XCTAssertTrue(home.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertEqual(home.frame.height, app.buttons["undo-last-play"].frame.height, accuracy: 1)
        XCTAssertEqual(home.frame.height, app.buttons["redo-last-play"].frame.height, accuracy: 1)
        home.tap()
        XCTAssertTrue(app.buttons["start-new-game"].waitForExistence(timeout: 5))
        app.tabBars.buttons["我的"].tap()
        XCTAssertTrue(app.navigationBars["我的"].waitForExistence(timeout: 5))
        app.tabBars.buttons["比赛"].tap()
        let saved = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "completed-game-")).firstMatch
        revealStatisticsControl(saved, in: app)
        XCTAssertTrue(saved.exists); saved.tap()
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5))
        let reopen = app.buttons["reopen-game"]
        revealStatisticsControl(reopen, in: app)
        XCTAssertTrue(reopen.exists)
        reopen.tap()
        XCTAssertTrue(app.buttons["open-finish-game"].waitForExistence(timeout: 5))
        app.navigationBars["比赛结果"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["start-new-game"].waitForExistence(timeout: 5))
        let ongoing = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        revealStatisticsControl(ongoing, in: app)
        XCTAssertTrue(ongoing.exists); ongoing.tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "1坏球")).firstMatch.exists)
    }

    func testManualEndFromNewObservedGameResultsCanReturnHome() {
        let app = XCUIApplication()
        app.launchArguments = ["--root-preview", "-appAppearance", "dark"]
        app.launch()
        startNewGameForNavigationTest(in: app, observed: true)
        app.buttons["open-box-score"].tap()
        let finish = app.buttons["open-finish-game"]
        revealStatisticsControl(finish, in: app)
        finish.tap()
        let reason = app.buttons["finish-reason-规定局数完成"]
        XCTAssertTrue(reason.waitForExistence(timeout: 5)); reason.tap()
        app.buttons["确认保存"].tap()
        keepFinishedGameLocal(in: app)
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5))
        app.navigationBars["比赛结果"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["return-final-game-home"].waitForExistence(timeout: 5))
        let returnHome = app.buttons["return-game-home"]
        XCTAssertFalse(app.buttons["keep-final-local"].exists, "Returning from results must not present a second postgame prompt")
        XCTAssertTrue(returnHome.isHittable, app.debugDescription)
        returnHome.tap()
        XCTAssertTrue(app.buttons["start-new-game"].waitForExistence(timeout: 5), app.debugDescription)
        app.tabBars.buttons["统计"].tap()
        XCTAssertTrue(app.navigationBars["统计"].waitForExistence(timeout: 5))
    }

    func testCancelAndSuspendNewGameKeepResumeAndHomeNavigation() {
        let app = XCUIApplication()
        app.launchArguments = ["--root-preview"]
        app.launch()
        startNewGameForNavigationTest(in: app)
        app.buttons["pitch-坏球"].tap()
        app.buttons["结束比赛"].tap()
        let reason = app.buttons["finish-reason-时间限制结束"]
        XCTAssertTrue(reason.waitForExistence(timeout: 5)); reason.tap()
        app.buttons["取消"].firstMatch.tap()
        XCTAssertTrue(reason.waitForExistence(timeout: 5))
        app.navigationBars["结束或中断比赛"].buttons["取消"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        app.buttons["结束比赛"].tap()
        let suspend = app.buttons["finish-reason-比赛中断"]
        revealStatisticsControl(suspend, in: app)
        suspend.tap(); app.buttons["确认保存"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["keep-final-local"].exists, "Suspending is not finishing and must not offer upload")
        app.buttons["return-game-home"].tap()
        XCTAssertTrue(app.buttons["start-new-game"].waitForExistence(timeout: 5))
        let ongoing = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        revealStatisticsControl(ongoing, in: app)
        XCTAssertTrue(ongoing.exists); ongoing.tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "1坏球")).firstMatch.exists)
    }

    private func startNewGameForNavigationTest(in app: XCUIApplication, observed: Bool = false) {
        XCTAssertTrue(app.buttons["start-new-game"].waitForExistence(timeout: 5))
        app.buttons["start-new-game"].tap()
        if observed { app.buttons["观赛记录"].tap() }
        let next = app.buttons["continue-to-lineup"]
        revealStatisticsControl(next, in: app)
        next.tap()
        let lineup = app.buttons[observed ? "confirm-observed-lineup" : "confirm-lineup"]
        revealStatisticsControl(lineup, in: app)
        lineup.tap()
        let confirmation = app.alerts[observed ? "开始观赛记录？" : "建立比赛并开始记录？"]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        confirmation.buttons["开始记录"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        let playBall = app.buttons["start-game-clock"]
        if playBall.exists {
            if !app.frame.contains(playBall.frame) { revealStatisticsControl(playBall, in: app) }
            // Verify the actual tap and confirmation for this visible compact-header control.
            XCTAssertTrue(app.frame.intersects(playBall.frame), app.debugDescription)
            playBall.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            XCTAssertTrue(app.alerts["确认双方阵容并开赛？"].waitForExistence(timeout: 5), app.debugDescription)
            app.alerts.buttons["确认开赛"].tap()
        }
    }

    func testV11FullBasesContrast() {
        for appearance in ["light", "dark"] {
            let app = XCUIApplication()
            app.launchArguments = ["--v11-bases-preview", "-appAppearance", appearance]
            app.launch()
            XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
            for base in 1...3 {
                XCTAssertTrue(app.descendants(matching: .any)["base-\(base)-occupied"].firstMatch.exists)
            }
            let attachment = XCTAttachment(screenshot: app.screenshot())
            attachment.name = "V1.1-满垒-\(appearance)"; attachment.lifetime = .keepAlways; add(attachment)
        }
    }

    func testV11CoachLimitAndReopenFlow() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--v11-coach-preview"]
        app.launch()
        XCTAssertTrue(app.staticTexts["coach-pitch-count"].waitForExistence(timeout: 5))
        for _ in 0..<6 { app.buttons["pitch-坏球"].tap() }
        XCTAssertTrue(app.staticTexts["coach-pitch-count"].label.contains("0 / 6"))
        XCTAssertTrue(app.buttons["结束比赛"].isHittable)
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "V1.1-教练投手-二垒跑者"; image.lifetime = .keepAlways; add(image)
        app.buttons["结束比赛"].tap()
        let finishReason = app.buttons["finish-reason-记录员结束记录"]
        revealStatisticsControl(finishReason, in: app)
        finishReason.tap()
        app.buttons["确认保存"].tap()
        keepFinishedGameLocal(in: app)
        XCTAssertTrue(app.buttons["return-final-game-home"].waitForExistence(timeout: 3))
        app.buttons["open-box-score"].tap()
        let reopen = app.buttons["reopen-game"]
        revealStatisticsControl(reopen, in: app)
        XCTAssertTrue(reopen.exists)
        reopen.tap()
        XCTAssertTrue(app.buttons["open-finish-game"].waitForExistence(timeout: 3))
    }

    func testV11RunLimitPromptAndManualHalfInDarkMode() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--v11-cap-preview", "-appAppearance", "dark"]
        app.launch()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "V1.1-深色-二垒跑者"; image.lifetime = .keepAlways; add(image)
        app.buttons["ball-in-play"].tap()
        app.buttons["arrival-打者得分"].tap()
        app.buttons["cause-本垒打"].tap()
        let confirm = app.buttons["confirm-runners"]
        revealStatisticsControl(confirm, in: app)
        confirm.tap()
        XCTAssertTrue(app.buttons["继续本半局（不再加分）"].waitForExistence(timeout: 5))
        app.buttons["继续本半局（不再加分）"].tap()
        app.buttons["结束半局"].tap()
        app.buttons["确认交换攻守"].tap()
        XCTAssertTrue(app.buttons["pitch-坏球"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["pitch-坏球"].isHittable)
        let after = XCTAttachment(screenshot: app.screenshot())
        after.name = "V1.1-手动换边"; after.lifetime = .keepAlways; add(after)
    }

    func testV11OptionalNamesAndDoubleZeroEditor() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--team-preview"]
        app.launch()
        app.buttons["team-card-海浪"].tap()
        app.buttons["add-player"].tap()
        app.textFields["背号"].tap(); app.textFields["背号"].typeText("00")
        XCTAssertTrue(app.buttons["保存"].isEnabled)
        app.buttons["保存"].tap()
        XCTAssertTrue(app.buttons["player-card-#00"].waitForExistence(timeout: 3))
    }
}

extension BaseballMasterUITests {
    private func publicLiveSnapshot(_ code: String) throws -> [String: Any] {
        let url = URL(string: "https://baseballmaster.cc/livestreaming/novideo/api/sessions/\(code)")!
        let done = expectation(description: "Read public live projection")
        var received: Data?; var status: Int?; var failure: Error?
        URLSession.shared.dataTask(with: url) { data, response, error in
            received = data; status = (response as? HTTPURLResponse)?.statusCode; failure = error; done.fulfill()
        }.resume()
        wait(for: [done], timeout: 15)
        if let failure { throw failure }
        XCTAssertEqual(status, 200)
        let envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(received)) as? [String: Any])
        return try XCTUnwrap(envelope["snapshot"] as? [String: Any])
    }

    private func waitForPublicLive(_ code: String, matching condition: ([String: Any]) -> Bool) throws -> [String: Any] {
        let deadline = Date().addingTimeInterval(30)
        repeat {
            let state = try publicLiveSnapshot(code)
            if condition(state) { return state }
            Thread.sleep(forTimeInterval: 0.4)
        } while Date() < deadline
        XCTFail("The public HTTPS projection did not reach the expected state")
        return try publicLiveSnapshot(code)
    }

    func testLiveProductionHTTPSScoringCorrectionShareRelaunchAndDelete() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--live-production-persistent-test", "--live-integration-store=\(UUID().uuidString)", "-appAppearance", "light"]
        app.launch()
        let open = app.buttons["open-live-broadcast"]
        XCTAssertTrue(open.waitForExistence(timeout: 10)); open.tap()
        let start = app.buttons["start-live-broadcast"]
        if start.waitForExistence(timeout: 2) { start.tap() }
        XCTAssertTrue(app.buttons["share-live-link"].waitForExistence(timeout: 20), app.debugDescription)
        let code = app.staticTexts["live-view-code"].label
        XCTAssertEqual(code.count, 24)
        XCTAssertEqual(app.staticTexts["live-view-url"].label, "https://baseballmaster.cc/livestreaming/novideo/\(code)")
        let initial = try publicLiveSnapshot(code)
        let initialBalls = try XCTUnwrap(initial["balls"] as? Int)
        let initialRevision = try XCTUnwrap(initial["revision"] as? Int)
        captureHistory(app, name: "公网直播-分享二维码")
        app.buttons["share-live-link"].tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 5))
        if app.buttons["Close"].exists { app.buttons["Close"].tap() }
        else if app.buttons["关闭"].exists { app.buttons["关闭"].tap() }
        else { app.swipeDown() }
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.otherElements["ActivityListView"])
        XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 5), .completed)
        app.buttons["完成"].tap()
        if app.buttons["start-game-clock"].exists {
            app.buttons["start-game-clock"].tap()
            XCTAssertTrue(app.alerts["确认双方阵容并开赛？"].waitForExistence(timeout: 5))
            app.alerts.buttons["确认开赛"].tap()
        }
        app.buttons["pitch-坏球"].tap()
        _ = try waitForPublicLive(code) { ($0["revision"] as? Int ?? 0) > initialRevision && $0["balls"] as? Int == initialBalls + 1 }
        app.buttons["undo-last-play"].tap()
        _ = try waitForPublicLive(code) { $0["balls"] as? Int == initialBalls }
        app.buttons["redo-last-play"].tap()
        _ = try waitForPublicLive(code) { $0["balls"] as? Int == initialBalls + 1 }

        let beforeOffline = try publicLiveSnapshot(code)
        open.tap()
        let network = app.buttons["live-integration-network"]
        revealStatisticsControl(network, in: app); network.tap()
        app.buttons["完成"].tap()
        app.buttons["pitch-坏球"].tap()
        open.tap()
        let pending = app.staticTexts["live-sync-status"]
        XCTAssertTrue(pending.waitForExistence(timeout: 5))
        let pendingSync = expectation(for: NSPredicate(format: "label CONTAINS %@", "待同步"), evaluatedWith: pending)
        wait(for: [pendingSync], timeout: 10)
        XCTAssertEqual(try publicLiveSnapshot(code)["revision"] as? Int, beforeOffline["revision"] as? Int)
        captureHistory(app, name: "公网直播-断网本地记分待补传")
        revealStatisticsControl(network, in: app); network.tap()
        _ = try waitForPublicLive(code) { $0["balls"] as? Int == initialBalls + 2 }
        app.buttons["完成"].tap()
        app.buttons["undo-last-play"].tap()
        _ = try waitForPublicLive(code) { $0["balls"] as? Int == initialBalls + 1 }

        // Correct the latest recorded ball to a called strike. Drafts must stay local.
        let beforeCorrection = try publicLiveSnapshot(code)
        app.buttons["open-history-correction"].tap()
        app.buttons["history-kind-漏记局面"].tap()
        let edit = app.buttons["修改此条"].firstMatch
        revealStatisticsControl(edit, in: app); edit.tap()
        let pitch = app.buttons["history-pitch"]
        XCTAssertTrue(pitch.waitForExistence(timeout: 5)); pitch.tap()
        app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", "好球 - 看", "pitch-看振")).firstMatch.tap()
        app.buttons["history-add-draft"].tap()
        app.buttons["history-preview"].tap()
        XCTAssertTrue(app.buttons["history-save"].waitForExistence(timeout: 5))
        XCTAssertEqual(try publicLiveSnapshot(code)["revision"] as? Int, beforeCorrection["revision"] as? Int)
        app.buttons["history-save"].tap()
        _ = try waitForPublicLive(code) { ($0["revision"] as? Int ?? 0) > (beforeCorrection["revision"] as? Int ?? 0) && ($0["strikes"] as? Int ?? 0) > 0 }
        XCTAssertTrue(open.waitForExistence(timeout: 5))

        app.terminate(); app.launch()
        XCTAssertTrue(open.waitForExistence(timeout: 10)); open.tap()
        XCTAssertTrue(app.buttons["share-live-link"].waitForExistence(timeout: 15))
        XCTAssertEqual(app.staticTexts["live-view-code"].label, code)
        _ = try publicLiveSnapshot(code)
        app.buttons["完成"].tap()
        app.buttons["结束比赛"].tap()
        XCTAssertTrue(app.buttons["finish-reason-记录员结束记录"].waitForExistence(timeout: 5))
        app.buttons["finish-reason-记录员结束记录"].tap(); app.buttons["确认保存"].tap()
        keepFinishedGameLocal(in: app)
        _ = try waitForPublicLive(code) { $0["isFinal"] as? Bool == true && $0["batterOrder"] is NSNull }
        app.buttons["open-box-score"].tap()
        let reopen = app.buttons["reopen-game"]
        revealStatisticsControl(reopen, in: app); reopen.tap()
        _ = try waitForPublicLive(code) { $0["isFinal"] as? Bool == false }
        open.tap()
        XCTAssertTrue(app.buttons["share-live-link"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["live-view-code"].label, code)
        let close = app.buttons["close-live-broadcast"]
        revealStatisticsControl(close, in: app); close.tap(); app.buttons["关闭直播"].tap()
        XCTAssertTrue(start.waitForExistence(timeout: 15))
        let gone = expectation(description: "Public live content deleted")
        URLSession.shared.dataTask(with: URL(string: "https://baseballmaster.cc/livestreaming/novideo/api/sessions/\(code)")!) { _, response, _ in
            XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 404); gone.fulfill()
        }.resume()
        wait(for: [gone], timeout: 15)
        app.buttons["完成"].tap()
        app.navigationBars["比赛结果"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        captureHistory(app, name: "公网直播-关闭后保留本地比赛")
    }

    func testLiveBroadcastStartShareAndCloseWithLocalService() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--v11-bases-preview", "--live-local-test"]
        app.launch()
        let open = app.buttons["open-live-broadcast"]
        XCTAssertTrue(open.waitForExistence(timeout: 5)); XCTAssertTrue(open.isHittable)
        XCTAssertTrue(app.buttons["结束比赛"].isHittable)
        open.tap()
        let start = app.buttons["start-live-broadcast"]
        XCTAssertTrue(start.waitForExistence(timeout: 3)); XCTAssertTrue(start.isEnabled); start.tap()
        let share = app.buttons["share-live-link"]
        XCTAssertTrue(share.waitForExistence(timeout: 10), app.debugDescription)
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "文字直播分享"; screenshot.lifetime = .keepAlways; add(screenshot)
        share.tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 3))
        // Close the system share sheet through its native close button, never select an external recipient.
        let closeShare = app.buttons["Close"]
        if closeShare.exists { closeShare.tap() } else if app.buttons["关闭"].exists { app.buttons["关闭"].tap() } else { app.swipeDown() }
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.otherElements["ActivityListView"])
        XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 5), .completed)
        let close = app.buttons["close-live-broadcast"]
        if !close.isHittable { app.swipeUp() }
        XCTAssertTrue(close.waitForExistence(timeout: 3)); close.tap()
        app.buttons["关闭直播"].tap()
        XCTAssertTrue(start.waitForExistence(timeout: 10), app.debugDescription)
        app.buttons["完成"].tap()
        XCTAssertTrue(open.waitForExistence(timeout: 3)); XCTAssertTrue(app.buttons["结束比赛"].isHittable)
    }
}


extension BaseballMasterUITests {
    func testV21PrivacyDescribesOptionalLiveAndLinksPolicyAndSupport() {
        let app = XCUIApplication()
        app.launchArguments = ["--v11-navigation-preview"]
        app.launch()
        let game = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        XCTAssertTrue(game.waitForExistence(timeout: 5)); game.tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        // In-memory preview stores cannot publish unless a live test is explicitly requested.
        XCTAssertFalse(app.buttons["open-live-broadcast"].exists)
        let scoring = XCTAttachment(screenshot: app.screenshot()); scoring.name = "离线记分"; scoring.lifetime = .keepAlways; add(scoring)
        app.buttons["open-box-score"].tap()
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["open-live-broadcast"].exists)
        app.terminate(); app.launchArguments = ["--profile-preview"]; app.launch()
        let about = app.buttons["open-app-about"]
        revealStatisticsControl(about, in: app); about.tap()
        let description = app.staticTexts["about-privacy-description"]
        XCTAssertTrue(description.waitForExistence(timeout: 5))
        XCTAssertTrue(description.label.contains("主动开启球队共享"))
        XCTAssertTrue(description.label.contains("球队长期档案仍保留"))
        revealStatisticsControl(app.buttons["about-privacy-policy"], in: app)
        XCTAssertTrue(app.buttons["about-privacy-policy"].exists)
        XCTAssertTrue(app.buttons["about-support"].exists)
        let privacy = XCTAttachment(screenshot: app.screenshot()); privacy.name = "2.1 隐私说明"; privacy.lifetime = .keepAlways; add(privacy)
    }
}

extension BaseballMasterUITests {
    private func captureV111(_ name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }

    func testV111LineupReorderProfilesBothTeamsAndCancel() {
        let app = XCUIApplication()
        app.launchArguments = ["--v11-navigation-preview"]
        app.launch()
        let game = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        XCTAssertTrue(game.waitForExistence(timeout: 5)); game.tap()
        let batter = app.descendants(matching: .any)["current-batter"].firstMatch
        XCTAssertTrue(batter.waitForExistence(timeout: 5))
        XCTAssertTrue(batter.label.contains("第 1 棒"))
        app.buttons["pitch-坏球"].tap()
        app.buttons["open-substitutions"].tap()
        XCTAssertTrue(app.buttons["edit-home-lineup"].waitForExistence(timeout: 3))
        app.buttons["edit-away-lineup"].tap()
        XCTAssertTrue(app.buttons["lineup-down-1"].waitForExistence(timeout: 3))
        app.buttons["lineup-down-1"].tap()
        let player = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "lineup-player-", "陈昊")).firstMatch
        player.tap()
        app.buttons["更正姓名／号码"].tap()
        let name = app.textFields["lineup-chinese-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 3))
        name.tap(); name.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (name.value as? String ?? "").count) + "Updated Player")
        let numbers = app.textFields["lineup-numbers"]
        numbers.tap(); numbers.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (numbers.value as? String ?? "").count) + "00, 0")
        app.buttons["lineup-profile-done"].tap()
        captureV111("V1.1.1-阵容排序与资料更正", app: app)
        app.buttons["lineup-save"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["lineup-saved"].firstMatch.waitForExistence(timeout: 3))
        captureV111("2.2-阵容保存后继续调整", app: app)
        app.buttons["lineup-finish"].tap()
        XCTAssertTrue(app.buttons["edit-home-lineup"].waitForExistence(timeout: 3))
        app.buttons["返回比赛"].tap()
        XCTAssertTrue(app.buttons["pitch-坏球"].waitForExistence(timeout: 5))
        XCTAssertTrue(batter.label.contains("第 2 棒"), batter.label)
        XCTAssertTrue(batter.label.contains("Updated Player"), batter.label)
        app.buttons["undo-last-play"].tap()
        XCTAssertTrue(batter.label.contains("第 1 棒")); XCTAssertTrue(batter.label.contains("陈昊"))
        app.buttons["redo-last-play"].tap()
        XCTAssertTrue(batter.label.contains("Updated Player"))
        app.buttons["open-substitutions"].tap(); app.buttons["edit-home-lineup"].tap()
        XCTAssertTrue(app.buttons["lineup-down-1"].waitForExistence(timeout: 3))
        app.buttons["lineup-down-1"].tap()
        app.buttons["lineup-finish"].tap()
        app.buttons["放弃调整并返回"].tap()
        XCTAssertTrue(app.buttons["edit-home-lineup"].waitForExistence(timeout: 3))
        app.buttons["返回比赛"].tap()
        XCTAssertTrue(batter.label.contains("第 2 棒"))
    }

    func testV111FoulBuntVisibleWithoutShrinkingCoreControlsAndCanUndo() {
        for appearance in ["light", "dark"] {
            let app = XCUIApplication()
            app.launchArguments = ["--v11-bases-preview", "-appAppearance", appearance]
            app.launch()
            XCTAssertTrue(app.buttons["pitch-界外"].waitForExistence(timeout: 5))
            let field = app.descendants(matching: .any)["base-2-occupied"].firstMatch
            let beforeFrame = field.frame
            app.buttons["pitch-界外"].tap(); app.buttons["pitch-界外"].tap()
            let bunt = app.buttons["foul-bunt-strikeout"]
            XCTAssertTrue(bunt.waitForExistence(timeout: 3)); XCTAssertTrue(bunt.isHittable)
            for id in ["pitch-坏球", "pitch-界外", "ball-in-play", "open-substitutions", "open-runner-events", "open-special-events"] {
                XCTAssertTrue(app.buttons[id].isHittable)
                XCTAssertGreaterThanOrEqual(app.buttons[id].frame.height, 40)
            }
            XCTAssertEqual(field.frame.height, beforeFrame.height, accuracy: 1)
            XCTAssertTrue(app.buttons["结束半局"].isHittable); XCTAssertTrue(app.buttons["结束比赛"].isHittable)
            captureV111("V1.1.1-两好球触击入口-" + appearance, app: app)
            let before = app.descendants(matching: .any)["current-batter"].firstMatch.label
            app.buttons["pitch-界外"].tap()
            XCTAssertEqual(app.descendants(matching: .any)["current-batter"].firstMatch.label, before)
            bunt.tap()
            XCTAssertFalse(bunt.exists)
            XCTAssertNotEqual(app.descendants(matching: .any)["current-batter"].firstMatch.label, before)
            app.buttons["undo-last-play"].tap()
            XCTAssertTrue(bunt.waitForExistence(timeout: 3))
        }
    }

    func testV111ThirdOutAndTriplePlaySkipRemainingRunnerDestinations() {
        let app = XCUIApplication()
        app.launchArguments = ["--v111-bases-preview"]
        app.launch()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        // Two strikeouts keep existing runners on base.
        for _ in 0..<6 { app.buttons["pitch-看振"].tap() }
        app.buttons["ball-in-play"].tap(); app.buttons["arrival-打者出局"].tap()
        app.buttons["cause-打者未到一垒前出局"].tap()
        let route = app.buttons["defense-6-3"]
        XCTAssertTrue(route.waitForExistence(timeout: 3)); route.tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["ball-in-play"].isHittable)
        XCTAssertFalse(app.alerts.firstMatch.exists)
        XCTAssertFalse(app.buttons["confirm-runners"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["base-1-empty"].firstMatch.waitForExistence(timeout: 3))
        captureV111("V1.1.1-第三出局直接换边", app: app)

        app.terminate(); app.launch()
        app.buttons["ball-in-play"].tap(); app.buttons["arrival-打者出局"].tap()
        let triple = app.buttons["cause-一次出了三人"]
        revealStatisticsControl(triple, in: app); triple.tap()
        let tripleRoute = app.buttons["defense-5-4-3-TP"]
        XCTAssertTrue(tripleRoute.waitForExistence(timeout: 3)); tripleRoute.tap()
        XCTAssertTrue(app.buttons["confirm-terminal-outs"].waitForExistence(timeout: 3))
        XCTAssertFalse(app.buttons["confirm-terminal-outs"].isEnabled)
        let runners = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "terminal-out-"))
        runners.element(boundBy: 0).tap(); runners.element(boundBy: 2).tap()
        captureV111("V1.1.1-三杀只确认实际出局者", app: app)
        app.buttons["confirm-terminal-outs"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 3))
        XCTAssertFalse(app.buttons["confirm-runners"].exists)
    }

    func testV111PitcherCanReturnThroughExistingQuickSubstitution() {
        let app = XCUIApplication()
        app.launchArguments = ["--v11-navigation-preview"]
        app.launch()
        let game = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        XCTAssertTrue(game.waitForExistence(timeout: 5)); game.tap()
        app.buttons["pitch-坏球"].tap()
        app.buttons["open-substitutions"].tap(); app.buttons["substitution-pitcher"].tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "高博文")).firstMatch.tap()
        XCTAssertTrue(app.buttons["confirm-current-pitcher"].firstMatch.waitForExistence(timeout: 3))
        app.buttons["confirm-current-pitcher"].firstMatch.tap()
        XCTAssertTrue(app.buttons["返回比赛"].waitForExistence(timeout: 3)); app.buttons["返回比赛"].tap()
        app.buttons["pitch-看振"].tap()
        app.buttons["open-substitutions"].tap(); app.buttons["substitution-pitcher"].tap()
        let original = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "赵一鸣")).firstMatch
        revealStatisticsControl(original, in: app); original.tap()
        XCTAssertTrue(app.buttons["confirm-current-pitcher"].firstMatch.waitForExistence(timeout: 3))
        app.buttons["confirm-current-pitcher"].firstMatch.tap()
        XCTAssertTrue(app.buttons["返回比赛"].waitForExistence(timeout: 3)); app.buttons["返回比赛"].tap()
        let pitcher = app.descendants(matching: .any)["current-pitcher"].firstMatch
        XCTAssertTrue(pitcher.label.contains("赵一鸣")); XCTAssertTrue(pitcher.label.contains("本场已投 1 球"))
        app.buttons["pitch-坏球"].tap()
        XCTAssertTrue(pitcher.label.contains("本场已投 2 球"))
    }
}

extension BaseballMasterUITests {
    func testV111ForceThirdOutOnlyAsksWhichRunnerAndKeepsScoringReachable() {
        let app = XCUIApplication()
        app.launchArguments = ["--v111-bases-preview"]
        app.launch()
        XCTAssertTrue(app.buttons["pitch-看振"].waitForExistence(timeout: 5))
        for _ in 0..<6 { app.buttons["pitch-看振"].tap() }
        app.buttons["open-runner-events"].tap()
        let force = app.buttons["runner-event-封杀"]
        revealStatisticsControl(force, in: app); force.tap()
        XCTAssertTrue(app.buttons["confirm-third-out-runner"].waitForExistence(timeout: 3))
        let runner = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "third-out-runner-")).firstMatch
        runner.tap()
        captureV111("V1.1.1-封杀第三出局", app: app)
        app.buttons["confirm-third-out-runner"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 3))
        XCTAssertFalse(app.buttons["confirm-runners"].exists)
    }
}

extension BaseballMasterUITests {
    func testV111OpponentPitcherWarningRemainsAfterThirdOutAndOpensAllDetails() {
        let app = XCUIApplication()
        app.launchArguments = ["--v111-limits-preview"]
        app.launch()
        XCTAssertTrue(app.buttons["pitch-看振"].waitForExistence(timeout: 5))
        let initialWarning = app.buttons["game-rule-warning"]
        XCTAssertTrue(initialWarning.waitForExistence(timeout: 3))
        XCTAssertEqual(initialWarning.value as? String, "还有 3 球")
        assertV111WarningBesidePitcher(app)
        captureV111("V1.1.1-投手旁还有3球", app: app)
        for _ in 0..<3 { app.buttons["pitch-看振"].tap() }
        let warning = app.descendants(matching: .any)["game-rule-warning"].firstMatch
        XCTAssertTrue(warning.waitForExistence(timeout: 3))
        XCTAssertTrue(warning.label.contains("飞鹰")); XCTAssertTrue(warning.label.contains("赵一鸣"))
        XCTAssertEqual(warning.value as? String, "其他达限")
        assertV111WarningBesidePitcher(app)
        captureV111("V1.1.1-换边保留对手达限提醒", app: app)
        warning.tap()
        XCTAssertTrue(app.navigationBars["比赛规则提醒"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "达到 2 局登板额度")).firstMatch.exists)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "达到 6 球限制")).firstMatch.exists)
        captureV111("V1.1.1-投手限制详情", app: app)
        app.buttons["完成"].tap()
        app.buttons["undo-last-play"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["current-pitcher"].firstMatch.label.contains("赵一鸣"))
    }
}

extension BaseballMasterUITests {
    private func assertV111WarningBesidePitcher(_ app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let pitcher = app.descendants(matching: .any)["current-pitcher"].firstMatch
        let warning = app.buttons["game-rule-warning"]
        XCTAssertLessThan(abs(pitcher.frame.midY - warning.frame.midY), 3, file: file, line: line)
        // Accessibility reports the touch target, expanded 10 pt beyond the visible badge.
        XCTAssertGreaterThanOrEqual(warning.frame.minX + 10, pitcher.frame.maxX, file: file, line: line)
        XCTAssertTrue(app.buttons["ball-in-play"].isHittable, file: file, line: line)
        XCTAssertGreaterThanOrEqual(app.buttons["ball-in-play"].frame.height, 44, file: file, line: line)
    }

    func testV111FinalInningBadgeAndLongPitcherNameKeepScoringUsable() {
        let app = XCUIApplication()
        for argument in ["--v111-final-inning-preview", "--v111-long-pitcher-preview"] {
            app.launchArguments = [argument, "-appAppearance", "dark"]
            app.launch()
            let warning = app.buttons["game-rule-warning"]
            XCTAssertTrue(warning.waitForExistence(timeout: 5))
            let longName = argument.contains("long-pitcher")
            XCTAssertEqual(warning.value as? String, longName ? "还有 3 球" : "最后登板局")
            assertV111WarningBesidePitcher(app)
            captureV111(longName ? "V1.1.1-长投手姓名提醒-dark" : "V1.1.1-投手旁最后一局-dark", app: app)
            warning.tap()
            XCTAssertTrue(app.navigationBars["比赛规则提醒"].waitForExistence(timeout: 3))
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "最后允许登板")).firstMatch.exists)
            app.buttons["完成"].tap()
            app.buttons["pitch-坏球"].tap()
            XCTAssertTrue(app.descendants(matching: .any)["current-pitcher"].firstMatch.label.contains(longName ? "本场已投 4 球" : "本场已投 1 球"))
            app.terminate()
        }
    }
}

extension BaseballMasterUITests {
    func testHistoryCorrectionDraftPreviewAndFourScreenshots() throws {
        let app = XCUIApplication(); app.launchArguments = ["--correction-preview"]; app.launch()
        XCTAssertTrue(app.buttons["history-kind-漏记换人"].waitForExistence(timeout: 10))
        captureHistory(app, name: "2.1-01-四类纠错入口")
        app.buttons["history-kind-漏记换人"].tap()
        XCTAssertTrue(app.buttons["此前补录"].firstMatch.waitForExistence(timeout: 5))
        captureHistory(app, name: "2.1-02-历史事件与局面")
        revealStatisticsControl(app.buttons["此前补录"].firstMatch, in: app)
        app.buttons["此前补录"].firstMatch.tap()
        XCTAssertTrue(app.buttons["history-add-draft"].waitForExistence(timeout: 5))
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "换上球员")).firstMatch.tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "周亦辰")).firstMatch.tap()
        captureHistory(app, name: "2.1-03-补记换投")
        app.buttons["history-add-draft"].tap()
        XCTAssertTrue(app.buttons["history-preview"].waitForExistence(timeout: 5))
        app.buttons["history-preview"].tap()
        XCTAssertTrue(app.buttons["history-save"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["history-save"].isEnabled)
        captureHistory(app, name: "2.1-04-保存前影响预览")
        app.buttons["history-save"].tap()
    }

    func testHistoryPositionAndErrorEditors() throws {
        let app = XCUIApplication(); app.launchArguments = ["--correction-preview"]; app.launch()
        XCTAssertTrue(app.buttons["history-kind-漏记换守位"].waitForExistence(timeout: 10))
        revealStatisticsControl(app.buttons["history-kind-漏记换守位"], in: app)
        app.buttons["history-kind-漏记换守位"].tap()
        revealStatisticsControl(app.buttons["此前补录"].firstMatch, in: app); app.buttons["此前补录"].firstMatch.tap()
        XCTAssertTrue(app.buttons["history-add-draft"].waitForExistence(timeout: 5))
        captureHistory(app, name: "2.1-05-历史守位调整")
        app.buttons["取消"].tap(); app.buttons["更换类型"].tap()
        revealStatisticsControl(app.buttons["history-kind-漏记失误"], in: app); app.buttons["history-kind-漏记失误"].tap()
        revealStatisticsControl(app.buttons["此前补录"].firstMatch, in: app)
        app.buttons["此前补录"].firstMatch.tap()
        XCTAssertTrue(app.buttons["history-add-draft"].waitForExistence(timeout: 5))
        captureHistory(app, name: "2.1-06-失误与实际进垒")
    }

    func testAppStore21ScoringLineupAndStatisticsScreenshots() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--v111-bases-preview", "-appAppearance", "light"]
        app.launch()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["open-history-correction"].exists)
        captureHistory(app, name: "AppStore-01-现场记分")
        app.buttons["open-substitutions"].tap()
        XCTAssertTrue(app.buttons["edit-away-lineup"].waitForExistence(timeout: 5))
        app.buttons["edit-away-lineup"].tap()
        XCTAssertTrue(app.buttons["lineup-save"].waitForExistence(timeout: 5))
        captureHistory(app, name: "AppStore-06-阵容调整")
        app.terminate()
        app.launchArguments = ["--statistics-preview", "-appAppearance", "light"]
        app.launch()
        XCTAssertTrue(app.buttons["export-team-season-pdf"].waitForExistence(timeout: 10))
        captureHistory(app, name: "AppStore-07-数据统计")
        app.buttons["export-team-season-pdf"].tap()
        XCTAssertTrue(app.navigationBars["球队年度报告"].waitForExistence(timeout: 10))
        captureHistory(app, name: "AppStore-08-PDF战报")
    }

    func testAppStore21SituationScreenshot() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--correction-preview", "-appAppearance", "light"]
        app.launch()
        XCTAssertTrue(app.buttons["history-kind-漏记局面"].waitForExistence(timeout: 10))
        revealStatisticsControl(app.buttons["history-kind-漏记局面"], in: app)
        app.buttons["history-kind-漏记局面"].tap()
        revealStatisticsControl(app.buttons["此前补录"].firstMatch, in: app)
        app.buttons["此前补录"].firstMatch.tap()
        XCTAssertTrue(app.buttons["history-add-draft"].waitForExistence(timeout: 5))
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "记录类型")).firstMatch.tap()
        app.buttons["仅修正局面"].tap()
        captureHistory(app, name: "AppStore-04-补记局面")
    }

    func testAppStore21LiveSharingScreenshot() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--v111-bases-preview", "--live-local-test", "--live-release-preview", "-appAppearance", "light"]
        app.launch()
        let open = app.buttons["open-live-broadcast"]
        XCTAssertTrue(open.waitForExistence(timeout: 10))
        captureHistory(app, name: "AppStore-01-现场记分含直播入口")
        open.tap()
        let start = app.buttons["start-live-broadcast"]
        XCTAssertTrue(start.waitForExistence(timeout: 5)); start.tap()
        let share = app.buttons["share-live-link"]
        XCTAssertTrue(share.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "https://baseballmaster.cc/livestreaming/novideo/")).firstMatch.exists)
        captureHistory(app, name: "AppStore-03-直播分享")
        share.tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 5))
        captureHistory(app, name: "AppStore-03b-系统分享")
    }

    private func captureHistory(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}


extension BaseballMasterUITests {
    func testV22OpeningSwapCanRepeatAndLocksAfterPitchUndo() {
        for flag in ["--v22-opening-preview", "--v22-coach-opening-preview"] {
            let app = XCUIApplication(); app.launchArguments = [flag]; app.launch()
            let game = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
            XCTAssertTrue(game.waitForExistence(timeout: 5)); game.tap()
            let swap = app.buttons["swap-opening-sides"]
            XCTAssertTrue(swap.waitForExistence(timeout: 3))
            for id in ["ball-in-play", "open-substitutions", "结束比赛"] { XCTAssertTrue(app.buttons[id].isHittable) }
            let batter = app.descendants(matching: .any)["current-batter"].firstMatch
            let initial = batter.label
            captureV111("2.2-首球前互换攻守-" + flag, app: app)
            swap.tap(); XCTAssertNotEqual(batter.label, initial)
            swap.tap(); XCTAssertEqual(batter.label, initial)
            swap.tap(); XCTAssertNotEqual(batter.label, initial)
            app.buttons["pitch-坏球"].tap()
            if app.alerts["确认双方阵容并开赛？"].waitForExistence(timeout: 2) { app.alerts.buttons["确认开赛"].tap() }
            XCTAssertFalse(swap.exists)
            app.buttons["undo-last-play"].tap(); XCTAssertFalse(swap.exists)
            XCTAssertTrue(app.buttons["pitch-界外"].isHittable)
        }
    }

    func testV22LineupDragAndTwoSavesStayInEditor() {
        let app = XCUIApplication(); app.launchArguments = ["--v22-opening-preview"]; app.launch()
        let game = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        XCTAssertTrue(game.waitForExistence(timeout: 5)); game.tap()
        app.buttons["open-substitutions"].tap(); app.buttons["edit-away-lineup"].tap()
        XCTAssertTrue(app.buttons["lineup-down-1"].waitForExistence(timeout: 3))
        let first = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "lineup-player-", "陈昊")).firstMatch
        let startY = first.frame.midY
        let rows = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "lineup-player-"))
        // Native List reorder controls are at the trailing edge of each row.
        let source = app.buttons["对1重新排序"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let target = app.buttons["对4重新排序"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        source.press(forDuration: 1, thenDragTo: target)
        XCTAssertGreaterThan(first.frame.midY, startY + 40, app.debugDescription)
        XCTAssertTrue(app.buttons["lineup-save"].isEnabled)
        captureV111("2.2-长按拖动阵容", app: app)
        app.buttons["lineup-save"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["lineup-saved"].firstMatch.waitForExistence(timeout: 3))
        XCTAssertFalse(app.buttons["lineup-save"].isEnabled)
        app.buttons["lineup-down-1"].tap(); app.buttons["lineup-save"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["lineup-saved"].firstMatch.exists)
        XCTAssertTrue(rows.count > 0)
        captureV111("2.2-连续调整阵容已保存", app: app)
        app.buttons["lineup-finish"].tap(); app.buttons["返回比赛"].tap()
        XCTAssertTrue(app.buttons["swap-opening-sides"].waitForExistence(timeout: 3))
    }

    func testV22TeamGameSelectionEmptyRestoreAndPlayerScope() {
        let app = XCUIApplication(); app.launchArguments = ["--v22-statistics-preview"]; app.launch()
        let scope = app.staticTexts["stats-game-scope"]
        XCTAssertTrue(scope.waitForExistence(timeout: 5)); XCTAssertTrue(scope.label.contains("3 / 3"))
        let initialPlayer = app.buttons["stats-player-陈昊"]
        revealStatisticsControl(initialPlayer, in: app); initialPlayer.tap()
        XCTAssertTrue(app.staticTexts["已选 3 / 3 场比赛"].waitForExistence(timeout: 3), "Default team scope must exclude standalone legacy records")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        revealStatisticsControl(app.buttons["stats-open-game-filter"], in: app)
        app.buttons["stats-open-game-filter"].tap()
        let choices = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "stats-select-game-"))
        XCTAssertEqual(choices.count, 3)
        choices.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["已选 2 场"].waitForExistence(timeout: 3))
        XCTAssertEqual(choices.element(boundBy: 1).value as? String, "未选择")
        XCTAssertEqual(choices.element(boundBy: 0).value as? String, "已选择")
        XCTAssertTrue(choices.element(boundBy: 0).label.contains("普通比赛"))
        captureV111("2.2-选择指定比赛统计", app: app)
        app.buttons["stats-apply-game-filter"].tap()
        XCTAssertTrue(scope.label.contains("2 / 3"))
        captureV111("2.2-筛选两场比赛统计", app: app)
        let player = app.buttons["stats-player-陈昊"]
        revealStatisticsControl(player, in: app); player.tap()
        XCTAssertTrue(app.staticTexts["已选 2 / 2 场比赛"].waitForExistence(timeout: 3))
        app.buttons["player-season-picker"].tap(); app.buttons["2025 年"].tap()
        XCTAssertTrue(app.staticTexts["已选 1 / 1 场比赛"].waitForExistence(timeout: 3), "Explicit season switching must leave the inherited previous-season scope")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let filter = app.buttons["stats-open-game-filter"]
        revealStatisticsControl(filter, in: app); filter.tap()
        app.buttons["清空"].tap(); app.buttons["stats-apply-game-filter"].tap()
        XCTAssertTrue(scope.label.contains("0 / 3"))
        let restore = app.buttons["stats-reset-games"]; restore.tap()
        XCTAssertTrue(scope.label.contains("3 / 3"))
    }
}

extension BaseballMasterUITests {
    func testV22LiveViewerFromActualAppCanInspectBothLineupsAndReturn() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--v11-bases-preview", "--live-local-test"]
        app.launch()
        XCTAssertTrue(app.buttons["pitch-坏球"].waitForExistence(timeout: 5))
        app.buttons["pitch-坏球"].tap()
        let open = app.buttons["open-live-broadcast"]
        XCTAssertTrue(open.waitForExistence(timeout: 5)); open.tap()
        app.buttons["start-live-broadcast"].tap()
        XCTAssertTrue(app.buttons["share-live-link"].waitForExistence(timeout: 10))
        let code = app.staticTexts["live-view-code"].label
        XCTAssertEqual(code.count, 24)
        captureHistory(app, name: "2.2直播-App开播与分享")
        let url = URL(string: "http://127.0.0.1:18088/livestreaming/novideo/\(code)")!
        XCUIDevice.shared.system.open(url)
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        safari.activate()
        for title in ["Continue", "继续", "Not Now", "暂不"] {
            if safari.buttons[title].waitForExistence(timeout: 1) { safari.buttons[title].tap() }
        }
        let lineups = safari.buttons["查看双方阵容"]
        XCTAssertTrue(lineups.waitForExistence(timeout: 15), safari.debugDescription)
        // Safari 26 shows a first-run toolbar tip that otherwise consumes the first page tap.
        if safari.otherElements["TipView"].exists { safari.buttons["xmark.circle.fill"].tap() }
        XCTAssertTrue(safari.staticTexts["打席实况"].exists)
        XCTAssertTrue(safari.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "本场")).firstMatch.exists)
        captureHistory(safari, name: "2.2直播-Safari记分牌与实况")
        lineups.tap()
        let back = safari.buttons.matching(NSPredicate(format: "label CONTAINS %@", "返回直播")).firstMatch
        if !back.waitForExistence(timeout: 2) { lineups.tap() }
        XCTAssertTrue(back.waitForExistence(timeout: 5), safari.debugDescription)
        let home = safari.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "主队 ·")).firstMatch
        XCTAssertTrue(home.exists); home.tap()
        XCTAssertTrue(safari.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "当前投手：")).firstMatch.exists)
        captureHistory(safari, name: "2.2直播-Safari主队阵容")
        safari.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "客队 ·")).firstMatch.tap()
        XCTAssertTrue(safari.staticTexts["打击中"].exists)
        captureHistory(safari, name: "2.2直播-Safari客队阵容")
        back.tap(); XCTAssertTrue(lineups.waitForExistence(timeout: 5))
        app.activate()
        let close = app.buttons["close-live-broadcast"]
        revealStatisticsControl(close, in: app); close.tap()
        app.buttons["关闭直播"].tap()
        XCTAssertTrue(app.buttons["start-live-broadcast"].waitForExistence(timeout: 10))
        safari.activate()
        XCTAssertTrue(safari.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "直播已关闭")).firstMatch.waitForExistence(timeout: 15))
        XCTAssertFalse(lineups.exists)
        captureHistory(safari, name: "2.2直播-Safari关闭链接")
    }
}

extension BaseballMasterUITests {
    private func v23Capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }

    @objc func testV23PregameBothLineupsAndConfirmedPlayBallRetainLiveEditing() throws {
        let app = XCUIApplication(); app.launchArguments = ["--v23-slow-preview"]; app.launch()
        XCTAssertTrue(app.buttons["start-game-clock"].waitForExistence(timeout: 10))
        v23Capture(app, "2.3 慢垒开赛前")
        revealStatisticsControl(app.buttons["open-substitutions"], in: app); app.buttons["open-substitutions"].tap()
        XCTAssertTrue(app.buttons["edit-away-lineup"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["edit-home-lineup"].exists)
        XCTAssertFalse(app.buttons["substitution-pitcher"].exists)
        app.buttons["edit-away-lineup"].tap()
        XCTAssertTrue(app.buttons["lineup-finish"].waitForExistence(timeout: 5))
        v23Capture(app, "2.3 开赛前完整打序")
        app.buttons["lineup-finish"].tap()
        app.buttons["返回比赛"].tap()
        app.buttons["start-game-clock"].tap()
        XCTAssertTrue(app.alerts["确认双方阵容并开赛？"].waitForExistence(timeout: 5))
        app.alerts.buttons["确认开赛"].tap()
        XCTAssertTrue(app.buttons["toggle-game-clock-running"].waitForExistence(timeout: 5))
        revealStatisticsControl(app.buttons["open-substitutions"], in: app); app.buttons["open-substitutions"].tap()
        XCTAssertTrue(app.buttons["substitution-pitcher"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["edit-away-lineup"].exists); XCTAssertTrue(app.buttons["edit-home-lineup"].exists)
        v23Capture(app, "2.3 开赛后沿用阵容调整")
    }

    @objc func testV23SlowFoulGraceIllegalAndFreeAreVisibleAndRecordable() throws {
        let app = XCUIApplication(); app.launchArguments = ["--v23-slow-preview"]; app.launch()
        XCTAssertTrue(app.buttons["start-game-clock"].waitForExistence(timeout: 10))
        app.buttons["start-game-clock"].tap(); app.alerts.buttons["确认开赛"].tap()
        revealStatisticsControl(app.buttons["pitch-界外"], in: app); app.buttons["pitch-界外"].tap(); app.buttons["pitch-界外"].tap()
        XCTAssertTrue(app.staticTexts["额外界外机会已用，下次界外出局"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["foul-bunt-strikeout"].exists)
        revealStatisticsControl(app.buttons["record-illegal-pitch"], in: app); app.buttons["record-illegal-pitch"].tap()
        app.buttons["界外，按本场界外规则处理"].tap()
        XCTAssertTrue(app.staticTexts["两好后仍允许一次界外"].exists == false)
        XCTAssertTrue(app.buttons["pitch-坏球"].exists)
        v23Capture(app, "2.3 Illegal 界外终结打席")
        app.buttons["open-box-score"].tap()
        XCTAssertTrue(app.buttons["share-play-by-play"].waitForExistence(timeout: 10))
        v23Capture(app, "2.3 慢垒结果与完整阵容")
    }

    @objc func testV23NewOwnSlowGameConfiguresBothSidesAndExtraHitters() throws {
        let app = XCUIApplication(); app.launchArguments = ["--v23-new-game-preview"]; app.launch()
        let mode = app.buttons["game-mode"]
        revealStatisticsControl(mode, in: app); mode.tap()
        app.buttons["成人慢垒"].tap()
        let duration = app.textFields["slow-pitch-time-limit"]
        revealStatisticsControl(duration, in: app); duration.tap(); duration.typeText("90")
        app.buttons["dismiss-rule-keyboard"].tap()
        let foul = app.buttons["slow-pitch-foul-policy"]
        revealStatisticsControl(foul, in: app); foul.tap()
        app.buttons["再允许一次界外，第二次出局"].tap()
        v23Capture(app, "AppStore23-03-慢垒规则设置")
        for title in ["单投手球数限制", "单投手局数限制"] {
            let reminder = app.switches[title]
            revealStatisticsControl(reminder, in: app)
            XCTAssertTrue(reminder.isEnabled)
            XCTAssertEqual(reminder.value as? String, "0", "Slow-pitch pitcher reminders default to off")
            reminder.tap()
            XCTAssertEqual(reminder.value as? String, "1")
        }
        v23Capture(app, "2.3 慢垒赛前可选投手提醒")
        let next = app.buttons["continue-to-lineup"]
        revealStatisticsControl(next, in: app); next.tap()
        XCTAssertTrue(app.buttons["confirm-lineup"].waitForExistence(timeout: 10))
        let addHitters = app.buttons["增加打者／全员加入"].firstMatch
        revealStatisticsControl(addHitters, in: app); addHitters.tap(); app.buttons["全员加入打序"].tap()
        v23Capture(app, "2.3 创建比赛时设置双方阵容")
        app.buttons["confirm-lineup"].tap(); app.alerts.buttons["开始记录"].tap()
        XCTAssertTrue(app.buttons["start-game-clock"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["open-substitutions"].exists)
        v23Capture(app, "2.3 创建后等待确认开赛")
    }
}

extension BaseballMasterUITests {
    @objc func testV23SlowProductionHTTPSAndSafariViewer() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--v23-live-production-preview"]
        app.launch()
        XCTAssertTrue(app.buttons["start-game-clock"].waitForExistence(timeout: 10))
        app.buttons["start-game-clock"].tap(); app.alerts.buttons["确认开赛"].tap()
        let open = app.buttons["open-live-broadcast"]
        open.tap(); app.buttons["start-live-broadcast"].tap()
        XCTAssertTrue(app.buttons["share-live-link"].waitForExistence(timeout: 20))
        let code = app.staticTexts["live-view-code"].label
        let initial = try publicLiveSnapshot(code)
        XCTAssertEqual(initial["mode"] as? String, "slowPitch")
        XCTAssertEqual(initial["pitchCount"] as? Int, 0)
        XCTAssertEqual(((initial["away"] as? [String: Any])?["lineup"] as? [[String: Any]])?.count, 12)
        XCTAssertEqual(((initial["home"] as? [String: Any])?["lineup"] as? [[String: Any]])?.count, 11)
        app.buttons["完成"].tap(); app.buttons["pitch-界外"].tap(); app.buttons["pitch-界外"].tap()
        let state = try waitForPublicLive(code) {
            $0["pitchCount"] as? Int == 2 && ($0["rules"] as? [String: Any])?["extraFoulUsed"] as? Bool == true
        }
        let source = XCTAttachment(data: try JSONSerialization.data(withJSONObject: state, options: [.prettyPrinted, .sortedKeys]), uniformTypeIdentifier: "public.json")
        source.name = "AppStore23-slow-live-source.json"; source.lifetime = .keepAlways; add(source)
        XCUIDevice.shared.system.open(URL(string: "https://baseballmaster.cc/livestreaming/novideo/\(code)")!)
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari"); safari.activate()
        for title in ["Continue", "继续", "Not Now", "暂不"] {
            if safari.buttons[title].waitForExistence(timeout: 1) { safari.buttons[title].tap() }
        }
        if safari.otherElements["TipView"].exists { safari.buttons["xmark.circle.fill"].tap() }
        XCTAssertTrue(safari.staticTexts["成人慢垒"].waitForExistence(timeout: 15))
        v23Capture(safari, "AppStore23-05-慢垒公网观赛")
        safari.buttons["查看双方阵容"].tap()
        XCTAssertTrue(safari.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "自由人")).firstMatch.waitForExistence(timeout: 5))
        v23Capture(safari, "2.3 公网完整阵容与Free")
        app.activate(); open.tap()
        let close = app.buttons["close-live-broadcast"]
        revealStatisticsControl(close, in: app); close.tap(); app.buttons["关闭直播"].tap()
        XCTAssertTrue(app.buttons["start-live-broadcast"].waitForExistence(timeout: 10))
        let gone = expectation(description: "Slow-pitch test broadcast deleted")
        URLSession.shared.dataTask(with: URL(string: "https://baseballmaster.cc/livestreaming/novideo/api/sessions/\(code)")!) { _, response, _ in
            XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 404); gone.fulfill()
        }.resume()
        wait(for: [gone], timeout: 15)
    }

    @objc func testAppStore23SlowFullInningLineupIllegalAndReportScreenshots() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--v23-slow-preview"]
        app.launch()
        XCTAssertTrue(app.buttons["start-game-clock"].waitForExistence(timeout: 10))
        app.buttons["open-substitutions"].tap()
        app.buttons["edit-away-lineup"].tap()
        XCTAssertTrue(app.buttons["lineup-finish"].waitForExistence(timeout: 5))
        revealStatisticsControl(app.buttons["lineup-up-12"], in: app)
        v23Capture(app, "AppStore23-02-双方阵容与扩展打序")
        app.buttons["lineup-finish"].tap()
        app.buttons["返回比赛"].tap()
        app.buttons["start-game-clock"].tap()
        app.alerts.buttons["确认开赛"].tap()
        app.buttons["pitch-界外"].tap()
        app.buttons["pitch-界外"].tap()
        XCTAssertTrue(app.buttons["game-clock-display"].label.hasPrefix("剩余"))
        XCTAssertTrue(app.staticTexts["额外界外机会已用，下次界外出局"].waitForExistence(timeout: 5))
        v23Capture(app, "AppStore23-01-成人慢垒记分")
        app.buttons["record-illegal-pitch"].tap()
        v23Capture(app, "AppStore23-04-非法投球按裁判结果")
        app.buttons["界外，按本场界外规则处理"].tap()
        // Finish both halves through actual recorded outs, using the per-PA
        // initial strike count rather than fabricated score/statistic fields.
        for _ in 0..<5 {
            app.buttons["pitch-看振"].tap()
            app.buttons["pitch-看振"].tap()
        }
        XCTAssertTrue(app.buttons["pitch-坏球"].exists)
        app.buttons["结束比赛"].tap()
        app.buttons["finish-reason-记录员结束记录"].tap()
        app.buttons["确认保存"].tap()
        keepFinishedGameLocal(in: app)
        let result = app.buttons["open-box-score"]
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        result.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5))
        let pitcherStats = app.buttons["投手"]
        revealStatisticsControl(pitcherStats, in: app)
        XCTAssertTrue(pitcherStats.waitForExistence(timeout: 5))
        pitcherStats.tap()
        v23Capture(app, "AppStore23-07-完整比赛与战报")
        let report = app.buttons["share-box-score"]
        revealStatisticsControl(report, in: app)
        XCTAssertTrue(report.waitForExistence(timeout: 5))
        report.tap()
        XCTAssertTrue(app.navigationBars["比赛战报"].waitForExistence(timeout: 5))
        v23Capture(app, "AppStore23-07b-BoxScore战报预览")
    }

    @objc func testV23SmallDarkSlowScoringAndRosterKeepActionsReachable() {
        let app = XCUIApplication()
        app.launchArguments = ["--v23-slow-preview", "-appAppearance", "dark", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryXXXL"]
        app.launch()
        XCTAssertTrue(app.buttons["start-game-clock"].waitForExistence(timeout: 10))
        v23Capture(app, "2.3 小屏深色十人场图")
        app.buttons["start-game-clock"].tap(); app.alerts.buttons["确认开赛"].tap()
        revealStatisticsControl(app.buttons["open-substitutions"], in: app)
        app.buttons["open-substitutions"].tap(); app.buttons["edit-away-lineup"].tap()
        XCTAssertTrue(app.buttons["lineup-finish"].waitForExistence(timeout: 5))
        for _ in 0..<3 { app.swipeUp() }
        v23Capture(app, "2.3 小屏深色长打序")
        app.buttons["lineup-finish"].tap(); app.buttons["返回比赛"].tap()
        revealStatisticsControl(app.buttons["record-illegal-pitch"], in: app)
        XCTAssertTrue(app.buttons["record-illegal-pitch"].isHittable)
        app.buttons["record-illegal-pitch"].tap(); app.buttons["未实际投出的罚球，记一坏"].tap()
        v23Capture(app, "2.3 小屏深色非法投球")
    }
}

extension BaseballMasterUITests {
    func testV30BackfillControlsAndLineupMenuShowsJerseyNumbers() throws {
        let app = XCUIApplication(); app.launchArguments = ["--v23-new-game-preview"]; app.launch()
        let toggle = app.switches["backfill-game-toggle"]
        revealStatisticsControl(toggle, in: app); toggle.tap()
        XCTAssertTrue(app.datePickers["backfill-game-date"].exists)
        captureHistory(app, name: "3.0-赛前补填比赛时间")
        let next = app.buttons["continue-to-lineup"]
        revealStatisticsControl(next, in: app); next.tap()
        XCTAssertTrue(app.buttons["confirm-lineup"].waitForExistence(timeout: 5))
        let menu = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "lineup-player-menu-")).firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 5), app.debugDescription)
        revealStatisticsControl(menu, in: app); menu.tap()
        XCTAssertTrue(app.buttons["飞鹰球员10 · #10"].waitForExistence(timeout: 5), app.debugDescription)
        captureHistory(app, name: "3.0-阵容选人显示背号")
        app.buttons["飞鹰球员10 · #10"].tap()
        app.buttons["confirm-lineup"].tap()
        XCTAssertTrue(app.alerts.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "补填比赛")).firstMatch.exists)
        app.alerts.buttons["开始记录"].tap()
        XCTAssertTrue(app.buttons["start-game-clock"].waitForExistence(timeout: 5))
    }

    func testV30CompletedGameTimeEditorCancelAndSaveKeepFinalState() throws {
        let app = XCUIApplication(); app.launchArguments = ["--v30-date-preview"]; app.launch()
        XCTAssertTrue(app.staticTexts["result-game-date"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["result-game-date"].label.contains("2024-02-29 14:00"))
        app.buttons["game-result-more"].tap(); app.buttons["edit-game-date"].tap()
        XCTAssertTrue(app.datePickers["completed-game-date"].waitForExistence(timeout: 5))
        captureHistory(app, name: "3.0-赛后修改比赛时间")
        app.buttons["取消"].tap()
        XCTAssertTrue(app.staticTexts["result-game-date"].label.contains("2024-02-29 14:00"))
        app.buttons["game-result-more"].tap(); app.buttons["edit-game-date"].tap()
        app.buttons["save-completed-game-date"].tap()
        XCTAssertTrue(app.buttons["game-result-more"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["result-game-date"].label.contains("2024-02-29 14:00"))
    }
}

extension BaseballMasterUITests {
    func testV30PracticeFieldAndPitcherConfirmation() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--practice-scorekeeping-preview", "-appAppearance", "dark", "-showPracticeGuidance", "YES"]
        app.launch()
        XCTAssertTrue(app.buttons["pitch-坏球"].waitForExistence(timeout: 5))
        assertV30FieldIsContained(app)
        for id in ["pitch-坏球", "ball-in-play", "open-runner-events", "open-substitutions", "reset-practice-inning"] {
            XCTAssertTrue(app.buttons[id].isHittable, id)
        }
        captureHistory(app, name: "3.0-practice-field")
        app.buttons["pitch-坏球"].tap()
        if app.buttons["确认开赛"].waitForExistence(timeout: 2) { app.buttons["确认开赛"].tap() }
        app.buttons["open-substitutions"].tap()
        XCTAssertTrue(app.buttons["substitution-pitcher"].waitForExistence(timeout: 5))
        app.buttons["substitution-pitcher"].tap()
        let candidate = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "高博文")).firstMatch
        XCTAssertTrue(candidate.waitForExistence(timeout: 5)); candidate.tap()
        let confirmation = app.alerts["确认当前投手"]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 3))
        XCTAssertTrue(confirmation.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "赵一鸣")).firstMatch.exists)
        XCTAssertTrue(confirmation.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "高博文")).firstMatch.exists)
        XCTAssertFalse(app.buttons["pitch-坏球"].isHittable)
        captureHistory(app, name: "3.0-confirm-pitcher")
        app.buttons["cancel-pitcher-change"].firstMatch.tap()
        XCTAssertTrue(candidate.waitForExistence(timeout: 3))
        app.buttons["返回"].tap(); app.buttons["返回比赛"].tap()
        let pitcher = app.descendants(matching: .any)["current-pitcher"].firstMatch
        XCTAssertTrue(pitcher.label.contains("赵一鸣"))
        app.buttons["open-substitutions"].tap(); app.buttons["substitution-pitcher"].tap()
        candidate.tap(); app.buttons["confirm-current-pitcher"].firstMatch.tap()
        XCTAssertTrue(app.buttons["返回比赛"].waitForExistence(timeout: 3)); app.buttons["返回比赛"].tap()
        XCTAssertTrue(pitcher.label.contains("高博文"))
        XCTAssertTrue(pitcher.label.contains("本场已投 0 球"))
        app.buttons["pitch-坏球"].tap()
        XCTAssertTrue(pitcher.label.contains("本场已投 1 球"))
        app.terminate()
        app.launchArguments = ["--practice-scorekeeping-preview", "-appAppearance", "dark", "-showPracticeGuidance", "NO"]
        app.launch()
        XCTAssertTrue(app.buttons["pitch-坏球"].waitForExistence(timeout: 5))
        assertV30FieldIsContained(app)
        captureHistory(app, name: "3.0-practice-without-guidance")
    }

    private func assertV30FieldIsContained(_ app: XCUIApplication) {
        let field = app.descendants(matching: .any)["scorekeeping-field"].firstMatch
        if field.exists {
            for position in 1...9 {
                let fielder = app.descendants(matching: .any)["fielder-\(position)"].firstMatch
                XCTAssertTrue(fielder.exists, "Missing field position \(position)")
                XCTAssertTrue(field.frame.insetBy(dx: -1, dy: -1).contains(fielder.frame),
                              "Fielder \(position) clipped: \(fielder.frame), field: \(field.frame)")
            }
        } else {
            XCTAssertTrue(app.staticTexts["一垒"].exists)
        }
    }
}

extension BaseballMasterUITests {
    func testV30PitcherConfirmationExplainsAppearanceLimitBeforeZeroPitchEntry() {
        let app = XCUIApplication()
        app.launchArguments = ["--v111-final-inning-preview", "-appAppearance", "dark"]
        app.launch()
        let warning = app.buttons["game-rule-warning"]
        XCTAssertTrue(warning.waitForExistence(timeout: 5))
        XCTAssertEqual(warning.value as? String, "最后登板局")
        app.buttons["open-substitutions"].tap(); app.buttons["substitution-pitcher"].tap()
        let candidate = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "高博文")).firstMatch
        XCTAssertTrue(candidate.waitForExistence(timeout: 5)); candidate.tap()
        let confirmation = app.alerts["确认当前投手"]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 3))
        XCTAssertTrue(confirmation.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "本场限制 2 个登板局")).firstMatch.exists)
        XCTAssertTrue(confirmation.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "即使未投球")).firstMatch.exists)
        XCTAssertFalse(app.buttons["pitch-坏球"].isHittable)
        captureHistory(app, name: "3.0-appearance-confirmation")
        app.buttons["cancel-pitcher-change"].firstMatch.tap()
        app.buttons["返回"].tap(); app.buttons["返回比赛"].tap()
        let pitcher = app.descendants(matching: .any)["current-pitcher"].firstMatch
        XCTAssertTrue(pitcher.label.contains("赵一鸣"))
        XCTAssertEqual(warning.value as? String, "最后登板局")
        app.buttons["open-substitutions"].tap(); app.buttons["substitution-pitcher"].tap()
        candidate.tap(); app.buttons["confirm-current-pitcher"].firstMatch.tap()
        XCTAssertTrue(app.buttons["返回比赛"].waitForExistence(timeout: 3)); app.buttons["返回比赛"].tap()
        XCTAssertTrue(pitcher.label.contains("高博文"))
        XCTAssertTrue(pitcher.label.contains("本场已投 0 球"))
        XCTAssertTrue(warning.label.contains("已确认登板 1 / 2 局"))
        warning.tap()
        XCTAssertTrue(app.navigationBars["比赛规则提醒"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "已确认登板 1 / 2 局")).firstMatch.exists)
        captureHistory(app, name: "3.0-zero-pitch-appearance-usage")
    }

    func testV30LineupEditorConfirmsPitcherBeforeSavingAndReturning() {
        let app = XCUIApplication()
        app.launchArguments = ["--v11-navigation-preview"]
        app.launch()
        let match = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        XCTAssertTrue(match.waitForExistence(timeout: 5)); match.tap()
        app.buttons["pitch-坏球"].tap()
        app.buttons["open-substitutions"].tap(); app.buttons["edit-home-lineup"].tap()
        let oldPitcher = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "lineup-player-", "赵一鸣")).firstMatch
        XCTAssertTrue(oldPitcher.waitForExistence(timeout: 5)); oldPitcher.tap()
        app.buttons["调整守位"].tap(); app.buttons["捕手"].tap()
        app.buttons["lineup-save"].tap()
        XCTAssertTrue(app.alerts["确认当前投手"].waitForExistence(timeout: 3))
        app.buttons["cancel-pitcher-change"].firstMatch.tap()
        XCTAssertTrue(app.buttons["lineup-save"].isEnabled)
        app.buttons["lineup-finish"].tap()
        app.buttons["保存并返回"].tap()
        XCTAssertTrue(app.alerts["确认当前投手"].waitForExistence(timeout: 3))
        app.buttons["confirm-current-pitcher"].firstMatch.tap()
        XCTAssertTrue(app.buttons["edit-home-lineup"].waitForExistence(timeout: 5))
        app.buttons["返回比赛"].tap()
        let pitcher = app.descendants(matching: .any)["current-pitcher"].firstMatch
        XCTAssertTrue(pitcher.label.contains("高博文"))
        XCTAssertTrue(pitcher.label.contains("本场已投 0 球"))
    }
}

extension BaseballMasterUITests {
    @MainActor private func createSharedMatchUI(final: Bool = false, shareFromResult: Bool = false) async throws -> (XCUIApplication, String, String, String) {
        let app = XCUIApplication()
        app.launchArguments = ["--v30-sharing-preview", "--team-test-id=\(UUID().uuidString)", "--v30-match-fixture", "-appAppearance", "dark"] + (final ? ["--v30-match-final"] : [])
        app.launch()
        XCTAssertTrue(app.buttons["open-team-sharing"].waitForExistence(timeout: 5)); app.buttons["open-team-sharing"].tap()
        XCTAssertTrue(app.buttons["create-shared-team"].waitForExistence(timeout: 8)); app.buttons["create-shared-team"].tap()
        app.textFields["shared-team-name"].tap(); app.textFields["shared-team-name"].typeText("海浪棒球队")
        app.textFields["shared-nickname"].tap(); app.textFields["shared-nickname"].typeText("陈教练")
        if app.buttons["收起键盘"].exists { app.buttons["收起键盘"].tap() }
        revealStatisticsControl(app.buttons["confirm-sharing-scope"], in: app); app.buttons["confirm-sharing-scope"].tap()
        app.buttons["submit-shared-team"].firstMatch.tap()
        XCTAssertTrue(app.buttons["defer-recovery-card"].waitForExistence(timeout: 8)); app.buttons["defer-recovery-card"].tap()
        XCTAssertTrue(app.buttons["shared-team-海浪棒球队"].waitForExistence(timeout: 8)); app.buttons["shared-team-海浪棒球队"].tap()
        revealStatisticsControl(app.buttons["invite-team-member"], in: app); app.buttons["invite-team-member"].tap()
        let link = app.staticTexts["team-invitation-url"]; XCTAssertTrue(link.waitForExistence(timeout: 8))
        let token = try XCTUnwrap(URL(string: link.label)?.fragment), other = sharingTestSecret()
        _ = try await sharingAPI(other, "/devices", body: ["label": "另一台 iPhone 17 Pro"])
        let joined = try await sharingAPI(other, "/invitations/redeem", body: ["operationID": UUID().uuidString, "token": token, "nickname": "李记录员"])
        let teamID = try XCTUnwrap(joined["teamID"] as? String)
        app.buttons["完成"].firstMatch.tap()
        let manage = app.buttons["manage-member-李记录员"]; revealStatisticsControl(manage, in: app); manage.tap()
        app.buttons["设为记录员"].tap(); app.buttons["confirm-member-change"].firstMatch.tap()
        if shareFromResult {
            app.tabBars.buttons["比赛"].tap()
            let original = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "completed-game-")).firstMatch
            XCTAssertTrue(original.waitForExistence(timeout: 5)); revealStatisticsControl(original, in: app); original.tap()
            let destination = app.buttons["share-destination-picker"]; XCTAssertTrue(destination.waitForExistence(timeout: 5)); destination.tap()
            app.buttons["海浪棒球队 · 共享球队"].tap()
            let share = app.buttons["share-current-local-game"]; revealStatisticsControl(share, in: app); share.tap()
            let confirmation = app.alerts["是否上传这场已结束的比赛？"]
            XCTAssertTrue(confirmation.waitForExistence(timeout: 8))
            XCTAssertTrue(confirmation.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "本机原件永远保留")).firstMatch.exists)
            let beforeConfirmation = try await sharingAPI(other, "/teams/\(teamID)/games", method: "GET")
            XCTAssertEqual((beforeConfirmation["games"] as? [[String: Any]])?.count, 0, "Reviewing a local final must not upload it")
            captureHistory(app, name: "3.1.1-result-upload-confirmation")
            confirmation.buttons["暂不上传，保留本机"].tap()
            let afterCancel = try await sharingAPI(other, "/teams/\(teamID)/games", method: "GET")
            XCTAssertEqual((afterCancel["games"] as? [[String: Any]])?.count, 0, "Cancelling confirmation must keep the complete match local")
            share.tap()
            XCTAssertTrue(confirmation.waitForExistence(timeout: 8))
            confirmation.buttons["确认上传到球队"].tap()
            let uploaded = app.staticTexts["postgame-upload-status"]
            let acknowledged = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "完整记录已上传，本机原件保留"), object: uploaded)
            await fulfillment(of: [acknowledged], timeout: 12)
            let archive = app.buttons["查看球队已保存的档案"]
            revealStatisticsControl(archive, in: app); XCTAssertTrue(archive.waitForExistence(timeout: 5)); archive.tap()
        } else {
        revealStatisticsControl(app.buttons["open-shared-matches"], in: app); app.buttons["open-shared-matches"].tap(); XCTAssertTrue(app.buttons["publish-local-match"].waitForExistence(timeout: 5)); app.buttons["publish-local-match"].tap()
        app.buttons["select-match-to-share"].firstMatch.tap(); app.buttons["确认共享"].firstMatch.tap()
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "shared-match-")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10)); row.tap()
        }
        XCTAssertTrue(app.staticTexts["shared-match-status"].waitForExistence(timeout: 5))
        let page = try await sharingAPI(other, "/teams/\(teamID)/games", method: "GET")
        let game = try XCTUnwrap((page["games"] as? [[String: Any]])?.first), id = try XCTUnwrap(game["id"] as? String)
        return (app, other, teamID, id)
    }
    @MainActor func testV30SharedMatchNativeHandoffBothConfirmAndContinue() async throws {
        let (app, other, teamID, gameID) = try await createSharedMatchUI()
        let path = "/teams/\(teamID)/games/\(gameID)"
        captureHistory(app, name: "3.0-shared-match-synced")
        let handoff = app.buttons["propose-match-handoff"].firstMatch; revealStatisticsControl(handoff, in: app); handoff.tap()
        app.buttons["同步并发起交接"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["等待对方核对并确认。"].waitForExistence(timeout: 8))
        captureHistory(app, name: "3.0-match-handoff-waiting")
        let offered = try await sharingAPI(other, path, method: "GET"), h = try XCTUnwrap(offered["handoff"] as? [String: Any])
        let originalDevice = try XCTUnwrap(h["sourceDeviceID"] as? String)
        let accepted = try await sharingAPI(other, path + "/handoff/accept", body: ["operationID": UUID().uuidString, "handoffID": h["id"]!, "digest": offered["digest"]!, "baseRevision": offered["serverRevision"]!, "writerEpoch": offered["writerEpoch"]!])
        app.buttons["刷新"].firstMatch.tap()
        XCTAssertTrue(app.buttons["force-match-handoff"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.buttons["score-shared-match"].exists)
        _ = try await sharingAPI(other, path + "/handoff/propose", body: ["operationID": UUID().uuidString, "targetDeviceID": originalDevice, "baseRevision": accepted["serverRevision"]!, "writerEpoch": accepted["writerEpoch"]!])
        app.buttons["刷新"].firstMatch.tap()
        let accept = app.buttons["accept-match-handoff"]; XCTAssertTrue(accept.waitForExistence(timeout: 8)); captureHistory(app, name: "3.0-match-handoff-confirm"); accept.tap()
        let score = app.buttons["score-shared-match"]; XCTAssertTrue(score.waitForExistence(timeout: 8)); revealStatisticsControl(score, in: app); score.tap()
        XCTAssertTrue(app.buttons["pitch-坏球"].waitForExistence(timeout: 5)); app.buttons["pitch-坏球"].tap()
        XCTAssertTrue(app.buttons["match-sync-controls"].exists)
        captureHistory(app, name: "3.0-shared-scoring-original-layout")
        app.buttons["match-sync-controls"].tap()
        let syncEntry = app.buttons["open-match-sync-detail"].firstMatch
        XCTAssertTrue(syncEntry.waitForExistence(timeout: 5)); syncEntry.tap()
        XCTAssertTrue(app.navigationBars["共享比赛"].waitForExistence(timeout: 5))
    }
    @MainActor func testV30HistoricalAccessModesAndViewerInviteNative() async throws {
        let (app, other, teamID, gameID) = try await createSharedMatchUI(final: true)
        let mode = app.staticTexts["historical-access-mode"]
        revealStatisticsControl(mode, in: app); XCTAssertEqual(mode.label, "不开放")
        app.buttons["永久开放"].tap(); app.buttons["确认设为永久开放"].firstMatch.tap()
        XCTAssertTrue(app.buttons["分享历史回看"].waitForExistence(timeout: 8)); captureHistory(app, name: "3.0-history-public")
        app.buttons["仅指定人员"].tap(); app.buttons["确认设为仅指定人员"].firstMatch.tap()
        let invite = app.buttons["invite-history-viewer"]; revealStatisticsControl(invite, in: app); invite.tap()
        let url = app.staticTexts["history-viewer-invite-url"]; XCTAssertTrue(url.waitForExistence(timeout: 8))
        XCTAssertTrue(url.label.contains("baseballmaster.cc/watch/#")); captureHistory(app, name: "3.0-history-restricted-invitation")
        let close = app.buttons["不开放"]; revealStatisticsControl(close, in: app); close.tap(); app.buttons["确认设为不开放"].firstMatch.tap()
        XCTAssertTrue(mode.waitForExistence(timeout: 8))
        let remote = try await sharingAPI(other, "/teams/\(teamID)/games/\(gameID)", method: "GET")
        XCTAssertEqual(remote["accessMode"] as? String, "closed"); XCTAssertNotNil(remote["payload"])
        // This summary row is above the long permission section and may be recycled by List.
        for _ in 0..<5 { app.swipeDown() }
        let result = app.buttons["view-shared-match-result"]; revealStatisticsControl(result, in: app); result.tap()
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["open-history-correction"].exists); XCTAssertFalse(app.buttons["reopen-game"].exists)
        captureHistory(app, name: "3.0-shared-history-readonly-result")
    }
}

extension BaseballMasterUITests {
    @MainActor private func launchDeviceSecurityApp() -> XCUIApplication {
        let app = XCUIApplication(); app.launchArguments = ["--v30-sharing-preview", "--team-test-id=\(UUID().uuidString)", "-appAppearance", "dark"]
        app.launch(); XCTAssertTrue(app.buttons["open-team-sharing"].waitForExistence(timeout: 5)); app.buttons["open-team-sharing"].tap()
        XCTAssertTrue(app.buttons["create-shared-team"].waitForExistence(timeout: 5)); return app
    }
    @MainActor func testV30DevicePairingApprovalAndSingleDeviceRemovalNative() async throws {
        let app = launchDeviceSecurityApp(); app.buttons["create-shared-team"].tap()
        app.textFields["shared-team-name"].tap(); app.textFields["shared-team-name"].typeText("配对验收队")
        app.textFields["shared-nickname"].tap(); app.textFields["shared-nickname"].typeText("陈教练")
        let scope = app.buttons["confirm-sharing-scope"]; revealStatisticsControl(scope, in: app); scope.tap(); app.buttons["submit-shared-team"].firstMatch.tap()
        let show = app.buttons["show-recovery-card"]; XCTAssertTrue(show.waitForExistence(timeout: 8)); show.tap()
        let code = app.staticTexts["recovery-card-code"]; XCTAssertTrue(code.waitForExistence(timeout: 5))
        let teamID = try XCTUnwrap(code.label.split(separator: ".").dropFirst().first).description
        captureHistory(app, name: "3.0-created-recovery-card")
        let later = app.buttons["defer-recovery-card"]; revealStatisticsControl(later, in: app); later.tap()
        app.buttons["shared-team-配对验收队"].tap()
        let devices = app.buttons["open-team-devices"]; revealStatisticsControl(devices, in: app); devices.tap()
        app.buttons["open-pairing-approval"].tap()
        let credential = sharingTestSecret(), token = sharingTestSecret()
        _ = try await sharingAPI(credential, "/devices", body: ["label": "配对的新 iPhone"])
        let pairing = try await sharingAPI(credential, "/pairings", body: ["operationID": UUID().uuidString, "token": token])
        let input = app.textFields["pairing-request-input"]; input.tap(); input.typeText("https://baseballmaster.cc/pair#" + token)
        let preview = app.buttons["preview-device-pairing"]; revealStatisticsControl(preview, in: app); preview.tap()
        let phrase = app.staticTexts["pairing-preview-phrase"]; XCTAssertTrue(phrase.waitForExistence(timeout: 6)); XCTAssertEqual(phrase.label, pairing["phrase"] as? String)
        let approve = app.buttons["approve-device-pairing"]; XCTAssertFalse(approve.isEnabled)
        let checked = app.switches["pairing-phrase-checked"]; revealStatisticsControl(checked, in: app); checked.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap(); XCTAssertTrue(approve.isEnabled)
        revealStatisticsControl(approve, in: app); captureHistory(app, name: "3.0-approve-new-device"); approve.tap()
        XCTAssertTrue(app.staticTexts["pairing-approved"].waitForExistence(timeout: 8))
        let id = try XCTUnwrap(pairing["id"] as? String), offered = try await sharingAPI(credential, "/pairings/\(id)", method: "GET")
        _ = try await sharingAPI(credential, "/pairings/\(id)/accept", body: ["operationID": UUID().uuidString, "phrase": try XCTUnwrap(offered["phrase"]), "memberRevision": try XCTUnwrap(offered["memberRevision"])])
        app.navigationBars["授权我的新设备"].buttons.element(boundBy: 0).tap()
        app.buttons["刷新"].tap()
        let remove = app.buttons["remove-device-配对的新 iPhone"]; XCTAssertTrue(remove.waitForExistence(timeout: 6)); captureHistory(app, name: "3.0-paired-device-list")
        revealStatisticsControl(remove, in: app); remove.tap(); app.buttons["confirm-remove-device"].firstMatch.tap()
        XCTAssertTrue(app.buttons["remove-device-iPhone"].waitForExistence(timeout: 8))
        XCTAssertFalse(remove.exists)
        let teams = try await sharingAPI(credential, "/teams", method: "GET")["teams"] as? [[String: Any]]
        XCTAssertFalse(teams?.contains { $0["id"] as? String == teamID } == true)
    }
    @MainActor func testV30NewDevicePairingAcceptNative() async throws {
        let source = sharingTestSecret(); _ = try await sharingAPI(source, "/devices", body: ["label": "原手机"])
        let team = try await sharingAPI(source, "/teams", body: ["operationID": UUID().uuidString, "name": "新手机接入队", "nickname": "李教练", "recoveryToken": sharingTestSecret()])
        let teamID = try XCTUnwrap(team["teamID"] as? String)
        let app = launchDeviceSecurityApp(); app.buttons["open-new-device-pairing"].tap()
        let create = app.buttons["create-device-pairing"]; XCTAssertTrue(create.waitForExistence(timeout: 5)); create.tap()
        let url = app.staticTexts["pairing-request-url"]; XCTAssertTrue(url.waitForExistence(timeout: 8))
        let token = try XCTUnwrap(URL(string: url.label)?.fragment)
        captureHistory(app, name: "3.0-new-device-request")
        let preview = try await sharingAPI(source, "/teams/\(teamID)/pairings/preview", body: ["token": token])
        XCTAssertEqual(app.staticTexts["pairing-phrase"].label, preview["phrase"] as? String)
        let pairID = try XCTUnwrap(preview["id"] as? String)
        _ = try await sharingAPI(source, "/teams/\(teamID)/pairings/\(pairID)/approve", body: ["operationID": UUID().uuidString, "token": token, "phrase": preview["phrase"]!, "memberRevision": preview["memberRevision"]!])
        app.buttons["刷新"].tap()
        let accept = app.buttons["accept-device-pairing"]; XCTAssertTrue(accept.waitForExistence(timeout: 8)); revealStatisticsControl(accept, in: app); accept.tap()
        XCTAssertTrue(app.staticTexts["pairing-completed"].waitForExistence(timeout: 8)); captureHistory(app, name: "3.0-new-device-paired")
        let members = try await sharingAPI(source, "/teams/\(teamID)/members", method: "GET")["members"] as? [[String: Any]]; XCTAssertEqual(members?.count, 1)
    }
    @MainActor func testV30ManagementRecoveryAndRotationNative() async throws {
        let old = sharingTestSecret(), oldToken = sharingTestSecret(); _ = try await sharingAPI(old, "/devices", body: ["label": "丢失的管理手机"])
        let team = try await sharingAPI(old, "/teams", body: ["operationID": UUID().uuidString, "name": "恢复管理验收队", "nickname": "旧教练", "recoveryToken": oldToken])
        let teamID = try XCTUnwrap(team["teamID"] as? String), oldCard = "BMREC1.\(teamID).\(oldToken)"
        let app = launchDeviceSecurityApp(); app.buttons["open-management-recovery"].tap()
        let input = app.secureTextFields["management-recovery-input"]; XCTAssertTrue(input.waitForExistence(timeout: 5)); input.tap(); input.typeText(oldCard)
        let name = app.textFields["recovery-nickname"]; name.tap(); name.typeText("恢复后的教练")
        let preview = app.buttons["preview-management-recovery"]; revealStatisticsControl(preview, in: app); preview.tap()
        let recover = app.buttons["recover-management"]; XCTAssertTrue(recover.waitForExistence(timeout: 6)); revealStatisticsControl(recover, in: app)
        captureHistory(app, name: "3.0-recovery-impact-confirmation")
        recover.tap(); app.alerts.buttons["取消"].firstMatch.tap()
        let oldTeams = try await sharingAPI(old, "/teams", method: "GET")["teams"] as? [[String: Any]]; XCTAssertEqual(oldTeams?.count, 1)
        recover.tap(); app.buttons["confirm-management-recovery"].firstMatch.tap()
        let show = app.buttons["show-recovery-card"]; XCTAssertTrue(show.waitForExistence(timeout: 8)); show.tap()
        let card = app.staticTexts["recovery-card-code"]; XCTAssertTrue(card.waitForExistence(timeout: 5)); let newCard = card.label
        XCTAssertNotEqual(newCard, oldCard); XCTAssertTrue(newCard.contains(teamID))
        captureHistory(app, name: "3.0-recovered-new-card")
        let after = try await sharingAPI(old, "/teams", method: "GET")["teams"] as? [[String: Any]]; XCTAssertTrue(after?.isEmpty == true)
        let saved = app.buttons["confirm-recovery-saved"]; revealStatisticsControl(saved, in: app); saved.tap()
        XCTAssertEqual(app.staticTexts["recovery-card-status"].label, "已标记保存")
        let rotate = app.buttons["rotate-recovery-code"]; revealStatisticsControl(rotate, in: app); rotate.tap(); app.buttons["确认更换并保存新卡"].firstMatch.tap()
        XCTAssertTrue(card.waitForExistence(timeout: 8)); XCTAssertNotEqual(card.label, newCard)
    }
}

extension BaseballMasterUITests {
    @MainActor func testV30SharedRosterCreateEditArchiveRestoreAndAnnualNavigation() async throws {
        let app = launchDeviceSecurityApp(); app.buttons["create-shared-team"].tap()
        app.textFields["shared-team-name"].tap(); app.textFields["shared-team-name"].typeText("海浪棒球队")
        app.textFields["shared-nickname"].tap(); app.textFields["shared-nickname"].typeText("陈教练")
        revealStatisticsControl(app.buttons["confirm-sharing-scope"], in: app); app.buttons["confirm-sharing-scope"].tap()
        app.buttons["submit-shared-team"].firstMatch.tap()
        XCTAssertTrue(app.buttons["defer-recovery-card"].waitForExistence(timeout: 8)); app.buttons["defer-recovery-card"].tap()
        app.buttons["shared-team-海浪棒球队"].tap()
        let add = app.buttons["add-shared-player"]; XCTAssertTrue(add.waitForExistence(timeout: 5)); revealStatisticsControl(add, in: app); add.tap()
        let name = app.textFields["roster-name"]; XCTAssertTrue(name.waitForExistence(timeout: 5)); name.tap(); name.typeText("陈可匀")
        app.textFields["roster-numbers"].tap(); app.textFields["roster-numbers"].typeText("00 12")
        let save = app.buttons["save-shared-roster"]; revealStatisticsControl(save, in: app); captureHistory(app, name: "3.0-roster-player-editor"); save.tap()
        let menu = app.buttons["edit-shared-player-陈可匀"]; XCTAssertTrue(menu.waitForExistence(timeout: 8)); revealStatisticsControl(menu, in: app)
        XCTAssertTrue(app.staticTexts["#00 / #12"].exists); captureHistory(app, name: "3.0-shared-roster-active")
        menu.tap(); app.buttons["停用球员"].tap(); app.alerts.buttons["确认"].tap()
        XCTAssertTrue(app.staticTexts["已停用球员"].waitForExistence(timeout: 8)); captureHistory(app, name: "3.0-shared-roster-archived")
        revealStatisticsControl(menu, in: app); menu.tap(); app.buttons["恢复现役"].tap(); app.alerts.buttons["确认"].tap()
        XCTAssertTrue(menu.waitForExistence(timeout: 8)); XCTAssertFalse(app.staticTexts["已停用球员"].exists)
        revealStatisticsControl(menu, in: app); menu.tap(); app.buttons["编辑资料"].tap()
        XCTAssertTrue(name.waitForExistence(timeout: 5)); name.tap(); name.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 3) + "陈可均")
        revealStatisticsControl(save, in: app); save.tap()
        XCTAssertTrue(app.buttons["edit-shared-player-陈可均"].waitForExistence(timeout: 8))
        let years = app.buttons["shared-annual-statistics"]; revealStatisticsControl(years, in: app); years.tap()
        XCTAssertTrue(app.buttons["stats-season-picker"].waitForExistence(timeout: 8)); XCTAssertTrue(app.buttons["stats-season-picker"].label.contains("年度"))
        captureHistory(app, name: "3.0-shared-annual-statistics")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let matches = app.buttons["open-shared-matches"]; revealStatisticsControl(matches, in: app); matches.tap()
        XCTAssertTrue(app.buttons["shared-year-picker"].waitForExistence(timeout: 8)); captureHistory(app, name: "3.0-shared-match-year-filter")
    }
}

extension BaseballMasterUITests {
    @MainActor func testV30RosterConflictRefreshPreservesTypedPlayerAndIdentity() async throws {
        let admin = sharingTestSecret(), invitation = sharingTestSecret()
        _ = try await sharingAPI(admin, "/devices", body: ["label": "另一台管理设备"])
        let created = try await sharingAPI(admin, "/teams", body: ["operationID": UUID().uuidString, "name": "冲突核对队", "nickname": "原管理员"])
        let teamID = try XCTUnwrap(created["teamID"] as? String)
        _ = try await sharingAPI(admin, "/teams/\(teamID)/invitations", body: ["operationID": UUID().uuidString, "token": invitation, "role": "scorer"])
        let app = launchDeviceSecurityApp(); app.buttons["join-shared-team"].tap()
        app.textFields["team-invite-link"].tap(); app.textFields["team-invite-link"].typeText("https://baseballmaster.cc/join#\(invitation)")
        app.buttons["preview-team-invite"].tap()
        XCTAssertTrue(app.textFields["join-nickname"].waitForExistence(timeout: 8)); app.textFields["join-nickname"].tap(); app.textFields["join-nickname"].typeText("名单记录员")
        revealStatisticsControl(app.buttons["confirm-join-team"], in: app); app.buttons["confirm-join-team"].tap()
        let teamButton = app.buttons["shared-team-冲突核对队"]; XCTAssertTrue(teamButton.waitForExistence(timeout: 8))
        teamButton.tap(); XCTAssertTrue(app.buttons["add-shared-player"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.buttons["edit-shared-profile"].exists)
        XCTAssertFalse(app.buttons["invite-team-member"].exists)
        app.buttons["add-shared-player"].tap()
        let name = app.textFields["roster-name"]; name.tap(); name.typeText("保留的输入")
        app.textFields["roster-numbers"].tap(); app.textFields["roster-numbers"].typeText("00")
        if app.buttons["收起键盘"].exists { app.buttons["收起键盘"].tap() }
        let current = try await sharingAPI(admin, "/teams/\(teamID)", method: "GET")
        let revision = try XCTUnwrap((current["team"] as? [String: Any])?["revision"])
        _ = try await sharingAPI(admin, "/teams/\(teamID)/roster", method: "PATCH", body: ["operationID": UUID().uuidString, "baseRevision": revision, "kind": "profile", "name": "冲突核对队", "shortName": "云端简称", "city": "上海"])
        let save = app.buttons["save-shared-roster"]; revealStatisticsControl(save, in: app); save.tap()
        let refresh = app.buttons["refresh-roster-conflict"]; XCTAssertTrue(refresh.waitForExistence(timeout: 8))
        revealStatisticsControl(refresh, in: app); refresh.tap()
        XCTAssertEqual(name.value as? String, "保留的输入"); XCTAssertEqual(app.textFields["roster-numbers"].value as? String, "00")
        captureHistory(app, name: "3.0-roster-conflict-review")
        revealStatisticsControl(save, in: app); save.tap()
        XCTAssertTrue(app.buttons["edit-shared-player-保留的输入"].waitForExistence(timeout: 8))
        let detail = try await sharingAPI(admin, "/teams/\(teamID)", method: "GET")
        let profile = try XCTUnwrap(detail["team"] as? [String: Any])
        XCTAssertEqual(profile["shortName"] as? String, "云端简称")
        let players = try XCTUnwrap(profile["players"] as? [[String: Any]])
        XCTAssertEqual(players.count, 1); XCTAssertEqual(players.first?["chineseName"] as? String, "保留的输入")
        app.buttons["edit-shared-player-保留的输入"].tap()
        XCTAssertTrue(app.buttons["编辑资料"].waitForExistence(timeout: 3))
        XCTAssertFalse(app.buttons["停用球员"].exists)
        app.buttons["编辑资料"].tap()
        let editName = app.textFields["roster-name"]
        editName.tap(); editName.typeText("已修改")
        if app.buttons["收起键盘"].exists { app.buttons["收起键盘"].tap() }
        revealStatisticsControl(app.buttons["save-shared-roster"], in: app); app.buttons["save-shared-roster"].tap()
        XCTAssertTrue(app.buttons["edit-shared-player-保留的输入已修改"].waitForExistence(timeout: 8))
        captureHistory(app, name: "3.0-scorer-player-edit")
        let third = sharingTestSecret(), thirdInvitation = sharingTestSecret()
        _ = try await sharingAPI(third, "/devices", body: ["label": "第三位设备"])
        _ = try await sharingAPI(admin, "/teams/\(teamID)/invitations", body: ["operationID": UUID().uuidString, "token": thirdInvitation, "role": "scorer"])
        _ = try await sharingAPI(third, "/invitations/redeem", body: ["operationID": UUID().uuidString, "token": thirdInvitation, "nickname": "第三位记录员"])
        let list = try await sharingAPI(admin, "/teams/\(teamID)/members", method: "GET")
        let member = try XCTUnwrap((list["members"] as? [[String: Any]])?.first { $0["nickname"] as? String == "名单记录员" })
        _ = try await sharingAPI(admin, "/teams/\(teamID)/members/\(try XCTUnwrap(member["id"] as? String))", method: "PATCH", body: ["operationID": UUID().uuidString, "baseRevision": try XCTUnwrap(member["revision"]), "role": "admin"])
        app.buttons["刷新"].tap()
        let limit = app.staticTexts["team-admin-limit"]
        XCTAssertTrue(limit.waitForExistence(timeout: 8)); revealStatisticsControl(limit, in: app)
        XCTAssertTrue(limit.label.contains("2 / 3"))
        captureHistory(app, name: "3.0.1-two-of-three-administrators")
        let thirdMenu = app.buttons["manage-member-第三位记录员"]
        revealStatisticsControl(thirdMenu, in: app); thirdMenu.tap()
        XCTAssertTrue(app.buttons["设为管理员"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["设为管理员"].isEnabled)
        app.buttons["设为管理员"].tap(); app.buttons["confirm-member-change"].firstMatch.tap()
        let full = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", "3 / 3"), object: limit)
        await fulfillment(of: [full], timeout: 8)
        let fourth = sharingTestSecret(), fourthInvitation = sharingTestSecret()
        _ = try await sharingAPI(fourth, "/devices", body: ["label": "第四位设备"])
        _ = try await sharingAPI(admin, "/teams/\(teamID)/invitations", body: ["operationID": UUID().uuidString, "token": fourthInvitation, "role": "scorer"])
        _ = try await sharingAPI(fourth, "/invitations/redeem", body: ["operationID": UUID().uuidString, "token": fourthInvitation, "nickname": "第四位记录员"])
        app.buttons["刷新"].tap()
        let fourthMenu = app.buttons["manage-member-第四位记录员"]; revealStatisticsControl(fourthMenu, in: app); fourthMenu.tap()
        XCTAssertTrue(app.buttons["设为管理员"].waitForExistence(timeout: 3))
        XCTAssertFalse(app.buttons["设为管理员"].isEnabled)
        captureHistory(app, name: "3.0.1-three-administrator-limit")
    }
}

extension BaseballMasterUITests {
    @MainActor func testV30HistoricalImportPreviewCancelUploadAndRepeatSkipKeepLocalOriginal() async throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--v30-sharing-preview", "--team-test-id=\(UUID().uuidString)", "--v30-match-fixture", "--v30-match-final", "-appAppearance", "dark"]
        app.launch()
        XCTAssertTrue(app.buttons["open-team-sharing"].waitForExistence(timeout: 5)); app.buttons["open-team-sharing"].tap()
        XCTAssertTrue(app.buttons["create-shared-team"].waitForExistence(timeout: 8)); app.buttons["create-shared-team"].tap()
        app.textFields["shared-team-name"].tap(); app.textFields["shared-team-name"].typeText("海浪棒球队")
        app.textFields["shared-nickname"].tap(); app.textFields["shared-nickname"].typeText("陈教练")
        if app.buttons["收起键盘"].exists { app.buttons["收起键盘"].tap() }
        revealStatisticsControl(app.buttons["confirm-sharing-scope"], in: app); app.buttons["confirm-sharing-scope"].tap()
        app.buttons["submit-shared-team"].firstMatch.tap()
        XCTAssertTrue(app.buttons["defer-recovery-card"].waitForExistence(timeout: 8)); app.buttons["defer-recovery-card"].tap()
        XCTAssertTrue(app.buttons["shared-team-海浪棒球队"].waitForExistence(timeout: 8)); app.buttons["shared-team-海浪棒球队"].tap()
        revealStatisticsControl(app.buttons["open-shared-matches"], in: app); app.buttons["open-shared-matches"].tap()
        XCTAssertTrue(app.buttons["import-history"].waitForExistence(timeout: 5)); app.buttons["import-history"].tap()
        func selectAndPreview() {
            let choose = app.switches.matching(NSPredicate(format: "identifier BEGINSWITH %@", "import-select-")).firstMatch
            XCTAssertTrue(choose.waitForExistence(timeout: 8)); revealStatisticsControl(choose, in: app)
            choose.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
            XCTAssertEqual(choose.value as? String, "1")
            captureHistory(app, name: "3.0-history-import-selection")
            let preview = app.buttons["import-preview"]; revealStatisticsControl(preview, in: app); XCTAssertTrue(preview.isEnabled); preview.tap()
            XCTAssertTrue(app.staticTexts["import-progress"].waitForExistence(timeout: 8))
        }
        selectAndPreview()
        let confirm = app.buttons["import-confirm"]; revealStatisticsControl(confirm, in: app)
        XCTAssertTrue(confirm.isEnabled); captureHistory(app, name: "3.0-history-import-preview")
        confirm.tap(); XCTAssertTrue(app.alerts["确认导入这些历史比赛？"].waitForExistence(timeout: 3))
        app.buttons["返回核对"].tap()
        XCTAssertTrue(app.staticTexts["import-progress"].label.contains("已导入 0 场"))
        confirm.tap(); app.buttons["备份并开始导入"].tap()
        let finished = NSPredicate(format: "label CONTAINS %@", "已导入 1 场")
        let completion = XCTNSPredicateExpectation(predicate: finished, object: app.staticTexts["import-progress"])
        await fulfillment(of: [completion], timeout: 12)
        captureHistory(app, name: "3.0-history-import-completed")
        let another = app.buttons["import-end-batch"]; revealStatisticsControl(another, in: app); another.tap()
        selectAndPreview()
        let duplicate = app.staticTexts["这场已在队内，跳过重复记录"]
        revealStatisticsControl(duplicate, in: app); XCTAssertTrue(duplicate.exists)
        captureHistory(app, name: "3.0-history-import-duplicate")
        for _ in 0..<15 {
            if confirm.exists && confirm.isHittable && confirm.frame.minY > 150 { break }
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)).press(forDuration: 0.05,
                thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7)))
        }
        XCTAssertTrue(confirm.isHittable); confirm.tap(); app.buttons["备份并开始导入"].tap()
        let skipped = app.staticTexts["import-progress"]
        let check = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", "已跳过 1 场"), object: skipped)
        await fulfillment(of: [check], timeout: 10)
        app.buttons["关闭"].firstMatch.tap()
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "shared-match-")).count, 1)
    }
}

extension BaseballMasterUITests {
    @MainActor func testV30PlayerAssociationConfirmCancelUnlinkAndReadOnlyHistory() async throws {
        continueAfterFailure = false
        let admin = sharingTestSecret(), invite = sharingTestSecret(), target = UUID().uuidString.lowercased()
        _ = try await sharingAPI(admin, "/devices", body: ["label": "教练的 iPhone"])
        let created = try await sharingAPI(admin, "/teams", body: ["operationID": UUID().uuidString, "name": "海浪棒球队", "nickname": "陈教练", "profile": ["shortName": "海浪", "city": "", "players": [["sourceID": target, "chineseName": "正式陈昊", "englishName": "", "numbers": ["00"]]]]])
        let teamID = try XCTUnwrap(created["teamID"] as? String)
        _ = try await sharingAPI(admin, "/teams/\(teamID)/invitations", body: ["operationID": UUID().uuidString, "token": invite, "role": "scorer"])
        let app = XCUIApplication()
        app.launchArguments = ["--v30-sharing-preview", "--team-test-id=\(UUID().uuidString)", "--v30-match-fixture", "--v30-match-final", "-appAppearance", "dark"]
        app.launch(); app.buttons["open-team-sharing"].tap()
        XCTAssertTrue(app.buttons["join-shared-team"].waitForExistence(timeout: 8)); app.buttons["join-shared-team"].tap()
        app.textFields["team-invite-link"].tap(); app.textFields["team-invite-link"].typeText("https://baseballmaster.cc/join#\(invite)")
        app.buttons["preview-team-invite"].tap()
        XCTAssertTrue(app.textFields["join-nickname"].waitForExistence(timeout: 8)); app.textFields["join-nickname"].tap(); app.textFields["join-nickname"].typeText("李教练")
        revealStatisticsControl(app.buttons["confirm-join-team"], in: app); app.buttons["confirm-join-team"].tap()
        let team = app.buttons["shared-team-海浪棒球队"]; XCTAssertTrue(team.waitForExistence(timeout: 8))
        let members = try await sharingAPI(admin, "/teams/\(teamID)/members", method: "GET")
        let member = try XCTUnwrap((members["members"] as? [[String: Any]])?.first { $0["nickname"] as? String == "李教练" })
        let memberID = try XCTUnwrap(member["id"] as? String)
        _ = try await sharingAPI(admin, "/teams/\(teamID)/members/\(memberID)", method: "PATCH", body: ["operationID": UUID().uuidString, "baseRevision": try XCTUnwrap(member["revision"]), "role": "admin"])
        team.tap(); revealStatisticsControl(app.buttons["open-shared-matches"], in: app); app.buttons["open-shared-matches"].tap()
        app.buttons["publish-local-match"].tap(); app.buttons["select-match-to-share"].firstMatch.tap(); app.buttons["确认共享"].firstMatch.tap()
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "shared-match-")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10)); row.tap()
        let entry = app.buttons["open-match-player-links"]; revealStatisticsControl(entry, in: app); entry.tap()
        let picker = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "player-link-")).firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 8)); captureHistory(app, name: "3.0-player-association-selection")
        let pickerID = picker.identifier
        picker.tap(); app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "正式陈昊")).firstMatch.tap()
        let save = app.buttons["save-player-links"]; revealStatisticsControl(save, in: app)
        XCTAssertTrue(save.isEnabled); captureHistory(app, name: "3.0-player-association-review"); save.tap()
        app.alerts.buttons["返回核对"].tap(); XCTAssertTrue(save.isEnabled)
        save.tap(); app.alerts.buttons["确认保存"].tap()
        let saved = app.staticTexts["player-links-saved"]; revealStatisticsControl(saved, in: app); XCTAssertTrue(saved.waitForExistence(timeout: 10))
        let page = try await sharingAPI(admin, "/teams/\(teamID)/games", method: "GET")
        let gameID = try XCTUnwrap(((page["games"] as? [[String: Any]])?.first)?["id"] as? String)
        let path = "/teams/\(teamID)/games/\(gameID)"
        var game = try await sharingAPI(admin, path, method: "GET")
        XCTAssertEqual((game["playerLinks"] as? [[String: Any]])?.first?["targetID"] as? String, target)
        for _ in 0..<8 { if app.buttons[pickerID].isHittable && app.buttons[pickerID].frame.minY > 100 { break }; app.swipeDown() }
        captureHistory(app, name: "3.0-player-association-saved")
        app.buttons[pickerID].tap(); app.buttons["暂不关联"].firstMatch.tap()
        revealStatisticsControl(save, in: app); save.tap(); app.alerts.buttons["确认保存"].tap()
        revealStatisticsControl(saved, in: app); XCTAssertTrue(saved.waitForExistence(timeout: 10))
        game = try await sharingAPI(admin, path, method: "GET"); XCTAssertEqual((game["playerLinks"] as? [[String: Any]])?.count, 0)
        let history = try await sharingAPI(admin, path + "/player-links", method: "GET"); XCTAssertEqual((history["changes"] as? [[String: Any]])?.count, 2)
        // Downgrade while the screen is open: refresh and reopening must expose history without editing.
        _ = try await sharingAPI(admin, "/teams/\(teamID)/members/\(memberID)", method: "PATCH", body: ["operationID": UUID().uuidString, "baseRevision": 2, "role": "member"])
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["刷新"].firstMatch.tap(); XCTAssertTrue(team.waitForExistence(timeout: 8)); team.tap()
        revealStatisticsControl(app.buttons["open-shared-matches"], in: app); app.buttons["open-shared-matches"].tap()
        XCTAssertTrue(row.waitForExistence(timeout: 8)); row.tap(); revealStatisticsControl(entry, in: app); entry.tap()
        XCTAssertTrue(app.staticTexts["管理员可修改对应关系。"].waitForExistence(timeout: 8))
        XCTAssertFalse(save.exists); captureHistory(app, name: "3.0-player-association-readonly")
    }
}

extension BaseballMasterUITests {
    @MainActor func testV30AdminDraftReviewAuditDissolveCancelAndRestore() async throws {
        continueAfterFailure = false
        let admin = sharingTestSecret(), invite = sharingTestSecret()
        _ = try await sharingAPI(admin, "/devices", body: ["label": "陈教练的 iPhone"])
        let created = try await sharingAPI(admin, "/teams", body: ["operationID": UUID().uuidString, "name": "海浪", "nickname": "陈教练"])
        let teamID = try XCTUnwrap(created["teamID"] as? String)
        _ = try await sharingAPI(admin, "/teams/\(teamID)/invitations", body: ["operationID": UUID().uuidString, "token": invite, "role": "scorer"])
        let app = XCUIApplication()
        app.launchArguments = ["--v30-sharing-preview", "--team-test-id=\(UUID().uuidString)", "--v30-match-fixture", "--v30-match-final", "-appAppearance", "dark"]
        app.launch(); app.buttons["open-team-sharing"].tap()
        XCTAssertTrue(app.buttons["join-shared-team"].waitForExistence(timeout: 8)); app.buttons["join-shared-team"].tap()
        app.textFields["team-invite-link"].tap(); app.textFields["team-invite-link"].typeText("https://baseballmaster.cc/join#\(invite)")
        app.buttons["preview-team-invite"].tap()
        XCTAssertTrue(app.textFields["join-nickname"].waitForExistence(timeout: 8)); app.textFields["join-nickname"].tap(); app.textFields["join-nickname"].typeText("李教练")
        revealStatisticsControl(app.buttons["confirm-join-team"], in: app); app.buttons["confirm-join-team"].tap()
        let teamButton = app.buttons["shared-team-海浪"]; XCTAssertTrue(teamButton.waitForExistence(timeout: 8))
        let members = try await sharingAPI(admin, "/teams/\(teamID)/members", method: "GET")
        let member = try XCTUnwrap((members["members"] as? [[String: Any]])?.first { $0["nickname"] as? String == "李教练" })
        _ = try await sharingAPI(admin, "/teams/\(teamID)/members/\(try XCTUnwrap(member["id"] as? String))", method: "PATCH", body: ["operationID": UUID().uuidString, "baseRevision": try XCTUnwrap(member["revision"]), "role": "admin"])
        teamButton.tap(); revealStatisticsControl(app.buttons["open-shared-matches"], in: app); app.buttons["open-shared-matches"].tap()
        app.buttons["publish-local-match"].tap(); app.buttons["select-match-to-share"].firstMatch.tap(); app.buttons["确认共享"].firstMatch.tap()
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "shared-match-")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        let games = try await sharingAPI(admin, "/teams/\(teamID)/games", method: "GET")
        let gameID = try XCTUnwrap(((games["games"] as? [[String: Any]])?.first)?["id"] as? String)
        let path = "/teams/\(teamID)/games/\(gameID)"
        let game = try await sharingAPI(admin, path, method: "GET")
        let projection = try XCTUnwrap(game["projection"] as? [String: Any])
        let submitted = try await sharingAPI(admin, path + "/drafts", body: ["operationID": UUID().uuidString, "conflictID": UUID().uuidString, "reason": "旧手机断网后保留的记录", "formatVersion": 1, "payload": try XCTUnwrap(game["payload"]), "projection": try XCTUnwrap(projection["snapshot"])])
        let draftID = try XCTUnwrap(submitted["draftID"] as? String)
        row.tap(); let entry = app.buttons["open-team-conflicts"]; revealStatisticsControl(entry, in: app); entry.tap()
        let draftRow = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "team-conflict-")).firstMatch
        XCTAssertTrue(draftRow.waitForExistence(timeout: 8)); captureHistory(app, name: "3.0-admin-draft-list"); draftRow.tap()
        let note = app.textFields["conflict-resolution-note"]; revealStatisticsControl(note, in: app); XCTAssertTrue(note.waitForExistence(timeout: 8)); note.tap(); note.typeText("核对后采用球队记录")
        let discard = app.buttons["discard-team-conflict"]; revealStatisticsControl(discard, in: app); discard.tap()
        XCTAssertTrue(app.alerts["确认处理这份待核对记录？"].waitForExistence(timeout: 5)); app.alerts.buttons["取消"].tap()
        let stillPending = try await sharingAPI(admin, path + "/drafts/" + draftID, method: "GET"); XCTAssertEqual(stillPending["status"] as? String, "pending")
        discard.tap(); app.alerts.buttons["确认处理"].tap()
        let processed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "保留球队记录"), object: app.staticTexts["conflict-resolution-status"])
        await fulfillment(of: [processed], timeout: 12); captureHistory(app, name: "3.0-admin-draft-resolved")
        for _ in 0..<4 { app.navigationBars.buttons.element(boundBy: 0).tap() }
        let archive = app.buttons["open-team-archive"]; revealStatisticsControl(archive, in: app); archive.tap()
        XCTAssertTrue(app.buttons["export-team-archive"].waitForExistence(timeout: 8)); captureHistory(app, name: "3.0-team-portable-archive")
        app.buttons["export-team-archive"].tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 12) || app.buttons["Copy"].exists || app.buttons["拷贝"].exists || app.buttons["Save to Files"].exists || app.buttons["存储到“文件”"].exists)
        app.swipeDown(); if app.buttons["Close"].exists { app.buttons["Close"].tap() }
        if app.buttons["关闭"].exists { app.buttons["关闭"].tap() }
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let audit = app.buttons["open-team-audit"]; revealStatisticsControl(audit, in: app); audit.tap()
        XCTAssertTrue(app.staticTexts["保存记录核对说明"].waitForExistence(timeout: 8)); captureHistory(app, name: "3.0-team-operation-history")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let dissolve = app.buttons["open-dissolve-team"]; revealStatisticsControl(dissolve, in: app); dissolve.tap()
        let name = app.textFields["dissolve-team-name"]; XCTAssertTrue(name.waitForExistence(timeout: 5)); name.tap(); name.typeText("海浪")
        let confirm = app.buttons["confirm-team-dissolution"]; revealStatisticsControl(confirm, in: app); confirm.tap(); app.alerts.buttons["取消"].tap()
        XCTAssertTrue(confirm.isEnabled); captureHistory(app, name: "3.0-team-dissolution-confirmation")
        confirm.tap(); app.alerts.buttons["确认解散全队"].tap()
        XCTAssertTrue(app.staticTexts["team-dissolved"].waitForExistence(timeout: 10))
        app.navigationBars.buttons.element(boundBy: 0).tap(); app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["open-dissolved-teams"].waitForExistence(timeout: 8)); revealStatisticsControl(app.buttons["open-dissolved-teams"], in: app); app.buttons["open-dissolved-teams"].tap()
        let restore = app.buttons["restore-dissolved-team"]; XCTAssertTrue(restore.waitForExistence(timeout: 8)); captureHistory(app, name: "3.0-dissolved-team-recovery")
        restore.tap(); app.alerts.buttons["确认恢复"].tap()
        XCTAssertTrue(app.staticTexts["这台设备没有可恢复的已解散球队。"].waitForExistence(timeout: 10))
        app.navigationBars.buttons.element(boundBy: 0).tap(); XCTAssertTrue(teamButton.waitForExistence(timeout: 8)); teamButton.tap()
        let recovery = app.buttons["open-team-recovery-card"]; revealStatisticsControl(recovery, in: app); XCTAssertTrue(recovery.label.contains("请确认已保存")); captureHistory(app, name: "3.0-restored-team-private")
    }
}

extension BaseballMasterUITests {
    @MainActor func testV311FixedInviteMultipleMembersAndSharedRosterScheduleStaysLocal() async throws {
        let app = launchDeviceSecurityApp()
        app.buttons["create-shared-team"].tap()
        app.textFields["shared-team-name"].tap(); app.textFields["shared-team-name"].typeText("301共享队")
        app.textFields["shared-nickname"].tap(); app.textFields["shared-nickname"].typeText("教练")
        if app.buttons["收起键盘"].exists { app.buttons["收起键盘"].tap() }
        revealStatisticsControl(app.buttons["confirm-sharing-scope"], in: app); app.buttons["confirm-sharing-scope"].tap()
        app.buttons["submit-shared-team"].firstMatch.tap()
        XCTAssertTrue(app.buttons["defer-recovery-card"].waitForExistence(timeout: 8)); app.buttons["defer-recovery-card"].tap()
        app.buttons["shared-team-301共享队"].tap()
        let invite = app.buttons["invite-team-member"]; revealStatisticsControl(invite, in: app); invite.tap()
        let link = app.staticTexts["team-invitation-url"]; XCTAssertTrue(link.waitForExistence(timeout: 10))
        let initial = link.label, token = try XCTUnwrap(URL(string: initial)?.fragment)
        var reader = "", teamID = ""
        for index in 1...2 {
            let credential = sharingTestSecret()
            _ = try await sharingAPI(credential, "/devices", body: ["label": "成员设备"])
            let joined = try await sharingAPI(credential, "/invitations/redeem", body: ["operationID": UUID().uuidString, "token": token, "nickname": "家长\(index)"])
            reader = credential; teamID = try XCTUnwrap(joined["teamID"] as? String)
        }
        captureHistory(app, name: "3.0.1-fixed-invitation")
        app.buttons["完成"].firstMatch.tap()
        revealStatisticsControl(invite, in: app); invite.tap(); XCTAssertTrue(link.waitForExistence(timeout: 8)); XCTAssertEqual(link.label, initial)
        app.buttons["rotate-team-invitation"].tap(); app.alerts.buttons["更换链接"].tap()
        let changed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label != %@", initial), object: link)
        await fulfillment(of: [changed], timeout: 8)
        app.buttons["完成"].firstMatch.tap()
        XCTAssertFalse(app.buttons["shared-roster-new-game"].exists, "Shared-team pages only view and manage archives")
        app.tabBars.buttons["比赛"].tap()
        let create = app.buttons["start-new-game"]
        XCTAssertTrue(create.waitForExistence(timeout: 5)); create.tap()
        XCTAssertTrue(app.navigationBars["新比赛"].waitForExistence(timeout: 5))
        let ownChoice = app.buttons["new-game-own-team"]
        XCTAssertTrue(ownChoice.exists); ownChoice.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "本机球队")).firstMatch.waitForExistence(timeout: 3))
        captureHistory(app, name: "3.0.1-local-and-shared-team-choices")
        app.buttons["301共享队 · 共享球队 · 0 人"].tap()
        let future = app.switches["安排未来比赛"]; revealStatisticsControl(future, in: app)
        future.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        let destination = app.buttons["share-destination-picker"]; revealStatisticsControl(destination, in: app)
        XCTAssertTrue(destination.label.contains("301共享队"), "Selecting a shared roster only presets its post-game upload target")
        let beforeSave = try await sharingAPI(reader, "/teams/\(teamID)/games?cursor=0", method: "GET")
        XCTAssertEqual((beforeSave["games"] as? [[String: Any]])?.count, 0, "Choosing a shared roster must not create a cloud match")
        captureHistory(app, name: "3.1.1-shared-roster-postgame-target")
        let save = app.buttons["continue-to-lineup"]; revealStatisticsControl(save, in: app); save.tap()
        app.alerts.buttons["保存安排"].tap()
        XCTAssertTrue(app.alerts["比赛安排已保存"].waitForExistence(timeout: 12))
        let response = try await sharingAPI(reader, "/teams/\(teamID)/games?cursor=0", method: "GET")
        let games = try XCTUnwrap(response["games"] as? [[String: Any]])
        XCTAssertEqual(games.count, 0, "Saving a future match is local only; uploading requires a new confirmation after completion")
        XCTAssertTrue(app.alerts.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "比赛结束后仍需确认是否上传")).firstMatch.exists)
        captureHistory(app, name: "3.1.1-future-local-only-saved")
        app.alerts.buttons["返回比赛首页"].tap()
        let localSchedule = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "scheduled-game-", "301共享队")).firstMatch
        XCTAssertTrue(localSchedule.waitForExistence(timeout: 5), "The complete schedule must remain available on this device")
    }
}


extension BaseballMasterUITests {
    @MainActor func testV301ResultImportThenDeleteSharedKeepsLocalResult() async throws {
        let (app, reader, teamID, gameID) = try await createSharedMatchUI(final: true, shareFromResult: true)
        let delete = app.buttons["删除共享比赛"]; revealStatisticsControl(delete, in: app); delete.tap()
        app.alerts.buttons["确认删除共享比赛"].tap()
        var remote = try await sharingAPI(reader, "/teams/\(teamID)/games/\(gameID)", method: "GET")
        for _ in 0..<20 where remote["deleted"] as? Bool != true {
            try await Task.sleep(nanoseconds: 100_000_000)
            remote = try await sharingAPI(reader, "/teams/\(teamID)/games/\(gameID)", method: "GET")
        }
        XCTAssertEqual(remote["deleted"] as? Bool, true)
        app.navigationBars["共享比赛"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5))
        app.navigationBars["比赛结果"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["start-new-game"].waitForExistence(timeout: 5))
        let original = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "completed-game-")).firstMatch
        XCTAssertTrue(original.waitForExistence(timeout: 5)); revealStatisticsControl(original, in: app); original.tap()
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["open-history-correction"].exists)
        captureHistory(app, name: "3.0.1-original-after-shared-deletion")
    }
}

extension BaseballMasterUITests {
    @MainActor func testV301TeamPageCachesNavigationAndRefreshesManuallyAndAfterBackground() async throws {
        let admin = sharingTestSecret(), invitation = sharingTestSecret()
        _ = try await sharingAPI(admin, "/devices", body: ["label": "管理员设备"])
        let created = try await sharingAPI(admin, "/teams", body: ["operationID": UUID().uuidString, "name": "刷新原名", "nickname": "管理员"])
        let teamID = try XCTUnwrap(created["teamID"] as? String)
        _ = try await sharingAPI(admin, "/teams/\(teamID)/invitations", body: ["operationID": UUID().uuidString, "token": invitation, "role": "member"])
        let app = launchDeviceSecurityApp()
        app.buttons["join-shared-team"].tap()
        app.textFields["team-invite-link"].tap(); app.textFields["team-invite-link"].typeText("https://baseballmaster.cc/join#\(invitation)")
        app.buttons["preview-team-invite"].tap()
        XCTAssertTrue(app.staticTexts["invitation-team-name"].waitForExistence(timeout: 8))
        app.textFields["join-nickname"].tap(); app.textFields["join-nickname"].typeText("成员")
        revealStatisticsControl(app.buttons["confirm-join-team"], in: app); app.buttons["confirm-join-team"].tap()
        let row = app.buttons["shared-team-刷新原名"]
        XCTAssertTrue(row.waitForExistence(timeout: 8)); row.tap()
        XCTAssertTrue(app.staticTexts["刷新原名"].waitForExistence(timeout: 5))
        func rename(_ name: String, revision: Int) async throws {
            _ = try await sharingAPI(admin, "/teams/\(teamID)/roster", method: "PATCH", body: ["operationID": UUID().uuidString, "baseRevision": revision, "kind": "profile", "name": name, "shortName": name, "city": ""])
        }
        try await rename("手动刷新后", revision: 1)
        app.navigationBars["共享球队"].buttons.element(boundBy: 0).tap(); row.tap()
        XCTAssertTrue(app.staticTexts["刷新原名"].waitForExistence(timeout: 3))
        XCTAssertFalse(app.staticTexts["手动刷新后"].exists)
        app.buttons["刷新"].tap()
        XCTAssertTrue(app.staticTexts["手动刷新后"].waitForExistence(timeout: 8))
        captureHistory(app, name: "3.0.1-manual-refresh")
        XCUIDevice.shared.press(.home)
        try await rename("后台返回后", revision: 2)
        app.activate()
        XCTAssertTrue(app.staticTexts["后台返回后"].waitForExistence(timeout: 10))
        captureHistory(app, name: "3.0.1-foreground-refresh")
    }
}

extension BaseballMasterUITests {
    func testV310PracticeOffersBothMethodsAndObservationCancelDoesNotRecord() throws {
        let app = XCUIApplication(); app.launchArguments = ["--practice-preview", "-showPracticeGuidance", "NO"]
        app.launch(); app.segmentedControls["recording-method-picker"].buttons["Pro 版"].tap()
        let choice = XCTAttachment(screenshot: app.screenshot()); choice.name = "V4-两种练习方式"; choice.lifetime = .keepAlways; add(choice)
        let start = app.buttons["start-practice-inning"]; revealStatisticsControl(start, in: app); start.tap()
        let batter = app.descendants(matching: .any)["current-batter"].firstMatch, before = batter.label
        app.buttons["ball-in-play"].tap()
        if app.buttons["确认开赛"].waitForExistence(timeout: 1) { app.buttons["确认开赛"].tap() }
        XCTAssertTrue(app.buttons["quick-outcome-1B"].waitForExistence(timeout: 5))
        let menu = XCTAttachment(screenshot: app.screenshot()); menu.name = "V4-常用击球入口"; menu.lifetime = .keepAlways; add(menu)
        app.buttons["quick-outcome-1B"].tap(); app.buttons["contact-type-line"].tap()
        XCTAssertFalse(app.buttons["finish-situation-field"].isEnabled)
        app.otherElements["contact-field"].coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.3)).tap()
        XCTAssertTrue(app.buttons["situation-fielder-8"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["finish-situation-field"].isEnabled, "An empty-base hit still requires explicit fielding")
        app.buttons["situation-fielder-8"].tap(); app.buttons["situation-fielder-4"].tap()
        let contact = XCTAttachment(screenshot: app.screenshot()); contact.name = "V4-同场落点与守备传球"; contact.lifetime = .keepAlways; add(contact)
        app.buttons["cancel-situation-play"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5)); XCTAssertEqual(batter.label, before)
        app.buttons["ball-in-play"].tap(); app.buttons["quick-outcome-1B"].tap(); app.buttons["contact-type-line"].tap()
        app.otherElements["contact-field"].coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.3)).tap()
        app.buttons["situation-fielder-8"].tap(); app.buttons["situation-fielder-4"].tap()
        app.buttons["finish-situation-field"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5)); XCTAssertNotEqual(batter.label, before)
        XCTAssertTrue(app.buttons["amend-situation-play"].exists)
        let field = XCTAttachment(screenshot: app.screenshot()); field.name = "V4-姓名背号与补充这球"; field.lifetime = .keepAlways; add(field)
        let savedBatter = batter.label
        app.buttons["amend-situation-play"].tap()
        XCTAssertTrue(app.buttons["edit-situation-field"].waitForExistence(timeout: 5)); app.buttons["edit-situation-field"].tap()
        app.buttons["situation-fielder-2"].tap()
        app.buttons["finish-situation-field"].tap(); app.buttons["cancel-situation-play"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5)); XCTAssertEqual(batter.label, savedBatter)
        app.buttons["amend-situation-play"].tap(); app.buttons["edit-situation-field"].tap()
        app.buttons["situation-fielder-2"].tap(); app.buttons["finish-situation-field"].tap(); app.buttons["confirm-situation-play"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        let pitcher = app.descendants(matching: .any)["current-pitcher"].firstMatch
        XCTAssertTrue(pitcher.label.contains("本场已投 1 球"), "Amending the complete play must not add a pitch")
        app.buttons["undo-last-play"].tap(); app.buttons["redo-last-play"].tap()
        XCTAssertTrue(pitcher.label.contains("本场已投 1 球"))
    }

    func testV310TraditionalPracticeRetainsOriginalFlowAndSetupChoice() throws {
        let app = XCUIApplication(); app.launchArguments = ["--practice-preview", "-showPracticeGuidance", "NO"]
        app.launch()
        app.segmentedControls["recording-method-picker"].buttons["基础版"].tap()
        let start = app.buttons["start-practice-inning"]; revealStatisticsControl(start, in: app); start.tap()
        app.buttons["ball-in-play"].tap()
        if app.buttons["确认开赛"].waitForExistence(timeout: 1) { app.buttons["确认开赛"].tap() }
        XCTAssertTrue(app.buttons["arrival-到一垒"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["quick-outcome-1B"].exists)
        app.buttons["arrival-到一垒"].tap(); app.buttons["cause-正常打上垒"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["continue-contact"].exists)
        app.terminate(); app.launchArguments = ["--setup-preview"]; app.launch()
        XCTAssertTrue(app.segmentedControls["recording-method-picker"].waitForExistence(timeout: 5))
        app.segmentedControls["recording-method-picker"].buttons["Pro 版"].tap()
        let setup = XCTAttachment(screenshot: app.screenshot()); setup.name = "3.1.0-开局选择记录方式"; setup.lifetime = .keepAlways; add(setup)
    }
}

extension BaseballMasterUITests {
    func testV310BatterChartCurrentAnnualDarkModeAndNamedFielders() throws {
        let app = XCUIApplication(); app.launchArguments = ["--batted-ball-preview", "-appAppearance", "light"]
        app.launch()
        let batter = app.descendants(matching: .any)["current-batter"].firstMatch
        XCTAssertTrue(batter.waitForExistence(timeout: 5))
        let fielder = app.descendants(matching: .any)["fielder-6"].firstMatch
        XCTAssertTrue(fielder.exists); XCTAssertTrue(fielder.label.contains("号")); XCTAssertFalse(fielder.label.contains("游击"))
        let field = XCTAttachment(screenshot: app.screenshot()); field.name = "3.1.0-球场姓名背号"; field.lifetime = .keepAlways; add(field)
        batter.tap()
        XCTAssertTrue(app.navigationBars["打击表现"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["batter-contact-coverage"].label.contains("有去向 2 球 · 缺少去向 1 球"))
        let current = XCTAttachment(screenshot: app.screenshot()); current.name = "3.1.0-本场击球图"; current.lifetime = .keepAlways; add(current)
        app.segmentedControls["batter-performance-scope"].buttons.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["batter-contact-coverage"].label.contains("有去向 2 球 · 缺少去向 1 球"))
        let mark = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "点选去向")).firstMatch
        XCTAssertTrue(mark.exists); mark.tap()
        XCTAssertTrue(app.staticTexts["selected-contact-detail"].exists)
        let annual = XCTAttachment(screenshot: app.screenshot()); annual.name = "3.1.0-年度击球图"; annual.lifetime = .keepAlways; add(annual)
        app.terminate(); app.launchArguments = ["--batted-ball-preview", "-appAppearance", "dark"]; app.launch()
        app.descendants(matching: .any)["current-batter"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["打击表现"].waitForExistence(timeout: 5))
        let dark = XCTAttachment(screenshot: app.screenshot()); dark.name = "3.1.0-深色击球图"; dark.lifetime = .keepAlways; add(dark)
    }
}

extension BaseballMasterUITests {
    func testV310DoublePlayChecksRunnersAndDefenseBackPreservesObservation() throws {
        let app = XCUIApplication(); app.launchArguments = ["--practice-preview", "-showPracticeGuidance", "NO"]
        app.launch(); app.segmentedControls["recording-method-picker"].buttons["Pro 版"].tap()
        let start = app.buttons["start-practice-inning"]; revealStatisticsControl(start, in: app); start.tap()
        app.buttons["ball-in-play"].tap()
        if app.buttons["确认开赛"].waitForExistence(timeout: 1) { app.buttons["确认开赛"].tap() }
        XCTAssertTrue(app.buttons["quick-outcome-DP"].waitForExistence(timeout: 5)); XCTAssertFalse(app.buttons["quick-outcome-DP"].isEnabled)
        app.buttons["quick-outcome-1B"].tap(); app.buttons["contact-type-line"].tap()
        app.otherElements["contact-field"].coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.3)).tap()
        app.buttons["situation-fielder-7"].tap(); app.buttons["finish-situation-field"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5)); app.buttons["ball-in-play"].tap()
        XCTAssertTrue(app.buttons["quick-outcome-DP"].isEnabled); app.buttons["quick-outcome-DP"].tap(); app.buttons["contact-type-ground"].tap()
        app.otherElements["contact-field"].coordinate(withNormalizedOffset: CGVector(dx: 0.32, dy: 0.45)).tap()
        app.buttons["situation-route-6-4-3"].tap(); app.buttons["finish-situation-field"].tap()
        XCTAssertTrue(app.buttons["situation-runner-0"].waitForExistence(timeout: 5)); XCTAssertTrue(app.buttons["situation-runner-1"].exists)
        XCTAssertTrue(app.staticTexts["situation-defense-description"].label.contains("防守路线 6-4-3"))
        app.buttons["edit-situation-field"].tap()
        XCTAssertTrue(app.staticTexts["situation-route"].label.contains("6 → 4 → 3"))
        app.buttons["finish-situation-field"].tap()
        let runners = XCTAttachment(screenshot: app.screenshot()); runners.name = "V4-统一核对双杀跑者"; runners.lifetime = .keepAlways; add(runners)
        app.buttons["confirm-situation-play"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
    }
}

extension BaseballMasterUITests {
    func testV310V4CompoundHitReusesFieldingAndRecordsRunnerCausesOnce() throws {
        let app = XCUIApplication(); app.launchArguments = ["--situation-compound-preview"]
        app.launch(); app.buttons["ball-in-play"].tap(); app.buttons["quick-outcome-1B"].tap()
        app.buttons["contact-type-line"].tap()
        app.otherElements["contact-field"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).tap()
        app.buttons["situation-fielder-8"].tap(); app.buttons["situation-fielder-4"].tap(); app.buttons["finish-situation-field"].tap()
        XCTAssertTrue(app.buttons["situation-destination-1-得分"].waitForExistence(timeout: 5))
        app.buttons["situation-destination-1-得分"].tap(); app.buttons["situation-runner-0"].tap()
        app.buttons["situation-destination-0-出局"].tap()
        let extra = app.buttons["situation-extra-route-0"]; revealStatisticsControl(extra, in: app); extra.tap()
        XCTAssertTrue(app.buttons["situation-reuse-route"].waitForExistence(timeout: 5)); app.buttons["situation-reuse-route"].tap()
        XCTAssertTrue(app.buttons["confirm-situation-play"].waitForExistence(timeout: 5))
        let capture = XCTAttachment(screenshot: app.screenshot()); capture.name = "V4-安打后进垒被触杀-独立原因和路线"; capture.lifetime = .keepAlways; add(capture)
        app.buttons["confirm-situation-play"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        let pitcher = app.descendants(matching: .any)["current-pitcher"].firstMatch
        XCTAssertTrue(pitcher.label.contains("本场已投 2 球"))
    }

    func testV310V4ErrorResponsibilityIsIndependentOfFirstHandler() throws {
        let app = XCUIApplication(); app.launchArguments = ["--practice-preview", "-showPracticeGuidance", "NO"]
        app.launch(); app.segmentedControls["recording-method-picker"].buttons["Pro 版"].tap()
        let start = app.buttons["start-practice-inning"]; revealStatisticsControl(start, in: app); start.tap()
        app.buttons["ball-in-play"].tap()
        if app.buttons["确认开赛"].waitForExistence(timeout: 1) { app.buttons["确认开赛"].tap() }
        app.buttons["quick-outcome-E"].tap(); app.buttons["contact-type-ground"].tap()
        app.otherElements["contact-field"].coordinate(withNormalizedOffset: CGVector(dx: 0.32, dy: 0.45)).tap()
        app.buttons["situation-fielder-6"].tap(); app.buttons["situation-fielder-3"].tap()
        XCTAssertFalse(app.buttons["finish-situation-field"].isEnabled)
        app.segmentedControls["situation-field-mode"].buttons["失误责任"].tap(); app.buttons["situation-fielder-3"].tap()
        let field = XCTAttachment(screenshot: app.screenshot()); field.name = "V4-同场指定独立失误责任人"; field.lifetime = .keepAlways; add(field)
        app.buttons["finish-situation-field"].tap()
        XCTAssertTrue(app.staticTexts["situation-error-responsibility"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["situation-error-responsibility"].label.contains("一垒手"))
        app.buttons["confirm-situation-play"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
    }
}


extension BaseballMasterUITests {
    func testV311ProOtherResultsOpensFullCatalogAndPreservesSelectedTypeOnBack() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--practice-preview", "-showPracticeGuidance", "NO"]
        app.launch()
        app.segmentedControls["recording-method-picker"].buttons["Pro 版"].tap()
        let start = app.buttons["start-practice-inning"]
        revealStatisticsControl(start, in: app); start.tap()
        let batter = app.descendants(matching: .any)["current-batter"].firstMatch
        let before = batter.label
        app.buttons["ball-in-play"].tap()
        if app.buttons["确认开赛"].waitForExistence(timeout: 1) { app.buttons["确认开赛"].tap() }
        app.buttons["situation-by-process"].tap()
        app.buttons["contact-type-line"].tap()
        let more = app.buttons["process-all-outcomes"]
        revealStatisticsControl(more, in: app); more.tap()
        XCTAssertTrue(app.buttons["quick-outcome-HR"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["quick-outcome-HR"].isEnabled)
        app.navigationBars.buttons["返回"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["平飞 · 结果"].waitForExistence(timeout: 5))
        revealStatisticsControl(more, in: app); more.tap()
        app.buttons["quick-outcome-HR"].tap()
        XCTAssertTrue(app.navigationBars["本垒打"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["contact-type-line"].value as? String, "已选择")
        app.navigationBars.buttons["返回"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["全部击球"].waitForExistence(timeout: 5))
        app.buttons["cancel-situation-play"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        XCTAssertEqual(batter.label, before, "Browsing or cancelling other results must not record a play")
    }

    func testV311ProSacrificeResultsExplainUnavailableAndSelectMatchingBallType() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--practice-preview", "-showPracticeGuidance", "NO"]
        app.launch()
        app.segmentedControls["recording-method-picker"].buttons["Pro 版"].tap()
        let start = app.buttons["start-practice-inning"]
        revealStatisticsControl(start, in: app); start.tap()
        app.buttons["ball-in-play"].tap()
        if app.buttons["确认开赛"].waitForExistence(timeout: 1) { app.buttons["确认开赛"].tap() }
        app.buttons["all-batted-outcomes"].tap()
        let sacrifice = app.buttons["quick-outcome-SH"]
        revealStatisticsControl(sacrifice, in: app)
        XCTAssertFalse(sacrifice.isEnabled)
        XCTAssertFalse(app.buttons["quick-outcome-SF"].isEnabled)
        XCTAssertTrue(app.staticTexts["outcome-unavailable-SH"].label.contains("垒上有跑者"))
        XCTAssertTrue(app.staticTexts["outcome-unavailable-SF"].label.contains("三垒有跑者"))
        // Record one actual triple, so both sacrifice results become available at zero outs.
        let triple = app.buttons["quick-outcome-3B"]
        revealStatisticsControl(triple, in: app); triple.tap()
        app.buttons["contact-type-line"].tap()
        app.otherElements["contact-field"].coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.2)).tap()
        app.buttons["situation-fielder-8"].tap()
        app.buttons["finish-situation-field"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        let batter = app.descendants(matching: .any)["current-batter"].firstMatch
        let before = batter.label
        app.buttons["ball-in-play"].tap()
        app.buttons["situation-by-process"].tap(); app.buttons["contact-type-ground"].tap()
        let more = app.buttons["process-all-outcomes"]
        revealStatisticsControl(more, in: app); more.tap()
        revealStatisticsControl(sacrifice, in: app)
        XCTAssertTrue(sacrifice.isEnabled)
        XCTAssertTrue(app.buttons["quick-outcome-SF"].isEnabled)
        app.buttons["quick-outcome-SF"].tap()
        XCTAssertEqual(app.buttons["contact-type-fly"].value as? String, "已选择")
        app.navigationBars.buttons["返回"].firstMatch.tap()
        revealStatisticsControl(sacrifice, in: app); sacrifice.tap()
        XCTAssertEqual(app.buttons["contact-type-bunt"].value as? String, "已选择")
        app.buttons["cancel-situation-play"].tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        XCTAssertEqual(batter.label, before)
    }
}


extension BaseballMasterUITests {
    func testV311ManualFinishWaitsForRecordingSheetAndKeepsLocalWithoutUpload() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--v11-navigation-preview"]
        app.launch()
        let ongoing = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        XCTAssertTrue(ongoing.waitForExistence(timeout: 5)); ongoing.tap()
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        app.buttons["结束比赛"].tap()
        let reason = app.buttons["finish-reason-记录员结束记录"]
        XCTAssertTrue(reason.waitForExistence(timeout: 5)); reason.tap()
        app.buttons["确认保存"].tap()
        XCTAssertTrue(app.navigationBars["赛后记录去向"].waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(app.navigationBars["结束或中断比赛"].exists)
        XCTAssertTrue(app.staticTexts["比赛已结束，完整记录已保存在本机。"].exists)
        let keep = app.buttons["keep-final-local"]
        XCTAssertTrue(keep.waitForExistence(timeout: 5)); keep.tap()
        XCTAssertTrue(keep.waitForNonExistence(timeout: 5), "Wait for the postgame sheet to finish dismissing before navigating")
        let home = app.buttons["return-final-game-home"]
        XCTAssertTrue(home.waitForExistence(timeout: 5))
        let result = app.buttons["open-box-score"]
        XCTAssertTrue(app.frame.intersects(result.frame), app.debugDescription)
        result.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5), app.debugDescription)
        app.navigationBars["比赛结果"].buttons.element(boundBy: 0).tap()
        XCTAssertFalse(app.navigationBars["赛后记录去向"].exists, "Viewing a saved result must not reopen the automatic prompt")
        home.tap()
        XCTAssertTrue(app.buttons["start-new-game"].waitForExistence(timeout: 5))
        XCTAssertFalse(ongoing.exists)
    }
}

// Run these cases on an iPad destination. Fixtures use isolated in-memory records.
extension BaseballMasterUITests {
    private func requireV312IPad() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires an iPad test destination")
        XCUIDevice.shared.orientation = .portrait
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
    }

    private func rotateV312(_ app: XCUIApplication, landscape: Bool) {
        XCUIDevice.shared.orientation = landscape ? .landscapeLeft : .portrait
        // iPadOS windows can stay narrow after the device rotates. XCUI frames use
        // screen coordinates, so a width/height comparison is not an orientation signal.
        // The rotation call waits for UI idling; each caller then checks its live draft/actions.
        XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 5))
        print("iPad rotation requested: \(landscape ? "landscape" : "portrait"), app=\(app.frame), window=\(app.windows.firstMatch.frame)")
    }

    private func revealV312Workbench(_ element: XCUIElement, in app: XCUIApplication) {
        let scroll = app.scrollViews["scorekeeping-wide-layout"]
        XCTAssertTrue(scroll.waitForExistence(timeout: 5))
        var reachability: [String] = []
        // Off-screen SwiftUI elements can leave the AX tree. Search toward the top
        // first rather than treating a missing element as one below the viewport.
        for towardTop in [true, false] {
            for step in 0..<6 {
                let exists = element.exists
                let hittable = exists && element.isHittable
                reachability.append("towardTop=\(towardTop), step=\(step), exists=\(exists), hittable=\(hittable), frame=\(exists ? String(describing: element.frame) : "missing")")
                if hittable { return }
                let start = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.08, dy: towardTop ? 0.3 : 0.8))
                let end = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.08, dy: towardTop ? 0.8 : 0.3))
                start.press(forDuration: 0.05, thenDragTo: end)
            }
        }
        let reachable = element.exists && element.isHittable
        if !reachable {
            captureHistory(app, name: "3.1.2-workbench-unreachable-control")
            print("Workbench failed reachability: \(element)\n" + reachability.joined(separator: "\n"))
            print("Workbench geometry: app=\(app.frame), window=\(app.windows.firstMatch.frame), scroll=\(scroll.frame)")
        }
        XCTAssertTrue(reachable, "Workbench content must be reachable: \(element)\n\(app.debugDescription)")
    }

    private func assertV312WorkbenchPanels(in app: XCUIApplication) {
        for identifier in ["workbench-line-score", "workbench-matchup", "workbench-lineups",
                           "workbench-recent-log", "workbench-recent-appearances"] {
            XCTAssertTrue(app.descendants(matching: .any)[identifier].firstMatch.waitForExistence(timeout: 5),
                          "The wide workbench must include \(identifier)")
        }
        for heading in ["本场投打表现", "场上阵容", "最近实况", "最近打席"] {
            let label = app.staticTexts[heading].firstMatch
            revealV312Workbench(label, in: app)
            XCTAssertTrue(label.isHittable, "Workbench panels must be readable by scrolling")
        }
        revealV312Workbench(app.buttons["ball-in-play"], in: app)
    }

    func testV312IPadLaunchRotationSkipAndNewGameNavigation() throws {
        try requireV312IPad()
        let app = XCUIApplication()
        app.launchArguments = ["--launch-screen-custom-preview"]
        app.launch()
        let brand = app.images["launch-fixed-bm"]
        let icon = app.images["launch-fixed-app-icon"]
        let skip = app.buttons["skip-launch-screen"]
        XCTAssertTrue(brand.waitForExistence(timeout: 5))
        XCTAssertTrue(icon.exists)
        XCTAssertTrue(skip.isHittable)
        captureHistory(app, name: "3.1.2-ipad-portrait-launch")
        rotateV312(app, landscape: true)
        XCTAssertTrue(brand.exists)
        XCTAssertTrue(icon.exists)
        XCTAssertTrue(app.frame.contains(brand.frame), "Fixed branding must fit the landscape window")
        XCTAssertTrue(app.frame.contains(icon.frame), "The App icon must stay inside the landscape window")
        XCTAssertTrue(app.staticTexts["launch-message"].label.contains("固定品牌 图文保持清晰"))
        XCTAssertTrue(skip.isHittable)
        captureHistory(app, name: "3.1.2-ipad-landscape-launch")
        skip.tap()
        let create = app.buttons["start-new-game"]
        XCTAssertTrue(create.waitForExistence(timeout: 5))
        XCTAssertTrue(create.isHittable); create.tap()
        XCTAssertTrue(app.navigationBars["新比赛"].waitForExistence(timeout: 5))
        let ownTeam = app.buttons["new-game-own-team"]
        XCTAssertTrue(ownTeam.exists)
        rotateV312(app, landscape: false)
        XCTAssertTrue(ownTeam.isHittable, "The new-game form must remain usable after rotating")
        ownTeam.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "本机球队")).firstMatch.waitForExistence(timeout: 5))
        captureHistory(app, name: "3.1.2-ipad-new-game-team-picker")
    }

    func testV312IPadScoringRotationKeepsPitchCountAndUndoRedo() throws {
        try requireV312IPad()
        let app = XCUIApplication()
        app.launchArguments = ["--v11-navigation-preview"]
        app.launch()
        let game = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        XCTAssertTrue(game.waitForExistence(timeout: 5)); game.tap()
        let pitcher = app.descendants(matching: .any)["current-pitcher"].firstMatch
        XCTAssertTrue(pitcher.waitForExistence(timeout: 5))
        XCTAssertTrue(pitcher.label.contains("本场已投 0 球"))
        assertV312WorkbenchPanels(in: app)
        revealV312Workbench(app.buttons["pitch-坏球"], in: app)
        app.buttons["pitch-坏球"].tap()
        rotateV312(app, landscape: true)
        let strike = app.buttons["pitch-看振"]
        revealStatisticsControl(strike, in: app)
        XCTAssertTrue(strike.isHittable); strike.tap()
        XCTAssertTrue(pitcher.label.contains("本场已投 2 球"), "Rotation must retain the recorded pitch")
        captureHistory(app, name: "3.1.2-ipad-landscape-scoring")
        app.buttons["undo-last-play"].tap()
        XCTAssertTrue(pitcher.label.contains("本场已投 1 球"))
        rotateV312(app, landscape: false)
        app.buttons["redo-last-play"].tap()
        XCTAssertTrue(pitcher.label.contains("本场已投 2 球"))
        XCTAssertTrue(app.buttons["ball-in-play"].isHittable)
        captureHistory(app, name: "3.1.2-ipad-portrait-scoring")
    }

    func testV312IPadProRotationPreservesContactAndFieldingDraftThenCancel() throws {
        try requireV312IPad()
        let app = XCUIApplication()
        app.launchArguments = ["--practice-preview", "-showPracticeGuidance", "NO"]
        app.launch()
        app.segmentedControls["recording-method-picker"].buttons["Pro 版"].tap()
        let start = app.buttons["start-practice-inning"]
        revealStatisticsControl(start, in: app); start.tap()
        let batter = app.descendants(matching: .any)["current-batter"].firstMatch
        let before = batter.label
        assertV312WorkbenchPanels(in: app)
        app.buttons["ball-in-play"].tap()
        if app.buttons["确认开赛"].waitForExistence(timeout: 1) { app.buttons["确认开赛"].tap() }
        XCTAssertTrue(app.descendants(matching: .any)["docked-situation-editor"].firstMatch.waitForExistence(timeout: 5),
                      "Wide Pro recording must open inside the workbench")
        XCTAssertTrue(app.navigationBars["现场记分"].exists, "The workbench must stay on the current scoring page")
        XCTAssertFalse(app.buttons["reset-practice-inning"].isEnabled,
                       "An inline draft must lock the parent practice-reset action")
        revealV312Workbench(app.buttons["quick-outcome-1B"], in: app)
        app.buttons["quick-outcome-1B"].tap()
        app.buttons["contact-type-line"].tap()
        app.otherElements["contact-field"].coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.3)).tap()
        app.buttons["situation-fielder-8"].tap()
        let summary = app.staticTexts["contact-summary"].label
        let route = app.staticTexts["situation-route"].label
        rotateV312(app, landscape: true)
        XCTAssertEqual(app.buttons["contact-type-line"].value as? String, "已选择")
        XCTAssertEqual(app.staticTexts["contact-summary"].label, summary, "The selected landing point must survive rotation")
        XCTAssertEqual(app.staticTexts["situation-route"].label, route)
        app.buttons["situation-fielder-4"].tap()
        XCTAssertTrue(app.staticTexts["situation-route"].label.hasPrefix("8 → 4"))
        XCTAssertTrue(app.buttons["finish-situation-field"].isEnabled)
        captureHistory(app, name: "3.1.2-ipad-landscape-pro-draft")
        rotateV312(app, landscape: false)
        XCTAssertTrue(app.staticTexts["situation-route"].label.hasPrefix("8 → 4"))
        app.buttons["cancel-situation-play"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["docked-situation-editor"].firstMatch.waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.buttons["ball-in-play"].waitForExistence(timeout: 5))
        XCTAssertEqual(batter.label, before, "Cancelling the rotated draft must not record a play")
        XCTAssertTrue(app.buttons["reset-practice-inning"].isEnabled)
    }

    func testV312IPadDockedProCompletesOnePlayAndUndoRedoKeepsSinglePitch() throws {
        try requireV312IPad()
        let app = XCUIApplication()
        app.launchArguments = ["--practice-preview", "-showPracticeGuidance", "NO"]
        app.launch()
        app.segmentedControls["recording-method-picker"].buttons["Pro 版"].tap()
        let start = app.buttons["start-practice-inning"]
        revealStatisticsControl(start, in: app); start.tap()
        let batter = app.descendants(matching: .any)["current-batter"].firstMatch
        let before = batter.label
        let pitcher = app.descendants(matching: .any)["current-pitcher"].firstMatch
        assertV312WorkbenchPanels(in: app)
        app.buttons["ball-in-play"].tap()
        if app.buttons["确认开赛"].waitForExistence(timeout: 1) { app.buttons["确认开赛"].tap() }
        let editor = app.descendants(matching: .any)["docked-situation-editor"].firstMatch
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        revealV312Workbench(app.buttons["quick-outcome-1B"], in: app)
        app.buttons["quick-outcome-1B"].tap()
        app.buttons["contact-type-line"].tap()
        app.otherElements["contact-field"].coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.3)).tap()
        app.buttons["situation-fielder-8"].tap()
        let finish = app.buttons["finish-situation-field"]
        XCTAssertTrue(finish.isEnabled)
        finish.tap()
        XCTAssertTrue(editor.waitForNonExistence(timeout: 5), "Completing the play must close the inline editor")
        XCTAssertNotEqual(batter.label, before)
        XCTAssertTrue(pitcher.label.contains("本场已投 1 球"), "A completed inline play must add exactly one pitch")
        revealV312Workbench(app.staticTexts["最近打席"].firstMatch, in: app)
        XCTAssertFalse(app.staticTexts["尚无已完成打席。当前打席确认后显示结果。"].exists)
        captureHistory(app, name: "3.1.2-ipad-docked-pro-completed-play")
        revealV312Workbench(app.buttons["undo-last-play"], in: app)
        app.buttons["undo-last-play"].tap()
        XCTAssertEqual(batter.label, before, "One undo must reverse the entire recorded play")
        XCTAssertTrue(pitcher.label.contains("本场已投 0 球"))
        app.buttons["redo-last-play"].tap()
        XCTAssertNotEqual(batter.label, before)
        XCTAssertTrue(pitcher.label.contains("本场已投 1 球"), "Redo must restore one play without duplicating its pitch")
    }

    func testV312IPadFinishRotatePostgameAndReturnHomeKeepsSavedResult() throws {
        try requireV312IPad()
        let app = XCUIApplication()
        app.launchArguments = ["--v11-navigation-preview"]
        app.launch()
        let game = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        XCTAssertTrue(game.waitForExistence(timeout: 5)); game.tap()
        rotateV312(app, landscape: true)
        let finish = app.buttons["结束比赛"]
        revealStatisticsControl(finish, in: app); finish.tap()
        let reason = app.buttons["finish-reason-记录员结束记录"]
        XCTAssertTrue(reason.waitForExistence(timeout: 5)); reason.tap()
        app.buttons["确认保存"].tap()
        let keep = app.buttons["keep-final-local"]
        XCTAssertTrue(keep.waitForExistence(timeout: 5))
        rotateV312(app, landscape: false)
        XCTAssertTrue(keep.isHittable); keep.tap()
        XCTAssertTrue(keep.waitForNonExistence(timeout: 5))
        let result = app.buttons["open-box-score"]
        revealStatisticsControl(result, in: app); result.tap()
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5))
        rotateV312(app, landscape: true)
        captureHistory(app, name: "3.1.2-ipad-landscape-final-result")
        app.navigationBars["比赛结果"].buttons.element(boundBy: 0).tap()
        XCTAssertFalse(app.navigationBars["赛后记录去向"].exists)
        app.buttons["return-final-game-home"].tap()
        XCTAssertTrue(app.buttons["start-new-game"].waitForExistence(timeout: 5))
        let saved = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "completed-game-")).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 5)); saved.tap()
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars["赛后记录去向"].exists, "Opening the saved result must not present a second automatic prompt")
    }

    func testV312IPadPDFShareDismissalReturnsToPreviewAndResult() throws {
        try requireV312IPad()
        let app = XCUIApplication()
        app.launchArguments = ["--boxscore-preview"]
        app.launch()
        rotateV312(app, landscape: true)
        let export = app.buttons["share-box-score"]
        revealStatisticsControl(export, in: app); export.tap()
        XCTAssertTrue(app.navigationBars["比赛战报"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["report-pdf-page-count"].label.contains("A4"))
        app.buttons["share-report-pdf"].tap()
        let activity = app.otherElements["ActivityListView"]
        XCTAssertTrue(activity.waitForExistence(timeout: 5), app.debugDescription)
        captureHistory(app, name: "3.1.2-ipad-landscape-pdf-share")
        // iPad activity presentation dismisses by tapping outside its popover/sheet.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.5)).tap()
        XCTAssertTrue(activity.waitForNonExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(app.buttons["close-report-pdf"].isHittable)
        rotateV312(app, landscape: false)
        app.buttons["close-report-pdf"].tap()
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5))
        XCTAssertTrue(export.exists, "Dismissing sharing and preview must retain the saved result")
    }
}

extension BaseballMasterUITests {
    private func enterV312BulkRoster(_ text: String, in app: XCUIApplication) {
        let input = app.textViews["bulk-roster-input"]
        XCTAssertTrue(input.waitForExistence(timeout: 5))
        input.tap(); input.typeText(text)
        let preview = app.buttons["preview-bulk-roster"]
        captureHistory(app, name: "3.1.2-bulk-after-input-before-preview")
        print("Bulk preview: exists=\(preview.exists), enabled=\(preview.isEnabled), hittable=\(preview.isHittable), frame=\(preview.frame), app=\(app.frame)")
        if preview.isHittable {
            preview.tap()
        } else {
            let keyboardPreview = app.buttons["keyboard-preview-bulk-roster"]
            XCTAssertTrue(keyboardPreview.waitForExistence(timeout: 5), "Focused input must expose the keyboard preview action")
            print("Bulk keyboard preview: enabled=\(keyboardPreview.isEnabled), hittable=\(keyboardPreview.isHittable), frame=\(keyboardPreview.frame)")
            XCTAssertTrue(keyboardPreview.isHittable)
            keyboardPreview.tap()
        }
    }

    private func assertV312SinglePlayerEditorIsDefault(in app: XCUIApplication) {
        XCTAssertTrue(app.navigationBars["添加球员"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["中文名"].exists)
        XCTAssertTrue(app.textFields["英文名"].exists)
        XCTAssertFalse(app.textViews["bulk-roster-input"].exists,
                       "Opening Add Player must continue to use the single-player editor")
        let bulk = app.buttons["open-bulk-roster"]
        revealStatisticsControl(bulk, in: app)
        XCTAssertTrue(bulk.isHittable); bulk.tap()
    }

    func testV312LocalBulkRosterRejectsWholeInvalidPreviewAndCancelAddsNothing() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--team-detail-preview"]
        app.launch()
        XCTAssertTrue(app.buttons["add-player"].waitForExistence(timeout: 5))
        let count = app.staticTexts["roster-player-count"]
        XCTAssertTrue(count.exists)
        let originalCount = count.label
        app.buttons["add-player"].tap()
        assertV312SinglePlayerEditorIsDefault(in: app)
        enterV312BulkRoster("未写入甲,,00\n错误乙,Bad,07", in: app)
        XCTAssertTrue(app.descendants(matching: .any)["bulk-roster-error"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["confirm-bulk-roster"].isEnabled,
                       "One invalid line must prevent committing the entire roster")
        captureHistory(app, name: "3.1.2-bulk-local-invalid-preview")
        app.buttons["cancel-bulk-roster"].tap()
        XCTAssertTrue(app.navigationBars["添加球员"].waitForExistence(timeout: 5))
        app.navigationBars["添加球员"].buttons["取消"].tap()
        XCTAssertTrue(app.buttons["add-player"].waitForExistence(timeout: 5))
        XCTAssertEqual(count.label, originalCount, "Invalid preview must not partially write valid rows")
        XCTAssertFalse(app.buttons["player-card-未写入甲"].exists,
                       "A valid row from a rejected batch must not have been added")
        XCTAssertFalse(app.buttons["player-card-错误乙"].exists)
        app.buttons["bulk-add-players"].tap()
        enterV312BulkRoster("取消甲,,98", in: app)
        XCTAssertTrue(app.buttons["confirm-bulk-roster"].isEnabled)
        app.buttons["cancel-bulk-roster"].tap()
        XCTAssertTrue(app.buttons["add-player"].waitForExistence(timeout: 5))
        XCTAssertEqual(count.label, originalCount, "Cancelling valid preview must leave the roster count unchanged")
        XCTAssertFalse(app.buttons["player-card-取消甲"].exists,
                       "Cancelling a valid preview must not add its players")
    }

    func testV312LocalBulkRosterConfirmsTwoPlayersWithOptionalEnglishAndDoubleZero() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--team-detail-preview"]
        app.launch()
        let bulk = app.buttons["bulk-add-players"]
        XCTAssertTrue(bulk.waitForExistence(timeout: 5))
        let count = app.staticTexts["roster-player-count"]
        let originalCount = try XCTUnwrap(Int(count.label))
        bulk.tap()
        enterV312BulkRoster("姓名,英文名,背号\n批量甲,,00\n批量乙,Bulk Two,0", in: app)
        let confirm = app.buttons["confirm-bulk-roster"]
        XCTAssertTrue(confirm.isEnabled)
        XCTAssertFalse(app.buttons["player-card-批量甲"].exists, "Preview must not write into the roster")
        confirm.tap()
        XCTAssertTrue(app.descendants(matching: .any)["bulk-roster-success"].firstMatch.waitForExistence(timeout: 5))
        app.buttons["close-bulk-roster"].tap()
        XCTAssertTrue(bulk.waitForExistence(timeout: 5))
        XCTAssertEqual(Int(count.label), originalCount + 2, "One confirmation must add exactly the two previewed players")
        let first = app.buttons["player-card-批量甲"]
        revealStatisticsControl(first, in: app)
        XCTAssertTrue(first.exists)
        XCTAssertTrue(first.label.contains("00"), "Double zero must remain distinct from zero")
        let second = app.buttons["player-card-批量乙"]
        revealStatisticsControl(second, in: app)
        XCTAssertTrue(second.exists)
        XCTAssertTrue(second.label.contains("Bulk Two"))
        captureHistory(app, name: "3.1.2-bulk-local-confirmed")
    }

    func testV312OpponentBulkRosterKeepsSingleDefaultCancelsAndConfirmsBatch() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--setup-preview"]
        app.launch()
        XCTAssertTrue(app.buttons["manage-opponents"].waitForExistence(timeout: 5))
        app.buttons["manage-opponents"].tap()
        app.buttons["opponent-card-飞鹰"].tap()
        XCTAssertTrue(app.buttons["add-opponent-player"].waitForExistence(timeout: 5))
        let count = app.staticTexts["roster-player-count"]
        let originalCount = try XCTUnwrap(Int(count.label))
        app.buttons["add-opponent-player"].tap()
        assertV312SinglePlayerEditorIsDefault(in: app)
        enterV312BulkRoster("对手取消甲,,99", in: app)
        XCTAssertTrue(app.buttons["confirm-bulk-roster"].isEnabled)
        app.buttons["cancel-bulk-roster"].tap()
        app.navigationBars["添加球员"].buttons["取消"].tap()
        XCTAssertTrue(app.buttons["add-opponent-player"].waitForExistence(timeout: 5))
        XCTAssertEqual(Int(count.label), originalCount, "Cancelling opponent preview must not add a player")
        XCTAssertFalse(app.staticTexts["对手取消甲"].exists)
        app.buttons["bulk-add-players"].tap()
        enterV312BulkRoster("对手批量甲,,00\n对手批量乙,Away Two,0", in: app)
        let confirm = app.buttons["confirm-bulk-roster"]
        XCTAssertTrue(confirm.isEnabled); confirm.tap()
        XCTAssertTrue(app.descendants(matching: .any)["bulk-roster-success"].firstMatch.waitForExistence(timeout: 5))
        app.buttons["close-bulk-roster"].tap()
        XCTAssertTrue(app.buttons["add-opponent-player"].waitForExistence(timeout: 5))
        XCTAssertEqual(Int(count.label), originalCount + 2, "Opponent batch confirmation must add exactly two players")
        let first = app.staticTexts["对手批量甲"].firstMatch
        revealStatisticsControl(first, in: app)
        XCTAssertTrue(first.exists)
        let second = app.staticTexts["对手批量乙"].firstMatch
        revealStatisticsControl(second, in: app)
        XCTAssertTrue(second.exists)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Away Two")).firstMatch.exists)
        XCTAssertFalse(app.staticTexts["对手取消甲"].exists)
        captureHistory(app, name: "3.1.2-bulk-opponent-confirmed")
    }
}

extension BaseballMasterUITests {
    @MainActor func testV312SharedBulkRosterPreviewDoesNotWriteAndConfirmationAddsAtomicBatch() async throws {
        let app = launchDeviceSecurityApp()
        let create = app.buttons["create-shared-team"]
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: create)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 8), .completed)
        create.tap()
        XCTAssertTrue(app.textFields["shared-team-name"].waitForExistence(timeout: 5))
        app.textFields["shared-team-name"].tap()
        app.textFields["shared-team-name"].typeText("批量共享验收队")
        app.textFields["shared-nickname"].tap()
        app.textFields["shared-nickname"].typeText("批量教练")
        let scope = app.buttons["confirm-sharing-scope"]
        revealStatisticsControl(scope, in: app); scope.tap()
        app.buttons["submit-shared-team"].firstMatch.tap()
        let deferCard = app.buttons["defer-recovery-card"]
        XCTAssertTrue(deferCard.waitForExistence(timeout: 8)); deferCard.tap()
        let team = app.buttons["shared-team-批量共享验收队"]
        XCTAssertTrue(team.waitForExistence(timeout: 8)); team.tap()

        // An independent member reads the real local service; no production credentials or endpoints are used.
        let invite = app.buttons["invite-team-member"]
        revealStatisticsControl(invite, in: app); invite.tap()
        let link = app.staticTexts["team-invitation-url"]
        XCTAssertTrue(link.waitForExistence(timeout: 8))
        let token = try XCTUnwrap(URL(string: link.label)?.fragment)
        let reader = sharingTestSecret()
        _ = try await sharingAPI(reader, "/devices", body: ["label": "批量验收只读设备"])
        let joined = try await sharingAPI(reader, "/invitations/redeem", body: [
            "operationID": UUID().uuidString, "token": token, "nickname": "批量核对成员"
        ])
        let teamID = try XCTUnwrap(joined["teamID"] as? String)
        app.buttons["完成"].firstMatch.tap()
        let initial = try await sharingAPI(reader, "/teams/\(teamID)", method: "GET")
        let initialProfile = try XCTUnwrap(initial["team"] as? [String: Any])
        let initialPlayers = try XCTUnwrap(initialProfile["players"] as? [[String: Any]])
        XCTAssertEqual(initialPlayers.count, 0)

        let bulk = app.buttons["open-shared-bulk-roster"]
        XCTAssertTrue(bulk.waitForExistence(timeout: 5))
        revealStatisticsControl(bulk, in: app); bulk.tap()
        enterV312BulkRoster("共享批量甲,,00\n共享批量乙,Shared Two,0", in: app)
        let confirm = app.buttons["confirm-bulk-roster"]
        XCTAssertTrue(confirm.isEnabled)
        let previewed = try await sharingAPI(reader, "/teams/\(teamID)", method: "GET")
        let previewProfile = try XCTUnwrap(previewed["team"] as? [String: Any])
        XCTAssertEqual((previewProfile["players"] as? [[String: Any]])?.count, 0,
                       "Previewing the batch must not write any shared players")
        XCTAssertEqual(previewProfile["revision"] as? Int, initialProfile["revision"] as? Int)
        revealStatisticsControl(confirm, in: app); confirm.tap()
        XCTAssertTrue(app.descendants(matching: .any)["bulk-roster-success"].firstMatch.waitForExistence(timeout: 10), app.debugDescription)
        app.buttons["close-bulk-roster"].tap()
        let first = app.buttons["edit-shared-player-共享批量甲"]
        XCTAssertTrue(first.waitForExistence(timeout: 8))
        revealStatisticsControl(first, in: app)
        XCTAssertTrue(app.staticTexts["#00"].exists, "Shared roster must retain the double-zero jersey")
        XCTAssertTrue(app.buttons["edit-shared-player-共享批量乙"].exists)

        let saved = try await sharingAPI(reader, "/teams/\(teamID)", method: "GET")
        let savedProfile = try XCTUnwrap(saved["team"] as? [String: Any])
        let savedPlayers = try XCTUnwrap(savedProfile["players"] as? [[String: Any]])
        XCTAssertEqual(savedPlayers.count, 2, "A single confirmation must add exactly the complete batch")
        let doubleZero = try XCTUnwrap(savedPlayers.first { $0["chineseName"] as? String == "共享批量甲" })
        XCTAssertEqual(doubleZero["numbers"] as? [String], ["00"])
        XCTAssertEqual(doubleZero["englishName"] as? String, "")
        let zero = try XCTUnwrap(savedPlayers.first { $0["chineseName"] as? String == "共享批量乙" })
        XCTAssertEqual(zero["numbers"] as? [String], ["0"])
        XCTAssertEqual(zero["englishName"] as? String, "Shared Two")
        captureHistory(app, name: "3.1.2-bulk-shared-confirmed-local-http")
    }
}

extension BaseballMasterUITests {
    func testV312StatisticsPlayerContactsUseSelectedGamesAndCanReadEachContact() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--v312-player-contacts-preview"]
        app.launch()
        let player = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "stats-player-")).firstMatch
        revealStatisticsControl(player, in: app)
        XCTAssertTrue(player.isHittable); player.tap()
        XCTAssertTrue(app.navigationBars["球员数据"].waitForExistence(timeout: 5))
        let coverage = app.staticTexts["player-contact-coverage"]
        revealStatisticsControl(coverage, in: app)
        XCTAssertEqual(coverage.label, "有去向 2 球 · 缺少去向 1 球")
        XCTAssertTrue(app.descendants(matching: .any)["player-contact-field"].firstMatch.exists)
        let disclosure = app.buttons["逐球查看（含重叠位置）"]
        revealStatisticsControl(disclosure, in: app); disclosure.tap()
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "player-contact-row-")).firstMatch
        revealStatisticsControl(row, in: app)
        XCTAssertTrue(row.isHittable); row.tap()
        let detail = app.staticTexts["player-selected-contact-detail"]
        revealStatisticsControl(detail, in: app)
        XCTAssertTrue(detail.label.contains("一垒安打"))
        if UIDevice.current.userInterfaceIdiom == .pad {
            rotateV312(app, landscape: true)
            revealStatisticsControl(detail, in: app)
            XCTAssertTrue(detail.exists)
        }
        captureHistory(app, name: "3.1.2-statistics-player-contact-field")
        let filter = app.buttons["open-game-filter"]
        revealStatisticsControl(filter, in: app); filter.tap()
        app.buttons["清空"].tap(); app.buttons["应用"].tap()
        revealStatisticsControl(coverage, in: app)
        XCTAssertEqual(coverage.label, "有去向 0 球 · 缺少去向 0 球")
        XCTAssertTrue(app.staticTexts["player-contact-empty"].exists)
        XCTAssertFalse(detail.exists, "Changing the selected games must clear the previous contact detail")
        revealStatisticsControl(filter, in: app); filter.tap()
        app.buttons["全选"].tap(); app.buttons["应用"].tap()
        revealStatisticsControl(coverage, in: app)
        XCTAssertEqual(coverage.label, "有去向 2 球 · 缺少去向 1 球")
        if UIDevice.current.userInterfaceIdiom == .pad { rotateV312(app, landscape: false) }
    }
}

// These tests require a Duo destination and a host driving the real DeviceHub
// posture controls. Set BM_DUO_TESTS=1 in the test-runner environment when the
// simulator does not export its model identifier. No app geometry is simulated.
extension BaseballMasterUITests {
    private func requireV312Duo() throws {
        let environment = ProcessInfo.processInfo.environment
        let isDuo = environment["SIMULATOR_MODEL_IDENTIFIER"] == "iPhone19,4"
        try XCTSkipUnless(isDuo || environment["BM_DUO_TESTS"] == "1",
                          "Requires iPhone Duo and a host operating its real posture controls")
        let documents = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first)
        try FileManager.default.createDirectory(at: documents, withIntermediateDirectories: true)
        for name in ["duo-phase.txt", "duo-host-step.txt"] {
            let url = documents.appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
        }
        print("DUO_RUNNER_DOCUMENTS=\(documents.path)")
    }

    /// Host reads phase, changes the actual device posture, captures DeviceHub
    /// evidence, then atomically writes the identical step to duo-host-step.txt.
    private func awaitDuoHost(_ step: String) throws {
        let documents = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first)
        let phase = documents.appendingPathComponent("duo-phase.txt")
        let acknowledgement = documents.appendingPathComponent("duo-host-step.txt")
        try step.write(to: phase, atomically: true, encoding: .utf8)
        print("DUO_AWAIT_HOST=\(step)")
        let hostReady = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (try? String(contentsOf: acknowledgement, encoding: .utf8)
                .trimmingCharacters(in: .whitespacesAndNewlines)) == step
        }, object: nil)
        // Host-driven posture and native input checks can span tool/UI handoffs.
        let result = XCTWaiter.wait(for: [hostReady], timeout: 1800)
        XCTAssertEqual(result, .completed, "Host did not acknowledge actual Duo posture step: \(step)")
        guard result == .completed else { throw NSError(domain: "DuoHostCheckpoint", code: 1) }
    }

    private func captureV312Duo(_ app: XCUIApplication, name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        print("DUO_OBSERVED=\(name), app=\(app.frame), window=\(app.windows.firstMatch.frame), screen=\(screenshot.image.size)")
    }

    private func revealV312DuoControl(_ element: XCUIElement, in app: XCUIApplication) {
        for towardTop in [true, false] {
            for _ in 0..<8 {
                let viewport = app.windows.firstMatch.frame
                if element.exists && element.isHittable && viewport.contains(element.frame) { return }
                let editor = app.descendants(matching: .any)["docked-situation-editor"].firstMatch
                let identifier = element.exists ? element.identifier : ""
                let editorContent = identifier.isEmpty || ["quick-outcome-", "contact-", "situation-fielder-", "situation-route", "finish-situation-"]
                    .contains { identifier.hasPrefix($0) }
                let editorScroll = editor.scrollViews.firstMatch
                let workbench = app.scrollViews["scorekeeping-wide-layout"]
                let scroll: XCUIElement
                if editorContent && editor.exists && editorScroll.exists && viewport.intersects(editorScroll.frame) {
                    // Once the inline panel is visible, scroll its flow rather than
                    // moving the whole workbench away from the active recording.
                    scroll = editorScroll
                } else if workbench.exists {
                    scroll = workbench
                } else {
                    scroll = app.scrollViews.firstMatch
                }
                let surface = scroll.exists ? scroll : app
                let start = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: towardTop ? 0.3 : 0.75))
                let end = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: towardTop ? 0.75 : 0.3))
                start.press(forDuration: 0.05, thenDragTo: end)
            }
        }
        XCTAssertTrue(element.exists && element.isHittable && app.windows.firstMatch.frame.contains(element.frame),
                      "Duo control must be reachable in its current window: \(element)\n\(app.debugDescription)")
    }

    private func assertV312DuoLaunch(_ app: XCUIApplication) {
        let brand = app.images["launch-fixed-bm"]
        let icon = app.images["launch-fixed-app-icon"]
        let skip = app.buttons["skip-launch-screen"]
        XCTAssertTrue(brand.waitForExistence(timeout: 5))
        XCTAssertTrue(icon.exists)
        let viewport = app.windows.firstMatch.frame
        XCTAssertTrue(viewport.contains(brand.frame), "Brand must fit the actual current window")
        XCTAssertTrue(viewport.contains(icon.frame), "App icon must fit the actual current window")
        XCTAssertTrue(skip.isHittable, "Skip must remain reachable after changing displays")
        XCTAssertTrue(app.staticTexts["launch-message"].label.contains("固定品牌 图文保持清晰"))
    }

    private func selectV312DuoTab(_ title: String, in app: XCUIApplication) {
        // Duo may expose root navigation in a vertical toolbar instead of tabBars.
        // Query any AX type: iOS 27.2 reports these controls as tabs, not buttons.
        let identifiers = ["比赛": "baseball.diamond.bases", "球队": "person.3.fill",
                           "统计": "chart.bar.fill", "我的": "person.crop.circle"]
        let button = app.descendants(matching: .any).matching(
            NSPredicate(format: "identifier == %@ AND label == %@", identifiers[title] ?? "", title)
        ).firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(button.isHittable, "Root navigation must be reachable in the current toolbar: \(title)")
        button.tap()
        XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5), app.debugDescription)
    }

    func testV312DuoLaunchAcrossDisplaysKeepsBrandAndNavigationReachable() throws {
        try requireV312Duo()
        try awaitDuoHost("launch-01-closed")
        let app = XCUIApplication()
        app.launchArguments = ["--launch-screen-custom-preview"]
        app.launch()
        assertV312DuoLaunch(app)
        captureV312Duo(app, name: "3.1.2-duo-launch-closed")
        for step in ["launch-02-open", "launch-03-partiallyOpen", "launch-04-closed"] {
            try awaitDuoHost(step)
            assertV312DuoLaunch(app)
            captureV312Duo(app, name: "3.1.2-duo-\(step)")
        }
        app.buttons["skip-launch-screen"].tap()
        let create = app.buttons["start-new-game"]
        XCTAssertTrue(create.waitForExistence(timeout: 5))
        XCTAssertTrue(create.isHittable)
        create.tap()
        XCTAssertTrue(app.navigationBars["新比赛"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["new-game-own-team"].isHittable)
        captureV312Duo(app, name: "3.1.2-duo-new-game-closed")
        let back = app.descendants(matching: .any)["BackButton"].firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(back.isHittable, "The system back control must be reachable in Duo's vertical toolbar")
        back.tap()
        for title in ["球队", "统计", "我的", "比赛"] { selectV312DuoTab(title, in: app) }
        try awaitDuoHost("launch-05-open-home")
        XCTAssertFalse(app.images["launch-fixed-bm"].exists, "Unfolding must not replay a dismissed launch screen")
        for title in ["球队", "统计", "我的", "比赛"] { selectV312DuoTab(title, in: app) }
        XCTAssertTrue(create.isHittable)
        captureV312Duo(app, name: "3.1.2-duo-home-open-navigation")
    }

    private func enterV312DuoContactDraft(in app: XCUIApplication) throws {
        let inPlay = app.buttons["ball-in-play"]
        inPlay.tap()
        if app.buttons["确认开赛"].waitForExistence(timeout: 1) { app.buttons["确认开赛"].tap() }
        XCTAssertTrue(app.descendants(matching: .any)["docked-situation-editor"].firstMatch.waitForExistence(timeout: 5))
        let single = app.buttons["quick-outcome-1B"]
        single.tap()
        let type = app.buttons["contact-type-line"]
        type.tap()
        try tapV312DuoContactPoint(in: app)
        let center = app.buttons["situation-fielder-8"]
        center.tap()
    }

    private func tapV312DuoContactPoint(in app: XCUIApplication) throws {
        if app.descendants(matching: .any)["docked-situation-editor"].firstMatch.exists {
            // XCUI element coordinates can disagree with the active Duo display.
            // The host taps the real visible canvas through Device Hub, then this
            // test checks the actual fielding stage and all subsequent draft state.
            try awaitDuoHost("draft-contact-" + UUID().uuidString)
            XCTAssertTrue(app.buttons["situation-fielder-8"].waitForExistence(timeout: 5),
                          "The host must select a real landing point before continuing")
            return
        }
        let field = app.otherElements["contact-field"]
        for _ in 0..<12 {
            let editor = app.descendants(matching: .any)["docked-situation-editor"].firstMatch
            let scroll = editor.exists ? editor.scrollViews.firstMatch : app.scrollViews.firstMatch
            let viewport = app.windows.firstMatch.frame
            let visible = scroll.exists ? viewport.intersection(scroll.frame) : viewport
            if field.exists {
                let fieldFrame = field.frame
                let location = CGPoint(x: fieldFrame.minX + fieldFrame.width * 0.3,
                                       y: fieldFrame.minY + fieldFrame.height * 0.3)
                // Duo's element-relative coordinates can report a rotated screen space.
                // Anchor in this window and verify the actual screen point.
                let origin = app.windows.firstMatch.coordinate(withNormalizedOffset: .zero)
                let originLocation = origin.screenPoint
                let point = origin.withOffset(CGVector(dx: location.x - originLocation.x,
                                                       dy: location.y - originLocation.y))
                let actual = point.screenPoint
                if field.isHittable && fieldFrame.contains(actual)
                    && visible.insetBy(dx: 4, dy: 4).contains(actual) {
                    XCTAssertEqual(actual.x, location.x, accuracy: 0.5)
                    XCTAssertEqual(actual.y, location.y, accuracy: 0.5)
                    print("DUO_CONTACT_POINT=\(actual), target=\(location), field=\(fieldFrame), visibleFlow=\(visible)")
                    point.tap()
                    XCTAssertTrue(app.buttons["situation-fielder-8"].waitForExistence(timeout: 5),
                                  "Tapping the visible landing point must enter the fielding stage")
                    return
                }
                let towardTop = location.y < visible.minY
                let surface = scroll.exists ? scroll : app
                let start = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: towardTop ? 0.3 : 0.75))
                let end = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: towardTop ? 0.75 : 0.3))
                start.press(forDuration: 0.05, thenDragTo: end)
            } else {
                scroll.swipeUp()
            }
        }
        XCTFail("The selected contact point must be visible inside the recording flow before tapping:\n\(app.debugDescription)")
    }

    private func revealExpandedDuoControl(_ element: XCUIElement, in app: XCUIApplication, towardTop: Bool = false) {
        let scroll = app.scrollViews["scorekeeping-wide-layout"]
        for _ in 0..<8 {
            if element.exists && element.isHittable && app.windows.firstMatch.frame.contains(element.frame) { return }
            if towardTop { scroll.swipeDown() } else { scroll.swipeUp() }
        }
        XCTAssertTrue(element.isHittable, "Expanded workbench control must be reachable: \(element.identifier)")
    }

    private func assertV312DuoContactDraft(in app: XCUIApplication, summary: String, route: String) {
        let summaryLabel = app.staticTexts["contact-summary"]
        XCTAssertTrue(summaryLabel.waitForExistence(timeout: 5))
        XCTAssertTrue(summaryLabel.label.contains("平飞"), "The selected ball type must survive display and posture changes")
        XCTAssertEqual(summaryLabel.label, summary, "Landing point must survive display and posture changes")
        XCTAssertEqual(app.staticTexts["situation-route"].label, route, "Fielding route must survive display and posture changes")
        let finish = app.buttons["finish-situation-field"]
        revealExpandedDuoControl(finish, in: app)
        XCTAssertTrue(finish.isHittable)
        XCTAssertTrue(finish.isEnabled)
        XCTAssertFalse(app.buttons["reset-practice-inning"].isEnabled,
                       "The parent practice reset must stay locked while a draft is open")
    }

    func testV312DuoProDraftSurvivesFoldingThenCancelAndOnePlayUndoRedo() throws {
        try requireV312Duo()
        try awaitDuoHost("draft-01-open")
        let app = XCUIApplication()
        app.launchArguments = ["--practice-preview", "-showPracticeGuidance", "NO"]
        app.launch()
        app.segmentedControls["recording-method-picker"].buttons["Pro 版"].tap()
        let start = app.buttons["start-practice-inning"]
        revealV312DuoControl(start, in: app)
        start.tap()
        let batter = app.descendants(matching: .any)["current-batter"].firstMatch
        let pitcher = app.descendants(matching: .any)["current-pitcher"].firstMatch
        XCTAssertTrue(batter.waitForExistence(timeout: 5))
        let before = batter.label
        XCTAssertTrue(pitcher.label.contains("本场已投 0 球"))
        XCTAssertTrue(app.scrollViews["scorekeeping-wide-layout"].exists, "The real unfolded display must provide the workbench")
        try awaitDuoHost("draft-record-1")
        let editor = app.descendants(matching: .any)["docked-situation-editor"].firstMatch
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        XCTAssertEqual(editor.scrollViews.count, 1, "Only the compact horizontal fielding-route rail may scroll inside the expanded editor")
        let routeRail = editor.scrollViews.firstMatch
        XCTAssertLessThan(routeRail.frame.height, 80, "The route rail must not become a vertical viewport that clips the recording field")
        XCTAssertGreaterThan(routeRail.frame.width, routeRail.frame.height)
        let summaryLabel = app.staticTexts["contact-summary"]
        let summary = summaryLabel.label
        let route = app.staticTexts["situation-route"].label
        XCTAssertTrue(summary.contains("平飞"), "Start the transition check with the explicitly selected line drive")
        XCTAssertTrue(route.hasPrefix("8"))
        captureV312Duo(app, name: "3.1.2-duo-draft-before-fold")
        for step in ["draft-02-closed", "draft-03-partiallyOpen", "draft-04-open"] {
            try awaitDuoHost(step)
            assertV312DuoContactDraft(in: app, summary: summary, route: route)
            captureV312Duo(app, name: "3.1.2-duo-\(step)")
        }
        revealExpandedDuoControl(app.buttons["cancel-situation-play"], in: app, towardTop: true)
        try awaitDuoHost("draft-cancel")
        XCTAssertTrue(editor.waitForNonExistence(timeout: 5))
        revealExpandedDuoControl(batter, in: app, towardTop: true)
        XCTAssertEqual(batter.label, before, "Cancelling a folded draft must not change the batter")
        XCTAssertTrue(pitcher.label.contains("本场已投 0 球"), "Cancelling must not record a pitch")
        XCTAssertTrue(app.buttons["reset-practice-inning"].isEnabled)
        revealExpandedDuoControl(app.buttons["ball-in-play"], in: app)
        try awaitDuoHost("draft-record-2")
        revealExpandedDuoControl(app.buttons["finish-situation-field"], in: app)
        try awaitDuoHost("draft-confirm-one")
        XCTAssertTrue(editor.waitForNonExistence(timeout: 5))
        revealExpandedDuoControl(batter, in: app, towardTop: true)
        XCTAssertNotEqual(batter.label, before)
        XCTAssertTrue(pitcher.label.contains("本场已投 1 球"), "One confirmation must add exactly one pitch")
        revealExpandedDuoControl(app.buttons["undo-last-play"], in: app)
        try awaitDuoHost("draft-undo")
        revealExpandedDuoControl(batter, in: app, towardTop: true)
        XCTAssertEqual(batter.label, before)
        XCTAssertTrue(pitcher.label.contains("本场已投 0 球"))
        revealExpandedDuoControl(app.buttons["redo-last-play"], in: app)
        try awaitDuoHost("draft-redo")
        revealExpandedDuoControl(batter, in: app, towardTop: true)
        XCTAssertNotEqual(batter.label, before)
        XCTAssertTrue(pitcher.label.contains("本场已投 1 球"), "Redo must restore one play without duplication")
        captureV312Duo(app, name: "3.1.2-duo-pro-one-play-undo-redo")
    }

    func testV312DuoOpenWorkbenchKeepsCoreControlsInFirstViewport() throws {
        try requireV312Duo()
        try awaitDuoHost("layout-01-open")
        let app = XCUIApplication()
        app.launchArguments = ["--practice-scorekeeping-preview", "-showPracticeGuidance", "YES"]
        app.launch()
        let field = app.descendants(matching: .any)["scorekeeping-field"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertTrue(app.scrollViews["scorekeeping-wide-layout"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["practice-mode-banner"].firstMatch.exists,
                      "Verify the real guided-practice screen shown in the user's feedback")
        // Deliberately do not scroll: core scoring must fit in the initial viewport.
        let viewport = app.windows.firstMatch.frame
        XCTAssertTrue(viewport.contains(field.frame), "The complete field must fit the first viewport: \(field.frame), window=\(viewport)")
        for identifier in ["current-pitcher", "current-batter", "pitch-坏球", "pitch-看振", "pitch-挥空", "pitch-界外",
                           "ball-in-play", "open-runner-events", "open-special-events", "open-state-correction",
                           "open-substitutions", "结束半局", "结束比赛"] {
            let control = app.descendants(matching: .any)[identifier].firstMatch
            XCTAssertTrue(control.exists, "Core content must exist without scrolling: \(identifier)")
            XCTAssertTrue(control.isHittable, "Core content must be reachable in the first viewport: \(identifier), frame=\(control.frame)")
            XCTAssertTrue(viewport.contains(control.frame), "Core content must fully fit: \(identifier), frame=\(control.frame), window=\(viewport)")
        }
        captureV312Duo(app, name: "3.1.2-duo-open-workbench-first-viewport-guidance")
    }

    func testV312DuoCompactProSheetKeepsDraftAcrossDisplaysAndRecordsOnePlay() throws {
        try requireV312Duo()
        try awaitDuoHost("sheet-01-closed")
        let app = XCUIApplication()
        app.launchArguments = ["--practice-preview", "-showPracticeGuidance", "NO"]
        app.launch()
        app.segmentedControls["recording-method-picker"].buttons["Pro 版"].tap()
        let start = app.buttons["start-practice-inning"]
        revealV312DuoControl(start, in: app)
        start.tap()
        let batter = app.descendants(matching: .any)["current-batter"].firstMatch
        let pitcher = app.descendants(matching: .any)["current-pitcher"].firstMatch
        XCTAssertTrue(batter.waitForExistence(timeout: 5))
        let before = batter.label
        XCTAssertTrue(pitcher.label.contains("本场已投 0 球"))
        XCTAssertFalse(app.scrollViews["scorekeeping-wide-layout"].exists,
                       "The real closed display must begin with compact scoring")
        let inPlay = app.buttons["ball-in-play"]
        revealV312DuoControl(inPlay, in: app)
        inPlay.tap()
        if app.buttons["确认开赛"].waitForExistence(timeout: 1) { app.buttons["确认开赛"].tap() }
        let single = app.buttons["quick-outcome-1B"]
        XCTAssertTrue(single.waitForExistence(timeout: 5))
        XCTAssertFalse(app.descendants(matching: .any)["docked-situation-editor"].firstMatch.exists,
                       "A draft opened on the outer display must use the compact sheet")
        revealV312DuoControl(single, in: app)
        single.tap()
        let type = app.buttons["contact-type-line"]
        revealV312DuoControl(type, in: app)
        type.tap()
        try tapV312DuoContactPoint(in: app)
        let center = app.buttons["situation-fielder-8"]
        revealV312DuoControl(center, in: app)
        center.tap()
        let summaryLabel = app.staticTexts["contact-summary"]
        revealV312DuoControl(summaryLabel, in: app)
        let summary = summaryLabel.label
        let route = app.staticTexts["situation-route"].label
        XCTAssertFalse(summary.isEmpty)
        XCTAssertTrue(route.hasPrefix("8"))
        captureV312Duo(app, name: "3.1.2-duo-compact-sheet-before-unfold")
        for step in ["sheet-02-open", "sheet-03-closed"] {
            try awaitDuoHost(step)
            XCTAssertFalse(app.descendants(matching: .any)["docked-situation-editor"].firstMatch.exists,
                           "An active compact sheet must retain its presentation across displays")
            revealV312DuoControl(type, in: app)
            XCTAssertEqual(type.value as? String, "已选择")
            revealV312DuoControl(summaryLabel, in: app)
            XCTAssertEqual(summaryLabel.label, summary, "The sheet's landing point must survive display changes")
            XCTAssertEqual(app.staticTexts["situation-route"].label, route,
                           "The sheet's fielding route must survive display changes")
            let finish = app.buttons["finish-situation-field"]
            revealV312DuoControl(finish, in: app)
            XCTAssertTrue(finish.isHittable)
            XCTAssertTrue(finish.isEnabled)
            captureV312Duo(app, name: "3.1.2-duo-\(step)")
        }
        let finish = app.buttons["finish-situation-field"]
        finish.tap()
        XCTAssertTrue(app.buttons["cancel-situation-play"].waitForNonExistence(timeout: 5))
        XCTAssertTrue(inPlay.waitForExistence(timeout: 5))
        revealV312DuoControl(batter, in: app)
        XCTAssertNotEqual(batter.label, before)
        XCTAssertTrue(pitcher.label.contains("本场已投 1 球"),
                      "Confirming the retained sheet must add exactly one pitch")
        let undo = app.buttons["undo-last-play"]
        revealV312DuoControl(undo, in: app)
        undo.tap()
        XCTAssertEqual(batter.label, before, "One undo must reverse the complete sheet submission")
        XCTAssertTrue(pitcher.label.contains("本场已投 0 球"))
        app.buttons["redo-last-play"].tap()
        XCTAssertNotEqual(batter.label, before)
        XCTAssertTrue(pitcher.label.contains("本场已投 1 球"))
        captureV312Duo(app, name: "3.1.2-duo-compact-sheet-confirmed-one-play")
    }

    func testV312DuoPDFSharingSurvivesUnfoldingAndReturnsToResult() throws {
        try requireV312Duo()
        try awaitDuoHost("share-01-closed")
        let app = XCUIApplication()
        app.launchArguments = ["--boxscore-preview"]
        app.launch()
        let export = app.buttons["share-box-score"]
        revealV312DuoControl(export, in: app)
        export.tap()
        XCTAssertTrue(app.navigationBars["比赛战报"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["report-pdf-page-count"].label.contains("A4"))
        app.buttons["share-report-pdf"].tap()
        let activity = app.otherElements["ActivityListView"]
        XCTAssertTrue(activity.waitForExistence(timeout: 5), app.debugDescription)
        captureV312Duo(app, name: "3.1.2-duo-pdf-share-closed")
        try awaitDuoHost("share-02-open")
        XCTAssertTrue(activity.waitForExistence(timeout: 5), "System sharing must remain presented after unfolding")
        captureV312Duo(app, name: "3.1.2-duo-pdf-share-open")
        let closeCandidates = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label IN %@ AND identifier != %@", ["Close", "关闭"], "close-report-pdf"))
            .allElementsBoundByIndex
        guard let close = closeCandidates.first(where: { $0.isHittable }) else {
            XCTFail("The system share sheet must expose its own reachable close control:\n\(app.debugDescription)")
            return
        }
        close.tap()
        XCTAssertTrue(activity.waitForNonExistence(timeout: 5), app.debugDescription)
        let closePreview = app.buttons["close-report-pdf"]
        XCTAssertTrue(closePreview.isHittable)
        closePreview.tap()
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5))
        XCTAssertTrue(export.exists, "Closing sharing and its preview must retain the saved result")
        captureV312Duo(app, name: "3.1.2-duo-pdf-returned-to-result")
    }
}

extension BaseballMasterUITests {
    @MainActor func testTeamBroadcastSourcesExistBeforeFirstPitchAndFollowPlayBall() {
        let app = XCUIApplication()
        app.launchArguments = ["--v30-sharing-preview", "--team-test-id=\(UUID().uuidString)",
                               "--v30-match-fixture", "--v30-match-unstarted"]
        app.launch()
        let sharing = app.buttons["open-team-sharing"]
        XCTAssertTrue(sharing.waitForExistence(timeout: 10)); sharing.tap()
        let create = app.buttons["create-shared-team"]
        XCTAssertTrue(create.waitForExistence(timeout: 8)); create.tap()
        app.buttons["shared-source-team"].tap()
        app.buttons["青岛海浪"].firstMatch.tap()
        app.textFields["shared-nickname"].tap()
        app.textFields["shared-nickname"].typeText("记录员")
        let confirm = app.buttons["confirm-sharing-scope"]
        revealStatisticsControl(confirm, in: app); confirm.tap()
        app.buttons["submit-shared-team"].firstMatch.tap()
        let recovery = app.buttons["defer-recovery-card"]
        XCTAssertTrue(recovery.waitForExistence(timeout: 8)); recovery.tap()
        let team = app.buttons["shared-team-青岛海浪"]
        XCTAssertTrue(team.waitForExistence(timeout: 8)); team.tap()
        let entry = app.buttons["open-team-broadcast-sources"]
        revealStatisticsControl(entry, in: app); entry.tap()
        for name in ["score", "batter", "away", "home"] {
            XCTAssertTrue(app.buttons["team-broadcast-source-" + name].waitForExistence(timeout: 8))
        }
        XCTAssertEqual(app.staticTexts["team-broadcast-current-match"].label, "暂无比赛转播")

        app.tabBars.buttons["比赛"].tap()
        let ongoing = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "continue-game-")).firstMatch
        XCTAssertTrue(ongoing.waitForExistence(timeout: 8)); ongoing.tap()
        let start = app.buttons["start-game-clock"]
        XCTAssertTrue(start.waitForExistence(timeout: 8)); start.tap()
        let play = app.alerts.buttons["确认开赛"]
        XCTAssertTrue(play.waitForExistence(timeout: 5)); play.tap()
        let pitch = app.buttons["pitch-坏球"]
        XCTAssertTrue(pitch.waitForExistence(timeout: 8)); pitch.tap()

        let broadcast = app.buttons["转播源"]
        XCTAssertTrue(broadcast.waitForExistence(timeout: 8)); broadcast.tap()
        let active = app.staticTexts.matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@",
                                                          "team-broadcast-current-match", "青岛海浪")).firstMatch
        XCTAssertTrue(active.waitForExistence(timeout: 15))
    }
}
