#!/usr/bin/env bash
#
# Install the signed release APK on the physical device.
#
# This is the only thing the physical device (RELEASE_DEVICE_SERIAL below) is
# used for; all development, debug installs, and testing happen on the emulator
# via scripts/run-device.sh. The script refuses unsigned APKs and refuses to
# target the emulator.
#
# Usage:
#   scripts/install-release-apk.sh                 # dist/ezvpn-android-<versionName>.apk
#   scripts/install-release-apk.sh path/to.apk     # a specific signed APK
#   scripts/install-release-apk.sh --build         # run scripts/build-release-apk.sh first
#   scripts/install-release-apk.sh --launch        # also start the app afterwards
#   RELEASE_DEVICE_SERIAL=<serial> scripts/install-release-apk.sh   # another phone
#
# Note: a release-signed build cannot be installed over a debug build of the
# same applicationId; uninstall the other one first (adb uninstall ...).
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

EMULATOR_SERIAL="${EMULATOR_SERIAL:-10.22.35.66:5555}"
RELEASE_DEVICE_SERIAL="${RELEASE_DEVICE_SERIAL:-10.22.38.204:5555}"

BUILD=0
LAUNCH=0
apk=""
for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    --launch) LAUNCH=1 ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    -*) echo "unknown option: $arg" >&2; exit 1 ;;
    *) apk="$arg" ;;
  esac
done

if [ "$RELEASE_DEVICE_SERIAL" = "$EMULATOR_SERIAL" ]; then
  echo "refusing to install the release APK on the emulator $EMULATOR_SERIAL; it is for development only" >&2
  exit 1
fi

if [ "$BUILD" = 1 ]; then
  scripts/build-release-apk.sh
fi

if [ -z "$apk" ]; then
  version="$(sed -n 's/^ezvpn\.versionName=//p' gradle.properties)"
  apk="dist/ezvpn-android-$version.apk"
fi
[ -f "$apk" ] || { echo "no APK at $apk (build one with scripts/build-release-apk.sh or pass --build)" >&2; exit 1; }
case "$apk" in
  *unsigned*) echo "refusing to install an unsigned APK: $apk" >&2; exit 1 ;;
esac

# Require a verified signature: an unsigned or debug-signed APK must not reach
# the release device.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] && [ -f local.properties ]; then
  sdk="$(sed -n 's/^sdk\.dir=//p' local.properties)"
fi
apksigner="$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n1 || true)"
[ -n "$apksigner" ] || { echo "apksigner not found under $sdk/build-tools; cannot verify $apk" >&2; exit 1; }
echo "== verifying signature of $apk"
certs="$("$apksigner" verify --print-certs "$apk")" || { echo "$apk is not validly signed" >&2; exit 1; }
echo "$certs" | grep -E 'certificate (DN|SHA-256)'
if echo "$certs" | grep -q 'CN=Android Debug'; then
  echo "refusing to install a debug-signed APK on the release device" >&2
  exit 1
fi

# The physical device is attached over adb-over-TCP; (re)connect if needed.
case "$RELEASE_DEVICE_SERIAL" in
  *:*) adb connect "$RELEASE_DEVICE_SERIAL" >/dev/null 2>&1 || true ;;
esac
ADB=(adb -s "$RELEASE_DEVICE_SERIAL")
state="$("${ADB[@]}" get-state 2>/dev/null || true)"
if [ "$state" != "device" ]; then
  echo "release device $RELEASE_DEVICE_SERIAL is not ready (state: ${state:-absent}); check 'adb devices'" >&2
  exit 1
fi

echo "== installing $apk on $RELEASE_DEVICE_SERIAL ($("${ADB[@]}" shell getprop ro.product.model | tr -d '\r'))"
# adb-over-Wi-Fi occasionally drops a streamed install part-way with no reason
# given; reconnect and retry once before giving up.
if ! "${ADB[@]}" install -r "$apk"; then
  echo "== install failed; reconnecting to $RELEASE_DEVICE_SERIAL and retrying once"
  adb connect "$RELEASE_DEVICE_SERIAL" >/dev/null 2>&1 || true
  "${ADB[@]}" install -r "$apk"
fi

if [ "$LAUNCH" = 1 ]; then
  echo "== launching"
  "${ADB[@]}" shell am start -n dev.flexaccess.ezvpn/.MainActivity >/dev/null
fi
