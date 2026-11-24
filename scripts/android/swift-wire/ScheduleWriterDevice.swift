
// Appended to the simulator test target by build-schedule-witness.py.
extension GameStoreTests {
    @MainActor func testAndroidScheduleCheckpointRoundTrip() async throws {
        let env = ProcessInfo.processInfo.environment
        let credential = try XCTUnwrap(env["BM314_DEVICE"])
        let port = try XCTUnwrap(env["BM314_PORT"])
        let team = try XCTUnwrap(env["BM314_TEAM"].flatMap(UUID.init(uuidString:)))
        let client = try TeamServiceClient(baseURL: URL(string:"http://127.0.0.1:" + port + "/api/v3")!,
                                            vault:V30MemoryTeamCredential(value:credential))
        let store = GameStore(persistenceURL:nil)
        store.teamSharing = TeamSharingStore(service:client,storage:V30DirectoryMemoryStorage())
        await store.teamSharing.connect()
        func until(_ predicate: () async throws -> Bool) async throws {
            for _ in 0..<300 {
                if try await predicate() { return }
                try await Task.sleep(nanoseconds:300_000_000)
            }
            XCTFail("等待 Android 设备超时")
            throw TeamServiceError.message("等待 Android 设备超时")
        }
        try await until {
            await store.refreshTeamSchedules()
            return store.visibleTeamSchedules.first?.scoring?.openingReady == true
        }
        let notice = try XCTUnwrap(store.visibleTeamSchedules.first)
        let key = ScheduleMatchKey(team:team,game:notice.id)
        try await store.enterScheduledScore(key)
        let opening = try await store.scoringStatus(key)
        try await store.scoringWrite(key,"confirmOpening",ScheduleWrite(epoch:opening.access.epoch,version:opening.version))
        _ = try await store.scoringStatus(key)
        store.recordIntentionalWalk()
        XCTAssertNotNil(store.game.baseRunners[.first])
        _ = try await store.synchronizeScheduledScore(key)
        try await until {
            _ = try await store.synchronizeScheduledScore(key)
            return store.game.baseRunners[.first]?.chineseName == "临场代跑"
        }
        let late = try XCTUnwrap(store.game.baseRunners[.first])
        XCTAssertTrue(store.recordRunnerEvent(.wildPitch,decisions:[RunnerDecision(player:late,origin:.base(.first),destination:.score)]))
        _ = try await store.synchronizeScheduledScore(key)
        let events = store.game.scoringEvents ?? []
        let log = store.game.playLog
        try await until { try await store.scoringStatus(key).access.transfer != nil }
        let transfer = try await store.scoringStatus(key)
        try await store.releaseScheduledScore(key,status:transfer)
        try await until {
            let s = try await store.scoringStatus(key)
            return s.access.hasCheckpoint == true && s.access.role == "scorer"
        }
        try await store.enterScheduledScore(key)
        XCTAssertEqual(Array(store.game.playLog.prefix(log.count)),log)
        XCTAssertEqual(Array((store.game.scoringEvents ?? []).prefix(events.count)),events)
        XCTAssertEqual(store.game.balls,1)
        XCTAssertEqual(store.battingLine(for:late).runs,1)
        store.recordPitch(.calledStrike)
        XCTAssertEqual(store.game.strikes,1)
        _ = try await store.synchronizeScheduledScore(key)
    }
}
