#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$(uname -s)" != Darwin ]] || ! command -v xcodebuild >/dev/null; then
  echo "Für den iPhone-Build werden macOS und Xcode benötigt." >&2
  exit 1
fi
xcode_major="$(xcodebuild -version | awk 'NR==1 {split($2,v,"."); print v[1]}')"
if [[ ! "$xcode_major" =~ ^[0-9]+$ ]] || (( xcode_major < 16 )); then
  echo "Für iPhone 16 wird Xcode 16 oder neuer benötigt." >&2
  exit 1
fi
sdk_major="$(xcrun --sdk iphoneos --show-sdk-version | cut -d. -f1)"
if [[ ! "$sdk_major" =~ ^[0-9]+$ ]] || (( sdk_major < 18 )); then
  echo "Für diesen Build wird ein iOS-SDK ab Version 18 benötigt." >&2
  exit 1
fi
build_root="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/kursfenster-ios.XXXXXX")"
trap 'rm -rf "$build_root"' EXIT
xcodebuild -project Kursfenster.xcodeproj -scheme Kursfenster \
  -configuration Release -destination 'generic/platform=iOS' \
  -archivePath "$build_root/Kursfenster.xcarchive" \
  -derivedDataPath "$build_root/DerivedData" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO archive
app="$build_root/Kursfenster.xcarchive/Products/Applications/Kursfenster.app"
if [[ ! -d "$app" ]]; then
  echo "Das Archiv enthält keine Kursfenster.app; keine IPA erstellt." >&2
  exit 1
fi
mkdir -p "$build_root/Payload" artifacts
ditto "$app" "$build_root/Payload/Kursfenster.app"
# The staging path comes from mktemp, never from a user-provided deletion path.
output_path="$PWD/artifacts/Kursfenster-unsigned.ipa"
rm -f "$output_path"
(cd "$build_root" && /usr/bin/zip -qr "$output_path" Payload)
(cd artifacts && shasum -a 256 Kursfenster-unsigned.ipa > Kursfenster-unsigned.ipa.sha256)
cat > artifacts/INSTALLATION.txt <<'NOTE'
Kursfenster – unsignierter iPhone-Build

Diese IPA ist für ein echtes iPhone gebaut und nicht für den Simulator.
Sie ist NICHT signiert und lässt sich noch nicht direkt auf einem iPhone installieren.
Es wurden keine Apple-Zertifikate, Provisioning-Profile oder Kontodaten verwendet.

Für die Installation muss die IPA anschließend mit einer geeigneten persönlichen
oder Developer-Signierung samt Gerätefreigabe versehen werden. Für TestFlight ist
eine kostenpflichtige Apple-Developer-Mitgliedschaft erforderlich.

Bei kostenloser persönlicher Signierung sind Apple-seitige Einschränkungen und
regelmäßige Erneuerungen (üblicherweise nach sieben Tagen) zu beachten. Nach Ablauf
funktionieren App und Automatik nicht weiter. Der konkrete Installationsweg ist separat
mit der iOS-Version und dem verwendeten Signierwerkzeug zu prüfen.

Ein erfolgreicher Build beweist keine zuverlässige Hintergrundausführung auf iOS.
Siehe ios/README.md im Repository.
NOTE
printf '%s\n' "Erstellt: artifacts/Kursfenster-unsigned.ipa (NICHT direkt installierbar)"
