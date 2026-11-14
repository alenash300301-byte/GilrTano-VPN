# Upstream API и сборка

Источник: https://github.com/Wendor/teapod-core

- `Teapodcore.initCoreEnv(path, key)`
- `Teapodcore.registerVpnProtector(VpnProtector)`
- `Teapodcore.startXray(config, XrayCallback)`
- `Teapodcore.startTun2Socks(fd, socksPort, user, pass, validator)`
- `Teapodcore.stopTun2Socks()` и `Teapodcore.stopXray()`
- `VpnProtector.protect(fd: Long): Boolean`
- `XrayCallback.onStatus(status: Long, message: String)`
- `TunValidator.onValidate(srcIP, srcPort, dstIP, dstPort, protocol)`

**ABI correction:** актуальный upstream build.sh targets `android/arm64` and `android/amd64` only (arm64-v8a + x86_64). Не заявлять ARMv7, пока отдельная сборка не реализована и не протестирована.

Build requirements and version coupling are described in `build-requirements.md`.
