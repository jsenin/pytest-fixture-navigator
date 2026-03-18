#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

echo "Building Fixture Navigator plugin..."
./gradlew clean buildPlugin

ZIP=$(ls build/distributions/*.zip 2>/dev/null | head -1)
if [[ -z "$ZIP" ]]; then
  echo "Build failed — no zip found in build/distributions/" >&2
  exit 1
fi

echo ""
echo "Done: $ZIP"
