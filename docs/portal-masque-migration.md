# PORTAL MASQUE migration plan

## Status

This branch is an isolated migration track. The production app still uses
`teapod-core-1.7.7.aar`; no production dependency or connection mode is
switched by this change.

## Why a separate migration is required

The current `teapod-core` AAR contains an independently built Go runtime and
also bundles sing-box (GPL-3.0-or-later). libXray also embeds a Go runtime.
Loading both Go-based AARs in the same Android process is unsupported and can
fail at link/load/runtime initialization. Merely merging their AAR files does
not solve this.

The safe choices are:

1. Replace the current Go core with one combined Go build that exposes both
   the Xray runtime and the required TUN bridge APIs; or
2. Run the two engines in separate Android processes and explicitly design
   the VPN file-descriptor and socket-protection handoff.

Neither path is complete until Quattro and the free mode have passed regression
tests. Do not add libXray.aar beside teapod-core.aar in the app's current
process.

## Pinned build

The companion workflow builds the upstream libXray Android AAR from the pinned
upstream tag `v26.11.9`, with Android API 29, and uploads it as a temporary
GitHub Actions artifact. This is a buildability check only; the artifact is not
yet wired into the APK.

Upstream API: `LibXray.invoke` with API version 3. The request method
`runXray` starts the managed core and `stopXray` stops it. The runtime TUN
descriptor is supplied inside the Xray config root `env` as
`xray.tun.fd`; a top-level request `env` is ignored.

## PORTAL profile requirements

- Generate Xray MASQUE outbounds from app-held settings; never commit a WARP
  private key or decrypted subscription/configuration.
- Keep the two configured Cloudflare MASQUE endpoints as separate candidates.
- Preserve the supplied profile's route policy before claiming parity. A
  single catch-all WARP route is not equivalent to the original profile's
  category-specific DIRECT/WARP rules and rule providers.
- Use a dedicated TLS/SNI and ALPN configuration per endpoint and validate it
  against the actual endpoint. Do not silently rewrite SNI values.
- Protect core sockets from the VPN tunnel and verify real HTTPS traffic before
  displaying a connected state.
- Store any private key encrypted at rest (Android Keystore-backed key); never
  write it to logs, Git history, crash reports, or analytics.

## Acceptance gates

1. Build the pinned AAR and verify its exported Android API surface.
2. Implement and unit-test a sanitized MASQUE JSON generator.
3. Establish Android VpnService TUN and pass its FD using the documented Xray
   config environment field.
4. Verify socket protection, DNS, IPv4/IPv6 behavior, and HTTPS traffic.
5. Test both PORTAL endpoints independently and automatic failover.
6. Regression-test Quattro and free modes on arm64-v8a and x86_64.
7. Only then switch the app dependency and publish an APK.

## License note

The upstream libXray wrapper is MIT-licensed and Xray-core is MPL-2.0. Review
the exact pinned source tree and all transitive dependencies before shipping;
this note is not a substitute for a release license audit.
