#!/usr/bin/env bash
set -euo pipefail
test_root="$(cd "$(dirname "$0")/../.." && pwd)"
json_jar="${1:?Usage: bash tests/illustrations/run-image-client.sh /path/org-json.jar}"
test_output="$(mktemp -d -t novelstudio-image-client-XXXXXX)"
trap 'rm -rf "$test_output"' EXIT
mapfile -t fixtures < <(rg --files -g '*.java' "$test_root/tests/illustrations/image-client")
java -Xmx256m -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 \
  -classpath "$json_jar" -d "$test_output" \
  "$test_root/app/src/main/java/jp/haruya/novelstudio/ImageGenerationClient.java" "${fixtures[@]}"
java -Xmx256m -classpath "$test_output:$json_jar" jp.haruya.novelstudio.ImageClientChecks
