#!/usr/bin/env bash
set -euo pipefail
# PostgreSQL connection from PGSERVICE/PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSFILE.
# Never print passwords or put a connection URL on the command line.
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
action=${1:-}; runtime=${2:-}; operation=${3:-}
if [[ "$action" != inspect && "$action" != activate ]] || [[ -z "$runtime" ]]; then
  echo 'Usage: event-sequencing.sh inspect RUNTIME_ROLE | activate RUNTIME_ROLE OPERATION_UUID' >&2; exit 2
fi
if [[ "$action" == activate ]]; then
  [[ "$operation" =~ ^[[:xdigit:]]{8}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{12}$ ]] || { echo 'Stable operation UUID required' >&2; exit 2; }
  # Calling activate is explicit administrative intent; runbook authorization remains environment-specific.
  exec psql -X -v ON_ERROR_STOP=1 -v runtime_role="$runtime" -v operation_id="$operation" \
    -v lock_timeout="${POCOMA_CUTOVER_LOCK_TIMEOUT:-5s}" \
    -v statement_timeout="${POCOMA_CUTOVER_STATEMENT_TIMEOUT:-5min}" \
    -f "$root/docs/operations/sql/event-sequencing-cutover.sql"
fi
exec psql -X -v ON_ERROR_STOP=1 -v runtime_role="$runtime" -f "$root/docs/operations/sql/event-sequencing-inspect.sql"
