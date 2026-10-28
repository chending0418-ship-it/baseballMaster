import Foundation

// Decodes with the application's unmodified Codable models, then checks their
// Equatable representation after re-encoding. This does not run GameStore or UI.
let arguments = CommandLine.arguments
precondition(arguments.count == 3, "usage: swift-wire INPUT_SNAPSHOT OUTPUT_SNAPSHOT")
let input = URL(fileURLWithPath: arguments[1])
let output = URL(fileURLWithPath: arguments[2])
do {
let snapshot = try JSONDecoder().decode(RosterSnapshot.self, from: Data(contentsOf: input))
let encoder = JSONEncoder()
encoder.outputFormatting = [.sortedKeys]
let encoded = try encoder.encode(snapshot)
let decoded = try JSONDecoder().decode(RosterSnapshot.self, from: encoded)
precondition(snapshot == decoded, "Swift typed snapshot changed on roundtrip")
// Check every opaque history checkpoint too; Data itself otherwise remains opaque to Codable.
var journals = 0
for game in snapshot.games {
    let history = [game.state.historyJournal].compactMap { $0 } + (game.state.correctionRevisions ?? []).map(\.previousJournal)
    for journal in history {
        let checkpoint = try JSONDecoder().decode(GameState.self, from: journal.checkpoint)
        let roundtrip = try JSONDecoder().decode(GameState.self, from: encoder.encode(checkpoint))
        precondition(checkpoint == roundtrip, "Swift history checkpoint changed on roundtrip")
        journals += 1
    }
}
try encoded.write(to: output, options: .atomic)
let report: [String: Any] = ["games": snapshot.games.count, "journals": journals,
    "operations": snapshot.games.reduce(0) { $0 + ($1.state.historyJournal?.operations.count ?? 0) },
    "corrections": snapshot.games.reduce(0) { $0 + ($1.state.correctionRevisions?.count ?? 0) }]
print(String(data: try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]), encoding: .utf8)!)

} catch {
    FileHandle.standardError.write(Data("Swift model decode failed: \(error)\n".utf8))
    exit(1)
}
