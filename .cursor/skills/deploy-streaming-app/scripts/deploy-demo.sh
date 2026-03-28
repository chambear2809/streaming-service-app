#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel 2>/dev/null || true)"

if [[ -z "${REPO_ROOT}" ]]; then
  printf '[deploy-streaming-app] ERROR: Could not determine the repository root. Run this script from within the streaming-service-app git checkout.\n' >&2
  exit 1
fi

exec bash "${REPO_ROOT}/skills/deploy-streaming-app/scripts/deploy-demo.sh" "$@"
