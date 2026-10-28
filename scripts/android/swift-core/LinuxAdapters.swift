import Foundation
import COpenSSL
extension URL {
    func startAccessingSecurityScopedResource()->Bool {false}
    func stopAccessingSecurityScopedResource() {}
}
struct Color {}
enum BMTheme {static let green=Color();static let red=Color();static let orange=Color()}

// Linux reference harness only. In-memory/no-op platform adapters are NOT Core Data, UI,
// networking, Keychain, live or shared-match validation. Business source is unchanged.
enum SHA256 {
    static func hash(data: Data) -> [UInt8] {
        var bytes=[UInt8](repeating: 0,count:32)
        data.withUnsafeBytes { source in
            bytes.withUnsafeMutableBytes { target in
                _ = COpenSSL.SHA256(source.bindMemory(to: UInt8.self).baseAddress,data.count,target.bindMemory(to: UInt8.self).baseAddress)
            }
        }
        return bytes
    }
}
@MainActor protocol ObservableObject {}
@MainActor final class ObservableObjectPublisher {func send() {}}
@MainActor extension ObservableObject {var objectWillChange: ObservableObjectPublisher { ObservableObjectPublisher() }}
@propertyWrapper struct Published<Value> {
    var wrappedValue: Value
    init(wrappedValue: Value) {self.wrappedValue=wrappedValue}
}
@MainActor final class CoreDataRosterStore {
    static let schemaVersion=8
    static var writeFault: (() throws -> Void)?
    var loadedSchemaVersion: Int?=8
    var migratedLegacySchema=false
    let resolvedURL: URL?=nil
    private var snapshot: RosterSnapshot?
    init(storeURL: URL?, protectsUpgrade: Bool=true) throws {guard storeURL==nil else {throw LocalDataError.invalid("Linux cannot open Core Data")}}
    func loadSnapshot() throws -> RosterSnapshot? {snapshot}
    func close() throws {}
    static func replaceDatabase(at: URL,from: URL) throws {throw LocalDataError.invalid("Linux cannot replace Core Data")}
    static func copyStoreFamily(_ source: URL,to: URL) throws {throw LocalDataError.invalid("Linux cannot copy Core Data")}
    func replaceAll(with value: RosterSnapshot) throws {try Self.writeFault?();snapshot=value}
    func upsertTeam(_ team: Team,sortOrder: Int,isOpponent: Bool=false) throws {try Self.writeFault?()}
    func upsertGame(_ game: StoredGame) throws {try Self.writeFault?()}
    func deleteGame(id: UUID) throws {try Self.writeFault?()}
    func setCurrentTeamID(_ id: UUID?) throws {try Self.writeFault?()}
    func upsertPlayer(_ player: Player,rosterTeamID: UUID,sortOrder: Int) throws {try Self.writeFault?()}
    func deletePlayer(id: UUID) throws {try Self.writeFault?()}
}
enum AppFeatureAvailability {static let liveBroadcast=false}
enum StubRole {case admin,scorer,member}
struct StubTeam {let id: UUID;let role: StubRole}
struct StubSharedPlayer {let localPlayer: Player}
struct StubSharedProfile {let id: UUID;let players: [StubSharedPlayer]}
struct StubDirectoryState {var profiles: [StubSharedProfile]?=nil}
@MainActor final class TeamSharingStore {
    static func deviceStore()->TeamSharingStore {TeamSharingStore()}
    var onChange: (() -> Void)?
    var sharedRosterTeams: [Team]=[]
    var teams: [StubTeam]=[]
    var state=StubDirectoryState()
    func loadCachedDirectory() {}
}
struct StubMatchState {var matches: [SharedMatch]=[]}
@MainActor final class SharedMatchCoordinator {
    static func deviceStore(store: GameStore)->SharedMatchCoordinator {SharedMatchCoordinator()}
    var state=StubMatchState()
    func canEdit(_ id: UUID)->Bool {true}
    func isShared(_ id: UUID)->Bool {false}
    func saved(_ game: StoredGame) {}
    func detachAfterRestore() {}
}
enum ReportExportFile {static func url(name: String,extension: String) throws -> URL {throw LocalDataError.invalid("Linux report exports disabled")}}
enum LiveError: Error {case message(String)}
enum TeamServiceError: Error {case message(String)}
@MainActor final class LiveBroadcastManager {
    init(store: GameStore,enabled: Bool) {precondition(!enabled)}
    func close(_ id: UUID) {}
    func requestSync() {}
    func detachAfterRestore() {}
}
