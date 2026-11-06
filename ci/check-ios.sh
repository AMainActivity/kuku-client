#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ "$(uname -s)" != Darwin ]; then
    echo "iOS validation requires macOS with Xcode and an installed iOS simulator runtime." >&2
    exit 2
fi
# Java and Android SDK must also match gradle/libs.versions.toml.
sh ./gradlew compileCommonMainKotlinMetadata iosSimulatorArm64Test --console=plain
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
    -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
    CODE_SIGNING_ALLOWED=NO build
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
    -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
    CODE_SIGNING_ALLOWED=NO build
