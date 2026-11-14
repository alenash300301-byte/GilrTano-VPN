#!/usr/bin/env bash
# Build the real upstream teapod-core fat AAR and install only the matching
# all-ABI artifact required by app/build.gradle.kts.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$ROOT/upstream/teapod-core"
PUBSPEC="$ROOT/upstream/teapodstream/pubspec.yaml"

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: missing command: $1" >&2; exit 2; }; }
for x in git go java javac jar javap unzip zip; do need "$x"; done

if [[ ! -f "$CORE/build.sh" ]]; then
  if [[ -e "$CORE" ]]; then
    echo "ERROR: $CORE exists but build.sh is missing; refusing to overwrite sources." >&2
    exit 2
  fi
  mkdir -p "$ROOT/upstream"
  git clone --depth=1 https://github.com/Wendor/teapod-core.git "$CORE"
fi
[[ -f "$PUBSPEC" ]] || { echo "ERROR: missing version source: $PUBSPEC" >&2; exit 3; }
[[ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]] || { echo "ERROR: Android SDK is not configured." >&2; exit 2; }
[[ -n "${ANDROID_NDK_HOME:-}" ]] || { echo "ERROR: ANDROID_NDK_HOME is not configured." >&2; exit 2; }

if [[ ! -x "$HOME/go/bin/gomobile" ]] && ! command -v gomobile >/dev/null 2>&1; then
  go install golang.org/x/mobile/cmd/gomobile@latest
fi
GOMOBILE="$(command -v gomobile || printf '%s' "$HOME/go/bin/gomobile")"
"$GOMOBILE" init

VERSION="$(awk '/^version:/{split($2,v,"+"); print v[1]; exit}' "$PUBSPEC")"
[[ -n "$VERSION" ]] || { echo "ERROR: cannot read AAR version from $PUBSPEC" >&2; exit 3; }

# The upstream build script expects teapodstream next to teapod-core.
(cd "$CORE" && PATH="$HOME/go/bin:$PATH" bash ./build.sh build)

FAT_AAR="$CORE/outputs/teapod-core-${VERSION}.aar"
[[ -s "$FAT_AAR" ]] || { echo "ERROR: expected fat AAR not found: $FAT_AAR" >&2; exit 4; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$FAT_AAR" -d "$TMP/aar"
[[ -s "$TMP/aar/classes.jar" ]] || { echo "ERROR: classes.jar missing from AAR" >&2; exit 5; }
jar tf "$TMP/aar/classes.jar" | grep -q '^teapodcore/Teapodcore.class$' || {
  echo "ERROR: Teapodcore Java bindings missing." >&2; exit 5;
}
javap -classpath "$TMP/aar/classes.jar" teapodcore.Teapodcore > "$TMP/api.txt"
for method in startBox stopBox measureBoxDelay measureBoxOutboundDelay startXray stopXray startTun2Socks; do
  grep -qi "$method" "$TMP/api.txt" || {
    echo "ERROR: required native API method '$method' missing from AAR $VERSION." >&2
    exit 6
  }
done
for abi in arm64-v8a x86_64; do
  [[ -s "$TMP/aar/jni/$abi/libgojni.so" ]] || {
    echo "ERROR: fat AAR is missing jni/$abi/libgojni.so" >&2; exit 7;
  }
done

mkdir -p "$ROOT/app/libs"
rm -f "$ROOT/app/libs"/teapod-core-*.aar
cp "$FAT_AAR" "$ROOT/app/libs/teapod-core-1.7.7.aar"
echo "Verified native AAR $VERSION (arm64-v8a + x86_64; Xray + sing-box APIs) installed."
