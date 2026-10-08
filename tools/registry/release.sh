#!/bin/bash
# Builds every package from a clean checkout of one commit and publishes it to the hosted repositories.
#
#   tools/registry/release.sh <commit-or-tag> [--dry-run]
#
# Order: Maven (Java, Android core, Android drop-in), npm (web, mini program), SwiftPM git repositories
# (iOS, iOS drop-in), documentation site built by tools/docs-site. The site is built before anything is
# published, so a broken link stops the release. Released versions are immutable: every publish step refuses
# to overwrite an existing version with different content.
set -euo pipefail
REV=${1:?commit or tag}
DRY=${2:-}
SRC_REPO=${SRC_REPO:-/home/ubuntu/yougu/sdk-ci/repo.git}
WEB=${WEB_ROOT:-/home/ubuntu/yougu/frontend/prod-ygyx/index}
TOOLS=/home/ubuntu/tools/yugu-sdk-toolchain
export ANDROID_HOME=$TOOLS/android-sdk ANDROID_SDK_ROOT=$TOOLS/android-sdk
export PATH="$TOOLS/swift/usr/bin:$TOOLS/gradle-8.9/bin:$PATH"
WORK=$(mktemp -d /tmp/claude-1000/release-XXXX)
STAGE=$WORK/stage
mkdir -p "$STAGE/maven" "$STAGE/npm"
trap 'echo "work dir kept at $WORK"' EXIT
git clone -q "$SRC_REPO" "$WORK/src"
cd "$WORK/src"
git checkout -q "$REV"
echo "== releasing $(git rev-parse --short HEAD) $(git log -1 --format=%s)"

echo "== Java"
(cd java && ./mvnw -q -B -ntp -DskipTests deploy \
  -DaltDeploymentRepository=stage::file://$STAGE/maven \
  -Daether.checksums.algorithms=SHA-512,SHA-256,SHA-1,MD5)

echo "== Android core"
(cd android && printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties && \
  ./gradlew -q --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m --max-workers=2 \
  :yugu-android-sdk:publishToYuguDir -PyuguPublishDir="$STAGE/maven")

echo "== Android Shengtong drop-in"
(cd android-stcompat && printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties && \
  ./gradlew -q --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m --max-workers=2 \
  :stkouyu-compat:publishReleasePublicationToYuguRepository -PyuguPublishDir="$STAGE/maven")

echo "== web"
(cd web && npm ci --no-audit --no-fund --silent && npm run build --silent && npm pack --silent --pack-destination "$STAGE/npm")
echo "== mini program"
(cd miniprogram && npm ci --no-audit --no-fund --silent && npm run build --silent && npm pack --silent --pack-destination "$STAGE/npm")

echo "== documentation site"
(cd tools/docs-site && npm ci --no-audit --no-fund --silent)
node tools/docs-site/build.mjs --repo . --out "$WORK/docs-site"

echo "== staged files"
find "$STAGE" -type f | sed "s#$STAGE/##" | sort | head -80

if [ "$DRY" = "--dry-run" ]; then
  python3 -I tools/registry/maven_publish.py --staging "$STAGE/maven" --repo "$WEB/maven" --dry-run
  for t in "$STAGE"/npm/*.tgz; do python3 -I tools/registry/npm_publish.py --tgz "$t" --registry-dir "$WEB/npm" --base-url https://open.shengzhiai.com/npm/ --dry-run; done
  echo "dry run, nothing published"
  exit 0
fi

echo "== publish Maven"
python3 -I tools/registry/maven_publish.py --staging "$STAGE/maven" --repo "$WEB/maven"
echo "== publish npm"
for t in "$STAGE"/npm/*.tgz; do
  python3 -I tools/registry/npm_publish.py --tgz "$t" --registry-dir "$WEB/npm" --base-url https://open.shengzhiai.com/npm/
done
VERSION=$(python3 -I -c "import json;print(json.load(open('web/package.json'))['version'])")
echo "== publish SwiftPM repositories $VERSION"
bash tools/registry/git_publish.sh ios yugu-ios-sdk "$VERSION" "$WEB/git"
bash tools/registry/git_publish.sh ios-stcompat stkouyu-ios-compat "$VERSION" "$WEB/git"
echo "== publish documentation"
# Pages and raw files follow the new build; hashed assets are only added, so pages already open in a
# browser can still load the files they reference.
rsync -a --delete --exclude '/assets/' "$WORK/docs-site/" "$WEB/sdk/v2/"
rsync -a "$WORK/docs-site/assets/" "$WEB/sdk/v2/assets/"
echo "== done"
