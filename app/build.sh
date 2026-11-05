#!/usr/bin/env bash
# Build Device Diag (no Gradle). Needs an Android SDK with platforms;android-27 and any
# build-tools, plus a JDK 17/21.
#   ./build.sh                 -> build ./DeviceDiag.apk
#   SERIAL=<CLOCK_IP>:5555 ./build.sh install   -> build + adb install -r + launch
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AJ="$SDK/platforms/android-27/android.jar"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
: "${JAVA_HOME:=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || echo "$JAVA_HOME")}"
export JAVA_HOME
[ -f "$AJ" ] || { echo "missing $AJ — run: sdkmanager 'platforms;android-27'"; exit 1; }

B="$HERE/build"; OUT="$HERE/DeviceDiag.apk"
rm -rf "$B"; mkdir -p "$B/classes" "$B/dex"

echo "== aapt2 link =="
"$BT/aapt2" link -I "$AJ" --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 27 -o "$B/base-unsigned.apk"

echo "== javac =="
"$JAVA_HOME/bin/javac" --release 8 -nowarn -encoding UTF-8 -classpath "$AJ" \
  -d "$B/classes" $(find "$HERE/src" -name '*.java')

echo "== d8 =="
"$BT/d8" --min-api 24 --lib "$AJ" --output "$B/dex" $(find "$B/classes" -name '*.class')
( cd "$B/dex" && zip -q -j "$B/base-unsigned.apk" classes.dex )

echo "== zipalign + sign =="
"$BT/zipalign" -f -p 4 "$B/base-unsigned.apk" "$B/base-aligned.apk"
# One-time keystore (keep it — in-place updates and the packages.xml cert must match):
#   keytool -genkeypair -keystore debug.jks -storepass android -keypass android \
#     -alias test -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=DeviceDiag"
"$BT/apksigner" sign --ks "$HERE/debug.jks" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias test --min-sdk-version 24 --out "$OUT" "$B/base-aligned.apk"
"$BT/apksigner" verify "$OUT" && echo "APK: $OUT"

if [ "${1:-}" = "install" ]; then
  [ -n "${SERIAL:-}" ] || { echo "set SERIAL=<CLOCK_IP>:5555"; exit 1; }
  adb -s "$SERIAL" install -r "$OUT"
  adb -s "$SERIAL" shell am start -n com.example.diag/.MainActivity
fi
