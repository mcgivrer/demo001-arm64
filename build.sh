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

native_classifier() {
  local os arch
  os="$(uname -s)"
  arch="$(uname -m)"

  case "$os" in
    Linux)
      case "$arch" in
        aarch64|arm64) echo "linux-arm64" ;;
        *) echo "linux" ;;
      esac
      ;;
    Darwin)
      echo "macos"
      ;;
    MINGW*|MSYS*|CYGWIN*|Windows_NT)
      echo "windows"
      ;;
    *)
      echo "Unsupported OS: $os" >&2
      exit 1
      ;;
  esac
}

deps() {
  mkdir -p "$LIB_DIR"
  classifier="$(native_classifier)"

  for module in $LWJGL_MODULES; do
    for suffix in "" "-natives-$classifier"; do
      local_jar="$LIB_DIR/${module}-${LWJGL_VERSION}${suffix}.jar"
      if [[ ! -f "$local_jar" ]]; then
        url="$MAVEN_BASE/$module/$LWJGL_VERSION/${module}-${LWJGL_VERSION}${suffix}.jar"
        echo "Downloading $(basename "$local_jar")..."
        curl -fsSL -o "$local_jar" "$url"
      fi
    done
  done
  echo "Dependencies ready for $classifier -> $LIB_DIR/"
}

cmd_build() {
  deps
  echo "Compiling..."
  mkdir -p "$OUT_DIR"
  find "$SRC_DIR" -name "*.java" | xargs javac -cp "$LIB_DIR/*" -d "$OUT_DIR"
  if [[ -d "src/main/resources" ]]; then
    cp -r src/main/resources/. "$OUT_DIR/"
    echo "Resources copied -> $OUT_DIR"
  fi
  echo "Build successful -> $OUT_DIR"
}

cmd_jar() {
  cmd_build
  MANIFEST="target/MANIFEST.MF"
  class_path=""
  for jar_file in "$LIB_DIR"/*.jar; do
    class_path+="../$(basename "$jar_file") "
  done
  class_path="${class_path% }"

  write_manifest_attr() {
    local name="$1"
    local value="$2"
    local line="$name: $value"
    # JAR manifest lines must be wrapped; continuation lines start with one space.
    while [[ ${#line} -gt 72 ]]; do
      printf '%s\n' "${line:0:72}" >> "$MANIFEST"
      line=" ${line:72}"
    done
    printf '%s\n' "$line" >> "$MANIFEST"
  }

  : > "$MANIFEST"
  write_manifest_attr "Main-Class" "$MAIN_CLASS"
  write_manifest_attr "Class-Path" "$class_path"
  printf '\n' >> "$MANIFEST"
  jar --create --file="$JAR_FILE" --manifest="$MANIFEST" -C "$OUT_DIR" .
  echo "JAR created -> $JAR_FILE (requires $LIB_DIR/ next to the project root)"
}

cmd_run() {
  java $JAVA_OPTS -cp "$OUT_DIR:$LIB_DIR/*" "$MAIN_CLASS" "$@"
}

cmd_clean() {
  rm -rf target/
  echo "Cleaned."
}

is_command() {
  case "$1" in
    deps|build|jar|run|clean|all)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

usage() {
  echo "Usage: $0 [deps|build|jar|run [args...]|clean|all]..."
  echo "Examples:"
  echo "  $0 build"
  echo "  $0 clean build run"
  echo "  $0 build run -- --seed=123"
}

run_sequence() {
  if [[ $# -eq 0 ]]; then
    cmd_build
    return
  fi

  while [[ $# -gt 0 ]]; do
    cmd="$1"
    shift

    case "$cmd" in
      deps)
        deps
        ;;
      build)
        cmd_build
        ;;
      jar)
        cmd_jar
        ;;
      run)
        run_args=()
        if [[ $# -gt 0 && "$1" == "--" ]]; then
          shift
          run_args=("$@")
          set --
        else
          while [[ $# -gt 0 ]] && ! is_command "$1"; do
            run_args+=("$1")
            shift
          done
        fi
        cmd_run "${run_args[@]}"
        ;;
      clean)
        cmd_clean
        ;;
      all)
        cmd_build
        cmd_run
        ;;
      *)
        echo "Unknown command: $cmd" >&2
        usage
        exit 1
        ;;
    esac
  done
}

run_sequence "$@"
