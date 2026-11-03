#!/usr/bin/env bash
# Builds build/dashboard.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Uses the JDK and SDK in ../tools (see docs/SETUP.md).
set -euo pipefail
cd "$(dirname "$0")"
# Windows-style paths: native tools must not depend on MSYS path conversion.
TOOLS="$(cd ../tools && pwd -W)"
export JAVA_HOME="$(ls -d "$TOOLS"/jdk/jdk-17*)"
export PATH="$(cygpath -u "$JAVA_HOME")/bin:$PATH"
BT="$TOOLS/sdk/build-tools/30.0.3"
JAR="$TOOLS/sdk/platforms/android-23/android.jar"

# Retry: a virus scanner can briefly lock android.jar or a freshly written APK on Windows.
retry() { for try in 1 2 3 4 5; do "$@" && return 0; sleep 2; done; return 1; }

rm -rf build && mkdir -p build/res build/gen build/classes build/dex
"$BT/aapt2.exe" compile --dir res -o build/res/
retry "$BT/aapt2.exe" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
    --java build/gen build/res/*.flat
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$JAR" -classpath "$JAR" -Xlint:-options \
    -d build/classes $(find src build/gen -name '*.java')
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 17 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
py -3.10 -c "import zipfile; zipfile.ZipFile('build/unsigned.apk', 'a').write('build/dex/classes.dex', 'classes.dex')"
retry "$BT/zipalign.exe" -f 4 build/unsigned.apk build/aligned.apk

# Local signing key: the same key must sign every update, or the installed app (and its stored
# Google login) cannot be upgraded. Its password lives in tools/keystore.pass (git-ignored),
# never in this script; apksigner/keytool read it from the file / environment.
KS=../tools/dashboard-debug.keystore
PASSFILE=../tools/keystore.pass
if [ ! -f "$PASSFILE" ]; then
    (umask 077; py -3.10 -I -c "import secrets; print(secrets.token_urlsafe(24))" > "$PASSFILE")
    if [ -f "$KS" ]; then
        # One-time migration from the former fixed password. The keystore is PKCS12, where the
        # key password equals the store password, so -storepasswd changes both.
        export KS_OLD=android KS_NEW="$(cat "$PASSFILE")"
        keytool -storepasswd -keystore "$KS" -storepass:env KS_OLD -new:env KS_NEW
        unset KS_OLD KS_NEW
    fi
fi
if [ ! -f "$KS" ]; then
    export KS_NEW="$(cat "$PASSFILE")"
    keytool -genkeypair -keystore "$KS" -storepass:env KS_NEW -keypass:env KS_NEW \
        -alias dashboard -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Dashboard" >/dev/null
    unset KS_NEW
fi
java -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass "file:$PASSFILE" \
    --v1-signing-enabled true --v2-signing-enabled true --out build/dashboard.apk build/aligned.apk
java -jar "$BT/lib/apksigner.jar" verify build/dashboard.apk
echo "Built android/build/dashboard.apk"
