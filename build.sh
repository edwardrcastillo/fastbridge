#!/usr/bin/env bash
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk}"
export PATH="$JAVA_HOME/bin:$HOME/.local/android-sdk/android-14:$HOME/.local/bin:$PATH"
ANDROID_JAR="${ANDROID_JAR:-$HOME/.local/android-sdk/android-9/android.jar}"

BUILD="$PROJ/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/obj" "$BUILD/apk"

echo "[1/4] Compiling Android resources with aapt2..."
aapt2 compile --dir "$PROJ/res" -o "$BUILD/res.zip"
aapt2 link -o "$BUILD/unaligned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$PROJ/AndroidManifest.xml" \
    --java "$BUILD/gen" \
    "$BUILD/res.zip"

echo "[2/4] Compiling Java sources with javac..."
javac -source 1.8 -target 1.8 \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d "$BUILD/obj" \
    "$BUILD/gen/com/fastmovie/fastbridge/R.java" \
    "$PROJ"/src/com/fastmovie/fastbridge/*.java

echo "[3/4] Converting bytecode to DEX with d8..."
d8 --lib "$ANDROID_JAR" --output "$BUILD/apk" "$BUILD"/obj/com/fastmovie/fastbridge/*.class

echo "[4/4] Packaging, aligning, and signing FastBridge.apk..."
cd "$BUILD"
cp unaligned.apk FastBridge-unsigned.apk
jar uf FastBridge-unsigned.apk -C "$BUILD/apk" classes.dex
zipalign -f 4 FastBridge-unsigned.apk FastBridge-aligned.apk

if [ ! -f "$PROJ/debug.keystore" ]; then
    keytool -genkeypair -v \
        -keystore "$PROJ/debug.keystore" \
        -storepass android \
        -alias androiddebugkey \
        -keypass android \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=FastBridge,O=FastMovie,C=MX"
fi

apksigner sign \
    --ks "$PROJ/debug.keystore" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$PROJ/FastBridge.apk" \
    FastBridge-aligned.apk

rm -rf "$BUILD"
echo "Done! Built APK: $PROJ/FastBridge.apk ($(du -h "$PROJ/FastBridge.apk" | cut -f1))"
