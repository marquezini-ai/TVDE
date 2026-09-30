#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
# Isolated empty build cache; never delete the user's shared Gradle home/caches.
cache=$(mktemp -d -t tvde-gradle-cache.XXXXXX)
trap 'rmdir -- "$cache" 2>/dev/null || true' EXIT
# Windows lint workers can retain open JAR handles after a previous release build.
./gradlew --stop
./gradlew clean assembleDebug --parallel --no-daemon --no-build-cache --no-configuration-cache --rerun-tasks -PqaBuildCacheDir="$cache"
