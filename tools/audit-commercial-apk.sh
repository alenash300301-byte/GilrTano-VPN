#!/usr/bin/env bash
# Static release gate for known GPL indicators. This is a guardrail, not a
# substitute for an SPDX/SBOM audit or legal review.
set -euo pipefail

APK="${1:-}"
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "Usage: $0 path/to/app.apk" >&2
  exit 2
fi
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 2; }
command -v strings >/dev/null || { echo "strings is required" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$APK" -d "$TMP"

echo "APK: $APK"
echo "ABIs:"
find "$TMP/lib" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | sort || true

# Known forbidden artifacts and package identifiers from the current baseline.
if find "$TMP" -type f \( -iname '*sing-box*' -o -iname '*libbox*' -o -iname '*mihomo*' -o -iname '*clash*' \) | grep -q .; then
  echo "FAIL: known forbidden engine/artifact name detected:"
  find "$TMP" -type f \( -iname '*sing-box*' -o -iname '*libbox*' -o -iname '*mihomo*' -o -iname '*clash*' \)
  exit 1
fi

# Scan embedded native binaries and text resources for GPL identifiers.
# False positives are possible; every hit must be reviewed, never ignored silently.
HITS="$TMP/gpl-hits.txt"
: > "$HITS"
while IFS= read -r -d '' file; do
  if file "$file" | grep -Eq 'ELF|Zip archive|Java archive|ASCII text|Unicode text'; then
    strings "$file" 2>/dev/null | grep -Ein -m 20 'GNU General Public License|GPL-3\.0|GPL-2\.0|sing-box|SagerNet/sing-box|MetaCubeX/mihomo' \
      | sed "s#^#$file: #" >> "$HITS" || true
  fi
done < <(find "$TMP" -type f -print0)

if [[ -s "$HITS" ]]; then
  echo "REVIEW REQUIRED: GPL indicators found (may include false positives):"
  cat "$HITS"
  exit 1
fi

echo "PASS: no known GPL indicators found by this limited static scan."
echo "This does NOT prove GPL-free status. Generate and review a complete SBOM/license report."
