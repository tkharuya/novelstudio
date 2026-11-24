#!/usr/bin/env bash
set -euo pipefail
JOURNAL_TEST_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
JOURNAL_PROJECT_DIR="$(cd -- "$JOURNAL_TEST_DIR/../../.." && pwd)"
JOURNAL_JAVA_HOME="${JOURNAL_JAVA_HOME:-${JAVA_HOME:-}}"
java_cmd=java
if [[ -n "$JOURNAL_JAVA_HOME" ]]; then
    java_cmd="$JOURNAL_JAVA_HOME/bin/java"
fi
JOURNAL_JSON_JAR="${1:-${JOURNAL_JSON_JAR:-}}"
: "${JOURNAL_JSON_JAR:?Usage: bash tests/background/journal/run.sh /path/org-json.jar}"
JOURNAL_CLASSES="$(mktemp -d)"
trap 'rm -rf -- "$JOURNAL_CLASSES"' EXIT
export JAVA_TOOL_OPTIONS="-Xmx256m"
mapfile -t JOURNAL_SOURCES < <(rg --files "$JOURNAL_TEST_DIR/harness" -g '*.java')
"$java_cmd" -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 -cp "$JOURNAL_JSON_JAR" -d "$JOURNAL_CLASSES" \
  "${JOURNAL_SOURCES[@]}" \
  "$JOURNAL_PROJECT_DIR/app/src/main/java/jp/haruya/novelstudio/GenerationJournal.java"
"$java_cmd" -cp "$JOURNAL_CLASSES:$JOURNAL_JSON_JAR" jp.haruya.novelstudio.JournalChecks
