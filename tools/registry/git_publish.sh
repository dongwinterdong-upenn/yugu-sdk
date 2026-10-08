#!/bin/bash
# Publish a directory as a read-only git repository served over dumb HTTP, tagged for SwiftPM.
#
#   tools/registry/git_publish.sh <src-dir> <repo-name> <tag> [<served-git-root>]
#
# The bare repository lives at <served-git-root>/<repo-name>.git (default
# /home/ubuntu/yougu/frontend/prod-ygyx/index/git). Each release adds one commit on main with the
# exact content of <src-dir> and an annotated tag. Existing tags are never moved.
set -euo pipefail
SRC=$(cd "$1" && pwd)
NAME=$2
TAG=$3
ROOT=${4:-/home/ubuntu/yougu/frontend/prod-ygyx/index/git}
BARE="$ROOT/$NAME.git"
mkdir -p "$ROOT"
if [ ! -d "$BARE" ]; then
  git init -q --bare -b main "$BARE"
  echo "优谷雅言 $NAME" > "$BARE/description"
fi
if git --git-dir="$BARE" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "tag $TAG already exists in $NAME, refusing to move it" >&2
  exit 2
fi
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
git clone -q "$BARE" "$WORK/repo" 2>/dev/null || git init -q -b main "$WORK/repo"
cd "$WORK/repo"
git config user.name "Yugu SDK"
git config user.email "sdk-noreply@open.shengzhiai.com"
git checkout -q -B main
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
rsync -a --exclude '.git' --exclude '.build' --exclude '.swiftpm' --exclude 'ci-out' --exclude 'node_modules' \
  --exclude '*.profraw' --exclude 'coverage' "$SRC/" ./
git add -A
git commit -q -m "Release $TAG" --allow-empty
git tag -a "$TAG" -m "$NAME $TAG"
git push -q origin main "refs/tags/$TAG"
git --git-dir="$BARE" update-server-info
echo "published $NAME $TAG at $BARE ($(git rev-parse --short HEAD))"
