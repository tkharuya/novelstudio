#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/../.." && pwd)"
json_jar="${1:?Usage: bash tests/batch/run.sh /path/org-json.jar}"
test_output="$(mktemp -d -t novelstudio-batch-XXXXXX)"
trap 'rm -rf "$test_output"' EXIT
mapfile -t fixtures < <(rg --files -g '*.java' "$project_root/tests/background/runtime/harness" | rg -v '/(GenerationJournal|DraftStore|RuntimeChecks)\.java$')
java -Xmx256m -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 \
  -classpath "$json_jar" -d "$test_output" "${fixtures[@]}" \
  "$project_root/tests/background/journal/harness/android/util/AtomicFile.java" \
  "$project_root/app/src/main/java/jp/haruya/novelstudio/GenerationRuntime.java" \
  "$project_root/app/src/main/java/jp/haruya/novelstudio/GenerationJournal.java" \
  "$project_root/app/src/main/java/jp/haruya/novelstudio/DraftStore.java" \
  "$project_root/app/src/main/java/jp/haruya/novelstudio/BatchGeneration.java" \
  "$project_root/tests/batch/BatchChecks.java"
java -Xmx512m -classpath "$test_output:$json_jar" jp.haruya.novelstudio.BatchChecks
