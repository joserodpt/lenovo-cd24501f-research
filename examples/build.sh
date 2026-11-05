#!/usr/bin/env bash
# Build a tiny single-Activity APK without Gradle (aapt2 -> javac -> d8 -> zipalign -> apksigner).
# Needs: an Android SDK with platforms;android-27 and any build-tools, plus a JDK 17/21.
#
#   ANDROID_HOME=~/Library/Android/sdk ./build.sh
#
# Keep the signing key (debug.jks) stable: `adb install -r` requires the same key, and the cert
# must match the <sigs> entry you put in packages.xml.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AJ="$SDK/platforms/android-27/android.jar"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
: "${JAVA_HOME:=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || echo "$JAVA_HOME")}"
export JAVA_HOME
[ -f "$AJ" ] || { echo "missing $AJ — run: sdkmanager 'platforms;android-27'"; exit 1; }

B="$HERE/build"; OUT="$HERE/app.apk"
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
# One-time keystore:
#   keytool -genkeypair -keystore debug.jks -storepass android -keypass android \
#     -alias test -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Kiosk"
"$BT/apksigner" sign --ks "$HERE/debug.jks" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias test --min-sdk-version 24 --out "$OUT" "$B/base-aligned.apk"
"$BT/apksigner" verify "$OUT" && echo "APK: $OUT"

# To get the DER-hex of the cert for packages.xml <sigs>:
#   keytool -exportcert -keystore debug.jks -alias test -storepass android | xxd -p | tr -d '\n' | tr a-f A-F
