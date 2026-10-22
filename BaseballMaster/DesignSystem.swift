import SwiftUI
import UIKit

enum BMTheme {
    static let background = dynamicColor(light: 0xF5F2EA, dark: 0x111820)
    static let surface = dynamicColor(light: 0xFFFEFA, dark: 0x1B2632)
    static let navy = dynamicColor(light: 0x102A43, dark: 0xE8F1F7)
    static let brandNavy = Color(uiColor: UIColor(hex: 0x102A43))
    static let brandGreen = Color(uiColor: UIColor(hex: 0x217A57))
    static let brandRed = Color(uiColor: UIColor(hex: 0xA8312B))
    static let brandOrange = Color(uiColor: UIColor(hex: 0x9F4C0D))
    static let secondaryText = dynamicColor(light: 0x607284, dark: 0xAAB8C5)
    static let green = dynamicColor(light: 0x217A57, dark: 0x48B889)
    static let greenSoft = dynamicColor(light: 0xE4F2EA, dark: 0x173D30)
    static let orange = dynamicColor(light: 0xA35513, dark: 0xF2A65A)
    static let orangeSoft = dynamicColor(light: 0xFFF0DE, dark: 0x49321C)
    static let red = dynamicColor(light: 0xC7463D, dark: 0xF17870)
    static let redSoft = dynamicColor(light: 0xFBE7E4, dark: 0x482422)
    static let line = dynamicColor(light: 0xDADFD9, dark: 0x31404D)
    static let field = dynamicColor(light: 0x2E765A, dark: 0x245F49)
    static let dirt = dynamicColor(light: 0xD7B785, dark: 0xA78356)

    static let cardRadius: CGFloat = 18
    static let smallRadius: CGFloat = 12
    static let horizontalPadding: CGFloat = 18

    private static func dynamicColor(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}

private extension UIColor {
    convenience init(hex: UInt32) {
        let red = CGFloat((hex >> 16) & 0xFF) / 255
        let green = CGFloat((hex >> 8) & 0xFF) / 255
        let blue = CGFloat(hex & 0xFF) / 255
        self.init(red: red, green: green, blue: blue, alpha: 1)
    }
}

struct BMCard<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .padding(16)
            .background(BMTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: BMTheme.cardRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: BMTheme.cardRadius, style: .continuous)
                    .stroke(BMTheme.line.opacity(0.75), lineWidth: 1)
            }
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    var color: Color = BMTheme.brandGreen

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(color.opacity(configuration.isPressed ? 0.78 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    var color: Color = BMTheme.navy

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(color)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(BMTheme.surface.opacity(configuration.isPressed ? 0.6 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(color.opacity(0.25), lineWidth: 1)
            }
    }
}

struct SectionHeader: View {
    let title: String
    var subtitle: String? = nil

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(BMTheme.navy)
            Spacer()
            if let subtitle {
                Text(subtitle)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(BMTheme.secondaryText)
            }
        }
    }
}

extension View {
    func bmScreenBackground() -> some View {
        background(BMTheme.background.ignoresSafeArea())
    }
}
