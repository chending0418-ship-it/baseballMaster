import Foundation
import CryptoKit

enum LocalDataError: LocalizedError {
    case invalid(String)
    var errorDescription: String? {
        switch self { case .invalid(let reason): return reason }
    }
}

struct LocalBackup: Codable {
    let format: String
    let version: Int
    let createdAt: Date
    let payload: Data
    let checksum: String

    init(snapshot: RosterSnapshot, date: Date = Date()) throws {
        try Self.validate(snapshot)
        format = "BaseballMaster Backup"
        version = 1
        createdAt = date
        payload = try JSONEncoder().encode(snapshot)
        checksum = Self.digest(payload)
    }

    func snapshot() throws -> RosterSnapshot {
        guard format == "BaseballMaster Backup", version == 1 else {
            throw LocalDataError.invalid("备份格式或版本不受支持，请使用兼容的 App 版本。")
        }
        guard checksum == Self.digest(payload) else {
            throw LocalDataError.invalid("备份校验失败，文件可能已损坏。原有数据未修改。")
        }
        let snapshot = try JSONDecoder().decode(RosterSnapshot.self, from: payload)
        try Self.validate(snapshot)
        return snapshot
    }

    static func read(_ url: URL) throws -> LocalBackup {
        let granted = url.startAccessingSecurityScopedResource()
        defer { if granted { url.stopAccessingSecurityScopedResource() } }
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size <= 100_000_000 else { throw LocalDataError.invalid("备份超过 100 MB，请使用较小的备份文件。") }
        let backup = try JSONDecoder().decode(Self.self, from: Data(contentsOf: url))
        _ = try backup.snapshot()
        return backup
    }

    func write(to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(self).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func validate(_ snapshot: RosterSnapshot) throws {
        func require(_ condition: Bool, _ reason: String) throws {
            if !condition { throw LocalDataError.invalid("本地数据不完整：" + reason) }
        }
        func unique<T: Hashable>(_ values: [T]) -> Bool { Set(values).count == values.count }
        try require(!snapshot.teams.isEmpty && snapshot.teams.contains { $0.id == snapshot.currentTeamID }, "缺少当前球队")
        let teams = snapshot.teams + snapshot.opponentTeams
        try require(unique(teams.map(\.id)), "球队编号重复")
        try require(unique(teams.flatMap(\.players).map(\.id)), "球员编号重复")
        try require(unique(snapshot.seasons.map(\.id)), "赛季编号重复")
        try require(snapshot.seasons.allSatisfy { !$0.id.isEmpty && !$0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }, "赛季资料缺失")
        try require(unique(snapshot.games.map(\.id)), "比赛编号重复")
        try require(unique(snapshot.playerGameRecords.map(\.id)), "个人记录编号重复")
        for team in teams {
            try require(!team.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "球队名称为空")
            for player in team.players {
                try require(player.numbers.allSatisfy { (0...99).contains($0) }, "球员背号无效")
            }
        }
        for record in snapshot.playerGameRecords {
            try validateCounts(record.batting)
        }
        for stored in snapshot.games {
            let game = stored.state
            try require(game.homeTeam.id != game.awayTeam.id, "比赛双方相同")
            try require((1...10_000).contains(game.inning) && (1...10_000).contains(game.scheduledInnings), "局数无效")
            try require((0...4).contains(game.balls) && (0...3).contains(game.strikes) && (0...3).contains(game.outs), "球数或出局数无效")
            try require((0...1_000_000).contains(game.homeBatterIndex) && (0...1_000_000).contains(game.awayBatterIndex), "打序无效")
            try require((1...10_000).contains(stored.rules.scheduledInnings) && (1...99).contains(stored.rules.fieldersCount), "比赛规则无效")
            for limit in [stored.rules.timeLimitMinutes, stored.rules.timeWarningMinutes, stored.rules.pitchLimit, stored.rules.pitchWarningRemaining, stored.rules.pitcherInningsLimit].compactMap({ $0 }) {
                try require((0...1_000_000).contains(limit), "比赛限制无效")
            }
            if let elapsed = game.clockElapsedSeconds {
                try require(elapsed.isFinite && (0...315_360_000).contains(elapsed), "计时无效")
            }
            try require(game.homeRunsByInning.count >= game.inning && game.awayRunsByInning.count >= game.inning, "逐局比分缺失")
            try require((game.homeRunsByInning + game.awayRunsByInning).allSatisfy { (0...1_000_000).contains($0) }, "比分无效")
            try require(unique(game.homeTeam.players.map(\.id) + game.awayTeam.players.map(\.id)), "比赛球员编号重复")
            for (team, order) in [(game.homeTeam, game.homeBattingOrderIDs), (game.awayTeam, game.awayBattingOrderIDs)] {
                try require(unique(order) && Set(order).isSubset(of: Set(team.players.map(\.id))), "打序球员无效")
                if stored.status != .scheduled { try require(!team.players.isEmpty && !order.isEmpty, "已开始比赛缺少名单") }
            }
            try require(Set(game.baseRunners.values.map(\.id)).count == game.baseRunners.count, "跑者重复")
            try require(game.baseRunners.values.allSatisfy { runner in game.battingTeam.players.contains { $0.id == runner.id } }, "垒上球员不存在")
            for value in game.batting.values { try validateCounts(value) }
            for value in game.pitching.values { try validateCounts(value) }
            for value in game.fielding.values { try validateCounts(value) }
            try require(unique((game.scoringEvents ?? []).map(\.id)), "事件编号重复")
        }
    }

    private static func validateCounts<T>(_ line: T) throws {
        for child in Mirror(reflecting: line).children {
            if let value = child.value as? Int, !(0...1_000_000).contains(value) {
                throw LocalDataError.invalid("统计值超出支持范围，原有数据未修改。")
            }
        }
    }
}
