import SwiftUI

struct TeamScheduleNotice: Codable, Equatable, Identifiable {
    let teamID: UUID
    let id: UUID
    let startsAt: Int64
    let away: String
    let home: String
    let venue: String
    let cancelled: Bool
    var scoring: ScheduleRole? = nil
    let revision: Int
    var date: Date { Date(timeIntervalSince1970: Double(startsAt) / 1000) }
    var key: String { teamID.uuidString + "/" + id.uuidString }
}

struct TeamScheduleRequest: Codable, Equatable {
    var operationID = UUID()
    let expectedRevision: Int
    let startsAt: Int64
    let away: String
    let home: String
    let venue: String
    let cancelled: Bool
}

struct LocalSchedulePublication: Codable, Equatable {
    let teamID: UUID
    var revision = 0
    var cancelled = false
    var pending: TeamScheduleRequest?
    var scoringVersion: Int?
    var pendingFrame: ScheduleFrameSubmission?
}

private struct ScheduleFeed: Decodable { let schedules: [TeamScheduleNotice] }
private struct ScheduleReceipt: Decodable { let id: UUID; let teamID: UUID; let revision: Int }

extension GameStore {
    func scheduleTeam(for ownID: UUID?) -> SharedTeamSummary? {
        guard let ownID else { return nil }
        let cloudID = teamSharing.state.bindings.first { $0.localTeamID == ownID }?.cloudTeamID ?? ownID
        return teamSharing.teams.first { $0.id == cloudID && $0.role != .member }
    }

    var scheduleTeamChoices: [Team] {
        let localIDs = Set(teams.map(\.id))
        let bound = Set(teamSharing.state.bindings.filter { localIDs.contains($0.localTeamID) }.map(\.cloudTeamID))
        return teams + recordableTeams.filter { !localIDs.contains($0.id) && !bound.contains($0.id) }
    }

    var visibleTeamSchedules: [TeamScheduleNotice] {
        guard !teamSchedules.isEmpty, !teamSharing.state.requiresRecoveryReview else { return [] }
        let memberships = Set(teamSharing.teams.map(\.id))
        let local = Set(games.compactMap { game in game.schedulePublication.map { $0.teamID.uuidString + "/" + game.id.uuidString } })
        return teamSchedules.filter { memberships.contains($0.teamID) && !local.contains($0.key) && ($0.date >= Date() || $0.scoring?.active == true || $0.scoring?.scorer != nil && $0.scoring?.closed == false) }
            .sorted { $0.startsAt == $1.startsAt ? $0.key < $1.key : $0.startsAt < $1.startsAt }
    }

    func refreshTeamSchedules() async {
        guard !teamSharing.teams.isEmpty, !teamSharing.state.requiresRecoveryReview,
              let client = teamSharing.service as? TeamServiceClient else {
            scheduleFetchID = nil
            if !teamSchedules.isEmpty { teamSchedules = [] }
            if scheduleFeedError != nil { scheduleFeedError = nil }
            return
        }
        let generation = UUID()
        scheduleFetchID = generation
        do {
            let feed: ScheduleFeed = try await client.request("GET", path: "team-schedules", body: Optional<String>.none)
            guard scheduleFetchID == generation, !Task.isCancelled else { return }
            if teamSchedules != feed.schedules { teamSchedules = feed.schedules }
            if scheduleFeedError != nil { scheduleFeedError = nil }
        } catch {
            guard scheduleFetchID == generation, !Task.isCancelled else { return }
            if !teamSchedules.isEmpty { teamSchedules = [] }
            scheduleFeedError = "球队赛程暂时无法读取，下拉可重试。"
        }
    }

    func saveScheduleFields(_ value: StoredGame) throws {
        guard !requiresDataRecovery, let index = games.firstIndex(where: { $0.id == value.id }) else {
            throw TeamServiceError.message("本机比赛暂时无法保存。")
        }
        try persistenceStore.upsertGame(value)
        games[index] = value
    }

    func publishSchedule(_ id: UUID, date: Date, venue: String, cancelled: Bool = false) async throws {
        guard var game = games.first(where: { $0.id == id && $0.status == .scheduled && $0.sharedGameID == nil }),
              let team = scheduleTeam(for: game.ourTeamID), !teamSharing.state.requiresRecoveryReview else {
            throw TeamServiceError.message("当前球队或设备没有发布赛程的权限。")
        }
        var publication = game.schedulePublication ?? LocalSchedulePublication(teamID: team.id)
        guard publication.teamID == team.id else { throw TeamServiceError.message("球队关联已改变，请核对原赛程。") }
        if publication.pending == nil {
            let location = venue.trimmingCharacters(in: .whitespacesAndNewlines)
            guard location.utf16.count <= 120, !location.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else {
                throw TeamServiceError.message("场地请使用单行文字，最多 120 个字符。")
            }
            guard date > Date() || cancelled else { throw TeamServiceError.message("请先将开赛时间改到未来。") }
            publication.pending = TeamScheduleRequest(expectedRevision: publication.revision,
                startsAt: Int64((date.timeIntervalSince1970 * 1000).rounded()), away: game.state.awayTeam.name,
                home: game.state.homeTeam.name, venue: location, cancelled: cancelled)
            game.schedulePublication = publication
            try saveScheduleFields(game)
        }
        try await retrySchedulePublication(id)
    }

    func retrySchedulePublication(_ id: UUID) async throws {
        guard let publication = games.first(where: { $0.id == id })?.schedulePublication,
              let request = publication.pending, !teamSharing.state.requiresRecoveryReview,
              let client = teamSharing.service as? TeamServiceClient else { throw TeamServiceError.message("赛程暂时无法发布。") }
        let path = "teams/\(publication.teamID.uuidString.lowercased())/schedules/\(id.uuidString.lowercased())"
        let receipt: ScheduleReceipt
        do {
            receipt = try await client.request("PUT", path: path, body: request)
        } catch TeamServiceError.response(let status, let message) where status == 400 || status == 409 {
            // A rejected write has no effect. On conflict read the current revision before offering a new edit.
            let feed: ScheduleFeed = try await client.request("GET", path: "team-schedules", body: Optional<String>.none)
            if var latest = games.first(where: { $0.id == id }), latest.schedulePublication?.pending?.operationID == request.operationID {
                if let current = feed.schedules.first(where: { $0.id == id && $0.teamID == publication.teamID }) {
                    latest.scheduledAt = current.date
                    latest.scheduleVenue = current.venue
                    latest.schedulePublication = LocalSchedulePublication(teamID: current.teamID, revision: current.revision, cancelled: current.cancelled)
                } else if publication.revision == 0 {
                    latest.schedulePublication = nil
                } else {
                    latest.schedulePublication?.pending = nil
                }
                try saveScheduleFields(latest)
            }
            throw TeamServiceError.message(message)
        }
        guard receipt.id == id, receipt.teamID == publication.teamID, receipt.revision == request.expectedRevision + 1 else {
            throw TeamServiceError.message("发布结果需要重新核对。")
        }
        guard var latest = games.first(where: { $0.id == id }), latest.schedulePublication?.pending?.operationID == request.operationID else { return }
        latest.schedulePublication = LocalSchedulePublication(teamID: publication.teamID, revision: receipt.revision, cancelled: request.cancelled)
        latest.scheduledAt = Date(timeIntervalSince1970: Double(request.startsAt) / 1000)
        latest.scheduleVenue = request.venue
        try saveScheduleFields(latest)
    }
}

struct SchedulePublicationControls: View {
    @EnvironmentObject var store: GameStore
    let game: StoredGame
    @State private var editing = false
    @State private var confirmingCancellation = false
    @State private var date = Date()
    @State private var venue = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        BMCard {
            VStack(alignment: .leading, spacing: 10) {
                if game.schedulePublication?.pending != nil {
                    Text("球队发布结果待确认").font(.headline)
                    Button("重试发布") { submit { try await store.retrySchedulePublication(game.id) } }
                } else {
                    Text(game.schedulePublication?.cancelled == true ? "球队赛程已取消" :
                            (game.schedulePublication == nil ? "赛程仅自己可见" : "赛程已发布给球队成员")).font(.headline)
                    Button(game.schedulePublication == nil ? "发布到球队赛程" : "更新球队赛程") {
                        date = max(game.effectiveScheduledAt, Date()); venue = game.scheduleVenue ?? ""; editing = true
                    }
                    if let publication = game.schedulePublication, !publication.cancelled {
                        Button("取消球队赛程", role: .destructive) { confirmingCancellation = true }
                    }
                }
                if let error { Text(error).font(.footnote).foregroundStyle(BMTheme.orange) }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
        .disabled(busy)
        .confirmationDialog("向全队显示这场赛程已取消？", isPresented: $confirmingCancellation) {
            Button("取消球队赛程", role: .destructive) {
                submit { try await store.publishSchedule(game.id, date: game.effectiveScheduledAt, venue: game.scheduleVenue ?? "", cancelled: true) }
            }
        }
        .sheet(isPresented: $editing) {
            NavigationStack {
                Form {
                    DatePicker("开赛时间", selection: $date, in: Date()...)
                    TextField("场地（选填）", text: $venue)
                }.navigationTitle("球队赛程").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("返回") { editing = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("发布") {
                            editing = false
                            submit { try await store.publishSchedule(game.id, date: date, venue: venue) }
                        }.disabled(venue.utf16.count > 120)
                    }
                }
            }
        }
    }

    private func submit(_ action: @escaping @MainActor () async throws -> Void) {
        busy = true; error = nil
        Task { @MainActor in
            defer { busy = false }
            do { try await action() } catch { self.error = error.localizedDescription }
        }
    }
}

struct ScheduleSaveStyle: ButtonStyle {
    let secondary: Bool
    @ViewBuilder func makeBody(configuration: Configuration) -> some View {
        if secondary { SecondaryButtonStyle().makeBody(configuration: configuration) }
        else { PrimaryButtonStyle().makeBody(configuration: configuration) }
    }
}

extension GameStore {
    func saveScheduleVenue(_ id: UUID, venue: String) {
        guard var game = games.first(where: { $0.id == id && $0.status == .scheduled }) else { return }
        game.scheduleVenue = venue
        do { try saveScheduleFields(game) } catch { storageErrorMessage = error.localizedDescription }
    }
}

enum UpcomingSchedule: Identifiable {
    case local(StoredGame)
    case team(TeamScheduleNotice)
    var id: String {
        switch self { case .local(let game): return game.id.uuidString; case .team(let notice): return notice.key }
    }
    var date: Date {
        switch self { case .local(let game): return game.effectiveScheduledAt; case .team(let notice): return notice.date }
    }
}

struct MemberScheduleRow: View {
    let notice: TeamScheduleNotice
    var body: some View {
        BMCard {
            HStack(spacing: 12) {
                Image(systemName: "calendar.badge.clock").foregroundStyle(BMTheme.green)
                VStack(alignment: .leading, spacing: 5) {
                    Text(notice.away + " 对 " + notice.home).font(.headline).foregroundStyle(BMTheme.navy)
                    if let scoring = notice.scoring { Text(scoring.description).font(.footnote) }
                    Text(notice.date.formatted(date: .abbreviated, time: .shortened)).font(.subheadline)
                    if !notice.venue.isEmpty { Label(notice.venue, systemImage: "mappin.and.ellipse").font(.footnote) }
                }.foregroundStyle(BMTheme.secondaryText)
                Spacer()
                Text(notice.cancelled ? "已取消" : "球队赛程").font(.caption.bold())
                    .foregroundStyle(notice.cancelled ? BMTheme.orange : BMTheme.green)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("team-schedule-" + notice.id.uuidString.lowercased())
    }
}
