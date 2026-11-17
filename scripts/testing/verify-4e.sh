#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
mode=${1:-local}
[[ "$mode" == local || "$mode" == closure ]] || { echo 'Usage: verify-4e.sh [local|closure]' >&2; exit 2; }
evidence=${POCOMA_4E_EVIDENCE_DIR:-$(mktemp -d /private/tmp/pocoma-4e-gates.XXXXXX)}
mkdir -p "$evidence"
cd "$root/app"
run() {
  local name=$1; shift
  local started; started=$(date +%s)
  local status=0
  ./mvnw "$@" > "$evidence/$name.log" 2>&1 || status=$?
  python3 "$root/scripts/testing/summarize-4e.py" "$root/app" "$evidence" "$name" "$started" "$status" "$@"
  [[ "$status" == 0 ]] || { echo "Gate $name failed; evidence: $evidence" >&2; exit "$status"; }
}
if [[ "$mode" == local ]]; then
  run local -pl runtime-command-consumption-worker -am test \
    -Dtest=EventSequencingAdministrationPostgresTest,EventSequencingTransportPostgresTest,EventSequencingQualificationPostgresTest \
    -Dsurefire.failIfNoSpecifiedTests=false
else
  run ve -pl runtime-command-consumption-worker,runtime-web-api,runtime-binding-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker,runtime-latest-known-version-consumption-worker -am test
  run architecture -pl architecture-tests -am test
  run full test
fi
printf 'Evidence: %s\n' "$evidence"
