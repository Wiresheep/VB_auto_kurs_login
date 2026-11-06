#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$(uname -s)" != Darwin ]] || ! command -v xcodebuild >/dev/null; then
  echo "Für den iOS-App-Build werden macOS und Xcode benötigt." >&2
  exit 1
fi
swift test --package-path KursfensterCore
xcodebuild -project Kursfenster.xcodeproj -scheme Kursfenster \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath DerivedData CODE_SIGNING_ALLOWED=NO build
