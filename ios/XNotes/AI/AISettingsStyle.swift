import SwiftUI

private struct AISettingsRows: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    func body(content: Content) -> some View {
        content.listRowBackground(scheme == .dark ? Color.black : Color.white)
    }
}

private struct AISettingsSurface: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    private var foreground: Color { scheme == .dark ? .white : .black }
    private var background: Color { scheme == .dark ? .black : .white }
    func body(content: Content) -> some View {
        content
            .scrollContentBackground(.hidden)
            .background(background)
            .foregroundStyle(foreground)
            .tint(foreground)
            .environment(\.defaultMinListRowHeight, UITokens.minTouchTarget)
            .toolbarBackground(background, for: .navigationBar)
    }
}

extension View {
    func aiSettingsRows() -> some View { modifier(AISettingsRows()) }
    func aiSettingsSurface() -> some View { modifier(AISettingsSurface()) }
}
