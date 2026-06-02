#!/usr/bin/env bash
# Build librift_ffi for all supported platforms and vendor them into
# resources/prebuilds/. macOS targets build natively; Linux targets build in
# per-arch Docker containers (native inside the container — no cross-linker).
#
# Usage:
#   scripts/build-natives.sh [RIFT_REF]      # default ref: contents of VERSION
#
# Requires: rust + cmake (macOS), docker (Linux targets).
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
ref="${1:-$(tr -d '[:space:]' < "$here/VERSION")}"
src="$(mktemp -d)/rift"
prebuilds="$here/resources/prebuilds"

echo ">> Cloning anomalyco/rift @ ${ref}"
git clone --depth 1 --branch "v${ref#v}" https://github.com/anomalyco/rift.git "$src" 2>/dev/null \
  || git clone --depth 1 --branch "$ref" https://github.com/anomalyco/rift.git "$src"

vendor() { # <platform> <built-path> <lib-name>
  mkdir -p "$prebuilds/$1"
  cp "$2" "$prebuilds/$1/$3"
  echo "   vendored $1/$3"
}

# --- macOS (native + cross target) ------------------------------------------
if [[ "$(uname -s)" == "Darwin" ]]; then
  echo ">> Building darwin-arm64 (native)"
  ( cd "$src" && cargo build --release -p rift-ffi --target aarch64-apple-darwin )
  vendor darwin-arm64 "$src/target/aarch64-apple-darwin/release/librift_ffi.dylib" librift_ffi.dylib

  echo ">> Building darwin-x64 (cross target)"
  rustup target add x86_64-apple-darwin >/dev/null 2>&1 || true
  ( cd "$src" && cargo build --release -p rift-ffi --target x86_64-apple-darwin )
  vendor darwin-x64 "$src/target/x86_64-apple-darwin/release/librift_ffi.dylib" librift_ffi.dylib
fi

# --- Linux (per-arch docker) ------------------------------------------------
build_linux() { # <platform> <docker-arch>
  echo ">> Building $1 via docker ($2)"
  docker run --rm --platform "$2" -v "$src:/work" -w /work \
    -e CARGO_TARGET_DIR="/work/target-$1" rust:1.91-bookworm bash -lc \
    "apt-get update -qq && apt-get install -y -qq cmake >/dev/null && cargo build --release -p rift-ffi"
  vendor "$1" "$src/target-$1/release/librift_ffi.so" librift_ffi.so
}

if command -v docker >/dev/null 2>&1; then
  build_linux linux-arm64 linux/arm64
  build_linux linux-x64   linux/amd64
else
  echo "!! docker not found — skipping Linux targets (use the CI workflow instead)"
fi

echo ">> Done. Vendored libraries:"
find "$prebuilds" -type f -print
