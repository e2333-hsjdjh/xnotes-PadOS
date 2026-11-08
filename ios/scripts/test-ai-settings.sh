#!/bin/bash
set -euo pipefail
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <booted-iOS-simulator-UUID>" >&2
    exit 2
fi
simulator_id="$1"
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
test_root="$repo_root/tmp/ios/ai-settings-tests"
bundle_path="$test_root/AISettingsRegression.app"
mkdir -p "$bundle_path"
cat > "$bundle_path/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>com.xnotes.ios.ai-settings-regression</string>
<key>CFBundleExecutable</key><string>Regression</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleVersion</key><string>1</string>
<key>CFBundleDevelopmentRegion</key><string>en</string>
<key>CFBundleLocalizations</key><array><string>en</string><string>zh-Hans</string></array>
<key>CFBundleShortVersionString</key><string>1.0</string>
<key>MinimumOSVersion</key><string>17.0</string>
<key>CFBundleSupportedPlatforms</key><array><string>iPhoneSimulator</string></array>
<key>UIDeviceFamily</key><array><integer>1</integer><integer>2</integer></array>
<key>UIApplicationSceneManifest</key><dict><key>UIApplicationSupportsMultipleScenes</key><false/></dict>
</dict></plist>
PLIST
# Match Xcode's simulator link step: simulated entitlements belong in __TEXT,
# not in the host macOS code signature.
cat > "$test_root/SimulatedEntitlements.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict>
<key>application-identifier</key><string>com.xnotes.ios.ai-settings-regression</string>
</dict></plist>
PLIST
xcrun --sdk iphonesimulator swiftc -target arm64-apple-ios17.0-simulator \
    -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
    "$repo_root"/ios/XNotes/AI/*.swift \
    "$repo_root"/ios/XNotes/Storage/*.swift \
    "$repo_root"/ios/XNotes/Editor/{NotebookPageDrawing,NotebookCoverDrawing,PaperPatternDrawing,MusicPaperDrawing,StudyPaperDrawing,IsometricPaperDrawing,ScientificPaperDrawing,PDFTextMarkupDrawing,PageImageCrop,PDFTextSelection,PDFNavigation,DocumentHistory,NotebookPDFExport,PDFExportLinks}.swift \
    "$repo_root/ios/XNotes/App/UITokens.swift" \
    "$repo_root/ios/Tests/AISettingsRegression.swift" \
    "$repo_root/ios/Tests/AIModelKindsRegression.swift" \
    "$repo_root/ios/Tests/AIHTTPCompatibilityRegression.swift" \
    "$repo_root/ios/Tests/AIChatRegression.swift" \
    "$repo_root/ios/Tests/AIMarkdownRegression.swift" \
    "$repo_root/ios/Tests/PDFSelectionRegression.swift" \
    "$repo_root/ios/Tests/PDFExportLinkRegression.swift" \
    "$repo_root/ios/Tests/WorkspaceRegression.swift" \
    "$repo_root/ios/Tests/AIImageRegression.swift" \
    -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __entitlements -Xlinker "$test_root/SimulatedEntitlements.plist" \
    -o "$bundle_path/Regression"
cp -R "$repo_root/ios/XNotes/zh-Hans.lproj" "$bundle_path/"
codesign --force --sign - "$bundle_path"
xcrun simctl install "$simulator_id" "$bundle_path"
trap 'xcrun simctl uninstall "$simulator_id" com.xnotes.ios.ai-settings-regression >/dev/null 2>&1 || true' EXIT
xcrun simctl launch --console --terminate-running-process "$simulator_id" com.xnotes.ios.ai-settings-regression "$test_root" -AppleLanguages '(zh-Hans)' | tee "$test_root/Result.log"
rg -q '^PASS AI settings native light and dark screenshots' "$test_root/Result.log"
rg -q '^PASS AI chat and history native light and dark screenshots' "$test_root/Result.log"
rg -q '^PASS AI images orientation' "$test_root/Result.log"
rg -q '^PASS AI image attachments and screenshot preview native screenshots' "$test_root/Result.log"
rg -q '^PASS Markdown headings' "$test_root/Result.log"
rg -q '^PASS PDF free selection' "$test_root/Result.log"
rg -q '^PASS PDF exported URL' "$test_root/Result.log"

rg -q '^PASS workspace canvas solo' "$test_root/Result.log"

rg -q '^PASS AI chat and image model groups' "$test_root/Result.log"

rg -q '^PASS AI OpenAI token field' "$test_root/Result.log"
