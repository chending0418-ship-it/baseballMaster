import SwiftUI

struct TeamMark: View {
    let team: Team
    var size: CGFloat = 48

    var body: some View {
        ZStack {
            Circle()
                .fill(BMTheme.brandNavy)
            Text(String(team.shortName.prefix(1)))
                .font(.system(size: size * 0.42, weight: .black, design: .rounded))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

struct ScoreboardCard: View {
    let game: DemoGameState

    var body: some View {
        BMCard {
            VStack(spacing: 12) {
                HStack {
                    inningBadge
                    Spacer()
                    Text(game.isFinal ? "比赛结束" : "正在记录")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(game.isFinal ? BMTheme.secondaryText : BMTheme.green)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background((game.isFinal ? BMTheme.line : BMTheme.greenSoft).opacity(0.9))
                        .clipShape(Capsule())
                }

                HStack(spacing: 12) {
                    teamScore(team: game.awayTeam, score: game.awayScore)
                    Text(":")
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .foregroundStyle(BMTheme.secondaryText)
                    teamScore(team: game.homeTeam, score: game.homeScore)
                }
            }
        }
    }

    private var inningBadge: some View {
        HStack(spacing: 6) {
            Image(systemName: game.isTop ? "arrowtriangle.up.fill" : "arrowtriangle.down.fill")
                .font(.system(size: 9))
            Text("\(game.inning)局 \(game.halfLabel)")
                .font(.system(size: 13, weight: .bold))
        }
        .foregroundStyle(BMTheme.navy)
    }

    private func teamScore(team: Team, score: Int) -> some View {
        HStack(spacing: 9) {
            TeamMark(team: team, size: 38)
            VStack(alignment: .leading, spacing: 2) {
                Text(team.shortName)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(BMTheme.secondaryText)
                Text("\(score)")
                    .font(.system(size: 34, weight: .black, design: .rounded))
                    .foregroundStyle(BMTheme.navy)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

struct CountStrip: View {
    let balls: Int
    let strikes: Int
    let outs: Int

    var body: some View {
        HStack(spacing: 20) {
            countGroup(label: "坏球 B", count: balls, maximum: 3, color: BMTheme.green)
            countGroup(label: "好球 S", count: strikes, maximum: 2, color: BMTheme.orange)
            countGroup(label: "出局 O", count: outs, maximum: 2, color: BMTheme.red)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(BMTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(BMTheme.line.opacity(0.7), lineWidth: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(balls)坏球，\(strikes)好球，\(outs)出局")
    }

    private func countGroup(label: String, count: Int, maximum: Int, color: Color) -> some View {
        VStack(spacing: 7) {
            Text(label)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(BMTheme.secondaryText)
            HStack(spacing: 5) {
                ForEach(0..<maximum, id: \.self) { index in
                    Circle()
                        .fill(index < count ? color : BMTheme.line)
                        .frame(width: 11, height: 11)
                }
            }
        }
    }
}

struct BaseballDiamondView: View {
    let game: DemoGameState

    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            ZStack {
                BMTheme.dirt.opacity(0.28)

                outfield(in: size)
                    .fill(BMTheme.field)

                outfield(in: size)
                    .stroke(BMTheme.dirt.opacity(0.9), lineWidth: 8)

                foulLines(in: size)
                    .stroke(.white.opacity(0.82), style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))

                infieldDirt(in: size)
                    .fill(BMTheme.dirt)

                innerGrass(in: size)
                    .fill(BMTheme.field.opacity(0.92))

                basePath(in: size)
                    .stroke(.white.opacity(0.72), lineWidth: 2)

                mound(in: size)

                ForEach(FieldPosition.allCases) { position in
                    if let player = position == .pitcher
                        ? Optional(game.currentPitcher)
                        : game.fieldingTeam.players.first(where: { $0.primaryPosition == position }) {
                        fielderMarker(player: player, position: position)
                            .position(fieldPosition(position, in: size))
                    }
                }

                baseMarker(.first)
                    .position(basePosition(.first, in: size))
                baseMarker(.second)
                    .position(basePosition(.second, in: size))
                baseMarker(.third)
                    .position(basePosition(.third, in: size))

                HomePlateShape()
                    .fill(.white)
                    .frame(width: 18, height: 18)
                    .position(point(0.50, 0.88, in: size))
                    .shadow(color: .black.opacity(0.12), radius: 2, y: 1)
            }
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(alignment: .topLeading) {
                Text("\(game.fieldingTeam.shortName)守备")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(.black.opacity(0.22))
                    .clipShape(Capsule())
                    .padding(10)
            }
            .overlay(alignment: .topTrailing) {
                Text(game.baseRunners.isEmpty ? "垒上无人" : "垒上 \(game.baseRunners.count) 人")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(game.baseRunners.isEmpty ? .white : BMTheme.brandNavy)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(game.baseRunners.isEmpty ? .black.opacity(0.22) : .white.opacity(0.9))
                    .clipShape(Capsule())
                    .padding(10)
            }
        }
        .frame(height: 260)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("完整棒球场，\(game.fieldingTeam.shortName)守备，垒上\(game.baseRunners.count)人")
    }

    private func outfield(in size: CGSize) -> Path {
        Path { path in
            path.move(to: point(0.50, 0.90, in: size))
            path.addLine(to: point(0.055, 0.25, in: size))
            path.addQuadCurve(
                to: point(0.945, 0.25, in: size),
                control: point(0.50, -0.09, in: size)
            )
            path.closeSubpath()
        }
    }

    private func foulLines(in size: CGSize) -> Path {
        Path { path in
            let home = point(0.50, 0.88, in: size)
            path.move(to: home)
            path.addLine(to: point(0.055, 0.25, in: size))
            path.move(to: home)
            path.addLine(to: point(0.945, 0.25, in: size))
        }
    }

    private func infieldDirt(in size: CGSize) -> Path {
        Path { path in
            path.move(to: point(0.50, 0.91, in: size))
            path.addLine(to: point(0.72, 0.68, in: size))
            path.addQuadCurve(to: point(0.50, 0.43, in: size), control: point(0.69, 0.48, in: size))
            path.addQuadCurve(to: point(0.28, 0.68, in: size), control: point(0.31, 0.48, in: size))
            path.closeSubpath()
        }
    }

    private func innerGrass(in size: CGSize) -> Path {
        Path { path in
            path.move(to: point(0.50, 0.80, in: size))
            path.addLine(to: point(0.62, 0.68, in: size))
            path.addLine(to: point(0.50, 0.55, in: size))
            path.addLine(to: point(0.38, 0.68, in: size))
            path.closeSubpath()
        }
    }

    private func basePath(in size: CGSize) -> Path {
        Path { path in
            path.move(to: point(0.50, 0.88, in: size))
            path.addLine(to: basePosition(.first, in: size))
            path.addLine(to: basePosition(.second, in: size))
            path.addLine(to: basePosition(.third, in: size))
            path.closeSubpath()
        }
    }

    private func mound(in size: CGSize) -> some View {
        ZStack {
            Circle()
                .fill(BMTheme.dirt)
                .frame(width: 34, height: 20)
            Capsule()
                .fill(.white.opacity(0.9))
                .frame(width: 14, height: 3)
        }
        .position(point(0.50, 0.69, in: size))
    }

    private func baseMarker(_ base: Base) -> some View {
        let runner = game.baseRunners[base]
        return ZStack {
            RoundedRectangle(cornerRadius: 3)
                .fill(runner == nil ? .white : BMTheme.green)
                .frame(width: runner == nil ? 16 : 31, height: runner == nil ? 16 : 31)
                .rotationEffect(.degrees(45))
                .shadow(color: .black.opacity(0.14), radius: 2, y: 1)
            if let runner {
                Text("#\(runner.number)")
                    .font(.system(size: 10, weight: .black, design: .rounded))
                    .foregroundStyle(.white)
            }
        }
        .accessibilityIdentifier("base-\(base.rawValue)-\(runner == nil ? "empty" : "occupied")")
        .accessibilityLabel(runner.map { "\(base.title)有\($0.name)" } ?? "\(base.title)无人")
    }

    private func fielderMarker(player: Player, position: FieldPosition) -> some View {
        let isPitcher = position == .pitcher
        return VStack(spacing: 1) {
            Text(position.shortName)
                .font(.system(size: position.shortName.count > 1 ? 9 : 11, weight: .black))
            Text("#\(player.number)")
                .font(.system(size: 8, weight: .bold, design: .rounded))
        }
        .foregroundStyle(isPitcher ? .white : BMTheme.brandNavy)
        .frame(width: 34, height: 34)
        .background(isPitcher ? BMTheme.brandNavy : .white.opacity(0.92))
        .clipShape(Circle())
        .overlay {
            Circle().stroke(isPitcher ? .white.opacity(0.7) : BMTheme.brandNavy.opacity(0.2), lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.16), radius: 2, y: 1)
        .accessibilityLabel("\(position.fullName)，\(player.name)")
    }

    private func basePosition(_ base: Base, in size: CGSize) -> CGPoint {
        switch base {
        case .first: point(0.67, 0.69, in: size)
        case .second: point(0.50, 0.50, in: size)
        case .third: point(0.33, 0.69, in: size)
        }
    }

    private func fieldPosition(_ position: FieldPosition, in size: CGSize) -> CGPoint {
        switch position {
        case .pitcher: point(0.50, 0.68, in: size)
        case .catcher: point(0.50, 0.955, in: size)
        case .firstBase: point(0.76, 0.64, in: size)
        case .secondBase: point(0.61, 0.54, in: size)
        case .thirdBase: point(0.24, 0.64, in: size)
        case .shortstop: point(0.39, 0.54, in: size)
        case .leftField: point(0.25, 0.31, in: size)
        case .centerField: point(0.50, 0.20, in: size)
        case .rightField: point(0.75, 0.31, in: size)
        }
    }

    private func point(_ x: CGFloat, _ y: CGFloat, in size: CGSize) -> CGPoint {
        CGPoint(x: size.width * x, y: size.height * y)
    }
}

private struct HomePlateShape: Shape {
    func path(in rect: CGRect) -> Path {
        Path { path in
            path.move(to: CGPoint(x: rect.minX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
            path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.midY))
            path.closeSubpath()
        }
    }
}

struct PlayerRow: View {
    let player: Player
    var order: Int? = nil
    var trailingText: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            if let order {
                Text("\(order)")
                    .font(.system(size: 15, weight: .black, design: .rounded))
                    .foregroundStyle(.white)
                    .frame(width: 32, height: 32)
                    .background(BMTheme.green)
                    .clipShape(Circle())
            } else {
                Text("\(player.number)")
                    .font(.system(size: 16, weight: .black, design: .rounded))
                    .foregroundStyle(BMTheme.navy)
                    .frame(width: 38, height: 38)
                    .background(BMTheme.greenSoft)
                    .clipShape(Circle())
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(player.name)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(BMTheme.navy)
                Text("#\(player.number) · \(player.primaryPosition.fullName)")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(BMTheme.secondaryText)
            }
            Spacer()
            if let trailingText {
                Text(trailingText)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(BMTheme.green)
            }
        }
        .contentShape(Rectangle())
    }
}

struct StatPill: View {
    let label: String
    let value: String
    var color: Color = BMTheme.navy

    var body: some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.system(size: 20, weight: .black, design: .rounded))
                .foregroundStyle(color)
            Text(label)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(BMTheme.secondaryText)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(BMTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

func statText(_ value: Double) -> String {
    let formatted = String(format: "%.3f", value)
    return value < 1 ? String(formatted.dropFirst()) : formatted
}
