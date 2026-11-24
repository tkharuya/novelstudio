#!/usr/bin/env bash
set -euo pipefail
test_root="$(cd "$(dirname "$0")/../.." && pwd)"
android_jar="${1:?Usage: bash tests/siwc/run.sh /path/android.jar /path/org-json.jar}"
json_jar="${2:?Supply a standalone org.json runtime jar for JVM tests}"
test_output="$(mktemp -d -t novelstudio-siwc-XXXXXX)"
trap 'rm -rf "$test_output"' EXIT
mkdir -p "$test_output/classes" "$test_output/testclasses"
java -Xmx256m -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 \
  -classpath "$android_jar" -d "$test_output/classes" \
  "$test_root/app/src/main/java/jp/haruya/novelstudio/SiwcClient.java" \
  "$test_root/app/src/main/java/jp/haruya/novelstudio/SecureStore.java"
mapfile -t fixtures < <(rg --files -g '*.java' "$test_root/tests/siwc/harness")
java -Xmx256m -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 \
  -classpath "$test_output/classes:$json_jar:$android_jar" -d "$test_output/testclasses" \
  "${fixtures[@]}"
java -Xmx256m -classpath "$test_output/testclasses:$test_output/classes:$json_jar:$android_jar" \
  jp.haruya.novelstudio.SiwcChecks
