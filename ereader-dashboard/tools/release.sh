#!/usr/bin/env bash
# Production release of the dashboard app: every gate must pass before anything is installed.
#   1. Java: TimeUtil tests + full compile with -Xlint:all -Werror
#   2. Python: unit tests + flake8
#   3. Shell scripts: bash -n
#   4. Build + signature check (same certificate as the installed app)
#   5. Install over USB, then verify the installed versionCode on the reader
# Usage: bash tools/release.sh
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
A=tools/platform-tools/adb.exe
JH="$(cd tools && pwd -W)/jdk/$(ls tools/jdk | grep jdk-17)"
J="$(cygpath -u "$JH")/bin"
JAR="$(cd tools && pwd -W)/sdk/platforms/android-23/android.jar"
step() { echo; echo "== $*"; }

want_code=$(grep -o 'android:versionCode="[0-9]*"' android/AndroidManifest.xml | grep -o '[0-9]*')
want_name=$(grep -o 'android:versionName="[^"]*"' android/AndroidManifest.xml | cut -d'"' -f2)
echo "Release $want_name (versionCode $want_code)"

step "1/5 Java tests and -Xlint:all -Werror"
bash android/test/run.sh
bash android/build.sh >/dev/null   # generates R.java for the lint compile
OUT="$(mktemp -d)"
"$J/javac" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$JAR" -Xlint:all -Xlint:-options -Werror \
    -d "$OUT" $(find android/src -name '*.java') android/build/gen/nl/japandi/dashboard/R.java
echo "javac: 0 warnings"

step "2/5 Python tests and flake8"
py -3.10 -W ignore -m unittest discover -s tests
py -3.10 -m flake8 --max-line-length=110 backend tools tests
echo "flake8: 0 findings"

step "3/5 Shell syntax"
for f in android/build.sh android/test/run.sh tools/*.sh; do bash -n "$f"; done
echo "bash -n: ok"

step "4/5 Build and signature"
bash android/build.sh | tail -1
new_cert=$("$J/keytool" -printcert -jarfile android/build/dashboard.apk | grep -m1 'SHA256:' | tr -d ' \t')
echo "signed by $new_cert"

step "5/5 Install on the reader"
"$A" get-state >/dev/null 2>&1 || { echo "Reader not connected over USB: nothing installed."; exit 1; }
"$A" install -r android/build/dashboard.apk | tail -1
got_code=$("$A" shell dumpsys package nl.japandi.dashboard | grep -m1 -o 'versionCode=[0-9]*' | cut -d= -f2)
got_name=$("$A" shell dumpsys package nl.japandi.dashboard | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2 | tr -d '\r')
[ "$got_code" = "$want_code" ] || { echo "Installed versionCode $got_code, expected $want_code"; exit 1; }
"$A" shell am start -n nl.japandi.dashboard/.MainActivity >/dev/null 2>&1
echo "Installed $got_name (versionCode $got_code) on the reader."
sha256sum android/build/dashboard.apk
