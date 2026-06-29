#!/usr/bin/env bash
set -euo pipefail

SRC_DIR="src/main/java"
OUT_DIR="target/classes"
JAR_FILE="target/${PWD##*/}.jar"
MAIN_CLASS="Main"

case "${1:-build}" in
  build)
    echo "Compiling..."
    mkdir -p "$OUT_DIR"
    find "$SRC_DIR" -name "*.java" | xargs javac -d "$OUT_DIR"
    if [[ -d "src/main/resources" ]]; then
      cp -r src/main/resources/. "$OUT_DIR/"
      echo "Resources copied -> $OUT_DIR"
    fi
    echo "Build successful -> $OUT_DIR"
    ;;
  jar)
    "$0" build
    MANIFEST="target/MANIFEST.MF"
    echo "Main-Class: $MAIN_CLASS" > "$MANIFEST"
    jar --create --file="$JAR_FILE" --manifest="$MANIFEST" -C "$OUT_DIR" .
    echo "JAR created -> $JAR_FILE"
    ;;
  run)
    shift
    if [[ -f "$JAR_FILE" ]]; then
      java -jar "$JAR_FILE" "$@"
    else
      java -cp "$OUT_DIR" "$MAIN_CLASS" "$@"
    fi
    ;;
  clean)
    rm -rf target/
    echo "Cleaned."
    ;;
  all)
    "$0" build && "$0" run
    ;;
  *)
    echo "Usage: $0 {build|jar|run [args...]|clean|all}"
    exit 1
    ;;
esac
