# GilrTano 0.7.0 — source audit

## A. Wendor/teapod-tun2socks

The public repository README describes:

- Kotlin package `com.teapodstream.tun2socks`.
- `TeapodVpnManager` public manager and `WhitelistMode`.
- Go/gomobile JNI dataplane using gVisor/netstack.
- TUN FD input, TCP SOCKS5 CONNECT, UDP SOCKS5 UDP ASSOCIATE.
- IPv4/IPv6 packet parsing.
- UID validation through `ConnectivityManager.getConnectionOwnerUid`.
- API 29+, Go 1.21+, gomobile, NDK r21+.
- Build script emits architecture-specific AARs.

The README sample shows `start(tunFd, socksHost, socksPort, socksUsername, socksPassword, allowedUids, whitelistMode, ...)`, `stop()`, `isRunning()`, `getCacheSize()`, and `setDebugLogging()`.

### Unresolved source-level questions

1. Exact gomobile-generated API signature and whether `tunFd` is an `Int`, `Long`, or `ParcelFileDescriptor`.
2. Whether the manager duplicates or takes ownership of the descriptor, and exactly when it closes it.
3. Whether the native TCP and UDP socket dialers call a Kotlin protector callback / `VpnService.protect(fd)`.
4. How SOCKS5 UDP relay endpoints are handled when the relay address is wildcard or differs from the control connection peer.
5. Whether IPv6 UDP, fragmentation, ICMP, and DNS are covered by tests.
6. Whether API 26 can run the dataplane if UID filtering is disabled. README only declares API 29+.

No implementation claim is made for these items until source inspection and tests establish the behavior.

## B. XTLS/Xray-core

Current upstream documentation explicitly describes Android `VpnService` integration: obtain `vpnInterface.fd`, pass it to Xray using `XRAY_TUN_FD` (or `xray.tun.fd`), and build an Android library with gomobile. The docs also warn that routing-loop prevention is essential. Android's outbound-interface binding needs the actual physical interface name when using Xray's automatic system routing.

This provides a genuine alternative to TUN → tun2socks → SOCKS → Xray: TUN FD → Xray's TUN inbound → VLESS/REALITY outbound. It should be evaluated as a separate implementation path, not silently mixed with the requested design.

## C. Required routing-loop protection

The requested two-stage design has two classes of sockets that must bypass the VPN TUN:

- Xray's outbound sockets to the VLESS/REALITY server.
- tun2socks' SOCKS5 sockets to `127.0.0.1:10808` are loopback and should remain local; any native socket that dials non-loopback must be protected/bound correctly.

`VpnService.protect()` is an Android API on the Java/Kotlin service. A native engine must expose the socket FD at creation time or use a documented protector callback. A README that merely describes a SOCKS client is not enough evidence that this is implemented.

## D. API-level decision

The current teapod-tun2socks README requires API 29+. Since its documented UID resolver uses `getConnectionOwnerUid`, this project cannot retain minSdk 26 while claiming full upstream compatibility without a tested fallback. Baseline minSdk is therefore 29 for the chosen integration candidate.
