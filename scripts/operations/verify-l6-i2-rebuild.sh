#!/usr/bin/env bash
# ==============================================================================
# Pocoma V3 — L6/I2 Verification Gate C: Rebuild & Operational Journey
#
# Validates the complete operational reconstruction lifecycle:
# 1. Preflight rejection on missing or invalid confirmation token.
# 2. Concurrency rejection on active maintenance by another operator (CAS).
# 3. Rejection of implicit rebuild start from FAILED state without reset.
# 4. HTTP 503 fail-closed barrier during MAINTENANCE / REBUILDING.
# 5. Non-destructive crash recovery protocol (--resume).
# 6. Active WRITE permission denial proof (pocoma_read cannot write to pocoma_write).
# 7. Full end-to-end destructive rebuild (--confirm-destructive=RESET_READ_DATA).
# 8. Post-rebuild state, checkpoints (28/28), slots (0), and metrics verification.
# 9. Multi-table WRITE isolation verification.
# 10. Discriminant HTTP smoke test (rejecting 000 and 503; verifying 401 unauthenticated and 200 Actuator).
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

# Ensure database and public.read_maintenance_state exist and are clean
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

# 3. Test Concurrency Exclusion & FAILED State Fencing
echo "3. Verifying concurrency exclusion (CAS operator lock) & FAILED state fencing..."
docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -v ON_ERROR_STOP=1 -c \
    "UPDATE public.read_maintenance_state SET state = 'MAINTENANCE', phase = 'DRAINING', operator_id = 'foreign-operator', operation_id = '11111111-1111-1111-1111-111111111111'::uuid, updated_at = clock_timestamp();" > /dev/null

# Concurrent rebuild by another operator must be rejected
if python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --confirm-destructive=RESET_READ_DATA --operator-id=local-operator 2>/dev/null; then
    echo "FAILURE: Concurrent rebuild succeeded when foreign operator held the lock!"
    exit 1
fi
echo "   ✓ Concurrent rebuild correctly refused while foreign operator holds lock"

# Test FAILED state fencing: cannot start fresh rebuild from FAILED without reset
docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -v ON_ERROR_STOP=1 -c \
    "UPDATE public.read_maintenance_state SET state = 'FAILED', error_detail = 'Simulated prior failure', updated_at = clock_timestamp();" > /dev/null

if python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --confirm-destructive=RESET_READ_DATA 2>/dev/null; then
    echo "FAILURE: Fresh rebuild started from FAILED state without explicit reset!"
    exit 1
fi
echo "   ✓ Implicit restart from FAILED state correctly refused"

# Reset failed state via CLI
python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --reset-failed
RESET_STATE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT state FROM public.read_maintenance_state WHERE id = 1;")
if [ "${RESET_STATE}" != "READY" ]; then
    echo "FAILURE: Expected state READY after --reset-failed, got: ${RESET_STATE}"
    exit 1
fi
echo "   ✓ Explicit --reset-failed restored authority to READY"

# 4. Test HTTP 503 Fail-Closed Barrier
echo "4. Verifying HTTP 503 fail-closed barrier during MAINTENANCE..."
# Temporarily set to MAINTENANCE
docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -v ON_ERROR_STOP=1 -c \
    "UPDATE public.read_maintenance_state SET state = 'MAINTENANCE', phase = 'DRAINING', operator_id = 'test-op', operation_id = '22222222-2222-2222-2222-222222222222'::uuid, updated_at = clock_timestamp();" > /dev/null

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

# 5. Test Active WRITE Permission Denial (AUD-L6-I2-04 & AUD-L6-I2-10)
echo "5. Proving active PostgreSQL permission denial on WRITE database (role: pocoma_read)..."
# Attempt UPDATE on v3_event_counter as pocoma_read
set +e
WRITE_MUTATION_OUT=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma_read -d pocoma_write -v ON_ERROR_STOP=1 -c \
    "UPDATE v3_write.v3_event_counter SET position = 999 WHERE family = 'POT';" 2>&1)
MUT_STATUS=$?
set -e
if [ ${MUT_STATUS} -eq 0 ]; then
    echo "FAILURE: pocoma_read role was able to mutate v3_event_counter on pocoma_write!"
    exit 1
fi
if ! echo "${WRITE_MUTATION_OUT}" | grep -q "permission denied"; then
    echo "FAILURE: Expected permission denied error, got: ${WRITE_MUTATION_OUT}"
    exit 1
fi
echo "   ✓ PostgreSQL strictly denies WRITE mutations to rebuild role (42501 permission denied)"

# 6. Test Non-Destructive Crash Recovery Protocol (--resume)
echo "6. Verifying non-destructive crash recovery protocol (--resume)..."
# Authority is currently in MAINTENANCE/DRAINING
python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --resume --operator-id=test-op
RESUMED_STATE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT state FROM public.read_maintenance_state WHERE id = 1;")
if [ "${RESUMED_STATE}" != "READY" ]; then
    echo "FAILURE: Expected state READY after --resume, got: ${RESUMED_STATE}"
    exit 1
fi
echo "   ✓ Crash recovery via --resume succeeded non-destructively and converged to READY"

# 7. Test Full End-to-End Fresh Destructive Rebuild
echo "7. Executing full end-to-end fresh destructive rebuild..."
# Capture multi-table WRITE database snapshot before rebuild to prove strict isolation
WRITE_SNAPSHOT_BEFORE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_write -tAc \
    "SELECT md5(string_agg(t, ',')) FROM (
        SELECT row_to_json(c)::text AS t FROM (SELECT * FROM v3_write.v3_event_counter ORDER BY family) c
        UNION ALL
        SELECT count(*)::text FROM v3_write.v3_command
        UNION ALL
        SELECT count(*)::text FROM v3_write.v3_pot_event
        UNION ALL
        SELECT count(*)::text FROM v3_write.v3_event_position
    ) s;")

python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --confirm-destructive=RESET_READ_DATA

# 8. Post-Reconstruction Verification
echo "8. Verifying post-reconstruction invariants..."

# 8a. State and Phase in public.read_maintenance_state
FINAL_STATE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT state FROM public.read_maintenance_state WHERE id = 1;")
FINAL_PHASE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT phase FROM public.read_maintenance_state WHERE id = 1;")
if [ "${FINAL_STATE}" != "READY" ] || [ "${FINAL_PHASE}" != "IDLE" ]; then
    echo "FAILURE: Expected state=READY, phase=IDLE. Got state=${FINAL_STATE}, phase=${FINAL_PHASE}"
    exit 1
fi
echo "   ✓ Maintenance authority in state=READY, phase=IDLE"

# 8b. Checkpoints count (must be exactly 28: 7 consumptions x 4 partitions)
CP_COUNT=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT count(*) FROM v3_read.discovery_checkpoint;")
if [ "${CP_COUNT}" -ne 28 ]; then
    echo "FAILURE: Expected 28 checkpoints in discovery_checkpoint, got: ${CP_COUNT}"
    exit 1
fi
echo "   ✓ All 28 consumption checkpoints present and verified (7 routes x 4 partitions)"

# 8c. Residual slots (must be 0 <= B)
SLOT_COUNT=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT count(*) FROM v3_read.consumption_slot;")
if [ "${SLOT_COUNT}" -ne 0 ]; then
    echo "FAILURE: Expected 0 residual slots in consumption_slot, got: ${SLOT_COUNT}"
    exit 1
fi
echo "   ✓ Zero residual slots remaining (consumption_slot == 0)"

# 8d. WRITE database untouched (multi-table isolation invariant)
WRITE_SNAPSHOT_AFTER=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_write -tAc \
    "SELECT md5(string_agg(t, ',')) FROM (
        SELECT row_to_json(c)::text AS t FROM (SELECT * FROM v3_write.v3_event_counter ORDER BY family) c
        UNION ALL
        SELECT count(*)::text FROM v3_write.v3_command
        UNION ALL
        SELECT count(*)::text FROM v3_write.v3_pot_event
        UNION ALL
        SELECT count(*)::text FROM v3_write.v3_event_position
    ) s;")
if [ "${WRITE_SNAPSHOT_BEFORE}" != "${WRITE_SNAPSHOT_AFTER}" ]; then
    echo "FAILURE: WRITE database was mutated during READ rebuild!"
    exit 1
fi
echo "   ✓ Multi-table WRITE database snapshot strictly identical (zero mutations)"

# 8e. Discriminant HTTP Smoke Tests (AUD-L6-I2-12)
echo "8e. Testing HTTP availability and security..."
FINAL_HTTP=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/api/v3/me/binding || true)
if [ "${FINAL_HTTP}" = "000" ]; then
    echo "FAILURE: HTTP API is unreachable (status 000)!"
    exit 1
fi
if [ "${FINAL_HTTP}" = "503" ]; then
    echo "FAILURE: HTTP API still returning 503 after READY transition!"
    exit 1
fi
if [ "${FINAL_HTTP}" != "401" ] && [ "${FINAL_HTTP}" != "200" ]; then
    echo "FAILURE: Unexpected HTTP response code: ${FINAL_HTTP}"
    exit 1
fi
echo "   ✓ HTTP API restored to nominal production service (HTTP ${FINAL_HTTP})"

# 8f. Actuator Prometheus Metrics
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
echo " Pocoma V3 — Gate C: ALL CHECKS PASSED in ${ELAPSED}s (Ceiling: 180s)"
echo "================================================================================"
exit 0
