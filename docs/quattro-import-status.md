# Quattro profile import status

The supplied DOCX was inspected locally. Its Clash YAML contains 544 `type: vless` profiles and 19 `type: hysteria2` profiles (563 profiles total). The source also contains credentials; the raw DOCX and credentials must not be committed to this public repository.

## Import and engine routing

- The Android DOCX picker extracts `word/document.xml` locally and passes the YAML to `SubscriptionImporter`.
- The importer now accepts VLESS and Hysteria2 share links and Clash YAML entries for both protocols. Imported profiles are saved in app-private preferences.
- VLESS profiles continue through the Xray configuration path.
- Hysteria2 profiles are converted to sing-box outbound JSON and started through the existing `Teapodcore.startBox` path. The service's server-switch action now carries the engine selection, and automatic recovery retains the preferred engine flag.
- The Hysteria2 configuration maps server, port, password, SNI, ALPN and `skip-cert-verify` to sing-box fields. The source Quattro Hysteria2 entries inspected contain those fields; no Hysteria2 obfuscation settings were present in those entries.

## Verification status and limitations

- The source YAML was parsed independently: 544 VLESS + 19 Hysteria2 entries.
- No profile credentials were copied into this repository.
- This change has not yet been validated by an Android Gradle build or on a physical device. Successful import does not prove that every remote endpoint is online or that its credentials are still authorized.
- sing-box documents Hysteria2 as an outbound with required server, port, password and TLS configuration: https://sing-box.sagernet.org/configuration/outbound/hysteria2/
- The tunnel's HTTPS probes cover YouTube, Instagram, Telegram and VK host reachability only; they do not prove app login, playback, uploads or long-duration stability.
- UDP/443 remains enabled in the TUN bridge. Physical-device testing is still required for UDP/QUIC, DNS, IPv6, reconnect and failover behavior.
- Importing profiles does not transfer ownership of Quattro servers or guarantee that credentials remain valid.
