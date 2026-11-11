#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
app="$PWD/DerivedData/Build/Products/Debug-iphonesimulator/Kursfenster.app"
if [[ ! -d "$app" ]]; then
  echo "Zuerst verify-on-mac.sh ausführen: Simulator-App fehlt." >&2
  exit 1
fi
runtime="$(xcrun simctl list runtimes --json | python3 -c 'import json,sys; r=[x for x in json.load(sys.stdin)["runtimes"] if x.get("isAvailable") and x["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")]; print(max(r,key=lambda x:tuple(map(int,x["version"].split("."))))["identifier"])')"
device="$(xcrun simctl create "Kursfenster Appearance" com.apple.CoreSimulator.SimDeviceType.iPhone-16 "$runtime")"
cleanup() {
  xcrun simctl shutdown "$device" >/dev/null 2>&1 || true
  xcrun simctl delete "$device" >/dev/null 2>&1 || true
}
trap cleanup EXIT
xcrun simctl boot "$device"
xcrun simctl bootstatus "$device" -b
xcrun simctl status_bar "$device" override --time "9:41" --batteryState charged --batteryLevel 100
xcrun simctl install "$device" "$app"
mkdir -p artifacts/appearance
for mode in light dark; do
  xcrun simctl ui "$device" appearance "$mode"
  xcrun simctl launch --terminate-running-process "$device" de.wiresheep.kursfenster.ios --ui-screenshot
  sleep 3
  xcrun simctl io "$device" screenshot "$PWD/artifacts/appearance/kursfenster-$mode.png"
done
