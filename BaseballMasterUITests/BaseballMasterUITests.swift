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
        app.buttons["结束比赛"].tap()
        let reason = app.buttons["finish-reason-时间限制结束"]
        XCTAssertTrue(reason.waitForExistence(timeout: 5)); reason.tap()
        app.buttons["确认保存"].tap()
        let back = app.buttons["return-final-game-home"]
        XCTAssertTrue(back.waitForExistence(timeout: 5))
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
        XCTAssertTrue(app.navigationBars["比赛结果"].waitForExistence(timeout: 5))
        app.navigationBars["比赛结果"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["return-final-game-home"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["return-game-home"].isHittable)
        app.buttons["return-game-home"].tap()
        XCTAssertTrue(app.buttons["start-new-game"].waitForExistence(timeout: 5))
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
            revealStatisticsControl(playBall, in: app)
            XCTAssertTrue(playBall.isHittable)
            playBall.tap()
            XCTAssertTrue(app.alerts["确认双方阵容并开赛？"].waitForExistence(timeout: 5))
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
        app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", "看振", "pitch-看振")).firstMatch.tap()
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
        XCTAssertTrue(description.label.contains("只有你主动开启文字直播"))
        XCTAssertTrue(description.label.contains("本地比赛保留"))
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
        XCTAssertTrue(app.buttons["返回比赛"].waitForExistence(timeout: 3)); app.buttons["返回比赛"].tap()
        app.buttons["pitch-看振"].tap()
        app.buttons["open-substitutions"].tap(); app.buttons["substitution-pitcher"].tap()
        let original = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "赵一鸣")).firstMatch
        revealStatisticsControl(original, in: app); original.tap()
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
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "达到 2 局投球限制")).firstMatch.exists)
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
            XCTAssertEqual(warning.value as? String, longName ? "还有 3 球" : "最后一局")
            assertV111WarningBesidePitcher(app)
            captureV111(longName ? "V1.1.1-长投手姓名提醒-dark" : "V1.1.1-投手旁最后一局-dark", app: app)
            warning.tap()
            XCTAssertTrue(app.navigationBars["比赛规则提醒"].waitForExistence(timeout: 3))
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "允许的最后一局")).firstMatch.exists)
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
        XCTAssertTrue(app.navigationBars["球队赛季报告"].waitForExistence(timeout: 10))
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
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
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
        app.buttons["player-season-picker"].tap(); app.buttons["2025 秋季"].tap()
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
