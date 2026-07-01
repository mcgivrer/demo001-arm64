#!/usr/bin/env bash
set -euo pipefail

SRC_DIR="src/main/java"
OUT_DIR="target/classes"
LIB_DIR="lib"
JAR_FILE="target/${PWD##*/}.jar"
MAIN_CLASS="Main"

# LWJGL: OpenGL ES + GLFW bindings and their linux-arm64 natives
LWJGL_VERSION="3.3.6"
LWJGL_MODULES="lwjgl lwjgl-glfw lwjgl-opengles"
MAVEN_BASE="https://repo1.maven.org/maven2/org/lwjgl"

# AWT only rasterises text (headless); native access is required by LWJGL
JAVA_OPTS="-Djava.awt.headless=true --enable-native-access=ALL-UNNAMED"

deps() {
  mkdir -p "$LIB_DIR"
  for module in $LWJGL_MODULES; do
    #for suffix in "" "-natives-linux-arm64"; do
    for suffix in "-natives-macos" "-natives-windows" "-natives-linux" "-natives-linux-arm64"; do
      local_jar="$LIB_DIR/${module}-${LWJGL_VERSION}${suffix}.jar"
      if [[ ! -f "$local_jar" ]]; then
        url="$MAVEN_BASE/$module/$LWJGL_VERSION/${module}-${LWJGL_VERSION}${suffix}.jar"
        echo "Downloading $(basename "$local_jar")..."
        curl -fsSL -o "$local_jar" "$url"
      fi
    done
  done
  echo "Dependencies ready -> $LIB_DIR/"
}

case "${1:-build}" in
  deps)
    deps
    ;;
  build)
    deps
    echo "Compiling..."
    mkdir -p "$OUT_DIR"
    find "$SRC_DIR" -name "*.java" | xargs javac -cp "$LIB_DIR/*" -d "$OUT_DIR"
    if [[ -d "src/main/resources" ]]; then
      cp -r src/main/resources/. "$OUT_DIR/"
      echo "Resources copied -> $OUT_DIR"
    fi
    echo "Build successful -> $OUT_DIR"
    ;;
  jar)
    "$0" build
    MANIFEST="target/MANIFEST.MF"
    {
      echo "Main-Class: $MAIN_CLASS"
      echo "Class-Path: $(ls "$LIB_DIR"/*.jar | sed "s|^|../|" | tr '\n' ' ')"
    } > "$MANIFEST"
    jar --create --file="$JAR_FILE" --manifest="$MANIFEST" -C "$OUT_DIR" .
    echo "JAR created -> $JAR_FILE (requires $LIB_DIR/ next to the project root)"
    ;;
  run)
    shift
    java $JAVA_OPTS -cp "$OUT_DIR:$LIB_DIR/*" "$MAIN_CLASS" "$@"
    ;;
  clean)
    rm -rf target/
    echo "Cleaned."
    ;;
  all)
    "$0" build && "$0" run
    ;;
  *)
    echo "Usage: $0 {deps|build|jar|run [args...]|clean|all}"
    exit 1
    ;;
esac
