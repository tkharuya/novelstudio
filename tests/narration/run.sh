#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/../.." && pwd)"
json_jar="${1:?Usage: bash tests/narration/run.sh /path/org-json.jar}"
test_output="$(mktemp -d -t novelstudio-narration-XXXXXX)"
trap 'rm -rf "$test_output"' EXIT
mapfile -t fixtures < <(rg --files -g '*.java' "$project_root/tests/narration/harness")
java -Xmx256m -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 -classpath "$json_jar" -d "$test_output" \
  "${fixtures[@]}" "$project_root/tests/background/journal/harness/android/util/AtomicFile.java" \
  "$project_root/app/src/main/java/jp/haruya/novelstudio/NarrationStore.java" \
  "$project_root/app/src/main/java/jp/haruya/novelstudio/SpeechCatalog.java" \
  "$project_root/app/src/main/java/jp/haruya/novelstudio/SpeechAudioClient.java" \
  "$project_root/tests/narration/NarrationChecks.java"
java -Xmx256m -classpath "$test_output:$json_jar" jp.haruya.novelstudio.NarrationChecks
