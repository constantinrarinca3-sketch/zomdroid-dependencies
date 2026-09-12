#!/usr/bin/env bash
set -euo pipefail

PZ_JAR="${PZ_JAR:-/storage/emulated/0/projectzomboid42.20.3/projectzomboid.jar}"
BUILD_DIR="${TMPDIR:-/tmp}/pz-renderer-hook-build"
OUT_NAME="PZRendererHook-v5.2-depth-batch.jar"
LOCAL_OUT="pz-renderer-hook/target/${OUT_NAME}"
DEVICE_OUT="${PZ_RENDERER_INSTALL:-/storage/emulated/0/Download/${OUT_NAME}}"

rm -rf "$BUILD_DIR"
mvn -f pz-renderer-hook/pom.xml \
  -Dpz.jar="$PZ_JAR" \
  -Drenderer.build.directory="$BUILD_DIR" \
  clean package

mkdir -p pz-renderer-hook/target
cp "$BUILD_DIR/PZRendererHook.jar" "$LOCAL_OUT"

if [[ -d "$(dirname "$DEVICE_OUT")" ]]; then
  cp "$LOCAL_OUT" "$DEVICE_OUT"
  echo "installed=$DEVICE_OUT"
fi

sha256sum "$LOCAL_OUT"
echo "built=$LOCAL_OUT"
