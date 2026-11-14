#!/usr/bin/env bash
# Fetch and build the upstream teapod-core AAR using its own build script.
# This script does not fabricate an AAR or substitute a stub dataplane.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$ROOT/upstream/teapod-core"

need() { command -v "$1" >/dev/null 2>&1 || { echo "ОШИБКА: не найдена команда $1" >&2; exit 2; }; }
for x in git go java javac unzip zip; do need "$x"; done

if [[ ! -f "$CORE/build.sh" ]]; then
  if [[ -e "$CORE" ]]; then
    echo "ОШИБКА: $CORE существует, но build.sh отсутствует; не перезаписываю исходники." >&2
    exit 2
  fi
  mkdir -p "$ROOT/upstream"
  git clone --depth=1 https://github.com/Wendor/teapod-core.git "$CORE"
fi

if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]]; then
  echo "ОШИБКА: Android SDK не найден. Установите SDK и задайте ANDROID_HOME." >&2
  exit 2
fi
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ОШИБКА: Android NDK не найден. Задайте ANDROID_NDK_HOME." >&2
  exit 2
fi
if [[ ! -x "$HOME/go/bin/gomobile" ]] && ! command -v gomobile >/dev/null 2>&1; then
  echo "Устанавливаю gomobile..."
  go install golang.org/x/mobile/cmd/gomobile@latest
fi
GOMOBILE="$(command -v gomobile || printf '%s' "$HOME/go/bin/gomobile")"
"$GOMOBILE" init

if [[ ! -f "$CORE/../teapodstream/pubspec.yaml" ]]; then
  echo "ОШИБКА: upstream build.sh требует соседний teapodstream/pubspec.yaml." >&2
  exit 3
fi
# Upstream file may not have the executable bit in the Git checkout.
(cd "$CORE" && PATH="$HOME/go/bin:$PATH" bash ./build.sh build)

AAR="$CORE/outputs/teapod-core-*.aar"
compgen -G "$AAR" >/dev/null || { echo "ОШИБКА: AAR не создан" >&2; exit 4; }
mkdir -p "$ROOT/app/libs"
cp $AAR "$ROOT/app/libs/"
echo "AAR скопирован в app/libs. Перед подключением проверьте содержимое и ABI."
