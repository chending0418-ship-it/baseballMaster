import Foundation

enum PlayByPlayPDFStyle {
    case detailed
    case textOnly
}

struct PlayByPlayEntry {
    let appearance: PlateAppearanceRecord
    let events: [ScoringEventRecord]
    let isLast: Bool
    var before: GameSituationSnapshot? { appearance.events.first?.beforeSituation }
    var after: GameSituationSnapshot? { events.last?.afterSituation }
    var status: String {
        if appearance.needsReview { return "待确认" }
        if appearance.isComplete { return "打席已结束" }
        return isLast ? "打席未完成" : "打者更换或打席中断"
    }
}

@MainActor
struct PlayByPlayPDFReport {
    let game: GameState
    let appearances: [PlateAppearanceRecord]
    let playedAt: Date?
    var gameMode: GameMode = .standard

    var entries: [PlayByPlayEntry] {
        let all = (game.scoringEvents ?? []).filter { !$0.isPregameLineupAdjustment }
        let owners = Dictionary(uniqueKeysWithValues: appearances.flatMap { pa in pa.events.map { ($0.id, pa.id) } })
        var groups: [UUID: [ScoringEventRecord]] = [:]
        var owner = appearances.first?.id
        for (index, original) in all.enumerated() {
            if let next = owners[original.id] { owner = next }
            guard let owner else { continue }
            var event = original
            // The current state is authoritative for the latest event. Older
            // records retain their stored snapshots; missing snapshots stay nil.
            if index == all.count - 1, event.afterSituation != nil { event.afterSituation = game.situationSnapshot }
            groups[owner, default: []].append(event)
        }
        return appearances.enumerated().map { index, pa in
            PlayByPlayEntry(appearance: pa, events: groups[pa.id] ?? pa.events, isLast: index == appearances.count - 1)
        }
    }

    func pdfData(appearanceID: UUID? = nil, style: PlayByPlayPDFStyle = .detailed, generatedAt: Date = Date()) -> Data {
        if style == .textOnly { return textPDFData(appearanceID: appearanceID, generatedAt: generatedAt) }
        let selected = entries.filter { appearanceID == nil || $0.appearance.id == appearanceID }
        let title = appearanceID == nil ? "逐打席比赛速报" : "\(selected.first?.appearance.sequenceLabel ?? "单打席")速报"
        return ReportPDFCanvas.render(kind: "PLAY BY PLAY / 逐打席速报", title: title,
            subtitle: "\(game.awayTeam.name) vs \(game.homeTeam.name) · \(playedAt.map { ReportPDFCanvas.dateText($0, includesTime: true) } ?? "比赛时间未记录") · \(game.isFinal ? "已结束" : "进行中")" + (game.rulesSnapshot?.isSlowPitch == true ? " · " + (game.rulesSnapshot?.summary ?? "") : ""),
            generatedAt: generatedAt) { canvas in
            if game.statisticsIncomplete == true { canvas.paragraph("过程或责任待确认 · 统计可能不完整", bold: true) }
            if selected.isEmpty {
                canvas.paragraph("暂无可导出的逐打席记录", size: 16, bold: true)
                canvas.paragraph("旧记录若仅有中文日志而没有打席归属与局面快照，不能还原逐球局面。可在完整比赛记录中查看原文。", muted: true)
                let setupLogIDs = Set((game.scoringEvents ?? []).filter(\.isPregameLineupAdjustment).compactMap(\.logEntryID))
                let logs = game.playLog.filter { !setupLogIDs.contains($0.id) }
                if !logs.isEmpty {
                    canvas.table(title: "原始比赛记录", headers: ["局次", "记录"], rows: logs.map {
                        ["\($0.inning)局\($0.isTop ? "上" : "下")", $0.text]
                    }, weights: [1, 8])
                }
                return
            }
            if appearanceID == nil {
                canvas.metrics([("打席记录", "\(selected.count)"), ("客队得分", "\(game.awayScore)"), ("主队得分", "\(game.homeScore)"),
                                ("待确认打席", "\(selected.filter { $0.appearance.needsReview }.count)")])
                canvas.paragraph("按比赛时间顺序展示，主客队的打席分别从 1 累计，跨局继续。每个打席包含前后局面、逐球及跑垒记录；换人、计时、TB 和修正保留在发生位置。事件序号不等同于投球数。", muted: true)
                canvas.table(title: "打席索引", headers: ["打席 / 局次", "打者", "结果", "状态"], rows: selected.map {
                    ["\($0.appearance.teamLabel) \($0.appearance.sequence) · \($0.appearance.inningLabel)", $0.appearance.batter.compactName,
                     $0.appearance.resultText, $0.status]
                }, weights: [1.8, 2.3, 6, 1.8])
            }
            for (index, entry) in selected.enumerated() {
                if appearanceID == nil || index > 0 { canvas.newPage() }
                let pa = entry.appearance
                let label = "\(pa.sequenceLabel) · \(pa.inningLabel) · \(pa.batter.compactName)"
                canvas.paragraph(label, size: 17, bold: true)
                canvas.paragraph("\(entry.status)  |  结果：\(pa.resultText)", size: 10, bold: true)
                canvas.situationPair(before: entry.before, after: entry.after, game: game)
                canvas.table(title: label + " · 事件过程", headers: ["序号 / 类型", "逐球与比赛记录", "投手", "事件后 B/S/O · 比分（客:主）", "事件后垒况"], rows: entry.events.enumerated().map { index, event in
                    let snapshot = event.afterSituation
                    let pitcher = event.beforeSituation.map { $0.isTop ? $0.activeHomePitcherID : $0.activeAwayPitcherID } ?? nil
                    let detail = event.title + (event.needsReview ? "（待确认）" : "")
                        + (event.reviewNote.map { "\n复核：" + $0 } ?? "")
                    return ["\(index + 1) · \(event.category.title)", detail,
                            playerName(pitcher), snapshot.map { "\($0.balls)/\($0.strikes)/\($0.outs) · \($0.awayRunsByInning.reduce(0,+)):\($0.homeRunsByInning.reduce(0,+))\n\($0.inning)局\($0.isTop ? "上" : "下")" } ?? "未记录",
                            snapshot.map(baseText) ?? "未记录"]
                }, weights: [1.25, 5.5, 1.9, 2.65, 2.8])
                canvas.paragraph("B/S/O = 坏球 / 好球 / 出局。半局结束后的局面显示下一半局；自动保送、三振结果独立列出，不重复计为投球。未保存的球路、球速与局面不作推测。", size: 8, muted: true)
            }
        }
    }

    private func textPDFData(appearanceID: UUID?, generatedAt: Date) -> Data {
        let selected = entries.filter { appearanceID == nil || $0.appearance.id == appearanceID }
        let title = appearanceID == nil ? "逐打席文字简版" : "单打席文字简版"
        return ReportPDFCanvas.render(kind: "逐打席 / 文字简版", title: title,
            subtitle: "\(game.awayTeam.name) vs \(game.homeTeam.name) · \(playedAt.map { ReportPDFCanvas.dateText($0, includesTime: true) } ?? "比赛时间未记录") · \(game.isFinal ? "已结束" : "进行中")",
            generatedAt: generatedAt, pageSize: ReportPDFCanvas.portraitPageSize) { canvas in
            if game.rulesSnapshot?.isSlowPitch == true { canvas.paragraph(game.rulesSnapshot?.summary ?? "", size: 10, muted: true) }
            if game.statisticsIncomplete == true { canvas.paragraph("过程或责任待确认 · 统计可能不完整", bold: true) }
            guard !selected.isEmpty else {
                canvas.paragraph("暂无可导出的打席文字记录。", size: 11)
                return
            }
            for entry in selected {
                let pa = entry.appearance
                let status = pa.needsReview || !pa.isComplete ? " · \(entry.status)" : ""
                var pitcherID = entry.before.flatMap(self.pitcherID(in:))
                var descriptions = [gameMode == .coachPitch ? "投手不适用（教练投手）" : "投手：\(playerName(pitcherID))"]
                for event in entry.events {
                    if gameMode != .coachPitch, let before = event.beforeSituation,
                       before.inning == pa.inning, before.isTop == pa.isTop {
                        let actual = self.pitcherID(in: before)
                        if actual != pitcherID {
                            descriptions.append("投手：\(playerName(pitcherID)) → \(playerName(actual))")
                            pitcherID = actual
                        }
                    }
                    var detail = event.title + (event.needsReview ? "（待确认）" : "")
                        + (event.reviewNote.flatMap { $0.isEmpty ? nil : "（复核：\($0)）" } ?? "")
                    if gameMode != .coachPitch, let before = event.beforeSituation, let after = event.afterSituation,
                       before.inning == after.inning, before.isTop == after.isTop,
                       before.inning == pa.inning, before.isTop == pa.isTop,
                       self.pitcherID(in: before) != self.pitcherID(in: after) {
                        pitcherID = self.pitcherID(in: after)
                        detail += "（投手：\(playerName(self.pitcherID(in: before))) → \(playerName(pitcherID))）"
                    }
                    descriptions.append(detail)
                }
                let description = descriptions.joined(separator: "；")
                canvas.textRecord(title: "\(pa.inningLabel) · \(pa.sequenceLabel) · \(pa.batter.compactName)\(status)",
                                  body: description.isEmpty ? "暂无文字描述。" : description)
            }
        }
    }

    private func pitcherID(in snapshot: GameSituationSnapshot) -> UUID? {
        snapshot.isTop ? snapshot.activeHomePitcherID : snapshot.activeAwayPitcherID
    }

    private func playerName(_ id: UUID?) -> String {
        guard let id else { return "未记录" }
        return (game.homeTeam.players + game.awayTeam.players).first { $0.id == id }?.compactName ?? "历史球员"
    }

    private func baseText(_ snapshot: GameSituationSnapshot) -> String {
        if snapshot.baseRunners.isEmpty { return "垒上无人" }
        return snapshot.baseRunners.sorted { $0.base.rawValue < $1.base.rawValue }.map {
            "\($0.base.title)：\(playerName($0.playerID))"
        }.joined(separator: "\n")
    }

    func write(appearanceID: UUID? = nil, style: PlayByPlayPDFStyle = .detailed) throws -> URL {
        let suffix = style == .textOnly ? "-文字简版" : ""
        return try ReportExportFile.write(pdfData(appearanceID: appearanceID, style: style), name: "\(game.awayTeam.shortName)-vs-\(game.homeTeam.shortName)-\(appearanceID == nil ? "全场逐打席速报" : "单打席速报")\(suffix)")
    }
}
