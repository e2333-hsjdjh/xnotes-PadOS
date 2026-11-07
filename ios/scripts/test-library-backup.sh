#!/bin/bash
set -euo pipefail
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <booted-iOS-simulator-UUID>" >&2
    exit 2
fi
simulator_id="$1"
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
test_root="$repo_root/tmp/ios/library-backup-tests"
bundle_path="$test_root/LibraryBackupRegression.app"
mkdir -p "$bundle_path"
cat > "$bundle_path/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>com.xnotes.ios.library-backup-regression</string>
<key>CFBundleExecutable</key><string>Regression</string>
<key>CFBundlePackageType</key><string>APPL</string>
</dict></plist>
PLIST
xcrun --sdk iphonesimulator swiftc -target arm64-apple-ios17.0-simulator \
    -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
    "$repo_root"/ios/XNotes/Storage/*.swift \
    "$repo_root/ios/Tests/LibraryBackupRegression.swift" -o "$bundle_path/Regression"
xcrun simctl spawn "$simulator_id" "$bundle_path/Regression" "$test_root"
