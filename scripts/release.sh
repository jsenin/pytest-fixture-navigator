#!/usr/bin/env bash
#set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  echo "Usage: ./scripts/release.sh <version>  (e.g. 1.2.0)" >&2
  exit 1
fi

echo "Bumping version to $VERSION..."
if [[ "$(uname)" == "Darwin" ]]; then
  sed -i '' "s/^version\s*=\s*\".*\"/version = \"$VERSION\"/" build.gradle.kts
else
  sed -i "s/^version\s*=\s*\".*\"/version = \"$VERSION\"/" build.gradle.kts
fi

echo "Building plugin..."
./gradlew clean buildPlugin

ZIP=$(ls build/distributions/*.zip 2>/dev/null | head -1)
if [[ -z "$ZIP" ]]; then
  echo "Build failed — no zip found in build/distributions/" >&2
  exit 1
fi

echo "Tagging $VERSION..."
git add build.gradle.kts
git commit -m "chore: release v$VERSION"
git tag "$VERSION"

echo ""
echo "Done: $ZIP"
echo ""
echo "Next steps:"
echo "  git push origin main --tags"
echo "  Upload $ZIP to the GitHub release for v$VERSION"
