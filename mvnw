#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_JAVA_HOME="$SCRIPT_DIR/.tools/jdk/jdk-22.0.2+9/Contents/Home"
REPO_MAVEN_LOCAL="$SCRIPT_DIR/.tools/m2/repository"

if [ -d "$REPO_JAVA_HOME" ]; then
  JAVA_HOME="$REPO_JAVA_HOME"
  export JAVA_HOME
  PATH="$JAVA_HOME/bin:$PATH"
  export PATH
fi

mkdir -p "$REPO_MAVEN_LOCAL"

exec sh "$SCRIPT_DIR/services/content-service/mvnw" -Dmaven.repo.local="$REPO_MAVEN_LOCAL" "$@"
