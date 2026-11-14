# Native integration contract — Wendor/teapod-core

Inspected public upstream source: `teapod-core/teapodcore.go` (master), README.

## Confirmed gomobile API

Package: `teapodcore`

Interfaces:

- `VpnProtector.Protect(fd int) bool`
- `XrayCallback.OnStatus(status int64, message string)`
- `TunValidator.OnValidate(srcIP string, srcPort int64, dstIP string, dstPort int64, protocol int64) bool`

Functions:

- `InitCoreEnv(envPath string, key string)`
- `RegisterVpnProtector(p VpnProtector)`
- `StartXray(config string, cb XrayCallback)` — asynchronous start; success/failure reported through callback.
- `StopXray()`
- `StartTun2Socks(tunFD int64, mtu int64, socksPort int64, socksUser string, socksPass string, allowICMP bool, blockQuic bool, validator TunValidator) string` — empty string means start accepted; non-empty string is an error.
- `StopTun2Socks()`
- `IsTunRunning() bool`
- `GetTunUploadBytes() int64`, `GetTunDownloadBytes() int64`

## Required Kotlin service order

1. Call `startForeground()` with a persistent VPN notification before long-running work.
2. Build Xray JSON with SOCKS inbound bound to `127.0.0.1:10808`, UDP enabled.
3. Call `Teapodcore.initCoreEnv(filesDir.absolutePath, "")` after geodata assets are installed.
4. Register a `VpnProtector` implementation that calls `VpnService.protect(fd)` and returns its Boolean result. Do this **before** starting Xray.
5. Start Xray; wait for `OnStatus(0, ...)` before creating TUN. On negative status, stop and report failure.
6. Establish TUN with IPv4 and IPv6 addresses/routes and explicit DNS. Keep the `ParcelFileDescriptor` alive for the entire bridge lifetime.
7. Call `StartTun2Socks(tun.fd.toLong(), mtu, 10808L, "", "", false, false, null)`. Treat any non-empty return as fatal; close TUN and stop Xray.
8. Verify `IsTunRunning()` before reporting Connected.
9. On stop/revoke/network loss: stop tun2socks first, close TUN FD second, stop Xray third, unregister protector last. Make stop idempotent.

## Important verification still required

- The protector callback in the inspected source is explicitly registered on Xray's outbound dialer. Inspect/build/test the tun2socks SOCKS socket path to prove its loopback sockets cannot be captured by the VPN route. Do not assume this from the Xray protector alone.
- Verify actual UDP DNS and general UDP flows on device.
- Verify IPv6 route behavior and leak behavior when IPv6 is unavailable.
- Verify TUN FD ownership/duplication and stop races under repeated connect/disconnect.
- Test VPN revoke, Wi-Fi↔mobile handover, Doze, process death, and foreground-service restrictions.
- `validator = null` means allow all; do not use UID filtering below API 29.

## Architecture/licensing note

Current `teapod-core` bundles Go Xray + tun2socks, xray-rust, and sing-box in the fat AAR. Its README notes that sing-box is GPL-3.0-or-later with an additional name condition. For a distributable GilrTano build, review the actual AAR contents and licenses; ideally create a minimal Xray+tun2socks-only build rather than shipping unwanted cores.


## Updated upstream build findings (2026-11-11)

The currently published `build.sh` in `Wendor/teapod-core` lists three ABIs: `armeabi-v7a`, `arm64-v8a`, and `x86_64`. It invokes gomobile separately for each ABI and assembles a fat AAR. Its script hard-codes the maintainer's local macOS paths for Go, gomobile, Android SDK, and NDK; it is therefore not portable as-is. `scripts/build-native-aar.sh` provides a portable equivalent build invocation, but has not been executed in this environment.

The current `teapodcore.go` API has the eight-argument `StartTun2Socks` signature shown above. The `VpnProtector` callback is explicitly installed into Xray's outbound dialer only. The inspected code does not establish that tun2socks' own SOCKS connection is protected from VPN routing; this remains a release-blocking test/design issue.

Sources: `https://github.com/Wendor/teapod-core/blob/master/build.sh` and `https://github.com/Wendor/teapod-core/blob/master/teapodcore.go`.
