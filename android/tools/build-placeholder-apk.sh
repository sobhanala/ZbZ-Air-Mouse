#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
echo "This project now uses Gradle dependencies for QR scanning." >&2
echo "Build with: cd $PROJECT_DIR && ./gradlew :app:assembleDebug" >&2
exit 1

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
PLATFORM_DIR="$SDK_DIR/platforms/android-36.1"
BUILD_TOOLS_DIR="$SDK_DIR/build-tools/36.1.0"
OUT_DIR="$PROJECT_DIR/build/manual"
RES_FLAT_DIR="$OUT_DIR/res-flat"
CLASSES_DIR="$OUT_DIR/classes"
DEX_DIR="$OUT_DIR/dex"
PACKAGE_NAME="io.github.zbz.airmouse"

if [[ ! -f "$PLATFORM_DIR/android.jar" ]]; then
    echo "Missing Android platform: $PLATFORM_DIR/android.jar" >&2
    exit 1
fi

rm -rf "$OUT_DIR"
mkdir -p "$RES_FLAT_DIR" "$CLASSES_DIR" "$DEX_DIR"

"$BUILD_TOOLS_DIR/aapt2" compile \
    --dir "$PROJECT_DIR/app/src/main/res" \
    -o "$RES_FLAT_DIR"

"$BUILD_TOOLS_DIR/aapt2" link \
    -I "$PLATFORM_DIR/android.jar" \
    --manifest "$PROJECT_DIR/app/src/main/AndroidManifest.xml" \
    --java "$OUT_DIR/generated" \
    -o "$OUT_DIR/resources.ap_" \
    "$RES_FLAT_DIR"/*.flat

javac \
    -source 8 \
    -target 8 \
    -bootclasspath "$PLATFORM_DIR/android.jar" \
    -d "$CLASSES_DIR" \
    $(find "$PROJECT_DIR/app/src/main/java" -name "*.java" -print) \
    "$OUT_DIR/generated/io/github/zbz/airmouse/R.java"

jar --create --file "$OUT_DIR/classes.jar" -C "$CLASSES_DIR" .

"$BUILD_TOOLS_DIR/d8" \
    --lib "$PLATFORM_DIR/android.jar" \
    --output "$DEX_DIR" \
    "$OUT_DIR/classes.jar"

cp "$OUT_DIR/resources.ap_" "$OUT_DIR/AirMouse-unsigned.apk"
(cd "$DEX_DIR" && zip -q "$OUT_DIR/AirMouse-unsigned.apk" classes.dex)

echo "Built $OUT_DIR/AirMouse-unsigned.apk"
