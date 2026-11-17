#!/usr/bin/env bash
# 4E -> drained 4D twice on the same disposable V31 PREPARED database.
# No activation and no edits to any checkout: the existing harness uses exports.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"
exec scripts/testing/verify-4d-previous-runtime.sh "${1:-465f7a784423da356b7b56f5fb66b489412480a1}"
