#!/usr/bin/env bash
#
# Build, install, launch, and watch the app on the connected Android device.
#
# By default the Rust core is rebuilt from the sibling ../ezvpn checkout for the
# device's primary ABI (release profile) and the app links it via
# EZVPN_LOCAL_JNILIBS=1. Then the debug APK is installed, the app launched, and
# logcat tailed for the `ezvpn` tag (Ctrl-C to stop watching; the app keeps
# running).
#
# Usage:
#   scripts/run-device.sh              # local core (rebuilt) + install + logcat
#   scripts/run-device.sh --no-core    # local core as last built, skip rebuild
#   scripts/run-device.sh --pinned     # the pinned release core instead
#   scripts/run-device.sh --no-log     # don't tail logcat
#   ADB_SERIAL=10.22.38.204:51035 scripts/run-device.sh   # pick a device
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

REBUILD_CORE=1
LOCAL=1
TAIL_LOG=1
for arg in "$@"; do
  case "$arg" in
    --no-core) REBUILD_CORE=0 ;;
    --pinned) LOCAL=0; REBUILD_CORE=0 ;;
    --no-log) TAIL_LOG=0 ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 1 ;;
  esac
done

ADB=(adb)
if [ -n "${ADB_SERIAL:-}" ]; then
  ADB=(adb -s "$ADB_SERIAL")
fi
adb devices
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "no device: check 'adb devices' (or set ADB_SERIAL)" >&2
  exit 1
fi
# A VPN needs the real network stack: this script targets physical devices only.
if [ "$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" = "1" ] ||
   [ "$("${ADB[@]}" shell getprop ro.boot.qemu | tr -d '\r')" = "1" ]; then
  echo "the selected target is an emulator; connect a physical device (or set ADB_SERIAL to one)" >&2
  exit 1
fi

if [ "$REBUILD_CORE" = 1 ]; then
  abilist="$("${ADB[@]}" shell getprop ro.product.cpu.abilist | tr -d '\r')"
  abi="${abilist%%,*}"
  case "$abi" in
    arm64-v8a|armeabi-v7a|x86_64|x86) ;;
    *) echo "unsupported device ABI '$abi'" >&2; exit 1 ;;
  esac
  echo "== building libezvpn.so for $abi in ../ezvpn"
  (cd ../ezvpn && ABIS="$abi" ./build-android.sh release)
fi

if [ "$LOCAL" = 1 ]; then
  export EZVPN_LOCAL_JNILIBS=1
else
  unset EZVPN_LOCAL_JNILIBS
fi

echo "== installing"
./gradlew :app:installDebug --console=plain -q

echo "== launching"
"${ADB[@]}" shell am start -n dev.flexaccess.ezvpn/.MainActivity >/dev/null

if [ "$TAIL_LOG" = 1 ]; then
  echo "== logcat (ezvpn); Ctrl-C to stop"
  "${ADB[@]}" logcat -c || true
  exec "${ADB[@]}" logcat -v time -s ezvpn AndroidRuntime:E DEBUG:F
fi
