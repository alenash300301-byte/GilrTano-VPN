# Требования к сборке настоящего ядра

- Git и доступ к GitHub/Go module proxy.
- Go (upstream README: Go 1.21+).
- Java/JDK и Android SDK.
- Android NDK 28 (по upstream README/build script).
- gomobile (`go install golang.org/x/mobile/cmd/gomobile@latest`, затем `gomobile init`).
- Согласованные исходники teapod-core и соседнего teapodstream/pubspec.yaml: upstream build.sh берёт номер версии из соседнего Flutter-проекта.

Upstream build.sh формирует AAR для arm64-v8a и x86_64 и включает отдельный Rust AAR; ARMv7 в актуальном build.sh не заявлен.

Команда проекта: `scripts/build-native-aar.sh`.

Сборка не выполняется без реального исходного кода, SDK/NDK и зависимостей. Скрипт завершается с явной ошибкой вместо выдачи фальшивого AAR.
