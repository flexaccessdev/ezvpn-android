#!/usr/bin/env bash
#
# Pin the app to a published ezvpn release: download that tag's
# libezvpn-android.zip, compute its sha256, and rewrite gradle.properties
# (ezvpn.releaseTag, ezvpn.releaseSha256, ezvpn.versionName from the tag's
# numeric part; ezvpn.versionCode is bumped by one).
#
# Usage: scripts/bump-jnilibs.sh v0.0.42
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

tag="${1:-}"
if [ -z "$tag" ]; then
  echo "usage: $0 <release tag, e.g. v0.0.42>" >&2
  exit 1
fi
url="https://github.com/flexaccessdev/ezvpn/releases/download/$tag/libezvpn-android.zip"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

echo "Downloading $url"
curl -fsSL -o "$tmp" "$url"
sha="$(sha256sum "$tmp" | cut -d' ' -f1)"
version="${tag#v}"
code="$(sed -n 's/^ezvpn.versionCode=\([0-9]*\)$/\1/p' gradle.properties)"
code=$((${code:-0} + 1))

sed -i \
  -e "s|^ezvpn.releaseTag=.*|ezvpn.releaseTag=$tag|" \
  -e "s|^ezvpn.releaseSha256=.*|ezvpn.releaseSha256=$sha|" \
  -e "s|^ezvpn.versionName=.*|ezvpn.versionName=$version|" \
  -e "s|^ezvpn.versionCode=.*|ezvpn.versionCode=$code|" \
  gradle.properties

echo "Pinned $tag (sha256 $sha), versionName $version, versionCode $code"
grep '^ezvpn\.' gradle.properties
