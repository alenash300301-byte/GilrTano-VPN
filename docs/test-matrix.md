# Device validation matrix — release blocker

| Area | Required test | Pass condition |
|---|---|---|
| Startup | Start/stop repeatedly | No leaked FD, thread, or native engine |
| TCP | HTTPS and long-lived TCP | Traffic uses proxy; no direct fallback |
| UDP | QUIC and UDP DNS | Works through SOCKS5 UDP relay |
| IPv4 | IPv4-only endpoint | Reachable through tunnel |
| IPv6 | IPv6-only endpoint | Reachable or explicitly blocked without leak |
| DNS | DNS query capture | No physical-interface DNS leak |
| Loop prevention | Xray and native socket routing | No packet recursion; server connection survives |
| Reconnect | Wi-Fi/mobile switch | Reconnects and restores traffic |
| Lifecycle | App swipe-away / service restart | Predictable teardown and recovery |
| ABI | arm64-v8a, armeabi-v7a | Native libraries load on matching devices |
| Android | API 29, 30, 33, 34+ | VPN permission and foreground service lifecycle valid |

Do not describe the application as production-ready until every supported row is tested on physical devices.
