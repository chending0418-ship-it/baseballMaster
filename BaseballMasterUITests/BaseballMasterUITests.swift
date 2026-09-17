import XCTest

final class BaseballMasterUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testWholeGameAndSingleAppearancePDFCanBePreviewed() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--boxscore-preview"]
        app.launch()
        app.buttons["记录"].tap()
        let single = app.buttons["export-appearance-3"]
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
        let single = app.buttons["export-appearance-text-3"]
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
        XCTAssertTrue(app.navigationBars["Browse"].waitForExistence(timeout: 3) || app.buttons["Browse"].exists || app.buttons["浏览"].exists || app.buttons["Cancel"].exists || app.buttons["取消"].exists)
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
        XCTAssertTrue(app.navigationBars["球队赛季报告"].waitForExistence(timeout: 5))
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
        app.buttons["2025 秋季"].tap()
        XCTAssertEqual(record.label, "1 场比赛 · 0胜1负0平")
        app.buttons["stats-team-picker"].tap()
        app.buttons["新建球队"].tap()
        XCTAssertEqual(record.label, "0 场比赛 · 0胜0负0平")
        let empty = app.staticTexts["这个赛季还没有已结束的比赛"]
        for _ in 0..<5 where !empty.isHittable { app.swipeUp() }
        XCTAssertTrue(empty.exists)
    }

    func testStatisticsCategoriesOpenPlayerWithSeasonAndGameFilter() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--statistics-preview"]
        app.launch()
        XCTAssertTrue(app.buttons["stats-season-picker"].waitForExistence(timeout: 5))
        app.buttons["stats-season-picker"].tap()
        app.buttons["2025 秋季"].tap()
        app.buttons["投手"].tap()
        let player = app.buttons["stats-player-陈昊"]
        for _ in 0..<6 where !player.isHittable { app.swipeUp() }
        XCTAssertTrue(player.isHittable)
        player.tap()
        XCTAssertTrue(app.navigationBars["球员数据"].waitForExistence(timeout: 3))
        let season = app.buttons["player-season-picker"]
        XCTAssertTrue((season.label + (season.value as? String ?? "")).contains("2025 秋季"))
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
        let empty = app.staticTexts["这个赛季还没有已结束的比赛"]
        for _ in 0..<5 where !empty.isHittable { app.swipeUp() }
        XCTAssertTrue(empty.exists)
        XCTAssertTrue(app.staticTexts["没有符合条件的球员"].exists)
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
        let firstAppearance = app.descendants(matching: .any).matching(identifier: "plate-appearance-1").firstMatch
        let secondAppearance = app.descendants(matching: .any).matching(identifier: "plate-appearance-2").firstMatch
        let thirdAppearance = app.descendants(matching: .any).matching(identifier: "plate-appearance-3").firstMatch
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
        app.buttons["取消"].tap()

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
        XCTAssertTrue(app.staticTexts["本赛季"].exists)
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
        app.launchArguments = ["--practice-preview"]
        app.launch()

        let start = app.buttons["start-practice-inning"]
        XCTAssertTrue(start.waitForExistence(timeout: 3))
        start.tap()

        XCTAssertTrue(app.staticTexts["练习模式 · 不会保存到正式数据"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["pitch-坏球"].exists)
        app.buttons["pitch-坏球"].tap()

        let reset = app.buttons["reset-practice-inning"]
        XCTAssertTrue(reset.exists)
        reset.tap()
        XCTAssertTrue(app.buttons["重新开始"].waitForExistence(timeout: 2))
        app.buttons["重新开始"].tap()
        XCTAssertTrue(app.buttons["pitch-坏球"].waitForExistence(timeout: 2))
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
        XCTAssertTrue(app.navigationBars["选择先发"].waitForExistence(timeout: 3))
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
        XCTAssertTrue(app.buttons["confirm-observed-lineup"].isEnabled)
    }
}
