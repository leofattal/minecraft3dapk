#!/usr/bin/env bash
# MC 3D Weaver — one-shot dev setup: build, publish, install, re-grant Shizuku.
#
# Usage:
#   ./setup.sh            build + install on the connected device
#   ./setup.sh --build    build only (no adb)
#
# Notes:
# - Requires JDK 17 (Homebrew: brew install openjdk@17) and the Android SDK.
# - Every `adb install -r` revokes the Shizuku API permission, so it is
#   re-granted here after each install. Shizuku must be running on the tablet.

set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APK_SRC="weaver/build/outputs/apk/release/weaver-release.apk"
APK_OUT="MC3D-Weaver.apk"
PKG="com.leofattal.mcweaver"

echo "==> Building :weaver:assembleRelease (JDK: $JAVA_HOME)"
./gradlew :weaver:assembleRelease --console=plain

cp "$APK_SRC" "$APK_OUT"
echo "==> Published $APK_OUT"

if [[ "${1:-}" == "--build" ]]; then
  exit 0
fi

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "!! No adb device connected — skipping install. Re-run without --build later."
  exit 0
fi

echo "==> Installing on $($ADB shell getprop ro.product.model 2>/dev/null || echo device)"
"$ADB" install -r "$APK_OUT"

# Reinstall revokes runtime permissions; re-grant what the pipeline needs.
"$ADB" shell pm grant "$PKG" moe.shizuku.manager.permission.API_V23
"$ADB" shell pm grant "$PKG" android.permission.CAMERA

echo "==> Done. Open 'MC 3D Weaver' on the tablet and tap START 3D."
