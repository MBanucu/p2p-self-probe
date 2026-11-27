#!/bin/sh
# Plain-JDK tests: ProbeLogic unit checks and the recorded run-3 fixture.
# Run inside `nix develop` (or any shell with javac/java on PATH).
set -eu

here=$(cd -- "$(dirname -- "$0")" && pwd -P)
root=$(cd -- "$here/.." && pwd -P)
out=${TMPDIR:-/tmp}/p2p-self-probe-tests
rm -rf "$out"
mkdir -p "$out"

javac --release 11 -encoding UTF-8 -d "$out" \
    "$root/src/org/phonelink/p2pprobe/ProbeLogic.java" \
    "$here/org/phonelink/p2pprobe/ProbeLogicTest.java"

java -cp "$out" org.phonelink.p2pprobe.ProbeLogicTest "$here/fixtures/run3.log"
