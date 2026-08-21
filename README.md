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
| `tunnelcore` | Pure Kotlin, no Android dependency: IP/CIDR math (`IpPrefix`, `RouteMath.subtract` for the no-`excludeRoute` bypass), the profile model + editor validation, the `VpnService.Builder` plan (`TunnelPlan`), split-DNS rules (`SplitDns`, `DnsProxy`), and the core's JSON shapes. Unit-tested on the JVM. |

## Requirements

- JDK 17, Android SDK with platform 37 and build-tools 37 (the Gradle wrapper
  brings Gradle itself; AGP 9 with built-in Kotlin).
- A device running Android 10+ (`minSdk` 29). Development is done against a
  physical device over adb — a VPN needs the real network stack.
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
./gradlew :app:installDebug         # install on the connected device
```

Pin a newer core release with `scripts/bump-jnilibs.sh <tag>` (rewrites the
tag, the sha256, and the app version in `gradle.properties`).

### Local FFI development

To run against a local build of the core instead of the pinned release, build
it in the sibling checkout and set `EZVPN_LOCAL_JNILIBS=1` (only the exact
value `1` opts in; anything else uses the release):

```bash
(cd ../ezvpn && ./build-android.sh release)        # or ABIS="arm64-v8a" ./build-android.sh debug
EZVPN_LOCAL_JNILIBS=1 ./gradlew :app:installDebug
```

`scripts/run-device.sh` does all of it — builds the core for the connected
device's ABI, installs, launches the app, and tails `logcat` for the `ezvpn`
tag (`--pinned` skips the local core and uses the release, `--no-core` skips
rebuilding it).

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
adb logcat -s ezvpn
```

Both the Kotlin side and the Rust core log under the `ezvpn` tag.
