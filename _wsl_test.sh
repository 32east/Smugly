#!/usr/bin/env bash
# Sync the working tree into the WSL build copy and run the given Gradle tasks there.
set -uo pipefail
SRC=/mnt/c/Users/newbie/Documents/vphysics-compile/SlipstreamCLI
DST=/home/reil/Smugly-ui
mkdir -p "$DST"
rsync -a --delete \
  --exclude '.gradle' --exclude 'desktop/build' --exclude 'shared/build' \
  --exclude 'app/build' --exclude 'build/' --exclude '.git' --exclude 'engines' \
  --exclude '/*.log' --exclude '/*.txt' \
  "$SRC/" "$DST/"
echo "sdk.dir=/home/reil/android-sdk" > "$DST/local.properties"
export ANDROID_HOME=/home/reil/android-sdk ANDROID_SDK_ROOT=/home/reil/android-sdk HOME=/home/reil
export PATH="/home/reil/.cargo/bin:$ANDROID_HOME/platform-tools:$PATH"
cd "$DST"
chmod +x gradlew
./gradlew "$@" --console=plain 2>&1 | grep -vE "^\s*$|^> Task .*(UP-TO-DATE|NO-SOURCE|SKIPPED)$" | tail -${TAIL_LINES:-80}
