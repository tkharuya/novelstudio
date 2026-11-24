#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/../../.." && pwd)"
json_jar="${1:?Usage: bash tests/background/runtime/run.sh /path/org-json.jar}"
test_output="$(mktemp -d -t novelstudio-runtime-XXXXXX)"
trap 'rm -rf "$test_output"' EXIT
mapfile -t fixtures < <(rg --files -g '*.java' "$project_root/tests/background/runtime/harness")
java -Xmx256m -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 \
  -classpath "$json_jar" -d "$test_output" \
  "$project_root/app/src/main/java/jp/haruya/novelstudio/GenerationRuntime.java" \
  "$project_root/app/src/main/java/jp/haruya/novelstudio/BatchGeneration.java" \
  "$project_root/tests/background/journal/harness/android/util/AtomicFile.java" "${fixtures[@]}"
java -Xmx256m -classpath "$test_output:$json_jar" jp.haruya.novelstudio.RuntimeChecks
