#!/bin/sh
# Build without Gradle. Needs JDK 17+ and Android SDK (platforms;android-34, build-tools;34.0.0).
# Signing: set SM_KS_PASS (keystore password); SM_KS = keystore path (default sm.keystore, created if missing).
set -e
cd "$(dirname "$0")"
: "${SM_KS_PASS:?Set SM_KS_PASS (keystore password)}"
KS=${SM_KS:-sm.keystore}
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
rm -rf build && mkdir -p build/gen build/classes build/dex

$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/base.apk -I $JAR --manifest AndroidManifest.xml \
  --java build/gen --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 build/res.zip

javac -source 11 -target 11 -encoding UTF-8 -Xlint:-options -classpath $JAR \
  -d build/classes $(find src build/gen -name '*.java')
$BT/d8 --release --min-api 26 --lib $JAR --output build/dex $(find build/classes -name '*.class')

cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f -p 4 build/unsigned.apk build/aligned.apk

[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -alias sm -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass "$SM_KS_PASS" -keypass "$SM_KS_PASS" -dname "CN=S-Mykhailo" >/dev/null 2>&1
$BT/apksigner sign --ks "$KS" --ks-pass env:SM_KS_PASS --key-pass env:SM_KS_PASS \
  --out SM-Share.apk build/aligned.apk
$BT/apksigner verify SM-Share.apk && ls -la SM-Share.apk
