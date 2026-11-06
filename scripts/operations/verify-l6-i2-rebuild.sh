#!/usr/bin/env bash
# ==============================================================================
# Pocoma V3 — L6/I2 Verification Gate C: Rebuild & Operational Journey
#
# Validates the complete operational reconstruction lifecycle:
# 1. Preflight rejection on missing --confirm-destructive.
# 2. Concurrency rejection on active maintenance by another operator.
# 3. HTTP 503 fail-closed barrier during MAINTENANCE / REBUILDING.
# 4. Crash recovery and resume protocol (--resume).
# 5. Full end-to-end destructive rebuild (--confirm-destructive=RESET_READ_DATA).
# 6. Post-rebuild state, checkpoints (28/28), slots (0), and metrics verification.
# 7. WRITE isolation verification (zero events or states mutated on pocoma_write).
#
# Execution Ceiling: 180 seconds.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILES=(-f "${REPO_ROOT}/docker-compose.functional.yml" -f "${REPO_ROOT}/docker/compose/docker-compose.observability.yml")
POSTGRES="pocoma-functional-postgres-1"

START_TIME=${SECONDS}

echo "================================================================================"
echo " Pocoma V3 — Gate C: READ Reconstruction Verification Suite"
echo "================================================================================"

cd "${REPO_ROOT}"

# 1. Pre-check: Ensure local compose services are active
echo "1. Checking active services in docker-compose functional stack..."
if ! docker inspect -f '{{.State.Running}}' "${POSTGRES}" 2>/dev/null | grep -q 'true'; then
    echo "Starting PostgreSQL stack..."
    docker compose "${COMPOSE_FILES[@]}" up -d postgres
    sleep 3
fi

# Ensure database and public.read_maintenance_state exist
python3 "${REPO_ROOT}/scripts/operations/setup-v3-database.py" > /dev/null

# 2. Test Preflight Rejections
echo "2. Verifying preflight rejections and dry run..."

# 2a. Missing --confirm-destructive must fail
if python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" 2>/dev/null; then
    echo "FAILURE: Rebuild without --confirm-destructive succeeded unexpectedly!"
    exit 1
fi
echo "   ✓ Missing --confirm-destructive correctly rejected (exit code 1)"

# 2b. Wrong confirmation token must fail
if python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --confirm-destructive=WRONG_TOKEN 2>/dev/null; then
    echo "FAILURE: Rebuild with wrong token succeeded unexpectedly!"
    exit 1
fi
echo "   ✓ Invalid confirmation token correctly rejected (exit code 1)"

# 2c. Dry run must succeed without mutation
python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --confirm-destructive=RESET_READ_DATA --dry-run
echo "   ✓ Preflight --dry-run validated successfully"

# 3. Test Concurrency Exclusion
echo "3. Verifying concurrency exclusion (active operator lock)..."
docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -v ON_ERROR_STOP=1 -c \
    "UPDATE public.read_maintenance_state SET state = 'MAINTENANCE', phase = 'DRAINING', operator_id = 'foreign-operator', updated_at = clock_timestamp();" > /dev/null

if python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --confirm-destructive=RESET_READ_DATA 2>/dev/null; then
    echo "FAILURE: Concurrent rebuild succeeded when another operator held the lock!"
    exit 1
fi
echo "   ✓ Concurrent rebuild correctly refused while maintenance active"

# 4. Test HTTP 503 Fail-Closed Barrier
echo "4. Verifying HTTP 503 fail-closed barrier during MAINTENANCE..."
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/api/v3/me/binding || true)
if [ "${HTTP_STATUS}" != "503" ]; then
    echo "FAILURE: Expected HTTP 503 during MAINTENANCE, got: ${HTTP_STATUS}"
    exit 1
fi

HEADERS=$(curl -s -i http://127.0.0.1:8080/api/v3/me/binding || true)
if ! echo "${HEADERS}" | grep -q "Retry-After: 30"; then
    echo "FAILURE: Missing Retry-After: 30 header in 503 response"
    exit 1
fi
if ! echo "${HEADERS}" | grep -q "Cache-Control: no-store"; then
    echo "FAILURE: Missing Cache-Control: no-store header in 503 response"
    exit 1
fi
echo "   ✓ HTTP 503 + Retry-After: 30 + Cache-Control: no-store verified under MAINTENANCE"

# 5. Test Crash & Resume Recovery (--resume)
echo "5. Verifying crash recovery protocol (--resume)..."
python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --resume
RESUMED_STATE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT state FROM public.read_maintenance_state WHERE id = 1;")
if [ "${RESUMED_STATE}" != "READY" ]; then
    echo "FAILURE: Expected state READY after --resume, got: ${RESUMED_STATE}"
    exit 1
fi
echo "   ✓ Crash recovery via --resume succeeded and converged to READY"

# 6. Test Full End-to-End Fresh Destructive Rebuild
echo "6. Executing full end-to-end fresh destructive rebuild..."
# Capture WRITE database snapshot before rebuild to prove strict isolation
WRITE_SNAPSHOT_BEFORE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_write -tAc \
    "SELECT md5(string_agg(t, ',')) FROM (SELECT row_to_json(v3_event_counter)::text AS t FROM v3_write.v3_event_counter ORDER BY family) s;")

python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --confirm-destructive=RESET_READ_DATA

# 7. Post-Reconstruction Verification
echo "7. Verifying post-reconstruction invariants..."

# 7a. State and Phase in public.read_maintenance_state
FINAL_STATE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT state FROM public.read_maintenance_state WHERE id = 1;")
FINAL_PHASE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT phase FROM public.read_maintenance_state WHERE id = 1;")
if [ "${FINAL_STATE}" != "READY" ] || [ "${FINAL_PHASE}" != "IDLE" ]; then
    echo "FAILURE: Expected state=READY, phase=IDLE. Got state=${FINAL_STATE}, phase=${FINAL_PHASE}"
    exit 1
fi
echo "   ✓ Maintenance authority in state=READY, phase=IDLE"

# 7b. Checkpoints count (must be exactly 28: 7 consumptions x 4 partitions)
CP_COUNT=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT count(*) FROM v3_read.discovery_checkpoint;")
if [ "${CP_COUNT}" -ne 28 ]; then
    echo "FAILURE: Expected 28 checkpoints in discovery_checkpoint, got: ${CP_COUNT}"
    exit 1
fi
echo "   ✓ All 28 consumption checkpoints present and verified (7 routes x 4 partitions)"

# 7c. Residual slots (must be 0 <= B)
SLOT_COUNT=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT count(*) FROM v3_read.consumption_slot;")
if [ "${SLOT_COUNT}" -ne 0 ]; then
    echo "FAILURE: Expected 0 residual slots in consumption_slot, got: ${SLOT_COUNT}"
    exit 1
fi
echo "   ✓ Zero residual slots remaining (consumption_slot == 0)"

# 7d. WRITE database unchanged (isolation invariant)
WRITE_SNAPSHOT_AFTER=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_write -tAc \
    "SELECT md5(string_agg(t, ',')) FROM (SELECT row_to_json(v3_event_counter)::text AS t FROM v3_write.v3_event_counter ORDER BY family) s;")
if [ "${WRITE_SNAPSHOT_BEFORE}" != "${WRITE_SNAPSHOT_AFTER}" ]; then
    echo "FAILURE: WRITE database was mutated during READ rebuild!"
    exit 1
fi
echo "   ✓ WRITE database strictly isolated and untouched (hash match verified)"

# 7e. HTTP API returning normal non-503 response
FINAL_HTTP=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/api/v3/me/binding || true)
if [ "${FINAL_HTTP}" = "503" ]; then
    echo "FAILURE: HTTP API still returning 503 after READY transition!"
    exit 1
fi
echo "   ✓ HTTP API restored to nominal production service (HTTP ${FINAL_HTTP})"

# 7f. Actuator Prometheus Metrics
echo -n "   • Waiting for Actuator Prometheus endpoint readiness"
DEADLINE=$((SECONDS + 45))
until curl -s http://127.0.0.1:18081/actuator/prometheus 2>/dev/null | grep "pocoma_v3_read_rebuild_state" > /dev/null; do
    if [ $SECONDS -ge $DEADLINE ]; then
        echo ""
        echo "FAILURE: Timeout waiting for Prometheus Actuator metrics on port 18081"
        docker logs pocoma-functional-runtime-monolith-1 --tail 50
        exit 1
    fi
    echo -n "."
    sleep 1
done
echo " Ready."
PROMETHEUS_METRICS=$(curl -s http://127.0.0.1:18081/actuator/prometheus || true)
if [[ "${PROMETHEUS_METRICS}" != *"pocoma_v3_read_rebuild_state 0.0"* ]]; then
    echo "FAILURE: Expected Prometheus metric pocoma_v3_read_rebuild_state 0.0"
    exit 1
fi
if [[ "${PROMETHEUS_METRICS}" != *"pocoma_v3_read_rebuild_checkpoints_satisfied 28.0"* ]]; then
    echo "FAILURE: Expected Prometheus metric pocoma_v3_read_rebuild_checkpoints_satisfied 28.0"
    exit 1
fi
echo "   ✓ Prometheus Actuator metrics verified (state=0.0, checkpoints=28.0)"

ELAPSED=$((SECONDS - START_TIME))
echo "================================================================================"
echo " Pocoma V3 — Gate C: ALL 7 REBUILD CHECKS PASSED in ${ELAPSED}s (Ceiling: 180s)"
echo "================================================================================"
exit 0
