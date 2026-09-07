#!/usr/bin/env bash
# Fast pure-Kotlin verification. Requires a normal JDK 17+ and Kotlin compiler 2.0.21+.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p core/build/standalone
kotlinc core/src/main/kotlin core/src/test/kotlin -include-runtime -d core/build/standalone/core-tests.jar
java -jar core/build/standalone/core-tests.jar
