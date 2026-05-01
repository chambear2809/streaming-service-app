#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
POM_FILE="${REPO_ROOT}/services/user-service/pom.demo.xml"
APP_FILE="${REPO_ROOT}/services/user-service/src/main/java/io/github/marianciuc/streamingservice/user/demo/DemoUserApplication.java"
SECURITY_FILE="${REPO_ROOT}/services/user-service/src/main/java/io/github/marianciuc/streamingservice/user/demo/DemoSecurityConfig.java"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_file_contains() {
  local file_path="$1"
  local expected="$2"

  grep -Fq "${expected}" "${file_path}" || fail "${file_path} is missing expected content: ${expected}"
}

assert_file_contains "${POM_FILE}" "<artifactId>spring-boot-starter-security</artifactId>"
assert_file_contains "${APP_FILE}" 'application.setAdditionalProfiles("broadcast-demo");'
assert_file_contains "${SECURITY_FILE}" '@Profile("broadcast-demo")'
assert_file_contains "${SECURITY_FILE}" 'auth.anyRequest().permitAll()'

printf 'PASS: user-service demo profile regression test\n'
