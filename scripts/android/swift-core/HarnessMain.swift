import Foundation
@main struct Harness {
    @MainActor static func main() {
        do { try run() } catch {
            FileHandle.standardError.write(Data(("Swift reference check failed: \(error.localizedDescription)\n").utf8))
            exit(1)
        }
    }
    @MainActor static func run() throws {
        precondition(SHA256.hash(data:Data("abc".utf8)).map {String(format:"%02x",$0)}.joined()=="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        let args=CommandLine.arguments
        if args.count==4 && args[1]=="--scenario" {
            try ScenarioHarness.run(input:URL(fileURLWithPath:args[2]),output:URL(fileURLWithPath:args[3]));return
        }
        guard args.count==4 else {throw LocalDataError.invalid("usage: harness INPUT_SNAPSHOT OUTPUT_SNAPSHOT REPORT")}
        let snapshot=try JSONDecoder().decode(RosterSnapshot.self,from:Data(contentsOf:URL(fileURLWithPath:args[1])))
        var games:[StoredGame]=[]
        var results:[[String:Any]]=[]
        for stored in snapshot.games {
            guard stored.sharedGameID==nil,stored.sharedTeamID==nil,stored.isSpectator != true,stored.state.historyJournal != nil else {
                throw LocalDataError.invalid("Harness accepts local modern journal games only")
            }
            let store=GameStore(persistenceURL:nil)
            store.isHistoryReplay=true
            store.activeGameID=nil
            store.game=stored.state
            store.games=[stored]
            store.activeGameID=stored.id
            let draft=try store.makeCorrectionDraft()
            let preview=store.previewCorrection(draft)
            guard preview.canSave else {throw LocalDataError.invalid("Original GameStore replay: \(preview.conflict ?? "unknown")")}
            let scoreMatches=stored.state.historyScoreEquivalent(to:preview.game)
            let oldEvents=Set((stored.state.scoringEvents ?? []).map(\.id)),newEvents=Set((preview.game.scoringEvents ?? []).map(\.id))
            let oldAppearances=Set((stored.state.plateAppearances ?? []).map(\.id)),newAppearances=Set((preview.game.plateAppearances ?? []).map(\.id))
            let idsPreserved=draft.journal.operations.map(\.id)==preview.journal.operations.map(\.id)
            // Original iOS actions may stamp their event just after the journal
            // command. Original replay uses the command time. Expose exact
            // equality separately; allow at most 1 ms for this reference check.
            var normalizedEvents=preview.game.scoringEvents ?? []
            let sourceEvents=stored.state.scoringEvents ?? []
            var eventTimes=true,maximumTimestampDelta=0.0
            if normalizedEvents.count != sourceEvents.count {eventTimes=false}
            for index in normalizedEvents.indices where index<sourceEvents.count {
                let delta=abs(normalizedEvents[index].timestamp.timeIntervalSince(sourceEvents[index].timestamp))
                maximumTimestampDelta=max(maximumTimestampDelta,delta)
                if delta>0.001 {eventTimes=false}
                normalizedEvents[index].timestamp=sourceEvents[index].timestamp
            }
            var normalizedLogs=preview.game.playLog
            var logTimes=normalizedLogs.count==stored.state.playLog.count
            for index in normalizedLogs.indices where index<stored.state.playLog.count {
                let delta=abs(normalizedLogs[index].timestamp.timeIntervalSince(stored.state.playLog[index].timestamp))
                maximumTimestampDelta=max(maximumTimestampDelta,delta)
                if delta>0.001 {logTimes=false}
                normalizedLogs[index].timestamp=stored.state.playLog[index].timestamp
            }
            var result=stored
            result.state=preview.game
            result.state.historyJournal=preview.journal
            result.state.correctionRevisions=stored.state.correctionRevisions
            games.append(result)
            results.append(["id":stored.id.uuidString,"scoreMatches":scoreMatches,"operationIDsPreserved":idsPreserved,
                "sourceEventIDsPreserved":oldEvents.isSubset(of:newEvents),"sourceAppearanceIDsPreserved":oldAppearances.isSubset(of:newAppearances),
                "sourceEvents":oldEvents.count,"replayedEvents":newEvents.count,"sourceAppearances":oldAppearances.count,"replayedAppearances":newAppearances.count,
                "operations":preview.journal.operations.count,
                "eventsEqual":stored.state.scoringEvents==preview.game.scoringEvents,
                "logsEqual":stored.state.playLog==preview.game.playLog,
                "eventsEqualWithinOneMillisecond":eventTimes && sourceEvents==normalizedEvents,
                "logsEqualWithinOneMillisecond":logTimes && stored.state.playLog==normalizedLogs,
                "maximumTimestampDeltaSeconds":maximumTimestampDelta,
                "appearancesEqual":stored.state.plateAppearances==preview.game.plateAppearances,
                "journalEqual":draft.journal==preview.journal,
                "checkpointPreserved":draft.journal.checkpoint==preview.journal.checkpoint])
        }
        let result=RosterSnapshot(teams:snapshot.teams,opponentTeams:snapshot.opponentTeams,currentTeamID:snapshot.currentTeamID,
            seasons:snapshot.seasons,playerGameRecords:snapshot.playerGameRecords,games:games,archivedTeams:snapshot.archivedTeams)
        let encoder=JSONEncoder();encoder.outputFormatting=[.sortedKeys]
        try encoder.encode(result).write(to:URL(fileURLWithPath:args[2]),options:.atomic)
        try JSONSerialization.data(withJSONObject:results,options:[.sortedKeys,.prettyPrinted]).write(to:URL(fileURLWithPath:args[3]),options:.atomic)
        print("original GameStore replayed \(games.count) game(s)")
    }
}
