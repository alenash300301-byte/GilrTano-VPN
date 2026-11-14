#!/usr/bin/env bash
# Inspect a locally built teapod-core AAR before wiring it into the app.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /path/to/teapod-core.aar" >&2
  exit 2
fi
AAR="$(realpath "$1")"
command -v unzip >/dev/null || { echo "ERROR: unzip is required" >&2; exit 2; }
[[ -f "$AAR" ]] || { echo "ERROR: AAR not found: $AAR" >&2; exit 2; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$AAR" -d "$TMP"

echo "AAR: $AAR"
sha256sum "$AAR"
echo
echo "Native ABI libraries:"
find "$TMP/jni" -mindepth 2 -maxdepth 2 -type f -name '*.so' -printf '%P\n' 2>/dev/null | sort || true

for abi in arm64-v8a x86_64; do
  if [[ -d "$TMP/jni/$abi" ]]; then echo "OK: $abi present"; else echo "WARNING: $abi missing"; fi
done
if [[ -d "$TMP/jni/armeabi-v7a" ]]; then
  echo "OK: armeabi-v7a present"
else
  echo "WARNING: armeabi-v7a missing (32-bit ARM devices are unsupported by this AAR)"
fi

if [[ -f "$TMP/classes.jar" ]]; then
  echo
echo "Gomobile classes mentioning expected API:"
  if command -v jar >/dev/null; then
    jar tf "$TMP/classes.jar" | grep -E 'teapodcore/.*(Teapodcore|VpnProtector|XrayCallback|TunValidator)' || true
  fi
  if command -v javap >/dev/null; then
    mkdir -p "$TMP/classes"
    (cd "$TMP/classes" && jar xf "$TMP/classes.jar")
    while IFS= read -r classfile; do
      class="${classfile#"$TMP/classes/"}"
      class="${class%.class}"
      class="${class//\//.}"
      echo "--- $class"
      javap -classpath "$TMP/classes" "$class" 2>/dev/null || true
    done < <(find "$TMP/classes" -type f -name '*.class' | grep -E '/(Teapodcore|VpnProtector|XrayCallback|TunValidator)\.class$' || true)
  fi
else
  echo "ERROR: classes.jar missing from AAR" >&2
  exit 1
fi

echo
echo "NOTICE: this is a structural inspection only; it does not prove VPN connectivity, UDP, DNS, IPv6, or socket-loop safety."
