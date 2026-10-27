#!/bin/zsh
set -euo pipefail
ANDROID_TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ANDROID_TASK_ROOT/output/cache/android/gradle-user}"
cd "$ANDROID_TASK_ROOT/android"
exec ./gradlew "$@"
