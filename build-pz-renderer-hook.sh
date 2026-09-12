#!/usr/bin/env bash
set -euo pipefail

PZ_JAR="${PZ_JAR:-/storage/emulated/0/projectzomboid42.20.3/projectzomboid.jar}"
BUILD_DIR="${TMPDIR:-/tmp}/pz-renderer-hook-build"
rm -rf "$BUILD_DIR"
mvn -f pz-renderer-hook/pom.xml -Dpz.jar="$PZ_JAR" -Drenderer.build.directory="$BUILD_DIR" clean package
mkdir -p pz-renderer-hook/target
cp "$BUILD_DIR/PZRendererHook.jar" pz-renderer-hook/target/PZRendererHook.jar
