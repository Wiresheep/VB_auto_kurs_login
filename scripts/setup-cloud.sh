#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/bootstrap-android.py
source "${ANDROID_TOOLS_ROOT:-/workspace/.android-tools}/env.sh"
python3 - <<'PY'
import os, subprocess
subprocess.run([os.environ['ANDROID_HOME']+'/cmdline-tools/19.0/bin/sdkmanager','--licenses'],
               input='y\n'*100,text=True,check=True)
PY
"$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug
