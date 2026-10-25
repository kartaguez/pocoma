#!/usr/bin/env bash
set -euo pipefail
task_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
exec python3 -B "$task_root/scripts/testing/summarize-v3.py" --root "$task_root" --gate "${1:-foundation}"
