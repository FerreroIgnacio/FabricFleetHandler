#!/usr/bin/env bash
#DEBUG PARA HOTREALOAD
# Compiles the mod (main, debug, common) and redefines the changed classes in the running runClient.
# Run from anywhere. Needs the client launched by runClient (JDWP on 5005) and its stamp touched at launch.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"
V="$ROOT/versions/1.12.2"
cd "$ROOT"
./gradlew -q :versions:1.12.2:classes :versions:1.12.2:debugClasses
java "$V/src/debug/hotswap/Hotswap.java" 5005 "$V/run/mapmcbot/debug/hotswap.stamp" \
	"$V/build/classes/java/main" "$V/build/classes/java/debug" "$ROOT/common/build/classes/java/main"
