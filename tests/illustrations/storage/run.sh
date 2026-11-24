#!/usr/bin/env bash
set -euo pipefail
ILLUSTRATION_TEST_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
ILLUSTRATION_PROJECT_DIR="$(cd -- "$ILLUSTRATION_TEST_DIR/../../.." && pwd)"
ILLUSTRATION_JAVA_HOME="${ILLUSTRATION_JAVA_HOME:-${JAVA_HOME:-}}"
java_cmd=java
if [[ -n "$ILLUSTRATION_JAVA_HOME" ]]; then
    java_cmd="$ILLUSTRATION_JAVA_HOME/bin/java"
fi
ILLUSTRATION_JSON_JAR="${1:-${ILLUSTRATION_JSON_JAR:-}}"
: "${ILLUSTRATION_JSON_JAR:?Usage: bash tests/illustrations/storage/run.sh /path/org-json.jar}"
ILLUSTRATION_CLASSES="$(mktemp -d)"
trap 'rm -rf -- "$ILLUSTRATION_CLASSES"' EXIT
export JAVA_TOOL_OPTIONS="-Xmx256m"
mapfile -t ILLUSTRATION_SOURCES < <(find "$ILLUSTRATION_TEST_DIR/harness" -name '*.java' -print)
"$java_cmd" -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 -cp "$ILLUSTRATION_JSON_JAR" -d "$ILLUSTRATION_CLASSES" \
  "${ILLUSTRATION_SOURCES[@]}" \
  "$ILLUSTRATION_PROJECT_DIR/app/src/main/java/jp/haruya/novelstudio/IllustrationStore.java" \
  "$ILLUSTRATION_PROJECT_DIR/app/src/main/java/jp/haruya/novelstudio/IllustrationBackup.java"
"$java_cmd" -Djava.awt.headless=true -cp "$ILLUSTRATION_CLASSES:$ILLUSTRATION_JSON_JAR" jp.haruya.novelstudio.StorageChecks
