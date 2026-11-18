# GilrTano VPN — commercial dual-mode integration

Status: implementation branch; not a release build.

## Product contract

- The app has two user-selected modes: FREE and ПОДПИСКА.
- Switching between these modes is always explicit and manual.
- A failed connection may retry or test another endpoint inside the selected mode, but it must never change the selected mode.
- The Android UI and one VPN service own the TUN lifecycle. The mode backends provide validated connection configurations and health results.
- Do not ship binaries or copied source from FlClashX, Mihomo, sing-box, or CyberPortal X in the commercial APK.

## Integration targets

### FlClashX-inspired subscription capabilities (independent implementation)
- VLESS/Clash YAML subscription import and profile parsing.
- Stable profile identity, groups, server selection, latency checks.
- Subscription metadata and provider-controlled settings, with explicit user override rules.
- Per-service connectivity checks with individual results and timeouts.
- Rule-based routing only where supported by the selected engine and covered by tests.

### CyberPortal-inspired FREE capabilities (independent implementation)
- WARP/MASQUE profile support through an audited non-GPL implementation.
- DNS policy, leak checks, and tunnel health checks.
- DPI-resistance transports only where supported by a verified, redistributable implementation.
- Never reuse bundled credentials, private keys, or server tokens from third-party APKs.

## Engine/licensing gate

The current `app/libs/teapod-core-1.7.7.aar` is not acceptable for the intended GPL-free distribution. The checked-in Teapod Core README states that its fat AAR bundles sing-box GPL-3.0-or-later. Its Go module also contains GPL-marked files and sing-box-specific packages. Merely removing a dependency declaration or excluding one class is not enough.

A clean engine build must be produced from a minimal source tree containing only:
- Xray-core (MPL-2.0, preserving MPL notices and source availability for modified MPL files),
- teapod-tun2socks (license and all transitive dependencies verified),
- Android/JNI glue authored for GilrTano.

Do not merge the current Rust AAR until its full source, native objects, and dependency inventory have been independently checked. Do not claim GPL-free status until the final APK and every ABI have been scanned and the complete SBOM/license report reviewed.

## Delivery stages

1. Inventory current APK/AAR contents and establish a reproducible license audit.
2. Create a clean Xray + TUN bridge build in isolation; no changes to the current release dependency until it builds and passes tests.
3. Refactor the app to manual FREE / ПОДПИСКА selection and remove cross-mode fallback.
4. Implement subscription import, profile grouping, and service health checks.
5. Implement WARP/MASQUE and DNS capabilities only after protocol and license validation.
6. Run Android tests on arm64, verify VPN permission, connect/disconnect/pause/resume, DNS, IPv4/IPv6 behavior, service checks, and mode isolation.
7. Produce a signed candidate APK and attach SBOM, notices, test report, and SHA-256.

## Release blockers

- No successful Xray-only AAR build yet.
- No final APK license scan yet.
- MASQUE/WARP interoperability has not yet been tested against a live account/profile.
- The current UI and service still contain legacy automatic cross-mode fallback paths.
- No release APK is produced by this planning commit.
