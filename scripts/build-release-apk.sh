#!/usr/bin/env bash
#
# Build a signed release APK locally.
#
# The signing key lives outside the repo in a keystore (default
# ~/.config/ezvpn-android/release.jks, override with EZVPN_KEYSTORE). On the
# first run the keystore is created with keytool; the password is taken from
# EZVPN_KEYSTORE_PASSWORD or prompted for. Keep the keystore and password safe:
# a device only accepts updates signed with the same key, and there is no way
# to recover a lost keystore.
#
# The APK is written to app/build/outputs/apk/release/app-release.apk and copied
# to dist/ezvpn-android-<versionName>.apk. The core (libezvpn.so) comes from the
# pinned ezvpn release unless EZVPN_LOCAL_JNILIBS=1 (see README).
#
# Usage:
#   scripts/build-release-apk.sh             # signed release APK
#   scripts/build-release-apk.sh --unsigned  # skip signing (app-release-unsigned.apk)
#   EZVPN_KEYSTORE=/path/to/key.jks EZVPN_KEYSTORE_PASSWORD=... scripts/build-release-apk.sh
#
# Note: a release-signed build cannot be installed over a debug build of the
# same applicationId (and vice versa); uninstall the other one first.
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

SIGN=1
for arg in "$@"; do
  case "$arg" in
    --unsigned) SIGN=0 ;;
    -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 1 ;;
  esac
done

if [ "$SIGN" = 1 ]; then
  keystore="${EZVPN_KEYSTORE:-$HOME/.config/ezvpn-android/release.jks}"
  alias="${EZVPN_KEY_ALIAS:-ezvpn}"
  password="${EZVPN_KEYSTORE_PASSWORD:-}"

  keytool=keytool
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
    keytool="$JAVA_HOME/bin/keytool"
  fi

  if [ ! -f "$keystore" ]; then
    echo "== no keystore at $keystore; creating one"
    if [ -z "$password" ]; then
      read -r -s -p "New keystore password (min 6 chars): " password; echo
      read -r -s -p "Repeat password: " again; echo
      [ "$password" = "$again" ] || { echo "passwords do not match" >&2; exit 1; }
    fi
    [ "${#password}" -ge 6 ] || { echo "password must be at least 6 characters" >&2; exit 1; }
    mkdir -p "$(dirname "$keystore")"
    (umask 077 && "$keytool" -genkeypair -keystore "$keystore" -alias "$alias" \
      -keyalg RSA -keysize 4096 -validity 10000 \
      -dname "CN=ezvpn-android" \
      -storepass "$password" -keypass "$password" >/dev/null)
    echo "   created $keystore (alias $alias) — back it up, it cannot be regenerated"
  elif [ -z "$password" ]; then
    read -r -s -p "Password for $keystore: " password; echo
  fi

  export EZVPN_KEYSTORE="$keystore" EZVPN_KEYSTORE_PASSWORD="$password" EZVPN_KEY_ALIAS="$alias"
  apk=app/build/outputs/apk/release/app-release.apk
else
  unset EZVPN_KEYSTORE EZVPN_KEYSTORE_PASSWORD
  apk=app/build/outputs/apk/release/app-release-unsigned.apk
fi

echo "== building release APK"
./gradlew :app:assembleRelease --console=plain
[ -f "$apk" ] || { echo "expected $apk after assembleRelease" >&2; exit 1; }

if [ "$SIGN" = 1 ]; then
  # Verify with apksigner from the newest installed build-tools, if any.
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$sdk" ] && [ -f local.properties ]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' local.properties)"
  fi
  apksigner="$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n1 || true)"
  if [ -n "$apksigner" ]; then
    echo "== verifying signature"
    "$apksigner" verify --print-certs "$apk" | grep -E 'certificate (DN|SHA-256)'
  fi
fi

version="$(sed -n 's/^ezvpn\.versionName=//p' gradle.properties)"
mkdir -p dist
if [ "$SIGN" = 1 ]; then
  out="dist/ezvpn-android-$version.apk"
else
  out="dist/ezvpn-android-$version-unsigned.apk"
fi
cp "$apk" "$out"
echo "== $out"
