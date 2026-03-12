#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
echo "Starting PyCharm with Fixture Navigator plugin..."
./gradlew clean runIde
