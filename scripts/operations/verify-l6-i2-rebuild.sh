#!/usr/bin/env bash
# ==============================================================================
# Pocoma V3 — L6/I2 Verification Gate C: Rebuild & Operational Journey Suite
#
# Validates the complete operational reconstruction and recovery lifecycle:
# 1. Preflight rejection on missing or invalid confirmation token.
# 2. Concurrency exclusion: CAS operator lock & PostgreSQL session advisory lock (AUD-L6-I2-14).
# 3. FAILED state safety & --reset-failed oracle enforcement (AUD-L6-I2-16).
# 4. HTTP 503 fail-closed barrier during MAINTENANCE.
# 5. Active WRITE permission denial proof (pocoma_read cannot write to pocoma_write).
# 6. Resume from CONVERGED refuses faux READY when oracle is KO (AUD-L6-I2-13).
# 7. Non-destructive crash recovery protocol (--resume).
# 8. Incomplete DDL rejection on --resume & recovery via --recover-destructive (AUD-L6-I2-15).
# 9. Full end-to-end fresh destructive rebuild (--confirm-destructive=RESET_READ_DATA).
# 10. Post-rebuild state, checkpoints (28/28), slots (0), and metrics verification.
# 11. Multi-table WRITE isolation verification.
# 12. Discriminant HTTP smoke test (rejecting 000 and 503; verifying 401 unauthenticated and 200 Actuator).
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

# 3. Test Concurrency Exclusion & Advisory Lock (AUD-L6-I2-14)
echo "3. Verifying exclusivity: CAS operator lock & PostgreSQL session advisory lock..."
docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -v ON_ERROR_STOP=1 -c \
    "UPDATE public.read_maintenance_state SET state = 'MAINTENANCE', phase = 'DRAINING', operator_id = 'foreign-operator', operation_id = '11111111-1111-1111-1111-111111111111'::uuid, updated_at = clock_timestamp();" > /dev/null

# 3a. Concurrent rebuild by another operator must be rejected by CAS
if python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --confirm-destructive=RESET_READ_DATA --operator-id=local-operator 2>/dev/null; then
    echo "FAILURE: Concurrent rebuild succeeded when foreign operator held the lock!"
    exit 1
fi
echo "   ✓ Concurrent rebuild correctly refused while foreign operator holds CAS lock"

# 3b. Test session advisory lock exclusion: process holding advisory lock blocks another rebuild/resume
python3 -c "
import subprocess, time
p = subprocess.Popen(
    ['docker', 'exec', '-i', '-e', 'PGPASSWORD=pocoma-local-only', 'pocoma-functional-postgres-1', 'psql', '-X', '-U', 'pocoma', '-d', 'pocoma_read', '-t', '-A'],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
)
p.stdin.write('SELECT pg_try_advisory_lock(3847291054321);\n')
p.stdin.flush()
assert p.stdout.readline().strip() == 't'
time.sleep(3)
p.stdin.write('SELECT pg_advisory_unlock(3847291054321);\n')
p.stdin.flush()
p.terminate()
" &
HOLDER_PID=$!
sleep 0.5

# While holder is running, another CLI call must be refused by advisory lock
if python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --resume 2>/dev/null; then
    echo "FAILURE: CLI succeeded when advisory lock was held by another process!"
    kill -9 ${HOLDER_PID} 2>/dev/null || true
    exit 1
fi
wait ${HOLDER_PID}
echo "   ✓ PostgreSQL session advisory lock strictly excludes concurrent CLI executions"

# 4. Test FAILED State Safety & --reset-failed Oracle Enforcement (AUD-L6-I2-16)
echo "4. Verifying FAILED state safety & --reset-failed oracle enforcement..."
# Set FAILED state with missing target prefix
docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -v ON_ERROR_STOP=1 -c \
    "UPDATE public.read_maintenance_state SET state = 'FAILED', target_prefix = NULL, error_detail = 'Simulated failure', updated_at = clock_timestamp();" > /dev/null

# 4a. --reset-failed MUST FAIL when target prefix is missing (oracle cannot validate)
if python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --reset-failed 2>/dev/null; then
    echo "FAILURE: --reset-failed succeeded without valid target prefix or oracle proof!"
    exit 1
fi
echo "   ✓ --reset-failed rejected when target prefix is missing"

# Set valid target prefix with all 5 families
TARGET_B='{"POT": 8, "IDENTITY": 2, "INVITATION": 0, "COMMAND_COMPLETION": 8, "IDENTITY_COMPLETION": 4}'
docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -v ON_ERROR_STOP=1 -c \
    "UPDATE public.read_maintenance_state SET state = 'FAILED', target_prefix = '${TARGET_B}'::jsonb, updated_at = clock_timestamp();" > /dev/null

# 4b. When schema and checkpoints are intact and oracle passes, --reset-failed restores READY safely
python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --reset-failed
RESET_STATE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT state FROM public.read_maintenance_state WHERE id = 1;")
if [ "${RESET_STATE}" != "READY" ]; then
    echo "FAILURE: Expected state READY after valid --reset-failed, got: ${RESET_STATE}"
    exit 1
fi
echo "   ✓ Explicit --reset-failed safely restored authority to READY after oracle validation"

# 5. Test HTTP 503 Fail-Closed Barrier
echo "5. Verifying HTTP 503 fail-closed barrier during MAINTENANCE..."
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

# 6. Test Active WRITE Permission Denial (AUD-L6-I2-04)
echo "6. Proving active PostgreSQL permission denial on WRITE database (role: pocoma_read)..."
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

# 7. Test Resume from CONVERGED refuses faux READY when oracle is KO (AUD-L6-I2-13)
echo "7. Verifying resume from CONVERGED strictly rejects faux READY when oracle is KO..."
# Set state to REBUILDING / CONVERGED
docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -v ON_ERROR_STOP=1 -c \
    "UPDATE public.read_maintenance_state SET state = 'REBUILDING', phase = 'CONVERGED', operator_id = 'test-op', operation_id = '33333333-3333-3333-3333-333333333333'::uuid, target_prefix = '{\"POT\": 999, \"IDENTITY\": 0, \"INVITATION\": 0, \"COMMAND_COMPLETION\": 0, \"IDENTITY_COMPLETION\": 0}'::jsonb, updated_at = clock_timestamp();" > /dev/null

# Oracle is KO because POT target is 999 while checkpoint is <= 8
if python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --resume --operator-id=test-op 2>/dev/null; then
    echo "FAILURE: --resume from CONVERGED succeeded when oracle was KO!"
    exit 1
fi
# Verify authority is still not READY and HTTP remains 503
CONV_HTTP=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/api/v3/me/binding || true)
if [ "${CONV_HTTP}" != "503" ]; then
    echo "FAILURE: HTTP returned ${CONV_HTTP} instead of 503 after refused resume!"
    exit 1
fi
echo "   ✓ --resume from CONVERGED correctly refused when oracle is unsatisfied (HTTP remains 503)"

# 8. Test Non-Destructive Crash Recovery Protocol (--resume)
echo "8. Verifying non-destructive crash recovery protocol (--resume) with valid oracle..."
# Set valid target prefix matching current checkpoints
docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -v ON_ERROR_STOP=1 -c \
    "UPDATE public.read_maintenance_state SET state = 'REBUILDING', phase = 'CONVERGED', target_prefix = '${TARGET_B}'::jsonb, updated_at = clock_timestamp();" > /dev/null

python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --resume --operator-id=test-op
RESUMED_STATE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT state FROM public.read_maintenance_state WHERE id = 1;")
if [ "${RESUMED_STATE}" != "READY" ]; then
    echo "FAILURE: Expected state READY after valid --resume, got: ${RESUMED_STATE}"
    exit 1
fi
echo "   ✓ Crash recovery via --resume succeeded non-destructively and converged to READY"

# 9. Test Incomplete DDL Rejection & Destructive Recovery (AUD-L6-I2-15)
echo "9. Verifying incomplete DDL handling: --resume refuses DROP, --recover-destructive repairs..."
# Simulate crash during DDL by dropping read_current_pot table and setting state to FAILED
docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -v ON_ERROR_STOP=1 -c \
    "DROP TABLE IF EXISTS v3_read.read_current_pot CASCADE;
     UPDATE public.read_maintenance_state SET state = 'FAILED', error_detail = 'Crash during DDL', updated_at = clock_timestamp();" > /dev/null

# 9a. Non-destructive --resume MUST refuse to proceed on incomplete schema
if python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --resume 2>/dev/null; then
    echo "FAILURE: Non-destructive --resume proceeded on incomplete schema!"
    exit 1
fi
echo "   ✓ Non-destructive --resume strictly refused on broken physical schema"

# 9b. --recover-destructive without confirmation MUST be rejected
if python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --recover-destructive 2>/dev/null; then
    echo "FAILURE: --recover-destructive without confirmation succeeded!"
    exit 1
fi
echo "   ✓ --recover-destructive without confirmation token rejected"

# 9c. --recover-destructive with confirmation successfully rebuilds from broken state
python3 "${REPO_ROOT}/scripts/operations/v3-rebuild-read.py" --recover-destructive --confirm-destructive=RESET_READ_DATA
REC_STATE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT state FROM public.read_maintenance_state WHERE id = 1;")
if [ "${REC_STATE}" != "READY" ]; then
    echo "FAILURE: Expected state READY after --recover-destructive, got: ${REC_STATE}"
    exit 1
fi
# Verify read_current_pot table was recreated
HAS_TABLE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT to_regclass('v3_read.read_current_pot')::text;")
if [ -z "${HAS_TABLE}" ]; then
    echo "FAILURE: read_current_pot table not recreated by --recover-destructive!"
    exit 1
fi
echo "   ✓ --recover-destructive successfully rebuilt incomplete schema and restored READY"

# 10. Test Full End-to-End Fresh Destructive Rebuild
echo "10. Executing full end-to-end fresh destructive rebuild..."
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

# 11. Post-Reconstruction Verification
echo "11. Verifying post-reconstruction invariants..."

# 11a. State and Phase in public.read_maintenance_state
FINAL_STATE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT state FROM public.read_maintenance_state WHERE id = 1;")
FINAL_PHASE=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT phase FROM public.read_maintenance_state WHERE id = 1;")
if [ "${FINAL_STATE}" != "READY" ] || [ "${FINAL_PHASE}" != "IDLE" ]; then
    echo "FAILURE: Expected state=READY, phase=IDLE. Got state=${FINAL_STATE}, phase=${FINAL_PHASE}"
    exit 1
fi
echo "   ✓ Maintenance authority in state=READY, phase=IDLE"

# 11b. Checkpoints count (must be exactly 28: 7 consumptions x 4 partitions)
CP_COUNT=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT count(*) FROM v3_read.discovery_checkpoint;")
if [ "${CP_COUNT}" -ne 28 ]; then
    echo "FAILURE: Expected 28 checkpoints in discovery_checkpoint, got: ${CP_COUNT}"
    exit 1
fi
echo "   ✓ All 28 consumption checkpoints present and verified (7 routes x 4 partitions)"

# 11c. Residual slots (must be 0 <= B)
SLOT_COUNT=$(docker exec -i -e PGPASSWORD=pocoma-local-only "${POSTGRES}" psql -U pocoma -d pocoma_read -tAc "SELECT count(*) FROM v3_read.consumption_slot;")
if [ "${SLOT_COUNT}" -ne 0 ]; then
    echo "FAILURE: Expected 0 residual slots in consumption_slot, got: ${SLOT_COUNT}"
    exit 1
fi
echo "   ✓ Zero residual slots remaining (consumption_slot == 0)"

# 11d. WRITE database untouched (multi-table isolation invariant)
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

# 11e. Discriminant HTTP Smoke Tests (AUD-L6-I2-12)
echo "11e. Testing HTTP availability and security..."
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

# 11f. Actuator Prometheus Metrics
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
