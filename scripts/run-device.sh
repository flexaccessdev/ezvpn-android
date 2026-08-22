#!/usr/bin/env bash
#
# Build, install, launch, and watch the app on the development device.
#
# The target is ADB_SERIAL, else EMULATOR_SERIAL (a shell default for a
# development emulator/VM), else the single attached adb device. A host:port
# serial is (re)connected first.
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
#   ADB_SERIAL=<serial> scripts/run-device.sh   # pick a device
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
    -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 1 ;;
  esac
done

ADB_SERIAL="${ADB_SERIAL:-${EMULATOR_SERIAL:-}}"
if [ -z "$ADB_SERIAL" ]; then
  mapfile -t attached < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  case "${#attached[@]}" in
    1) ADB_SERIAL="${attached[0]}" ;;
    0) echo "no device attached; set ADB_SERIAL=<serial> (see 'adb devices')" >&2; exit 1 ;;
    *) echo "several devices attached (${attached[*]}); set ADB_SERIAL=<serial>" >&2; exit 1 ;;
  esac
fi
# ANDROID_SERIAL makes Gradle's installDebug (and plain adb) use the same target
# instead of failing/fanning out when several devices are attached.
export ANDROID_SERIAL="$ADB_SERIAL"
ADB=(adb -s "$ADB_SERIAL")

# A TCP serial: (re)connect if adb has lost it.
case "$ADB_SERIAL" in
  *:*) adb connect "${ADB_SERIAL}" >/dev/null 2>&1 || true ;;
esac
adb devices
state="$("${ADB[@]}" get-state 2>/dev/null || true)"
if [ "$state" != "device" ]; then
  echo "device $ADB_SERIAL is not ready (state: ${state:-absent}); start it / accept its USB-debugging prompt, or set ADB_SERIAL" >&2
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
