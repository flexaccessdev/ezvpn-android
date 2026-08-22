# ezvpn-android

A native Kotlin/Jetpack Compose client for Android that connects to an
[`ezvpn`](https://github.com/flexaccessdev/ezvpn) server: dual-stack split
tunnel, optional tunnel DNS with split-DNS match domains (implemented by an
in-tunnel forwarder, since Android has no per-domain VPN DNS), underlay bypass,
always-on support. The Rust core (`libezvpn.so`, built from the `ezvpn` repo)
runs the data plane inside a `VpnService`; this repo is the app, the service,
and the pure-Kotlin `tunnelcore` module around it.

Design and the JNI contract are documented in the core repo:
[`docs/Android-App.md`](https://github.com/flexaccessdev/ezvpn/blob/main/docs/Android-App.md).

## Layout

| Module | What |
|---|---|
| `app` | The Compose app (`MainActivity`, screens under `ui/`), `EzvpnVpnService`, `TunnelsManager`, the encrypted secret/profile stores, and `EzvpnNative` (the JNI binding — its package and name are fixed by the symbols in `libezvpn.so`). |
| `tunnelcore` | Pure Kotlin, no Android dependency: IP/CIDR math (`IpPrefix`, `RouteMath.subtract` for the bypass on Android < 13, which has no `excludeRoute`), the profile model + editor validation, the `VpnService.Builder` plan (`TunnelPlan`), split-DNS rules (`SplitDns`, `DnsProxy`), and the core's JSON shapes. Unit-tested on the JVM. |

## Requirements

- JDK 17, Android SDK with platform 37 and build-tools 37 (the Gradle wrapper
  brings Gradle itself; AGP 9 with built-in Kotlin).
- An Android 10+ (`minSdk` 29) arm64 device (the app is arm64-v8a only, per
  [Play's 64-bit requirement](https://developer.android.com/google/play/requirements/64-bit)). Development is done against an
  adb-connected device — a phone, or an arm64 Android VM/emulator bridged onto
  the LAN like a phone (`adb connect <host>`) — since a `VpnService` cannot be
  exercised on the JVM.
- For FFI work: the sibling `../ezvpn` checkout, the Android NDK and
  `cargo-ndk` (see that repo's `build-android.sh`).

`local.properties` (git-ignored) points Gradle at the SDK:
`sdk.dir=/path/to/Android/Sdk`.

## Building

By default the app downloads the pinned `libezvpn-android.zip` release asset of
the core repo (tag + sha256 in `gradle.properties`) and unpacks the
`jniLibs/<abi>/libezvpn.so` tree into `app/build/ezvpn-jnilibs`:

```bash
./gradlew :tunnelcore:test          # pure-Kotlin unit tests
./gradlew :app:testDebugUnitTest    # app-module JVM unit tests
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
ANDROID_SERIAL=<serial> ./gradlew :app:installDebug   # debug install on that device
```

Pin a newer core release with `scripts/bump-jnilibs.sh <tag>` (rewrites the
tag, the sha256, and the app version in `gradle.properties`).

CI (`.github/workflows/ci.yml`) runs the tests and uploads the debug APK of
every push/PR as the `app-debug` workflow artifact.

### Release APK

```bash
scripts/build-release-apk.sh            # → dist/ezvpn-android-<version>.apk
scripts/build-release-apk.sh --unsigned # no signing (not installable as is)
```

The release key is a keystore outside the repo (default
`~/.config/ezvpn-android/release.jks`, override with `EZVPN_KEYSTORE`;
password from `EZVPN_KEYSTORE_PASSWORD` or prompted). The script creates it on
first use — back it up, devices only accept updates signed with the same key.
A release build cannot be installed over a debug build of the app (different
signature); uninstall the other one first.

Install the signed APK on a real device (the serial is required — there is
no default device):

```bash
RELEASE_DEVICE_SERIAL=<serial> scripts/install-release-apk.sh           # dist/ezvpn-android-<version>.apk
RELEASE_DEVICE_SERIAL=<serial> scripts/install-release-apk.sh --build   # build it first
RELEASE_DEVICE_SERIAL=<serial> scripts/install-release-apk.sh --launch  # and start the app
```

It verifies the signature with `apksigner` and refuses unsigned or
debug-signed APKs, and refuses to target the emulator (`EMULATOR_SERIAL`
overrides that serial).

### Local FFI development

To run against a local build of the core instead of the pinned release, build
it in the sibling checkout and set `EZVPN_LOCAL_JNILIBS=1` (only the exact
value `1` opts in; anything else uses the release):

```bash
(cd ../ezvpn && ABIS="arm64-v8a" ./build-android.sh release)
EZVPN_LOCAL_JNILIBS=1 ANDROID_SERIAL=<serial> ./gradlew :app:installDebug
```

`scripts/run-device.sh` does all of it — builds the core for the device's ABI,
installs the debug APK, launches the app, and tails `logcat` for the `ezvpn`
tag (`--pinned` skips the local core and uses the release, `--no-core` skips
rebuilding it). It targets `ADB_SERIAL`, else `EMULATOR_SERIAL` (a convenient
shell default for a development emulator/VM), else the single attached device;
a `host:port` serial is (re)connected first.

## Using the app

1. **Auth keys** (key icon): generate a key on the device or paste an
   `ed25519-sec:…` secret from another device. Put the shown `ed25519-pub:…`
   line on the server's `authorized_keys` file. Keys are shared by all
   profiles; deleting one does not affect profiles already saved with it.
2. **Add a profile** (+): server node id, the auth key, optional custom relay
   URLs (+ token), split-tunnel CIDRs (the server gateway is always routed),
   and optionally DNS servers with match domains.
3. Toggle the profile to connect. The first connect shows Android's VPN
   consent dialog. The detail screen shows the applied addresses, routes,
   bypass set, DNS setup, and a live "Connection path" readout (direct vs
   relay).

Network changes disconnect the tunnel (reconnect on the new network). A
profile whose split-tunnel prefix overlaps the Wi-Fi/Ethernet subnet the device
is on is refused, since routing the local subnet into the tunnel would cut the
tunnel's own underlay off. For always-on VPN (Settings › Network › VPN › ezvpn),
the service connects the last-used profile when the system starts it.

### Split DNS

With DNS servers and match domains set, names under the match domains resolve
through the profile's servers over the tunnel and everything else keeps the
network's normal resolvers. Android's `VpnService` cannot express that, so the
app points the VPN's DNS at a proxy address inside the tunnel (`198.18.0.53` /
`fd7e:7a00:d45::53`) and the Rust core forwards each query to the right
resolver (Tailscale's MagicDNS approach). With servers but no match domains,
the servers answer every name, as on any VPN app.

## Logs

```bash
adb logcat -s ezvpn          # add -s <serial> with several devices attached
```

Both the Kotlin side and the Rust core log under the `ezvpn` tag.

## Seeing and driving a device from a terminal

Useful when the device is remote (wireless adb, a VM, an emulator reached over
an ssh port forward) or when a script needs to watch the screen.

**Screenshots** — `adb exec-out screencap -p > shot.png` works everywhere and
needs no display. If an X display is available (a desktop session on the build
host), mirror the device with [scrcpy](https://github.com/Genymobile/scrcpy)
and capture its window with [scrot](https://github.com/resurrecting-open-source-projects/scrot):

```bash
DISPLAY=:0 scrcpy -s <serial> --window-title dev --max-size 1000 &   # live mirror (always -s: scrcpy refuses to pick among several devices)
WID=$(DISPLAY=:0 xdotool search --name '^dev$' | head -1)
DISPLAY=:0 scrot -o -w "$WID" shot.png      # just the device window (-o overwrites)
DISPLAY=:0 scrot -o shot.png                # the whole desktop
```

scrcpy keyboard shortcuts sent to that window reach the device (`xdotool key
--window "$WID" alt+n` opens the notification shade), but synthetic xdotool
mouse clicks do not register in scrcpy's SDL window — drive the device with
adb instead:

```bash
adb shell input tap X Y                 # coordinates in device pixels
adb shell input text 'hello%sworld'     # %s for a space
adb shell input keyevent KEYCODE_BACK   # keyevent 111 (ESCAPE) hides the keyboard without navigating back
adb shell 'uiautomator dump /sdcard/ui.xml >/dev/null; cat /sdcard/ui.xml'  # widget tree with bounds
```

`uiautomator dump` sees Compose text and content descriptions with their
bounds, which is enough to find a field or button to tap. Two Compose quirks:
an unfocused text field is best tapped a little below its label's top edge, and
pressing BACK to dismiss the keyboard also dismisses a Compose dialog — tap the
dialog's button instead. Emulators with Gboard show a one-time "Try out your
stylus" sheet over the first text field; it swallows taps until cancelled.
