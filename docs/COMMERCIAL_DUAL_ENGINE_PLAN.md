# GilrTano VPN — commercial dual-mode integration

Status: implementation branch; not a release build.

## Product contract

- The app has two user-selected modes: PORTAL and QUATTRO. The legacy FREE mode is not a product mode and must not appear in the final UI.
- Switching between PORTAL and QUATTRO is always explicit and manual, using the in-app mode switch.
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

### CyberPortal-inspired PORTAL capabilities (independent implementation)
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

A separate Meow Android integration branch has a successful native ARM64 CI run and lists Meow Android and Meow engine as MIT. Its checked-in THIRD-PARTY-NOTICES explicitly says a complete Rust/Android dependency inventory is still required. Treat Meow as a candidate engine only; do not call it GPL-free or merge it until the full dependency tree, native libraries, GeoIP/GeoSite data terms, and final APK SBOM are reviewed.

Do not merge the current Rust AAR until its full source, native objects, and dependency inventory have been independently checked. Do not claim GPL-free status until the final APK and every ABI have been scanned and the complete SBOM/license report reviewed.

## Delivery stages

1. Inventory current APK/AAR contents and establish a reproducible license audit.
2. Compare the clean Xray + TUN route against the Meow ARM64 candidate; keep each engine isolated until dependency licensing and runtime tests pass.
3. Refactor the app to manual PORTAL / QUATTRO selection, remove legacy FREE/AUTO UI and code, and preserve strict mode isolation.
4. Implement subscription import, profile grouping, and service health checks.
5. Implement WARP/MASQUE and DNS capabilities only after protocol and license validation.
6. Run Android tests on arm64, verify VPN permission, connect/disconnect/pause/resume, DNS, IPv4/IPv6 behavior, service checks, and mode isolation.
7. Produce a signed candidate APK and attach SBOM, notices, test report, and SHA-256.

## Release blockers

- No final engine selected: the Meow branch has a successful ARM64 native build and APK CI run, but its complete dependency/license audit is outstanding; no clean Xray-only AAR build has been verified.
- No final APK license scan yet.
- MASQUE/WARP interoperability has not yet been tested against a live account/profile.
- The service-side automatic cross-mode fallback was removed on the integration branch. MainActivity still contains legacy AUTO/FREE UI and connection code and must be refactored before this contract is met.
- No release APK is produced by this planning commit.
