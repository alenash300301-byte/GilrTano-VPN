# FREE / Usque integration audit

Date: 2026-11-27
Base inspected: `main`
Work branch: `feature/usque-free-integration`

## Confirmed current behavior

- `MainActivity.kt` maintains separate `freeNodes` and `quattroNodes` collections.
- FREE nodes are currently imported from a subscription URL and parsed as proxy nodes.
- The default FREE source is a public VLESS subscription URL.
- `switchMode(true)` selects a FREE node and sends its serialized proxy configuration to `GilrTanoVpnService`.
- `GilrTanoVpnService.startTunnel()` initializes `Teapodcore`, starts Xray, then establishes Android TUN and starts tun2socks.
- Quattro and FREE currently share this service and Xray/TUN pipeline; the service also contains automatic fallback/recovery logic.
- The repository currently declares `teapod-core-1.7.7.aar`; no Usque Android AAR/JAR or Usque registration code is present in the inspected app source tree.
- The repository's upstream Xray source tree contains MASQUE/WARP transport implementation, but that is not evidence that the currently packaged AAR has a working WARP registration flow.

## Integration consequence

The current FREE implementation is not Usque. It is a public VLESS-node workflow over Xray/tun2socks. Therefore, fixing VLESS subscription imports or tuning Xray would not deliver the requested Usque/WARP mode.

Do not replace the current FREE code in-place until the intended behavior is agreed and the native dependency is available. In particular, do not change Quattro's current Xray path, shared TUN ownership, or UI resources.

## Safe implementation plan

1. Add a separate FREE engine behind an internal engine selector; keep Quattro on its existing Xray path.
2. Select and pin a reproducible Usque Android native build (Go library/AAR plus JNI libraries) and verify its license and supported ABIs.
3. Integrate WARP registration/config persistence in app-private storage; do not log private keys, tokens, or registration JSON.
4. Adapt the existing VPN service so only one engine owns the Android TUN at a time. Ensure the inactive engine is fully stopped before TUN ownership changes.
5. Verify socket protection for the native MASQUE control connection, foreground-service lifecycle, stop/revoke races, and Android 16 network handover.
6. Build and test on an ARM64 Android device over Wi-Fi and mobile data. Confirm DNS and real HTTPS traffic before reporting connected.
7. Keep UI, drawables, layouts, animations, and Quattro implementation unchanged.

## Release blockers / unknowns

- The exact Usque native artifact and its API must be pinned and reviewed before writing the adapter.
- The public Usque sample app's API and packaging do not automatically make its prebuilt AAR/JNI artifacts suitable for this repository.
- No device test has yet demonstrated successful WARP registration or real traffic through a GilrTano Usque tunnel.
