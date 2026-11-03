#!/usr/bin/env bash
# Compiles the pure-Java classes (no Android dependencies) with their tests and runs them on
# the local JDK.
set -euo pipefail
cd "$(dirname "$0")/.."
JH="$(ls -d ../tools/jdk/jdk-17*)"
OUT="$(mktemp -d)"
P=src/nl/japandi/dashboard
"$JH/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:all -Xlint:-options -Werror -d "$OUT" \
    $P/TimeUtil.java $P/Birthdays.java $P/TaskOrder.java $P/Paging.java $P/PixelFont.java \
    test/TimeUtilTest.java test/LogicTest.java
"$JH/bin/java" -cp "$OUT" nl.japandi.dashboard.TimeUtilTest
"$JH/bin/java" -cp "$OUT" nl.japandi.dashboard.LogicTest
