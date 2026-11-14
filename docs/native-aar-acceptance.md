# Проверка нативного ядра перед подключением

## Важная поправка по ABI

Актуальный upstream `Wendor/teapod-core` build.sh собирает **android/arm64 (arm64-v8a)** и **android/amd64 (x86_64)**. В текущем скрипте upstream нет `android/arm` / `armeabi-v7a`. Поэтому нельзя обещать поддержку ARMv7 на основании этой сборки.

## Требования к артефакту

- AAR получен из проверенного upstream исходного кода, а не создан как пустая заглушка.
- В `jni/arm64-v8a/` и `jni/x86_64/` присутствует `libgojni.so`.
- В `classes.jar` присутствуют классы `teapodcore.Teapodcore`, `VpnProtector`, `XrayCallback`, `TunValidator`.
- AAR подключается в Gradle, проект компилируется.
- Проверены старт/остановка Xray и tun2socks, ошибки callback, закрытие TUN FD и повторный запуск.
- На физическом устройстве проверены TCP, UDP, DNS, IPv4/IPv6, отсутствие утечки и VPN-loop.

Структурная проверка AAR не является доказательством работающего VPN.
