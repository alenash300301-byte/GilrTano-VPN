#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM="$ROOT/upstream"
mkdir -p "$UPSTREAM"

fetch_repo() {
  local name="$1" url="$2"
  local dest="$UPSTREAM/$name"
  if [[ -d "$dest/.git" ]]; then
    git -C "$dest" fetch --depth=1 origin
    git -C "$dest" reset --hard FETCH_HEAD
  else
    rm -rf "$dest"
    git clone --depth=1 "$url" "$dest"
  fi
  git -C "$dest" rev-parse HEAD > "$UPSTREAM/$name.commit"
}

fetch_repo teapod-core https://github.com/Wendor/teapod-core.git
fetch_repo teapod-tun2socks https://github.com/Wendor/teapod-tun2socks.git
fetch_repo Xray-core https://github.com/XTLS/Xray-core.git

(
  cd "$UPSTREAM"
  find teapod-core teapod-tun2socks Xray-core -type f -not -path '*/.git/*' -print0 \
    | sort -z | xargs -0 sha256sum > SHA256SUMS
)
echo "Fetched upstream sources and wrote commit IDs + SHA256SUMS under $UPSTREAM"
echo "Review licenses and teapod-core/build.sh before building any distributable AAR."
