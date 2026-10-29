#!/usr/bin/env bash
# tools/jvmtest/run.sh - compiles the app sources (via tools/check.sh) and runs the plain-JVM
# T9 / ContactMatcher test. android.jar is only on the classpath for linking; the tested code
# paths must be pure Java (android.jar stubs throw "Stub!" when invoked).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ANDROID_JAR="${ANDROID_JAR:-/tmp/claude-0/-home-claude/45c174cd-86d0-5acd-adc0-a68b5c77f238/scratchpad/android-platforms/repo/android-35/android.jar}"
export ANDROID_JAR
if [ ! -f "$ANDROID_JAR" ]; then
  echo "android.jar not found: $ANDROID_JAR (set ANDROID_JAR)" >&2
  exit 2
fi

# 1) Build the app classes into tools/out/classes (and run all static checks).
# (check.sh recreates tools/out, so its log is kept in a temporary file.)
CHECK_LOG="$(mktemp)"
trap 'rm -f "$CHECK_LOG"' EXIT
if ! tools/check.sh > "$CHECK_LOG" 2>&1; then
  cat "$CHECK_LOG"
  echo "tools/check.sh failed" >&2
  exit 1
fi
grep -E 'Summary:|RESULT:' "$CHECK_LOG"

# 2) Compile and run the JVM test.
rm -rf tools/out/jvmtest
mkdir -p tools/out/jvmtest
javac --release 17 -proc:none -encoding UTF-8 -cp "$ANDROID_JAR:tools/out/classes" \
  -d tools/out/jvmtest tools/jvmtest/se/jobbdialer/T9Test.java
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$ANDROID_JAR:tools/out/classes:tools/out/jvmtest" se.jobbdialer.T9Test
