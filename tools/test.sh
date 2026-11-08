#!/bin/sh
# Compiles the engine as Java 8 (what the phone build uses) and runs the desktop tests.
# usage: tools/test.sh [test names...]
set -e
cd "$(dirname "$0")/.."
rm -rf build/core build/test
mkdir -p build/core build/test
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d build/core $(find src/com/alihelal/core -name '*.java') 2>&1 | grep -v '^Picked\|^Note' || true
javac --release 21 -encoding UTF-8 -cp build/core -d build/test test/com/alihelal/core/CoreTest.java 2>&1 | grep -v '^Picked\|^Note' || true
# The music remover for this computer (the phone's are built by build.sh).
mkdir -p build/sep
(cd native/sep && go build -o ../../build/sep/helinksep .)
export LC_ALL=C.UTF-8 LANG=C.UTF-8
exec java -Dsun.jnu.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp build/core:build/test com.alihelal.core.CoreTest "$@"
