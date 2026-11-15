# Quattro profile import status

The supplied DOCX configuration was inspected locally for protocol inventory. It contains 544 `type: vless` entries (373 TCP and 171 gRPC transports) and 19 `type: hysteria2` entries. The document also contains access credentials and server parameters, so its raw contents must not be committed to this public repository.

## Current app behavior

- The Android app already has a DOCX picker and extracts `word/document.xml` locally on-device.
- `SubscriptionImporter` converts supported VLESS entries from Clash YAML into VLESS share links and persists imported profiles in app-private preferences.
- The service's VLESS-to-Xray builder accepts `grpc` transport and emits `grpcSettings`; VLESS TCP and gRPC can therefore use the existing Xray path.
- Hysteria2 entries are currently skipped by `SubscriptionImporter`; the current import/connect path does not yet route Hysteria2 through the sing-box engine. Do not report those 19 entries as imported or working.
- Importing profiles does not transfer ownership of Quattro servers or guarantee that their credentials remain valid.

## Required service checks

The tunnel's lightweight reachability checks cover YouTube, Instagram, Telegram, and VK hostnames. These checks only establish HTTPS reachability through the local SOCKS tunnel; they do not prove app login, video playback, uploads, or long-duration stability. Those require testing on a physical Android device and a currently authorized server profile.

UDP/443 remains enabled in the TUN-to-SOCKS bridge so QUIC is not intentionally blocked. Actual QUIC behavior still needs device testing.
