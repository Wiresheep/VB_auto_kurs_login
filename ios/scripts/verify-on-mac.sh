#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$(uname -s)" != Darwin ]] || ! command -v xcodebuild >/dev/null; then
  echo "Für den iOS-App-Build werden macOS und Xcode benötigt." >&2
  exit 1
fi
xcode_major="$(xcodebuild -version | awk 'NR==1 {split($2,v,"."); print v[1]}')"
if [[ ! "$xcode_major" =~ ^[0-9]+$ ]] || (( xcode_major < 16 )); then
  echo "Dieses Projekt benötigt Xcode 16 oder neuer." >&2
  exit 1
fi
swift test --package-path KursfensterCore
swift scripts/validate-project.swift "$PWD"
xcodebuild -project Kursfenster.xcodeproj -scheme Kursfenster \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath DerivedData CODE_SIGNING_ALLOWED=NO build
