import SwiftUI

/// Shared native layout values; do not scatter touch-target dimensions across screens.
enum UITokens {
    static let noSpacing: CGFloat = 0
    static let hairline: CGFloat = 1
    static let overlayOutline: CGFloat = 3
    static let cropHandle: CGFloat = 18
    static let attachmentPreview: CGFloat = 96
    static let minTouchTarget: CGFloat = 48
    static let secondaryText = Color(white: 0.5)
}
